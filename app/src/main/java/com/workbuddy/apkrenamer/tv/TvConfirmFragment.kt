package com.workbuddy.apkrenamer.tv

import android.os.Bundle
import android.view.View
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.ViewModelProvider
import com.workbuddy.apkrenamer.R
import com.workbuddy.apkrenamer.vm.ApkListViewModel

/**
 * 执行重命名前的二次确认（Q5 / RQ-T7-7）。
 *
 * 与手机端的 `MaterialAlertDialog` 是**同一份语义**：都展示 `executableCount`
 * 与 `hiddenSelectedCount`，都只调 [ApkListViewModel.renameSelected]，
 * 执行范围的过滤条件在 ViewModel 里唯一定义（§7.4），此处不做任何筛选。
 */
class TvConfirmFragment : GuidedStepSupportFragment() {

    companion object {
        private const val ARG_EXECUTABLE = "arg_executable"
        private const val ARG_HIDDEN_SELECTED = "arg_hidden_selected"

        /** 取消。**必须**是 actions 列表的第 0 项，即默认焦点位（Q5：危险操作不做默认项） */
        private const val ACTION_CANCEL = 1L

        /** 确认执行 */
        private const val ACTION_CONFIRM = 2L

        /**
         * @param executableCount     本次实际会被重命名的文件数（选中 ∧ needsRename ∧ meta 有效）
         * @param hiddenSelectedCount 已选但被当前筛选隐藏、因而**不在**本次执行范围内的数量
         */
        fun newInstance(executableCount: Int, hiddenSelectedCount: Int): TvConfirmFragment =
            TvConfirmFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_EXECUTABLE, executableCount)
                    putInt(ARG_HIDDEN_SELECTED, hiddenSelectedCount)
                }
            }
    }

    private val vm: ApkListViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[ApkListViewModel::class.java]
    }

    private val executableCount: Int
        get() = arguments?.getInt(ARG_EXECUTABLE, 0) ?: 0

    private val hiddenSelectedCount: Int
        get() = arguments?.getInt(ARG_HIDDEN_SELECTED, 0) ?: 0

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val description = if (hiddenSelectedCount > 0) {
            getString(R.string.tv_confirm_desc_hidden, executableCount, hiddenSelectedCount)
        } else {
            getString(R.string.tv_confirm_desc, executableCount)
        }
        return GuidanceStylist.Guidance(
            getString(R.string.tv_confirm_title),
            description,
            getString(R.string.tv_settings_breadcrumb),
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        // 顺序即焦点顺序：[0] 取消 —— 误触 OK 时落在安全项上
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_CANCEL)
                .title(R.string.cancel)
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_CONFIRM)
                .title(R.string.tv_confirm_ok)
                .build()
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // leanback 默认也是 0，这里显式声明，防止后续有人调整 action 顺序后默认焦点悄悄落到「确认」上
        setSelectedActionPosition(0)
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ACTION_CONFIRM) {
            vm.renameSelected()
        }
        // 取消与确认都关闭本页：确认后的进度由 TvBrowseFragment 的标题区与进度条呈现
        finishGuidedStepSupportFragments()
    }
}
