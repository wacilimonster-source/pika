# PiKA 性能优化与技术架构优化设计

日期:2026-10-24
范围:全量代码审查(82 个 Kotlin 文件 / 约 1.76 万行),覆盖数据层、网络与图片管线、UI/Compose、启动与构建四个专项。
方法:四个并行深度分析 + 关键结论人工复核(P0 项均已逐一读源码确认)。

---

## 0. TL;DR

当前代码库已经做过大量针对性优化(解码上限体系、行前缀和去物化、页面镜像防每帧重组、原子 manifest、预取键对齐等),整体质量高于同类业余项目。剩余的性能问题按性价比排序,**Top 10**:

| # | 级别 | 问题 | 位置 | 预期收益 |
|---|------|------|------|----------|
| 1 | P0 | 长图同页多切片各自发起独立请求,无在途合并(最多 6 路并发下载/解码同一张数 MB 原图) | `ReaderScreen.kt:844-860` → `WebtoonSplitPage.kt:78-86` | 消除最大 OOM/带宽风险,首屏峰值内存降 60%+ |
| 2 | P0 | 失败重试改写 model(`#retryN`)导致缓存键漂移,失败过的页反复全量重新下载 | `WebtoonSplitPage.kt:71-80`、`ReaderScreen.kt:1272-1276` | 弱网场景流量减半 |
| 3 | P0 | 无 Baseline Profile,冷启动全走 JIT | 全局 | 启动提速 20-30% |
| 4 | P1 | API/下载每请求 `disconnect()`,放弃 keep-alive,BCJSSE 握手(比 Conscrypt 慢数倍)逐请求重复 | `PicaHttpEngine.kt:180`、`DownloadManager.kt:519` | API 密集路径延迟明显下降 |
| 5 | P1 | 章节抽屉在组合期做 `listFiles()` 磁盘 IO(长篇几百话 = 每行一次目录遍历) | `ReaderScreen.kt:1055` | 消除抽屉滚动卡顿 |
| 6 | P1 | 列表封面也拉 original 原图(`image-quality` 头硬编码) | `PicaHttpEngine.kt:99` | 网格流量降 70%+ |
| 7 | P1 | 142 处 `collectAsState` 全部无生命周期感知 | 全 UI 层 | 后台 CPU/电量 |
| 8 | P1 | 阅读进度每翻一页整文件重写 DataStore + 100 条 JSON 反复编解码 | `ReaderPrefs.kt:141-146,345-366` | 阅读期 IO 写放大降 90%+ |
| 9 | P1 | `FollowSettings` 用 `commit()` 在主线程同步 fsync(设置页点击卡顿/ANR 风险) | `FollowSettings.kt:75,92` | 消除点击卡顿 |
| 10 | P2 | 冷启动关键路径:一次性迁移 runBlocking + BC Provider 注册;1MB 图标 PNG;无闪屏占位 | `PiKAApp.kt:24,41` 等 | 首帧感知提速 |

详细清单见第 2 章,架构级设计见第 3 章,实施路线图见第 4 章。

---

## 1. 现状评估

### 1.1 技术栈

- Kotlin 2.1 + Jetpack Compose(BOM 2024.12)+ Navigation Compose,单 Activity + 20 路由
- 传输栈四套并存:API 走 `HttpsURLConnection` + BCJSSE(绕 Cloudflare 指纹)、Coil 图片走独立 OkHttpClient(BC TLS 包装)、APK 更新走第三个 OkHttpClient、下载/保存走 `HttpURLConnection`
- 图片:Coil 2.7 全局单例,内存缓存 32%
- 持久化:DataStore ×5、SharedPreferences+JSON 当数据库 ×9、JSON 文件当数据库(manifest.json)
- 无 Room、无 Paging、无 DI 框架(手写 object 单例群)、无 Baseline Profile/macrobenchmark

### 1.2 已经做得好的(设计基线,勿回退)

