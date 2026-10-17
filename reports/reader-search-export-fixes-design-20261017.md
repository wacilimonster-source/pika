# PiKA · 阅读器卡顿 / 读完提示 / 下载导出 / 搜索回顶 —— 检查结论与产品设计

日期：2026-10-17 ｜ 状态：**已实施（v1.8.0, build 82）** ｜ 基线：main @ 28bafea（v1.7.2, build 81）
建议版本号：**v1.8.0（build 82）**——含 1 项新功能（P3 导出）与多项行为修复，不用 patch 号。

> 实施记录（2026-10-17，按 P4 → P1 → P2 → P3 顺序，每项一个提交）：
> - P4 `dbb42c4`：多词发布尊重当前页 + 回顶两段式（tick 内容落地后强制兑现）；
>   SearchScreen 移除 VM 手工滚动恢复；`beginSearch` 统一四入口归位 filterPage。
> - P1 `5fd814b`：删除组合期物化 rows（前缀和 + 二分，新增 `WebtoonRows` 纯函数，
>   配 6 条 JVM 单测）；页位镜像收集器替代组合期页位读；切片缓存批量化；
>   Coil 内存缓存显式 32%。
> - P2 `54145e2`：滚动流结束区行 + 翻页虚拟结束页（`pageCount = pages.size + 1`）；
>   结束卡片；一次性 Snackbar；`effectivePageNow()` 统一钳制事件期页位。
> - P3 `35834f2`：`DownloadExporter`（SAF 流式 zip + pika.json schema=1）；
>   DownloadScreen 导出按钮 + 部分完成确认 + 进度卡/取消。
> - 验证：`:app:compileDebugKotlin` / `:app:testDebugUnitTest`（含新增 WebtoonRowsTest
>   6 条全绿）/ `:app:assembleRelease` 通过；实机验收清单见 §6（无连接设备，
>   UI 行为验收待用户实机执行）。
> - 实施相对设计的两处偏差：①P1 深页渐进填充（publishDisplay 在深页改为
>   "切片有真实变化才替换"，优于设计稿的直接静默跳过）；②P2 结束卡片直接
>   提供「下一话/返回」按钮（设计稿为点击区域跳转）。


---

## 0. 问题清单与检查路径

| # | 用户反馈 | 检查方法 | 结论 |
|---|---|---|---|
| P1 | 漫画页数太多，查看时有时会卡住 | 阅读器全链路代码走读（ReaderScreen / ReaderViewModel / WebtoonSplitPage / PiKAApp） | 确认，2 个根因（结构性 + 内存级） |
| P2 | 看漫画看到最后以后没有提示 | 阅读器尾部行为走读 + 全仓 grep（结束页/Snackbar/下一话） | 确认，功能完全缺失（仅数据层已读完标记） |
| P3 | 下载的漫画需要支持导出 | DownloadManager / DownloadScreen 走读 + 全仓 grep（导出/SAF/zip） | 确认，功能不存在，需从零设计 |
| P4 | 搜索里翻页以后没有默认从页的最上方开始 | SearchScreen / SearchViewModel / PaginationBar / ComicGridView 走读 + git 历史比对（v1.5.36/v1.5.39） | 确认，2 处硬缺陷 + 2 处薄弱链路 |

无实测设备（adb 无连接），以上均为代码级结论；实现后需真机验收（见 §6）。

---

## 1. 检查结论（代码级根因）

### P1 页数多时卡顿

**根因 A（主）——滚动流「页→行」换算在主线程全量重算 + 整屏重组级联。**

`ui/reader/ReaderScreen.kt:235-273`：

```kotlin
val rows: List<Pair<Int, Int>> = if (scrollMode) {          // ← 组合期直接构建
    buildList {
        for (p in pages.indices) {
            val n = (sliceCounts[p] ?: 1).coerceAtLeast(1)
            repeat(n) { s -> add(p to s) }                   // ← O(总切片数) 分配
        }
    }
} else { ... }
```

三个级联：

