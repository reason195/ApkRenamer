package com.workbuddy.apkrenamer.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import com.workbuddy.apkrenamer.RobolectricTestBase
import org.junit.Test
import java.util.Locale

/**
 * 手动改名（设计文档 §3.4）的单元测试。
 *
 * 覆盖面：
 *  1. [NameError] 五类错误各 ≥ 1 例；
 *  2. 校验顺序 EMPTY → ILLEGAL_ONLY → RESERVED → TOO_LONG → CONFLICT；
 *  3. 「与自身同名不算冲突」；
 *  4. 冲突**不**自动追加 `_1`；
 *  5. 中文名按 UTF-8 字节截断且不切碎多字节字符；
 *  6. `Locale.ROOT` 大小写比较（土耳其语 I 问题）。
 *
 * 业务逻辑仍是纯 Kotlin（[NameComposer] 零 `android.*` import，R5）；测试跑在
 * Robolectric 下只是为了构造 `ApkItem` 所需的真实 `android.net.Uri`（见 README 测试分层，
 * runner 与 SDK 统一在 [RobolectricTestBase]）。
 */
class NameComposerManualNameTest : RobolectricTestBase() {

    private val rule = NameComposer.Rule()

    /** 保存系统默认 Locale，土耳其语用例跑完后必须还原，否则会污染同一 JVM 内的其它测试 */
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    /* ================================================================== */
    /* sanitizeManualName                                                  */
    /* ================================================================== */

    @Test
    fun `sanitizeManualName 剔除全部非法字符`() {
        val result = NameComposer.sanitizeManualName("""a\b/c:d*e?f"g<h>i|j.apk""", "apk", rule)

        for (illegal in NameComposer.ILLEGAL_CHARS) {
            assertFalse("清洗后仍残留非法字符 '$illegal'：$result", result.contains(illegal))
        }
        assertEquals("abcdefghij.apk", result)
    }

    @Test
    fun `sanitizeManualName 补齐缺失的扩展名`() {
        assertEquals("MyApp.apk", NameComposer.sanitizeManualName("MyApp", "apk", rule))
    }

    @Test
    fun `sanitizeManualName 按规则把扩展名规范为小写`() {
        assertEquals("MyApp.apk", NameComposer.sanitizeManualName("MyApp.APK", "apk", rule))
    }

    @Test
    fun `sanitizeManualName 保留扩展名大小写当规则关闭时`() {
        val keepCase = rule.copy(lowercaseExtension = false)
        assertEquals("MyApp.APK", NameComposer.sanitizeManualName("MyApp.APK", "apk", keepCase))
    }

    /**
     * 与 `compose()` 的关键差异：手动输入的连续分隔符是**用户有意为之**，不得折叠。
     * 这条用例正是用来钉死「不要图省事直接复用 compose 的 polish()」。
     */
    @Test
    fun `sanitizeManualName 不折叠连续分隔符`() {
        val result = NameComposer.sanitizeManualName("my___app.apk", "apk", rule)
        assertTrue("连续分隔符被折叠了：$result", result.contains("___"))
        assertEquals("my___app.apk", result)
    }

    @Test
    fun `sanitizeManualName 清理首尾的点与空格`() {
        val result = NameComposer.sanitizeManualName("  .MyApp.apk  ", "apk", rule)
        assertEquals("MyApp.apk", result)
    }

