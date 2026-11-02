package com.otscup.otsmail.ui
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Mail

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.MailItem
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon

/** 分类码 → 中文（详情页也用这个） */
internal fun categoryLabel(code: String): String = when (code) {
    "primary" -> "主要"
    "social" -> "社交"
    "promotion" -> "推广"
    "updates" -> "动态"
    "ads" -> "广告"
    "spam" -> "垃圾"
    else -> ""
}

/** "2026-10-30 15:32:26" / 10 位秒 / 13 位毫秒 → "2026-10-30 15:32" */
internal fun fmtFull(raw: String): String = runCatching {
    val txt = raw.trim()
    if (txt.length >= 16 && txt.contains("-") && txt.contains(" ")) return@runCatching txt
    val num = txt.toLongOrNull() ?: return@runCatching txt
    val instant = if (num > 100000000000L) java.time.Instant.ofEpochMilli(num) else java.time.Instant.ofEpochSecond(num)
    val ldt = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
    String.format("%04d-%02d-%02d %02d:%02d", ldt.year, ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
}.getOrDefault(raw)

private fun clock(raw: String): String = clockOf(raw)

/** 当天→今天，昨天→昨天，其余→MM月DD日（v5 定稿的日期分组写法） */
private fun dayOf(raw: String): String {
    val day = fmtFull(raw).take(10)
    val now = java.time.LocalDate.now()
    return when (day) {
        now.toString() -> "今天"
        now.minusDays(1).toString() -> "昨天"
        else -> if (day.length >= 10) "${day.substring(5, 7)}月${day.substring(8, 10)}日" else day
    }
}

private data class Mailbox(val label: String, val key: String)

private val mailboxes = listOf(
    Mailbox("收件箱", "receive"),
    Mailbox("未读邮件", "unread"),
    Mailbox("已标星", "starred"),
    Mailbox("已删除", "deleted"),
    Mailbox("垃圾邮件", "spam"),
)

@Composable
fun InboxScreen(
    session: Session,
    onOpenDetail: (Long) -> Unit,
    onCompose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenSysSettings: () -> Unit,
) {
    val t = OT.type
    val c = OT.colors
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val items = remember { mutableStateListOf<MailItem>() }
    val listState = rememberLazyListState()
    // 每次列表内容变化（翻页/切换邮箱/搜索）回到顶部，避免启动后停在上次位置
    // 只在本次组合内首次拿到数据时置顶，之后翻页/滚动不再打扰
    var didTop by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(items.isNotEmpty()) { if (!didTop && items.isNotEmpty()) { listState.scrollToItem(0); didTop = true } }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    var current by remember { mutableStateOf(mailboxes[0]) }
    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var unread by remember { mutableStateOf(0) }
    var accounts by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var accVersion by remember { mutableStateOf(0) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var category by remember { mutableStateOf("") }
    var catCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    suspend fun load(next: Int, reset: Boolean) {
        if (loading) return
        loading = true
        error = ""
        runCatching {
            if (session.accountId == 0L) {
                runCatching { Api.me() }.onSuccess { session.accountId = it.accountId }
            }
            val pair = if (current.key == "starred") Api.starred(next)
            else Api.emails(session.accountId, next, current.key, keyword, category)
            if (reset) items.clear()
            items.addAll(pair.first)
            total = pair.second.toLong().toInt()
            page = next
            if (current.key == "receive") unread = items.count { it.unread }
            // 抽屉数据跟着列表一起拉：composition 早期 Api.base 还没就绪，独立 LaunchedEffect 会静默失败
            runCatching { Api.accounts() }.onSuccess { accounts = it }
            runCatching { Api.menuCounts() }.onSuccess { counts = it }
            runCatching { Api.categoryCounts() }.onSuccess { catCounts = it }
        }.onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    LaunchedEffect(current.key, keyword, accVersion, category) {
        load(1, true)
        listState.scrollToItem(0)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = c.bgCard,
                drawerShape = RoundedCornerShape(28.dp),
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(8.dp)
                    .width(322.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(c.blue),
                            contentAlignment = Alignment.Center,
                        ) { Text(session.name.take(1).uppercase(), style = t.title3, color = Color.White) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(session.name.ifEmpty { "我" }, style = t.body, color = c.label)
                            Text(session.email, style = t.footnote, color = c.label2)
                        }
                        Box(Modifier.size(36.dp).tapArea { scope.launch { drawer.close() }; onOpenSysSettings() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Settings, contentDescription = "系统设置", tint = c.label2, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(OTSpace.s4))
                    OTGroupHeader("帐户")
                    OTGroup {
                        accounts.forEachIndexed { i, acc ->
                            if (i > 0) OTDivider(inset = 16)
                            val active = acc.first == session.accountId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .tapArea {
                                        if (!active) {
                                            session.accountId = acc.first
                                            session.apply()
                                            accVersion++
                                            didTop = false
                                        }
                                        scope.launch { drawer.close() }
                                    }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val accColor = listOf(c.blue, c.green, c.orange, c.purple, c.teal, c.indigo)[i % 6]
                                Box(
                                    Modifier.size(12.dp).border(2.dp, accColor, CircleShape),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(acc.second, style = t.body, color = if (active) c.blue else c.label, maxLines = 1, modifier = Modifier.weight(1f))
                                val unreadN = counts["inbox"] ?: 0
                                if (active && unreadN > 0) {
                                    Text(
                                        unreadN.toString(),
                                        style = t.caption1,
                                        color = Color.White,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(c.orange)
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                    OTDash()
                    Spacer(Modifier.height(OTSpace.s4))
                    OTGroupHeader("邮箱")
                    OTGroup {
                        mailboxes.forEachIndexed { i, box ->
                            if (i > 0) OTDivider(inset = 16)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(if (box == current) c.bgPressed else Color.Transparent)
                                    .tapArea {
                                        current = box
                                        scope.launch { drawer.close() }
                                    }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(boxIcon(box.label), contentDescription = null, tint = if (box == current) c.blue else c.label2, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
Text(box.label, style = t.body, color = if (box == current) c.blue else c.label, modifier = Modifier.weight(1f))
                                val boxN = when (box.key) { "receive" -> counts["inbox"] ?: 0; "deleted" -> counts["deleted"] ?: 0; "spam" -> counts["spam"] ?: 0; else -> 0 }
                                if (boxN > 0) Text(boxN.toString(), style = t.caption1, color = c.label2)
                            }
                        }
                    }
                    Spacer(Modifier.height(OTSpace.s4))
                    OTGroupHeader("分类")
                    OTGroup {
                        val cats = listOf("primary" to "主要", "social" to "社交", "promotion" to "推广", "updates" to "动态", "ads" to "广告")
                        cats.forEachIndexed { i, (key, label) ->
                            if (i > 0) OTDivider(inset = 16)
                            val on = category == key
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(if (on) c.bgPressed else Color.Transparent)
                                    .tapArea {
                                        category = if (on) "" else key
                                        scope.launch { drawer.close() }
                                    }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, style = t.body, color = if (on) c.blue else c.label, modifier = Modifier.weight(1f))
                                val n = catCounts[key] ?: 0
                                if (n > 0) Text(n.toString(), style = t.caption1, color = c.label2)
                            }
                        }
                    }
                    OTDash()
                    Spacer(Modifier.height(OTSpace.s4))
                    OTGroupHeader("其他")
                    OTGroup {
                        val labels = listOf("联系人", "AI 助手", "设置", "系统设置")
                        val actions: List<() -> Unit> = listOf(onOpenContacts, onOpenAi, onOpenSettings, onOpenSysSettings)
                        labels.forEachIndexed { i, label ->
                            if (i > 0) OTDivider(inset = 16)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .tapArea {
                                        scope.launch { drawer.close() }
                                        actions[i]()
                                    }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) { Icon(otherIcon(label), contentDescription = null, tint = c.label2, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
Text(label, style = t.body, color = c.label) }
                        }
                    }
                    Spacer(Modifier.height(OTSpace.s4))
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp)
                            .clip(CircleShape)
                            .background(c.blue)
                            .tapArea { scope.launch { drawer.open() } },
                        contentAlignment = Alignment.Center,
                    ) { Text(session.name.take(1).uppercase(), style = t.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    OTSearchPill(value = keyword, onValueChange = { keyword = it }) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(c.blue)
                                .tapArea { onOpenAi() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OTLargeTitle(
                        current.label + if (category.isEmpty()) "" else " · " + when (category) {
                            "primary" -> "主要"; "social" -> "社交"; "promotion" -> "推广"
                            "updates" -> "动态"; "ads" -> "广告"; else -> category
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    if (unread > 0) Text("$unread 未读", style = t.footnote, color = c.blue,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                if (error.isNotEmpty()) {
                    Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(16.dp))
                }
                if (items.isEmpty() && !loading) {
                    Column(
                        Modifier.fillMaxSize().padding(top = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.Inbox, contentDescription = null, tint = c.label3, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(OTSpace.s3))
                        Text("还没有邮件", style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                        Spacer(Modifier.height(4.dp))
                        Text("换个邮箱，或稍后再试", style = t.subhead, color = c.label2)
                    }
                } else {
                    val groups = items.groupBy { dayOf(it.time) }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                        groups.forEach { (day, list) ->
                            item(key = "h-$day") { OTDayHeader(day) }
                            item(key = "g-$day") {
                                OTGroup {
                                    list.forEachIndexed { index, mail ->
                                        if (index > 0) OTDivider(inset = 58)
                                        OTMailRow(
                                            from = mail.name.ifEmpty { mail.from },
                                            subject = mail.subject.ifEmpty { "（无主题）" },
                                            snippet = mail.text,
                                            time = clock(mail.time),
                                            unread = mail.unread,
                                            starred = mail.starred,
                                            category = categoryLabel(mail.category).ifEmpty { null },
                                            onOpen = { onOpenDetail(mail.emailId) },
                                            onStar = {
                                                val idx = items.indexOfFirst { it.emailId == mail.emailId }
                                                if (idx >= 0) {
                                                    val old = items[idx]
                                                    items[idx] = old.copy(starred = !old.starred)
                                                    scope.launch {
                                                        runCatching {
                                                            if (old.starred) Api.starCancel(old.emailId)
                                                            else Api.starAdd(old.emailId)
                                                        }.onFailure {
                                                            val back = items.indexOfFirst { it.emailId == old.emailId }
                                                            if (back >= 0) items[back] = old
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "footer") {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("共 $total 封 · 第 $page 页", style = t.footnote, color = c.label2)
                                if (items.size < total) {
                                    Spacer(Modifier.height(OTSpace.s2))
                                    Text(
                                        if (loading) "正在加载…" else "加载更多（还有 ${total - items.size} 封）",
                                        style = t.subhead,
                                        color = c.blue,
                                        modifier = Modifier.tapArea { scope.launch { load(page + 1, false) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 34.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(c.blue)
                    .tapArea { onCompose() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Edit, contentDescription = "写邮件", tint = Color.White, modifier = Modifier.size(24.dp)) }
        }
    }
}

private fun boxIcon(label: String) = when {
    label.startsWith("收件") -> Icons.Outlined.Inbox
    label.startsWith("未读") -> Icons.Outlined.MarkEmailUnread
    label.contains("星") -> Icons.Outlined.StarBorder
    label.contains("删除") -> Icons.Outlined.Delete
    label.contains("垃圾") -> Icons.Outlined.Report
    label.contains("草稿") -> Icons.Outlined.Drafts
    else -> Icons.Outlined.Mail
}

private fun otherIcon(label: String) = when {
    label.contains("联系人") -> Icons.Outlined.People
    label.contains("AI") -> Icons.Outlined.AutoAwesome
    label.contains("系统") -> Icons.Outlined.Tune
    else -> Icons.Outlined.Settings
}
