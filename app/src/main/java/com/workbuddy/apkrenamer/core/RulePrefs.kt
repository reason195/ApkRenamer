package com.workbuddy.apkrenamer.core

import android.content.SharedPreferences

/**
 * `SharedPreferences` ↔ [NameComposer.Rule] 的读写桥接层。
 *
 * 规则设置从主界面迁移到独立设置页后，两个 Activity 需要共享同一份持久化格式：
 * 设置页负责写（[saveRule]），主界面负责读（[loadRule]）。把键名与默认值集中到这里，
 * 可以避免两端各写一份而产生「重启后行为漂移」。
 *
 * 纯 Kotlin + `SharedPreferences`，不持有任何 View，便于复用与测试。
 *
 * ### 默认值约定
 * 所有默认值必须与 [NameComposer.Rule] 的构造默认值严格一致，否则「从未保存过设置」
 * 与「保存过默认设置」两种状态下的命名结果会不同。
 */
object RulePrefs {

    /* ---------------- SharedPreferences 文件名 ---------------- */

    /**
     * 应用级 SharedPreferences 文件名。
     *
     * P3 之前这个字符串以 `private const val PREFS` 的形式散落在 `MainActivity` 与
     * `SettingsActivity` 里；T7 引入 TV 端后出现第三个使用方（`TvSettingsFragment`），
     * 再复制一份必然写错——所以提升为公共常量，取值与历史完全一致，不影响已有数据。
     */
    const val PREFS_NAME = "apk_renamer_prefs"

    /* ---------------- 键名（与旧版 MainActivity 保持一致，兼容历史数据） ---------------- */

    /**
     * 上次授权目录的 tree Uri（字符串形式）。
     *
     * 严格来说它不属于「命名规则」，但它与规则共用同一个 prefs 文件，
     * 且手机端与 TV 端都要读写，集中在这里比各写各的更安全。
     */
    const val KEY_TREE = "tree_uri"

    const val KEY_FIELDS = "fields"
    const val KEY_SEP = "separator"
    const val KEY_PREFIX = "prefix"
    const val KEY_VERSION_V = "version_v"
    const val KEY_RECURSIVE = "recursive"

    /* 高级选项键名：独立命名，不复用上方旧键 */
    const val KEY_WRAP_VC = "wrap_version_code"
    const val KEY_ILLEGAL = "illegal_replacement"
    const val KEY_COLLAPSE = "collapse_separator"
    const val KEY_TRIM = "trim_spaces"
    const val KEY_LOWER_EXT = "lowercase_extension"
    const val KEY_MAXBYTE = "max_byte_length"

    /* 可移动卷（OTG / SD）记忆：按卷 ID 各记一个键，键名即卷 ID */
    const val KEY_OTG_PREFIX = "otg_folder:"

    /* 列表视图偏好（排序 / 筛选，存枚举 name，跨重启保留） */
    const val KEY_SORT_KEY = "sort_key"
    const val KEY_SORT_DIRECTION = "sort_direction"
    const val KEY_FILTER = "filter"

    /* ---------------- 默认值 / 边界（与 NameComposer.Rule 对齐） ---------------- */

    /** 与 [NameComposer.Rule.maxByteLength] 默认值保持一致 */
    const val DEFAULT_MAX_BYTE = 200
    const val MIN_MAX_BYTE = 20
    const val MAX_MAX_BYTE = 255

    const val DEFAULT_SEPARATOR = "_"
    const val DEFAULT_RECURSIVE = true

    /** 未保存过字段时的默认拼接顺序：应用名 + 版本号 */
    val DEFAULT_FIELDS: List<NameComposer.Field> = listOf(
        NameComposer.Field.APP_NAME,
        NameComposer.Field.VERSION_NAME
    )

    /* ---------------- 读 ---------------- */

