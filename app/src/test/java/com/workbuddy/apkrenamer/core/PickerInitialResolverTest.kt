package com.workbuddy.apkrenamer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PickerInitialResolver.resolve] 的纯 JVM 单测（§7.7.1 三级取位）。
 *
 * 原先这些分支只能由 Robolectric 的 `ApkListViewModelPickerTest` 覆盖；
 * 提炼成纯函数后，决策本身在这里以毫秒级全覆盖，Robolectric 测试退化为
 * 「VM 状态 → 输入投影 / 决策 → Uri 回投」的接线验证。
 */
class PickerInitialResolverTest {

    private fun resolve(
        current: String? = null,
        memories: List<Pair<String, String?>> = emptyList(),
        grants: List<String> = emptyList(),
    ) = PickerInitialResolver.resolve(
        currentDocId = current,
        rememberDocId = { volume -> memories.firstOrNull { it.first == volume }?.second },
        grantDocIds = grants,
    )

    private fun rememberedOf(volume: String) = PickerInitialResolver.Initial.Remembered(volume)

    /* ================================================================ */
    /* 分支 2：有当前目录                                                */
    /* ================================================================ */

    @Test
    fun `无当前目录无授权时返回 null`() {
        assertNull(resolve())
    }

    @Test
    fun `主存储当前目录返回 Current`() {
        assertEquals(
            PickerInitialResolver.Initial.Current,
            resolve(current = "primary/Download/APKs")
        )
    }

    @Test
    fun `主存储当前目录即使挂有记忆也返回 Current`() {
        // 主存储不参与 OTG 记忆，绝不能因为「primary 卷有记忆」而偏离当前目录
        assertEquals(
            PickerInitialResolver.Initial.Current,
            resolve(
                current = "primary/Download/APKs",
                memories = listOf("primary" to "primary/Download/APKs")
            )
        )
    }

    /* ================================================================ */
    /* 分支 1：可移动卷 + OTG 记忆                                       */
    /* ================================================================ */

    @Test
    fun `可移动卷记忆是更深子目录时返回 Remembered`() {
        assertEquals(
            rememberedOf("USB:1234-5678"),
            resolve(
                current = "USB:1234-5678", // 卷根
                memories = listOf("USB:1234-5678" to "USB:1234-5678/Download/APKs")
            )
        )
    }

    @Test
    fun `可移动卷记忆与当前相同仍返回 Remembered`() {
        assertEquals(
            rememberedOf("USB:1234-5678"),
            resolve(
                current = "USB:1234-5678/Download/APKs",
                memories = listOf("USB:1234-5678" to "USB:1234-5678/Download/APKs")
            )
        )
    }

    @Test
    fun `可移动卷记忆不在当前授权内时回落 Current`() {
        assertEquals(
            PickerInitialResolver.Initial.Current,
            resolve(
                current = "USB:1234-5678",
                memories = listOf("USB:1234-5678" to "USB:9999-0000/Other")
            )
        )
    }

    @Test
    fun `可移动卷无记忆时回落 Current`() {
        assertEquals(
            PickerInitialResolver.Initial.Current,
            resolve(current = "USB:1234-5678")
        )
    }

    @Test
    fun `卷 ID 大小写不同不算同一卷的记忆`() {
        // 记忆挂在小写卷下，当前目录是大写卷 → 视为不同卷，回落 Current
        assertEquals(
            PickerInitialResolver.Initial.Current,
            resolve(
                current = "USB:ABC/Download",
                memories = listOf("usb:abc" to "usb:abc/Download/APKs")
            )
        )
    }

    /* ================================================================ */
    /* 分支 3：无当前目录，靠持久化授权 + OTG 记忆兜底                   */
    /* ================================================================ */

    @Test
    fun `无当前目录时授权覆盖记忆返回 Remembered`() {
        assertEquals(
            rememberedOf("USB:1234-5678"),
            resolve(
                memories = listOf("USB:1234-5678" to "USB:1234-5678/Download/APKs"),
                grants = listOf("USB:1234-5678")
            )
        )
    }

    @Test
    fun `无当前目录有授权无记忆返回 null`() {
        assertNull(resolve(grants = listOf("USB:1234-5678")))
    }

    @Test
    fun `无当前目录有记忆无授权返回 null`() {
        assertNull(
            resolve(memories = listOf("USB:1234-5678" to "USB:1234-5678/Download/APKs"))
        )
    }

    @Test
    fun `无当前目录授权与记忆卷不匹配返回 null`() {
        assertNull(
            resolve(
                memories = listOf("USB:1234-5678" to "USB:1234-5678/Download/APKs"),
                grants = listOf("USB:9999-0000")
            )
        )
    }

    @Test
    fun `无当前目录主存储授权被跳过`() {
        // 只有主存储授权（不参与 OTG 记忆），即使卷上挂着记忆也不返回
        assertNull(
            resolve(
                memories = listOf("primary" to "primary/Download/APKs"),
                grants = listOf("primary/Download")
            )
        )
    }

    @Test
    fun `无当前目录多授权时取第一个命中的记忆`() {
        // 第一个授权没有记忆 → 跳过；第二个授权命中
        assertEquals(
            rememberedOf("USB:2222-3333"),
            resolve(
                memories = listOf("USB:2222-3333" to "USB:2222-3333/B"),
                grants = listOf("USB:1111-2222", "USB:2222-3333")
            )
        )
    }

    @Test
    fun `无当前目录授权前缀碰撞不误判`() {
        // 授权是 USB:1，记忆挂在 USB:12 下 → 不是 USB:1 的子目录，不能直达
        assertNull(
            resolve(
                memories = listOf("USB:12" to "USB:12/foo"),
                grants = listOf("USB:1")
            )
        )
    }
}
