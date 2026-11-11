package com.otscup.otsmail.data

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SnoozeStatus { pending, fired, done, canceled }

data class SnoozeReminder(
    val id: String = UUID.randomUUID().toString(),
    val accountKey: String = "",
    val base: String = "",
    val userId: Long? = null,
    // 第三方提醒才有的绑定账户 id：解绑该邮箱时按它删数据和闹钟（accountKey 仍是 OTS 账号）
    val extId: Long? = null,
    val mailId: String = "",
    val subject: String = "",
    val fromName: String = "",
    val atMillis: Long = 0L,
    val atLocal: String = "",
    val status: SnoozeStatus = SnoozeStatus.pending,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "otsmail", // otsmail|ext; MVP UI wires otsmail Detail
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("accountKey", accountKey)
        .put("base", base)
        .put("userId", userId ?: 0L)
        .put("extId", extId ?: 0L)
        .put("mailId", mailId)
        .put("subject", subject)
        .put("fromName", fromName)
        .put("atMillis", atMillis)
        .put("atLocal", atLocal)
        .put("status", status.name)
        .put("createdAt", createdAt)
        .put("source", source)

    companion object {
        fun fromJson(o: JSONObject): SnoozeReminder? {
            val id = o.optString("id")
            if (id.isBlank()) return null
            val st = o.optString("status")
            return SnoozeReminder(
                id = id,
                accountKey = o.optString("accountKey"),
                base = o.optString("base"),
                userId = o.optLong("userId", 0L).takeIf { it != 0L },
                extId = o.optLong("extId", 0L).takeIf { it != 0L },
                mailId = o.optString("mailId"),
                subject = o.optString("subject"),
                fromName = o.optString("fromName"),
                atMillis = o.optLong("atMillis"),
                atLocal = o.optString("atLocal"),
                status = SnoozeStatus.entries.firstOrNull { it.name == st } ?: SnoozeStatus.pending,
                createdAt = o.optLong("createdAt"),
                source = o.optString("source", "otsmail"),
            )
        }
    }
}

object SnoozeTime {
    fun toLocalLabel(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = Instant.ofEpochMilli(atMillis).atZone(zone)
        val sameDay = dt.toLocalDate() == ZonedDateTime.now(zone).toLocalDate()
        val pat = if (sameDay) "HH:mm" else "M月d日 HH:mm"
        return dt.format(DateTimeFormatter.ofPattern(pat, Locale.CHINA))
    }

    // 0 = 未登录/未取到，按「无 id」处理，退回用邮箱做 key
    fun accountKey(base: String, accountId: Long?, userId: Long?, email: String): String {
        val id = accountId?.takeIf { it != 0L } ?: userId?.takeIf { it != 0L }
        return if (id != null) "${base.trim().trimEnd('/')}#$id" else "${base.trim().trimEnd('/')}#${email.trim().lowercase()}"
    }

    fun upsertKey(accountKey: String, source: String, mailId: String): String =
        "$accountKey|$source|$mailId"
}

object SnoozeReminderStore {
    private const val FILE = "otsmail_snooze.bin"
    private const val KS = "AndroidKeyStore"
    private const val ALIAS_SUFFIX = ".snooze"
    @Synchronized
    fun all(ctx: Context): List<SnoozeReminder> = read(ctx)

    @Synchronized
    fun pendingForAccount(ctx: Context, accountKey: String): List<SnoozeReminder> =
        read(ctx).filter { it.accountKey == accountKey && it.status == SnoozeStatus.pending }
            .sortedBy { it.atMillis }

    @Synchronized
    fun allForAccount(ctx: Context, accountKey: String): List<SnoozeReminder> =
        read(ctx).filter { it.accountKey == accountKey }
            .sortedWith(compareBy<SnoozeReminder> { it.status != SnoozeStatus.pending }
                .thenBy { if (it.status == SnoozeStatus.pending) it.atMillis else -it.atMillis })

    @Synchronized
    fun get(ctx: Context, id: String): SnoozeReminder? = read(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(ctx: Context, r: SnoozeReminder): SnoozeReminder {
        val list = read(ctx).toMutableList()
        val key = SnoozeTime.upsertKey(r.accountKey, r.source, r.mailId)
        val idx = list.indexOfFirst { SnoozeTime.upsertKey(it.accountKey, it.source, it.mailId) == key && it.status == SnoozeStatus.pending }
        if (idx >= 0) list[idx] = r.copy(id = list[idx].id, createdAt = list[idx].createdAt) else list += r
        write(ctx, list)
        return list.first { SnoozeTime.upsertKey(it.accountKey, it.source, it.mailId) == key && (it.id == r.id || idx >= 0) }
    }

    @Synchronized
    fun setStatus(ctx: Context, id: String, status: SnoozeStatus, atMillis: Long? = null): SnoozeReminder? {
        val list = read(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        val cur = list[i]
        list[i] = cur.copy(
            status = status,
            atMillis = atMillis ?: cur.atMillis,
            atLocal = if (atMillis != null) SnoozeTime.toLocalLabel(atMillis) else cur.atLocal,
        )
        write(ctx, list)
        return list[i]
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        write(ctx, read(ctx).filterNot { it.id == id })
    }

    @Synchronized
    fun clearForAccount(ctx: Context, accountKey: String) {
        write(ctx, read(ctx).filterNot { it.accountKey == accountKey })
    }

    /** 解绑第三方邮箱：只删该 extId 名下提醒，同账号的 OTS 与其他第三方提醒不动。 */
    @Synchronized
    fun clearForExt(ctx: Context, extId: Long) {
        write(ctx, read(ctx).filterNot { it.extId == extId })
    }

    @Synchronized
    fun clearAll(ctx: Context) {
        file(ctx).delete()
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun read(ctx: Context): List<SnoozeReminder> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val blob = f.readBytes()
            if (blob.size < 13) return emptyList()
            val iv = blob.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(ctx), GCMParameterSpec(128, iv))
            val arr = JSONArray(String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { SnoozeReminder.fromJson(arr.optJSONObject(it) ?: return@mapNotNull null) }
        } catch (_: Exception) { emptyList() }
    }

    private fun write(ctx: Context, list: List<SnoozeReminder>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Keystore GCM keys reject a caller-supplied IV; the cipher generates one we read back.
        cipher.init(Cipher.ENCRYPT_MODE, key(ctx))
        val iv = cipher.iv
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file(ctx).writeBytes(iv + cipher.doFinal(arr.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun key(ctx: Context): SecretKey {
        val alias = ctx.packageName + ALIAS_SUFFIX
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= 28) {
            spec.setUnlockedDeviceRequired(false)
        }
        return KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, KS)
            .apply { init(spec.build()) }.generateKey()
    }
}
