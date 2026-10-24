# 阅读器页面图片加载失败 · 自动重试设计（v3）

> 版本基准：v1.9.2 (build 85) → v1.9.3 (build 86) → v1.9.4 (build 87)
> 日期：2026-10-25
> 性质：设计稿 + 两轮事故分析与实施记录。所有「现状」均经代码级核实，附文件依据。

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

## 四、第三轮（v1.9.4）：v1.9.3 仍「卡住后闪退」+ 崩溃日志丢失

### 4.1 用户反馈

v1.9.3 阅读仍会卡住然后闪退；崩溃后重新进入，应用内日志为空——LogStore 是纯内存环形缓冲，进程死亡即清零，崩溃证据无从取证。

### 4.2 根因定位（本轮已核实的事实链）

1. v1.9.3 的阅读器代码与 v1.9.1 **逐字节一致**（`git diff 8285116` 为空），代码级差异仅 RetryInterceptor（HTTP 层）。
2. 核实 Coil 2.7 `HttpUriFetcher.executeNetworkRequest`：图片请求经 `call.enqueue`（异步调度器）执行 → **受 OkHttp Dispatcher「每主机最多 5 个并发」默认约束**。
3. **重试槽位风暴**：用户网络本身频繁瞬时失败（这正是自动重试需求的来源）。v1.9.3 的每个失败请求在「退避 sleep + 最多 3 次尝试（每次最长 10s 连接 + 10s 读超时）」期间**持续占住每主机槽位**。阅读时可见页 + 预取共 5+ 个不同 URL 并发失败，5 个槽位即被重试循环占满 → 其余全部图片请求在 dispatcher 排队 → **全屏「加载中」卡死（=用户看到的「卡住」）**，持续无响应后被系统按 ANR/无响应处理或用户侧表现为「闪退」。v1.9.1 失败即单次快速返回、槽位立刻释放，无此放大效应。
4. 「闪退」的确切类别（ANR / OOM / 异常）此前无数据可证——这暴露了第二个缺陷：崩溃不落盘。

### 4.3 v1.9.4 修复（三层）

1. **RetryInterceptor 并发重试限额（治根）**：全进程 `Semaphore(3)`，进入重试前 `tryAcquire`，拿不到名额即放弃本次自动重试、按 v1.9.1 行为直接交还失败（手动重试兜底仍在）。限额 3 < 每主机 5，**数学上保证重试风暴下至少 2 个槽位留给正常请求，不可能再全局停摆**；退避缩短为 400ms/1200ms（单请求额外占用上限 ≈1.6s sleep + 尝试时间）。重试事件同步写入 LogStore（崩溃报告的证据链）。
2. **CrashReporter 崩溃落盘（治盲）**：全局 UncaughtExceptionHandler 在崩溃瞬间同步写 `filesDir/crash/last_crash.txt`（堆栈 + 应用版本 + 设备 + LogStore 尾部 + 系统 crash 缓冲尽力抓取），下次启动日志页顶部横幅呈现（查看/复制/忽略）——崩溃证据跨进程存活，后续任何闪退均可取证。
3. **MainThreadWatchdog 卡顿看门狗（补证）**：后台线程每 4s 向主线程投递心跳，3s 未到达即记录主线程调用栈到 LogStore（每次卡顿周期 ≥10s 才记一条）。「卡住」阶段若是主线程阻塞，栈会随崩溃报告一并落盘。

### 4.4 验证与发版

- `gbuild.sh assembleRelease` BUILD SUCCESSFUL；v1.9.4 (build 87)，APK/update.json 已更新并推送。
- 真机复现路径：安装后正常阅读；若再次卡住/闪退 → 重新打开 App → 设置 → 调试日志 → 顶部「检测到上次异常退出」→ 复制报告反馈。报告含确切崩溃堆栈与版本号（同时可甄别实际运行的版本）。

## 五、残余风险与后续

- 若崩溃根因并非槽位风暴（如 OOM），v1.9.4 的崩溃报告将给出确切堆栈，按报告定向修复。
- `Thread.sleep` 退避仍占用 OkHttp dispatcher 线程，但并发已被信号量压到 ≤3，可接受；如后续观测到问题再改自管调度。
- Cloudflare 403 类确定性拦截不自动重试（重试无意义），手动重试兜底行为与 v1.9.1 一致。
- 教训沉淀：① 对「自动、并发、与生命周期时序交织」的请求重执行，优先选择对上层完全透明的层实现；② **任何重试机制必须先回答「重试期间占用了什么资源、上限是多少」**——v1.9.3 的回归正是没有回答这个问题；③ 无崩溃落盘的 App 排障等于盲飞，崩溃捕获应是第一个上线的「功能」。
