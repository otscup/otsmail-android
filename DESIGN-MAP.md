# DESIGN-MAP · 原生端（Kotlin/Compose）对照定稿样张的设计规范

> 先设计、后写码。这份文档是原生端**唯一的视觉真值来源**：样张 `F:\Desktop\MFiles\otsmail-ios-v2` 的 `tokens.css` / `ui.css` 是 CSS 形态的真值，这里把它**逐条翻译成 Compose 规格**。
> 规则：以后任何页面只允许从这里取尺寸和组件；写页面前先查本表，表里没有的先补表。

---

## 0. 为什么要有这份文件（复盘）

第一版原生端和定稿差得远，原因不是「手艺」，是方法：

1. **把样张当参考图，没当组件规范**——只抄了标题、搜索框、FAB 这些显眼件，`insetGrouped`（灰底浮白卡）、行高、发丝线缩进、胶囊标签这些**结构规则**没落成组件。
2. **顺序错**——先用 Material 默认件跑通功能再「上色」，得到的是「上色的 Material」，不是样张的结构。
3. **只有页面层没有组件层**——同一件事（分组列表）在 4 个页面 4 种写法。
4. **pt→dp 没做网格换算**——行高、触控 44、边距 16 全部偏松。
5. **token 只抄了字号**，`lineHeight`/`letterSpacing` 缺省 → 字看着不对。
6. **我自己加了设计外元素**（刷新钮 `↻`、方块 chip、时间与星标挤一行）。

所以下面先把规则写死，再谈改代码。

---

## 1. Token（唯一来源，落在 `ui/theme/OT.kt`）

### 1.1 字阶（字号 / 行高 / 字距）

| 语义 | sp | lineHeight | letterSpacing | 用途 |
|---|---|---|---|---|
| largeTitle | 34 | 41 | +0.37 | 收件箱「收件箱」、设置页大标题 |
| title1 | 28 | 34 | +0.36 | 登录页字标 |
| title2 | 22 | 28 | +0.35 | 弹层标题 |
| title3 | 20 | 25 | +0.38 | 卡标题、读信页主题 |
| body | 17 | 22 | −0.41 | 正文、行主文字 |
| subhead | 15 | 20 | −0.24 | 行副文字、按钮 |
| footnote | 13 | 18 | −0.08 | 说明、分组标题、键盘说明 |
| caption1 | 12 | 16 | 0 | chip、时间次要信息 |
| caption2 | 11 | 13 | +0.07 | 动作条标签、A–Z 索引 |

**硬规则**：Compose 里 `Text(style = t.body)` 必须同时拿到 lineHeight/letterSpacing，禁止只设 fontSize。

### 1.2 颜色（亮 / 暗）

| 语义 | 亮 | 暗 | 用途 |
|---|---|---|---|
| bgGrouped | #F2F2F7 | #000000 | 页面底（分组列表底） |
| bgCard | #FFFFFF | #1C1C1E | 卡片、sheet、导航栏实体 |
| bgBar | rgba(249,249,249,.82) | rgba(28,28,30,.82) | 毛玻璃栏（滚动后才需要） |
| bgInput | rgba(118,118,128,.12) | rgba(118,118,128,.24) | 搜索框、输入底、圆形按钮底 |
| label | #000 | #FFF | 主文字 |
| label2 | rgba(60,60,67,.6) | rgba(235,235,245,.6) | 次文字、说明 |
| label3 | rgba(60,60,67,.3) | rgba(235,235,245,.3) | 占位、禁用地 |
| label4 | rgba(60,60,67,.18) | rgba(235,235,245,.18) | 发丝线之上的弱图形（空心星） |
| separator | rgba(60,60,67,.29) | rgba(84,84,88,.6) | 发丝线（0.5dp） |
| blue | #007AFF | #0A84FF | 强调、链接、发送、未读 |
| green | #34C759 | #30D158 | 成功、已启用 |
| orange | #FF9500 | #FF9F0A | 星标 |
| red | #FF3B30 | #FF453A | 危险、删除、封禁 |
| purple / teal / indigo | #AF52DE / #30B0C7 / #5856D6 | #BF5AF2 / #40C8E0 / #5E5CE6 | 头像与图标底色 |