- **阅读器是全项目优化最充分的部分**:滚动流行模型用 IntArray 前缀和 + 二分惰性换算(不物化 rows 列表);页位经 `snapshotFlow` 收集进 `pageMirror`,组合期不读;切片数按章批量落盘;翻页预取(网络 ±2 页 + 渲染 `beyondViewportPageCount=1`)且预取与展示键对齐(注释记录了 OOM 教训)
- **解码上限体系**:按设备内存分级 8192/6144/4096px 封顶,超长图切片 ≤6 屏,同页切片共享同一份位图平移绘制
- **API 容错完善**:429 换域 + 20s 全局冷却、IOException 重试联动换域、成功域名持久化、3s 去抖互斥
- **下载模块**:并发 2 章 + pump 补槽、`.part` 临时文件 + runTag 隔离、manifest 原子替换、重试跳过已完成页、导出 zip 全流式
- **取消语义**:`runCatchingCancellable` 全项目普遍使用,无 GlobalScope,无主线程重 IO(除 3.7 节列出的兜底分支)
- **无 Context 泄漏**:所有单例持 applicationContext
- 启动路径已做过一轮治理(SourcePrefs/ReaderPrefs/GridSettings 后台预热,更新检查异步,登录态不主动恢复)

---

## 2. 性能优化清单

> 影响级别:高(用户可感知 / 崩溃风险)/ 中(可测量的卡顿或资源浪费)/ 低(打磨)。
> 工作量:S(<半天)/ M(1-2 天)/ L(3 天+)。

### 2.1 图片管线(P0 集中区)

**【P0/高】长图多切片在途请求无合并**
- 证据:滚动流每个切片行各自 `rememberAsyncImagePainter`(`ReaderScreen.kt:844-860`),内部对同一 URL 建独立 `ImageRequest`(`WebtoonSplitPage.kt:78-86`)。Coil 2.7 不做在途请求去重:首次进入一个 6 切片长图页,同一张数 MB 原图同时 6 路下载 + 6 路并发解码,瞬时内存峰值可达数百 MB、带宽放大 6 倍,完成前互不共享。
- 方案:见 3.2 节图片子系统设计(推荐"合流拦截器",系统性解决)。
- 工作量:M。

**【P0/中】`#retryN` 重试缓存键漂移**
- 证据:失败重试把 model 改成 `"$imageUrl#retry$retryTick"`(`WebtoonSplitPage.kt:71-80`);重试成功后位图落在 `#retryN` 键下,LazyColumn 回滚重建 item 时 `retryTick` 归 0,内存 miss + 磁盘 miss + 整图重新下载;与预取键(裸 URL)也错位。翻页模式同病(`ReaderScreen.kt:1272-1276`)。
- 方案:重试请求显式固定 `.memoryCacheKey(imageUrl).diskCacheKey(imageUrl)`。S。

**【P1/中】Coil 客户端无超时/无重试**
- 证据:`imageLoaderClient` 是裸 `OkHttpClient.Builder()`(`BcTls.kt:105-107`),默认 connect/read 各 10s,original 大图弱网慢流易断;API 层的自动换域重试与图片路径不对等。
- 方案:`callTimeout(90s)/readTimeout(30s)` + OkHttp 幂等 GET 重试拦截器(1 次)。S。

**【P1/中-高】列表封面拉原图**
- 证据:`image-quality` 头硬编码 `"original"`(`PicaHttpEngine.kt:99`),服务端按该头返回直链,网格页所有封面均为原图。
- 方案:列表类接口(分类/搜索/收藏/关注)传 `thumbnail` 或 `medium`,章节图片列表传 `original`。注意:URL 会随画质变化,缓存键随之分离,属预期行为。S。

**【P2/低-中】磁盘缓存未显式配置**
- 证据:`ImageLoader.Builder` 无 `.diskCache(...)`(`PiKAApp.kt:47-56`),落 Coil 默认配额,与 `CacheCleaner` 的清理口径不统一。
- 方案:显式 `.diskCache { maxSizeBytes(512MB).directory(cacheDir/image_cache) }`,CacheCleaner 同步改读同一实例的 size。S。

**【P2/低】crossfade 全局生效于预取请求**
- 证据:`.crossfade(true)`(`PiKAApp.kt:53`),网格快滚时每个新封面叠加过渡与解码。
- 方案:预取请求 `.crossfade(false)` 即可(展示路径保留淡入)。S。

### 2.2 网络传输层

**【P1/中-高】API 每请求 `disconnect()`,放弃 keep-alive**
- 证据:`PicaHttpEngine.kt:180`、`PicaHttpApi.kt:55-57`(finally 中无条件 disconnect)、`DownloadManager.kt:519`、`ImageSaver.kt:88-96`。每个 API 请求、每张下载图片都要完整 TCP+TLS 握手,而握手用纯 Java BCJSSE(比 Conscrypt 慢数倍);章节 fan-out(Semaphore(3) × 多页,`PicacgSource.kt:189-226`)下放大明显。
- 前置确认:需确认 disconnect() 是否为规避 CF 指纹或连接泄漏的有意行为(用 `tools/pica-api-probe.py` 做 A/B 验证)。
- 方案:成功路径不调用 disconnect(仅错误/重定向路径调用),恢复平台 KeepAliveCache 复用;结构级方案见 3.3 节(统一到 OkHttp + BC TLS)。S(战术)/ M(结构)。