1. **`rows` 在每次重组时全量重建**（不是 `remember` 的派生）。
2. `sliceCounts` 是 `mutableStateMapOf`，而每个切片图片加载完成时都会上报切片数
   （`WebtoonSplitPage.kt:92-101`——哪怕 n==1 也上报，`ReaderScreen.kt:730-738` 每次都写 map）。
   因此**每解析一张图片 = 触发整个 ReaderScreen 重组 + rows 全量重建 + rowPrefix 重算**。
3. `ReaderScreen.kt:325-329` 在组合期直接读 `scrollVisiblePage`/`pagerState.currentPage` 定义
   `currentPage`，且 `LaunchedEffect(currentPage)`（:373）把页码作为 effect key 在组合期求值——
   **每跨一行/每翻一页就整屏重组一次**。

代入量级：300 页章节平均 3 屏 ≈ 900 行；快滚时 LazyColumn 预组合窗口内十来张图陆续解析，
每张解析触发上述全链路。页数少时总开销可忽略，**页数多时 per-update 成本线性放大，
表现为掉帧/冻结感，与"页数多才卡"完全吻合**。

`rowPrefix` 的 `derivedStateOf`（:248-256）虽然把前缀和从"每次滚动回调重算"优化掉了，
但它订阅的是 **所有** `sliceCounts[p]` 条目——单个写入仍触发 O(N) 重算。IntArray 重算本身
便宜（μs 级），问题在于连带的全屏重组与 rows 重建被同一批写入反复拉动。

**根因 B（次）——Coil 内存缓存为默认配置，超高分辨率长图解码反复化。**

`PiKAApp.kt:43` 的全局 ImageLoader 未配置任何缓存参数；Coil 默认内存缓存为应用内存类
的 ~25%，本地解码上限 8192×1080 ≈ 35MB/张（`WebtoonSplitPage.kt:211-231`）。在
memoryClass=256 设备上默认缓存仅够驻留约 2 张位图，快滚长章时必然**持续解码→驱逐→GC 抖动**。

**伴随项（低优先）**：`ReaderScreen.kt:730-738` 中 `WebtoonSliceCache.putAll` 共用一次
字符串合并 + SP write；当前每解析一页执行一次，长图章节下写入频率高（虽 apply 异步，
但 merge 的 join 是 O(既有条目) 同步开销）。批次化即可。

### P2 看到最后没有提示

- `ReaderScreen.kt:461-475` `pageTurn`：翻页模式到达尾页后继续点右/上键被
  `coerceIn(0, pages.size-1)` **静默吞掉**，无任何反馈；滚动流滚到底部后同理（不能再滚）。
- 两种阅读模式均**没有结束页/结束区**：滚动流末尾就是最后一张图（:714-741），
  翻页模式末尾就是最后一页（:745-763）。
- `HorizontalPager` 到尾页后无法再滑动，也没有下一话引导；「下一话」按钮只存在于
  手动唤出的控制面板（:606-615）。
- 唯一的"读完"痕迹在数据层：`ReaderViewModel.saveProgress`（:214-222）写 finished 标记，
  供封面角标与筛选使用——用户在阅读器内完全无感知。

### P3 下载导出不存在

- 存储位置：`Android/data/com.pika/files/downloads/{comicId}/{order}/page_N.jpg`
  （`DownloadManager.kt:147-153`）+ `manifest.json` 清单（:508-531，含
  comicId/title/cover/order/epTitle/pageCount/source）。数据结构完整、无 SQLite 依赖，
  是很好的导出基础。
- UI：`DownloadScreen.kt` 仅有重试/删除/点击阅读，无任何导出入口。
- 全仓 grep `导出|import|export|ACTION_CREATE_DOCUMENT|ZipOutputStream`：
  现有可复用的基础是 `util/ImageSaver.kt`（MediaStore 单页保存相册）与设置页的
  `rememberLauncherForActivityResult` 用法先例；无整本导出/SAF 创建文档逻辑。
