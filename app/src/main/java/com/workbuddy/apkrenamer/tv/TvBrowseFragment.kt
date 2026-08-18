package com.workbuddy.apkrenamer.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.RowPresenter
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.workbuddy.apkrenamer.R
import com.workbuddy.apkrenamer.vm.ApkListViewModel
import com.workbuddy.apkrenamer.vm.ApkRow
import com.workbuddy.apkrenamer.vm.ListProjection
import com.workbuddy.apkrenamer.vm.ListUiState
import com.workbuddy.apkrenamer.vm.Phase
import com.workbuddy.apkrenamer.vm.RowStatus
import com.workbuddy.apkrenamer.vm.UiEvent
import com.workbuddy.apkrenamer.ui.resolve
import kotlinx.coroutines.launch

/** 瞬时提示占用标题区的时长；到点后自动还原为状态标题 */
private const val TRANSIENT_TITLE_MS = 3000L

/** 行索引恒定（设计文档 §4.3 纪律 3）：行数固定 4，空行也保留，避免焦点乱跳 */
private const val ROW_ACTIONS = 0
private const val ROW_NEEDS_RENAME = 1
private const val ROW_NO_CHANGE = 2
private const val ROW_DONE_FAILED = 3

/**
 * 让行标题能显示实时计数「待改名 (N)」的 [ListRowPresenter]。
 *
 * 为什么需要它：leanback 的 `HeaderItem.getName()` 是 `final` 且**没有 setter**
 * （已对 leanback-1.0.0 的 class 反编译确认），想改行标题只能替换整个 `ListRow`。
 * 而 `ArrayObjectAdapter.replace()` → `ListRowPresenter.onBindRowViewHolder()` →
 * `HorizontalGridView.setAdapter()` → `RecyclerView.removeAndRecycleViews()`，
 * 会把该行所有卡片连同 D-pad 焦点一起清掉 —— 直接违反设计文档 §4.3 纪律 3「焦点稳定」。
 *
 * 所以这里走两条都不碰外层 ObjectAdapter 的路径：
 *  1. 行**首次绑定 / 回收后重绑**时，从 [titleOf] 取最新文案覆盖 HeaderItem 的静态占位文案；
 *  2. 行**已附着**时，[TvBrowseFragment.applyHeaderTitles] 直接改 RowHeaderView 的 text。
 *
 * 拿不到 TextView 时静默跳过：最坏结果只是标题停留在占位文案，不会崩。
 */
private class CountingListRowPresenter(
    private val titleOf: (Long) -> String?
) : ListRowPresenter() {

    override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any?) {
        super.onBindRowViewHolder(holder, item)
        val headerId = (item as? ListRow)?.headerItem?.id ?: return
        titleOf(headerId)?.let { applyHeaderText(holder, it) }
    }

    companion object {
        /** RowHeaderPresenter 默认布局 `lb_row_header` 的根就是 RowHeaderView（extends TextView） */
        fun applyHeaderText(holder: RowPresenter.ViewHolder, text: String) {
            (holder.headerViewHolder?.view as? TextView)?.text = text
        }
    }
}

/**
 * TV 主界面（RQ-T7-2 ~ RQ-T7-9、RQ-T7-14）。
 *
 * 职责边界严格限制为两件事：
 *  1. 收集 [ApkListViewModel.uiState] → 渲染 4 个固定行与标题；
 *  2. 把遥控器的 OK 事件翻译成 ViewModel 的方法调用。
 *
 * 本文件**不得**出现 `SafScanner.scan` / `MetaExtractor` / `NameComposer.compose` /
 * `ApkMetadataCache` 的直接调用（设计文档 §4.3 纪律 1 / RQ-T7-6）——
 * 所有业务编排都在 ViewModel 里，与手机端共用同一条代码路径。
 */
class TvBrowseFragment : BrowseSupportFragment() {

