package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.theme.OT
import com.otscup.otsmail.ui.theme.OTTheme
import kotlinx.coroutines.launch

private sealed interface Route {
    data object Login : Route
    data object Inbox : Route
    data class Detail(val id: Long) : Route
    data class Compose(val to: String, val subject: String, val quoted: String) : Route
    data object Settings : Route
    data object Contacts : Route
    data object Ai : Route
    data object SysSettings : Route
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
    val session = remember { Session(context).also { it.apply() } }
    var route by remember { mutableStateOf<Route>(if (session.token.isEmpty()) Route.Login else Route.Inbox) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    OTTheme {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(OT.colors.bgGrouped)) {
            when (val r = route) {
                Route.Login -> LoginScreen(
                    session = session,
                    onLoggedIn = { route = Route.Inbox },
                )

                Route.Inbox -> InboxScreen(
                    session = session,
                    onOpenDetail = { route = Route.Detail(it) },
                    onCompose = { route = Route.Compose("", "", "") },
                    onOpenSettings = { route = Route.Settings },
                    onOpenContacts = { route = Route.Contacts },
                    onOpenAi = { route = Route.Ai },
                    onOpenSysSettings = { route = Route.SysSettings },
                )

                Route.Contacts -> SlidePage(onDismiss = { route = Route.Inbox }) {
                    ContactsScreen(
                        session = session,
                        onBack = { route = Route.Inbox },
                        onWrite = { email -> route = Route.Compose(email, "", "") },
                    )
                }

                Route.Ai -> SlidePage(onDismiss = { route = Route.Inbox }) {
                    AiScreen(
                        session = session,
                        onBack = { route = Route.Inbox },
                        onOpenCompose = { route = Route.Compose("", "", "") },
                    )
                }

                Route.Drafts -> SlidePage(onDismiss = { route = Route.Settings }) {
                    DraftsScreen(
                        onBack = { route = Route.Settings },
                        onOpen = { dr ->
                            route = Route.Compose(dr.optString("to"), dr.optString("subject"), dr.optString("text"))
                        },
                    )
                }

                Route.AllMail -> SlidePage(onDismiss = { route = Route.Settings }) {
                    AllMailScreen(
                        onBack = { route = Route.Settings },
                        onOpen = { route = Route.Detail(it) },
                        onToast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                }

                Route.Users -> SlidePage(onDismiss = { route = Route.Settings }) {
                    UsersScreen(
                        onBack = { route = Route.Settings },
                        onToast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                }

                Route.Roles -> SlidePage(onDismiss = { route = Route.Settings }) {
                    RolesScreen(
                        onBack = { route = Route.Settings },
                        onToast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                }

                Route.RegKeys -> SlidePage(onDismiss = { route = Route.Settings }) {
                    RegKeysScreen(
                        onBack = { route = Route.Settings },
                        onToast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                }

                Route.Analysis -> SlidePage(onDismiss = { route = Route.Settings }) {
                    AnalysisScreen(onBack = { route = Route.Settings })
                }

                Route.SysSettings -> SlidePage(onDismiss = { route = Route.Settings }) {
                    SysSettingsScreen(
                        onBack = { route = Route.Settings },
                        onToast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                }

                Route.Settings -> SlidePage(onDismiss = { route = Route.Inbox }) {
                    SettingsScreen(
                        session = session,
                        onBack = {
                            route = if (session.token.isEmpty()) Route.Login else Route.Inbox
                        },
                        onToast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                        onOpenSysSettings = { route = Route.SysSettings },
                        onOpenAdmin = { key ->
                            route = when (key) {
                                "drafts" -> Route.Drafts
                                "all" -> Route.AllMail
                                "users" -> Route.Users
                                "roles" -> Route.Roles
                                "regkeys" -> Route.RegKeys
                                "analysis" -> Route.Analysis
                                else -> Route.Settings
                            }
                        },
                    )
                }

                is Route.Detail -> SlidePage(onDismiss = { route = Route.Inbox }) {
                    DetailScreen(
                        emailId = r.id,
                        onBack = { route = Route.Inbox },
                        onReply = { m ->
                            val quoted = "<blockquote><p>${m.name} 写道：</p>${m.content}</blockquote>"
                            route = Route.Compose(m.from, if (m.subject.startsWith("Re:")) m.subject else "Re: ${m.subject}", quoted)
                        },
                    )
                }

                is Route.Compose -> SlidePage(onDismiss = { route = Route.Inbox }) {
                    ComposeScreen(
                        accountId = session.accountId,
                        to = r.to,
                        subject = r.subject,
                        quoted = r.quoted,
                        onDone = {
                            scope.launch { snackbar.showSnackbar("已处理") }
                            route = Route.Inbox
                        },
                    )
                }
            }
            SnackbarHost(hostState = snackbar)
        }
    }
}
