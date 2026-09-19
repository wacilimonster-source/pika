# PiKA · 移除禁漫（JM）源 —— 产品设计与实施方案

日期：2026-09-20 ｜ 状态：已实施（提交 c72977e 移除 + 9f96cfc 测试与配置清理）｜ 基线：main @ ece5180 + 未提交工作树（ComicRef 双源标识改造，已单独提交为 73b6ac9）

> 实施后验证记录（代码级）：compileDebugKotlin / assembleRelease（含 R8）/ testDebugUnitTest（12 用例）/ lintDebug（0 错误）全部通过；
> 全仓 JM 残留清零（唯一例外为 JmRemovalMigration 及配套 purge 函数中有意保留的历史源名字面量，见其 KDoc）。
> 实施中发现并补清：res/xml/network_security_config.xml 中禁漫 CDN 域名及明文 HTTP 放行（9f96cfc）。
> 模拟器/真机升级冒烟未执行（按用户要求仅做代码级验证），建议发版前人工走一遍「v1.5.50 覆盖装 → 迁移 → 哔咔进度/收藏/下载完好」。

---

## 1. 背景与目标

PiKA 当前为双源架构：哔咔漫画（PICACG）+ 禁漫天堂（JMCOMIC）。因业务决策需要将禁漫源
整体下线：**功能、代码、网络层、持久化数据、工具脚本一并移除**，App 收敛为哔咔单源。

目标：
1. 用户可见层面：禁漫相关入口、功能、文案全部消失，无死链、无报错、无残留入口。
2. 代码层面：JM 专属代码清零；通用架构（Source 抽象、ComicRef 标识、单活动源管理）保留，
   不因移除而破坏。
3. 数据层面：存量用户升级后无脏数据——无打不开的阅读记录、无错源恢复的下载任务、无残留凭据。
4. 安全层面：禁漫账号凭据（加密存储）与 token 在升级迁移中彻底清除。

非目标：
- 不移除哔咔源任何功能；不做架构重构（Source 抽象、ComicRef 机制原样保留，见 §5 决策 D2）。
- 不做"禁用开关"式保留（开关式下线只增加维护面，本次为彻底移除）。

---

## 2. 移除范围总览（功能面）

| 功能 / 能力 | 现状 | 处置 |
|---|---|---|
| 禁漫源切换（源管理页） | 设置 → 数据源管理，双源切换 | 切换器移除，页面保留哔咔账号区 |
| 禁漫登录 | 邮箱 + 密码（v3 sign-in） | 移除登录分支 |
| 禁漫注册限制提示 | "当前源（禁漫）不支持注册" | 移除该限制分支 |
| 禁漫每日签到 | 我的页 JM 专属入口 | 移除 |
| 云端浏览历史 | 禁漫源独有功能（整页） | **整页下线**（入口：我的 → 云端历史） |
| 禁漫 API 域名编辑 | 源管理页高级/故障排除 | 移除 |
| 按作者浏览限制 | "当前源（禁漫）不支持按作者浏览" | 移除该限制分支（哔咔本来就支持） |
| 禁漫分类/排序客户端重排 | BrowseViewModel 内 JM 分支 | 移除 |
| 禁漫搜索/分类/详情/章节/阅读 | 经 Source 抽象通用链路 | 随源实现删除 |
| 禁漫图片 AES 解密 + 会话静默重登 | JmCrypto / JmClient hook | 随网络层删除 |

---

## 3. 用户可见变化（升级后）

1. **源管理页**：不再出现"禁漫天堂"及切换项；禁漫 API 域名编辑项消失；页面仅保留哔咔账号状态。
2. **我的页**："云端历史"菜单项消失；禁漫签到入口消失。
3. **作者页 / 注册页**：不再出现"禁漫不支持……"的降级提示（单源下这些限制不存在）。
4. **设置页关于文案**："聚合哔咔漫画与禁漫天堂双数据源……" 改为哔咔单源描述。
5. **存量数据（关键体验点）**：
   - 最近阅读、已读/读完角标、阅读进度中属于禁漫的条目：升级后**一次性清除**，不再出现死条目。
   - 禁漫下载任务与其已下载文件：升级后一次性清除（记录 + 磁盘目录）。
   - 禁漫账号凭据与 token：升级后清除（凭据为 Keystore 加密存储，按源键控）。
   - 哔咔数据**零影响**：哔咔的进度、角标、收藏、下载原样保留。
6. **版本号**：建议 **v1.6.0**（功能性移除属破坏性变更，不用 patch 号），Release Note 明列上述移除项与数据清除说明。

---

