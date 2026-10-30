# OTSMail Android — 施工单与交接（2026-10-30 21:50）

工程：`E:\DeepSeek workers\otsmail-android`（Compose，包名 `com.otscup.otsmail`）
后端：`E:\DeepSeek workers\otsmail\mail-worker`（本地 `wrangler dev` 8789 分端口，手机用 `adb reverse tcp:8789 tcp:8789`）

## 一、已完成（真机验证过）

- 组件库 `ui/OTComponents.kt`、`ui/OTMore.kt`：`OTGroup`(白卡 14dp)、`OTGroupHeader`(上 24 / 下 8 / 左 20)、`OTMailRow`、`OTNavBar`(矢量返回箭头)、`OTFieldRow`、`OTSwitchRow`、`OTActionBar`、`OTStat`、`OTLargeTitle`、`OTSearchPill`、`OTChip`、`OTAvatar`、`Modifier.pressable` / `Modifier.tapArea`、`OTSpace`(4/8/12/16/20/24/32/44 + cardGap=8)
- 14 屏：登录、收件箱、读信、写信、设置、联系人、AI 助手、系统设置（8 卡真读写）、全部邮件、用户管理、权限控制、注册密钥、分析、草稿箱
- 设置页「管理」组 7 行同构：30×30 圆角 7 彩色图标块 + 12dp + 文字起点 58dp + 发丝线从 58dp 起 + 矢量 `ChevronRight`
- 字符图标全部矢量化：星标、AI 钮（AutoAwesome）、写邮件 FAB（Edit）、空状态（Inbox）、导航返回（ChevronLeft）
- 交互：无涟漪按下压深/微缩 `pressable`、push 转场 `SlidePage`（左缘 28dp 热区可拖回）、星标乐观更新

## 二、下一步（按顺序，别插队）

1. `OTMailRow` 右列定宽：时间 44dp、星标 26dp；`Row(verticalAlignment = Alignment.Top)` 让发件人与时间基线对齐
2. 卡间距统一：组间 = `OTSpace.cardGap`（8）、组内 = 0。涉及 `InboxScreen`(现 8)、`SysSettingsScreen`(现 0/2/8 三档)、`AdminScreens`(现 0)、`SettingsScreen`(现 8)
3. 各页硬写的 `12.dp / 16.dp / 20.dp` 替换为 `OTSpace.s3 / s4 / s5`
4. 每步都跑：`assembleDebug` → `adb install -r` → 截图与 `F:\Desktop\MFiles\otsmail-ios-v2` 定稿样张对照

构建命令：
```powershell
$env:JAVA_HOME='E:\tools\jdk'; $env:ANDROID_HOME='E:\tools\android-sdk'
$env:ANDROID_SDK_ROOT='E:\tools\android-sdk'; $env:GRADLE_USER_HOME='E:\tools\gradle-home'
.\gradlew.bat --no-daemon assembleDebug
```

## 三、坑（都踩过，别再踩）

- 中文文件只能用 write/edit 工具；`Set-Content` / `Add-Content` 在 PowerShell 5.1 下会按 GBK 解码造成双重编码
- 不许用正则/截断脚本改 Kotlin 源文件（曾把整个界面函数删掉）
- 不许凭记忆写 `Api` 方法，先 grep 签名（曾写不存在的 `Api.accounts()`）
- 截图必须 `adb shell screencap -p /sdcard/x.png` + `adb pull`；`adb exec-out >` 在 PowerShell 会写坏 PNG
- 不要用 `adb shell input keyevent 4` 当返回（会退到桌面），点页面左上 `‹`
- `wrangler dev` 不要挂后台 job（会被回收），用隐藏窗口常驻；`mail-worker/dist` 里的 `tinymce` 会让 watch 报 `EBUSY`
- 装包后必须重跑 `adb reverse tcp:8789 tcp:8789`

## 四、后端改动未提交未部署（重要）

`E:\DeepSeek workers\otsmail` 工作区：我的改动 + 别人未提交的 `email-scroll` / `aside` / `setting-service` 等混在一起，部署前必须分清。

我的后端改动：
- `mail-worker/src/entity/user.js`：`signature`、`send_on_enter` 两列
- `mail-worker/src/api/my-api.js`：`POST /my/uploadImage`、`PUT /my/setting`
- `mail-worker/src/service/user-service.js`：`uploadImage`、`updateMySetting`、`loginUserInfo` 补两字段
- `mail-worker/src/service/att-service.js`：站内图发 buff、`mimeType` 兼容 `contentType`
- `mail-worker/src/service/star-service.js`：`toListText` 不存在导致的 500
- `mail-worker/src/service/r2-service.js` + `src/index.js`：`/attachments/**` 走 `publicObjResp`（KV/R2/S3 分派）
- `mail-worker/src/utils/jwt-utils.js`：`verifyToken` 对 `undefined` 兜底
- `mail-worker/src/i18n/zh.js`、`en.js`：三条新增文案
- `dev/migrate-mobile-settings.sql`：两条 ALTER（线上 D1 已执行）

## 五、验证脚本

- `dev/smoke-mobile.mjs`（21 项后端冒烟）、`dev/api-smoke.mjs`（53 项样张联网冒烟）
- `dev/web-interact.mjs`（15 项网页端交互回归）、`dev/web-walk.mjs`（17 路由巡检）
- 手机端目前只有手工截图验证，尚未写自动化用例（下一步可补 `androidTest`）