    /**
     * 中文名截断：UTF-8 下 1 个汉字 3 字节，若按 char 截断必然切碎最后一个字符，
     * 产生 U+FFFD 甚至让文件系统拒绝写入。
     */
    @Test
    fun `sanitizeManualName 按UTF8字节截断中文名且不切碎字符`() {
        val limited = rule.copy(maxByteLength = 40)
        val input = "中".repeat(30) + ".apk"   // 30*3 + 4 = 94 字节，远超上限

        val result = NameComposer.sanitizeManualName(input, "apk", limited)
        val bytes = result.toByteArray(Charsets.UTF_8)

        assertTrue("截断后仍超上限：${bytes.size} 字节 > 40", bytes.size <= 40)
        assertTrue("扩展名被截掉了：$result", result.endsWith(".apk"))
        // 字节数组 → 字符串 → 字节数组 能原样往返，说明没有半个汉字被留下
        assertEquals("出现了被切碎的多字节字符：$result", result, String(bytes, Charsets.UTF_8))
        assertFalse("出现了 U+FFFD 替换字符：$result", result.contains('\uFFFD'))
        // 主干必须是若干个完整的「中」
        assertTrue("主干被污染：$result", result.removeSuffix(".apk").all { it == '中' })
    }

    /* ================================================================== */
    /* validateManualName —— 五类错误                                      */
    /* ================================================================== */

    @Test
    fun `validateManualName 空白输入报EMPTY`() {
        val validation = NameComposer.validateManualName("   ", "old.apk", emptyList(), rule)

        assertFalse(validation.isValid)
        assertEquals(NameError.EMPTY, validation.error)
        assertNotNull("错误必须带可直接展示的文案", validation.message)
    }

    @Test
    fun `validateManualName 全非法字符报ILLEGAL_ONLY`() {
        // 主干全部由非法字符构成，illegalReplacement 为空 → 清洗后主干为空
        val validation = NameComposer.validateManualName("""<<>>||??.apk""", "old.apk", emptyList(), rule)

        assertFalse(validation.isValid)
        assertEquals(NameError.ILLEGAL_ONLY, validation.error)
    }

    @Test
    fun `validateManualName 系统保留名报RESERVED`() {
        val validation = NameComposer.validateManualName("con.apk", "old.apk", emptyList(), rule)

        assertFalse(validation.isValid)
        assertEquals(NameError.RESERVED, validation.error)
    }

    @Test
    fun `validateManualName 保留名判定忽略大小写`() {
        for (candidate in listOf("CON.apk", "Nul.apk", "LPT1.apk", "com9.apk")) {
            val validation = NameComposer.validateManualName(candidate, "old.apk", emptyList(), rule)
            assertEquals("「$candidate」应被判为系统保留名", NameError.RESERVED, validation.error)
        }
    }

    @Test
    fun `validateManualName 超出字节上限报TOO_LONG`() {
        val limited = rule.copy(maxByteLength = 20)
        val input = "a".repeat(40) + ".apk"   // 44 字节 > 20

        val validation = NameComposer.validateManualName(input, "old.apk", emptyList(), limited)

        assertFalse(validation.isValid)
        assertEquals(NameError.TOO_LONG, validation.error)
        assertTrue(
            "TOO_LONG 文案应告知具体上限，便于用户自行裁剪",
            validation.message?.contains("20") == true
        )
    }

    @Test
    fun `validateManualName 中文名按字节而非字符判定TOO_LONG`() {
        // 10 个汉字 = 30 字节，字符数只有 10；若按 length 判定就会漏报
        val limited = rule.copy(maxByteLength = 25)
        val validation = NameComposer.validateManualName("中".repeat(10) + ".apk", "old.apk", emptyList(), limited)

        assertEquals(NameError.TOO_LONG, validation.error)
    }

    @Test
    fun `validateManualName 同目录撞名报CONFLICT`() {
        val siblings = listOf("other.apk", "taken.apk")
        val validation = NameComposer.validateManualName("taken.apk", "old.apk", siblings, rule)

        assertFalse(validation.isValid)
        assertEquals(NameError.CONFLICT, validation.error)
    }

    /* ================================================================== */
    /* validateManualName —— 语义边界                                      */
    /* ================================================================== */

    @Test
    fun `validateManualName 合法输入无错误且返回清洗后的名字`() {
        val validation = NameComposer.validateManualName(
            """微信:8.0.49.apk""", "weixin.apk", listOf("other.apk"), rule
        )

        assertTrue("应当校验通过，实际 error=${validation.error}", validation.isValid)
        assertNull(validation.error)
        assertEquals("微信8.0.49.apk", validation.sanitized)
    }

