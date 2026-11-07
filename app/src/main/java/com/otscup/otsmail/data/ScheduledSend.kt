package com.otscup.otsmail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 一封等待定时发送的邮件。
 * [atMillis] 是真正触发的绝对时刻；[atLocal] 记下用户当时选的「墙上时间」
 * （`yyyy-MM-dd'T'HH:mm`），设备改时区后按它重算，避免「明早 8 点」变成当地的 3 点。
 */
internal data class ScheduledMail(
    val id: String,
    /** 去重键：同账号同收件人同主题同正文只排一次 */
    val key: String,
    val accountId: Long,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val text: String,
    val html: String,
    /** 未附签名/引用的原始正文：取消定时、发送失败转草稿时用它，避免签名重复 */
    val body: String = "",
    /** 引用原文（HTML） */
    val quoted: String = "",
    val attachmentsJson: String = "[]",
    val atMillis: Long,
    val atLocal: String,
    /** pending = 等待发送；failed = 到点发送失败，保留原文与原因，等用户重试或转草稿 */
    val status: String = STATUS_PENDING,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 写信页草稿 id：定时成功后删掉，取消定时时用来恢复成草稿 */
    val draftId: String? = null,
    /** 发件账号邮箱，恢复草稿时用 */
    val fromEmail: String = "",
) {
    val pending: Boolean get() = status == STATUS_PENDING

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("key", key)
        .put("accountId", accountId)
        .put("to", JSONArray(to))
        .put("cc", JSONArray(cc))
        .put("bcc", JSONArray(bcc))
        .put("subject", subject)
        .put("text", text)
        .put("html", html)
        .put("body", body)
        .put("quoted", quoted)
        .put("attachments", attachmentsJson)
        .put("atMillis", atMillis)
        .put("atLocal", atLocal)
        .put("status", status)
        .put("error", error)
        .put("createdAt", createdAt)
        .put("draftId", draftId.orEmpty())
        .put("fromEmail", fromEmail)

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_FAILED = "failed"

        private fun JSONArray?.strList(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

        fun fromJson(o: JSONObject): ScheduledMail? {
            val id = o.optString("id")
            if (id.isBlank()) return null
            return ScheduledMail(
                id = id,
                key = o.optString("key"),
                accountId = o.optLong("accountId"),
                to = o.optJSONArray("to").strList(),
                cc = o.optJSONArray("cc").strList(),
                bcc = o.optJSONArray("bcc").strList(),
                subject = o.optString("subject"),
                text = o.optString("text"),
                html = o.optString("html"),
                body = o.optString("body"),
                quoted = o.optString("quoted"),
                attachmentsJson = o.optString("attachments").ifBlank { "[]" },
                atMillis = o.optLong("atMillis"),
                atLocal = o.optString("atLocal"),
                status = o.optString("status").ifBlank { STATUS_PENDING },
                error = o.optString("error"),
                createdAt = o.optLong("createdAt"),
                draftId = o.optString("draftId").ifBlank { null },
                fromEmail = o.optString("fromEmail"),
            )
        }
    }
}

/**
 * 定时发送的时间计算（纯函数，单测覆盖）。
 * 全部按设备本地时区换算，跨天/跨月由 java.time 处理；夏令时跳变交给
 * [LocalDateTime.atZone]（不存在的时刻按 ICU 规则顺延，重复的时刻取较早的一次）。
 */
internal object ScheduledSendTime {
    val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun atLocal(zone: ZoneId, date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** 下一个本地 hour:minute（今天这个点还没到就是今天，否则明天）。 */
    fun nextAt(nowMillis: Long, zone: ZoneId, hour: Int, minute: Int): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = atLocal(zone, now.toLocalDate(), hour, minute)
        return if (today > nowMillis) today else atLocal(zone, now.toLocalDate().plusDays(1), hour, minute)
    }

    /** 快捷「明早 8 点」：即下一个本地 08:00。 */
    fun nextMorning(nowMillis: Long, zone: ZoneId = zone()): Long = nextAt(nowMillis, zone, 8, 0)

    /** 快捷「今天下班后」：本地 18:30，已过则明天 18:30。 */
    fun nextEvening(nowMillis: Long, zone: ZoneId = zone()): Long = nextAt(nowMillis, zone, 18, 30)

