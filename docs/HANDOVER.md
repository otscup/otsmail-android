# OTSMail Android 交接文档

> 核查时间：2026-11-08。范围：客户端 `otscup/otsmail-android`，服务端 `otscup/otsmail`。
>
> 本文只写「不在代码里、但一丢就会断」的东西：密钥、账号、云端资源、归档物、不能改的约束、当前版本状态与已知欠账。代码结构、构建命令、接口说明见 `README.md` 与 `docs/` 下的其它文档，这里不重复。

## 0. 交接总览：先拿齐这 8 样

| 顺序 | 拿什么 | 从哪拿 | 拿不到的直接后果 |
|---|---|---|---|
| 1 | Release keystore + 口令 | 本机 `E:\tools\keys\`，口令在 `keystore.properties` | 已发布的 `com.otscup.otsmail` 永远无法再升级 |
| 2 | GitHub 两个仓库的 owner 权限 + Actions secrets | GitHub `otscup/otsmail-android`、`otscup/gotify-plus` | Release CI 直接红，发不了正式包 |
| 3 | Cloudflare 账号（Worker / D1 / KV / R2 / Email Routing） | Cloudflare Dashboard | 线上收信、登录、附件全部中断，数据无法导出 |
| 4 | 域名注册商账号 + DNS 控制权 | 域名注册商；`otscup.com` 的 NS 当前在 Cloudflare | 邮箱地址、`mail.otscup.com`、OAuth 回调全部失效 |
| 5 | Worker secrets（`jwt_secret`、`EXT_CRED_KEY`、OAuth、Firebase、VAPID） | Cloudflare Worker `otsmail` → Settings → Variables and Secrets | 登录态、第三方邮箱凭据、推送、OAuth 登录无法恢复 |
| 6 | Firebase 项目 `otsmail-app` 的 Owner 权限 | Firebase Console → 项目设置 → 用户和权限 | FCM 推送无法维护；项目一旦删除，旧 token 全废 |
| 7 | Secrets Broker 条目 `gotify-fcm-firebase` | 自建 Secrets Broker（MCP / 审批流程） | 服务端 Firebase 服务账号私钥没有可读副本 |
| 8 | 每版 `mapping.txt` | 本机构建产物 + GitHub Actions artifact（仅 90 天） | 线上混淆崩溃堆栈永远还原不回可读符号 |

原则：任何口令、私钥、Token、cookie 只走加密渠道或密码管理器，**不要**写进仓库、Issue、聊天记录、截图、日志。

---

## 1. Release 签名 keystore

**是什么**

- `PKCS12` 格式，2730 B，别名 `otsmail`；keystore 文件 SHA-256（用于校验文件完整性）：`BF2416BB0AA493AB1DBB1E00BEB1B8C2828393552AF955D9C05EA57AF853A199`。
- 这是正式包 `com.otscup.otsmail` 的 release 签名证书，不是 debug 证书；Android 只认「同包名 + 同签名」的升级。
- 口令与别名存在 `keystore.properties`，四个字段：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`。

**存在哪**

- 主文件：`E:\tools\keys\otsmail-release.jks`
- 口令文件：仓库根 `keystore.properties` 与 `E:\tools\keys\keystore.properties`；两份内容 SHA-256 完全相同（`22920924C2BF55B96B039BB4178A62ED7179C2EBD13994FC5B088C7C73E1F5B0`），`storeFile` 都指向 `E:/tools/keys/otsmail-release.jks`。
- 备份：`E:\tools\keys\otsmail-release-20261108.bak.jks`，与主文件 SHA-256 完全相同，**同一目录、同一块磁盘，不算异地备份**。
- 两者都在 `.gitignore` 中（`keystore.properties`、`*.jks`、`*.keystore`），仓库里没有副本。

**怎么转移**

