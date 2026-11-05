package com.otscup.otsmail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 自托管形态下没有内置服务器地址：地址只能由用户填写并保存在本地。
 * 这里负责「输入规范化 + 连通性/服务身份校验」，供首次引导页与登录页/设置页复用。
 */
object ServerAddress {

    /** 输入框占位示例，仅作文档用途。 */
    const val HINT = "https://mail.example.com"

    sealed interface Parsed {
        data class Ok(val apiBase: String) : Parsed
        data class Fail(val message: String) : Parsed
    }

    /**
     * 规范化用户输入，返回可直接写入 [Session.base] 的 API 根地址（形如 https://host/api）。
     * 规则：补协议（默认 https）、去尾部斜杠、拒绝路径/查询串/用户信息；允许显式写 /api 后缀。
     * 只允许 https：明文 http 会让账号密码和 JWT 在链路上裸奔，v0.2.3 起不再接受。
     */
    fun parse(raw: String): Parsed {
        val s = raw.trim()
        if (s.isEmpty()) return Parsed.Fail("请输入服务器地址")
        if (s.any { it.isWhitespace() }) return Parsed.Fail("服务器地址不能包含空格")

        val withScheme = if (s.contains("://")) s else "https://$s"
        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: return Parsed.Fail("服务器地址格式不正确")
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "http") return Parsed.Fail("请使用 https 地址")
        if (scheme != "https") {
            return Parsed.Fail("只支持 https 地址")
        }
        val host = uri.host.orEmpty()
        if (host.isEmpty()) return Parsed.Fail("服务器地址格式不正确，请填写域名或 IP")
        if (uri.userInfo != null) return Parsed.Fail("服务器地址不能包含用户名或密码")
        if (uri.query != null || uri.fragment != null) return Parsed.Fail("服务器地址不能带参数")
        // 兼容旧版本里带 /api 的写法；其他路径一律拒绝
        val path = uri.path.orEmpty()
        if (path.isNotEmpty() && path != "/" && path != "/api" && path != "/api/") {
            return Parsed.Fail("服务器地址只填域名，不要带路径")
        }

        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val root = "$scheme://${host.lowercase()}$port"
        return Parsed.Ok("$root/api")
    }

    /**
     * 请求 <apiBase>/setting/websiteConfig 确认地址可用。
     * 返回 null 表示通过，否则返回中文失败原因（区分域名/网络/证书/不是 OTSMail）。
     */
    suspend fun verify(apiBase: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$apiBase/setting/websiteConfig").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return@withContext "该地址不是 OTSMail 服务（HTTP $code）"
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext "该地址不是 OTSMail 服务"
            if (json.optInt("code", 200) != 200) return@withContext "该地址不是 OTSMail 服务"
            if (json.optJSONObject("data") == null) return@withContext "该地址不是 OTSMail 服务"
            null
        } catch (e: Throwable) {
            reason(e)
        }
    }

    private fun reason(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException || m.contains("Unable to resolve host", true) ->
                "找不到该服务器，请检查域名是否正确"
            e is javax.net.ssl.SSLException || m.contains("SSL", true) || m.contains("certificate", true) ->
                "无法建立安全连接，请检查服务器证书"
            e is java.net.ConnectException || m.contains("Failed to connect", true) ||
                m.contains("Connection refused", true) -> "无法连接到服务器，请检查网络或地址"
            e is java.net.SocketTimeoutException || m.contains("timeout", true) || m.contains("timed out", true) ->
                "连接超时，请检查网络或地址"
            else -> "无法连接到服务器，请检查网络或地址"
        }
    }
}
