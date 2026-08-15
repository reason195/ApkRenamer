package com.workbuddy.apkrenamer.vm

import android.net.Uri
import com.workbuddy.apkrenamer.model.ApkItem
import com.workbuddy.apkrenamer.model.ApkMeta
import com.workbuddy.apkrenamer.model.ArchiveType
import com.workbuddy.apkrenamer.model.ItemState
import com.workbuddy.apkrenamer.model.MetaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import com.workbuddy.apkrenamer.RobolectricTestBase
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ListProjection] 的投影逻辑单测（设计文档 §5.2 A5）。
 *
 * 这一层是手机端与 TV 端**共享**的投影逻辑，一旦出错，两端会同时以相同的方式出错，
 * 而且症状是「显示将改名 8 个、实际只改了 6 个」这类极难在真机上定位的问题。
 * 因此这里的断言密度刻意高于普通业务代码。
 *
 * 逻辑本身零 `android.*` import（[ListProjection] / [ListUiState]）；测试跑在
 * Robolectric 下只是为了构造 `ApkItem` 所需的真实 `android.net.Uri`（见 README 测试分层，
 * runner 与 SDK 统一在 [RobolectricTestBase]）。
 */
class ListProjectionTest : RobolectricTestBase() {

    /* ================================================================ */
    /* 测试数据工厂                                                      */
    /* ================================================================ */

    private fun meta(
        packageName: String = "com.example.demo",
        error: String? = null
    ): ApkMeta = ApkMeta(
        appName = "Demo",
        packageName = packageName,
        versionName = "1.0",
        versionCode = 1L,
        archiveType = ArchiveType.APK,
        source = MetaSource.PACKAGE_MANAGER,
        error = error
    )

    /** 解析失败的元数据：`isValid == false`，用于验证「手动名救场」分支 */
    private fun brokenMeta(): ApkMeta = meta(packageName = "", error = "zip 损坏")

    private fun item(
        id: String,
        displayName: String = "$id.apk",
        targetName: String? = null,
        state: ItemState = ItemState.PENDING,
        relativePath: String = "",
        meta: ApkMeta? = null
    ): ApkItem = ApkItem(
        uri = Uri.parse("content://tests/$id"),
        documentId = id,
        displayName = displayName,
        size = 1024L,
        lastModified = 1_700_000_000_000L,
        relativePath = relativePath,
        meta = meta,
        targetName = targetName,
        state = state
    )

    /* ================================================================ */
    /* statusOf：ItemState → RowStatus                                  */
    /* ================================================================ */

    @Test
    fun `statusOf 映射五种状态`() {
        assertEquals(
            RowStatus.PENDING,
            ListProjection.statusOf(item("a", state = ItemState.PENDING))
        )
        assertEquals(
            RowStatus.FAILED,
            ListProjection.statusOf(item("b", state = ItemState.FAILED))
        )
        assertEquals(
            RowStatus.DONE,
            ListProjection.statusOf(item("c", state = ItemState.RENAMED))
        )
        assertEquals(
            RowStatus.NO_CHANGE,
            ListProjection.statusOf(item("d", state = ItemState.SKIPPED))
        )
    }

    @Test
    fun `PARSED 且目标名与原名不同时为待改名`() {
        val parsed = item(
            "e",
            displayName = "old.apk",
            targetName = "new.apk",
            state = ItemState.PARSED
        )
        assertEquals(RowStatus.NEEDS_RENAME, ListProjection.statusOf(parsed))
    }

    @Test
    fun `PARSED 但目标名与原名一致时为无需改动`() {
        val same = item(
            "f",
            displayName = "same.apk",
            targetName = "same.apk",
            state = ItemState.PARSED
        )
        assertEquals(RowStatus.NO_CHANGE, ListProjection.statusOf(same))
    }

    @Test
    fun `PARSED 但目标名为空时为无需改动`() {
        // 解析成功却没算出目标名（例如所有命名字段都被用户关掉），不应误判成待改名
        val noTarget = item("g", targetName = null, state = ItemState.PARSED)
        assertEquals(RowStatus.NO_CHANGE, ListProjection.statusOf(noTarget))

        val blankTarget = item("h", targetName = "   ", state = ItemState.PARSED)
        assertEquals(RowStatus.NO_CHANGE, ListProjection.statusOf(blankTarget))
    }

