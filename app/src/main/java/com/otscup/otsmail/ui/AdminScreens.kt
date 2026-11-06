package com.otscup.otsmail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otscup.otsmail.R
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.data.MailItem
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/** 管理区的通用内联导航栏（‹ + 标题 + 可选动作）。 */
@Composable
private fun AdminBar(title: String, action: String? = null, onAction: () -> Unit = {}, onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    OTNavBar(title = title, onLeading = onBack) {
        if (action != null) {
            Box(
                Modifier.height(44.dp).tapArea { onAction() }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(action, style = t.subhead, color = c.blue) }
        }
    }
}

/* ---------------- 全部邮件（全站口径） ---------------- */

/**
 * 分组 pill 与搜索词放进程级：进详情丢 composition，返回后仍是同一分组、同一页、同一位置。
 * 缓存 key 必须带 scope（site-mail|），否则会和旧版「当前用户全部邮箱」的 allmail| 快照串数据。
 */
internal object AllMailUi {
    var pill by mutableStateOf("all")
    var keyword by mutableStateOf("")
    /** /my/loginUserInfo 的 userId：用来判断这一封是不是自己的邮件（他人邮件详情服务端会拒绝） */
    var myUserId by mutableStateOf(-1L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllMailScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenReadOnly: (Long) -> Unit,
    onToast: (String) -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()

    val pill = AllMailUi.pill
    val keyword = AllMailUi.keyword
    var kwText by remember { mutableStateOf(AllMailUi.keyword) }
    LaunchedEffect(kwText) {
        if (kwText != AllMailUi.keyword) {
            delay(280)
            AllMailUi.keyword = kwText
        }
    }

    // scope 写进 key：全站列表与「我的全部邮箱」是两套数据，绝不能共用快照
    val key = "site-mail|pill:$pill|kw:${keyword.trim()}"
    val store = MailCache.list(key, 0L)
    var myUserId by remember { mutableStateOf(AllMailUi.myUserId) }
    LaunchedEffect(Unit) {
        if (myUserId <= 0) runCatching { Api.me() }.onSuccess {
            myUserId = it.userId
            AllMailUi.myUserId = it.userId
        }
    }
    val items = store.items
    var restoreToken by remember { mutableStateOf(0) }
    val listState = remember(store, restoreToken) { LazyListState(store.scrollIndex, store.scrollOffset) }

    var refreshing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val ptrState = rememberPullToRefreshState()

    // 全站列表只读：不接批量删除/移动（all-email:delete 按 emailId 物理删除且无 userId 过滤，会误删他人邮件）

    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            delay(2600)
            notice = ""
        }
    }

    LaunchedEffect(listState, store) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (i, o) ->
                // 列表空的时候不要把 0 写回去，避免覆盖磁盘里的位置
                if (items.isNotEmpty()) {
                    store.scrollIndex = i
                    store.scrollOffset = o
                    MailCache.saveScroll(store)
                }
            }
    }

    suspend fun fetchRaw(next: Int): JSONObject = Api.siteEmailsRaw(
        page = next,
        keyword = keyword,
        type = when (pill) {
            "inbox" -> "receive"
            "sent" -> "send"
            "deleted" -> "delete"
            "noone" -> "noone"
            else -> "all"
        },
        timeSort = 0,
    )

    suspend fun refreshFirst(toTop: Boolean = false) {
        val (freshRaw, total) = Api.parseList(fetchRaw(1))
        // 刷新首页 = 重置为第 1 页：不能把新首页和旧的第 2..N 页继续拼在一起，
        // 邮件上下位移后旧页码已经是另一批数据，拼接会重复并让分页漂移。
        val fresh = MailCache.dedupeByEmailId(freshRaw)
        val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }?.key as? Long
        val anchorOffset = listState.firstVisibleItemScrollOffset
        items.clear()
        items.addAll(fresh)
        store.total = total.toInt()
        store.page = 1
        store.error = ""
        MailCache.savePage(store, 1, fresh, total.toInt(), resetLater = true)
        if (toTop) {
            // 无条件回到最顶端：index 可能已是 0，但行偏移还没归零
            listState.scrollToItem(0, 0)
        } else if (anchor != null) {
            val row = items.indexOfFirst { it.emailId == anchor }
            // 首行是「共 N 封」统计行，所以邮件行要 +1
            if (row >= 0) {
                val target = row + 1
                if (target != listState.firstVisibleItemIndex) listState.scrollToItem(target, anchorOffset)
            } else {
                // 锚点邮件不在新首页了：夹回最后一封，避免越界空白
                listState.scrollToItem((items.size).coerceAtLeast(0), 0)
            }
        } else if (listState.firstVisibleItemIndex >= items.size + 1) {
            listState.scrollToItem(items.size.coerceAtLeast(0), 0)
        }
    }

    suspend fun loadMore() {
        if (store.loadingMore || items.size >= store.total) return
        store.loadingMore = true
        runCatching { Api.parseList(fetchRaw(store.page + 1)) }
            .onSuccess { (list, n) ->
                val ids = items.mapTo(HashSet()) { it.emailId }
                val add = list.filter { it.emailId !in ids }
                items.addAll(add)
                store.total = n.toInt()
                store.page += 1
                store.error = ""
                if (add.isNotEmpty()) MailCache.savePage(store, store.page, add, n.toInt())
            }
            // 只有用户点「加载更多」才走到这里：给 toast 就行，不再把红字长期挂在列表上方
            .onFailure { onToast(friendlyError(it)) }
        store.loadingMore = false
    }

    /** 手动下拉：合并第一页并回到最新；离线时只给轻提示，不清缓存。 */
    suspend fun manualRefresh() {
        if (refreshing) return
        refreshing = true
        notice = ""
        runCatching { refreshFirst(toTop = true) }
            .onFailure { e ->
                notice = if (e is com.otscup.otsmail.data.ApiException) friendlyError(e)
                else "当前离线，显示缓存内容"
            }
        refreshing = false
    }

    fun pick(p: String) {
        if (p == pill) return
        AllMailUi.pill = p
    }

    LaunchedEffect(key, MailSync.rev) {
        // 冷启动：先渲染 SQLite 里的缓存，再后台静默刷新
        if (items.isEmpty()) {
            val disk = MailCache.readPages(key)
            if (disk != null && items.isEmpty()) {
                items.addAll(disk.items)
                store.total = disk.total
                store.page = disk.page
                store.updatedAt = disk.updatedAt
                store.scrollIndex = disk.scrollIndex
                store.scrollOffset = disk.scrollOffset
                restoreToken++
            } else {
                store.loading = true
            }
        }
        if (MailSync.rev != store.syncRev) {
            store.syncRev = MailSync.rev
            store.updatedAt = 0L
        }
        if (items.isEmpty() || System.currentTimeMillis() - store.updatedAt > MailCache.LIST_TTL) {
            runCatching { refreshFirst() }
                .onFailure { if (items.isEmpty()) store.error = friendlyError(it) }
        }
        store.loading = false
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("全部邮件", onBack = onBack)
        Column(Modifier.padding(horizontal = d.sp4.dp, vertical = d.sp2.dp)) {
            OTSearchPill(value = kwText, placeholder = "搜索发件人、主题、邮箱", onValueChange = { kwText = it })
            Spacer(Modifier.height(d.sp2.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(d.sp2.dp),
            ) {
                listOf("all" to "全部", "inbox" to "收件", "sent" to "发件", "deleted" to "已删除", "noone" to "无人收件").forEach { (k, label) ->
                    val on = pill == k
                    Box(
                        Modifier
                            .background(if (on) c.blue else c.bgInput, RoundedCornerShape(50))
                            .pressable(shape = RoundedCornerShape(50)) { pick(k) }
                            .padding(horizontal = d.sp3.dp, vertical = OTSpace.s1),
                    ) { Text(label, style = t.caption1, color = if (on) Color.White else c.label2) }
                }
            }
            Text(
                "全站只读：不提供批量删除/移动（网页端 /allEmail/delete 无 userId 过滤，会跨用户误删）",
                style = t.caption2, color = c.label3,
                modifier = Modifier.padding(top = OTSpace.s2),
            )
        }
        if (store.error.isNotEmpty()) {
            Text(store.error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        }
        if (notice.isNotEmpty()) {
            Text(notice, style = t.footnote, color = c.label2, modifier = Modifier.padding(horizontal = d.sp4.dp, vertical = d.sp2.dp))
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { manualRefresh() } },
            state = ptrState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = ptrState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = c.bgCard,
                    color = c.blue,
                )
            },
        ) {
        if (items.isEmpty() && store.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = c.blue, strokeWidth = 2.dp, modifier = Modifier.size(22.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            item(key = "count") {
                Column(Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp2.dp)) {
                    Text(
                        "全站共 ${store.total} 封 · 已加载 ${items.size} 封",
                        style = t.footnote, color = c.label2,
                    )
                    Text(
                        "全站口径：同网页 /allEmail/list，含已删除；抽屉「收件箱」徽标只统计我的邮箱，两者不可比",
                        style = t.caption2, color = c.label3,
                    )
                }
            }
            items(items, key = { it.emailId }) { m ->
                OTMailRow(
                    from = m.from.ifEmpty { m.name },
                    subject = buildString {
                        val tag = when {
                            m.status == 7 -> "无人收件"
                            m.type == 1 -> "发件"
                            else -> "收件"
                        }
                        append("[$tag] ").append(m.subject.ifEmpty { "(无主题)" })
                    },
                    snippet = m.text.take(90),
                    time = TimeFmt.rowTime(m.time),
                    unread = m.unread,
                    starred = m.starred,
                    category = null,
                    onOpen = {
                        // /email/detail 按 userId 过滤，别人的邮件（含 userId=0 的无人收件）必然 404；
                        // 这类走全站只读详情，正文取 /allEmail/list 带回的整行内容。
                        if (myUserId > 0 && m.userId != myUserId) {
                            onOpenReadOnly(m.emailId)
                        } else {
                            onOpen(m.emailId)
                        }
                    },
                    onLongPress = null,
                    selectionMode = false,
                    selected = false,
                    onStar = {},
                    // 全站数据里同一账户/不同账户混杂：行尾显示收件人姓名（服务端 toName），
                    // 没有姓名才退回收件邮箱，最后退回收件人字段
                    mailboxLabel = m.toName.takeIf { it.isNotBlank() }
                        ?: m.toEmail.takeIf { it.isNotBlank() }
                        ?: m.recipient.takeIf { it.isNotBlank() },
                    // 所属用户（登录邮箱）单列一行小字；userId 只用来判断是否本人，不直接展示
                    ownerLabel = m.userEmail.takeIf { it.isNotBlank() && it != m.toEmail },
                )
                HorizontalDivider(color = c.separatorOpaque, thickness = 0.5.dp, modifier = Modifier.padding(start = 58.dp))
            }
            if (items.isNotEmpty() && items.size < store.total) {
                item(key = "more") {
                    Box(
                        Modifier.fillMaxWidth().tapArea { if (!store.loadingMore) scope.launch { loadMore() } }.padding(d.sp4.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (store.loadingMore) "加载中…" else "加载更多", style = t.subhead, color = c.blue) }
                }
            }
            }
        }
        }
    }
}