- 隐患确认：下载全部位于应用私有目录，**卸载即丢**，用户无自救通道。

### P4 搜索翻页后不回顶

现有链路（v1.5.36 引入、v1.5.39 调整）：`onBarPageChange` → `jumpToPage(p)`（内部
`_shouldScrollToTop.value++`）+ `listState.requestScrollToItem(0)` + `LaunchedEffect(shouldScrollToTop)`
二次 `scrollToItem(0)`（SearchScreen.kt:108-112, 249-263; SearchViewModel.kt:484-495）。

理论路径都在，但存在 2 处确定性缺陷与 2 处薄弱环节：

1. **多词搜索后台进度会把用户正在看的页"打回"第 1 页内容**（硬缺陷）
   `SearchViewModel.publishDisplay`（:434-446）与 `publishFinalResult`（:449-459）始终发布
   `buildDisplay()` = 交集排序后的**前 20 条（第 1 页切片）**，完全不感知用户当前页
   `_currentPage`。多词搜索后台仍在补页时用户跳到第 2 页：列表里先出现第 2 页切片，
   随后任意一次交集扩展把 `_comics.value` 整表替换回第 1 页内容——内容变换时
   LazyVerticalGrid 按 item key 保留滚动位置，用户看到的是"翻页了但停在半中间/内容不对"。
   同理 `publishFinalResult` 在后台拉完的瞬间也会覆盖用户所在页。
2. **回顶是"请求一次、尽力而为"，没有落点保证**（硬缺陷的另一半）
   单词搜索 `search()` 会把 `_comics.value` 清空 → 网格被移出组合、换成"搜索中…"占位；
   `requestScrollToItem(0)` 是对 state 的"待执行请求"，`LaunchedEffect(shouldScrollToTop)`
   里的 `scrollToItem(0)` 是挂起等待下一次测量。两条路都依赖"下一次恰好发生的测量"，
   在 加载中网格反复挂载/卸载、数据二次落地、`shouldScrollToTop` 未与数据落地对齐 的
   组合下没有兜底——新数据就位后无人再保证回顶。
3. （薄弱）滚动恢复 effect 的 key 是**普通 var**：`LaunchedEffect(viewModel.isScrollStateRestored)`
   （SearchScreen.kt:124-129），`isScrollStateRestored` 是 VM 普通布尔而非 State，
   LaunchedEffect 不会因它变 true 而重启，恢复全靠"恰好还有别的重组发生"。
4. （薄弱）`filterPage` 与 VM 页码双轨：历史词/热搜词/搜索框触发 `search(kw, 1)` 的
   三处入口（SearchScreen.kt:296-334, 141-144）都不重置 `filterPage`，筛选模式下
   页码指示与内容可能错位。

git 佐证：v1.5.36 同步给分类页做了同样改造；分类页（CategoryScreen.kt:310-323）没有
"加载中清空列表"行为，因此受缺陷 2 影响更小，也与"搜索里最明显"的反馈吻合。

---

## 2. 产品设计

### P1 阅读器大章节性能

**目标**：500+ 页/900+ 行的章节顺滑滚动，主线程无 O(总行数) 的重复工作；解码不反复抖动。
**不动的东西**：切片渲染方案、解码上限策略、手势方案、进度/恢复逻辑语义全部保持。

方案（三步，全部在 `ReaderScreen.kt` / `PiKAApp.kt` 内，代码量约 +40/-25）：

1. **换算结构惰性化：删除 `rows` 物化列表，改为前缀和 + 槽位二分。**
   - `rowPrefix` 保持 `derivedStateOf`（O(N) 重建 IntArray，μs 级，保留）。
   - `totalRows = rowPrefix[pages.size]`，`LazyColumn items(count = totalRows)`。
   - itemContent 内用现有 `rowToPage(row)` 的二分求页 p，`sliceIndex = row - rowPrefix[p]`
     （新增 `fun sliceOfRow(row: Int): Pair<Int,Int>` 替代 `rows[index]`）。
   - 收益：切片上报/滚动不再做 O(总行数) 列表分配与重建；derivedStateOf 值相等时
     不会无效化读者（item 数不变的上报不再引发列表级重组）。
