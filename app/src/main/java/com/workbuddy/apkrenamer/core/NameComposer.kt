package com.workbuddy.apkrenamer.core

import com.workbuddy.apkrenamer.model.ApkItem
import com.workbuddy.apkrenamer.model.ApkMeta
import com.workbuddy.apkrenamer.model.baseName
import com.workbuddy.apkrenamer.model.extension
import com.workbuddy.apkrenamer.model.sizeLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ==================================================================== */
/* 手动改名的校验模型（设计文档 §3.4 / R5）                              */
/* ==================================================================== */

/**
 * 手动输入文件名时可能命中的错误类别。
 *
 * 顺序即**校验优先级**：[EMPTY] → [ILLEGAL_ONLY] → [RESERVED] → [TOO_LONG] → [CONFLICT]。
 * 优先级固定不是为了好看——用户输入 `con.apk` 且同时超长时，先告诉他「这是系统保留名」
 * 比「太长了」更有指导意义；反之若先报长度，他缩短后还会再撞一次保留名。
 */
enum class NameError {
    /** 输入为空或全空白 */
    EMPTY,

    /** 主干全部由非法字符构成，清洗后什么都不剩 */
    ILLEGAL_ONLY,

    /** 主干是 Windows 保留设备名（con / prn / aux / nul / com1-9 / lpt1-9） */
    RESERVED,

    /** UTF-8 字节数超出 `Rule.maxByteLength` */
    TOO_LONG,

    /** 同目录下已存在同名文件（大小写不敏感） */
    CONFLICT
}

/**
 * 手动改名的校验结果。
 *
 * @param sanitized 清洗后的名字。**即使校验失败也会给出**，方便 UI 在输入框下方
 *                  预览「实际会变成什么」。注意 [NameError.CONFLICT] 时它就是用户
 *                  输入的原值——冲突**不会**被自动追加 `_1`（§3.4 明文约束：
 *                  静默改名是欺骗，必须让用户自己决定）。
 * @param error     命中的错误，`null` 表示通过。
 * @param message   可直接展示给用户的中文文案，`null` 表示通过。
 */
data class NameValidation(
    val sanitized: String,
    val error: NameError? = null,
    val message: String? = null
) {
    val isValid: Boolean get() = error == null
}

/**
 * 纯 Kotlin 命名规则生成 + 文件名合法性清洗器。
 *
 * 不依赖任何 Android API，可直接在 JVM 单元测试中验证。
 *
 * ### 三步流水线
 * ```
 * compose()   字段按用户顺序拼接 → 分隔符连接
 *    ↓
 * sanitize()  剔除 \ / : * ? " < > | 与控制字符 → 合并冗余分隔符 → 截断超长
 *    ↓
 * ConflictGuard.claim()  同名冲突自动追加序号 AppName_1.0_2.apk
 * ```
 */
object NameComposer {

    /** Windows / FAT32 / exFAT 通用非法字符 */
    val ILLEGAL_CHARS: CharArray = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    private val ILLEGAL_SET: Set<Char> = ILLEGAL_CHARS.toSet()