    @Test
    fun `SKIPPED 即使目标名与原名不同也归为无需改动`() {
        // SKIPPED 是 applyRule 已经判过的终态，statusOf 不得二次推翻
        val skipped = item(
            "i",
            displayName = "old.apk",
            targetName = "new.apk",
            state = ItemState.SKIPPED
        )
        assertEquals(RowStatus.NO_CHANGE, ListProjection.statusOf(skipped))
    }

    /* ================================================================ */
    /* buildRows                                                        */
    /* ================================================================ */

    @Test
    fun `buildRows 保持输入顺序并正确接线`() {
        val items = listOf(
            item("a", displayName = "a.apk", targetName = "A.apk", state = ItemState.PARSED),
            item("b", state = ItemState.FAILED),
            item("c", state = ItemState.RENAMED)
        )

        val rows = ListProjection.buildRows(
            items = items,
            selectedIds = setOf("a", "c"),
            manualIds = setOf("c")
        )

        assertEquals(listOf("a", "b", "c"), rows.map { it.id })
        assertEquals(listOf(true, false, true), rows.map { it.selected })
        assertEquals(listOf(false, false, true), rows.map { it.manual })
        assertEquals(
            listOf(RowStatus.NEEDS_RENAME, RowStatus.FAILED, RowStatus.DONE),
            rows.map { it.status }
        )
        // item 必须是同一实例，不能在投影层复制，否则 DiffCallback 的 == 会退化成深比较
        assertSame(items[0], rows[0].item)
    }

    @Test
    fun `buildRows 的选中与手动集合默认为空`() {
        val rows = ListProjection.buildRows(listOf(item("a")))
        assertEquals(1, rows.size)
        assertFalse(rows[0].selected)
        assertFalse(rows[0].manual)
    }

    @Test
    fun `buildRows 处理空列表`() {
        assertTrue(ListProjection.buildRows(emptyList()).isEmpty())
    }

    @Test
    fun `buildRows 的 id 取自 documentId 而非文件名`() {
        // 重命名后 displayName 会变、documentId 不变，选中态必须挂在 documentId 上
        val rows = ListProjection.buildRows(listOf(item("doc-42", displayName = "任意.apk")))
        assertEquals("doc-42", rows[0].id)
    }

    /* ================================================================ */
    /* groupByStatus                                                    */
    /* ================================================================ */

    @Test
    fun `groupByStatus 分组且组内保持原始顺序`() {
        val items = listOf(
            item("n1", displayName = "1.apk", targetName = "x1.apk", state = ItemState.PARSED),
            item("p1", state = ItemState.PENDING),
            item("n2", displayName = "2.apk", targetName = "x2.apk", state = ItemState.PARSED),
            item("f1", state = ItemState.FAILED)
        )
        val grouped = ListProjection.groupByStatus(ListProjection.buildRows(items))

        assertEquals(
            listOf("n1", "n2"),
            grouped[RowStatus.NEEDS_RENAME].orEmpty().map { it.id }
        )
        assertEquals(listOf("p1"), grouped[RowStatus.PENDING].orEmpty().map { it.id })
        assertEquals(listOf("f1"), grouped[RowStatus.FAILED].orEmpty().map { it.id })
    }

    @Test
    fun `groupByStatus 不产生空分组键`() {
        // 文档明确承诺「不保证包含全部枚举键」，TV 端靠 orEmpty() 兜底；
        // 这里反向锁定契约：没有的状态就是没有这个 key，而不是空 List
        val grouped = ListProjection.groupByStatus(
            ListProjection.buildRows(listOf(item("only", state = ItemState.PENDING)))
        )
        assertEquals(setOf(RowStatus.PENDING), grouped.keys)
        assertNull(grouped[RowStatus.DONE])
    }

    @Test
    fun `groupByStatus 不丢行`() {
        val items = (1..20).map {
            item("id$it", state = ItemState.values()[it % ItemState.values().size])
        }
        val rows = ListProjection.buildRows(items)
        val grouped = ListProjection.groupByStatus(rows)
        assertEquals(rows.size, grouped.values.sumOf { it.size })
    }

