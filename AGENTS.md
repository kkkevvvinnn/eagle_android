# AGENTS.md — 给 AI 编码助手（与深入了解实现的人）的完整技术文档

本文档解释这个项目的**一切关键决策与不可破坏的约束**。修改代码前务必通读；
改了本文档描述的任何机制（结构、约定、工作流），必须同步更新本文档。

## 1. 项目是什么

**Eagle 图库浏览器（Android）**：在安卓设备上离线浏览由电脑端
[Eagle](https://eagle.cool/) 素材管理软件导出的 `.library` 图库。
用户用第三方同步工具（Syncthing 等）把 `.library` 目录原样同步到手机，
本应用通过 SAF 只读访问该目录，在本地 Room 建立索引后提供相册式浏览。

非目标：不修改图库任何文件（**严格只读**）、不上传任何数据（无网络权限）、
不兼容 Eagle 的写操作/标签编辑。

- 包名 `com.eagleviewer.app`，minSdk 26 / targetSdk 35 / compileSdk 35
- 语言 Kotlin，UI 全部 Jetpack Compose（Material 3），无 Fragment/XML 布局
- 仓库：https://github.com/kkkevvvinnn/eagle_android （public，MIT）

## 2. 构建、测试、发版、提交约定

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK（本地无签名环境变量时回退 debug 签名）
./gradlew :app:testDebugUnitTest  # 单元测试（纯 JVM，23 个左右，必须全绿才能提交）
./gradlew :app:installDebug       # 安装到已连接 adb 设备
```

- Gradle wrapper 固定 9.5.1（系统 gradle 9.6 与 AGP 8.13 不兼容，不要升级 wrapper 而不验证 AGP 兼容性）
- 依赖版本**只允许**写在 `gradle/libs.versions.toml`，`app/build.gradle.kts` 用 `libs.xxx` alias 引用
- **版本号管理**：每次有代码变更的提交都要在 `app/build.gradle.kts` 提升
  `versionCode`（+1）与 `versionName`（语义化：bugfix 走第三位，功能走第二位）。
  设置页会显示 versionName
- **提交约定**：用户要求每次代码修改后都提交并推送 GitHub；提交信息用**中文**，
  首行概括 + 分条列出要点 + 末尾注明版本号。禁止 `git commit` 之外的破坏性 git 操作（reset/rebase/push -f）
- **推送到手机**：只在用户明确要求时执行 `adb install -r`；
  adb 路径 `~/Library/Android/sdk/platform-tools/adb`（设备 vivo V2408A，
  安装确认弹窗可能导致 INSTALL_FAILED_ABORTED，唤醒屏幕重试即可）
- **CI/Release**：`.github/workflows/android-ci.yml`（push/PR 到 main 自动构建+测试）；
  `.github/workflows/release.yml`（push `v*` tag 自动签名构建并发 GitHub Releases）。
  签名材料在仓库 Secrets，本地 `release.keystore` 与 `credentials-*` 已在 `.gitignore`，**绝不能提交**
- 用户本机的 Eagle 图库 `/Users/cwk/Pictures/我的灵感.library` **只读分析用，禁止修改其中任何内容**

## 3. 架构总览

无 DI 框架：`EagleApp`（Application）持有手工容器 `AppContainer`
（`db` / `settings` / `scanner` / `items` / Coil `imageLoader`）。
单 Activity（`MainActivity`）+ Compose Navigation，四个目的地：
`setup`（选目录）、`grid`（瀑布流）、`settings`、`detail/{index}`（大图）。

**关键结构：`GridViewModel` 是 activity 作用域的共享 ViewModel**，
网格页与大图页消费**同一份** `pagingData`（`cachedIn(viewModelScope)`），
因此大图页的 HorizontalPager 与网格的筛选结果集天然一致，
从网格第 N 项进入大图即 `navigate("detail/$index")` 直接定位。

```
SAF tree URI ──► EagleScanner ──► Room (items + item_tag) ──► ItemRepository
                     ▲ 增量 diff(mtime.json)                      │ 动态 SQL
                     │                                            ▼
DataStore ◄── SettingsRepository              Paging3 (placeholders) ──► GridViewModel
(目录/多图库/筛选/列数/主题)                                      │         │
                                                  ┌───────────────┘         │
                                                  ▼                         ▼
                                            GridScreen               DetailScreen
```

### 文件地图（app/src/main/java/com/eagleviewer/app/）

| 文件 | 职责 |
|---|---|
| `EagleApp.kt` | Application + AppContainer（手工 DI），Coil 全局配置（GIF 解码器按 API 28 分流） |
| `MainActivity.kt` | 唯一 Activity；主题模式收集；NavHost；**启动时校验 SAF 持久授权** |
| `data/EagleItemMeta.kt` | `.info/metadata.json` 模型 + `parseMtimeJson`；`Palette`；`json` 单例（ignoreUnknownKeys） |
| `data/EagleScanner.kt` | 增量扫描器（见 §5.1，约束最多） |
| `data/ScanDiffer.kt` | diff 纯逻辑（JVM 可测） |
| `data/ItemRepository.kt` | `Filter`/`Sort` 模型、动态筛选 SQL、相似配色检索 |
| `data/SettingsRepository.kt` | DataStore 封装（目录、多图库列表、筛选 JSON、列数、主题、上次扫描时间、索引根目录） |
| `data/db/Entities.kt` | `ItemEntity`、`ItemTagCrossRef`（CASCADE）、`ItemWithTags`、投影类 |
| `data/db/ItemDao.kt` | 全部 DAO；**含 SQL_BATCH 分批封装** |
| `data/db/AppDatabase.kt` | Room 单例，`fallbackToDestructiveMigration` |
| `ui/GridViewModel.kt` | 共享 VM：筛选/分页/列数/多选/扫描状态机/**扫描互斥锁** |
| `ui/GridScreen.kt` | 瀑布流、顶栏（标题/搜索/多选）、捏合调列数、下拉刷新、分享 |
| `ui/FilterBar.kt` | 排序菜单、星级筛选、标签 BottomSheet（与/或）、相似配色 chip |
| `ui/DetailScreen.kt` | HorizontalPager 大图页 + 信息面板（色卡触发相似配色） |
| `ui/ZoomableImage.kt` | 缩放手势组件（见 §5.5 契约） |
| `ui/SettingsScreen.kt` | 多图库管理、重扫、主题切换、关于/版本/版权 |
| `ui/LibrarySetupScreen.kt` | SAF 选目录 + 首次扫描引导（复用共享 GridViewModel，不得自建 VM 实例——scanMutex 必须唯一） |
| `ui/Theme.kt` / `ui/Format.kt` / `ui/Components.kt` | 深浅色主题 / libraryDisplayName 等 / AppSnackbarHost |

## 4. Eagle `.library` 格式（只读契约）

```
xxx.library/
├── mtime.json    # { "<图片ID>": lastModified(ms), "all": 总数 }  ← 增量扫描的唯一 diff 依据
├── tags.json     # 标签使用历史（historyTags/starredTags），不是标签字典，本应用不用
├── metadata.json # 全局 folders/tagsGroups，本应用不用
└── images/<ID>.info/
    ├── metadata.json        # 单条元数据（核心）
    ├── <name>.<ext>         # 原图（ext 可能是 jpg/png/gif/webp...）
    └── <name>_thumbnail.png # Eagle 预生成缩略图（可能缺失）
```

单条 `metadata.json` 字段（`EagleItemMeta`）：`id`、`name`（不含扩展名）、`ext`、
`size`（字节）、`width`/`height`、`btime`（添加时间 ms）、`mtime`、`lastModified`、
`tags`（**名称字符串数组**，不是 ID——标签关联就是按名称匹配）、
`folders`、`isDeleted`（回收站）、`star`（**可选 0–5，未评分时不写出，默认 0**）、
`annotation`、`url`、`palettes`（`[{color:[r,g,b], ratio:..}]`，可能含 `$$hashKey` 等未知字段，
解析器必须 ignoreUnknownKeys）。

注意：`mtime.json` 的 `all` 字段是统计值不是条目；`mtime.json` 包含回收站条目
（总数口径要排除 `isDeleted`）。

## 5. 核心机制与关键不变量

> 以下每条都是踩过坑后确立的。**违反任何一条都会引入真实 bug**（括号内是后果）。

### 5.1 扫描（EagleScanner + GridViewModel）

1. **文档 URI 直接拼原始 documentId，绝不自行编码**：`buildDocumentUriUsingTree` 内部用
   `appendPath`（已核对 AOSP 源码），会对整个段做一次 URL 编码、provider 侧解码还原，
   `#`/`?`/空格/中文文件名都安全。若自行 `Uri.encode` 预编码，会被 appendPath **二次编码**
   （`%` → `%25`），所有含特殊字符的文件探测不到（1.7.0 曾因此回归，1.7.1 修复）。
   扫描器内含 URI 自修复：已索引条目 `imageUri` 含 `%25` 的自动补进重扫列表（幂等，可常驻）。
2. **mtime 为空映射时拒绝扫描**（抛 ScannerException）：同步工具写入中间态的 `{}`
   会让 diff 认为全库待删（全库索引被清空）。
3. **mtime 单条坏值跳过**：null/字符串/浮点用 `runCatching` 逐条容错（一条脏数据中断整个扫描）。
4. **扫描互斥**：`GridViewModel.scanMutex` 串行化一切扫描（并发扫描会把两个图库混入同一索引表）。
5. **扫描成功才持久化当前图库 URI**（`onLibraryPicked`/`switchLibrary`）：失败时保持
   旧 URI + 旧索引一致（否则标题新库、内容旧库的永久错配）。
6. **索引只对应"当前激活图库"**：单表单库设计，切库扫描的 diff 会删掉旧库全部条目
   （`toDelete = indexed − mtime`）。这是设计取舍，不是 bug。
7. **条目级跳过与自愈**：原图文件不存在（同步不全）的条目不索引、不计入 scanned，
   下次扫描 diff 自动重试；缺失/回收站条目累积到 `toRemove` 循环结束后**批量**删除
   （逐条单行事务在大库下极慢）。
8. **批次与节流**：upsert 每 200 条一批；进度回调每 20 条一次（逐条回调会让 UI 持续重组）。
   `ScanResult.total` 直接取扫描后 DB 实数（`itemCountNow`），不按 mtime 推算——
   回收站条目留在 mtime 中但永不进索引，且 mtime 未变的回收站条目不会再被扫描统计。
9. **不探测缩略图存在性**：`thumbUri` 总是直接存（省去每条一次跨进程 IPC），
   缺失时 Coil `onError` 触发 `useOriginal` 降级原图（GridCell 已有该路径）。
10. **`catch (e: Exception)` 之前必须先 `catch (CancellationException) { throw e }`**
    （结构化并发，取消不能被报成"扫描失败"）。
11. **索引必须绑定图库根目录（tree documentId），根目录不一致时全量重建**：
    DataStore 持久化 `indexedRoot`，扫描前与本次根目录比对，不一致先 `clearAll()` 再扫
    （mtime 校验通过之后才清空，失败时不持久化新根目录）。同一份图库换路径后
    mtime.json 内容完全相同，增量 diff 看不出任何变化，已索引条目的 URI 会永远指向
    旧目录（Coil 全部加载失败、重扫无效）——只能靠根目录比对触发重建。
    真实事故（1.7.3）：换同步工具后图库从 `/sdcard/eagle同步` 挪到
    `/sdcard/Pictures/ShotSync/eagle同步`，99 条迁移后新增的条目用新路径索引正常，
    165 条旧条目 URI 指向已删除的旧目录全部变灰块。

### 5.2 SQL 与分页（ItemDao / ItemRepository）

1. **一切 IN 列表必须分批（`SQL_BATCH = 400`）**：Android 8–11 的 SQLite 变量上限是 999
   （API 30+ 是 32766）。切库删除、多选分享的 id 列表都可能超限
   （→ `SQLiteException: too many SQL variables`，分享路径未捕获即崩溃）。
   用 `deleteItemsChunked` / `imageUrisForChunked`。
   （注意：Room 的 `@Insert(List)` 是逐行复用预编译语句，**不受此限**，不要画蛇添足。）
2. **动态 SQL 只允许参数绑定 + 枚举拼接**：`Sort.clause` 是枚举白名单；
   标签/评分/搜索词全走 `?`。文件名搜索要转义 LIKE 通配符（`%`/`_`/`\`，`ESCAPE '\'`）。
3. **Paging 必须 `enablePlaceholders = true`**：大图页要按全局 index 跳转任意页，
   关占位符整个跳转模型会崩。`pageSize = 60`、`initialLoadSize = 120`。
4. **相似配色**：`pagedItems` 的 `flow {}` 收集方在主线程——全库色板 JSON 解码 + 排序
   **必须** `withContext(Dispatchers.Default)`（万级库主线程秒级卡顿/ANR）。
   结果用 `CASE id WHEN ? THEN n` 保序，上限 `SIMILAR_LIMIT = 150`；
   `paletteDistance` 用 `minOfOrNull ?: MAX_VALUE`（残缺色板不能抛异常）。

### 5.3 网格（GridScreen）

1. **滚动性能**（不要回退）：GridCell 不加 `animateItem`（滚动时每进项都动画是卡顿主因）、
   Coil 请求 `crossfade(false)` + `.size(360)` + `Precision.INEXACT`、
   `ImageRequest` 用 `remember(item.id, useOriginal)` 缓存、
   视口外手动预取（snapshotFlow 监听可见末位，提前 enqueue 之后 12 项的 Coil 请求；
   staggered grid 在 foundation 1.8 没有 beyondBoundsItemCount API，勿找）。
2. **列数切换的视觉过渡**由整体 `gridAlpha` 淡入（0.3→1）负责；不要给逐项加位移动画
   （新组合的项无法参与位移动画，会"突然出现"，更割裂）。
3. **捏合调列数**：网格用 `StaggeredGridCells.Fixed(columnCount)`，列数由捏合直接决定
   （不要用 Adaptive——竖屏手机宽度不足时选 4 列实际只显示 3 列）。
   指针数变化的那一帧 `calculateZoom()` 失真（新手指 previousPosition==position
   产生 zoom 尖峰），必须跳过该帧并重置累积系数；累积系数限幅 (0.5, 2)，预览限幅 (0.7, 1.4)，
   提交阈值 >1.3 减列 / <0.75 加列。
4. **筛选变化不闪**：仅 `itemCount == 0 && refresh is Loading` 才显示转圈，有数据时后台刷新不换布局。
5. **搜索防抖**：`pagingData` 管线对 `nameQuery` 非空的筛选变化 `debounce(300)`（每击键重启 Pager 会卡）。
6. **标题逻辑**：`filter.isActive`（标签/评分/搜索/未标记/相似配色）才显示「筛选 n/n」；
   **排序不算筛选**，无筛选显示图库目录名。
7. **多选**：`selection` 空集合 = 非多选模式；多选模式下长按另一张图是 toggle 追加，
   **不是** 重置选择集。分享走 `ACTION_SEND_MULTIPLE` + `FLAG_GRANT_READ_URI_PERMISSION` + clipData，
   全程 runCatching（binder 超限/查询失败 → Snackbar）。

### 5.4 标签弹层（FilterBar.TagSheet）

1. `rememberModalBottomSheetState(skipPartiallyExpanded = true)`：部分展开锚点会在内容高度
   变化时重新停靠滑动（视觉=滚动重置回顶部）。
2. 头部行固定 `heightIn(min = 48.dp)`：「清除」「与/或」按钮出现/消失不改变 sheet 高度。
3. 标签区自持有 `rememberScrollState()` + `heightIn(max = 400.dp)`：点选标签重组后保持滚动位置。
4. 互斥矩阵：选中任一标签 → 清 `untaggedOnly` 和 `similarColor`；选「未标记」→ 清 `tags` 和
   `similarColor`；进相似配色 → 清 `tags` 和 `untaggedOnly`（MainActivity 的 onFindSimilar）。
5. 与/或切换仅在选中 ≥2 个标签时显示。

### 5.5 大图（DetailScreen + ZoomableImage）

1. **解码尺寸必须约束到屏幕像素**（`.size(displayMetrics)`）：全尺寸解码一张 4000×3000
   位图 ≈48MB，Pager `beyondViewportPageCount = 1` 并发 3 页必 OOM。
   手势放大只是像素插值，不需要原尺寸位图。
2. **手势契约**：单指且 scale==1 时不消费事件（让给 Pager 翻页）；双指或已放大时消费。
3. **平移边界按 Fit 实际显示尺寸逐轴计算**（`imageAspect` 参数，0=未知退化整屏估算）：
   某轴放大后仍小于屏幕则该轴禁止平移（否则图片可被完全拖出屏幕且无法翻页）。
4. **双指缩放做质心焦点补偿**：`base += (centroid - center - base) * (1 - newScale/oldScale)`，
   围绕屏幕中心缩放会让指尖内容漂移。
5. 返回键优先关闭信息面板（`BackHandler { if (showInfo) showInfo = false else onBack() }`）。
6. 原图加载期间底层先显示缩略图（`thumbUri`），crossfade 过渡。

### 5.6 应用装配（MainActivity / Settings）

1. **启动校验 SAF 持久授权**：`persistedUriPermissions` 不覆盖当前 URI 时路由到 setup
   （URI 失效的图库全是灰块且无任何提示）。三处选目录入口（setup/settings/addLibrary）
   的 `takePersistableUriPermission` 失败可以容忍（个别提供方不支持持久授权）。
2. **恢复竞态**：`filter`/`columnCount` 从 DataStore 异步恢复，`filterTouched`/`columnCountTouched`
   标记防止恢复值覆盖用户已做的修改。
3. `searchActive` 要随恢复出的非空 `nameQuery` 自动展开（否则结果被过滤却找不到搜索框）。
4. 主题模式：0 跟随系统 / 1 深色 / 2 浅色，DataStore 持久化，`EagleTheme(themeMode)` 即时切换。

## 6. 测试

纯 JVM 单测在 `app/src/test/java/com/eagleviewer/app/data/`：
- `EagleItemMetaTest`：真实样本解析 + mtime 容错
- `ScanDifferTest`：diff 逻辑
- `FilterQueryTest`：动态 SQL 组装（含 LIKE 转义）
- `ColorSearchTest`：色板距离 + CASE 保序 SQL + 残缺色板

约定：改 `data/` 层的纯逻辑必须配/更新测试；测试样本用真实 Eagle 导出 JSON 文本内联。
UI/手势无自动化测试，靠真机验证（构建通过 + 用户在手机上确认）。

## 7. 已知取舍（有意为之，不要"修复"）

- 索引不区分图库（单表对应当前激活库，切库即重建）；`removeLibrary` 不主动清索引
- 筛选变化后网格滚动位置重置（paging 重建，rememberSaveable 状态销毁）
- 相似配色忽略其他筛选条件（全库检索），上限 150 张
- 分享 URI 顺序按数据库返回序，不保选择顺序
- 详情页快速甩动跨页时逐页串行加载（Paging 占位符模式的固有限制）

## 8. 给 AI 助手的工作流提示

1. 改代码前先确认相关 §5 不变量；改完跑 `assembleDebug + testDebugUnitTest` 再提交
2. 版本号必升（见 §2）；提交信息中文；提交后推送 `origin main`
3. 只有用户明确说"推送手机"才 `adb install`
4. 发现新的"不可破坏约束"时，补进 §5 并告知用户
