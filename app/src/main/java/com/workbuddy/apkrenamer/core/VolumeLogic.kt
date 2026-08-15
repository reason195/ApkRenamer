package com.workbuddy.apkrenamer.core

/**
 * 可移动卷（OTG / SD）与树 Uri 的 **docId 层面**纯字符串运算。
 *
 * ### 输入约定
 * 这里的 `docId` 一律是**解码后**的树 document id——即
 * `DocumentsContract.getTreeDocumentId` 的返回值，形如
 * `USB:1234-5678/Download/APKs` 或 `primary`。本对象不做任何 Uri 解析/编码，
 * 「Uri → docId」的 Android 边界统一留在 [SafScanner]（`SafScanner.treeDocumentIdOf`），
 * 因此这里的逻辑零 Android 依赖，断言不依赖任何框架能力（测试跑在 Robolectric 沙箱下）。
 *
 * ### 编码歧义约定（为什么在这里放心做字符串比较）
 * 树 Uri 路径中的 document id 是**整体百分号编码成单个路径段**的，`getTreeDocumentId`
 * 返回解码后的一段。文件夹名里若真的含 `%2F` 字样，它在 Uri 里会是 `%252F`，解码回来
 * 仍是字面 `%2F`。因此在 docId 字符串里：`/` 一定是层级分隔符、字面 `%2F` 一定是文件夹名
 * 内容，前缀比较不会产生歧义（`VolumeLogicTest` 锁定该约定）。
 */
object VolumeLogic {

    /** 主存储卷的 SAF 卷 ID（非可移动卷，不参与 OTG 记忆） */
    const val VOLUME_PRIMARY = "primary"

    /**
     * docId 的卷 ID：第一个 `/` 之前的部分。
     * ExternalStorageProvider 的卷 ID 形如 `USB:1234-5678` 或 `primary`；
     * 同一 U 盘重插后卷 ID 保持不变，是 OTG 记忆的键。
     */
    fun volumeOf(docId: String): String = docId.substringBefore('/')

    /**
     * 是否可移动卷（主存储不参与「记住上次文件夹」）。
     *
     * 卷 ID 是不透明的 provider 令牌，比较**区分大小写**：`USB:ABC` 与 `usb:abc`
     * 视为两个不同的卷，不做归一化（避免把真实不同的卷误合并）。
     */
    fun isRemovableVolumeId(volumeId: String): Boolean = volumeId != VOLUME_PRIMARY

    /**
     * [child] 是否位于 [parent] 的子树内（含相等）。
     *
     * 用 `/` 作硬边界防止前缀碰撞：`USB:1` 不是 `USB:12/foo` 的祖先
     * （`startsWith("$parent/")` 要求边界斜杠紧跟其后）。
     */
    fun isDescendantOf(child: String, parent: String): Boolean =
        child == parent || child.startsWith("$parent/")

    /** docId 在卷内的相对路径；卷根返回 `/`。 */
    fun relativePathOf(docId: String): String =
        if ('/' in docId) docId.substringAfter('/') else "/"
}
