# PiKA（com.pika）静态代码评审报告

- **项目路径**：`G:\game\新建文件夹\pka\pika`
- **应用**：`com.pika`，Kotlin + Jetpack Compose，minSdk 26 / targetSdk 35
- **版本**：versionCode 43 / versionName 1.5.17
- **评审方式**：全量静态通读（接口层 / 交互层 / 数据并发层 / 构建发布），**未编译、未运行**
- **评审范围**：本次**不含禁漫（JM）源模块**（用户要求本轮暂不处理）
- **评审日期**：2026-08-15

> 说明：所有"文件:行号"均来自本次实际通读复核。P1=明显功能缺陷（用户可感知）；P2=行为不正确/能力缺失/主线程阻塞等；P3=次要体验/健壮性。"关联调用点"为本次新增检索结果，用于确认缺陷的真实影响面。

---

## 一、缺陷分级统计

| 级别 | 数量 | 条目 |
|------|------|------|
| P1 | 3 | P1-1 多关键词页码条失效 · P1-2 删除不释放文件 · P1-3 阅读器续读错位 |
| P2 | 7 | P2-2 收藏 add 忽略 · P2-3 主线程 runBlocking · P2-4 作者页死代码 · P2-6 data!! · P2-7 反序列化未兜底 · P2-8 BcTls 宽松信任 · P2-9 关注流翻页跳过 |
| P3 | 4 | P3-1 失败文案不可点 · P3-2 切片重复解码 · P3-3 Retrofit 闲置 · P3-4 pageCount=0 边界 |
| **合计** | **14** | |

---

## 二、P1（明显功能缺陷，建议优先修复）

### P1-1 多关键词搜索分页（页码条）失效
- **位置**：`ui/search/SearchViewModel.kt`（`search` 135-173、`computeMultiWordIntersection` 182-263、`buildDisplay` 276-282、`publishFinalResult` 307-315、`jumpToPage` 335-339）；`ui/search/SearchScreen.kt`（188-192）；`ui/browse/PaginationBar.kt`（UI 正常）
- **现象**：
  - **单关键词页码条正常**：`PaginationBar`（`SearchScreen.kt:188-192`）→ `jumpToPage` → `search(keyword, page)`，单关键词分支（`SearchViewModel.kt:154-165`）以 `source.search(keyword, page, sort)` 走服务端分页，正确（用户已实测可翻页，本次复核确认）。
  - **多关键词页码条彻底失灵**：点第 2/N 页仍显示第 1 页前 20 条；交集 >20 条时永远看不到第 21 条之后。因滚动"加载更多"已废弃、页码条是**唯一**翻页入口，多关键词搜索实际被掐在前 20 条。
- **根因（已逐行复核）**：
  1. `search(keyword, page)`（135）第 142 行 `currentPage = 1` 在**每次**搜索被重置，传入的 `page` 参数在多关键词分支被丢弃；
  2. 多关键词分支（166-168）调用 `computeMultiWordIntersection(source, words, this)` 时**未把 `page` 传入**，该函数（182-263）永远从每词第 1 页开始拉；
  3. `publishFinalResult`（307-315）第 312 行无条件 `_endReached.value = true`；
  4. `buildDisplay()`（276-282）永远 `.take(pageSize)`（=20），**完全忽略 `currentPage`**（实际恒为 1）。
  - 综上：`jumpToPage(2)`→`search(keyword,2)`→`currentPage` 重置 1、重跑全量交集、`buildDisplay()` 仍取前 20 条 → 页码条在多关键词下是摆设。
- **关联调用点**：
  - 排序芯片走 `updateSortOnly`（`SearchViewModel.kt:322-333`）：多关键词分支只对本地的 `_multiAllComics` 重排，不触发翻页，与本条无冲突。
  - `ComicGridView` 滚动触发 `onLoadMore`（`SearchScreen.kt:176`→`SearchViewModel.loadMore` 341-355）——滚动加载已废弃；且该路径多关键词下 `buildDisplayForPage`（285-292，`drop/take` 正确）其实可用，却**从未被 `jumpToPage` 复用**，属接线遗漏（本可对缓存交集本地翻页，却绕回重算全量交集）。
