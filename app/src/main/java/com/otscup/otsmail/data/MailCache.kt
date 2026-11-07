package com.otscup.otsmail.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 列表快照：按（账号, mailbox/分类, 关键词）聚成一个 key。
 * 放在内存里而不是 remember，是为了让「进详情 → 返回」时列表、分页、滚动位置原样还在
 * （详情页会把列表的 composition 整个销毁，remember 的状态跟着丢）。
 */
class ListSnapshot(val key: String, val accountId: Long) {
    val items = mutableStateListOf<MailItem>()
    var total by mutableStateOf(0)
    var page by mutableStateOf(0)
    var updatedAt by mutableStateOf(0L)
    var loading by mutableStateOf(false)      // 只有真正没有缓存的首屏才 true
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf("")
    var scrollIndex by mutableStateOf(0)
    var scrollOffset by mutableStateOf(0)
    /** 已应用过的 MailSync 版本；用于判断「详情里改过东西，回来要静默刷新」。 */
    var syncRev by mutableStateOf(-1)
    /** 最近一次被 [MailCache.list] 取用的时间，只用来做内存淘汰。 */
    var lastUsedAt: Long = 0L
}

/**
 * 本地缓存：列表分页 + 邮件详情，SQLite 直写（不引第三方 ORM）。
 *
 * 策略：
 * - 列表页 TTL 5 分钟、详情 TTL 10 分钟；超过只代表「该刷新了」，缓存内容仍然先渲染。
 * - 退出登录清空全部（避免串号）。
 * - 已读/星标/移动/删除会同步改内存列表行和 SQLite 行。
 */
object MailCache {

    const val LIST_TTL = 5 * 60 * 1000L
    const val DETAIL_TTL = 10 * 60 * 1000L

    /*
     * 长期保留（中间档）：
     * - 用户打开过的正文留 14 天：够覆盖一次离线出差/度假，同时把明文正文的落盘窗口
     *   从 90 天压到两周（这里是明文 SQLite，缩短保留期是成本最低的收敛手段）；
     * - 没打开过的正文（未来若做正文预取）只留 1 小时，只当热缓存；
     * - 每个列表快照留最近 20 页 × 20 封 = 400 行（服务端 size 固定 20）；
     * - 字节上限按 JSON 长度估算：列表 24MB、正文 32MB。超限先清没打开过的旧正文，再清旧索引页。
     * 400 的理由：约 20 页 ≈ 正常用户半个月的收件量，再往上翻还能从服务端取；
     * 24MB ≈ 400 行 × 约 60KB 的头部 JSON，留一倍余量给多账号多分类快照。
     */
    const val OPENED_KEEP = 7L * 24 * 60 * 60 * 1000
    private const val UNOPENED_KEEP = 60L * 60 * 1000
    const val RETAIN_ROWS_PER_LIST = 400
    private const val LIST_BUDGET_BYTES = 24L * 1024 * 1024
    private const val DETAIL_BUDGET_BYTES = 32L * 1024 * 1024

    private var db: CacheDb? = null
    private val lists = HashMap<String, ListSnapshot>()
    private val details = HashMap<Long, DetailEntry>()
    private val translations = LinkedHashMap<Long, String>()

    /*
     * 内存上限：磁盘有字节预算，内存也需要一条硬线，否则长会话（反复搜索 + 连续读长正文）
     * 会一直涨到 OOM。数值按"够用且可解释"取：
     * - 8 个列表快照 = 当前入口 + 最近 7 个入口（抽屉里 5 个信箱 + 常驻的分类/搜索）；
     * - 50 封正文 ≈ 5~10MB HTML（单封正文页通常 100~200KB），够覆盖一次连续阅读；
     * - 50 条译文同上，翻译缓存只是切"原文/译文"用的热数据。
     * 淘汰按"最久没被打开"，被淘汰的数据仍在 SQLite 里，回来时从磁盘读。
     */
    private const val MAX_LIST_SNAPSHOTS = 8
    private const val MAX_DETAIL_ENTRIES = 50
    private const val MAX_TRANSLATIONS = 50

    private data class DetailEntry(val mail: MailDetail, val at: Long, val opened: Boolean)

