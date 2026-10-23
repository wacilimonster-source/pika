# PiKA v1.9.0 · 体验缺陷修复 + 质感打磨 —— 产品设计

> 版本基准：v1.8.0 (build 82)，main @ 0bc5cdd
> 日期：2026-10-24
> 状态：产品设计（实施中）
> 范围决策（用户确认）：第一层缺陷 10 项全修 + 第三层质感 12 项全做 + 第二层仅「关注流真分页」。
> 缺陷编号沿用 `reports/pika-bug-scan-20261017.html`（BUG-xx），优化项沿用其 A 系列。

---

## 一、总览

| # | 项 | 来源 | 级别 | 一句话 |
|---|---|---|---|---|
| F1 | 离线优先读残章（.part 半成品） | BUG-01 | P1 | 下载中的章不再被当成本地完整章 |
| F2 | 章节列表失败清空已显示页面 | BUG-02 | P1 | 章节接口失败不影响本章阅读 |
| F3 | 切章竞态把旧章页码写进新章 | BUG-03 | P1 | 进度写入与章节严格一致 |
| F4 | 「我的评论」无入口 | BUG-04 | P1 | 死功能接线：路由 + 我的页入口 |
| F5 | 关注标签弹层不可滚动 | BUG-05 | P1 | 长词表可滚动 + 搜索过滤 |
| F6 | 阅读器首帧闪「加载失败」 | BUG-06 | P2 | loading 初始 true |
| F7 | 结束卡误报「全本已读完 · 0 话」 | BUG-07 | P2 | 章节未就绪时中性文案 |
| F8 | 更新弹窗下载期间锁死 | BUG-19 | P2 | 可关弹窗，下载转后台继续 |
| F9 | 登录/注册键盘遮挡 | BUG-22 | P2 | 表单可滚动 |
| F10 | 我的/收藏失败态无重试 | BUG-23 | P2 | 共享重试组件接入 |
| P1 | 关注流真分页 | D9 | — | 去掉 120 条硬截断，触底加载 |
| Q1 | 全局图片淡入 | A1 | — | Coil 全局 crossfade |
| Q2 | 骨架屏 | A2 | — | 首屏网格 shimmer 替代文字转圈 |
| Q3 | 页面转场动画 | A4 | — | NavHost slide+fade |
| Q4 | 列表增删动画 | A5 | — | animateItem 四处列表 |
| Q5 | 翻页预取 | A9 | — | Pager beyondViewportPageCount=1 |
| Q6 | 首次点击分区引导 | A7 | — | 阅读器一次性引导层 |
| Q7 | 阅读器长按分享 | D6余 | — | 长按菜单：保存 / 分享 |
| Q8 | 下载卡进度平滑 | A10 | — | 进度动画 + 速度 |
| Q9 | 完成通知直达详情 | A11 | — | 通知带封面 + 点按跳详情 |
| Q10 | 结果 Snackbar 带动作 | A12 | — | 导出附「分享」；失败附「重试」 |
| Q11 | 破坏性操作撤销 | A14 | — | 历史/关键词删除可撤销；单章删除加确认 |
| Q12 | 评论点赞 | D2 | — | 「赞 N」可点，乐观更新 |

---

## 二、阅读器缺陷（F1–F3、F6、F7）

### F1 · 离线优先读残章（BUG-01）

**现状**（`ReaderViewModel.kt:100-109`）：离线分支用 `listFiles().filter { startsWith("page_") && length>0 }` 自建判定——`.part` 半成品被算作一页（破图）；目录里哪怕 1/50 页也短路整个网络抓取（残章）；不随下载完成刷新。而章节抽屉的 `isDownloaded()`（`DownloadManager.kt:156-166`）已按任务状态 + 非 .part 文件 + 页数校验给出正确结论，两处 UI 自相矛盾。

**方案**：
- 离线门槛改为唯一复用 `DownloadManager.isDownloaded(comicId, order)`（IO 上下文内同步调用，读 `_tasks.value` + 目录枚举，成本与现状相同）；
- 本地命中时文件枚举补 `!name.endsWith(".part")`（双保险）；
- 未完整下载的章节一律走网络加载（含下载中/排队/失败态任务）。

**验收**：下载中点开该章 → 网络完整加载；下载完成后重进 → 本地完整页序；弱网下已完整下载的章 → 仍离线可读。

