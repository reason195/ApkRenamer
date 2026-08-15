package com.workbuddy.apkrenamer.vm

import com.workbuddy.apkrenamer.model.ApkItem
import com.workbuddy.apkrenamer.model.ItemState
import com.workbuddy.apkrenamer.model.needsRename
import java.util.Locale

/* ==================================================================== */
/* 排序维度                                                            */
/* ==================================================================== */

/**
 * 列表排序维度。
 *
 * 默认 [NAME]：与 `SafScanner` 的扫描顺序（相对路径 + 文件名）完全一致，
 * 保证「从未碰过排序」的用户看到的列表和旧版一模一样。
 */
enum class SortKey(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    TIME("时间"),
    STATUS("状态"),
    VERSION("版本");

    companion object {
        /** 按持久化名称解析；未知 / null 回退默认 [NAME]。 */
        fun of(raw: String?): SortKey =
            values().firstOrNull { it.name == raw } ?: SortKey.NAME
    }
}

/** 排序方向。 */
enum class SortDirection {
    ASC, DESC;

    companion object {
        /** 按持久化名称解析；未知 / null 回退默认 [ASC]。 */
        fun of(raw: String?): SortDirection =
            values().firstOrNull { it.name == raw } ?: SortDirection.ASC
    }
}

/**
 * 自然版本号比较器：`1.9` 排在 `1.10` 之前（字符串序会排反）。
 *
 * 规则：
 * - 先剥掉前导 `v`/`V`（`v1.2.3` 与 `1.2.3` 视为相等）；
 * - 按非字母数字切成段，数字段按数值比较、字母段忽略大小写比较；
 * - 段相等时取较短者优先（`1.0` < `1.0.1`）；
 * - **空版本排最后**（未解析 / 失败的文件沉底，不干扰版本顺序）。
 */
fun compareVersions(a: String, b: String): Int {
    val aBlank = a.isBlank()
    val bBlank = b.isBlank()
    if (aBlank && bBlank) return 0
    if (aBlank) return 1
    if (bBlank) return -1

    val aTokens = tokenize(a)
    val bTokens = tokenize(b)
    val n = minOf(aTokens.size, bTokens.size)
    for (i in 0 until n) {
        val aNum = aTokens[i].toLongOrNull()
        val bNum = bTokens[i].toLongOrNull()
        val c = when {
            aNum != null && bNum != null -> aNum.compareTo(bNum)
            aNum != null -> -1          // 数字段 < 字母段：1.2 < 1.2beta
            bNum != null -> 1
            else -> aTokens[i].compareTo(bTokens[i], ignoreCase = true)
        }
        if (c != 0) return c
    }
    return aTokens.size.compareTo(bTokens.size)
}

private fun tokenize(version: String): List<String> {
    val stripped = version.trim()
        .removePrefix("v")
        .removePrefix("V")
    return stripped.split(NON_ALNUM).filter { it.isNotEmpty() }
}

private val NON_ALNUM = Regex("[^0-9A-Za-z]+")

/**
 * 列表投影：把「原始数据 + 选中集合 + 手动名集合」映射成可直接渲染的 [ApkRow] 列表。
 *
 * ⚠️ 本文件同样**零 `android.*` import**（R5 / 包纪律），因此可以在普通 JVM 测试里
 * 直接断言分组、计数与执行范围，不需要 Robolectric。
 *
 * ### 为什么要单独抽一层
 * 手机端（`ApkAdapter`）与 TV 端（`TvBrowseFragment`）都需要「按状态分组」「算计数」
 * 「判断哪些行会被真正执行」。这些规则一旦在两端各写一份，必然漂移——
 * 典型症状是手机上显示「将改名 8 个」、TV 上却只改了 6 个。
 *
 * 所以这里是**唯一入口**：
 * - 渲染层不得自己写 `when (item.state)` → 用 [statusOf]
 * - 渲染层不得自己写 `filter { it.selected && ... }` → 用 [isExecutable]
 * - 渲染层不得自己写 `groupBy` / `sortedBy` → 用 [groupByStatus]
 */
object ListProjection {

    /* ================================================================ */
    /* 状态推导                                                          */
    /* ================================================================ */

    /**
     * [ItemState]（数据层的处理状态）→ [RowStatus]（展示层的分组状态）。
     *
     * 唯一有判断的分支是 [ItemState.PARSED]：解析成功之后还要看目标名是否真的
     * 与原名不同，才能区分「待改名」与「无需改动」。
     * `SKIPPED` 是 `applyRule` 已经判定过同名的情形，直接归入「无需改动」。
     */
    fun statusOf(item: ApkItem): RowStatus = when (item.state) {
        ItemState.PENDING -> RowStatus.PENDING
        ItemState.FAILED -> RowStatus.FAILED
        ItemState.RENAMED -> RowStatus.DONE
        ItemState.SKIPPED -> RowStatus.NO_CHANGE
        ItemState.PARSED -> if (item.needsRename) RowStatus.NEEDS_RENAME else RowStatus.NO_CHANGE
    }

