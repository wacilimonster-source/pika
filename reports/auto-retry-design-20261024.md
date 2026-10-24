# 阅读器页面图片加载失败 · 自动重试设计

> 版本基准：v1.9.1 (build 84)，哔咔单源
> 日期：2026-10-24
> 性质：设计稿 + 实施记录。所有「现状」均经代码级核实，附文件依据。

---

## 一、背景与问题

看漫画时部分页面图片加载失败，UI 显示「第 N 页加载失败 / 点击重试」，用户手动点击后即可加载成功。这说明失败绝大多数是**瞬时性**的（网络抖动、CDN 闪断、Cloudflare 边缘节点偶发拦截），但当前需要用户手动介入。目标：**失败后自动重试，用户无感知恢复；自动重试用尽后保留现有手动兜底。**

## 二、现状（代码级核实）

阅读器有两条单页渲染路径，各自维护一个手动 `retryTick`（model 追加 `#retryN` 强制 Coil 重新请求，缓存键固定裸 URL）：

| 路径 | 文件 | 失败态 UI | 手动重试 |
|---|---|---|---|
| 滚动流（条漫/切片） | `WebtoonSplitPage.kt` PageImage | 「第 N 页加载失败\n点击重试」，整块可点 | `retryTick++` |
| 横滑翻页 | `ReaderScreen.kt` PagerPage | 「第 N 页加载失败」+「点击重试」 | `retryTick++` |

辅助设施：
- `CoalescingInterceptor`：同「缓存键+解码尺寸」的并发请求合流；首领失败时等待者降级自执行，失败结果只在合流窗口内共享——天然抑制失败风暴。
- 整章加载失败（pages 为空，`ReaderScreen.kt` L788）走 `viewModel.retry()`，是 **API 层失败**（可能 token 过期等），不属于本次目标。

## 三、设计

### 3.1 语义

- **触发**：`AsyncImagePainter.State.Error` 进入且自动重试额度未用尽 → 安排一次延迟重试。
- **退避**：1s → 2s → 4s（`1000L shl attempts`），共 **3 次**自动重试（总窗口 ≈7s + 请求耗时）。足以覆盖瞬时故障，又不会在持续故障时制造请求风暴（配合合流拦截器的失败窗口共享）。
- **用尽**：3 次后停表，UI 回退为现有「点击重试」手动态，行为与 v1.9.1 完全一致。
- **手动重试**：任何时刻点击（包括自动重试退避等待中）立即重试，并**清零自动计数、重新计满一轮**。
- **生命周期**：定时器随组合存在——LazyColumn / HorizontalPager 会回收屏幕外 item（Pager `beyondViewportPageCount = 1`），离开可视区自动取消挂起的 delay，**无后台重试**。
- **计数重置**：状态持有者按 `(pageIndex, imageUrl)` remember——切章时 LazyColumn item 键（`row_p_s`）复用组合节点，若不重置，上一章用尽的额度会传染给新章同位置的页。

### 3.2 UI 文案（两条路径一致）

| 状态 | 滚动流 | 横滑翻页 |
|---|---|---|
| 自动重试中（额度未用尽） | 第 N 页加载失败<br>**正在自动重试…** | 第 N 页加载失败<br>**正在自动重试…** |
| 自动重试用尽 | 第 N 页加载失败<br>点击重试（现状文案） | 第 N 页加载失败<br>点击重试（现状文案） |

两种状态下整块区域始终可点击 = 立即手动重试。

### 3.3 技术方案

新文件 `ui/reader/PageAutoRetry.kt`（两条路径共用，保证行为一致）：

```kotlin
class PageRetryState internal constructor() {
    var tick: Int            // 请求代次，每次重试 +1，驱动 model 变化触发 Coil 重新请求
    var autoAttempts: Int    // 已自动重试次数（snapshot state：UI 文案依赖它切换）
    val autoRetriesLeft: Boolean
    fun retryManually()      // 清零计数 + tick++
    internal fun retryAutomatically()  // 计数 +1 + tick++
}

@Composable
fun rememberPageRetryState(vararg keys: Any?): PageRetryState   // keys 变化即重置

@Composable
fun PageAutoRetryEffect(state: AsyncImagePainter.State, retry: PageRetryState)
// LaunchedEffect(state, retry.tick)：Error 且额度未用尽 → delay(1000 shl attempts) → retryAutomatically()
```

关键机制（均为既有已验证行为的复用）：
- **tick 驱动重新请求**：与现有手动重试同一手法（model 追加 `#retryN`），HTTP 请求不受 fragment 影响；`memoryCacheKey/diskCacheKey` 固定裸 URL，重试成功结果可被预取/其他切片/回滚重建的 item 复用（v1.9.1 第 2 项优化的语义保持不变）。
- **Effect 重启链条**：tick++ → 重组出新 model → painter 重新执行 → state 经 Loading 再落 Error 时，`(state, tick)` 键值必然变化，effect 重启进入下一档退避；state 离开 Error（成功/被回收）即取消挂起的 delay。
- **autoAttempts 用 snapshot state**：普通 var 的变化不会触发重组，UI 文案「自动重试中 ↔ 点击重试」无法切换。
- **与合流拦截器协同**：自动重试请求若与在途请求同键则合流共享（成功即复用、失败不放大风暴）；首领失败后等待者降级自执行的语义不受影响。

### 3.4 非目标

- **整章加载失败不加自动重试**：API 层失败原因可能是 token 过期/风控，盲目自动重试整条管线风险大于收益，保留现有手动「重试」按钮。
- 不改 Coil 全局 OkHttp 层重试：页级重试在 UI 层语义更精确（只重试可见页、随组合取消、可视反馈）。
- 详情页/搜索页的全屏「点击重试」不在本次范围（用户场景为阅读器内单页失败）。

## 四、实施记录

- `PageAutoRetry.kt`：新增（状态 + Effect，约 80 行）。
- `WebtoonSplitPage.kt`：`retryTick` 换为 `PageRetryState`，接入 `PageAutoRetryEffect`；Error 文案按额度分支。
- `ReaderScreen.kt` PagerPage：同上。

## 五、测试与发版

- 测试：`gbuild.sh assembleRelease` 编译级验证（按要求不做模拟器/真机），BUILD SUCCESSFUL 即通过；重点自查两条路径接入、文案分支、生命周期取消路径、切章计数重置。
- 发版：v1.9.2 (build 85)——版本号 bump、release APK 构建产物 `pika-v1.9.2.apk`、`update.json` 更新（sha256/apkUrl/notes）、提交推送。

## 六、残余风险

- 自动重试只覆盖「请求级」瞬时失败；若失败原因是图片 URL 失效（404 等），3 次自动重试后仍回退手动态——与现状一致，无回归。
- 持续弱网下 3 次自动重试会带来最多 3 次额外流量（每页），合流拦截器保证同页多切片不会 ×6 放大。
