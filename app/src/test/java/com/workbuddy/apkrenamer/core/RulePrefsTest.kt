package com.workbuddy.apkrenamer.core

import com.workbuddy.apkrenamer.core.NameComposer.Field
import com.workbuddy.apkrenamer.core.NameComposer.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.workbuddy.apkrenamer.RobolectricTestBase
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RulePrefs] 的持久化契约。
 *
 * 使用自实现的 [InMemorySharedPreferences]（`android.content.SharedPreferences` 是接口，
 * 可在 JVM 侧实现）。测试跑在 Robolectric 下只是为了构造 `ApkItem` 所需的真实
 * `android.net.Uri`（见 README 测试分层，runner 与 SDK 统一在 [RobolectricTestBase]）。
 *
 * 这里最关键的一条不变式：**空 prefs 读出的规则必须与 `NameComposer.Rule()` 的默认值完全一致**，
 * 否则「从未进过设置页」与「进过设置页但没改」两种状态下的命名结果会不同。
 */
class RulePrefsTest : RobolectricTestBase() {

    private fun prefs(vararg entries: Pair<String, Any>) =
        InMemorySharedPreferences(entries.toMap())

    /* ================================================================ */
    /* 默认值                                                            */
    /* ================================================================ */

    @Test
    fun `空 prefs 读出的规则与 Rule 默认值完全一致`() {
        assertEquals(Rule(), RulePrefs.loadRule(prefs()))
    }

    @Test
    fun `默认常量与 Rule 默认值对齐`() {
        val default = Rule()
        assertEquals(default.maxByteLength, RulePrefs.DEFAULT_MAX_BYTE)
        assertEquals(default.separator, RulePrefs.DEFAULT_SEPARATOR)
        assertEquals(default.fields, RulePrefs.DEFAULT_FIELDS)
    }

    @Test
    fun `loadRecursive 默认开启`() {
        assertTrue(RulePrefs.loadRecursive(prefs()))
        assertEquals(RulePrefs.DEFAULT_RECURSIVE, RulePrefs.loadRecursive(prefs()))
        assertFalse(RulePrefs.loadRecursive(prefs(RulePrefs.KEY_RECURSIVE to false)))
    }

    /* ================================================================ */
    /* 可移动卷（OTG）记忆                                               */
    /* ================================================================ */

    @Test
    fun `otg 文件夹记录按卷 ID 独立存取`() {
        val p = InMemorySharedPreferences()
        RulePrefs.saveOtgFolder(p, "USB:1234-5678", "content://a/tree/USB:1234-5678/Download/APKs")
        RulePrefs.saveOtgFolder(p, "USB:9999-0000", "content://a/tree/USB:9999-0000/Games")

        // 两个卷互不干扰
        assertEquals(
            "content://a/tree/USB:1234-5678/Download/APKs",
            RulePrefs.loadOtgFolder(p, "USB:1234-5678")
        )
        assertEquals(
            "content://a/tree/USB:9999-0000/Games",
            RulePrefs.loadOtgFolder(p, "USB:9999-0000")
        )
    }

    @Test
    fun `同一卷重复保存会覆盖旧记录`() {
        val p = InMemorySharedPreferences()
        RulePrefs.saveOtgFolder(p, "USB:1234-5678", "content://a/tree/USB:1234-5678/old")
        RulePrefs.saveOtgFolder(p, "USB:1234-5678", "content://a/tree/USB:1234-5678/new")

        assertEquals("content://a/tree/USB:1234-5678/new", RulePrefs.loadOtgFolder(p, "USB:1234-5678"))
    }

    @Test
    fun `从未记录过的卷返回 null`() {
        assertEquals(null, RulePrefs.loadOtgFolder(InMemorySharedPreferences(), "USB:0000-0000"))
    }

    /* ================================================================ */
    /* 旧键解析（历史数据兼容）                                           */
    /* ================================================================ */

    @Test
    fun `解析逗号分隔的字段串并保留顺序`() {
        val rule = RulePrefs.loadRule(prefs(RulePrefs.KEY_FIELDS to "pkg,ver,app"))
        assertEquals(
            listOf(Field.PACKAGE_NAME, Field.VERSION_NAME, Field.APP_NAME),
            rule.fields
        )
    }

    @Test
    fun `字段串两侧空白被容忍`() {
        val rule = RulePrefs.loadRule(prefs(RulePrefs.KEY_FIELDS to " pkg , ver ,app "))
        assertEquals(
            listOf(Field.PACKAGE_NAME, Field.VERSION_NAME, Field.APP_NAME),
            rule.fields
        )
    }