    /* ================================================================ */
    /* 构建                                                              */
    /* ================================================================ */

    /**
     * 组装可见行之前的全量行列表。
     *
     * @param items       扫描 + 解析 + 应用规则之后的数据（顺序即展示顺序，由 `SafScanner`
     *                    按 `relativePath` / `displayName` 排好，这里**不再重排**）
     * @param selectedIds 选中的 `documentId` 集合
     * @param manualIds   目标名来自用户手动输入的 `documentId` 集合
     */
    fun buildRows(
        items: List<ApkItem>,
        selectedIds: Set<String> = emptySet(),
        manualIds: Set<String> = emptySet()
    ): List<ApkRow> = items.map { item ->
        ApkRow(
            id = item.documentId,
            item = item,
            selected = item.documentId in selectedIds,
            manual = item.documentId in manualIds,
            status = statusOf(item)
        )
    }

    /* ================================================================ */
    /* 分组与筛选                                                        */
    /* ================================================================ */

    /**
     * 按 [RowStatus] 分组。TV 端三个内容行的唯一数据来源（§7.3）。
     *
     * 使用 `groupBy` 而非手写循环：Kotlin 保证组内保持原始遍历顺序，
     * 因此列表顺序稳定，遥控器焦点不会因为重排而乱跳。
     *
     * 返回的 Map **不保证包含全部枚举键**，调用方请用 `?.orEmpty()`。
     */
    fun groupByStatus(rows: List<ApkRow>): Map<RowStatus, List<ApkRow>> =
        rows.groupBy { it.status }

    /** 按筛选维度取可见行。[StatusFilter.NO_CHANGE] 合并「待解析」，与 TV 端行语义保持一致。 */
    fun applyFilter(rows: List<ApkRow>, filter: StatusFilter): List<ApkRow> = when (filter) {
        StatusFilter.ALL -> rows
        StatusFilter.NEEDS_RENAME -> rows.filter { it.status == RowStatus.NEEDS_RENAME }
        StatusFilter.NO_CHANGE ->
            rows.filter { it.status == RowStatus.NO_CHANGE || it.status == RowStatus.PENDING }
        StatusFilter.DONE -> rows.filter { it.status == RowStatus.DONE }
        StatusFilter.FAILED -> rows.filter { it.status == RowStatus.FAILED }
        StatusFilter.SELECTED -> rows.filter { it.selected }
        StatusFilter.DUPLICATE -> rows.filter { it.duplicate }
    }

    /** 全量分状态计数。一次遍历算完，避免为每个 chip 各扫一遍列表。 */
    fun countsOf(rows: List<ApkRow>): Counts {
        var needsRename = 0
        var noChange = 0
        var pending = 0
        var done = 0
        var failed = 0
        var duplicates = 0
        for (row in rows) {
            when (row.status) {
                RowStatus.NEEDS_RENAME -> needsRename++
                RowStatus.NO_CHANGE -> noChange++
                RowStatus.PENDING -> pending++
                RowStatus.DONE -> done++
                RowStatus.FAILED -> failed++
            }
            if (row.duplicate) duplicates++
        }
        return Counts(
            total = rows.size,
            needsRename = needsRename,
            noChange = noChange,
            pending = pending,
            done = done,
            failed = failed,
            duplicates = duplicates
        )
    }

    /* ================================================================ */
    /* 排序                                                              */
    /* ================================================================ */

