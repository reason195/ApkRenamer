package com.workbuddy.apkrenamer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.workbuddy.apkrenamer.databinding.ActivityMainBinding
import com.workbuddy.apkrenamer.ui.ApkAdapter
import com.workbuddy.apkrenamer.ui.applySystemBarInsets
import com.workbuddy.apkrenamer.ui.enableEdgeToEdgeCompat
import com.workbuddy.apkrenamer.ui.showTargetNameEditor
import com.workbuddy.apkrenamer.core.SafScanner
import com.workbuddy.apkrenamer.vm.ApkListViewModel
import com.workbuddy.apkrenamer.vm.ListProjection
import com.workbuddy.apkrenamer.vm.ListUiState
import com.workbuddy.apkrenamer.vm.StatusFilter
import com.workbuddy.apkrenamer.vm.UiEvent
import kotlinx.coroutines.launch

/**
 * 主界面（A3 重写）：只做「选目录 → 扫描 → 预览 → 执行」的编排与渲染，
 * 所有业务逻辑下沉到 [ApkListViewModel]。
 *
 * ### 与 ViewModel 的关系
 * - 单例式业务状态（扫描结果、选中、手动名、阶段）全部在 VM 里，本页只 `collect` 两个流：
 *   [ApkListViewModel.uiState]（可重放的快照）与 [ApkListViewModel.events]（一次性事件）；
 * - 任何用户操作都只是调用 VM 的一个方法，绝不自己扫描 / 解析 / 拼名字 ——
 *   否则手机端与 TV 端（[com.workbuddy.apkrenamer.tv.TvActivity]）行为会漂移（RQ-T7-6）。
 *
 * 耗时工作全部在 VM 的 `viewModelScope` 中执行，旋转屏幕不会中断，协程随 VM 销毁自动取消。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: ApkListViewModel

    private val adapter by lazy {
        ApkAdapter(
            onToggle = { vm.toggleSelection(it) },
            onLongPress = { showTargetNameEditor(this, vm, it) }
        )
    }

    /** 状态筛选 chip，按 [StatusFilter] 顺序固定，便于更新角标计数 */
    private val chipViews = LinkedHashMap<StatusFilter, Chip>()

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            // OTG 记忆：授权范围内藏有上次用过的文件夹时，先问用户要不要恢复
            val remembered = vm.rememberedFolderWithin(uri)
            if (remembered != null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.otg_restore_title)
                    .setMessage(
                        getString(R.string.otg_restore_msg, SafScanner.displayPathOf(remembered))
                    )
                    .setNegativeButton(R.string.otg_use_current) { _, _ -> vm.onTreeGranted(uri) }
                    .setPositiveButton(R.string.otg_restore) { _, _ -> vm.onTreeGranted(uri, remembered) }
                    .show()
            } else {
                vm.onTreeGranted(uri)
            }
        }

    /* ================================================================ */
    /* 生命周期                                                          */
    /* ================================================================ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeCompat()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // ViewModel 必须是 AndroidViewModel：需要 Application 级 Context 给 SafScanner / 缓存
        vm = ViewModelProvider(this)[ApkListViewModel::class.java]

        // edge-to-edge：顶栏吃状态栏、底部条吃导航栏，中间的 RecyclerView 被两者夹住无需处理
        binding.toolbar.applySystemBarInsets(top = true, sides = true)
        binding.bottomBar.applySystemBarInsets(bottom = true, sides = true)

        setupToolbar()
        setupRecycler()
        setupChips()
        setupListeners()

        // 恢复上次的筛选维度（排序在 VM 构造时已恢复；筛选只能在手机端恢复，TV 保持 ALL）
        vm.restoreFilterPref()
        vm.bootstrap()
        observe()
    }

    /** 从设置页返回后按最新规则即时重算预览（保留已有的勾选与手动名） */
    override fun onResume() {
        super.onResume()
        vm.refreshRuleOnResume()
    }

    override fun onStop() {
        super.onStop()
        vm.flushCache()
    }

    /* ================================================================ */
    /* 视图装配                                                          */
    /* ================================================================ */

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.inflateMenu(R.menu.list_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_toggle_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_clear_cache -> {
                    vm.clearCache()
                    true
                }
                R.id.action_clear_visible -> {
                    vm.clearVisibleSelection()
                    true
                }
                R.id.action_clear_all -> {
                    vm.clearAllSelection()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecycler() {
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
    }

    private fun setupChips() {
        for (filter in StatusFilter.values()) {
            val chip = Chip(this).apply {
                text = filter.label
                isCheckable = true
                setOnCheckedChangeListener { _, checked ->
                    if (checked) vm.setFilter(filter)
                }
            }
            chipViews[filter] = chip
            binding.chipGroup.addView(chip)
        }
        // 默认选中「全部」
        chipViews[StatusFilter.ALL]?.isChecked = true
    }

    private fun setupListeners() {
        // 初始位置传上次授权目录（EXTRA_INITIAL_URI），省去每次手动翻路径；从未授权时回落系统默认位置
        binding.btnPickFolder.setOnClickListener { folderPicker.launch(vm.pickerInitialUri()) }
        binding.btnRescan.setOnClickListener {
            if (vm.uiState.value.hasFolder) vm.scan() else folderPicker.launch(vm.pickerInitialUri())
        }
        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnExecute.setOnClickListener { confirmAndRename() }
    }

    /**
     * 底部「全选 / 取消全选」按钮：按当前筛选后的可见视图切换。
     * 默认筛选为「全部」，因此默认即为全选 / 取消全部；若用户切到某个 chip 筛选，
     * 则只作用于该可见子集，与用户所见一致（避免误伤被隐藏的选中项）。
     */
    private fun toggleSelectAll() {
        val rows = vm.uiState.value.rows
        if (rows.isEmpty()) return
        val visibleSelected = rows.count { it.selected }
        if (visibleSelected == rows.size) vm.clearVisibleSelection() else vm.selectAllVisible()
    }

    /* ================================================================ */
    /* 状态订阅                                                          */
    /* ================================================================ */

    private fun observe() {
        lifecycleScope.launchWhenStarted {
            launch { vm.uiState.collect(::render) }
            launch { vm.events.collect(::onEvent) }
        }
    }

    private fun render(state: ListUiState) {
        binding.tvFolder.text = if (state.hasFolder) {
            "已授权：${Uri.decode(state.treeUri)}"
        } else {
            getString(R.string.no_folder)
        }
        binding.tvStatus.text = state.statusText
        // ListAdapter 内部做 DiffUtil，绝不使用 notifyDataSetChanged()
        adapter.submitList(state.rows)
        updateProgress(state)
        updateChips(state)
        binding.tvSelection.text =
            getString(R.string.selection_summary, state.selectedCount, state.executableCount)
        val allSel = state.rows.isNotEmpty() && state.rows.all { it.selected }
        binding.btnSelectAll.text = getString(if (allSel) R.string.deselect_all else R.string.select_all)
        binding.btnSelectAll.isEnabled = state.rows.isNotEmpty()
        setBusy(state.busy)
    }

    private fun updateProgress(state: ListUiState) {
        if (state.busy) {
            binding.progress.visibility = View.VISIBLE
            if (state.hasDeterminateProgress) {
                binding.progress.isIndeterminate = false
                binding.progress.max = state.progressMax
                binding.progress.setProgressCompat(state.progress, true)
            } else {
                binding.progress.isIndeterminate = true
            }
        } else {
            binding.progress.visibility = View.GONE
        }
    }

    private fun updateChips(state: ListUiState) {
        chipViews.forEach { (filter, chip) ->
            val count = state.counts.of(filter, state.selectedCount)
            chip.text = "${filter.label} $count"
        }
    }

    private fun onEvent(event: UiEvent) {
        when (event) {
            is UiEvent.Toast -> toast(event.text)
            is UiEvent.RenameFinished -> toast("完成：成功 ${event.success}，失败 ${event.failed}")
            is UiEvent.PickerUnavailable -> toast(getString(R.string.picker_unavailable))
        }
    }

    /* ================================================================ */
    /* 执行确认                                                          */
    /* ================================================================ */

    private fun confirmAndRename() {
        val state = vm.uiState.value
        // 执行范围与 ViewModel.renameSelected() 一致：筛选后 ∧ 可执行
        val exec = ListProjection.executableRows(ListProjection.applyFilter(state.rows, state.filter))
        if (exec.isEmpty()) {
            toast("没有需要重命名的文件（都已无需改动或未解析）")
            return
        }
        val first = exec.first()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_rename_title)
            .setMessage(
                "将对 ${exec.size} 个文件执行重命名，操作不可撤销。\n\n示例：\n" +
                    "${first.item.displayName}\n→ ${first.item.targetName}"
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.execute) { _, _ -> vm.renameSelected() }
            .show()
    }

    /* ================================================================ */
    /* 小工具                                                            */
    /* ================================================================ */

    private fun setBusy(busy: Boolean) {
        binding.btnPickFolder.isEnabled = !busy
        binding.btnRescan.isEnabled = !busy
        binding.btnSelectAll.isEnabled = !busy
        binding.btnExecute.isEnabled = !busy
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