    @Test
    fun `字段串中的未知 id 被丢弃`() {
        val rule = RulePrefs.loadRule(prefs(RulePrefs.KEY_FIELDS to "pkg,bogus,ver"))
        assertEquals(listOf(Field.PACKAGE_NAME, Field.VERSION_NAME), rule.fields)
    }

    @Test
    fun `未保存过字段时才回退默认_已保存的空串解析为空列表`() {
        // 「键不存在」＝ 从未保存过 → 回退默认字段
        assertEquals(RulePrefs.DEFAULT_FIELDS, RulePrefs.loadRule(prefs()).fields)

        // 「键存在但内容解析不出任何字段」＝ 用户主动清空 → 保持空列表，不回退
        assertEquals(
            emptyList<Field>(),
            RulePrefs.loadRule(prefs(RulePrefs.KEY_FIELDS to "")).fields
        )
        assertEquals(
            emptyList<Field>(),
            RulePrefs.loadRule(prefs(RulePrefs.KEY_FIELDS to "   ")).fields
        )
        assertEquals(
            emptyList<Field>(),
            RulePrefs.loadRule(prefs(RulePrefs.KEY_FIELDS to "bogus,also-bogus")).fields
        )
    }

    @Test
    fun `解析全部已保存的高级选项`() {
        val rule = RulePrefs.loadRule(
            prefs(
                RulePrefs.KEY_FIELDS to "date,code",
                RulePrefs.KEY_SEP to "-",
                RulePrefs.KEY_PREFIX to "Tag",
                RulePrefs.KEY_VERSION_V to false,
                RulePrefs.KEY_WRAP_VC to true,
                RulePrefs.KEY_ILLEGAL to "#",
                RulePrefs.KEY_COLLAPSE to false,
                RulePrefs.KEY_TRIM to false,
                RulePrefs.KEY_LOWER_EXT to false,
                RulePrefs.KEY_MAXBYTE to 120
            )
        )
        assertEquals(
            Rule(
                fields = listOf(Field.DATE, Field.VERSION_CODE),
                separator = "-",
                customPrefix = "Tag",
                versionPrefixV = false,
                wrapVersionCode = true,
                illegalReplacement = "#",
                collapseSeparator = false,
                trimSpaces = false,
                lowercaseExtension = false,
                maxByteLength = 120
            ),
            rule
        )
    }

    @Test
    fun `读取时只 trim customPrefix 而 illegalReplacement 原样保留`() {
        val rule = RulePrefs.loadRule(
            prefs(
                RulePrefs.KEY_PREFIX to "  Tag  ",
                RulePrefs.KEY_ILLEGAL to "  #  "
            )
        )
        // 前缀是文件名的一部分，首尾空格无意义 → trim
        assertEquals("Tag", rule.customPrefix)
        // 替换符里的空格是有效负载（用户可能就想用空格替换非法字符）→ 不能 trim
        assertEquals("  #  ", rule.illegalReplacement)
    }

    /* ================================================================ */
    /* 往返一致性                                                        */
    /* ================================================================ */

    @Test
    fun `saveRule 后 loadRule 完全还原`() {
        val p = InMemorySharedPreferences()
        val rule = Rule(
            fields = listOf(Field.DATE, Field.PACKAGE_NAME, Field.VERSION_CODE),
            separator = "-",
            customPrefix = "Prefix",
            versionPrefixV = false,
            wrapVersionCode = true,
            illegalReplacement = "#",
            collapseSeparator = false,
            trimSpaces = false,
            lowercaseExtension = false,
            maxByteLength = 120
        )

        RulePrefs.saveRule(p, rule, recursive = false)

        assertEquals(rule, RulePrefs.loadRule(p))
        assertFalse(RulePrefs.loadRecursive(p))
        assertTrue("saveRule 必须真正落盘", p.flushCount > 0)
    }

    @Test
    fun `默认规则往返后依旧等于默认规则`() {
        val p = InMemorySharedPreferences()
        RulePrefs.saveRule(p, Rule(), recursive = true)

        assertEquals(Rule(), RulePrefs.loadRule(p))
        assertTrue(RulePrefs.loadRecursive(p))
    }

    @Test
    fun `空分隔符可以被持久化`() {
        val p = InMemorySharedPreferences()
        val rule = Rule(separator = "")

        RulePrefs.saveRule(p, rule, recursive = true)

        assertEquals("", RulePrefs.loadRule(p).separator)
    }

    @Test
    fun `saveRule 写入的字段串使用枚举 id`() {
        val p = InMemorySharedPreferences()
        RulePrefs.saveRule(
            p,
            Rule(fields = listOf(Field.CUSTOM_PREFIX, Field.ORIGINAL_NAME, Field.ARCHIVE_TYPE)),
            recursive = true
        )
        assertEquals("custom,orig,type", p.rawString(RulePrefs.KEY_FIELDS))
    }