**硬规则**：页面里禁止出现字面量 `Color(0xFF…)`，只能取 `OT.colors.*`；危险操作只允许 `c.red`。

### 1.3 间距（8pt 网格）

`sp1=4 sp2=8 sp3=12 sp4=16 sp5=20 sp6=24 sp8=32 sp10=44`

### 1.4 圆角

`rXs=4 rSm=8 rMd=12 rLg=16 rIcon=7 pill=高度/2`

### 1.5 结构常量

| 常量 | 值 | 说明 |
|---|---|---|
| screenW / screenH | 393 × 852 | 设计基准（不写死，只用作比例参考） |
| screenPad | 16 | 左右边距 |
| tapMin | 44 | 一切可点区域的最小边长 |
| rowH | 44 | 设置类行高 |
| mailRowH | 78 | 邮件行高（含两行摘要） |
| navH | 44 | 内联导航栏 |
| statusH | 54 | 状态栏参考（实际用 `statusBarsPadding()`） |
| homeH | 34 | 底部手势区（实际用 `navigationBarsPadding()`） |
| rowInset | 58 | 行内发丝线起点（= 16 边距 + 34 头像 + 8 间距） |

### 1.6 动效

| token | 值 | 用途 |
|---|---|---|
| easeIos | `CubicBezierEasing(0.32, 0.72, 0, 1)` | 一切位移/转场 |
| durPush | 320ms | push 转场 |
| durPress | 200ms | 按压反馈淡入淡出 |
| pressScale | 0.975（行）/ 0.86（圆形图标钮） | 按下缩放 |
| pressDim | 目标色叠 6% 压深 | 按下变暗 |

**硬规则**：不使用 Material 涟漪（`indication = null`）；转场不用默认 300ms；quit 弹出用 `spring(dampingRatio = NoBouncy, stiffness = 420)` 回弹。

---

## 2. 组件映射表（CSS → Compose，唯一实现地址 `ui/OTComponents.kt`）

