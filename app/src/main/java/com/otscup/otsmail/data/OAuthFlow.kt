package com.otscup.otsmail.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 一次第三方授权（OIDC / GitHub / Google / linux.do）的上下文。
 * verifier 只在内存与本地 SharedPreferences 里，persist 是为了 Custom Tabs
 * 期间 App 被系统回收后还能用同一个 PKCE verifier 换 token。
 */
data class OAuthRequest(
    val url: String,
    val callback: String,
    val provider: String,
    val providerId: String,
    val state: String,
    val nonce: String,
    val verifier: String,
)

/**
 * 自定义 scheme `otsmail://oauth/callback?code=..&state=..` 的中转。
 * MainActivity 在 onCreate / onNewIntent 里投递，登录页消费。
 */
object OAuthCallbackBus {
    var pendingUrl by mutableStateOf<String?>(null)
        private set

    fun post(url: String) {
        pendingUrl = url
    }

    fun consume(): String? {
        val u = pendingUrl
        pendingUrl = null
        return u
    }
}

/** 授权上下文落盘（进程被杀后冷启动还能接着换 token）。 */
class OAuthPendingStore(context: Context) {

    private val prefs = context.getSharedPreferences("otsmail_oauth", Context.MODE_PRIVATE)

    fun save(req: OAuthRequest) {
        val obj = JSONObject()
            .put("url", req.url)
            .put("callback", req.callback)
            .put("provider", req.provider)
            .put("providerId", req.providerId)
            .put("state", req.state)
            .put("nonce", req.nonce)
            .put("verifier", req.verifier)
            .put("at", System.currentTimeMillis())
        prefs.edit().putString("pending", obj.toString()).apply()
    }

    /** 超过 10 分钟的授权一律作废，避免拿过期 code/verifier 去换 token。 */
    fun load(): OAuthRequest? {
        val raw = prefs.getString("pending", null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - obj.optLong("at", 0L) > 10 * 60 * 1000L) {
            clear()
            return null
        }
        val state = obj.optString("state")
        if (state.isEmpty()) return null
        return OAuthRequest(
            url = obj.optString("url"),
            callback = obj.optString("callback"),
            provider = obj.optString("provider"),
            providerId = obj.optString("providerId"),
            state = state,
            nonce = obj.optString("nonce"),
            verifier = obj.optString("verifier"),
        )
    }

    fun clear() = prefs.edit().remove("pending").apply()
}
