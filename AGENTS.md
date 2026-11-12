# PiKA 工程守则（供所有 agent / 开发者）

> 这不是建议清单，而是本仓库的硬约束。改动前先读完本文件；
> 违反其中任何一条，都会让后续维护者付出比本次改动更高的成本。
> 事故复盘与真实案例见 [`docs/ENGINEERING-LESSONS.md`](docs/ENGINEERING-LESSONS.md)。

## 1. 项目速览

- 单模块 Android App：Kotlin + Jetpack Compose + Material 3，`minSdk 26 / targetSdk 35`。
- 单 Activity + Navigation Compose；入口 `app/src/main/java/com/pika/MainActivity.kt`。
- 分层（依赖方向只能向下）：
  - `ui/`：Compose 页面与 ViewModel（只依赖 `core` / `data` 的公开接口）
  - `core/source/`：`Source` 接口 + `SourceManager` + `PicacgSource`（网络/解析）
  - `core/download/`、`core/update/`、`core/log/`
  - `data/`：DataStore / SharedPreferences 持久化
  - `network/`：签名、TLS、HTTP 引擎与 DTO
  - `image/`：Coil 图片管线（合流 + 重试 + 取证）
- 当前只有哔咔一个数据源。`ComicRef` 的 `源_id` 是跨模块唯一标识，**不要用裸 comicId 记账**。
- 设计令牌与组件：`ui/theme/`、`ui/common/`（`PikaSegmentedControl` / `PikaFilterChip` / `ErrorRetry` / `ComicGridSkeleton`）。

## 2. 构建与验证

```bash
# 编译（最快反馈）
bash gbuild.sh compileReleaseKotlin

# 单元测试（纯 JVM，无 Android 依赖）
bash gbuild.sh testReleaseUnitTest

# 打 release APK（发版才需要）
bash gbuild.sh assembleRelease
```

硬规则：

1. **构建成功 ≠ 可运行**。任何涉及启动、Compose 状态、生命周期、DataStore 初始化的改动，必须在模拟器/真机冷启动一次；仅跑编译不算验证。
2. **UI 改动必须实测深浅色 + 至少一次字体缩放 1.3**；涉及阅读器的还要测滚动流与横滑翻页两种模式。
3. **网络/重试/下载改动必须做故障注入**（断网、截断响应、5xx、限流），不能只测正常网络。
4. 提交前跑 `python tools/code-health.py --check`；结构性指标（全限定名、超长函数/文件、协程内裸 `runCatching`、TODO、`!!`、重复代码）变差或测试文件数减少，视为回归。
5. 修复 bug 必须补一个能复现该 bug 的纯 JVM 单测；无法单测的，在 PR/提交说明里写清手动复现步骤与实测结果。

## 3. 代码硬规则

### 3.1 先找现成的，再写新的

- 写筛选/排序/分页/网格间距/重试/解码上限前，先全局搜索：
  `applyStatusAndSort`、`pageSlice`、`pageCount`、`comicGridMetrics`、`maxDecodeHeightPx`、`searchWithRetry`、`fetchPagesInOrder`、`runCatchingCancellable`。
- **禁止复制粘贴后改一处**。同一语义出现第二份实现时，先提取共享函数；提取不了必须在注释里写明原因。
- **禁止新增「兼容旧引用」别名/死代码**。没有真实调用点的声明直接删除；Kotlin 编译器会告诉你有没有漏。

### 3.2 导入与命名

- 业务代码中**禁止使用全限定名**（`com.pika.xxx.Yyy`、`androidx.xxx.Yyy`），一律 import。
  （KDoc 链接与字符串字面量除外。）
- 排序/分页/页大小等常量只能有一个来源：
  - 页大小：`com.pika.ui.browse.SERVICE_PAGE_SIZE`
  - 解码上限：`WebtoonSplitPage.MAX_DECODE_WIDTH_PX` / `maxDecodeHeightPx()`
  - 网格间距：`comicGridMetrics(columns)`
- 新增字段到可序列化模型时，必须给默认值并考虑旧数据反序列化（见 `DownloadTask.source`、`AuthorEntry.createdAt`）。

