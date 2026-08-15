package com.workbuddy.apkrenamer

import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.workbuddy.apkrenamer.core.NameComposer
import com.workbuddy.apkrenamer.core.RulePrefs
import com.workbuddy.apkrenamer.databinding.ActivitySettingsBinding
import com.workbuddy.apkrenamer.ui.applySystemBarInsets
import com.workbuddy.apkrenamer.ui.enableEdgeToEdgeCompat

/**
 * 设置页：命名规则 + 高级选项 + 模板预览。
 *
 * 从 [MainActivity] 迁出的规则 UI 全部集中在这里。任何一次改动都会**即时**通过
 * [RulePrefs.saveRule] 落盘，主界面在 `onResume()` 中重新读取并刷新预览，
 * 因此本页不需要「保存」按钮，也不需要回传 result。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    /** 用 LinkedHashSet 保存字段：勾选顺序 == 文件名拼接顺序 */
    private val selectedFields = LinkedHashSet<NameComposer.Field>()
    private var separator: String = RulePrefs.DEFAULT_SEPARATOR

    /* ================================================================ */
    /* 生命周期                                                          */
    /* ================================================================ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeCompat()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // edge-to-edge：顶栏吃状态栏，滚动容器吃导航栏（布局已配 clipToPadding=false）
        binding.toolbar.applySystemBarInsets(top = true, sides = true)
        binding.scrollContent.applySystemBarInsets(bottom = true, sides = true)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 顺序要求：先还原状态，再挂监听，避免 setChecked/setText 触发回写
        restoreRule()
        buildFieldChips()
        buildSeparatorChips()
        setupListeners()
        renderTemplate()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        // 输入框可能仍持有焦点（失焦监听未触发），离开页面前强制落盘一次
        onRuleChanged()
    }

    /* ================================================================ */
    /* 规则 UI                                                           */
    /* ================================================================ */

    /** 从模板 inflate 一枚 Material3 Filter Chip */
    private fun newChip(parent: ViewGroup): Chip =
        layoutInflater.inflate(R.layout.item_chip_filter, parent, false) as Chip

    private fun buildFieldChips() {
        binding.chipFields.removeAllViews()
        NameComposer.Field.values().forEach { field ->
            val chip = newChip(binding.chipFields).apply {
                text = field.label
                isChecked = field in selectedFields
                tag = field
            }
            chip.setOnCheckedChangeListener { _, checked ->
                // 重新勾选时排到末尾，让用户可以通过「取消 → 再选」调整拼接顺序
                if (checked) selectedFields.add(field) else selectedFields.remove(field)
                onRuleChanged()
            }
            binding.chipFields.addView(chip)
        }
    }

    private fun buildSeparatorChips() {
        binding.chipSeparators.removeAllViews()
        SEPARATORS.forEach { (value, label) ->
            val chip = newChip(binding.chipSeparators).apply {
                text = label
                isChecked = value == separator
                tag = value
            }
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    separator = value
                    onRuleChanged()
                }
            }
            binding.chipSeparators.addView(chip)
        }
    }

    private fun setupListeners() {
        binding.swVersionV.setOnCheckedChangeListener { _, _ -> onRuleChanged() }
        binding.swRecursive.setOnCheckedChangeListener { _, _ -> onRuleChanged() }
        binding.etPrefix.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onRuleChanged() }

        // 高级选项：开关即时生效，输入框失焦后再统一刷新，避免逐字符重算预览
        binding.swWrapVersionCode.setOnCheckedChangeListener { _, _ -> onRuleChanged() }
        binding.swCollapseSeparator.setOnCheckedChangeListener { _, _ -> onRuleChanged() }
        binding.swTrimSpaces.setOnCheckedChangeListener { _, _ -> onRuleChanged() }
        binding.swLowercaseExt.setOnCheckedChangeListener { _, _ -> onRuleChanged() }
        binding.etIllegalReplacement.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onRuleChanged()
        }
        binding.etMaxByteLength.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                // 失焦时把越界/空值回写成实际生效的值，避免 UI 显示与规则不一致
                binding.etMaxByteLength.setText(readMaxByteLength().toString())
                onRuleChanged()
            }
        }
    }

    /** 以当前控件状态组装一份规则（UI → Rule） */
    private fun currentRule() = NameComposer.Rule(
        fields = selectedFields.toList(),
        separator = separator,
        customPrefix = binding.etPrefix.text?.toString()?.trim().orEmpty(),
        versionPrefixV = binding.swVersionV.isChecked,
        wrapVersionCode = binding.swWrapVersionCode.isChecked,
        illegalReplacement = binding.etIllegalReplacement.text?.toString()?.trim().orEmpty(),
        collapseSeparator = binding.swCollapseSeparator.isChecked,
        trimSpaces = binding.swTrimSpaces.isChecked,
        lowercaseExtension = binding.swLowercaseExt.isChecked,
        maxByteLength = readMaxByteLength()
    )

    /**
     * 读取字节上限输入框：空值 / 非数字回退到 [RulePrefs.DEFAULT_MAX_BYTE]，
     * 其余夹紧到 [RulePrefs.MIN_MAX_BYTE]–[RulePrefs.MAX_MAX_BYTE]，
     * 防止用户填 0 导致文件名被截空。
     */
    private fun readMaxByteLength(): Int =
        binding.etMaxByteLength.text?.toString()?.toIntOrNull()
            ?.coerceIn(RulePrefs.MIN_MAX_BYTE, RulePrefs.MAX_MAX_BYTE)
            ?: RulePrefs.DEFAULT_MAX_BYTE

    /** 任一控件变化后：落盘 + 刷新模板预览（本页无文件列表，不做 applyRule） */
    private fun onRuleChanged() {
        RulePrefs.saveRule(prefs, currentRule(), binding.swRecursive.isChecked)
        renderTemplate()
    }

    private fun renderTemplate() {
        binding.tvTemplate.text = getString(R.string.template_preview, currentRule().template())
    }

    /** 从 prefs 还原全部控件状态（Rule → UI） */
    private fun restoreRule() {
        val rule = RulePrefs.loadRule(prefs)

        selectedFields.clear()
        selectedFields.addAll(rule.fields)
        separator = rule.separator

        binding.etPrefix.setText(rule.customPrefix)
        binding.swVersionV.isChecked = rule.versionPrefixV
        binding.swRecursive.isChecked = RulePrefs.loadRecursive(prefs)

        // 高级选项：默认值由 RulePrefs 保证与 NameComposer.Rule 一致
        binding.swWrapVersionCode.isChecked = rule.wrapVersionCode
        binding.etIllegalReplacement.setText(rule.illegalReplacement)
        binding.swCollapseSeparator.isChecked = rule.collapseSeparator
        binding.swTrimSpaces.isChecked = rule.trimSpaces
        binding.swLowercaseExt.isChecked = rule.lowercaseExtension
        binding.etMaxByteLength.setText(rule.maxByteLength.toString())
    }

    private companion object {
        const val PREFS = "apk_renamer_prefs"

        val SEPARATORS = listOf(
            "_" to "下划线 _",
            "-" to "连字符 -",
            " " to "空格",
            "." to "点 .",
            "" to "无"
        )
    }
}
