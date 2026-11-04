package com.otscup.otsmail.ui

import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AllInbox
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otscup.otsmail.R
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.data.MailItem
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.motion.MailSwipeRow
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 滑动删除的延迟提交作用域：不跟 composition 走，3 秒内退到别的页面也照样提交。 */
private val swipeUndoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

private data class Mailbox(val label: String, val key: String)

private val mailboxes = listOf(
    Mailbox("收件箱", "receive"),
    Mailbox("未读", "unread"),
    Mailbox("已标星", "starred"),
    Mailbox("垃圾箱", "spam"),
    Mailbox("已删除", "deleted"),
)

/**
 * 抽屉「分类」区的入口。网页端侧边栏只列 primary/social/promotion/updates/important，
 * 已把 ads 从分类标签栏和「移动到分类」里过滤掉（email-scroll/index.vue 的 tabCategories），
 * 服务端也没有下发分类开关的配置字段，所以这里跟随上游静态去掉 ads。
 * categoryLabel("ads") 保留：AI 仍会给邮件打 ads 标签，列表/详情要能显示。
 */
internal val categories = listOf(
    "primary" to "主要",
    "social" to "社交",
    "promotion" to "推广",
    "updates" to "动态",
)

/**
 * 列表入口状态（mailbox / 分类 / 搜索词 / 全部帐户）放进程级：
 * 进详情会销毁收件箱整棵 composition，remember 存不住，返回时就会回到默认的「收件箱·全部帐户」。
 */
internal object InboxUi {
    var boxKey by mutableStateOf("receive")
    var category by mutableStateOf("")
    var keyword by mutableStateOf("")
    var allAccounts by mutableStateOf(true)
    var accountTick by mutableStateOf(0)
    /** 「全部帐户」已选中时再点一次 = 强制重拉（不改变 listKey，只用来打破「空列表卡住」）。 */
    var forceTick by mutableStateOf(0)
}

/**
 * 抽屉里「分类 / 账户」两组的展开状态：进程级，和 InboxUi 同级。
 * 进详情再返回、切账户都不丢；不落盘，冷启动回到折叠态（默认只露前 3 项）。
 * 两组互不覆盖：展开分类不影响账户，反之亦然。
 */
internal object DrawerUi {
    var categoriesExpanded by mutableStateOf(false)
    var accountsExpanded by mutableStateOf(false)
}

/** 列表是「日期 stickyHeader + 邮件行」拼起来的扁平索引，恢复滚动位置要按这个口径算。 */
private fun flatIndexOf(items: List<MailItem>, emailId: Long): Int? {
    var idx = 0
    for ((_, list) in items.groupBy { TimeFmt.dayKey(it.time) }) {
        idx++ // 分组头
        val j = list.indexOfFirst { it.emailId == emailId }
        if (j >= 0) return idx + j
        idx += list.size
    }
    return null
}

