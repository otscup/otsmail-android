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

## 2026-10-31 第三轮：虚线分隔落地 + VIP 取舍结论

1. **虚分隔已落地**（提交 `47e008c`）：新增 `OTDash()`（`OTComponents.kt`，`Canvas` + `PathEffect.dashPathEffect(3f,3f)`，1.5dp 高、左右 18dp、颜色 `separator`），插在抽屉「帐户」组之后与「分类」组之后，对齐样张 `.drawer__dash`（`otsmail-ios-v2/ui.css:712`）。真机截图 `acc/03f-drawer-dash.png`：帐户组（admin 带橙色 18 胶囊 + ops 绿圈）→ 虚线 → 邮箱组（收件箱 18 / 未读邮件选中 / 已标星 / 已删除 2 / 垃圾邮件）→ 分类组 → 虚线。
2. **VIP 行取舍：v1 不做**。样张的 `drawer__vip`（`01-inbox.html:564`，固定 21px 宽蓝色 `VIP` 标签，对应「重要联系人」）只是视觉占位：`mail-worker` 全库没有 VIP/重要联系人的表或字段，联系人页也只有「加入 VIP」按钮（`06-contacts.html:168`）。做它得先加表与接口，属 v1 外。**落地时机**：等后端有 `vip` 标记后，在 `OTRow`/抽屉行复用同一 21dp 尾部槽位即可，不需要新组件。
3. **已知数据缺口**：`/star/list` 不返回 `category`，所以「已标星」列表不显示分类 chip（收件箱显示）。要么后端补字段，要么客户端在星标视图隐藏 chip——v1 保持现状，记录在案。
4. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0；`assembleDebug` 通过。

## 2026-11-01 第四轮：15 屏回归 + 四个真 bug

