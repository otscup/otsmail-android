package com.otscup.otsmail.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.otscup.otsmail.push.PushDedupe
import com.otscup.otsmail.push.PushSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 只用 SharedPreferences，不引 DataStore：三个字段，没有必要为它加依赖。 */
class Session(context: Context) {

    private val prefs = context.getSharedPreferences("otsmail", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var base: String
        get() = prefs.getString("base", "").orEmpty()
        set(value) = prefs.edit().putString("base", value).apply()

    /** 登录 token 走 Keystore AES-GCM 落盘；旧版明文在首次读取时自动迁移。 */
    var token: String
        get() = TokenCrypto.read(appContext, prefs)
        set(value) = TokenCrypto.write(appContext, prefs, value)

    var accountId: Long
        get() = prefs.getLong("accountId", 0L)
        set(value) = prefs.edit().putLong("accountId", value).apply()

    var name: String
        get() = prefs.getString("name", "").orEmpty()
        set(value) = prefs.edit().putString("name", value).apply()

    var email: String
        get() = prefs.getString("email", "").orEmpty()
        set(value) = prefs.edit().putString("email", value).apply()

    /** 登录页邮箱域名（可和服务器 host 不同）；空则回退到 base 的 host。 */
    var loginDomain: String
        get() = prefs.getString("loginDomain", "").orEmpty()
        set(value) = prefs.edit().putString("loginDomain", value).apply()

    var signature: String
        get() = prefs.getString("signature", "").orEmpty()
        set(value) = prefs.edit().putString("signature", value).apply()

    var sendOnEnter: String
        get() = prefs.getString("sendOnEnter", "enter").orEmpty().ifEmpty { "enter" }
        set(value) = prefs.edit().putString("sendOnEnter", value).apply()

    /** 列表显示发件人头像（设置页开关）；关掉时改显示账户彩点。 */
    var showAvatars: Boolean
        get() = prefs.getBoolean("showAvatars", true)
        set(value) = prefs.edit().putBoolean("showAvatars", value).apply()

    /** 服务端 /my/loginUserInfo 返回的 avatar 外链；空表示没设置。 */
    var avatar: String
        get() = prefs.getString("avatar", "").orEmpty()
        set(value) = prefs.edit().putString("avatar", value).apply()

    /**
     * 本地头像（data:image/...;base64,）。服务端上传失败（比如本地 R2 没配）时兜底，
     * 设置页会明确标注「本地头像」，避免让人以为已经同步到服务端。
     */
    var avatarLocal: String
        get() = prefs.getString("avatarLocal", "").orEmpty()
        set(value) = prefs.edit().putString("avatarLocal", value).apply()

    /**
     * Android 13+ 的通知权限是否已经问过一次。只在首次登录后请求一次，
     * 拒绝后不再打扰（想开只能去系统设置）。
     */
    var notifAsked: Boolean
        get() = prefs.getBoolean("notifAsked", false)
        set(value) = prefs.edit().putBoolean("notifAsked", value).apply()

    fun apply() {
        Api.base = base
        Api.token = token
    }

    fun clear() {
        // 先注销推送设备：内部会捕获当前的 Api.token（下面就把 token 清掉了）
        PushManager.unregisterAsync(appContext)
        // 登录态 + 个人资料 + 登录域名 + 草稿 + OAuth 半成品全部清掉：
        // 换号/换服务器时不能把上一个账号的签名、头像、草稿、state/verifier 带过去。
        TokenCrypto.clear(prefs)
        prefs.edit()
            .remove("token")
            .remove("tokenEnc")
            .remove("accountId")
            .remove("name")
            .remove("email")
            .remove("loginDomain")
            .remove("signature")
            .remove("avatar")
            .remove("avatarLocal")
            // 新账号要重新走一次 Android 13+ 的通知权限询问
            .remove("notifAsked")
            .apply()
        Api.token = ""
        Drafts.clearAll(appContext)
        OAuthPendingStore(appContext).clear()
        // 通知偏好里跟账号绑定的部分（静音名单、去重表）不能带到下一个账号：
        // 否则新账号的通知会被上一账号静音的发送者拦掉，且用户找不到原因。
        PushSettings.clear(appContext)
        PushDedupe.clear(appContext)
        // 「这封不显示图片」是按账号记的，同样不能带到下一个账号
        ImagePrefs.clear(appContext)
        // WebView 里可能残留上一个账号登录过的网页 cookie（正文里的外链、OAuth 页面）
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
        // 退出登录/删除账户：列表与详情缓存一起清，避免换号后串数据
        MailCache.wipe()
    }
}

/**
 * 登录 token 的落盘加密：与 Drafts 同一套 Android Keystore AES-256/GCM，独立别名，互不影响。
 * 威胁模型只覆盖「文件被拷走」：同一台已 root 的设备仍可调用 Keystore，这一点和草稿一致。
 *
 * 兼容旧版明文 prefs["token"]：第一次读取时加密成 prefs["tokenEnc"] 并删除明文，
 * 所以升级后已登录用户不会掉登录态。解密失败（换机恢复、Keystore 条目丢失）按未登录处理。
 */
private object TokenCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val LOG_TAG = "SessionToken"
    private const val PLAIN_KEY = "token"
    private const val ENC_KEY = "tokenEnc"
    private const val SEPARATOR = ":"

