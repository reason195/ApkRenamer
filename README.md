# APK Renamer · Kotlin + 协程 + LruCache

[![CI](https://github.com/reason195/ApkRenamer/actions/workflows/ci.yml/badge.svg)](https://github.com/reason195/ApkRenamer/actions/workflows/ci.yml)
[![Android](https://img.shields.io/badge/Android-Platform-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![SDK](https://img.shields.io/badge/SDK-minSdk%2021%20%C2%B7%20targetSdk%2035-3DDC84)]()
[![Permissions](https://img.shields.io/badge/Permissions-none-brightgreen)]()
[![License](https://img.shields.io/badge/License-Apache--2.0-blue)](LICENSE)

Android 批量 APK 重命名工具，**手机 + Android TV 双端**共用同一个状态源。100% Kotlin、
零存储权限、零第三方解析库，无需 root —— 天然适配 Android 10~15 分区存储与 USB OTG 挂载盘。

## ✨ 功能特性

- **零权限 SAF 方案**：Manifest 无任何 `uses-permission`；目录授权走 `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` 跨重启保留。
- **双端共用状态源**：手机（Material3）与 TV（Leanback）共享 `ApkListViewModel`，扫描 / 解析 / 命名 / 重命名行为天然一致，不会出现「手机能改 TV 改不了」的分叉。
- **规则组合命名**：应用名 / 版本号 / VersionCode / 包名 / 自定义前缀 / 日期 / 文件大小等字段按 Chip 勾选顺序拼接，分隔符可选；清洗流水线处理非法字符、保留名与 UTF-8 字节截断。
- **批量重命名防覆盖**：大小写不敏感冲突消解（FAT/exFAT 安全），重名自动 `_1`、`_2` 递进，绝不静默覆盖。
- **多格式零依赖解析**：`.apk` / `.apkm` / `.apks` / `.xapk` 全部原生解析，无第三方解析库；应用名正确读取资源表 `@string/app_name`，图标一并提取进双层缓存。
- **记住 OTG 设备**：按卷 ID 记忆上次文件夹，重插同一 U 盘直达记忆子目录；目录选择器携带 `EXTRA_INITIAL_URI` 默认定位上次授权目录，不用每次翻路径。
- **双层缓存**：`LruCache` + 磁盘 JSON，缓存 Key 含 URI + 时间戳 + 大小，文件一变即自动失效。
- **筛选 / 搜索 / 多选 / 手动命名**：状态 chip 叠加关键词，多选只作用于可见行，手动命名走严格校验。
- **187 例单测**：全部跑在 Robolectric 沙箱，覆盖命名、投影、SAF 授权链路与选择器取位。

---

## 一、模块地图

| 文件 | 职责 | 关键技术 |
|---|---|---|
| `model/ApkItem.kt` | 文件与元数据结构 | Data Class、枚举、扩展函数、空安全 |
| `cache/ApkMetadataCache.kt` | 双层缓存 | `LruCache(200)` + `cacheDir` JSON + `Mutex` |
| `core/MetaExtractor.kt` | 并发解析（单例 object） | `Dispatchers.IO` + `async/awaitAll` + `Semaphore` |
| `core/NameComposer.kt` | 命名拼接 / 清洗 / 冲突消解 | 纯 Kotlin，可 JVM 单测 |
| `core/RulePrefs.kt` | SharedPreferences ↔ Rule 桥接层 | 纯 Kotlin，可 JVM 单测 |
| `core/VolumeLogic.kt` | docId 层面纯字符串卷运算（卷 ID / 子树判断 / 相对路径） | 纯 Kotlin，可 JVM 单测 |
| `core/PickerInitialResolver.kt` | 目录选择器初始位置的三级取位纯决策 | 纯 Kotlin，可 JVM 单测 |
| `core/SafScanner.kt` | SAF 扫描 / 重命名 / 卷与授权范围判断（OTG 记忆） | `DocumentsContract` 批量 Cursor |
| `vm/ApkListViewModel.kt` | **手机 / TV 共享的唯一状态源** | `AndroidViewModel` + `StateFlow` + `SharedFlow` |
| `vm/ListUiState.kt` | 列表 UI 状态与行模型 | 纯 Kotlin data class / enum |
| `vm/ListProjection.kt` | 筛选 / 排序 / 分组 / 计数投影 | 纯函数 `object`，可 JVM 单测 |
| `MainActivity.kt` | 手机端流程编排 | `lifecycleScope`、ViewBinding、Material3 |
| `SettingsActivity.kt` | 设置页（规则 UI 已迁移至此） | Material3 |
| `ui/ApkAdapter.kt` | 预览列表（含应用图标） | `ListAdapter` + `DiffUtil` |
| `ui/AppIconLoader.kt` | 图标异步解码（LruCache + 采样 + tag 防错位） | 纯框架 API，零第三方库 |
| `ui/InsetsExt.kt` | edge-to-edge 与系统栏 insets 统一处理 | `WindowCompat` + `OnApplyWindowInsetsListener` |
| `tv/TvActivity.kt` | TV 入口（`LEANBACK_LAUNCHER`） | `FragmentActivity` + `Theme.Leanback` |
| `tv/TvBrowseFragment.kt` | TV 主界面：操作行 + 三个状态行 | `BrowseSupportFragment` + `ArrayObjectAdapter` |
| `tv/ApkCardPresenter.kt` | TV 卡片渲染与焦点缩放 | `Presenter` + `DiffCallback` |
| `tv/TvConfirmFragment.kt` | 执行前二次确认（默认焦点在「取消」） | `GuidedStepSupportFragment` |
| `tv/TvSettingsFragment.kt` | TV 端规则设置 | `GuidedStepSupportFragment` |

**状态层与 UI 层的边界**：`vm/` 是手机与 TV 的**唯一共享状态源**，两端各自只负责渲染与输入分发；
所有筛选 / 排序 / 分组 / 计数一律走 `ListProjection`，UI 层不得对 `ApkItem` 集合直接做
`filter` / `sortedBy`，否则两端行为必然漂移。

---

## 二、并发模型

```kotlin
withContext(Dispatchers.IO) {
    coroutineScope {
        items.map { item ->
            async { gate.withPermit { resolveOne(item) } }   // 多核并行
        }.awaitAll()
    }
}
```

- **`Semaphore` 限流**：并发度 = `CPU 核心数 ∈ [2, 8]`。解压 base.apk 会产生数十 MB 的临时 I/O，
  无限并发只会抢占带宽并推高 OOM 风险。
- **进度节流**：50 ms 一次切回 `Dispatchers.Main` 回调，缓存全命中时上千文件也不会刷爆主线程。
- **可取消**：`lifecycleScope` + `ensureActive()`，退出页面即中断，无泄漏。

---

## 三、双层缓存

```
get(key)
 ├─ LruCache 命中 ───────────────► 返回（微秒级）
 ├─ diskIndex 命中 ─► 回填内存 ──► 返回（微秒级）
 └─ miss ─► 真解析 ─► put() 两层 ─► flush() 批量落盘
```

**缓存 Key**：`SHA-256(URI + "|" + lastModified + "|" + size)` 取前 32 位。

文件被替换、重新下载或编辑后，三元组必然变化 → 旧缓存自动失效。
不需要任何显式失效逻辑，也不可能读到过期元数据。

其它设计点：

- 磁盘层用 `org.json` 手写序列化，**不引入 Gson / Moshi**，也无反射，`R8` 无需额外规则。
- 落盘为**原子写**（先写 `.tmp` 再 rename），进程被杀不会留下截断的 JSON。
- 解析**失败的结果不入缓存**，避免一次偶发 I/O 错误被永久固化。
- 重命名成功后会把元数据**挂到新 URI 的 Key** 上，否则改完名下次扫描会全部重新解压。

---

## 四、格式兼容与零依赖解析

| 格式 | 策略 |
|---|---|
| `.apk` | `ParcelFileDescriptor` → `/proc/self/fd/N` 交给 `PackageManager.getPackageArchiveInfo()`，**零拷贝**；个别 ROM 不支持时回退到 `cacheDir` 临时复制 |
| `.apkm` | `ZipInputStream` 流式扫描；内嵌 `info.json` 供元数据，`base.apk` 始终抽出供图标 |
| `.apks` | 同上，兼容 `base-master.apk` 与 SAI 的 `meta.sai_v2.json` |
| `.xapk` | 额外兼容 APKPure 的 `manifest.json` |

`base.apk` 候选优先级：`base.apk` > `base-master*` > 非 split 包 > 其它，命中最高优先级即停止扫描。

应用名通过设置 `ApplicationInfo.sourceDir / publicSourceDir` 后调 `getApplicationLabel()` 拿到，
这样才能正确解析资源表里的 `@string/app_name`，而不是只拿到包名。

**应用图标**（借鉴 APK-Info / APK Explorer & Editor 等同类工具）：同一路径下再调
`PackageManager.getResourcesForApplication()` 加载归档的资源表，取 `appInfo.icon` 编码为 PNG，
按**同一缓存 Key** 另存为 `cacheDir/icons/<key>.png` —— 与元数据缓存失效时机完全一致；
重命名成功后图标随 Key 一起迁移，不会因改个文件名就丢。个别 ROM 上
`getResourcesForApplication` 对未安装 APK 会抛异常，此时**静默放弃图标**，绝不影响元数据主流程。
列表端由 `AppIconLoader` 采样解码 + LruCache，滚动不卡顿。

**Bundle 也带图标**：`.apkm / .apks / .xapk` 即便命中内嵌 `info.json` / `manifest.json` 快速通道，
也会按优先级抽出 `base.apk` 补一次图标 —— 图标存在资源表里，manifest 给不了。代价是这类包
多一次 base.apk 解压（首次解析慢一点，之后缓存命中秒回）；base.apk 缺失或损坏时回退到纯
manifest 元数据（无图标），不会因此读不出名字。

---

## 五、零权限的 SAF 方案

`AndroidManifest.xml` 里 **没有任何 `uses-permission`**。

- 目录授权：`ACTION_OPEN_DOCUMENT_TREE` → `takePersistableUriPermission`（跨启动保留）
- 目录枚举：`DocumentsContract.buildChildDocumentsUriUsingTree` + 一次 `query` 批量取
  documentId / 名称 / 大小 / 修改时间。比 `DocumentFile.listFiles()` 快一个数量级
  （后者每个文件一次 IPC）
- 重命名：`DocumentsContract.renameDocument`

因此天然适配 Android 10 ~ 15 分区存储、Android TV、以及 USB OTG 挂载盘。

**记住 OTG 设备**：可移动卷（OTG U 盘 / SD 卡）的树 Uri 以 `USB:xxxx-yyyy` 之类的**卷 ID**
打头，同一设备重插后保持不变。App 会按卷 ID 记住**上次使用的文件夹**，下次插入同一
U 盘并授权（哪怕只选了根目录）后，若记忆目录落在本次授权子树内，会弹窗询问是否恢复：

- 恢复 = 直接把 tree Uri 指向记忆的子目录（父级树授权天然覆盖，无需二次授权）；
- 重启恢复判断用「覆盖」而非精确相等：`takePersistableUriPermission` 挂在父级，子目录重启后依然可用；
- 记录按卷 ID 隔离，换一个 U 盘不会串；主存储（`primary`）不参与记忆。

**选择器默认定位上次目录**：拉起系统目录选择器（`ACTION_OPEN_DOCUMENT_TREE`）时携带
`DocumentsContract.EXTRA_INITIAL_URI`（API 26+ 生效；低版本与「目录已不存在」时系统忽略
该 extra 并回落默认位置，不报错），不用每次授权都从根目录一路翻路径。取位优先级
（`ApkListViewModel.pickerInitialUri`）：① 当前目录在可移动卷上 → 直达该卷 OTG 记忆的
**更深子目录**（即使当前目录恰好是卷根）；② 否则回落上次授权目录；③ 本会话没有生效目录
但某可移动卷的持久化授权仍覆盖其记忆目录时（如启动时卷未插入、`tree_uri` 被清但记忆仍在）
→ 同样直达记忆目录，卷重插后**无需重新授权**。决策是纯函数 `core/PickerInitialResolver`、
卷运算在 `core/VolumeLogic`，均零 Android 依赖；测试覆盖见下节「测试分层」。

已知限制：个别设备重插后卷 ID 会变，此时无法识别为「同一个 U 盘」，记忆静默失效（行为等同旧版）。

---

## 六、命名规则与防覆盖

可拼接字段：`应用名`、`版本号`、`VersionCode`、`包名`、`自定义前缀`、`原文件名`、`格式`、
`日期`、`文件大小`（紧凑格式 `12.3MB`）、`最低系统版本`（`API 21`）、`目标系统版本`（`API 34`）。
**Chip 的勾选顺序即拼接顺序**；分隔符可选 `_` `-` 空格 `.` 或无。

新增字段沿用 `NameComposer.Field` 枚举的 id 序列化，旧规则配置自动兼容，无需迁移。

清洗流水线：

1. 剔除 `\ / : * ? " < > |` 与全部控制字符
2. 折叠连续空白与重复分隔符，去掉首尾的 `.` 和空格（Windows / FAT 会静默截断）
3. 规避 `CON` / `PRN` / `LPT1` 等保留名
4. 按 **UTF-8 字节数**截断（默认 200），且绝不从多字节字符中间切断

冲突消解由 `ConflictGuard` 负责，**大小写不敏感**（FAT/exFAT 不区分大小写，
按大小写区分会在 U 盘上直接覆盖文件）：

```
AppName_v1.0.apk
AppName_v1.0_1.apk
AppName_v1.0_2.apk
```

文件自身的原名会先 `release()` 让位，避免「A.apk → A.apk」被误判成冲突而变成 `A_1.apk`。

---

## 七、列表交互（筛选 / 排序 / 搜索 / 多选 / 手动命名）

列表的全部派生数据都由 `vm/ListProjection.kt` 的纯函数产出，`ViewModel` 只负责持有原始
`List<ApkItem>` 与交互意图，UI 层拿到的永远是**已经算好的** `List<ApkRow>`：

```
ApkItem 原始集合
  └─ overlayManual()   叠加手动命名（覆盖规则产物，SKIPPED → PARSED）
      └─ project()     筛选（状态 + 关键词）→ 排序（名称/大小/时间/状态/版本，可反向）
          └─ toRows()  组装 ApkRow（选中态、手动态、行状态）
              ├─ countBy()        各状态计数
              └─ groupByStatus()  TV 端按状态分行
```

- **筛选**：按行状态（待改名 / 无需改 / 已完成 / 失败 / 待解析 / 重复）叠加关键词，关键词同时匹配
  文件名与包名且忽略大小写；两个条件是**交集**关系，空关键词直接跳过匹配。
- **重复包检测**（借鉴 APK 管理类工具的 dedupe）：同 `packageName` + 同 `versionCode` 视为
  同一应用的同一版本，组内**全部成员**都会被标记并计入「重复」chip —— 方便对比后自行决定留哪个；
  判定只看包名与版本号，与文件大小、归档格式无关，未解析 / 解析失败的文件不参与。
- **排序**：排序引擎（`ListProjection.sortRows`：名称 / 大小 / 时间 / 状态 / 版本五键、
  **版本自然序**——`1.9` 排在 `1.10` 前、`v1.2.3` 与 `1.2.3` 视为相等、空版本沉底；
  相等时统一按 `documentId` 兜底保证**结果稳定**）保留在共享逻辑层；
  手机端排序 chip 行（名称 / 大小 / 时间 / 状态 / 版本）**已移除**，
  列表按持久化的排序偏好展示（未设置过即默认扫描顺序）。
- **视图偏好持久化**：排序维度与方向存 `SharedPreferences`，重启后保留；筛选维度同样持久化，
  但只在**手机端**恢复（TV 端无筛选入口，恒为「全部」，不会被旧筛选锁住）。
- **多选**：全选仅作用于**当前可见（已筛选）**的行；被筛选隐藏但仍处于选中态的行会被单独统计为
  `hiddenSelectedCount`，执行前二次确认会明确提示「另有 N 项被筛选隐藏但仍会被改名」，
  避免用户在筛选视图下误伤看不见的文件。
- **手动命名**：`NameComposer.sanitizeManualName()` 先清洗（剔非法字符、补扩展名、按 UTF-8 字节
  截断），`validateManualName()` 再按 **空 → 全非法 → 保留名 → 超长 → 冲突** 的固定顺序校验。
  冲突**不会**自动加 `_1`，而是直接报错交还用户决定 —— 手动命名的语义是「我就要这个名字」，
  静默改名比报错更危险。大小写比较一律走 `Locale.ROOT`，规避土耳其语 `i/I` 陷阱。

---

## 八、Android TV（Leanback）

手机与 TV 共用同一个 `ApkListViewModel`，**TV 端不直接调用任何业务类**
（`SafScanner.scan` / `MetaExtractor` / `NameComposer.compose` / `ApkMetadataCache` 一律经由 ViewModel），
因此两端的扫描、解析、命名、重命名行为天然一致，不存在「手机能改 TV 改不了」的分叉。

- 入口分离：`MainActivity` 只保留 `LAUNCHER`，`TvActivity` 只保留 `LEANBACK_LAUNCHER`，
  两者互不干扰；`TvActivity` 继承 `FragmentActivity` 而非 `AppCompatActivity`
  —— `Theme.Leanback` 继承自 `Theme.Material`，套 AppCompat 会在启动时崩。
- 界面结构：`BrowseSupportFragment` 固定四行 —— 操作行（选目录 / 重新扫描 / 预览 / 执行 / 设置）、
  待改名、无需改+待解析、已完成+失败。行数固定意味着刷新时只需
  `ArrayObjectAdapter.setItems(newList, diffCallback)` 局部 diff，**不做 `clear() + addAll()`**，
  遥控器焦点不会在每次刷新后被打回第一项。
- 二次确认：`TvConfirmFragment` 的默认焦点强制落在「取消」上。遥控器的确认键很容易连按，
  默认焦点放在「确认执行」等于把批量改名做成了一次误触。
- 目录选择器可用性：通过 `try/catch ActivityNotFoundException` 探测，**不用 `resolveActivity`**
  —— API 30+ 的包可见性限制会让 `resolveActivity` 在没有 `<queries>` 的情况下恒返回 null，
  而本项目坚持零权限、零 `<queries>`。部分 TV ROM 确实没有 SAF 选择器，此时给出明确提示而不是静默失败。

---

## 九、构建

```bash
# Android Studio Ladybug+ 直接 Open 本目录即可
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # 全部单测（含 Robolectric）
```

- compileSdk 35 / targetSdk 35 / minSdk 21 / JDK 17 / Kotlin 1.9.24 / AGP 8.5.2 / Gradle 8.7
- 仅依赖 AndroidX + Material + kotlinx-coroutines + `androidx.leanback`，无解析类第三方库；
  测试仅额外引入 `org.robolectric:robolectric:4.14.1`（`testImplementation`）。
  `androidx.leanback:leanback:1.0.0` 自身 `minSdk 17`，低于本项目的 21，**不会抬高最低版本要求**；
  它只被 `tv/` 包引用，手机端不加载任何 leanback 类。
- 构建环境相关目录：`.buildcheck/` 存放本地隔离的 Temurin JDK 17、Gradle 8.7 以及
  `debug.keystore`（本地 debug 签名用，避免与其它 Gradle/Studio 实例争用 `~/.android` 的文件锁）；
  `gradle/` 是 Gradle Wrapper 脚本与配置（构建必需，不可删除，否则 `./gradlew` 无法运行）。

### 测试分层

全部单测统一跑在 **Robolectric 沙箱**下（`testOptions.unitTests.isIncludeAndroidResources = true`）：

- **为什么**：`ApkItem.uri` 是非空 `android.net.Uri`，纯 JVM 上 AGP 的 mockable android.jar
  无法构造实例。早期方案是在测试源集放一个同名的 `android.net.Uri` 替身，但 Kotlin 测试的
  `Uri.parse` 会被编译成 `Uri.Companion.parse`，而 Robolectric 沙箱加载真实 Java `Uri`
  （无 Companion 字段），运行抛 `NoSuchFieldError`，还得套一层 Java 工厂变通。现在**删除替身**、
  全部测试跑 Robolectric，Kotlin 测试代码直接 `Uri.parse` 构造真实 Uri，无任何变通层。
- **分工**：业务类（`NameComposer` / `RulePrefs` / `ListProjection` / `VolumeLogic` /
  `PickerInitialResolver`）本身仍保持零 `android.*` import（R5），它们的测试也是纯逻辑断言，
  只是运行时需要真实框架来构造 `ApkItem`；`ApkListViewModel*` 测试额外覆盖
  `DocumentsContract` / `ContentResolver.persistedUriPermissions` 等真实框架能力。
- **代价与并行**：每个测试类启动一次 Robolectric 沙箱（约 1~2s），全量 187 例约 11s（测试任务）；
  runner 与模拟 SDK 统一在共享基类 `RobolectricTestBase`（`@Config(sdk = [35])`，子类继承）。
  测试任务按 `maxParallelForks` 并行（默认 = 核数/2、封顶 4，CI 可用 `GRADLE_TEST_PARALLEL_FORKS`
  环境变量覆盖；每个 fork 独立限堆 1g）。本机 6 核实测并行与串行相当（瓶颈在每类沙箱插桩与
  fork 启动开销），多核 CI 上可显著缩短全量耗时。
- 依赖：`junit:junit:4.13.2` + `org.robolectric:robolectric:4.14.1`。
- 测试源集不再有同名的 `android.net.Uri`，`kotlin.incremental` 已恢复默认（增量编译开启）。

---

## 十、License

本项目以 [Apache License 2.0](LICENSE) 开源，Copyright 2026 reason195。
你可以自由使用、修改与再分发，但需保留版权声明与许可文本；按需
[提交 Issue](https://github.com/reason195/ApkRenamer/issues) 或在
[GitHub Discussions](https://github.com/reason195/ApkRenamer/discussions) 讨论。