**【P2/中】镜像仅双硬编码,无用户干预入口**
- 证据:`PicaApi.kt:104-108` 只定义 2 个 host,设置页无 host 相关 UI,无双败兜底。
- 方案:设置页加"线路选择(自动/线路1/线路2)",双败时给出明确错误文案;可选 DNS 预解析。M。

**【P2/低】签名与编码重复计算**
- 证据:每请求 `Mac.getInstance("HmacSHA256")` 新建 + API_KEY 重复解码 2 次(`PicaConfig.kt:78-83,41-42,69`)、查询串编码两遍(`PicaHttpEngine.kt:55-57,73-77`)。均在 IO 线程,代价微小。S(顺手改)。

### 2.3 启动与构建

**【P0/高】无 Baseline Profile**
- 证据:全项目无 profile 文件、无 benchmark 模块(`settings.gradle.kts:26` 仅 `:app`)。Compose 冷启动全走 JIT,首帧/首屏全部慢。
- 方案:见 3.5 节启动链路设计。M。

**【P1/中】冷启动关键路径同步工作**
- 证据:`JmRemovalMigration.run()` 在 onCreate 主线程 runBlocking 读两个 DataStore + 扫全部 shared_prefs XML(`PiKAApp.kt:24` → `JmRemovalMigration.kt:31-48`;仅升级后首次,但首启体感明显);`BcTls.install()` 每次冷启动在主线程注册 Provider + 初始化 BCJSSE SSLContext(`PiKAApp.kt:41`,BC 类加载较重)。
- 方案:迁移移到后台协程 + UI 显示轻量加载位(或用 `appScope.launch` + 迁移完成前延迟首个网络调用);BcTls.install 改 `ensureInstalled()` 惰性触发(首次网络/图片调用前),install 本身已有 installed 标志。S。

**【P1/中】无闪屏占位**
- 证据:主题直接 `Material.Light.NoActionBar`(`themes.xml:3`),冷启动到 Compose 首帧前纯白窗口。
- 方案:`androidx.core.splashscreen` 兼容库(覆盖 API 26-35),主题换 `Theme.SplashScreen` + `postSplashScreenTheme`,图标直接复用现有 launcher icon。S。

**【P2/中】APK 体积三项**
- 证据:① `pika_icon_foreground.png` 1.05MB(`drawable-nodpi/`,shrinkResources 裁不掉,占 APK 16%);② BouncyCastle 三件套 keep-all(`proguard-rules.pro:5-6`),R8 无法裁剪,约数 MB class;③ 无 resConfigs。
- 方案:① 转 WebP 并降到 192dp 内约可省 0.8-0.9MB;② BC 改精细 keep(仅 Provider/SSLContext/反射入口),预期再省 1-2MB,需回归 TLS 全链路;③ `resConfigs("zh","en")`。各 S,② M。

**【P2/中】构建脚本 --no-daemon**
- 证据:`gbuild.sh:19,54` 每次清 daemon + `--no-daemon`,5m48s 全冷构建;`gradle.properties:1-5` 未显式开 caching/configuration-cache。
- 方案:若沙箱约束已解除,去掉 --no-daemon;显式开 `org.gradle.caching=true`、`org.gradle.configuration-cache=true`。S。

### 2.4 Compose UI

**【P1/中】142 处 `collectAsState` 全部无生命周期感知**
- 证据:全项目 0 处 `collectAsStateWithLifecycle`;重灾区 `HomeScreen.kt:56-83`(单屏 21 个流)、`ComicDetailScreen.kt:85-112`(23 个)、`ReaderScreen.kt:141-179`。
- 方案:加 `lifecycle-runtime-compose` 依赖,机械替换。S。

**【P1/中-高】章节抽屉组合期磁盘 IO**
- 证据:`ReaderScreen.kt:1055` 在抽屉 item 内调 `DownloadManager.isDownloaded(...)`,内部 `listFiles()` 目录遍历(`DownloadManager.kt:157-167`)。详情页同类问题已修(`ComicDetailViewModel.kt:271-294` 有现成范式),阅读器漏改。
- 方案:仿 `observeDownloaded` 下沉 VM(IO 线程预计算 + StateFlow)。S。

