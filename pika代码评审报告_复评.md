# PiKA（com.pika）代码评审报告 · 交互与布局专项复评

- **项目路径**：`G:\game\新建文件夹\pka\pika`
- **应用**：`com.pika`，Kotlin + Jetpack Compose，minSdk 26 / targetSdk 35
- **版本**：versionCode 43 / versionName 1.5.17
- **评审方式**：全量静态通读 + 交互与页面布局专项排查（3 路并行 UI 走查，并对所有高影响 claim 二次读源码核实）
- **评审范围**：本次**不含禁漫（JM）源模块**
- **复评日期**：2026-08-15

> 分级：P1=明显功能缺陷（用户可感知）；P2=行为不正确/能力缺失/主线程阻塞等；P3=次要体验/健壮性。
> 每条均经源码核实（`文件:行号`），冲突项已二次读源码确认，避免误报。每条含「当前交互/状态说明」描述用户当前版本下的实际可感知表现。

---

## 一、发现概览

| 级别 | 数量 | 条目 |
|------|------|------|
| P1 | 2 | ① 首页关注流翻页"假死"（将按设计消除） · ② 分类漫画流页码条失效 |
| P2 | 2 | ③ 收藏页（我的/收藏）改纯分页 · ④ 离线阅读页码顺序错乱 |
| P3 | 4 | ⑤ 表单键盘遮挡 · ⑥ 阅读器切章切片残留 · ⑦ 首页滚动恢复未可观察化 · ⑧ 分类/作者分页上下间距不合理 |
| **合计** | **8** | |

> 另有两项用户确认正常、已从缺陷清单移除：作者页/分类页/详情页顶栏（`WindowInsets(0,0)` 视觉正常）、搜索页导航栏 inset（页码条未被手势条遮挡，正常）。

---

## 二、详细发现

### ①（P1，功能 → 将按设计消除）首页关注流翻页"假死"

- **位置**：`ui/home/HomeViewModel.kt` `loadMore()`（188-199）、`fetchTargetPage()`（206-233）、`fetchKeywordPage()`（239-290）。
- **当前交互/状态说明**：进入首页"关注"Tab 并下拉刷新后可见第 1 页混合作品；**向下滑动触发"加载更多"后，加载菊花持续旋转但列表不再增长，且永不出现"已到底"提示**。只关注单个作者、或所有关注来源页数相同时表现正常；一旦关注 ≥2 个作者且页数不同（任一来源先结束），从第 2 次加载起即卡死。
- **根因（已核实）**：`loadMore` 第 193 行 `val next = targetPages.values.minOrNull()?.plus(1) ?: 2`。`fetchTargetPage(page)` 只对**未结束**来源写 `targetPages[key]=page`（`:222`/`:247`/`:285`），已结束来源在循环里 `continue` 跳过、其 `targetPages` 条目**永久冻结在结束页**。一旦某来源先结束（如仅 1 页的作者），其冻结值成全局 `min`，`next` 被钉死，其余更长来源永远在同一页反复拉取、不再推进 → 假死。
  - 示例：关注作者 A（1 页）+ 作者 B（50 页）。刷新均=1；LM1 取页 2，A 因 `pages=1` 在页 2 即 `ended`（`targetPages[A]` 冻结=2）；LM2 `min=2`→取页 3，A 跳过、B→3；LM3 `min=2`（A 冻结）→又取页 3，B 卡死。
- **用户决定的修复方向（消除假死）**：首页关注流**改为仅展示最新 120 条、不做滚动加载**。去除 `loadMore`/分页后，上述假死根因随之消除。
  - **待实施要点**：`refresh()` 取各关注来源第 1 页合并、按更新时间排序取前 120 条；UI 移除 `PaginationBar` 与 `loadMore` 调用、`endReached` 不再依赖逐源翻页；`targetPages`/`targetEnded` 相关逻辑可整体退役。

### ②（P1，交互）分类漫画流页码条高亮失效

- **位置**：`ui/browse/BrowseViewModel.kt:63` `var currentPage: Int = 1`（**非 StateFlow**）；`ui/category/CategoryScreen.kt:323` `PaginationBar(currentPage = viewModel.currentPage, ...)`（直接读 `var`，未 `collectAsState`）。
- **当前交互/状态说明**：在"分类 → 某分类"页点底部页码条第 2 页及以后，网格**内容**会刷新到对应页（取决于 `jumpToPage` 内部），但页码条上**高亮小方块永远停在第 1 页**，当前页指示错误——用户无法从页码条判断自己停留在第几页。
- **根因（已核实）**：`BrowseViewModel.currentPage` 是普通 `var`；`CategoryComicsScreen` 组合时一次性读取该值，VM 内部改写不触发重组。与搜索/作者/收藏三处已修的同类问题一致，唯独分类流遗漏。
- **关联调用点**：`CategoryComicsScreen` 的 `PaginationBar`（`CategoryScreen.kt:322-327`）`totalPages` 已用 `collectAsState`（`totalPages` 是 StateFlow），但 `currentPage` 漏接；同文件 `:186` `saveScrollState(..., viewModel.currentPage)` 同样读该 `var`。
- **修复建议**：`BrowseViewModel.currentPage` 改为 `private val _currentPage = MutableStateFlow(1)` + `val currentPage: StateFlow<Int>`，VM 内赋值改 `_currentPage.value=`，`CategoryComicsScreen` 加 `val currentPage by viewModel.currentPage.collectAsState()` 并传 `currentPage`。