- **修复**：`jumpToPage` 多关键词分支复用 `buildDisplayForPage(page)` 对**已缓存的 `_multiAllComics`** 做客户端本地分页；去掉 `search()` 对 `currentPage` 的硬重置；`publishFinalResult` 的 `endReached` 改由"已展示页数 ≥ 总页数"决定。
- **影响**：搜索是最高频入口；多关键词（"空格分词且关系"）为核心卖点之一，目前被掐在前 20 条且翻页全坏。

### P1-2 删除下载任务不释放文件（存储泄漏）
- **位置**：`core/download/DownloadManager.kt` `remove()`（202-213）
- **现象**：在"我的下载"删除章节/整本，列表项消失，但 `downloads/{comicId}/{order}/` 目录仍占用空间，重进由 `restoreTasks()` 恢复出来。
- **根因（已确认）**：
  ```kotlin
  mutex.withLock {
      _tasks.value = _tasks.value.filterNot { it.key == key }   // 先把任务移出列表并持久化
      persist()
  }
  val rt = _tasks.value.firstOrNull { it.key == key }            // 上一步已移除，rt 永远为 null
  if (deleteFiles && rt != null) { chapterDir(...).deleteRecursively() }  // 永不执行
  ```
- **关联调用点**：`ui/download/DownloadScreen.kt:174` `onDelete = { DownloadManager.remove(it) }`、`:175` `onDeleteAll = { comicTasks.forEach { t -> DownloadManager.remove(t.key) } }`——均使用默认 `deleteFiles = true`，因此删除章节与删除整本都**不会释放磁盘空间**。
- **修复**：移除前先取出待删对象，再删文件：
  ```kotlin
  mutex.withLock {
      val target = _tasks.value.firstOrNull { it.key == key }
      _tasks.value = _tasks.value.filterNot { it.key == key }
      persist()
      if (deleteFiles && target != null) {
          chapterDir(target.task.comicId, target.task.order).deleteRecursively()
      }
  }
  ```

### P1-3 阅读器续读定位在长图切片后错位
- **位置**：`ui/reader/ReaderScreen.kt`（续读恢复 154-164；`rows` 平铺与 `rowToPage` 171-191）
- **现象**：开启"滚动流"模式且某页被自动切成多屏（长图）时，重进阅读器续读位置停错页。
- **根因（已确认）**：`scrollMode` 下 `rows` 是"页 × 切片数"平铺（171-180），行数 ≠ 页数。续读用 `listState.scrollToItem(target)`（158），其中 `target = restorePage`（页码）。页码 ≠ 行号——正确应为累加前面各页切片数后的行号。`rowToPage()`（182-191）已实现但**无反向 `pageToRow`**。
- **关联调用点**：`ui/reader/WebtoonSplitPage.kt` 的 `computeSliceCount`（上限 6 屏）会把一页展开成多个 item；`onModeChange` 内 `pagerState.currentPage` 同理需转为行号。
- **修复**：实现 `pageToRow(page)` 并在续读前 `target = pageToRow(restorePage)`；模式切换同理。

---

## 三、P2（行为不正确 / 能力缺失 / 主线程阻塞）

### P2-2 收藏 `add` 参数被忽略，状态可能错位（哔咔源）
- **位置**：`core/source/PicacgSource.kt` `favourite()`（173-176）
- **现象**：哔咔 `/comics/{id}/favourite` 接口是"切换"语义，`add` 参数被整段忽略。当本地展示状态与服务端真实状态不一致（如用户在网页端已收藏后首次用 App）时，点一下可能把已收藏取消，且 UI 乐观置为相反状态 → 长期错位。
- **根因（已确认）**：`PicaClient.safeCall { PicaClient.api.favorite(comicId) }` 未使用 `add`；返回 `resp.action.isNotBlank()`。
- **关联调用点**：`ui/detail/ComicDetailViewModel.kt:140` `SourceManager.current().favourite(comicId, !_favourited.value)` 传入 `add` 但被忽略。
- **修复**：无独立 add/remove 接口时，应以本地权威状态驱动单次 `favourite()`（或先用 `favourites()` 拉取真实集合校正）；按 `action` 字段解析真实新状态。