### F2 · 章节列表失败清空页面（BUG-02）

**现状**（`ReaderViewModel.kt:107-113,138-143`）：同一 try 内先 `_pages = chapterPages()` 再 `_chapters = chapters()`；章节接口失败（本 App 有专门的限流冷却，属常发）→ 外层 catch `_pages = emptyList()` → 已显示的图整屏变失败态。catch 本身必须保留（chapterPages 失败要清掉上一章残留防进度污染）。

**方案**：
- `_chapters.value = runCatchingCancellable { pageSource.chapters(comicId) }.getOrDefault(emptyList())`——章节失败静默置空（顶栏已有「第 N 话」兜底），绝不清 pages；
- 补一个轻量重试通道：VM 新增 `retryChapters(context)`（只重拉章节列表，不动 pages）；章节抽屉打开且列表为空时显示「章节列表加载失败 · 点击重试」行。

**验收**：本章图片已显示时断开网络触发章节失败 → 阅读不受影响；打开抽屉可见重试行并恢复。

### F3 · 切章竞态进度写错章（BUG-03）

**现状**：非最终路径 `saveProgress` 在**协程体内**读 `ref/currentOrder`（`ReaderViewModel.kt:204-208`）；防抖 1 秒 + IO 排队窗口内切章 → 旧章页码写进新章进度。另：切章会重启防抖链（`LaunchedEffect(vmOrder...)`），旧章最后 1 秒内的翻页无人保存。

**方案**：
- `saveProgress` 入口快照 `refNow/orderNow/pagesSize`（对齐 final 路径的既有正确写法），协程体只用快照——旧章晚到的写盘落旧章，天然正确；
- 切章兜底：`ReaderScreen.switchTo` 在调 `viewModel.switchChapter` 前，先按当前有效页调一次 `saveProgress(page)` + `recordRecentRead(page)`（recordRecentRead 本就在入口快照，正确）——堵住防抖链重启造成的旧章末页丢失。

**验收**：快速翻页后 1 秒内切章，退出重进，新旧章各自恢复到正确页码。

### F6 · 首帧闪「加载失败」（BUG-06）

**现状**（`ReaderScreen.kt:714-741`）：`loading` 初值 false、pages 空 → 失败分支先闪现，随后才被加载态覆盖。

**方案**：`ReaderViewModel._loading` 初始值改 `true`。load() 短路场景（loadedKey 命中且 pages 非空）不显示失败态，无回归。

**验收**：冷进阅读器直接菊花，无红字假态闪烁。

### F7 · 结束卡「全本已读完 · 0 话」（BUG-07）

**现状**（`ReaderScreen.kt:408-409,823`）：章节列表空时 `currentChapterIndex == lastIndex == -1` → 判成最后一章，「全本已读完 · 已读完全部 0 话」+ Snackbar「全本已读完 🎉」。

**方案**：
- `isLastChapter` 统一改 `sortedChapters.isNotEmpty() && currentChapterIndex == sortedChapters.lastIndex`（两处 EndCard + Snackbar 的 hasNext 一并）；
- 章节未就绪时结束卡显示「本话已读完」+ 副文案「章节列表未就绪，稍后重进可继续」（复用现有 nextTitle==null 分支文案语义）；Snackbar 不再庆祝。

**验收**：弱网章节未返回时翻到末页，文案中性，无「0 话」。

---

## 三、可达性与表单（F4、F5、F8、F9、F10）

### F4 · 我的评论接线（BUG-04）

**现状**：`MyCommentsScreen` + VM 完整实现（分页/跳详情），但无路由、无入口。

**方案**：MainScreen 注册 `my-comments` 路由（挂 `MyCommentsScreen`，onComicClick → `comic/{ref}`，裸 id 按 ComicRef 兜底解释为哔咔）；MineScreen「关注管理」行后新增「我的评论」行（登录态展示）。

**验收**：我的页 → 我的评论 → 列表翻页 → 点条目进对应详情。

### F5 · 关注标签弹层（BUG-05）

**现状**（`FollowManageScreen.kt:247-283`）：60–90 个 FilterChip 塞进不可滚动的 FlowRow，小屏尾部标签被裁剪不可点。

