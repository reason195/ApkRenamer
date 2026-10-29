package com.workbuddy.apkrenamer.cache

import android.content.Context
import android.util.LruCache
import com.workbuddy.apkrenamer.model.ApkItem
import com.workbuddy.apkrenamer.model.ApkMeta
import com.workbuddy.apkrenamer.model.ArchiveType
import com.workbuddy.apkrenamer.model.MetaSource
import com.workbuddy.apkrenamer.model.cacheSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条被缓存的元数据记录。
 */
data class CachedMeta(
    val key: String,
    val meta: ApkMeta,
    val savedAt: Long = System.currentTimeMillis()
)

/**
 * 双层缓存：`LruCache`（内存，热数据）+ `cacheDir/JSON`（磁盘，跨启动持久化）。
 *
 * ### Key 策略
 * `SHA-256(uri + "|" + lastModified + "|" + size)` 取前 32 位十六进制。
 * 只要文件被替换、编辑或移动，三元组必然变化 → 旧缓存自然失效，
 * 无需任何显式的失效逻辑，也不会出现「读到过期元数据」的问题。
 *
 * ### 读写路径
 * ```
 * get() ──► LruCache 命中 ─────────────► 返回（微秒级）
 *   └─ miss ─► diskIndex 命中 ─► 回填 LruCache ─► 返回（微秒级）
 *        └─ miss ─► null（交由 MetaExtractor 真解析）
 * ```
 * 真解析结果通过 [put] 同时写入两层，[flush] 时一次性落盘（避免每条 I/O）。
 *
 * 全类线程安全：`LruCache` 与 `ConcurrentHashMap` 自带同步，落盘用 `Mutex` 串行化。
 */
