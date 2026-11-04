# PiKA v1.12.0 作者名 / 作品名复制 · 交付报告

日期：2026-11-04 ｜ 基线：`c003f35`（v1.11.2 / build 101）→ 本次 **v1.12.0 / build 102**
范围：P0（作品详情页 + 作者页），仅展示层新增，业务语义零改动。

---

## 1. 结论

- 详情页顶栏「更多」菜单可复制作品名/作者名；长按标题、长按作者行可直接复制。
- 作者页顶栏新增复制图标；长按标题可直接复制当前浏览的署名。
- 复制内容所见即所得：详情页作者复制完整原署名，作者页复制当前 `currentQuery`；不带《》/「作者：」，不拆分复合署名。
- 现有交互语义未变：作者行点按仍进作者页/作者选择层；标题点按仍无动作；分享、收藏、返回、阅读器均未触碰。
- 构建与单测通过；release 包在独立 AVD（`pika_test`，Android 14）实测通过；MuMu 覆盖安装启动正常、数据保留，零 FATAL/ANR。
- 交付 APK：`G:\game\nw\pka\pika\pika-v1.12.0.apk`
  SHA-256：`edd29c84e2e5fbb33a25694a1a44b8309dca09b2a6a197f21cd0c6b71378caa1`
  applicationId `com.pika`、versionCode 102、versionName 1.12.0、debug keystore 签名（可覆盖升级）。

---

## 2. 代码变更

| 文件 | 变更 |
|---|---|
| `ui/common/Clipboard.kt`（新） | `Context.copyPlainText()`：trim、空值保护、异常捕获；`rememberClipboardCopier()`：统一 Snackbar 反馈，连续复制先收起上一条 |
| `ui/detail/ComicDetailScreen.kt` | 顶栏新增 `⋮ 更多操作` + DropdownMenu（复制作品名/复制作者名，作者为空隐藏）；标题接长按复制；作者行由 `clickable` 改为 `combinedClickable`（点按逻辑原样保留，长按复制）；两处补 TalkBack 自定义操作 |
| `ui/author/AuthorScreen.kt` | 顶栏新增复制图标（收藏左侧，收藏位置不变）；标题接长按复制；新增 SnackbarHost |
| `app/build.gradle.kts` | versionCode 101→102，versionName 1.11.2→1.12.0 |
| `update.json` | 版本、APK 地址、SHA-256、更新说明 |

**未改动**：ViewModel、StateFlow、路由参数、网络层、下载、持久化、分享文案、主题与布局。

---

## 3. 复制语义

| 入口 | 复制值 |
|---|---|
| 详情页 · 更多 → 复制作品名 / 长按标题 | `comic.title.trim()`（完整值，不受界面省略号影响） |
| 详情页 · 更多 → 复制作者名 / 长按作者行 | `comic.author.trim()`（完整原署名，保留括号/角色说明/日文） |
| 作者页 · 复制图标 / 长按标题 | `currentQuery.trim()`（当前浏览的署名片段） |

- 不带 `《》`、不带「作者：」、不带来源尾注。
- 空值不写入剪贴板；失败提示「复制失败，请重试」。
- 成功提示「已复制作品名」/「已复制作者名」；Android 13+ 系统剪贴板预览同时出现（已实测）。

---

## 4. 验证记录

### 4.1 构建与单测

| 检查 | 结果 |
|---|---|
| `./gbuild.sh :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `./gbuild.sh :app:testDebugUnitTest` | BUILD SUCCESSFUL（现有 5 组 JVM 单测全绿） |
| `./gbuild.sh :app:assembleRelease` | BUILD SUCCESSFUL（4m44s） |
| APK 版本 | package `com.pika` / versionCode 102 / versionName 1.12.0 |
| APK 签名 | Android Debug（与历史版本同签名，可覆盖升级） |

### 4.2 独立 AVD 交互验收（Android 14，release 包，导入登录态）

| 用例 | 结果 | 证据 |
|---|---|---|
| 详情页顶栏「更多」菜单显示两个复制项 | 通过 | `copy-test-avd-04-menu.png` |
| 菜单复制作品名 → 搜索框粘贴 | 通过，逐字一致：`絶頂リフレ-駅前の性感マッサージ店で◯◯になっちゃう女の子の話-` | `copy-test-avd-05-title-copied.png`、`copy-test-avd-06-title-paste.png` |
| 菜单复制作者名 → 搜索框粘贴 | 通过，逐字一致：`ぽちたろ` | `copy-test-avd-07-author-copied.png` |
| 长按详情标题 | 通过，Snackbar「已复制作品名」 | MuMu `copy-test-10-longpress-title.png` |
| 长按作者行 | 通过，Snackbar「已复制作者名」，停留详情页未误导航 | `copy-test-avd-08-longpress-author.png` |
| 点按作者行仍进作者页 | 通过（`combinedClickable` 未破坏点击） | `copy-test-avd-09-author-page.png` |
| 作者页复制图标 | 通过，Snackbar「已复制作者名」 | `copy-test-avd-10-authorpage-copy.png` |
| 作者页长按标题 | 通过，Snackbar「已复制作者名」 | `copy-test-avd-11-authorpage-longpress.png` |
| 作者页复制值粘贴校验 | 通过：`ぽちたろ` | UI dump `EditText text="ぽちたろ"` |
| 从标题起手上下滑动 | 通过，页面正常滚动，长按手势未拦截滚动 | `copy-test-avd-18-scroll-from-title.png` |
| 浅色 + 字体 1.3：首页/详情/菜单/作者页 | 通过，顶栏三图标不重叠、菜单项不截断、标题两行省略正常 | `copy-test-avd-12/13/14/15-light-font13-*.png` |
| Android 13+ 系统剪贴板预览 | 出现并显示正确作品名 | `copy-test-avd-05-title-copied.png` |
| FATAL / ANR | 0 | `logcat` 过滤 `FATAL EXCEPTION` / `ANR in com.pika` |

### 4.3 MuMu 覆盖安装

- 从 v1.11.2（build 101）覆盖安装 v1.12.0（build 102）成功，登录态与数据保留，启动正常。
- 证据：`copy-test-mumu-17-release-launch.png`；MuMu 实测菜单、作品名复制、作者名复制、长按标题均正常。
- 说明：验收期间 MuMu 主屏被同机其他自动化反复抢占前台（多次自行启动 `com.xdo.app` / `cn.fjob.todayagain` 并切换横竖屏），因此完整交互矩阵在独立 AVD 上完成；MuMu 侧验证了覆盖安装与核心复制路径。

### 4.4 未覆盖项

- TalkBack 实机手势未逐项执行（已补 `CustomAccessibilityAction`，读屏操作菜单可触发；未做 TalkBack 语音回归）。
- 空作者作品、详情加载中状态未在真实数据中触发；代码侧已做 `isNotBlank` 隐藏与空值不写入保护。
- 复合署名作品的复制未单独找样本实测；实现直接复制 `comic.author` 原串，不经过解析器，逻辑上无拆分风险。

---

## 5. 设计与实现一致性

- 与 `reports/copy-author-title-design-20261104.md` 的 P0 范围一致，无额外功能。
- 未实现 P1（阅读器/复制完整原署名）与 P2（列表卡片长按）。
- 版本按次版本处理：v1.12.0 / build 102。