### P2-3 DataStore 读取在主线程 `runBlocking`
- **位置**：`data/SourcePrefs.kt`（`activeSource` 38、`getOrCreateAppUuid` 54/60、`picaToken` 69、`picaEmail` 74 等 getter）；`core/source/SourceManager.kt:13`（object 初始化）；`ui/mine/MineScreen.kt:58`（`isLoggedIn`）
- **现象/风险**：DataStore 首次读取/磁盘冷启动时在主线程阻塞 → 掉帧甚至 ANR。
- **根因（已确认）**：`activeSource` 等 getter 直接 `runBlocking { dataStore.data.first() }`；`SourceManager` 单例初始化（13 行）即调用 `SourcePrefs.current().activeSource` getter，在启动期主线程阻塞；`MineScreen:58` 组合体内 `val loggedIn = SourceManager.current().isLoggedIn`（`isLoggedIn` 经 `picaToken` getter 同样 `runBlocking`）。
- **关联调用点**：`data/ReaderPrefs.kt` 亦多处 `runBlocking`（55/75/81/92/98/110/113）读取阅读进度/收藏，属同一通用数据层问题（非禁漫专属）。
- **修复**：组合内改用 `SourceManager.activeSource`/`loggedOut` 的 `StateFlow` + `collectAsState()`；`isLoggedIn` 改为 `StateFlow`；保存操作改用 `scope.launch { ... }` 调 `suspend` 版本 setter。

### P2-4 作者页"连载状态"筛选与本地排序是死代码
- **位置**：`ui/author/AuthorViewModel.kt` `applyFilterAndSort()`（132-146）从未被调用；`setStatus()`（126-130）未把 `status` 传入 `browse()`
- **现象**：在作者页切换"连载/完结"筛选、以及想用本地排序（更新/热度）时无任何效果。
- **根因（已确认）**：`applyFilterAndSort` 定义后未被任何调用；`setStatus` 只改 `_status` 后 `loadComics(page = 1)`，而 `loadComics`（71-77）调 `browse()` 时不带 `status`；`setSort`（120-124）走服务端排序可用，但本地排序分支死。
- **关联调用点**：`AuthorScreen` 调用 `setStatus`/`setSort`。
- **修复**：`loadComics` 把 `_status` 映射为请求参数（若源支持）或在拿到结果后调用 `applyFilterAndSort()`；切换排序时套用本地排序。

### P2-6 `PicaClient.safeCall` 对空 `data` 直接 `!!`
- **位置**：`network/PicaClient.kt:63` `return response.data!!`
- **风险**：服务端返回 `code=200` 但 `data` 为 `null`（如某些 action/空响应）时直接 NPE。
- **根因（已确认）**：`safeCall`（51-91）只捕获 `PicaException`/`IOException`，`data!!` 无空守卫。
- **关联调用点**：所有经由 `PicaClient.safeCall` 的哔咔接口（`search`/`browse`/`favourite`/`favourites`/`rank`/…）均受影响。
- **修复**：`response.data ?: throw PicaException("空响应数据")` 或更稳妥地按接口契约判断。

### P2-7 `PicaHttpApi.parseResponse` 未兜底反序列化异常
- **位置**：`network/PicaHttpApi.kt` `parseResponse`（27-32）；`get()`（34-45）
- **风险**：响应体不完整/字段缺失且违反 `coerceInputValues` 时抛 `SerializationException`；`get()` 的 `try/finally` 无 `catch`，`safeCall` 仅捕获 `PicaException`/`IOException` → 异常原样上抛到 UI 层（部分 UI 未捕获 `SerializationException`）。
- **根因（已确认）**：`parseResponse` 内 `json.decodeFromString(...)` 无 `try/catch`；`get()` 仅 `finally { resp.close() }`。
- **关联调用点**：全部引擎调用经 `get()` → `parseResponse`。
- **修复**：在 `parseResponse` 外包 `try/catch (SerializationException)` 转为 `PicaException`。

