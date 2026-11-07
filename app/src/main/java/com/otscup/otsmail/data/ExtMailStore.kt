package com.otscup.otsmail.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ExtLocalMail(
    val localId: Long = 0,
    val extId: Long,
    val uid: Long,
    val uidValidity: Long,
    val messageId: String,
    val subject: String,
    val fromName: String,
    val fromAddr: String,
    val toName: String,
    val toAddr: String,
    val cc: String,
    val dateMs: Long,
    val seen: Boolean,
    /** IMAP \Flagged 在本地的镜像；详情页星标按钮以它为准 */
    val starred: Boolean = false,
    val snippet: String,
    val bodyText: String,
    val bodyHtml: String,
    /** 附件元信息（JSON 数组，只含文件名 / 类型 / 大小，不含字节） */
    val attachmentsJson: String,
)

data class ExtLocalCursor(
    val uidValidity: Long,
    val lastUid: Long,
    val uidNext: Long,
    val lastSyncAt: Long,
    val lastError: String,
)

/** 外部账户直连 SMTP 发送成功后落本地的「已发送」副本。 */
data class ExtSentMail(
    val localId: Long = 0,
    val extId: Long,
    val messageId: String,
    val subject: String,
    val fromAddr: String,
    val toAddr: String,
    val cc: String,
    val dateMs: Long,
    val snippet: String,
    val bodyText: String,
    val bodyHtml: String,
    val attachmentsJson: String,
)

/**
 * 第三方邮件本地库（独立 DB 文件，不碰现有 MailCache 的 otsmail_cache.db）。
 *
 * 只负责落地与查询，不触发任何网络请求；游标和邮件行放在同一个库里，
 * 断网重来先看 last_uid，绝不整箱重拉。
 */
object ExtMailStore {

    private const val DB_NAME = "otsmail_ext.db"

    /** v2：ext_mail 增加 starred 列，并新增 ext_sent（外部账户直连 SMTP 的已发送本地副本） */
    private const val DB_VERSION = 2

    // 列表只取摘要列：body_text / body_html 不进列表查询，避免翻页时整窗正文进内存。
    private val RECEIVED_SUMMARY_COLUMNS = arrayOf(
        "local_id", "ext_id", "uid", "uid_validity", "message_id", "subject",
        "from_name", "from_addr", "to_name", "to_addr", "cc", "date_ms",
        "seen", "starred", "snippet", "att_json",
    )
    private val SENT_SUMMARY_COLUMNS = arrayOf(
        "local_id", "ext_id", "message_id", "subject", "from_addr", "to_addr",
        "cc", "date_ms", "snippet", "att_json",
    )

    // 关键词搜索在 SQL 里做（含正文匹配），列表不再为了本地过滤而读正文。
    private const val RECEIVED_SEARCH_FIELDS = 5
    private const val RECEIVED_SEARCH_WHERE =
        "instr(lower(subject), lower(?)) > 0 OR instr(lower(from_name), lower(?)) > 0 OR " +
            "instr(lower(from_addr), lower(?)) > 0 OR instr(lower(snippet), lower(?)) > 0 OR " +
            "instr(lower(body_text), lower(?)) > 0"
    private const val SENT_SEARCH_FIELDS = 5
    private const val SENT_SEARCH_WHERE =
        "instr(lower(subject), lower(?)) > 0 OR instr(lower(from_addr), lower(?)) > 0 OR " +
            "instr(lower(to_addr), lower(?)) > 0 OR instr(lower(snippet), lower(?)) > 0 OR " +
            "instr(lower(body_text), lower(?)) > 0"

    @Volatile
    private var helper: Helper? = null

    fun init(context: Context) {
        if (helper != null) return
        synchronized(this) {
            if (helper == null) helper = Helper(context.applicationContext)
        }
    }

    private fun db(): SQLiteDatabase =
        helper?.writableDatabase ?: throw IllegalStateException("ExtMailStore 未初始化")