    /**
     * 按 [key] 与 [direction] 排序可见行。
     *
     * - **稳定排序**：Kotlin `sortedWith` 是稳定的，相等元素保持输入（扫描）顺序；
     *   另加 `documentId` 兜底，结果在两次刷新之间确定，不会跳行；
     * - [SortKey.NAME] 用「相对路径 + 文件名」——与 `SafScanner` 的扫描顺序一致，
     *   作为默认维度时列表与旧版完全相同（同目录文件保持相邻）；
     * - [SortKey.VERSION] 用 [compareVersions] 做自然比较，空版本排最后。
     */
    fun sortRows(
        rows: List<ApkRow>,
        key: SortKey,
        direction: SortDirection
    ): List<ApkRow> {
        if (rows.isEmpty()) return rows
        val base: Comparator<ApkRow> = when (key) {
            SortKey.NAME -> compareBy<ApkRow> { it.item.relativePath }
                .thenBy { it.item.displayName.lowercase(Locale.ROOT) }
            SortKey.SIZE -> compareBy<ApkRow> { it.item.size }
            SortKey.TIME -> compareBy<ApkRow> { it.item.lastModified }
            SortKey.STATUS -> compareBy<ApkRow> { it.status }
            SortKey.VERSION -> Comparator<ApkRow> { x, y ->
                compareVersions(
                    x.item.meta?.versionName.orEmpty(),
                    y.item.meta?.versionName.orEmpty()
                )
            }.thenBy { it.item.displayName.lowercase(Locale.ROOT) }
        }
        val comparator = base.thenBy { it.id }
        return rows.sortedWith(
            if (direction == SortDirection.ASC) comparator else comparator.reversed()
        )
    }

    /* ================================================================ */
    /* 重复包检测（dedupe）                                              */
    /* ================================================================ */

    /**
     * 找出内容重复的包：**同 packageName + 同 versionCode** 视为同一应用的同一版本。
     *
     * - 组内**全部成员**（不止后续者）都标记，方便用户对比后自行决定留哪个；
     * - 未解析 / 解析失败的文件无法判定，不参与；
     * - 与文件大小、归档格式（apk / xapk / apks）无关——同包同版本即是重复。
     */
    fun duplicateIdsOf(items: List<ApkItem>): Set<String> {
        val byKey = HashMap<String, MutableList<String>>()
        for (item in items) {
            val meta = item.meta ?: continue
            if (!meta.isValid) continue
            val key = "${meta.packageName}|${meta.versionCode}"
            byKey.getOrPut(key) { ArrayList() }.add(item.documentId)
        }
        val duplicates = HashSet<String>()
        for (ids in byKey.values) {
            if (ids.size > 1) duplicates.addAll(ids)
        }
        return duplicates
    }

    /* ================================================================ */
    /* 执行范围（§7.4：唯一定义）                                        */
    /* ================================================================ */

    /**
     * 该行是否会被本次「执行重命名」真正处理。
     *
     * 三个条件缺一不可：
     *  1. **被选中** —— 用户的显式意图；
     *  2. **待改名** —— 目标名非空且与原名不同，否则改了也是白改；
     *  3. **有可信目标名** —— 元数据解析成功，**或者**用户手动指定了名字。
     *
     * 第 3 条的「∨ 手动指定」是对 §7.4 的必要补充：手动改名（§3.4）存在的意义
     * 正是救回那些解析失败、自动规则算不出名字的包；若仍要求 `meta.isValid`，
     * 手动名对这类文件永远无法落地，功能等于摆设。
     *
     * 手机端弹窗与 TV 端 `TvConfirmFragment` 都只认这一个判据。
     */
    fun isExecutable(row: ApkRow): Boolean =
        row.selected &&
            row.item.needsRename &&
            (row.item.meta?.isValid == true || row.manual)

    /**
     * 可见行中会被执行的部分，保持原始顺序。
     *
     * **顺序即执行顺序**：`NameComposer.composeAll` 的时序约束要求同目录内按返回顺序
     * 依次改名，否则链式目标（A→C 且另有一项目标为 A）会瞬时撞名。
     */
    fun executableRows(rows: List<ApkRow>): List<ApkRow> = rows.filter(::isExecutable)

    /* ================================================================ */
    /* 手动改名的兄弟名集合                                              */
    /* ================================================================ */

    /**
     * 取 [id] 所在目录下**其它**文件的当前名 + 已确定的目标名，供
     * `NameComposer.validateManualName` 做冲突检测。
     *
     * 为什么要连目标名一起收：用户先给 A 手动改成 `X.apk`，再给 B 也输入 `X.apk`，
     * 若只比对当前名（`a.apk` / `b.apk`）就检测不出来，执行时后者会覆盖前者。
     *
     * 大小写不敏感的比较交给 `validateManualName` 内部用 `Locale.ROOT` 处理，
     * 这里只负责把候选名收全，并按 `Locale.ROOT` 去重以缩小集合。
     */
    fun siblingNamesOf(items: List<ApkItem>, id: String): List<String> {
        val self = items.firstOrNull { it.documentId == id } ?: return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (item in items) {
            if (item.documentId == id) continue
            if (item.relativePath != self.relativePath) continue
            for (candidate in listOfNotNull(item.displayName, item.targetName)) {
                if (candidate.isBlank()) continue
                if (seen.add(candidate.lowercase(Locale.ROOT))) result += candidate
            }
        }
        return result
    }
}
