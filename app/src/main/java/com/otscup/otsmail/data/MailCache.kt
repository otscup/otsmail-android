package com.otscup.otsmail.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
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
    private const val RETENTION = 7 * 24 * 60 * 60 * 1000L

    private var db: CacheDb? = null
    private val lists = HashMap<String, ListSnapshot>()
    private val details = HashMap<Long, DetailEntry>()
    private val translations = HashMap<Long, String>()

    private data class DetailEntry(val mail: MailDetail, val at: Long)

    fun init(context: Context) {
        if (db == null) db = CacheDb(context.applicationContext)
    }

    fun list(key: String, accountId: Long): ListSnapshot =
        lists.getOrPut(key) { ListSnapshot(key, accountId) }

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
        translations[emailId] = html
    }

    fun clearTranslation(emailId: Long) {
        translations.remove(emailId)
    }

    // ---------------- 详情 ----------------

    /** 内存里的详情；第二个返回值表示是否还在 TTL 内。 */
    fun detailMemory(emailId: Long): Pair<MailDetail, Boolean>? {
        val e = details[emailId] ?: return null
        return e.mail to (System.currentTimeMillis() - e.at <= DETAIL_TTL)
    }

    suspend fun detailDisk(emailId: Long): Pair<MailDetail, Boolean>? = withContext(Dispatchers.IO) {
        val h = db ?: return@withContext null
        h.readableDatabase.query(
            "detail", arrayOf("json", "updated_at"), "email_id=?",
            arrayOf(emailId.toString()), null, null, null,
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            val at = c.getLong(1)
            val mail = runCatching { parseDetail(JSONObject(c.getString(0))) }.getOrNull()
                ?: return@withContext null
            mail to (System.currentTimeMillis() - at <= DETAIL_TTL)
        }
    }

    suspend fun saveDetail(mail: MailDetail) {
        details[mail.emailId] = DetailEntry(mail, System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            val h = db ?: return@withContext
            val cv = ContentValues().apply {
                put("email_id", mail.emailId)
                put("json", mail.toJson().toString())
                put("updated_at", System.currentTimeMillis())
            }
            h.writableDatabase.insertWithOnConflict("detail", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
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

    suspend fun savePage(snap: ListSnapshot, page: Int, items: List<MailItem>, total: Int) {
        snap.page = maxOf(snap.page, page)
        snap.total = total
        snap.updatedAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            val h = db ?: return@withContext
            val now = System.currentTimeMillis()
            val w = h.writableDatabase
            w.beginTransaction()
            try {
                w.delete("list_row", "key=? AND page=?", arrayOf(snap.key, page.toString()))
                items.forEachIndexed { i, m ->
                    val cv = ContentValues().apply {
                        put("key", snap.key)
                        put("page", page)
                        put("pos", i)
                        put("email_id", m.emailId)
                        put("json", m.toJson().toString())
                        put("updated_at", now)
                    }
                    w.insertWithOnConflict("list_row", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                val meta = ContentValues().apply {
                    put("key", snap.key)
                    put("total", total)
                    put("page", snap.page)
                    put("updated_at", now)
                    put("scroll_index", snap.scrollIndex)
                    put("scroll_offset", snap.scrollOffset)
                }
                w.insertWithOnConflict("list_meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)
                w.delete("list_row", "updated_at<?", arrayOf((now - RETENTION).toString()))
                w.delete("detail", "updated_at<?", arrayOf((now - RETENTION).toString()))
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
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
            val out = ArrayList<MailItem>(c.count)
            while (c.moveToNext()) {
                runCatching { parseItem(JSONObject(c.getString(0))) }.getOrNull()?.let { out.add(it) }
            }
            out
        }
        if (items.isEmpty()) return@withContext null
        Pages(items, meta[0] as Int, meta[1] as Int, meta[2] as Long, meta[3] as Int, meta[4] as Int)
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
        details[emailId]?.let { details[emailId] = DetailEntry(transform(it.mail), it.at) }
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
            snap.items.removeAll { it.emailId == emailId }
            if (snap.total > 0) snap.total -= 1
        }
        withContext(Dispatchers.IO) {
            db?.writableDatabase?.delete("list_row", "email_id=?", arrayOf(emailId.toString()))
        }
    }

    /** 只从某一个列表快照里移除（例如「已标星」里取消星标），其他 mailbox 不受影响。 */
    suspend fun removeRow(key: String, emailId: Long) {
        lists[key]?.let { snap ->
            snap.items.removeAll { it.emailId == emailId }
            if (snap.total > 0) snap.total -= 1
        }
        withContext(Dispatchers.IO) {
            db?.writableDatabase?.delete("list_row", "key=? AND email_id=?", arrayOf(key, emailId.toString()))
        }
    }

    private suspend fun patchRowsInDb(emailId: Long, transform: (MailItem) -> MailItem) {
        withContext(Dispatchers.IO) {
            val h = db ?: return@withContext
            h.writableDatabase.query(
                "list_row", arrayOf("rowid", "json"), "email_id=?",
                arrayOf(emailId.toString()), null, null, null,
            ).use { c ->
                val updates = ArrayList<Pair<Long, String>>(c.count)
                while (c.moveToNext()) {
                    val rowid = c.getLong(0)
                    val item = runCatching { parseItem(JSONObject(c.getString(1))) }.getOrNull() ?: continue
                    updates.add(rowid to transform(item).toJson().toString())
                }
                updates.forEach { (rowid, json) ->
                    val cv = ContentValues().apply { put("json", json) }
                    h.writableDatabase.update("list_row", cv, "rowid=?", arrayOf(rowid.toString()))
                }
            }
        }
    }

    /** 退出登录/切换用户：内存与磁盘全部清空，避免串号。 */
    suspend fun clearAll() {
        lists.clear()
        details.clear()
        translations.clear()
        withContext(Dispatchers.IO) {
            val w = db?.writableDatabase ?: return@withContext
            w.delete("list_row", null, null)
            w.delete("list_meta", null, null)
            w.delete("detail", null, null)
        }
    }

    /** 同步版本，给退出登录这种非协程上下文用（内存 + 磁盘一起清，避免串号）。 */
    fun wipe() {
        lists.clear()
        details.clear()
        translations.clear()
        runCatching {
            val w = db?.writableDatabase ?: return@runCatching
            w.delete("list_row", null, null)
            w.delete("list_meta", null, null)
            w.delete("detail", null, null)
        }
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
        .put("attList", JSONArray().also { arr -> attachments.forEach { arr.put(JSONObject().put("filename", it.filename).put("key", it.key)) } })

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
                MailAtt(x.optString("filename"), x.optString("key"))
            }
        }.orEmpty(),
        category = o.optString("category"),
    )

    private class CacheDb(context: Context) : SQLiteOpenHelper(context, "otsmail_cache.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE list_row (key TEXT NOT NULL, page INTEGER NOT NULL, pos INTEGER NOT NULL, email_id INTEGER NOT NULL, json TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(key, page, pos))")
            db.execSQL("CREATE INDEX idx_list_row_email ON list_row(email_id)")
            db.execSQL("CREATE TABLE list_meta (key TEXT PRIMARY KEY, total INTEGER NOT NULL, page INTEGER NOT NULL, updated_at INTEGER NOT NULL, scroll_index INTEGER NOT NULL, scroll_offset INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE detail (email_id INTEGER PRIMARY KEY, json TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS list_row")
            db.execSQL("DROP TABLE IF EXISTS list_meta")
            db.execSQL("DROP TABLE IF EXISTS detail")
            onCreate(db)
        }
    }
}
