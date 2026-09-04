# PiKA（com.pika）代码复测报告（第二次）

- **项目路径**：`G:\game\新建文件夹\pka\pika`
- **应用**：`com.pika`，Kotlin + Jetpack Compose，minSdk 26 / targetSdk 35
- **版本**：versionCode 44 / versionName 1.5.18（工作区含未提交改动，介于 v1.5.18 与下一版之间）
- **复测日期**：2026-08-15
- **复测对象**：上一轮复测报告的遗留/未修复项（③ 收藏布局、⑦ 首页滚动恢复 var、C1/C2/C3 首页缓存新功能）+ 已修复项回归核查 + 关键词删除
- **复测方式**：全部基于 `git diff` + 源码通读核实，**未做编译/运行**。

> 结论速览：**上一轮遗留的 5 个问题，③/⑦/C1/C2 已彻底修复，C3 主问题（取消关注后回退陈旧缓存）已修复、仅剩一处轻微残留（刷新失败无错误态 + loading 可能卡死）；其余已修复项无回归。**

---

## 一、上一轮 8 条发现 · 本轮复测结论

| 条目 | 原级 | 本轮结论 | 关键证据 |
|------|------|----------|----------|
| ① 首页关注流"假死" | P1 | ✅ **已修复（无回归）** | `HomeViewModel.refresh()` 只拉各来源第 1 页、合并排序 `take(120)`、`endReached=true`、`onLoadMore={}`；多词关注项一次拉全取交集，不再滚动加载。`HomeScreen.kt:179` |
| ② 分类页码条高亮失效 | P1 | ✅ **已修复（无回归）** | `BrowseViewModel.kt:63` `_currentPage = MutableStateFlow(1)`（StateFlow）；`CategoryScreen` 用 `collectAsState` 传入 |
| ③ 收藏页改纯分页（布局回归） | P2 | ✅ **已彻底修复** | `FavouriteScreen.kt:169-190` 改为 `Column(fillMaxSize){ ComicGridView(weight(1f)); if(totalPages>1) PaginationBar }`，浮层覆盖末行的回归消失 |
| ④ 离线页码字典序 | P2 | ✅ **已修复（无回归）** | `ReaderViewModel.kt:62` `sortedBy { it.name.replace(Regex("\\D"),"").toIntOrNull() ?: 0 }`，按数字排序 |
| ⑤ 表单键盘遮挡 | P3 | ✅ **已修复（无回归）** | 登录/注册/找回密码三页均含 `.imePadding()`（各 2 处） |
| ⑥ 阅读器切章切片残留 | P3 | ✅ **已修复（无回归）** | `ReaderScreen.kt:157-158` `LaunchedEffect(order){ sliceCounts.clear() }`，切章即清 |
| ⑦ 首页滚动恢复用 `var` | P3 | ✅ **已修复** | `HomeViewModel.kt:94` `_isScrollStateRestored = MutableStateFlow(false)`；`HomeScreen.kt:71,80` 改 `collectAsState` + `LaunchedEffect(scrollStateRestored)` |
| ⑧ 分类/作者分页间距 | P3 | ✅ **已修复（观感统一）** | `PaginationBar.kt:53` 改为 `.padding(8.dp)`（含垂直）；三处调用点统一，分类页不再多 8dp |

---

## 二、关注管理「关键词无法删除」· 修复验证（仍有效）

- `data/FollowSettings.kt` `addItem`/`removeItem` 已用 `.commit()` 同步落盘；`removeItem` 按唯一 `createdAt` 过滤，`FollowManageScreen` 删除后 `reload()` 重读触发重组。
- 本轮：`.commit()` 仍保留（`FollowSettings.kt:51,58`），无回归。✅ **已修复，逻辑正确**。

---

## 三、首页关注页缓存新功能 · 本轮复测结论

用户需求：*"首页关注页面增加缓存，在没有拉到最新数据前，展示最近的一次缓存。"*

### 🔴 C1（P2，功能缺口 — 主问题）缓存仅存内存，冷启动不展示上次缓存
- **状态：✅ 已修复。**
- 新增 `data/FollowFeedCache.kt`：`SharedPreferences` 持久化（key `follow_feed_cache/feed_json`），`save(feed)` 写入 `feed.take(120)`、`load()` 读回、`clear()` 清空；并在 `PiKAApp.onCreate` 注册 `FollowFeedCache.init(this)`（`:21`）。
- `HomeViewModel.init { _followFeed.value = FollowFeedCache.load() }`（`:111`）——**冷启动立即展示上次成功刷新的缓存**。
- `refresh()` 成功末尾 `FollowFeedCache.save(_followFeed.value)`（`:172`）持久化。满足需求。