1. **15 屏全部出图**（`F:\Desktop\MFiles\otsmail-android-shots\acc\`），脚本 `tools/cap15.cjs` 改成「每屏冷启动 → 开抽屉 → 按文本点」，不再依赖上一步的页面栈；`tools/cap-longmail*.cjs` 专拍长正文那封。
2. **抽屉「其他」组不可达**（提交 `7e79820`）：抽屉根 `Column` 没有滚动，1080×2280 上「联系人/AI 助手/设置/系统设置」被裁在屏外。加 `.verticalScroll(rememberScrollState())` 后一屏滑到底可见，末尾还有「退出登录」。
3. **启动时 token 没就位**（提交 `170fb33`）：`Session.apply()` 原在 `LaunchedEffect(Unit)` 里，晚于子屏首帧发出的请求，导致第一批接口带空 token 出门（分析页收件/发件/已删除恒为 0）。改成 `remember { Session(context).also { it.apply() } }` 同步执行。
4. **收件箱时间列是 `mm:ss`**（提交 `d8660be`）：`clock()` 用 `fmtFull(raw).takeLast(5)`，从 `yyyy-MM-dd HH:mm:ss` 取到的是分秒（真机显示 `32:38`）。改用 `clockOf()` 取 `HH:mm`（`TimeFmt.kt`），真机恢复 `06:32`。
5. **读信页正文被截断**（提交 `e7bd475`）：`contentHeight` 量出来的高度不可靠（宽表格/长正文只渲染到第 2~3 段，其余被 WebView 裁掉且再没有滚动）。结构性改法：页头 + 翻译行 + 附件行固定在顶部，正文 `Box` 用 `weight(1f)` 拿剩余高度、由 WebView 自己滚。真机验证长邮件：6 段全出、4 列宽表格不撑破、长链接不破版、正文可滚动（`acc/08-detail-long.png`、`acc/08b-detail-long-scrolled.png`）。
6. 分析页脚注措辞从「本轮没接」改成用户可读的「统计口径…暂未接入」。
7. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0；`assembleDebug` 通过。

## 2026-11-01 第五轮：抽屉「退出登录」删掉 + 15 屏重拍

1. **抽屉「退出登录」不可达（真机语义节点 `bounds=[0,0][0,0]`）**：抽屉内容用 `verticalScroll` 后总高仍超屏，末行被 Layout 裁掉——看得见（Surface 不裁绘制）但 uiautomator 拿不到 bounds，点击也无命中。试过「外层 `Column` + 内层 `weight(1f)` 把退出键钉在底部」，语义节点依旧是 0,0（`ModalDrawerSheet` 内高度约束不受 `fillMaxSize` 控制）。
2. **定案：抽屉里不放退出登录**（提交 `2d2c0db`）。理由：设置页底部本来就有「退出登录」（`SettingsScreen.kt:197`），抽屉那一份是重复入口；定稿样张的抽屉也只有 帐户/邮箱/分类/其他 四组。删掉后 `InboxScreen` 的 `onLogout` 形参与 `App.kt` 的传参一并去掉，退出统一走「设置 → 退出登录」。
3. **15 屏重拍**：`tools/cap15.cjs` 一次跑完；抽屉单独用新增的 `tools/cap-drawer.cjs`（等 2.5s 动画结束再截，之前那张是半开状态）。登录页用 `tools/cap-login.cjs`（走「设置 → 退出登录」→ 截 `01-login` → 重新登录 → 截 `01b-after-login`）。**坑**：`adb shell input keyevent 111`（ESC）会直接关掉 Activity 掉回桌面，不要用它收键盘；点「登录」按钮失败时再点一次即可。
4. **拼图**：`tools/contact-sheet.ps1` → `F:\Desktop\MFiles\otsmail-android-shots\contact-sheet.png`（1200×1494，15 屏带文件名）。
5. 截图左侧的「ICB」圆球是手机里工行 App 的悬浮球，属系统悬浮窗，`am force-stop com.icbc` 只能短暂去掉（会自动复活），不影响验收；要干净截图需用户手动关掉该悬浮窗。
6. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0；`assembleDebug` 通过。

## 2026-11-01 第六轮：UI 面板对齐设计稿

1. **收件箱**（提交 `4a31223`）：行高 `min=86` + 上下 11dp、行间补 `OTDivider(inset = 58)`（此前注释误记「设计稿无行间线」）、新增 `OTDayHeader`（13/18 ink2，14/16/8）替换 `OTGroupHeader`、时间色 `label2→label3`、头像 44→36、顶栏 padding 6/16/4、标题行 padding 6/16/10 且去掉 `OTLargeTitle` 的 20dp 顶距、「12 未读」下移 6dp、FAB `end=20 bottom=34`、搜索胶囊 `start 10 / end 4`、图标 15、gap 6。
2. **抽屉改浮动卡片**（提交 `4a31223`）：`ModalDrawerSheet` 由满高贴边改为 `width 322dp` + 四边 8dp 内缩 + `RoundedCornerShape(28.dp)` + `bgCard` + `WindowInsets(0)`，`statusBarsPadding/navigationBarsPadding` 移到 sheet 的 modifier 上；抬头头像 56→46、顶部 padding 16/16/12；帐户行圆点由「28dp 带首字母实心圆」改回样张的 12dp 空心环（2dp 描边，`accColor` 六色轮转）。
3. **系统设置**（提交 `9a2d95e`）：原来每个子项一张卡（9 张），改为「每个分区一张卡 + 行间 `OTDivider(inset = 16)` + 行高 44dp」，分区仍为 站点(3)/存储与推送(4)/AI(1)。
4. **写邮件**（提交 `f104fb0`）：附件由「已选 N 个附件」文字改为逐行列表（回形针图标 + 文件名 + KB + 右侧 ✕ 移除，移除时同步 `attachments`）；正文区 `height(220)` → `heightIn(min = 160)`；签名从 `Session.signature` 读，展示在正文下方（subhead/label2），发送时追加到 `text` 与 `html`（此前签名设置项存了但发信根本没用）。
5. **真机复核**：`acc/02-inbox.png`（收件箱新行距/发丝线/日期头）、`acc/03-drawer.png`（322/28/8 浮动抽屉 + 空心帐户点）、`acc/04-settings.png`、`acc/05-syssettings.png`（分区卡）、`acc/09-compose.png`（签名显示在正文下）逐张 `read_image` 看过。
6. **未对齐项**：设置页「系统设置」仍是独立卡（与下方管理卡之间有 8dp 缝，样张是一张卡）；管理员昵称卡头像 76dp 未按样张收小；写邮件缺样张的格式工具栏（属新功能，未做）。
7. **踩坑复述**：用 PowerShell `Get-Content`/`WriteAllLines` 改含中文的 `.kt` 会双重编码（本次把 `SysSettingsScreen.kt` 写成乱码，`git checkout --` 复原后改用 edit 工具）；`adb shell input text` 遇空格只输入第一个词（签名被打成 `Sent`），多词要用 `%s`。
8. 质量闸门：`node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0；`assembleDebug` 通过。