@Composable
private fun categoryIcon(key: String): Painter = when (key) {
    "social" -> painterResource(R.drawable.ot_people)
    "promotion" -> painterResource(R.drawable.ot_tag)
    "updates" -> painterResource(R.drawable.ot_bell)
    "ads" -> painterResource(R.drawable.ot_tag)
    else -> painterResource(R.drawable.ot_inbox)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    session: Session,
    onOpenDetail: (Long) -> Unit,
    onCompose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenAi: () -> Unit,
    /** 行内右滑 → 回复：由 App 负责拿原文并进写信页 */
    onSwipeReply: (MailItem) -> Unit = {},
) {
    val t = OT.type
    val c = OT.colors
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    // 抽屉打开时按返回键先收起抽屉，而不是直接退出 App
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    // 入口选择放在 InboxUi 里：进详情会把这里的 composition 整个销毁，
    // 只有进程级状态能让返回时还是同一个 mailbox / 分类 / 搜索词。
    val current = mailboxes.firstOrNull { it.key == InboxUi.boxKey } ?: mailboxes[0]
    val keyword = InboxUi.keyword
    /** 输入框里的字（防抖 280ms 后才成为真正生效的 keyword，避免每敲一个字就换一份列表快照） */
    var kwText by remember { mutableStateOf(InboxUi.keyword) }
    LaunchedEffect(kwText) {
        if (kwText != InboxUi.keyword) {
            kotlinx.coroutines.delay(280)
            InboxUi.keyword = kwText
        }
    }
    val category = if (current.key == "receive") InboxUi.category else ""
    val allAccounts = InboxUi.allAccounts
    // 读一下 accountTick，保证切换帐号（session.accountId 不是 State）也能重组
    val accountId = session.accountId.also { InboxUi.accountTick }

    var accounts by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var catCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    /**
     * 计数必须跟随抽屉当前选中的范围：
     * 单帐户 → accountId + allReceive=0（服务端才按该 account 统计）；
     * 「全部帐户」→ accountId=0 + allReceive=1（该 user 名下全部 account 汇总）。
     * 之前不带参数，服务端永远返回用户级总数，切帐户后徽标看起来不刷新。
     */
    suspend fun loadCounts() {
        val acc = if (allAccounts) 0L else accountId
        runCatching { Api.menuCounts(acc, allAccounts) }.onSuccess { counts = it }
        runCatching { Api.categoryCounts(acc, allAccounts) }.onSuccess { catCounts = it }
    }
    var showAvatars by remember { mutableStateOf(session.showAvatars) }
    // 多帐户混合列表用：accountId → 邮箱地址（/account/list 已有，不新增服务端依赖）
    val accountEmails = remember(accounts) { accounts.toMap() }
    // 行内收件邮箱：只有「全部帐户」才显示，单帐户列表每行都是同一个地址，不重复刷屏
    fun mailboxLabelOf(mail: com.otscup.otsmail.data.MailItem): String? {
        if (!allAccounts) return null
        accountEmails[mail.accountId]?.takeIf { it.isNotBlank() }?.let { return it }
        return mail.recipient.takeIf { it.isNotBlank() && mail.type != 1 }
    }
    var addSheet by remember { mutableStateOf(false) }
    var newEmail by remember { mutableStateOf("") }

    // 多选：长按某行进入，标题栏变「已选 N 封」，返回键退出并保持滚动位置
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var batchBusy by remember { mutableStateOf(false) }
    var moveSheet by remember { mutableStateOf(false) }
    fun exitSelect() {
        selectMode = false
        selected = emptySet()
    }
    // 注册在抽屉之后 → 多选时返回键优先退出多选，而不是退到桌面
    BackHandler(enabled = selectMode) { exitSelect() }

    // 列表快照按（账号, mailbox, 分类, 关键词）存进 MailCache：
    // 内存负责「返回详情」原地恢复，SQLite 负责冷启动。
    val listKey = buildString {
        append("acct:").append(if (allAccounts) "all" else accountId.toString())
        append("|mb:").append(current.key)
        append("|cat:").append(category)
        append("|kw:").append(keyword.trim())
    }
    val store = MailCache.list(listKey, if (allAccounts) 0L else accountId)
    val items = store.items
    // token 用来在「从 SQLite 读到滚动位置」后重建 LazyListState，
    // 这样首帧布局就落在同一行，而不是先到顶部再 scrollToItem。
    var restoreToken by remember { mutableStateOf(0) }
    val listState = remember(store, restoreToken) {
        // 磁盘里的滚动索引可能大于当前能渲染的行数（换范围/缓存被裁剪后），
        // LazyListState 拿到越界初始索引会整屏空白，这里先夹到有效区间。
        val maxIndex = (items.size - 1).coerceAtLeast(0)
        val index = store.scrollIndex.coerceIn(0, maxIndex)
        LazyListState(index, if (index == store.scrollIndex) store.scrollOffset else 0)
    }

    // 下拉刷新：只有手指下拉才转圈；离线时给一句轻提示，不清缓存、不打断阅读
    var refreshing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val ptrState = rememberPullToRefreshState()
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            kotlinx.coroutines.delay(2600)
            notice = ""
        }
    }

    // 滑动删除：先把行从内存列表拿走，3 秒后才真正调服务端；撤销只是放回原位。
    // 不立刻写缓存/删接口，撤销才可能是"真的没删过"，而不是本地补一条假数据。
    var undoMail by remember { mutableStateOf<MailItem?>(null) }
    var undoIndex by remember { mutableStateOf(0) }
    var undoJob by remember { mutableStateOf<Job?>(null) }

    fun countKeyFor(box: String): String = when (box) {
        "receive" -> "inbox"
        "unread" -> "unread"
        "starred" -> "starred"
        "deleted" -> "deleted"
        "spam" -> "spam"
        else -> ""
    }

    fun bumpCount(key: String, delta: Int) {
        if (key.isEmpty()) return
        counts = counts + (key to ((counts[key] ?: 0) + delta).coerceAtLeast(0))
    }

    fun swipeDelete(mail: MailItem) {
        val idx = items.indexOfFirst { it.emailId == mail.emailId }
        if (idx < 0) return
        undoJob?.cancel()
        items.removeAt(idx)
        store.total = (store.total - 1).coerceAtLeast(0)
        bumpCount(countKeyFor(current.key), -1)
        undoMail = mail
        undoIndex = idx
        val at = idx
        undoJob = swipeUndoScope.launch {
            delay(3000)
            undoMail = null
            runCatching { Api.trash(listOf(mail.emailId)) }
                .onSuccess { MailCache.removeItem(mail.emailId) }
                .onFailure {
                    // 服务端没删掉：把行放回原位，别让界面和数据对不上
                    if (items.none { it.emailId == mail.emailId }) {
                        items.add(at.coerceIn(0, items.size), mail)
                    }
                    store.total += 1
                    bumpCount(countKeyFor(current.key), +1)
                    notice = "删除失败，已恢复"
                }
        }
    }

    fun undoDelete() {
        undoJob?.cancel()
        undoJob = null
        val mail = undoMail ?: return
        undoMail = null
        if (items.none { it.emailId == mail.emailId }) {
            items.add(undoIndex.coerceIn(0, items.size), mail)
        }
        store.total += 1
        bumpCount(countKeyFor(current.key), +1)
        notice = "已撤销删除"
    }

    // 滚动位置实时写回快照；返回时用这份值初始化 LazyListState，能精确到同一行
    LaunchedEffect(listState, store) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (i, o) ->
                // 列表还没内容时不要把 0 写回去，否则会覆盖掉磁盘里存的位置
                if (items.isNotEmpty()) {
                    store.scrollIndex = i
                    store.scrollOffset = o
                    MailCache.saveScroll(store)
                }
            }
    }

    suspend fun fetchRaw(next: Int): org.json.JSONObject {
        val mb = if (current.key == "spam") null else current.key
        val cat = when {
            current.key == "spam" -> "spam"
            current.key == "receive" -> category
            else -> ""
        }
        return when {
            current.key == "starred" -> Api.starredRaw(next)
            else -> Api.emailsRaw(accountId, next, mb, keyword, cat, allAccounts)
        }
    }

    /**
     * 首屏刷新：增量合并而不是重建；自动刷新用当前可见行做锚点，新邮件插进来也不跳走。
     * toTop = true 只给「用户手动下拉」用——那是明确要回到最新，允许回到顶部。
     */
    suspend fun refreshFirst(toTop: Boolean = false) {
        val (fresh, total) = Api.parseList(fetchRaw(1))
        val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }?.key as? Long
        val anchorOffset = listState.firstVisibleItemScrollOffset
        val ids = fresh.mapTo(HashSet()) { it.emailId }
        val merged = ArrayList<MailItem>(fresh.size + items.size)
        merged.addAll(fresh)
        items.forEach { if (it.emailId !in ids) merged.add(it) }
        items.clear()
        items.addAll(merged)
        store.total = total.toInt()
        store.page = maxOf(store.page, 1)
        store.error = ""
        MailCache.savePage(store, 1, fresh, total.toInt())
        if (toTop) {
            // 无条件回到列表最顶端：firstVisibleItemIndex 可能已经是 0，但第一行（日期头）还滚出去了一截
            listState.scrollToItem(0, 0)
        } else if (anchor != null) {
            val target = flatIndexOf(items, anchor)
            if (target != null && target != listState.firstVisibleItemIndex) {
                listState.scrollToItem(target, anchorOffset)
            }
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
            // 滚动触底是自动行为，失败必须静默：离线浏览缓存时不能反复往标题下弹错误
        store.loadingMore = false
    }

    /** 手动下拉：合并第一页并回到最新；失败时离线给「当前离线」，服务端报错给可读原因。 */
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

    /** 批量动作：逐条串行；失败只汇总失败数，成功项已经由 MailBatchOps 同步列表与缓存。 */
    fun runBatch(action: MailBatch) {
        if (batchBusy || selected.isEmpty()) return
        val ids = selected.toList()
        batchBusy = true
        scope.launch {
            val failed = MailBatchOps.run(action, ids)
            batchBusy = false
            exitSelect()
            notice = if (failed > 0) "$failed 封失败，其余已处理" else "已处理 ${ids.size} 封"
            loadCounts()
        }
    }

    fun runMove(target: String) {
        if (batchBusy || selected.isEmpty()) return
        val ids = selected.toList()
        batchBusy = true
        moveSheet = false
        scope.launch {
            val failed = MailBatchOps.move(target, ids)
            // 在某个分类里把邮件移走：该视图不再包含它们
            if (category.isNotEmpty() && target != category) ids.forEach { MailCache.removeRow(listKey, it) }
            batchBusy = false
            exitSelect()
            notice = if (failed > 0) "$failed 封失败，其余已处理" else "已移动 ${ids.size} 封"
            loadCounts()
        }
    }

    LaunchedEffect(listKey) {
        // 冷启动：内存没有 → 先读 SQLite 渲染，再后台刷新（不显示骨架屏/loading）
        if (items.isEmpty()) {
            val disk = MailCache.readPages(listKey)
            if (disk != null && items.isEmpty()) {
                items.addAll(disk.items)
                store.total = disk.total
                store.page = disk.page
                store.updatedAt = disk.updatedAt
                val maxIndex = (disk.items.size - 1).coerceAtLeast(0)
                store.scrollIndex = disk.scrollIndex.coerceIn(0, maxIndex)
                store.scrollOffset = if (store.scrollIndex == disk.scrollIndex) disk.scrollOffset else 0
                // 重建 LazyListState，首帧就落在原来那一行
                restoreToken++
            } else {
                store.loading = true
            }
        }
        // MailSync.rev 变过 = 详情/写信对服务端做过写操作，强制刷新
        if (MailSync.rev != store.syncRev) {
            store.syncRev = MailSync.rev
            store.updatedAt = 0L
        }
        if (items.isEmpty() || System.currentTimeMillis() - store.updatedAt > MailCache.LIST_TTL) {
            runCatching { refreshFirst() }
                .onFailure { if (items.isEmpty()) store.error = friendlyError(it) }
        }
        store.loading = false
        runCatching { Api.accounts() }.onSuccess { accounts = it }
        loadCounts()
    }

    // 已经在「全部帐户·收件箱」时再点抽屉里的「全部帐户」不改变 listKey，
    // 这里单独给一次强制重拉：首屏失败（弱网/超时）后列表会停在空态，重进入口要能恢复。
    // handledTick 记住本页已经处理过的信号，避免「进详情再返回」时又白刷一次。
    var handledForceTick by remember { mutableStateOf(InboxUi.forceTick) }
    LaunchedEffect(InboxUi.forceTick) {
        if (InboxUi.forceTick == handledForceTick) return@LaunchedEffect
        handledForceTick = InboxUi.forceTick
        store.error = ""
        runCatching { refreshFirst() }
            .onFailure { if (items.isEmpty()) store.error = friendlyError(it) }
    }

    // 滚动到底部前 4 行时自动翻页（稿子没有"加载更多"按钮）
    LaunchedEffect(listState, store) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (last, count) ->
                if (items.size in 1 until store.total && last >= count - 4) loadMore()
            }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = c.scrim,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = c.bgDrawer,
                drawerShape = RoundedCornerShape(topEnd = OT.dimens.rDrawer.dp, bottomEnd = OT.dimens.rDrawer.dp),
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier
                    .fillMaxHeight()
                    .width(OT.dimens.drawerW.dp),
            ) {
                val close: () -> Unit = { scope.launch { drawer.close() } }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
                        Column {
                            // header：真实头像（服务端 avatar 优先，其次本地头像，最后姓名首字）
                            Row(
                                Modifier.padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OTAvatar(
                                    name = session.name.ifEmpty { session.email },
                                    size = 46,
                                    imageUrl = session.avatarLocal.ifEmpty { session.avatar },
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("全部帐户", style = t.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                                    Text(session.email, style = t.caption1, color = c.label2, maxLines = 1)
                                }
                            }
                            Column(Modifier.padding(horizontal = 8.dp)) {
                                // 样张顺序：先列帐户，再是"全部帐户"，最后"添加帐户"。
                                // 超过 3 个帐户时默认只露前 3 个，其余收进「更多账户 (N)」；固定项始终可见。
                                val accountLimit = 3
                                val visibleAccountIdx =
                                    if (DrawerUi.accountsExpanded || accounts.size <= accountLimit) {
                                        accounts.indices.toList()
                                    } else {
                                        accounts.indices.take(accountLimit)
                                    }
                                visibleAccountIdx.forEach { i ->
                                    val acc = accounts[i]
                                    val accColor = accColor(i)
                                    DrawerRow(
                                        icon = null,
                                        label = acc.second,
                                        selected = !allAccounts && acc.first == session.accountId,
                                        subhead = true,
                                        onClick = {
                                            if (acc.first != session.accountId) {
                                                session.accountId = acc.first
                                                session.apply()
                                                InboxUi.accountTick++
                                                // 切帐户必须复位分类：旧分类（如「推广」）在新邮箱里
                                                // 往往一封都没有，列表看起来像空的，选中态也残留
                                                InboxUi.category = ""
                                            }
                                            InboxUi.allAccounts = false
                                            close()
                                        },
                                        leading = { OTAccountDot(accColor, unread = false, size = 12) },
                                    )
                                }
                                if (accounts.size > accountLimit) {
                                    DrawerRow(
                                        icon = null,
                                        label = if (DrawerUi.accountsExpanded) {
                                            "收起账户"
                                        } else {
                                            "更多账户 (${accounts.size - accountLimit})"
                                        },
                                        selected = false,
                                        subhead = true,
                                        onClick = { DrawerUi.accountsExpanded = !DrawerUi.accountsExpanded },
                                        trailing = {
                                            Icon(
                                                painterResource(R.drawable.ot_chevron_right),
                                                contentDescription = null,
                                                tint = c.label3,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .rotate(if (DrawerUi.accountsExpanded) -90f else 90f),
                                            )
                                        },
                                    )
                                }
                                DrawerRow(
                                    icon = null,
                                    label = "全部帐户",
                                    selected = allAccounts,
                                    subhead = true,
                                    onClick = {
                                        // 「全部帐户」= 所有帐户的收件箱：必须把 mailbox/分类一起复位，
                                        // 否则从「垃圾箱/已删除/某个空分类」进来会继续带着旧过滤条件，看起来就是空的
                                        val sameScope = allAccounts && InboxUi.boxKey == mailboxes[0].key && InboxUi.category.isEmpty()
                                        InboxUi.allAccounts = true
                                        InboxUi.boxKey = mailboxes[0].key
                                        InboxUi.category = ""
                                        // 已经在这个范围时再点一次 = 手动重拉（清掉首屏失败留下的空列表）
                                        if (sameScope) InboxUi.forceTick++
                                        close()
                                    },
                                    trailing = {
                                        Icon(painterResource(R.drawable.ot_chevron_right), contentDescription = null, tint = c.label3, modifier = Modifier.size(16.dp))
                                    },
                                )
                                AddAccountRow {
                                    close()
                                    addSheet = true
                                }
                            }
                            OTDash()
                            Column(Modifier.padding(horizontal = 8.dp)) {
                                mailboxes.forEach { box ->
                                    val boxN = when (box.key) {
                                        "receive" -> counts["inbox"] ?: 0
                                        "unread" -> counts["unread"] ?: 0
                                        "starred" -> counts["starred"] ?: 0
                                        "deleted" -> counts["deleted"] ?: 0
                                        "spam" -> counts["spam"] ?: 0
                                        else -> 0
                                    }
                                    DrawerRow(
                                        icon = boxIcon(box.key),
                                        label = box.label,
                                        selected = category.isEmpty() && box == current,
                                        onClick = {
                                            InboxUi.boxKey = box.key
                                            InboxUi.category = ""
                                            close()
                                        },
                                        trailing = {
                                            if (boxN > 0 && (box.key == "receive" || box.key == "unread")) {
                                                OTBadge(boxN)
                                            } else if (boxN > 0) {
                                                Text("$boxN", style = t.caption1, color = c.label2)
                                            }
                                        },
                                    )
                                }
                            }
                            OTDash()
                            Column(Modifier.padding(horizontal = 8.dp)) {
                                DrawerLabel("分类")
                                // 分类同理：默认前 3 项，其余收进「更多分类 (N)」；≤3 项时不显示该行。
                                val categoryLimit = 3
                                val visibleCategories =
                                    if (DrawerUi.categoriesExpanded || categories.size <= categoryLimit) {
                                        categories
                                    } else {
                                        categories.take(categoryLimit)
                                    }
                                visibleCategories.forEach { (key, label) ->
                                    DrawerRow(
                                        icon = categoryIcon(key),
                                        label = label,
                                        selected = category == key,
                                        onClick = {
                                            InboxUi.boxKey = mailboxes[0].key
                                            InboxUi.category = if (InboxUi.category == key) "" else key
                                            close()
                                        },
                                        trailing = {
                                            val n = catCounts[key] ?: 0
                                            if (n > 0) Text("$n", style = t.caption1, color = c.label2)
                                        },
                                    )
                                }
                                if (categories.size > categoryLimit) {
                                    DrawerRow(
                                        icon = null,
                                        label = if (DrawerUi.categoriesExpanded) {
                                            "收起分类"
                                        } else {
                                            "更多分类 (${categories.size - categoryLimit})"
                                        },
                                        selected = false,
                                        onClick = { DrawerUi.categoriesExpanded = !DrawerUi.categoriesExpanded },
                                        trailing = {
                                            Icon(
                                                painterResource(R.drawable.ot_chevron_right),
                                                contentDescription = null,
                                                tint = c.label3,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .rotate(if (DrawerUi.categoriesExpanded) -90f else 90f),
                                            )
                                        },
                                    )
                                }
                                DrawerLabel("其他")
                                DrawerRow(icon = painterResource(R.drawable.ot_people), label = "联系人", selected = false, onClick = onOpenContacts, close = close)
                            }
                            // 设置跟着内容一起滚动，滚到底自然露出，不再固定遮挡分类区
                            OTDivider(strong = true)
                            Column(
                                Modifier
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .navigationBarsPadding(),
                            ) {
                                DrawerRow(
                                    icon = painterResource(R.drawable.ot_gear),
                                    label = "设置",
                                    selected = false,
                                    onClick = onOpenSettings,
                                    close = close,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                if (selectMode) {
                    MailSelectionHeader(
                        count = selected.size,
                        allSelected = items.isNotEmpty() && selected.size == items.size,
                        onExit = { exitSelect() },
                        onToggleAll = {
                            selected = if (items.isNotEmpty() && selected.size == items.size) {
                                emptySet()
                            } else {
                                items.map { it.emailId }.toSet()
                            }
                        },
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.clip(CircleShape).tapArea { scope.launch { drawer.open() } }) {
                            OTAvatar(
                                session.name.ifEmpty { session.email },
                                36,
                                imageUrl = session.avatarLocal.ifEmpty { session.avatar },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OTSearchPill(value = kwText, onValueChange = { kwText = it }) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .tapArea { onOpenAi() },
                                contentAlignment = Alignment.Center,
                            ) {
                                AiStarGradient(22.dp)
                            }
                        }
                    }
                }
                val visibleTitle = when {
                    selectMode -> ""
                    current.key != "receive" -> current.label
                    category.isNotEmpty() -> categoryLabel(category)
                    else -> ""
                }
                if (visibleTitle.isNotEmpty()) OTViewTitle(visibleTitle)
                if (store.error.isNotEmpty()) {
                    Text(store.error, style = t.footnote, color = c.red, modifier = Modifier.padding(16.dp))
                }
                if (notice.isNotEmpty()) {
                    Text(notice, style = t.footnote, color = c.label2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                }
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { scope.launch { manualRefresh() } },
                    state = ptrState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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
                when {
                items.isEmpty() && store.loading ->
                    // 只有确实没有缓存才出现加载态；有缓存时直接渲染内容、后台静默刷新
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.blue, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                items.isEmpty() ->
                    Box(Modifier.fillMaxSize()) {
                        OTEmptyState(
                            icon = if (keyword.isNotBlank()) painterResource(R.drawable.ot_search) else painterResource(R.drawable.ot_inbox),
                            title = if (keyword.isNotBlank()) "没有匹配的邮件" else "还没有邮件",
                            text = if (keyword.isNotBlank()) "换个关键词试试" else "换个邮箱，或稍后再试",
                        )
                    }
                else -> {
                    val groups = items.groupBy { TimeFmt.dayKey(it.time) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().background(c.bgCard),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        groups.forEach { (day, list) ->
                            stickyHeader(key = "h-$day") {
                                Box(Modifier.fillMaxWidth().background(c.bgCard)) { OTDayHeader(TimeFmt.dayLabel(day)) }
                            }
                            list.forEachIndexed { index, mail ->
                                item(key = mail.emailId) {
                                    MailSwipeRow(
                                        enabled = !selectMode && !batchBusy,
                                        onReply = { onSwipeReply(mail) },
                                        onDelete = { swipeDelete(mail) },
                                    ) {
                                    Column(Modifier.fillMaxWidth().background(c.bgCard)) {
                                        // 发丝线从文字起始处开始，不穿过头像（16 + 头像 34 + 间距 10）
                                        if (index > 0) {
                                            OTDivider(inset = 60, endInset = 16)
                                        }
                                        OTMailRow(
                                            from = mail.name.ifEmpty { mail.from },
                                            subject = mail.subject.ifEmpty { "（无主题）" },
                                            snippet = mail.text,
                                            time = TimeFmt.rowTime(mail.time),
                                            unread = mail.unread,
                                            starred = mail.starred,
                                            onOpen = {
                                                if (selectMode) {
                                                    selected = if (mail.emailId in selected) selected - mail.emailId else selected + mail.emailId
                                                } else {
                                                    onOpenDetail(mail.emailId)
                                                }
                                            },
                                            onLongPress = {
                                                if (!selectMode) selectMode = true
                                                selected = if (mail.emailId in selected) selected - mail.emailId else selected + mail.emailId
                                            },
                                            selectionMode = selectMode,
                                            selected = mail.emailId in selected,
                                            onStar = {
                                                val idx = items.indexOfFirst { it.emailId == mail.emailId }
                                                if (idx >= 0) {
                                                    val old = items[idx]
                                                    val next = !old.starred
                                                    items[idx] = old.copy(starred = next)
                                                    scope.launch {
                                                        runCatching {
                                                            if (old.starred) Api.starCancel(old.emailId) else Api.starAdd(old.emailId)
                                                        }.onSuccess {
                                                            // 星标数字与「已标星」列表同步变，避免抽屉徽标和列表打架
                                                            MailCache.setStar(old.emailId, next)
                                                            counts = counts + ("starred" to
                                                                ((counts["starred"] ?: 0) + if (next) 1 else -1).coerceAtLeast(0))
                                                            if (current.key == "starred" && !next) {
                                                                MailCache.removeRow(listKey, old.emailId)
                                                                store.total = (store.total - 1).coerceAtLeast(0)
                                                            }
                                                        }.onFailure {
                                                            val back = items.indexOfFirst { it.emailId == old.emailId }
                                                            if (back >= 0) items[back] = old
                                                            MailCache.setStar(old.emailId, old.starred)
                                                        }
                                                    }
                                                }
                                            },
                                            showAvatar = showAvatars,
                                            // 头像模式按发件人取色；只有"彩点"模式才用帐户色，避免所有邮件都是同一个蓝头像
                                            accountColor = if (showAvatars) null
                                            else accountColorFor(accounts.indexOfFirst { it.first == mail.accountId }, mail.from),
                                            seed = mail.from,
                                            mailboxLabel = mailboxLabelOf(mail),
                                        )
                                    }
                                    }
                                }
                            }
                        }
                        item(key = "footer") {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "共 ${store.total} 封 · 第 ${store.page} / ${((store.total + 19) / 20).coerceAtLeast(1)} 页",
                                    style = t.footnote,
                                    color = c.label2,
                                )
                                if (items.size < store.total) {
                                    Spacer(Modifier.height(12.dp))
                                    CircularProgressIndicator(
                                        color = c.blue,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                }
                }
                }
                if (selectMode) {
                    MailSelectionActions(
                        busy = batchBusy,
                        onAction = { runBatch(it) },
                        onMove = { moveSheet = true },
                    )
                }
            }
            // 滑动删除的撤销条：3 秒内可点，FAB 上方，不挡列表最后一行
            undoMail?.let { m ->
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 80.dp)
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                        .background(c.toastBg)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已删除「${m.subject.ifEmpty { "（无主题）" }}」",
                        style = t.footnote,
                        color = c.toastFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                            .tapArea { undoDelete() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("撤销", style = t.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.onAccent)
                    }
                }
            }
            if (!selectMode) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = 16.dp),
                ) { OTFab(onCompose) }
            }
        }
    }

    if (moveSheet) {
        OTSheet(onDismiss = { moveSheet = false }) {
            Text(
                "移动到",
                style = t.title3,
                color = c.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            categories.forEach { (key, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .tapArea { runMove(key) }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = t.body, color = c.label, modifier = Modifier.weight(1f))
                    if (batchBusy) CircularProgressIndicator(color = c.blue, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (addSheet) {
        OTSheet(onDismiss = { addSheet = false }) {
            Text(
                "添加帐户",
                style = t.title3,
                color = c.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            OTFieldRow(
                label = "邮箱地址",
                value = newEmail,
                onValueChange = { newEmail = it },
                hint = "name@${session.email.substringAfter("@", "domain")}",
            )
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OTPrimaryButton("添加", enabled = newEmail.contains("@")) {
                    scope.launch {
                        runCatching { Api.addAccount(newEmail.trim()) }
                            .onSuccess {
                                addSheet = false
                                newEmail = ""
                                InboxUi.allAccounts = true
                                InboxUi.accountTick++
                                runCatching { Api.me() }.onSuccess { session.accountId = it.accountId }
                            }
                            .onFailure { store.error = friendlyError(it) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DrawerLabel(text: String) {
    Text(
        text,
        style = OT.type.caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        color = OT.colors.label3,
        modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerRow(
    icon: Painter?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    close: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** 帐户行用 subhead 15（.acct__mail），导航行用 body 17（.drawer__item） */
    subhead: Boolean = false,
) {
    val t = OT.type
    val c = OT.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
            .background(if (selected) c.bgInput else Color.Transparent)
            .tapArea { close?.invoke(); onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.label2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(30.dp))
        }
        Text(
            label,
            style = (if (subhead) t.subhead else t.body).copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = c.label,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun AddAccountRow(onClick: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .height(38.dp)
            .drawBehind {
                drawRoundRect(
                    color = c.separator,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))),
                )
            }
            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
            .tapArea { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("添加帐户", style = t.subhead, color = c.label2)
    }
}

@Composable
private fun boxIcon(key: String): Painter = when (key) {
    "receive" -> painterResource(R.drawable.ot_inbox)
    "unread" -> painterResource(R.drawable.ot_mail)
    "starred" -> painterResource(R.drawable.ot_star)
    "deleted" -> painterResource(R.drawable.ot_trash)
    "spam" -> painterResource(R.drawable.ot_alert)
    else -> painterResource(R.drawable.ot_mail)
}

@Composable
private fun accColor(i: Int): Color {
    val c = OT.colors
    val palette = listOf(c.acc1, c.acc2, c.acc3, c.acc4, c.acc5, c.acc6)
    return palette[(if (i < 0) 0 else i) % palette.size]
}

@Composable
private fun accountColorFor(index: Int, name: String): Color {
    if (index >= 0) return accColor(index)
    val c = OT.colors
    val palette = listOf(c.acc1, c.acc2, c.acc3, c.acc4, c.acc5, c.acc6)
    val h = name.hashCode().let { if (it < 0) -it else it }
    return palette[h % palette.size]
}
