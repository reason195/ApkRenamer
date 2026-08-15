package com.workbuddy.apkrenamer.core

import com.workbuddy.apkrenamer.core.NameComposer.Field
import com.workbuddy.apkrenamer.core.NameComposer.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.workbuddy.apkrenamer.RobolectricTestBase
import org.junit.Test

/**
 * [NameComposer.compose] / [NameComposer.composeAll] 的字段拼接与高级开关行为。
 *
 * 业务逻辑本身是纯 Kotlin（[NameComposer] 零 `android.*` import，R5）；测试跑在
 * Robolectric 下只是因为 `ApkItem.uri` 是非空 `android.net.Uri`，需要真实框架
 * 才能构造（见 README 测试分层，runner 与 SDK 统一在 [RobolectricTestBase]）。
 */
class NameComposerComposeTest : RobolectricTestBase() {

    /* ================================================================ */
    /* 字段拼接                                                          */
    /* ================================================================ */

    @Test
    fun `全部字段全开时按枚举顺序拼接`() {
        val rule = Rule(
            fields = Field.values().toList(),
            customPrefix = "PRE"
        )
        val actual = NameComposer.compose(apkItem(), rule)
        // 注意末尾三个字段：SIZE / MIN_SDK / TARGET_SDK 是后加入的，枚举顺序排在 DATE 之后
        assertEquals(
            "Demo App_v1.2.3_4210_com.example.demo_PRE_raw name_APK_${DATE_STAMP}_1.0KB_API 21_API 34.apk",
            actual
        )
    }

    @Test
    fun `字段顺序即输出顺序`() {
        val forward = Rule(fields = listOf(Field.APP_NAME, Field.VERSION_NAME))
        val backward = Rule(fields = listOf(Field.VERSION_NAME, Field.APP_NAME))

        assertEquals("Demo App_v1.2.3.apk", NameComposer.compose(apkItem(), forward))
        assertEquals("v1.2.3_Demo App.apk", NameComposer.compose(apkItem(), backward))
    }

    @Test
    fun `单字段各自取值正确`() {
        val item = apkItem(displayName = "raw name.apk")
        fun only(field: Field) = NameComposer.compose(item, Rule(fields = listOf(field)))

        assertEquals("Demo App.apk", only(Field.APP_NAME))
        assertEquals("v1.2.3.apk", only(Field.VERSION_NAME))
        assertEquals("4210.apk", only(Field.VERSION_CODE))
        assertEquals("com.example.demo.apk", only(Field.PACKAGE_NAME))
        assertEquals("raw name.apk", only(Field.ORIGINAL_NAME))
        assertEquals("APK.apk", only(Field.ARCHIVE_TYPE))
        assertEquals("$DATE_STAMP.apk", only(Field.DATE))
        assertEquals("1.0KB.apk", only(Field.SIZE))
        assertEquals("API 21.apk", only(Field.MIN_SDK))
        assertEquals("API 34.apk", only(Field.TARGET_SDK))
    }

    @Test
    fun `SIZE 在尺寸未知时被跳过`() {
        val item = apkItem(displayName = "no-size.apk", meta = metaOf())
            .copy(size = 0L)
        assertEquals(
            "no-size.apk",
            NameComposer.compose(item, Rule(fields = listOf(Field.SIZE)))
        )
    }

    @Test
    fun `MIN_SDK 与 TARGET_SDK 在未知时被跳过`() {
        val meta = metaOf(minSdk = 0, targetSdk = 0)
        val item = apkItem(displayName = "unknown-sdk.apk", meta = meta)
        val rule = Rule(fields = listOf(Field.MIN_SDK, Field.TARGET_SDK))
        assertEquals("unknown-sdk.apk", NameComposer.compose(item, rule))
    }

    @Test
    fun `自定义前缀为空时该字段被跳过`() {
        val item = apkItem()
        val withPrefix = Rule(fields = listOf(Field.CUSTOM_PREFIX, Field.APP_NAME), customPrefix = "MyTag")
        val blankPrefix = Rule(fields = listOf(Field.CUSTOM_PREFIX, Field.APP_NAME), customPrefix = "   ")

        assertEquals("MyTag_Demo App.apk", NameComposer.compose(item, withPrefix))
        assertEquals("Demo App.apk", NameComposer.compose(item, blankPrefix))
    }

    /* ================================================================ */
    /* 高级开关                                                          */
    /* ================================================================ */

    @Test
    fun `versionPrefixV 默认给版本号加 v`() {
        val rule = Rule(fields = listOf(Field.VERSION_NAME))
        assertTrue(rule.versionPrefixV)
        assertEquals("v1.2.3.apk", NameComposer.compose(apkItem(), rule))
    }

