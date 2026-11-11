package com.otscup.otsmail.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.BuildConfig
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.AuthState
import com.otscup.otsmail.data.Demo
import com.otscup.otsmail.data.ExtSync
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.data.MailAddress
import com.otscup.otsmail.data.MailDetail
import com.otscup.otsmail.data.OAuthTiming
import com.otscup.otsmail.data.PushManager
import com.otscup.otsmail.data.QrScanBus
import com.otscup.otsmail.data.ExtMailStore
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.SnoozeReminderStore
import com.otscup.otsmail.push.ExtIdleManager
import com.otscup.otsmail.push.PushRouter
import com.otscup.otsmail.schedule.ScheduledSendScheduler
import com.otscup.otsmail.schedule.SnoozeRouter
import com.otscup.otsmail.schedule.SnoozeScheduler
import com.otscup.otsmail.ui.theme.OT
import com.otscup.otsmail.ui.theme.OTTheme
import kotlinx.coroutines.launch

private sealed interface Route {
    data object ServerSetup : Route
    data object Login : Route
    data object Inbox : Route
    data class Detail(val id: Long) : Route
    /** 「全部邮件」里非本人邮件的只读详情（含 userId=0 的无人收件） */
    data class SiteDetail(val id: Long) : Route
    /** 聚合收件：本地第三方邮件的详情（按 otsmail_ext.db 的 local_id 取）；sent=true 读本地已发送副本 */
    data class ExtDetail(val localId: Long, val sent: Boolean = false) : Route
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
        /** 智能回复点选后带进写信页的正文；其它入口为空 */
        val body: String = "",
        /** >0 表示用这个第三方邮箱自己的 SMTP 发信，而不是 otsmail 后端代发 */
        val extId: Long = 0L,
        val extAccountId: Long = 0L,
        val extAddress: String = "",
    ) : Route
    data object Settings : Route
    /** 聚合收件：第三方邮箱绑定向导（第一批只做绑定与连通性验证） */
    data object ExtBind : Route
    data object Contacts : Route
    data object SystemSettings : Route
    data object ServerSettings : Route
    data object AllMail : Route
    data object Drafts : Route
    data object ScheduledSends : Route
    /** 稍后提醒（本机、跟随账号；不改服务端） */
    data object SnoozeReminders : Route
    /** 扫码确认网页端登录（App 必须已登录；不会拿到 token） */
    data object QrScan : Route
    /** 在线会话与设备管理 */
    data object Sessions : Route
    /** 邮件退订管理中心 */
    data object Unsubscribe : Route
    /** 通行密钥（Passkey）凭据管理 */
    data object Passkey : Route
    /** 端到端加密（E2EE）安全管理 */
    data object E2EE : Route
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
    // AI 助手是盖在当前页面上的底部面板，不占路由/返回栈：收起后原页面还在原处
    var aiOpen by remember { mutableStateOf(false) }
    fun go(next: Route) {
        backStack.add(route)
        route = next
    }
    fun goBack() {
        route = backStack.removeLastOrNull() ?: Route.Inbox
    }
    // 扫码入口（长按图标 / otsmail://qr）：未登录先留在登录页，登录成功后由 onLoggedIn 接着跳扫码
    LaunchedEffect(QrScanBus.pending) {
        if (!QrScanBus.pending || session.token.isEmpty()) return@LaunchedEffect
        QrScanBus.consume()
        backStack.clear()
        route = Route.QrScan
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 401 已经统一显示顶部提示，friendlyError 对 401 返回空串，这里再兜一层空消息
    val toast: (String) -> Unit = { msg -> if (msg.isNotEmpty()) scope.launch { snackbar.showSnackbar(msg) } }
    // 收件箱/登录页是根：在根上按返回才退出到桌面
    androidx.activity.compose.BackHandler(
        enabled = route != Route.Inbox && route != Route.Login && route != Route.ServerSetup,
    ) { goBack() }

    // 定时发送的闹钟在「强制停止」后会被系统清掉，进程没法收到广播；每次打开 App 重排一遍，
    // 漏过的（比如关机期间到点）会立即补发，不会永远丢在本地。
    LaunchedEffect(Unit) {
        runCatching { ScheduledSendScheduler.rescheduleAll(context.applicationContext) }
    }

    // 稍后提醒只排当前账号：切号/退出时已取消闹钟，重登同一账号在这里恢复。
    LaunchedEffect(session.token.isNotEmpty()) {
        runCatching { SnoozeScheduler.rescheduleForCurrentAccount(context.applicationContext) }
    }

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
        // 聚合收件拉取层：客户端直连 IMAP，15 分钟节流，全程静默（本批不做界面）
        if (route is Route.Inbox) ExtSync.maybeSync(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !session.notifAsked &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            session.notifAsked = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 第三方登录耗时的最后一段：收件箱真正画出第一帧（没在计时就不打印）
        if (route is Route.Inbox) {
            androidx.compose.runtime.withFrameNanos { }
            OAuthTiming.mark(context, "inbox-visible")
            OAuthTiming.finish(context)
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

    // 外部邮箱通知：local_id 直接开本地详情
    LaunchedEffect(PushRouter.pendingExtLocalId) {
        val localId = PushRouter.pendingExtLocalId ?: return@LaunchedEffect
        if (session.token.isEmpty()) return@LaunchedEffect
        PushRouter.consumeExt()
        backStack.clear()
        route = Route.ExtDetail(localId)
    }

    // 稍后提醒通知/点击：openList 进入列表；否则打开对应邮件详情（mailId 可能不存在时由详情降级）。
    LaunchedEffect(session.token.isNotEmpty()) {
        if (session.token.isEmpty()) return@LaunchedEffect
        SnoozeRouter.events.collect { ev ->
            val mailId = ev.mailId?.toLongOrNull()
            when {
                ev.openList -> { backStack.clear(); route = Route.SnoozeReminders }
                // 第三方提醒的 mailId 是 messageId，要靠 extId 反查本地 local_id 才能开详情
                ev.source == "ext" -> {
                    val rem = ev.reminderId?.let { SnoozeReminderStore.get(context, it) }
                    val extId = rem?.extId
                    val local = if (extId != null && !ev.mailId.isNullOrBlank()) ExtMailStore.lastLocalId(extId, ev.mailId) else 0L
                    backStack.clear()
                    if (local > 0L) {
                        route = Route.ExtDetail(local)
                    } else {
                        route = Route.SnoozeReminders
                        toast("原邮件已不在本地，重新同步后再试")
                    }
                }
                mailId != null -> { backStack.clear(); route = Route.Detail(mailId) }
                else -> { backStack.clear(); route = Route.SnoozeReminders }
            }
        }
    }

    // 第三方邮件提醒：默认走 IMAP IDLE；用户可在外部邮箱页选「关闭第三方邮件提醒」。
    // otsmail 自己的邮件始终走 FCM，与这里无关；判断与停止路径详见 ExtIdleManager，重复调用幂等。
    LaunchedEffect(session.token.isNotEmpty()) {
        if (session.token.isEmpty()) {
            ExtIdleManager.stop(context)
        } else {
            ExtIdleManager.startIfNeeded(context)
        }
    }

    OTTheme {
        @Composable
        fun RenderRoute(
            r: Route,
            slideEnabled: Boolean = true,
            onDismissRoute: () -> Unit = { goBack() },
        ) {
            when (r) {
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
                        val pendingExt = PushRouter.consumeExt()
                        // 未登录时长按图标进的扫码：登录成功后继续
                        val pendingQr = QrScanBus.consume()
                        route = when {
                            pendingQr -> {
                                backStack.clear()
                                Route.QrScan
                            }

                            pending != null -> {
                                backStack.clear()
                                Route.Detail(pending)
                            }

                            pendingExt != null -> {
                                backStack.clear()
                                Route.ExtDetail(pendingExt)
                            }

                            else -> backStack.removeLastOrNull() ?: Route.Inbox
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
                    onOpenAi = { aiOpen = true },
                    onOpenQrScan = { go(Route.QrScan) },
                    onOpenDrafts = { go(Route.Drafts) },
                    onOpenSnooze = { go(Route.SnoozeReminders) },
                    onOpenExtDetail = { localId, sent -> go(Route.ExtDetail(localId, sent)) },
                    onComposeExt = { extId, accountId, address ->
                        go(
                            Route.Compose(
                                to = "",
                                subject = "",
                                quoted = "",
                                extId = extId,
                                extAccountId = accountId,
                                extAddress = address,
                            ),
                        )
                    },
                )

                Route.Contacts -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    ContactsScreen(
                        session = session,
                        onBack = { goBack() },
                        onWrite = { email -> go(Route.Compose(email, "", "")) },
                    )
                }

                Route.Drafts -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    DraftsScreen(
                        accountId = session.accountId,
                        onBack = { goBack() },
                        onOpen = { draftId -> go(Route.Compose("", "", "", draftId = draftId)) },
                    )
                }

                Route.ScheduledSends -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    ScheduledSendScreen(
                        onBack = { goBack() },
                        onToast = toast,
                    )
                }

                Route.SnoozeReminders -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    SnoozeReminderScreen(
                        session = session,
                        onBack = { goBack() },
                        onToast = toast,
                        onOpenMail = { r ->
                            // 第三方提醒按 extId+messageId 反查本地行；OTS 提醒 mailId 就是服务端 emailId
                            if (r.source == "ext") {
                                val extId = r.extId
                                val local = if (extId != null) ExtMailStore.lastLocalId(extId, r.mailId) else 0L
                                if (local > 0L) go(Route.ExtDetail(local)) else toast("原邮件已不在本地，重新同步后再试")
                            } else {
                                go(Route.Detail(r.mailId.toLongOrNull() ?: 0L))
                            }
                        },
                    )
                }

                Route.AllMail -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    AllMailScreen(
                        onBack = { goBack() },
                        onOpen = { go(Route.Detail(it)) },
                        onOpenReadOnly = { go(Route.SiteDetail(it)) },
                        onToast = toast,
                    )
                }

                is Route.SiteDetail -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    SiteMailDetailScreen(
                        emailId = r.id,
                        onBack = { goBack() },
                        // 与普通详情同一套回复/转发规则：收件人取原信的 From（不是 userId），
                        // 发信人始终是当前登录账号，不会把 userId=0 的来源当成自己的地址。
                        onReply = { m, action ->
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

                is Route.ExtDetail -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    val extRoute = route as Route.ExtDetail
                    ExtMailDetailScreen(
                        localId = extRoute.localId,
                        sent = extRoute.sent,
                        onBack = { goBack() },
                        onReply = { m, action, address, extAccId ->
                            val author = m.fromName.ifEmpty { m.fromAddr }
                            val quoted = buildQuoteHtml(author, m.bodyHtml.ifEmpty { m.bodyText })
                            when (action) {
                                ReplyAction.REPLY -> go(
                                    Route.Compose(
                                        m.fromAddr, reSubject(m.subject), quoted,
                                        extId = m.extId, extAccountId = extAccId, extAddress = address,
                                    ),
                                )
                                ReplyAction.REPLY_ALL -> {
                                    // 回复全部：去掉自己和原发件人后剩下的 To/Cc 全部进抄送
                                    val others = (MailAddress.list(m.toAddr) + MailAddress.list(m.cc))
                                        .map { it.address }
                                        .filter {
                                            it.lowercase() != address.lowercase() &&
                                                it.lowercase() != m.fromAddr.lowercase()
                                        }
                                        .distinct()
                                    go(
                                        Route.Compose(
                                            m.fromAddr, reSubject(m.subject), quoted,
                                            cc = others, mode = "replyAll",
                                            extId = m.extId, extAccountId = extAccId, extAddress = address,
                                        ),
                                    )
                                }
                                ReplyAction.FORWARD -> go(
                                    Route.Compose(
                                        "", fwdSubject(m.subject), quoted, mode = "forward",
                                        extId = m.extId, extAccountId = extAccId, extAddress = address,
                                    ),
                                )
                            }
                        },
                        onToast = toast,
                    )
                }

                Route.Users -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    UsersScreen(
                        onBack = { goBack() },
                    )
                }

                Route.Roles -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    RolesScreen(
                        onBack = { goBack() },
                    )
                }

                Route.RegKeys -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    RegKeysScreen(
                        onBack = { goBack() },
                    )
                }

                Route.Analysis -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    AnalysisScreen(onBack = { goBack() })
                }

                Route.ServerSettings -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    SysSettingsScreen(
                        onBack = { goBack() },
                        onToast = toast,
                    )
                }

                Route.SystemSettings -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
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

                Route.Settings -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    SettingsScreen(
                        session = session,
                        onBack = {
                            if (session.token.isEmpty()) route = Route.Login else goBack()
                        },
                        onToast = toast,
                        onOpenSystemSettings = { go(Route.SystemSettings) },
                        onOpenExtAccounts = { go(Route.ExtBind) },
                        onOpenScheduled = { go(Route.ScheduledSends) },
                        onOpenSnooze = { go(Route.SnoozeReminders) },
                        onOpenSessions = { go(Route.Sessions) },
                        onOpenUnsubscribe = { go(Route.Unsubscribe) },
                        onOpenPasskey = { go(Route.Passkey) },
                        onOpenE2EE = { go(Route.E2EE) },
                    )
                }

                Route.Sessions -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    SessionScreen(
                        onBack = { goBack() },
                        onToast = toast,
                    )
                }

                Route.Unsubscribe -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    UnsubscribeScreen(
                        onBack = { goBack() },
                        onToast = toast,
                    )
                }

                Route.Passkey -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    PasskeyScreen(
                        onBack = { goBack() },
                    )
                }

                Route.E2EE -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    E2EEScreen(
                        onBack = { goBack() },
                    )
                }

                Route.QrScan -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    QrScanScreen(session = session, onBack = { goBack() })
                }

                Route.ExtBind -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    ExtBindScreen(
                        onBack = { goBack() },
                        onCompose = { extId, accountId, address ->
                            go(
                                Route.Compose(
                                    to = "",
                                    subject = "",
                                    quoted = "",
                                    extId = extId,
                                    extAccountId = accountId,
                                    extAddress = address,
                                ),
                            )
                        },
                        onToast = toast,
                    )
                }

                is Route.Detail -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
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
                        // 智能回复：点选一条候选才进写信页，收件人/主题按回复规则，不会自动发送
                        onAiReply = { m, text ->
                            val quoted = buildQuoteHtml(m.name.ifEmpty { m.from }, m.content)
                            go(Route.Compose(m.from, reSubject(m.subject), quoted, body = text))
                        },
                        onToast = toast,
                    )
                }

                is Route.Compose -> SlidePage(onDismiss = onDismissRoute, enabled = slideEnabled) {
                    ComposeScreen(
                        accountId = if (r.extId > 0) r.extAccountId else session.accountId,
                        to = r.to,
                        subject = r.subject,
                        quoted = r.quoted,
                        cc = r.cc,
                        mode = r.mode,
                        quoteEmailId = r.quoteEmailId,
                        draftId = r.draftId,
                        initialBody = r.body,
                        extId = r.extId,
                        extAddress = r.extAddress,
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
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(OT.colors.bgGrouped)) {
            val isLargeScreen = maxWidth >= 720.dp && session.token.isNotEmpty() && route != Route.Login && route != Route.ServerSetup

            if (isLargeScreen) {
                // 平板 / 折叠屏展开态自适应双栏（List-Detail Pane）布局
                Row(Modifier.fillMaxSize()) {
                    // 左侧常驻列表栏（360dp，保证抽屉与现有交互完好）
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(360.dp).fillMaxHeight(),
                    ) {
                        InboxScreen(
                            session = session,
                            onOpenDetail = { OpenPerf.click(it); go(Route.Detail(it)) },
                            onCompose = { go(Route.Compose("", "", "")) },
                            onSwipeReply = { m ->
                                scope.launch {
                                    val d = MailCache.detailMemory(m.emailId)?.first
                                        ?: MailCache.detailDisk(m.emailId)?.first
                                    val quoted = d?.let { buildQuoteHtml(it.name.ifEmpty { it.from }, it.content) }.orEmpty()
                                    val subj = if (m.subject.startsWith("Re:")) m.subject else "Re: ${m.subject}"
                                    go(Route.Compose(m.from, subj, quoted, quoteEmailId = m.emailId))
                                }
                            },
                            onOpenSettings = { go(Route.Settings) },
                            onOpenContacts = { go(Route.Contacts) },
                            onOpenAi = { aiOpen = true },
                            onOpenQrScan = { go(Route.QrScan) },
                            onOpenDrafts = { go(Route.Drafts) },
                            onOpenSnooze = { go(Route.SnoozeReminders) },
                            onOpenExtDetail = { localId, sent -> go(Route.ExtDetail(localId, sent)) },
                            onComposeExt = { extId, accountId, address ->
                                go(
                                    Route.Compose(
                                        to = "",
                                        subject = "",
                                        quoted = "",
                                        extId = extId,
                                        extAccountId = accountId,
                                        extAddress = address,
                                    ),
                                )
                            },
                        )
                    }

                    // 纵向分隔线
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(1.dp).fillMaxHeight().background(OT.colors.separator),
                    )

                    // 右侧内容区
                    androidx.compose.foundation.layout.Box(
                        Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        if (route == Route.Inbox) {
                            LargeScreenEmptyPreview(onCompose = { go(Route.Compose("", "", "")) })
                        } else {
                            RenderRoute(
                                r = route,
                                slideEnabled = false,
                                onDismissRoute = { if (backStack.isNotEmpty()) goBack() else route = Route.Inbox },
                            )
                        }
                    }
                }
            } else {
                RenderRoute(
                    r = route,
                    slideEnabled = true,
                    onDismissRoute = { goBack() },
                )
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

            // AI 助手：盖在当前页面上的底部面板，关闭后原页面与状态都还在
            if (aiOpen) {
                AiScreen(
                    session = session,
                    onClose = { aiOpen = false },
                    onOpenCompose = {
                        aiOpen = false
                        go(Route.Compose("", "", ""))
                    },
                    // 把这段 AI 内容作为正文带进写信页；不绑收件人、不自动发送
                    onReply = { text ->
                        aiOpen = false
                        go(Route.Compose("", "", "", body = text))
                    },
                )
            }
        }
    }
}

/** 平板/折叠屏大屏未选中邮件时的右侧空预览状态 */
@Composable
private fun LargeScreenEmptyPreview(onCompose: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(c.bgGrouped)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(c.blue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Mail,
                    contentDescription = null,
                    tint = c.blue,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("选择一封邮件", style = t.title3, color = c.label)
            Spacer(Modifier.height(6.dp))
            Text("在左侧列表中点击邮件，即可在右侧快速预览完整内容", style = t.footnote, color = c.label2)
            Spacer(Modifier.height(20.dp))
            androidx.compose.foundation.layout.Box(
                Modifier
                    .clip(RoundedCornerShape(OT.dimens.rPill.dp))
                    .background(c.blue)
                    .clickable { onCompose() }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("撰写新邮件", style = t.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
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