### ③（P2，交互）收藏页（我的/收藏）改为纯分页

- **位置**：`ui/favourite/FavouriteScreen.kt` `FavouriteViewModel` + `FavouriteScreen`（148-184）；`ComicGridView.kt:53` `fillMaxSize()`。
- **当前交互/状态说明**：用户实测"我的/收藏"页**以滚动加载为主（向下滚动自动加载更多），未见稳定分页条**。代码当前同时保留 `onLoadMore` 滚动加载与"仅当 `totalPages>1` 时条件显示"的 `PaginationBar`（`:177-183`）；按用户需求，该页**将改为纯分页导航**。需注意：一旦分页条稳定展示，当前 `ComicGridView` 与 `PaginationBar` 直接并列于 `Scaffold` content（无 `Column`+`weight`）的写法会导致**分页条覆盖网格首行**。
- **根因（已核实）**：`ComicGridView` 与其下方 `PaginationBar` 作为**直接兄弟**并列于 `Scaffold` 的 `content` lambda；而 `ComicGridView` 内部 `Modifier.fillMaxSize()`（`ComicGridView.kt:53`），Material3 `Scaffold` content 区为 `Box`，两兄弟会叠加（搜索/作者页均用 `Column { ComicGridView(weight(1f)); PaginationBar }` 正确）。
- **修复建议（纯分页 + 正确布局）**：
  - 移除 `onLoadMore` 滚动加载（`ComicGridView` 的 `onLoadMore` 置空或去除触发），由 `PaginationBar` 承担翻页；
  - `PaginationBar` 在多页时常驻展示；
  - 用 `Column(Modifier.fillMaxSize().padding(innerPadding)) { ComicGridView(Modifier.weight(1f)); PaginationBar(...) }` 包裹，避免覆盖。

### ④（P2，功能）离线阅读页码顺序字典序错乱（≥10 页章节）

- **位置**：`ui/reader/ReaderViewModel.kt:60-66`（离线分支）。
- **当前交互/状态说明**：已下载章节**离线**打开时，第 1~9 页正常，但第 10、11 页会穿插显示在第 2、3 页之前（顺序形如 1,10,11,2,3,4…）；在线阅读不受影响（走 API 有序返回）。
- **根因（已核实）**：离线加载用 `dir.listFiles()?.filter{...}?.sortedBy { it.name }`。`File.name` 为字符串，**字典序**比较 `"page_10.jpg" < "page_2.jpg"`（因 `'1' < '2'`）。落盘文件名本身正确（`DownloadManager.pageFile` 按 `page_${index+1}.jpg`），问题在**读取端排序**。
- **修复建议**：按文件名中数字排序，例如
  ```kotlin
  val local = chapterDir.listFiles()
      ?.filter { it.name.startsWith("page_") && it.length() > 0 }
      ?.sortedBy { it.name.removePrefix("page_").removeSuffix(".jpg").toIntOrNull() ?: 0 }
  ```

### ⑤（P3，交互）表单页缺少 `imePadding`，键盘遮挡输入框/按钮

- **位置**：登录/注册/找回密码等表单页（`ui/login/*`、`ui/register/*`、`ui/forgot/*` 等）。
- **当前交互/状态说明**：在登录/注册/找回密码页，点击底部输入框或聚焦于最后一个字段时，软键盘弹出会把"登录/注册"按钮或验证码输入框**顶到键盘后面**；用户须手动收起键盘才能点到按钮。
- **根因（已核实）**：全项目 `grep imePadding` **无任何命中**——没有屏幕处理 IME inset。凡含 `OutlinedTextField` + 底部提交按钮的页面均受影响（`MainActivity` 已 `enableEdgeToEdge`，系统栏不自动为键盘让位）。
- **修复建议**：表单根容器加 `.imePadding()`（或 `.padding(WindowInsets.ime)`），并确保可滚动以便聚焦项自动滚入视野。

### ⑥（P3，布局）阅读器切章后切片数（sliceCounts）未清空

- **位置**：`ui/reader/ReaderScreen.kt:153` `sliceCounts = remember { mutableStateMapOf() }`；`switchChapter`（`:98-103`）/`load`（`:49`）未重置它；仅 `LaunchedEffect(scrollMode)`（`:154-156`）在切模式时清空。
- **当前交互/状态说明**：在阅读器内点"下一话/上一话"切章后，若新章页数 ≤ 旧章，旧章残留的切片数会让新章前几页在图片测量完成前被切成**错误数量的屏**（长图误判为短图或反之）；若带续读进度，切章后**瞬间**可能定位到错误行，待图片加载后才自行纠正——存在可见的"跳一下/错位一下"过程。
- **根因（已核实）**：`sliceCounts` 是 Composable 内 `remember` 状态，跨章节切换（`switchChapter`→`load` 换 `comicId/order`）时不被清空，旧页索引切片数被复用到新章同索引页；`rows`/`rowToPage` 用陈旧切片数计算，`onSliceCountResolved` 测量后逐步自愈。
- **修复建议**：`switchChapter`/`load` 在章节变化（order 改变）时 `sliceCounts.clear()`；`LaunchedEffect` 的 restore 计算亦应依赖 `sliceCounts` 稳定后再执行（或测量完成后重新定位一次）。

