# PiKA v1.11.0 新版 UI 交付报告

日期：2026-11-04
基线：`be69b5f`（v1.10.0 / build 98）→ 本次 **v1.11.0 / build 99**
范围：`reports/pika-vnext-handoff/` 交接包的视觉与交互精修，原生 Compose 实现。

## 1. 结论

- 八个主页面与未画页面的主题、字号、卡片、底栏、筛选与状态组件已按 design-tokens 落地，深浅双主题完整、仍跟随系统。
- 业务语义零改动：ViewModel / StateFlow / 路由参数 / 异步任务 / 持久化路径 / 网络与下载逻辑均未触碰；全部修改位于 `ui/` 展示层与版本号。
- 单元测试通过；debug/release 构建通过；release 在 MuMu 模拟器上覆盖升级安装成功、数据保留、启动无崩溃。
- 交付 APK：`G:\game\nw\pka\pika\pika-v1.11.0.apk`
  SHA-256：`6beec338760f7d0c0a3390286dd91ef57190f842897ce72cbfc4deb793a7e495`
  applicationId `com.pika`、versionCode 99、versionName 1.11.0、沿用 debug keystore 签名（可覆盖升级）。

## 2. 代码变更摘要

| 文件 | 变更 | 理由 |
|---|---|---|
| `ui/theme/Color.kt` | 新增 design-tokens 全部深浅色角色 + 同色系派生角色（secondary/tertiary/各 surfaceContainer 层/outline/error/success） | 消除默认 Material 紫残留，组件在任意页面都有正确语义色 |
| `ui/theme/Theme.kt` | 补全 dark/light 完整 ColorScheme；新增 `MaterialTheme.pikaExtras.success`（tokens 的 success 角色）；新增全局 Shapes（6/10/14/18/24） | 主题完整化；按钮/chip 10dp、分组卡 14dp、弹层 24dp 与设计一致 |
| `ui/theme/Type.kt` | 字阶：页面标题 22/28·600、区块标题 18/24·600、详情标题 24/32·600、正文 14/22、次级 12/18、辅助 11/16 | 设计 token 字阶；系统无衬线，不引入字体依赖 |
| `ui/theme/PiKATextTheme.kt` | 改为直通（原嵌套 MaterialTheme 覆盖 titleLarge） | 字阶已在 Type.kt 完整定义；消除嵌套覆盖丢字段风险 |
| `ui/MainScreen.kt` | 底栏四项加图标（选中实心/未选中描边，22dp）、11sp 标签、选中药丸 primaryContainer、`heightIn(min=64dp)` 与系统 inset 协同 | 设计稿底栏形态；inset 不再挤压内容 |
| `ui/browse/ComicGridView.kt` | 封面 10dp 圆角、间距 12/20、页边距 16；标题两列 14/20·600、三列 12/18·600；阅读角标移至右下（不与人物面部/更新点重叠）；新增可选真实榜单位次角标（衬线斜体 + 投影）；`itemsIndexed` 稳定 key | 卡片与设计对齐；名次仅在服务端榜单流开启（真实顺序） |
| `ui/common/Shimmer.kt` | 骨架节奏与卡片一致（16 边距/12-20 间距/10dp 圆角/三条信息行） | 加载完成不突跳 |
| `ui/common/ErrorRetry.kt` | 统一排版，重试按钮 48dp 高、10dp 圆角 | 失败态全 App 一致，触控达标 |
| `ui/common/Brand.kt`（新） | `EighteenPlusBadge` 应用级 18+ 标识 | 18+ 仅作应用定位展示，非逐作品分级 |
| `ui/home/HomeScreen.kt` | 品牌栏（PiKA. 字标+粉点+18+ pill+真实源 chip）；榜单区头（人气榜单 + 日/周/月分段控件）；榜单流启用真实名次 | 设计首页信息层级；全部沿用既有 VM 调用 |
| `ui/category/CategoryScreen.kt` | 大标题页头；分类卡改为图底+底部渐变+标题叠加（104dp/14dp 圆角），无封面/加载失败有阴影兜底 | 设计分类卡；真实分类数据原样展示 |
| `ui/search/SearchScreen.kt` | 大标题（仅 tab 直达）；圆角填充搜索框（14dp）；热搜榜列表（真实顺序 01-05，前三强调）；历史清理按钮图标化 | 设计搜索页；逻辑（分词/排序/筛选/分页/回顶）未变 |
| `ui/mine/MineScreen.kt` | 资料卡（头像/昵称/等级真实数据）+ 三组菜单（我的收藏与关注/阅读与离线/通用）图标行 64dp，全部 8 项入口保留；页面可滚动 | 设计个人中心分组；长内容可达（此前无滚动） |
| `ui/settings/SettingsWidgets.kt` + `SettingsScreen.kt` | 分组卡 14dp/surfaceContainer；数据源、日志、关于跳转行加图标；页边距 20dp | 设计设置页层级 |
| `ui/detail/ComicDetailScreen.kt` | 标题 24sp；主按钮占主宽 48dp 10dp 圆角，下载次按钮；封面/推荐卡圆角与容器色 | 设计详情页层级；全部原操作与状态保留 |
| `ui/download/DownloadScreen.kt` | 总览卡/作品分组卡 14dp、封面 10dp 圆角、容器色分层 | 设计下载页；分组/展开/导出/删除逻辑未变 |
| `ui/reader/ReaderScreen.kt` | 控制面板重排：上一话｜页码｜下一话 → 页码进度条（第X页/共Y页）→ 亮度 → 章节/横滑/滚动流；面板近不透明深紫；抽屉与结束卡同色板 | 设计阅读面板；点击分区、缩放、进度保存、分片、亮度覆盖层等逻辑零改动 |
| `ui/login/*.kt` | 登录/注册/找回主按钮 48dp、10dp 圆角 | 表单按钮统一 |
| `app/build.gradle.kts` | versionCode 99 / versionName 1.11.0 | 版本递增（基线 1.10.0/98 未被占用） |

