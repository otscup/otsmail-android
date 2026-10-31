# 进度与计划（长线任务）

更新时间：2026-10-31 05:35
基线提交：`75f36c0`（乱码注释 + 草稿箱图标），本文件随后与该批一起提交。

## 当前状态

- 14 屏全部实现，`assembleDebug` 通过，版本 0.2.0；
- 本地静态自查全绿：`U+FFFD 0`、主题外写死颜色 `0`、文字字形冒充图标 `0`（`tools/local-audit.cjs`）；
- **收件箱**已逐项对照 `design/v5/01-inbox.png`；其余 13 屏的「卡间距 / 组标题 / 行内图标块 / 右侧列」已统一到 `OTSpace` + `OTGroup` + `OTRow`；
- 真机只验过 5 屏：收件箱、抽屉、设置、系统设置（根 + 一张卡）、详情页。**用户已拔线，其余留待一次性回归。**

## 步骤（每步一次提交）

| # | 步骤 | 验收方式（全部本地） | 状态 |
|---|---|---|---|
| 1 | 库补齐：`OTIconTile`（30dp 同色 14% 圆角块）+ `OTRow(title, subtitle, icon, trailing)` | 编译 + 设置页替换 ≥5 行 | ✅ `0bab6bd` |
| 2 | 设置页 / 系统设置各卡改用 `OTRow`，行高 44、图标块 30、文字起点 58dp | 自查 + 编译 | ✅ 设置页 `447214e`；系统设置组标题与间距 `2292da0` / `2c…`（字段行仍是输入框，需保留自定义布局，故只统一卡片与间距） |
| 3 | 管理五页行首补图标块、右侧列定宽 | 自查 + 编译 | ✅ 用户 `ffba9ed` / 注册密钥 `bb08798` / 权限 `9179222` / 全部邮件 `8fc4337`；分析页 `083898f`；全部邮件翻页 `cf4dbd5` |
| 4 | 间距令牌收口 | `grep 'height(1[26].dp)'` 归零 | ✅ 七文件 `570b01c` + 系统设置 `2292da0` |
| 5 | 读信 / 写信 / 联系人 / AI / 登录 五屏对照设计稿校准 | 自查 + 编译 | 部分：读信（星标改矢量图标、正文高度自适应、暗色配色）、写信（`OTFieldRow`/`OTPrimaryButton`）、登录（`OTGroup` + 三行 `OTFieldRow`）已改；**联系人 / AI 只换了导航栏**，行距与右侧列未校准 |
| 6 | 真机一次性回归：`adb reverse` + `install -r` + 15 屏截图对照 | 需要用户接手机 | 🚫 阻塞（设备已断开） |

## 已知未验证项（上真机时必须盯）

1. 详情页正文 `contentHeight` 自适应（极短正文可能停在 220dp 兜底）；
2. 详情页暗色正文配色（HTML 壳已改为主题色）；
3. 各屏在 1080×2280 的实际留白与设计稿 393×852 的换算是否一致；
4. 写信页附件选择、草稿回填在真机上的行为；
5. 全部邮件「加载更多」的最后一页边界（`total` 与实际可取数可能不一致）；
6. 联系人页 A–Z 索引按「每组两项」计算（每个字母恰好 = 组标题 + 一个卡片 item，所以是精确的；只有字母分组新增/删除时才需要复核）。

## 仍有硬写 dp 的位置（有意保留）

组件几何值不算间距，未强行令牌化：`51/31/27/2/16`（开关几何）、`44`（触控尺寸在部分行内联）、图标尺寸 `16/18/20/22/24/48`、圆角 `7/10/14`。全量体检明细：26 个 `.kt` 共 213 处 `\.dp`，其中间距类已收口。

## 后端待办（不属于本仓库，但影响交付）

## 2026-10-31 本轮：抽屉空数据的根因已定位