    @Test
    fun `versionPrefixV 不会重复加 v 且忽略大小写`() {
        val rule = Rule(fields = listOf(Field.VERSION_NAME))
        assertEquals("v2.0.apk", NameComposer.compose(apkItem(meta = metaOf(versionName = "v2.0")), rule))
        assertEquals("V2.0.apk", NameComposer.compose(apkItem(meta = metaOf(versionName = "V2.0")), rule))
    }

    @Test
    fun `versionPrefixV 关闭后保留原始版本号`() {
        val rule = Rule(fields = listOf(Field.VERSION_NAME), versionPrefixV = false)
        assertEquals("1.2.3.apk", NameComposer.compose(apkItem(), rule))
    }

    @Test
    fun `wrapVersionCode 用括号包裹 VersionCode`() {
        val plain = Rule(fields = listOf(Field.VERSION_CODE))
        val wrapped = Rule(fields = listOf(Field.VERSION_CODE), wrapVersionCode = true)

        assertFalse(plain.wrapVersionCode)
        assertEquals("4210.apk", NameComposer.compose(apkItem(), plain))
        assertEquals("(4210).apk", NameComposer.compose(apkItem(), wrapped))
    }

    @Test
    fun `VersionCode 为 0 时字段被跳过并回退原名`() {
        val rule = Rule(fields = listOf(Field.VERSION_CODE), wrapVersionCode = true)
        val item = apkItem(displayName = "fallback.apk", meta = metaOf(versionCode = 0L))
        assertEquals("fallback.apk", NameComposer.compose(item, rule))
    }

    @Test
    fun `illegalReplacement 为空时非法字符被删除`() {
        val rule = Rule(fields = listOf(Field.APP_NAME))
        val item = apkItem(meta = metaOf(appName = "A/B:C*D"))
        assertEquals("ABCD.apk", NameComposer.compose(item, rule))
    }

    @Test
    fun `illegalReplacement 非空时非法字符被替换`() {
        val rule = Rule(fields = listOf(Field.APP_NAME), illegalReplacement = "-")
        val item = apkItem(meta = metaOf(appName = "A/B:C*D"))
        assertEquals("A-B-C-D.apk", NameComposer.compose(item, rule))
    }

    @Test
    fun `illegalReplacement 覆盖全部九个非法字符`() {
        val rule = Rule(fields = listOf(Field.APP_NAME), illegalReplacement = "#")
        val dirty = NameComposer.ILLEGAL_CHARS.joinToString("") { it.toString() }
        val clean = NameComposer.sanitizeSegment(dirty, rule)
        assertEquals("#".repeat(NameComposer.ILLEGAL_CHARS.size), clean)
    }

    /**
     * 修复 #1 后的正确行为：空格是**有效的替换符**，非法字符应变成空格而不是被删除。
     * 这条曾以「已知限制」形式锁定旧的错误行为（空格被 trim 成空串 → 退化为删除）。
     */
    @Test
    fun `illegalReplacement 为空格时非法字符被替换为空格而非删除`() {
        val rule = Rule(fields = listOf(Field.APP_NAME), illegalReplacement = " ")

        // 单个非法字符 → 变成空格（而不是消失成 "AB"）
        assertEquals("A B", NameComposer.sanitizeSegment("A/B", rule))

        // 九个非法字符逐一被替换为空格
        assertEquals(
            "a b c d e f g h i j",
            NameComposer.sanitizeSegment("a\\b/c:d*e?f\"g<h>i|j", rule)
        )

        // 连续非法字符先各变一个空格，再被 trimSpaces 折叠为单个空格
        assertEquals("A B", NameComposer.sanitizeSegment("A//B", rule))

        // 首尾非法字符替换出的空格被 trim 掉
        assertEquals("A", NameComposer.sanitizeSegment("/A/", rule))
    }

    @Test
    fun `空格替换符在 compose 全流程中生效`() {
        val rule = Rule(fields = listOf(Field.APP_NAME), illegalReplacement = " ")
        val item = apkItem(meta = metaOf(appName = "A/B:C*D"))
        assertEquals("A B C D.apk", NameComposer.compose(item, rule))
    }

    @Test
    fun `关闭 trimSpaces 后空格替换符不折叠`() {
        val rule = Rule(
            fields = listOf(Field.APP_NAME),
            illegalReplacement = " ",
            trimSpaces = false
        )
        assertEquals("A  B", NameComposer.sanitizeSegment("A//B", rule))
    }