1. 用加密渠道（密码管理器条目 / 加密压缩包 / 面对面拷贝）把 `.jks` 和四个字段交给对方；口令不要和 `.jks` 放在同一个明文消息里。
2. 对方把 `.jks` 放到自己的路径，改 `keystore.properties` 的 `storeFile`，其余三个字段保持不变。
3. 用 `keytool -list -v` 校验别名与证书指纹，并与已发布 APK 的 `apksigner verify --print-certs` 输出比对（不要让口令进入 shell history）。
4. 重新设置 GitHub Actions 的 `KEYSTORE_BASE64` 等四个 secret；base64 可从本地文件生成，生成后立即删除临时文件。
5. 至少保留两份离线副本，其中一份放不同位置/不同账号；当前那份同目录 `.bak` 只能防误删，不能防磁盘或账号丢失。

**丢了会怎样**

- 同包名 `com.otscup.otsmail` 无法再发布升级包：签名不一致会被 Android 拒绝安装，已装用户收不到任何更新。
- 只能换 `applicationId` 重新发一个新应用；老用户、老数据、老评分/下载量全部带不过去。
- APK 里只有公钥证书，**无法从已发布 APK 反推出私钥**；没有可用后门。
- 只丢口令、`.jks` 还在，同样签不了；必须找到口令或放弃这条签名链。

---

## 2. GitHub Actions secrets

**是什么**

- GitHub 仓库级 Actions secrets，**只写不可读**：网页/API 只能看到名字，看不到值；交接不能「导出」，只能由新 owner 重新设置值。
- 客户端仓库 `otscup/otsmail-android` 已配置 5 个：
  - `KEYSTORE_BASE64`
  - `KEYSTORE_PASSWORD`
  - `KEY_ALIAS`
  - `KEY_PASSWORD`
  - `GOOGLE_SERVICES_JSON_BASE64`
- gotify 相关仓库实际是 `otscup/gotify-plus`（包名 `com.gotify.client`，不是 `otscup/gotify`），已配置 5 个：
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`
  - `GOOGLE_SERVICES_JSON_BASE64`
- 注意：`gotify-plus` 当前 `release.yml` **没有**解码/使用 `GOOGLE_SERVICES_JSON_BASE64`，构建脚本也没接 google-services 插件；这个 secret 是已配置但当前未使用，删除前先确认是否为预留。

**存在哪**

- GitHub → 仓库 → Settings → Secrets and variables → Actions。
- 值对应的原始材料：keystore 与口令见第 1 项；`google-services.json` 的本地副本在 `app/google-services.json`（670 B，未被 Git 跟踪），项目为 `otsmail-app`，包名 `com.otscup.otsmail`。

**怎么转移**

1. 先转移仓库所有权（GitHub → Settings → General → Transfer ownership），或让对方以新 owner 重建仓库并推送。
2. 新 owner 用原始材料重新 `gh secret set` / 网页设置；不要从旧仓库「复制值」，因为读不到。
3. base64 用本机命令生成，例如 PowerShell：
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("E:\tools\keys\otsmail-release.jks"))`
4. 设置后跑一次 `workflow_dispatch` 的 Release 工作流（只出 artifact、不建 Release）验证四个签名 secret 与 google-services secret 都能解码。
5. `gotify-plus` 的 4 个 keystore secret 是另一套签名材料，需单独确认来源；本地只找到 `otsmail` 那一套，未见 `gotify-plus` 对应的 `.jks` 副本。

**丢了会怎样**

- Release 流水线会在 `Decode keystore` / `Decode google-services.json` 步骤直接失败，发不了正式包；debug CI 不受影响。
- GitHub 不提供 secret 的历史值，旧值不可恢复；只能重新设置正确值。
- 如果连原始 keystore 也丢了，见第 1 项后果。

---

## 3. Secrets Broker 条目 `gotify-fcm-firebase`

**是什么**

- 自建 Secrets Broker 里的一条凭据，值是一个 JSON 字符串，包含：
  - `FIREBASE_CLIENT_EMAIL`
  - `FIREBASE_PRIVATE_KEY`
  - `FIREBASE_PROJECT_ID`
- 用途是给服务端 FCM 下发使用；属于 Firebase 服务账号材料。

**存在哪**

- Secrets Broker（自建服务，条目名 `gotify-fcm-firebase`）；不在客户端仓库、不在服务端仓库。
- 本地没有 `mail-worker/.dev.vars`，只有模板 `.dev.vars.example`；所以本机文件中没有这份 JSON 的可读副本。
- 本次未读取条目值，只核对名称与字段清单；交接时由持有 Broker 权限的人确认。