**业务层改动：无。** 仅展示层与版本号。

## 3. 设计对照（与图稿的必要差异）

| 页面 | 与图稿差异 | 原因 |
|---|---|---|
| 全站 | 无设备外框/9:41/左右说明栏/演示状态切换器 | 仅属设计工作台 |
| 首页 | 封面不叠加书名/英文副标题；18+ 只在品牌栏 | 保持真实封面；无逐作品分级数据不伪造 |
| 首页 | 榜单名次显示于服务端榜单（真实顺序）；关注/随机流不显示 | 仅真实排名可作名次 |
| 分类 | 无 “EXPLORE YOUR TASTE” 营销横幅与英文副标题；无 “所有角色均为成年设定” 文案 | 不新增营销装饰/年龄声明；不伪造分类英文名 |
| 搜索 | “已读/读完”筛选改为源码的 全部/只看未读/只看未读完 | 交接包纠偏：保留真实枚举 |
| 详情 | 保留收藏与作品分享，不新增“喜欢作品”；评论点赞保留 | 纠偏：功能边界 |
| 阅读 | 手势分区/双击缩放/亮度/进度映射全部沿用 | 不改手势时序与算法 |
| 我的 | 无续读卡；全部 8 项原菜单入口按登录态展示 | 纠偏：不新增能力、不删入口 |
| 下载 | 无暂停/恢复；保留作品分组/章节展开/失败重试/删除确认/SAF 导出/分享 | 纠偏：功能边界 |
| 全站 | 无年龄验证/应用锁/截屏屏蔽/隐私承诺；深浅跟随系统，无主题开关 | 纠偏：不新增隐私功能 |

## 4. 验证记录

设备与工具：
- MuMu 模拟器 `127.0.0.1:16416`（Android 12，1080×1920 @480dpi → 360dp 宽；系统深色/浅色切换）
- AOSP 模拟器 `emulator-5554`（Pixel 6，Android 14，411dp 宽；亮色登录页验证）
- 服务端状态：验证期间哔咔服务端出现真实 HTTP 502——恰好用于验证错误态与榜单缓存兜底