    /*
     * 字节占用增量维护：懒初始化时做一次全表求和，之后每次插入/删除只按受影响行的
     * 差值更新，避免每次写一页/存一封正文都全表 SUM(length(json))。
     * 写入事务与计数更新都在同一个锁里，防止两路并发写把总数算坏。
     */
    private val bytesLock = Any()
    private var listBytes = -1L
    private var detailBytes = -1L

    private fun ensureListBytes(w: SQLiteDatabase) {
        if (listBytes < 0) listBytes = sumBytes(w, "SELECT COALESCE(SUM(bytes),0) FROM list_row")
    }

    private fun ensureDetailBytes(w: SQLiteDatabase) {
        if (detailBytes < 0) detailBytes = sumBytes(w, "SELECT COALESCE(SUM(bytes),0) FROM detail")
    }

    /** 列表行估算字节：JSON 长度 + 固定列开销，与旧版 SUM(length(json)+64) 的口径一致。 */
    private fun listRowBytes(json: String): Long = json.length.toLong() + 64

    private fun listRowsBytes(w: SQLiteDatabase, where: String, args: Array<String>): Long =
        w.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM list_row WHERE $where", args)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun detailRowsBytes(w: SQLiteDatabase, where: String): Long =
        w.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM detail WHERE $where", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** 正文内存上限：超了就从最久没写入的开始丢，磁盘副本还在。 */
    private fun evictDetailsIfNeeded() {
        if (details.size <= MAX_DETAIL_ENTRIES) return
        details.entries.sortedBy { it.value.at }
            .take(details.size - MAX_DETAIL_ENTRIES)
            .forEach { details.remove(it.key) }
    }

    fun init(context: Context) {
        if (db == null) db = CacheDb(context.applicationContext)
    }

    fun list(key: String, accountId: Long): ListSnapshot {
        lists[key]?.let {
            it.lastUsedAt = System.currentTimeMillis()
            return it
        }
        // 超出上限先淘汰最久没用过的快照；被淘汰的数据仍在 SQLite，回来时重新读盘
        if (lists.size >= MAX_LIST_SNAPSHOTS) {
            lists.entries.minByOrNull { it.value.lastUsedAt }?.let { lists.remove(it.key) }
        }
        return ListSnapshot(key, accountId).also {
            it.lastUsedAt = System.currentTimeMillis()
            lists[key] = it
        }
    }

    /**
     * 搜索框被清空时调用：所有带关键词的快照都只是临时查询结果，连内存带引用一起释放。
     * 当前正在看的空关键词快照（key 以 "|kw:" 结尾）不受影响。
     */
    fun dropKeywordSnapshots() {
        lists.keys.filter { it.substringAfter("|kw:", "").isNotEmpty() }.forEach { lists.remove(it) }
    }

    /** 从任意已加载快照里找一行：详情页一进来就能先用列表里的标题/发件人占位。 */
    fun findItem(emailId: Long): MailItem? {
        for (snap in lists.values) {
            val hit = snap.items.firstOrNull { it.emailId == emailId }
            if (hit != null) return hit
        }
        return null
    }

    /** 已加载过的快照（内存）。 */
    fun peekList(key: String): ListSnapshot? = lists[key]

    /** 翻译结果按 emailId 留在内存里：切「原文/译文」不重新请求接口。 */
    fun translation(emailId: Long): String? = translations[emailId]

    fun putTranslation(emailId: Long, html: String) {
        if (!translations.containsKey(emailId) && translations.size >= MAX_TRANSLATIONS) {
            translations.keys.firstOrNull()?.let { translations.remove(it) }
        }
        translations[emailId] = html
    }

    fun clearTranslation(emailId: Long) {
        translations.remove(emailId)
    }

    // ---------------- 详情 ----------------

    /** 内存里的详情；第二个返回值表示是否还在 TTL 内（打开过的按长期保留口径）。 */
    fun detailMemory(emailId: Long): Pair<MailDetail, Boolean>? {
        val e = details[emailId] ?: return null
        val keep = if (e.opened) OPENED_KEEP else DETAIL_TTL
        return e.mail to (System.currentTimeMillis() - e.at <= keep)
    }

