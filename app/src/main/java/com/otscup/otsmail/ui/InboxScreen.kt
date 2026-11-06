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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    // 已发送：/email/list 的 type=1（服务端 mailbox 只有 all/unread/deleted/spam，没有 sent）
    Mailbox("已发送", "sent"),
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
    /**
     * 搜索范围：0 当前文件夹 / 1 全部邮件（我的所有邮箱，allReceive=1）/ 2 全站（需 all-email:query）。
     * 只在有关键词时生效；换范围会改 listKey，上一份快照与请求都不会被复用。
     */
    var searchScope by mutableStateOf(0)
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

/**
 * 抽屉徽标计数（信箱总数 + 分类数）的进程级缓存。
 * 每次切分类/切邮箱/从详情返回都会重新走 loadCounts，但计数只会被写操作改变：
 * MailSync.rev 一变就失效，60 秒内直接复用，避免来回切换时重复打 4 个接口。
 */
internal object CountsCache {
    private var key = ""
    private var rev = -1
    private var at = 0L
    private var boxes: Map<String, Int> = emptyMap()
    private var cats: Map<String, Int> = emptyMap()

    fun read(key: String): Pair<Map<String, Int>, Map<String, Int>>? =
        if (key == this.key && rev == MailSync.rev && System.currentTimeMillis() - at < 60_000) {
            boxes to cats
        } else {
            null
        }

    fun write(key: String, boxes: Map<String, Int>, cats: Map<String, Int>) {
        this.key = key
        rev = MailSync.rev
        at = System.currentTimeMillis()
        this.boxes = boxes
        this.cats = cats
    }
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
    /** 抽屉里的「草稿箱」：本地草稿不进服务端列表，交给 App 打开 DraftsScreen */
    onOpenDrafts: () -> Unit = {},
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
    // 星标列表的 /star/list 不接受 keyword：进星标时把关键词视为空（搜索框也隐藏），
    // 否则会把"全部星标"当成关键词搜索结果缓存进快照，看起来像搜索坏了。
    val keyword = if (current.key == "starred") "" else InboxUi.keyword
    /** 输入框里的字（防抖 280ms 后才成为真正生效的 keyword，避免每敲一个字就换一份列表快照） */
    var kwText by remember { mutableStateOf(InboxUi.keyword) }
    LaunchedEffect(kwText) {
        if (kwText != InboxUi.keyword) {
            kotlinx.coroutines.delay(280)
            InboxUi.keyword = kwText
        }
    }
    // 搜索框清空后，带关键词的快照就只是垃圾，立刻释放内存（磁盘行由字节预算兜底回收）
    LaunchedEffect(keyword) {
        if (keyword.isBlank()) MailCache.dropKeywordSnapshots()
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
    suspend fun loadCounts(force: Boolean = false) {
        val acc = if (allAccounts) 0L else accountId
        val cacheKey = "$acc|$allAccounts"
        if (!force) {
            CountsCache.read(cacheKey)?.let { (cachedBoxes, cachedCats) ->
                counts = cachedBoxes
                catCounts = cachedCats
                return
            }
        }
        // 两组计数互不依赖，并发取；任何一组失败都保留旧值，不把徽标清空
        coroutineScope {
            val boxesJob = async { runCatching { Api.menuCounts(acc, allAccounts) }.getOrNull() }
            val catsJob = async { runCatching { Api.categoryCounts(acc, allAccounts) }.getOrNull() }
            val freshBoxes = boxesJob.await()
            val freshCats = catsJob.await()
            if (freshBoxes != null) counts = freshBoxes
            if (freshCats != null) catCounts = freshCats
            if (freshBoxes != null && freshCats != null) {
                CountsCache.write(cacheKey, freshBoxes, freshCats)
            }
        }
    }
    var showAvatars by remember { mutableStateOf(session.showAvatars) }
    // 多帐户混合列表用：accountId → 邮箱地址（/account/list 已有，不新增服务端依赖）
    val accountEmails = remember(accounts) { accounts.toMap() }
    // 行内收件邮箱：多帐户 / 跨帐户搜索 / 全站搜索都必须标明归属，否则分不清是哪封信
    fun mailboxLabelOf(mail: com.otscup.otsmail.data.MailItem): String? {
        if (!allAccounts && InboxUi.searchScope == 0) return null
        accountEmails[mail.accountId]?.takeIf { it.isNotBlank() }?.let { return it }
        mail.toEmail.takeIf { it.isNotBlank() }?.let { return it }
        mail.userEmail.takeIf { it.isNotBlank() }?.let { return it }
        return mail.recipient.takeIf { it.isNotBlank() && mail.type != 1 }
    }
    var addSheet by remember { mutableStateOf(false) }
    var newEmail by remember { mutableStateOf("") }

    // 全站搜索入口的门槛与设置页「全部邮件」同一规则：permKeys 含 all-email:query / *，或系统管理员。
    // 初值 false（fail-closed）：拿不到权限就不显示这个范围，也不拿 403 冒充「没有结果」。
    var canSiteSearch by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        canSiteSearch = runCatching { Api.me() }.getOrNull()?.let { me ->
            me.type == 0L || me.permKeys.contains("all-email:query") || me.permKeys.contains("*")
        } ?: false
    }
    LaunchedEffect(canSiteSearch) {
        if (!canSiteSearch && InboxUi.searchScope == 2) InboxUi.searchScope = 0
    }