**怎么转移**

1. 把新负责人加入 Secrets Broker 的可用用户/审批链，或由原持有人通过 Broker 的导出/审批流程交付。
2. 也可以在新环境重新创建同名条目；值从 Firebase 控制台重新生成的服务账号 JSON 里取，不必依赖旧条目。
3. 无论哪种方式，值都不要写进仓库、`.dev.vars`、文档或聊天；`FIREBASE_PRIVATE_KEY` 只放 Cloudflare Worker secret。

**丢了会怎样**

- 不能从仓库恢复；只能到 Firebase 重新生成服务账号私钥，再更新 Worker secret。
- 已配置在 Worker 里的旧值继续有效，直到被覆盖或撤销；所以「Broker 丢了」通常不立即中断推送，但会失去唯一可读备份。
- 如果 Firebase 项目权限也丢了，则无法重新生成，FCM 推送只能宣告不可维护。

---

## 4. 每版 `mapping.txt`（R8 混淆映射）

**是什么**

- R8 产出的混淆映射，用于把线上崩溃堆栈里的 `a.a.a` 还原成类名/方法名/行号；没有它，线上崩溃只能看到混淆后的符号。
- 本次 release 构建产物实测 `46,139,730 B`（约 44.0 MiB；按十进制约 46.1 MB），量级与「45.9 MB」一致。

**存在哪**

- 本地：`app/build/outputs/mapping/release/mapping.txt`（每次 `assembleRelease` 覆盖）。
- CI：`release.yml` 把它复制成 `mapping-v<版本>.txt`，与 APK 一起上传到 artifact `otsmail-release`。
- **不随 GitHub Release 公开**（提交 `4aac70d` 起）；Release 资产只有 APK。
- 保留期：artifact 配置 `retention-days: 90`。GitHub 公开仓库的 artifact 保留上限是 **90 天**；私有仓库最高可设 400 天；`upload-artifact` 的 `retention-days` 输入默认上限也是 90，除非仓库设置放宽。**不存在「永久」选项**。

**怎么转移 / 归档**

1. 每次正式发版后，立即从 Actions run 下载 `otsmail-release` artifact，取出 `mapping-v<版本>.txt`。
2. 按 `versionCode` 归档到一个**私有**位置：私有 Git 仓库、私有对象存储（R2/S3）或内部制品库；不要放进公开仓库。
3. 同时保存 `sha256` 与版本清单（versionName / versionCode / commit / 构建时间），便于核对。
4. 不要只依赖 artifact：90 天后自动删除；也不要把「重新构建同 commit」当成归档替代，工具链/配置变化不保证映射完全一致。
5. 若日后上架 Google Play，还需同时保存 Play 控制台里的混淆映射上传记录（或每次随 AAB 上传）。

**丢了会怎样**

- 该版本的线上崩溃无法还原到可读代码位置，只能看到混淆符号；排障基本只能靠复现，不能靠堆栈。
- 旧版本映射无法用新版本替代；每个 versionCode 的映射只对那个包有效。
- 这是「事后无法补救」的归档物：包发布后，artifact 过期就真的没有了。

---

## 5. 服务端（`e:\DeepSeek workers\otsmail`）

**是什么**

- Cloudflare Worker `otsmail` + D1 `otsmail` + KV + R2 `mail-r2` + Email Routing + 自定义域名 `mail.otscup.com`。
- 一组 Worker secrets / vars，决定登录态、第三方邮箱凭据解密、OAuth 登录、Web Push、FCM 推送能否工作。
- 服务端域名：`otscup.com`、`atproxy.eu.org`（`wrangler.production.toml` 的 `domain` 数组）；`otscup.com` 的 NS 实测为 `brodie.ns.cloudflare.com` / `harlee.ns.cloudflare.com`，即 DNS 托管在 Cloudflare。

必须转移的服务端密钥：