    fun cursor(extId: Long): ExtLocalCursor? {
        db().query(
            "ext_state", null, "ext_id = ?", arrayOf(extId.toString()), null, null, null,
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ExtLocalCursor(
                uidValidity = c.getLong(c.getColumnIndexOrThrow("uid_validity")),
                lastUid = c.getLong(c.getColumnIndexOrThrow("last_uid")),
                uidNext = c.getLong(c.getColumnIndexOrThrow("uid_next")),
                lastSyncAt = c.getLong(c.getColumnIndexOrThrow("last_sync_at")),
                lastError = c.getString(c.getColumnIndexOrThrow("last_error")).orEmpty(),
            )
        }
    }

    /** 成功推进游标时顺手清掉上一次的错误；失败原因由 [markError] 单独写 */
    fun saveCursor(extId: Long, address: String, uidValidity: Long, lastUid: Long, uidNext: Long) {
        val values = ContentValues().apply {
            put("ext_id", extId)
            put("address", address)
            put("uid_validity", uidValidity)
            put("last_uid", lastUid)
            put("uid_next", uidNext)
            put("last_sync_at", System.currentTimeMillis())
            put("last_error", "")
        }
        val updated = db().update("ext_state", values, "ext_id = ?", arrayOf(extId.toString()))
        if (updated == 0) db().insert("ext_state", null, values)
    }

    fun markError(extId: Long, reason: String) {
        val values = ContentValues().apply {
            put("last_error", reason.take(300))
            put("last_sync_at", System.currentTimeMillis())
        }
        val updated = db().update("ext_state", values, "ext_id = ?", arrayOf(extId.toString()))
        if (updated == 0) {
            values.put("ext_id", extId)
            db().insert("ext_state", null, values)
        }
    }