**方案**：Sheet 内容：标题下加过滤输入框（实时 contains 过滤，默认空=全部）；FlowRow 包 `verticalScroll(rememberScrollState())` + `heightIn(max = 520.dp)`；整列 `navigationBarsPadding()`。

**验收**：任意长度词表全部可见可选；输入关键字即过滤。

### F8 · 更新弹窗下载中锁死（BUG-19）

**现状**（`UpdateDialog.kt:46-47,107-119`）：下载中 `onDismissRequest` 与取消按钮都被禁用。而下载本就在 `UpdateManager` 的 app 级状态机里，关闭弹窗不会取消下载。

**方案**：弹窗一律可关；下载中按钮文案「后台下载」，点击即关（下载继续，首页横幅/设置页可重开看进度）。

**验收**：下载中关闭弹窗 → 进度持续 → 横幅重开可见仍在推进/已完成。

### F9 · 登录/注册键盘遮挡（BUG-22）

**现状**：两页 Column 均 `imePadding + Center` 但无 `verticalScroll`——小屏 + 键盘弹出时密码框/按钮被裁。

**方案**：两页 Column 加 `verticalScroll(rememberScrollState())`（imePadding 保留）；Center 布局改为可滚动内的自然排布（保持视觉重心：`Arrangement.Center` 在 scroll 容器中仍生效，内容超高时可滚）。

**验收**：小屏 + 键盘弹出，全部输入框与按钮可滚动到达。

### F10 · 我的/收藏失败态重试（BUG-23）

**现状**：MineScreen 资料失败只显示错误文字（106-119）；FavouriteScreen 首屏失败只显示错误文字（206-214）。

**方案**：新建 `ui/common/ErrorRetry.kt`（图标 + 错误文案 + 「重试」按钮，居中布局）；
- Mine：失败卡接入组件，重试 → 重拉 profile（本地 retryTick state 并入 LaunchedEffect key）；
- Favourite：首屏失败接入组件，重试 → `viewModel.load(1)`。

**验收**：断网进收藏页 → 失败 + 重试；恢复网络点重试 → 列表出现。

---

## 四、关注流真分页（P1/D9）

**现状**（`HomeViewModel.kt:267-329` + `HomeScreen.kt:216-230`）：refresh 只拉各来源第 1 页，合并后 `take(120)` 硬截断并置 `endReached=true`；渲染处 `onLoadMore = {}`。已有的 `targetPages/targetEnded` 分页游标基建闲置。代码注释自称「滚动加载」与实际矛盾。

**方案**：
- `fetchTargetPage(page)` 重构为 `fetchTargetPageForNext()`：对每个未 ended 来源拉**各自**下一页（`(targetPages[key] ?: 0) + 1`；refresh 前清空游标 → 全部从 1 开始，行为等价现状首屏）。多词关键词来源首轮即全量交集（现有逻辑立即置 ended），不参与后续分页；
- refresh()：去掉 `take(120)` 与 `_followEndReached=true`，改为按「全部来源 ended」判定；冷启动缓存保存保留 `take(120)` 上限（只影响秒显首屏，内存列表不截断）；
- 新增 `loadMoreFollow()`：`followLoading` 期间拒绝；串行拉取 + 限流间隔/冷却检查全部沿用；新状态 `followLoadingMore`（区别于下拉刷新的 loading，避免误动 PullToRefresh 指示器）；合并沿用 `mergeIntoFeed(append=true)`（按时间排序去重）；
- UI：FollowTab 重新收集 `followEndReached`，`endReached` 传真实值，`onLoadMore = viewModel::loadMoreFollow`，尾部转圈开启（`showTailLoading` 默认）；全部到末页时尾部显示「没有更多了」。

**边界**：某来源单页失败 → 该来源本轮跳过不置 ended（现有 catch 置 ended 的行为改为仅记录失败，下轮 loadMore 重试该来源）；限流冷却打断 → 本轮中止，不误标 ended。

**验收**：关注流滚到底自动加载下一页并按时间归位；全部来源到末页后停止；下拉刷新行为与现状一致。

---

## 五、全局质感（Q1–Q4）

### Q1 · 图片淡入（A1）

`PiKAApp` 的全局 ImageLoader 加 `crossfade(true)`（约 150ms 默认）——所有 Coil 请求（网格卡、详情、下载卡、历史、头像）统一获得淡入；封面底色占位已有（surfaceVariant 色块）。成本：一行。**验收**：网格快滚无「闪现感」。