    @Test
    fun `saveRule 只 trim customPrefix 而 illegalReplacement 原样落盘`() {
        val p = InMemorySharedPreferences()
        RulePrefs.saveRule(
            p,
            Rule(customPrefix = "  Tag  ", illegalReplacement = "  #  "),
            recursive = true
        )
        assertEquals("Tag", p.rawString(RulePrefs.KEY_PREFIX))
        assertEquals("  #  ", p.rawString(RulePrefs.KEY_ILLEGAL))
    }

    /* ================================================================ */
    /* maxByteLength 边界                                                */
    /* ================================================================ */

    @Test
    fun `loadRule 把 maxByteLength 夹紧到 20 到 255`() {
        assertEquals(
            RulePrefs.MIN_MAX_BYTE,
            RulePrefs.loadRule(prefs(RulePrefs.KEY_MAXBYTE to 0)).maxByteLength
        )
        assertEquals(
            RulePrefs.MIN_MAX_BYTE,
            RulePrefs.loadRule(prefs(RulePrefs.KEY_MAXBYTE to -100)).maxByteLength
        )
        assertEquals(
            RulePrefs.MAX_MAX_BYTE,
            RulePrefs.loadRule(prefs(RulePrefs.KEY_MAXBYTE to 100_000)).maxByteLength
        )
        // 边界值本身原样保留
        assertEquals(
            RulePrefs.MIN_MAX_BYTE,
            RulePrefs.loadRule(prefs(RulePrefs.KEY_MAXBYTE to RulePrefs.MIN_MAX_BYTE)).maxByteLength
        )
        assertEquals(
            RulePrefs.MAX_MAX_BYTE,
            RulePrefs.loadRule(prefs(RulePrefs.KEY_MAXBYTE to RulePrefs.MAX_MAX_BYTE)).maxByteLength
        )
    }

    @Test
    fun `saveRule 落盘前就把 maxByteLength 夹紧`() {
        val p = InMemorySharedPreferences()
        RulePrefs.saveRule(p, Rule(maxByteLength = 0), recursive = true)
        assertEquals(RulePrefs.MIN_MAX_BYTE, p.rawInt(RulePrefs.KEY_MAXBYTE))

        RulePrefs.saveRule(p, Rule(maxByteLength = 9999), recursive = true)
        assertEquals(RulePrefs.MAX_MAX_BYTE, p.rawInt(RulePrefs.KEY_MAXBYTE))
    }

    /* ================================================================ */
    /* 与 NameComposer 的联动                                            */
    /* ================================================================ */

    @Test
    fun `持久化后的规则可直接驱动 compose`() {
        val p = InMemorySharedPreferences()
        RulePrefs.saveRule(
            p,
            Rule(
                fields = listOf(Field.APP_NAME, Field.VERSION_NAME, Field.VERSION_CODE),
                separator = "-",
                wrapVersionCode = true
            ),
            recursive = true
        )

        val restored = RulePrefs.loadRule(p)
        assertEquals("Demo App-v1.2.3-(4210).apk", NameComposer.compose(apkItem(), restored))
    }

    /* ================================================================ */
    /* 回归：曾经的两处「已知限制」现已修复                                */
    /* ================================================================ */

    /** 修复 #2：区分「从未保存」与「保存了空列表」 */
    @Test
    fun `空字段列表可以被持久化且不回退默认`() {
        val p = InMemorySharedPreferences()
        RulePrefs.saveRule(p, Rule(fields = emptyList()), recursive = true)

        assertEquals("", p.rawString(RulePrefs.KEY_FIELDS))
        // 键已存在 → 视为用户主动清空，保持空列表
        assertEquals(emptyList<Field>(), RulePrefs.loadRule(p).fields)

        // 对照组：全新 prefs（键不存在）仍回退默认，证明「null=未保存」分支没坏
        assertEquals(RulePrefs.DEFAULT_FIELDS, RulePrefs.loadRule(InMemorySharedPreferences()).fields)
    }

    /** 修复 #1：空格是合法的替换符负载，不能被 trim 抹平 */
    @Test
    fun `空格可以作为非法字符替换符并往返持久化`() {
        val p = InMemorySharedPreferences()
        RulePrefs.saveRule(p, Rule(illegalReplacement = " "), recursive = true)

        assertEquals(" ", p.rawString(RulePrefs.KEY_ILLEGAL))
        val restored = RulePrefs.loadRule(p)
        assertEquals(" ", restored.illegalReplacement)

        // 端到端：还原出来的规则确实把非法字符换成空格，而不是删除
        assertEquals("A B", NameComposer.sanitizeSegment("A/B", restored))
    }
}
