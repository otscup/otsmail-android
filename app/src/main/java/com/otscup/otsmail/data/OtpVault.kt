package com.otscup.otsmail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机验证器密钥：Android Keystore AES-256/GCM，独立别名，与 token 加密互不影响。
 *
 * 按登录邮箱分条存放，退出登录**不删除**（用户可能只是换号查看，回来还要出码）；
 * 只有「移除验证器」或删除账号时才清。
 * 威胁模型与 token 一致：只防文件被拷走；已 root 的设备仍可调用 Keystore。
 */
object OtpVault {

    private const val PREFS = "otsmail_otp"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val LOG_TAG = "OtpVault"
    private const val SEPARATOR = ":"

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun alias(context: Context) = "${context.packageName}.otp"

    private fun key(context: Context): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getEntry(alias(context), null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
                cachedKey = it
                return it
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias(context),
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // 显示验证码前不做生物识别：出码要在锁屏通知等场景可用
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey().also { cachedKey = it }
        }
    }

    private fun slot(email: String) = email.trim().lowercase()

    private fun encrypt(context: Context, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(context))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(context: Context, packed: String): String? {
        val parts = packed.split(SEPARATOR, limit = 2)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(context), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.onFailure {
            Log.w(LOG_TAG, "解密本机验证器密钥失败：${it.javaClass.simpleName}")
        }.getOrNull()
    }

    fun save(context: Context, email: String, secret: String) {
        if (email.isBlank() || secret.isBlank()) return
        runCatching {
            prefs(context).edit().putString(slot(email), encrypt(context, secret)).apply()
        }.onFailure { Log.w(LOG_TAG, "保存本机验证器密钥失败：${it.message}") }
    }

    fun load(context: Context, email: String): String? {
        if (email.isBlank()) return null
        val packed = prefs(context).getString(slot(email), null) ?: return null
        return decrypt(context, packed)
    }

    fun remove(context: Context, email: String) {
        if (email.isBlank()) return
        prefs(context).edit().remove(slot(email)).apply()
    }

    fun has(context: Context, email: String): Boolean = load(context, email) != null
}
