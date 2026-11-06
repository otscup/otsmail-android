package com.otscup.otsmail.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.otscup.otsmail.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

data class ExtSyncReport(
    val accounts: Int = 0,
    val fetched: Int = 0,
    val archived: Int = 0,
    val failed: Int = 0,
    val errors: List<String> = emptyList(),
)

/**
 * 第三方邮箱拉取层（客户端执行，服务端只收归档）。
 *
 * 每一轮：取口令 → IMAP 只读登录 → 按 UIDVALIDITY + last_uid 增量取最近 ≤limit 封 →
 * 逐封「解析 → 归档到服务端 → 写本地库 → 推进游标」。中途失败只停这一个账户，游标停在上一封，
 * 下次从这里续；重复的 Message-ID 服务端与本地都按唯一键忽略，所以重跑不会脏数据。
 */
object ExtSync {

    const val DEFAULT_LIMIT = 40

    /** 静默触发的最小间隔；界面批次会换成前台/推送触发，这里只防止每次进收件箱都全量跑 */
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val PREFS = "otsmail_ext"
    private const val KEY_LAST = "last_sync_at"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    /**
     * 每完成一轮同步 +1。收件箱页把它放进 LaunchedEffect 的 key 里，
     * 后台静默同步结束后自动重读本地库，不用切页才看到新邮件。
     */
    var rev by mutableIntStateOf(0)
        private set

    private data class AccountResult(val fetched: Int, val archived: Int)

    /** 登录后调用：节流 + 不占 UI 线程，失败只写本地/服务端状态，不弹提示 */
    fun maybeSync(context: Context) {
        if (BuildConfig.DEMO) return
        if (Api.token.isEmpty() || Api.base.isEmpty()) return

        val app = context.applicationContext
        ExtMailStore.init(app)

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last < INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return

        scope.launch {
            try {
                runOnce(app)
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            } catch (e: Throwable) {
                // 拉取层不弹提示：写最后一次时间，避免网络差时每次进页面都重试
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            } finally {
                running.set(false)
            }
        }
    }

    suspend fun runOnce(context: Context, limit: Int = DEFAULT_LIMIT): ExtSyncReport =
        withContext(Dispatchers.IO) {
            try {
                runOnceInner(context, limit)
            } finally {
                // 无论成功失败都通知界面重读本地库：失败原因也要能立刻显示出来
                rev++
            }
        }

    private suspend fun runOnceInner(context: Context, limit: Int): ExtSyncReport =
        withContext(Dispatchers.IO) {
            ExtMailStore.init(context.applicationContext)

            val accounts = try {
                Api.extAccounts()
            } catch (e: Throwable) {
                return@withContext ExtSyncReport(failed = 1, errors = listOf("读取账户失败：" + ExtSyncError.reason(e)))
            }

            var total = 0
            var fetched = 0
            var archived = 0
            var failed = 0
            val errors = mutableListOf<String>()

            for (i in 0 until accounts.length()) {
                val o = accounts.optJSONObject(i) ?: continue
                val extId = o.optLong("ext_id", 0L)
                if (extId <= 0) continue
                total++

                try {
                    val result = syncAccount(o, extId, limit)
                    fetched += result.fetched
                    archived += result.archived
                } catch (e: Throwable) {
                    // 单账户失败不影响其他账户
                    val reason = ExtSyncError.reason(e)
                    failed++
                    errors += "#$extId $reason"
                    ExtMailStore.markError(extId, reason)
                    runCatching { Api.extSyncError(extId, reason, o.optLong("uid_validity", 0L)) }
                }
            }

            ExtSyncReport(accounts = total, fetched = fetched, archived = archived, failed = failed, errors = errors)
        }