    /** 「改成自己现在的名字」是无操作，不能报冲突——否则用户想微调大小写都做不到 */
    @Test
    fun `validateManualName 与自身同名不算冲突`() {
        val siblings = listOf("self.apk", "other.apk")   // 列表里包含自身当前名
        val validation = NameComposer.validateManualName("self.apk", "self.apk", siblings, rule)

        assertTrue("与自身同名被误判为冲突：${validation.error}", validation.isValid)
    }

    @Test
    fun `validateManualName 仅改大小写不算冲突`() {
        val siblings = listOf("Self.apk", "other.apk")
        val validation = NameComposer.validateManualName("SELF.apk", "Self.apk", siblings, rule)

        assertTrue("仅调整自身大小写被误判为冲突：${validation.error}", validation.isValid)
    }

    /** 冲突必须报错让用户自己改，静默追加 `_1` 是欺骗（§3.4 明文约束） */
    @Test
    fun `validateManualName 冲突时不自动追加序号`() {
        val validation = NameComposer.validateManualName("taken.apk", "old.apk", listOf("taken.apk"), rule)

        assertEquals(NameError.CONFLICT, validation.error)
        assertFalse(
            "冲突时不得静默改名，sanitized=${validation.sanitized}",
            validation.sanitized.contains("_1")
        )
        assertEquals("taken.apk", validation.sanitized)
    }

    /**
     * 土耳其语 Locale 下 `"I".lowercase()` 得到无点的 `ı`，
     * 用默认 Locale 做大小写归一会让 `MYFILE.APK` 与 `myfile.apk` 判为不同名，
     * 冲突检测直接失效 → 用户在 U 盘（FAT，不区分大小写）上会覆盖掉别的文件。
     */
    @Test
    fun `validateManualName 冲突比较使用LocaleROOT不受土耳其语影响`() {
        Locale.setDefault(Locale("tr", "TR"))

        val validation = NameComposer.validateManualName(
            "myfile.apk", "old.apk", listOf("MYFILE.APK"), rule
        )

        assertEquals(
            "土耳其语 Locale 下漏报冲突，说明大小写比较没用 Locale.ROOT",
            NameError.CONFLICT,
            validation.error
        )
    }

    /* ================================================================== */
    /* validateManualName —— 校验顺序                                      */
    /* ================================================================== */

    @Test
    fun `validateManualName 空白优先于其它错误`() {
        // 空白 + 同时存在同名兄弟：必须先报 EMPTY
        val validation = NameComposer.validateManualName("  ", "old.apk", listOf("  "), rule)
        assertEquals(NameError.EMPTY, validation.error)
    }

    @Test
    fun `validateManualName 保留名优先于长度与冲突`() {
        val limited = rule.copy(maxByteLength = 3)   // "con.apk" 共 7 字节，也超长
        val validation = NameComposer.validateManualName("con.apk", "old.apk", listOf("con.apk"), limited)

        assertEquals(
            "校验顺序错误：RESERVED 必须先于 TOO_LONG / CONFLICT 报出",
            NameError.RESERVED,
            validation.error
        )
    }

    @Test
    fun `validateManualName 长度优先于冲突`() {
        val limited = rule.copy(maxByteLength = 20)
        val long = "a".repeat(40) + ".apk"
        val validation = NameComposer.validateManualName(long, "old.apk", listOf(long), limited)

        assertEquals(
            "校验顺序错误：TOO_LONG 必须先于 CONFLICT 报出",
            NameError.TOO_LONG,
            validation.error
        )
    }

    @Test
    fun `validateManualName 非法字符优先于保留名`() {
        // 清洗后主干为空 → ILLEGAL_ONLY，不应因为原始输入里含 "con" 而报 RESERVED
        val validation = NameComposer.validateManualName("///.apk", "old.apk", emptyList(), rule)
        assertEquals(NameError.ILLEGAL_ONLY, validation.error)
    }
}