### Q2 · 骨架屏（A2）

新建 `ui/common/Shimmer.kt`：
- `shimmerBrush()`：`rememberInfiniteTransition` 驱动的线性渐变（surfaceVariant → surface → surfaceVariant，平移动画 1200ms）；
- `ComicGridSkeleton(columns: Int)`：固定 12 个卡片骨架（封面 3:4 圆角块 + 两行文字条）。

接入四处首屏（loading 且列表为空时替代「加载中...」文字/空白）：首页排行榜、随便看看、分类漫画流（CategoryComicsScreen）、搜索结果首屏。

**验收**：弱网首屏为网格骨架而非文字；骨架微光流动。

### Q3 · 页面转场（A4）

MainScreen 的 NavHost 配置：
- `enterTransition = slideInHorizontally { it / 12 } + fadeIn(220ms)`；`exitTransition = slideOutHorizontally { -it / 12 } + fadeOut`；pop 方向镜像；
- reader 路由单独用纯 fade（阅读器进出不做水平位移，画面更稳）。

**验收**：全部页面切换有方向感淡入淡出，返回方向相反。

### Q4 · 列表增删动画（A5）

Compose BOM 2024.12（foundation 1.7+）支持 `Modifier.animateItem()`：
- DownloadScreen 任务组列表；
- FavouriteScreen 收藏网格；
- RecentReadsScreen 历史列表；
- MyCommentsScreen 评论列表（顺手）。

**验收**：删除条目时平滑收起，相邻项补位动画，无跳变。

---

## 六、阅读器打磨（Q5–Q7）

### Q5 · 翻页预取（A9）

`HorizontalPager` 加 `beyondViewportPageCount = 1`。与现有 `preloadNearby`（预取前后 2 页进缓存）互补：预取管网络，本参数管组合渲染。**验收**：翻页几乎无白图。

### Q6 · 首次分区引导（A7）

- `ReaderPrefs` 新增 `readerGuideShown: Boolean`（DataStore，默认 false）；
- ReaderScreen：pages 非空且未看过引导时，叠加全屏引导层——半透明黑幕 + 三分区示意（左 30%「点击上一页」/ 中 40%「点击呼出菜单」/ 右 30%「点击下一页」）+ 底部「点击任意处开始阅读」；点击任意处置位 true 并消失。每次安装只出现一次。

**验收**：全新安装首次进阅读器出现引导；退出重进不再出现。

### Q7 · 长按分享（D6 余量）

- 长按当前页 → 弹 `ModalBottomSheet` 菜单：「保存到相册」（现行为）/「分享此页」；
- 分享实现：`ImageSaver` 新增 `downloadToCache(context, source): File`（cacheDir/share/ 下落盘，复用 openSource）；FileProvider（file_paths.xml 的 cache-path 已覆盖全 cacheDir）+ `ACTION_SEND image/jpeg` + `FLAG_GRANT_READ`；
- 保存/分享失败的 Toast 文案沿用现有风格。

**验收**：长按出现两项菜单；分享拉起系统面板，图片可发送到微信/Telegram 等。

---

## 七、反馈与操作（Q8–Q12）

### Q8 · 下载卡进度平滑（A10）

- `DownloadTaskRow` 与组卡的 `LinearProgressIndicator` 的 progress 值经 `animateFloatAsState(tween 300ms)`；速度文案（bytesPerSecond）已有，保留。
**验收**：进度条平滑推进无跳档。

### Q9 · 完成通知直达（A11）

- `notifyComplete` 增强：
  - `contentIntent` 携带路由 extra `open_route = "comic/<ComicRef.ofName(source, comicId)>"`（source 取该漫画任务快照）；
  - `largeIcon` = 封面位图（Coil execute 同步尝试，失败省略，不阻塞通知）；
- 导航接线：MainActivity `launchMode="singleTask"` + onCreate/onNewIntent 解析 extra 写入单例 `NavDeeplink.pendingRoute`；MainScreen LaunchedEffect 消费并 navigate。

**验收**：整本下载完成的通知带封面；点通知直达该漫画详情。

### Q10 · Snackbar 带动作（A12）

