package com.otscup.otsmail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 写信草稿：只存本机（filesDir/otsmail_drafts.json），按 accountId 隔离。
 *
 * 网页版的草稿在浏览器 IndexedDB，后端没有 draft 表；要做跨端同步得先加服务端接口。
 * 这里保存写信页能还原的全部字段（收件人/抄送/密送含正在输入的文字、主题、正文与光标、
 * 引用、附件、发件人），而不是原来只有 to/subject/text 的三字段。
 *
 * 用文件而不是 SharedPreferences：附件 base64 很容易超过 SP 能可靠写入的大小。
 */
data class Draft(
    val id: String,
    val accountId: Long,
    val fromId: Long,
    val toTags: List<String>,
    val toInput: String,
    val ccTags: List<String>,
    val ccInput: String,
    val bccTags: List<String>,
    val bccInput: String,
    val subject: String,
    val body: String,
    val selStart: Int,
    val selEnd: Int,
    val quoted: String,
    /** 附件 JSON 数组（与 /email/send 的 attachments 同结构），过大时 content 会被清空。 */
    val attachmentsJson: String,
    /** true 表示有附件因体积过大没能完整保存，恢复时要明确提示。 */
    val attachmentsDropped: Boolean,
    val updatedAt: Long,
) {
    fun isEmpty(): Boolean =
        toTags.isEmpty() && toInput.isBlank() &&
            ccTags.isEmpty() && ccInput.isBlank() &&
            bccTags.isEmpty() && bccInput.isBlank() &&
            subject.isBlank() && body.isBlank() &&
            (attachmentsJson.isBlank() || attachmentsJson == "[]")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("accountId", accountId)
        .put("fromId", fromId)
        .put("toTags", JSONArray(toTags))
        .put("toInput", toInput)
        .put("ccTags", JSONArray(ccTags))
        .put("ccInput", ccInput)
        .put("bccTags", JSONArray(bccTags))
        .put("bccInput", bccInput)
        .put("subject", subject)
        .put("body", body)
        .put("selStart", selStart)
        .put("selEnd", selEnd)
        .put("quoted", quoted)
        .put("attachments", attachmentsJson)
        .put("attachmentsDropped", attachmentsDropped)
        .put("updatedAt", updatedAt)

    companion object {
        private fun JSONArray?.list(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

        fun fromJson(o: JSONObject): Draft = Draft(
            id = o.optString("id"),
            accountId = o.optLong("accountId"),
            fromId = o.optLong("fromId"),
            toTags = o.optJSONArray("toTags").list(),
            toInput = o.optString("toInput"),
            ccTags = o.optJSONArray("ccTags").list(),
            ccInput = o.optString("ccInput"),
            bccTags = o.optJSONArray("bccTags").list(),
            bccInput = o.optString("bccInput"),
            subject = o.optString("subject"),
            body = o.optString("body"),
            selStart = o.optInt("selStart", -1),
            selEnd = o.optInt("selEnd", -1),
            quoted = o.optString("quoted"),
            attachmentsJson = o.optString("attachments").ifBlank { "[]" },
            attachmentsDropped = o.optBoolean("attachmentsDropped", false),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}

object Drafts {
    private const val FILE = "otsmail_drafts.json"
    private const val MAX_PER_ACCOUNT = 20
    /** 附件 base64 累计上限：超过的单条只留文件名/大小，恢复时提示。 */
    private const val MAX_ATTACH_CHARS = 2_000_000

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE)

    private fun readAll(ctx: Context): MutableList<Draft> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val raw = runCatching { f.readText() }.getOrDefault("[]")
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Draft::fromJson) }.toMutableList()
    }

    private fun writeAll(ctx: Context, list: List<Draft>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString())
                tmp.delete()
            }
        }
    }

    /** 某账号的草稿，最新在前。 */
    fun list(ctx: Context, accountId: Long): List<Draft> =
        readAll(ctx).filter { it.accountId == accountId }.sortedByDescending { it.updatedAt }

    fun get(ctx: Context, id: String): Draft? = readAll(ctx).firstOrNull { it.id == id }

    fun latest(ctx: Context, accountId: Long): Draft? = list(ctx, accountId).firstOrNull()

    fun delete(ctx: Context, id: String) {
        val all = readAll(ctx)
        all.removeAll { it.id == id }
        writeAll(ctx, all)
    }

    /**
     * 保存/覆盖一份草稿。附件过大时逐条剥掉 content，并置 attachmentsDropped。
     */
    fun save(ctx: Context, draft: Draft): Draft {
        val (json, dropped) = capAttachments(draft.attachmentsJson)
        val now = System.currentTimeMillis()
        val d = draft.copy(
            attachmentsJson = json,
            attachmentsDropped = draft.attachmentsDropped || dropped,
            updatedAt = now,
        )
        val all = readAll(ctx)
        all.removeAll { it.id == d.id }
        all.add(0, d)
        // 每个账号只保留最近 20 份，避免文件无限长
        val trimmed = ArrayList<Draft>(all.size)
        var kept = 0
        all.sortedByDescending { it.updatedAt }.forEach { item ->
            if (item.accountId != d.accountId || kept < MAX_PER_ACCOUNT) {
                trimmed.add(item)
                if (item.accountId == d.accountId) kept++
            }
        }
        writeAll(ctx, trimmed)
        return d
    }

    private fun capAttachments(raw: String): Pair<String, Boolean> {
        if (raw.isBlank() || raw == "[]") return "[]" to false
        val src = runCatching { JSONArray(raw) }.getOrNull() ?: return "[]" to false
        val out = JSONArray()
        var sum = 0
        var dropped = false
        for (i in 0 until src.length()) {
            val o = src.optJSONObject(i) ?: continue
            val copy = JSONObject(o.toString())
            val content = copy.optString("content")
            if (sum + content.length > MAX_ATTACH_CHARS) {
                copy.remove("content")
                dropped = true
            } else {
                sum += content.length
            }
            out.put(copy)
        }
        return out.toString() to dropped
    }
}
