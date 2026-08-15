package com.workbuddy.apkrenamer.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.Presenter
import com.workbuddy.apkrenamer.R

/** 焦点缩放的目标倍率与时长，两个 Presenter 共用，保证操作卡与 APK 卡的手感一致 */
private const val FOCUS_SCALE = 1.08f
private const val FOCUS_ANIM_DURATION_MS = 150L

/** 置灰态的透明度。leanback 不会自动渲染 disabled，需要自己压暗 */
private const val DISABLED_ALPHA = 0.4f

/**
 * 给卡片挂上「获焦放大、失焦复原」的动画（RQ-T7-5）。
 *
 * 放在文件顶层而不是各 Presenter 内部，是为了让 [ActionCardPresenter] 与 [ApkCardPresenter]
 * 共用同一份实现——两端手感不一致是 D-pad 界面最容易被察觉的瑕疵。
 */
internal fun View.bindTvFocusScale() {
    setOnFocusChangeListener { v, hasFocus ->
        val target = if (hasFocus) FOCUS_SCALE else 1.0f
        v.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(FOCUS_ANIM_DURATION_MS)
            .start()
    }
}

/**
 * 操作行（Row0）的卡片 Presenter。
 *
 * @param isEnabled 由 [TvBrowseFragment] 注入的可用性判定。用于把「选择目录」在
 *   设备无系统文件选择器时提前置灰（`SafScanner.isTreePickerAvailable()` 的尽力预检），
 *   以及在忙碌 / 无数据时压暗「生成预览」「执行重命名」。
 *
 *   之所以用回调而不是把 `enabled` 塞进一个包装 data class，是为了让 Row0 的
 *   `ArrayObjectAdapter` 里躺的仍然是纯粹的 [TvAction]（与设计文档 §3.8 一致）；
 *   可用性变化时由 Fragment 调 `notifyArrayItemRangeChanged()` 触发重绑即可。
 */
class ActionCardPresenter(
    private val isEnabled: (TvAction) -> Boolean = { true }
) : Presenter() {

    /** 缓存三个子 View 的引用，避免每次 bind 都走 findViewById */
    class ActionViewHolder(val card: BaseCardView) : Presenter.ViewHolder(card) {
        val icon: ImageView = card.findViewById(R.id.ivActionIcon)
        val title: TextView = card.findViewById(R.id.tvActionTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val card = BaseCardView(parent.context).apply {
            cardType = BaseCardView.CARD_TYPE_MAIN_ONLY
            isFocusable = true
            isFocusableInTouchMode = true
            // BaseCardView 自带的 padding 会把固定尺寸的卡片撑歪，这里清零由内部布局自己控制
            setPadding(0, 0, 0, 0)
            LayoutInflater.from(context).inflate(R.layout.view_tv_action_card, this, true)
        }
        card.bindTvFocusScale()
        return ActionViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val holder = viewHolder as ActionViewHolder
        val action = item as? TvAction ?: return

        holder.title.setText(action.titleRes)
        holder.icon.setImageResource(action.iconRes)

        val enabled = isEnabled(action)
        // 置灰但**不摘掉焦点**：TV 上不可聚焦的卡片会让 D-pad 直接跳过去，
        // 用户不知道这里有个功能。保留焦点 + 压暗 + 点击时给文字提示，体验更可控。
        holder.card.alpha = if (enabled) 1.0f else DISABLED_ALPHA
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val holder = viewHolder as ActionViewHolder
        // 回收前复位，避免复用到别的卡片时残留缩放 / 透明度
        holder.card.alpha = 1.0f
        holder.card.scaleX = 1.0f
        holder.card.scaleY = 1.0f
        holder.icon.setImageDrawable(null)
        holder.title.text = null
    }
}