    /** Keystore 密钥引用缓存；进程内复用，避免每次读 token 都做一次 Keystore 查询。 */
    @Volatile
    private var cachedKey: SecretKey? = null

    /** (密文, 明文) 一对一缓存：token 每次读都要解密会拖慢请求，这里按密文命中。 */
    @Volatile
    private var cachedEnc: String? = null
    @Volatile
    private var cachedPlain: String = ""

    private fun alias(ctx: Context): String = "${ctx.packageName}.session"

    @Synchronized
    private fun key(ctx: Context): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(alias(ctx), null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
            cachedKey = it
            return it
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias(ctx),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 每次读 token 都要解锁会打断后台同步，这里不要求用户认证
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey().also { cachedKey = it }
    }

    private fun encrypt(ctx: Context, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(ctx))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(ctx: Context, packed: String): String? {
        val parts = packed.split(SEPARATOR, limit = 2)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            if (iv.size != IV_BYTES || body.size < 16) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(ctx), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun read(ctx: Context, prefs: SharedPreferences): String {
        val enc = prefs.getString(ENC_KEY, "").orEmpty()
        if (enc.isEmpty()) {
            val legacy = prefs.getString(PLAIN_KEY, "").orEmpty()
            if (legacy.isEmpty()) return ""
            // 升级迁移：先加密落盘、再删明文；加密失败时明文保留，避免用户被动掉登录
            write(ctx, prefs, legacy)
            return legacy
        }
        if (enc == cachedEnc) return cachedPlain
        val plain = decrypt(ctx, enc).orEmpty()
        if (plain.isEmpty()) Log.w(LOG_TAG, "token 解密失败，按未登录处理")
        cachedEnc = enc
        cachedPlain = plain
        return plain
    }

    @Synchronized
    fun write(ctx: Context, prefs: SharedPreferences, value: String) {
        if (value.isEmpty()) {
            clear(prefs)
            return
        }
        val enc = runCatching { encrypt(ctx, value) }.getOrNull()
        if (enc == null) {
            // 加密失败不落明文：宁可让用户重新登录，也不写回旧版明文格式
            Log.w(LOG_TAG, "token 加密失败，不落盘")
            return
        }
        prefs.edit().putString(ENC_KEY, enc).remove(PLAIN_KEY).apply()
        cachedEnc = enc
        cachedPlain = value
    }

    @Synchronized
    fun clear(prefs: SharedPreferences) {
        cachedEnc = null
        cachedPlain = ""
        prefs.edit().remove(ENC_KEY).remove(PLAIN_KEY).apply()
    }
}