    /** 按 (ext_id, message_id) 去重；返回是否真的新插入 */
    fun upsert(mail: ExtLocalMail): Boolean {
        val values = ContentValues().apply {
            put("ext_id", mail.extId)
            put("uid", mail.uid)
            put("uid_validity", mail.uidValidity)
            put("message_id", mail.messageId)
            put("subject", mail.subject)
            put("from_name", mail.fromName)
            put("from_addr", mail.fromAddr)
            put("to_name", mail.toName)
            put("to_addr", mail.toAddr)
            put("cc", mail.cc)
            put("date_ms", mail.dateMs)
            put("seen", if (mail.seen) 1 else 0)
            put("starred", if (mail.starred) 1 else 0)
            put("snippet", mail.snippet)
            put("body_text", mail.bodyText)
            put("body_html", mail.bodyHtml)
            put("att_json", mail.attachmentsJson)
            put("archived", 1)
            put("created_at", System.currentTimeMillis())
        }
        val row = db().insertWithOnConflict("ext_mail", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return row != -1L
    }

    /** 刚插入的这封邮件的本地主键（通知点击要用 local_id 打开详情） */
    fun lastLocalId(extId: Long, messageId: String): Long {
        db().query(
            "ext_mail", arrayOf("local_id"), "ext_id = ? AND message_id = ?",
            arrayOf(extId.toString(), messageId), null, null, "local_id DESC", "1",
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun count(extId: Long): Int {
        db().rawQuery("SELECT COUNT(*) FROM ext_mail WHERE ext_id = ?", arrayOf(extId.toString())).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 本地是否已有这个 UID（补历史时用来跳过重复下载，避免整封重新拉一遍） */
    fun hasUid(extId: Long, uid: Long): Boolean {
        if (uid <= 0) return false
        db().rawQuery(
            "SELECT 1 FROM ext_mail WHERE ext_id = ? AND uid = ? LIMIT 1",
            arrayOf(extId.toString(), uid.toString()),
        ).use { c -> return c.moveToFirst() }
    }

    /** 本地这一批邮件里最大的 UID：换机补完归档后用它续接 IMAP 增量 */
    fun maxUid(extId: Long): Long {
        db().rawQuery(
            "SELECT MAX(uid) FROM ext_mail WHERE ext_id = ?",
            arrayOf(extId.toString()),
        ).use { c -> return if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    /**
     * 收件箱合并展示用：按时间倒序取窗口。
     *
     * [extIds] 为空表示不展示任何第三方邮件；[sinceMs] > 0 时只取这个时间点及更新的行
     * （与服务端已加载窗口对齐，避免把全库一次读进内存）；[limit] 兜底防止异常数据撑爆列表。
     *
     * 列表只投影摘要列，**不再把正文读进内存**；关键词搜索下沉到 SQL（正文也参与匹配），
     * 正文仍由 [byLocalId] 在详情页按需取。
     */
    fun feed(
        extIds: Collection<Long>,
        sinceMs: Long,
        limit: Int,
        keyword: String = "",
    ): List<ExtLocalMail> {
        if (extIds.isEmpty() || limit <= 0) return emptyList()
        val ids = extIds.joinToString(",") { it.toString() }
        val args = ArrayList<String>()
        val where = buildString {
            append("ext_id IN (").append(ids).append(")")
            if (sinceMs > 0) append(" AND date_ms >= ").append(sinceMs)
            val k = keyword.trim()
            if (k.isNotEmpty()) {
                append(" AND (").append(RECEIVED_SEARCH_WHERE).append(")")
                repeat(RECEIVED_SEARCH_FIELDS) { args.add(k) }
            }
        }
        val out = ArrayList<ExtLocalMail>()
        db().query(
            "ext_mail", RECEIVED_SUMMARY_COLUMNS, where, args.toTypedArray(),
            null, null, "date_ms DESC, local_id DESC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) out.add(receivedSummaryRow(c))
        }
        return out
    }

    /** 详情页按本地主键取一封（合并列表里点的是第三方邮件） */
    fun byLocalId(localId: Long): ExtLocalMail? {
        db().query(
            "ext_mail", null, "local_id = ?", arrayOf(localId.toString()), null, null, null, "1",
        ).use { c ->
            if (!c.moveToNext()) return null
            return ExtLocalMail(
                localId = c.getLong(c.getColumnIndexOrThrow("local_id")),
                extId = c.getLong(c.getColumnIndexOrThrow("ext_id")),
                uid = c.getLong(c.getColumnIndexOrThrow("uid")),
                uidValidity = c.getLong(c.getColumnIndexOrThrow("uid_validity")),
                messageId = c.getString(c.getColumnIndexOrThrow("message_id")),
                subject = c.getString(c.getColumnIndexOrThrow("subject")),
                fromName = c.getString(c.getColumnIndexOrThrow("from_name")),
                fromAddr = c.getString(c.getColumnIndexOrThrow("from_addr")),
                toName = c.getString(c.getColumnIndexOrThrow("to_name")),
                toAddr = c.getString(c.getColumnIndexOrThrow("to_addr")),
                cc = c.getString(c.getColumnIndexOrThrow("cc")),
                dateMs = c.getLong(c.getColumnIndexOrThrow("date_ms")),
                seen = c.getInt(c.getColumnIndexOrThrow("seen")) == 1,
                starred = c.getInt(c.getColumnIndexOrThrow("starred")) == 1,
                snippet = c.getString(c.getColumnIndexOrThrow("snippet")),
                bodyText = c.getString(c.getColumnIndexOrThrow("body_text")),
                bodyHtml = c.getString(c.getColumnIndexOrThrow("body_html")),
                attachmentsJson = c.getString(c.getColumnIndexOrThrow("att_json")),
            )
        }
    }

    /** 解绑后清本地（第二批不接 UI，留给界面批次调用） */
    fun deleteAccount(extId: Long) {
        db().delete("ext_mail", "ext_id = ?", arrayOf(extId.toString()))
        db().delete("ext_sent", "ext_id = ?", arrayOf(extId.toString()))
        db().delete("ext_state", "ext_id = ?", arrayOf(extId.toString()))
    }

    // ---- 上游写操作成功后的本地镜像 ----

    fun setSeen(localId: Long, seen: Boolean) {
        val v = ContentValues().apply { put("seen", if (seen) 1 else 0) }
        db().update("ext_mail", v, "local_id = ?", arrayOf(localId.toString()))
    }

    fun setStarred(localId: Long, starred: Boolean) {
        val v = ContentValues().apply { put("starred", if (starred) 1 else 0) }
        db().update("ext_mail", v, "local_id = ?", arrayOf(localId.toString()))
    }

    /** 上游已移入 Trash / 已删除：本地同步移除，避免列表里点开一封服务器上不存在的信 */
    fun deleteReceived(localId: Long) {
        db().delete("ext_mail", "local_id = ?", arrayOf(localId.toString()))
    }

    // ---- 已发送（本地副本） ----

    fun saveSent(mail: ExtSentMail): Long {
        val values = ContentValues().apply {
            put("ext_id", mail.extId)
            put("message_id", mail.messageId)
            put("subject", mail.subject)
            put("from_addr", mail.fromAddr)
            put("to_addr", mail.toAddr)
            put("cc", mail.cc)
            put("date_ms", mail.dateMs)
            put("snippet", mail.snippet)
            put("body_text", mail.bodyText)
            put("body_html", mail.bodyHtml)
            put("att_json", mail.attachmentsJson)
            put("created_at", System.currentTimeMillis())
        }
        return db().insert("ext_sent", null, values)
    }

    /** 已发送列表同样只取摘要列；正文由 [sentByLocalId] 在详情页按需取。 */
    fun sentFeed(
        extIds: Collection<Long>,
        sinceMs: Long,
        limit: Int,
        keyword: String = "",
    ): List<ExtLocalMail> {
        if (extIds.isEmpty() || limit <= 0) return emptyList()
        val ids = extIds.joinToString(",") { it.toString() }
        val args = ArrayList<String>()
        val where = buildString {
            append("ext_id IN (").append(ids).append(")")
            if (sinceMs > 0) append(" AND date_ms >= ").append(sinceMs)
            val k = keyword.trim()
            if (k.isNotEmpty()) {
                append(" AND (").append(SENT_SEARCH_WHERE).append(")")
                repeat(SENT_SEARCH_FIELDS) { args.add(k) }
            }
        }
        val out = ArrayList<ExtLocalMail>()
        db().query(
            "ext_sent", SENT_SUMMARY_COLUMNS, where, args.toTypedArray(),
            null, null, "date_ms DESC, local_id DESC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) out.add(sentSummaryRow(c))
        }
        return out
    }

    fun sentByLocalId(localId: Long): ExtLocalMail? {
        db().query("ext_sent", null, "local_id = ?", arrayOf(localId.toString()), null, null, null, "1")
            .use { c -> return if (c.moveToNext()) sentRow(c) else null }
    }

    fun deleteSent(localId: Long) {
        db().delete("ext_sent", "local_id = ?", arrayOf(localId.toString()))
    }

    /** 列表行：正文置空，详情页另行按需读取 */
    private fun receivedSummaryRow(c: android.database.Cursor) = ExtLocalMail(
        localId = c.getLong(c.getColumnIndexOrThrow("local_id")),
        extId = c.getLong(c.getColumnIndexOrThrow("ext_id")),
        uid = c.getLong(c.getColumnIndexOrThrow("uid")),
        uidValidity = c.getLong(c.getColumnIndexOrThrow("uid_validity")),
        messageId = c.getString(c.getColumnIndexOrThrow("message_id")),
        subject = c.getString(c.getColumnIndexOrThrow("subject")),
        fromName = c.getString(c.getColumnIndexOrThrow("from_name")),
        fromAddr = c.getString(c.getColumnIndexOrThrow("from_addr")),
        toName = c.getString(c.getColumnIndexOrThrow("to_name")),
        toAddr = c.getString(c.getColumnIndexOrThrow("to_addr")),
        cc = c.getString(c.getColumnIndexOrThrow("cc")),
        dateMs = c.getLong(c.getColumnIndexOrThrow("date_ms")),
        seen = c.getInt(c.getColumnIndexOrThrow("seen")) == 1,
        starred = c.getInt(c.getColumnIndexOrThrow("starred")) == 1,
        snippet = c.getString(c.getColumnIndexOrThrow("snippet")),
        bodyText = "",
        bodyHtml = "",
        attachmentsJson = c.getString(c.getColumnIndexOrThrow("att_json")),
    )

    private fun sentSummaryRow(c: android.database.Cursor) = ExtLocalMail(
        localId = c.getLong(c.getColumnIndexOrThrow("local_id")),
        extId = c.getLong(c.getColumnIndexOrThrow("ext_id")),
        uid = 0,
        uidValidity = 0,
        messageId = c.getString(c.getColumnIndexOrThrow("message_id")),
        subject = c.getString(c.getColumnIndexOrThrow("subject")),
        fromName = "",
        fromAddr = c.getString(c.getColumnIndexOrThrow("from_addr")),
        toName = "",
        toAddr = c.getString(c.getColumnIndexOrThrow("to_addr")),
        cc = c.getString(c.getColumnIndexOrThrow("cc")),
        dateMs = c.getLong(c.getColumnIndexOrThrow("date_ms")),
        seen = true,
        starred = false,
        snippet = c.getString(c.getColumnIndexOrThrow("snippet")),
        bodyText = "",
        bodyHtml = "",
        attachmentsJson = c.getString(c.getColumnIndexOrThrow("att_json")),
    )

    private fun sentRow(c: android.database.Cursor) = ExtLocalMail(
        localId = c.getLong(c.getColumnIndexOrThrow("local_id")),
        extId = c.getLong(c.getColumnIndexOrThrow("ext_id")),
        uid = 0,
        uidValidity = 0,
        messageId = c.getString(c.getColumnIndexOrThrow("message_id")),
        subject = c.getString(c.getColumnIndexOrThrow("subject")),
        fromName = "",
        fromAddr = c.getString(c.getColumnIndexOrThrow("from_addr")),
        toName = "",
        toAddr = c.getString(c.getColumnIndexOrThrow("to_addr")),
        cc = c.getString(c.getColumnIndexOrThrow("cc")),
        dateMs = c.getLong(c.getColumnIndexOrThrow("date_ms")),
        seen = true,
        starred = false,
        snippet = c.getString(c.getColumnIndexOrThrow("snippet")),
        bodyText = c.getString(c.getColumnIndexOrThrow("body_text")),
        bodyHtml = c.getString(c.getColumnIndexOrThrow("body_html")),
        attachmentsJson = c.getString(c.getColumnIndexOrThrow("att_json")),
    )

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ext_state (
                    ext_id INTEGER PRIMARY KEY,
                    address TEXT NOT NULL DEFAULT '',
                    uid_validity INTEGER NOT NULL DEFAULT 0,
                    last_uid INTEGER NOT NULL DEFAULT 0,
                    uid_next INTEGER NOT NULL DEFAULT 0,
                    last_sync_at INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ext_mail (
                    local_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ext_id INTEGER NOT NULL,
                    uid INTEGER NOT NULL DEFAULT 0,
                    uid_validity INTEGER NOT NULL DEFAULT 0,
                    message_id TEXT NOT NULL,
                    subject TEXT NOT NULL DEFAULT '',
                    from_name TEXT NOT NULL DEFAULT '',
                    from_addr TEXT NOT NULL DEFAULT '',
                    to_name TEXT NOT NULL DEFAULT '',
                    to_addr TEXT NOT NULL DEFAULT '',
                    cc TEXT NOT NULL DEFAULT '',
                    date_ms INTEGER NOT NULL DEFAULT 0,
                    seen INTEGER NOT NULL DEFAULT 0,
                    starred INTEGER NOT NULL DEFAULT 0,
                    snippet TEXT NOT NULL DEFAULT '',
                    body_text TEXT NOT NULL DEFAULT '',
                    body_html TEXT NOT NULL DEFAULT '',
                    att_json TEXT NOT NULL DEFAULT '',
                    archived INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_ext_mail_dedup ON ext_mail(ext_id, message_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ext_mail_list ON ext_mail(ext_id, date_ms DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ext_mail_uid ON ext_mail(ext_id, uid)")
            createSentTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE ext_mail ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
                createSentTable(db)
            }
        }

        private fun createSentTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ext_sent (
                    local_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ext_id INTEGER NOT NULL,
                    message_id TEXT NOT NULL DEFAULT '',
                    subject TEXT NOT NULL DEFAULT '',
                    from_addr TEXT NOT NULL DEFAULT '',
                    to_addr TEXT NOT NULL DEFAULT '',
                    cc TEXT NOT NULL DEFAULT '',
                    date_ms INTEGER NOT NULL DEFAULT 0,
                    snippet TEXT NOT NULL DEFAULT '',
                    body_text TEXT NOT NULL DEFAULT '',
                    body_html TEXT NOT NULL DEFAULT '',
                    att_json TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ext_sent_list ON ext_sent(ext_id, date_ms DESC)")
        }
    }
}
