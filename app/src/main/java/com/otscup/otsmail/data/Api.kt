package com.otscup.otsmail.data

import android.util.Log
import com.otscup.otsmail.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(val httpCode: Int, override val message: String) : Exception(message)

/** 把底层网络异常翻成用户能看懂的中文，别把 exception message 直接贴到界面上。 */
fun friendlyError(e: Throwable): String {
    val m = e.message.orEmpty()
    return when {
        // 401 统一由 AuthState + 顶部提示处理：这里返回空串，各页面就不会再各弹一条「登录已失效」
        e is ApiException && e.httpCode == 401 -> ""
        // 第三方邮箱发信：SMTP 客户端已经把原因翻成中文（认证失败/超时/服务器拒收等），原样展示
        e is ExtSmtpException -> e.message.orEmpty().ifEmpty { "发信失败" }
        e is java.net.UnknownHostException || m.contains("Unable to resolve host", true) ||
            m.contains("No address associated", true) -> "无法解析服务器地址，请检查网络或服务器地址"
        m.contains("Network is unreachable", true) || m.contains("No route to host", true) ->
            "网络不可用，请检查网络连接"
        m.contains("Failed to connect", true) || m.contains("Connection refused", true) ->
            "连接不上服务器，请检查网络或服务器地址"
        m.contains("SSL", true) || m.contains("certificate", true) -> "安全连接失败，请检查服务器证书"
        m.contains("end of stream", true) || m.contains("unexpected", true) ->
            "服务器响应异常，请稍后重试"
        m.contains("timeout", true) || m.contains("timed out", true) -> "网络超时，请稍后重试"
        e is ApiException -> e.message.ifEmpty { "请求失败" }
        else -> "请求失败，请稍后重试"
    }
}

/**
 * AI 接口的失败文案：服务端没配 aiApiKey 时 Worker 回 503「AI 服务未配置，请联系管理员」，
 * 这里统一成产品要求的「AI 服务未配置，请联系服务器管理员」，不把别的错误伪装成这句话。
 */
fun aiErrorText(e: Throwable): String {
    val m = e.message.orEmpty()
    return when {
        e is ApiException && (e.httpCode == 503 || m.contains("AI 服务未配置") || m.contains("not configured", true)) ->
            "AI 服务未配置，请联系服务器管理员"
        else -> friendlyError(e).ifEmpty { "AI 请求失败，请稍后重试" }
    }
}

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
    val type: Int = 0,
    val status: Int = 0,
    val accountId: Long = 0,
    /** 收件地址（服务端 recipient 数组里的第一个）；发件行为收件人列表首项，展示时按 type 区分 */
    val recipient: String = "",
    /** 归属用户：全站列表按它判断「是不是自己的邮件」，普通列表为 0 */
    val userId: Long = 0,
    /** 收件邮箱原文（服务端 toEmail）：全站列表行内必须显示，否则分不清邮件属于哪个邮箱 */
    val toEmail: String = "",
    /** 收件人显示名（服务端 toName，来自原始 To 头）；全站列表优先显示它 */
    val toName: String = "",
    /** 该邮件所属用户的登录邮箱（服务端 userEmail），用于行内归属标签 */
    val userEmail: String = "",
    /** 邮件提取到的验证码（服务端解析优先，本地正则提取兜底） */
    val code: String = "",
    /** 退订链接/头信息（若有） */
    val listUnsubscribe: String = "",
)

/**
 * 附件：只用服务端 attList 里的签名 url（带 uid/exp/sig），不再拿 key 拼存储地址。
 * url 可能是相对路径（/attachments/...），展示前用 [Api.attachmentUrl] 补全站点的 origin。
 */
data class MailAtt(val filename: String, val url: String = "")

data class MailDetail(
    val emailId: Long,
    val subject: String,
    val from: String,
    val name: String,
    val time: String,
    val content: String,
    val unread: Boolean,
    val starred: Boolean,
    val attachments: List<MailAtt>,
    val category: String = "",
    /** 原 To 列表（服务端 recipient）：回复全部时一并带入 */
    val recipient: List<String> = emptyList(),
    /** 原 Cc 列表：回复全部时带入抄送 */
    val cc: List<String> = emptyList(),
    val code: String = "",
    val otpLink: String = "",
    val authSpf: String = "",
    val authDmarc: String = "",
    val listUnsubscribe: String = "",
    val listUnsubscribePost: String = "",
    val status: Int = 0,
    val type: Int = 0,
)

/**
 * 全站只读邮件：不属于当前账号（含 userId=0 的「无人收件」），服务端 /email/detail 按 userId
 * 过滤拿不到，正文只能来自 /allEmail/list 的整行返回。详情页只读，不提供任何写操作。
 */
data class SiteMail(
    val detail: MailDetail,
    val userId: Long,
    val status: Int,
    val toEmail: String,
    /** 收件人显示名（服务端 toName）；详情展示优先用它，空则退回地址 */
    val toName: String,
    /** 服务端 text 原文，复制正文时优先用它（HTML 只用来渲染） */
    val plain: String,
)

/** 翻译结果：成功时服务端返回 translatedContent；alreadyInTargetLang=true 表示正文本来就是目标语言。 */
data class Translation(
    val subject: String,
    val content: String,
    val sourceLang: String,
    val alreadyInTargetLang: Boolean,
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
    val avatar: String = "",
    val twoFactor: Boolean = false,
    val twoFactorRequired: Boolean = false,
    /** 服务端 user.type 原样（当 roleId 用）；0 = 系统管理员，全站邮件入口的门槛之一 */
    val type: Long = -1,
    val recoveryEmail: String = "",
    val disablePasswordLogin: Boolean = false,
)

data class UnsubscribeItem(
    val sendEmail: String,
    val name: String,
    val emailId: Long,
    val createTime: String,
    val count: Int,
    val listUnsubscribe: String,
    val listUnsubscribePost: String,
)

data class SessionDeviceItem(
    val id: String,
    val current: Boolean,
    val ip: String,
    val browser: String,
    val os: String,
    val device: String,
    val time: String,
)

/**
 * 只有 HttpURLConnection + org.json 两个平台自带的东西，不加网络库。
 * 服务端统一回 {code, message, data}，data 可能是对象也可能是数组，所以两个入口。
 */
object Api {

    @Volatile var base: String = ""

