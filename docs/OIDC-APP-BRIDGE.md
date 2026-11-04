# OIDC 登录：站点侧桥接交接

**目标**：Android 客户端用系统浏览器（Custom Tabs）走 OIDC，浏览器里的授权回调落在网页 `https://mail.otscup.com/login`，需要网页在**消费 code 之前**把 `code` / `state` 原样转回 `otsmail://oauth/callback`，App 才能完成 PKCE 换 token。

本文档只描述改动，不改站点仓库代码、不部署。

涉及两个文件：

- App：[LoginScreen.kt](file:///e:/DeepSeek%20workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/LoginScreen.kt)
- 站点：[index.vue](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue)

---

## 1. 两端对齐的事实（均已读代码确认）

### 1.1 App 端

**state 格式**

[LoginScreen.kt:230-231](file:///e:/DeepSeek%20workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/LoginScreen.kt#L230-L231)：

```kotlin
// nonce 末尾带 app 标记：站点回调页据此把 code 转回 otsmail://oauth/callback
val nonce = UUID.randomUUID().toString().replace("-", "") + "app"
```

`app` 标记在**生成 nonce 时**一次性拼上，之后 state 直接复用这个 nonce。

OIDC 分支（[LoginScreen.kt:236](file:///e:/DeepSeek%20workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/LoginScreen.kt#L236)）：

```kotlin
val state = (if (id.isEmpty() || id == "legacy") "oidc" else "oidc:$id") + ":$nonce"
```

→ OIDC 的 state 是 `oidc:<32位hex>app` 或 `oidc:<providerId>:<32位hex>app`。

非 OIDC 分支（GitHub / Google / linux.do，[LoginScreen.kt:249](file:///e:/DeepSeek%20workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/LoginScreen.kt#L249)）：

```kotlin
val state = "${p.key}:$nonce"
```

→ `github:<32位hex>app`、`google:<32位hex>app`、`linuxdo:<32位hex>app`。

**结论**：App 的每一种 state，最后一段都满足 `^[0-9a-f]{32}app$`（32 位小写 hex + 字面量 `app`，共 35 字符）。而网页端的 nonce 是纯 32 位 hex，不含 `app` —— 这是两端唯一可安全判定的分界点。

**回调 scheme / 主机 / 路径**

[AndroidManifest.xml:30-33](file:///e:/DeepSeek%20workers/otsmail-android/app/src/main/AndroidManifest.xml#L30-L33)：

```xml
<data
    android:scheme="otsmail"
    android:host="oauth"
    android:pathPrefix="/callback" />
```

→ 完整地址 `otsmail://oauth/callback`。

**参数名**：`code`、`state`（另读 `error_description` / `error`）。

**接收入口** [MainActivity.kt:39-44](file:///e:/DeepSeek%20workers/otsmail-android/app/src/main/java/com/otscup/otsmail/MainActivity.kt#L39-L44)：

```kotlin
private fun handleOAuthIntent(intent: Intent?) {
    val data = intent?.data ?: return
    if (data.scheme == "otsmail" && data.host == "oauth") {
        OAuthCallbackBus.post(data.toString())
    }
}
```

`onCreate` 与 `onNewIntent` 都会调用它；push 深链走 intent extras，与此无冲突。

**校验逻辑** [LoginScreen.kt:275-291](file:///e:/DeepSeek%20workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/LoginScreen.kt#L275-L291)：

```kotlin
val code = uri.getQueryParameter("code")
val state = uri.getQueryParameter("state").orEmpty()
val err = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
// 整串相等 + 最后一段必须就是本地 nonce，任一条不满足直接拒绝
if (state.isEmpty() || state != req.state || state.substringAfterLast(':') != req.nonce) {
    cancelOAuth()
    toast("登录校验失败（state 不一致），已拒绝本次授权")
    return
}
```

→ **站点必须原样回传 state**，不能改一个字符（App 做整串比对）。

授权上下文（含 PKCE verifier）在 [OAuthFlow.kt:44-83](file:///e:/DeepSeek%20workers/otsmail-android/app/src/main/java/com/otscup/otsmail/data/OAuthFlow.kt#L44-L83) 落盘，浏览器期间 App 被杀也能续上。

### 1.2 站点端

文件：[index.vue](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue)

**nonce 生成与 cookie** [index.vue:382-387](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L382-L387)：

```js
function oauthNonce() {
  const nonce = crypto.randomUUID().replaceAll('-', '')
  const secure = window.location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `oauth_nonce=${nonce}; Path=/; SameSite=Lax${secure}`
  return nonce
}
```

**网页端 state 拼接方式**（四种，均无 `app` 后缀）：

| provider | 行号 | state |
|---|---|---|
| linux.do | [389-394](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L389-L394) | `linuxdo:${nonce}` |
| GitHub | [396-401](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L396-L401) | `github:${nonce}` |
| Google | [403-408](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L403-L408) | `google:${nonce}` |
| OIDC | [410-426](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L410-L426) | `(providerId ? 'oidc:' + providerId : 'oidc') + ':' + nonce` |

**`oauthGetUser()`** [index.vue:434-509](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L434-L509)，关键点：

- [436-439](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L436-L439) 从 `new URLSearchParams(window.location.search)` 取 `code` / `state` / `error`
- [446](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L446) `const codeVerifier = takePkce(state || '')` —— **这里开始消费 code**
- [445-505](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L445-L505) `if (code) { ... }` 走 `loginFn` 换 token
- [507-508](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L507-L508) 最后 `history.replaceState` 清掉 query
- [432](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L432) 模块顶层直接调用 `oauthGetUser()`（函数声明提升，定义在后不影响）

**结论**：桥接必须插在 [439](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L439) 之后、[446](file:///e:/DeepSeek%20workers/otsmail/mail-vue/src/views/login/index.vue#L446) 之前。晚于 `takePkce` 就已经把 code 消费掉，网页登录会先拿到 token。

---

## 2. 改动补丁

**唯一改动文件**：`mail-vue/src/views/login/index.vue`
**唯一改动函数**：`oauthGetUser()`（当前第 434–509 行）
**无新增 import**，不需要调整函数顺序（`oauthGetUser` 本身就是函数声明）。

### 2.1 完整替换代码（整段替换 434–509 行）

```js
async function oauthGetUser() {

  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')
  const state = params.get('state')
  const oauthError = params.get('error_description') || params.get('error')

  // ---- App 桥接：state 末段是「32位hex + app」时，把 code/state 原样转回 Android 客户端 ----
  // 网页端 oauthNonce() 只生成纯 32 位 hex，永远不会命中；App 端固定多拼一个 app。
  const bridgeNonce = (state || '').split(':').pop() || ''
  if (/^[0-9a-f]{32}app$/.test(bridgeNonce)) {
    const q = new URLSearchParams()
    if (code) q.set('code', code)
    if (state) q.set('state', state)
    if (oauthError) q.set('error_description', oauthError)
    const target = 'otsmail://oauth/callback?' + q.toString()

    // 自定义 scheme 的自动跳转可能被 Chrome 按「无用户手势」拦下：先试一次，
    // 1.2 秒后若还停在当前页，就给一个可点击的链接兜底。
    window.location.replace(target)
    setTimeout(() => {
      if (document.getElementById('ots-app-bridge')) return
      const a = document.createElement('a')
      a.id = 'ots-app-bridge'
      a.href = target
      a.textContent = '返回 OTSMail 完成登录'
      a.style.cssText = 'display:block;margin:24px auto;padding:14px 22px;max-width:260px;' +
        'text-align:center;border-radius:10px;background:#111;color:#fff;' +
        'font:600 16px/1.4 -apple-system,BlinkMacSystemFont,sans-serif;text-decoration:none'
      document.body.appendChild(a)
    }, 1200)
    return
  }

  if (!code && oauthError) {
    ElMessage({message: oauthError, type: 'error', plain: true, duration: 6000})
  }

  if (code) {
    const codeVerifier = takePkce(state || '')

    const providerMap = { github: oauthGithubLogin, google: oauthGoogleLogin, linuxdo: oauthLinuxDoLogin, oidc: oauthOidcLogin }
    // state 形如 github / oidc:<id>（登录）或 bind-github / bind-oidc:<id>（个人设置里绑定）
    const isBindState = (state || '').startsWith('bind')
    const rawProvider = isBindState ? state.replace(/^bind-/, '') : (state || '')
    const [provider, ...providerIdParts] = rawProvider.split(':')
    const nonce = providerIdParts.pop() || ''
    const providerId = providerIdParts.join(':')
    const loginFn = providerMap[provider] || oauthLinuxDoLogin
    // OIDC 需要 provider/codeVerifier；GitHub/Google/LinuxDo 只取 code/state/nonce。
    const runLogin = () => provider === 'oidc'
      ? loginFn(code, state, providerId, codeVerifier, nonce)
      : loginFn(code, state, nonce)

    oauthLoading.value = true

    // v1.3 登录态绑定（大网站模式）：state=bind 时已登录用户把 OAuth 授权绑到当前账号
    if (isBindState) {
      runLogin().then(data => {
        // 授权落库但未绑定 → 绑到当前登录账号；这里绝不能用返回的 token 换会话（那是别人的账号）
        oauthBind({ oauthUserId: data.userInfo.oauthUserId, provider: data.userInfo.provider, bindToken: data.bindToken, nonce }).then(() => {
          ElMessage({ message: '绑定成功', type: 'success', plain: true })
          router.replace('/settings')
        }).catch(() => {
          router.replace('/settings')
        }).finally(() => {
          oauthLoading.value = false
        })
      }).catch(() => {
        oauthLoading.value = false
        router.replace('/settings')
      })
      return
    }

    runLogin().then(data => {

      bindForm.oauthUserId = data.userInfo.oauthUserId;
      bindForm.provider = data.userInfo.provider;
      bindForm.bindToken = data.bindToken || '';
      bindForm.nonce = nonce;

      if (!data.token) {
        showBindForm.value = true
        oauthLoading.value = false
        ElMessage({
          message: '请注册绑定一个邮箱',
          type: 'warning',
          duration: 4000,
          plain: true,
        })
        return;
      }

      saveToken(data.token);
    }).catch(() => {
      oauthLoading.value = false
    })
  }

  const cleanUrl = window.location.origin + window.location.pathname
  window.history.replaceState({}, '', cleanUrl)
}
```

**return 位置说明**：

- 桥接分支在 `takePkce()` 之前 `return`，因此不会消费 code、不会调 `loginFn`
- 原有网页分支（OIDC / GitHub / Google / linux.do / bind）完全不改，原样保留
- `if (!code && oauthError)` 的提示移到桥接之后：App 侧的失败授权也会带 `error_description` 转回 App，由 App 自己提示

### 2.2 最小 diff（unified）

```diff
--- a/mail-vue/src/views/login/index.vue
+++ b/mail-vue/src/views/login/index.vue
@@ -434,9 +434,37 @@
 async function oauthGetUser() {
 
   const params = new URLSearchParams(window.location.search)
   const code = params.get('code')
   const state = params.get('state')
   const oauthError = params.get('error_description') || params.get('error')
+
+  // ---- App 桥接：state 末段是「32位hex + app」时，把 code/state 原样转回 Android 客户端 ----
+  // 网页端 oauthNonce() 只生成纯 32 位 hex，永远不会命中；App 端固定多拼一个 app。
+  const bridgeNonce = (state || '').split(':').pop() || ''
+  if (/^[0-9a-f]{32}app$/.test(bridgeNonce)) {
+    const q = new URLSearchParams()
+    if (code) q.set('code', code)
+    if (state) q.set('state', state)
+    if (oauthError) q.set('error_description', oauthError)
+    const target = 'otsmail://oauth/callback?' + q.toString()
+
+    // 自定义 scheme 的自动跳转可能被 Chrome 按「无用户手势」拦下：先试一次，
+    // 1.2 秒后若还停在当前页，就给一个可点击的链接兜底。
+    window.location.replace(target)
+    setTimeout(() => {
+      if (document.getElementById('ots-app-bridge')) return
+      const a = document.createElement('a')
+      a.id = 'ots-app-bridge'
+      a.href = target
+      a.textContent = '返回 OTSMail 完成登录'
+      a.style.cssText = 'display:block;margin:24px auto;padding:14px 22px;max-width:260px;' +
+        'text-align:center;border-radius:10px;background:#111;color:#fff;' +
+        'font:600 16px/1.4 -apple-system,BlinkMacSystemFont,sans-serif;text-decoration:none'
+      document.body.appendChild(a)
+    }, 1200)
+    return
+  }
 
   if (!code && oauthError) {
     ElMessage({message: oauthError, type: 'error', plain: true, duration: 6000})
   }
```

> 行号以当前工作区文件为准；只按内容 apply 也能成功（上下文唯一）。

---

## 3. 部署与验证清单

### 3.1 部署前

- 在 `mail-vue` 目录跑 `npm run build`，确认通过（只改一个函数，无新依赖、无新 import）
- 回滚：`git checkout -- src/views/login/index.vue`（单文件回滚）；或反向 apply 上面的 diff
- 部署顺序无特殊要求；改的是静态前端，发布后强刷一次页面

### 3.2 部署后验证（按顺序）

1. **网页密码登录**：账号 + 密码（含两步验证）正常；URL 上没有 `?code=` 时不触发桥接
2. **网页 OIDC 登录**：在网页上点第三方登录，仍能正常换 token 进收件箱（state 末段不含 `app`，走原分支）
3. **网页 GitHub / Google / linux.do 登录**：同上
4. **App OIDC**：真机点第三方登录 → 浏览器授权 → 应自动回到 App 并进入收件箱
5. **state 篡改**：把 `state` 最后一段改掉再打开，App 应提示「登录校验失败（state 不一致），已拒绝本次授权」，且不发换 token 请求
6. **失败授权**：IdP 拒绝时，应回到 App 并提示错误，而不是停在网页

### 3.3 风险与边界

- **IdP 的 redirect_uri 仍是 `https://mail.otscup.com/login`**，所以桥接必须发生在网页消费 code 之前 —— 补丁已保证（在 `takePkce` 之前 return）
- **Chrome 可能拦截无手势的自定义 scheme 跳转**：已加 1.2 秒后的可点击兜底链接；若线上普遍被拦，可改为「只显示按钮、不自动跳」，改的是同一处
- **网页端不会误判**：判定条件要求末段为 `32位小写hex + app`，网页 nonce 永远不含 `app`。极端情况下用户在网页手工构造这种 state，只会把自己的浏览器跳到本 App，无安全影响
- **bridge 只转发，不校验**：真正的 state 校验在 App 侧做（整串比对 + nonce 比对），站点侧不需要也不应该再校验一次
- **绑定（bind）流程不涉及**：App 不发起 bind，桥接分支只可能命中 App 登录

### 3.4 替代方案（若 IdP 支持自定义 scheme）

如果 OIDC 提供商允许把 redirect_uri 注册成 `otsmail://oauth/callback`：

- 客户端直接用自定义 scheme 作 `redirect_uri`（[LoginScreen.kt:240](file:///e:/DeepSeek%20workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/LoginScreen.kt#L240) 的 `callbackUrl` 来源改成固定值）
- 服务端换 token 的 `redirect_uri` 必须与授权时一致（当前 `mail-worker/src/service/oauth-service.js` 写死 `provider.callbackUrl`，需要同步改）
- 此方案无需站点桥接，但改动了三处（App、Worker、IdP 注册），且 GitHub / Google 不支持自定义 scheme，无法统一

---

## 4. 给 DeepSeek 的一页话

**改哪里**：只改一个文件 `mail-vue/src/views/login/index.vue`，只改一个函数 `oauthGetUser()`（第 434–509 行，整段替换为文档第 2.1 节的代码）。

**加什么**：在函数开头、`takePkce()` 调用之前，插入「App 桥接」：取 `state` 最后一段，若匹配 `/^[0-9a-f]{32}app$/`，就把 `code` / `state` / `error_description` 原样拼成 `otsmail://oauth/callback?code=..&state=..`，用 `window.location.replace` 跳过去并 `return`；1.2 秒后若还停在当前页，再插入一个「返回 OTSMail 完成登录」按钮作为兜底。

**为什么**：Android 客户端改用系统浏览器做 OIDC（WebView 不支持 passkey），但 IdP 注册的回调地址是网页 `/login`。网页必须在 `takePkce()` 消费 code 之前把 code 交还 App，否则网页会先用掉 code，App 永远拿不到。`app` 后缀是两端区分「网页登录」和「App 登录」的唯一标记——网页端 nonce 是纯 32 位 hex，绝不带这个后缀。

**不能做什么**：不要动 `oauthNonce()`、四个 `*Login()` 函数、`createPkce` / `takePkce`；不要改 state 的任何字符（App 整串比对）；不要在桥接里自己校验 state；不要加新依赖。

**验收标准**：网页密码登录、网页 OIDC / GitHub / Google / linux.do 登录全部照旧；App 从浏览器授权后自动回到收件箱；篡改 state 时 App 提示校验失败且不发换 token 请求。
