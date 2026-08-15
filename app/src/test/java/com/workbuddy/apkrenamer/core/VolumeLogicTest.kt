package com.workbuddy.apkrenamer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VolumeLogic] 的纯 JVM 单测。
 *
 * 输入约定是**解码后**的 docId（`SafScanner.treeDocumentIdOf` 的输出），
 * 本测试不依赖任何 Android 运行时。这里刻意锁定的细边界：
 * - `/` 硬边界防前缀碰撞（`USB:1` ≠ `USB:12/foo` 的祖先）；
 * - 字面 `%2F` 是文件夹名内容而非层级分隔符；
 * - 卷 ID 区分大小写，不做归一化。
 */
class VolumeLogicTest {

    /* ================================================================ */
    /* volumeOf                                                         */
    /* ================================================================ */

    @Test
    fun `volumeOf 取第一个斜杠之前的部分`() {
        assertEquals("primary", VolumeLogic.volumeOf("primary/Download/APKs"))
        assertEquals("USB:1234-5678", VolumeLogic.volumeOf("USB:1234-5678/Download/APKs"))
        assertEquals("primary", VolumeLogic.volumeOf("primary"))
        assertEquals("USB:1234-5678", VolumeLogic.volumeOf("USB:1234-5678"))
    }

    /* ================================================================ */
    /* isRemovableVolumeId                                              */
    /* ================================================================ */

    @Test
    fun `isRemovableVolumeId 只排除主存储`() {
        assertFalse(VolumeLogic.isRemovableVolumeId(VolumeLogic.VOLUME_PRIMARY))
        assertTrue(VolumeLogic.isRemovableVolumeId("USB:1234-5678"))
        assertTrue(VolumeLogic.isRemovableVolumeId("sdcard:ABC-123"))
        assertTrue(VolumeLogic.isRemovableVolumeId("usb:1234-5678"))
    }

    @Test
    fun `卷 ID 比较区分大小写_视为不同卷`() {
        // 契约：大小写不同即不同卷（不归一化，避免把真实不同的卷误合并）
        assertNotEquals(VolumeLogic.volumeOf("USB:ABC/x"), VolumeLogic.volumeOf("usb:abc/x"))
    }

    /* ================================================================ */
    /* isDescendantOf                                                   */
    /* ================================================================ */

    @Test
    fun `isDescendantOf 含相等与真子级`() {
        assertTrue(VolumeLogic.isDescendantOf("USB:1/Download/APKs", "USB:1"))
        assertTrue(VolumeLogic.isDescendantOf("USB:1/Download/APKs", "USB:1/Download"))
        assertTrue(VolumeLogic.isDescendantOf("USB:1/Download", "USB:1/Download"))
        assertTrue(VolumeLogic.isDescendantOf("USB:1", "USB:1"))
    }

    @Test
    fun `isDescendantOf 前缀碰撞被斜杠边界挡住`() {
        // USB:1 不能是 USB:12 或 USB:1EVIL 的祖先
        assertFalse(VolumeLogic.isDescendantOf("USB:12/foo", "USB:1"))
        assertFalse(VolumeLogic.isDescendantOf("USB:1EVIL/x", "USB:1"))
    }

    @Test
    fun `isDescendantOf 字面百分号斜杠按文件夹名内容处理`() {
        // 解码后 docId 里的 %2F 是文件夹名字面内容（如「Download%2FAPKs」），不是层级分隔符
        assertTrue(VolumeLogic.isDescendantOf("USB:1/Download%2FAPKs", "USB:1"))
        assertTrue(VolumeLogic.isDescendantOf("USB:1/Download%2FAPKs", "USB:1/Download%2FAPKs"))
        // 它也不是「Download」的子目录
        assertFalse(VolumeLogic.isDescendantOf("USB:1/Download%2FAPKs", "USB:1/Download"))
    }

    @Test
    fun `isDescendantOf 无关卷与反向不成立`() {
        assertFalse(VolumeLogic.isDescendantOf("USB:2/x", "USB:1"))
        // parent 比 child 深：child 不是 parent 的子目录
        assertFalse(VolumeLogic.isDescendantOf("USB:1", "USB:1/Download"))
        // 根目录互不包含
        assertFalse(VolumeLogic.isDescendantOf("USB:1", "USB:2"))
    }

    /* ================================================================ */
    /* relativePathOf                                                   */
    /* ================================================================ */

    @Test
    fun `relativePathOf 卷根返回斜杠`() {
        assertEquals("/", VolumeLogic.relativePathOf("primary"))
        assertEquals("/", VolumeLogic.relativePathOf("USB:1"))
        assertEquals("Download/APKs", VolumeLogic.relativePathOf("USB:1/Download/APKs"))
    }
}
