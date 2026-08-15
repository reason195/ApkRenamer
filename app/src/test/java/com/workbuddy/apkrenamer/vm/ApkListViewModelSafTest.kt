package com.workbuddy.apkrenamer.vm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.workbuddy.apkrenamer.RobolectricTestBase
import com.workbuddy.apkrenamer.core.RulePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/**
 * SAF 授权全链路的 Robolectric 单测：`onTreeGranted` 的持久化落盘与
 * `restoreFolder`（`bootstrap` 内）的恢复 / 脏记录清理。
 *
 * 与 [ApkListViewModelPickerTest]（pickerInitialUri 接线）互补：
 * 本类不测取位优先级（已由纯 JVM 的 [com.workbuddy.apkrenamer.core.PickerInitialResolverTest]
 * 全覆盖），只锁定「授权/记忆写进 prefs 与持久化授权」这一层。
 *
 * 依赖 Robolectric 的真实 `ContentResolver`（`takePersistableUriPermission` /
 * `persistedUriPermissions` 的 shadow 支持，见 picker 测试里的沙箱守卫）与
 * `viewModelScope` 的主 looper 调度（`bootstrap` 是异步的，需
 * `ShadowLooper.idleMainLooper()` 让协程跑完）。runner 与 SDK 统一在
 * [com.workbuddy.apkrenamer.RobolectricTestBase]。
 */
class ApkListViewModelSafTest : RobolectricTestBase() {

    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var vm: ApkListViewModel

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences(RulePrefs.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        vm = ApkListViewModel(app)
    }

    /* ================================================================ */
    /* 工具                                                              */
    /* ================================================================ */

    /** 构造 SAF 树 Uri：docId 整体百分号编码成单个路径段，否则 getTreeDocumentId 会截断 */
    private fun treeUri(docId: String): Uri =
        Uri.parse(
            "content://com.android.externalstorage.documents/tree/" +
                docId.replace(":", "%3A").replace("/", "%2F")
        )

    private fun otgRoot(serial: String = "1234-5678"): Uri = treeUri("USB:$serial")

    private fun otgDeep(serial: String = "1234-5678", path: String = "Download/APKs"): Uri =
        treeUri("USB:$serial/$path")

    private fun primary(path: String): Uri = treeUri("primary/$path")

    private fun persist(uri: Uri) {
        app.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    private fun idleMainLooper() = ShadowLooper.idleMainLooper()

    /**
     * 有界等待 [condition] 成立：bootstrap 的协程体内 `clearTempDir` / `warmUp`
     * 都会 `withContext(Dispatchers.IO)`，`idleMainLooper()` 只排空主队列，
     * 等不到 IO 线程完成后的回投续体——必须轮询到可观察结果（或超时）。
     */
    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("等待条件在 ${timeoutMs}ms 内未成立", condition())
    }

    /* ================================================================ */
    /* onTreeGranted：持久化落盘                                        */
    /* ================================================================ */

    @Test
    fun `onTreeGranted 主存储持久化授权并写入 KEY_TREE 但不写 OTG 记忆`() {
        val uri = primary("Download/APKs")
        vm.onTreeGranted(uri)

        val perm = app.contentResolver.persistedUriPermissions.first { it.uri == uri }
        assertTrue("授权必须可读可写", perm.isReadPermission && perm.isWritePermission)
        assertEquals(uri.toString(), prefs.getString(RulePrefs.KEY_TREE, null))
        // 主存储不参与 OTG 记忆
        assertNull(prefs.getString(RulePrefs.KEY_OTG_PREFIX + "primary", null))
    }

    @Test
    fun `onTreeGranted 可移动卷 KEY_TREE 记生效目录并写入 OTG 记忆`() {
        val root = otgRoot()
        val deep = otgDeep()
        // 恢复流程：授权挂卷根，生效目录为记忆子目录（effectiveUri 默认参数被替换）
        vm.onTreeGranted(root, deep)

        // 持久化授权挂在 grantedUri 上
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == root })
        // KEY_TREE 与 OTG 记忆同写同值 = 生效目录
        assertEquals(deep.toString(), prefs.getString(RulePrefs.KEY_TREE, null))
        assertEquals(
            deep.toString(),
            prefs.getString(RulePrefs.KEY_OTG_PREFIX + "USB:1234-5678", null)
        )
    }

    @Test
    fun `onTreeGranted 可移动卷授权与生效目录一致时记忆即目录本身`() {
        val deep = otgDeep()
        vm.onTreeGranted(deep)

        assertEquals(deep.toString(), prefs.getString(RulePrefs.KEY_TREE, null))
        assertEquals(
            deep.toString(),
            prefs.getString(RulePrefs.KEY_OTG_PREFIX + "USB:1234-5678", null)
        )
    }

    /* ================================================================ */
    /* restoreFolder（bootstrap 内）：恢复与脏记录清理                   */
    /* ================================================================ */

    @Test
    fun `bootstrap 恢复被持久化授权覆盖的目录`() {
        val root = otgRoot()
        val deep = otgDeep()
        persist(root)
        // 模拟上次会话落盘：KEY_TREE 与 OTG 记忆都指向记忆子目录
        prefs.edit().putString(RulePrefs.KEY_TREE, deep.toString()).commit()
        RulePrefs.saveOtgFolder(prefs, "USB:1234-5678", deep.toString())

        vm.bootstrap()
        // 恢复是异步的（IO 往返后回投主队列），等到可观察结果
        awaitUntil { vm.pickerInitialUri() != null }

        // 恢复后选择器初始位置直接就是该目录
        assertEquals(deep, vm.pickerInitialUri())
    }

    @Test
    fun `bootstrap 授权被回收时清掉 KEY_TREE 脏记录`() {
        // 只有 KEY_TREE 记录、没有任何对应持久化授权 → 模拟重装 / 撤销 / 卷拔出后被系统回收
        prefs.edit().putString(RulePrefs.KEY_TREE, otgDeep().toString()).commit()

        vm.bootstrap()
        awaitUntil { prefs.getString(RulePrefs.KEY_TREE, null) == null }

        assertNull("脏记录必须被清掉，否则每次启动都会反复失败", prefs.getString(RulePrefs.KEY_TREE, null))
        assertNull(vm.pickerInitialUri())
    }

    @Test
    fun `bootstrap 主存储目录被授权覆盖时同样恢复`() {
        val uri = primary("Download/APKs")
        persist(uri)
        prefs.edit().putString(RulePrefs.KEY_TREE, uri.toString()).commit()

        vm.bootstrap()
        awaitUntil { vm.pickerInitialUri() != null }

        assertEquals(uri, vm.pickerInitialUri())
    }
}