## 2026-11-01 第七轮：联系人接真通讯录、写邮件补发件人/抄送密送

1. **联系人原来不用后端通讯录**（提交 `0aa2d6b`）：`ui/ContactsScreen.kt` 注释写着「后端暂时没有通讯录接口」，实际 `mail-worker/src/api/contact-api.js` 有 `GET /contact/list` 与 `POST /contact/add|set|delete`，表 `src/entity/contact.js` 字段 `contactId/userId/name/email/groupName/remark/createTime/isDel`。改为新增 `data/Api.kt` 的 `contacts()` 读 `/contact/list?page=1&size=200`，通讯录为空才退回「从收件箱抽发件人」。
2. **行内信息补齐**：副标题按样张 `email · 分组`（`groupName` 空则只显示邮箱），行尾补 `Icons.Outlined.ChevronRight` 18dp/label3；非拉丁首字母归入 `#`（原实现直接取首字符，索引条上会出现「张」「李」）。
3. **`/contact/set` 只认全量字段**：`contact-service.js` 的 `set` 走 `normalize(params)` + `checkEmail`，只传 `contactId+groupName` 会被判非法邮箱；`tools/seed-contacts.cjs` 改成连同 `name/email/remark` 一起提交，12 条联系人按 客户/工作/家人/默认 轮换分组。
4. **写邮件补样张的结构**（提交 `af38f4f`）：新增「发件人」行（`Api.accounts()` 取多账户，点开就地下拉选择，选中项标蓝「当前」）、「抄送 / 密送」两个蓝字按钮（右对齐，展开后各占一行）、`Api.send` 增加 `cc`/`bcc` 参数并如实传给 `/email/send`。
5. **真机验证 cc 链路**：`tools/compose-cc-steps.cjs` 走完整 UI 流程（发件人 admin@example.com、收件人 admin@example.com、抄送 ops@example.com、主题 `cc-ui-3105`）→ 发件箱出现 `209 | cc-ui-3105 | to admin@example.com | cc [{"address":"ops@example.com","name":"ops"}]`（`tools/find-cc.cjs` 查）。后端 `email-service.js:send` 本来就吃 `cc/bcc` 并按 `domainList` 判站内站外。
6. **两个测试陷阱（写进工具脚本注释）**：① `adb shell input keyevent 111`（ESC）会直接关掉 Activity 掉回桌面，`keyevent 4` 会退页，收键盘不能靠它们；② 手机屏幕左缘有一个不属于本 App 的悬浮球（工行 `com.icbc`），`x < ~200` 的点击会被它吃掉并跳到工行，所以脚本一律点行中间（`x=540`），`am force-stop com.icbc` 只能短暂压制。
7. 缺样张的「格式工具栏」仍未做（要做富文本编辑，属新功能）；管理员昵称卡头像仍未按样张收到 76dp。
8. 质量闸门：`node tools/local-audit.cjs` → encoding / hardColor / glyph 全 0；`assembleDebug` 通过。

`otsmail/mail-worker` 已有未提交改动：7 个 bug 修复（星标列表 500、站内图不入附件行、`/attachments` 只读 KV、read/unread 入参、jwt 未登录堆栈、附件 mimeType、CORS 本地白名单）与两个新接口（`POST /my/uploadImage`、`PUT /my/setting`）。上线前：`npm run check` → 本地 8789 + `npm run smoke:mobile`（21 项）→ 确认 `git status` 里他人未提交改动 → deploy → 验星标页与图片。清单另见 `docs/BACKEND-DEPLOY.md`。