    /* ================================================================ */
    /* applyFilter                                                      */
    /* ================================================================ */

    /** 覆盖全部五种 RowStatus 的固定样本，供筛选与计数用例复用 */
    private fun sampleRows(): List<ApkRow> = ListProjection.buildRows(
        items = listOf(
            item("n", displayName = "n.apk", targetName = "N.apk", state = ItemState.PARSED),
            item("c", state = ItemState.SKIPPED),
            item("p", state = ItemState.PENDING),
            item("d", state = ItemState.RENAMED),
            item("f", state = ItemState.FAILED)
        ),
        selectedIds = setOf("n", "d")
    )

    @Test
    fun `applyFilter ALL 原样返回`() {
        val rows = sampleRows()
        assertEquals(rows, ListProjection.applyFilter(rows, StatusFilter.ALL))
    }

    @Test
    fun `applyFilter 按状态筛选`() {
        val rows = sampleRows()
        assertEquals(
            listOf("n"),
            ListProjection.applyFilter(rows, StatusFilter.NEEDS_RENAME).map { it.id }
        )
        assertEquals(
            listOf("d"),
            ListProjection.applyFilter(rows, StatusFilter.DONE).map { it.id }
        )
        assertEquals(
            listOf("f"),
            ListProjection.applyFilter(rows, StatusFilter.FAILED).map { it.id }
        )
    }

    @Test
    fun `applyFilter NO_CHANGE 合并待解析`() {
        // 与 TV 端 Row2「无需改动 + 待解析」保持同一语义，两端不得各自定义
        assertEquals(
            listOf("c", "p"),
            ListProjection.applyFilter(sampleRows(), StatusFilter.NO_CHANGE).map { it.id }
        )
    }

    @Test
    fun `applyFilter SELECTED 只看选中态与状态无关`() {
        assertEquals(
            listOf("n", "d"),
            ListProjection.applyFilter(sampleRows(), StatusFilter.SELECTED).map { it.id }
        )
    }

    @Test
    fun `applyFilter 保持原始相对顺序`() {
        val rows = ListProjection.buildRows(
            listOf(
                item("z", displayName = "z.apk", targetName = "Z.apk", state = ItemState.PARSED),
                item("a", displayName = "a.apk", targetName = "A.apk", state = ItemState.PARSED)
            )
        )
        // 投影层不排序（顺序由 SafScanner 决定），筛选也不得偷偷重排
        assertEquals(
            listOf("z", "a"),
            ListProjection.applyFilter(rows, StatusFilter.NEEDS_RENAME).map { it.id }
        )
    }

    @Test
    fun `applyFilter 覆盖全部枚举值不抛异常`() {
        val rows = sampleRows()
        for (filter in StatusFilter.values()) {
            assertTrue(ListProjection.applyFilter(rows, filter).size <= rows.size)
        }
    }

    /* ================================================================ */
    /* duplicateIdsOf / DUPLICATE 筛选（dedupe）                         */
    /* ================================================================ */

    /** 带 meta 的文件工厂：duplicate 判定依赖 packageName / versionCode */
    private fun dupItem(id: String, packageName: String, versionCode: Long = 1L): ApkItem =
        item(id, meta = meta(packageName = packageName).copy(versionCode = versionCode))

    @Test
    fun `duplicateIdsOf 同包同版本标记组内全部成员`() {
        val items = listOf(
            dupItem("a", "com.x.app"),
            dupItem("b", "com.x.app"),
            dupItem("c", "com.other")
        )
        assertEquals(setOf("a", "b"), ListProjection.duplicateIdsOf(items))
    }

    @Test
    fun `duplicateIdsOf 同包不同版本不算重复`() {
        val items = listOf(
            dupItem("v1", "com.x.app", versionCode = 1L),
            dupItem("v2", "com.x.app", versionCode = 2L)
        )
        assertTrue(ListProjection.duplicateIdsOf(items).isEmpty())
    }

    @Test
    fun `duplicateIdsOf 解析失败与未解析不参与`() {
        val items = listOf(
            dupItem("ok1", "com.x.app"),
            dupItem("ok2", "com.x.app"),
            item("broken", meta = brokenMeta()),
            item("noMeta", meta = null)
        )
        assertEquals(setOf("ok1", "ok2"), ListProjection.duplicateIdsOf(items))
    }

