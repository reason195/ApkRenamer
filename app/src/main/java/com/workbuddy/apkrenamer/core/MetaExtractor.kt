package com.workbuddy.apkrenamer.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import com.workbuddy.apkrenamer.cache.ApkMetadataCache
import com.workbuddy.apkrenamer.model.ApkItem
import com.workbuddy.apkrenamer.model.ApkMeta
import com.workbuddy.apkrenamer.model.ArchiveType
import com.workbuddy.apkrenamer.model.ItemState
import com.workbuddy.apkrenamer.model.MetaSource
import com.workbuddy.apkrenamer.model.archiveType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** 并发解析进度 */
data class ExtractProgress(
    val done: Int,
    val total: Int,
    val currentName: String,
    val fromCache: Boolean
) {
    val percent: Int get() = if (total == 0) 0 else done * 100 / total
}

/**
 * APK / APKM / APKS / XAPK 元数据并发提取器（单例）。
 *
 * ### 并发模型
 * ```
 * withContext(Dispatchers.IO) {
 *     items.map { async { semaphore.withPermit { parse(it) } } }.awaitAll()
 * }
 * ```
 * `Semaphore` 把并发度压在 CPU 核心数（2..8）内 —— 解压 base.apk 会产生数十 MB 的
 * 临时 I/O，无限并发反而抢占带宽并可能 OOM。进度通过 `Dispatchers.Main` 实时回调，
 * 主线程永不阻塞。
 *
 * ### 零依赖解析
 * - 单体 `.apk`：优先把 SAF 的 `ParcelFileDescriptor` 映射成 `/proc/self/fd/N`
 *   直接喂给 `PackageManager.getPackageArchiveInfo()` —— **零拷贝**，几十 MB 的包
 *   不落地即可读出四要素；个别 ROM 不支持时才回退到 cacheDir 临时复制。
 * - Bundle（`.apkm` / `.apks` / `.xapk`）：`ZipInputStream` 单遍流式扫描，
 *   内嵌的 `info.json` / `manifest.json` / `meta.sai_v2.json` 负责**元数据**（官方应用名等），
 *   `base.apk` 则**始终**按优先级抽出交给 PackageManager —— 只为拿到应用图标
 *   （图标存在 `resources.arsc` 资源表里，manifest 快速通道给不了）；
 *   base.apk 抽取或解析失败时回退到纯 manifest 元数据（无图标），不至于全盘失败。
 */
object MetaExtractor {

    private const val TEMP_DIR = "apk_extract"
    private const val BUFFER = 64 * 1024
    private const val MAX_JSON_BYTES = 1 shl 19  // 512 KB，防御畸形包
    private const val PROGRESS_INTERVAL_MS = 50L

    // base.apk 候选优先级
    private const val RANK_EXACT_BASE = 100
    private const val RANK_BASE_MASTER = 90
    private const val RANK_PLAIN = 50
    private const val RANK_FALLBACK = 10

    /** 单个图标 PNG 上限，超出丢弃（防畸形资源表） */
    private const val MAX_ICON_BYTES = 512 * 1024

    private val BUNDLE_JSON_NAMES = setOf(
        "info.json",          // APKMirror .apkm
        "manifest.json",      // APKPure .xapk
        "meta.sai_v1.json",   // SAI .apks
        "meta.sai_v2.json"
    )