### 🟡 C2（P3，逻辑）缓存展示分支是"死代码"
- **状态：✅ 已修复（设计重构）。**
- 原 `_followCachedFeed` 内存字段与 `followFeed.isEmpty() && cached.isNotEmpty()` 死分支已彻底移除。现改为「初始化即把持久化缓存读入 `_followFeed`、刷新成功再覆盖并保存」的清晰模型，`FollowTab` 直接展示 `followFeed`，无冗余分支。

### 🟡 C3（P3，边界）清空关注 / 刷新失败时静默回退陈旧缓存
- **主问题（取消关注全部）：✅ 已修复。** `refresh()` 的 `targets.isEmpty()` 分支现同时 `_followFeed.value = emptyList()` + `FollowFeedCache.clear()`（`:152-155`）+ 设空态提示「还没有关注内容…」，不再回退显示已取消关注作者的旧漫画。
- **残留（刷新失败）：⚠️ 轻微、符合预期的一部分。** `refresh()` 的 `launch` 块**没有 `try/catch/finally`**：若 `fetchTargetPage(1)` 抛异常（网络失败），`_followLoading` 不会被复位而**卡在 `true`**（下拉刷新圈一直转），且 `_followFeed` 保留 init 载入的缓存（即展示陈旧缓存）——但"展示缓存"本身正是该功能的预期降级行为，无功能错误，仅缺**错误/重试态**与 loading 兜底。
- **建议（非阻塞）**：`refresh()` 的 `launch` 块加 `try/finally { _followLoading.value = false }`，失败时给出轻量错误提示（toast 或 inline），与"展示缓存"并存。

---

## 四、收藏页布局回归 · 已修复确认（对应原 ③）

- 原 `Box{ ComicGridView(fillMaxSize) + PaginationBar(align BottomCenter) }` 浮层覆盖末行 → 现改为 `Column(fillMaxSize){ ComicGridView(weight(1f)); PaginationBar }`（`FavouriteScreen.kt:169-190`）。
- 分页条排在网格**下方**、各占高度，不再叠加覆盖任何漫画行；与搜索/作者页的 `Column + weight` 模式一致。✅

---

## 五、顺带确认的正面改动（非缺陷）

- `SearchViewModel`/`AuthorViewModel`/`FavouriteViewModel` 的 `currentPage` 均已 `StateFlow` + `collectAsState`，全站分页高亮一致。
- 滚动恢复用 `LifecycleEventEffect(ON_PAUSE/ON_RESUME)`（`FavouriteScreen`/`AuthorScreen`/`HomeScreen`），比原 `DisposableEffect` 更稳，已覆盖导航返回场景。
- 多线程关注项客户端取交集、`searchWithRetry` 失败重试 3 次；多词拉取 `Semaphore(4)` 控并发避免哔咔限速。

---

## 六、修复状态汇总与剩余项

| 项 | 上一轮状态 | 本轮状态 |
|----|-----------|----------|
| ① 假死 | 已修复 | ✅ 保持，无回归 |
| ② 分类页码 | 已修复 | ✅ 保持，无回归 |
| ③ 收藏布局 | ⚠️ 布局回归 | ✅ 已彻底修复 |
| ④ 离线字典序 | 已修复 | ✅ 保持，无回归 |
| ⑤ 键盘遮挡 | 已修复 | ✅ 保持，无回归 |
| ⑥ 切片残留 | 已修复 | ✅ 保持，无回归 |
| ⑦ 滚动恢复 var | ➖ 未改 | ✅ 已修复（StateFlow） |
| ⑧ 分页间距 | 已修复 | ✅ 保持，观感统一 |
| 关键词删除 | 已修复 | ✅ 保持，无回归 |
| C1 缓存持久化 | 🔴 未修 | ✅ 已修复（FollowFeedCache + init 载入） |
| C2 死代码 | 🟡 未修 | ✅ 已修复（设计重构） |
| C3 取消关注回退 | 🟡 未修 | ✅ 主问题已修复 |
| **C3 残留：刷新失败无错误态 / loading 卡死** | — | ⚠️ **轻微残留（非阻塞，建议加 try/finally）** |

**结论：除 C3 的轻微残留外，上一轮所有缺陷与缓存新功能的 bug 均已修复完成，且已修复项无回归。C3 残留仅影响失败时的错误提示与刷新圈复位，不影响"展示缓存"这一核心需求，可后续顺手修。**
