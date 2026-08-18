package com.workbuddy.apkrenamer.vm

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.workbuddy.apkrenamer.R
import com.workbuddy.apkrenamer.cache.ApkMetadataCache
import com.workbuddy.apkrenamer.core.MetaExtractor
import com.workbuddy.apkrenamer.core.NameComposer
import com.workbuddy.apkrenamer.core.NameValidation
import com.workbuddy.apkrenamer.core.PickerInitialResolver
import com.workbuddy.apkrenamer.core.RulePrefs
import com.workbuddy.apkrenamer.core.SafScanner
import com.workbuddy.apkrenamer.model.ApkItem
import com.workbuddy.apkrenamer.model.ItemState
import com.workbuddy.apkrenamer.model.cacheSeed
import com.workbuddy.apkrenamer.model.extension
import com.workbuddy.apkrenamer.model.needsRename
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 批量重命名过程中的 UI 刷新间隔（ms）：进度按时间节流，避免大列表反复全量重投影 */
private const val RENAME_UI_INTERVAL_MS = 100L

/**
 * 手机端与 TV 端**共享**的业务编排层（Q8 / RQ-T7-6）。
 *
 * ### 为什么是 AndroidViewModel 而不是 DI
 * `SafScanner` / `MetaExtractor` / `ApkMetadataCache` 三者都需要 `Context`。
 * P3 明确不引入 Hilt / Koin（R6），直接用 [AndroidViewModel] 拿 Application 是
 * 成本最低且不新增任何 artifact 的方案。Application 级 Context 不会泄漏 Activity。
 *
 * ### 唯一真源
 * ```
 * items          扫描 + 解析 + 应用规则后的原始数据
 * selectedIds    选中的 documentId（Q1，两端共享）
 * manualTargets  手动指定的目标名（Q2，进程内有效、不持久化）
 *        ↓  ListProjection.buildRows / applyFilter / countsOf
 * uiState        两端唯一消费的状态快照
 * ```
 * 任何 UI 层都**不得**自己调用 `SafScanner.scan` / `MetaExtractor.extractAll` /
 * `NameComposer.compose`——那会立刻让两端行为漂移（RQ-T7-6 / §4.3 纪律 1）。
 *
 * ### 生命周期
 * 所有耗时工作跑在 [viewModelScope]，配置变更（旋转、TV 端切 Fragment）不会中断；
 * ViewModel 销毁时协程自动取消，[CancellationException] 一律原样上抛，
 * 绝不吞掉——吞掉会让 `finally` 之后的取消传播链断掉。
 */
class ApkListViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Context = app.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(RulePrefs.PREFS_NAME, Context.MODE_PRIVATE)

    private val cache: ApkMetadataCache = ApkMetadataCache.getInstance(appContext)

    private fun text(resId: Int, vararg args: Any): UiText = UiText.Resource(resId, args.toList())

    /* ---------------- 唯一真源 ---------------- */

    /** 授权目录（字符串形式，与 [ListUiState.treeUri] 一致） */
    private var treeUri: String? = null

    /** 扫描 / 解析 / 规则应用之后的原始数据，顺序即展示顺序 */
    private var items: List<ApkItem> = emptyList()

    /** Q1：选中集合，key = `documentId`。改名后 documentId 不变，选中态不会错位 */
    private val selectedIds: MutableSet<String> = LinkedHashSet()

    /** Q2：手动目标名，仅进程内有效，**不持久化**（重启后回落到规则生成的名字） */
    private val manualTargets: MutableMap<String, String> = LinkedHashMap()

    /**
     * 筛选：默认 [StatusFilter.ALL]。**不在这里恢复持久化值**——筛选只在手机端恢复
     * （见 [restoreFilterPref]），TV 端 ViewModel 永远从 ALL 起步，否则没有筛选入口
     * 的 TV 会被旧筛选锁住（行缺失且无法解除）。
     */
    private var filter: StatusFilter = StatusFilter.ALL

    /**
     * 排序：从 prefs 恢复（两端都安全——它只影响展示顺序，不参与执行范围判定），
     * 变更时落盘。默认 [SortKey.NAME] 与扫描顺序一致。
     */
    private var sortKey: SortKey = SortKey.of(prefs.getString(RulePrefs.KEY_SORT_KEY, null))
    private var sortDirection: SortDirection =
        SortDirection.of(prefs.getString(RulePrefs.KEY_SORT_DIRECTION, null))

    /* ---------------- 阶段快照 ---------------- */

    private var phase: Phase = Phase.IDLE
    private var progress: Int = 0
    private var progressMax: Int = 0
    private var statusText: UiText = UiText.Raw("")

    /** 当前在跑的扫描 / 重命名任务，新任务发起前先取消旧的 */
    private var runningJob: Job? = null

    /** [bootstrap] 的幂等闸门：旋转屏幕、TV 端重建 Fragment 都不会重复初始化 */
    private var bootstrapped: Boolean = false

    /* ---------------- 对外状态 ---------------- */

    private val _uiState = MutableStateFlow(ListUiState())
    val uiState: StateFlow<ListUiState> = _uiState.asStateFlow()

    /**
     * 一次性事件。`extraBufferCapacity = 8` 让 [emitToast] 可以在非挂起上下文里
     * 用 `tryEmit` 投递而不丢事件；`replay = 0` 保证旋转后不会重放旧提示。
     */
    private val _events = MutableSharedFlow<UiEvent>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /* ================================================================ */
    /* 初始化                                                            */
    /* ================================================================ */

    /**
     * 幂等初始化：清理上次异常退出残留的临时目录、预热磁盘缓存、恢复上次授权目录。
     *
     * **不自动扫描**——恢复目录后只提示用户点「重新扫描」，与手机端原有行为一致。
     * 自动扫描会让用户一进 App 就被几百个文件的解析卡住，且无法取消。
     */
    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        viewModelScope.launch {
            runCatching { MetaExtractor.clearTempDir(appContext) }
            runCatching { cache.warmUp() }
            restoreFolder()
            publish()
        }
    }

    private fun restoreFolder() {
        val saved = prefs.getString(RulePrefs.KEY_TREE, null) ?: return
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: return
        // 用「覆盖判断」而非精确相等：记忆的 OTG 子目录由父级树授权覆盖，重启后依然可用
        if (SafScanner.isCoveredByPersistedGrant(appContext, uri)) {
            treeUri = saved
            statusText = text(R.string.status_restored_folder)
        } else {
            // 授权已被系统回收（重装 / 清数据 / 用户手动撤销 / U 盘拔出），清掉脏记录避免反复失败
            prefs.edit().remove(RulePrefs.KEY_TREE).apply()
        }
    }

    /* ================================================================ */
    /* SAF 授权                                                          */
    /* ================================================================ */

    /**
     * 用户选定目录后的唯一入口：持久化授权 → 落盘 → 记录可移动卷 → 清空旧数据 → 立即扫描。
     *
     * @param grantedUri   用户在 SAF 选择器里实际点选的目录（授权与持久化都挂它）
     * @param effectiveUri 本次实际使用的目录；默认等于 [grantedUri]，
     *                     当用户同意「恢复上次文件夹」时传入记忆的子目录
     *                      （它在该授权子树的覆盖范围内，父级授权天然覆盖）
     */
    fun onTreeGranted(grantedUri: Uri, effectiveUri: Uri = grantedUri) {
        SafScanner.persistPermission(appContext, grantedUri)
        val saved = effectiveUri.toString()
        treeUri = saved
        prefs.edit().putString(RulePrefs.KEY_TREE, saved).apply()

        // 可移动卷（OTG / SD）按卷 ID 记住本次生效目录，供下次插入同一卷时恢复
        if (SafScanner.isRemovableVolume(grantedUri)) {
            SafScanner.volumeIdOf(grantedUri)?.let { volumeId ->
                RulePrefs.saveOtgFolder(prefs, volumeId, saved)
            }
        }

        // 换目录等于换数据集，旧的选中与手动名全部作废
        items = emptyList()
        selectedIds.clear()
        manualTargets.clear()
        statusText = text(R.string.status_authorized_scanning)
        publish()

        scan()
    }

    /**
     * 本次授权范围内是否藏着一个上次用过的可移动卷文件夹（OTG 记忆）。
     *
     * 供 UI 层在授权回调里弹「恢复上次文件夹」对话框：
     * - 无记忆 / 不是可移动卷 / 记忆目录不在本次授权子树内 → 返回 null；
     * - 用户选中的恰好就是记忆目录 → 返回 null（没有恢复的必要）。
     */
    fun rememberedFolderWithin(granted: Uri): Uri? {
        if (!SafScanner.isRemovableVolume(granted)) return null
        val volumeId = SafScanner.volumeIdOf(granted) ?: return null
        val remembered = RulePrefs.loadOtgFolder(prefs, volumeId) ?: return null
        val rememberedUri = runCatching { Uri.parse(remembered) }.getOrNull() ?: return null
        if (rememberedUri == granted) return null
        return if (SafScanner.isDescendantOf(rememberedUri, granted)) rememberedUri else null
    }

    /**
     * 拉起系统目录选择器时的初始位置（对应 `DocumentsContract.EXTRA_INITIAL_URI`），
     * 让用户每次点「选择文件夹」不用再从头一路翻路径。
     *
     * 决策逻辑在纯函数 [PickerInitialResolver.resolve]（§7.7.1 的三级取位，
     * 见 `PickerInitialResolverTest`），这里只负责两件事：
     * 1. 把 VM 状态投影成它的输入——当前生效目录 docId（[treeUri]，可能已被
     *    [restoreFolder] 清成 null）、可读写持久化授权的 docId 列表、OTG 记忆；
     * 2. 把决策投影回 Uri——[PickerInitialResolver.Initial.Remembered] 直接返回
     *    记忆里存的**完整树 Uri 串**（`otg_folder:<卷ID>`），不做 docId → Uri 重建，
     *    从根上避开树 Uri 的编码歧义。
     */
    fun pickerInitialUri(): Uri? {
        val current = treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val grantDocIds = appContext.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.isWritePermission }
            .mapNotNull { SafScanner.treeDocumentIdOf(it.uri) }

        return when (val initial = PickerInitialResolver.resolve(
            currentDocId = current?.let { SafScanner.treeDocumentIdOf(it) },
            rememberDocId = ::rememberedDocIdOf,
            grantDocIds = grantDocIds,
        )) {
            null -> null
            PickerInitialResolver.Initial.Current -> current
            is PickerInitialResolver.Initial.Remembered -> rememberedUriOf(initial.volumeId)
        }
    }

    /** 按卷 ID 查 OTG 记忆的树 Uri（记忆里存的就是完整树 Uri 串，直接解析返回） */
    private fun rememberedUriOf(volumeId: String): Uri? =
        RulePrefs.loadOtgFolder(prefs, volumeId)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 按卷 ID 查 OTG 记忆的 docId */
    private fun rememberedDocIdOf(volumeId: String): String? =
        rememberedUriOf(volumeId)?.let { SafScanner.treeDocumentIdOf(it) }

    /** 设备没有目录选择器时由 UI 层回调，走统一的降级提示通道（RQ-T7-8） */
    fun onPickerUnavailable() {
        _events.tryEmit(UiEvent.PickerUnavailable)
    }

    /* ================================================================ */
    /* 扫描 + 并发解析                                                    */
    /* ================================================================ */

    /**
     * 扫描授权目录并并发解析元数据，收尾自动应用一次命名规则生成预览。
     *
     * 逐行迁移自原 `MainActivity.startScan()`，行为完全一致：
     * 进度经 `Dispatchers.Main` 回调 → 写入 [phase] 快照 → [publish] 推给两端。
     */
    fun scan() {
        val tree = treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: run {
            emitToast(text(R.string.toast_select_folder))
            return
        }
        runningJob?.cancel()
        runningJob = viewModelScope.launch {
            setPhase(Phase.SCANNING, 0, 0, text(R.string.status_scanning_folder))
            cache.resetStats()
            try {
                val found = SafScanner.scan(
                    context = appContext,
                    treeUri = tree,
                    recursive = RulePrefs.loadRecursive(prefs)
                ) { count -> setPhase(Phase.SCANNING, count, 0, text(R.string.status_scanning_found, count)) }

                if (found.isEmpty()) {
                    items = emptyList()
                    selectedIds.clear()
                    manualTargets.clear()
                    setPhase(Phase.IDLE, 0, 0, text(R.string.status_no_archives))
                    return@launch
                }

                items = found
                pruneStaleState()
                setPhase(Phase.PARSING, 0, found.size, text(R.string.status_parsing_metadata))

                val t0 = System.currentTimeMillis()
                val parsed = MetaExtractor.extractAll(
                    context = appContext,
                    items = found
                ) { p ->
                    setPhase(Phase.PARSING, p.done, p.total, text(R.string.status_parsing_progress, p.done, p.total, p.currentName))
                }
                val cost = System.currentTimeMillis() - t0

                items = parsed
                // 解析完立刻出预览，但**不**自动勾选：勾选是用户的显式意图
                applyRuleInternal(autoSelect = false)

                val s = cache.stats()
                setPhase(
                    Phase.IDLE, 0, 0,
                    text(R.string.status_parsing_complete, parsed.size, cost, s.hitRate, s.memoryHits, s.diskHits, s.misses)
                )
            } catch (ce: CancellationException) {
                // 必须原样上抛：吞掉会截断取消传播，viewModelScope 无法正常收敛
                throw ce
            } catch (t: Throwable) {
                setPhase(Phase.IDLE, 0, 0, text(R.string.status_scan_failed, t.message ?: t.javaClass.simpleName))
            } finally {
                // 无论正常结束、异常还是被取消，都要退出忙态，否则按钮永久置灰
                if (phase != Phase.IDLE) setPhase(Phase.IDLE, 0, 0, statusText)
            }
        }
    }

    /** 重新扫描后，丢弃指向已不存在文件的选中态与手动名 */
    private fun pruneStaleState() {
        val alive = items.mapTo(HashSet(items.size)) { it.documentId }
        selectedIds.retainAll(alive)
        manualTargets.keys.retainAll(alive)
    }

    /* ================================================================ */
    /* 规则应用（含冲突消解）                                             */
    /* ================================================================ */

    /**
     * 生成目标名预览。
     *
     * @param autoSelect 生成后是否自动勾选全部「待改名」项。TV 端「生成预览」传 `true`
     *                   （遥控器逐个勾选成本太高）；手机端与设置页返回传 `false`。
     * @param showToast  是否弹出结果提示。后台自动触发的重算传 `false`，避免打扰。
     */
    fun applyRule(autoSelect: Boolean, showToast: Boolean) {
        if (items.isEmpty()) {
            if (showToast) emitToast(text(R.string.toast_scan_folder))
            return
        }
        val rule = currentRule()
        if (rule.fields.isEmpty()) {
            emitToast(text(R.string.toast_select_name_field))
            return
        }
        val pending = applyRuleInternal(autoSelect)
        if (showToast) {
            emitToast(if (pending == 0) text(R.string.toast_nothing_to_rename) else text(R.string.toast_preview_generated, pending))
        }
    }

    /**
     * 规则应用的实际实现，返回待改名条数。
     *
     * 逐行迁移自原 `MainActivity.applyRule()`，并补上手动名的处理：
     *
     * 1. **按 `relativePath` 分组**做冲突消解 —— 不同子目录下同名文件互不影响；
     * 2. 组内**先给手动名占位**，再算自动名。顺序反过来的话，自动名可能先抢走
     *    用户手动指定的名字，导致手动名被迫追加 `_1`；
     * 3. 每个文件在 claim 之前先 `guard.release(displayName)` 让出自身原名，
     *    否则「A.apk 目标仍是 A.apk」会被误判为冲突而变成 `A_1.apk`。
     */
    private fun applyRuleInternal(autoSelect: Boolean): Int {
        val rule = currentRule()
        val resolved = HashMap<String, ApkItem>(items.size)

        items.groupBy { it.relativePath }.forEach { (_, group) ->
            val guard = NameComposer.ConflictGuard(
                existing = group.map { it.displayName },
                separator = rule.separator
            )

            // ── 第一轮：手动名优先占位 ──────────────────────────────
            group.forEach { item ->
                val manual = manualTargets[item.documentId] ?: return@forEach
                guard.release(item.displayName)
                // claim 的返回值可能被追加序号（两个手动名撞车的极端情况）。
                // 宁可显示 `_1` 也不能让后者静默覆盖前者。
                val claimed = guard.claim(manual)
                resolved[item.documentId] = item.copy(
                    targetName = claimed,
                    state = if (claimed == item.displayName) ItemState.SKIPPED else ItemState.PARSED
                )
            }

            // ── 第二轮：其余按规则生成 ──────────────────────────────
            group.forEach { item ->
                if (manualTargets.containsKey(item.documentId)) return@forEach
                if (item.meta?.isValid != true) {
                    resolved[item.documentId] = item.copy(
                        targetName = null,
                        state = ItemState.FAILED
                    )
                    return@forEach
                }
                guard.release(item.displayName)
                val target = guard.claim(NameComposer.compose(item, rule))
                resolved[item.documentId] = item.copy(
                    targetName = target,
                    state = if (target == item.displayName) ItemState.SKIPPED else ItemState.PARSED
                )
            }
        }

        items = items.map { resolved[it.documentId] ?: it }

        if (autoSelect) {
            selectedIds.clear()
            items.forEach { if (it.needsRename) selectedIds.add(it.documentId) }
        }

        val pending = items.count { it.needsRename }
        statusText = text(R.string.status_pending_rename, pending, items.size)
        publish()
        return pending
    }

    /**
     * 从设置页返回时重算预览。
     *
     * 走这里而不是 [applyRule]：**保留**用户已有的勾选与手动名（§3.6 行为契约）。
     * 手机端 `onResume()` 与 TV 端 `TvSettingsFragment.persist()` 共用同一入口。
     */
    fun refreshRuleOnResume() {
        if (items.isEmpty()) return
        if (currentRule().fields.isEmpty()) return
        applyRuleInternal(autoSelect = false)
    }

    /* ================================================================ */
    /* 选择                                                              */
    /* ================================================================ */

    /** 切换单行选中态。手机端点击行、TV 端 OK 键（RQ-T7-14）共用。 */
    fun toggleSelection(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
        publish()
    }

    /** 全选**当前可见**范围内的行。被筛选挡住的不会被选中，语义与用户所见一致。 */
    fun selectAllVisible() {
        val visible = ListProjection.applyFilter(currentRows(), filter)
        visible.forEach { selectedIds.add(it.id) }
        publish()
    }

    /** 只清空可见范围的选中，被筛选隐藏的选中项保持不变（避免误伤） */
    fun clearVisibleSelection() {
        val visible = ListProjection.applyFilter(currentRows(), filter)
        visible.forEach { selectedIds.remove(it.id) }
        publish()
    }

    /** 清空全部选中，包含被隐藏的 */
    fun clearAllSelection() {
        selectedIds.clear()
        publish()
    }

    fun setFilter(next: StatusFilter) {
        if (filter == next) return
        filter = next
        prefs.edit().putString(RulePrefs.KEY_FILTER, filter.name).apply()
        publish()
    }

    /**
     * 手机端启动时调用：把持久化的筛选维度恢复到本 ViewModel。
     * 与排序（构造时自动恢复）不同，筛选必须由手机端显式拉起——
     * TV 端没有筛选 UI，绝不能让它继承手机端的旧筛选。
     */
    fun restoreFilterPref() {
        val saved = StatusFilter.of(prefs.getString(RulePrefs.KEY_FILTER, null))
        setFilter(saved)
    }

    /** 切换排序维度与方向。点击同一维度时由 UI 层负责翻转向。变更持久化，重启保留。 */
    fun setSort(key: SortKey, direction: SortDirection) {
        if (sortKey == key && sortDirection == direction) return
        sortKey = key
        sortDirection = direction
        prefs.edit()
            .putString(RulePrefs.KEY_SORT_KEY, key.name)
            .putString(RulePrefs.KEY_SORT_DIRECTION, direction.name)
            .apply()
        publish()
    }

    /* ================================================================ */
    /* 手动改名（§3.4）                                                  */
    /* ================================================================ */

    /** 当前生效的命名规则，完全来自设置页的持久化结果 */
    fun currentRule(): NameComposer.Rule = RulePrefs.loadRule(prefs)

    fun itemOf(id: String): ApkItem? = items.firstOrNull { it.documentId == id }

    /**
     * 校验一个手动输入的名字。UI 层（`TargetNameEditor`）边输入边调用，
     * 拿到 [NameValidation] 直接渲染错误文案，不需要自己判断任何规则。
     */
    fun validateManualName(id: String, input: String): NameValidation {
        val item = itemOf(id) ?: return NameComposer.validateManualName(
            input, "", emptyList(), currentRule()
        )
        return NameComposer.validateManualName(
            input = input,
            originalName = item.displayName,
            siblingNames = ListProjection.siblingNamesOf(items, id),
            rule = currentRule()
        )
    }

    /**
     * 提交手动目标名。校验不通过则原样返回错误、**不**改动任何状态。
     *
     * 成功后自动勾选该行：用户特意改了名字，几乎必然想执行它。
     */
    fun setManualTarget(id: String, input: String): NameValidation {
        val validation = validateManualName(id, input)
        if (!validation.isValid) return validation

        manualTargets[id] = validation.sanitized
        selectedIds.add(id)
        applyRuleInternal(autoSelect = false)
        return validation
    }

    /** 撤销手动名，该行回落到规则自动生成的目标名 */
    fun clearManualTarget(id: String) {
        if (manualTargets.remove(id) == null) return
        applyRuleInternal(autoSelect = false)
    }

    /** 手动改名对话框的默认扩展名 */
    fun defaultExtensionOf(id: String): String = itemOf(id)?.extension ?: "apk"

    /* ================================================================ */
    /* 批量重命名                                                        */
    /* ================================================================ */

    /**
     * 执行重命名。执行范围由 [ListProjection.isExecutable] 唯一定义（§7.4），
     * 两端的二次确认弹窗展示的也是同一份计数。
     *
     * 逐行迁移自原 `MainActivity.performRename()`，保留三个关键细节：
     * - 成功后把元数据**重新挂到新 Key 上**（R4）——改名后 Uri 变了，
     *   不这么做下次扫描会当成新文件重新解压解析；
     * - [CancellationException] 原样上抛；
     * - `finally` 里强制退出忙态。
     */
    fun renameSelected() {
        val targets = ListProjection
            .executableRows(ListProjection.applyFilter(currentRows(), filter))
            .map { it.item }

        if (targets.isEmpty()) {
            emitToast(text(R.string.toast_generate_preview_first))
            return
        }

        runningJob?.cancel()
        runningJob = viewModelScope.launch {
            setPhase(Phase.RENAMING, 0, targets.size, text(R.string.status_renaming_prepare))
            var success = 0
            var failed = 0

            val working = items.toMutableList()
            val indexOf = HashMap<String, Int>(working.size)
            working.forEachIndexed { i, it -> indexOf[it.documentId] = i }

            try {
                // 按时间节流刷新：SAF 重命名本身是几十 ms 一次的 IPC，若每条都
                // 全量重投影（排序 + 重复包 + 计数），大列表下主线程要吃 O(n²) 的重算
                var lastUiAt = 0L
                targets.forEachIndexed { index, item ->
                    val newName = item.targetName ?: return@forEachIndexed
                    val pos = indexOf[item.documentId] ?: return@forEachIndexed

                    val result = SafScanner.rename(appContext, item, newName)
                    working[pos] = result.fold(
                        onSuccess = { newUri ->
                            success++
                            val renamed = item.copy(
                                uri = newUri,
                                displayName = newName,
                                targetName = null,
                                state = ItemState.RENAMED,
                                detail = null
                            )
                            // R4：把元数据挂到新 Key 上，避免下次扫描重新解压解析；
                            // 图标跟着一起迁移（内容没变，只是缓存种子变了）
                            item.meta?.let { meta ->
                                cache.put(cache.keyOf(renamed.cacheSeed), meta)
                                cache.moveIcon(cache.keyOf(item.cacheSeed), cache.keyOf(renamed.cacheSeed))
                            }
                            // 手动名已落地，撤掉登记；选中态一并清掉，避免重复执行
                            manualTargets.remove(item.documentId)
                            selectedIds.remove(item.documentId)
                            renamed
                        },
                        onFailure = { e ->
                            failed++
                            item.copy(
                                state = ItemState.FAILED,
                                detail = e.message ?: appContext.getString(R.string.row_status_failed)
                            )
                        }
                    )

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiAt >= RENAME_UI_INTERVAL_MS) {
                        lastUiAt = now
                        items = working.toList()
                        setPhase(
                            Phase.RENAMING, index + 1, targets.size,
                            text(R.string.status_renaming_progress, index + 1, targets.size, success, failed)
                        )
                    }
                }

                items = working.toList()
                cache.flush()
                setPhase(Phase.IDLE, 0, 0, text(R.string.status_renaming_complete, success, failed))
                _events.emit(UiEvent.RenameFinished(success, failed))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                setPhase(Phase.IDLE, 0, 0, text(R.string.status_renaming_interrupted, t.message ?: t.javaClass.simpleName))
            } finally {
                // 取消 / 中断也要把已落地的那部分重命名同步进列表，再退出忙态
                items = working.toList()
                if (phase != Phase.IDLE) setPhase(Phase.IDLE, 0, 0, statusText)
            }
        }
    }

    /* ================================================================ */
    /* 缓存                                                              */
    /* ================================================================ */

    fun clearCache() {
        viewModelScope.launch {
            cache.clear()
            // 图标文件已全部删除：作废路径 memo 并递增纪元，强迫下一次投影重查
            iconPathMemo.clear()
            iconEpoch++
            statusText = text(R.string.status_cache_cleared)
            publish()
            _events.emit(UiEvent.Toast(text(R.string.status_cache_cleared)))
        }
    }

    /** Activity `onStop()` 时把内存里的缓存条目落盘 */
    fun flushCache() {
        viewModelScope.launch { runCatching { cache.flush() } }
    }

    /* ================================================================ */
    /* 状态发布                                                          */
    /* ================================================================ */

    private fun currentRows(): List<ApkRow> =
        ListProjection.buildRows(items, selectedIds, manualTargets.keys)

    private fun setPhase(next: Phase, progressValue: Int, maxValue: Int, status: UiText) {
        phase = next
        progress = progressValue
        progressMax = maxValue
        statusText = status
        publish()
    }

    /**
     * 一次全量投影的结果。输入签名（items 引用 + 选中 / 手动名快照 + 筛选 / 排序 +
     * 图标纪元）不变时整体复用——解析 / 重命名期间的进度 tick 只改 phase / progress /
     * statusText，不必每次都重算排序、重复包、计数与图标路径。
     */
    private class ProjectionSnapshot(
        val itemsRef: List<ApkItem>,
        val selection: Set<String>,
        val manualKeys: Set<String>,
        val filter: StatusFilter,
        val sortKey: SortKey,
        val sortDirection: SortDirection,
        val iconEpoch: Long,
        val allRows: List<ApkRow>,
        val visible: List<ApkRow>,
        val visibleIds: Set<String>,
        val counts: Counts
    )

    private var projectionSnapshot: ProjectionSnapshot? = null

    /** 图标纪元：清空缓存会使全部图标文件失效，投影必须重算（iconPath memo 一并作废） */
    private var iconEpoch: Long = 0L

    /**
     * 图标路径 memo（cache key → 路径；null = 已确认无文件）。
     * `iconFileOf` 底层是 `File.isFile` 磁盘 I/O，不能在每次 publish 里对每行重复做——
     * 解析进度每 50ms 推一帧，上千行 × 每帧一次 stat 足以拖卡主线程。
     */
    private val iconPathMemo = HashMap<String, String?>()

    private fun iconPathFor(item: ApkItem): String? {
        if (item.meta?.hasIcon != true) return null
        val key = cache.keyOf(item.cacheSeed)
        return iconPathMemo.getOrPut(key) { cache.iconFileOf(key)?.absolutePath }
    }

    /**
     * 把内部真源投影成 [ListUiState] 并推给两端。
     *
     * 每次都重建整个快照而不是增量 `copy`：状态字段之间存在派生关系
     * （可见行 → 可执行数 → 隐藏已选数），增量更新极易漏算某一项。
     * 但**重投影本身**（排序 / 重复包 / 计数 / 图标路径）有输入签名兜底：
     * 签名不变（典型：只变了进度数字的 tick）时复用 [ProjectionSnapshot]，
     * 上千文件的列表也不会在主线程反复重算。
     */
    private fun publish() {
        val selection = LinkedHashSet(selectedIds)
        val manualKeys = HashSet(manualTargets.keys)

        val snapshot = projectionSnapshot?.takeIf { s ->
            s.itemsRef === items && s.selection == selection && s.manualKeys == manualKeys &&
                s.filter == filter && s.sortKey == sortKey && s.sortDirection == sortDirection &&
                s.iconEpoch == iconEpoch
        } ?: rebuildProjection(selection, manualKeys)

        _uiState.value = ListUiState(
            treeUri = treeUri,
            rows = snapshot.visible,
            filter = filter,
            sortKey = sortKey,
            sortDirection = sortDirection,
            phase = phase,
            progress = progress,
            progressMax = progressMax,
            statusText = statusText,
            counts = snapshot.counts,
            totalCount = items.size,
            selectedCount = selectedIds.size,
            executableCount = ListProjection.executableRows(snapshot.visible).size,
            hiddenSelectedCount = selectedIds.count { it !in snapshot.visibleIds },
            manualCount = manualTargets.size
        )
    }

    private fun rebuildProjection(selection: Set<String>, manualKeys: Set<String>): ProjectionSnapshot {
        val dupIds = ListProjection.duplicateIdsOf(items)
        val allRows = ListProjection.sortRows(currentRows(), sortKey, sortDirection).map { row ->
            row.copy(
                duplicate = row.id in dupIds,
                iconPath = iconPathFor(row.item)
            )
        }
        val visible = ListProjection.applyFilter(allRows, filter)
        val visibleIds = visible.mapTo(HashSet(visible.size)) { it.id }
        return ProjectionSnapshot(
            items, selection, manualKeys, filter, sortKey, sortDirection, iconEpoch,
            allRows, visible, visibleIds, ListProjection.countsOf(allRows)
        ).also { projectionSnapshot = it }
    }

    private fun emitToast(text: UiText) {
        _events.tryEmit(UiEvent.Toast(text))
    }
}