### 3.3 协程与状态

- **协程内包裹挂起调用时禁止裸 `runCatching`**：它会吞掉 `CancellationException`。用
  `com.pika.core.runCatchingCancellable`；手写 `try/catch` 时把
  `catch (e: CancellationException) { throw e }` 放在最前面。
  （纯 CPU 的 JSON 解码、同步 SharedPreferences 读写可以用 `runCatching`。）
- ViewModel 里所有「请求代际」逻辑（搜索、翻页、切章、详情）必须用 token/generation 丢弃过期响应；
  不要只靠 `Job.cancel()`（取消只在挂起点生效）。
- 分页循环必须满足两个不变量：
  1. 请求**成功**（包括空页）就推进游标；
  2. 本轮全部失败必须 `break`，不能原地重试到限流。
  参考 `SearchViewModel.computeMultiWordIntersection` 与 `fetchPagesInOrder`。
- 主线程禁止同步读盘/写盘；DataStore 写操作用「内存缓存即时生效 + AppScope/IO 投递」。
- Compose 组合期禁止做 IO / SharedPreferences 读取；放到 `LaunchedEffect` + `Dispatchers.IO`。

### 3.4 错误与用户可见性

- 失败必须可见、可重试；禁止把异常吞成「空列表/暂无内容」。
- 错误文案要区分：网络失败、限流、服务端 5xx、真实空结果。不要把原始 HTML/JSON 直接展示给用户。
- 任何自动重试都要有上限、退避、并发限额，并且对取消透明。

### 3.5 规模控制

- 单个 Composable 函数超过 ~150 行、单个文件超过 ~800 行时，必须先拆分或说明理由。
  `ReaderScreen` 的 900+ 行主函数是已知技术债，**不要继续往里堆逻辑**；新增能力优先做成独立 composable/纯函数。
- 一个 release 只做一组相关改动。禁止「22 项修复一起发」；问题越多，回归定位越难。

## 4. 验证门槛（Definition of Done）

一个改动只有同时满足以下条件才算完成：

- [ ] `compileReleaseKotlin` 通过
- [ ] `testReleaseUnitTest` 通过，且新增/修改逻辑有对应单测
- [ ] `python tools/code-health.py --check` 通过
- [ ] UI：模拟器冷启动 + 目标页面操作 + 深浅色/字体 1.3（如涉及）
- [ ] 网络/下载：至少一种故障注入场景实测
- [ ] 发版：APK 安装/覆盖安装成功，`update.json` 的 sha256 与实际 APK 一致
- [ ] 提交说明写清「改了什么 / 为什么 / 怎么验证的」，不要只写「优化」「修复」

## 5. 已知技术债（改动时避开或顺手偿还）

| 位置 | 问题 | 约束 |
|---|---|---|
| `ui/reader/ReaderScreen.kt` | 1500+ 行，主 Composable 900+ 行 | 新增逻辑放独立 composable；不要扩大主函数 |
| `core/source/PicacgSource.kt` | 网络/映射/收藏纠错混在一起 | 新逻辑优先放独立文件；不要复制分页 fan-out |
| `image/` 三层拦截器 | `RetryInterceptor`(OkHttp) + `CoilRetryInterceptor`(Coil) + `CoalescingInterceptor` | 三层职责已在类注释写清；不要随意合并或调整顺序 |
| `data/` 多个 SharedPreferences+JSON 单例 | 模板重复 | 新增此类存储优先抽 helper，不要继续复制 |
| `.git` 体积 | 历史 APK 较大 | 不要提交调试 APK/截图；发版 APK 仅保留当前版本 |

## 6. 文档与记忆

- 发生线上/实测事故后，除了修代码，必须往 `docs/ENGINEERING-LESSONS.md` 追加一条：
  症状、根因、为什么之前的验证没发现、现在的防线。
- `.workbuddy/memory/MEMORY.md` 是运行环境/发版流程的长期记忆；与本文件冲突时，以本文件为准并同步修正。
- 注释写「为什么」，不要写「是什么」；过时注释比没有注释更危险，改代码时同步改注释。