### P2-8 BcTls 使用"信任所有证书"的 TrustManager（MITM 风险）
- **位置**：`network/BcTls.kt` `permissiveTrustManager`（30-34）
- **风险**：完全绕过证书校验，理论上可遭中间人劫持（仍走真实 Cloudflare 域名 + 主机名校验生效）。
- **根因（已确认）**：`install()`（41-56）第 48 行 `ctx.init(null, arrayOf(permissiveTrustManager), SecureRandom())` 使用该宽松 TM；代码注释声明为"因 Android 上 BC 找不到系统 CA 目录"的已知权衡。
- **关联调用点**：被 `core/download/DownloadManager.kt:303`（`BcTls.openConnection`，下载器）与 `BcTls.imageLoaderClient`（73，Coil 图片加载）使用。
- **建议**：改为平台默认 `TrustManagerFactory`（或内置 Cloudflare/Let's Encrypt 根证书），仅在 BC 不可用时回退宽松策略。

### P2-9 首页"关注"流 `loadMore` 在含多关键词关注项时跳过作者页 2–50
- **位置**：`ui/home/HomeViewModel.kt` `loadMore()`（188-199）、`fetchTargetPage()`（206-233）、`fetchKeywordPage()`（285-286）
- **现象**：在"关注管理"添加过多关键词（如"A B"）关注项后，首页"关注" tab 加载更多时，被关注的**作者（单关键词）新作品只显示第 1 页，第 2–50 页被整体跳过**。
- **根因（已确认）**：`loadMore` 计算 `next = targetPages.values.maxOrNull()?.plus(1) ?: 2`（193）；多关键词关注项在 `fetchKeywordPage` 第 285 行写哨兵 `targetPages[key] = 50`、第 286 行 `targetEnded = true`（一次性拉完全部交集）。于是只要存在任一多关键词关注项，`targetPages` 里就有一个 `=50` 的键 → `next` 抬高到 `51` → `fetchTargetPage(51)` 对所有**未结束的单关键词源（作者）**请求第 51 页 → 实际只有 1 页 → `page >= r.pages` 判 `ended` → **第 2–50 页从未被拉取**。
- **关联调用点**：关注管理（FollowScreen/MineScreen）添加关注项；`fetchTargetPage` 串行拉取各 `target`，已结束的 `target` 直接跳过。
- **修复**：翻页进度**按来源独立维护**——`fetchTargetPage` 内对单关键词源用各自 `targetPages[key] + 1`，多关键词源因 `targetEnded` 跳过，不要用全局 `max` 污染。

---

## 四、P3（次要体验 / 健壮性）

### P3-1 阅读器"加载失败"文案误导（不可点击）
- **位置**：`ui/reader/ReaderScreen.kt:332-339`，"加载失败，点击返回重试"的 `Box`/`Text` 没有 `clickable`，点击无效（只能按系统返回）。
- **修复**：给该 `Box` 加 `Modifier.clickable(onClick = onBack)`（或触发重试）。

### P3-2 长图切片各自独立建 `painter`（重复解码）
- **位置**：`ui/reader/WebtoonSplitPage.kt:71-79` `rememberAsyncImagePainter` 在**每个切片** Composable 内创建；外层对每 `(page, sliceIndex)` 渲染一个 `WebtoonSplitPage` → 同一大图被多次解码，内存/CPU 偏高。
- **修复**：原图一次性解码后按切片绘制，或将 `painter` 提升到 page 级别复用。

### P3-3 Retrofit 依赖基本闲置（死重）
- **位置**：`app/build.gradle.kts:78-79` 引入 `retrofit` / `retrofit.converter.kotlinx`；`network/PicaApi.kt` 仅用 `retrofit2.http.*` 注解声明接口；`PicaClient.api`（40-41）返回 `PicaHttpApi`（`HttpsURLConnection`）实例，**从未**调用 `Retrofit.create`。
- **结论**：运行时 Retrofit 为死重依赖，可后续清理（非缺陷）。

