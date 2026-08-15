package com.workbuddy.apkrenamer.tv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.workbuddy.apkrenamer.R

/**
 * TV 端操作行（Row0）的动作模型。
 *
 * 顺序即卡片在行内的排列顺序，与手机端顶部按钮的语义**一一对应**，
 * 保证同一个功能在两端叫同一个名字（文案直接复用手机端的 string，不另起一套）。
 *
 * > 设计偏差说明：设计文档 §3.8 的示意签名是 `TvAction(val title: String, val iconRes: Int)`，
 * > 但 §7.9 明确要求「新增文案一律进 `res/values/strings.xml`，不得硬编码」。
 * > 两者冲突时以 §7.9 为准，故此处用 [titleRes] 而非 `title: String`。
 * > 行为完全等价，且顺带让 TV 端天然跟随系统语言。
 */
enum class TvAction(
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int
) {
    /** 拉起 SAF 目录选择器。设备无选择器时由 TvActivity 降级提示（RQ-T7-8） */
    PICK_FOLDER(R.string.pick_folder, R.drawable.ic_tv_folder),

    /** 对已授权目录重新扫描 */
    RESCAN(R.string.rescan, R.drawable.ic_tv_refresh),

    /** 按当前规则重算目标名 */
    PREVIEW(R.string.preview, R.drawable.ic_tv_preview),

    /** 执行重命名，先弹 GuidedStep 二次确认（Q5） */
    EXECUTE(R.string.execute, R.drawable.ic_tv_execute),

    /** 打开 TV 精简设置页（RQ-T7-10） */
    SETTINGS(R.string.settings_title, R.drawable.ic_tv_settings);
}