## 4. 存量数据迁移方案（升级首次启动一次性执行）

新增 `data/JmRemovalMigration.kt`：一次性迁移（DataStore 记录 `jm_removal_done` 标记，执行幂等）。

清除清单（全部带 `JMCOMIC` 源前缀/键的条目）：

| 存储位置 | 键形态 | 动作 |
|---|---|---|
| SourcePrefs（DataStore） | `jm_token` / `jm_base` | 删除键 + 删除内存缓存字段 |
| SecureAccountStore | `account_JMCOMIC` / `email_JMCOMIC` / `pass_JMCOMIC`（Keystore 加密） | 删除三键 |
| ReaderPrefs / ReaderStatus（DataStore） | 键含 `JMCOMIC_` 前缀（`源_id` 作品标识） | 删除含该前缀的全部条目 |
| UpdatedAtCache（DataStore） | 同上 | 同上 |
| WebtoonSliceCache（SharedPreferences `pika_webtoon_slices`） | `JMCOMIC_<id>#<章>` | 删除含前缀的键（该缓存本可重建，不做迁移） |
| 下载任务记录 + 磁盘目录 | `DownloadTask.source == "JMCOMIC"` | 删除任务记录及其下载目录 |
| 收藏同步 / 作者收藏 | 键含 `JMCOMIC_` 前缀 | 删除含前缀条目 |

**执行顺序敏感**：迁移必须在任何 ComicRef 解析逻辑读到旧数据之前完成（Application 启动期、
进入 UI 前）。原因：`ComicRef.parse` 与 `DownloadManager` 的任务恢复对未知源名**回落 PICACG**
——若迁移不先行，残留的 `JMCOMIC_` 键会被当哔咔 id 去请求哔咔接口（错源请求）。
迁移完成后，该回落行为退化为主键即哔咔，无风险。

---

## 5. 产品 / 技术决策点

- **D1 · 未提交工作树的处理**：当前工作树有 16 文件约 291 行的"ComicRef 双源标识改造"
  未提交（JM 源依赖该改造）。**建议：先将在途改造单独提交为基线，再做 JM 移除提交**——
  两个语义独立的提交，出问题可单独回滚、可 bisect。
- **D2 · ComicRef 与 `源_id` 键格式去留**：移除 JM 后只剩一个源，但**建议保留**
  （已上线的进度/角标/更新时间键格式为 `PICACG_<id>`，回退裸 id 需再迁移一次且零收益；
  机制本身是通用基础设施，未来若接新源可直接复用）。仅在 KDoc 中更新说明。
- **D3 · 云端历史页**：JM 独有功能，随源下线，整页删除，不做"哔咔云端历史"替代。
- **D4 · SourceType 枚举**：保留枚举形态（仅剩 `PICACG`），不降级为常量——
  SecureAccountStore / DownloadTask / ComicRef 均按枚举名键控，保持最小改动面。
- **D5 · 源管理页去留**：保留（哔咔账号管理、登录态展示仍挂在此页），仅移除切换器与禁漫区块。

---

## 6. 代码改造拆解（文件级清单）

### 6.1 删除（整文件）

| 文件 | 行数 | 说明 |
|---|---|---|
| `core/source/JmcomicSource.kt` | 241 | 禁漫源实现 |
| `network/JmClient.kt` | 224 | 禁漫 API 客户端（含域名、401 hook） |
| `network/JmModels.kt` | 228 | 禁漫 API 模型 |
| `network/JmCrypto.kt` | 77 | 响应体 AES-256-ECB 解密 |
| `ui/history/CloudHistoryScreen.kt` | — | 云端浏览历史页（JM 独有） |
| `tools/jm-api-check.mjs` / `jm-capability-probe.mjs` / `jm-shape-probe.mjs` / `jm-scramble-rule.py` | — | 逆向探测脚本 |
| `reports/jm-source-design-20260919.html` / `jm-report-extracted.txt` | — | 过期设计/提取报告（移除完成后归档删除） |

保留说明：`network/BcTls.kt` **不删**——哔咔客户端、下载管理器、应用更新管理器共用该 TLS 层。

### 6.2 修改

