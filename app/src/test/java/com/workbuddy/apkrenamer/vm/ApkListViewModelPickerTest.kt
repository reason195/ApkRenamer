package com.workbuddy.apkrenamer.vm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import com.workbuddy.apkrenamer.RobolectricTestBase
import com.workbuddy.apkrenamer.core.RulePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * [ApkListViewModel.pickerInitialUri] 的**接线** Robolectric 单测。
 *
 * 取位决策本身（三级分支、卷 ID 大小写、前缀碰撞等边界）已由纯 JVM 的
 * [com.workbuddy.apkrenamer.core.PickerInitialResolverTest] 全覆盖；本类只验证
 * VM 把状态正确投影成纯函数的输入、再把决策正确投影回 Uri（含分支 3 所需的
 * `ContentResolver.persistedUriPermissions`）。
 *
 * 为什么需要 Robolectric：`pickerInitialUri` 依赖 `DocumentsContract.getTreeDocumentId`
 * 与 `ContentResolver.persistedUriPermissions`，必须加载真实 android 框架；
 * 且本项目测试源集已无 `android.net.Uri` 替身——全部单测统一跑在 Robolectric 沙箱下，
 * Kotlin 测试代码可以直接 `Uri.parse` 构造**真实** Uri（见 README 测试分层，
 * runner 与 SDK 统一在 [RobolectricTestBase]）。
 */
class ApkListViewModelPickerTest : RobolectricTestBase() {

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
    /* Uri 工厂                                                        */
    /* ================================================================ */

    /**
     * 构造 SAF 树 Uri。docId 必须整体百分号编码成**单个路径段**（冒号 / 斜杠），
     * 否则 `DocumentsContract.getTreeDocumentId` 只能取到第一段（编码歧义，见 §7.7.1）。
     */
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

    /* ================================================================ */
    /* 前置条件守卫：沙箱能力真实可用                                    */
    /* ================================================================ */

    @Test
    fun `真实 Uri 与 DocumentsContract 可用`() {
        // 防止沙箱类加载异常或解析漂移，导致这里静默退化
        val tree = treeUri("USB:1234-5678/Download/APKs")
        assertEquals("USB:1234-5678/Download/APKs", DocumentsContract.getTreeDocumentId(tree))
    }

    @Test
    fun `持久化授权可写入并读回`() {
        val tree = otgRoot()
        persist(tree)
        assertTrue(
            "takePersistableUriPermission 后应有持久化授权",
            app.contentResolver.persistedUriPermissions.any { it.uri == tree }
        )
    }

    /* ================================================================ */
    /* 分支 2：当前目录存在（主存储 / 无 OTG 记忆参与）                   */
    /* ================================================================ */

    @Test
    fun `从未授权过时返回 null`() {
        assertNull(vm.pickerInitialUri())
    }

    @Test
    fun `当前目录在主存储时原样返回`() {
        val uri = primary("Download/APKs")
        vm.onTreeGranted(uri)
        assertEquals(uri, vm.pickerInitialUri())
    }

    /* ================================================================ */
    /* 分支 1：可移动卷 + OTG 记忆                                       */
    /* ================================================================ */

    @Test
    fun `可移动卷优先定位到记忆的更深子目录`() {
        val root = otgRoot()
        vm.onTreeGranted(root) // treeUri = 卷根，OTG 记忆 = 卷根
        val deep = otgDeep()
        // 模拟记忆比当前生效目录更深（真实写路径下两者同写同值，此处构造分叉验证取位优先级）
        RulePrefs.saveOtgFolder(prefs, "USB:1234-5678", deep.toString())

        assertEquals(deep, vm.pickerInitialUri())
    }

    @Test
    fun `可移动卷记忆与当前目录相同时返回当前`() {
        val deep = otgDeep()
        vm.onTreeGranted(deep)
        assertEquals(deep, vm.pickerInitialUri())
    }

    @Test
    fun `可移动卷记忆不在当前授权内时回落当前目录`() {
        val root = otgRoot()
        vm.onTreeGranted(root)
        // 记忆指向另一卷 → 不在当前授权子树内 → 只能回落当前
        RulePrefs.saveOtgFolder(prefs, "USB:1234-5678", otgDeep("9999-0000").toString())

        assertEquals(root, vm.pickerInitialUri())
    }

    /* ================================================================ */
    /* 分支 3：无当前目录，靠持久化授权 + OTG 记忆兜底（卷重插场景）      */
    /* ================================================================ */

    @Test
    fun `无当前目录但持久化授权覆盖记忆时直接定位到记忆目录`() {
        persist(otgRoot())
        RulePrefs.saveOtgFolder(prefs, "USB:1234-5678", otgDeep().toString())

        assertEquals(otgDeep(), vm.pickerInitialUri())
    }

    @Test
    fun `无当前目录且有持久化授权但无记忆时返回 null`() {
        persist(otgRoot())
        assertNull(vm.pickerInitialUri())
    }

    @Test
    fun `无当前目录时只有记忆而无授权返回 null`() {
        RulePrefs.saveOtgFolder(prefs, "USB:1234-5678", otgDeep().toString())
        assertNull(vm.pickerInitialUri())
    }

    @Test
    fun `持久化授权与记忆目录不匹配时返回 null`() {
        persist(otgRoot())
        // 记忆指向另一卷，授权覆盖不到 → 找不到可直达的位置
        RulePrefs.saveOtgFolder(prefs, "USB:1234-5678", otgDeep("9999-0000").toString())
        assertNull(vm.pickerInitialUri())
    }
}
