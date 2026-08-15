package com.workbuddy.apkrenamer.vm

import com.workbuddy.apkrenamer.model.ApkItem

/*
 * ⚠️ 本文件**零 `android.*` import**（设计文档 R5 / 包纪律）。
 *
 * 这不是洁癖：状态模型一旦沾上 `android.net.Uri` / `@StringRes`，就必须靠 Robolectric
 * 才能跑单元测试。保持纯 Kotlin 之后，[ListProjection] 的分组、计数、执行范围判定
 * 全部可以在普通 JVM 测试里直接断言（见 `ListProjectionTest`）。
 *
 * 唯一的外部依赖是 [ApkItem] —— 它属于 `model` 包，本文件只是引用类型名，
 * 并未在此处 import 任何 `android.` 包。
 */

/* ==================================================================== */
/* 阶段                                                                  */
/* ==================================================================== */

/**
 * 流水线阶段。**恰好四个**，与 `TvBrowseFragment.statusTitleOf()` 的
 * 穷尽 `when` 一一对应；新增分支前必须同步修改该处，否则编译不过。
 */
enum class Phase {
    /** 空闲：可以接受任何用户操作 */
    IDLE,

    /** 正在遍历 SAF 目录树（progress = 已发现数量，progressMax 无意义） */
    SCANNING,

    /** 正在并发解析元数据（progress / progressMax 有效） */
    PARSING,

    /** 正在批量重命名（progress / progressMax 有效） */
    RENAMING
}

/* ==================================================================== */
/* 行状态                                                                */
/* ==================================================================== */

/**
 * 单行的展示状态。
 *
 * [label] 是**纯 Kotlin 字符串**而非 `@StringRes`——本文件不允许 `android.*` import，
 * 而且 TV 端 `ApkCardPresenter` 需要在没有 Context 的 diff 场景里直接读它。
 * 代价是这几个词不参与多语言，考虑到 P3 只交付中文界面，可接受（见 README 已知取舍）。
 */
enum class RowStatus(val label: String) {
    /** 已生成目标名且与原名不同 —— 本次执行的候选 */
    NEEDS_RENAME("待改名"),

    /** 已解析但目标名与原名一致，改了也是白改 */
    NO_CHANGE("无需改动"),

    /** 已扫描到，元数据尚未解析 */
    PENDING("待解析"),

    /** 本次会话内已成功改名 */
    DONE("已完成"),

    /** 解析失败或重命名失败，[ApkItem.detail] 里有原因 */
    FAILED("失败")
}

/* ==================================================================== */
/* 筛选                                                                  */
/* ==================================================================== */

/**
 * 列表筛选维度（手机端顶部 chip 组）。
 *
 * TV 端不提供筛选入口，恒为 [ALL]——所以 `state.rows` 在 TV 上等于全量，
 * `hiddenSelectedCount` 恒为 0，`TvConfirmFragment` 的「另有 N 个被隐藏」分支不会触发。
 *
 * 持久化注意：筛选只在**手机端**恢复（`MainActivity` 调 `restoreFilterPref`），
 * TV 端 ViewModel 永远从 [ALL] 起步，否则没有筛选入口的 TV 会被旧筛选锁住。
 */
enum class StatusFilter(val label: String) {
    ALL("全部"),
    NEEDS_RENAME("待改名"),
    NO_CHANGE("无需改动"),
    DONE("已完成"),
    FAILED("失败"),
    SELECTED("已选中"),
    /** 同包名 + 同版本号的重复文件（借鉴同类 APK 管理工具的 dedupe） */
    DUPLICATE("重复");

    companion object {
        /** 按持久化名称解析；未知 / null 回退默认 [ALL]。 */
        fun of(raw: String?): StatusFilter =
            values().firstOrNull { it.name == raw } ?: StatusFilter.ALL
    }
}

/* ==================================================================== */
/* 行模型                                                                */
/* ==================================================================== */

/**
 * 列表的一行。手机端 `ApkAdapter` 与 TV 端 `ApkCardPresenter` **共用同一个模型**。
 *
 * 刻意做成 `data class`：两端的 DiffUtil / DiffCallback 都靠 `==` 判断内容是否变化，
 * 只要新增字段就自动纳入比较，不会出现「加了字段忘了改 areContentsTheSame」的经典漏刷新。
 *
 * @param id       稳定标识 = [ApkItem.documentId]。重命名后 Uri 会变、documentId 不变，
 *                 因此选中态与手动名在改名前后都不会错位。
 * @param item     底层数据。目标名 [ApkItem.targetName] 已由 ViewModel 算好，
 *                 渲染层不得再调用 `NameComposer.compose()`（RQ-T7-6）。
 * @param selected 是否被勾选。选中集合是两端共享的唯一真源。
 * @param manual   目标名是否来自用户手动输入（§3.4），UI 上打「手动」角标。
 * @param status   由 [ListProjection.statusOf] 统一推导，渲染层不得自己 `when (item.state)`。
 */
