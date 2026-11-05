package com.otscup.otsmail.ui

import android.net.Uri
import android.content.Intent
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.otscup.otsmail.R
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.ApiException
import com.otscup.otsmail.data.OAuthCallbackBus
import com.otscup.otsmail.data.OAuthPendingStore
import com.otscup.otsmail.data.OAuthRequest
import com.otscup.otsmail.data.OAuthTiming
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.ServerAddress
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

private fun hostOf(base: String): String = runCatching {
    val b = base.trim().let { if (it.contains("://")) it else "https://$it" }
    java.net.URI(b).host.orEmpty()
}.getOrDefault("")

/**
 * 登录页唯一的强调色：低饱和灰蓝（HSL 约 205°，饱和度 13%）。
 * 亮色 #5C6A76 对 bgGrouped #F2F2F7 约 4.9:1，暗色 #9BAAB8 对纯黑约 8.6:1，都过 WCAG AA。
 * 只用在链接、光标这类小面积元素；logo 与主按钮保持黑白灰，避免整页被品牌蓝压住。
 */
private val LoginAccentLight = Color(0xFF5C6A76)
private val LoginAccentDark = Color(0xFF9BAAB8)

@Composable
internal fun loginAccent(): Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) LoginAccentDark else LoginAccentLight

/** 中性主按钮：亮色黑底白字、暗色白底黑字；不使用品牌蓝，也不加阴影/渐变。 */
@Composable
internal fun LoginPrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
            .background(if (enabled) c.label else c.label3.copy(alpha = 0.28f))
            .tapArea { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = t.body.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) c.bgGrouped else c.label2,
        )
    }
}