/* ---------------- 用户 ---------------- */

@Composable
fun UsersScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<JSONObject>() }
    var total by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf("") }
    var ufilter by remember { mutableStateOf(-1) }
    var keyword by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var roleNames by remember { mutableStateOf(mapOf<Long, String>()) }
    // 搜索/筛选/翻页会并发触发，用递增 token 丢弃过期响应，避免旧结果覆盖新结果
    var seq by remember { mutableStateOf(0) }

    fun load(next: Int) {
        val token = ++seq
        busy = true
        scope.launch {
            val result = runCatching {
                if (roleNames.isEmpty()) {
                    val map = mutableMapOf<Long, String>()
                    for (r in Api.roles()) map[r.optLong("roleId")] = r.optString("roleName")
                    roleNames = map
                }
                Api.users(next, ufilter, keyword)
            }
            if (token != seq) return@launch
            result.onSuccess { (list, n) ->
                if (next == 1) items.clear()
                items.addAll(list.filter { row ->
                    items.none { it.optLong("userId") == row.optLong("userId") }
                })
                total = n
                page = next
                error = ""
            }.onFailure { error = it.message ?: "读取失败" }
            busy = false
        }
    }

    LaunchedEffect(keyword, ufilter) {
        delay(250)
        load(1)
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("用户管理", onBack = onBack)
        Column(Modifier.padding(horizontal = d.sp4.dp, vertical = d.sp2.dp)) {
            OTSearchPill(value = keyword, placeholder = "搜索邮箱", onValueChange = { keyword = it })
            Spacer(Modifier.height(d.sp2.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.sp2.dp)) {
                listOf(-1 to "全部", 0 to "活跃", 1 to "封禁").forEach { (k, label) ->
                    val on = ufilter == k
                    Box(Modifier.background(if (on) c.blue else c.bgInput, RoundedCornerShape(50)).pressable(shape = RoundedCornerShape(50)) { ufilter = k }.padding(horizontal = d.sp3.dp, vertical = OTSpace.s1)) {
                        Text(label, style = t.caption1, color = if (on) Color.White else c.label2)
                    }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            item { Text("共 $total 个用户 · 已加载 ${items.size} 个", style = t.footnote, color = c.label2, modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp2.dp)) }
            items(items) { u ->
                val type = u.optLong("type")
                val email = u.optString("email").ifEmpty { u.optString("username") }
                // oauth 没有绑定时 leftJoin 返回 JSON null，optString 会得到字符串 "null"，要当空处理
                val uname = u.optString("username").takeUnless { it == "null" || it == "undefined" }.orEmpty()
                val role = if (type == 0L) "管理员" else roleNames[type] ?: "普通用户"
                val banned = u.optInt("status") > 0
                val created = u.optString("createTime").take(10)
                val meta = listOfNotNull(
                    uname.takeIf { it.isNotEmpty() && it != email },
                    role,
                    if (banned) "已封禁" else "正常",
                    created.takeIf { it.isNotEmpty() }?.let { "注册于 $it" },
                ).joinToString(" · ")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OT.dimens.rowMin.dp)
                        .pressable(scale = 0.99f) { }
                        .padding(horizontal = OT.dimens.screenPad.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OTAvatar(email, 34)
                    Spacer(Modifier.width(OT.dimens.sp3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(email, style = t.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(meta, style = t.footnote, color = if (banned) c.red else c.label2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(painterResource(R.drawable.ot_chevron_right), contentDescription = null, tint = c.label3, modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = c.separatorOpaque, thickness = 0.5.dp, modifier = Modifier.padding(start = 58.dp))
            }
            if (items.isNotEmpty() && items.size < total.toInt()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().tapArea { load(page + 1) }.padding(d.sp4.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (busy) "加载中…" else "加载更多", style = t.subhead, color = c.blue) }
                }
            }
        }
    }
}

/* ---------------- 权限 ---------------- */

@Composable
fun RolesScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<JSONObject>() }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch { runCatching { Api.roles() }.onSuccess { list ->
            items.clear()
            for (x in list) items.add(x)
        }.onFailure { error = it.message ?: "读取失败" } }
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("权限控制", onBack = onBack)
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(items) { r ->
                val permCount = (r.optJSONArray("permIds") ?: JSONArray()).length()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OT.dimens.rowMin.dp)
                        .pressable(scale = 0.99f) { }
                        .padding(horizontal = OT.dimens.screenPad.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(34.dp).background(c.purple.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ot_shield), null, tint = c.purple, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(OT.dimens.sp3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.optString("roleName"), style = t.body, color = c.label)
                        Text("排序 ${r.optInt("sort")} · $permCount 项权限", style = t.footnote, color = c.label2)
                    }
                    Icon(painterResource(R.drawable.ot_chevron_right), null, tint = c.label3, modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = c.separatorOpaque, thickness = 0.5.dp, modifier = Modifier.padding(start = 58.dp))
            }
            item { Text("提示：角色权限在样张中以开关树展示，当前仅列表查看。", style = t.footnote, color = c.label2, modifier = Modifier.padding(d.sp5.dp)) }
        }
    }
}