**【P1/中】重组热点两处**
- ① `userScrollEnabled = zoomScale <= 1f`(`ReaderScreen.kt:806,873`):捏合期间每帧重组整个阅读分支 → 包 `remember { derivedStateOf { zoomScale <= 1f } }`(布尔翻转才重组)。
- ② 亮度蒙层 `if (brightness < 1f)` 在内容 Box 作用域读状态(`ReaderScreen.kt:908-914`,滑条 onValueChange 在 `:698-701`):拖动每帧重组含 LazyColumn/Pager 的整个内容作用域 → 蒙层拆独立子 Composable,只读该状态。
- 工作量:S。

**【P1/中】DownloadScreen 组合期分组排序无 remember**
- 证据:`tasks.groupBy{}.entries.sortedByDescending{}` 写在 Composable 体内(`DownloadScreen.kt:300-303`),而 `_tasks` 每下载一页就发新 List(`DownloadManager.kt:364-369`)——批量下载整本时打开下载页,每页进度触发一次全量分组重排。
- 方案:`remember(tasks) { ... }` 或下沉 VM;配合 2.5 节 manifest 写防抖后触发频率同步下降。S。

**【P2/中】全项目列表 0 处 `contentType`**
- 证据:混合条目列表如 `ComicGridView.kt:75,93,100`(卡/tail_loading/end_hint 三类)、`ReaderScreen.kt:820-829`、`ComicDetailScreen.kt:186-454`。
- 方案:优先给阅读器行/网格卡/详情页混排处补齐。S。

**【P2/中低】其余打磨项**
- Shimmer:`composed{}` 内每帧新建 Brush + 6 个独立 `rememberInfiniteTransition`(`Shimmer.kt:33-54,71-99`)→ 单 transition + `drawBehind` 复用 brush。S。
- `HomeViewModel.kt:214-220` 订阅 `UpdatedAtCache.version` 每次 +1 就 map 整条 feed → 加差量判断(仅当 version 变化且 map 结果 key 不同才重建)。S。
- `RecentReadsScreen.kt:234-245` 每行新建 `SimpleDateFormat` → remember(对照 `LogScreen.kt:104` 正确写法)。S。
- `animateItem` 在整列表替换场景全量参与动画(`ComicGridView.kt:81` 等 4 处)→ 数据整体替换时可接受,暂不动,观察。

### 2.5 持久化与内存

**【P1/中-高】`FollowSettings.commit()` 主线程同步落盘**
- 证据:`FollowSettings.kt:75,92`,调用点在 UI 点击(`FollowManageScreen.kt:120,135`)——主线程 fsync。
- 方案:改 `apply()`(列表小,序列化在主线程的代价可忽略);顺手把"get→filter→save 读改写"包进单线程调度器消除并发窗口。S。

**【P1/中】阅读进度写放大**
- 证据:每翻一页触发 DataStore 整文件重写(`ReaderPrefs.kt:141-146`,调用点 `ReaderViewModel.kt:241-259` 已有 1s 防抖);`recordRecentRead` 每次 edit 内解码+重编码整份 100 条 JSON(`ReaderPrefs.kt:345-366`);progress_/finished_/chapters_read_ 键全部塞同一文件,键数随漫画数累积。
- 方案:进度与最近阅读拆到"每部漫画一个小 JSON 文件"(`files/progress/{comicId}.json`,tmp+rename 原子写,仅当前在看的漫画被写),DataStore 只留全局阅读器设置;或最低成本版——`recordRecentRead` 改 5s 防抖 + 仅在退出阅读器时强制落盘。M(拆文件)/ S(最低成本)。

**【P1/中】筛选模式全量拉页进内存**
- 证据:筛选激活时自动链式补页直到 endReached(`CategoryScreen.kt:269-273`、`SearchScreen.kt:83-87`),服务端最多 50 页×20 条,千条级 ComicSummary 全驻内存并参与每次筛选切换的 O(n log n) 重排(`BrowseViewModel.kt:130-134,159`)。
- 方案:短期——筛选模式下限拉前 5 页(100 条)+ "继续加载更多结果"按钮;长期——见 3.4 节分页架构。S(短期)。