    @Test
    fun `删除模式与替换模式对照`() {
        val dropping = Rule(fields = listOf(Field.APP_NAME))                       // illegalReplacement = ""
        val replacing = Rule(fields = listOf(Field.APP_NAME), illegalReplacement = " ")

        assertEquals("", dropping.illegalReplacement)
        assertEquals("AB", NameComposer.sanitizeSegment("A/B", dropping))
        assertEquals("A B", NameComposer.sanitizeSegment("A/B", replacing))
    }

    @Test
    fun `collapseSeparator 合并重复分隔符并去掉首尾分隔符`() {
        val item = apkItem(meta = metaOf(appName = "_A_"))
        val collapsed = Rule(fields = listOf(Field.APP_NAME, Field.VERSION_NAME))
        val kept = Rule(fields = listOf(Field.APP_NAME, Field.VERSION_NAME), collapseSeparator = false)

        assertEquals("A_v1.2.3.apk", NameComposer.compose(item, collapsed))
        assertEquals("_A__v1.2.3.apk", NameComposer.compose(item, kept))
    }

    @Test
    fun `trimSpaces 折叠多余空白`() {
        val item = apkItem(meta = metaOf(appName = "  Demo   App  "))
        val trimmed = Rule(fields = listOf(Field.APP_NAME, Field.VERSION_NAME))
        val raw = Rule(fields = listOf(Field.APP_NAME, Field.VERSION_NAME), trimSpaces = false)

        assertEquals("Demo App_v1.2.3.apk", NameComposer.compose(item, trimmed))
        // 关闭后仅整串首尾被 polish() 清理，字段内部的连续空格原样保留
        assertEquals("Demo   App  _v1.2.3.apk", NameComposer.compose(item, raw))
    }

    @Test
    fun `lowercaseExtension 控制扩展名大小写`() {
        val item = apkItem(displayName = "App.APK")
        val lower = Rule(fields = listOf(Field.APP_NAME))
        val keep = Rule(fields = listOf(Field.APP_NAME), lowercaseExtension = false)

        assertEquals("Demo App.apk", NameComposer.compose(item, lower))
        assertEquals("Demo App.APK", NameComposer.compose(item, keep))
    }

    @Test
    fun `maxByteLength 生效且不切断中文`() {
        val item = apkItem(meta = metaOf(appName = "中".repeat(100)))
        val rule = Rule(fields = listOf(Field.APP_NAME), maxByteLength = 40)

        val actual = NameComposer.compose(item, rule)
        assertEquals("中".repeat(12) + ".apk", actual)
        assertTrue(actual.toByteArray(Charsets.UTF_8).size <= 40)
        assertFalse(actual.contains('\uFFFD'))
    }

    /**
     * 修复 #5 的正常路径回归：在 RulePrefs 允许的 [20, 255] 全区间内，
     * 无论主干是 ASCII 还是中文，产物都必须满足「不超字节上限 + 不产生乱码 + 保留扩展名」。
     */
    @Test
    fun `字节上限在 RulePrefs 允许区间内始终被遵守`() {
        val stems = listOf("中".repeat(200), "a".repeat(500), "混合Mixed名称".repeat(30))
        for (maxBytes in intArrayOf(20, 21, 32, 64, 100, 200, 254, 255)) {
            for (stem in stems) {
                val actual = NameComposer.truncateKeepingExtension(stem, "apk", maxBytes)
                val size = actual.toByteArray(Charsets.UTF_8).size
                assertTrue(
                    "maxBytes=$maxBytes 实际=$size 值=$actual",
                    size <= maxBytes
                )
                assertTrue("应保留扩展名：$actual", actual.endsWith(".apk"))
                assertFalse("不应从多字节字符中间切断：$actual", actual.contains('\uFFFD'))
            }
        }
    }

    /**
     * 修复 #5 的针对性回归：后缀本身就吃满 / 撑爆预算（`budget <= 0`）时，
     * 既不能死递归（旧实现在此处 StackOverflow），返回值也必须守住字节上限。
     */
    @Test
    fun `后缀吃满预算时不死递归且仍不超字节上限`() {
        for (maxBytes in intArrayOf(0, 1, 4, 20)) {
            val r = NameComposer.truncateKeepingExtension("SomeApp", "aaaaaaaaaaaaaaaaaaaa", maxBytes)
            assertTrue(
                "maxBytes=$maxBytes 返回 [$r] 共 ${r.toByteArray(Charsets.UTF_8).size} 字节，超出上限",
                r.toByteArray(Charsets.UTF_8).size <= maxOf(maxBytes, 1)
            )
        }
    }

    @Test
    fun `separator 为空串时字段直接相连`() {
        val rule = Rule(fields = listOf(Field.APP_NAME, Field.VERSION_NAME), separator = "")
        assertEquals("Demo Appv1.2.3.apk", NameComposer.compose(apkItem(), rule))
    }

