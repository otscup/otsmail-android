package com.otscup.otsmail.ui

import android.content.Context
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.MailItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收件人自动补齐的候选：只来自邮件历史（不接系统通讯录、不申请联系人权限）。
 * 来源是收件箱的发件人（最近两页）与已发送的收件人（最近两页），
 * 按使用频次与最近使用时间排序。
 */
internal data class RecipientCandidate(
    val name: String,
    val email: String,
    /** 在邮件历史里出现的次数 */
    val count: Int = 0,
    /** 最近一次出现的时间（服务端 createTime 字符串，字典序即时间序） */
    val lastUsed: String = "",
    /** 姓名汉字的拼音首字母，小写；用于「zs」匹配「张三」 */
    val initials: String = "",
)

/**
 * 候选的匹配与排序规则（纯函数，单测覆盖）。
 * 只加载一次进内存，敲键时只做内存过滤，不按查询重扫服务端。
 */
internal object RecipientSuggest {
    private const val LIMIT = 6

    private val ADDRESS_SPLIT = Regex("[,;，；\\s]+")
    private val PLAIN_ADDRESS = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+$")

    /** 输入框里最后一个未提交的片段：多个收件人时，前面已锁定的地址不参与匹配。 */
    fun querySegment(input: String): String =
        input.replace('，', ',').replace('；', ';')
            .split(ADDRESS_SPLIT)
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

    /**
     * 过滤 + 排序 + 截断。
     * 排序：有历史使用的在前 → 频次高的在前 → 最近用过的在前 → 匹配位置（邮箱前缀、
     * 姓名前缀、拼音首字母、其他包含）→ 姓名 → 邮箱，保证同名不同址是两条独立候选。
     */
    fun rank(
        candidates: List<RecipientCandidate>,
        query: String,
        taken: Collection<String> = emptyList(),
        limit: Int = LIMIT,
    ): List<RecipientCandidate> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val takenLower = taken.map { it.trim().lowercase() }.toSet()

        val merged = LinkedHashMap<String, RecipientCandidate>()
        candidates.forEach { c ->
            val email = c.email.trim()
            if (!PLAIN_ADDRESS.matches(email)) return@forEach
            val key = email.lowercase()
            if (key in takenLower) return@forEach
            val prev = merged[key]
            merged[key] = if (prev == null) {
                c.copy(email = email)
            } else {
                prev.copy(
                    name = prev.name.ifBlank { c.name },
                    count = maxOf(prev.count, c.count),
                    lastUsed = maxOf(prev.lastUsed, c.lastUsed),
                    initials = prev.initials.ifBlank { c.initials },
                )
            }
        }

        return merged.values
            .filter { matches(it, q) }
            .sortedWith(
                compareBy<RecipientCandidate> {
                    if (it.count > 0 || it.lastUsed.isNotEmpty()) 0 else 1
                }
                    .thenByDescending { it.count }
                    .thenByDescending { it.lastUsed }
                    .thenBy { matchRank(it, q) }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.email.lowercase() },
            )
            .take(limit)
    }

    /**
     * 通讯/营销地址不是「常用联系人」，避免补全时排在真人前面。
     * 只按本地部分判断，不额外请求服务端。
     */
    fun isAutomated(email: String): Boolean {
        val local = email.substringBefore("@").lowercase()
        return local.contains("noreply") || local.contains("no-reply") ||
            local.startsWith("notification") || local.startsWith("notify") ||
            local.startsWith("mailer") || local.startsWith("bounce") ||
            local.startsWith("donotreply") || local.startsWith("do-not-reply") ||
            local == "postmaster"
    }

    fun isPlainAddress(email: String): Boolean = PLAIN_ADDRESS.matches(email.trim())

    private fun matches(c: RecipientCandidate, q: String): Boolean {
        if (c.email.contains(q, ignoreCase = true)) return true
        if (c.name.contains(q, ignoreCase = true)) return true
        // 拼音首字母只对字母查询生效；「@」开头说明在按邮箱匹配，不再看首字母
        return q.none { it == '@' } && c.initials.isNotEmpty() && c.initials.contains(q)
    }

    private fun matchRank(c: RecipientCandidate, q: String): Int = when {
        c.email.startsWith(q, ignoreCase = true) -> 0
        c.name.startsWith(q, ignoreCase = true) -> 1
        c.initials.startsWith(q, ignoreCase = true) -> 2
        else -> 3
    }

    /**
     * 姓名拼音首字母：用系统自带的 ICU Han-Latin 转写，不引入词库、不联网。
     * 不支持时返回空串（只按姓名/邮箱匹配），不影响其它候选。
     */
    fun pinyinInitials(name: String): String {
        if (name.none { it.code in 0x3400..0x9FFF }) return ""
        return runCatching {
            val transliterated = android.icu.text.Transliterator
                .getInstance("Han-Latin; Latin-ASCII")
                .transliterate(name)
            transliterated
                .split(' ', '-', '\'', '·')
                .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
                .joinToString("")
                .lowercase()
        }.getOrDefault("")
    }
}