    suspend fun detailDisk(emailId: Long): Pair<MailDetail, Boolean>? = withContext(Dispatchers.IO) {
        val h = db ?: return@withContext null
        h.readableDatabase.query(
            "detail", arrayOf("json", "updated_at", "opened"), "email_id=?",
            arrayOf(emailId.toString()), null, null, null,
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            val at = c.getLong(1)
            val opened = c.getInt(2) == 1
            val mail = runCatching { parseDetail(JSONObject(c.getString(0))) }.getOrNull()
                ?: return@withContext null
            // 打开过的正文长期不再回源（秒开/离线读）；没打开过的仍按 10 分钟 TTL 判断
            mail to (System.currentTimeMillis() - at <= if (opened) OPENED_KEEP else DETAIL_TTL)
        }
    }

    /**
     * 存正文。opened=true 表示这封是用户真的点开过（长期保留），
     * false 预留给以后做相邻邮件预取（只当短期热缓存）。
     */
    suspend fun saveDetail(mail: MailDetail, opened: Boolean = true) {
        details[mail.emailId] = DetailEntry(mail, System.currentTimeMillis(), opened)
        evictDetailsIfNeeded()
        withContext(Dispatchers.IO) {
            val h = db ?: return@withContext
            synchronized(bytesLock) {
                val now = System.currentTimeMillis()
                val json = mail.toJson().toString()
                val newBytes = json.length.toLong() * 2
                val w = h.writableDatabase
                w.beginTransaction()
                try {
                    ensureDetailBytes(w)
                    // REPLACE 会覆盖同一封的旧正文：先取旧行占用，差量更新总量
                    val oldBytes = detailRowsBytes(w, "email_id=${mail.emailId}")
                    val cv = ContentValues().apply {
                        put("email_id", mail.emailId)
                        put("json", json)
                        put("updated_at", now)
                        put("opened", if (opened) 1 else 0)
                        put("bytes", newBytes)
                    }
                    w.insertWithOnConflict("detail", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    detailBytes += newBytes - oldBytes
                    trimDetails(w, now)
                    w.setTransactionSuccessful()
                } finally {
                    w.endTransaction()
                }
            }
        }
    }

    // ---------------- 列表 ----------------

    data class Pages(
        val items: List<MailItem>,
        val total: Int,
        val page: Int,
        val updatedAt: Long,
        val scrollIndex: Int,
        val scrollOffset: Int,
    )

    fun markFresh(snap: ListSnapshot) {
        snap.updatedAt = System.currentTimeMillis()
    }

    /**
     * 详情页做了改变邮件归属的操作（恢复 / 取消垃圾 / 标记垃圾）：
     * 该邮件会从当前 mailbox 消失并出现在别的 mailbox。把所有列表快照标成「该刷新」，
     * 返回列表时按现行 TTL 规则重拉第一页，不会残留旧行。
     */
    fun expireLists() {
        lists.values.forEach { it.updatedAt = 0L }
    }

    /**
     * 写一页缓存。
     * - 行主键是 (key, email_id)，同一封邮件在库里只会有一行，翻页位移不会叠出重复行。
     * - resetLater=true 用于「刷新首页」：同时清掉第 2..N 页旧缓存，分页从 1 重新开始。
     */
    suspend fun savePage(
        snap: ListSnapshot,
        page: Int,
        items: List<MailItem>,
        total: Int,
        resetLater: Boolean = false,
    ) {
        val rows = dedupeByEmailId(items)
        snap.page = if (resetLater) page else maxOf(snap.page, page)
        snap.total = total
        snap.updatedAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            val h = db ?: return@withContext
            synchronized(bytesLock) {
                val now = System.currentTimeMillis()
                val w = h.writableDatabase
                w.beginTransaction()
                try {
                    ensureListBytes(w)
                    // 被替换/清掉的旧行先按实际占用扣减（只扫这一页，不全表求和）
                    var removed = listRowsBytes(w, "key=? AND page=?", arrayOf(snap.key, page.toString()))
                    w.delete("list_row", "key=? AND page=?", arrayOf(snap.key, page.toString()))
                    if (resetLater) {
                        val later = arrayOf(snap.key, page.toString())
                        removed += listRowsBytes(w, "key=? AND page>?", later)
                        w.delete("list_row", "key=? AND page>?", later)
                    }
                    var added = 0L
                    rows.forEachIndexed { i, m ->
                        val json = m.toJson().toString()
                        val bytes = listRowBytes(json)
                        val cv = ContentValues().apply {
                            put("key", snap.key)
                            put("page", page)
                            put("pos", i)
                            put("email_id", m.emailId)
                            put("json", json)
                            put("bytes", bytes)
                            put("updated_at", now)
                        }
                        // CONFLICT_IGNORE：同一封邮件已经在页码更小的页里存在时保留旧行，
                        // 不会因为后一页也返回它而被改到后页（"保留首次出现、页码小者优先"）。
                        // 返回 -1 表示被忽略，这一行的字节不能计入总量。
                        if (w.insertWithOnConflict("list_row", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                            added += bytes
                        }
                    }
                    listBytes += added - removed
                    // 清理放在写 meta 之前：trim 可能会把本 key 的页码收窄，meta 要写收窄后的值
                    trimLists(w, snap)
                    val meta = ContentValues().apply {
                        put("key", snap.key)
                        put("total", total)
                        put("page", snap.page)
                        put("updated_at", now)
                        put("scroll_index", snap.scrollIndex)
                        put("scroll_offset", snap.scrollOffset)
                    }
                    w.insertWithOnConflict("list_meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)
                    w.setTransactionSuccessful()
                } finally {
                    w.endTransaction()
                }
            }
        }
    }

    /** 冷启动时把磁盘上的分页拼回来；没有缓存返回 null。 */
    suspend fun readPages(key: String): Pages? = withContext(Dispatchers.IO) {
        val h = db ?: return@withContext null
        val meta = h.readableDatabase.query(
            "list_meta", arrayOf("total", "page", "updated_at", "scroll_index", "scroll_offset"),
            "key=?", arrayOf(key), null, null, null,
        ).use { c ->
            if (!c.moveToFirst()) null
            else arrayOf(c.getInt(0), c.getInt(1), c.getLong(2), c.getInt(3), c.getInt(4))
        } ?: return@withContext null
        val items = h.readableDatabase.query(
            "list_row", arrayOf("json"), "key=?", arrayOf(key),
            null, null, "page ASC, pos ASC",
        ).use { c ->
            // 按 emailId 去重，保留页码小、位置靠前的那一条：
            // 旧库里可能残留同一封邮件跨页的重复行，直接拼回去会让 LazyColumn 的 key 冲突崩溃。
            val seen = LinkedHashMap<Long, MailItem>(c.count)
            while (c.moveToNext()) {
                val item = runCatching { parseItem(JSONObject(c.getString(0))) }.getOrNull() ?: continue
                if (!seen.containsKey(item.emailId)) seen[item.emailId] = item
            }
            seen.values.toList()
        }
        if (items.isEmpty()) return@withContext null
        // meta 里的 page 可能比实际保留的页更大（清理删过整页）：
        // 以库里真实存在的最大页为准，否则重启后 loadMore 会从缺行的那页继续翻
        val maxPage = h.readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(page),0) FROM list_row WHERE key=?", arrayOf(key),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        Pages(items, meta[0] as Int, minOf(meta[1] as Int, maxPage), meta[2] as Long, meta[3] as Int, meta[4] as Int)
    }

    suspend fun saveScroll(snap: ListSnapshot) {
        withContext(Dispatchers.IO) {
            val h = db ?: return@withContext
            val cv = ContentValues().apply {
                put("key", snap.key)
                put("total", snap.total)
                put("page", snap.page)
                put("updated_at", snap.updatedAt)
                put("scroll_index", snap.scrollIndex)
                put("scroll_offset", snap.scrollOffset)
            }
            h.writableDatabase.insertWithOnConflict("list_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    // ---------------- 行更新 ----------------

    fun patchItem(emailId: Long, transform: (MailItem) -> MailItem) {
        lists.values.forEach { snap ->
            val i = snap.items.indexOfFirst { it.emailId == emailId }
            if (i >= 0) snap.items[i] = transform(snap.items[i])
        }
    }

    fun patchDetail(emailId: Long, transform: (MailDetail) -> MailDetail) {
        details[emailId]?.let { details[emailId] = DetailEntry(transform(it.mail), it.at, it.opened) }
    }

    suspend fun markRead(emailId: Long) {
        patchItem(emailId) { it.copy(unread = false) }
        patchDetail(emailId) { it.copy(unread = false) }
        patchRowsInDb(emailId) { it.copy(unread = false) }
    }

    suspend fun markUnread(emailId: Long) {
        patchItem(emailId) { it.copy(unread = true) }
        patchDetail(emailId) { it.copy(unread = true) }
        patchRowsInDb(emailId) { it.copy(unread = true) }
    }

    suspend fun setStar(emailId: Long, starred: Boolean) {
        patchItem(emailId) { it.copy(starred = starred) }
        patchDetail(emailId) { it.copy(starred = starred) }
        patchRowsInDb(emailId) { it.copy(starred = starred) }
    }

    suspend fun setCategory(emailId: Long, category: String) {
        patchItem(emailId) { it.copy(category = category) }
        patchDetail(emailId) { it.copy(category = category) }
        patchRowsInDb(emailId) { it.copy(category = category) }
    }

    /** 删除/移入垃圾箱：列表行直接移除，详情保留但不再展示。 */
    suspend fun removeItem(emailId: Long) {
        lists.values.forEach { snap ->
            // 只有真的命中这个快照才减 total，否则多个快照会把同一个数字重复减掉
            if (snap.items.removeAll { it.emailId == emailId } && snap.total > 0) snap.total -= 1
        }
        withContext(Dispatchers.IO) {
            synchronized(bytesLock) {
                val w = db?.writableDatabase ?: return@withContext
                ensureListBytes(w)
                val removed = listRowsBytes(w, "email_id=?", arrayOf(emailId.toString()))
                w.delete("list_row", "email_id=?", arrayOf(emailId.toString()))
                listBytes -= removed
            }
        }
    }

    /** 只从某一个列表快照里移除（例如「已标星」里取消星标），其他 mailbox 不受影响。 */
    suspend fun removeRow(key: String, emailId: Long) {
        lists[key]?.let { snap ->
            if (snap.items.removeAll { it.emailId == emailId } && snap.total > 0) snap.total -= 1
        }
        withContext(Dispatchers.IO) {
            synchronized(bytesLock) {
                val w = db?.writableDatabase ?: return@withContext
                ensureListBytes(w)
                val args = arrayOf(key, emailId.toString())
                val removed = listRowsBytes(w, "key=? AND email_id=?", args)
                w.delete("list_row", "key=? AND email_id=?", args)
                listBytes -= removed
            }
        }
    }

    /** 清空垃圾箱后让整个列表快照失效：内存行、总数、分页与 SQLite 对应 key 全部丢掉。 */
    suspend fun clearList(key: String) {
        lists[key]?.let { snap ->
            snap.items.clear()
            snap.total = 0
            snap.page = 0
            snap.updatedAt = 0L
            snap.scrollIndex = 0
            snap.scrollOffset = 0
        }
        withContext(Dispatchers.IO) {
            synchronized(bytesLock) {
                val w = db?.writableDatabase ?: return@withContext
                ensureListBytes(w)
                val removed = listRowsBytes(w, "key=?", arrayOf(key))
                w.delete("list_row", "key=?", arrayOf(key))
                w.delete("list_meta", "key=?", arrayOf(key))
                listBytes -= removed
            }
        }
    }

    private suspend fun patchRowsInDb(emailId: Long, transform: (MailItem) -> MailItem) {
        withContext(Dispatchers.IO) {
            val h = db ?: return@withContext
            val w = h.writableDatabase
            synchronized(bytesLock) {
                ensureListBytes(w)
                val updates = ArrayList<Triple<Long, String, Long>>()
                var delta = 0L
                w.query(
                    "list_row", arrayOf("rowid", "json", "bytes"), "email_id=?",
                    arrayOf(emailId.toString()), null, null, null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val rowid = c.getLong(0)
                        val oldBytes = c.getLong(2)
                        val item = runCatching { parseItem(JSONObject(c.getString(1))) }.getOrNull() ?: continue
                        val json = transform(item).toJson().toString()
                        val newBytes = listRowBytes(json)
                        delta += newBytes - oldBytes
                        updates.add(Triple(rowid, json, newBytes))
                    }
                }
                updates.forEach { (rowid, json, bytes) ->
                    val cv = ContentValues().apply {
                        put("json", json)
                        put("bytes", bytes)
                    }
                    w.update("list_row", cv, "rowid=?", arrayOf(rowid.toString()))
                }
                listBytes += delta
            }
        }
    }

    /** 退出登录/切换用户：内存与磁盘全部清空，避免串号。 */
    suspend fun clearAll() {
        lists.clear()
        details.clear()
        translations.clear()
        withContext(Dispatchers.IO) {
            synchronized(bytesLock) {
                val w = db?.writableDatabase ?: return@withContext
                w.delete("list_row", null, null)
                w.delete("list_meta", null, null)
                w.delete("detail", null, null)
                listBytes = 0L
                detailBytes = 0L
            }
        }
    }

    /** 同步版本，给退出登录这种非协程上下文用（内存立刻清，磁盘交给 IO 线程）。 */
    fun wipe() {
        lists.clear()
        details.clear()
        translations.clear()
        val handle = db
        ioScope.launch {
            runCatching {
                synchronized(bytesLock) {
                    val w = handle?.writableDatabase ?: return@runCatching
                    w.delete("list_row", null, null)
                    w.delete("list_meta", null, null)
                    w.delete("detail", null, null)
                    listBytes = 0L
                    detailBytes = 0L
                }
            }
        }
    }

    /** 后台清理用：不阻塞主线程，退出登录后即使界面已经销毁也能落盘。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 按 emailId 去重，保留首次出现（首页优先）；写库和内存都用这一份，避免 key 冲突。 */
    internal fun dedupeByEmailId(items: List<MailItem>): List<MailItem> {
        if (items.size < 2) return items
        val seen = HashSet<Long>(items.size)
        return items.filter { seen.add(it.emailId) }
    }

    // ---------------- 序列化 ----------------

    private fun MailItem.toJson(): JSONObject = JSONObject()
        .put("emailId", emailId)
        .put("from", from)
        .put("name", name)
        .put("subject", subject)
        .put("text", text)
        .put("time", time)
        .put("unread", unread)
        .put("starred", starred)
        .put("category", category)
        .put("type", type)
        .put("status", status)
        .put("accountId", accountId)
        .put("recipient", recipient)
        .put("userId", userId)
        .put("toEmail", toEmail)
        .put("userEmail", userEmail)

    private fun parseItem(o: JSONObject): MailItem = MailItem(
        emailId = o.optLong("emailId"),
        from = o.optString("from"),
        name = o.optString("name"),
        subject = o.optString("subject"),
        text = o.optString("text"),
        time = o.optString("time"),
        unread = o.optBoolean("unread"),
        starred = o.optBoolean("starred"),
        category = o.optString("category"),
        type = o.optInt("type"),
        status = o.optInt("status"),
        accountId = o.optLong("accountId"),
        recipient = o.optString("recipient"),
        userId = o.optLong("userId"),
        toEmail = o.optString("toEmail"),
        userEmail = o.optString("userEmail"),
    )

    private fun MailDetail.toJson(): JSONObject = JSONObject()
        .put("emailId", emailId)
        .put("subject", subject)
        .put("from", from)
        .put("name", name)
        .put("time", time)
        .put("content", content)
        .put("unread", unread)
        .put("starred", starred)
        .put("category", category)
        .put("recipient", JSONArray(recipient))
        .put("cc", JSONArray(cc))
        .put("attList", JSONArray().also { arr -> attachments.forEach { arr.put(JSONObject().put("filename", it.filename).put("url", it.url)) } })

    private fun parseDetail(o: JSONObject): MailDetail = MailDetail(
        emailId = o.optLong("emailId"),
        subject = o.optString("subject"),
        from = o.optString("from"),
        name = o.optString("name"),
        time = o.optString("time"),
        content = o.optString("content"),
        unread = o.optBoolean("unread"),
        starred = o.optBoolean("starred"),
        attachments = o.optJSONArray("attList")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val x = a.optJSONObject(i) ?: return@mapNotNull null
                MailAtt(x.optString("filename"), x.optString("url"))
            }
        }.orEmpty(),
        category = o.optString("category"),
        recipient = o.optJSONArray("recipient")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty(),
        cc = o.optJSONArray("cc")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty(),
    )

    private fun sumBytes(w: SQLiteDatabase, sql: String): Long =
        w.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /**
     * 清理顺序（长期缓存的容量约束）：
     * 1) 没打开过的旧正文（超过 1 小时）；
     * 2) 打开过但超过 7 天（[OPENED_KEEP]）的正文；
     * 3) 仍超字节上限时，先删没打开过的旧正文，再删打开过里最旧的（每次 20 条，留安全上限防死循环）。
     */
    private fun trimDetails(w: SQLiteDatabase, now: Long) {
        val unopenedWhere = "opened=0 AND updated_at<?"
        val unopenedBytes = detailRowsBytes(w, "opened=0 AND updated_at<${now - UNOPENED_KEEP}")
        w.delete("detail", unopenedWhere, arrayOf((now - UNOPENED_KEEP).toString()))
        val expiredWhere = "updated_at<?"
        val expiredBytes = detailRowsBytes(w, "updated_at<${now - OPENED_KEEP}")
        w.delete("detail", expiredWhere, arrayOf((now - OPENED_KEEP).toString()))
        detailBytes -= unopenedBytes + expiredBytes
        var guard = 0
        while (detailBytes > DETAIL_BUDGET_BYTES && guard++ < 200) {
            // 先清没打开过的旧正文；每轮只算这 20 条的实际占用，不再全表求和
            val coldWhere = "email_id IN (SELECT email_id FROM detail WHERE opened=0 ORDER BY updated_at ASC LIMIT 20)"
            val coldBytes = detailRowsBytes(w, coldWhere)
            if (w.delete("detail", coldWhere, null) > 0) {
                detailBytes -= coldBytes
                continue
            }
            val oldestWhere = "email_id IN (SELECT email_id FROM detail ORDER BY updated_at ASC LIMIT 20)"
            val oldestBytes = detailRowsBytes(w, oldestWhere)
            if (w.delete("detail", oldestWhere, null) == 0) break
            detailBytes -= oldestBytes
        }
    }

    /**
     * 列表清理：每个快照保留最近 [RETAIN_ROWS_PER_LIST] 行，按整页删除（只删页号最大的），
     * 保证剩下的 page 从 1 连续，重启后翻页不会跳号缺行；缺的页用户滚到底时会从服务端补。
     * 再按全库字节上限删「最久没刷新」的 key 的最后一页。
     */
    private fun trimLists(w: SQLiteDatabase, snap: ListSnapshot) {
        val pages = ArrayList<Pair<Int, Int>>()
        w.rawQuery(
            "SELECT page, COUNT(*) FROM list_row WHERE key=? GROUP BY page ORDER BY page ASC",
            arrayOf(snap.key),
        ).use { c -> while (c.moveToNext()) pages.add(c.getInt(0) to c.getInt(1)) }
        var rows = 0
        var lastKept = 0
        for ((p, n) in pages) {
            if (rows + n > RETAIN_ROWS_PER_LIST) break
            rows += n
            lastKept = p
        }
        val maxPage = pages.lastOrNull()?.first ?: 0
        if (lastKept in 1 until maxPage) {
            val later = arrayOf(snap.key, lastKept.toString())
            val dropped = listRowsBytes(w, "key=? AND page>?", later)
            w.delete("list_row", "key=? AND page>?", later)
            listBytes -= dropped
            snap.page = minOf(snap.page, lastKept)
        }
        var guard = 0
        while (listBytes > LIST_BUDGET_BYTES && guard++ < 200) {
            val victim = w.rawQuery(
                "SELECT key, MAX(page) FROM list_row GROUP BY key ORDER BY MIN(updated_at) ASC LIMIT 1",
                null,
            ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getInt(1) else null } ?: break
            val (vk, vp) = victim
            val victimArgs = arrayOf(vk, vp.toString())
            val dropped = listRowsBytes(w, "key=? AND page=?", victimArgs)
            w.delete("list_row", "key=? AND page=?", victimArgs)
            listBytes -= dropped
            // 删的正好是那个 key 的最后一页：把 meta.page 收回来，下次从这页重新拉
            val remain = w.rawQuery(
                "SELECT COALESCE(MAX(page),0) FROM list_row WHERE key=?", arrayOf(vk),
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            if (remain == 0) w.delete("list_meta", "key=?", arrayOf(vk))
            else w.execSQL("UPDATE list_meta SET page=? WHERE key=?", arrayOf<Any>(remain, vk))
        }
    }

    private class CacheDb(context: Context) : SQLiteOpenHelper(context, "otsmail_cache.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            // 主键 (key, email_id)：同一封邮件在一个列表里只允许一行，翻页位移不会叠出重复行
            db.execSQL("CREATE TABLE list_row (key TEXT NOT NULL, email_id INTEGER NOT NULL, page INTEGER NOT NULL, pos INTEGER NOT NULL, json TEXT NOT NULL, updated_at INTEGER NOT NULL, bytes INTEGER NOT NULL, PRIMARY KEY(key, email_id))")
            db.execSQL("CREATE INDEX idx_list_row_email ON list_row(email_id)")
            db.execSQL("CREATE TABLE list_meta (key TEXT PRIMARY KEY, total INTEGER NOT NULL, page INTEGER NOT NULL, updated_at INTEGER NOT NULL, scroll_index INTEGER NOT NULL, scroll_offset INTEGER NOT NULL)")
            // 新建库也要建全当前字段（opened/bytes 是 v3 引入的）：旧版 onCreate 少了这两列，
            // 全新安装第一次读磁盘正文就会报 no such column
            db.execSQL("CREATE TABLE detail (email_id INTEGER PRIMARY KEY, json TEXT NOT NULL, updated_at INTEGER NOT NULL, opened INTEGER NOT NULL DEFAULT 1, bytes INTEGER NOT NULL DEFAULT 0)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // v1 是 (key, page, pos) 主键，同一封邮件可以同时存在第 1、2 页。
                // 真实迁移而不是直接删库：把旧行搬进新表并按 email_id 去重。
                // ORDER BY page DESC, pos DESC 配合 INSERT OR REPLACE，最后写入的正是页码最小、位置最前的那条。
                db.execSQL("DROP INDEX IF EXISTS idx_list_row_email")
                db.execSQL("ALTER TABLE list_row RENAME TO list_row_v1")
                db.execSQL("CREATE TABLE list_row (key TEXT NOT NULL, email_id INTEGER NOT NULL, page INTEGER NOT NULL, pos INTEGER NOT NULL, json TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(key, email_id))")
                db.execSQL("CREATE INDEX idx_list_row_email ON list_row(email_id)")
                db.execSQL(
                    "INSERT OR REPLACE INTO list_row (key, email_id, page, pos, json, updated_at) " +
                        "SELECT key, email_id, page, pos, json, updated_at FROM list_row_v1 ORDER BY page DESC, pos DESC"
                )
                db.execSQL("DROP TABLE list_row_v1")
            }
            if (oldVersion < 3) {
                // 长期缓存：正文加「打开过」与估算字节。旧版本存下来的正文都是用户点开过才写的，
                // 所以默认 opened=1，升级后仍能秒开、不会丢已读内容。
                db.execSQL("ALTER TABLE detail ADD COLUMN opened INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE detail ADD COLUMN bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE detail SET bytes = length(json) * 2")
            }
            if (oldVersion < 4) {
                // 列表行也存估算字节：清理时只按受影响的页求和，不再每次全表 SUM(length(json))。
                // 用 PRAGMA 检查而不是直接 ADD：v3 时期的 onCreate 漏建了 opened/bytes，
                // 那批全新安装的库升级上来时 detail 也缺列，这里一并补齐。
                if (!hasColumn(db, "detail", "opened")) {
                    db.execSQL("ALTER TABLE detail ADD COLUMN opened INTEGER NOT NULL DEFAULT 1")
                }
                if (!hasColumn(db, "detail", "bytes")) {
                    db.execSQL("ALTER TABLE detail ADD COLUMN bytes INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE detail SET bytes = length(json) * 2")
                }
                if (!hasColumn(db, "list_row", "bytes")) {
                    db.execSQL("ALTER TABLE list_row ADD COLUMN bytes INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE list_row SET bytes = length(json) + 64")
                }
            }
        }

        private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == column) return true
                }
                false
            }
    }
}