### P3-4 `restoreTasks` 在 `pageCount=0` 时的边界
- **位置**：`core/download/DownloadManager.kt:356` `finished = pages >= task.pageCount`；整本批量入队 `enqueueAll`（174）`pageCount = 0`。
- **风险**：若 manifest 在首次拉取页数前被写入（整本批量入队时 `pageCount=0` 且进程被杀），`pages >= 0` 恒为真 → 把未完成任务判为已完成。
- **修复**：恢复时若 `task.pageCount == 0` 则按未完成处理。

---

## 五、复查排除项（已确认非缺陷 / 正常）

- **单关键词搜索页码条**：底部 `PaginationBar` 走 `jumpToPage → search(keyword, page)`，服务端分页正常（用户实测 + 本次复核确认）。
- **滚动到底"加载更多"**：按设计已废弃，翻页职责改由页码条承担，**不构成缺陷**。
- **发布一致性**：`update.json` 指向 `pika-v1.5.17.apk`，根目录存在该 APK，应用内更新链路正常。
- **R8 / 混淆**：`proguard-rules.pro` 已 `-keep class org.bouncycastle.**`，BcTls 不会被误删；`packaging` 排除 `/META-INF/versions/**` 避免 BC 多版本资源打包冲突。
- **阅读器主体逻辑**：双模式、手势、亮度、预加载、本地续读、进程恢复（`ON_STOP` 保存）等健全，仅 P1-3 长图切片续读例外。

---

## 六、未覆盖（本次静态评审的边界）

- 未编译、未运行，无法发现语法/类型在 R8 全量模式下的真实裁剪问题、运行时崩溃、权限/Manifest/资源缺失。
- 未对 `JmClient`（禁漫，本轮不在范围）、`PicaHttpEngine` 签名/401 流程、`Coil` 图片加载做深入验证。
- 未做性能压测（大列表滚动手感、并发下载吞吐、内存占用）。
- 未审计第三方依赖许可证与版本漏洞。

---

## 七、修复复查结果（2026-08-15 用户修复后复核）

用户本轮提交 12 个文件改动（`update.json` 版本 bump 至 1.5.17 + 11 源码）。已逐文件 `git diff` + 关键源码重读复核。

### 复核结论概览
| 状态 | 条目 | 说明 |
|------|------|------|
| ✅ 已修复·复核确认 | P1-2、P1-3、P2-2、P2-4、P2-6、P2-7、P2-9 | 共 7 项，diff 正确、无新回归 |
| ❌ 尝试修复·仍失效（且更严重） | P1-1 | 多关键词页码条依旧失灵；本次改动引入"翻到第 N 页显示错误子集" |
| ⚠️ 修复引入的新缺陷 | 收藏页页码条 `jumpToPage` 追加而非跳转 | 新增 PaginationBar 在收藏页翻页错乱 |
| ⬜ 本轮未改动·仍为缺陷 | P2-3、P2-8、P3-1、P3-2、P3-3、P3-4 | 共 6 项，不在本次 diff 中 |

### 逐项复核（已修复·确认）

