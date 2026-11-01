# 进度与计划（长线任务）

更新时间：2026-11-02 06:10
基线提交：`75f36c0`（乱码注释 + 草稿箱图标），本文件随后与该批一起提交。

## 当前状态

- 14 屏全部实现，`assembleDebug` 通过，版本 0.2.0；
- 本地静态自查全绿：`U+FFFD 0`、主题外写死颜色 `0`、文字字形冒充图标 `0`（`tools/local-audit.cjs`）；
- **收件箱**已逐项对照 `design/v5/01-inbox.png`；其余 13 屏的「卡间距 / 组标题 / 行内图标块 / 右侧列」已统一到 `OTSpace` + `OTGroup` + `OTRow`；
- 第八轮 UI 对齐已落地并真机重拍全套 15 屏。

## 步骤（每步一次提交）

| # | 步骤 | 验收方式（全部本地） | 状态 |
|---|---|---|---|
| 1 | 库补齐：`OTIconTile`（30dp 同色 14% 圆角块）+ `OTRow(title, subtitle, icon, trailing)` | 编译 + 设置页替换 ≥5 行 | ✅ `0bab6bd` |
| 2 | 设置页 / 系统设置各卡改用 `OTRow`，行高 44、图标块 30、文字起点 58dp | 自查 + 编译 | ✅ 设置页 `447214e`；系统设置组标题与间距 `2292da0` / `2c…`（字段行仍是输入框，需保留自定义布局，故只统一卡片与间距） |
| 3 | 管理五页行首补图标块、右侧列定宽 | 自查 + 编译 | ✅ 用户 `ffba9ed` / 注册密钥 `bb08798` / 权限 `9179222` / 全部邮件 `8fc4337`；分析页 `083898f`；全部邮件翻页 `cf4dbd5` |
| 4 | 间距令牌收口 | `grep 'height(1[26].dp)'` 归零 | ✅ 七文件 `570b01c` + 系统设置 `2292da0` |
| 5 | 5 屏对照设计稿校准 | 自查 + 编译 | 部分：读信/写信/登录已改；联系人/AI/剩余管理页第八轮继续对齐 |
| 6 | 真机一次性回归：`adb reverse` + `install -r` + 15 屏截图对照 | 真机验收 | ✅ 第八轮已重拍全套 15 屏 |

## 已知未验证项（上真机时必须盯）

1. 详情页正文 `contentHeight` 自适应（极短正文可能停在 220dp 兜底）；
2. 详情页暗色正文配色（HTML 壳已改为主题色）；
3. 各屏在 1080×2280 的实际留白与设计稿 393×852 的换算是否一致；
4. 写信页附件选择、草稿回填在真机上的行为；
5. 全部邮件「加载更多」的最后一页边界（`total` 与实际可取数可能不一致）；
6. 联系人页 A–Z 索引按「每组两项」计算（每个字母恰好 = 组标题 + 一个卡片 item，所以是精确的；只有字母分组新增/删除时才需要复核）。

## 仍有硬写 dp 的位置（有意保留）

组件几何值不算间距，未强行令牌化：`51/31/27/2/16`（开关几何）、`44`（触控尺寸在部分行内联）、图标尺寸 `16/18/20/22/24/48`、圆角 `7/10/14`。全量体检明细：26 个 `.kt` 共 213 处 `\.dp`，其中间距类已收口。

## 2026-10-31 本轮：抽屉空数据的根因已定位

1. **抽屉「帐户」与各计数为空不是客户端时机问题**：设备 `shared_prefs/otsmail.xml` 里存着 `token=dev-token`（早期调试包遗留），服务端对所有需要鉴权的接口返回 `{"code":401,"message":"身份认证失效,请重新登录"}`（实测 `/account/list`、`/email/menu-counts`、`/email/category-counts`、`/email/list`、`/my/setting` 全是这个结果）。收件箱本身此刻也显示「身份认证失效,请重新登录」。
2. 处理：`adb shell pm clear com.otscup.otsmail` 清偏好后回到登录页。**本地 dev 管理员口令已按用户授权重置为 `123456`**（`tools/set-dev-pwd.cjs` 生成 PBKDF2 哈希 → `wrangler d1 execute otsmail-dev --local --file dev/_tmp/set-dev-pwd.sql`），仅动本地 D1。
3. 重置后真机复验通过：收件箱 18 封/12 未读正常加载；抽屉出现两个帐户（admin 橙色 18 胶囊、ops 绿圈）、邮箱计数 18/2、分类计数 主要 18；点 `ops@example.com` 切帐户 → 列表重载为空、再切回 admin 恢复 18 封。
4. 顺带修掉的真 bug（`mail-worker` 已提交）：`src/service/account-service.js:20` 解构 `minEmailPrefix/emailPrefixFilter` 无默认值导致 `/account/add` 500；本地 D1 缺 `user_perm` 表导致权限操作 500，已手工补建。
5. 抽屉数据拉取合并进 `load()` 成功分支，去掉重复的 `LaunchedEffect`。
6. 待办：`/account/add` 从客户端新建帐户后抽屉是否即时刷新。

