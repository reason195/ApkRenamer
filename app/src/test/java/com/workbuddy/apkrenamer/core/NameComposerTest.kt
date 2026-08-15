package com.workbuddy.apkrenamer.core

import com.workbuddy.apkrenamer.RobolectricTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NameComposer] 的清洗与冲突消解逻辑是纯 Kotlin 实现，
 * 不触碰任何 Android API；测试跑在 Robolectric 下只是为了构造
 * `ApkItem` 所需的真实 `android.net.Uri`（见 README 测试分层，
 * runner 与 SDK 统一在 [RobolectricTestBase]）。
 */
class NameComposerTest : RobolectricTestBase() {

    private val rule = NameComposer.Rule()

    @Test
    fun `非法字符被剔除`() {
        val dirty = "My:App*Name?<v1/2>|\"x\"\\y"
        val clean = NameComposer.sanitizeSegment(dirty, rule)
        NameComposer.ILLEGAL_CHARS.forEach { ch ->
            assertTrue("残留非法字符 $ch", !clean.contains(ch))
        }
        assertEquals("MyAppNamev12xy", clean)
    }

    @Test
    fun `控制字符与多余空白被折叠`() {
        val clean = NameComposer.sanitizeSegment("  Hello\u0000\u0007   World  ", rule)
        assertEquals("Hello World", clean)
    }

    @Test
    fun `冲突时按序号递增`() {
        val guard = NameComposer.ConflictGuard(listOf("App_v1.0.apk"), "_")
        assertEquals("App_v1.0_1.apk", guard.claim("App_v1.0.apk"))
        assertEquals("App_v1.0_2.apk", guard.claim("App_v1.0.apk"))
        assertEquals("Other.apk", guard.claim("Other.apk"))
    }

    @Test
    fun `冲突检测大小写不敏感`() {
        val guard = NameComposer.ConflictGuard(listOf("app.apk"), "_")
        assertEquals("APP_1.apk", guard.claim("APP.apk"))
    }

    @Test
    fun `自身让位后不产生多余序号`() {
        val guard = NameComposer.ConflictGuard(listOf("App_v1.0.apk"), "_")
        guard.release("App_v1.0.apk")
        assertEquals("App_v1.0.apk", guard.claim("App_v1.0.apk"))
    }

    @Test
    fun `按字节截断且不切断中文`() {
        val long = "中".repeat(200)   // 600 字节
        val result = NameComposer.truncateKeepingExtension(long, "apk", 60)
        assertTrue(result.endsWith(".apk"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 60)
        // 未产生乱码替换字符，说明没有从多字节字符中间截断
        assertTrue(!result.contains('\uFFFD'))
    }
}
