package com.otscup.otsmail.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.BuildConfig
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.AuthState
import com.otscup.otsmail.data.Demo
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.data.MailDetail
import com.otscup.otsmail.data.PushManager
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.push.PushRouter
import com.otscup.otsmail.ui.theme.OT
import com.otscup.otsmail.ui.theme.OTTheme
import kotlinx.coroutines.launch

private sealed interface Route {
    data object ServerSetup : Route
    data object Login : Route
    data object Inbox : Route
    data class Detail(val id: Long) : Route
    data class Compose(
        val to: String,
        val subject: String,
        val quoted: String,
        /** 回复全部带入的原 cc（新写/回复时为空） */
        val cc: List<String> = emptyList(),
        /** 写信页标题：空=写邮件/回复，replyAll=回复全部，forward=转发 */
        val mode: String = "",
        /** 从草稿箱继续编辑时带上草稿 id；新写邮件为 null */
        val draftId: String? = null,
        /** 行内右滑回复：原文还没缓存时先开页，由 ComposeScreen 后台补引用 */
        val quoteEmailId: Long? = null,
    ) : Route
    data object Settings : Route
    data object Contacts : Route
    data object Ai : Route
    data object SystemSettings : Route
    data object ServerSettings : Route
    data object AllMail : Route
    data object Drafts : Route
    data object Users : Route
    data object Roles : Route
    data object RegKeys : Route
    data object Analysis : Route
}