## 2026-10-31 第二轮：分类与邮箱组合筛选 + 草稿方案定案

1. **组合筛选已落地**：分类点按不再强制切回收件箱，分类与邮箱/星标/未读/垃圾可叠加。服务端 `/email/list` 本来就同时吃 `type/emailId` 与 `category`。
2. **发现的小差异**：`/star/list` 返回的行不带 `category` 字段，所以「已标星」里不显示分类 chip。
3. **草稿跨端同步：选「本地持久化」，不建后端草稿表**。现有 `data/Drafts.kt`（SharedPreferences）已满足需求，跨端同步留到 v1.1。
4. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0。

## 2026-10-31 第三轮：虚线分隔落地 + VIP 取舍结论

1. **虚分隔已落地**（`OTDash()`，Canvas 虚线）：插在抽屉「帐户」组之后与「分类」组之后。
2. **VIP 行取舍：v1 不做**。样张的 `drawer__vip` 只是视觉占位，后端无 VIP 字段。
3. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0。

## 2026-11-01 第四轮：15 屏回归 + 四个真 bug

1. **15 屏全部出图**，脚本改成每屏冷启动。
2. **抽屉「其他」组不可达**：加 `.verticalScroll(rememberScrollState())`。
3. **启动时 token 没就位**：`Session.apply()` 改成构造时同步执行。
4. **收件箱时间列 `mm:ss`→`HH:mm`**（`TimeFmt.kt`）。
5. **读信页正文被截断**：结构改为页头固定 + WebView `weight(1f)` 自滚，真机验证长邮件通过。
6. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0。

## 2026-11-01 第五轮：抽屉「退出登录」删掉 + 15 屏重拍

1. **抽屉「退出登录」不可达**：语义节点 `bounds=[0,0][0,0]`，定案删除，统一走「设置 → 退出登录」。
2. **15 屏重拍**：新增 `tools/cap-drawer.cjs`、`tools/cap-login.cjs`，`tools/contact-sheet.ps1` 生成拼图。
3. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0。

## 2026-11-01 第六轮：UI 面板对齐设计稿

1. **收件箱**：行高/行间线/日期头/头像/顶栏/搜索胶囊/FAB 对齐样张。
2. **抽屉改浮动卡片**：322dp×28dp×8dp，帐户圆点改为空心环。
3. **系统设置**：每个分区一张卡 + 行间分隔线。
4. **写邮件**：附件列表+移除、正文区高度调整、签名展示。
5. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0。

## 2026-11-01 第七轮：联系人接真通讯录、写邮件补发件人/抄送密送

1. **联系人**：接入 `GET/POST /contact/*`，优先通讯录、非拉丁首字母归 `#`、补充分组和 ChevronRight。
2. **写邮件**：发件人下拉、抄送/密送右对齐展开、`Api.send` 增加 `cc/bcc`，附件列表可移除、签名进正文。
3. **cc 链路真机验证通过**。
4. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0。

## 2026-11-02 第八轮：剩余页面对照样张（邮件端 UI 对齐）

1. **后端（mail-worker）**：`email-service.js` 加 `status` 参数，支持 `status=7`（无人收件）筛选。
2. **数据层（Api.kt）**：`MailItem` 增加 `type/status`，`allEmails` 扩展 `type/mailbox/status`，新增 `analysisEcharts()`。
3. **管理页（AdminScreens.kt）**：`AllMailScreen`（5 个 pills + 搜索）、`UsersScreen`（角色名映射）、`RolesScreen`（权限数）、`RegKeysScreen`（角色名/过期）、`AnalysisScreen`（echarts 真数据 + HBar/Bars）、`NotFoundScreen` 抛光。
4. **登录页（LoginScreen.kt）**：74dp 渐变标 + OTS/Mail 双色标题。
5. **读信页（DetailScreen.kt）**：翻译条改用图标。
6. **AI 页（AiScreen.kt）**：FlowRow 4 个快捷指令、气泡 20dp 圆角、动作纯文字、发送按钮 38dp。
7. **编译与真机**：`assembleDebug` BUILD SUCCESSFUL，已安装到真机并用 `tools/cap15.cjs` 重拍全套 15 屏。
8. **待办**：联系人 contact-hero、写邮件格式工具栏、管理员昵称卡头像、`mail-worker` 部署、精细像素校对、更新交付物。

`otsmail/mail-worker` 已有未提交改动：7 个 bug 修复与两个新接口（`POST /my/uploadImage`、`PUT /my/setting`）。上线前：`npm run check` → 本地 8789 + `npm run smoke:mobile`（21 项）→ 确认 `git status` → deploy → 验星标页与图片。清单另见 `docs/BACKEND-DEPLOY.md`。

### 第九轮（2026-11-02）
- BACKEND-DEPLOY 补充 mail-worker status 筛选（支持无人收件）。
- 装机验证，local-audit 全 0，assembleDebug 通过。