    // 多选：长按某行进入，标题栏变「已选 N 封」，返回键退出并保持滚动位置
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var batchBusy by remember { mutableStateOf(false) }
    var moveSheet by remember { mutableStateOf(false) }
    // 移动到分类时的进度（已完成 / 总数）；服务端只有单封接口，逐条串行时要让用户看到进展
    var moveProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 「清空已删除」的二次确认：不可恢复，必须明确告知
    var clearDeletedConfirm by remember { mutableStateOf(false) }
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
        // 搜索范围参与 key：全站/全部邮箱/当前文件夹是三套数据，绝不能共用同一份快照
        if (keyword.isNotBlank()) append("|scope:").append(InboxUi.searchScope)
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
    // 每条删除各自排队：新的撤销条不会取消前一条已排队的删除；真正请求用 Mutex 串行发出。
    data class SwipeUndo(val mail: MailItem, val index: Int) { var job: Job? = null }

    val swipes = remember { mutableStateListOf<SwipeUndo>() }
    val trashMutex = remember { Mutex() }

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
        items.removeAt(idx)
        store.total = (store.total - 1).coerceAtLeast(0)
        bumpCount(countKeyFor(current.key), -1)
        val entry = SwipeUndo(mail, idx)
        swipes.add(entry)
        entry.job = swipeUndoScope.launch {
            delay(3000)
            swipes.remove(entry)
            trashMutex.withLock {
                runCatching { Api.trash(listOf(mail.emailId)) }
                    .onSuccess { MailCache.removeItem(mail.emailId) }
                    .onFailure {
                        // 服务端没删掉：把行放回原位，别让界面和数据对不上
                        if (items.none { it.emailId == mail.emailId }) {
                            items.add(idx.coerceIn(0, items.size), mail)
                        }
                        store.total += 1
                        bumpCount(countKeyFor(current.key), +1)
                        notice = "删除失败，已恢复"
                    }
            }
        }
    }

    fun undoDelete() {
        // 只撤销当前这一条：取消它自己的定时任务，已经排队的其它删除照常继续
        val entry = swipes.lastOrNull() ?: return
        entry.job?.cancel()
        swipes.remove(entry)
        if (items.none { it.emailId == entry.mail.emailId }) {
            items.add(entry.index.coerceIn(0, items.size), entry.mail)
        }
        store.total += 1
        bumpCount(countKeyFor(current.key), +1)
        notice = "已撤销删除"
    }

    /** 已删除列表的行操作：恢复到收件箱。恢复成功只提示，不进删除撤销条（语义不同）。 */
    fun restoreOne(mail: MailItem) {
        scope.launch {
            runCatching { Api.restore(mail.emailId) }
                .onSuccess {
                    MailCache.removeItem(mail.emailId)
                    MailSync.changed()
                    notice = "已恢复到收件箱"
                    loadCounts()
                }
                .onFailure { notice = friendlyError(it) }
        }
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
        // 搜索时按用户选的范围取数：全站 / 我的全部邮箱 / 当前文件夹
        if (keyword.isNotBlank()) {
            when (InboxUi.searchScope) {
                1 -> return Api.emailsRaw(0, next, null, keyword, "", allAccounts = true)
                2 -> return Api.siteEmailsRaw(next, keyword, type = "all")
            }
        }
        // 已发送不是服务端的 mailbox 值：传 type=1、mailbox 留空（默认排除垃圾/已删除）
        val mb = if (current.key == "sent" || current.key == "spam") null else current.key
        val cat = when {
            current.key == "spam" -> "spam"
            current.key == "receive" -> category
            else -> ""
        }
        return when {
            current.key == "starred" -> Api.starredRaw(next)
            current.key == "sent" -> Api.emailsRaw(accountId, next, null, keyword, "", allAccounts, type = 1)
            else -> Api.emailsRaw(accountId, next, mb, keyword, cat, allAccounts)
        }
    }

    /**
     * 首屏刷新：重置为第 1 页，并清掉旧的第 2..N 页缓存。
     * 不能把新第 1 页和旧的第 2..N 页继续拼在一起——邮件上下位移后，旧页码对应的是另一批数据，
     * 拼接会出现重复行和"翻页漂移"。toTop = true 只给「用户手动下拉」用。
     */
    suspend fun refreshFirst(toTop: Boolean = false) {
        val raw = fetchRaw(1)
        val (freshRaw, total) = Api.parseList(raw)
        val fresh = MailCache.dedupeByEmailId(freshRaw)
        val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }?.key as? Long
        val anchorOffset = listState.firstVisibleItemScrollOffset
        items.clear()
        items.addAll(fresh)
        store.total = total.toInt()
        store.page = 1
        store.error = ""
        MailCache.savePage(store, 1, fresh, total.toInt(), resetLater = true)
        if (toTop && items.isNotEmpty()) {
            // 无条件回到列表最顶端：firstVisibleItemIndex 可能已经是 0，但第一行（日期头）还滚出去了一截
            listState.scrollToItem(0, 0)
        } else if (anchor != null) {
            val target = flatIndexOf(items, anchor)
            if (target != null && target != listState.firstVisibleItemIndex) {
                listState.scrollToItem(target, anchorOffset)
            }
        } else if (items.isNotEmpty() && listState.firstVisibleItemIndex >= items.size) {
            // 之前滚到第 2..N 页、但这封锚点邮件不在新首页了：夹回最后一封，避免越界空白
            // 列表变成空时不滚：scrollToItem 要等 LazyColumn 布局，空列表没有可布局的项，会一直挂起
            listState.scrollToItem((items.size - 1).coerceAtLeast(0), 0)
        }
    }

    /** 清空已删除：服务端物理删除，成功后让本列表快照与 SQLite 缓存整体失效。 */
    suspend fun clearDeleted() {
        val removed = runCatching { Api.clearDeleted() }
            .onFailure { notice = friendlyError(it); return }
            .getOrNull() ?: return
        MailCache.clearList(listKey)
        swipes.clear()
        restoreToken++
        runCatching { refreshFirst(toTop = true) }
        loadCounts()
        MailSync.changed()
        notice = if (removed > 0) "已清空 $removed 封" else "垃圾箱已经空了"
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
            notice = when {
                failed > 0 -> "$failed 封失败，其余已处理"
                action == MailBatch.RESTORE -> "已恢复 ${ids.size} 封"
                else -> "已处理 ${ids.size} 封"
            }
            loadCounts()
        }
    }

    fun runMove(target: String) {
        if (batchBusy || selected.isEmpty()) return
        val ids = selected.toList()
        val label = categories.firstOrNull { it.first == target }?.second ?: target
        batchBusy = true
        moveProgress = 0 to ids.size
        scope.launch {
            val failed = MailBatchOps.move(target, ids) { done, total -> moveProgress = done to total }
            // 在某个分类里把邮件移走：该视图不再包含它们
            if (category.isNotEmpty() && target != category) ids.forEach { MailCache.removeRow(listKey, it) }
            // 分类是服务端状态：通知列表/计数重新拉，别只改本地缓存
            MailSync.changed()
            batchBusy = false
            moveProgress = null
            moveSheet = false
            exitSelect()
            notice = if (failed > 0) {
                "已移到「$label」${ids.size - failed} 封，$failed 封失败"
            } else {
                "已移到「$label」${ids.size} 封"
            }
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
        // 账户标签与抽屉计数跟列表刷新互不依赖：并发拉，不再排在列表请求后面串行等
        val accountsJob = launch { runCatching { Api.accounts() }.onSuccess { accounts = it } }
        val countsJob = launch { loadCounts() }
        // MailSync.rev 变过 = 详情/写信对服务端做过写操作，强制刷新
        if (MailSync.rev != store.syncRev) {
            store.syncRev = MailSync.rev
            store.updatedAt = 0L
        }
        // 有缓存时这是"缓存首帧"：先把行画出来，网络刷新在后台跑，不清列表
        val needRefresh = items.isEmpty() || System.currentTimeMillis() - store.updatedAt > MailCache.LIST_TTL
        if (items.isNotEmpty()) store.loading = false
        if (needRefresh) {
            store.loading = true
            runCatching { refreshFirst() }
                .onFailure { if (items.isEmpty()) store.error = friendlyError(it) }
            store.loading = false
        }
        accountsJob.join()
        countsJob.join()
    }

    // 首屏预取：首帧出来后顺序取第 2、3 页，供滚到底时直接拼接。
    // 一次只发一个请求、且首屏刷新/下拉刷新在跑时让路，避免把首屏的 RTT 挤慢；搜索不预取。
    LaunchedEffect(listKey) {
        if (keyword.isNotBlank() || store.total <= 20) return@LaunchedEffect
        delay(2500)
        var attempts = 0
        while (store.page < 3 && items.size < store.total && attempts < 2) {
            if (store.loading || store.loadingMore) {
                delay(300)
                continue
            }
            val before = store.page
            loadMore()
            attempts++
            // 预取失败（离线/超时）就停手，不反复重试把网络打满
            if (store.page == before) break
            delay(600)
        }
    }
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
                                // 草稿箱在本地（Drafts 加密落盘），没有服务端计数，也不进 MailCache 列表
                                DrawerRow(
                                    icon = painterResource(R.drawable.ot_mail),
                                    label = "草稿箱",
                                    selected = false,
                                    onClick = {
                                        close()
                                        onOpenDrafts()
                                    },
                                )
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
                        if (current.key == "starred") {
                            // 星标接口不支持关键词：这里不展示搜索框，避免"搜了却返回全部星标"的错觉。
                            // AI 入口保留，否则星标页会少一个快捷方式。
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .tapArea { onOpenAi() },
                                contentAlignment = Alignment.Center,
                            ) {
                                AiStarGradient(22.dp)
                            }
                        } else {
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
                    // 搜索范围：只在有关键词时出现；没站点权限就整块不显示「全站」，不留一个点进去 403 的选项
                    if (keyword.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SearchScopeChip("当前文件夹", InboxUi.searchScope == 0) { InboxUi.searchScope = 0 }
                            SearchScopeChip("全部邮件", InboxUi.searchScope == 1) { InboxUi.searchScope = 1 }
                            if (canSiteSearch) {
                                SearchScopeChip("全站", InboxUi.searchScope == 2) { InboxUi.searchScope = 2 }
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
                if (visibleTitle.isNotEmpty()) {
                    if (current.key == "deleted" && !selectMode && items.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                visibleTitle,
                                style = t.title3.copy(fontWeight = FontWeight.Bold),
                                color = c.label,
                                modifier = Modifier.weight(1f).padding(start = 20.dp),
                            )
                            Box(
                                Modifier.height(36.dp).tapArea { clearDeletedConfirm = true }.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("清空", style = t.subhead, color = c.blue)
                            }
                        }
                    } else {
                        OTViewTitle(visibleTitle)
                    }
                }
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
                        val (icon, title, text) = when {
                            keyword.isNotBlank() && InboxUi.searchScope == 2 ->
                                Triple(R.drawable.ot_search, "全站没有匹配的邮件", "换个关键词，或缩小到自己的邮箱")
                            keyword.isNotBlank() && InboxUi.searchScope == 1 ->
                                Triple(R.drawable.ot_search, "你名下邮箱里没有匹配的邮件", "换个关键词试试")
                            keyword.isNotBlank() -> Triple(R.drawable.ot_search, "没有匹配的邮件", "换个关键词试试")
                            current.key == "sent" -> Triple(R.drawable.ot_send, "还没有已发送邮件", "写信并发出后，邮件会出现在这里")
                            current.key == "spam" -> Triple(R.drawable.ot_alert, "垃圾箱是空的", "被判定为垃圾的邮件会收在这里")
                            current.key == "deleted" -> Triple(R.drawable.ot_trash, "没有已删除的邮件", "删除的邮件会先放到这里")
                            current.key == "starred" -> Triple(R.drawable.ot_star, "还没有标星邮件", "在邮件上点星标即可收藏")
                            current.key == "unread" -> Triple(R.drawable.ot_mail, "没有未读邮件", "都看过了")
                            else -> Triple(R.drawable.ot_inbox, "还没有邮件", "换个邮箱，或稍后再试")
                        }
                        OTEmptyState(
                            icon = painterResource(icon),
                            title = title,
                            text = text,
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
                                        // 已删除列表里右滑 = 恢复到收件箱；其它列表照旧是回复
                                        onReply = { if (current.key == "deleted") restoreOne(mail) else onSwipeReply(mail) },
                                        onDelete = { swipeDelete(mail) },
                                        rightIcon = if (current.key == "deleted") R.drawable.ot_inbox else R.drawable.ot_reply,
                                        rightLabel = if (current.key == "deleted") "恢复" else "回复",
                                    ) {
                                    Column(Modifier.fillMaxWidth().background(c.bgCard)) {
                                        // 发丝线从文字起始处开始，不穿过头像（16 + 头像 34 + 间距 10）
                                        if (index > 0) {
                                            OTDivider(inset = 60, endInset = 16)
                                        }
                                        OTMailRow(
                                            // 已发送箱的行要显示收件人，显示自己没意义
                                            from = if (mail.type == 1) {
                                                mail.recipient.ifEmpty { "（无收件人）" }
                                            } else {
                                                mail.name.ifEmpty { mail.from }
                                            },
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
                        showRestore = current.key == "deleted",
                    )
                }
            }
            // 滑动删除的撤销条：3 秒内可点，FAB 上方，不挡列表最后一行
            swipes.lastOrNull()?.let { entry ->
                val m = entry.mail
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

    if (clearDeletedConfirm) {
        AlertDialog(
            onDismissRequest = { clearDeletedConfirm = false },
            title = { Text("清空已删除", style = t.title3, color = c.label) },
            text = {
                Text(
                    "已删除里的邮件会被彻底删除，且不可恢复。确定继续？",
                    style = t.subhead, color = c.label2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clearDeletedConfirm = false
                    scope.launch { clearDeleted() }
                }) { Text("清空", color = c.red) }
            },
            dismissButton = {
                TextButton(onClick = { clearDeletedConfirm = false }) { Text("取消", color = c.label2) }
            },
            containerColor = c.bgElevated,
        )
    }

    if (moveSheet) {
        OTSheet(onDismiss = { if (!batchBusy) moveSheet = false }) {
            Text(
                if (batchBusy) {
                    val p = moveProgress
                    if (p != null) "正在移动 ${p.first} / ${p.second} 封…" else "正在移动…"
                } else {
                    "移到分类（已选 ${selected.size} 封）"
                },
                style = t.title3,
                color = c.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            categories.forEach { (key, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .tapArea { if (!batchBusy) runMove(key) }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = t.body, color = if (batchBusy) c.label3 else c.label, modifier = Modifier.weight(1f))
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

/** 搜索范围胶囊：选中态用底色区分，样式和分类 pill 一致。 */
@Composable
private fun SearchScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    Box(
        Modifier
            .clip(RoundedCornerShape(OT.dimens.rPill.dp))
            .background(if (selected) c.bgPressed else c.bgInput)
            .tapArea { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = t.caption1, color = if (selected) c.label else c.label2)
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
    "sent" -> painterResource(R.drawable.ot_send)
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
