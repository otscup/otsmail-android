# otsmail Android 客户端

Kotlin + Jetpack Compose，从零做，不 fork 旧的 `otsmail-android`。视觉照 `F:\Desktop\MFiles\otsmail-ios-v2\` 的样张，token 由 `ui/theme/*.kt` 承载（对应样张的 `tokens.css`）。

## 本机环境（全部在 E 盘）

| 东西 | 位置 |
|---|---|
| JDK 17（Temurin） | `E:\tools\jdk` |
| Android SDK | `E:\tools\android-sdk` |
| Gradle 用户缓存 | `E:\tools\gradle-home`（`GRADLE_USER_HOME`） |
| AVD/用户态 | `E:\tools\android-user`（`ANDROID_USER_HOME`） |
| 项目 | `E:\DeepSeek workers\otsmail-android` |

环境变量已写成用户级（新开终端生效）：`JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `ANDROID_USER_HOME` / `GRADLE_USER_HOME`。
当前 shell 里临时补齐：`$env:JAVA_HOME='E:\tools\jdk'` 等。

## 构建

```powershell
cd 'E:\DeepSeek workers\otsmail-android'
.\gradlew.bat assembleDebug          # 首次会下 AGP/Compose 依赖，约 300 MB
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

装到 USB 手机（先开「开发者选项 → USB 调试」）：

```powershell
& 'E:\tools\android-sdk\platform-tools\adb.exe' devices
& 'E:\tools\android-sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
```

## 现在有什么

- `MainActivity.kt`：收件箱骨架页（真样式，假数据），亮/暗两套 `@Preview`
- `ui/theme/Color.kt`：`tokens.css` 的 17 个颜色 1:1 映射 + 两档 tint（14/12% ↔ 26/24%）
- `ui/theme/Type.kt`：9 档字号/行高/字距（34/28/22/20/17/15/13/12/11）
- `ui/theme/Theme.kt`：`OTTheme` + `LocalOTColors/Type/Dimens`，页面只准从 `OT.*` 取 token

## 已验证（2026-10-29 16:56）

```
BUILD SUCCESSFUL in 13m 50s   35 tasks
app-debug.apk   9.0 MB   com.otscup.otsmail 0.1.0  minSdk 26  targetSdk 35  label OTSMail
```

首次构建 13m50s（要下 AGP/Compose 依赖到 `E:\tools\gradle-home`），之后增量约 1 分钟。`adb devices` 目前为空——等你插上手机开 USB 调试，我这边一条 `adb install -r` 就能装上。

## 已适配的页面（2026-10-29 17:05，全部照样张 + `OT.*` token）

| 文件 | 对应样张页 |
|---|---|
| `ui/LoginScreen.kt` | `05-login.html`（logo、服务器/邮箱/密码、登录） |
| `ui/InboxScreen.kt` | `01-inbox.html`（头像抽屉、搜索、邮件行、未读点、星标、加载更多、FAB） |
| `ui/DetailScreen.kt` | 读信 push 层（返回/星标/删除、WebView 渲染 HTML、附件名、回复/转发/移入垃圾） |
| `ui/ComposeScreen.kt` | `02-compose.html`（收件人、主题、正文、附件 ≤10 个 5MB、发送） |
| `ui/App.kt` | 路由：登录 → 收件箱 ↔ 详情 → 写信（没引 navigation 库） |

数据层 `data/Api.kt`（HttpURLConnection + org.json，零网络库）：`/login`、`/my/loginUserInfo`、`/email/list`、`/email/detail`、`/email/read`、`/star/add`、`/star/cancel`、`/email/spam`、`/email/delete`、`/email/send`、`/email/category-counts`、`/email/menu-counts`（形状都是本地 worker 实测过的）。
`data/Session.kt`：SharedPreferences 存 base / token / accountId / 用户名邮箱。

服务地址默认 `BuildConfig.BASE_URL` = `http://192.168.2.129:8789/api`（本机 WLAN），登录页可改并记住。

## 真机联调记录（Samsung SM-G9700 / Android 12，2026-10-29 18:3x）

- `adb install -r` 成功，冷启动无崩溃（`logcat -s AndroidRuntime:E` 全程为空）
- 用真账号登录 → 收件箱 **28 封真数据**（本地 worker 的 D1），今天/昨天分组、未读蓝点、星标、时间都对
- 点行进读信页：主题/发件人/时间/正文（WebView 渲染 HTML）/附件行/底部三按钮都在；点 ★ 真调 `POST /star/add`，返回列表后该行变实心星
- **手机上修掉的 4 个问题**：① 登录页没给状态栏留位（logo 压住时间）→ `.statusBarsPadding()`；② 收件箱顶栏同样压状态栏 → 根 Box 加 inset、内容加 `.navigationBarsPadding()`；③ 读信页顶栏压状态栏 → 同法；④ 读信正文原来是纯白块 → 改成整页白底 + 正文用 `<style>` 包一层（17px/1.45、`word-break`、图片 `max-width:100%`、`overflow-x:hidden`）
- **踩坑（横向，值得记住）**：手机开着移动数据时，Android 把 3G/4G 当默认网络，`192.168.2.129:8789` 连不上（报 `failed to connect ... from /10.62.155.119`）→ `adb shell svc data disable` 后 200。局域网调试必须走 Wi-Fi，或给 app 显式 `bindProcessToNetwork`（本轮没加，生产不需要）
- `adb exec-out screencap -p > x.png` 在 PowerShell 下写坏二进制，要用 `adb shell screencap -p /sdcard/x.png` + `adb pull`
## 联调通道：adb reverse（别再跟着 IP 跑）

PC 的 Wi-Fi 一掉线 IP 就变，BASE_URL 写死在 build.gradle.kts 里每次都要重装，太折腾。现在改成 ADB 反向隧道：

    adb reverse tcp:8789 tcp:8789          # 每次 USB 重连都要重跑这一条
    # app/build.gradle.kts: BASE_URL = "http://127.0.0.1:8789/api"

手机上的 127.0.0.1:8789 会被 adb 转到 PC 的 worker，和 Wi-Fi / 移动数据 / IP 全无关。已实测：登录 -> 收件箱 29 封。

**worker 起不来的坑**：wrangler dev 会 watch mail-worker/dist/，如果之前 npm run build 把网页版产物（含 dist/tinymce）放进去，会报 EBUSY: resource busy or locked, watch '...dist\tinymce\tinymce.d.ts' 然后直接退出。做法：跑 dev 前把 dist 挪成 dist.bak、留一个只有 index.html 的空 dist；要验构建产物时再换回来（别同时跑）。

## 按下反馈（2026-10-29 晚）

新增 ui/PressFeedback.kt 的 Modifier.pressable()：按下瞬间整块微缩 + 压深，90ms 线性到位，抬起才提交；关掉 Material 涟漪（indication = null），因为两层会叠。已接入：邮件行整卡、星标（同时把触控区从文字本身 ~20x26dp 扩到 44dp）、详情页底部回复/转发/移入垃圾。
星标改乐观更新：先本地翻，失败按 emailId 校验后回滚（原来要等 200~800ms 才变）。

## 转场与手势（2026-10-29 深夜）

`ui/SlidePage.kt`：详情/写信不再硬切，改成 push 式滑入 + 可拖拽返回。

- 进场 240ms `cubic-bezier(.2,0,0,1)`（跟样张 `--ease-ios` 同族），不是 Material 默认 300ms
- 拖拽期间跟手（每帧同步 dx），**抬手才决定**：超过 32% 屏宽淡出退出，否则临界阻尼 spring 回弹（不弹跳）
- 左缘留 28dp 透明热区做拖拽入口——WebView 是自绘的，会把落在它身上的横向手势全吃掉，只有这条热区稳定抢得到
- shortcut: 拖拽时底下的页面不跟着视差后移（iOS 会），要更强的层次感再补

**worker 起不来的第二个原因**：`npx wrangler dev` 挂在后台 job 里，job 一结束进程就没了，手机就报 `unexpected end of stream` / `timeout`。改成脱离 job 常驻：

    Start-Process -WindowStyle Hidden -FilePath cmd.exe -ArgumentList '/c','npx wrangler dev --config wrangler-dev.toml --port 8789 --local > E:\tools\wrangler-dev.log 2>&1' -WorkingDirectory 'E:\DeepSeek workers\otsmail\mail-worker'

日志落在 `E:\tools\wrangler-dev.log`，`Ready on http://0.0.0.0:8789` 之后手机就能连。


## 已完成的界面（2026-10-29 22:30）

| 界面 | 状态 | 数据来源 |
|---|---|---|
| 登录 | 完成 | POST /login + GET /my/loginUserInfo |
| 收件箱（收件箱/未读/已标星/已删除/垃圾） | 完成，抽屉切换真筛选 | GET /email/list?mailbox=、GET /star/list |
| 读信 | 完成（push 转场 + 左缘拖回） | GET /email/detail、PUT /email/read |
| 写信 | 完成（附件走 Base64 随 send 提交） | POST /email/send、POST /email/spam |
| 个人设置（签名 + 回车发送方式） | 完成，真落库 | PUT /my/setting |

**剩下的界面与批次计划**（每批都含构建 + 真机验证）：

- 批次 B（约 1 天）：AI 对话（POST /ai/command，含动作回执）、联系人（网页版是前端 IndexedDB，手机端要本地库）
- 批次 C（约 2 天）：管理页 6 个 —— 全部邮件、用户管理、权限控制、注册密钥、分析、MCP
- 批次 D（约 2 天）：系统设置 9 个子页（含 AI/DeepL Key 编辑）、空态与 404

**答案**：全部界面按上表还要 5 天左右（不含逐页真机打磨）；我每轮能做 2~4 页。

## 下一步

- 已知未验：写信发送全流程（UI 上还没走完一遍）、附件选择器、加载更多在真机上的手感；读信正文极短时左侧排版看着像被裁（4 字邮件，判断不了是气泡遮挡还是 WebView 偏移，需再看一封长邮件确认）

1. 手机插 USB 后跑接口联调（本目录 `adb install`，日志用 `adb logcat -s otsmail`）
2. 其余页面：个人设置、系统设置 9 张卡、联系人、MCP、管理 6 页
3. 系统能力：返回键、分享、附件下载、FCM 推送、深色跟随