@Composable
fun LoginScreen(session: Session, onLoggedIn: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 服务器地址只来自本机保存（首次启动由 ServerSetupScreen 写入），没有内置默认值
    var base by remember { mutableStateOf(session.base) }
    var domain by remember { mutableStateOf(session.loginDomain.ifEmpty { hostOf(base) }) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var serverSheet by remember { mutableStateOf(false) }
    var serverBusy by remember { mutableStateOf(false) }
    // 登录前可读的站点配置：注册开关、注册码策略、域名列表、第三方登录开关
    var config by remember { mutableStateOf<JSONObject?>(null) }
    var twoFactorCode by remember { mutableStateOf("") }
    var twoFactorNeeded by remember { mutableStateOf(false) }
    var registerOpen by remember { mutableStateOf(false) }
    var forgotOpen by remember { mutableStateOf(false) }
    val oauthStore = remember { OAuthPendingStore(context) }
    // 进程被杀后冷启动时，授权上下文从本地恢复（PKCE verifier 不能丢）
    var oauthPending by remember { mutableStateOf(oauthStore.load()) }
    // 浏览器里没拿到回调时的兜底 / 内置 WebView 授权页
    var oauthFallback by remember { mutableStateOf<OAuthRequest?>(null) }
    var oauthWeb by remember { mutableStateOf<OAuthRequest?>(null) }
    // 深链拿到 code 后到换 token/进收件箱之间：给一个可见的「正在登录…」，别让用户以为卡死
    var oauthBusy by remember { mutableStateOf(false) }
    // 阻塞遮罩：点第三方入口或 App 回到前台就开始计时，直到登录成功/失败/超时才解除
    var oauthBlocking by remember { mutableStateOf(false) }
    var oauthWaitSecs by remember { mutableIntStateOf(0) }
    // 同一个回调只换一次 token：Custom Tabs 关闭后再来一条深链时直接忽略
    var oauthExchanging by remember { mutableStateOf(false) }
    // Custom Tabs 会把 App 切到后台；回来时若还没收到回调就走兜底
    var tabOpen by remember { mutableStateOf(false) }
    var tabStopped by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** 拿到 token 后统一收口：写会话并通知外层进入收件箱。 */
    suspend fun applyLogin(token: String, fallbackEmail: String) {
        // 不能先落 token 再查资料：/my/loginUserInfo 失败时会把半登录态留在 prefs。
        // 先在内存里用这个 token 查 me()，成功后才写 Session（失败则回滚内存 token 并抛给调用方）。
        Api.token = token
        try {
            val me = Api.me()
            OAuthTiming.mark(context, "me-ok")
            session.token = token
            session.accountId = me.accountId
            session.name = me.name
            session.email = me.email.ifEmpty { fallbackEmail }
            session.signature = me.signature
            session.sendOnEnter = me.sendOnEnter
        } catch (e: Throwable) {
            Api.token = session.token
            throw e
        }
    }

    LaunchedEffect(base) {
        Api.base = base.trim()
        runCatching { Api.websiteConfig() }.onSuccess { cfg ->
            config = cfg
            // 服务端给了域名列表时用它做默认后缀（哪儿都不给才回退到服务器 host）
            if (session.loginDomain.isEmpty()) {
                val first = cfg.optJSONArray("domainList")?.let { a ->
                    if (a.length() > 0) a.optString(0).removePrefix("@") else ""
                }.orEmpty()
                if (first.isNotEmpty()) domain = first
            }
        }
    }

    fun submit() {
        if (loading) return
        val local = email.trim()
        if (local.isEmpty()) {
            error = "请输入邮箱账号"
            return
        }
        if (password.isEmpty()) {
            error = "请输入密码"
            return
        }
        val full = if (local.contains("@")) local else "$local@$domain"
        loading = true
        error = ""
        scope.launch {
            runCatching {
                session.base = base.trim()
                session.loginDomain = domain
                session.apply()
                val token = Api.login(full, password, twoFactorCode.trim())
                applyLogin(token, full)
            }.onSuccess {
                onLoggedIn()
            }.onFailure {
                // 服务端用 428 表示「密码已通过，还缺两步验证码」
                if (it is ApiException && it.httpCode == 428) {
                    twoFactorNeeded = true
                    error = it.message.ifEmpty { "请输入两步验证码" }
                } else {
                    error = friendlyError(it)
                }
            }
            loading = false
        }
    }

    /** 有 Custom Tabs 就用 Custom Tabs；没有就交给系统浏览器；连浏览器都没有才用内置 WebView。 */
    fun openInBrowser(req: OAuthRequest) {
        oauthFallback = null
        tabStopped = false
        tabOpen = true
        val uri = Uri.parse(req.url)
        // 1) 首选 Custom Tabs：复用用户已在用的浏览器，自带「返回 App」与更快的冷启动
        val pkg = runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()
        if (pkg != null) {
            val tabs = CustomTabsIntent.Builder().setShowTitle(true).build().apply { intent.setPackage(pkg) }
            if (runCatching { tabs.launchUrl(context, uri) }.isSuccess) {
                OAuthTiming.mark(context, "browser:custom-tabs")
                return
            }
        }
        // 2) 没有 Custom Tabs：普通的系统浏览器 Intent，不把授权页塞进 WebView
        val view = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(view) }.isSuccess) {
            OAuthTiming.mark(context, "browser:system")
            return
        }
        // 3) 设备上真没有浏览器：内置 WebView 兜底，并提前说明限制
        tabOpen = false
        // 内置授权页是 App 自己的 Dialog，遮罩会让它没法操作，这里解除阻塞
        oauthBlocking = false
        toast("设备上没有可用浏览器，已改用内置浏览器；内置浏览器不支持通行密钥")
        OAuthTiming.mark(context, "browser:webview")
        oauthWeb = req
    }

    /**
     * 发起第三方登录：授权页交给系统浏览器（Custom Tabs）。
     * Android WebView 不支持 WebAuthn，passkey 只有在 Chrome 这类浏览器里才可能被调起。
     */
    fun startOAuth(p: OAuthProvider) {
        // 遮罩期间/上一次授权还在途：直接忽略，避免连点发起两轮 OAuth
        if (oauthPending != null || oauthBlocking || oauthExchanging) return
        // 点入口就立刻进阻塞态：请求发出前先把页面锁住
        oauthBlocking = true
        OAuthTiming.start(context, "provider:${p.key}")
        scope.launch {
            // nonce 末尾带 app 标记：站点回调页据此把 code 转回 otsmail://oauth/callback
            val nonce = UUID.randomUUID().toString().replace("-", "") + "app"
            runCatching {
                if (p.key == "oidc") {
                    val cfg = Api.oidcConfig(p.providerId)
                    val id = cfg.optString("id")
                    val state = (if (id.isEmpty() || id == "legacy") "oidc" else "oidc:$id") + ":$nonce"
                    val verifier = randomVerifier()
                    val url = cfg.optString("authorizationEndpoint") + "?" + encodeQuery(
                        "client_id" to cfg.optString("clientId"),
                        "redirect_uri" to cfg.optString("callbackUrl"),
                        "response_type" to "code",
                        "scope" to cfg.optString("scope").ifEmpty { "openid profile email" },
                        "state" to state,
                        "code_challenge" to pkceChallenge(verifier),
                        "code_challenge_method" to "S256",
                    )
                    OAuthRequest(url, cfg.optString("callbackUrl"), "oidc", id, state, nonce, verifier)
                } else {
                    val state = "${p.key}:$nonce"
                    val url = p.authorize + "?" + encodeQuery(
                        "client_id" to p.clientId,
                        "redirect_uri" to p.callback,
                        "response_type" to "code",
                        "scope" to p.scope,
                        "state" to state,
                    )
                    OAuthRequest(url, p.callback, p.key, "", state, nonce, "")
                }
            }.onSuccess {
                oauthPending = it
                oauthStore.save(it)
                openInBrowser(it)
            }.onFailure {
                oauthBlocking = false
                toast(friendlyError(it))
            }
        }
    }

    fun cancelOAuth() {
        oauthPending = null
        oauthFallback = null
        oauthWeb = null
        oauthBlocking = false
        oauthBusy = false
        oauthExchanging = false
        oauthStore.clear()
    }

    /** 自定义 scheme 回调：校验 state 后拿 code 换 token（不打印 code / verifier）。 */
    fun handleOAuthCallback(u: String) {
        // 同一个回调可能来两次（浏览器关掉后深链又到一次）：已经换过 token 就不再换
        if (oauthExchanging) return
        val req = oauthPending ?: return
        OAuthTiming.mark(context, "callback-arrived")
        val uri = Uri.parse(u)
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state").orEmpty()
        val err = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        // 整串相等 + 最后一段必须就是本地 nonce，任一条不满足直接忽略：
        // 这是第三方页面能随便构造的伪造回调，既不弹提示，也不取消真正的 pending 授权。
        if (state.isEmpty() || state != req.state || state.substringAfterLast(':') != req.nonce) {
            return
        }
        if (code.isNullOrEmpty()) {
            cancelOAuth()
            toast(err ?: "第三方授权没有返回 code")
            return
        }
        OAuthTiming.mark(context, "callback-verified")
        oauthPending = null
        oauthStore.clear()
        oauthExchanging = true
        oauthBusy = true
        scope.launch {
            val token = runCatching {
                session.base = base.trim()
                session.apply()
                Api.oauthLogin(req.provider, code, state, req.nonce, req.providerId, req.verifier)
            }.onFailure { toast(friendlyError(it)) }.getOrNull() ?: run {
                oauthBusy = false
                oauthExchanging = false
                oauthBlocking = false
                OAuthTiming.finish(context)
                return@launch
            }
            OAuthTiming.mark(context, "token-exchanged")
            if (token.isEmpty()) {
                oauthBusy = false
                oauthExchanging = false
                oauthBlocking = false
                toast("该第三方账号还没绑定邮箱，请先在网页版完成绑定")
                OAuthTiming.finish(context)
                return@launch
            }
            runCatching {
                applyLogin(token, "")
                OAuthTiming.mark(context, "session-saved")
                oauthBusy = false
                oauthExchanging = false
                oauthBlocking = false
                onLoggedIn()
            }.onFailure {
                oauthBusy = false
                oauthExchanging = false
                oauthBlocking = false
                toast(friendlyError(it))
                OAuthTiming.finish(context)
            }
        }
    }

    // 阻塞遮罩的计时与超时：5s/15s 换文案，45s 必须解除阻塞给「重试/取消」
    LaunchedEffect(oauthBlocking) {
        if (!oauthBlocking) {
            oauthWaitSecs = 0
            return@LaunchedEffect
        }
        val started = android.os.SystemClock.elapsedRealtime()
        while (true) {
            oauthWaitSecs = ((android.os.SystemClock.elapsedRealtime() - started) / 1000).toInt()
            if (oauthWaitSecs >= 45) {
                // 超时：先解除阻塞，再把「重试/取消」交给兜底面板（正在换 token 时只提示可重试）
                oauthBlocking = false
                val req = oauthPending
                if (req != null) oauthFallback = req else error = "登录超时，请重试"
                break
            }
            kotlinx.coroutines.delay(250)
        }
    }

    // 冷启动且没带回回调：授权上下文还在，但浏览器那条路已经断了，直接给兜底选择
    LaunchedEffect(Unit) {
        if (OAuthCallbackBus.pendingUrl == null && oauthPending != null && !tabOpen) {
            oauthFallback = oauthPending
        }
    }
    // 自定义 scheme 回调由 MainActivity 投递到这里
    LaunchedEffect(OAuthCallbackBus.pendingUrl) {
        val u = OAuthCallbackBus.consume() ?: return@LaunchedEffect
        handleOAuthCallback(u)
    }
    // Custom Tabs 把 App 切到后台；回到前台还没拿到回调就走兜底
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (tabOpen) {
            tabStopped = true
            OAuthTiming.mark(context, "app-background")
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!tabOpen) return@LifecycleEventEffect
        OAuthTiming.mark(context, "app-foreground")
        tabOpen = false
        if (tabStopped && oauthPending != null) {
            // 回到前台立刻进阻塞态：授权可能已经在路上，别让用户继续点登录发第二轮
            oauthBlocking = true
            // 深链有时比 ON_RESUME 晚几十毫秒到：等一小会儿再弹兜底，别在成功路径上闪一个「未完成」
            scope.launch {
                kotlinx.coroutines.delay(1200)
                if (oauthPending != null && !oauthBusy) {
                    oauthFallback = oauthPending
                    // 兜底面板要能点，遮罩先撤
                    oauthBlocking = false
                }
            }
        }
        tabStopped = false
    }

    if (serverSheet) {
        OTSheet(onDismiss = { serverSheet = false }) {
            Text(
                "服务器",
                style = t.title3,
                color = c.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            OTFieldRow(
                label = "服务器地址",
                value = base,
                onValueChange = { base = it },
                hint = ServerAddress.HINT,
                number = false,
            )
            OTDivider(inset = 16)
            OTFieldRow(
                label = "登录域名",
                value = domain,
                onValueChange = { domain = it },
                hint = "otsmail.example.com",
            )
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                LoginPrimaryButton(if (serverBusy) "检查中…" else "保存", enabled = !serverBusy) {
                    when (val parsed = ServerAddress.parse(base)) {
                        is ServerAddress.Parsed.Fail -> toast(parsed.message)
                        is ServerAddress.Parsed.Ok -> {
                            serverBusy = true
                            scope.launch {
                                val problem = ServerAddress.verify(parsed.apiBase)
                                if (problem != null) {
                                    toast(problem)
                                } else {
                                    if (parsed.apiBase != session.base) {
                                        // 旧 token 属于旧服务器：换地址前先清掉登录态
                                        session.clear()
                                        domain = hostOf(parsed.apiBase)
                                    }
                                    session.base = parsed.apiBase
                                    session.loginDomain = domain
                                    session.apply()
                                    base = parsed.apiBase
                                    serverSheet = false
                                }
                                serverBusy = false
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgGrouped)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // 顶部右侧"服务器"入口：服务器地址与登录域名都收在这里
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(OT.dimens.rSm.dp))
                    .tapArea { serverSheet = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ot_gear),
                    contentDescription = null,
                    tint = c.label2,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("服务器", style = t.footnote, color = c.label2)
            }
        }
        // 头部：无彩色 logo + 大字号单色字标，整块左对齐，靠留白撑层级
        Column(
            Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 32.dp),
        ) {
            // 品牌图标：v2 超椭圆实底 + 反白 @，双色矢量，不能用 Icon（会被整体 tint）
            Image(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(22.dp))
            Text("OTSMail", style = t.largeTitle, color = c.label)
            Spacer(Modifier.height(8.dp))
            Text("安全、干净的私人邮箱", style = t.subhead, color = c.label2)
            Spacer(Modifier.height(4.dp))
            Text("登录到 ${hostOf(base)}", style = t.caption1, color = c.label3)
        }

        // 邮箱 + 密码卡
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OT.dimens.rCard.dp))
                .background(c.bgCard),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 9.dp)) {
                Text("邮箱账号", style = t.footnote, color = c.label2, modifier = Modifier.padding(bottom = 1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (email.isEmpty()) {
                            Text(if (domain.isEmpty()) "邮箱账号" else "name@$domain", style = t.body, color = c.label3)
                        }
                        BasicTextField(
                            value = email,
                            onValueChange = { email = it },
                            singleLine = true,
                            textStyle = t.body.copy(color = c.label),
                            cursorBrush = SolidColor(loginAccent()),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            OTDivider(inset = 16, strong = true)
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 9.dp)) {
                Text("密码", style = t.footnote, color = c.label2, modifier = Modifier.padding(bottom = 1.dp))
                BasicTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    textStyle = t.body.copy(color = c.label),
                    cursorBrush = SolidColor(loginAccent()),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 两步验证：服务端 /login 回 428 时才出现（网页端同款逻辑）
            if (twoFactorNeeded) {
                OTDivider(inset = 16, strong = true)
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 9.dp)) {
                    Text("两步验证码", style = t.footnote, color = c.label2, modifier = Modifier.padding(bottom = 1.dp))
                    BasicTextField(
                        value = twoFactorCode,
                        onValueChange = { twoFactorCode = it.filter { ch -> ch.isDigit() }.take(6) },
                        singleLine = true,
                        textStyle = t.body.copy(color = c.label),
                        cursorBrush = SolidColor(loginAccent()),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (error.isNotEmpty()) {
            Text(
                error,
                style = t.footnote,
                color = c.red,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        LoginPrimaryButton(if (loading) "登录中…" else "登录", enabled = !loading) { submit() }

        // 两个次级入口并排一行，不再上下堆叠，减少视线跳转
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "忘记密码？",
                style = t.footnote,
                color = loginAccent(),
                modifier = Modifier.tapArea { forgotOpen = true },
            )
            if (config != null && config?.optInt("register", 0) != 1) {
                Row(
                    Modifier.tapArea { registerOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("没有账号？", style = t.footnote, color = c.label2)
                    Text("注册", style = t.footnote, color = loginAccent())
                }
            }
        }

        val providers = oauthProviders(config)
        if (providers.isNotEmpty()) {
            // 或
            Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).height(1.dp).background(c.separator))
                Text("或", style = t.footnote, color = c.label3, modifier = Modifier.padding(horizontal = 12.dp))
                Box(Modifier.weight(1f).height(1.dp).background(c.separator))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { p ->
                    OidcButton(p.label, p.icon) { startOAuth(p) }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    if (forgotOpen) {
        ForgetPasswordSheet(onDismiss = { forgotOpen = false })
    }
    if (registerOpen) {
        RegisterSheet(
            config = config,
            onDismiss = { registerOpen = false },
            onDone = { mail ->
                email = mail
                registerOpen = false
                toast("注册成功，请用密码登录")
            },
        )
    }
    oauthWeb?.let { req ->
        OAuthWebDialog(
            url = req.url,
            callbackUrl = req.callback,
            onCallback = { handleOAuthCallback(it) },
            onCancel = { cancelOAuth() },
            onUseBrowser = { openInBrowser(req) },
        )
    }
    // 第三方登录阻塞遮罩：点入口或 App 回前台就出现，整页不可操作（含返回键），
    // 文案随等待时间升级；45 秒由上面的计时器解除并转成「重试/取消」，不会永远卡住。
    if (oauthBlocking) {
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(OT.dimens.rCard.dp))
                        .background(c.bgCard)
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        color = c.label2,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (oauthWaitSecs >= 5) "正在验证账号…" else "正在登录…",
                        style = t.body,
                        color = c.label,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            oauthWaitSecs >= 15 -> "连接较慢，请稍候"
                            oauthWaitSecs >= 5 -> "正在用授权的账号完成登录"
                            else -> "正在等待浏览器授权完成"
                        },
                        style = t.footnote,
                        color = c.label3,
                    )
                }
            }
        }
    }
    oauthFallback?.let { req ->
        OAuthFallbackSheet(
            onRetryBrowser = { openInBrowser(req) },
            onUseWebView = {
                oauthFallback = null
                oauthWeb = req
            },
            onCancel = { cancelOAuth() },
        )
    }
}