class ApkMetadataCache private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 内存缓存：最近解析的 200 个文件元数据 */
    private val memory = object : LruCache<String, CachedMeta>(MEMORY_ENTRIES) {
        override fun sizeOf(key: String, value: CachedMeta): Int = 1
    }

    /** 磁盘索引的内存镜像，warmUp 后常驻，落盘时整体序列化 */
    private val diskIndex = ConcurrentHashMap<String, CachedMeta>()

    private val diskFile: File
        get() = File(appContext.cacheDir, CACHE_FILE)

    private val ioMutex = Mutex()
    private val warmedUp = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)

    private val memoryHits = AtomicInteger()
    private val diskHits = AtomicInteger()
    private val misses = AtomicInteger()

    /* ---------------------------------------------------------------- */
    /* Key                                                              */
    /* ---------------------------------------------------------------- */

    /** 由 URI + 修改时间 + 文件大小生成稳定唯一键 */
    fun keyOf(item: ApkItem): String = hash(item.cacheSeed)

    fun keyOf(seed: String): String = hash(seed)

    private fun hash(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(KEY_LENGTH)
        for (i in 0 until KEY_LENGTH / 2) {
            sb.append(String.format(Locale.US, "%02x", digest[i]))
        }
        return sb.toString()
    }

    /* ---------------------------------------------------------------- */
    /* 读写                                                              */
    /* ---------------------------------------------------------------- */

    /**
     * 查缓存。命中时返回的 [ApkMeta.source] 已被改写为 MEMORY_CACHE / DISK_CACHE，
     * 方便 UI 区分「⚡ 秒开」与「真解析」。
     */
    fun get(key: String): ApkMeta? {
        memory.get(key)?.let {
            memoryHits.incrementAndGet()
            return it.meta.copy(source = MetaSource.MEMORY_CACHE)
        }
        diskIndex[key]?.let { cached ->
            diskHits.incrementAndGet()
            memory.put(key, cached)           // 回填内存层
            return cached.meta.copy(source = MetaSource.DISK_CACHE)
        }
        misses.incrementAndGet()
        return null
    }

    /** 写入两层缓存（磁盘层仅进内存镜像，等待 [flush] 批量落盘） */
    fun put(key: String, meta: ApkMeta) {
        // 解析失败的结果不缓存，避免一次偶发 I/O 错误被永久固化
        if (!meta.isValid) return
        val entry = CachedMeta(key, meta.copy(source = MetaSource.PACKAGE_MANAGER))
        memory.put(key, entry)
        diskIndex[key] = entry
        dirty.set(true)
    }

    /* ---------------------------------------------------------------- */
    /* 图标缓存（与元数据共用同一 Key，按 Key 另存 PNG 文件）            */
    /* ---------------------------------------------------------------- */

    /** 图标文件：`cacheDir/icons/<key>.png`。与元数据同 Key，失效时机完全一致。 */
    private val iconsDir: File
        get() = File(appContext.cacheDir, ICONS_DIR)

    /** 保存图标 PNG。超限丢弃；写入走临时文件 + rename，避免进程被杀留下半截文件。 */
    fun putIcon(key: String, bytes: ByteArray) {
        if (bytes.size > MAX_ICON_BYTES) return
        runCatching {
            iconsDir.mkdirs()
            val target = File(iconsDir, "$key.png")
            val tmp = File(iconsDir, "$key.png.tmp")
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** 图标文件存在时返回其路径，否则 null。 */
    fun iconFileOf(key: String): File? {
        val f = File(iconsDir, "$key.png")
        return if (f.isFile) f else null
    }

    /**
     * 重命名成功后把图标从旧 Key 迁到新 Key：文件内容没变，只是 Uri / 时间戳 / 大小
     * 组成的种子变了。不迁移的话，下次扫描命中新 Key 但找不到图标文件。
     */
    fun moveIcon(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        runCatching {
            val src = File(iconsDir, "$fromKey.png")
            if (!src.isFile) return
            iconsDir.mkdirs()
            val dst = File(iconsDir, "$toKey.png")
            if (dst.exists()) dst.delete()
            if (!src.renameTo(dst)) {
                dst.writeBytes(src.readBytes())
                src.delete()
            }
        }
    }

    /**
     * 删除不再被磁盘索引引用的图标 PNG。
     *
     * 图标与元数据同 Key：元数据条目被 LRU 淘汰后，对应的 `<key>.png` 就成了
     * 谁也命中不了的孤儿文件——不清理的话 `apk_icons` 目录的占用只增不减。
     * 只在 [flush]（已持锁、且仅在淘汰发生时才可能产生新孤儿）里调用。
     */
    private fun deleteOrphanIcons() {
        runCatching {
            val liveKeys = diskIndex.keys
            iconsDir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
                ?.forEach { f -> if (f.nameWithoutExtension !in liveKeys) f.delete() }
        }
    }

    /* ---------------------------------------------------------------- */
    /* 持久化                                                            */
    /* ---------------------------------------------------------------- */

    /** 应用启动或首次扫描前调用，把磁盘 JSON 读入内存镜像。幂等。 */
    suspend fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                runCatching {
                    val file = diskFile
                    if (!file.exists() || file.length() == 0L) return@runCatching
                    val root = JSONObject(file.readText(Charsets.UTF_8))
                    if (root.optInt(FIELD_VERSION, -1) != CACHE_VERSION) {
                        file.delete()   // 结构升级，直接丢弃旧缓存
                        // 索引已整体作废，图标与新条目无对应关系，一并重建
                        runCatching { iconsDir.deleteRecursively() }
                        return@runCatching
                    }
                    val array = root.optJSONArray(FIELD_ENTRIES) ?: return@runCatching
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val entry = obj.toCachedMeta() ?: continue
                        diskIndex[entry.key] = entry
                    }
                    // 最近使用的若干条预热进 LruCache
                    diskIndex.values
                        .sortedByDescending { it.savedAt }
                        .take(MEMORY_ENTRIES / 2)
                        .forEach { memory.put(it.key, it) }
                }.onFailure {
                    runCatching { diskFile.delete() }   // JSON 损坏则重建
                    // 索引不可信（此时 diskIndex 为空），图标无法反查归属，一并重建
                    runCatching { iconsDir.deleteRecursively() }
                }
            }
        }
    }

    /** 批量落盘。无变更时零 I/O。 */
    suspend fun flush() {
        if (!dirty.get()) return
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                if (!dirty.getAndSet(false)) return@withLock
                runCatching {
                    // LRU 淘汰：按写入时间保留最近 MAX_DISK_ENTRIES 条
                    val snapshot = diskIndex.values.sortedByDescending { it.savedAt }
                    if (snapshot.size > MAX_DISK_ENTRIES) {
                        snapshot.drop(MAX_DISK_ENTRIES).forEach { diskIndex.remove(it.key) }
                    }
                    // 被淘汰条目的图标 PNG 已成孤儿，一并删除（见 deleteOrphanIcons）
                    deleteOrphanIcons()
                    val array = JSONArray()
                    snapshot.take(MAX_DISK_ENTRIES).forEach { array.put(it.toJson()) }
                    val root = JSONObject()
                        .put(FIELD_VERSION, CACHE_VERSION)
                        .put(FIELD_UPDATED, System.currentTimeMillis())
                        .put(FIELD_ENTRIES, array)

                    // 原子写：先写临时文件再改名，避免进程被杀导致 JSON 截断
                    val tmp = File(appContext.cacheDir, "$CACHE_FILE.tmp")
                    tmp.writeText(root.toString(), Charsets.UTF_8)
                    if (diskFile.exists()) diskFile.delete()
                    if (!tmp.renameTo(diskFile)) {
                        tmp.copyTo(diskFile, overwrite = true)
                        tmp.delete()
                    }
                }.onFailure { dirty.set(true) }
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                memory.evictAll()
                diskIndex.clear()
                runCatching { diskFile.delete() }
                runCatching { iconsDir.deleteRecursively() }
                dirty.set(false)
                memoryHits.set(0); diskHits.set(0); misses.set(0)
            }
        }
    }

    /* ---------------------------------------------------------------- */
    /* 统计                                                              */
    /* ---------------------------------------------------------------- */

    data class Stats(
        val memoryEntries: Int,
        val diskEntries: Int,
        val memoryHits: Int,
        val diskHits: Int,
        val misses: Int
    ) {
        val total: Int get() = memoryHits + diskHits + misses
        val hitRate: Int get() = if (total == 0) 0 else (memoryHits + diskHits) * 100 / total
    }

    fun stats(): Stats = Stats(
        memoryEntries = memory.size(),
        diskEntries = diskIndex.size,
        memoryHits = memoryHits.get(),
        diskHits = diskHits.get(),
        misses = misses.get()
    )

    fun resetStats() {
        memoryHits.set(0); diskHits.set(0); misses.set(0)
    }

    /* ---------------------------------------------------------------- */
    /* 序列化                                                            */
    /* ---------------------------------------------------------------- */

    private fun CachedMeta.toJson(): JSONObject = JSONObject().apply {
        put("k", key)
        put("an", meta.appName)
        put("pn", meta.packageName)
        put("vn", meta.versionName)
        put("vc", meta.versionCode)
        put("min", meta.minSdk)
        put("tgt", meta.targetSdk)
        put("at", meta.archiveType.name)
        put("ic", meta.hasIcon)
        put("ts", savedAt)
    }

    private fun JSONObject.toCachedMeta(): CachedMeta? {
        val key = optString("k").takeIf { it.isNotEmpty() } ?: return null
        val pkg = optString("pn")
        if (pkg.isEmpty()) return null
        val meta = ApkMeta(
            appName = optString("an"),
            packageName = pkg,
            versionName = optString("vn"),
            versionCode = optLong("vc"),
            minSdk = optInt("min"),
            targetSdk = optInt("tgt"),
            archiveType = runCatching { ArchiveType.valueOf(optString("at")) }
                .getOrDefault(ArchiveType.UNKNOWN),
            source = MetaSource.DISK_CACHE,
            hasIcon = optBoolean("ic")
        )
        return CachedMeta(key, meta, optLong("ts"))
    }

    companion object {
        /** 内存缓存条目上限（需求指定 200） */
        const val MEMORY_ENTRIES = 200

        /** 磁盘缓存条目上限，超出按时间淘汰 */
        const val MAX_DISK_ENTRIES = 3000

        /** 单个图标 PNG 上限，超出丢弃（防畸形资源表） */
        private const val MAX_ICON_BYTES = 512 * 1024

        private const val CACHE_FILE = "apk_meta_cache.json"
        /**
         * v2：新增 hasIcon 字段，旧缓存（无图标标记）直接作废重建
         * v3：图标提取改为兼容 AdaptiveIconDrawable / VectorDrawable，
         *     v2 里这些应用被记成「无图标」，不重建就永远显示占位图
         */
        private const val CACHE_VERSION = 3
        private const val ICONS_DIR = "apk_icons"
        private const val KEY_LENGTH = 32

        private const val FIELD_VERSION = "v"
        private const val FIELD_UPDATED = "t"
        private const val FIELD_ENTRIES = "e"

        @Volatile
        private var instance: ApkMetadataCache? = null

        fun getInstance(context: Context): ApkMetadataCache =
            instance ?: synchronized(this) {
                instance ?: ApkMetadataCache(context).also { instance = it }
            }
    }
}