    @Volatile var token: String = ""

    private const val TAG = "Api"

    private suspend fun raw(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 20_000,
        /** 覆盖全局 base：退出登录/换服务器时全局地址已经变了，旧请求仍要打旧服务器 */
        baseUrl: String? = null,
    ): JSONObject {
        // 鉴权上下文在**调用方线程**快照：连接是在 IO 线程里造的，中间可能发生
        // 「退出登录 → 立刻换号登录」，那时全局 token/base 已经是新会话的了。
        // 快照后旧请求只可能用旧令牌打旧服务器，不会串到新账号上。
        val authToken = if (auth) token else ""
        val targetBase = baseUrl ?: base
        return withContext(Dispatchers.IO) {
            // 演示构建（-Pdemo=true，独立 applicationId）：全部请求由本地虚构数据应答，
            // 不连服务器、不碰真实账户。正式包 BuildConfig.DEMO=false，这里不会命中。
            if (BuildConfig.DEMO) return@withContext Demo.respond(method, path, body)
            val conn = (URL(targetBase.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json")
                if (authToken.isNotEmpty()) setRequestProperty("Authorization", authToken)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toString().toByteArray()) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val json = if (text.isBlank()) {
                JSONObject()
            } else {
                try {
                    JSONObject(text)
                } catch (e: org.json.JSONException) {
                    // 网关错误页 / 413 / 1102 之类不是 JSON：把状态码和开头一段带出来，
                    // 否则所有这类失败都会被统一翻成「请求失败，请稍后重试」，看不到真实原因
                    val body = text.replace(Regex("\\s+"), " ").trim().take(160)
                    Log.w(TAG, "$method $path 非 JSON 响应 HTTP $code：$body")
                    throw ApiException(code, "HTTP $code $body")
                }
            }
            val bizCode = json.optInt("code", code)
            // 业务错误码优先：服务端 2FA 用 428 表达「缺验证码」，HTTP 状态仍是 200
            val failCode = if (code in 200..299) bizCode else code
            // 401 只在这里登记一次，界面统一显示顶部「登录已过期」；token 与缓存都不清。
            // 仅当请求用的令牌仍是当前令牌时才登记：否则这是上一次会话的迟到响应，
            // 不能把已经重新登录成功的新会话误标成过期（换服务器同理，base 也一起比对）。
            if (auth && failCode == 401 && authToken == token && targetBase.trimEnd('/') == base.trimEnd('/')) {
                AuthState.expire()
            }
            if (code !in 200..299 || bizCode != 200) {
                throw ApiException(failCode, json.optString("message").ifEmpty { "HTTP $code" })
            }
            json
        }
    }

    private suspend fun obj(
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = 20_000,
    ): JSONObject = raw(method, path, body, readTimeoutMs = readTimeoutMs).optJSONObject("data") ?: JSONObject()

    private suspend fun arr(method: String, path: String, body: JSONObject? = null): JSONArray =
        raw(method, path, body).optJSONArray("data") ?: JSONArray()

    // ---- 会话 ----

    suspend fun login(email: String, password: String, twoFactorCode: String = ""): String {
        val body = JSONObject().put("email", email).put("password", password)
        if (twoFactorCode.isNotEmpty()) body.put("twoFactorCode", twoFactorCode)
        val data = raw("POST", "/login", body, auth = false).optJSONObject("data") ?: JSONObject()
        return data.optString("token").ifEmpty { throw ApiException(401, "服务器没有返回 token") }
    }

    /**
     * POST /my/qr/confirm：用当前登录会话确认网页端扫码登录。
     * 失败码：400 二维码无效/已确认，410 已过期，401 登录失效（由 AuthState 统一提示）。
     */
    suspend fun qrConfirm(id: String) {
        obj("POST", "/my/qr/confirm", JSONObject().put("id", id))
    }

    /** POST /register：code 为注册码（服务端 regKey=0 必填、=2 可选），token 为人机验证 token。 */
    suspend fun register(email: String, password: String, code: String, turnstileToken: String = "") {
        val body = JSONObject().put("email", email).put("password", password)
        if (code.isNotEmpty()) body.put("code", code)
        if (turnstileToken.isNotEmpty()) body.put("token", turnstileToken)
        raw("POST", "/register", body, auth = false)
    }

    /** GET /setting/websiteConfig：登录前可读的站点配置（注册开关/域名/第三方登录等）。 */
    suspend fun websiteConfig(): JSONObject = obj("GET", "/setting/websiteConfig")

    /** GET /oauth/oidc/config：企业 SSO 授权端点与公开 clientId。 */
    suspend fun oidcConfig(providerId: String): JSONObject {
        val q = if (providerId.isEmpty()) "/oauth/oidc/config" else "/oauth/oidc/config?provider=$providerId"
        return obj("GET", q)
    }

    /**
     * 第三方登录换 token。服务端用 Cookie 里的 oauth_nonce 校验 state，
     * 浏览器里由登录页写 cookie，原生端只能自己带同名 Cookie 头。
     */
    suspend fun oauthLogin(
        provider: String,
        code: String,
        state: String,
        nonce: String,
        providerId: String = "",
        codeVerifier: String = "",
    ): String {
        val body = JSONObject().put("code", code).put("state", state).put("nonce", nonce)
        if (providerId.isNotEmpty()) body.put("provider", providerId)
        if (codeVerifier.isNotEmpty()) body.put("codeVerifier", codeVerifier)
        val data = raw(
            "POST", "/oauth/$provider/login", body,
            auth = false,
            headers = mapOf("Cookie" to "oauth_nonce=$nonce"),
        ).optJSONObject("data") ?: JSONObject()
        return data.optString("token")
    }

    /**
     * 附件地址：一律用服务端 attList 里的签名 url。
     * 服务端返回的是相对路径（/attachments/xxx?uid=&exp=&sig=），这里补站点 origin；
     * url 缺失时返回空串，调用方必须提示「附件地址不可用」，不能拿 key 再拼旧路径。
     */
    fun attachmentUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return ""
        if (u.startsWith("http://", true) || u.startsWith("https://", true) || u.startsWith("data:", true)) return u
        val origin = runCatching {
            val uri = java.net.URI(base.trimEnd('/'))
            "${uri.scheme}://${uri.authority}"
        }.getOrNull().orEmpty()
        if (origin.isEmpty()) return ""
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> origin + u
            else -> "$origin/$u"
        }
    }

    // ---- 推送设备登记（服务端接口尚未实现，失败由 PushManager 静默处理）----

    suspend fun pushRegister(token: String, deviceId: String, appVersion: String) {
        val body = JSONObject()
            .put("token", token)
            .put("platform", "android")
            .put("deviceId", deviceId)
            .put("appVersion", appVersion)
        raw("POST", PushScaffold.REGISTER_PATH, body)
    }

    suspend fun pushUnregister(authToken: String, token: String) {
        if (authToken.isEmpty() || token.isEmpty()) return
        raw(
            "DELETE",
            PushScaffold.REGISTER_PATH + "?token=" + URLEncoder.encode(token, "UTF-8"),
            null,
            auth = false,
            headers = mapOf("Authorization" to authToken),
        )
    }

    /**
     * 退出登录：DELETE /logout。
     * 地址与令牌都由调用方显式传入（退出时会立刻清本地登录态，全局 base/token 随后就不可靠了）；
     * auth=false 是为了不触发 401 的「登录已过期」提示——退出本来就是主动结束会话。
     */
    suspend fun logout(baseUrl: String, authToken: String) {
        if (baseUrl.isEmpty() || authToken.isEmpty()) return
        raw(
            "DELETE",
            "/logout",
            null,
            auth = false,
            baseUrl = baseUrl,
            headers = mapOf("Authorization" to authToken),
        )
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
            avatar = d.optString("avatar"),
            twoFactor = d.optBoolean("twoFactor", false),
            twoFactorRequired = d.optBoolean("twoFactorRequired", false),
            type = d.optLong("type", -1L),
            recoveryEmail = d.optString("recoveryEmail"),
            disablePasswordLogin = d.optBoolean("disablePasswordLogin", false),
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

    // ---- 个人设置（对应网页端 /my/*）----

    /** PUT /my/resetPassword：服务端只校验长度 >= 6。 */
    suspend fun resetPassword(password: String) {
        obj("PUT", "/my/resetPassword", JSONObject().put("password", password))
    }

    /** POST /my/setAvatar：只接受 http(s) 外链。 */
    suspend fun setAvatar(url: String): String {
        val d = obj("POST", "/my/setAvatar", JSONObject().put("avatar", url))
        return d.optString("avatar")
    }

    /** POST /my/uploadAvatar：data:image/...;base64, 前缀，服务端存 R2 后回外链。 */
    suspend fun uploadAvatar(base64: String): String {
        val d = obj("POST", "/my/uploadAvatar", JSONObject().put("base64", base64))
        return d.optString("url")
    }

    /** PUT /account/setName：网页端个人资料页的「用户名」改的就是这个。 */
    suspend fun setAccountName(accountId: Long, name: String) {
        obj("PUT", "/account/setName", JSONObject().put("accountId", accountId).put("name", name))
    }

    suspend fun twoFactorStatus(): Pair<Boolean, Boolean> {
        val d = obj("GET", "/my/2fa/status")
        return d.optBoolean("enabled", false) to d.optBoolean("required", false)
    }

    suspend fun twoFactorSetup(): Pair<String, String> {
        val d = obj("POST", "/my/2fa/setup")
        return d.optString("secret") to d.optString("otpauthUrl")
    }

    suspend fun twoFactorEnable(code: String) {
        obj("POST", "/my/2fa/enable", JSONObject().put("code", code))
    }

    /** 验证码或登录密码任一即可关闭（对应网页端 /my/2fa/disable）。 */
    suspend fun twoFactorDisable(code: String = "", password: String = "") {
        val body = JSONObject()
        if (code.isNotEmpty()) body.put("code", code)
        if (password.isNotEmpty()) body.put("password", password)
        obj("POST", "/my/2fa/disable", body)
    }

    suspend fun setTwoFactorRequired(required: Boolean) {
        obj("PUT", "/my/2fa/required", JSONObject().put("required", required))
    }

    suspend fun deleteMyAccount() {
        obj("DELETE", "/my/delete")
    }

    /** 绑定/修改辅助安全邮箱（需输入当前密码核验身份；传空解绑） */
    suspend fun updateRecoveryEmail(recoveryEmail: String, password: String): String {
        val body = JSONObject().apply {
            put("recoveryEmail", recoveryEmail)
            put("password", password)
        }
        val d = obj("PUT", "/my/recoveryEmail", body = body)
        return d.optString("recoveryEmail")
    }

    /** 切换关闭/开启密码登录（安全熔断：若要关闭，必须至少绑定一种第三方或免密方式） */
    suspend fun togglePasswordLogin(disable: Boolean): Boolean {
        val body = JSONObject().apply {
            put("disable", disable)
        }
        val d = obj("PUT", "/my/togglePasswordLogin", body = body)
        return d.optBoolean("disablePasswordLogin", disable)
    }

    /** 获取当前账号的所有在线设备与会话列表 */
    suspend fun getSessions(): List<SessionDeviceItem> {
        val arr = arr("GET", "/my/sessions")
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    SessionDeviceItem(
                        id = o.optString("id"),
                        current = o.optBoolean("current", false),
                        ip = o.optString("ip"),
                        browser = o.optString("browser"),
                        os = o.optString("os"),
                        device = o.optString("device"),
                        time = o.optString("time"),
                    )
                )
            }
        }
    }

    /** 踢出并注销指定设备会话 */
    suspend fun kickSession(sessionId: String) {
        obj("DELETE", "/my/sessions/$sessionId")
    }

    /** 通行密钥（Passkey）凭据项 */
    data class PasskeyItem(
        val id: Long,
        val name: String,
        val credentialId: String,
        val createdAt: String,
    )

    /** 获取用户已绑定的通行密钥列表 */
    suspend fun passkeyList(): List<PasskeyItem> {
        val arr = arr("GET", "/my/passkey/list")
        val out = ArrayList<PasskeyItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                PasskeyItem(
                    id = o.optLong("id"),
                    name = o.optString("name").ifEmpty { "通行密钥" },
                    credentialId = o.optString("credentialId"),
                    createdAt = o.optString("createdAt"),
                ),
            )
        }
        return out
    }

    /** 生成用于注册通行密钥的 challenge 与配置 */
    suspend fun passkeyRegisterOptions(): JSONObject =
        obj("POST", "/my/passkey/register/options")

    /** 提交已注册的通行密钥凭据验证并落库 */
    suspend fun passkeyRegisterVerify(credentialJson: JSONObject) {
        raw("POST", "/my/passkey/register/verify", credentialJson)
    }

    /** 删除指定的通行密钥凭据 */
    suspend fun passkeyDelete(id: Long) {
        raw("DELETE", "/my/passkey/$id")
    }

    /** 免密登录：生成挑战参数 */
    suspend fun passkeyLoginOptions(): JSONObject =
        raw("POST", "/login/passkey/options", auth = false).optJSONObject("data") ?: JSONObject()

    /** 免密登录：验证凭据并返回 session token */
    suspend fun passkeyLoginVerify(credentialJson: JSONObject): String {
        val res = raw("POST", "/login/passkey/verify", credentialJson, auth = false).optJSONObject("data") ?: JSONObject()
        return res.optString("token").ifEmpty { throw ApiException(401, "免密登录验证失败") }
    }

    /** 获取聚合邮件订阅与退订列表（RFC 8058 / RFC 2369） */
    suspend fun unsubscribeList(): List<UnsubscribeItem> {
        val arr = arr("GET", "/email/unsubscribeList")
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    UnsubscribeItem(
                        sendEmail = o.optString("sendEmail"),
                        name = o.optString("name"),
                        emailId = o.optLong("emailId"),
                        createTime = o.optString("createTime"),
                        count = o.optInt("count", 1),
                        listUnsubscribe = o.optString("listUnsubscribe"),
                        listUnsubscribePost = o.optString("listUnsubscribePost"),
                    )
                )
            }
        }
    }

    // ---- 邮件 ----

    /** 多账户：/account/list 的 data 直接是数组。 */
    suspend fun accounts(): List<Pair<Long, String>> {
        val arr = arr("GET", "/account/list")
        val out = ArrayList<Pair<Long, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("accountId")
            if (id == 0L) continue
            out.add(id to o.optString("email").ifEmpty { o.optString("name") })
        }
        return out
    }

    /** 添加邮箱账户（POST /account/add） */
    suspend fun addAccount(email: String, token: String = ""): JSONObject {
        val body = JSONObject().put("email", email)
        if (token.isNotEmpty()) body.put("token", token)
        return obj("POST", "/account/add", body)
    }

    // ---- 第三方邮箱（聚合收件，第一批只做绑定与探测） ----

    /** GET /ext/providers：服务端自动探测规则表（客户端另有离线副本）。 */
    suspend fun extProviders(): JSONArray = arr("GET", "/ext/providers")

    /** GET /ext/accounts：已绑定的第三方邮箱元信息，不含凭据。 */
    suspend fun extAccounts(): JSONArray = arr("GET", "/ext/accounts")

    /**
     * POST /ext/bind：提交已验证的凭据。
     * 明文只在这里传一次，服务端立刻 AES-GCM 加密落库；客户端不缓存。
     */
    suspend fun bindExtAccount(
        address: String,
        providerId: String,
        authType: String,
        secret: String,
        imapOk: Boolean,
        smtpOk: Boolean,
        imapIdle: Boolean,
        imapHost: String = "",
        imapPort: Int = 0,
        smtpHost: String = "",
        smtpPort: Int = 0,
    ): JSONObject {
        val probe = JSONObject()
            .put("imapOk", imapOk)
            .put("smtpOk", smtpOk)
            .put("imapIdle", imapIdle)
        val body = JSONObject()
            .put("address", address)
            .put("providerId", providerId)
            .put("authType", authType)
            .put("secret", secret)
            .put("probe", probe)
        if (imapHost.isNotEmpty() && imapPort > 0) {
            body.put("imapHost", imapHost).put("imapPort", imapPort)
        }
        if (smtpHost.isNotEmpty() && smtpPort > 0) {
            body.put("smtpHost", smtpHost).put("smtpPort", smtpPort)
        }
        return obj("POST", "/ext/bind", body)
    }

    /** POST /ext/accounts/delete：解绑，同时把凭据行与本地账户行标记删除。 */
    suspend fun deleteExtAccount(extId: Long) {
        raw("POST", "/ext/accounts/delete", JSONObject().put("extId", extId))
    }

    /**
     * POST /ext/accounts/credential：拉取层临时取口令（服务端解密后经 TLS 单次下发）。
     * 调用方只在内存里用到连接结束，绝不落盘、绝不写日志。
     */
    suspend fun extCredential(extId: Long): JSONObject =
        obj("POST", "/ext/accounts/credential", JSONObject().put("extId", extId))

    /** POST /ext/mail/archive：一封一封归档，幂等（按账号 + Message-ID 去重）。 */
    suspend fun archiveExtMail(body: JSONObject): JSONObject =
        obj("POST", "/ext/mail/archive", body, readTimeoutMs = 60_000)

    /** GET /ext/mail/list：读回服务端已归档的邮件（换机补历史，只读，不改游标）。 */
    suspend fun extMailList(
        extId: Long,
        limit: Int = 50,
        beforeDate: Long = 0,
        beforeMailId: Long = 0,
    ): JSONObject = obj(
        "GET",
        "/ext/mail/list?extId=$extId&limit=$limit&beforeDate=$beforeDate&beforeMailId=$beforeMailId",
        readTimeoutMs = 60_000,
    )

    /** POST /ext/mail/sync-error：把这一轮失败原因写回服务端游标表，方便换机后看诊断。 */
    suspend fun extSyncError(extId: Long, message: String, uidValidity: Long) {
        raw(
            "POST", "/ext/mail/sync-error",
            JSONObject()
                .put("extId", extId)
                .put("message", message.take(300))
                .put("uidValidity", uidValidity),
        )
    }

    /**
     * 通讯录：/contact/list 的 data 是 { list, total }，取 list。返回 名称/邮箱/分组。
     * q 走服务端 LIKE（name/email 包含匹配），联系人表里没有「最近往来 / 使用频次」字段，
     * 所以写信页的候选只能按前缀/包含程度排序，不做假的“常用”排序。
     */
    suspend fun contacts(q: String = ""): List<Triple<String, String, String>> =
        contactList(q).first.map { Triple(it.name, it.email, it.group) }

    /** 通讯录条目：服务端 contact/list 的真实字段（contactId/name/email/groupName/remark）。 */
    data class ContactItem(
        val id: Long,
        val name: String,
        val email: String,
        val group: String,
        val remark: String = "",
    ) {
        val isVip: Boolean
            get() = group.equals("VIP", ignoreCase = true) ||
                remark.contains("VIP", ignoreCase = true) ||
                remark.contains("特别关注", ignoreCase = true)
    }

    /** 内存快照：特别关注（VIP）联系人邮箱集合，供列表极速判断加星 */
    @Volatile var vipEmails: Set<String> = emptySet()

    fun isVipSender(email: String): Boolean {
        if (email.isBlank() || vipEmails.isEmpty()) return false
        val clean = email.substringAfter("<").substringBefore(">").trim().lowercase()
        return vipEmails.contains(clean)
    }

    /** 返回联系人列表 + 服务端已有的分组名（分组下拉基于全量，不受 q/group 过滤影响）。 */
    suspend fun contactList(q: String = "", group: String = ""): Pair<List<ContactItem>, List<String>> {
        val query = StringBuilder("/contact/list?page=1&size=200")
        if (q.isNotBlank()) query.append("&q=").append(URLEncoder.encode(q, "UTF-8"))
        if (group.isNotBlank()) query.append("&group=").append(URLEncoder.encode(group, "UTF-8"))
        val data = obj("GET", query.toString())
        val list = data.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<ContactItem>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val email = o.optString("email")
            if (!email.contains("@")) continue
            out.add(
                ContactItem(
                    id = o.optLong("contactId"),
                    name = o.optString("name").ifEmpty { email.substringBefore("@") },
                    email = email,
                    group = o.optString("groupName"),
                    remark = o.optString("remark"),
                ),
            )
        }
        val vips = out.filter { it.isVip }.map { it.email.trim().lowercase() }.toSet()
        vipEmails = if (q.isBlank() && group.isBlank()) vips else (vipEmails + vips)

        val groups = data.optJSONArray("groups") ?: JSONArray()
        val groupNames = ArrayList<String>()
        for (i in 0 until groups.length()) {
            groups.optString(i).takeIf { it.isNotBlank() }?.let { groupNames.add(it) }
        }
        return out to groupNames
    }

    /** 快速切换联系人的 VIP 特别关注状态 */
    suspend fun contactToggleVip(item: ContactItem) {
        val willBeVip = !item.isVip
        val newGroup = if (willBeVip) {
            if (item.group.isBlank()) "VIP" else item.group
        } else {
            if (item.group.equals("VIP", ignoreCase = true)) "" else item.group
        }
        val newRemark = if (willBeVip) {
            if (item.remark.isBlank()) "VIP特别关注" else if (!item.remark.contains("VIP", ignoreCase = true)) "${item.remark} [VIP]" else item.remark
        } else {
            item.remark.replace("[VIP]", "").replace("(VIP)", "").replace("VIP特别关注", "").replace("特别关注", "").trim()
        }
        if (item.id > 0) {
            contactSet(item.id, item.name, item.email, newGroup, newRemark)
        } else {
            contactAdd(item.name, item.email, newGroup, newRemark)
        }
        val cleanEmail = item.email.trim().lowercase()
        vipEmails = if (willBeVip) (vipEmails + cleanEmail) else (vipEmails - cleanEmail)
    }

    /**
     * 新增/保存联系人。同名接口服务端按 email 做 upsert，所以 add 也带完整字段。
     * 分组留空时服务端回落「默认」，这里不做本地默认值，避免和网页端不一致。
     */
    suspend fun contactAdd(name: String, email: String, group: String, remark: String) {
        obj(
            "POST", "/contact/add",
            JSONObject()
                .put("name", name).put("email", email)
                .put("groupName", group).put("remark", remark),
        )
    }

    suspend fun contactSet(id: Long, name: String, email: String, group: String, remark: String) {
        obj(
            "POST", "/contact/set",
            JSONObject()
                .put("contactId", id).put("name", name).put("email", email)
                .put("groupName", group).put("remark", remark),
        )
    }

    suspend fun contactDelete(id: Long) {
        obj("POST", "/contact/delete", JSONObject().put("contactId", id))
    }

    suspend fun emails(accountId: Long, page: Int, mailbox: String? = null, keyword: String = "", category: String = "", allAccounts: Boolean = false): Pair<List<MailItem>, Long> =
        parseList(emailsRaw(accountId, page, mailbox, keyword, category, allAccounts))

    suspend fun emailsRaw(
        accountId: Long,
        page: Int,
        mailbox: String? = null,
        keyword: String = "",
        category: String = "",
        allAccounts: Boolean = false,
        // emailConst.type：0 收件 / 1 发送。已发送箱只能靠它区分，服务端 mailbox 里没有 sent
        type: Int = 0,
    ): JSONObject {
        val q = buildString {
            if (allAccounts) {
                append("/email/list?accountId=0&allReceive=1")
            } else {
                append("/email/list?accountId=").append(accountId).append("&allReceive=0")
            }
            append("&size=20&page=").append(page)
            append("&emailId=0&timeSort=0&type=").append(type)
            if (!mailbox.isNullOrEmpty()) append("&mailbox=").append(mailbox)
            if (category.isNotEmpty()) append("&category=").append(category)
            if (keyword.isNotEmpty()) append("&searchType=all&text=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
        }
        return obj("GET", q)
    }

    /** 星标邮件：服务端要 size/page，且返回的 list 与 email/list 字段一致。 */
    suspend fun starred(page: Int): Pair<List<MailItem>, Long> = parseList(starredRaw(page))

    suspend fun starredRaw(page: Int): JSONObject = obj("GET", "/star/list?size=20&page=$page")

    /**
     * AI 对话：/ai/command 接收 text + accountId + history，返回 {reply, actions[]}。
     * 服务端要先扫描候选邮件再走模型，实测一次 25 秒以上，所以这里单独放宽读超时到 60 秒，
     * 否则客户端 20 秒必然先超时（旧版「AI 没回话」的真正原因）。
     */
    /** 调用服务端大模型对全收件箱进行智能主题分类 */
    suspend fun aiClassify(): JSONObject =
        obj("POST", "/ai/classify", JSONObject(), readTimeoutMs = 60_000)

    suspend fun ai(text: String, accountId: Long, history: List<Pair<String, String>>, emailId: Long? = null): Pair<String, List<AiAction>> {
        val hist = org.json.JSONArray()
        history.takeLast(20).forEach { (role, t) ->
            hist.put(JSONObject().put("role", if (role == "me") "user" else "assistant").put("text", t))
        }
        val body = JSONObject()
            .put("text", text)
            .put("accountId", accountId)
            .put("history", hist)
        if (emailId != null) body.put("emailId", emailId)
        val d = obj("POST", "/ai/command", body, readTimeoutMs = 60_000)
        val acts = mutableListOf<AiAction>()
        d.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                acts.add(AiAction(o.optString("type"), o.optBoolean("ok", true), o.optString("error")))
            }
        }
        return d.optString("reply") to acts
    }

    /**
     * POST /ai/summary {emailId, lang} → {text}。
     * 服务端要先取正文再等模型，读超时统一放宽到 60 秒。
     */
    suspend fun aiSummary(emailId: Long, lang: String = "zh"): String {
        val d = obj(
            "POST", "/ai/summary",
            JSONObject().put("emailId", emailId).put("lang", lang),
            readTimeoutMs = 60_000,
        )
        val text = d.optString("text").trim()
        if (text.isEmpty()) throw ApiException(502, "AI 未返回摘要")
        return text
    }

    /** POST /ai/polish {text} → {text}；只传待润色文字，不传整封邮件。 */
    suspend fun aiPolish(text: String, lang: String = ""): String {
        val body = JSONObject().put("text", text)
        if (lang.isNotEmpty()) body.put("lang", lang)
        val d = obj("POST", "/ai/polish", body, readTimeoutMs = 60_000)
        val out = d.optString("text").trim()
        if (out.isEmpty()) throw ApiException(502, "AI 未返回润色结果")
        return out
    }

    /** POST /ai/reply {emailId, tone} → {subject, text}；tone 用来生成不同语气的候选。 */
    suspend fun aiReply(emailId: Long, tone: String = ""): Pair<String, String> {
        val body = JSONObject().put("emailId", emailId)
        if (tone.isNotEmpty()) body.put("tone", tone)
        val d = obj("POST", "/ai/reply", body, readTimeoutMs = 60_000)
        val text = d.optString("text").trim()
        if (text.isEmpty()) throw ApiException(502, "AI 未返回回复内容")
        return d.optString("subject") to text
    }

    // ---- 系统设置（需要 setting:set 权限，默认管理员）----

    /** GET /setting/query：AI 的 Key 会以 sk-xxxx****** 打码返回。 */
    suspend fun settings(): org.json.JSONObject = obj("GET", "/setting/query")

    /** PUT /setting/set：只传要改的字段；打码值不要回传（服务端会把 ****** 当未修改）。 */
    suspend fun setSetting(patch: JSONObject) {
        obj("PUT", "/setting/set", patch)
    }

    // ---- 管理页（全部邮件 / 用户 / 权限 / 注册密钥 / 分析）----

    /**
     * GET /email/list 全量（allReceive=1 表示不限账号）。type/mailbox/status 对应全部邮件页的分组 pills。
     * timeSort=0 才是服务端的时间倒序；1 是升序（最旧在前，这就是「全部邮件全是去年的」根因）。
     * mailbox=all 走的是「除已删除外的全部收件箱+全部分类」，已删除是独立 pills。
     */
    suspend fun allEmails(
        page: Int,
        keyword: String = "",
        type: Int? = 0,
        mailbox: String? = null,
        status: Int? = null,
    ): Pair<List<MailItem>, Long> = parseList(allEmailsRaw(page, keyword, type, mailbox, status))

    suspend fun allEmailsRaw(
        page: Int,
        keyword: String = "",
        type: Int? = 0,
        mailbox: String? = null,
        status: Int? = null,
    ): JSONObject {
        val q = StringBuilder("/email/list?size=20&page=$page&allReceive=1&timeSort=0")
        if (type != null) q.append("&type=$type")
        if (mailbox != null) q.append("&mailbox=$mailbox")
        if (status != null) q.append("&status=$status")
        if (keyword.isNotBlank()) {
            q.append("&text=").append(java.net.URLEncoder.encode(keyword, "UTF-8")).append("&searchType=all")
        }
        return obj("GET", q.toString())
    }

    /**
     * GET /allEmail/list —— 全站口径（所有域名下所有 account，无 user_id 过滤）。
     * 与网页版 /all-email 同一个接口、同一组参数，口径见 docs/全站邮件范围查证.md。
     * type 是字符串 all/receive/send/delete/noone；page 从 1 开始（服务端 >0 走 offset 分页）。
     * 注意服务端不默认排除 is_del，type=all 会包含已删除邮件（与网页版一致）。
     */
    suspend fun siteEmailsRaw(
        page: Int,
        keyword: String = "",
        type: String = "all",
        timeSort: Int = 0,
        category: String? = null,
    ): JSONObject {
        val q = StringBuilder("/allEmail/list?size=20&page=$page&timeSort=$timeSort&type=")
            .append(if (type.isBlank()) "all" else type)
        if (!category.isNullOrBlank()) {
            q.append("&category=").append(java.net.URLEncoder.encode(category, "UTF-8"))
        }
        if (keyword.isNotBlank()) {
            q.append("&keyword=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
        }
        return obj("GET", q.toString(), readTimeoutMs = 45_000)
    }

    suspend fun siteEmails(
        page: Int,
        keyword: String = "",
        type: String = "all",
        timeSort: Int = 0,
        category: String? = null,
    ): Pair<List<MailItem>, Long> =
        parseList(siteEmailsRaw(page, keyword, type, timeSort, category))

    /**
     * 全站只读详情：/allEmail/list 返回的就是整行（带 content/text），网页端 /all-email 也是直接
     * 用列表里的正文渲染、详情 404 被吞掉。所以这里不新增服务端接口，只按 emailId 精确取这一封。
     *
     * 列表接口本身按 emailId 游标翻页：page<=0 且 timeSort=1 表示「emailId > 参数」升序取 size 条，
     * 传 id-1 + size=1 就只命中目标那一封；返回 id 对不上说明已被物理删除。
     * 只为被点开的这一封取正文，列表映射里不保留整页内容，避免内存翻倍。
     */
    suspend fun siteMail(emailId: Long): SiteMail {
        val cursor = (emailId - 1).coerceAtLeast(0)
        val d = obj("GET", "/allEmail/list?size=1&page=0&timeSort=1&emailId=$cursor")
        val row = d.optJSONArray("list")?.optJSONObject(0)
        if (row == null || row.optLong("emailId") != emailId) {
            throw ApiException(404, "邮件已不存在或无权查看")
        }
        return SiteMail(
            detail = parseDetail(row),
            userId = row.optLong("userId"),
            status = row.optInt("status"),
            toEmail = row.optString("toEmail"),
            toName = row.optString("toName"),
            plain = row.optString("text"),
        )
    }

    /**
     * DELETE /allEmail/delete?emailIds=：全站物理删除（不进回收站）。
     * 只用于「全部邮件」里不属于当前账号的那封；权限由服务端 all-email:delete 判定，
     * 403 会原样抛给调用方提示，不做假成功。
     */
    suspend fun siteDelete(emailId: Long) {
        obj("DELETE", "/allEmail/delete?emailIds=$emailId")
    }

    /** GET /analysis/echarts：总览计数、15 天用户/邮件增长、发件人占比、今日发送。 */
    suspend fun analysisEcharts(): JSONObject = obj("GET", "/analysis/echarts")

    /**
     * GET /user/list：服务端分页参数是 num（第几页）+ size，排序用 timeSort（0=userId 倒序），
     * 之前传 page/sort 全被忽略 → num=NaN，列表为空或报错。status=-1 表示不过滤。
     */
    suspend fun users(page: Int, status: Int = -1, keyword: String = ""): Pair<List<JSONObject>, Long> {
        val q = StringBuilder("/user/list?size=20&num=$page&timeSort=0")
        if (status >= 0) q.append("&status=$status")
        if (keyword.isNotBlank()) {
            q.append("&email=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
        }
        val data = obj("GET", q.toString())
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

    /**
     * POST /translation/translate：lang 只收 zh/en/ja/ko/fr/de/es/ru/pt/it。
     * 服务端返回字段是 translatedSubject / translatedContent / sourceLang / alreadyInTargetLang，
     * 之前误读 content/text → 成功也被当成空串，界面才误报「正文已经是中文」。
     */
    suspend fun translate(emailId: Long, lang: String): Translation {
        val data = obj("POST", "/translation/translate", JSONObject().put("emailId", emailId).put("targetLang", lang))
        return Translation(
            subject = data.optString("translatedSubject", ""),
            content = data.optString("translatedContent", ""),
            sourceLang = data.optString("sourceLang", ""),
            alreadyInTargetLang = data.optBoolean("alreadyInTargetLang", false),
        )
    }

    suspend fun detail(emailId: Long): MailDetail = parseDetail(detailRaw(emailId))

    suspend fun detailRaw(emailId: Long): JSONObject = obj("GET", "/email/detail?emailId=$emailId")

    /**
     * 服务端 /email/detail 返回的字段 → MailDetail；缓存落盘也复用这一份解析。
     *
     * 正文按 html(content) → 纯文本(text) 的顺序回退：纯文本邮件的内容只写进 text，
     * 旧实现只读 content，于是正文区一片空白（用户反馈的「部分邮件进去空白」）。
     * 存进缓存的是转换后的 HTML，所以离线重开也有正文。
     */
    fun parseDetail(d: JSONObject): MailDetail {
        val html = d.optString("content")
        val text = d.optString("text")
        val body = when {
            htmlHasVisible(html) -> html
            text.isNotBlank() -> textToHtml(text)
            else -> ""
        }
        return MailDetail(
            emailId = d.optLong("emailId"),
            subject = d.optString("subject"),
            from = d.optString("sendEmail"),
            name = d.optString("name").ifEmpty { d.optString("sendEmail") },
            time = d.optString("createTime"),
            content = body,
            unread = d.optInt("unread", 1) == 0,
            starred = d.optInt("isStar", 0) == 1 || d.optLong("starId", 0) > 0,
            attachments = d.optJSONArray("attList")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    MailAtt(o.optString("filename"), o.optString("url"))
                }
            }.orEmpty(),
            category = d.optString("category"),
            recipient = parseAddresses(d.optString("recipient")),
            cc = parseAddresses(d.optString("cc")),
            code = d.optString("code").ifEmpty {
                VerificationCode.find("${d.optString("subject")}\n$body")?.code.orEmpty()
            },
            otpLink = d.optString("otpLink"),
            authSpf = d.optString("authSpf"),
            authDmarc = d.optString("authDmarc"),
            listUnsubscribe = d.optString("listUnsubscribe"),
            listUnsubscribePost = d.optString("listUnsubscribePost"),
            status = d.optInt("status"),
            type = d.optInt("type"),
        )
    }

    /**
     * recipient / cc 是 JSON 数组，元素可能是 {"address":"a@b","name":"x"} 或纯字符串。
     * 返回全部可用地址（大小写保持原样，去重交给调用方）。
     */
    fun parseAddresses(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val addr = when (val v = arr.opt(i)) {
                    is JSONObject -> v.optString("address").ifEmpty { v.optString("email") }
                    else -> v?.toString().orEmpty()
                }.trim()
                addr.takeIf { it.contains("@") }
            }
        }.getOrDefault(emptyList())
    }

    /** HTML 是否有可见内容：只有标签/空白也算空壳；但图片、表格这类非文字结构仍算有正文。 */
    private fun htmlHasVisible(html: String): Boolean {
        if (html.isBlank()) return false
        val plain = html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), "")
        if (plain.isNotEmpty()) return true
        return Regex("<(img|video|audio|table|hr|iframe|embed|object)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(html)
    }

    /** 纯文本正文 → 简单 HTML，保留换行。 */
    private fun textToHtml(text: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return text.trim().replace("\r\n", "\n").split("\n").joinToString("") { line ->
            if (line.isBlank()) "<br>" else "<p>${esc(line)}</p>"
        }
    }

    fun parseList(d: JSONObject): Pair<List<MailItem>, Long> =
        d.optJSONArray("list").toMailItems() to d.optLong("total")

    suspend fun markRead(emailId: Long) {
        obj("PUT", "/email/read", JSONObject().put("emailIds", JSONArray().put(emailId)))
    }

    suspend fun markUnread(emailId: Long) {
        obj("PUT", "/email/unread", JSONObject().put("emailIds", JSONArray().put(emailId)))
    }

    /** PUT /email/category：移动到主要/社交/推广/动态。 */
    suspend fun moveCategory(emailId: Long, category: String) {
        obj(
            "PUT", "/email/category",
            JSONObject().put("emailIds", JSONArray().put(emailId)).put("category", category),
        )
    }

    /**
     * 上下封切换：timeSort=1 取更新的、=0 取更早的，size=1 拿到的就是相邻那封。
     * 返回 0 表示到头了。
     */
    suspend fun neighbor(emailId: Long, newer: Boolean): Long {
        val q = "/email/list?accountId=0&allReceive=1&size=1&page=1&emailId=$emailId" +
            "&timeSort=${if (newer) 1 else 0}&type=0"
        val arr = obj("GET", q).optJSONArray("list") ?: return 0L
        return arr.optJSONObject(0)?.optLong("emailId") ?: 0L
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

    /** POST /email/restore：从已删除恢复到收件箱（服务端只接受单封 emailId）。 */
    suspend fun restore(emailId: Long) {
        obj("POST", "/email/restore", JSONObject().put("emailId", emailId))
    }

    /** POST /email/spamCancel：取消垃圾标记（服务端只接受单封 emailId）。 */
    suspend fun spamCancel(emailId: Long) {
        obj("POST", "/email/spamCancel", JSONObject().put("emailId", emailId))
    }

    /** DELETE /email/clearDeleted：彻底清空当前用户的已删除（服务端返回清除条数）。 */
    suspend fun clearDeleted(): Int = obj("DELETE", "/email/clearDeleted").optInt("total")

    /** POST /email/learn：学习用户对分类的纠正，并记录发件人和关键词规则 */
    suspend fun emailLearn(emailId: Long, category: String, keyword: String = ""): String {
        val body = JSONObject()
            .put("emailId", emailId)
            .put("category", category)
        if (keyword.isNotEmpty()) body.put("keyword", keyword)
        val d = obj("POST", "/email/learn", body)
        return d.optString("category")
    }

    /** POST /email/retry：对发送失败或超时的已发送邮件发起重试 */
    suspend fun emailRetry(emailId: Long): JSONObject {
        val body = JSONObject().put("emailId", emailId)
        return obj("POST", "/email/retry", body)
    }

    suspend fun send(
        accountId: Long,
        to: List<String>,
        subject: String,
        text: String,
        html: String,
        attachments: List<JSONObject> = emptyList(),
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
    ) {
        val body = JSONObject()
            .put("accountId", accountId)
            .put("receiveEmail", JSONArray(to))
            .put("subject", subject)
            .put("text", text)
            .put("content", html)
            .put("sendType", 0)
            .put("attachments", JSONArray(attachments))
            .put("cc", JSONArray(cc))
            .put("bcc", JSONArray(bcc))
        obj("POST", "/email/send", body)
    }

    /**
     * 分类计数：accountId + allReceive 决定范围。
     * 服务端只有当「给了 accountId 且 allReceive=0」才按单个帐户统计，否则累加该 user 的全部帐户。
     * 抽屉切帐户必须把 accountId 传下去，否则徽标永远是用户级总数、看起来不刷新。
     */
    suspend fun categoryCounts(accountId: Long = 0L, allReceive: Boolean = true): Map<String, Int> {
        val d = obj("GET", "/email/category-counts?accountId=$accountId&allReceive=${if (allReceive) 1 else 0}")
        return listOf("primary", "social", "promotion", "updates", "ads", "spam")
            .associateWith { d.optInt(it) }
    }

    suspend fun menuCounts(accountId: Long = 0L, allReceive: Boolean = true): Map<String, Int> = coroutineScope {
        val scope = "accountId=$accountId&allReceive=${if (allReceive) 1 else 0}"
        // 三个计数互不依赖，并发取：串行时首屏要白等三次 RTT（实测每次 0.8~1s）
        val menu = async { obj("GET", "/email/menu-counts?$scope") }
        // 未读数没有计数接口，用列表 total；范围必须与 menu-counts 保持一致
        val unread = async {
            runCatching {
                obj("GET", "/email/list?$scope&size=1&page=1&emailId=0&timeSort=0&type=0&mailbox=unread")
                    .optLong("total").toInt()
            }.getOrDefault(0)
        }
        // 星标接口只按 userId 统计（/star/list 没有账户参数），切帐户时保持用户级口径
        val starred = async {
            runCatching { obj("GET", "/star/list?size=1&page=1").optLong("total").toInt() }
                .getOrDefault(0)
        }
        val d = menu.await()
        listOf("inbox", "sent", "deleted", "spam", "draft").associateWith { d.optInt(it) } +
            ("unread" to unread.await()) + ("starred" to starred.await())
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
            val serverCode = o.optString("code")
            val codeVal = if (serverCode.isNotBlank()) serverCode else {
                VerificationCode.find("${o.optString("subject")}\n${o.optString("text")}")?.code.orEmpty()
            }
            MailItem(
                emailId = o.optLong("emailId"),
                from = o.optString("sendEmail"),
                name = o.optString("name").ifEmpty { o.optString("sendEmail") },
                subject = o.optString("subject"),
                text = o.optString("text"),
                time = o.optString("createTime"),
                unread = o.optInt("unread", 1) == 0,
                starred = o.optInt("isStar", 0) == 1 || o.optLong("starId", 0) > 0,
                category = o.optString("category"),
                type = o.optInt("type"),
                status = o.optInt("status"),
                accountId = o.optLong("accountId"),
                recipient = firstAddress(o.optString("recipient")),
                userId = o.optLong("userId"),
                toEmail = o.optString("toEmail"),
                toName = o.optString("toName"),
                userEmail = o.optString("userEmail"),
                code = codeVal,
                listUnsubscribe = o.optString("listUnsubscribe"),
            )
        }
    }

    /** 服务端 recipient 是 JSON 数组，元素可能是 {"address":"a@b"} 或纯字符串；取第一个非空地址。 */
    private fun firstAddress(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val addr = when (val v = arr.opt(i)) {
                    is JSONObject -> v.optString("address").ifEmpty { v.optString("email") }
                    else -> v?.toString().orEmpty()
                }
                if (addr.isNotBlank()) return@runCatching addr.trim()
            }
            ""
        }.getOrDefault("")
    }
}