| 文件 | 改动 |
|---|---|
| `core/source/SourceType.kt` | 枚举移除 `JMCOMIC` |
| `core/source/SourceManager.kt` | 移除 JM 注册项、`JmClient.onUnauthorizedHook` 挂接（静默重登机制本体保留，哔咔仍在用） |
| `core/model/SourceModels.kt` | 清理 JM 注释/分支（3 处） |
| `data/SourcePrefs.kt` | 删除 `JM_TOKEN`/`JM_BASE` 键、`jmToken`/`jmBaseUrl` 及缓存字段 |
| `core/download/DownloadManager.kt` | 任务恢复处源名解析保留（回落 PICACG），配合迁移清除 JM 任务；无结构性改动 |
| `ui/MainScreen.kt` | 移除 `cloud-history` 路由与 `onOpenCloudHistory` 参数 |
| `ui/mine/MineScreen.kt` | 移除 `isJm` 分支（签到、"云端历史"菜单、第 220 行 JM 分支） |
| `ui/settings/SourceManageScreen.kt` | 移除源切换器、`JmDomainEditor`；保留哔咔账号区 |
| `ui/login/LoginScreen.kt` | 移除禁漫邮箱登录分支 |
| `ui/login/RegisterScreen.kt` | 移除"禁漫不支持注册"限制分支 |
| `ui/author/AuthorScreen.kt` | 移除"禁漫不支持按作者浏览"限制分支 |
| `ui/browse/BrowseViewModel.kt` | 移除禁漫客户端重排相关分支/注释 |
| `ui/settings/SettingsScreen.kt` | 关于文案改为哔咔单源描述（455 行） |
| `core/source/ComicRef.kt` | KDoc 更新（单源说明）；逻辑不变 |
| 新增 `data/JmRemovalMigration.kt` | §4 的一次性清除迁移 |

### 6.3 预计规模

删除约 **900+ 行** JM 专属代码与 4 个工具脚本；修改 15 文件，均为局部删改，无接口签名级重构。

---

## 7. 实施阶段

| 阶段 | 内容 | 完成判据 |
|---|---|---|
| P0 基线 | 提交在途 ComicRef 改造（决策 D1） | 工作树干净，编译通过 |
| P1 核心移除 | 删除 6.1 整文件 + 6.2 枚举/SourceManager/SourcePrefs/模型层 | 编译通过 |
| P2 UI 收敛 | 6.2 全部 UI 文件 + 文案 | 编译通过，无 JM 残留引用（`grep -riE "jmcomic|禁漫|JmClient"` 清零） |
| P3 数据迁移 | 新增 JmRemovalMigration，挂接 Application 启动链 | 模拟升级场景（预置 JM 键数据 → 升级 → 数据清除、哔咔数据完好） |
| P4 工具/报告清理 | 删除 tools/ 与 reports/ 中 JM 产物 | 仓库无 jm 残留文件 |
| P5 验证发布 | 全量验证（§8）+ 打包 + Release Note | 冒烟清单全绿 |

---

## 8. 验证方案

**静态**：
- `grep -riE "jmcomic|禁漫|JmClient|JmCrypto|jm_" app/src` 结果为零；
- 编译通过（现有 compile-check 流程）。

**动态冒烟**（模拟器）：
1. 首次安装：哔咔登录 → 搜索/分类/详情/阅读/下载全链路正常；
2. **升级迁移**：用 v1.5.51 预置"哔咔进度 + 禁漫进度/下载/凭据"→ 覆盖装 v1.6.0 →
   禁漫条目/任务/凭据消失，哔咔进度、角标、收藏、下载完好；
3. 源管理页无禁漫区块；我的页无"云端历史"、无签到；作者页/注册页无降级提示；
4. 阅读器长图滚动流（WebtoonSliceCache 通用链路）回归正常；
5. 下载暂停/恢复：恢复走哔咔源解析，无错源请求。

---

## 9. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| 残留 `JMCOMIC_` 键被 ComicRef.parse / DownloadManager 回落解析为哔咔 → 错源请求 | 高 | 迁移先于 UI 启动执行（§4）；冒烟项 2/5 专项覆盖 |
| 迁移遗漏某个持久化位置 → 死条目 | 中 | §4 清单按实际 `grep` 盘点结果核对；迁移实现逐条对照 |
| BcTls 误删影响下载/更新 | 低 | 明确保留（6.1 说明），冒烟覆盖下载与检查更新 |
| 未提交改造与移除混在一起，不可回滚 | 中 | P0 单独提交基线（决策 D1） |
| 用户误以为数据丢失（禁漫条目消失） | 低 | Release Note 明示"禁漫内容及相关本地记录随源下线一并清除" |

---

## 10. 待确认问题

1. 版本号 v1.6.0 是否认可（vs 继续用 v1.5.5x patch 号）？
2. 禁漫下载文件是否随迁移直接删除磁盘文件（本设计为"是"），还是保留文件仅删记录？
3. `reports/jm-source-design-20260919.html` 等历史设计报告随本次删除，还是移入归档目录？