| 名称 | 用途 | 丢了 / 换了会怎样 |
|---|---|---|
| `jwt_secret` | 签发/校验登录 JWT；附件签名 HMAC 也复用它 | 换掉 = 所有用户重新登录，已签发的附件链接失效；数据不丢。丢失后可用新随机值重建，但登录态会清空 |
| `init_secret` | 首次 `/setup` 初始化数据库 | 丢失后无法初始化**新**库；已有库不受影响。可重新生成，但旧初始化链接失效 |
| `EXT_CRED_KEY` | 第三方邮箱凭据的 AES-256-GCM 密钥（32 字节 base64） | 丢失/更换 = 已绑定第三方账户的密文全部无法解密，用户必须重新绑定；代码没有密钥版本/轮换机制，不能平滑换钥 |
| `VAPID_PRIVATE_KEY` | 浏览器 Web Push 私钥 | 丢失/更换 = 现有浏览器订阅失效，需要重新订阅；不影响 Android FCM |
| `FIREBASE_CLIENT_EMAIL` / `FIREBASE_PRIVATE_KEY` | FCM HTTP v1 服务账号 | 丢失 = Android 推送停；可从 Firebase 重新生成，见第 6 项 |
| `github_client_secret` / `google_client_secret` / `linuxdo_client_secret` | OAuth 登录 | 丢失/更换 = 对应第三方登录按钮不可用；需在原 OAuth 应用里重新生成，并保持回调 URL 一致 |
| `CF_ANALYTICS_TOKEN` | 可选，账户用量/分析 | 只影响分析页，不影响收发信 |
| `GITHUB_TOKEN` | 可选，提高 GitHub API 限额 | 只影响更新检查等辅助功能 |

OAuth 的 `*_client_id`、`*_callback_url` 以及开关（`github_switch`、`google_switch`、`linuxdo_switch`、`mcp_switch`）属于 Worker 变量，和 secret 一起交接。

**存在哪**

- 生产值：Cloudflare Dashboard → Worker `otsmail` → Settings → Variables and Secrets。
- 非敏感配置与资源 ID：本地 `mail-worker/wrangler.production.toml`（该文件在 `.gitignore`，不在 Git 里），内含 account_id、D1 database_id、KV id、R2 bucket、domain、FIREBASE_PROJECT_ID 等；交接时把这个文件作为机器可读清单一起给。
- 本地开发：没有 `mail-worker/.dev.vars`；只有 `.dev.vars.example` 模板，不能当生产凭据。
- 服务端仓库：`https://github.com/otscup/otsmail`，默认分支 `main`，HEAD `3e37e10`（2026-11-08）。**当前工作区有 17 项未提交变更**，交接前必须确认哪些已部署、哪些只在本机。
- 部署方式：以手工 `wrangler deploy` 为主；仓库内的 `deploy-cloudflare.yml` 最近多次运行失败，且仓库没有配置 Actions secrets，不能当作可用的一键部署。线上 Worker 版本与部署时间以 Cloudflare Dashboard 为准，`docs/deploy-log.md` 已落后于当前代码。

**怎么转移**

1. 首选在 Cloudflare 账号内给对方 Super Administrator 角色（或走账号所有权转移流程），而不是重建资源：Worker、D1、KV、R2、Email Routing、自定义域名一起跟着走。
2. 若必须迁移账号：先 `wrangler d1 export` 导出 D1；R2 用 `rclone`/`wrangler r2 object get` 同步；KV 逐 key 导出（至少覆盖登录/推送/设置相关前缀）；再按 `wrangler.production.toml` 重建绑定。
3. **Cloudflare Worker secret 设置后不可读取**（Dashboard/`wrangler secret list` 只显示名称），不能从后台「导出值」。交接必须由原持有人提供原始值，或从原始平台重新生成：
   - `jwt_secret` / `init_secret` / `VAPID_PRIVATE_KEY`：可以生成新值，但会分别导致全员重登 / 旧初始化链接失效 / 现有 Web Push 订阅失效；
   - `EXT_CRED_KEY`：**不能换**，换了等于所有已绑定第三方邮箱失效；本次未在本地找到它的可读副本（无 `.dev.vars`，`wrangler.production.toml` 不含），交接前必须确认原持有人手里有原文；
   - OAuth client secret、Firebase 私钥：可在原平台重新生成，更新后生效。
   部署后做冒烟：`GET /api/setting/websiteConfig` 200、登录、收一封测试信、附件下载。
