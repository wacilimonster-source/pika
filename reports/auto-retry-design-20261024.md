# 阅读器页面图片加载失败 · 自动重试设计（v2）

> 版本基准：v1.9.2 (build 85) 回归修复 → v1.9.3 (build 86)
> 日期：2026-10-24
> 性质：设计稿 + 事故分析与实施记录。所有「现状」均经代码级核实，附文件依据。

---

## 一、需求

看漫画时部分页面图片加载失败，手动点「重试」即可恢复——失败多为瞬时性（网络抖动、CDN 闪断、限流），但需要用户手动介入。目标：**失败自动重试，用户无感知恢复；持久失败保留手动兜底。**

## 二、v1.9.2 方案（UI 层自动重试）与回归事故

### 2.1 原方案

新文件 `PageAutoRetry.kt`：`PageRetryState`（tick/autoAttempts）+ `PageAutoRetryEffect`（`LaunchedEffect(state, tick)`，Error 态按 1s/2s/4s 退避自动 tick++，共 3 次），驱动 model 追加 `#retryN` 强制 Coil 重新请求；用尽后回退手动「点击重试」，手动点击清零计数重新计满一轮。

### 2.2 事故

**真机阅读时卡住然后闪退**（v1.9.2 引入，v1.9.1 无此问题）。

### 2.3 事故分析（静态层面已排除的假设）

逐项核对 Coil 2.7.0 源码（compose 侧 painter 执行管线）后，以下机制均被证明有界/无死锁：

1. **painter 重启风暴**：`rememberAsyncImagePainter` 每次重组都会 `painter.request = request`（AsyncImagePainter.kt L212），但 `AsyncImagePainter.request` 是 `mutableStateOf`，且 **`ImageRequest` 实现了全字段 `equals`**（ImageRequest.kt L183）——内容相同的重建请求不会触发 `snapshotFlow { request }` 重执行；只有 data 变化（`#retryN`）才重执行。重执行次数受 attempts 计数约束（≤3）。
2. **重试死循环**：`autoAttempts` 只在 `delay` 完成后递增，effect 因 state/tick 变化重启只重置延迟不定额；手动清零仅由点击触发。数学上有界。
3. **CoalescingInterceptor 死锁**：leader 的 `finally { inFlight.remove(key) }` 保证任何退出路径（含取消）都清理窗口；等待者对已死 leader 的降级重选主逻辑自洽。
4. **CrossfadePainter 失控**：动画按 `SystemClock.uptimeMillis` 时间驱动，时长有界，无帧循环。
5. **请求无限挂起**：BcTls 未改 OkHttp 默认超时（connect/read 10s）。
6. **组合期快照写**：所有状态写（tick/attempts）都发生在协程或点击回调，无组合期写。

**结论：静态分析无法定位确定性根因**，但崩溃事实与 v1.9.2 唯一改动（UI 层自动重试机制）强相关。该机制的本质风险在于：自动重试把「一次刻意的、单次的 model 变更」变成「无人值守的、多 item 并发的、与滚动/切章/取消时序交织的重执行流」，与 painter 的 `snapshotFlow+mapLatest`（新请求自动取消在途执行）、合流器的选主/降级、交叉淡入等机制产生组合爆炸式的时序交互，其正确性无法靠静态审查穷尽验证，真机上已实际出现冻结+崩溃。

### 2.4 v2 决策：回退 UI 层机制，下沉到 HTTP 层

- **整体回退** `WebtoonSplitPage.kt` / `ReaderScreen.kt` 至 v1.9.1（8285116，经 `git diff 8285116` 验证零差异），删除 `PageAutoRetry.kt`。阅读器恢复到长期稳定的行为。
- **自动重试改在 OkHttp 应用层实现**（`image/RetryInterceptor.kt`，挂到 `BcTls.imageLoaderClient`，该 client 仅 Coil 使用）：

| 维度 | v1.9.2（UI 层） | v2（HTTP 层） |
|---|---|---|
| 重试位置 | Compose 状态驱动 painter 换 model 重执行 | 同一次 Coil 请求内部，OkHttp `proceed` 前循环 |
| 对 Coil 的可见性 | 每次重试 = 新请求（重组+painter 重启+合流重选主） | **完全不可见**（一次请求，只是更晚返回） |
| 触发面 | 仅阅读器两个渲染路径 | 所有 Coil 图片（封面等同样受益） |
| 崩溃面 | 引入回归 | **失败面严格小于 v1.9.1**（请求流同构，仅成功率更高） |
| 手动兜底 | 保留 | 保留（阅读器 UI 与 v1.9.1 完全一致） |

### 2.5 RetryInterceptor 语义

- **只重试瞬时失败**：`IOException`（连接重置/读写超时/DNS 抖动等）与 HTTP 408/429/5xx（含 Cloudflare 520-526）。403/404 等确定性失败不重试——重试无法恢复，只会拖慢出错反馈。
- **额度与退避**：最多 2 次重试（共 3 次尝试），退避 500ms/1500ms（总额外等待 ≈2s，覆盖用户「手点即成功」的瞬时窗口）。
- **取消感知**：`call.isCanceled()` 优先判断——Coil 在页面滚出屏幕/切章/退出阅读器时会取消请求，被取消的调用立即放行绝不重试；重试前关闭失败响应体，避免连接池泄漏。
- **背压**：退避用 `Thread.sleep`（OkHttp dispatcher 线程池承载，64 线程上限，失败风暴下最坏占用可控）。

## 三、测试与发版（v1.9.3, build 86）

- 编译级验证：`gbuild.sh assembleRelease` BUILD SUCCESSFUL（按要求不做模拟器/真机）。
- 静态自查：阅读器与 v1.9.1 逐字节一致；RetryInterceptor 的取消路径（isCanceled 三处检查）、响应体关闭路径、额度用尽路径均已核对。
- 发版：v1.9.3 (build 86)——版本 bump、release APK、update.json、提交推送。

## 四、残余风险与后续

- 若用户遇到的瞬时失败实为 Cloudflare 403（1023 挑战页）闪断，HTTP 层不会自动重试该类失败——但手动「点击重试」兜底仍在，且与 v1.9.1 行为一致。真机验证后如仍有个案，可按日志（RetryInterceptor tag）再评估把 403 纳入白名单。
- `Thread.sleep` 退避在高并发失败窗口占用 dispatcher 线程；如后续观测到线程饥饿，可改为响应式延迟（OkHttp 无原生支持，需自管调度线程）。
- 教训：**对涉及「自动、并发、与生命周期时序交织」的 UI 层请求重执行机制，编译通过≠可发版；应优先选择对上层完全透明的层（网络层）实现重试。**
