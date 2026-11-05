package com.otscup.otsmail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 写信草稿：只存本机（filesDir/otsmail_drafts.bin，AES-GCM 密文），按 accountId 隔离。
 *
 * 网页版的草稿在浏览器 IndexedDB，后端没有 draft 表；要做跨端同步得先加服务端接口。
 * 这里保存写信页能还原的全部字段（收件人/抄送/密送含正在输入的文字、主题、正文与光标、
 * 引用、附件、发件人），而不是原来只有 to/subject/text 的三字段。
 *
 * 用文件而不是 SharedPreferences：附件 base64 很容易超过 SP 能可靠写入的大小。
 *
 * 落盘格式（v0.2.3 起）：魔数 "OTSD1" + 12 字节 IV + AES-GCM 密文。
 * 密文里的附件 base64 一样是用户数据，明文落盘等于把草稿（含未发送的正文/附件）白送，
 * 所以统一加密。密钥在 Android Keystore 生成、不可导出；文件被拷走但没拿到设备密钥时解不开。
 * 这**不能**防已 root 的设备（root 能直接调用 Keystore / 读内存），也不防用户在解锁状态下
 * 手动点开 App 之外的东西；威胁模型只覆盖"文件被拷出去"。
 *
 * 旧版本留下的是明文 otsmail_drafts.json：读到就立刻迁移成密文，迁移失败保留原文件不动。
 */