4. 域名：在注册商处完成域名转移/过户，保留 Cloudflare NS；确认 Email Routing 的 catch-all 仍指向 Worker `otsmail`；`mail.otscup.com` 自定义域名重新绑定并签 TLS。
5. OAuth / OIDC：GitHub / Google / LinuxDO 的 OAuth 应用尽量迁到新 owner 名下；GitHub 个人 OAuth App 若不支持直接过户，就在新账号新建应用，并更新 Worker 的 `*_client_id`、`*_client_secret`、`*_callback_url`。企业 OIDC 的 provider 配置存在 D1 `setting.oidc_providers`，随 D1 一起迁移。

**丢了会怎样**

- 丢 Cloudflare 账号：Worker 停摆，`mail.otscup.com` 无法收信/登录，D1 邮件库与 R2 附件无法访问；用户端表现为服务器不可达，App 无法登录。
- 丢 D1：用户、邮件、设置、第三方账户密文全丢；`EXT_CRED_KEY` 单独留着也没有密文可解。
- 丢 R2：附件、网页静态资源丢失；已发出的邮件正文可能在，但附件下载 404。
- 丢域名/DNS：所有邮箱地址失效，OAuth 回调失效；已把 `mail.otscup.com` 录入为服务器地址的用户无法登录。App 本身不写死服务端地址，但换域名后每个用户都要重新录入并重新验证。
- 丢 `jwt_secret`：用户全部重新登录，附件旧链接失效；不丢数据。
- 丢 `EXT_CRED_KEY`：所有已绑定第三方邮箱必须重新绑定，且无法从密文恢复。
- 丢 OAuth client secret：对应第三方登录不可用，需在原平台重新生成。

---

## 6. Firebase 项目 `otsmail-app`

**是什么**

- Firebase 项目 `otsmail-app`，项目编号 `956020984687`，Android 包名 `com.otscup.otsmail`。
- 提供 FCM 推送；服务端用服务账号换取 OAuth2 access token，再走 FCM HTTP v1 下发。
- `google-services.json` 是客户端配置，不是服务端私钥；服务端私钥是服务账号 JSON。

**存在哪**

- Firebase Console → 项目 `otsmail-app` → 项目设置 → 服务账号 → 生成的私钥 JSON（只下载一次）。
- 服务端：`FIREBASE_CLIENT_EMAIL` / `FIREBASE_PRIVATE_KEY` 在 Worker secrets；另有一份在 Secrets Broker `gotify-fcm-firebase`。
- 客户端：`app/google-services.json` 本地存在（670 B），**未被 Git 跟踪**；`.gitignore` 明确忽略 `app/google-services.json`。仓库里没有这个文件。
- CI：`GOOGLE_SERVICES_JSON_BASE64`（Android 仓库）用于在构建时还原它。

**怎么转移**

1. 在 Firebase Console → 项目设置 → 用户和权限，把新负责人加为 Owner；或通过 Google Cloud 项目 IAM 转移所有权。
2. 在新环境「生成新的私钥」下载服务账号 JSON，取 `project_id` / `client_email` / `private_key`，更新 Worker secrets（旧 key 可在确认新 key 生效后撤销）。
3. 把本地 `app/google-services.json` 通过安全渠道交给对方，并同步更新 Android 仓库的 `GOOGLE_SERVICES_JSON_BASE64`。
4. 若对方另建 Firebase 项目：必须同时改 `applicationId`、`google-services.json` 的 `package_name`、服务端 `FIREBASE_PROJECT_ID` 与私钥——但这会触发第 7 项的「换应用」后果，不建议。

**丢了会怎样**

- 丢服务账号私钥：可重新生成，推送可恢复；旧私钥应撤销。
- 丢 Firebase 项目权限：无法生成新私钥、无法查看 FCM 配置；推送进入不可维护状态。
- 项目被删除：所有已登记 FCM token 失效，客户端要换新 `google-services.json` 并重新发版；服务端也要换项目 ID 与凭据。
- 丢 `google-services.json` 本身：可从 Firebase 控制台重新下载；只要项目还在就可恢复；若项目也没了，则同上。

