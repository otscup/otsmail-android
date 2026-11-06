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

/**
 * 第三方邮件本地库（独立 DB 文件，不碰现有 MailCache 的 otsmail_cache.db）。
 *
 * 只负责落地与查询，不触发任何网络请求；游标和邮件行放在同一个库里，
 * 断网重来先看 last_uid，绝不整箱重拉。
 */
object ExtMailStore {

    private const val DB_NAME = "otsmail_ext.db"
    private const val DB_VERSION = 1

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

    fun count(extId: Long): Int {
        db().rawQuery("SELECT COUNT(*) FROM ext_mail WHERE ext_id = ?", arrayOf(extId.toString())).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * 收件箱合并展示用：按时间倒序取窗口。
     *
     * [extIds] 为空表示不展示任何第三方邮件；[sinceMs] > 0 时只取这个时间点及更新的行
     * （与服务端已加载窗口对齐，避免把全库一次读进内存）；[limit] 兜底防止异常数据撑爆列表。
     */
    fun feed(extIds: Collection<Long>, sinceMs: Long, limit: Int): List<ExtLocalMail> {
        if (extIds.isEmpty() || limit <= 0) return emptyList()
        val ids = extIds.joinToString(",") { it.toString() }
        val where = buildString {
            append("ext_id IN (").append(ids).append(")")
            if (sinceMs > 0) append(" AND date_ms >= ").append(sinceMs)
        }
        val out = ArrayList<ExtLocalMail>()
        db().query(
            "ext_mail", null, where, null, null, null, "date_ms DESC, local_id DESC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ExtLocalMail(
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
                        snippet = c.getString(c.getColumnIndexOrThrow("snippet")),
                        bodyText = c.getString(c.getColumnIndexOrThrow("body_text")),
                        bodyHtml = c.getString(c.getColumnIndexOrThrow("body_html")),
                        attachmentsJson = c.getString(c.getColumnIndexOrThrow("att_json")),
                    ),
                )
            }
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
        db().delete("ext_state", "ext_id = ?", arrayOf(extId.toString()))
    }

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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 起步，暂不需要迁移
        }
    }
}
