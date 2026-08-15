package com.workbuddy.apkrenamer.model

import android.net.Uri
import java.util.Locale

/**
 * 归档包类型。
 *
 * - [APK]  普通单体安装包
 * - [APKM] APKMirror Bundle（内含 info.json + base.apk + split 配置包）
 * - [APKS] bundletool / SAI 导出的 Split APKs 集合（内含 base-master.apk + toc.pb）
 * - [XAPK] APKPure Bundle（内含 manifest.json + <package>.apk + obb）
 */
enum class ArchiveType(val ext: String, val label: String) {
    APK("apk", "APK"),
    APKM("apkm", "APKM Bundle"),
    APKS("apks", "Split APKs"),
    XAPK("xapk", "XAPK Bundle"),
    UNKNOWN("", "未知");

    /** 是否为需要解压内部 base.apk 的复合包 */
    val isBundle: Boolean get() = this == APKM || this == APKS || this == XAPK

    companion object {
        private val BY_EXT = values().associateBy { it.ext }

        fun fromFileName(fileName: String): ArchiveType {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return BY_EXT[ext] ?: UNKNOWN
        }

        /** 扫描时用于筛选目标文件 */
        val SCANNABLE: Set<String> = setOf(APK.ext, APKM.ext, APKS.ext, XAPK.ext)
    }
}

/** 元数据来源，用于 UI 上区分「秒开缓存」与「真实解析」 */
enum class MetaSource {
    NONE,
    MEMORY_CACHE,   // LruCache 命中
    DISK_CACHE,     // cacheDir JSON 命中
    PACKAGE_MANAGER,// PackageManager.getPackageArchiveInfo 解析
    BUNDLE_MANIFEST // .apkm/.xapk 内嵌 info.json / manifest.json 快速通道
}

/**
 * APK 描述文件信息。不可变数据类，可安全跨协程共享。
 */
data class ApkMeta(
    val appName: String = "",
    val packageName: String = "",
    val versionName: String = "",
    val versionCode: Long = 0L,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val archiveType: ArchiveType = ArchiveType.UNKNOWN,
    val source: MetaSource = MetaSource.NONE,
    val error: String? = null,
    /** 是否已提取并缓存应用图标（图标本体是 PNG 文件，按同一缓存 Key 另存） */
    val hasIcon: Boolean = false
) {
    /** 解析成功且关键字段齐备 */
    val isValid: Boolean
        get() = error == null && packageName.isNotBlank()


    /** 是否来自缓存（用于 UI 打「⚡」标记） */
    val fromCache: Boolean
        get() = source == MetaSource.MEMORY_CACHE || source == MetaSource.DISK_CACHE

    fun summary(): String = when {
        error != null -> "解析失败：$error"
        else -> "$packageName · v$versionName ($versionCode)"
    }
}

/** 单个文件在流水线中的处理状态 */
enum class ItemState {
    PENDING,   // 已扫描，待解析
    PARSED,    // 元数据就绪
    SKIPPED,   // 新旧同名，无需操作
    RENAMED,   // 重命名成功
    FAILED     // 解析或重命名失败
}

/**
 * 扫描到的一条 APK 文件记录。
 *
 * @param uri         SAF 文档 Uri，重命名与读流都基于它
 * @param documentId  DocumentsContract 文档 ID
 * @param displayName 当前文件名（含扩展名）
 * @param size        字节大小，参与缓存 Key 计算
 * @param lastModified 最后修改时间戳，参与缓存 Key 计算
 * @param relativePath 相对授权根目录的路径，仅用于 UI 展示
 */
data class ApkItem(
    val uri: Uri,
    val documentId: String,
    val displayName: String,
    val size: Long,
    val lastModified: Long,
    val relativePath: String = "",
    val meta: ApkMeta? = null,
    val targetName: String? = null,
    val state: ItemState = ItemState.PENDING,
    val detail: String? = null
)

/* ============================ 扩展函数区 ============================ */

/** 小写扩展名，缺失时回退为 apk */
val ApkItem.extension: String
    get() = displayName.substringAfterLast('.', "apk").lowercase(Locale.ROOT)

/** 去掉扩展名的主干 */
val ApkItem.baseName: String
    get() = displayName.substringBeforeLast('.')

val ApkItem.archiveType: ArchiveType
    get() = ArchiveType.fromFileName(displayName)

/**
 * 缓存 Key 的原始种子：URI + 最后修改时间 + 文件大小。
 * 三者任一变化即视为文件内容变动，缓存自动失效。
 */
val ApkItem.cacheSeed: String
    get() = "${uri}|$lastModified|$size"

/** 是否已经拿到目标名且与原名不同 */
val ApkItem.needsRename: Boolean
    get() = !targetName.isNullOrBlank() && targetName != displayName

fun ApkItem.readableSize(): String {
    if (size <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = size.toDouble()
    var i = 0
    while (value >= 1024.0 && i < units.lastIndex) {
        value /= 1024.0
        i++
    }
    return if (i == 0) "${size} B" else String.format(Locale.US, "%.1f %s", value, units[i])
}

/**
 * 紧凑尺寸（去掉空格），适合直接拼进文件名：`12.3MB`。
 * 展示用 [readableSize]（`12.3 MB`），拼文件名用本函数。
 */
fun ApkItem.sizeLabel(): String = readableSize().replace(" ", "")