    @Test
    fun `duplicateIdsOf 空列表返回空`() {
        assertTrue(ListProjection.duplicateIdsOf(emptyList()).isEmpty())
    }

    @Test
    fun `applyFilter DUPLICATE 只看重复标记`() {
        val all = listOf(
            dupItem("d1", "com.x.app"),
            dupItem("d2", "com.x.app"),
            dupItem("n", "com.other")
        )
        val dup = ListProjection.duplicateIdsOf(all)
        val rows = ListProjection.buildRows(all).map { it.copy(duplicate = it.id in dup) }
        assertEquals(
            listOf("d1", "d2"),
            ListProjection.applyFilter(rows, StatusFilter.DUPLICATE).map { it.id }
        )
    }

    @Test
    fun `Counts of DUPLICATE 与 applyFilter 一致`() {
        val all = listOf(
            dupItem("d1", "com.x.app"),
            dupItem("d2", "com.x.app"),
            dupItem("n", "com.other")
        )
        val dup = ListProjection.duplicateIdsOf(all)
        val rows = ListProjection.buildRows(all).map { it.copy(duplicate = it.id in dup) }
        val counts = ListProjection.countsOf(rows)
        assertEquals(2, counts.duplicates)
        assertEquals(2, counts.of(StatusFilter.DUPLICATE, selected = 0))
        assertEquals(
            ListProjection.applyFilter(rows, StatusFilter.DUPLICATE).size,
            counts.of(StatusFilter.DUPLICATE, selected = 0)
        )
    }

    /* ================================================================ */
    /* countsOf                                                         */
    /* ================================================================ */

    @Test
    fun `countsOf 分状态计数正确`() {
        val counts = ListProjection.countsOf(sampleRows())
        assertEquals(5, counts.total)
        assertEquals(1, counts.needsRename)
        assertEquals(1, counts.noChange)
        assertEquals(1, counts.pending)
        assertEquals(1, counts.done)
        assertEquals(1, counts.failed)
    }

    @Test
    fun `countsOf 各状态之和等于总数`() {
        val rows = ListProjection.buildRows(
            (1..37).map { item("id$it", state = ItemState.values()[it % 5]) }
        )
        val c = ListProjection.countsOf(rows)
        assertEquals(
            c.total,
            c.needsRename + c.noChange + c.pending + c.done + c.failed
        )
    }

    @Test
    fun `countsOf 空列表全为零`() {
        assertEquals(Counts(), ListProjection.countsOf(emptyList()))
    }

    @Test
    fun `Counts of 按筛选维度取值且 NO_CHANGE 含待解析`() {
        val counts = ListProjection.countsOf(sampleRows())
        assertEquals(5, counts.of(StatusFilter.ALL, selected = 2))
        assertEquals(1, counts.of(StatusFilter.NEEDS_RENAME, selected = 2))
        // 与 applyFilter(NO_CHANGE) 的结果数必须一致，否则 chip 角标会和列表对不上
        assertEquals(2, counts.of(StatusFilter.NO_CHANGE, selected = 2))
        assertEquals(1, counts.of(StatusFilter.DONE, selected = 2))
        assertEquals(1, counts.of(StatusFilter.FAILED, selected = 2))
        assertEquals(2, counts.of(StatusFilter.SELECTED, selected = 2))
    }

    @Test
    fun `Counts of 与 applyFilter 结果数逐项一致`() {
        val rows = sampleRows()
        val counts = ListProjection.countsOf(rows)
        val selected = rows.count { it.selected }
        for (filter in StatusFilter.values()) {
            assertEquals(
                "筛选 $filter 的角标计数与列表长度不一致",
                ListProjection.applyFilter(rows, filter).size,
                counts.of(filter, selected)
            )
        }
    }

    /* ================================================================ */
    /* isExecutable / executableRows（§7.4 执行范围唯一定义）            */
    /* ================================================================ */