| # | 样张类名 | 视觉规格 | Compose 组件 | 现状 |
|---|---|---|---|---|
| 1 | `.screen` | 全屏容器，底色 bgGrouped | `OTScreen` | 各页手写 Column，需统一 |
| 2 | `.statusbar` / `.home-bar` | — | `statusBarsPadding()` / `navigationBarsPadding()` | 已用，个别页漏 |
| 3 | `.navbar--inline` | 高 44，左右各 44 的按钮位，标题 17 半粗居中，底发丝线 | `OTNavBar(back, title, actions)` | 各页手写 Row，需统一 |
| 4 | `.navbtn` | 44×44 命中区，图标 20（蓝/主色） | `OTNavButton(icon, onClick)` = `tapArea` + 居中图标 | 用 `tapArea`，未成组件 |
| 5 | `.large-title` | 34/41，左对齐，下方 16 才接内容 | `OTLargeTitle(text)` | 收件箱内联 |
| 6 | `.scroll` | 滚动区，底部预留 homeH+16 | — | 各页 LazyColumn |
| 7 | `.searchbar` | **高 36，圆角 10，bgInput，左 8 右 0 内边距**，放大镜 15，占位 15pt label3 | `OTSearchPill(value, onChange, trailing)` | 56dp/16dp ✗ |
| 8 | `.topbar` | 高 44 + 上下各 8，头像 36 + 搜索胶囊占满 | `OTTopBar` | 收件箱内联 |
| 9 | `.group` | **bgCard、圆角 12、左右边距 16**，卡内行之间 0.5dp 发丝线（缩进 0，从卡片左缘） | `OTGroup { content }` | 全宽行 ✗ |
| 10 | `.group__header` | 13pt label2，左 16，上 20 下 8 | `OTGroupHeader(text)` | 手写 |
| 11 | `.group__footer` | 13pt label2，左 16 右 16，上 8 | `OTGroupFooter(text)` | 手写 |
| 12 | `.row` | **高 44**，内边距 左右 16，主文字 17pt，副文字 13pt label2（两行时行高自动 56） | `OTRow(icon, title, sub, value, chevron, onClick)` | 设置页手写 |
| 13 | `.row__icon` | 30×30，圆角 7，底色 = 主色 14% | `OTRowIcon(color, glyph)` | 手写 |
| 14 | `.row__chev` | 13pt label3 的 `›` | 内含于 OTRow | — |
| 15 | `.mail-row` | **高 78**；结构：[未读点 8] + 头像 34 + 主列（发件人 17 + chip + 时间 13 右）/ 主题 17 / 摘要 15 label2 两行；星标独立 36 命中 | `OTMailRow(mail, onOpen, onStar)` | 接近，行高 85 ✗ |
| 16 | `.dot` | 8dp 圆，蓝色，垂直居中于发件人行 | 内含 | 已对 |
| 17 | `.avatar` | 34dp 圆，底色按名字哈希取 6 色之一，白字 15 | `OTAvatar(name, size)` | 已对（全蓝可接受，见 §5-6） |
| 18 | `.star` | 36×36 命中，空心 = label4，实心 = orange | 内含 | 36dp 已对 |
| 19 | `.chip` | **高 20，圆角 10，字号 11，底色 tint 12%，字色同色系** | `OTChip(text, tone)` | 方块 ✗ |
| 20 | `.tag` | 高 26 胶囊，含 `×` | `OTTag(text, onRemove)` | 写信页手写 |
| 21 | `.switch` | 51×31，轨道 on=green / off=bgInput，滑块 27 白，投影 | `OTSwitch(checked, onChange)` | 用 Material Switch ✗ |
| 22 | `.btn` | 高 44（`--block` 全宽），圆角 12；filled(蓝底白字)/tinted(蓝 12% 底蓝字)/plain(无底蓝字)/destructive(红字) | `OTButton(text, style, onClick)` | 多处 Material Button ✗ |
| 23 | `.field` | 无边框，上方 11pt label3 标签，值 17pt，底发丝线 | `OTField(label, value, onChange)` | 已用 FieldRow（可复用，改名） |
| 24 | `.tagfield` | 收件人 token 区，高自适应，含 `+` 选人 | `OTTagField` | 写信页用逗号分隔 ✗ |
| 25 | `.actionbar` | 高 44 + homeH，四项均分，图标 20 + 11pt 标签 | `OTActionBar(items)` | 详情页已对 |
| 26 | `.fab` | 52×52 圆，蓝底，图标 22 白，右下 16 边距 | `OTFab(glyph, onClick)` | 已对 |
| 27 | `.scrim` + `.sheet` | scrim .4；sheet 圆角 16 顶角 + 36 抓取条，从底部 0.3s 升起 | `OTSheet(visible, content)` | 未建（现用 Toast/页面） |
| 28 | `.page`（push） | 新页右滑入 0.32s，底页左移 30% + 降亮度 | `SlidePage` | 已有，缺底页视差 |
| 29 | `.toast` | 底部 60 处，黑 82% 底，圆角 pill，13pt 白 | `OTToast` | 用 Material Snackbar ✗ |
| 30 | `.mailview` / `.mailbody` | 主题 20pt、日期 13pt label2、chip 行、正文 17/1.45 | `OTMailBody(html)` = WebView + shell CSS | 已对 |
| 31 | `.pull` | 下拉 96 上限，转圈 20 | `OTPullRefresh` | 未做（现用 ↻ 按钮） |
| 32 | `.empty` | 图标 56 label4 + 标题 17 + 说明 15，距顶 28vh | `OTEmpty(title, text)` | 收件箱内联 |
| 33 | `.grouppill` | 32 高胶囊筛选项，选中蓝底白字 | `OTFilterPills` | 未用 |
| 34 | `.azindex` | 右侧竖排 6 字母，24×18 命中 | `OTAzIndex(letters, onPick)` | 联系人页已有 |
| 35 | `.statcard` / `.hbar` / `.chart` | 2 列网格卡；横条 6dp 高；柱状 92dp | `OTStatCard` / `OTBar` | 分析页内联 |
| 36 | `.chat` / `.bubble` | 气泡 18 圆角、己侧蓝底白字靠右 / 对方 bgInput 靠左 | `OTBubble` | AI 页内联 |