**【P1/中】关注流内存列表只增不减**
- 证据:`HomeViewModel.kt:316`(注释自述"内存 feed 不截断")、`:577-583`。深度滚动无上限增长。
- 方案:append 保持不截断(滚动锚定安全),但设上限(如 600 条),超限时在头部插入"已折叠更早内容"占位条并裁剪,配合 scrollTop 恢复语义验证。S-M。

**【P2/中】manifest.json 全量重写放大**
- 证据:DownloadManager persist 为整清单重写,入队/批量/重试/删除/调度/页数回填/章节完成/失败共 8 个触发点(`DownloadManager.kt:209,261,283,296,332,369,397,409`),整本 N 章约 3N 次全文件重写;且 persist 在 `mutex.withLock` 内做磁盘 IO(:332)。
- 方案:仿 `UpdatedAtCache` 的 pendingPuts 合并模式——锁内只改内存 + 标脏,独立协程 300-500ms 防抖落盘。S(仓库内已有现成范式可抄)。

**【P2/中】HomeViewModel 主线程 JSON 读写**
- 证据:init 主线程 `FollowFeedCache.load()` 反序列化 120 条(`HomeViewModel.kt:210`)、`rebuildTargets()` 主线程读 2 个 SP+JSON(`:267-275`)、Main 协程内 `FollowFeedCache.save` 编码+SP 写(`:317-319`)。
- 方案:统一挪 IO + UI 侧骨架屏兜底(现有骨架屏组件直接复用)。S。

**【P2/低】其他**
- 主线程 runBlocking 兜底分支(`SourcePrefs.kt:64,94,101,107`、`ReaderPrefs.kt:196,217,318`、`SearchHistory.kt:34`):预热失败时退化为每次主线程阻塞读盘 → 兜底改 `error/log` + 返回默认值,预热失败的路径走异步重试。
- 10+ 个进程级 CoroutineScope 互不复用 → 收敛为单一 `AppScope`(io/default 两个),生命周期语义靠注释约定。S(纯重构)。
- 下载单页失败连坐整章(`DownloadManager.kt:378-411`)→ 页内自动重试 3 次(退避)再判章失败。S。
- 速度采样器 `while(true)` 永续空转(`DownloadManager.kt:621-659`)→ 无任务时挂起/降频。S。

---

## 3. 技术架构优化设计

### 3.1 目标架构总览

保持"单模块 + 手写 ServiceLocator"的轻量形态(1.8 万行规模引入 Hilt 收益为负),不做模块化拆分;改造集中在**四个子系统的内部结构**:

```
现状:
  UI ──→ ViewModel ──→ Source(网络+映射一体) ──→ PicaClient ──→ HttpURLConnection+BCJSSE
       └─(绕过VM)─→ 9 个 SP/JSON 单例直接读写
  Coil(独立OkHttp) / 下载(HttpURLConnection) / 更新(独立OkHttp) —— 三套传输互不相通

目标:
  UI ──→ ViewModel ──→ PikaRepository(薄门面,仅收敛"UI直连持久化"的路径)
                        │
                        ├─→ Source(纯映射) ──→ PicaClient ──→ PicaHttpEngine
                        │        (增加: 列表缓存TTL / 画质分级参数)
                        └─→ 本地库(progress/{id}.json / manifest.json 统一原子写范式)
  图片子系统: Coil 单例 + 合流拦截器 + 显式磁盘缓存 + 超时重试(OkHttp+BC TLS)
  启动链路: SplashScreen → 后台迁移 → 惰性 BC install → Baseline Profile AOT
```

### 3.2 图片子系统(收益最大)

**核心设计:Coil 合流拦截器(in-flight request coalescing)**

问题本质:Coil 2.7 每个 `ImageRequest` 独立执行,同 key 在途请求不去重。阅读器 6 切片长图页、网格快滚回滚、多卡片同封面,都是同一窗口内的重复下载/解码。

方案:在 `ImageLoader.components` 注册一个合流拦截器,插在 engine 之前:

```kotlin
class CoalescingInterceptor : Interceptor {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageResult>>()
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val key = chain.request.memoryCacheKey ?: chain.request.data.toString()
        val leader = inFlight.putIfAbsent(key, CompletableDeferred())
        if (leader == null) {
            try { val r = chain.proceed(); inFlight[key]?.complete(r); return r }
            finally { inFlight.remove(key) }
        }
        return leader!!.await()   // 追随者直接复用首领的结果(含位图引用)
    }
}
```