    private fun rowOf(
        id: String = "x",
        selected: Boolean = true,
        manual: Boolean = false,
        displayName: String = "old.apk",
        targetName: String? = "new.apk",
        meta: ApkMeta? = meta()
    ): ApkRow = ApkRow(
        id = id,
        item = item(
            id,
            displayName = displayName,
            targetName = targetName,
            state = ItemState.PARSED,
            meta = meta
        ),
        selected = selected,
        manual = manual,
        status = RowStatus.NEEDS_RENAME
    )

    @Test
    fun `isExecutable 三个条件齐备时为真`() {
        assertTrue(ListProjection.isExecutable(rowOf()))
    }

    @Test
    fun `isExecutable 未选中则为假`() {
        assertFalse(ListProjection.isExecutable(rowOf(selected = false)))
    }

    @Test
    fun `isExecutable 目标名为空或同名则为假`() {
        assertFalse(ListProjection.isExecutable(rowOf(targetName = null)))
        assertFalse(ListProjection.isExecutable(rowOf(targetName = "   ")))
        assertFalse(
            ListProjection.isExecutable(rowOf(displayName = "same.apk", targetName = "same.apk"))
        )
    }

    @Test
    fun `isExecutable 元数据无效且非手动则为假`() {
        assertFalse(ListProjection.isExecutable(rowOf(meta = null)))
        assertFalse(ListProjection.isExecutable(rowOf(meta = brokenMeta())))
    }

    @Test
    fun `手动指定的目标名可以救回解析失败的文件`() {
        // §3.4 手动改名存在的意义就是救这类文件；若这里返回 false，手动改名等于摆设
        assertTrue(ListProjection.isExecutable(rowOf(meta = brokenMeta(), manual = true)))
        assertTrue(ListProjection.isExecutable(rowOf(meta = null, manual = true)))
    }

    @Test
    fun `手动标记不能绕过选中与同名两道闸`() {
        assertFalse(ListProjection.isExecutable(rowOf(manual = true, selected = false)))
        assertFalse(
            ListProjection.isExecutable(
                rowOf(manual = true, displayName = "same.apk", targetName = "same.apk")
            )
        )
    }

    @Test
    fun `executableRows 过滤并保持原始顺序`() {
        val rows = listOf(
            rowOf(id = "a"),
            rowOf(id = "b", selected = false),
            rowOf(id = "c"),
            rowOf(id = "d", meta = brokenMeta()),
            rowOf(id = "e", meta = brokenMeta(), manual = true)
        )
        // 顺序即执行顺序：链式目标（A→C 且另有一项目标为 A）依赖它，不能重排
        assertEquals(listOf("a", "c", "e"), ListProjection.executableRows(rows).map { it.id })
    }

    @Test
    fun `executableRows 空输入返回空`() {
        assertTrue(ListProjection.executableRows(emptyList()).isEmpty())
    }

    /* ================================================================ */
    /* siblingNamesOf                                                   */
    /* ================================================================ */

    @Test
    fun `siblingNamesOf 排除自身`() {
        val items = listOf(
            item("a", displayName = "a.apk"),
            item("b", displayName = "b.apk")
        )
        assertEquals(listOf("b.apk"), ListProjection.siblingNamesOf(items, "a"))
    }

    @Test
    fun `siblingNamesOf 只收同目录`() {
        val items = listOf(
            item("a", displayName = "a.apk", relativePath = "dir1"),
            item("b", displayName = "b.apk", relativePath = "dir1"),
            item("c", displayName = "c.apk", relativePath = "dir2")
        )
        assertEquals(listOf("b.apk"), ListProjection.siblingNamesOf(items, "a"))
    }

    @Test
    fun `siblingNamesOf 同时收当前名与目标名`() {
        // 用户先把 A 改成 X，再给 B 也输 X —— 只比当前名检测不出这种冲突
        val items = listOf(
            item("a", displayName = "a.apk"),
            item("b", displayName = "b.apk", targetName = "X.apk")
        )
        assertEquals(listOf("b.apk", "X.apk"), ListProjection.siblingNamesOf(items, "a"))
    }

    @Test
    fun `siblingNamesOf 按大小写不敏感去重`() {
        // FAT / exFAT 不区分大小写，按大小写区分会在 U 盘上直接覆盖文件
        val items = listOf(
            item("a", displayName = "a.apk"),
            item("b", displayName = "Dup.apk"),
            item("c", displayName = "dup.apk")
        )
        assertEquals(listOf("Dup.apk"), ListProjection.siblingNamesOf(items, "a"))
    }