**统计**：需要新增的可复用组件 22 个（OTScreen/OTNavBar/OTNavButton/OTLargeTitle/OTSearchPill/OTTopBar/OTGroup/OTGroupHeader/OTGroupFooter/OTRow/OTRowIcon/OTMailRow/OTAvatar/OTChip/OTTag/OTSwitch/OTButton/OTField/OTTagField/OTActionBar/OTFab/OTSheet/OTToast/OTEmpty/OTFilterPills/OTAzIndex/OTStatCard/OTBubble/PullRefresh）。
一次性写 ~600 行，之后 14 个页面只拼装，不再各写样式。

---

## 3. 每屏布局规格（自上而下）

所有页面统一：`OTScreen`（bgGrouped）→ 状态栏 inset → 导航区 → 滚动区 → 底部 inset。

### 3.1 收件箱（`01-inbox`）
1. `OTTopBar`：头像 36（点击开抽屉）+ 搜索胶囊（占满剩余，内含右侧蓝色 AI 圆钮 34）
2. `OTLargeTitle("收件箱")` + 右上「N 未读」13pt 蓝字（**不是胶囊底**）
3. 列表：按 `createTime` 分「今天 / 昨天 / MM月DD日」→ 每组 `OTGroupHeader` + `OTGroup` 包 `OTMailRow`
4. 底部：「共 N 封 · 第 M 页」13pt 居中
5. `OTFab("✎")`

### 3.2 抽屉（`drawer`）
头部大圆头像 56 + 名称 17 + 邮箱 13 label2；账户列表（34 彩点 + 邮箱 + 右侧橙数字）；分组「邮箱」：收件箱/未读/已标星/草稿箱/已发送/垃圾邮件/已删除；分组「管理」：全部邮件/用户管理/权限控制/注册密钥/分析/系统设置；分组「其他」：联系人/AI 助手/设置；底部：显示发件人头像开关 + 退出登录（红）。

### 3.3 读信（`push`）
`OTNavBar(‹, 发件人, [☆, 🗑])` → 主题 20pt → 日期 13pt label2 → chip 行（分类）→ 白底正文（WebView shell：`padding 12px`、`font 17px/1.45`、`img max-width 100%`）→ 翻译行「译 翻译邮件 · 简体中文 ›」→ 底 `OTActionBar`（↩回复 ⇉全部 ↪转发 ✕删除红）。

### 3.4 写信（全屏 sheet）
头：取消(蓝) / 写邮件·回复(17 半粗居中) / 发送(蓝，发送中 label3) → `OTField("发件人")` → `OTTagField("收件人")` → 抄送/密送按钮行（15pt 蓝，右 `+`）→ `OTField("主题")` → 正文（撑满）→ 附件行 → 工具行（6 个 32dp 方块）。

### 3.5 设置（`03-profile`）
`OTLargeTitle("个人设置")` + 右上「完成」→ 账号抬头卡（56 头像 + 名称 + 邮箱 + `›`）→ `OTGroupHeader("账号与安全")` + 卡（用户名/邮箱/密码/两步验证[绿 chip 已开启]/强制两步验证[switch]）→ 通知卡 → AI 与翻译卡 → 第三方绑定卡 → 语言卡 → 危险卡（红字删账号）→ 管理组（草稿箱/全部邮件/用户/权限/注册密钥/分析/系统设置）。

