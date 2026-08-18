package com.workbuddy.apkrenamer.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.workbuddy.apkrenamer.R
import com.workbuddy.apkrenamer.databinding.DialogManualNameBinding
import com.workbuddy.apkrenamer.vm.ApkListViewModel

/**
 * 手动改名对话框（§3.4 / R5）。
 *
 * 设计要点：
 * - 输入时**实时**调用 [ApkListViewModel.validateManualName] 给出错误文案（非法字符 / 保留名 /
 *   超长 / 冲突），校验不通过时「应用」按钮置灰且不关闭对话框 —— 用户不会在「点了应用才发现
 *   名字不行」时才被拦下；
 * - 确认时调用 [ApkListViewModel.setManualTarget] 落地（它内部会重新跑一次规则预览，
 *   把该行接入冲突消解）；`neutral` 按钮用于撤销已设的手动名；
 * - 校验规则全部在 [com.workbuddy.apkrenamer.core.NameComposer] 里，本对话框只负责把
 *   ViewModel 返回的 [com.workbuddy.apkrenamer.core.NameValidation] 翻译成 UI，自己不判断任何规则。
 *
 * @param id 目标行对应的 `documentId`
 */
fun showTargetNameEditor(context: Context, vm: ApkListViewModel, id: String) {
    val binding = DialogManualNameBinding.inflate(LayoutInflater.from(context))
    val ext = vm.defaultExtensionOf(id)
    binding.tvHint.text = context.getString(R.string.manual_rename_hint, ext)

    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.manual_rename_title)
        .setView(binding.root)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.manual_rename_ok, null)
        .setNeutralButton(R.string.manual_rename_clear, null)
        .create()

    /** 重新校验当前输入并刷新错误文案 + 按钮可用性 */
    fun refresh(input: String) {
        val v = vm.validateManualName(id, input)
        binding.tvError.text = v.localizedMessage(context).orEmpty()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = v.isValid
    }

    binding.etName.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = refresh(s?.toString().orEmpty())
    })

    dialog.setOnShowListener {
        // 初次展示时先校验一次（可能已有手动名）
        refresh(binding.etName.text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val result = vm.setManualTarget(id, binding.etName.text.toString())
            if (result.isValid) dialog.dismiss()
            else binding.tvError.text = result.localizedMessage(context).orEmpty()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            vm.clearManualTarget(id)
            dialog.dismiss()
        }
    }

    dialog.show()
}