- 导出完成 Snackbar 附「分享」：`ExportState.Done` 增加 `uri: Uri?` 字段；动作触发 `ACTION_SEND application/zip + EXTRA_STREAM`（SAF uri 带 FLAG_GRANT_READ）；
- 下载失败 Snackbar 附「重试」：DownloadScreen 观察任务快照，diff 出**新增**的 FAILED key 时弹「N 个任务下载失败」+「重试」→ 逐个 `DownloadManager.retry(key)`。

**验收**：导出完成一键分享 zip；任务失败一键全部重试。

### Q11 · 破坏性操作撤销（A14）

- **阅读历史**：删除单条 / 清空前快照列表；操作后 Snackbar「已删除」+「撤销」（5s）；撤销 = `ReaderPrefs.restoreRecentReads(snapshot)`（新增 API：整表写回，保留原时间戳）；
- **关注关键词**：删除后快照 item；撤销 = `FollowSettings.addItem(...)` 恢复；
- **下载任务删除**：单章删除目前无确认 → 补确认对话框（与整本一致）；不做撤销（恢复=重新下载，流量成本高且实现侵入任务状态机——如实取舍，确认对话框已防误删）。

**验收**：误删历史/关键词 5 秒内可完整恢复；单章删除有确认。

### Q12 · 评论点赞（D2）

- 网络：`PicaApi` + `PicaHttpApi` 新增 `POST comics/{id}/comments/{commentId}/like`（响应 `ActionResponse.action == "like"/"unlike"`）；`Source.likeComment(comicId, commentId): Boolean`（返回点赞后状态）+ PicacgSource 实现；
- VM：`likedComments: Set<String>`（会话级）+ 乐观更新列表内 `likesCount ±1`；失败回滚 + Snackbar；
- UI：CommentItem「赞 N」改为可点（ThumbUp 图标 + 数字，会话内已赞高亮主色）；楼中楼子评论同样可点（同一数据通路）。

**验收**：点赞即时 +1 高亮，再点取消 -1；断网失败回滚并提示。

---

## 八、实施与验收节奏

每项独立提交（fix/feat 前缀 + 编号），顺序：

1. F1–F3、F6、F7（阅读器，2 个提交）
2. F4–F10（可达性表单，5 个提交）
3. P1 关注流分页（1 个提交）
4. Q1–Q4 全局质感（4 个提交）
5. Q5–Q7 阅读器打磨（2 个提交）
6. Q8–Q12 反馈操作（4 个提交）
7. 全量 `:app:compileDebugKotlin` + `:app:testDebugUnitTest`
8. 复测：逐项对照本节验收标准做代码级复核（独立只读扫描）+ 可用则模拟器冒烟
9. 发版 v1.9.0 (build 83)：版本号、APK、update.json、release 提交、推送

**回归红线**：阅读器进度保存/恢复语义（F1–F3 触碰核心路径）必须有针对性走查；关注流刷新节流与限流冷却逻辑不得回退。

---

## 附：涉及文件清单

- 阅读器：`ui/reader/ReaderViewModel.kt`、`ReaderScreen.kt`、`data/ReaderPrefs.kt`
- 可达性：`ui/MainScreen.kt`、`ui/mine/MineScreen.kt`、`ui/follow/FollowManageScreen.kt`、`ui/update/UpdateDialog.kt`、`ui/login/LoginScreen.kt`、`RegisterScreen.kt`、`ui/favourite/FavouriteScreen.kt`
- 分页：`ui/home/HomeViewModel.kt`、`HomeScreen.kt`
- 质感：`PiKAApp.kt`、`ui/common/Shimmer.kt`（新）、`ui/browse/ComicGridView.kt`、`ui/category/CategoryScreen.kt`、`ui/search/SearchScreen.kt`、`ui/download/DownloadScreen.kt`、`ui/history/RecentReadsScreen.kt`、`ui/comments/MyCommentsScreen.kt`
- 反馈：`core/download/DownloadManager.kt`、`DownloadExporter.kt`、`MainActivity.kt`、`AndroidManifest.xml`、`res/xml/file_paths.xml`
- 点赞：`network/PicaApi.kt`、`PicaHttpApi.kt`、`core/source/Source.kt`、`PicacgSource.kt`、`ui/detail/ComicDetailViewModel.kt`、`ComicDetailScreen.kt`