    @Test
    fun `siblingNamesOf 忽略空白目标名`() {
        val items = listOf(
            item("a", displayName = "a.apk"),
            item("b", displayName = "b.apk", targetName = "   ")
        )
        assertEquals(listOf("b.apk"), ListProjection.siblingNamesOf(items, "a"))
    }

    @Test
    fun `siblingNamesOf 目录内只有自己时返回空`() {
        assertTrue(ListProjection.siblingNamesOf(listOf(item("a")), "a").isEmpty())
    }

    @Test
    fun `siblingNamesOf 找不到 id 时返回空`() {
        val items = listOf(item("a"), item("b"))
        assertTrue(ListProjection.siblingNamesOf(items, "nope").isEmpty())
    }

    /* ================================================================ */
    /* compareVersions：自然版本比较                                      */
    /* ================================================================ */

    @Test
    fun `compareVersions 数字段按数值比较 1_9 排在 1_10 前`() {
        assertTrue(compareVersions("1.9", "1.10") < 0)
        assertTrue(compareVersions("1.10", "1.9") > 0)
    }

    @Test
    fun `compareVersions 前导 v 与大小写不影响比较`() {
        assertEquals(0, compareVersions("v1.2.3", "1.2.3"))
        assertEquals(0, compareVersions("V1.2.3", "1.2.3"))
    }

    @Test
    fun `compareVersions 前缀相等时短版本优先`() {
        assertTrue(compareVersions("1.0", "1.0.1") < 0)
        assertTrue(compareVersions("1.2.3", "1.2.3.0") < 0)
    }

    @Test
    fun `compareVersions 字母段忽略大小写且数字段在前`() {
        assertTrue(compareVersions("1.2beta", "1.2Beta") == 0)
        assertTrue(compareVersions("1.2", "1.2beta") < 0)
    }

    @Test
    fun `compareVersions 空版本排最后`() {
        assertTrue(compareVersions("", "1.0") > 0)
        assertTrue(compareVersions("1.0", "") < 0)
        assertEquals(0, compareVersions("", ""))
        assertEquals(0, compareVersions("  ", ""))
    }

    @Test
    fun `compareVersions 相等与自反`() {
        assertEquals(0, compareVersions("1.2.3", "1.2.3"))
        assertEquals(0, compareVersions("8.0.49", "8.0.49"))
        assertTrue(compareVersions("1.0", "1.0") == 0)
    }

    /* ================================================================ */
    /* sortRows：排序维度与方向                                          */
    /* ================================================================ */

    /** 带版本号的测试行（VERSION 排序专用）；null 表示未解析 */
    private fun versionRow(id: String, version: String?, name: String = "$id.apk"): ApkRow {
        val meta = version?.let { meta().copy(versionName = it) }
        return ApkRow(
            id = id,
            item = item(id, displayName = name, state = ItemState.PARSED, meta = meta),
            status = RowStatus.NEEDS_RENAME
        )
    }

    @Test
    fun `sortRows VERSION 升序时 1_9 在 1_10 前且空版本沉底`() {
        val rows = listOf(
            versionRow("a", "1.10"),
            versionRow("b", null),      // 未解析：应排最后
            versionRow("c", "1.9"),
            versionRow("d", "v2.0")
        )
        val sorted = ListProjection.sortRows(rows, SortKey.VERSION, SortDirection.ASC)
        assertEquals(listOf("c", "a", "d", "b"), sorted.map { it.id })
    }

    @Test
    fun `sortRows VERSION 降序时最新版本在顶`() {
        val rows = listOf(
            versionRow("a", "1.10"),
            versionRow("b", "1.9"),
            versionRow("c", "2.0")
        )
        val sorted = ListProjection.sortRows(rows, SortKey.VERSION, SortDirection.DESC)
        assertEquals(listOf("c", "a", "b"), sorted.map { it.id })
    }