/**
 * 联系人候选的本地缓存：上一次成功加载的结果按账号写进 prefs，断网时仍能补全。
 *
 * 时效与刷新：30 天。每次联网成功（进写信页后首次输入触发那一次）都会整体覆盖刷新；
 * 只有网络请求全部失败时才读缓存兜底，超过 30 天视为过期不再使用。
 * 只存姓名/邮箱/频次/最近时间，不存任何邮件正文。
 */
internal object RecipientCache {
    private const val PREF = "otsmail_recipients"
    private const val KEY_PREFIX = "cache_"
    private const val TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(ctx: Context, accountId: Long, list: List<RecipientCandidate>) {
        if (list.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("n", c.name)
                        .put("e", c.email)
                        .put("c", c.count)
                        .put("t", c.lastUsed)
                        .put("i", c.initials),
                )
            }
            val payload = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("list", arr)
            prefs(ctx).edit().putString(KEY_PREFIX + accountId, payload.toString()).apply()
        }
    }

    /** 过期或没有缓存返回空列表（调用方按「离线且无缓存」处理）。 */
    fun load(ctx: Context, accountId: Long, now: Long = System.currentTimeMillis()): List<RecipientCandidate> {
        val raw = prefs(ctx).getString(KEY_PREFIX + accountId, null) ?: return emptyList()
        return runCatching {
            val o = JSONObject(raw)
            if (now - o.optLong("at") > TTL_MILLIS) return emptyList()
            val arr = o.optJSONArray("list") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val x = arr.optJSONObject(i) ?: return@mapNotNull null
                val email = x.optString("e")
                if (email.isBlank()) {
                    null
                } else {
                    RecipientCandidate(
                        name = x.optString("n"),
                        email = email,
                        count = x.optInt("c"),
                        lastUsed = x.optString("t"),
                        initials = x.optString("i"),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(ctx: Context) {
        runCatching { prefs(ctx).edit().clear().apply() }
    }
}

/**
 * 一次加载：收件箱发件人 + 已发送收件人（各两页）。只读邮件历史，不接系统通讯录。
 * 联网成功即刷新本地缓存；网络全失败时用上次缓存兜底（30 天内），离线也能补全。
 * [selfEmail] 是本账号地址，自己不作为收件人候选。
 */
internal suspend fun loadRecipientCandidates(
    context: Context,
    accountId: Long,
    selfEmail: String = "",
): List<RecipientCandidate> =
    withContext(Dispatchers.IO) {
        val seen = LinkedHashMap<String, RecipientCandidate>()
        var reached = false

        fun add(name: String, email: String, time: String) {
            val addr = email.trim()
            if (!RecipientSuggest.isPlainAddress(addr)) return
            if (RecipientSuggest.isAutomated(addr)) return
            val key = addr.lowercase()
            if (selfEmail.isNotBlank() && key == selfEmail.trim().lowercase()) return
            val prev = seen[key]
            seen[key] = RecipientCandidate(
                name = name.trim().ifBlank { addr.substringBefore("@") },
                email = addr,
                count = (prev?.count ?: 0) + 1,
                lastUsed = maxOf(prev?.lastUsed.orEmpty(), time),
                initials = prev?.initials.orEmpty()
                    .ifBlank { RecipientSuggest.pinyinInitials(name) },
            )
        }

        for (page in 1..2) {
            val inbox = runCatching { Api.emails(accountId, page) }
                .onSuccess { reached = true }
                .getOrNull()?.first ?: break
            if (inbox.isEmpty()) break
            inbox.forEach { add(it.name, it.from, it.time) }
        }
        for (page in 1..2) {
            val sent = runCatching {
                Api.parseList(Api.emailsRaw(accountId, page, type = 1)).first
            }
                .onSuccess { reached = true }
                .getOrNull() ?: break
            if (sent.isEmpty()) break
            sent.forEach { m -> sentRecipients(m).forEach { add("", it, m.time) } }
        }

        val fresh = seen.values.toList()
        if (fresh.isNotEmpty()) {
            RecipientCache.save(context, accountId, fresh)
            return@withContext fresh
        }
        if (!reached) return@withContext RecipientCache.load(context, accountId)
        fresh
    }

/** 已发送邮件里能看到的收件人：recipient 是首项，toEmail 里可能还有一份完整地址。 */
private fun sentRecipients(m: MailItem): List<String> {
    val out = LinkedHashSet<String>()
    if (m.recipient.contains("@")) out.add(m.recipient.trim())
    m.toEmail.split(',', ';', ' ').map { it.trim() }
        .filter { it.contains("@") }
        .forEach { out.add(it) }
    return out.toList()
}