### ⑦（P3，健壮性）首页关注流滚动恢复用 `var`（非 StateFlow）

- **位置**：`ui/home/HomeViewModel.kt:94` `var isScrollStateRestored` 与 `saveScrollState`/`markScrollStateRestored`；`HomeScreen` 通过 `LaunchedEffect(viewModel.isScrollStateRestored)` 恢复。
- **当前交互/状态说明**：首页关注/排行/随便看看三个 Tab 间切换、或从详情返回时，关注流的滚动位置恢复**偶发不生效**（停留在顶部或上次位置不稳定），与搜索/作者/收藏页（均已用 StateFlow 驱动恢复）行为不一致。
- **根因（已核实）**：`isScrollStateRestored` 为普通 `var`，驱动 `LaunchedEffect` key 的组合期读取不保证在后续改写时重新触发恢复逻辑；属一致性/健壮性瑕疵，非必然崩溃。
- **修复建议**：与其他页对齐，将 `isScrollStateRestored` 改为 `StateFlow<Boolean>` + `collectAsState`，或用事件流驱动恢复。

### ⑧（P3，布局）分类页/作者页 PaginationBar 上下间距不合理（搜索页正常）

- **位置**：`ui/browse/PaginationBar.kt:49-53`（`Row` modifier 仅 `.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp)`，**无垂直内边距**）；调用点 `AuthorScreen.kt:205-209`（无额外修饰符）、`CategoryScreen.kt:322-327`（`Modifier.padding(bottom = 8.dp)`）、`SearchScreen.kt:189-193`（无额外修饰符）。
- **当前交互/状态说明**：分类页与作者页底部的分页组件，与上方漫画网格、下方屏幕边缘的**上下间距观感不合理**（显得拥挤/贴边）；而搜索页搜索出结果后的分页组件间距正常。
- **根因（已核实）**：`PaginationBar` 自身没有垂直内边距，且三处调用点修饰符不一致（搜索/作者无额外间距，分类仅 `padding(bottom = 8.dp)`），导致各页表现不一；栏高固定 48dp 又无上下留白，视觉上显得局促。
- **修复建议**：在 `PaginationBar` 内部统一设定合理垂直内边距，例如 `Row` modifier 改为 `.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp, vertical = 8.dp)`，并移除各调用点不一致的底部修饰符（如 `CategoryScreen` 的 `padding(bottom = 8.dp)`），使分类/作者/搜索三页分页条上下间距一致；必要时给分页条加 `Surface` 背景以增强层次。

---

## 三、修复优先级建议

| 优先级 | 条目 | 动作 |
|--------|------|------|
| 🔴 立即 | ① 关注流假死 | 按设计改为「最新 120 条 + 无滚动加载」，退役 `loadMore`/分页 |
| 🔴 立即 | ② 分类流页码条失效 | `currentPage` 改 StateFlow + `collectAsState` |
| 🟠 高 | ③ 收藏页改纯分页 | 去 `onLoadMore` + `Column`+`weight(1f)` 包裹 |
| 🟠 高 | ④ 离线页码字典序 | 按文件名数字排序 |
| 🟡 中 | ⑤ 键盘遮挡 | 表单加 `imePadding` |
| 🟡 中 | ⑥ 切章切片残留 | `switchChapter`/`load` 清 `sliceCounts` |
| 🟡 中 | ⑧ 分类/作者分页间距 | `PaginationBar` 统一垂直内边距 |
| 🟢 低 | ⑦ 首页恢复 var | 改 `StateFlow` + `collectAsState` |

---

## 四、排除项与未覆盖

- **禁漫（JM）源模块**：本轮按评审范围不评。
- **用户确认正常、已移除非缺陷**：
  - 作者页 / 分类页 / 详情页顶栏 `WindowInsets(0, 0)`：用户确认顶栏未压在状态栏下，视觉正常。
  - 搜索页导航栏 inset：用户确认底部页码条未被手势条遮挡，正常。
- **已确认非缺陷**：单关键词搜索页码条（服务端分页正常）、滚动"加载更多"（设计废弃）、发布一致性（`update.json`→`pika-v1.5.17.apk` 链路正常）、BcTls 的 R8 keep 与 `META-INF/versions` 排除、阅读器双模式/手势/亮度/预加载/进程恢复（`ON_STOP` 保存）主体逻辑。
- **本次未做**：编译/运行（无法验证 R8 全量裁剪、运行时崩溃、权限/Manifest/资源）、性能压测、第三方依赖许可证与版本漏洞审计。
