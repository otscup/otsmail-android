package com.otscup.otsmail.data

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 本地 TOTP（RFC 6238，HMAC-SHA1 / 6 位 / 30 秒）。
 *
 * 只支持标准参数：SHA1 / 6 位 / 30 秒。绝大多数验证器（Google Authenticator、
 * 1Password、微软验证器）都用这套默认值；otpauth 链接里若写了别的算法，
 * 这里仍按标准值计算，界面会提示导入的密钥用于本地出码。
 */
object Totp {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    const val PERIOD_SECONDS = 30L

    /** 归一化 base32：去空格/连字符、转大写；长度不足 16 视为无效。 */
    fun normalize(raw: String): String? {
        val clean = raw.uppercase().filter { ALPHABET.indexOf(it) >= 0 }
        return clean.takeIf { it.length >= 16 }
    }

    /** 接受原始 base32 或 otpauth://totp 链接，解析出 secret。 */
    fun parse(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("otpauth://", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
            return normalize(uri.getQueryParameter("secret").orEmpty())
        }
        return normalize(text)
    }

    fun base32Decode(secret: String): ByteArray {
        val out = ByteArrayOutputStream()
        var bits = 0
        var value = 0
        for (char in secret.uppercase()) {
            val index = ALPHABET.indexOf(char)
            if (index < 0) continue
            value = (value shl 5) or index
            bits += 5
            if (bits >= 8) {
                out.write((value shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    fun code(secret: String, timestampMillis: Long = System.currentTimeMillis()): String? {
        val key = base32Decode(secret)
        if (key.isEmpty()) return null
        return runCatching {
            val counter = timestampMillis / 1000L / PERIOD_SECONDS
            val message = ByteBuffer.allocate(8).putLong(counter).array()
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(message)
            val offset = hash[hash.size - 1].toInt() and 0x0f
            val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
            (binary % 1_000_000).toString().padStart(6, '0')
        }.getOrNull()
    }

    fun secondsRemaining(timestampMillis: Long = System.currentTimeMillis()): Int {
        val elapsed = (timestampMillis / 1000L) % PERIOD_SECONDS
        return (PERIOD_SECONDS - elapsed).toInt()
    }
}
