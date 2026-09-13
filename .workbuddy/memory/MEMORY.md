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
- **1.5.48 (versionCode 74)** 已发版（2026-09-13），commit `7e6e386`，远端 main 已同步；`sha256 = 5ad0b070ea87349bcd4aff2cbed6ea2ba84f36864e82cdb74fe364ae0b74ec55`（强校验已启用）。发版包含两轮评审的 45 项缺陷修复。
- 上一版 1.5.47 (versionCode 73)，HEAD 基线为 `22f6746`。
- 开发中（未发版，2026-09-13）：作品网格每行数量可切换（2/3，**默认 2**）。`data/GridSettings.kt`（DataStore `pika_ui`，key `grid_columns`，StateFlow 即时生效）；`ComicGridView` 全部 8 处调用点自动生效；设置入口为「浏览」分组。

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
- 发版步骤（已固化为人工 4 步，`release.sh` 在 HEAD 但工作树缺失、且其 commit 消息是 v1.5.47 写死的，别直接跑）：①改 `app/build.gradle.kts` 版本号 → ②`bash gbuild.sh assembleRelease` → ③`cp` 产物到根目录 `pika-v{VER}.apk` 并用 **python** 写全 `update.json` 四字段（`sha256`/`version`/`apkUrl`/`notes`，注意 `python3` 在本机是坏存根）→ ④提交 `A pika-vX.apk` + `M update.json` 并 push。
- **更新通道端到端自检**（强烈建议每次发版做）：在设备上用 `curl` 下远端 `apkUrl`，比对设备侧 `sha256sum` 与远端 `update.json` 的 `sha256` 是否一致。命令：`adb shell "curl -sL -o /data/local/tmp/x.apk '<apkUrl>'"` 然后 `adb shell sha256sum ...`。注意 host 侧 curl 可能被限速到不可用，不代表发版有问题。
- push 后核对：`git ls-remote origin main` 指向新 commit；远端 `update.json` 用 `curl -H "Accept: application/vnd.github.raw" https://api.github.com/repos/wacilimonster-source/pika/contents/update.json` 读取（`raw.githubusercontent.com` 直连可能被重置）。

## 真机/模拟器验证（2026-09-13 建立）
- 环境：**MuMu 模拟器**（`emulator-5554`，Android 12 / SDK 32 / x86_64 / 1440×2560@640dpi）；adb 用 `/g/Android/platform-tools/adb.exe`；APK 含 x86_64 native 库，可直接装。
- **MuMu adb 坑**：其自带 adb server 抢 5037，冷启动后**第一条命令静默失败**（`device offline`），会让 `input tap/text` 看起来像"点了没反应"。复用 `C:/Users/wacil/AppData/Local/Temp/adbwrap.sh`：`ensure_online || exit 1` 开场 + `retry` 自动重试；兜底端口 `127.0.0.1:16384`。
- **本工具环境跑 adb 必须 cmd 重定向**：PowerShell 里直接 `adb devices` 会因 adb server 持有 stdout 而挂起被杀（`ChildProcess.kill`）。统一 `cmd /c "adb.exe ... > out.txt 2>&1"` 再读文件；截图 `exec-out screencap -p > a.png` 同样走 cmd。uiautomator dump 解析用 regex 提 `text`/`bounds`，`[xml]` 强转会因控制字符失败。
- **debug 包可覆盖模拟器里的 release 包**：release 默认用本机 debug keystore 签名（`PIKA_KEYSTORE` 环境变量未设时），与 `assembleDebug` 同签名，`adb install -r` 直接成功、数据保留。
- 复核产物：`aapt2 dump badging`（版本）+ `java -jar build-tools/35.0.0/lib/apksigner.jar verify --print-certs`（签名 SHA-1 应为 `e7284370...`）。
- 交互式 UI 测试较实用：`adb shell input tap X Y` / `input text` + `exec-out screencap -p`，坐标按 1440×2560 实际分辨率算（预览图会被缩放）。
- **纯复选框交互务必让整行可点**（2026-09-13 真机发现 F16 的说明文字不可点，小屏上像"开关坏了"）。

