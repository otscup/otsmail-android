package com.otscup.otsmail.data

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * OTSMail 端到端加密（E2EE）管理器
 * 采用 RSA-2048 + AES-256-GCM 混合加密体制，支持本地公私钥安全存储、密文收发与跨设备密钥同步。
 */
object E2EEManager {

    private const val PREFS_NAME = "otsmail_e2ee"
    private const val KEY_PRIVATE = "e2ee_private_key"
    private const val KEY_PUBLIC = "e2ee_public_key"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 检查当前设备是否已初始化 E2EE 密钥对 */
    fun hasKeys(context: Context): Boolean {
        val p = prefs(context)
        return p.contains(KEY_PRIVATE) && p.contains(KEY_PUBLIC)
    }

    /** 获取本地公钥 PEM 字符串；如果未生成则自动生成 */
    fun getOrCreatePublicKey(context: Context): String {
        if (!hasKeys(context)) {
            generateKeyPair(context)
        }
        return prefs(context).getString(KEY_PUBLIC, "").orEmpty()
    }

    /** 生成新的 RSA-2048 密钥对并持久化到本地 */
    fun generateKeyPair(context: Context): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val pair = kpg.generateKeyPair()

        val pubB64 = Base64.getEncoder().encodeToString(pair.public.encoded)
        val privB64 = Base64.getEncoder().encodeToString(pair.private.encoded)

        prefs(context).edit()
            .putString(KEY_PUBLIC, pubB64)
            .putString(KEY_PRIVATE, privB64)
            .apply()

        return pair
    }

    /** 计算公钥指纹（SHA-256 冒号分隔的十六进制大写） */
    fun getFingerprint(context: Context): String {
        val pubB64 = getOrCreatePublicKey(context)
        if (pubB64.isEmpty()) return ""
        val bytes = Base64.getDecoder().decode(pubB64)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(16).joinToString(":") { String.format("%02X", it) }
    }

    private fun getPrivateKey(context: Context): PrivateKey {
        val privB64 = prefs(context).getString(KEY_PRIVATE, "") ?: ""
        if (privB64.isEmpty()) throw IllegalStateException("E2EE 私钥未就绪")
        val bytes = Base64.getDecoder().decode(privB64)
        val spec = PKCS8EncodedKeySpec(bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private fun parsePublicKey(pubB64: String): PublicKey {
        val bytes = Base64.getDecoder().decode(pubB64.trim())
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    /**
     * 端到端加密邮件正文
     * @param plaintext 明文内容
     * @param recipientPublicKeyB64 收件人公钥 Base64
     * @return 包含 version, encKey, iv, ciphertext 的 JSON 密文字符串
     */
    fun encrypt(plaintext: String, recipientPublicKeyB64: String): String {
        // 1. 生成一次性 AES-256 密钥
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, SecureRandom())
        val aesKey = keyGen.generateKey()

        // 2. 生成 12 字节 GCM IV
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        // 3. 用 AES-256-GCM 加密明文
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ciphertext = aesCipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        // 4. 用收件人 RSA 公钥加密 AES 密钥 (RSA/ECB/OAEPWithSHA-256AndMGF1Padding)
        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val pubKey = parsePublicKey(recipientPublicKeyB64)
        rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encryptedAesKey = rsaCipher.doFinal(aesKey.encoded)

        // 5. 组装密文包裹
        return JSONObject().apply {
            put("e2ee_ver", "1.0")
            put("enc_key", Base64.getEncoder().encodeToString(encryptedAesKey))
            put("iv", Base64.getEncoder().encodeToString(iv))
            put("cipher", Base64.getEncoder().encodeToString(ciphertext))
        }.toString()
    }

    /**
     * 解密端到端加密邮件密文
     * @param cipherBundle 密文 JSON 字符串
     * @return 解密后的原始文本
     */
    fun decrypt(context: Context, cipherBundle: String): String {
        val json = JSONObject(cipherBundle)
        val encKeyBytes = Base64.getDecoder().decode(json.getString("enc_key"))
        val iv = Base64.getDecoder().decode(json.getString("iv"))
        val cipherBytes = Base64.getDecoder().decode(json.getString("cipher"))

        // 1. 用本地私钥解出 AES 密钥
        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsaCipher.init(Cipher.DECRYPT_MODE, getPrivateKey(context))
        val aesKeyBytes = rsaCipher.doFinal(encKeyBytes)
        val aesKey: SecretKey = SecretKeySpec(aesKeyBytes, "AES")

        // 2. 用 AES-256-GCM 解密密文
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val plainBytes = aesCipher.doFinal(cipherBytes)
        return String(plainBytes, StandardCharsets.UTF_8)
    }

    /**
     * 生成跨设备同步二维码载荷（通过用户设定的临时配对密码加密私钥）
     */
    fun generateSyncPayload(context: Context, pairingCode: String): String {
        val privB64 = prefs(context).getString(KEY_PRIVATE, "").orEmpty()
        val pubB64 = prefs(context).getString(KEY_PUBLIC, "").orEmpty()
        if (privB64.isEmpty()) return ""

        val salt = MessageDigest.getInstance("SHA-256").digest(pairingCode.toByteArray(StandardCharsets.UTF_8))
        val derivedKey = SecretKeySpec(salt, "AES")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derivedKey, GCMParameterSpec(128, iv))
        val encryptedPriv = cipher.doFinal(privB64.toByteArray(StandardCharsets.UTF_8))

        return JSONObject().apply {
            put("ots_sync", "e2ee")
            put("pub", pubB64)
            put("iv", Base64.getEncoder().encodeToString(iv))
            put("sec", Base64.getEncoder().encodeToString(encryptedPriv))
        }.toString()
    }

    /**
     * 从跨设备同步载荷中恢复私钥
     */
    fun importSyncPayload(context: Context, payload: String, pairingCode: String): Boolean {
        return runCatching {
            val json = JSONObject(payload)
            if (json.optString("ots_sync") != "e2ee") return false
            val pubB64 = json.getString("pub")
            val iv = Base64.getDecoder().decode(json.getString("iv"))
            val secBytes = Base64.getDecoder().decode(json.getString("sec"))

            val salt = MessageDigest.getInstance("SHA-256").digest(pairingCode.toByteArray(StandardCharsets.UTF_8))
            val derivedKey = SecretKeySpec(salt, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derivedKey, GCMParameterSpec(128, iv))
            val privBytes = cipher.doFinal(secBytes)
            val privB64 = String(privBytes, StandardCharsets.UTF_8)

            prefs(context).edit()
                .putString(KEY_PUBLIC, pubB64)
                .putString(KEY_PRIVATE, privB64)
                .apply()
            true
        }.getOrDefault(false)
    }
}