- 命中追随路径的请求拿到的 `SuccessResult` 共享同一份 bitmap(硬件位图共享引用无拷贝),6 切片渲染路径本来就是"同一份位图按 offset 平移绘制"(`WebtoonSplitPage.kt:129-148`),完全兼容。
- 同时系统性修复网格封面重复请求、收藏流与关注流同封面双路径等所有同类场景。
- 注意:deferred 需要 `runCatching` 传递失败,失败时不缓存,让各请求走各自的错误态与手动重试。

**辅助设计**:
1. 重试请求固定缓存键(见 2.1 P0-2)——一行改动,与合流拦截器互补。
2. 显式磁盘缓存 512MB + `CacheCleaner` 读同一实例,形成"容量上限 - 主动清理"闭环。
3. OkHttp 层 `callTimeout(90s)/readTimeout(30s)` + 幂等 GET 一次重试拦截器,弱网长图不断流。
4. 列表封面 thumbnail 分级(见 2.1),服务端缩略图天然降低解码与内存压力。
5. 评估项(不进当前路线):Coil 3 升级需同步处理 memoryCacheKey 语义与 painter API 变化,收益主要是磁盘缓存句柄改进,暂缓。

### 3.3 网络传输子系统

约束前提:HttpsURLConnection + BCJSSE 是为绕 Cloudflare 对 BoringSSL/OkHttp 指纹的拦截而选的(`PicaClient.kt:14-20` 注释),**不能直接整体换回 OkHttp**。

两级演进:
- **战术(立即)**:成功路径去掉 `disconnect()`,恢复平台 KeepAliveCache 连接复用;`PicaHttpEngine` 持有可复用的 `Mac` 实例与已解码 API_KEY;用 `tools/pica-api-probe.py` 验证复用连接下的指纹兼容性。
- **结构(验证后)**:BC TLS 已经包装过 OkHttp 给 Coil 用(`BcTls.kt:105-107`),说明"OkHttp + 自定义 SSLSocketFactory"这条路的 ClientHello 可过 CF(图片域已被验证)。若探针确认 API 域同样放行(ALPN 扩展会改变 ClientHello,需实测),则把 API 迁到共享 OkHttpClient:连接池复用、HTTP/2 多路复用(章节 fan-out 延迟进一步下降)、拦截器统一签名/重试/换域逻辑,四套传输收敛为两套(Coil 与 API 共享 pool,更新下载并入)。这是本设计里唯一带外部不确定性(服务器侧指纹策略)的改造,必须以探针 A/B 结果为准,保留 HttpsURLConnection 路径作为可切换后备。

### 3.4 数据层

- **Repository 门面(薄)**:不改 8 个 ViewModel 的调用习惯,只把"UI 直连持久化单例"的 5 处(`AuthorScreen.kt:90-93,107-112`、`FollowManageScreen.kt:66-69,110-135`、`CategoryScreen.kt:102,178`、`RecentReadsScreen.kt:65,125-163`、`ReaderScreen.kt:683,700,921,998`)收敛为单一入口;顺带解决 `FavouriteSync.dirty` volatile 当事件总线(`FavouriteSync.kt:4-6`)、`ReaderStatus.version` 手动 bump 易漏(`ReaderStatus.kt:22-25`)这类隐性契约。
- **进度存储**:按 2.5 P1-2 拆"每漫画一个 JSON 文件"。理由:DataStore 单文件键值会随漫画数无限膨胀、每页重写;每漫画文件天然有界、只写活跃的一两个、tmp+rename 原子性与 manifest.json 一致。
- **分页**:不引入 Paging3(需 Room/RemoteMediator,复杂度不成比例),采用"筛选模式限量 + 手动加载更多"的轻量方案(见 2.5);关注流已是真分页,保持。
- **写放大统一范式**:manifest.json 与 UpdatedAtCache 已有两种写法,统一为"锁内改内存 + 标脏 + 防抖落盘"(pendingPuts 模式),新增的进度文件同范式。

### 3.5 启动链路