### 3.6–3.14 其余
系统设置（9 张卡，每卡入口行 `OTRow` → push 到字段页）、全部邮件（`OTSearchPill` + 每行 3 行摘要 + 行内删）、用户管理（分组胶囊 + 行 + 操作）、权限（角色行 + switch 树）、注册密钥（等宽码 + chip）、分析（2×2 `OTStatCard` + 横条 + 柱状 + 说明）、联系人（搜索 + A–Z 分组 + 右侧索引）、AI 对话（气泡 + 动作回执 + 快捷胶囊 + 输入条）、404（图标 + 52pt 404 + 按钮）。

---

## 4. 落地顺序（按这个做，每步都编译通过）

1. `ui/theme/OT.kt` 补齐字阶 `lineHeight/letterSpacing` + 全部颜色/间距/圆角**常量类**
2. `ui/OTComponents.kt` 一次性写 §2 的组件（先 22 个核心：Group/Row/MailRow/SearchPill/NavBar/NavButton/Chip/Switch/Button/Field/Empty/Avatar/Toast/Sheet/ActionBar/Fab/LargeTitle/TopBar/Divider/Tag/Bubble/StatCard）
3. 收件箱 → 设置 → 系统设置 → 管理五页 → 详情 → 写信 → 登录/AI/联系人（**逐页替换**，每页与样张同屏截图对照）
4. 全站扫一遍 `Color(0xFF` 与 `Modifier.clickable(`（应只剩 `tapArea` 内部一处）

---

## 5. 现状与规范的差异清单（改代码时按此验收）

| # | 差异 | 判据 |
|---|---|---|
| 1 | 列表是**全宽行**，不是灰底浮白卡 | 收件箱/设置/系统设置/管理页四处 |
| 2 | 搜索框 56dp / 16dp 圆角 | 应为 36 / 10，且放真放大镜图标（现为字形） |
| 3 | 大标题与头像同一行、贴顶 | 标题独占一行，与搜索框间距 16 |
| 4 | 时间与星标挤一行 | 时间在右上，星标在时间下方独立列 36 |
| 5 | chip 是灰方块 | 20dp 胶囊、tint 底 |
| 6 | 头像全蓝 | 允许（数据全是 admin），但颜色函数要按名字哈希 |
| 7 | 顶栏 `↻` 刷新钮 | 设计里没有；下拉刷新未做前允许临时保留，但要移进抽屉或长按 |
| 8 | switch 用 Material | 51×31 自绘，轨道 on=green |
| 9 | 按钮用 Material Button/Chip | `OTButton` 四态 |
| 10 | toast 用 Snackbar | 底部黑胶囊 |
| 11 | 行高 85pt | 78 |
| 12 | 设置页行未缩进发丝线 | 卡内行 0 缩进、从卡左缘起 |

---

## 6. 明确不做的（避免又跑偏）

- 不做 Material 3 的 `TopAppBar`/`NavigationBar`/`Tab`/`Card` 默认外观（可以内部复用布局能力，外观必须由 `OT*` 决定）
- 不做动效炫技：只保留按压、push、sheet、回弹四类
- 不新增设计表里没有的控件（要加先更新本文档）
- 平板/横屏按用户要求暂缓

---

## 7. 验收方式

每个页面完成后与样张**同屏**截图对照（`F:\Desktop\MFiles\otsmail-android-shots\` 与 `F:\Desktop\MFiles\otsmail-ios-v2\shots\` 同名后缀），三项硬指标：
1. 结构一致（卡片/分组/发丝线位置能一一对上）
2. 尺寸一致（行高、搜索框、圆角、边距误差 ≤ 2dp）
3. 字阶一致（大标题/正文/说明三级能在两张图上对齐）
