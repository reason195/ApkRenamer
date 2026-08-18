package com.workbuddy.apkrenamer.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.apkrenamer.R
import com.workbuddy.apkrenamer.databinding.ItemApkBinding
import com.workbuddy.apkrenamer.model.ItemState
import com.workbuddy.apkrenamer.model.archiveType
import com.workbuddy.apkrenamer.model.readableSize
import com.workbuddy.apkrenamer.vm.ApkRow
import com.workbuddy.apkrenamer.vm.RowStatus

/**
 * 重命名预览列表（A3 重写）。
 *
 * ### 与 ViewModel 的契约
 * - 数据模型从 [ApkItem] 换成 [ApkRow]：选中态、手动标记、展示状态都已由
 *   [com.workbuddy.apkrenamer.vm.ListProjection] 算好，渲染层不再自己 `when (item.state)`。
 * - 渲染层**绝不**调用 `NameComposer.compose()` / `SafScanner.scan()` —— 名字在 VM 里算好，
 *   `row.item.targetName` 直接拿来显示（RQ-T7-6）。
 *
 * ### DiffUtil + payload（纪律：禁止 `notifyDataSetChanged()`）
 * - `areItemsTheSame` 用 `id`（= documentId），改名后 Uri 变、id 不变，不会错位；
 * - `areContentsTheSame` 用 `==`：因为 [ApkRow] 是 `data class`，新增字段自动纳入比较；
 * - 仅选中态变化时 `getChangePayload` 返回 [PAYLOAD_SELECTION]，[onBindViewHolder] 只刷新
 *   那个 CheckBox，避免整行重绘导致列表抖动。
 */
class ApkAdapter(
    private val onToggle: (String) -> Unit,
    private val onLongPress: (String) -> Unit
) : ListAdapter<ApkRow, ApkAdapter.VH>(DIFF) {

    class VH(val binding: ItemApkBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemApkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        bind(holder, getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            // 仅选中态变化：最小刷新，不重建整行
            holder.binding.cbSelect.isChecked = getItem(position).selected
        } else {
            bind(holder, getItem(position))
        }
    }

    private fun bind(holder: VH, row: ApkRow) {
        val item = row.item
        val b = holder.binding
        val ctx = b.root.context

        b.cbSelect.isChecked = row.selected
        b.tvManual.visibility = if (row.manual) View.VISIBLE else View.GONE
        b.tvBadge.text = item.archiveType.ext.uppercase()
        b.tvOriginal.text = item.displayName
        // 图标异步加载；path 为 null（未解析 / 无图标）时保持占位背景
        AppIconLoader.bind(b.ivIcon, row.iconPath, row.id)

        val target = item.targetName
        b.tvTarget.text = when {
            item.state == ItemState.RENAMED -> item.displayName
            !target.isNullOrBlank() -> "→ $target"
            else -> "—"
        }

        val meta = item.meta
        b.tvMeta.text = buildString {
            if (meta != null && meta.isValid) {
                append(meta.packageName)
                if (meta.versionName.isNotBlank()) append(" · v").append(meta.versionName)
                if (meta.versionCode > 0) append(" (").append(meta.versionCode).append(')')
            } else if (meta?.error != null) {
                append(meta.error)
            } else {
                append(ctx.getString(R.string.metadata_pending))
            }
            append(" · ").append(item.readableSize())
            if (meta?.fromCache == true) append(" · ").append(ctx.getString(R.string.metadata_cache_hit))
            if (item.relativePath.isNotEmpty()) append(" · /").append(item.relativePath)
        }

        val (colorRes, bgRes) = stateStyle(row.status)
        b.tvState.text = row.status.localizedLabel(ctx)
        b.tvState.setTextColor(ContextCompat.getColor(ctx, colorRes))
        b.tvState.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, bgRes))

        // 整行点击切换选中；长按打开手动改名对话框
        b.root.setOnClickListener { onToggle(row.id) }
        b.root.setOnLongClickListener { onLongPress(row.id); true }
    }

    /**
     * 展示状态 → （文案，胶囊文字色，胶囊底色）。优先使用 [RowStatus.label] 保持两端一致。
     * 文字色与底色配对定义于 colors.xml（* 与 *_bg 成对，日/夜各自可读）。
     */
    private fun stateStyle(status: RowStatus): Pair<Int, Int> = when (status) {
        RowStatus.PENDING -> R.color.text_secondary to R.color.neutral_bg
        RowStatus.NEEDS_RENAME -> R.color.warning to R.color.warning_bg
        RowStatus.NO_CHANGE -> R.color.text_secondary to R.color.neutral_bg
        RowStatus.DONE -> R.color.success to R.color.success_bg
        RowStatus.FAILED -> R.color.danger to R.color.danger_bg
    }

    companion object {
        /** 仅选中态变化的轻量刷新标记 */
        val PAYLOAD_SELECTION = Any()

        private val DIFF = object : DiffUtil.ItemCallback<ApkRow>() {
            override fun areItemsTheSame(oldItem: ApkRow, newItem: ApkRow): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ApkRow, newItem: ApkRow): Boolean =
                oldItem == newItem

            override fun getChangePayload(oldItem: ApkRow, newItem: ApkRow): Any? =
                if (oldItem.selected != newItem.selected) PAYLOAD_SELECTION else null
        }
    }
}