```
目标冷启动关键路径(onCreate 主线程只做):
  super.onCreate()
  → Splash 主题(系统级,零代码首帧)
  → 注册延迟初始化清单(lazy handles,零 IO)
  → appScope.launch { JmRemovalMigration(带首帧后让出); 预热×12; BcTls.install 延后到首网络前 }
  → setContent()

 Baseline Profile 方案:
  1. 新建 :baselineprofile 模块(macrobenchmark + baselineprofile 插件)
  2. 用例:冷启动到首帧 / 进首页网格 / 进阅读器首图 / 打开抽屉
  3. AGP 接入 includeInStartupProfile,release 构建自动打包 profile
  4. 验收:macrobenchmark StartupBenchmarks 对比 P50/P95 frameDuration 与 timeToInitialDisplay
```

配套:闪屏主题(2.3)、1MB PNG 转 WebP、BC 精细 keep、gbuild.sh 去 --no-daemon + configuration cache。

### 3.6 UI 状态架构(轻改造)

- `collectAsStateWithLifecycle` 全量替换(142 处,机械)。
- DataStore 冷流在 Composable 顶层 collect 的(`MainScreen.kt:104-105`、`ReaderScreen.kt:162,172,179`、`ComicDetailScreen.kt:111-112`)改在 VM 侧 `stateIn(WhileSubscribed(5s))`,消除重组销毁重建收集。
- 重组热点治理(2.4 P1-3):derivedStateOf 布尔化 zoomScale、亮度蒙层子组件化。
- `contentType` 补齐混排列表。
- `MyCommentsScreen.kt:47`、`AuthorScreen.kt:72` 的无守卫 `LaunchedEffect(Unit)` 返回即重拉 → 加 hasLoaded 守卫(项目内已有守卫范式)。

### 3.7 明确不做的事(范围控制)

- **不引入 Hilt/DI 框架**:82 文件规模,手写 ServiceLocator + 收敛 AppScope 已足够,迁移成本 > 收益。
- **不引入 Room**:现存数据形态(少量列表 + 进度)用文件级存储即可,Room 迁移与数据迁移风险不成比例。
- **不引入 Paging3**:见 3.4。
- **不做模块化拆分**:单模块编译时间可接受,拆分收益为负。
- **不动已验证的阅读器核心方案**(前缀和行模型、pageMirror、解码上限、预取键对齐)——只在其上叠加合流拦截器。

---

## 4. 实施路线图

**M1 — 高收益快赢(约 2-3 个工作日,全部 S/M)**
1. 合流拦截器 + 重试固定缓存键(P0-1/P0-2)
2. keep-alive 恢复 + 签名去重(P1-4/P2)
3. 章节抽屉 isDownloaded 下沉 VM(P1-5)
4. 列表封面画质分级(P1-6)
5. FollowSettings apply + DownloadScreen remember + zoomScale/亮度重组热点(P1-9/P1-7/P1-8)
6. 验证:`pica-api-probe.py` 全接口回归 + 阅读器长图章节内存对比(Studio Profiler memory capture)

**M2 — 启动与体验(约 3-4 个工作日)**
1. Baseline Profile + :baselineprofile 模块 + 闪屏主题
2. 迁移/BC install 移出关键路径
3. collectAsStateWithLifecycle 142 处 + DataStore 流 stateIn 化
4. 进度存储拆分(每漫画一文件)+ manifest 防抖落盘 + 关注流窗口化
5. 验收指标:冷启动 TTI(P95)较基线 -25%+;阅读 100 页的磁盘写量 -80%+;首页后台驻留 CPU 时间 -50%+

**M3 — 结构与打磨(约 3 个工作日,可拆散随版本走)**
1. Repository 门面收敛 5 处 UI 直连
2. BC 精细 keep + PNG 转 WebP + resConfigs(APK 预期 6.26MB → ~4.5MB)
3. 网络结构级迁移(OkHttp+BC 统一,以探针结论为准,保留后备)
4. contentType 补齐、Shimmer、镜像设置项、下载页级重试、AppScope 收敛
5. 验收:APK size、全量手测清单、TLS 回归

**度量基建(随 M2 建立)**:`:baselineprofile` 模块同时承担 Startup/滚动/阅读器三组 benchmark;流量用 OkHttp/HttpURLConnection 字节计数日志对比"改造前后各 100 页阅读"。

---

## 附:各专项分析中确认无恙、无需处理的方面

线程纪律(无 GlobalScope/无主线程重 IO/取消语义)、Context 持有(全 applicationContext)、详情页下载态监听、导出器流式实现、API 容错与换域、KeyStore 账号存储、列表 key 设置(全列表已有 key)、图片卡片固定尺寸、Nav 转场配置、阅读器页位镜像、切片批量落盘、预取键对齐。