- **✅ P1-2 删除释放文件**（`DownloadManager.kt` `remove`）：正确。锁内先 `firstOrNull` 取出 `target`，再 `filterNot`+`persist`，最后 `target != null` 时 `deleteRecursively`。闭环，无回归。
- **✅ P1-3 阅读器续读**（`ReaderScreen.kt`）：正确。续读 `LaunchedEffect` 调整至切片计算之后，`scrollMode` 分支用 `pageToRow`（累加各页 `sliceCounts[p]`）换算行号再 `scrollToItem(row)`；`pagerState` 分支仍按页。修复"长图切片后续读错位"。
- **✅ P2-2 收藏 add**（`PicacgSource.kt` `favourite`）：返回判定由 `resp.action.isNotBlank()` 改为 `resp.action.contains("藏")`，对服务端"收藏成功/取消收藏成功"语义更精确。关联确认：哔咔 `/comics/{id}/favourite` 为切换接口、无独立 remove，`add` 无法选端；调用方 `ComicDetailViewModel.favourite()` 以 `!_favourited.value` 表达目标态、切换即达成。本次只矫正成功判定，无回归。
- **✅ P2-4 作者页**（`AuthorViewModel.kt`/`AuthorScreen.kt`）：`currentPage` 由 `var` 改 `StateFlow`（页码条可观察）；`loadComics` 内恢复 `_currentPage` 并新增 `applyFilterAndSave()`（`applyFilterAndSort` 真正生效，状态筛选+本地排序可用）；`LaunchedEffect(Unit)` + `LifecycleEventEffect(ON_PAUSE)` 负责滚动/页码恢复。`AuthorScreen` 的 `ComicGridView` 已 `onLoadMore = {}`，`loadMore()` 不可达，`applyFilterAndSort` 仅在 `loadComics` 调用，与 `loadMore` 的不一致属死代码路径，不影响。
- **✅ P2-6 `data!!`**（`PicaClient.kt`）：`response.data ?: throw PicaException("空响应数据")`，消除 NPE。
- **✅ P2-7 反序列化兜底**（`PicaHttpApi.kt` `parseResponse`）：外包 `try/catch(SerializationException)` 转 `PicaException`，经 `safeCall` 既有异常/重试流程。正确。
- **✅ P2-9 关注流翻页**（`HomeViewModel.kt` `loadMore`）：`next` 由 `maxOrNull()+1` 改 `minOrNull()+1`。原 `max` 被多关键词关注项（哨兵 `=50`）抬高导致单关键词作者页 2–50 跳过；`min` 始终推进"进度最落后"来源，避免跳过。正确。

### ❌ P1-1 多关键词页码条（尝试修复，仍失效且更严重）

用户改动：`computeMultiWordIntersection` 新增 `startPage` 参数，`search` 多关键词分支改传 `page`（即把"起始服务端页"从 1 改为 N）。**根因未触及，并产生新错误子集：**

1. `SearchViewModel.currentPage` 仍是 `var currentPage = 1`（55 行，非 StateFlow），`SearchScreen.kt:189` `PaginationBar(currentPage = viewModel.currentPage)` 无法随内部变化重组 → **页码条永远高亮第 1 页**。
2. 多关键词分支（165-167）`search(keyword, N)` 仍**不设置 `currentPage`**（仅单关键词分支 164 行设置），点第 N 页后 `currentPage` 仍为 1。
3. `buildDisplay()`（277-283）仍 `.take(pageSize)` **忽略 `currentPage`**，永远返回前 20 条。
4. `publishFinalResult()`（313）仍无条件 `_endReached.value = true`。
5. **新错误子集**：`startPage = N` 传入后，`computeMultiWordIntersection` 只从**每词服务端第 N 页起**拉取（192-216、235-249），得到"各词第 N 页之后集合的交集"——**并非"全量交集的第 N 页切片"**。再经 `buildDisplay()` 取前 20 → 翻到第 N 页显示一组**语义错误**内容（且只有 20 条）。

**正确修复方向（建议，未代写）：**
- 将 `currentPage` 改为 `StateFlow<Int>` 并在多关键词分支同步更新。
- 复用已存在的 `buildDisplayForPage(page)`（285-293，`drop/take` 正确）对**已缓存的 `_multiAllComics`** 做客户端分页；`jumpToPage` 在 `_multiSearchComplete` 后**不再重拉**，直接 `_comics.value = buildDisplayForPage(page)`、`_endReached = page >= _totalPages`。
- `PaginationBar` 仅在 `_multiSearchComplete == true`（交集已全量收集）时可点；未完成期间翻页不可靠。
- `publishFinalResult` 的 `endReached` 改为 `currentDisplayPage >= _totalPages`，去掉无条件 `true`。

### ⚠️ 修复引入的新缺陷：收藏页页码条 `jumpToPage` 追加而非跳转