2. **ReaderScreen 组合期"零页位读取"。**
   - `currentPage` 改由**单一 snapshotFlow 收集器**驱动：一个 `LaunchedEffect(pages.size, scrollMode)`
     内 `snapshotFlow { if (scrollMode) scrollVisiblePage else pagerState.currentPage }.collect {`
     写入 `remember { mutableIntStateOf(0) }`、调 `preloadNearby`、其余消费者全部改为
     事件期读取该 state（模式切换的 `target = currentPage`、ON_STOP/dispose 兜底、长按保存
     本来就是事件期读取，语义不变）。
   - 移除 `LaunchedEffect(currentPage)` 键驱动的预加载（改为上述收集器内调用），消除
     每翻页一次的协程重启链。
   - `ReaderControlPanel(pageIndex=…)` 保持现状：面板隐藏时内容不组合，不构成系统性读取。
   - 收益：跨行/翻页不再触发整屏重组；切片上报窗口内的重组收敛到"读者数可数"的局部。
3. **Coil 内存缓存显式配额。**
   - `PiKAApp.kt`：`memoryCache { MemoryCache.Builder(this).maxSizePercent(0.32f).build() }`，
     取值依据：35MB/张 × 默认 0.25 在主流 192-256MB 内存类设备恰好 2-3 张，0.32 抬到
     3-4 张，覆盖"前页回看 + 当前 + 预载 2 页 + LazyColumn 预组合同页多切片"的最坏窗口，
     不至于挤压系统。磁盘缓存维持默认。
   - `respectCacheHeaders(false)` 不做——哔咔图片缓存头本就宽松，改动无收益。

设计取舍（明确不做）：
- 不改解码上限体系（8192/6144/4096 已按内存分级，单图上限与预取 key 一致是既定设计）。
- 不加"分批加载章节页数"的分段机制——换算惰性化后兜得住千页级；分段会让进度/恢复复杂化。
- 恢复定位的 3 秒校正循环保留（行为问题不属于本卡顿根因；其写入已受 restoring 挂起保护）。

伴随项：`WebtoonSliceCache.putAll` 改为**章级批量**——本地 map 累积，切章/退出阅读器的
`DisposableEffect`（ReaderScreen.kt:363-370，已有 onDispose 兜底点）一次性落盘。
删掉 `onSliceCountResolved` 里的即时 SP 写。

### P2 读完提示与"结束页"

**目标**：读到本话末页有明确反馈与出口；读到全书最后一页有庆祝与收束；不打断阅读。

方案：滚动流尾部追加**结束区 item**；翻页模式追加**虚拟结束页**；两者共用一套判定与文案。

1. **统一判定**：`isAtChapterEnd = effectivePage >= pages.size - 1 && 已滚到底部（滚动流）
   /currentPage == 末真实页（翻页）`。`effectivePage = min(currentPage, pages.size - 1)`，
   全部进度写盘/面板页码/长按/滑条范围都已基于钳制后值，不受虚拟页影响。
2. **结束区/结束页内容**（同一 `ChapterEndCard` composable，两处复用）：
   - 有下一话：标题"本话已读完"，主按钮**「下一话 · {下一话标题}」**（触发现有 `switchTo`），
     次按钮「返回」（`onBack`）。
   - 已是最后一话：标题**"全本已读完"**，主按钮「返回」，附一行"已完成全部 N 话"。
   - 结束卡不占解码资源（无图片请求），背景用主题色 + 简单图形。
3. **翻页模式**：`pagerState` pageCount = `{ pages.size + 1 }`，第 `pages.size` index 渲染
   `ChapterEndCard`；从末真实页继续右滑/点右进入结尾页；在结尾页点左/左翻回最后一页。
   `pageTurn` 不再把末页当死路（zone=+1 时允许 animate 到结尾页）。切章时
   `LaunchedEffect(vmOrder)` 里补一次 `pagerState.scrollToPage(0)`（现有置空逻辑的显式缺口）。
