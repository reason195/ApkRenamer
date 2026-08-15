package com.workbuddy.apkrenamer.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.Presenter
import com.workbuddy.apkrenamer.R
import com.workbuddy.apkrenamer.vm.ApkRow

/**
 * Row1 / Row2 / Row3 共用的 diff 规则。
 *
 * `ArrayObjectAdapter.setItems(newList, diffCallback)` 依赖它做**增量**更新——
 * 这是设计文档 §4.3 纪律 2 的落点：绝不用 `clear() + addAll()`，
 * 后者会让整行 RecyclerView 重建，遥控器焦点直接丢到别的行去。
 *
 * [ApkRow] 是 data class，`areContentsTheSame` 用 `==` 即可覆盖
 * 目标名 / 选中位 / 手动位 / 状态 的全部变化。
 */
internal val APK_ROW_DIFF: DiffCallback<ApkRow> = object : DiffCallback<ApkRow>() {

    override fun areItemsTheSame(oldItem: ApkRow, newItem: ApkRow): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: ApkRow, newItem: ApkRow): Boolean =
        oldItem == newItem
}

/**
 * APK 卡片 Presenter（RQ-T7-4 / RQ-T7-5）。
 *
 * 只做「[ApkRow] → 三行文字」的纯渲染，**不含任何业务判断**：
 * 状态由 `ListProjection.statusOf()` 在 ViewModel 侧算好塞进 [ApkRow.status]，
 * 目标名由 `NameComposer.compose()` 算好塞进 `item.targetName`。
 * 这里若再写一行 `when (item.state)`，就违反了 RQ-T7-6。
 */
class ApkCardPresenter : Presenter() {

    /** 缓存四个 TextView，避免每次 bind 都遍历视图树 */
    class ApkViewHolder(val card: BaseCardView) : Presenter.ViewHolder(card) {
        val target: TextView = card.findViewById(R.id.tvCardTarget)
        val original: TextView = card.findViewById(R.id.tvCardOriginal)
        val status: TextView = card.findViewById(R.id.tvCardStatus)
        val selected: TextView = card.findViewById(R.id.tvCardSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val card = BaseCardView(parent.context).apply {
            cardType = BaseCardView.CARD_TYPE_MAIN_ONLY
            isFocusable = true
            isFocusableInTouchMode = true
            // BaseCardView 默认带 padding，会把 480×200 的固定尺寸卡片撑变形
            setPadding(0, 0, 0, 0)
            LayoutInflater.from(context).inflate(R.layout.view_tv_apk_card, this, true)
        }
        // 与 ActionCardPresenter 共用同一份缩放动画，保证两种卡片手感一致
        card.bindTvFocusScale()
        return ApkViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val holder = viewHolder as ApkViewHolder
        val row = item as? ApkRow ?: return
        val ctx = holder.card.context

        // 目标名为空（尚未生成预览 / 无需改动）时退化显示原名，
        // 避免主行空白导致卡片看起来像加载失败
        holder.target.text = row.item.targetName ?: row.item.displayName
        holder.original.text = row.item.displayName

        val statusLabel = row.status.label
        holder.status.text = if (row.manual) {
            ctx.getString(R.string.tv_status_with_manual, statusLabel)
        } else {
            statusLabel
        }

        holder.selected.setText(R.string.tv_badge_selected)
        holder.selected.visibility = if (row.selected) View.VISIBLE else View.GONE
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val holder = viewHolder as ApkViewHolder
        // 复位缩放，避免带着放大态被复用到另一张卡片上
        holder.card.scaleX = 1.0f
        holder.card.scaleY = 1.0f
        holder.target.text = null
        holder.original.text = null
        holder.status.text = null
        holder.selected.visibility = View.GONE
    }
}