    /**
     * 从 [p] 还原一份完整的命名规则。任何缺失 / 非法的值都回退到 [NameComposer.Rule] 的默认值。
     *
     * @param p 应用级 SharedPreferences（`apk_renamer_prefs`）
     * @return 可直接交给 [NameComposer.compose] 使用的规则对象
     */
    fun loadRule(p: SharedPreferences): NameComposer.Rule {
        val rawFields = p.getString(KEY_FIELDS, null)
        val fields: List<NameComposer.Field> = if (rawFields == null) {
            DEFAULT_FIELDS
        } else {
            rawFields.split(",").mapNotNull { NameComposer.Field.of(it.trim()) }
        }

        return NameComposer.Rule(
            fields = fields,
            separator = p.getString(KEY_SEP, DEFAULT_SEPARATOR) ?: DEFAULT_SEPARATOR,
            customPrefix = p.getString(KEY_PREFIX, "")?.trim().orEmpty(),
            versionPrefixV = p.getBoolean(KEY_VERSION_V, true),
            wrapVersionCode = p.getBoolean(KEY_WRAP_VC, false),
            illegalReplacement = p.getString(KEY_ILLEGAL, "").orEmpty(),
            collapseSeparator = p.getBoolean(KEY_COLLAPSE, true),
            trimSpaces = p.getBoolean(KEY_TRIM, true),
            lowercaseExtension = p.getBoolean(KEY_LOWER_EXT, true),
            maxByteLength = p.getInt(KEY_MAXBYTE, DEFAULT_MAX_BYTE)
                .coerceIn(MIN_MAX_BYTE, MAX_MAX_BYTE)
        )
    }

    /**
     * 读取「扫描时包含子目录」开关。该项不属于 [NameComposer.Rule]，但同样在设置页维护。
     */
    fun loadRecursive(p: SharedPreferences): Boolean =
        p.getBoolean(KEY_RECURSIVE, DEFAULT_RECURSIVE)

    /* ---------------- 可移动卷（OTG）记忆 ---------------- */

    /**
     * 记录某可移动卷（OTG U 盘 / SD 卡）上次使用的目录（tree Uri 字符串）。
     * 键 = `otg_folder:<卷ID>`，卷 ID 如 `USB:1234-5678`，同一 U 盘重插后保持不变。
     */
    fun saveOtgFolder(p: SharedPreferences, volumeId: String, treeUri: String) {
        p.edit().putString(KEY_OTG_PREFIX + volumeId, treeUri).apply()
    }

    /** 读取某可移动卷上次使用的目录；从未用过返回 null。 */
    fun loadOtgFolder(p: SharedPreferences, volumeId: String): String? =
        p.getString(KEY_OTG_PREFIX + volumeId, null)

    /* ---------------- 写 ---------------- */

    /**
     * 持久化规则与递归开关。
     *
     * 字符串项先 `trim()` 再落盘，保证「UI 显示的值」与「实际生效的值」一致；
     * 字节上限存夹紧后的有效值，避免写入 0 导致下次启动文件名被截空。
     *
     * @param p         应用级 SharedPreferences
     * @param rule      设置页当前控件状态组装出的规则
     * @param recursive 扫描是否递归子目录
     */
    fun saveRule(p: SharedPreferences, rule: NameComposer.Rule, recursive: Boolean) {
        p.edit()
            .putString(KEY_FIELDS, rule.fields.joinToString(",") { it.id })
            .putString(KEY_SEP, rule.separator)
            .putString(KEY_PREFIX, rule.customPrefix.trim())
            .putBoolean(KEY_VERSION_V, rule.versionPrefixV)
            .putBoolean(KEY_RECURSIVE, recursive)
            .putBoolean(KEY_WRAP_VC, rule.wrapVersionCode)
            .putString(KEY_ILLEGAL, rule.illegalReplacement)
            .putBoolean(KEY_COLLAPSE, rule.collapseSeparator)
            .putBoolean(KEY_TRIM, rule.trimSpaces)
            .putBoolean(KEY_LOWER_EXT, rule.lowercaseExtension)
            .putInt(KEY_MAXBYTE, rule.maxByteLength.coerceIn(MIN_MAX_BYTE, MAX_MAX_BYTE))
            .apply()
    }
}