4. **滚动流**：`totalRows += 1`，末行渲染 `ChapterEndCard`（全宽，item key `"end_card"`）。
   滚到底自然出现；`rowToPage` 对越界行返回 pages.size-1，不污染页码状态。
5. **一次性轻提示 Snackbar**（与结束卡并存，覆盖"后台挂着没看结尾"的场景）：
   进入会话首次满足 `isAtChapterEnd` 时弹一次（`remember(vmOrder, pages.size)` 标志位）：
   - 有下一话："本话已读完"，action「下一话」（调 `switchTo`）。
   - 最后一话："全本已读完 🎉"，无 action。
  Snackbar 走现有 `snackbarHostState`（:546-548），与续读提示互不冲突（不同时点出现）。
6. **数据层已就绪无需改**：`saveProgress` 的 finished 标记逻辑保持；结束卡出现即代表
   同一状态已被 VM 落盘（last chapter + last page 语义一致）。

实现落点：`ReaderScreen.kt`（rows/pagerCount/pageTurn/ChapterEndCard/snackbar）、
`ReaderViewModel.kt`（暴露 `hasNextChapter`/`nextChapterTitle` 派生值，删除现有面板内
`hasNext = currentChapterIndex in 0 until sortedChapters.size - 1` 的重复计算即可直接迁移语义）。

### P3 下载导出（新功能）

**目标**：用户能把已下载的漫画整体导出为标准 ZIP（含元数据），落到任意 SAF 可写位置；
流程可进度、可取消、可失败重试。卸载前/换机前/备份场景可用。

1. **格式规格（v1）**
   ```
   《{安全化标题}》.zip
   └─ {安全化标题}/
      ├─ pika.json                    # 元数据（见下）
      ├─ cover.jpg                    # 出现条件：本地存在封面缓存时导出，否则仅留 URL
      └─ chapters/
         ├─ 0001-{安全化话标题}/
         │     ├─ page_1.jpg … page_N.jpg   # 与现盘目录一一对应（直接搬文件字节）
         └─ 000N-…/
   pika.json =
     { "app":"pika", "formatVersion":1,
       "source":"PICACG", "comicId":"…", "title":"…", "author":"",
       "coverUrl":"…", "exportedAt":…,
       "chapters":[ { "order":1, "epTitle":"…", "pageCount":N, "pages":{ "1":"page_1.jpg" } } ] }
   ```
   - 章节文件夹 4 位零序号前缀，保证文件管理器内排序稳定。
   - 文件名安全化沿用 `ImageSaver` 的非法字符替换（`util/ImageSaver.kt:27`）。
   - 只导出 **COMPLETED** 章；部分完成时弹确认："尚有 N 章未完成，仍导出已完成章节？"
   - 压缩：`java.util.zip.ZipOutputStream`（DEFLATED）**流式转写**，单张图
     BufferInputStream→ZipEntry，不驻留内存；零新依赖（zip/SAF 均为平台与现有依赖）。
   - 体积护栏：预估总字节 > 3.5GB 时提前提示"过大，建议分章导出"（Android ZipOutputStream
     无 ZIP64 保证，注释里写明）。
2. **入口与 UI**
   - `DownloadScreen` 每本漫画卡头部加**「导出」图标按钮**（`Icons.Filled.SaveAlt`），
     ≥1 章完成时可用。多本批量导出（整页"导出全部"）不做，保持单本语义，后续按需扩展。
   - 点击 → `ActivityResultContracts.CreateDocument("application/zip")`，建议名
     `PiKA-{安全化标题}.zip`；用户选目录/改名 → 开始导出。
   - 导出状态集中在**新单例 `core/download/DownloadExporter.kt`**：
     `StateFlow<ExportUiState>`（Idle / Running(comicId, 章/页进度, 已写入字节) /
     Done(name) / Error(msg)），同 UI 挂顶栏下方一条进度条 + 取消按钮；同一时刻仅允许
     一个导出任务（重复触发直接忽略并提示）。
   - 文案：
     进行中"正在导出《X》· 第 i/N 章"，完成"导出完成：《X》"，失败"导出失败：{原因}"
     （原因含 SAF 流关闭/权限中断/磁盘满三类常见判别）。
   - 完成不弹系统通知（避免与下载完成通知双轨），页面内 Snackbar 即可。