/* ---------------- 注册密钥 ---------------- */

@Composable
fun RegKeysScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<JSONObject>() }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch {
            runCatching { Api.regKeys(1) }.onSuccess { pair ->
                val list = pair.first
                items.clear()
                for (x in list) items.add(x)
            }.onFailure { error = it.message ?: "读取失败" }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("注册密钥", onBack = onBack)
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(items) { rk ->
                val code = rk.optString("key")
                val used = rk.optInt("used")
                val count = rk.optInt("count")
                val expireTime = rk.optString("expireTime")
                val exp = if (expireTime.length >= 10) expireTime.substring(0, 10) else ""
                val expired = used >= count || exp.isEmpty()
                val roleName = rk.optString("roleName").ifEmpty { "普通角色" }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OT.dimens.rowMin.dp)
                        .padding(horizontal = OT.dimens.screenPad.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(34.dp).background(c.green.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ot_key), null, tint = c.green, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(OT.dimens.sp3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(code, style = t.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$roleName · 已用 $used / $count 次 · ${if (exp.isEmpty()) "已过期" else "$exp 到期"}", style = t.footnote, color = c.label2)
                    }
                    Box(Modifier.background((if (expired) c.label3 else c.green).copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 4.dp)) {
                        Text(if (expired) "失效" else "有效", style = t.caption2, color = if (expired) c.label3 else c.green)
                    }
                }
                HorizontalDivider(color = c.separatorOpaque, thickness = 0.5.dp, modifier = Modifier.padding(start = 58.dp))
            }
        }
    }
}