---

## 7. 不可改的约束

**是什么**

- `applicationId = com.otscup.otsmail`（`app/build.gradle.kts`）。
- Release 签名证书（第 1 项）必须与已发布包一致。
- 演示包用 `.demo` 后缀（`-Pdemo=true`），与正式包并存；它不是正式包，不能拿来升级正式包。

**存在哪**

- `app/build.gradle.kts` 的 `defaultConfig.applicationId` 与 `signingConfigs.release`。
- 证书与口令见第 1 项。

**怎么转移 / 交接时怎么强调**

- 明确告诉接手人：**不要改 `applicationId`，不要换签名证书**。这不是代码风格问题，是应用身份问题。
- 若未来上架 Google Play：先决定是否加入 Play App Signing。加入后 Google 持有 app signing key，上传 key 可重置；但当前 GitHub 分发模式下，本地 keystore 就是 app signing key，丢了同样不可恢复。
- 任何「换包名重新发」的方案都要按新产品处理：新商店条目、新用户、新数据，老用户不会收到更新。

**丢了 / 改了会怎样**

- 改 `applicationId` = 换应用：已装用户收不到更新，本地数据不继承，FCM token/推送归属变化，商店评分与下载量归零。
- 换签名证书 = 同包名无法覆盖安装；用户必须先卸载（本地邮件缓存清空），且无法从旧版平滑升级。
- 两者都不是「配置错误」，而是不可逆的产品/发行决策。

---

## 8. 当前版本状态与发版流程

**是什么**

- 当前最新 tag：`v0.3.3`（2026-11-07T22:41Z 发布，本地时间为 11-08 早晨），Release 资产只有 `otsmail-0.3.3-release.apk`；`mapping-v0.3.3.txt` 只在 Actions artifact。
- 最新提交：`ae3826a chore: 发布 0.3.3（预发布）`；`v0.3.3` 的 Release 与 CI 均成功。
- GitHub Releases 页面的 Latest 指针仍停在 `v0.3.2`：`v0.3.3` 的 `isPrerelease=false` 但 `isLatest=false`；原因未见记录，交接时在 Releases 页手动确认/调整。
- 版本号规则：`versionCode = major*10000 + minor*100 + patch`；`0.3.3 → 303`。**只升不降**。
- 本地包：`<下一个正式版本>-dev.<序号>`，序号存 `.dev-version`（不入库）；dev 包与对应正式版共用同一个 `versionCode`，覆盖安装不触发降级。
- 发版入口：`.github/workflows/release.yml`，`push tag v*` 或 `workflow_dispatch`（手动档只出 artifact，不建 Release，且必须显式填版本号）。

**存在哪**

- tag 在 GitHub `otscup/otsmail-android`；发版说明在 `docs/release-notes/<版本>.md`；版本规则在 `app/build.gradle.kts` 顶部注释与 `release.yml`。

**怎么转移 / 发版步骤**

1. 发版前先把 `app/build.gradle.kts` 里的 `nextReleaseName` 推到下一个版本（当前仍是 `"0.3.3"`，下次应为 `0.3.4`）；否则本地 dev 包版本名会和已发布版重叠，虽然 `versionCode` 相同、覆盖安装能过，但容易混淆。
2. 写 `docs/release-notes/<版本>.md`，必须包含五段：`## 新增功能`、`## 修复`、`## 已知问题`、`## 安装提示`、`## 本版仍未经真机验证的项`；缺一段 CI 直接判红。
3. 提交代码，打 tag `vX.Y.Z`，推送 tag。
4. CI 会：解码 keystore 与 `google-services.json` → 构建签名 APK → 用 `apksigner`/`aapt` 断言包内版本等于 tag 版本、`versionCode` 等于规则值 → 强制要求 `mapping.txt` 存在（缺失直接失败）→ 上传 artifact（90 天）→ 建 Release（当前工作流写死 `PRERELEASE: "true"`，即默认发预发布；要正式发布需改成 `"false"`）→ 清理更旧的 Release，只保留最近 3 个，**tag 不删**。
5. 发完立即下载 mapping 归档，见第 4 项。