    @Test
    fun `sortRows NAME 与扫描顺序一致 保持同目录相邻`() {
        val rows = listOf(
            ApkRow(id = "1", item = item("1", displayName = "b.apk", relativePath = "dir2")),
            ApkRow(id = "2", item = item("2", displayName = "a.apk", relativePath = "dir1")),
            ApkRow(id = "3", item = item("3", displayName = "c.apk", relativePath = "dir2"))
        )
        val sorted = ListProjection.sortRows(rows, SortKey.NAME, SortDirection.ASC)
        assertEquals(listOf("2", "1", "3"), sorted.map { it.id })
    }

    @Test
    fun `sortRows SIZE 升序按字节数排序`() {
        val rows = listOf(
            ApkRow(id = "big", item = item("big").copy(size = 5_000_000L)),
            ApkRow(id = "small", item = item("small").copy(size = 1_000L)),
            ApkRow(id = "mid", item = item("mid").copy(size = 500_000L))
        )
        val sorted = ListProjection.sortRows(rows, SortKey.SIZE, SortDirection.ASC)
        assertEquals(listOf("small", "mid", "big"), sorted.map { it.id })
    }

    @Test
    fun `sortRows STATUS 按行状态分组排序`() {
        val rows = listOf(
            ApkRow(id = "done", item = item("done", state = ItemState.RENAMED), status = RowStatus.DONE),
            ApkRow(id = "need", item = item("need", targetName = "x.apk", state = ItemState.PARSED), status = RowStatus.NEEDS_RENAME),
            ApkRow(id = "fail", item = item("fail", state = ItemState.FAILED), status = RowStatus.FAILED)
        )
        val sorted = ListProjection.sortRows(rows, SortKey.STATUS, SortDirection.ASC)
        // NEEDS_RENAME(0) < DONE(3) < FAILED(4)：按枚举 ordinal 排列
        assertEquals(listOf("need", "done", "fail"), sorted.map { it.id })
    }

    @Test
    fun `sortRows 空输入返回空且不抛异常`() {
        assertTrue(ListProjection.sortRows(emptyList(), SortKey.VERSION, SortDirection.ASC).isEmpty())
    }

    /* ================================================================ */
    /* 枚举持久化解析（of）：未知 / null 回退默认                        */
    /* ================================================================ */

    @Test
    fun `SortKey of 解析持久化名称并回退默认`() {
        assertEquals(SortKey.VERSION, SortKey.of("VERSION"))
        assertEquals(SortKey.NAME, SortKey.of("NAME"))
        assertEquals(SortKey.NAME, SortKey.of(null))
        assertEquals(SortKey.NAME, SortKey.of("bogus"))
        assertEquals(SortKey.NAME, SortKey.of(""))
    }

    @Test
    fun `SortDirection of 解析持久化名称并回退默认`() {
        assertEquals(SortDirection.DESC, SortDirection.of("DESC"))
        assertEquals(SortDirection.ASC, SortDirection.of("ASC"))
        assertEquals(SortDirection.ASC, SortDirection.of(null))
        assertEquals(SortDirection.ASC, SortDirection.of("sideways"))
    }

    @Test
    fun `StatusFilter of 解析持久化名称并回退默认`() {
        assertEquals(StatusFilter.NEEDS_RENAME, StatusFilter.of("NEEDS_RENAME"))
        assertEquals(StatusFilter.DUPLICATE, StatusFilter.of("DUPLICATE"))
        assertEquals(StatusFilter.ALL, StatusFilter.of(null))
        assertEquals(StatusFilter.ALL, StatusFilter.of("ALL"))
        assertEquals(StatusFilter.ALL, StatusFilter.of("bogus"))
    }

    @Test
    fun `sortRows 同版本时按文件名兜底 结果稳定`() {
        val rows = listOf(
            versionRow("z", "1.0", name = "zeta.apk"),
            versionRow("a", "1.0", name = "alpha.apk")
        )
        val asc = ListProjection.sortRows(rows, SortKey.VERSION, SortDirection.ASC)
        val desc = ListProjection.sortRows(rows, SortKey.VERSION, SortDirection.DESC)
        // 同版本：主键相等，副键（文件名）决定顺序；两次调用结果确定
        assertEquals(listOf("a", "z"), asc.map { it.id })
        assertEquals(listOf("z", "a"), desc.map { it.id })
    }
}