/* ---------------- 分析 ---------------- */

private val nf = NumberFormat.getNumberInstance(Locale.US)
private fun fmt(n: Int): String = nf.format(n.toLong())

@Composable
private fun Stat(label: String, value: Int, subOk: Int? = null, subDel: Int? = null, modifier: Modifier = Modifier) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    Column(
        modifier.background(c.bgCard, RoundedCornerShape(16.dp)).padding(d.sp3.dp),
    ) {
        Text(label, style = t.caption1, color = c.label2)
        Text(fmt(value), style = t.title1.copy(fontWeight = FontWeight.SemiBold), color = c.label)
        if (subOk != null || subDel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (subOk != null) Text("活跃 ${fmt(subOk)}", style = t.caption1, color = c.green)
                if (subOk != null && subDel != null) Spacer(Modifier.size(OTSpace.s1))
                if (subDel != null) Text("已删除 ${fmt(subDel)}", style = t.caption1, color = c.label2)
            }
        }
    }
}

@Composable
private fun HBar(name: String, total: Int, max: Int, pct: Int) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = d.sp5.dp, vertical = OTSpace.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name, style = t.caption1, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(84.dp).padding(end = d.sp2.dp),
        )
        Box(Modifier.weight(1f).height(6.dp).background(c.bgInput, RoundedCornerShape(3.dp))) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((total.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f))
                    .background(c.blue, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.size(d.sp2.dp))
        Text("$pct%", style = t.caption1, color = c.label2, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun Bars(data: JSONArray, label: String) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val max = (0 until data.length()).maxOfOrNull { data.optJSONObject(it).optInt("total") } ?: 1
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = d.sp4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until data.length()) {
            val v = data.optJSONObject(i).optInt("total")
            val f = (v.toFloat() / max.coerceAtLeast(1)).coerceAtLeast(0.05f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(f)
                    .background(
                        if (i == data.length() - 1) c.blue else c.blue.copy(alpha = 0.32f),
                        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                    ),
            )
        }
    }
    Text(label, style = t.caption1, color = c.label3, modifier = Modifier.padding(start = OT.dimens.sp5.dp).padding(top = OTSpace.s1))
}