位置：`ui/favourite/FavouriteScreen.kt` `FavouriteViewModel.load(page)` + `jumpToPage`（本轮新增 PaginationBar，update.json 1.5.17 已记）。

现象：`jumpToPage(page)` → `load(page)`，而 `load` 对 `page > 1` 走 `_comics.value = _comics.value + result.items`（**追加**）——`load` 本为"加载更多(append)"设计，`jumpToPage` 复用它却需要"替换"。

后果：收藏页点第 2/N 页会把该页结果**追加**到当前列表（第 1 页结果仍在），而非跳到该页；网格内容错乱、重复，`endReached` 误判。

**修复建议**：`jumpToPage` 直接按页**替换**——
```kotlin
fun jumpToPage(page: Int) {
    needsRestore = false
    _currentPage.value = page
    viewModelScope.launch {
        _loading.value = true
        try {
            val r = SourceManager.current().favourites(page)
            _comics.value = r.items
            _totalPages.value = r.pages.coerceAtLeast(1)
            _endReached.value = page >= r.pages
        } catch (e: Exception) {
            if (_comics.value.isEmpty()) _error.value = e.message ?: "加载失败"
        } finally {
            _loading.value = false
        }
    }
}
```
保留 `load(page)` 仅用于初始加载与 `onLoadMore` 追加。

### ⬜ 本轮未改动、仍为缺陷
P2-3（SourcePrefs 主线程 `runBlocking`）、P2-8（BcTls 宽松信任）、P3-1（阅读器失败文案不可点）、P3-2（长图切片重复解码）、P3-3（Retrofit 死重）、P3-4（`restoreTasks` `pageCount=0` 边界）——均不在本次 12 文件 diff 中，状态不变。

### 二次复查（2026-08-15 第二轮：用户据上轮建议落地修复后）
用户本轮仅改动 `SearchViewModel.kt`（diff 18→30）、`FavouriteScreen.kt`（57→73），按其语义落地了上轮两处建议。复核结果：

- **✅ 收藏页 `jumpToPage` 回归已修复**：`jumpToPage` 现改为按页**替换**（`_comics.value = r.items`）；`currentPage`/`totalPages` 均已转 `StateFlow` 并在 `FavouriteScreen` 用 `collectAsState` 收集，`PaginationBar` 高亮与总页数正确。`load()` 保留给初始加载与 `onLoadMore` 追加，分工清晰。**无新回归**（`needsRestore` 早返回与 `LaunchedEffect(Unit)` 恢复逻辑一致）。
- **✅ P1-1 多关键词分页（功能性）已修复**：`jumpToPage` 多关键词分支不再重拉服务端，改为对**已缓存全量交集** `_multiAllComics` 调用 `buildDisplayForPage(page)`（`drop/take` 正确）做客户端本地分页，`_endReached = page >= _totalPages`。上轮指出的"传 `startPage=N` 导致错误子集"问题，因 `jumpToPage` 不再以多关键词走 `search()`（仅初始 `search(keyword,1)` 以 `startPage=1` 进入）而不再触发。翻页**内容正确**。
- **✅（另一 agent 据建议落地，本轮复核确认）`SearchViewModel.currentPage` 可观察化已修复**：`currentPage` 改为 `private val _currentPage = MutableStateFlow(1)` + `val currentPage: StateFlow<Int>`；VM 内所有赋值 `_currentPage.value = x`、读取 `currentPage.value`（`search`/`jumpToPage`/`updateSortOnly`/`loadMore` 均已覆盖，无遗漏）；`SearchScreen` 改为 `val currentPage by viewModel.currentPage.collectAsState()`，`PaginationBar(currentPage = currentPage)`、`saveScrollState(..., viewModel.currentPage.value)`。全项目扫描无其他文件将 SearchViewModel.currentPage 当 Int 直接读，无编译/类型风险、无回归。P1-1 至此**完整修复**（多关键词翻页内容正确 + 页码条高亮正确）。
- **其余 7 项已修复项本轮未再改动，保持有效；6 项未改动缺陷（P2-3/P2-8/P3-1~P3-4）状态不变。**
