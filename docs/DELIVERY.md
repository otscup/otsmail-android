# OTSMail Android 交付说明（v0.2.0 / debug）

工程：`E:\DeepSeek workers\otsmail-android`（Kotlin + Jetpack Compose，`applicationId com.otscup.otsmail`，minSdk 26 / targetSdk 35，桌面名「OTSMail 新版」）。

## 交付物

| 交付物 | 位置 |
| --- | --- |
| APK（debug，17.0 MB） | `app\build\outputs\apk\debug\app-debug.apk` |
| 15 屏真机截图 | `F:\Desktop\MFiles\otsmail-android-shots\acc\` |
| 15 屏拼图 | `F:\Desktop\MFiles\otsmail-android-shots\contact-sheet.png` |
| 验收步骤 | `docs\ACCEPTANCE.md` |
| 进度与决策记录 | `docs\PROGRESS.md` |
| 后端上线清单 | `docs\BACKEND-DEPLOY.md` |

## 15 屏

登录、收件箱、抽屉、读信、写信、联系人、AI 助手、设置、系统设置、草稿箱、全部邮件、用户管理、权限控制、注册密钥、分析。

## 本轮（2026-11-01）改动

1. 抽屉删掉「退出登录」：其语义节点在真机上恒为 `bounds=[0,0][0,0]`，滚动到底也不可点；设置页底部本来就有同一入口，样张抽屉也只有 帐户/邮箱/分类/其他 四组。退出统一走「设置 → 退出登录」（提交 `2d2c0db`）。
2. 15 屏全部重拍（`tools/cap15.cjs`），抽屉单独等动画结束后截（`tools/cap-drawer.cjs`），登录页走「设置 → 退出登录」再重登（`tools/cap-login.cjs`）。
3. 全部邮件的删除键由文字「删」改为矢量图标（提交 `6764d35`）。

## 验证

- `gradlew assembleDebug` → BUILD SUCCESSFUL；`adb install -r` → Success（Samsung SM-G9700 / 1080×2280 / Android 12）。
- `node tools/local-audit.cjs` → files 27，encoding / hardColor / glyph 全 0。
- 真机回归：收件箱 17 封 / 12 未读、分类与邮箱组合筛选、帐户切换、长正文 + 4 列宽表格不撑破、暗色两屏、全部邮件尾页「已经到底了 · 共 29 封」。

## 已知未做 / 限制

1. 多端草稿同步：仅本地 `SharedPreferences`（`otsmail_draft`），未建后端草稿表。
2. VIP / 重要联系人行：后端无表无字段，v1 不做；有 `vip` 字段后复用抽屉尾部 21dp 槽位。
3. `/star/list` 不返回 `category`，「已标星」列表不显示分类 chip。
4. 截图左侧「ICB」圆球是手机里工行 App 的悬浮窗，与 App 无关；`am force-stop com.icbc` 只能短暂去掉。
5. 联系人页只有 1 条联系人，A–Z 索引跳转未实测。
6. `mail-worker` 的 7 个 bug 修复与 2 个新接口仍未部署，等确认后按 `docs/BACKEND-DEPLOY.md` 上线。