@Composable
private fun OidcButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
            .background(c.bgCard)
            .border(1.dp, c.separatorSoft, RoundedCornerShape(OT.dimens.rMd.dp))
            .tapArea { onClick() }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.label, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = t.body.copy(fontWeight = FontWeight.Medium), color = c.label)
    }
}

private data class OAuthProvider(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val clientId: String,
    val callback: String,
    val authorize: String,
    val scope: String,
    val providerId: String = "",
)

/** 第三方登录按钮按 /setting/websiteConfig 的开关动态生成，关掉的提供商不显示。 */
@Composable
private fun oauthProviders(cfg: JSONObject?): List<OAuthProvider> {
    if (cfg == null) return emptyList()
    val c = OT.colors
    val list = mutableListOf<OAuthProvider>()
    if (cfg.flag("githubSwitch") && cfg.optString("githubClientId").isNotEmpty()) {
        list += OAuthProvider(
            "github", "GitHub", BrandIcons.github(c.label),
            cfg.optString("githubClientId"), cfg.optString("githubCallbackUrl"),
            "https://github.com/login/oauth/authorize", "read:user",
        )
    }
    if (cfg.flag("googleSwitch") && cfg.optString("googleClientId").isNotEmpty()) {
        list += OAuthProvider(
            "google", "Google", BrandIcons.google(c.label),
            cfg.optString("googleClientId"), cfg.optString("googleCallbackUrl"),
            "https://accounts.google.com/o/oauth2/v2/auth", "openid email profile",
        )
    }
    if (cfg.flag("linuxdoSwitch") && cfg.optString("linuxdoClientId").isNotEmpty()) {
        list += OAuthProvider(
            "linuxdo", "LinuxDo", BrandIcons.linuxDo(c.label, c.bgCard),
            cfg.optString("linuxdoClientId"), cfg.optString("linuxdoCallbackUrl"),
            "https://connect.linux.do/oauth2/authorize", "openid profile email",
        )
    }
    // 企业 SSO：与网页端同一套判断——有服务商列表就全部展示（legacy 也展示），
    // 没有列表时按 oidcSwitch + oidcName 兜底。legacy 传 providerId=legacy 交给服务端 resolveOidcProvider。
    val arr = cfg.optJSONArray("oidcProviders")
    if (arr != null && arr.length() > 0) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            list += OAuthProvider(
                "oidc", o.optString("name").ifEmpty { "SSO" }, BrandIcons.shield(c.label),
                "", "", "", "", providerId = id,
            )
        }
    } else if (cfg.flag("oidcSwitch")) {
        list += OAuthProvider(
            "oidc", cfg.optString("oidcName").ifEmpty { "SSO 登录" }, BrandIcons.shield(c.label),
            "", "", "", "", providerId = "",
        )
    }
    return list
}