data class ApkRow(
    val id: String,
    val item: ApkItem,
    val selected: Boolean = false,
    val manual: Boolean = false,
    val status: RowStatus = RowStatus.PENDING,
    /** 是否为重复包：与其它文件同包名 + 同版本号（由 [com.workbuddy.apkrenamer.vm.ListProjection.duplicateIdsOf] 判定） */
    val duplicate: Boolean = false,
    /** 已缓存的应用图标 PNG 绝对路径；null 表示无图标。仅用于展示，不含任何业务语义。 */
    val iconPath: String? = null
)

/* ==================================================================== */
/* 计数                                                                  */
/* ==================================================================== */

/**
 * 全量（未经筛选）的分状态计数，用于筛选 chip 上的角标与标题栏摘要。
 *
 * 注意与 [ListUiState.selectedCount] / [ListUiState.executableCount] 的区别：
 * 本结构统计的是**全部**条目，后两者统计的是**当前可见**范围。
 */
data class Counts(
    val total: Int = 0,
    val needsRename: Int = 0,
    val noChange: Int = 0,
    val pending: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val duplicates: Int = 0
) {
    /** 按筛选维度取对应计数，避免 UI 侧写一堆 `when` */
    fun of(filter: StatusFilter, selected: Int): Int = when (filter) {
        StatusFilter.ALL -> total
        StatusFilter.NEEDS_RENAME -> needsRename
        StatusFilter.NO_CHANGE -> noChange + pending
        StatusFilter.DONE -> done
        StatusFilter.FAILED -> failed
        StatusFilter.SELECTED -> selected
        StatusFilter.DUPLICATE -> duplicates
    }
}

/* ==================================================================== */
/* 状态                                                                  */
/* ==================================================================== */

/**
 * 手机端与 TV 端共享的**唯一** UI 状态快照。
 *
 * ### 可见性契约（重要）
 * [rows] 是**筛选后**的可见行；[counts] / [totalCount] 是**全量**统计。
 * 因此：
 * - [executableCount] = 可见 ∧ 选中 ∧ 待改名 ∧（元数据有效 ∨ 手动指定）
 * - [hiddenSelectedCount] = 已选中但被筛选挡在可见范围之外的数量
 *
 * 两者相加才是「用户以为自己选了多少」，二次确认弹窗必须同时展示，
 * 否则用户会以为筛选状态下点执行会处理全部已选项（§7.4）。
 *
 * @param treeUri 授权目录的 **字符串形式**。这里刻意不用 `android.net.Uri`——
 *                本文件零 `android.*` import；消费方需要 Uri 时自行 `Uri.parse()`。
 *                所有消费点（TV 只做 null 判断、手机只做展示）都不需要 Uri 的能力。
 */
data class ListUiState(
    val treeUri: String? = null,
    val rows: List<ApkRow> = emptyList(),
    val filter: StatusFilter = StatusFilter.ALL,
    /** 当前排序维度与方向（纯会话态，不持久化） */
    val sortKey: SortKey = SortKey.NAME,
    val sortDirection: SortDirection = SortDirection.ASC,
    val phase: Phase = Phase.IDLE,
    val progress: Int = 0,
    val progressMax: Int = 0,
    val statusText: String = "",
    val counts: Counts = Counts(),
    val totalCount: Int = 0,
    val selectedCount: Int = 0,
    val executableCount: Int = 0,
    val hiddenSelectedCount: Int = 0,
    val manualCount: Int = 0
) {
    /**
     * 是否有后台任务在跑。
     *
     * 计算属性而非字段：让「忙」的定义只有一处，不可能出现
     * `phase = RENAMING` 却 `busy = false` 的不一致状态。
     */
    val busy: Boolean get() = phase != Phase.IDLE

    /** 进度条是否可给出确定百分比（SCANNING 阶段总数未知，只能转圈） */
    val hasDeterminateProgress: Boolean get() = busy && progressMax > 0

    /** 是否已经授权过目录 */
    val hasFolder: Boolean get() = treeUri != null
}

/* ==================================================================== */
/* 一次性事件                                                            */
/* ==================================================================== */

/**
 * 一次性事件（Toast / 完成提示 / 降级提示）。
 *
 * 与 [ListUiState] 分开走 `SharedFlow`：这类事件**不能**参与状态重放，
 * 否则旋转屏幕会把「重命名完成」再弹一遍。
 *
 * ⚠️ **恰好三个子类**。`TvBrowseFragment.onEvent()` 用的是穷尽 `when`，
 * 新增子类会直接编译报错——这是有意为之的护栏，逼着新增事件时同步处理两端。
 */
sealed class UiEvent {

    /** 通用短提示 */
    data class Toast(val text: String) : UiEvent()

    /** 批量重命名收尾，携带成功 / 失败数 */
    data class RenameFinished(val success: Int, val failed: Int) : UiEvent()

    /** 设备没有实现 `ACTION_OPEN_DOCUMENT_TREE`，降级为提示而非崩溃（RQ-T7-8） */
    object PickerUnavailable : UiEvent()
}
