package com.otscup.otsmail.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.otscup.otsmail.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 本轮真正新插入本地的邮件：IDLE/推送触发时用来弹通知（普通同步忽略） */
    val newMails: List<ExtLocalMail> = emptyList(),
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

    private const val TAG = "ExtSync"

    /** 静默触发的最小间隔；界面批次会换成前台/推送触发，这里只防止每次进收件箱都全量跑 */
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val PREFS = "otsmail_ext"
    private const val KEY_LAST = "last_sync_at"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    /** 同一时刻只跑一轮：IDLE 多个账户同时被唤醒时串行执行，避免重复拉取同一批 UID */
    private val syncMutex = Mutex()

    /**
     * 每完成一轮同步 +1。收件箱页把它放进 LaunchedEffect 的 key 里，
     * 后台静默同步结束后自动重读本地库，不用切页才看到新邮件。
     */
    var rev by mutableIntStateOf(0)
        private set

    /** 本地库被写操作改过（已读/星标/删除/发送成功）时通知列表重读；不触发网络同步。 */
    fun changed() {
        rev++
    }

    private data class AccountResult(
        val fetched: Int,
        val archived: Int,
        val newMails: List<ExtLocalMail> = emptyList(),
    )

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

    suspend fun runOnce(
        context: Context,
        limit: Int = DEFAULT_LIMIT,
        /** >0 时只同步这一个第三方账户（IDLE 唤醒时用，避免一封新邮件让所有账户重登 IMAP） */
        onlyExtId: Long = 0L,
    ): ExtSyncReport =
        withContext(Dispatchers.IO) {
            try {
                syncMutex.withLock { runOnceInner(context, limit, onlyExtId) }
            } finally {
                // 无论成功失败都通知界面重读本地库：失败原因也要能立刻显示出来
                rev++
            }
        }

    private suspend fun runOnceInner(context: Context, limit: Int, onlyExtId: Long): ExtSyncReport =
        withContext(Dispatchers.IO) {
            ExtMailStore.init(context.applicationContext)
            // 归档连续失败计数按账户落在这里：进程被杀后重试次数不重置，避免靠重启绕过上限
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
            val newMails = mutableListOf<ExtLocalMail>()

            for (i in 0 until accounts.length()) {
                val o = accounts.optJSONObject(i) ?: continue
                val extId = o.optLong("ext_id", 0L)
                if (extId <= 0) continue
                if (onlyExtId > 0 && extId != onlyExtId) continue
                total++

                try {
                    val result = syncAccount(o, extId, limit, prefs)
                    fetched += result.fetched
                    archived += result.archived
                    newMails += result.newMails
                } catch (e: Throwable) {
                    // 单账户失败不影响其他账户；这一轮连登录/列 UID 都没过，属于完全失败
                    val reason = "完全失败：" + ExtSyncError.reason(e)
                    Log.w(TAG, "账户 #$extId 同步中断：$reason", e)
                    failed++
                    errors += "#$extId $reason"
                    ExtMailStore.markError(extId, reason)
                    runCatching { Api.extSyncError(extId, reason, o.optLong("uid_validity", 0L)) }
                }
            }

            ExtSyncReport(
                accounts = total,
                fetched = fetched,
                archived = archived,
                failed = failed,
                errors = errors,
                newMails = newMails,
            )
        }

    private suspend fun syncAccount(
        account: JSONObject,
        extId: Long,
        limit: Int,
        prefs: android.content.SharedPreferences,
    ): AccountResult {
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
        // 游标回退（UIDVALIDITY 变化 / 首次绑定）：这一轮是历史重扫，旧邮件不能当成新邮件弹通知
        var fullScan = false
        val newMails = mutableListOf<ExtLocalMail>()
        // 单封失败不终止整轮：记下跳过数与原因，游标推过这一封，避免同一封反复卡住
        var skipped = 0
        val skippedReasons = mutableListOf<String>()
        // 归档失败：游标必须停在这一封，下一轮重试（达到连续失败上限才允许越过）
        var archiveRetry = false
        var archiveRetryReason = ""

        ExtImapClient(server, address, secret).use { imap ->
            val box = imap.login()
            if (uidValidity > 0 && box.uidValidity > 0 && box.uidValidity != uidValidity) {
                // 邮箱被服务端重建过：旧 UID 全部作废，游标从头开始（下一封归档会把新 UIDVALIDITY 写回服务端）
                lastUid = 0
                fullScan = true
                ExtMailStore.markError(extId, "邮箱 UIDVALIDITY 已变化，拉取游标重置为 0")
            }
            if (box.uidValidity > 0) uidValidity = box.uidValidity

            // 首次同步只取最近 limit 封，避免新绑定就把几千封历史全拉下来
            val start = if (lastUid > 0) lastUid + 1 else {
                fullScan = true
                maxOf(1L, box.uidNext - limit)
            }
            // IMAP 的 `n:*` 在 n 大于最大 UID 时会返回最后一封，没有新邮件就直接跳过，免得每轮重拉一封
            val uids = if (box.uidNext > 0 && start >= box.uidNext) emptyList() else imap.listUids(start)
            Log.i(TAG, "同步开始 ext=$extId start=$start uidNext=${box.uidNext} 待处理=${uids.size}")

            for (uid in uids) {
                if (processed + skipped >= limit) break
                try {
                    val fetchedMail = imap.fetch(uid)
                    val parsed = MimeParser.parse(fetchedMail.raw, fetchedMail.internalDateMs)
                    val messageId = parsed.messageId.ifBlank { "uid:$uidValidity:$uid" }
                    val attachmentsJson = attArray(parsed.attachments, withData = false).toString()

                    // 归档是「这封算同步完成」的前置条件：服务端没存下就不推进游标，
                    // 否则本地与服务端同时缺这封，而上游 IMAP 已经被越过，谁也补不回来
                    var archiveSkipped = false
                    try {
                        archiveToServer(extId, uid, uidValidity, parsed, messageId, fetchedMail.seen)
                        clearArchiveFail(prefs, extId)
                    } catch (e: Throwable) {
                        // 连接断了（IMAP 侧）照旧结束整轮；其余归档错误按「保留游标重试」处理
                        if (isConnectionBroken(e)) throw e
                        val tries = bumpArchiveFail(prefs, extId, uid)
                        val reason = ExtSyncError.reason(e)
                        when (ExtArchiveRetryPolicy.decide(failed = true, attempts = tries)) {
                            // 到上限：越过这一封，避免整条流水线被一封邮件永久卡死；
                            // 错误状态里写明「已跳过」，上游服务器上原邮件仍在
                            ExtArchiveAction.SKIP -> {
                                clearArchiveFail(prefs, extId)
                                archiveSkipped = true
                                skipped++
                                skippedReasons += "#$uid 归档连续失败 ${tries} 次已跳过（服务端未归档，上游仍保留）"
                                Log.w(TAG, "UID $uid 连续归档失败 $tries 次，跳过：$reason", e)
                            }
                            // 未到上限：游标停在这封，下一轮重试
                            else -> {
                                archiveRetry = true
                                archiveRetryReason = "归档失败（第 $tries/${ExtArchiveRetryPolicy.MAX_ATTEMPTS} 次），" +
                                    "游标停在第 $uid 封，下一轮重试：$reason"
                                Log.w(TAG, "UID $uid 归档失败，保留游标：$reason", e)
                            }
                        }
                    }
                    if (archiveRetry) break
                    if (archiveSkipped) {
                        lastUid = uid
                        ExtMailStore.saveCursor(extId, address, uidValidity, lastUid, box.uidNext)
                        continue
                    }

                    val localMail = ExtLocalMail(
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
                    )
                    val inserted = ExtMailStore.upsert(localMail)
                    if (inserted) newMails += localMail.copy(localId = ExtMailStore.lastLocalId(extId, messageId))

                    lastUid = uid
                    ExtMailStore.saveCursor(extId, address, uidValidity, lastUid, box.uidNext)
                    processed++
                    archived++
                } catch (e: Throwable) {
                    // 连接断了后面每封都会同样失败：游标停在上一封，下一轮从那封续拉
                    if (isConnectionBroken(e)) throw e
                    skipped++
                    val reason = ExtSyncError.reason(e)
                    skippedReasons += "#$uid $reason"
                    Log.w(TAG, "UID $uid 处理失败已跳过：$reason", e)
                    // 游标越过这一封，否则同一封会每轮都失败、永远拉不到后面的邮件
                    lastUid = uid
                    ExtMailStore.saveCursor(extId, address, uidValidity, lastUid, box.uidNext)
                }
            }

            val hadError = ExtMailStore.cursor(extId)?.lastError.orEmpty().isNotEmpty()
            ExtMailStore.saveCursor(extId, address, uidValidity, lastUid, box.uidNext)
            // 结算这一轮：部分失败要说清同步了几封、跳过几封；全部失败才算整轮失败
            val status = when {
                // 归档失败优先报：这一轮没有越过那封，游标停着等下一轮重试
                archiveRetry -> archiveRetryReason
                skipped == 0 -> ""
                processed == 0 -> "完全失败：$skipped 封全部跳过，" + skippedReasons.first()
                else -> "已同步 $processed 封，跳过 $skipped 封：" + skippedReasons.first()
            }
            Log.i(TAG, "同步结束 ext=$extId 成功=$processed 跳过=$skipped 归档=$archived")
            if (status.isNotEmpty()) {
                ExtMailStore.markError(extId, status)
                runCatching { Api.extSyncError(extId, status, uidValidity) }
            } else if (hadError) {
                // 上一轮失败过、这轮干净跑完：顺手清掉服务端 last_error，避免界面一直挂旧错误
                runCatching { Api.extSyncError(extId, "", uidValidity) }
            }
        }

        // 游标回退的重扫可能把旧邮件当“新插入”，这里直接不给通知候选
        if (fullScan && newMails.isNotEmpty()) {
            Log.i(TAG, "历史重扫 ext=$extId 新增 ${newMails.size} 封，本轮不弹通知")
            return AccountResult(processed, archived, emptyList())
        }
        return AccountResult(processed, archived, newMails)
    }

    /**
     * 连接级错误：换下一封也一定会再失败，直接结束这一轮，让游标停在上一封等下次续拉。
     * 其余异常（单封解析、归档被拒、本地落库）都只跳过这一封。
     */
    private fun isConnectionBroken(e: Throwable): Boolean = when (e) {
        is ExtImapException -> when (e.kind) {
            ExtProbeFailure.TIMEOUT, ExtProbeFailure.HOST_UNREACHABLE, ExtProbeFailure.TLS_FAILED -> true
            else -> false
        }
        is java.io.IOException -> true
        else -> false
    }

    /** 归档连续失败计数：记 uid 与次数，换一封就从 1 重新计 */
    private fun bumpArchiveFail(prefs: android.content.SharedPreferences, extId: Long, uid: Long): Int {
        val key = "archive_fail_$extId"
        val count = ExtArchiveRetryPolicy.nextAttempts(prefs.getString(key, ""), uid)
        prefs.edit().putString(key, "$uid:$count").apply()
        return count
    }

    private fun clearArchiveFail(prefs: android.content.SharedPreferences, extId: Long) {
        prefs.edit().remove("archive_fail_$extId").apply()
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

/**
 * 归档失败后游标该往哪走：成功推进；失败未达上限保留并重试；达上限才跳过并写明确错误。
 * 抽成纯函数是为了能单测——这段判定错了会直接丢邮件或把账户永久卡死。
 */
internal enum class ExtArchiveAction { ADVANCE, RETRY, SKIP }

internal object ExtArchiveRetryPolicy {

    /** 同一封连续归档失败到这个次数才允许越过，避免一封坏邮件把整个账户卡死 */
    const val MAX_ATTEMPTS = 5

    /** 计数存的是 `uid:count`：同一封累加，换一封（或数据损坏）从 1 重新计 */
    fun nextAttempts(stored: String?, uid: Long): Int {
        val parts = stored.orEmpty().split(':')
        val same = parts.size == 2 && parts[0].toLongOrNull() == uid
        return if (same) (parts[1].toIntOrNull() ?: 0) + 1 else 1
    }

    fun decide(failed: Boolean, attempts: Int, maxAttempts: Int = MAX_ATTEMPTS): ExtArchiveAction = when {
        !failed -> ExtArchiveAction.ADVANCE
        attempts >= maxAttempts -> ExtArchiveAction.SKIP
        else -> ExtArchiveAction.RETRY
    }
}

/** 拉取失败文案：区分是连不上、认证失败、还是服务器没开 IMAP，方便用户自己修 */
object ExtSyncError {
    fun reason(e: Throwable): String = when (e) {
        is ExtImapException -> textOf(e.kind) + "：" + (e.message.orEmpty().ifEmpty { "无详细信息" })
        is ExtSmtpException -> "发信失败：" + (e.message.orEmpty().ifEmpty { "无详细信息" })
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
