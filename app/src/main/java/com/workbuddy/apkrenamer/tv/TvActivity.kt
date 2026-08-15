package com.workbuddy.apkrenamer.tv

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.workbuddy.apkrenamer.R
import com.workbuddy.apkrenamer.core.SafScanner
import com.workbuddy.apkrenamer.ui.applySystemBarInsets
import com.workbuddy.apkrenamer.ui.enableEdgeToEdgeCompat
import com.workbuddy.apkrenamer.vm.ApkListViewModel

/**
 * Android TV 入口（RQ-T7-1 ~ RQ-T7-9）。
 *
 * ⚠️ **必须**继承 [FragmentActivity] 而非 `AppCompatActivity`：
 * `Theme.ApkRenamer.Leanback` 的祖先是 `Theme.Leanback` → `Theme.Material`，
 * 不含 AppCompat 所需的 `windowActionBar` 等属性，`AppCompatActivity` 在这套主题下
 * 会在 `onCreate` 期间抛 `IllegalArgumentException: You need to use a Theme.AppCompat theme`。
 *
 * 本类只负责三件事：edge-to-edge insets、SAF 目录选择器的拉起与降级、承载
 * [TvBrowseFragment]。业务编排全在 [ApkListViewModel]，与手机端同一条代码路径（RQ-T7-6）。
 */
class TvActivity : FragmentActivity() {

    private lateinit var vm: ApkListViewModel
    private lateinit var rootView: View

    /**
     * SAF 目录选择。
     *
     * 结果只往 [ApkListViewModel.onTreeGranted] 里递交一次，
     * 持久化授权（`takePersistableUriPermission`）与随后的 `scan()` 都由 ViewModel 完成——
     * TV 端不复制这段编排（RQ-T7-6）。
     */
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) vm.onTreeGranted(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 顺序要求：先关掉系统窗口自适配，再 setContentView，最后挂 inset 监听
        enableEdgeToEdgeCompat()
        setContentView(R.layout.activity_tv)

        rootView = findViewById(R.id.tv_root)
        // activity_tv.xml 的初始 padding 就是 overscan 安全边距；
        // applySystemBarInsets 会快照这份初始值并在其之上叠加系统栏 inset（§4.4）
        rootView.applySystemBarInsets(top = true, bottom = true, sides = true)

        vm = ViewModelProvider(this)[ApkListViewModel::class.java]

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tv_browse_container, TvBrowseFragment())
                .commit()
        }
    }

    /**
     * 尽力而为的预检（§7.7）：仅用于把「选择文件夹」卡片提前置灰。
     *
     * API 30+ 的包可见性限制会让 `resolveActivity` 在没有 `<queries>` 声明时误报 null，
     * 而我们明确不加 `<queries>`（R1 精神），所以这里的结果**不是**唯一判据——
     * 真正的兜底是 [launchTreePicker] 里的 try/catch。
     */
    fun isTreePickerAvailable(): Boolean = SafScanner.isTreePickerAvailable(this)

    /**
     * 拉起系统目录选择器；设备没有实现时降级为提示而**不是崩溃**（RQ-T7-8）。
     *
     * 降级之后已持久化授权的目录仍然可用：用户按「重新扫描」即可继续完整流程。
     */
    fun launchTreePicker() {
        // 初始位置传上次授权目录（EXTRA_INITIAL_URI），TV 遥控器逐级翻路径成本更高
        runCatching { folderPicker.launch(vm.pickerInitialUri()) }
            .onFailure {
                // ActivityNotFoundException 是最常见的一种；其余异常（如厂商 ROM 的
                // SecurityException）同样只降级提示，绝不让 TV 端闪退。
                //
                // 提示走 ViewModel 的 UiEvent.PickerUnavailable 而不是本地弹 Toast：
                // 手机端遇到同样情况走的是同一个事件，两端文案与时机自动一致（RQ-T7-6）。
                // TvBrowseFragment 收到后用标题区轻提示，不抢遥控器焦点。
                vm.onPickerUnavailable()
            }
    }
}