    /** 选中的时间不能早于现在（至少 1 分钟后），避免排到过去立刻触发。 */
    fun atLeastOneMinuteAhead(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long =
        maxOf(atMillis, nowMillis + 60_000L)

    /** 墙上时间字符串（ISO，可直接排序）。 */
    fun localStamp(atMillis: Long, zone: ZoneId = zone()): String =
        Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDateTime().toString()

    fun display(atMillis: Long, zone: ZoneId = zone()): String =
        Instant.ofEpochMilli(atMillis).atZone(zone).format(DISPLAY)

    /**
     * 设备换时区后按原「墙上时间」重算绝对时刻，并保证不会落到现在之前。
     * 解析失败（旧记录没存 atLocal）返回 null，由调用方回退到原 atMillis。
     */
    fun reanchor(atLocal: String, zone: ZoneId = zone(), nowMillis: Long = System.currentTimeMillis()): Long? =
        runCatching {
            atLeastOneMinuteAhead(LocalDateTime.parse(atLocal).atZone(zone).toInstant().toEpochMilli(), nowMillis)
        }.getOrNull()

    /** 同一封邮件（账号+收件人+主题+正文）只排一次队。 */
    fun dedupeKey(accountId: Long, to: List<String>, subject: String, html: String): String =
        accountId.toString() + "|" +
            to.map { it.trim().lowercase() }.sorted().joinToString(",") + "|" +
            subject.trim() + "|" + html.hashCode()

    /**
     * 插入或替换：同 [dedupeKey] 且仍在等待的旧条目直接被替换（改时间不算新排一封），
     * 失败条目不算占位，允许重新排。
     */
    fun upsert(list: List<ScheduledMail>, mail: ScheduledMail): List<ScheduledMail> {
        val idx = list.indexOfFirst { it.key == mail.key && it.pending }
        val next = if (idx < 0) {
            list + mail
        } else {
            list.toMutableList().also {
                it[idx] = mail.copy(id = list[idx].id, status = ScheduledMail.STATUS_PENDING, error = "")
            }
        }
        return next.sortedBy { it.atMillis }
    }
}

/**
 * 定时发送的本地存储：AES-GCM 加密文件，理由与草稿一致（正文与附件 base64 都是用户数据，
 * 明文落盘等于白送）。单封上限 4MB，超了直接拒绝排队（提示先直接发送），不做静默截断。
 */
internal object ScheduledSendStore {
    private const val FILE = "otsmail_scheduled.bin"
    private const val MAGIC = "OTSS1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEYSTORE = "AndroidKeyStore"
    private const val LOG_TAG = "ScheduledSend"
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val lock = Any()
    @Volatile
    private var cachedKey: SecretKey? = null

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE)

    private fun alias(ctx: Context): String = "${ctx.applicationContext.packageName}.scheduled"

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
            KeyGenParameterSpec.Builder(alias(ctx), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey().also { cachedKey = it }
    }

    fun list(ctx: Context): List<ScheduledMail> = synchronized(lock) { read(ctx) }

    fun get(ctx: Context, id: String): ScheduledMail? =
        synchronized(lock) { read(ctx).firstOrNull { it.id == id } }

    /** 写入/替换；超过上限或加密失败返回 false，调用方必须提示用户，不能悄悄丢。 */
    fun upsert(ctx: Context, mail: ScheduledMail): Boolean = synchronized(lock) {
        runCatching { write(ctx, ScheduledSendTime.upsert(read(ctx), mail)) }.isSuccess
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        write(ctx, read(ctx).filterNot { it.id == id })
    }

    fun markFailed(ctx: Context, id: String, error: String) = synchronized(lock) {
        write(ctx, read(ctx).map {
            if (it.id == id) it.copy(status = ScheduledMail.STATUS_FAILED, error = error) else it
        })
    }

    /** 改时间（失败项重新排队时也用它）。 */
    fun reschedule(ctx: Context, id: String, atMillis: Long, atLocal: String) = synchronized(lock) {
        write(ctx, read(ctx).map {
            if (it.id == id) {
                it.copy(
                    atMillis = atMillis,
                    atLocal = atLocal,
                    status = ScheduledMail.STATUS_PENDING,
                    error = "",
                )
            } else it
        })
    }
    /** 换时区后统一重算（把「8 点」保持成本地的 8 点）。 */
    fun reanchorAll(ctx: Context, zone: ZoneId) = synchronized(lock) {
        val now = System.currentTimeMillis()
        write(ctx, read(ctx).map { m ->
            if (!m.pending) return@map m
            val at = ScheduledSendTime.reanchor(m.atLocal, zone, now) ?: return@map m
            if (at == m.atMillis) m else m.copy(atMillis = at, atLocal = ScheduledSendTime.localStamp(at, zone))
        })
    }

    private fun read(ctx: Context): List<ScheduledMail> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val packed = f.readBytes()
            val plain = decrypt(ctx, packed) ?: return emptyList()
            val arr = JSONArray(String(plain, Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { ScheduledMail.fromJson(arr.optJSONObject(it) ?: return@mapNotNull null) }
        }.getOrElse {
            Log.w(LOG_TAG, "读取定时发送失败：${it.message}")
            emptyList()
        }
    }

    private fun write(ctx: Context, list: List<ScheduledMail>) {
        val payload = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        require(payload.length <= MAX_BYTES) { "附件过大，无法定时发送" }
        val packed = encrypt(ctx, payload.toByteArray(Charsets.UTF_8))
        val f = file(ctx)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeBytes(packed)
        if (!tmp.renameTo(f)) {
            f.writeBytes(packed)
            tmp.delete()
        }
    }

    private fun encrypt(ctx: Context, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(ctx))
        val body = cipher.doFinal(plain)
        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(cipher.iv)
        out.write(body)
        return out.toByteArray()
    }

    private fun decrypt(ctx: Context, packed: ByteArray): ByteArray? {
        if (packed.size < MAGIC.length + IV_BYTES + 16) return null
        val magic = String(packed, 0, MAGIC.length, Charsets.US_ASCII)
        if (magic != MAGIC) return null
        return runCatching {
            val iv = packed.copyOfRange(MAGIC.length, MAGIC.length + IV_BYTES)
            val body = packed.copyOfRange(MAGIC.length + IV_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(ctx), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        }.getOrNull()
    }

    /** 测试/退出登录时清空（文件删掉，Keystore 条目保留，理由同 Drafts）。 */
    fun clear(ctx: Context) {
        synchronized(lock) {
            runCatching { file(ctx).delete() }
            runCatching { File(file(ctx).parentFile, "$FILE.tmp").delete() }
        }
    }
}