@Composable
fun OtsApp() {
    val context = LocalContext.current
    // 列表/详情缓存要在 Session 之前初始化：退出登录时会清空它
    MailCache.init(context.applicationContext)
    val session = remember {
        Session(context).also {
            it.apply()
            // 演示构建：不填服务器地址、不登录，直接进入本地虚构数据的收件箱
            if (BuildConfig.DEMO) {
                it.base = "https://demo.local/api"
                it.token = Demo.TOKEN
                it.loginDomain = "demo.local"
                it.accountId = Demo.accounts[0].id
                it.email = Demo.accounts[0].email
                it.name = "Demo Lin"
                it.signature = "— 发自 OTSMail 演示账户"
                // 当前账户头像用演示构建自带的合成人像（正式包没有这张资源，返回空串）
                it.avatarLocal = Demo.avatarDataUrl(context)
                Api.base = it.base
                Api.token = it.token
            }
        }
    }
    // 旧版本允许保存 http:// 地址；明文流量已被全局禁用，这里把这类用户直接带到改地址页，
    // 而不是让他们在收件箱里看一串「网络错误」。
    val legacyInsecureBase = remember { session.base.startsWith("http://", ignoreCase = true) }
    var route by remember {
        mutableStateOf<Route>(
            when {
                // 没有保存过服务器地址：先走引导页，用户填完并通过校验才进登录页
                session.base.isEmpty() -> Route.ServerSetup
                legacyInsecureBase -> Route.ServerSetup
                session.token.isEmpty() -> Route.Login
                else -> Route.Inbox
            },
        )
    }
    // 路由返回栈：系统返回/左缘横滑统一回到上一层，而不是直接退出到桌面
    val backStack = remember { androidx.compose.runtime.mutableStateListOf<Route>() }
    // 写信页自动保存产出的草稿 id：401 被拦去登录后，靠它把写信页原样接回来
    var composeDraftId by remember { mutableStateOf<String?>(null) }
    fun go(next: Route) {
        backStack.add(route)
        route = next
    }
    fun goBack() {
        route = backStack.removeLastOrNull() ?: Route.Inbox
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 401 已经统一显示顶部提示，friendlyError 对 401 返回空串，这里再兜一层空消息
    val toast: (String) -> Unit = { msg -> if (msg.isNotEmpty()) scope.launch { snackbar.showSnackbar(msg) } }
    // 收件箱/登录页是根：在根上按返回才退出到桌面
    androidx.activity.compose.BackHandler(
        enabled = route != Route.Inbox && route != Route.Login && route != Route.ServerSetup,
    ) { goBack() }

    // 登录后空闲时预热 WebView 引擎：第一次建 WebView 约 0.9~1.4s，不预热就全算在开信耗时里
    LaunchedEffect(session.token.isNotEmpty()) {
        if (session.token.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        WebViewPrewarm.prewarm(context)
    }
    // 从详情页返回列表后，详情把预热实例取走了：回到列表空闲时再补一个，下一封同样快
    LaunchedEffect(route) {
        if (route !is Route.Inbox) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        WebViewPrewarm.prewarm(context)
    }

    // Android 13+ 通知权限：首次登录后问一次，拒绝后不再问（session.notifAsked 记住）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        session.notifAsked = true
        if (granted) PushManager.syncToken(context)
    }

    LaunchedEffect(route) {
        val loggedIn = session.token.isNotEmpty()
        val inMail = route is Route.Inbox || route is Route.Detail
        if (!loggedIn || !inMail) return@LaunchedEffect
        // 登录后 / token 刷新后登记设备；失败静默，不弹任何提示
        PushManager.syncToken(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !session.notifAsked &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            session.notifAsked = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 点通知带进来的 emailId：等 UI 起来（且已登录）后直接进该邮件详情
    LaunchedEffect(PushRouter.pendingEmailId) {
        val id = PushRouter.pendingEmailId ?: return@LaunchedEffect
        if (session.token.isEmpty()) return@LaunchedEffect
        PushRouter.consume()
        backStack.clear()
        route = Route.Detail(id)
    }

    OTTheme {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(OT.colors.bgGrouped)) {
            when (val r = route) {
                Route.ServerSetup -> ServerSetupScreen(
                    session = session,
                    onConfigured = { route = Route.Login },
                    prefill = if (legacyInsecureBase) session.base else "",
                    notice = if (legacyInsecureBase) {
                        "检测到旧版本保存的 http 地址（${session.base}）。明文连接已被禁用，" +
                            "请改成 https 地址后继续；邮箱和密码不会在明文链路上发送。"
                    } else {
                        ""
                    },
                )

                Route.Login -> LoginScreen(
                    session = session,
                    onLoggedIn = {
                        // 401 重登：清掉全局过期标记，恢复 401 之前所在的页面（路由从返回栈里拿）
                        AuthState.reset()
                        InboxUi.forceTick++
                        // 登录前点的通知（此时还没登录态），登录成功后补跳详情
                        val pending = PushRouter.consume()
                        route = if (pending != null) {
                            backStack.clear()
                            Route.Detail(pending)
                        } else {
                            backStack.removeLastOrNull() ?: Route.Inbox
                        }
                    },
                )

                Route.Inbox -> InboxScreen(
                    session = session,
                    onOpenDetail = { OpenPerf.click(it); go(Route.Detail(it)) },
                    onCompose = { go(Route.Compose("", "", "")) },
                    onSwipeReply = { m ->
                        scope.launch {
                            // 先试缓存（内存 → SQLite），命中就立刻带上引用原文；
                            // 没缓存也先开写信页，引用由 ComposeScreen 在后台补齐。
                            val d = MailCache.detailMemory(m.emailId)?.first
                                ?: MailCache.detailDisk(m.emailId)?.first
                            val quoted = d?.let { buildQuoteHtml(it.name.ifEmpty { it.from }, it.content) }.orEmpty()
                            val subj = if (m.subject.startsWith("Re:")) m.subject else "Re: ${m.subject}"
                            go(Route.Compose(m.from, subj, quoted, quoteEmailId = m.emailId))
                        }
                    },
                    onOpenSettings = { go(Route.Settings) },
                    onOpenContacts = { go(Route.Contacts) },
                    onOpenAi = { go(Route.Ai) },
                )

                Route.Contacts -> SlidePage(onDismiss = { goBack() }) {
                    ContactsScreen(
                        session = session,
                        onBack = { goBack() },
                        onWrite = { email -> go(Route.Compose(email, "", "")) },
                    )
                }

                Route.Ai -> SlidePage(onDismiss = { goBack() }) {
                    AiScreen(
                        session = session,
                        onBack = { goBack() },
                        onOpenCompose = { go(Route.Compose("", "", "")) },
                    )
                }

                Route.Drafts -> SlidePage(onDismiss = { goBack() }) {
                    DraftsScreen(
                        accountId = session.accountId,
                        onBack = { goBack() },
                        onOpen = { draftId -> go(Route.Compose("", "", "", draftId = draftId)) },
                    )
                }

                Route.AllMail -> SlidePage(onDismiss = { goBack() }) {
                    AllMailScreen(
                        onBack = { goBack() },
                        onOpen = { go(Route.Detail(it)) },
                        onToast = toast,
                    )
                }

                Route.Users -> SlidePage(onDismiss = { goBack() }) {
                    UsersScreen(
                        onBack = { goBack() },
                    )
                }

                Route.Roles -> SlidePage(onDismiss = { goBack() }) {
                    RolesScreen(
                        onBack = { goBack() },
                    )
                }

                Route.RegKeys -> SlidePage(onDismiss = { goBack() }) {
                    RegKeysScreen(
                        onBack = { goBack() },
                    )
                }

                Route.Analysis -> SlidePage(onDismiss = { goBack() }) {
                    AnalysisScreen(onBack = { goBack() })
                }

                Route.ServerSettings -> SlidePage(onDismiss = { goBack() }) {
                    SysSettingsScreen(
                        onBack = { goBack() },
                        onToast = toast,
                    )
                }

                Route.SystemSettings -> SlidePage(onDismiss = { goBack() }) {
                    SystemSettingsScreen(
                        session = session,
                        // 改服务器地址会清掉登录态，直接回登录页而不是停在已登出的设置页
                        onBack = { if (session.token.isEmpty()) route = Route.Login else goBack() },
                        onToast = toast,
                        onOpenServerSettings = { go(Route.ServerSettings) },
                        onOpenAdmin = { key ->
                            go(
                                when (key) {
                                    "drafts" -> Route.Drafts
                                    "all" -> Route.AllMail
                                    "users" -> Route.Users
                                    "roles" -> Route.Roles
                                    "regkeys" -> Route.RegKeys
                                    "analysis" -> Route.Analysis
                                    else -> Route.SystemSettings
                                },
                            )
                        },
                    )
                }

                Route.Settings -> SlidePage(onDismiss = { goBack() }) {
                    SettingsScreen(
                        session = session,
                        onBack = {
                            if (session.token.isEmpty()) route = Route.Login else goBack()
                        },
                        onToast = toast,
                        onOpenSystemSettings = { go(Route.SystemSettings) },
                    )
                }

                is Route.Detail -> SlidePage(onDismiss = { goBack() }) {
                    DetailScreen(
                        emailId = r.id,
                        onBack = { goBack() },
                        onReply = { m, action ->
                            // 三个入口共用 buildQuoteHtml：原内容里的 <style>、charset 声明、
                            // html/head/body 外壳和未转义的 name 都会污染引用，导致收发两端乱码。
                            val quoted = buildQuoteHtml(m.name.ifEmpty { m.from }, m.content)
                            when (action) {
                                ReplyAction.REPLY ->
                                    go(Route.Compose(m.from, reSubject(m.subject), quoted))
                                ReplyAction.REPLY_ALL -> {
                                    val (to, cc) = replyAllRecipients(m, session.email)
                                    go(Route.Compose(to, reSubject(m.subject), quoted, cc = cc, mode = "replyAll"))
                                }
                                ReplyAction.FORWARD ->
                                    go(Route.Compose("", fwdSubject(m.subject), quoted, mode = "forward"))
                            }
                        },
                        onToast = toast,
                    )
                }

                is Route.Compose -> SlidePage(onDismiss = { goBack() }) {
                    ComposeScreen(
                        accountId = session.accountId,
                        to = r.to,
                        subject = r.subject,
                        quoted = r.quoted,
                        cc = r.cc,
                        mode = r.mode,
                        quoteEmailId = r.quoteEmailId,
                        draftId = r.draftId,
                        onOpenDrafts = {
                            // 去草稿箱前把当前草稿 id 补进路由：返回栈里存的就不再是空白写信页
                            val cur = route
                            if (cur is Route.Compose && cur.draftId == null && composeDraftId != null) {
                                route = cur.copy(draftId = composeDraftId)
                            }
                            go(Route.Drafts)
                        },
                        onDraftId = { composeDraftId = it },
                        onFinished = { sent ->
                            if (sent) toast("已发送")
                            goBack()
                        },
                    )
                }
            }
            SnackbarHost(hostState = snackbar)

            // 401 的唯一可见提示：不跳页、不清数据，点一下才去登录；重登成功回到原页面
            if (AuthState.expiredAt > 0L && route != Route.Login) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(OT.colors.red)
                        .statusBarsPadding()
                        .tapArea {
                            // 正在写信时被 401 拦走：把草稿 id 补回路由，重登后仍是原来的写信页
                            val cur = route
                            if (cur is Route.Compose && cur.draftId == null && composeDraftId != null) {
                                route = cur.copy(draftId = composeDraftId)
                            }
                            if (route != Route.Login) go(Route.Login)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "登录已过期，点此重新登录",
                        style = OT.type.footnote,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Text("重新登录", style = OT.type.footnote, color = Color.White)
                }
            }
        }
    }
}

/** 回复主题前缀：已有 Re:（大小写不敏感）不再叠加。 */
private fun reSubject(subject: String): String =
    if (subject.trim().lowercase().startsWith("re:")) subject else "Re: $subject"

/** 转发主题前缀：已有 Fwd:/Fw: 不再叠加。 */
private fun fwdSubject(subject: String): String {
    val s = subject.trim()
    return if (s.lowercase().startsWith("fwd:") || s.lowercase().startsWith("fw:")) s else "Fwd: $s"
}

/**
 * 回复全部的收件人：
 * To = 原发件人 + 原 To（recipient），Cc = 原 cc；
 * 地址大小写不敏感去重，并排除当前登录账户自己的地址（避免自己收到自己）。
 */
private fun replyAllRecipients(m: MailDetail, selfEmail: String): Pair<String, List<String>> {
    val self = selfEmail.trim().lowercase()
    val to = LinkedHashMap<String, String>()
    val cc = LinkedHashMap<String, String>()
    fun add(map: LinkedHashMap<String, String>, raw: String) {
        val addr = raw.trim()
        if (addr.isEmpty() || !addr.contains("@")) return
        val key = addr.lowercase()
        if (key == self) return
        map.putIfAbsent(key, addr)
    }
    add(to, m.from)
    m.recipient.forEach { add(to, it) }
    m.cc.forEach { add(cc, it) }
    // 已经进了 To 的地址不再重复出现在 Cc
    cc.keys.removeAll(to.keys)
    if (to.isEmpty()) {
        // 原发件人就是自己（读的是自己发出的邮件）：退回原 cc，至少别把收件人留空
        cc.forEach { (k, v) -> to[k] = v }
        cc.clear()
    }
    return to.values.joinToString(", ") to cc.values.toList()
}