**丢了 / 搞错会怎样**

- tag 与包内版本不一致：CI 在 `Verify signature and version match tag` 判红，不会发出错版本。
- 缺 `mapping.txt`：CI 在 `Stage R8 mapping` 判红，拒绝发版；这是有意设计，不要绕过。
- 缺签名或 google-services secret：在 decode 步骤判红。
- 把 `versionCode` 改小：设备无法覆盖安装，老用户升级链断掉。
- `workflow_dispatch` 手动档不会建 Release，只出 artifact；想发正式版必须走 tag。

---

## 9. 已知未验证 / 已知缺陷

> 以下按 `docs/路线图-2026-11.md` 与 `docs/release-notes/0.3.1`~`0.3.3` 汇总。路线图的版本段已过期（还写着最后 tag `v0.2.5`、下一版 `0.2.6`），部分「已实现未验证」可能已在后续补验，交接时逐条复核，不要直接当现状。
>
> 2026-11 当前维护者的最新手动验证：详情页验证码复制已成功；通知栏复制尚未测；关闭第三方长连接后常驻通知/任务已确认消失。

**发布 / 工程**

| 项 | 状态 | 说明 |
|---|---|---|
| 扫码登录端到端 | 未验证 | 网页出码 → App 扫码确认 → 网页登录未真机走通；`/login/qr` 线上已确认 200 |
| 相机预览方向 / 对焦 / 暗光成功率 | 未验证 | 0.3.3 只通过 adb 日志确认帧能到、旋转不抛异常 |
| 提示音与震动 | 未验证 | 音量、时长、听感未验证 |
| 无效二维码提示 | 未验证 | 仅代码层确认文案与去重 |
| 扫码入口在窄屏/长邮箱下的排布 | 未验证 | 抽屉头像行右侧按钮 |
| 通知栏「复制验证码」 | 未验证 | 锁屏与通知折叠态下可见性/可点击性未测；详情页复制已验证可用 |
| 关闭第三方长连接后的服务停止 | 已真机验证 | 关掉长连接后常驻通知/任务消失（2026-11 实测）；功耗与长期残留未持续观察 |
| mapping 归档 | 有期限风险 | 只在 90 天 artifact 里，过期不可恢复 |

**推送 / 后台**

| 项 | 状态 | 说明 |
|---|---|---|
| 通知权限自愈 | 未验证 | 重装会把 `POST_NOTIFICATION` app-op 重置为关闭，应用内提示已加，但系统层仍需手动开启；横幅目视未验 |
| 跨通道 Message-ID 去重 | 未验证 | IDLE 与 FCM 同时触发同 Message-ID 只弹一条，需真机验证 |
| 无 GMS 机型电池白名单引导 | 未验证 | 设置页入口已做，点按复验被锁屏挡住 |
| 多设备 / 第二设备推送 | 未验证 | 当前只有一台真机 `R28M22RRZ6T`，没有常驻第二设备；FCM 多设备场景无法验证 |

**第三方邮箱（聚合收件）**

| 项 | 状态 | 说明 |
|---|---|---|
| Gmail 真实 AUTH 465 / 587 | 未验证 | TLS 修复与 587 回退只有代码与单测；无真实 587 provider，未真机验证 |
| 认证失败提示（535/534/530） | 仅单测 | 真实环境需改用户授权码，未做 |
| 附件发送 | 未验证 | 客户端限制单封 ≤10MB、总 ≤15MB，真实发送带附件未验 |
| 附件下载（IMAP 原文 + MimeParser） | 未验证 | 详情页重新 FETCH 原文解析，单附件 ≤10MB，未真机验证 |
| 外部邮箱回复 compose | 未验证 | otsmail 账户的新写/回复/回复全部/转发已验证；第三方详情胶囊存在，外部回复待验 |
| 删除到 Trash | 未验证 | 标记已读/星标已真机验证；删除到 Trash 待对自建测试件验证 |
| 外部 IMAP 其它文件夹同步 | 未验证 | 目前只保证收件箱 |
| 换机 / 重装补历史 | 未验证 | 服务端归档分页补回 + 7 天窗口回补，未在真机跑通 |
| P1 归档失败不推进游标 | 未验证 | 需设备侧注入归档失败复验 |
| P5 第 40 页停止 | 未验证 | 代码上限 `extPage < 40`（2400 行第三方 / 3320 行合并），滚动到上限的真机行为未复验 |