    /** 默认并发度：核心数收敛到 [2, 8] */
    fun defaultConcurrency(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    /* ================================================================ */
    /* 对外主入口                                                        */
    /* ================================================================ */

    /**
     * 并发提取一批文件的元数据。
     *
     * @param onProgress 在主线程回调，可直接更新 UI
     * @return 与入参顺序一致、已填充 [ApkItem.meta] 的新列表
     */
    suspend fun extractAll(
        context: Context,
        items: List<ApkItem>,
        concurrency: Int = defaultConcurrency(),
        onProgress: suspend (ExtractProgress) -> Unit = {}
    ): List<ApkItem> {
        if (items.isEmpty()) return emptyList()

        val cache = ApkMetadataCache.getInstance(context)
        cache.warmUp()

        val gate = Semaphore(concurrency.coerceAtLeast(1))
        val counter = AtomicInteger()
        val lastEmit = AtomicLong(0L)
        val total = items.size

        val result = withContext(Dispatchers.IO) {
            coroutineScope {
                items.map { item ->
                    async {
                        gate.withPermit {
                            coroutineContext.ensureActive()
                            val resolved = resolveOne(context, cache, item)
                            val done = counter.incrementAndGet()

                            // 节流：命中缓存时一秒能跑完上千条，无需每条都切主线程
                            val now = System.currentTimeMillis()
                            if (done == total || now - lastEmit.get() >= PROGRESS_INTERVAL_MS) {
                                lastEmit.set(now)
                                withContext(Dispatchers.Main) {
                                    onProgress(
                                        ExtractProgress(
                                            done = done,
                                            total = total,
                                            currentName = item.displayName,
                                            fromCache = resolved.meta?.fromCache == true
                                        )
                                    )
                                }
                            }
                            resolved
                        }
                    }
                }.awaitAll()
            }
        }

        cache.flush()
        return result
    }

    /** 单文件解析（缓存优先），供预览刷新等场景单独调用 */
    suspend fun extractOne(context: Context, item: ApkItem): ApkItem =
        withContext(Dispatchers.IO) {
            val cache = ApkMetadataCache.getInstance(context)
            cache.warmUp()
            resolveOne(context, cache, item).also { cache.flush() }
        }

    private fun resolveOne(
        context: Context,
        cache: ApkMetadataCache,
        item: ApkItem
    ): ApkItem {
        val key = cache.keyOf(item)

        // ① 缓存命中 → 秒级返回，完全跳过解压/解析
        cache.get(key)?.let { cached ->
            return item.copy(meta = cached, state = ItemState.PARSED)
        }

        // ② 真解析：元数据 + 图标一起入库，Key 相同失效时机一致
        val parsed = parse(context, item)
        if (parsed.meta.isValid) {
            cache.put(key, parsed.meta)
            parsed.iconPng?.let { cache.putIcon(key, it) }
        }

        return item.copy(
            meta = parsed.meta,
            state = if (parsed.meta.isValid) ItemState.PARSED else ItemState.FAILED,
            detail = parsed.meta.error
        )
    }

    /* ================================================================ */
    /* 解析分派                                                          */
    /* ================================================================ */

    /** 解析产物：元数据 + 可选的图标 PNG（与元数据走同一缓存 Key） */
    private class Parsed(val meta: ApkMeta, val iconPng: ByteArray? = null)

    private fun parse(context: Context, item: ApkItem): Parsed {
        val type = item.archiveType
        return try {
            when {
                type == ArchiveType.APK -> parseSingleApk(context, item.uri, type)
                type.isBundle -> parseBundle(context, item.uri, type)
                else -> Parsed(ApkMeta(archiveType = type, error = "不支持的文件类型"))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Parsed(
                ApkMeta(
                    archiveType = type,
                    error = t.message?.take(120) ?: t.javaClass.simpleName
                )
            )
        }
    }

    /* ---------------- 单体 APK：零拷贝优先 ---------------- */

    private fun parseSingleApk(context: Context, uri: Uri, type: ArchiveType): Parsed {
        // 路线 A：/proc/self/fd/N —— 不复制任何字节
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fdPath = "/proc/self/fd/${pfd.fd}"
                readByPackageManager(context, fdPath, type)?.let { return it }
            }
        }

        // 路线 B：复制到 cacheDir 后解析（兼容不支持 fd 直读的 ROM / 网络 Provider）
        val tmp = newTempFile(context, "single")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, BUFFER) }
            } ?: return Parsed(ApkMeta(archiveType = type, error = "无法打开文件流"))

            return readByPackageManager(context, tmp.absolutePath, type)
                ?: Parsed(ApkMeta(archiveType = type, error = "PackageManager 无法解析（文件可能损坏）"))
        } finally {
            tmp.delete()
        }
    }

    /**
     * 用系统原生 `PackageManager` 读取归档信息。
     * 设置 `sourceDir` / `publicSourceDir` 后才能通过资源表拿到真正的应用名。
     *
     * 图标提取走 `getResourcesForApplication`：这是业内（APK-Info / AEE 等同类工具）
     * 对**未安装** APK 取图标的唯一框架手段，需要在 `sourceDir` 指向归档时加载其资源表。
     * 个别 ROM 上它会抛 `NameNotFoundException`，此时**静默放弃图标**，绝不影响元数据主流程。
     */
    private fun readByPackageManager(
        context: Context,
        archivePath: String,
        type: ArchiveType
    ): Parsed? {
        val pm: PackageManager = context.packageManager
        val info: PackageInfo = runCatching {
            pm.getPackageArchiveInfo(archivePath, 0)
        }.getOrNull() ?: return null

        // compileSdk 35 起 packageName 已标注为非空
        val pkg = info.packageName

        val appInfo = info.applicationInfo
        var label = ""
        var iconPng: ByteArray? = null
        if (appInfo != null) {
            appInfo.sourceDir = archivePath
            appInfo.publicSourceDir = archivePath
            label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrNull()
                .orEmpty()
                .trim()
            iconPng = extractIconPng(pm, appInfo)
        }
        // 资源缺失时用包名末段兜底，例如 com.tencent.mm → mm
        if (label.isEmpty() || label == pkg) {
            label = pkg.substringAfterLast('.').ifEmpty { pkg }
        }

        return Parsed(
            meta = ApkMeta(
                appName = label,
                packageName = pkg,
                versionName = info.versionName.orEmpty(),
                versionCode = info.longVersionCodeCompat(),
                minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) (appInfo?.minSdkVersion ?: 0) else 0,
                targetSdk = appInfo?.targetSdkVersion ?: 0,
                archiveType = type,
                source = MetaSource.PACKAGE_MANAGER,
                hasIcon = iconPng != null
            ),
            iconPng = iconPng
        )
    }

    /** 从归档的资源表里取应用图标并编码为 PNG；任何一步失败都返回 null（静默降级）。 */
    private fun extractIconPng(pm: PackageManager, appInfo: ApplicationInfo): ByteArray? =
        runCatching {
            if (appInfo.icon == 0) return@runCatching null
            val resources = pm.getResourcesForApplication(appInfo)
            @Suppress("DEPRECATION")
            val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                resources.getDrawable(appInfo.icon, null)
            } else {
                resources.getDrawable(appInfo.icon)
            }
            val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return@runCatching null
            if (out.size() > MAX_ICON_BYTES) return@runCatching null
            out.toByteArray()
        }.getOrNull()

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else versionCode.toLong()

    /* ---------------- Bundle：ZipInputStream 流式提取 ---------------- */

    private fun parseBundle(context: Context, uri: Uri, type: ArchiveType): Parsed {
        var manifestMeta: ApkMeta? = null
        var extracted: File? = null
        var extractedRank = -1

        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw, BUFFER)).use zip@{ zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val current: ZipEntry = entry
                        if (!current.isDirectory) {
                            val name = current.name
                            val simple = name.substringAfterLast('/').lowercase(Locale.ROOT)

                            when {
                                // 内嵌描述文件：先拿元数据（官方应用名）。
                                // 注意这里**不提前返回** —— 图标必须从 base.apk 的资源表里取
                                manifestMeta == null && simple in BUNDLE_JSON_NAMES -> {
                                    val bytes = zis.readEntryBytes(MAX_JSON_BYTES)
                                    manifestMeta = parseBundleJson(String(bytes, Charsets.UTF_8), type)
                                }

                                simple.endsWith(".apk") -> {
                                    val rank = rankOfApkEntry(name.lowercase(Locale.ROOT))
                                    if (rank > extractedRank) {
                                        val file = newTempFile(context, "bundle")
                                        // 单个条目损坏（CRC / 截断）时丢弃继续扫，别让整包解析失败
                                        val copied = runCatching {
                                            file.outputStream().use { out -> zis.copyTo(out, BUFFER) }
                                        }
                                        if (copied.isFailure) {
                                            file.delete()
                                        } else {
                                            extracted?.delete()
                                            extracted = file
                                            extractedRank = rank
                                            // 已经是最优候选，不必再往下扫
                                            if (rank >= RANK_EXACT_BASE) return@zip
                                        }
                                    }
                                }
                            }
                        }
                        runCatching { zis.closeEntry() }
                        entry = runCatching { zis.nextEntry }.getOrNull()
                    }
                }
            } ?: return Parsed(ApkMeta(archiveType = type, error = "无法打开文件流"))

            // base.apk 解析失败（损坏 / ROM 不支持）时，manifest 元数据兜底 ——
            // 快速通道的价值就在这里：宁可没图标，也不能连名字都读不出来
            extracted?.let { file ->
                val pmParsed = runCatching {
                    readByPackageManager(context, file.absolutePath, type)
                }.getOrNull()
                if (pmParsed != null) {
                    return Parsed(
                        meta = mergeMeta(pmParsed.meta, manifestMeta),
                        iconPng = pmParsed.iconPng
                    )
                }
            }

            return Parsed(
                manifestMeta
                    ?: ApkMeta(archiveType = type, error = "压缩包内未找到 base.apk")
            )
        } finally {
            extracted?.delete()
        }
    }

    /** base.apk 候选优先级：base.apk > base-master.apk > 非 split 包 > 其它 */
    private fun rankOfApkEntry(lowerPath: String): Int {
        val simple = lowerPath.substringAfterLast('/')
        return when {
            simple == "base.apk" -> RANK_EXACT_BASE
            simple.startsWith("base-master") -> RANK_BASE_MASTER
            simple.contains("split") || simple.startsWith("config.") ||
                simple.contains("-config.") || simple.contains(".config.") -> RANK_FALLBACK
            else -> RANK_PLAIN
        }
    }

    /**
     * 解析 APKMirror `info.json` / APKPure `manifest.json` / SAI `meta.sai_v*.json`。
     * 三家字段名不同，这里做统一兼容取值。
     */
    private fun parseBundleJson(text: String, type: ArchiveType): ApkMeta? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null

        val pkg = json.firstString("pname", "package_name", "package", "packageName")
        if (pkg.isEmpty()) return null

        val name = json.firstString("app_name", "name", "label", "release_title")
        val versionName = json.firstString("release_version", "version_name", "versionName")
        val versionCode = json.firstString("versioncode", "version_code", "versionCode")
            .toLongOrNull() ?: 0L

        return ApkMeta(
            appName = name.ifEmpty { pkg.substringAfterLast('.') },
            packageName = pkg,
            versionName = versionName,
            versionCode = versionCode,
            archiveType = type,
            source = MetaSource.BUNDLE_MANIFEST
        )
    }

    /** PackageManager 结果为主，用内嵌 JSON 补齐空字段（JSON 里的应用名往往更规范） */
    private fun mergeMeta(primary: ApkMeta, fallback: ApkMeta?): ApkMeta {
        if (fallback == null) return primary
        return primary.copy(
            appName = fallback.appName.ifBlank { primary.appName },
            versionName = primary.versionName.ifBlank { fallback.versionName },
            versionCode = if (primary.versionCode > 0) primary.versionCode else fallback.versionCode
        )
    }

    /* ================================================================ */
    /* 临时文件                                                          */
    /* ================================================================ */

    private fun tempDir(context: Context): File =
        File(context.cacheDir, TEMP_DIR).apply { if (!exists()) mkdirs() }

    private fun newTempFile(context: Context, tag: String): File =
        File(tempDir(context), "${tag}_${System.nanoTime()}.apk")

    /** 应用启动时调用，清理上次异常退出遗留的临时包 */
    suspend fun clearTempDir(context: Context) = withContext(Dispatchers.IO) {
        runCatching { tempDir(context).listFiles()?.forEach { it.delete() } }
        Unit
    }

    private fun ZipInputStream.readEntryBytes(limit: Int): ByteArray {
        val out = ByteArrayOutputStream(16 * 1024)
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > limit) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun JSONObject.firstString(vararg keys: String): String {
        for (key in keys) {
            val v = optString(key, "").trim()
            if (v.isNotEmpty() && v != "null") return v
        }
        return ""
    }
}
