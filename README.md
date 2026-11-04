# otsmail for Android

OTS Mail 的 Android 客户端。Kotlin + Jetpack Compose，Material 3，单 Activity，包名 `com.otscup.otsmail`。

后端不是本仓库：服务端（Cloudflare Worker + D1）与网页端在 [otscup/otsmail](https://github.com/otscup/otsmail)，本仓库只是它的手机壳。装好后登录你的自建实例即可使用。

## 功能

- 登录：账号密码、两步验证（TOTP）、OIDC 第三方登录（GitHub / Google / 自定义 IdP）。第三方授权在系统浏览器里完成，通行密钥（passkey）需要浏览器支持，内置 WebView 不支持
- 收件箱：收件箱 / 未读 / 已标星 / 已删除 / 垃圾邮件，分类筛选，下拉刷新，分页加载
- 邮件：HTML 正文渲染、附件列表、星标、已读未读、删除、移入垃圾、多选批量操作、左右滑动快捷操作
- 写信：多收件人、抄送、附件（单个 ≤ 5 MB，最多 10 个）、草稿保存
- 搜索：按关键词搜索服务端邮件
- 推送：FCM 通知，点通知直接进对应邮件
- AI：自然语言命令（汇总未读、搜索、整理等），结果以动作回执呈现
- 其它：深色模式跟随系统、账号切换、签名与「回车发送」设置

## 普通用户：下载安装

从 [Releases](https://github.com/otscup/otsmail-android/releases/tag/v0.2.0) 下载 `otsmail-0.2.0-release.apk`，传到手机点击安装。首次安装需要在系统设置里允许「安装未知来源应用」。

下载入口也提供在 [otscup/otsmail 的 Releases](https://github.com/otscup/otsmail/releases/tag/android-v0.2.0)（同一个安装包，与服务端/网页版放在一起）。

系统要求：Android 8.0（API 26）及以上。

> 签名是固定的。后续版本直接覆盖安装即可；如果从别处装过同包名的包，会因签名不一致而装不上，需要先卸载（会清掉登录态）。

## 自托管：从零跑起来

### 1. 先部署后端

按 [otscup/otsmail](https://github.com/otscup/otsmail) 的说明部署 Worker 与网页端，拿到你的 API 地址（形如 `https://mail.example.com/api`）。本仓库不重复那套部署细节。

### 2. 构建 APK

需要 JDK 17、Android SDK Platform 35。Gradle 用仓库自带的 wrapper（8.11.1），不用另装。

```bash
# 调试包
./gradlew assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk

# 发布包（需先配签名，见下）
./gradlew assembleRelease
# 产物 app/build/outputs/apk/release/app-release.apk
```

发布签名：自己生成一个 keystore，**放在仓库外**，然后在仓库根新建 `keystore.properties`（该文件已被 `.gitignore` 忽略，不要提交）：

> CI 只自动构建 **debug 包**（见 `.github/workflows/android.yml`）。release 包目前仍由本地手工签名构建，keystore 不进仓库、不进 Secrets。

```bash
keytool -genkeypair -v -keystore ~/keys/otsmail-release.jks \
  -alias otsmail -keyalg RSA -keysize 2048 -validity 10000
```

```properties
# keystore.properties
storeFile=/绝对路径/otsmail-release.jks
storePassword=你的口令
keyAlias=otsmail
keyPassword=你的口令
```

没有这个文件时 `assembleRelease` 仍会出包，但用的是 debug 签名，不能用于正式分发。

> **务必备份 keystore**。密钥丢了就无法给已安装用户推送覆盖升级，只能换包名重发。

后端地址写死在 `app/build.gradle.kts` 的 `BASE_URL`（默认 `https://mail.otscup.com/api`）。自建实例改这一行，或装好后在登录页的「服务器」里改（会记在本地）。

### 3. Firebase 推送（可选）

**不配 Firebase 也能正常收发邮件，只是没有推送。** 没有 `app/google-services.json` 时 `google-services` 插件不会被应用，APK 照常构建。

要开推送：

1. Firebase 控制台新建项目 → 添加 Android 应用，包名必须填 `com.otscup.otsmail`
2. 下载 `google-services.json`，放到 `app/google-services.json`（已被 `.gitignore` 忽略，不入库）
3. 在 Worker 侧配置服务账号凭据（下一节）

### 4. Worker 的三个 secret

推送由服务端通过 FCM HTTP v1 发出，需要 Firebase 服务账号。控制台「项目设置 → 服务账号 → 生成新的私钥」，下载的 JSON 里取三个值，用 wrangler 写进 Worker（不要写进 `[vars]`，那会进 git）：

```bash
npx wrangler secret put FIREBASE_CLIENT_EMAIL   # JSON 里的 client_email
npx wrangler secret put FIREBASE_PRIVATE_KEY    # JSON 里的 private_key，整段 PEM，含 BEGIN/END
npx wrangler secret put FIREBASE_PROJECT_ID     # JSON 里的 project_id；代码默认 otsmail-app
```

`FIREBASE_PRIVATE_KEY` 贴整段 PEM（`-----BEGIN PRIVATE KEY-----` 到 `-----END PRIVATE KEY-----`）。如果粘贴工具把换行变成了 `\n` 字面量，不用手动改，服务端会自行还原。

### 5. D1 建表

推送设备表。Worker 首次初始化时会自动建（`src/init/init.js` 的 `otscmail_28DB`），也可以手动执行：

```sql
CREATE TABLE IF NOT EXISTS push_device (
  push_device_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id        INTEGER NOT NULL,
  token          TEXT NOT NULL,
  platform       TEXT NOT NULL DEFAULT 'android',
  device_id      TEXT NOT NULL DEFAULT '',
  app_version    TEXT NOT NULL DEFAULT '',
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  last_error_at  DATETIME
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_push_device_token ON push_device(token);
CREATE INDEX IF NOT EXISTS idx_push_device_user ON push_device(user_id, platform);
```

### 常见坑

- **没有推送但能收信**：检查 `app/google-services.json` 是否存在、Worker 三个 secret 是否都设置、手机是否授予通知权限。
- **FCM 成本**：FCM 本身免费、不限条数。真正的成本在 D1 —— 每次推送要读 `push_device` 表，量大时关注 D1 读额度。
- **第三方登录回不来**：OIDC 的回调地址必须能跳回 `otsmail://oauth/callback`。若挂在网页端 `/login` 上，需要站点侧把 `code` 转发给这个 scheme，否则浏览器会停在后端页面。
- **签名不一致**：从 debug 包切到 release 包必须先卸载，`INSTALL_FAILED_UPDATE_INCOMPATIBLE` 就是签名不同。

## 开发

### 目录

```
app/src/main/java/com/otscup/otsmail/
├── MainActivity.kt          单 Activity：Compose 入口 + 深链分发（push / OAuth 回调）
├── data/                    数据层（无第三方网络库，HttpURLConnection + org.json）
│   ├── Api.kt               全部 HTTP 接口与错误映射
│   ├── Session.kt           账号、服务器地址、token 的本地持久化
│   ├── MailCache.kt         列表内存快照 + SQLite 缓存，保证进出详情不丢滚动位置
│   ├── Drafts.kt            草稿本地存取
│   ├── PushManager.kt       FCM token 获取与设备登记（失败静默，不阻塞界面）
│   └── OAuthFlow.kt         OAuth 待处理请求 + 回调总线（跨进程存活）
├── push/                    FCM 接收、通知构建、去重、深链路由
└── ui/                      Compose 页面与组件
    ├── App.kt               路由（登录 → 收件箱 ↔ 详情 / 写信，未引 navigation 库）
    ├── LoginScreen.kt       账号密码 / 2FA / OIDC（Custom Tabs）
    ├── InboxScreen.kt       收件箱与筛选
    ├── DetailScreen.kt      HTML 正文 WebView、附件、星标
    ├── ComposeScreen.kt     写信与附件
    ├── AiScreen.kt          AI 命令
    ├── theme/               颜色、字号、间距 token
    └── motion/              滑动手势与转场
```

### 关键模块

| 模块 | 一句话 |
|---|---|
| `Api` | 所有后端接口的唯一出口，统一超时、错误文案与登录态失效处理 |
| `MailCache` | 列表快照按「账号 + 邮箱夹 + 关键词」聚合，详情返回后列表和滚动位置原样恢复；配 SQLite 做离线缓存 |
| `PushManager` | 取 FCM token 并向 `/push/device` 登记；任何失败只写日志，绝不弹错 |
| `OAuthFlow` | 把 PKCE verifier 与 state 存进 SharedPreferences，等浏览器返回时校验，防止进程被杀后授权丢失 |
| `MailSync` | 全局版本号，任何落库操作成功后 +1，列表据此自动重拉，不用退出重进 |

### 构建环境（已实测）

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.11.1（wrapper） |
| AGP | 8.7.2 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |

`assembleRelease` 若卡在 `lintVitalAnalyzeRelease`，可加 `-x lintVitalRelease` 跳过（本机在 Windows + 特定 AGP 组合下会长时间无进展）。

## 许可

MIT License，全文见 [LICENSE](LICENSE)。Copyright (c) 2026 OtsCup。
