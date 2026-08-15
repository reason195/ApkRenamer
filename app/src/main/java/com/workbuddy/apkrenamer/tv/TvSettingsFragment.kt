package com.workbuddy.apkrenamer.tv

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.ViewModelProvider
import com.workbuddy.apkrenamer.R
import com.workbuddy.apkrenamer.core.NameComposer
import com.workbuddy.apkrenamer.core.RulePrefs
import com.workbuddy.apkrenamer.vm.ApkListViewModel

/* ---------------- Action id（数值区间按类别划分，方便扩展时不撞号） ---------------- */

private const val ID_RECURSIVE = 1L
private const val ID_VERSION_V = 2L
private const val ID_WRAP_VC = 3L
private const val ID_COLLAPSE = 4L
private const val ID_TRIM = 5L
private const val ID_LOWER_EXT = 6L

private const val ID_SEPARATOR = 10L
private const val ID_SEP_UNDERSCORE = 11L
private const val ID_SEP_HYPHEN = 12L
private const val ID_SEP_DOT = 13L
private const val ID_SEP_SPACE = 14L

private const val ID_MAX_BYTE = 20L
private const val ID_PREFIX_READONLY = 30L
private const val ID_CLEAR_CACHE = 40L
private const val ID_DONE = 50L

/** 分隔符单选组的 checkSetId，避免与 leanback 内置的 CHECKBOX_CHECK_SET_ID(-1) 冲突 */
private const val CHECK_SET_SEPARATOR = 100

/** 字节上限步进粒度 */
private const val MAX_BYTE_STEP = 10

/**
 * TV 精简设置页（RQ-T7-10）。
 *
 * 只暴露**遥控器友好**的项：布尔开关、分隔符单选、字节上限步进。
 * 需要键入文本的「命名字段顺序」「自定义前缀」「非法字符替换」保持只读或不展示——
 * TV 上的软键盘输入体验极差，强行提供反而是负担（自定义前缀按设计要求以只读形式呈现）。
 *
 * ### 与业务逻辑的边界
 * 本页只做「读写 [RulePrefs]」+「通知 ViewModel 重载规则」两件事。
 * **不**调用 `NameComposer.compose()` 自行算目标名——那是 [ApkListViewModel.refreshRuleOnResume]
 * 的职责，两端共用（RQ-T7-6 / §4.3 纪律 1）。
 */
class TvSettingsFragment : GuidedStepSupportFragment() {