3. **实现落点**
   - 新文件 `core/download/DownloadExporter.kt`：`export(uri: Uri, tasks: List<TaskRuntime>)`
     挂 `Dispatchers.IO`；Job 句柄可取消；写入前校验 uri 可写（openOutputStream 失败即
     语义化报错）；半成品保护：流式写入由 SAF 管道保证，取消时 ultima writer close 后
     由系统侧产生不完整 zip 用户侧可见（文档注明：取消的导出产物不可用，重导即可）。
   - `DownloadScreen.kt`：launcher 状态 + 组卡 `onExport` 回调 + 顶部进度条卡。
   - `ComicDetailScreen` 不加入口（详情页承担"下载"心智，导出归"我的下载"统一管理）。

**非目标（后续 editions）**："导入还原"到本 App（pika.json 已为还原预留 source/comicId/
pageCount 映射）、跨设备互传、CSV/文件夹裸导出。v1 格式故意选择"可读目录 + 元数据"，
既是用户可读的产物，也是未来导入器可消费的输入。

### P4 搜索翻页回顶

**目标**：任何分页方式（条翻页/跳页/筛选切片/历史词/热搜/排序）后，内容呈现时视口必然
回到第 0 项；不再被后台加载把用户所在页的内容顶掉。

1. **多词发布尊重当前页（VM 侧，独立成立的最先修复）**
   - `publishDisplay`：`if (_currentPage.value > 1) return`——用户在深页时后台扩展不再
     触碰列表；交集扩展仅累积到 `_multiAllComics` / `_confirmedIntersectionIds`。
   - `publishFinalResult`：发布目标改为**当前页切片** `buildDisplayForPage(_currentPage.value)`，
     并按交集总量重算 `_totalPages`/`_endReached` 判定（沿用现有口径
     `_endReached = totalPages <= 1` 的补页链语义）。
   - 边界：若后台期间该页切片仍在变化、最终内容不足 20 条，按现状显示实际条数即可。
2. **回顶改为"未兑现请求 + 内容落地后强制兑现"（Screen 侧）**
   ```kotlin
   var appliedTopTick by remember { mutableIntStateOf(0) }
   LaunchedEffect(shouldScrollToTop, displayComics) {
       if (shouldScrollToTop > appliedTopTick) {
           listState.scrollToItem(0)          // 挂起 → 一定在下一次网格测量时兑现
           appliedTopTick = shouldScrollToTop
       }
   }
   ```
   - `jumpToPage` 内的 `shouldScrollToTop++` 语义保留；`onBarPageChange`/排序/筛选里的
     `listState.requestScrollToItem(0)` 保留为"立即位"（旧内容还在时先顶上去，消除闪留），
     新 effect 兜住"数据迟到时最终落在顶"—两段式互补。
   - 背景 `publishDisplay`（多词）触发 `displayComics` 变化但 tick 已兑现 → 不重复回顶；
     用户换页 tick++ → 兑现。筛选模式补页链追加数据（首项 ref 不变）也不会把用户拉顶——
     兑现只跟 tick 走，key 挂 `displayComics` 只是提供"落地后补执行"的时机。
3. **清扫薄弱点**
   - `isScrollStateRestored` / savedIndex 机制整体移除，改依赖
     `rememberLazyGridState()` 自带的 Saveable 恢复（Navigation Compose 会保存,
     v1.5.13 引入手工方案时本就双保险）。顺带删除 `DisposableEffect saveScrollState`、
     VM 三个成员与其注释；若需保守，退而求其次改为 StateFlow 键——设计倾向整段移除
     （少一套状态机），验收项单独列出。
   - `filterPage` 双轨收敛：`viewModel.search(kw, 1)` 的三个 UI 触发点统一先
     `filterPage = 1`（抽一个 `fun beginSearch(kw: String)` 本地 helper 替代三处直接调用）。