    private val vm: ApkListViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[ApkListViewModel::class.java]
    }

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var actionAdapter: ArrayObjectAdapter
    private lateinit var needsRenameAdapter: ArrayObjectAdapter
    private lateinit var noChangeAdapter: ArrayObjectAdapter
    private lateinit var doneFailedAdapter: ArrayObjectAdapter

    /**
     * 三个内容行的**最新**标题文案，按行索引存放（0 号操作行恒为静态文案，不参与）。
     * [CountingListRowPresenter] 重绑时读它，[applyHeaderTitles] 直接写 RowHeaderView 时也读它。
     */
    private val headerTitles = arrayOfNulls<String>(4)

    /** 最近一次渲染的状态。操作卡的可用性判定与点击兜底提示都读它 */
    private var latestState: ListUiState? = null

    /**
     * `SafScanner.isTreePickerAvailable()` 的尽力预检结果（§7.7）。
     * 只用于把「选择文件夹」卡片提前置灰，**不作为唯一判据**——
     * 真正的兜底是 `TvActivity.launchTreePicker()` 里的 try/catch。
     */
    private var pickerAvailable: Boolean = true

    /** 操作卡可用性的指纹，只有真的变了才 notify，避免每帧重绑一整行 */
    private var lastActionSignature: String = ""

    /** 三个内容行上一次的条目数，用于决定是否需要刷新 Header 文案 */
    private var lastCounts: Triple<Int, Int, Int> = Triple(-1, -1, -1)

    private val uiHandler = Handler(Looper.getMainLooper())
    private var transientTitleRunnable: Runnable? = null

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerAvailable = (activity as? TvActivity)?.isTreePickerAvailable() ?: true
        setupBrowseChrome()
        buildFixedRows()
        setupClickListener()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 默认 1000ms 延迟对 TV 偏长，缩短到 300ms 让用户更快看到「有反应」
        progressBarManager.initialDelay = 300L

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.uiState.collect(::render) }
                launch { vm.events.collect(::onEvent) }
            }
        }

        // bootstrap 幂等：ViewModel 自身会判断是否已初始化，旋转 / 重建不会重复扫描
        vm.bootstrap()
    }

    override fun onDestroyView() {
        transientTitleRunnable?.let(uiHandler::removeCallbacks)
        transientTitleRunnable = null
        super.onDestroyView()
    }

    /* ------------------------------------------------------------------ */
    /* 初始化                                                              */
    /* ------------------------------------------------------------------ */

    private fun setupBrowseChrome() {
        // 只有 4 行且语义固定，左侧 Header 导航栏反而会抢走 D-pad 左键，
        // 关掉之后行标题仍由 ListRowPresenter 显示在每行上方，信息不丢。
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        brandColor = ContextCompat.getColor(requireContext(), R.color.tv_bg)
        title = getString(R.string.tv_title_no_folder)
    }

    private fun buildFixedRows() {
        val ctx = requireContext()

        actionAdapter = ArrayObjectAdapter(ActionCardPresenter(::isActionEnabled)).apply {
            // 顺序即 TvAction 声明顺序：选择文件夹 / 重新扫描 / 生成预览 / 执行重命名 / 设置。
            // 这一行永不增删条目，可用性变化只走 notifyArrayItemRangeChanged 重绑。
            addAll(0, TvAction.values().toList())
        }
        needsRenameAdapter = ArrayObjectAdapter(ApkCardPresenter())
        noChangeAdapter = ArrayObjectAdapter(ApkCardPresenter())
        doneFailedAdapter = ArrayObjectAdapter(ApkCardPresenter())

        headerTitles[ROW_NEEDS_RENAME] = ctx.getString(R.string.tv_row_needs_rename, 0)
        headerTitles[ROW_NO_CHANGE] = ctx.getString(R.string.tv_row_no_change, 0)
        headerTitles[ROW_DONE_FAILED] = ctx.getString(R.string.tv_row_done_failed, 0)

        // HeaderItem 的 id 直接用行索引，CountingListRowPresenter 靠它回查 headerTitles
        val presenter = CountingListRowPresenter { id -> headerTitles.getOrNull(id.toInt()) }

        rowsAdapter = ArrayObjectAdapter(presenter).apply {
            add(
                ListRow(
                    HeaderItem(ROW_ACTIONS.toLong(), ctx.getString(R.string.tv_row_actions)),
                    actionAdapter
                )
            )
            add(
                ListRow(
                    HeaderItem(ROW_NEEDS_RENAME.toLong(), headerTitles[ROW_NEEDS_RENAME]!!),
                    needsRenameAdapter
                )
            )
            add(
                ListRow(
                    HeaderItem(ROW_NO_CHANGE.toLong(), headerTitles[ROW_NO_CHANGE]!!),
                    noChangeAdapter
                )
            )
            add(
                ListRow(
                    HeaderItem(ROW_DONE_FAILED.toLong(), headerTitles[ROW_DONE_FAILED]!!),
                    doneFailedAdapter
                )
            )
        }
        adapter = rowsAdapter
    }

    private fun setupClickListener() {
        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is TvAction -> onActionClicked(item)
                is ApkRow -> onRowClicked(item)
                else -> Unit
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 渲染                                                                */
    /* ------------------------------------------------------------------ */

    private fun render(state: ListUiState) {
        latestState = state

        renderTitle(state)
        renderProgress(state)
        renderRows(state)
        refreshActionAvailability()
    }

    private fun renderTitle(state: ListUiState) {
        // 瞬时提示占用标题区期间不覆盖，等它自己到点还原，避免提示一闪而过
        if (transientTitleRunnable != null) return
        title = statusTitleOf(state)
    }

    private fun statusTitleOf(state: ListUiState): String = when (state.phase) {
        Phase.SCANNING -> getString(R.string.tv_title_scanning, state.progress)
        Phase.PARSING -> getString(R.string.tv_title_parsing, state.progress, state.progressMax)
        Phase.RENAMING -> getString(R.string.tv_title_renaming, state.progress, state.progressMax)
        Phase.IDLE ->
            if (state.treeUri == null) {
                getString(R.string.tv_title_no_folder)
            } else {
                getString(
                    R.string.tv_title_idle,
                    state.totalCount,
                    state.counts.needsRename,
                    state.selectedCount
                )
            }
    }

    private fun renderProgress(state: ListUiState) {
        if (state.busy) progressBarManager.show() else progressBarManager.hide()
    }

    private fun renderRows(state: ListUiState) {
        // 分组唯一入口是 ListProjection（§7.3）；本文件不做任何 filter / sortedBy
        val grouped: Map<RowStatus, List<ApkRow>> = ListProjection.groupByStatus(state.rows)

        val needs = grouped[RowStatus.NEEDS_RENAME].orEmpty()
        // 「待解析」归入「无需改动」行：它既不待改也没完成，单独立行会让空行常驻
        val noChange = grouped[RowStatus.NO_CHANGE].orEmpty() + grouped[RowStatus.PENDING].orEmpty()
        val finished = grouped[RowStatus.DONE].orEmpty() + grouped[RowStatus.FAILED].orEmpty()

        needsRenameAdapter.setItems(needs, APK_ROW_DIFF)
        noChangeAdapter.setItems(noChange, APK_ROW_DIFF)
        doneFailedAdapter.setItems(finished, APK_ROW_DIFF)

        val counts = Triple(needs.size, noChange.size, finished.size)
        if (counts != lastCounts) {
            lastCounts = counts
            headerTitles[ROW_NEEDS_RENAME] = getString(R.string.tv_row_needs_rename, counts.first)
            headerTitles[ROW_NO_CHANGE] = getString(R.string.tv_row_no_change, counts.second)
            headerTitles[ROW_DONE_FAILED] = getString(R.string.tv_row_done_failed, counts.third)
            applyHeaderTitles()
        }
    }

    /**
     * 把 [headerTitles] 刷到**已附着**的行标题上。
     *
     * 刻意不走 `rowsAdapter.notifyArrayItemRangeChanged()`：那会触发
     * `ListRowPresenter.onBindRowViewHolder` → `HorizontalGridView.setAdapter()` →
     * `RecyclerView.removeAndRecycleViews()`，整行卡片被回收，焦点被打回行首。
     * 直接改 TextView 既不动适配器也不动布局层级，焦点纹丝不动。
     */
    private fun applyHeaderTitles() {
        val rows = rowsSupportFragment ?: return
        for (index in ROW_NEEDS_RENAME..ROW_DONE_FAILED) {
            val text = headerTitles[index] ?: continue
            val holder = rows.getRowViewHolder(index) ?: continue
            CountingListRowPresenter.applyHeaderText(holder, text)
        }
    }

    /* ------------------------------------------------------------------ */
    /* 操作卡可用性                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * 供 [ActionCardPresenter] 回调的可用性判定。
     *
     * 置灰只是**视觉提示**，卡片仍可聚焦可点击；点击时由 [onActionClicked] 给出
     * 具体不可用原因。TV 上直接摘掉焦点会让用户以为功能不存在。
     */
    private fun isActionEnabled(action: TvAction): Boolean {
        val state = latestState ?: return action == TvAction.PICK_FOLDER || action == TvAction.SETTINGS
        if (state.busy) return false
        return when (action) {
            TvAction.PICK_FOLDER -> pickerAvailable
            TvAction.RESCAN -> state.treeUri != null
            TvAction.PREVIEW -> state.totalCount > 0
            TvAction.EXECUTE -> state.executableCount > 0
            TvAction.SETTINGS -> true
        }
    }

    private fun refreshActionAvailability() {
        val signature = TvAction.values().joinToString(",") { if (isActionEnabled(it)) "1" else "0" }
        if (signature == lastActionSignature) return
        lastActionSignature = signature
        actionAdapter.notifyArrayItemRangeChanged(0, actionAdapter.size())
    }

    /* ------------------------------------------------------------------ */
    /* 交互                                                                */
    /* ------------------------------------------------------------------ */

    private fun onActionClicked(action: TvAction) {
        val state = latestState
        if (state != null && state.busy) {
            showTransientTitle(getString(R.string.tv_hint_busy))
            return
        }
        when (action) {
            TvAction.PICK_FOLDER -> (activity as? TvActivity)?.launchTreePicker()

            TvAction.RESCAN ->
                if (state?.treeUri == null) {
                    showTransientTitle(getString(R.string.tv_hint_no_folder))
                } else {
                    vm.scan()
                }

            TvAction.PREVIEW ->
                if (state == null || state.totalCount == 0) {
                    showTransientTitle(getString(R.string.tv_hint_no_items))
                } else {
                    // 与手机端「生成预览」完全同一个入口，不在 TV 侧另写一份规则应用逻辑
                    vm.applyRule(autoSelect = true, showToast = false)
                }

            TvAction.EXECUTE -> showConfirmOrHint(state)

            TvAction.SETTINGS -> GuidedStepSupportFragment.add(
                parentFragmentManager, TvSettingsFragment(), R.id.tv_root
            )
        }
    }

    private fun showConfirmOrHint(state: ListUiState?) {
        val executable = state?.executableCount ?: 0
        if (executable == 0) {
            showTransientTitle(getString(R.string.tv_hint_nothing_to_rename))
            return
        }
        GuidedStepSupportFragment.add(
            parentFragmentManager,
            TvConfirmFragment.newInstance(executable, state?.hiddenSelectedCount ?: 0),
            R.id.tv_root
        )
    }

    /** RQ-T7-14：OK 键切换选中，与手机端点击行的语义完全一致（同一个 selectedIds） */
    private fun onRowClicked(row: ApkRow) {
        val state = latestState
        if (state != null && state.busy) {
            showTransientTitle(getString(R.string.tv_hint_busy))
            return
        }
        vm.toggleSelection(row.id)
    }

    private fun onEvent(event: UiEvent) {
        when (event) {
            is UiEvent.Toast ->
                Toast.makeText(requireContext(), event.text.resolve(requireContext()), Toast.LENGTH_SHORT).show()

            is UiEvent.RenameFinished ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.tv_rename_finished, event.success, event.failed),
                    Toast.LENGTH_LONG
                ).show()

            UiEvent.PickerUnavailable ->
                showTransientTitle(getString(R.string.tv_picker_unavailable))
        }
    }

    /* ------------------------------------------------------------------ */
    /* 标题区瞬时提示                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * 在标题区显示一条瞬时提示，[TRANSIENT_TITLE_MS] 后自动还原为状态标题。
     *
     * TV 上不用 Dialog 是因为它会抢走焦点、打断用户当前的 D-pad 位置；
     * 标题区常驻可见且不参与焦点链，是 leanback 惯用的轻提示位。
     */
    fun showTransientTitle(text: String) {
        transientTitleRunnable?.let(uiHandler::removeCallbacks)
        title = text
        val runnable = Runnable {
            transientTitleRunnable = null
            if (isAdded) {
                title = latestState?.let(::statusTitleOf) ?: getString(R.string.tv_title_no_folder)
            }
        }
        transientTitleRunnable = runnable
        uiHandler.postDelayed(runnable, TRANSIENT_TITLE_MS)
    }
}
