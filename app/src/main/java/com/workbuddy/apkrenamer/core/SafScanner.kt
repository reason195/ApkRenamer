package com.workbuddy.apkrenamer.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.workbuddy.apkrenamer.model.ApkItem
import com.workbuddy.apkrenamer.model.ArchiveType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * 基于 Storage Access Framework 的目录扫描与重命名。
 *
 * 直接走 [DocumentsContract] 的批量 `query`，而不是 `DocumentFile.listFiles()` ——
 * 后者每个文件都要发起一次 IPC，几百个文件时会慢一个数量级。
 * 这里一次 Cursor 拿到 documentId / 名称 / 大小 / 修改时间，扫描上千文件通常在百毫秒级。
 *
 * 全程零存储权限：目录由用户经 `ACTION_OPEN_DOCUMENT_TREE` 授权，
 * 通过 `takePersistableUriPermission` 长期保留。
 */
object SafScanner {

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    /**
     * 扫描授权目录下的所有 APK/APKM/APKS/XAPK。
     *
     * @param recursive 是否深入子目录
     * @param onFound   每发现若干文件回调一次（主线程），用于「正在扫描 128 个…」
     */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        recursive: Boolean = true,
        onFound: suspend (Int) -> Unit = {}
    ): List<ApkItem> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val results = ArrayList<ApkItem>()

        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        // 广度优先：(documentId, 相对路径)
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootId to "")

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (parentId, parentPath) = queue.poll() ?: break
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)

            val cursor: Cursor? = runCatching {
                resolver.query(childrenUri, PROJECTION, null, null, null)
            }.getOrNull()

            cursor?.use { c ->
                val idxId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val idxName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val idxSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val idxTime = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idxId < 0 || idxName < 0) return@use

                while (c.moveToNext()) {
                    coroutineContext.ensureActive()
                    val docId = c.getString(idxId) ?: continue
                    val name = c.getString(idxName) ?: continue
                    val mime = if (idxMime >= 0) c.getString(idxMime) else null

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (recursive) {
                            val childPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                            queue.add(docId to childPath)
                        }
                        continue
                    }

                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in ArchiveType.SCANNABLE) continue

                    results += ApkItem(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        documentId = docId,
                        displayName = name,
                        size = if (idxSize >= 0 && !c.isNull(idxSize)) c.getLong(idxSize) else 0L,
                        lastModified = if (idxTime >= 0 && !c.isNull(idxTime)) c.getLong(idxTime) else 0L,
                        relativePath = parentPath
                    )
                }
            }

            if (results.isNotEmpty()) {
                withContext(Dispatchers.Main) { onFound(results.size) }
            }
        }

        results.sortedWith(compareBy({ it.relativePath }, { it.displayName.lowercase(Locale.ROOT) }))
    }

    /**
     * 执行重命名。
     * 部分 Provider 成功后返回 null（而非新 Uri），此处按成功处理并回退用原 Uri。
     */
    suspend fun rename(context: Context, item: ApkItem, newName: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val newUri = DocumentsContract.renameDocument(
                    context.contentResolver,
                    item.uri,
                    newName
                )
                newUri ?: item.uri
            }
        }

    /* ---------------------------------------------------------------- */
    /* 卷与授权范围（OTG 记忆 / 恢复判断）                                */
    /* ---------------------------------------------------------------- */

    /**
     * 树 Uri 的 document id（**解码后**，形如 `USB:1234-5678/Download/APKs` 或 `primary`）。
     * 这是「Uri → docId」的 Android 边界，统一收敛在这里；docId 层面的卷运算见
     * [VolumeLogic]（纯字符串，可 JVM 单测）。
     */
    fun treeDocumentIdOf(treeUri: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(treeUri)
    }.getOrNull()

    /** 树 Uri 所属卷的 ID（见 [VolumeLogic.volumeOf]）；无法解析返回 null。 */
    fun volumeIdOf(treeUri: Uri): String? =
        treeDocumentIdOf(treeUri)?.let { VolumeLogic.volumeOf(it) }

    /** 是否可移动卷（OTG U 盘 / SD 卡）。主存储（primary）不参与「记住上次文件夹」。 */
    fun isRemovableVolume(treeUri: Uri): Boolean {
        val volume = volumeIdOf(treeUri) ?: return false
        return VolumeLogic.isRemovableVolumeId(volume)
    }

    /**
     * [treeUri] 是否位于 [ancestorTreeUri] 的授权子树内（含相等）。
     * 两者必须来自同一 Provider（authority 相同）；docId 层面的比较见
     * [VolumeLogic.isDescendantOf]（含 `/` 边界，防前缀碰撞）。
     */
    fun isDescendantOf(treeUri: Uri, ancestorTreeUri: Uri): Boolean {
        if (treeUri.authority != ancestorTreeUri.authority) return false
        val child = treeDocumentIdOf(treeUri) ?: return false
        val parent = treeDocumentIdOf(ancestorTreeUri) ?: return false
        return VolumeLogic.isDescendantOf(child, parent)
    }

    /**
     * 该树 Uri 是否被某条**持久化**的（可读 + 可写）树授权覆盖。
     *
     * 用「覆盖」而非精确相等：记忆的 OTG 子目录由父级树授权天然覆盖，
     * 重启后无需对子目录单独授权即可访问。
     */
    fun isCoveredByPersistedGrant(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { perm ->
            perm.isReadPermission && perm.isWritePermission &&
                isDescendantOf(treeUri, perm.uri)
        }

    /** 树 Uri 在卷内的相对路径（供「恢复上次文件夹」对话框文案）；根目录返回 `/`。 */
    fun displayPathOf(treeUri: Uri): String =
        treeDocumentIdOf(treeUri)?.let { VolumeLogic.relativePathOf(it) } ?: ""

    /**
     * 长期保留目录的读写授权。
     *
     * 之前这段代码在 `MainActivity` 里；T7 引入 TV 端后成了两处使用方，
     * 上提到这里保证「拿到 Uri 之后做什么」只有一份定义。
     *
     * 失败**不抛异常**：个别厂商 ROM 在授权数量到达上限（默认 128 个）时会抛
     * `SecurityException`，此时目录在本次会话内仍然可用，只是重启后需要重新授权，
     * 直接崩掉得不偿失。
     *
     * @return 是否成功持久化
     */
    fun persistPermission(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }.isSuccess

    /**
     * **尽力而为**地预检设备是否实现了 `ACTION_OPEN_DOCUMENT_TREE`（§7.7 / RQ-T7-8）。
     *
     * 部分 Android TV 设备（尤其是精简版 ROM）没有预装 DocumentsUI，
     * 直接 `launch()` 会抛 `ActivityNotFoundException`。
     *
     * ### 为什么 API 30+ 一律返回 true
     * Android 11 起的包可见性限制会让 `resolveActivity()` 在没有 `<queries>` 声明时
     * 误报 `null`。而我们明确**不加** `<queries>`（R1：Manifest 权限区保持为空），
     * 因此在 API 30+ 上这个查询根本不可信 —— 与其误报「不可用」把按钮永久置灰，
     * 不如乐观返回 true，让真正的兜底 —— 调用方的 `runCatching { launch() }` —— 去处理。
     *
     * 调用方**不得**把本方法的结果当作唯一判据。
     */
    fun isTreePickerAvailable(context: Context): Boolean {
        val resolved = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                PackageManager.MATCH_DEFAULT_ONLY
            )
        }.getOrNull()

        if (resolved != null) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}