构建与测试（真实输出）：
- `./gradlew.bat :app:testDebugUnitTest` → **BUILD SUCCESSFUL**
- `./gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**
- `./gradlew.bat :app:assembleRelease` → **BUILD SUCCESSFUL**（minify+shrinkResources，6.6 MB）
- `./gradlew.bat :app:lintDebug` → BUILD FAILED：**2 个 Error 均为基线既有** `core/log/CrashReporter.kt:219/232`（ApplicationExitInfo#getReason 需 API 30，minSdk 26），该文件本次未改动；本次变更 **0 新增 lint Error**，未关闭任何规则。

安装与升级：
- MuMu：v1.9.13 → v1.11.0 覆盖安装（`install -r`）**Success**；应用数据（登录态、下载任务、阅读进度、网格偏好）全部保留；启动无 FATAL/崩溃。
- emulator-5554：全新安装 + 启动正常（无账号会话时走既有登录回跳）。

已覆盖场景（实机截图证据）：
- 深色：首页（真实榜单+名次+缓存兜底错误横幅）、分类（真实分类内容态；502 时错误态+重试）、搜索（热搜榜/历史）、搜索结果网格+分页、详情（内容态：继续阅读/下载/简介/标签/章节/评论）、阅读器面板（上一话/进度/亮度/章节/模式）、我的、设置、下载（总览/分组/展开章节）、导出 SAF 对话框。
- 浅色：首页、分类、搜索、搜索结果+分页、详情、阅读器面板、我的、设置、下载（九个页面全部）。
- 三列网格（设置切换即时生效，卡片 12sp 标题不撑坏）；字体缩放 1.3（首页/我的无溢出）；360dp 宽（MuMu 全流程）。
- 错误态：分类 502 错误+重试、榜单 502 缓存降级横幅（v1.9.14 能力在新主题下显示正常）。
- 空态：收藏作者空态（亮色）。
- 下载展开：第1話 已完成 63 页 17.1MB；导出拉起系统 SAF 且默认文件名正确（未实际写入文件，已取消）。

未覆盖 / 未验证项（如实记录）：
- 未在 release 上逐一重拍全部浅色页（浅色截图取自同代码的 debug 构建；release 已验证深色首页/分类/搜索/我的/详情/下载/设置/阅读器面板 + 覆盖升级）。
- 服务端 502 期间无法重复执行搜索内容态/详情评论翻页等在线路径（截图来自服务端正常时段）。
- 导出仅验证到 SAF 弹窗（未写入 zip、未验证分享回调）；逻辑本次未改动。
- 未验证 Android 8（minSdk 26）旧设备、平板/横屏（既有行为未改动，未回归）。
- 阅读器双击缩放/音量键/长按保存/分享本次未逐项复测（相关代码未改动）。
- 字体缩放 1.3 仅抽查首页/我的；未做 1.3 下全部八页巡检。

## 5. 截图清单（reports/）

- 基线：`vnext-baseline-home.png` / `-home-dark.png` / `-category.png` / `-search.png` / `-mine.png`
- 深色（release）：`v1.11.0-dark-*`（category/detail/downloads-expanded/mine/reader-panel/search）+ `release-v1.11.0-home.png`、`v1.11.0-export-saf.png`
- 深色（同代码 debug）：`vnext-mumu-home3.png`（名次+真实内容）、`vnext-mumu-downloads.png`、`final-dark-home.png`
- 浅色：`final-light-*`（home/category/search/search-results/detail/reader-panel/mine/settings/downloads）+ `final-light-3cols.png`
- 适配：`final-fontscale13-home.png` / `-mine.png`

## 6. 声明

- 未上传 APK、未修改远程更新清单、未推送/合并/发布。
- 未新增假数据、固定进度、AI 封面、演示状态切换器、设备外框或说明栏。
- 18+ 仅作应用定位展示，不声称逐作品分级或年龄认证。
