package com.otscup.otsmail.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.otscup.otsmail.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * FCM token 的获取与「设备登记」上报。
 *
 * 约定见 [PushScaffold]：POST /push/device、DELETE /push/device。
 * 登录态过期、断网、服务端 5xx 都可能让登记/注销失败；失败只写 logcat + 退避，
 * 不能影响任何界面（服务端接口已上线，但错误处理不能假设它总是成功）。
 */
object PushManager {

    private const val TAG = "OtsPush"
    private const val PREFS = "otsmail_push"
    private const val KEY_TOKEN = "fcmToken"
    private const val KEY_DEVICE = "deviceId"
    private const val KEY_OK_TOKEN = "okToken"
    private const val KEY_OK_USER = "okUser"
    private const val KEY_BACKOFF = "backoffUntil"

    /** 登记失败（含 404）后的退避：6 小时。 */
    private const val BACKOFF_MS = 6 * 60 * 60 * 1000L

    /** 拿不到 FCM token（无 Google 服务/断网）后的退避：10 分钟，比登记失败短。 */
    private const val TOKEN_BACKOFF_MS = 10 * 60 * 1000L

    private const val TOKEN_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 登记标记只存指纹：FCM token 与 JWT 都不落 prefs 做去重键。 */
    private fun fingerprint(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 设备标识：优先 ANDROID_ID，取不到才生成随机 UUID 并持久化。 */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE, "").orEmpty().takeIf { it.isNotEmpty() }?.let { return it }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val id = androidId.ifEmpty { UUID.randomUUID().toString() }
        p.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    fun cachedToken(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty()

    /**
     * 设备能力判定用：返回可用 FCM token（缓存优先），拿不到返回 null。
     * 与 [syncToken] 不同，这里不发登记请求，只判断「这台设备能不能走 FCM」；
     * 无 GMS / 国产 ROM 上 FirebaseMessaging.getInstance().token 会直接失败，返回 null。
     */
    suspend fun ensureToken(context: Context): String? {
        if (!PushScaffold.ENABLED) return null
        val app = context.applicationContext
        cachedToken(app).takeIf { it.isNotEmpty() }?.let { return it }
        return when (val r = fetchToken()) {
            is TokenResult.Ok -> r.token.also {
                prefs(app).edit().putString(KEY_TOKEN, it).apply()
                Log.i(TAG, "FCM token 可用（尾号 ${it.takeLast(6)}）")
            }

            is TokenResult.Fail -> {
                Log.i(TAG, "FCM token 不可用，外部邮箱改走 IMAP IDLE：${r.reason}")
                null
            }
        }
    }

    /** 登录后 / 进入收件箱时调用：token 拿到了就登记一次（同 token 同账号不重复 POST）。 */
    fun syncToken(context: Context) {
        if (!PushScaffold.ENABLED) return
        val app = context.applicationContext
        scope.launch {
            runCatching { registerIfPossible(app, force = false) }
                .onFailure { Log.w(TAG, "syncToken 异常: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** FCM 刷新 token 时回调：必须重新登记，忽略退避。 */
    fun onNewToken(context: Context, token: String) {
        val app = context.applicationContext
        val p = prefs(app)
        p.edit().putString(KEY_TOKEN, token).remove(KEY_OK_TOKEN).remove(KEY_OK_USER).apply()
        Log.i(TAG, "onNewToken 收到新 token（尾号 ${token.takeLast(6)}）")
        if (!PushScaffold.ENABLED) return
        scope.launch {
            runCatching { registerIfPossible(app, force = true) }
                .onFailure { Log.w(TAG, "onNewToken 登记异常: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** 退出登录时注销设备；失败只记日志并清掉登记标记，下次登录会重新登记。 */
    fun unregisterAsync(context: Context) {
        if (!PushScaffold.ENABLED) return
        val app = context.applicationContext
        val auth = Api.token
        val token = cachedToken(app)
        prefs(app).edit().remove(KEY_OK_TOKEN).remove(KEY_OK_USER).apply()
        if (auth.isEmpty() || token.isEmpty()) {
            Log.i(TAG, "退出登录：本地没有 token/登录态，跳过注销")
            return
        }
        scope.launch {
            runCatching { Api.pushUnregister(auth, token) }
                .onSuccess { Log.i(TAG, "设备已注销") }
                .onFailure {
                    val e = it as? ApiException
                    Log.w(TAG, "设备注销失败: " + (e?.let { "HTTP ${e.httpCode} ${e.message}" }
                        ?: "${it.javaClass.simpleName}: ${it.message}"))
                }
        }
    }

    private suspend fun registerIfPossible(context: Context, force: Boolean) {
        val auth = Api.token
        if (auth.isEmpty()) {
            Log.i(TAG, "未登录，跳过设备登记")
            return
        }
        val p = prefs(context)
        val until = p.getLong(KEY_BACKOFF, 0L)
        if (!force && until > System.currentTimeMillis()) {
            Log.i(TAG, "在退避期内，跳过登记（剩余 ${(until - System.currentTimeMillis()) / 1000}s）")
            return
        }

        val cached = p.getString(KEY_TOKEN, "").orEmpty()
        val token = if (cached.isNotEmpty()) {
            cached
        } else {
            when (val r = fetchToken()) {
                is TokenResult.Ok -> r.token.also {
                    p.edit().putString(KEY_TOKEN, it).apply()
                    Log.i(TAG, "FCM token 获取成功（尾号 ${it.takeLast(6)}）")
                }

                is TokenResult.Fail -> {
                    p.edit().putLong(KEY_BACKOFF, System.currentTimeMillis() + TOKEN_BACKOFF_MS).apply()
                    Log.w(TAG, "FCM token 获取失败：${r.reason}")
                    return
                }
            }
        }

        // 同账号 + 同 token 已经登记过就不再打网络。
        // 这里只存 token/登录态的 SHA-256 指纹，不在 prefs 里留 FCM token 和 JWT 原文。
        if (p.getString(KEY_OK_TOKEN, "") == fingerprint(token) &&
            p.getString(KEY_OK_USER, "") == fingerprint(auth)
        ) {
            Log.i(TAG, "该 token 已登记，跳过")
            return
        }

        try {
            Api.pushRegister(token, deviceId(context), BuildConfig.VERSION_NAME)
            p.edit()
                .putString(KEY_OK_TOKEN, fingerprint(token))
                .putString(KEY_OK_USER, fingerprint(auth))
                .putLong(KEY_BACKOFF, 0L)
                .apply()
            Log.i(TAG, "设备登记成功（platform=android, deviceId=${deviceId(context)}, appVersion=${BuildConfig.VERSION_NAME}）")
        } catch (e: Throwable) {
            p.edit().putLong(KEY_BACKOFF, System.currentTimeMillis() + BACKOFF_MS).apply()
            val detail = (e as? ApiException)?.let { "HTTP ${it.httpCode} ${it.message}" }
                ?: "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "设备登记失败，${BACKOFF_MS / 60_000} 分钟内不重试：$detail")
        }
    }

    private sealed interface TokenResult {
        data class Ok(val token: String) : TokenResult
        data class Fail(val reason: String) : TokenResult
    }

    /** 包一层 Task：15s 拿不到就按失败处理，避免在后台线程里无限等。 */
    private suspend fun fetchToken(): TokenResult =
        withTimeoutOrNull(TOKEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (!cont.isActive) return@addOnCompleteListener
                        if (task.isSuccessful) {
                            val t = task.result.orEmpty()
                            if (t.isEmpty()) {
                                cont.resume(TokenResult.Fail("token 为空"))
                            } else {
                                cont.resume(TokenResult.Ok(t))
                            }
                        } else {
                            val e = task.exception
                            cont.resume(TokenResult.Fail("${e?.javaClass?.simpleName ?: "Task"}: ${e?.message}"))
                        }
                    }
                } catch (e: Throwable) {
                    // 没有 Google Play 服务 / google-services 配置缺失时会直接抛
                    if (cont.isActive) {
                        cont.resume(TokenResult.Fail("${e.javaClass.simpleName}: ${e.message}"))
                    }
                }
            }
        } ?: TokenResult.Fail("等待 FirebaseMessaging.token 超时（${TOKEN_TIMEOUT_MS / 1000}s）")
}