    private suspend fun syncAccount(account: JSONObject, extId: Long, limit: Int): AccountResult {
        val address = account.optString("address")

        // 口令只在这一轮内存里存在，连接关掉即被回收；不打印、不落盘
        val cred = Api.extCredential(extId)
        val secret = cred.optString("secret")
        if (secret.isEmpty()) throw IllegalStateException("服务端没有返回可用的授权码")

        val providerId = cred.optString("provider").ifEmpty { account.optString("provider") }
        val provider = ExtProviders.byId(providerId)
        val host = cred.optString("imapHost").ifEmpty { account.optString("imap_host") }
        val port = cred.optInt("imapPort", 0).takeIf { it > 0 } ?: account.optInt("imap_port", 993)
        val server = ExtServer(
            host = host,
            port = port,
            secure = port == 993,
            needsId = provider?.imap?.needsId == true,
        )

        // 本机游标优先；本机没有（换机）时才用服务端归档时回写的游标
        val local = ExtMailStore.cursor(extId)
        var uidValidity = local?.uidValidity ?: account.optLong("uid_validity", 0L)
        var lastUid = local?.lastUid ?: account.optLong("last_uid", 0L)

        var processed = 0
        var archived = 0

        ExtImapClient(server, address, secret).use { imap ->
            val box = imap.login()
            if (uidValidity > 0 && box.uidValidity > 0 && box.uidValidity != uidValidity) {
                // 邮箱被服务端重建过：旧 UID 全部作废，游标从头开始（下一封归档会把新 UIDVALIDITY 写回服务端）
                lastUid = 0
                ExtMailStore.markError(extId, "邮箱 UIDVALIDITY 已变化，拉取游标重置为 0")
            }
            if (box.uidValidity > 0) uidValidity = box.uidValidity

            // 首次同步只取最近 limit 封，避免新绑定就把几千封历史全拉下来
            val start = if (lastUid > 0) lastUid + 1 else maxOf(1L, box.uidNext - limit)
            val uids = imap.listUids(start)

            for (uid in uids) {
                if (processed >= limit) break
                val fetchedMail = imap.fetch(uid)
                val parsed = MimeParser.parse(fetchedMail.raw, fetchedMail.internalDateMs)
                val messageId = parsed.messageId.ifBlank { "uid:$uidValidity:$uid" }
                val attachmentsJson = attArray(parsed.attachments, withData = false).toString()

                val ok = archiveToServer(extId, uid, uidValidity, parsed, messageId, fetchedMail.seen)

                ExtMailStore.upsert(
                    ExtLocalMail(
                        extId = extId,
                        uid = uid,
                        uidValidity = uidValidity,
                        messageId = messageId,
                        subject = parsed.subject,
                        fromName = parsed.fromName,
                        fromAddr = parsed.fromAddr,
                        toName = parsed.toName,
                        toAddr = parsed.toAddr,
                        cc = parsed.cc,
                        dateMs = parsed.dateMs,
                        seen = fetchedMail.seen,
                        snippet = MimeParser.snippet(parsed.text, parsed.html),
                        bodyText = parsed.text,
                        bodyHtml = parsed.html,
                        attachmentsJson = attachmentsJson,
                    ),
                )

                lastUid = uid
                ExtMailStore.saveCursor(extId, address, uidValidity, lastUid, box.uidNext)
                processed++
                if (ok) archived++
            }

            val hadError = ExtMailStore.cursor(extId)?.lastError.orEmpty().isNotEmpty()
            ExtMailStore.saveCursor(extId, address, uidValidity, lastUid, box.uidNext)
            // 上一轮失败过、这轮成功：顺手清掉服务端的 last_error，否则界面会一直挂着旧错误
            if (hadError) runCatching { Api.extSyncError(extId, "", uidValidity) }
        }

        return AccountResult(processed, archived)
    }

    /** 归档到服务端；返回服务端是否是新插入（重复归档也会返回成功） */
    private suspend fun archiveToServer(
        extId: Long,
        uid: Long,
        uidValidity: Long,
        parsed: ParsedMail,
        messageId: String,
        seen: Boolean,
    ): Boolean {
        val body = JSONObject().apply {
            put("extId", extId)
            put("uid", uid)
            put("uidValidity", uidValidity)
            put("date", parsed.dateMs)
            put("seen", seen)
            put("subject", parsed.subject)
            put("fromName", parsed.fromName)
            put("fromAddr", parsed.fromAddr)
            put("toName", parsed.toName)
            put("toAddr", parsed.toAddr)
            put("cc", parsed.cc)
            put("messageId", messageId)
            put("snippet", MimeParser.snippet(parsed.text, parsed.html))
            put("text", parsed.text)
            put("html", parsed.html)
            put("attachments", attArray(parsed.attachments, withData = true))
        }
        val saved = Api.archiveExtMail(body)
        return saved.optBoolean("inserted", false)
    }

    private fun attArray(list: List<MimeAttachment>, withData: Boolean): JSONArray {
        val arr = JSONArray()
        for (att in list) {
            val o = JSONObject()
                .put("filename", att.filename)
                .put("mime", att.mimeType)
                .put("size", att.size)
            if (withData && att.data != null) {
                o.put("dataB64", Base64.getEncoder().encodeToString(att.data))
            }
            arr.put(o)
        }
        return arr
    }
}

/** 拉取失败文案：区分是连不上、认证失败、还是服务器没开 IMAP，方便用户自己修 */
object ExtSyncError {
    fun reason(e: Throwable): String = when (e) {
        is ExtImapException -> textOf(e.kind) + "：" + (e.message.orEmpty().ifEmpty { "无详细信息" })
        is ApiException -> e.message
        else -> friendlyError(e).ifEmpty { e.message.orEmpty().ifEmpty { "未知错误" } }
    }

    fun textOf(kind: ExtProbeFailure): String = when (kind) {
        ExtProbeFailure.HOST_UNREACHABLE -> "连不上收件服务器"
        ExtProbeFailure.TIMEOUT -> "收件服务器响应超时"
        ExtProbeFailure.TLS_FAILED -> "TLS 加密连接失败"
        ExtProbeFailure.AUTH_FAILED -> "认证失败（授权码错误或已失效）"
        ExtProbeFailure.IMAP_DISABLED -> "邮箱没有开启 IMAP"
        ExtProbeFailure.SMTP_DISABLED -> "邮箱没有开启 SMTP"
        ExtProbeFailure.UNKNOWN -> "拉取失败"
    }
}