    /** Windows 保留设备名，作为文件主干时需规避 */
    private val RESERVED_NAMES: Set<String> = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        for (i in 1..9) {
            add("com$i")
            add("lpt$i")
        }
    }

    private val MULTI_SPACE = Regex("\\s+")

    /** 可参与拼接的字段 */
    enum class Field(val id: String, val label: String) {
        APP_NAME("app", "应用名"),
        VERSION_NAME("ver", "版本号"),
        VERSION_CODE("code", "VersionCode"),
        PACKAGE_NAME("pkg", "包名"),
        CUSTOM_PREFIX("custom", "自定义前缀"),
        ORIGINAL_NAME("orig", "原文件名"),
        ARCHIVE_TYPE("type", "格式"),
        DATE("date", "日期"),
        SIZE("size", "文件大小"),
        MIN_SDK("minSdk", "最低系统版本"),
        TARGET_SDK("targetSdk", "目标系统版本");

        companion object {
            fun of(id: String): Field? = values().firstOrNull { it.id == id }
        }
    }

    /**
     * 命名规则。
     *
     * @param fields            参与拼接的字段，**顺序即输出顺序**
     * @param separator         分隔符，如 `_` `-` ` ` `.`，空串表示直接相连
     * @param customPrefix      [Field.CUSTOM_PREFIX] 对应的文本
     * @param versionPrefixV    版本号前加 `v`，如 `1.2.3` → `v1.2.3`
     * @param wrapVersionCode   VersionCode 用括号包裹，如 `(4210)`
     * @param illegalReplacement 非法字符的替换串，默认删除
     * @param maxByteLength     文件名 UTF-8 字节上限（多数文件系统为 255）
     */
    data class Rule(
        val fields: List<Field> = listOf(Field.APP_NAME, Field.VERSION_NAME),
        val separator: String = "_",
        val customPrefix: String = "",
        val versionPrefixV: Boolean = true,
        val wrapVersionCode: Boolean = false,
        val illegalReplacement: String = "",
        val collapseSeparator: Boolean = true,
        val trimSpaces: Boolean = true,
        val lowercaseExtension: Boolean = true,
        val maxByteLength: Int = 200
    ) {
        /** 供 UI 展示的模板串，如 `应用名_版本号.apk` */
        fun template(): String =
            if (fields.isEmpty()) "（未选择字段）"
            else fields.joinToString(separator.ifEmpty { "" }) { it.label } + ".apk"
    }

    /* ================================================================ */
    /* 拼接                                                              */
    /* ================================================================ */

    /**
     * 依据 [rule] 为 [item] 生成目标文件名（**不含**冲突序号处理）。
     * 元数据缺失或规则为空时安全回退到原文件名。
     */
    fun compose(item: ApkItem, rule: Rule): String {
        val meta = item.meta
        val ext = if (rule.lowercaseExtension) item.extension else item.displayName.substringAfterLast('.', "apk")

        val parts = rule.fields
            .mapNotNull { field -> valueOf(field, item, meta, rule) }
            .map { sanitizeSegment(it, rule) }
            .filter { it.isNotBlank() }

        var stem = parts.joinToString(rule.separator)
        stem = polish(stem, rule)

        if (stem.isBlank()) stem = polish(sanitizeSegment(item.baseName, rule), rule)
        if (stem.isBlank()) stem = "unnamed"
        if (stem.lowercase(Locale.ROOT) in RESERVED_NAMES) stem = "_$stem"

        return truncateKeepingExtension(stem, ext, rule.maxByteLength)
    }

    /**
     * 批量生成，内部自动完成同名冲突消解。
     *
     * **时序约束**：调用方必须以本函数返回的列表顺序，对同一目录依次执行重命名；
     * 否则在链式目标场景（例如 A.apk 目标为 C.apk，且后续某 item 目标恰为 A.apk）
     * 可能瞬时撞名覆盖。如需绝对安全，执行层可对「目标名 == 他人当前原名」的项
     * 先改临时名再二次改名。
     */
    fun composeAll(
        items: List<ApkItem>,
        rule: Rule,
        existingNames: Collection<String> = emptyList()
    ): List<String> {
        val guard = ConflictGuard(existingNames.ifEmpty { items.map { it.displayName } }, rule.separator)
        return items.map { item ->
            // 自身原名先让位，避免「A.apk → A.apk」被误判为冲突而变成 A_1.apk
            guard.release(item.displayName)
            val target = guard.claim(compose(item, rule))
            target
        }
    }

    private fun valueOf(field: Field, item: ApkItem, meta: ApkMeta?, rule: Rule): String? = when (field) {
        Field.APP_NAME -> meta?.appName?.takeIf { it.isNotBlank() }
        Field.VERSION_NAME -> meta?.versionName?.takeIf { it.isNotBlank() }?.let {
            if (rule.versionPrefixV && !it.startsWith("v", ignoreCase = true)) "v$it" else it
        }
        Field.VERSION_CODE -> meta?.versionCode?.takeIf { it > 0L }?.let {
            if (rule.wrapVersionCode) "($it)" else it.toString()
        }
        Field.PACKAGE_NAME -> meta?.packageName?.takeIf { it.isNotBlank() }
        Field.CUSTOM_PREFIX -> rule.customPrefix.takeIf { it.isNotBlank() }
        Field.ORIGINAL_NAME -> item.baseName.takeIf { it.isNotBlank() }
        Field.ARCHIVE_TYPE -> item.extension.uppercase(Locale.ROOT)
        Field.DATE -> SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(item.lastModified))
        Field.SIZE -> item.size.takeIf { it > 0L }?.let { item.sizeLabel() }
        Field.MIN_SDK -> meta?.minSdk?.takeIf { it > 0 }?.let { "API $it" }
        Field.TARGET_SDK -> meta?.targetSdk?.takeIf { it > 0 }?.let { "API $it" }
    }

    /* ================================================================ */
    /* 清洗                                                              */
    /* ================================================================ */

    /**
     * 单个字段的清洗：剔除非法字符、控制字符。
     */
    fun sanitizeSegment(raw: String, rule: Rule): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch in ILLEGAL_SET -> sb.append(rule.illegalReplacement)
                ch.code < 0x20 || ch.code == 0x7F -> Unit          // 控制字符直接丢弃
                else -> sb.append(ch)
            }
        }
        var s = sb.toString()
        if (rule.trimSpaces) s = s.replace(MULTI_SPACE, " ").trim()
        return s
    }

    /** 整串收尾：合并重复分隔符、去掉首尾分隔符与句点 */
    private fun polish(input: String, rule: Rule): String {
        var s = input
        val sep = rule.separator
        if (rule.collapseSeparator && sep.isNotEmpty()) {
            val quoted = Regex.escape(sep)
            s = s.replace(Regex("(?:$quoted){2,}"), sep)
            s = s.trim(*sep.toCharArray())
        }
        s = s.trim()
        // 结尾的点和空格在 Windows / FAT 上会被静默截断
        s = s.trimEnd('.', ' ')
        // 开头的点会让文件变成隐藏文件
        s = s.trimStart('.', ' ')
        return s
    }

    /**
     * 按 UTF-8 **字节数**截断，且不切断多字节字符（中文文件名常见坑）。
     */
    fun truncateKeepingExtension(stem: String, ext: String, maxBytes: Int): String {
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        val budget = maxBytes - suffix.toByteArray(Charsets.UTF_8).size
        if (budget <= 0) {
            // 后缀本身已吃满预算：把「主干+后缀」当作整体做一次字节安全裁剪（不递归，保证收敛）
            val full = stem + suffix
            val fullBytes = full.toByteArray(Charsets.UTF_8)
            if (fullBytes.size <= maxBytes) return full
            var end = maxBytes
            while (end > 0 && (fullBytes[end].toInt() and 0xC0) == 0x80) end--
            val cut = String(fullBytes, 0, end, Charsets.UTF_8).trimEnd('.', ' ', '_', '-')
            return cut.ifEmpty { "unnamed" }.let { fallback ->
                if (fallback.toByteArray(Charsets.UTF_8).size <= maxBytes) fallback
                else fallback.take(maxOf(maxBytes, 1))   // maxBytes=0 时至少留 1 字符，避免空文件名
            }
        }

        val bytes = stem.toByteArray(Charsets.UTF_8)
        if (bytes.size <= budget) return stem + suffix

        var end = budget
        // 回退到完整字符边界：UTF-8 后续字节形如 10xxxxxx
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        val cut = String(bytes, 0, end, Charsets.UTF_8).trimEnd('.', ' ', '_', '-')
        return (cut.ifEmpty { "unnamed" }) + suffix
    }

    /* ================================================================ */
    /* 手动改名（§3.4 / R5）                                             */
    /* ================================================================ */

    /**
     * 清洗用户手动输入的文件名，返回**可直接落盘**的完整名（含扩展名）。
     *
     * ### 与 [compose] 的关键差异
     * 手动输入是用户的显式意图，因此**不做** [polish] 的那些「智能」处理：
     * - 连续分隔符 `my___app` 原样保留（用户就是想要三个下划线）；
     * - 不合并空白之外的任何字符。
     *
     * 保留的只有安全相关的清洗：
     * 1. 剔除 `\ / : * ? " < > |` 与控制字符（[sanitizeSegment]）；
     * 2. 去掉首尾的点与空格（结尾的点在 Windows/FAT 上被静默截断，开头的点会变隐藏文件）；
     * 3. 按 [Rule.lowercaseExtension] 规范扩展名大小写；
     * 4. 按 UTF-8 **字节数**截断，且不切碎多字节字符（中文名的经典坑）。
     *
     * @param input       用户原始输入，可含或不含扩展名
     * @param fallbackExt 输入未带扩展名时补上的扩展名（通常取原文件的扩展名）
     */
    fun sanitizeManualName(input: String, fallbackExt: String, rule: Rule): String {
        val parts = splitManualName(input, fallbackExt, rule)
        // 主干被清空时兜底为 unnamed，语义与 compose() 保持一致，避免产出 ".apk" 这种隐藏文件
        val stem = parts.stem.ifEmpty { "unnamed" }
        return truncateKeepingExtension(stem, parts.ext, rule.maxByteLength)
    }

    /**
     * 校验手动输入的文件名。
     *
     * 校验顺序固定为 [NameError] 的声明顺序，**不可调整**——
     * `NameComposerManualNameTest` 用四个用例把它钉死了。
     *
     * ### 冲突判定的两条语义
     * - **与自身同名不算冲突**：用户想把 `Self.apk` 改成 `SELF.apk`（只调大小写）
     *   必须放行，否则这个功能对大小写不敏感的文件系统毫无意义。
     * - **大小写不敏感且使用 [Locale.ROOT]**：FAT / exFAT 不区分大小写，
     *   漏判会直接覆盖别人的文件。用默认 Locale 则会踩土耳其语的无点 `ı` 陷阱
     *   （`"MYFILE".lowercase()` 在 tr-TR 下得到 `myfıle`，与 `myfile` 不等）。
     *
     * @param input        用户原始输入
     * @param originalName 该文件当前的名字，用于「与自身同名不算冲突」判定
     * @param siblingNames 同目录下其它文件的名字（当前名 + 已确定的目标名）
     */
    fun validateManualName(
        input: String,
        originalName: String,
        siblingNames: Collection<String>,
        rule: Rule
    ): NameValidation {
        // ① EMPTY —— 先于一切，空输入没有任何后续校验的意义
        if (input.isBlank()) {
            return NameValidation("", NameError.EMPTY, "文件名不能为空")
        }

        val fallbackExt = originalName.substringAfterLast('.', "apk")
        val parts = splitManualName(input, fallbackExt, rule)

        // ② ILLEGAL_ONLY —— 输入非空但清洗后主干为空，说明全是非法字符
        if (parts.stem.isEmpty()) {
            return NameValidation(
                sanitized = "",
                error = NameError.ILLEGAL_ONLY,
                message = "文件名不能只包含非法字符 ${ILLEGAL_CHARS.joinToString(" ")}"
            )
        }

        // 这里**不能**走 sanitizeManualName：它会先截断，导致 TOO_LONG 永远检测不到
        val candidate = if (parts.ext.isEmpty()) parts.stem else "${parts.stem}.${parts.ext}"

        // ③ RESERVED —— 优先于长度与冲突
        if (parts.stem.lowercase(Locale.ROOT) in RESERVED_NAMES) {
            return NameValidation(
                sanitized = candidate,
                error = NameError.RESERVED,
                message = "「${parts.stem}」是系统保留名，请换一个"
            )
        }

        // ④ TOO_LONG —— 按 UTF-8 字节而非字符数判定（1 个汉字 3 字节）
        val bytes = candidate.toByteArray(Charsets.UTF_8).size
        if (bytes > rule.maxByteLength) {
            return NameValidation(
                sanitized = candidate,
                error = NameError.TOO_LONG,
                message = "文件名 $bytes 字节，超出上限 ${rule.maxByteLength} 字节，请缩短"
            )
        }

        // ⑤ CONFLICT —— 最后判定；与自身同名放行
        val lower = candidate.lowercase(Locale.ROOT)
        if (lower != originalName.lowercase(Locale.ROOT) &&
            siblingNames.any { it.lowercase(Locale.ROOT) == lower }
        ) {
            return NameValidation(
                sanitized = candidate,   // 刻意不追加 _1，交给用户自己决定
                error = NameError.CONFLICT,
                message = "同目录下已存在「$candidate」，请换一个名字"
            )
        }

        return NameValidation(candidate, null, null)
    }

    /** [splitManualName] 的返回值：清洗后的主干与扩展名（均不含分隔的点） */
    private class ManualParts(val stem: String, val ext: String)

    /**
     * 把手动输入拆成「清洗后的主干 + 规范化的扩展名」。
     *
     * 拆分点取**最后一个**点，且要求它既不在开头也不在结尾：
     * - `MyApp` → 无点，扩展名回落到 [fallbackExt]；
     * - `.MyApp.apk` → 点在索引 6，主干 `.MyApp`（随后被 trim 掉前导点）、扩展名 `apk`；
     * - `.apk` → 点在索引 0，视为「整体都是主干」，避免把隐藏文件名误当成纯扩展名。
     *
     * 注意主干只经过 [sanitizeSegment] + 首尾修剪，**绝不**调用 `polish()`——
     * 后者会折叠连续分隔符，破坏用户的显式输入。
     */
    private fun splitManualName(input: String, fallbackExt: String, rule: Rule): ManualParts {
        val trimmed = input.trim()
        val dot = trimmed.lastIndexOf('.')

        val rawStem: String
        val rawExt: String
        if (dot > 0 && dot < trimmed.length - 1) {
            rawStem = trimmed.substring(0, dot)
            rawExt = trimmed.substring(dot + 1)
        } else {
            rawStem = trimmed
            rawExt = fallbackExt
        }

        val stem = sanitizeSegment(rawStem, rule)
            .trim()
            .trimEnd('.', ' ')
            .trimStart('.', ' ')

        var ext = sanitizeSegment(rawExt, rule).trim().trim('.', ' ')
        if (ext.isEmpty()) ext = sanitizeSegment(fallbackExt, rule).trim().trim('.', ' ')
        if (rule.lowercaseExtension) ext = ext.lowercase(Locale.ROOT)

        return ManualParts(stem, ext)
    }

    /* ================================================================ */
    /* 冲突防覆盖                                                        */
    /* ================================================================ */

    /**
     * 目标名占位登记表。大小写不敏感（FAT / exFAT 不区分大小写，
     * 若按大小写区分会在 U 盘上直接覆盖文件）。
     *
     * ```
     * AppName_v1.0.apk      // 首个
     * AppName_v1.0_1.apk    // 第二个同名
     * AppName_v1.0_2.apk    // 第三个同名
     * ```
     */
    class ConflictGuard(
        existing: Collection<String> = emptyList(),
        private val separator: String = "_"
    ) {
        private val used: MutableSet<String> = HashSet<String>().apply {
            existing.forEach { add(it.lowercase(Locale.ROOT)) }
        }

        /** 释放某个名字（用于文件自身重命名前让位） */
        fun release(name: String) {
            used.remove(name.lowercase(Locale.ROOT))
        }

        fun isTaken(name: String): Boolean = name.lowercase(Locale.ROOT) in used

        /** 占用一个名字；已被占用时自动追加 `_1`、`_2`… 直到空闲 */
        fun claim(desired: String): String {
            if (used.add(desired.lowercase(Locale.ROOT))) return desired

            val stem = desired.substringBeforeLast('.', desired)
            val ext = desired.substringAfterLast('.', "")
            val sep = separator.ifEmpty { "_" }

            var index = 1
            while (index <= MAX_TRY) {
                val candidate = if (ext.isEmpty()) "$stem$sep$index" else "$stem$sep$index.$ext"
                if (used.add(candidate.lowercase(Locale.ROOT))) return candidate
                index++
            }
            // 极端兜底：时间戳保证唯一
            val fallback = if (ext.isEmpty()) "$stem$sep${System.currentTimeMillis()}"
            else "$stem$sep${System.currentTimeMillis()}.$ext"
            used.add(fallback.lowercase(Locale.ROOT))
            return fallback
        }

        private companion object {
            const val MAX_TRY = 9999
        }
    }
}
