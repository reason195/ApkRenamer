package com.workbuddy.apkrenamer.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * edge-to-edge（`targetSdk 35`）统一适配入口。
 *
 * Android 15 起系统不再为状态栏 / 导航栏预留空间，窗口内容默认铺满整块屏幕。
 * 与其在每个 Activity 里各写一份 `setOnApplyWindowInsetsListener`，不如把两件事收敛到这里：
 *
 * 1. [enableEdgeToEdgeCompat]：关闭系统的窗口自适配，让 API 21~34 与 35+ 走**同一条**渲染路径，
 *    避免出现「Android 15 上对、Android 13 上错」这类只能靠真机才发现的分叉。
 * 2. [applySystemBarInsets]：把系统栏（含刘海）的 inset 以 padding 形式补给指定 View。
 *
 * ### 两条易踩的坑（本文件已规避）
 * - **padding 翻倍**：`setOnApplyWindowInsetsListener` 在旋转、软键盘弹出、多窗口切换时会被反复回调。
 *   若每次都在「当前 padding」上加 inset，几次之后顶栏就会被撑到半屏高。这里在注册监听时用闭包
 *   捕获 View 的**初始 padding**，之后每次 dispatch 都基于初始值重新计算，天然幂等。
 * - **兄弟节点收不到 inset**：监听器必须返回 `insets` **原样**而不是 `WindowInsetsCompat.CONSUMED`，
 *   否则同一层级的其它 View（例如底部按钮条）就再也拿不到导航栏高度。
 *
 * ### 使用约定
 * 吃 `top` inset 的 View 必须是 `wrap_content` + `android:minHeight`，不能是固定高度，
 * 否则加上 top padding 后内容区被压扁；吃 `bottom` inset 的可滚动容器必须 `clipToPadding="false"`。
 */

/**
 * 关闭系统窗口自适配，进入 edge-to-edge 模式。
 *
 * 可在 `setContentView` 之前或之后调用，二者等价。
 */
fun ComponentActivity.enableEdgeToEdgeCompat() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
}

/**
 * 把系统栏与显示切口（刘海 / 挖孔）的 inset 以 padding 形式补给该 View。
 *
 * @param top    是否补顶部 inset（状态栏）。用于顶栏 Toolbar
 * @param bottom 是否补底部 inset（导航栏 / 手势条）。用于底部操作条、可滚动容器
 * @param sides  是否补左右 inset（横屏刘海、侧边手势区）。默认开启
 */
fun View.applySystemBarInsets(
    top: Boolean = false,
    bottom: Boolean = false,
    sides: Boolean = true
) {
    // 注册时快照初始 padding：后续每次 dispatch 都基于它重算，避免累积膨胀
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(
            initialLeft + if (sides) bars.left else 0,
            initialTop + if (top) bars.top else 0,
            initialRight + if (sides) bars.right else 0,
            initialBottom + if (bottom) bars.bottom else 0
        )
        // 返回原样，不 CONSUMED —— 保证同层级兄弟 View 仍能拿到完整 insets
        insets
    }
    // 主动触发一次，避免首帧（View 尚未 attach）拿不到 inset 而闪烁
    ViewCompat.requestApplyInsets(this)
}