    private val vm: ApkListViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[ApkListViewModel::class.java]
    }

    private val prefs by lazy(LazyThreadSafetyMode.NONE) {
        requireContext().getSharedPreferences(RulePrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 当前规则的工作副本。
     *
     * TV 页不编辑 `fields` / `customPrefix` / `illegalReplacement`，
     * 靠 `copy()` 保留这三项，保证「在手机端配好、来 TV 改个开关」不会把它们清掉。
     */
    private lateinit var rule: NameComposer.Rule
    private var recursive: Boolean = RulePrefs.DEFAULT_RECURSIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        rule = RulePrefs.loadRule(prefs)
        recursive = RulePrefs.loadRecursive(prefs)
        super.onCreate(savedInstanceState)
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            getString(R.string.settings_title),
            getString(R.string.tv_settings_desc),
            getString(R.string.tv_settings_breadcrumb),
            null
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = requireContext()

        actions.add(switchAction(ctx, ID_RECURSIVE, R.string.recursive, recursive))
        actions.add(switchAction(ctx, ID_VERSION_V, R.string.version_prefix_v, rule.versionPrefixV))
        actions.add(switchAction(ctx, ID_WRAP_VC, R.string.wrap_version_code, rule.wrapVersionCode))
        actions.add(switchAction(ctx, ID_COLLAPSE, R.string.collapse_separator, rule.collapseSeparator))
        actions.add(switchAction(ctx, ID_TRIM, R.string.trim_spaces, rule.trimSpaces))
        actions.add(switchAction(ctx, ID_LOWER_EXT, R.string.lowercase_extension, rule.lowercaseExtension))

        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_SEPARATOR)
                .title(R.string.tv_settings_separator)
                .description(separatorLabel(rule.separator))
                .subActions(buildSeparatorSubActions(ctx))
                .build()
        )

        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_MAX_BYTE)
                .title(R.string.tv_settings_max_byte)
                .description(maxByteDescription())
                .build()
        )

        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_PREFIX_READONLY)
                .title(R.string.tv_settings_prefix_readonly)
                .description(prefixDescription())
                .infoOnly(true)
                .enabled(false)
                .focusable(false)
                .build()
        )

        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_CLEAR_CACHE)
                .title(R.string.clear_cache)
                .build()
        )

        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_DONE)
                .title(R.string.tv_settings_done)
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            // 复选类：框架已在回调前翻转 isChecked，这里直接读取即可
            ID_RECURSIVE -> {
                recursive = action.isChecked
                persist()
            }

            ID_VERSION_V -> {
                rule = rule.copy(versionPrefixV = action.isChecked)
                persist()
            }

            ID_WRAP_VC -> {
                rule = rule.copy(wrapVersionCode = action.isChecked)
                persist()
            }

            ID_COLLAPSE -> {
                rule = rule.copy(collapseSeparator = action.isChecked)
                persist()
            }

            ID_TRIM -> {
                rule = rule.copy(trimSpaces = action.isChecked)
                persist()
            }

            ID_LOWER_EXT -> {
                rule = rule.copy(lowercaseExtension = action.isChecked)
                persist()
            }

            // 步进：单键循环。到达上限后回到下限，遥控器上只需一个按键即可遍历全区间
            ID_MAX_BYTE -> {
                val next = rule.maxByteLength + MAX_BYTE_STEP
                val clamped = if (next > RulePrefs.MAX_MAX_BYTE) RulePrefs.MIN_MAX_BYTE else next
                rule = rule.copy(maxByteLength = clamped)
                action.description = maxByteDescription()
                notifyActionChanged(findActionPositionById(ID_MAX_BYTE))
                persist()
            }

            ID_CLEAR_CACHE -> {
                vm.clearCache()
                Toast.makeText(
                    requireContext(),
                    R.string.tv_settings_cache_cleared,
                    Toast.LENGTH_SHORT
                ).show()
            }

            ID_DONE -> finishGuidedStepSupportFragments()

            else -> Unit
        }
    }

    /**
     * 分隔符单选。返回 `true` 收起子动作列表。
     *
     * 子项的 checked 状态由 leanback 的 checkSet 机制维护，这里只需把选中值写回规则。
     */
    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val separator = when (action.id) {
            ID_SEP_UNDERSCORE -> "_"
            ID_SEP_HYPHEN -> "-"
            ID_SEP_DOT -> "."
            ID_SEP_SPACE -> " "
            else -> return true
        }
        rule = rule.copy(separator = separator)
        findActionById(ID_SEPARATOR)?.let { parent ->
            parent.description = separatorLabel(separator)
            notifyActionChanged(findActionPositionById(ID_SEPARATOR))
        }
        persist()
        return true
    }

    /* ------------------------------------------------------------------ */
    /* 组装辅助                                                            */
    /* ------------------------------------------------------------------ */

    private fun switchAction(
        ctx: Context,
        id: Long,
        titleRes: Int,
        checked: Boolean
    ): GuidedAction = GuidedAction.Builder(ctx)
        .id(id)
        .title(titleRes)
        .description(if (checked) R.string.tv_settings_on else R.string.tv_settings_off)
        .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
        .checked(checked)
        .build()

    private fun buildSeparatorSubActions(ctx: Context): List<GuidedAction> {
        val current = rule.separator
        return listOf(
            ID_SEP_UNDERSCORE to "_",
            ID_SEP_HYPHEN to "-",
            ID_SEP_DOT to ".",
            ID_SEP_SPACE to " "
        ).map { (id, value) ->
            GuidedAction.Builder(ctx)
                .id(id)
                .title(separatorLabel(value))
                .checkSetId(CHECK_SET_SEPARATOR)
                .checked(value == current)
                .build()
        }
    }

    private fun separatorLabel(separator: String): String = when (separator) {
        "_" -> getString(R.string.tv_settings_separator_underscore)
        "-" -> getString(R.string.tv_settings_separator_hyphen)
        "." -> getString(R.string.tv_settings_separator_dot)
        " " -> getString(R.string.tv_settings_separator_space)
        // 手机端可能配了 TV 不提供的分隔符，原样回显而不是悄悄改掉它
        else -> separator
    }

    private fun maxByteDescription(): String = getString(
        R.string.tv_settings_max_byte_desc,
        rule.maxByteLength,
        RulePrefs.MAX_MAX_BYTE,
        RulePrefs.MIN_MAX_BYTE
    )

    private fun prefixDescription(): String =
        if (rule.customPrefix.isEmpty()) {
            getString(R.string.tv_settings_prefix_empty)
        } else {
            getString(R.string.tv_settings_prefix_value, rule.customPrefix)
        }

    /**
     * 落盘 + 通知 ViewModel 重载。
     *
     * 走 `refreshRuleOnResume()` 而不是 `applyRule()`：前者保留用户已有的勾选与手动名
     * （§3.6 行为契约），语义与手机端「从设置页返回」完全一致。
     */
    private fun persist() {
        RulePrefs.saveRule(prefs, rule, recursive)
        vm.refreshRuleOnResume()
        // 开关类项的 description（已开启 / 已关闭）需要跟着刷新
        refreshSwitchDescriptions()
    }

    private fun refreshSwitchDescriptions() {
        val pairs = listOf(
            ID_RECURSIVE to recursive,
            ID_VERSION_V to rule.versionPrefixV,
            ID_WRAP_VC to rule.wrapVersionCode,
            ID_COLLAPSE to rule.collapseSeparator,
            ID_TRIM to rule.trimSpaces,
            ID_LOWER_EXT to rule.lowercaseExtension
        )
        for ((id, checked) in pairs) {
            val action = findActionById(id) ?: continue
            val expected = getString(
                if (checked) R.string.tv_settings_on else R.string.tv_settings_off
            )
            if (action.description == expected) continue
            action.description = expected
            notifyActionChanged(findActionPositionById(id))
        }
    }
}