private fun JSONObject.flag(key: String): Boolean = optBoolean(key, false) || optInt(key, 0) == 1

private fun encodeQuery(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

private fun randomVerifier(): String {
    // PKCE verifier 必须是密码学随机数：Kotlin 的 random() 走的是普通伪随机，
    // 32 字节 SecureRandom 熵后编码成 64 个 URL 安全字符（RFC 7636 允许的字符集）。
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val rnd = java.security.SecureRandom()
    return (1..64).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}

private fun pkceChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
    return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
}

/** 服务端没有 /my/forgetPassword：如实说明，不假装发信。 */
@Composable
private fun ForgetPasswordSheet(onDismiss: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    OTSheet(onDismiss = onDismiss) {
        Text("找回密码", style = t.title3, color = c.label, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Text(
            "服务端暂时没有提供自助找回密码的接口，网页端也只有登录后的「修改密码」。请让管理员重置密码，或先用其他方式登录后在个人设置里改密码。",
            style = t.subhead,
            color = c.label2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RegisterSheet(config: JSONObject?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val t = OT.type
    val c = OT.colors
    val scope = rememberCoroutineScope()
    val regKeyMode = config?.optInt("regKey", 1) ?: 1 // 0 必填 / 1 关闭 / 2 可选
    val hideDomain = config?.optInt("loginDomain", 0) == 1
    val domains = config?.optJSONArray("domainList")?.let { a ->
        // 服务端给的是 "@example.com"，去掉前缀 @ 再本地拼
        (0 until a.length()).map { a.optString(it).removePrefix("@") }.filter { it.isNotEmpty() }
    }.orEmpty()
    val needVerify = config?.optInt("registerVerify", 1) == 0
    var addr by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    OTSheet(onDismiss = onDismiss) {
        Text("注册邮箱", style = t.title3, color = c.label, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        OTFieldRow(
            "邮箱账号", addr, { addr = it },
            hint = if (hideDomain || domains.isEmpty()) "name@example.com" else "账号前缀",
        )
        if (!hideDomain && domains.isNotEmpty()) {
            Text(
                "域名为 ${domains.joinToString(" / ")}",
                style = t.footnote,
                color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        OTFieldRow("密码", pwd, { pwd = it }, secret = true)
        OTFieldRow("确认密码", pwd2, { pwd2 = it }, secret = true)
        if (regKeyMode != 1) OTFieldRow("注册码" + if (regKeyMode == 2) "（可选）" else "", code, { code = it })
        if (needVerify) {
            Text(
                "当前站点开启了人机验证，需要验证码；App 未集成验证码，可能注册失败。",
                style = t.footnote,
                color = c.orange,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (err.isNotEmpty()) {
            Text(err, style = t.footnote, color = c.red, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            LoginPrimaryButton(if (busy) "注册中…" else "注册", enabled = !busy) {
                val local = addr.trim()
                if (local.isEmpty()) { err = "请输入邮箱账号"; return@LoginPrimaryButton }
                if (pwd.isEmpty()) { err = "请输入密码"; return@LoginPrimaryButton }
                if (pwd != pwd2) { err = "两次输入的密码不一致"; return@LoginPrimaryButton }
                if (regKeyMode == 0 && code.trim().isEmpty()) { err = "该站点注册必须填写注册码"; return@LoginPrimaryButton }
                val full = if (local.contains("@")) local else "$local@${domains.firstOrNull().orEmpty()}"
                busy = true
                err = ""
                scope.launch {
                    runCatching { Api.register(full, pwd, code.trim()) }
                        .onSuccess { onDone(full) }
                        .onFailure { err = friendlyError(it) }
                    busy = false
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 浏览器回来但没拿到回调（例如站点还没接入 App 回调桥）时的兜底选择。 */
@Composable
private fun OAuthFallbackSheet(
    onRetryBrowser: () -> Unit,
    onUseWebView: () -> Unit,
    onCancel: () -> Unit,
) {
    val t = OT.type
    val c = OT.colors
    OTSheet(onDismiss = onCancel) {
        Text(
            "第三方登录未完成",
            style = t.title3,
            color = c.label,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            "浏览器没有带回授权结果。可以重试，或改用内置浏览器继续登录——内置浏览器不支持通行密钥。",
            style = t.subhead,
            color = c.label2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            LoginPrimaryButton("用内置浏览器继续") { onUseWebView() }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "重新用浏览器授权",
                style = t.subhead,
                color = loginAccent(),
                modifier = Modifier
                    .clickable { onRetryBrowser() }
                    .padding(vertical = 12.dp),
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "取消",
                style = t.subhead,
                color = c.label2,
                modifier = Modifier
                    .clickable { onCancel() }
                    .padding(bottom = 16.dp),
            )
        }
    }
}

/**
 * 内置浏览器兜底：只有在设备完全没有浏览器时才走到这里。
 *
 * 之前的版本只拦 callbackUrl 前缀，站点用 `location.replace('otsmail://...')` 回传时
 * WebView 会去加载未知 scheme 并以 ERR_UNKNOWN_URL_SCHEME 白屏失败；现在自定义 scheme
 * 和回调前缀都拦，且额外用 onReceivedError 兜住"JS 导航没进 shouldOverrideUrlLoading"的情况。
 * 同时补上第三方 Cookie、UA、超时和失败态，任何一步失败都有可见文案，不会白屏。
 */
@Composable
private fun OAuthWebDialog(
    url: String,
    callbackUrl: String,
    onCallback: (String) -> Unit,
    onCancel: () -> Unit,
    onUseBrowser: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var navTick by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }

    // 每次导航重置 45s 计时：站点首屏要拉 ~2 MB 的 SPA 资源，短超时会误判
    LaunchedEffect(navTick) {
        if (failure != null) return@LaunchedEffect
        kotlinx.coroutines.delay(45_000)
        if (failure == null) {
            loading = false
            failure = "授权页加载超时（45 秒没有新进展）。请检查网络后重试，或改用系统浏览器打开。"
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(top = 44.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        // 去掉 WebView 的 "; wv" 与 "Version/x" 标记：部分 IdP 见到就拒绝
                        settings.userAgentString = browserLikeUa(settings.userAgentString.orEmpty())
                        // 第三方 Cookie：Google/GitHub 的授权链路要在多个域之间跳转
                        val cookie = CookieManager.getInstance()
                        cookie.setAcceptCookie(true)
                        cookie.setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                                if (newProgress >= 100) loading = false
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            fun deliver(u: String): Boolean {
                                val explicit = u.startsWith("otsmail://", ignoreCase = true)
                                val prefix = callbackUrl.substringBefore("?").takeIf { it.isNotEmpty() }
                                val matched = prefix != null && u.startsWith(prefix, ignoreCase = true)
                                if (explicit || matched) {
                                    onCallback(u)
                                    return true
                                }
                                return false
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                deliver(request.url.toString())

                            @Deprecated("WebView < 24 的回调")
                            override fun shouldOverrideUrlLoading(view: WebView, target: String): Boolean =
                                deliver(target)

                            override fun onPageStarted(view: WebView, target: String?, favicon: android.graphics.Bitmap?) {
                                loading = true
                                failure = null
                                navTick++
                            }

                            override fun onPageFinished(view: WebView, target: String?) {
                                loading = false
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (!request.isForMainFrame) return
                                val failedUrl = request.url.toString()
                                // 自定义 scheme 有时不以页面请求的形式回调 shouldOverrideUrlLoading，
                                // 而是直接当成加载失败；这里再兜一次，把 code 交回去。
                                if (failedUrl.startsWith("otsmail://", ignoreCase = true)) {
                                    onCallback(failedUrl)
                                    return
                                }
                                loading = false
                                failure = "授权页加载失败（${error.description}）。请检查网络后重试，或改用系统浏览器打开。"
                            }
                        }
                        loadUrl(url)
                        web = this
                    }
                },
                update = { },
                onRelease = {
                    web = null
                    it.destroy()
                },
            )
            Text(
                "取消",
                style = t.subhead,
                color = c.blue,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 10.dp)
                    .tapArea { onCancel() },
            )
            Text(
                "第三方登录",
                style = t.body.copy(fontWeight = FontWeight.SemiBold),
                color = c.label,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            )
            if (loading && failure == null) {
                // 细进度条：轨道 + 自主绘制的前景，避免依赖 M3 进度条的新旧签名差异
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = 42.dp)
                        .height(2.dp)
                        .background(c.separatorSoft),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((progress / 100f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(c.label2),
                    )
                }
            }
            when {
                failure != null -> Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        failure!!,
                        style = t.subhead,
                        color = c.label,
                    )
                    Spacer(Modifier.height(18.dp))
                    LoginPrimaryButton("重试") {
                        failure = null
                        loading = true
                        progress = 0
                        navTick++
                        web?.loadUrl(url)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "改用系统浏览器",
                            style = t.subhead,
                            color = loginAccent(),
                            modifier = Modifier.clickable { onUseBrowser() }.padding(8.dp),
                        )
                        Text(
                            "取消",
                            style = t.subhead,
                            color = c.label2,
                            modifier = Modifier.clickable { onCancel() }.padding(8.dp),
                        )
                    }
                }
                loading && progress == 0 -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = c.label2, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("正在加载授权页…", style = t.subhead, color = c.label2)
                }
            }
        }
    }
}

/** WebView 默认 UA 带 "; wv" 与 "Version/x.x"，很多 IdP 会识别成内嵌浏览器并拒绝授权。 */
private fun browserLikeUa(ua: String): String {
    if (ua.isEmpty()) return ua
    return ua.replace("; wv", "").replace(Regex("Version/\\d+(?:\\.\\d+)* "), "")
}