1. **抽屉「帐户」与各计数为空不是客户端时机问题**：设备 `shared_prefs/otsmail.xml` 里存着 `token=dev-token`（早期调试包遗留），服务端对所有需要鉴权的接口返回 `{"code":401,"message":"身份认证失效,请重新登录"}`（实测 `/account/list`、`/email/menu-counts`、`/email/category-counts`、`/email/list`、`/my/setting` 全是这个结果）。收件箱本身此刻也显示「身份认证失效,请重新登录」，不是只有抽屉空。
2. 处理：`adb shell pm clear com.otscup.otsmail` 清偏好后回到登录页（服务器已预填 `http://127.0.0.1:8789/api`）。**本地 dev 管理员口令已按用户授权重置为 `123456`**（`tools/set-dev-pwd.cjs` 生成 PBKDF2 哈希 → `wrangler d1 execute otsmail-dev --local --file dev/_tmp/set-dev-pwd.sql`），仅动本地 D1，线上未变。
3. 重置后真机复验通过：收件箱 18 封/12 未读正常加载；抽屉出现两个帐户（admin@example.com 带橙色 18 未读胶囊、ops@example.com）、邮箱计数 18/2、分类计数 主要 18；点 `ops@example.com` 切帐户 → 列表重载为空、再切回 admin 恢复 18 封。截图 `acc/03d-drawer-two-accounts.png`、`acc/03e-drawer-switch-account.png`、`acc/_back-admin.png`。
3. 顺带修掉的真 bug（`mail-worker` 已提交 `f175c2e`）：`src/service/account-service.js:20` 解构 `minEmailPrefix/emailPrefixFilter` 无默认值 → setting 缓存缺字段时 `emailPrefixFilter.some()` 抛 TypeError，导致 `/account/add` 必 500；另外本地 D1 缺 `user_perm` 表（`src/init/init.js` 只在安装流程建表），老库升级后任何写权限操作都会 500，已手工补建。
4. 抽屉数据拉取合并进 `load()` 成功分支（提交 `2fe8f36`），去掉重复的 `LaunchedEffect`。
5. 待办：`/account/add` 从客户端新建帐户后抽屉是否即时刷新（本轮只验了服务端能建、抽屉能显示已有帐户）。

## 2026-10-31 第二轮：分类与邮箱组合筛选 + 草稿方案定案

1. **组合筛选已落地**（提交 `98951dc`）：`InboxScreen.kt` 的分类点按不再执行 `current = mailboxes[0]`，分类与邮箱/星标/未读/垃圾互相独立叠加。真机验证两张：`acc/02c-inbox-unread-primary.png`（未读邮件 · 主要，12 行全是 primary 且带未读点）、`acc/02d-inbox-starred-primary.png`（已标星 · 主要，7 封、页脚「共 7 封 · 第 1 页」）。服务端 `/email/list` 本来就同时吃 `type/emailId` 与 `category`（`mail-worker/src/service/email-service.js`），无需改后端。
2. **发现的小差异**：`/star/list` 返回的行不带 `category` 字段，所以「已标星」里不显示分类 chip（收件箱里有）。属数据字段缺失，不是排版问题，暂不处理。
3. **草稿跨端同步：选「本地持久化」，不建后端草稿表**。理由：① `mail-worker` 目前全无 draft 概念（`grep draft src/` 空），要做得新增表 + 两个接口 + 冲突合并策略（同草稿多端编辑谁赢），这是 v1 之外的一整块；② 现有 `data/Drafts.kt`（SharedPreferences `otsmail_draft`，`list/save/remove` 以 `at` 毫秒作主键）已经满足「退出重进不丢」。真机实证：写信页填 `ops@example.com / draft-check-2230 / local` 后返回，Toast「已处理」，`run-as cat shared_prefs/otsmail_draft.xml` 拿到 `{"to":"ops@example.com","subject":"draft-check-2230","text":"local","at":1793457045823}`。跨端同步留到 v1.1，届时加 `draft` 表 + `GET/PUT /my/draft`，用 `updatedAt` 后写覆盖。
4. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding 0 / hardColor 0 / glyph 0；`assembleDebug` 通过。

`otsmail/mail-worker` 已有未提交改动：7 个 bug 修复（星标列表 500、站内图不入附件行、`/attachments` 只读 KV、read/unread 入参、jwt 未登录堆栈、附件 mimeType、CORS 本地白名单）与两个新接口（`POST /my/uploadImage`、`PUT /my/setting`）。上线前：`npm run check` → 本地 8789 + `npm run smoke:mobile`（21 项）→ 确认 `git status` 里他人未提交改动 → deploy → 验星标页与图片。清单另见 `docs/BACKEND-DEPLOY.md`。