@Composable
fun AnalysisScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    var e by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { Api.analysisEcharts() }.onSuccess { e = it }.onFailure { error = it.message ?: "读取失败" }
    }

    val nc = e?.optJSONObject("numberCount")
    val names = e?.optJSONObject("receiveRatio")?.optJSONArray("nameRatio") ?: JSONArray()
    val userDay = e?.optJSONArray("userDayCount") ?: JSONArray()
    val receiveDay = e?.optJSONObject("emailDayCount")?.optJSONArray("receiveDayCount") ?: JSONArray()
    val receiveTotal = nc?.optInt("receiveTotal") ?: 0
    val maxName = (0 until names.length()).maxOfOrNull { names.optJSONObject(it).optInt("total") } ?: 1

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("分析", onBack = onBack)
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OTGroupHeader("总览")
            Row(Modifier.fillMaxWidth().padding(horizontal = d.sp4.dp), horizontalArrangement = Arrangement.spacedBy(d.sp2.dp)) {
                Stat("总收件", nc?.optInt("receiveTotal") ?: 0, nc?.optInt("normalReceiveTotal"), nc?.optInt("delReceiveTotal"), Modifier.weight(1f))
                Stat("总发件", nc?.optInt("sendTotal") ?: 0, nc?.optInt("normalSendTotal"), nc?.optInt("delSendTotal"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(d.sp2.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = d.sp4.dp), horizontalArrangement = Arrangement.spacedBy(d.sp2.dp)) {
                Stat("邮箱", nc?.optInt("accountTotal") ?: 0, nc?.optInt("normalAccountTotal"), nc?.optInt("delAccountTotal"), Modifier.weight(1f))
                Stat("总用户", nc?.optInt("userTotal") ?: 0, nc?.optInt("normalUserTotal"), nc?.optInt("delUserTotal"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(d.sp3.dp))
            OTGroupHeader("邮件来源")
            if (names.length() == 0) {
                Text("暂无数据", style = t.footnote, color = c.label3, modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp2.dp))
            } else {
                for (i in 0 until names.length()) {
                    val r = names.optJSONObject(i)
                    val total = r.optInt("total")
                    HBar(name = r.optString("name"), total = total, max = maxName, pct = if (receiveTotal > 0) total * 100 / receiveTotal else 0)
                }
            }
            Spacer(Modifier.height(d.sp3.dp))
            OTGroupHeader("用户增长")
            Bars(userDay, "最近 14 天")
            Spacer(Modifier.height(d.sp3.dp))
            OTGroupHeader("邮件增长")
            Bars(receiveDay, "最近 14 天")
            Spacer(Modifier.height(d.sp3.dp))
            OTGroupHeader("今日发送")
            Text(
                fmt(e?.optInt("daySendTotal") ?: 0),
                style = t.title1.copy(fontWeight = FontWeight.SemiBold),
                color = c.label,
                modifier = Modifier.padding(horizontal = d.sp5.dp),
            )
            Text(
                "数据来自服务端分析缓存（/analysis/echarts）。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp3.dp),
            )
            Spacer(Modifier.height(d.sp6.dp))
        }
    }
}

/* ---------------- 404 ---------------- */

@Composable
fun NotFoundScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    Column(
        Modifier.fillMaxSize().background(c.bgCard).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MailOutline, contentDescription = null, tint = c.label3, modifier = Modifier.size(64.dp))
            Icon(
                Icons.Outlined.Close, contentDescription = null, tint = c.label3,
                modifier = Modifier.size(22.dp).offset(x = 16.dp, y = 16.dp),
            )
        }
        Text(
            "404",
            style = t.largeTitle.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            color = c.label3,
        )
        Text("页面不存在", style = t.title3, color = c.label, modifier = Modifier.padding(top = d.sp2.dp))
        Text("链接可能已经失效，或者你没有这个页面的权限。", style = t.subhead, color = c.label2, modifier = Modifier.padding(top = OTSpace.s1))
        Box(
            Modifier
                .padding(top = d.sp5.dp)
                .widthIn(min = 180.dp)
                .background(c.blue, RoundedCornerShape(10.dp))
                .pressable(scale = 0.97f) { onBack() }
                .padding(horizontal = d.sp5.dp, vertical = d.sp3.dp),
        ) { Text("返回收件箱", style = t.body, color = Color.White, textAlign = TextAlign.Center) }
    }
}