    /* ================================================================ */
    /* 兜底分支                                                          */
    /* ================================================================ */

    @Test
    fun `元数据缺失时回退到原文件名`() {
        val item = apkItem(displayName = "Original Pkg.apk", meta = null)
        val rule = Rule(fields = listOf(Field.APP_NAME, Field.VERSION_NAME))
        assertEquals("Original Pkg.apk", NameComposer.compose(item, rule))
    }

    @Test
    fun `字段列表为空时回退到原文件名`() {
        val rule = Rule(fields = emptyList())
        assertEquals("raw name.apk", NameComposer.compose(apkItem(), rule))
    }

    @Test
    fun `原名也为空时回退到 unnamed`() {
        val item = apkItem(displayName = ".apk", meta = null)
        val rule = Rule(fields = listOf(Field.APP_NAME))
        assertEquals("unnamed.apk", NameComposer.compose(item, rule))
    }

    @Test
    fun `Windows 保留设备名被前缀下划线规避`() {
        val rule = Rule(fields = listOf(Field.APP_NAME))
        assertEquals("_CON.apk", NameComposer.compose(apkItem(meta = metaOf(appName = "CON")), rule))
        assertEquals("_Con.apk", NameComposer.compose(apkItem(meta = metaOf(appName = "Con")), rule))
        assertEquals("_com1.apk", NameComposer.compose(apkItem(meta = metaOf(appName = "com1")), rule))
        // 非保留名不受影响
        assertEquals("com10.apk", NameComposer.compose(apkItem(meta = metaOf(appName = "com10")), rule))
    }

    /* ================================================================ */
    /* 批量与冲突                                                        */
    /* ================================================================ */

    @Test
    fun `composeAll 自身原名让位不产生多余序号`() {
        val item = apkItem(displayName = "Demo App.apk")
        val rule = Rule(fields = listOf(Field.APP_NAME))
        assertEquals(listOf("Demo App.apk"), NameComposer.composeAll(listOf(item), rule))
    }

    @Test
    fun `composeAll 多个同名结果序号递增`() {
        val items = listOf(
            apkItem(displayName = "a.apk", meta = metaOf(appName = "Same")),
            apkItem(displayName = "b.apk", meta = metaOf(appName = "Same")),
            apkItem(displayName = "c.apk", meta = metaOf(appName = "Same"))
        )
        val rule = Rule(fields = listOf(Field.APP_NAME))
        assertEquals(
            listOf("Same.apk", "Same_1.apk", "Same_2.apk"),
            NameComposer.composeAll(items, rule)
        )
    }

    @Test
    fun `composeAll 序号使用规则里的分隔符`() {
        val items = listOf(
            apkItem(displayName = "a.apk", meta = metaOf(appName = "Same")),
            apkItem(displayName = "b.apk", meta = metaOf(appName = "Same"))
        )
        val rule = Rule(fields = listOf(Field.APP_NAME), separator = "-")
        assertEquals(listOf("Same.apk", "Same-1.apk"), NameComposer.composeAll(items, rule))
    }

    @Test
    fun `composeAll 尊重目录内已存在的文件名`() {
        val items = listOf(apkItem(displayName = "a.apk", meta = metaOf(appName = "Target")))
        val rule = Rule(fields = listOf(Field.APP_NAME))
        assertEquals(
            listOf("Target_1.apk"),
            NameComposer.composeAll(items, rule, existingNames = listOf("Target.apk"))
        )
    }

    @Test
    fun `composeAll 返回结果与输入一一对应`() {
        val items = listOf(
            apkItem(displayName = "one.apk", meta = metaOf(appName = "One")),
            apkItem(displayName = "two.apk", meta = metaOf(appName = "Two")),
            apkItem(displayName = "three.apk", meta = null)
        )
        val rule = Rule(fields = listOf(Field.APP_NAME))
        val result = NameComposer.composeAll(items, rule)

        assertEquals(items.size, result.size)
        assertEquals(listOf("One.apk", "Two.apk", "three.apk"), result)
    }

    /* ================================================================ */
    /* 模板串                                                            */
    /* ================================================================ */

    @Test
    fun `template 反映当前字段与分隔符`() {
        assertEquals("应用名_版本号.apk", Rule().template())
        assertEquals(
            "包名-VersionCode.apk",
            Rule(fields = listOf(Field.PACKAGE_NAME, Field.VERSION_CODE), separator = "-").template()
        )
        assertEquals("（未选择字段）", Rule(fields = emptyList()).template())
    }

    @Test
    fun `Field of 能按 id 反查且未知 id 返回 null`() {
        Field.values().forEach { field ->
            assertEquals(field, Field.of(field.id))
        }
        assertEquals(null, Field.of("not-a-field"))
    }
}