**功能 / 体验**

| 项 | 状态 | 说明 |
|---|---|---|
| 定时发送 | 已实现未验证 | 加密本地队列、AlarmManager、管理页、13 条单测完成；第三方账户入口已隐藏，避免走后端代发 |
| 规则分类 | 未开始 | 服务端有内置规则与 AI 分类，用户不能自定义 |
| AI 摘要 / 智能回复 / 润色 | 已实现未验证 | 接 `/ai/summary`、`/ai/reply`、`/ai/polish`；服务端未配 `aiApiKey` 时提示「AI 服务未配置」，成功返回路径未验证 |
| 跨设备一致 | 未开始 | 第三方凭据、已读/星标/删除、草稿均未随账号同步 |
| 窄屏 360–400dp 表格自适应 | 未验证 | 固定宽度清洗与表头折行未在真机/模拟器窄屏复验 |
| GitHub 通知 `Status` 折成 `Sta/tus` | 已知缺陷 | 列宽不足导致，不是右侧裁切；是否调列宽策略待评估 |
| 无障碍 | 未系统审计 | 文档中没有 TalkBack / 字体缩放 / 对比度专项验证记录 |

**服务端 / 运维**

| 项 | 状态 | 说明 |
|---|---|---|
| 服务端 CI 部署 | 不可用 | `deploy-cloudflare.yml` 最近多次失败，仓库没有 Actions secrets；实际以手工 `wrangler deploy` 为准 |
| 服务端工作区 | 未收口 | `e:\DeepSeek workers\otsmail` 有 17 项未提交变更；`docs/deploy-log.md` 落后于当前 HEAD |
| 线上版本记录 | 不完整 | 本地没有可靠的当前 Worker 版本记录，交接时必须在 Cloudflare Dashboard 确认 |

---

## 10. 交接时已发现的不一致（先确认再动手）

1. **路线图版本段过期**：`docs/路线图-2026-11.md` 写「最后正式 tag `v0.2.5`、下一版 `0.2.6`」，实际最新 tag 是 `v0.3.3`；以 GitHub tag 为准。
2. **`nextReleaseName` 过期**：`app/build.gradle.kts` 仍写 `"0.3.3"`，下次发版前改 `0.3.4`。
3. **Latest 指针未移动**：`v0.3.3` 不是 GitHub 的 Latest（仍指向 `v0.3.2`），页面手动确认。
4. **gotify-plus 的 google-services secret 未被使用**：工作流没有解码步骤，构建也没接 google-services 插件；先确认是否预留。
5. **服务端仓库未收口**：17 项未提交变更，且 CI 部署不可用；交接前先 `git status` 与线上版本对齐。
6. **本机设备状态过期**：路线图里的 `0.2.6-dev.15` / 206 是旧装机记录，当前正式版已到 0.3.3；设备验证记录不能直接复用。

---

## 附：交接验收（对方拿到后怎么证明能接上）

1. 用 keystore 签一个 release，`apksigner verify --print-certs` 的证书指纹与已发布 `otsmail-0.3.3-release.apk` 一致。
2. 触发一次 Android CI（debug）成功；再触发一次 `workflow_dispatch` Release，能产出 artifact（不建 Release）。
3. `wrangler secret list` 能看到 `jwt_secret`、`EXT_CRED_KEY`、`FIREBASE_CLIENT_EMAIL`、`FIREBASE_PRIVATE_KEY` 等名称；`wrangler deploy --dry-run` 能过。
4. 线上 `GET /api/setting/websiteConfig` 返回 200；用管理员账号登录、收一封测试信、下载一个附件。
5. 下载一个旧版本的 `mapping-v<版本>.txt`，用 R8 `retrace` 还原一条测试堆栈，确认映射有效。
6. 在 Firebase 控制台能看到项目 `otsmail-app`、包名 `com.otscup.otsmail`，并能「生成新的私钥」。
