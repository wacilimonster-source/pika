# PiKA 项目长期记忆

## 构建与工程化约定
- 构建用仓库内 `gbuild.sh`（非沙箱执行）；验证编译用 `bash gbuild.sh compileReleaseKotlin`。**打 APK 用 `bash gbuild.sh assembleRelease`（不带 -x）**：`checkReleaseBuilds=false` 下 `lintVitalAnalyzeRelease` 任务不注册，`-x` 会报 "Task not found"（Gradle 8.11 行为，compileKotlin 同理）。
- **⚠️ Bash 工具在本机沙箱不可用**（2026-09-12 实测）：Git Bash 的 shim 在第 3 行执行 `dirname` 失败 → PATH 未建立 → `grep`/`ls`/`head` 全部 command not found；且 `wsl.exe` 在安全策略黑名单中（`gbuild.sh` 会因此被拦）。**替代方案：用 PowerShell 调 `.\gradlew.bat`**：
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  Set-Location "G:\game\nw\pka\pika"
  # 先 Remove-Item -Recurse -Force ~/.gradle/native 与 ~/.gradle/daemon
  & ".\gradlew.bat" --no-daemon --console=plain assembleRelease *> $log
  ```
  需 `dangerouslyDisableSandbox`。**PowerShell 工具在本环境不回显 stdout**，必须把结果写文件再用 Read 读；直接把 `-Dorg.gradle.appname=gradle` 写在命令行会被 PS 吞掉参数（报 `找不到主类 .gradle.appname=gradle`），所以用 gradlew.bat。
- `gradle.properties` 不放本机环境配置（JDK 路径/代理在 `~/.gradle/gradle.properties`）；keystore 用 `PIKA_KEYSTORE` 等环境变量覆盖，缺省 `user.home/.android/debug.keystore`。
- 明文流量按域放行：`res/xml/network_security_config.xml`，禁漫 CDN 三个主域（jmapiproxy1.cc / jmapiproxy2.cc / jmapinodeudzn.net）；`JmCrypto.IMAGE_HOSTS` 新增主域时要同步更新。
- git 坑：commit 长消息时 stdout 可能 SIGTERM 截断但 commit 已写入（先看 status 再重试）；push 无输出≠成功，用 `git ls-remote origin main` 复核。

## 当前版本
- **1.5.46 (versionCode 72)** 为已发版；工作树 `app/build.gradle.kts` 为 **1.5.47 (versionCode 73)**，HEAD commit `22f6746`（含发版脚本修复）。2026-09-12 完成两轮全量评审 + 45 项缺陷修复（**未升版本号、未打发布包**）。

## 评审与修复产物（2026-09-12）
- `reports/bika-source-review-20260912.html`（哔咔源链路 17 条）
- `reports/app-review-20260912.html`（除禁漫源外全部功能 33 条）
- `reports/fix-verification-20260912.html`（修复与复核，45/50 项落地）

## 密钥与签名（2026-09-12 改）
- `PicaConfig` 的 `API_KEY`/`SECRET_KEY` 已从 `const val` 分段拼接改为**运行时按位还原**（`intArrayOf` + `xor KEY_MASK`，`val get()`）。原因：`const val` 是编译期常量，拼接会在编译期求值，产物里留下**完整明文**（已用二进制扫描证实：旧 `pika-v1.5.47.apk` 里完整密钥与两段片段全部可搜到，修复后全部消失）。改密钥时务必保持「长度 29 / 63」并用脚本断言回读验证，**不要手算异或表**（手写易错一位就全废）。
- 自检手段：对 release APK 全量搜 `C69BAF41DA5AB`（API_KEY 片段）应无命中。

## DataStore / 协程范式（2026-09-05 定，2026-09-12 扩充）
- **禁止把 DataStore 写操作包成主线程 runBlocking**：写路径用"内存缓存即时生效 + AppScope/IO 协程投递落盘"（高频场景加防抖合并，如亮度滑条 300ms）。
- 读路径：`init()` 后台预热内存缓存，getter 缓存优先；预热未完成的兜底 runBlocking 可接受（DataStore 已驻留内存）。协程上下文用 suspend 版本（`lastProgressAsync` / `recentReadsAsync` / `getOrCreateAppUuidAsync`）。**`ReaderPrefs` 已于 2026-09-12 补齐 `loadCache()` 预热**。
- **`SourcePrefs.markActiveSource()`（内存优先 + 后台落盘）用于「切源后马上导航」场景**；不要再用旧的挂起版先落盘后改内存（会让登录页读到旧源、把账号提交给旧源）。
- 高频落盘用"取消旧 Job + 重投递"防乱序（ReaderViewModel.progressJob 范式）；UpdatedAtCache 的 persistJob 防抖是教科书范式。
- 结构化并发：async 必须挂 `coroutineScope { }` 的 receiver（SearchViewModel 曾踩坑）；retry 循环必须先放行 CancellationException。
- **`runCatching` 会吞掉协程取消**：协程内一律改用 `com.pika.core.runCatchingCancellable`（2026-09-12 新增 `core/CoroutineExt.kt`）；手写 try/catch 时把 `catch (e: CancellationException) { throw e }` 放在 `catch (e: Exception)` 之前。`finally` 里复位标志位要加 `if (isActive)` 守卫。
- **`snapshotFlow { }` 的 State 读取必须写进 lambda 内部**才有订阅效果（写在外面读的是普通 val，flow 只发射一次）。
- 批量分页拉取必须带「冷却熔断 + 请求节拍」：每次取数前查 `PicaClient.rateLimitRemaining() > 0` 就中断，并 `delay(250)`。**已调好的并发度（章节 3 / 搜索 4）不要凭推算下调**，那是作者实测值。
- 单 Activity + Navigation 下，**点返回只触发 composable dispose，不触发 Activity ON_STOP**：需要「退出即保存」的逻辑必须放 `DisposableEffect { onDispose { } }`（配合 `rememberUpdatedState` 读最新值）。
- 列表 `key` 必须真正唯一：`LogEntry` 用自增 id（毫秒时间戳会同毫秒撞车导致 LazyColumn 崩溃）；关注项 key 用 `\u0000` 分隔（`+` 拼接有歧义）。
- `PicaClient.switchHost` 有 Mutex + 3 秒去抖；限流判定用 `PicaException.isRateLimit`（结构化），不要用 message.contains。
- 纯确认型接口（发评论/改资料/改密码等无返回体写入）走 `PicaClient.safeCallUnit`，**不要**用 `safeCall`（后者要求 data 非空，服务端省略 data 时会把成功报成失败）。

## 发布/更新机制
- 发版时在 `update.json` 补 `sha256` 字段（APK 的 SHA-256，下载时 LogStore 会打印实际值可核对），`downloadAndVerify` 即自动启用强校验；APK 落在 `filesDir`（file_paths.xml 已含 files-path）。
- **取安装包路径必须用 `downloadAndVerify` 的返回值**，不要自己拼 `cacheDir/filesDir` 路径（曾因此导致首页横幅更新「点安装无反应」）。