data class Draft(
    val id: String,
    val accountId: Long,
    val fromId: Long,
    val toTags: List<String>,
    val toInput: String,
    val ccTags: List<String>,
    val ccInput: String,
    val bccTags: List<String>,
    val bccInput: String,
    val subject: String,
    val body: String,
    val selStart: Int,
    val selEnd: Int,
    val quoted: String,
    /** 附件 JSON 数组（与 /email/send 的 attachments 同结构），过大时 content 会被清空。 */
    val attachmentsJson: String,
    /** true 表示有附件因体积过大没能完整保存，恢复时要明确提示。 */
    val attachmentsDropped: Boolean,
    val updatedAt: Long,
) {
    fun isEmpty(): Boolean =
        toTags.isEmpty() && toInput.isBlank() &&
            ccTags.isEmpty() && ccInput.isBlank() &&
            bccTags.isEmpty() && bccInput.isBlank() &&
            subject.isBlank() && body.isBlank() &&
            (attachmentsJson.isBlank() || attachmentsJson == "[]")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("accountId", accountId)
        .put("fromId", fromId)
        .put("toTags", JSONArray(toTags))
        .put("toInput", toInput)
        .put("ccTags", JSONArray(ccTags))
        .put("ccInput", ccInput)
        .put("bccTags", JSONArray(bccTags))
        .put("bccInput", bccInput)
        .put("subject", subject)
        .put("body", body)
        .put("selStart", selStart)
        .put("selEnd", selEnd)
        .put("quoted", quoted)
        .put("attachments", attachmentsJson)
        .put("attachmentsDropped", attachmentsDropped)
        .put("updatedAt", updatedAt)

    companion object {
        private fun JSONArray?.list(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

        fun fromJson(o: JSONObject): Draft = Draft(
            id = o.optString("id"),
            accountId = o.optLong("accountId"),
            fromId = o.optLong("fromId"),
            toTags = o.optJSONArray("toTags").list(),
            toInput = o.optString("toInput"),
            ccTags = o.optJSONArray("ccTags").list(),
            ccInput = o.optString("ccInput"),
            bccTags = o.optJSONArray("bccTags").list(),
            bccInput = o.optString("bccInput"),
            subject = o.optString("subject"),
            body = o.optString("body"),
            selStart = o.optInt("selStart", -1),
            selEnd = o.optInt("selEnd", -1),
            quoted = o.optString("quoted"),
            attachmentsJson = o.optString("attachments").ifBlank { "[]" },
            attachmentsDropped = o.optBoolean("attachmentsDropped", false),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}

object Drafts {
    /** 旧版本明文文件（只读兼容，迁移成功后删除）。 */
    private const val LEGACY_FILE = "otsmail_drafts.json"
    /** 新格式密文文件：魔数 + IV + GCM 密文。 */
    private const val ENC_FILE = "otsmail_drafts.bin"
    private const val MAGIC = "OTSD1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEYSTORE = "AndroidKeyStore"
    private const val LOG_TAG = "Drafts"
    private const val MAX_PER_ACCOUNT = 20
    /** 附件 base64 累计上限：超过的单条只留文件名/大小，恢复时提示。 */
    private const val MAX_ATTACH_CHARS = 2_000_000

    /** 内存里的密钥引用；[clearAll] 时置空。Keystore 条目本身保留（理由见 clearAll）。 */
    @Volatile
    private var cachedKey: SecretKey? = null

    private fun legacyFile(ctx: Context): File = File(ctx.applicationContext.filesDir, LEGACY_FILE)
    private fun encFile(ctx: Context): File = File(ctx.applicationContext.filesDir, ENC_FILE)

    private fun alias(ctx: Context): String = "${ctx.applicationContext.packageName}.drafts"

    /** Keystore 里没有就建一把：AES-256/GCM，密钥不可导出，不要求解锁时二次认证。 */
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
                // 草稿是后台自动保存的，不能要求用户每写一封都解锁一次
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey().also { cachedKey = it }
    }

    private fun encrypt(ctx: Context, plain: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(ctx))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return MAGIC.toByteArray(Charsets.UTF_8) + cipher.iv + body
    }

    /** 返回 null 表示不是本格式 / 被截断 / 密钥对不上（GCM tag 校验失败）。 */
    private fun decrypt(ctx: Context, data: ByteArray): String? {
        if (data.size < MAGIC.length + IV_BYTES + 16) return null
        if (!data.copyOfRange(0, MAGIC.length).contentEquals(MAGIC.toByteArray(Charsets.UTF_8))) return null
        val iv = data.copyOfRange(MAGIC.length, MAGIC.length + IV_BYTES)
        val body = data.copyOfRange(MAGIC.length + IV_BYTES, data.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(ctx), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun parse(raw: String): MutableList<Draft>? = runCatching { JSONArray(raw) }.getOrNull()
        ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Draft::fromJson) }.toMutableList() }

    /** 读不到（损坏/截断/密钥丢失）时把现场挪到 .corrupt，返回空列表，绝不抛给 UI。 */
    private fun quarantine(ctx: Context, f: File): MutableList<Draft> {
        val bad = File(f.parentFile, "${f.name}.corrupt")
        runCatching {
            if (bad.exists()) bad.delete()
            f.renameTo(bad)
        }.onFailure { Log.w(LOG_TAG, "quarantine ${f.name} failed", it) }
        Log.w(LOG_TAG, "draft file ${f.name} unreadable, kept as ${bad.name}")
        return mutableListOf()
    }

    @Synchronized
    private fun readAll(ctx: Context): MutableList<Draft> {
        val enc = encFile(ctx)
        if (enc.exists()) {
            val raw = runCatching { enc.readBytes() }.getOrNull()
                ?: return quarantine(ctx, enc)
            val text = decrypt(ctx, raw) ?: return quarantine(ctx, enc)
            return parse(text) ?: quarantine(ctx, enc)
        }
        // 旧版本明文：读出来立刻迁移；迁移失败保留原文件，用户草稿不丢
        val legacy = legacyFile(ctx)
        if (!legacy.exists()) return mutableListOf()
        val text = runCatching { legacy.readText() }.getOrNull()
        if (text == null) {
            Log.w(LOG_TAG, "legacy draft unreadable, kept as is")
            return mutableListOf()
        }
        val list = parse(text)
        if (list == null) {
            Log.w(LOG_TAG, "legacy draft invalid JSON, kept as is")
            return mutableListOf()
        }
        val ok = runCatching { enc.writeBytes(encrypt(ctx, text)) }.isSuccess
        if (ok) {
            runCatching { legacy.delete() }
            Log.i(LOG_TAG, "legacy plaintext drafts migrated to $ENC_FILE")
        } else {
            Log.w(LOG_TAG, "migrate drafts failed, plaintext kept")
        }
        return list
    }

    @Synchronized
    private fun writeAll(ctx: Context, list: List<Draft>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val bytes = runCatching { encrypt(ctx, arr.toString()) }.getOrElse {
            Log.w(LOG_TAG, "encrypt drafts failed", it)
            return
        }
        val f = encFile(ctx)
        val tmp = File(f.parentFile, "$ENC_FILE.tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                f.writeBytes(bytes)
                tmp.delete()
            }
            // 已经有密文了，旧明文副本没必要继续留
            legacyFile(ctx).delete()
        }.onFailure { Log.w(LOG_TAG, "write drafts failed", it) }
    }

    /** 某账号的草稿，最新在前。 */
    fun list(ctx: Context, accountId: Long): List<Draft> =
        readAll(ctx).filter { it.accountId == accountId }.sortedByDescending { it.updatedAt }

    fun get(ctx: Context, id: String): Draft? = readAll(ctx).firstOrNull { it.id == id }

    fun latest(ctx: Context, accountId: Long): Draft? = list(ctx, accountId).firstOrNull()

    fun delete(ctx: Context, id: String) {
        val all = readAll(ctx)
        all.removeAll { it.id == id }
        writeAll(ctx, all)
    }

    /**
     * 退出登录/换服务器：草稿是密文文件，连同内存里的密钥引用一起清掉，
     * 下个账号不会读到上一账号的草稿。
     *
     * 不删 Keystore 条目：文件已经删了，条目本身没有可解的内容；而删条目在某些机型上
     * 会连带影响同别名的加密调用，反而增加失败面。真要销毁密钥，清应用数据或卸载即可。
     */
    fun clearAll(ctx: Context) {
        cachedKey = null
        runCatching { encFile(ctx).delete() }
        runCatching { legacyFile(ctx).delete() }
    }

    /**
     * 保存/覆盖一份草稿。附件过大时逐条剥掉 content，并置 attachmentsDropped。
     */
    fun save(ctx: Context, draft: Draft): Draft {
        val (json, dropped) = capAttachments(draft.attachmentsJson)
        val now = System.currentTimeMillis()
        val d = draft.copy(
            attachmentsJson = json,
            attachmentsDropped = draft.attachmentsDropped || dropped,
            updatedAt = now,
        )
        val all = readAll(ctx)
        all.removeAll { it.id == d.id }
        all.add(0, d)
        // 每个账号只保留最近 20 份，避免文件无限长
        val trimmed = ArrayList<Draft>(all.size)
        var kept = 0
        all.sortedByDescending { it.updatedAt }.forEach { item ->
            if (item.accountId != d.accountId || kept < MAX_PER_ACCOUNT) {
                trimmed.add(item)
                if (item.accountId == d.accountId) kept++
            }
        }
        writeAll(ctx, trimmed)
        return d
    }

    private fun capAttachments(raw: String): Pair<String, Boolean> {
        if (raw.isBlank() || raw == "[]") return "[]" to false
        val src = runCatching { JSONArray(raw) }.getOrNull() ?: return "[]" to false
        val out = JSONArray()
        var sum = 0
        var dropped = false
        for (i in 0 until src.length()) {
            val o = src.optJSONObject(i) ?: continue
            val copy = JSONObject(o.toString())
            val content = copy.optString("content")
            if (sum + content.length > MAX_ATTACH_CHARS) {
                copy.remove("content")
                dropped = true
            } else {
                sum += content.length
            }
            out.put(copy)
        }
        return out.toString() to dropped
    }
}
