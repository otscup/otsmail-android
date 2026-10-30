package com.otscup.otsmail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val httpCode: Int, override val message: String) : Exception(message)

data class MailItem(
    val emailId: Long,
    val from: String,
    val name: String,
    val subject: String,
    val text: String,
    val time: String,
    val unread: Boolean,
    val starred: Boolean,
    val category: String = "",
)

data class MailDetail(
    val emailId: Long,
    val subject: String,
    val from: String,
    val name: String,
    val time: String,
    val content: String,
    val unread: Boolean,
    val starred: Boolean,
    val attachments: List<String>,
    val category: String = "",
)

data class AiAction(val type: String, val ok: Boolean, val error: String)

data class Me(
    val userId: Long,
    val email: String,
    val name: String,
    val accountId: Long,
    val permKeys: List<String>,
    val signature: String = "",
    val sendOnEnter: String = "enter",
)

/**
 * 只有 HttpURLConnection + org.json 两个平台自带的东西，不加网络库。
 * 服务端统一回 {code, message, data}，data 可能是对象也可能是数组，所以两个入口。
 */
object Api {

    @Volatile var base: String = ""

    @Volatile var token: String = ""

    private suspend fun raw(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json")
                if (auth && token.isNotEmpty()) setRequestProperty("Authorization", token)
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toString().toByteArray()) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            val bizCode = json.optInt("code", code)
            if (code !in 200..299 || bizCode != 200) {
                throw ApiException(code, json.optString("message").ifEmpty { "HTTP $code" })
            }
            json
        }

    private suspend fun obj(method: String, path: String, body: JSONObject? = null): JSONObject =
        raw(method, path, body).optJSONObject("data") ?: JSONObject()

    private suspend fun arr(method: String, path: String, body: JSONObject? = null): JSONArray =
        raw(method, path, body).optJSONArray("data") ?: JSONArray()

    // ---- 会话 ----

    suspend fun login(email: String, password: String): String {
        val data = raw(
            "POST", "/login",
            JSONObject().put("email", email).put("password", password),
            auth = false,
        ).optJSONObject("data") ?: JSONObject()
        return data.optString("token").ifEmpty { throw ApiException(401, "服务器没有返回 token") }
    }

    suspend fun me(): Me {
        val d = obj("GET", "/my/loginUserInfo")
        val acc = d.optJSONObject("account")
        return Me(
            userId = d.optLong("userId"),
            email = d.optString("email"),
            name = d.optString("name").ifEmpty { d.optString("email") },
            accountId = acc?.optLong("accountId") ?: 0L,
            permKeys = d.optJSONArray("permKeys").toStringList(),
            signature = d.optString("signature"),
            sendOnEnter = d.optString("sendOnEnter").ifEmpty { "enter" },
        )
    }

    /** 只改签名与回车发送方式；后端会回读规范化后的值（发送方式只认 enter/ctrl/off）。 */
    suspend fun updateMySetting(signature: String, sendOnEnter: String): Me {
        obj(
            "PUT", "/my/setting",
            JSONObject().put("signature", signature).put("sendOnEnter", sendOnEnter),
        )
        return me()
    }

    // ---- 邮件 ----

    suspend fun emails(accountId: Long, page: Int, mailbox: String? = null, keyword: String = ""): Pair<List<MailItem>, Long> {
        val q = buildString {
            append("/email/list?accountId=").append(accountId)
            append("&size=20&page=").append(page)
            append("&allReceive=0&emailId=0&timeSort=1&type=0")
            if (!mailbox.isNullOrEmpty()) append("&mailbox=").append(mailbox)
            if (keyword.isNotEmpty()) append("&searchType=all&text=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
        }
        val d = obj("GET", q)
        val list = d.optJSONArray("list").toMailItems()
        return list to d.optLong("total")
    }

    /** 星标邮件：服务端要 size/page，且返回的 list 与 email/list 字段一致。 */
    suspend fun starred(page: Int): Pair<List<MailItem>, Long> {
        val d = obj("GET", "/star/list?size=20&page=$page")
        return d.optJSONArray("list").toMailItems() to d.optLong("total")
    }

    /** AI 对话：/ai/command 接收 text + accountId + history，返回 {reply, actions[]}。 */    suspend fun ai(text: String, accountId: Long, history: List<Pair<String, String>>, emailId: Long? = null): Pair<String, List<AiAction>> {
        val hist = org.json.JSONArray()
        history.takeLast(20).forEach { (role, t) ->
            hist.put(JSONObject().put("role", if (role == "me") "user" else "assistant").put("text", t))
        }
        val body = JSONObject()
            .put("text", text)
            .put("accountId", accountId)
            .put("history", hist)
        if (emailId != null) body.put("emailId", emailId)
        val d = obj("POST", "/ai/command", body)
        val acts = mutableListOf<AiAction>()
        d.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                acts.add(AiAction(o.optString("type"), o.optBoolean("ok", true), o.optString("error")))
            }
        }
        return d.optString("reply") to acts
    }

    // ---- 系统设置（需要 setting:set 权限，默认管理员）----

    /** GET /setting/query：AI 的 Key 会以 sk-xxxx****** 打码返回。 */
    suspend fun settings(): org.json.JSONObject = obj("GET", "/setting/query")

    /** PUT /setting/set：只传要改的字段；打码值不要回传（服务端会把 ****** 当未修改）。 */
    suspend fun setSetting(patch: JSONObject) {
        obj("PUT", "/setting/set", patch)
    }

    // ---- 管理页（全部邮件 / 用户 / 权限 / 注册密钥 / 分析）----

    /** GET /email/list 全量（accountId=0 表示不限），只取列表所需字段。 */
    suspend fun allEmails(page: Int, keyword: String = ""): Pair<List<MailItem>, Long> {
        val q = "/email/list?size=20&page=$page&allReceive=1&timeSort=1&type=0" +
            if (keyword.isBlank()) "" else "&text=" + java.net.URLEncoder.encode(keyword, "UTF-8") + "&searchType=all"
        val data = obj("GET", q)
        return data.optJSONArray("list").toMailItems() to data.optLong("total")
    }

    suspend fun users(page: Int, status: Int = -1): Pair<List<JSONObject>, Long> {
        val data = obj("GET", "/user/list?size=20&page=$page&status=$status&sort=desc")
        val arr = data.optJSONArray("list") ?: org.json.JSONArray()
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
        return list to data.optLong("total")
    }

    suspend fun addUser(email: String, password: String, type: Long) {
        obj("POST", "/user/add", JSONObject().put("email", email).put("password", password).put("type", type))
    }

    suspend fun setUserStatus(userId: Long, status: Int) {
        obj("PUT", "/user/setStatus", JSONObject().put("userId", userId).put("status", status))
    }

    suspend fun deleteUsers(userIds: List<Long>) {
        obj("DELETE", "/user/delete?userIds=" + userIds.joinToString(","))
    }

    suspend fun roles(): List<JSONObject> {
        val arr = arr("GET", "/role/list")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    suspend fun rolePerms(): List<Pair<String, List<JSONObject>>> {
        val arr = arr("GET", "/role/tree")
        return (0 until arr.length()).map { i ->
            val g = arr.getJSONObject(i)
            val perms = g.optJSONArray("children") ?: org.json.JSONArray()
            g.optString("name", g.optString("label", "")) to (0 until perms.length()).map { perms.getJSONObject(it) }
        }
    }

    suspend fun setRole(patch: JSONObject) {
        obj("PUT", "/role/set", patch)
    }

    suspend fun regKeys(page: Int): Pair<List<JSONObject>, Long> {
        val data = obj("GET", "/regKey/list?size=20&page=$page")
        val arr = data.optJSONArray("list") ?: org.json.JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) } to data.optLong("total")
    }

    suspend fun addRegKey(code: String, roleId: Long, count: Long, expireTime: String) {
        obj("POST", "/regKey/add", JSONObject().put("code", code).put("roleId", roleId).put("count", count).put("expireTime", expireTime))
    }

    suspend fun deleteRegKeys(ids: List<Long>) {
        obj("DELETE", "/regKey/delete?regKeyIds=" + ids.joinToString(","))
    }

    /** 菜单计数：{inbox, sent, deleted, spam, …}，分析页与收件箱标题共用。 */
    suspend fun counts(): JSONObject = obj("GET", "/email/menu-counts").also { }

    /** POST /translation/translate：lang 只收 zh/en/ja/ko/fr/de/es/ru/pt/it。 */
    suspend fun translate(emailId: Long, lang: String): String {
        val data = obj("POST", "/translation/translate", JSONObject().put("emailId", emailId).put("targetLang", lang))
        return data.optString("content", data.optString("text", ""))
    }

    suspend fun detail(emailId: Long): MailDetail {        val d = obj("GET", "/email/detail?emailId=$emailId")
        return MailDetail(
            emailId = d.optLong("emailId"),
            subject = d.optString("subject"),
            from = d.optString("sendEmail"),
            name = d.optString("name").ifEmpty { d.optString("sendEmail") },
            time = d.optString("createTime"),
            content = d.optString("content"),
            unread = d.optInt("unread", 1) == 0,
            starred = d.optInt("isStar", 0) == 1,
            attachments = d.optJSONArray("attList").mapObjects { it.optString("filename") },
            category = d.optString("category"),
        )
    }

    suspend fun markRead(emailId: Long) {
        obj("PUT", "/email/read", JSONObject().put("emailIds", JSONArray().put(emailId)))
    }

    suspend fun starAdd(emailId: Long) {
        obj("POST", "/star/add", JSONObject().put("emailId", emailId))
    }

    suspend fun starCancel(emailId: Long) {
        obj("DELETE", "/star/cancel?emailId=$emailId")
    }

    suspend fun spam(emailId: Long) {
        obj("POST", "/email/spam", JSONObject().put("emailId", emailId))
    }

    suspend fun trash(emailIds: List<Long>) {
        obj("DELETE", "/email/delete?emailIds=" + emailIds.joinToString(","))
    }

    suspend fun send(
        accountId: Long,
        to: List<String>,
        subject: String,
        text: String,
        html: String,
        attachments: List<JSONObject> = emptyList(),
    ) {
        val body = JSONObject()
            .put("accountId", accountId)
            .put("receiveEmail", JSONArray(to))
            .put("subject", subject)
            .put("text", text)
            .put("content", html)
            .put("sendType", 0)
            .put("attachments", JSONArray(attachments))
        obj("POST", "/email/send", body)
    }

    suspend fun categoryCounts(): Map<String, Int> {
        val d = obj("GET", "/email/category-counts?allReceive=0")
        return listOf("primary", "social", "promotion", "updates", "ads", "spam")
            .associateWith { d.optInt(it) }
    }

    suspend fun menuCounts(): Map<String, Int> {
        val d = obj("GET", "/email/menu-counts")
        return listOf("inbox", "unread", "sent", "deleted", "spam", "draft")
            .associateWith { d.optInt(it) }
    }

    // ---- 解析小工具 ----

    private fun JSONArray?.mapObjects(block: (JSONObject) -> String): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(block) }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }

    private fun JSONArray?.toMailItems(): List<MailItem> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            MailItem(
                emailId = o.optLong("emailId"),
                from = o.optString("sendEmail"),
                name = o.optString("name").ifEmpty { o.optString("sendEmail") },
                subject = o.optString("subject"),
                text = o.optString("text"),
                time = o.optString("createTime"),
                unread = o.optInt("unread", 1) == 0,
                starred = o.optInt("isStar", 0) == 1,
                category = o.optString("category"),
            )
        }
    }
}