4. **验收口径**（见 §6）：单词第 1→N 页、跳页、多词后台加载中翻页、筛选模式切片翻页、
   加载失败重试后返回、详情返回恢复位置——六条链路逐一过。

---

## 3. 风险与实施顺序

| 顺序 | 项 | 风险点 | 缓解 |
|---|---|---|---|
| 1 | P4-1 多词当前页守卫 | 交集扩展后深页内容不再自动刷新——文案上"后台加载完成"提示补一条行内计数（multiLoading 行已有，补充"x/N 页已拉取"） | 现有 `_totalPages` 进度条已表达 |
| 2 | P1 改造 | 惰性换算一旦写错，页→行定位/跳页/修复全部错位 | 复用现有 rowToPage 的二分 + 保留 `rowPrefix`，只加 sliceOfRow；流水条目的 key 不变，行为面无新状态 |
| 3 | P2 虚拟结束页 | 页码写盘/面板显示/长按若未统一走钳制会读越界 | 统一 `effectivePage` 单点，其余路径全部改为取用；恢复逻辑（pendingRestore）不受影响 |
| 4 | P3 导出 | SAF 流中断 / 大文件 / 取消语义 | 流式 + 提前失败 + 取消产物不可用提示；单例状态机防并发 |
| 5 | P4 回顶 tick | 极端场景 tick 兑现后又被无关干扰拉离顶（无）| 无此风险：兑现后不再重写，行为与"到位一次"一致 |

建议提交切分（一次一 commit，各自可回滚）：
a) P4（VM 守卫 + Screen 兜底 + 清扫）；b) P1（换算惰性化 + 组合期零读 + Coil 缓存）；
c) P2（结束卡 + Snackbar）；d) P3（DownloadExporter + UI）。

## 4. 单元测试点

- `computeSliceCount` / `sliceOfRow`（新增）与 `rowPrefix` 一致性：随机 rows 数量下
  `sliceOfRow(rowForPage(p)).first == p`（复用 ComicRef 的 JVM 测试基建）。
- `SearchViewModel`：多词搜索深页期间 publish 调用不改变 `_comics`；完成时保持当前页切片。
- `DownloadExporter`：模拟 SAF Uri 写入（tmp 文件流）验证 zip 条目序列与 pika.json 可反串化。

## 5. 文案基准

- P2 Snackbar：`本话已读完` / `全本已读完 🎉`；action `下一话` / （无）
- P2 结束卡：`本话已读完` ＋ `下一话 · {标题}`；或 `全本已读完` ＋ `返回`
- P3 按钮提示 `导出`；对话框 `《X》部分章节尚未完成，仅导出已完成的 N 章？`

## 6. 真机验收清单

1. P1：打开 ≥500 页章节（本地已下载章更好），快速滚动 + 随机跳页 + 恢复定位，
   观察帧率（开发者选项 GPU 条形图，>16ms 帧占比应消失）；低内存设备（4096 档）复测。
2. P2：滚动流/翻页模式各自走到末页 → 结束卡出现、Snackbar 只一次、有下一话按钮可用、
   无下一话文案正确；末话末页角标"读完"出现。
3. P3：单本导出到下载目录、部分完成导出确认框、导出中取消、SAF 拒绝授权路径、
   导出产物在文件管理器打开核对目录结构与 pika.json。
4. P4：六条链路过测（见 P4-3），外加从详情返回时的位置保持。

## 7. 后续可选演进

- 导入还原器（消费 pika.json + chapters/）与"恢复缺失源"提示。
- 导出进度系统通知（长任务离场可见）。
- 阅读器"一次跳下一章"手势（右边缘快速连滑）——结束卡出现后体验自然衔接，暂不做。
