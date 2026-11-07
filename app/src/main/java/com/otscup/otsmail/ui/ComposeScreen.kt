package com.otscup.otsmail.ui

import android.provider.OpenableColumns
import android.text.Html
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.otscup.otsmail.R
import com.otscup.otsmail.data.AiConsent
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.Draft
import com.otscup.otsmail.data.Drafts
import com.otscup.otsmail.data.ExtSend
import com.otscup.otsmail.data.aiErrorText
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.data.ScheduledMail
import com.otscup.otsmail.data.ScheduledSendStore
import com.otscup.otsmail.data.ScheduledSendTime
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.schedule.ScheduledSendScheduler
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 写信页。草稿保护：
 * - 输入 800ms 防抖自动存本机草稿（按账号隔离，见 Drafts）；
 * - onStop（切后台）立即存一次；
 * - 发送失败不清空输入，保留正文并提示「已存草稿，可稍后重试」+「重试发送」；
 * - 成功发送删除草稿；重新进写信页若本账号有未完成草稿，顶部提示「继续编辑」；
 * - 标题栏「草稿」进入草稿列表。
 */
@Composable
fun ComposeScreen(
    accountId: Long,
    to: String,
    subject: String,
    quoted: String = "",
    /** 回复全部带入的原 cc；新写/普通回复为空 */
    cc: List<String> = emptyList(),
    /** 标题提示：replyAll=回复全部，forward=转发，空=按是否有引用判断 */
    mode: String = "",
    /** 右滑回复时列表只给了 emailId：原文没缓存时在这里后台补引用，不阻塞进写信页 */
    quoteEmailId: Long? = null,
    draftId: String? = null,
    /** 智能回复点选后带进来的正文；普通新建/回复为空 */
    initialBody: String = "",
    /**
     * >0 表示这封信用该第三方邮箱自己的 SMTP 直连发出，而不是交给 otsmail 后端代发。
     * 此时发件人固定为 [extAddress]，不显示 otsmail 的账号切换、不追加 otsmail 签名。
     */
    extId: Long = 0L,
    extAddress: String = "",
    onOpenDrafts: () -> Unit = {},
    /** 自动保存产出的草稿 id 同步给调用方：401 时路由会被重建，靠它把写信页原样接回来 */
    onDraftId: (String) -> Unit = {},
    onFinished: (sent: Boolean) -> Unit,
) {
    val t = OT.type
    val c = OT.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 草稿在 IO 线程读，不在组合阶段同步读文件（主线程 IO 会卡首帧）。
    // draftLoaded 为 false 期间禁止落盘，避免草稿还没加载完就被空内容覆盖。
    var draftLoaded by remember { mutableStateOf(draftId == null) }
    var draftIdState by remember { mutableStateOf(draftId ?: UUID.randomUUID().toString()) }
    var toTags by remember {
        mutableStateOf(to.split(",", ";", " ").map { it.trim() }.filter { it.contains("@") })
    }
    var toInput by remember {
        mutableStateOf(if (draftId == null && toTags.isEmpty() && to.isNotBlank()) to else "")
    }
    // 收件人格式错误：明确提示，绝不静默吞掉输入的地址
    var toError by remember { mutableStateOf("") }
    var ccError by remember { mutableStateOf("") }
    var bccError by remember { mutableStateOf("") }
    // 收件人补全：候选只来自邮件历史（收件箱发件人 + 已发送收件人）与联系人簿，
    // 首次输入时加载一次进内存，之后每次敲键只做内存过滤，不再按查询请求服务端。
    var toSuggest by remember { mutableStateOf(listOf<RecipientCandidate>()) }
    var toSuggestIndex by remember { mutableStateOf(0) }
    var ccTags by remember { mutableStateOf(cc) }
    var bccTags by remember { mutableStateOf(listOf<String>()) }
    var ccInput by remember { mutableStateOf("") }
    var bccInput by remember { mutableStateOf("") }
    var subjectText by remember { mutableStateOf(subject) }
    var body by remember { mutableStateOf(TextFieldValue(initialBody)) }
    var quotedHtml by remember { mutableStateOf(quoted) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var sendFailed by remember { mutableStateOf(false) }
    var draftSaved by remember { mutableStateOf(false) }
    var restoreNote by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(initialBody.isNotBlank()) }
    // AI 润色：替换前留一份快照，支持一次「撤销替换」；无选区时润色全文
    var polishBusy by remember { mutableStateOf(false) }
    var polishNote by remember { mutableStateOf("") }
    var polishUndo by remember { mutableStateOf<TextFieldValue?>(null) }
    var polishConsent by remember { mutableStateOf(false) }
    // 发送成功后禁止再落盘：否则 onDispose 会把刚删掉的草稿又写回来
    var sentOk by remember { mutableStateOf(false) }
    // 发送后的可撤销窗口（8 秒）：倒计时结束才真正调 /email/send。
    // 倒计时开始时已把内容落草稿，进程被杀后重进仍是草稿；离开写信页则立即发送。
    var pendingSend by remember { mutableStateOf<PendingEmail?>(null) }
    var pendingLeft by remember { mutableIntStateOf(0) }
    var delivering by remember { mutableStateOf(false) }
    // 已排入定时发送：与 sentOk 一样要阻止 onDispose/防抖把删掉的草稿又写回来
    var scheduledOk by remember { mutableStateOf(false) }
    var scheduleOpen by remember { mutableStateOf(false) }
    val sendJob = remember { mutableStateOf<Job?>(null) }
    // 右滑回复：缓存没命中时先开页，这里再补原文引用；拿不到就保持无引用，不阻塞发送
    LaunchedEffect(quoteEmailId) {
        val id = quoteEmailId ?: return@LaunchedEffect
        if (quotedHtml.isNotEmpty()) return@LaunchedEffect
        val d = MailCache.detailMemory(id)?.first
            ?: MailCache.detailDisk(id)?.first
            ?: runCatching { Api.detail(id) }.getOrNull()
        if (d != null && quotedHtml.isEmpty()) {
            quotedHtml = buildQuoteHtml(d.name.ifEmpty { d.from }, d.content)
            touched = true
        }
    }
    val attachments = remember { mutableListOf<JSONObject>() }
    var picks by remember { mutableStateOf(emptyList<String>()) }
    val isExt = extId > 0
    // 第三方邮箱发信：签名是 otsmail 账号的，不能塞进别人的 SMTP 发件里
    val signature = remember { if (isExt) "" else Session(ctx).signature }
    // 草稿隔离键：第三方写信页用负的 extId 单独命名空间，
    // 避免它混进 otsmail 草稿列表（从草稿箱点进来会丢掉 extId，变成后端代发）
    val draftKey = if (isExt) -extId else accountId

    var fromId by remember { mutableStateOf(accountId) }
    var fromEmail by remember { mutableStateOf(if (isExt) extAddress else "") }
    var accounts by remember { mutableStateOf(listOf<Pair<Long, String>>()) }
    var pickingFrom by remember { mutableStateOf(false) }
    var showCc by remember { mutableStateOf(cc.isNotEmpty()) }
    var showBcc by remember { mutableStateOf(false) }
    // 新建写信页时提示恢复本账号最近一份未完成草稿（不自动覆盖用户正在写的空白页）
    var offer by remember { mutableStateOf<Draft?>(null) }

    LaunchedEffect(Unit) {
        onDraftId(draftIdState)
        // 第三方邮箱写信：发件人固定是绑定地址，不拉 otsmail 账号列表（结果也不会给后端用）
        if (!isExt) {
            runCatching { Api.accounts() }.getOrNull()?.let { list ->
                accounts = list
                fromEmail = list.firstOrNull { it.first == fromId }?.second ?: list.firstOrNull()?.second ?: ""
            }
        }
        if (draftId == null) {
            val latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Drafts.latest(ctx, draftKey)
            }
            if (latest != null && !latest.isEmpty()) offer = latest
        }
    }

    suspend fun addFiles(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val remainingSlots = 10 - picks.size
        if (remainingSlots <= 0) {
            Toast.makeText(ctx, "单封最多 10 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        // 单封累计 10MB：base64 之后请求体会再涨约三分之一，超过就明确报错而不是悄悄丢掉
        val usedAtStart = attachments.sumOf { it.optLong("size", 0L) }
        var used = usedAtStart
        var overLimit = false
        val loaded = withContext(Dispatchers.IO) {
            val out = ArrayList<JSONObject>()
            uris.take(remainingSlots).forEach { uri ->
                if (overLimit) return@forEach
                runCatching {
                    // 读流和 base64 都在 IO 线程，避免大附件卡住写信页
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching
                    if (bytes.size > 5 * 1024 * 1024) return@runCatching
                    if (used + bytes.size > 10L * 1024 * 1024) {
                        overLimit = true
                        return@runCatching
                    }
                    val name = ctx.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
                        ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
                    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                    out.add(
                        JSONObject()
                            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            .put("filename", name)
                            .put("size", bytes.size)
                            .put("contentType", mime),
                    )
                    used += bytes.size
                }
            }
            out
        }
        if (loaded.isNotEmpty()) {
            attachments.addAll(loaded)
            picks = picks + loaded.map { it.optString("filename") }
            touched = true
        }
        if (overLimit) {
            Toast.makeText(
                ctx,
                "附件累计不能超过 10MB，超出的文件没有添加",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { addFiles(uris) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { addFiles(uris) }
    }

    /** 当前输入 → 草稿；空内容不落盘（顺手删掉旧草稿）。文件读写都在 IO 线程。 */
    suspend fun persist(force: Boolean = false): Boolean {
        if (sentOk || scheduledOk) return false
        // 草稿还没加载完就别写：否则空内容会把正在恢复的草稿覆盖掉
        if (!draftLoaded) return false
        if (!touched && !force) return false
        val atts = JSONArray()
        attachments.forEach { atts.put(it) }
        val draft = Draft(
            id = draftIdState,
            accountId = draftKey,
            fromId = fromId,
            toTags = toTags,
            toInput = toInput,
            ccTags = ccTags,
            ccInput = ccInput,
            bccTags = bccTags,
            bccInput = bccInput,
            subject = subjectText,
            body = body.text,
            selStart = body.selection.start,
            selEnd = body.selection.end,
            quoted = quotedHtml,
            attachmentsJson = atts.toString(),
            attachmentsDropped = false,
            updatedAt = 0L,
        )
        if (draft.isEmpty()) {
            withContext(Dispatchers.IO) { Drafts.delete(ctx, draftIdState) }
            return false
        }
        val saved = withContext(Dispatchers.IO) { Drafts.save(ctx, draft) }
        draftIdState = saved.id
        onDraftId(saved.id)
        if (saved.attachmentsDropped) restoreNote = "部分附件超过 2MB，未能完整保存"
        return true
    }

    fun applyDraft(d: Draft) {
        draftIdState = d.id
        onDraftId(d.id)
        toTags = d.toTags
        toInput = d.toInput
        ccTags = d.ccTags
        ccInput = d.ccInput
        bccTags = d.bccTags
        bccInput = d.bccInput
        showCc = d.ccTags.isNotEmpty() || d.ccInput.isNotBlank()
        showBcc = d.bccTags.isNotEmpty() || d.bccInput.isNotBlank()
        subjectText = d.subject
        val txt = d.body
        val start = d.selStart
        val end = d.selEnd
        body = TextFieldValue(
            txt,
            if (start in 0..txt.length && end in 0..txt.length) TextRange(start, end) else TextRange(txt.length),
        )
        quotedHtml = d.quoted
        if (d.fromId > 0) fromId = d.fromId
        val arr = runCatching { JSONArray(d.attachmentsJson) }.getOrNull() ?: JSONArray()
        val atts = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("content").isNotEmpty() }
        attachments.clear()
        atts.forEach(attachments::add)
        picks = atts.map { it.optString("filename") }
        restoreNote = if (d.attachmentsDropped) "部分附件超过 2MB，未能完整保存" else ""
        touched = true
        offer = null
    }

    // 从草稿列表点进来：文件读取放 IO，读完再套用；期间 persist 被 draftLoaded 挡住
    LaunchedEffect(draftId) {
        val id = draftId ?: return@LaunchedEffect
        val d = withContext(Dispatchers.IO) { Drafts.get(ctx, id) }
        if (d != null) applyDraft(d)
        draftLoaded = true
    }

    // 页面销毁后仍要把最后一笔草稿写完，所以不能用 rememberCoroutineScope（它会被取消）
    val persistScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /**
     * 真正提交一封邮件：第三方账户走自己的 SMTP 直连（凭据只在客户端解密使用），
     * 其它账户照旧交给 otsmail 后端 /email/send。
     */
    suspend fun submit(p: PendingEmail) {
        if (p.extId > 0) {
            ExtSend.send(
                ctx,
                extId = p.extId,
                from = p.extAddress,
                to = p.to,
                cc = p.cc,
                bcc = p.bcc,
                subject = p.subject,
                text = p.text,
                html = p.html,
                attachments = p.attachments,
            )
        } else {
            Api.send(
                p.accountId, p.to, p.subject, p.text, p.html, p.attachments,
                cc = p.cc,
                bcc = p.bcc,
            )
        }
    }

    /** 离开写信页时的补发：不再写界面状态，失败也保留草稿，重进能接回来。 */
    suspend fun deliverQuietly(p: PendingEmail) {
        runCatching { submit(p) }
            .onSuccess {
                withContext(Dispatchers.IO) { Drafts.delete(ctx, p.draftId) }
                MailSync.changed()
            }
    }

    // 切后台（onStop）立即落盘一次，不等 800ms 防抖
    val lifecycleOwner = LocalLifecycleOwner.current
    val saveNow = rememberUpdatedState { persistScope.launch { persist() } }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveNow.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 撤销窗口内离开写信页（取消/去草稿箱/切页）按「仍然发送」处理；
            // 已经在发（delivering）就不重复提交，等它自己发完。
            val queued = pendingSend
            if (queued != null) {
                sendJob.value?.cancel()
                sendJob.value = null
                pendingSend = null
                if (!delivering) persistScope.launch { deliverQuietly(queued) }
            } else {
                // 离开写信页（去草稿箱/被 401 拦去登录）也落盘，页面被重建时能原样接回来。
                // 有 queued 时不再落盘：草稿在进窗口那一刻已经写过，这时再写会把补发成功删掉的草稿又写回来
                saveNow.value()
            }
        }
    }

    // 输入 800ms 防抖自动保存：每次敲键都会取消上一个计时器并重新开始
    LaunchedEffect(
        toTags, toInput, ccTags, ccInput, bccTags, bccInput, subjectText, body.text, picks, fromId,
    ) {
        if (!touched) return@LaunchedEffect
        delay(800)
        persist()
    }

    val emailRe = remember { Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") }
    val addressSplit = remember { Regex("[,;\\s]+") }

    /** 中文输入法/中文应用粘贴时常见全角逗号、分号和全角空格，统一成半角再拆分。 */
    fun normalizeAddressText(raw: String): String =
        raw.replace('，', ',').replace('；', ';').replace('\u3000', ' ')

    /** 拆分一段文本里的多个地址；返回（合法，非法），合法去重保序。 */
    fun parseAddresses(raw: String): Pair<List<String>, List<String>> {
        val good = LinkedHashSet<String>()
        val bad = ArrayList<String>()
        normalizeAddressText(raw).split(addressSplit).map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            if (emailRe.matches(it)) good.add(it) else bad.add(it)
        }
        return good.toList() to bad
    }

    /**
     * 把一段输入锁定成收件人 tag。
     * 合法地址按逗号/分号/空白拆分，一次可锁多个；非法段不吞掉，留在输入框并给出提示。
     */
    fun commitAddresses(
        raw: String,
        tags: List<String>,
        setTags: (List<String>) -> Unit,
        setInput: (String) -> Unit,
        setError: (String) -> Unit,
        keepRest: String? = null,
    ) {
        val (good, bad) = parseAddresses(raw)
        if (good.isNotEmpty()) {
            val merged = tags.toMutableList()
            good.forEach { if (!merged.contains(it)) merged.add(it) }
            setTags(merged)
            touched = true
        }
        if (bad.isNotEmpty()) {
            setError("邮箱格式不正确：${bad.joinToString("、")}")
            setInput(keepRest ?: bad.joinToString(", "))
        } else {
            setError("")
            setInput(keepRest.orEmpty())
        }
    }

    /**
     * 输入变化：遇到分隔符（Enter 之外的逗号/分号/空格）或整段粘贴多地址时立即锁定；
     * 逐字输入时只把最后一个未写完的片段留在输入框，不打断输入。
     */
    fun onAddressInput(
        raw: String,
        tags: List<String>,
        setTags: (List<String>) -> Unit,
        setInput: (String) -> Unit,
        setError: (String) -> Unit,
    ) {
        val v = normalizeAddressText(raw)
        val endsWithDelimiter = v.isNotEmpty() && v.last().let { it == ',' || it == ';' || it.isWhitespace() }
        val parts = v.split(addressSplit).map { it.trim() }.filter { it.isNotEmpty() }
        when {
            endsWithDelimiter -> commitAddresses(v, tags, setTags, setInput, setError)
            parts.size > 1 && parts.all { emailRe.matches(it) } ->
                commitAddresses(v, tags, setTags, setInput, setError)
            parts.size > 1 -> commitAddresses(
                parts.dropLast(1).joinToString(","), tags, setTags, setInput, setError,
                keepRest = parts.last(),
            )
            else -> {
                setInput(v)
                setError("")
            }
        }
    }

    fun addSuggest(index: Int) {
        val item = toSuggest.getOrNull(index) ?: return
        if (toTags.none { it.equals(item.email, true) }) toTags = toTags + item.email
        toInput = ""
        toError = ""
        toSuggest = emptyList()
        toSuggestIndex = 0
        touched = true
    }

    /** Enter / IME 完成：完整地址优先锁定；只输了半截就选高亮候选；都没有才明确报错。 */
    fun commitTypedTo() {
        val typed = toInput.trim()
        when {
            emailRe.matches(typed) -> commitAddresses(typed, toTags, { toTags = it }, { toInput = it }, { toError = it })
            toSuggest.isNotEmpty() -> addSuggest(toSuggestIndex)
            typed.isNotEmpty() -> toError = "邮箱格式不正确：$typed"
        }
    }

    // 收件人补全：候选只来自邮件历史（收件箱发件人 + 已发送收件人），首次输入时加载一次，
    // 之后敲键只在内存里过滤，不按每次输入请求服务端，也不接系统通讯录。
    var recipientPool by remember { mutableStateOf<List<RecipientCandidate>>(emptyList()) }
    var recipientPoolAccount by remember { mutableStateOf(-1L) }
    LaunchedEffect(toInput.isNotEmpty(), fromId) {
        if (toInput.isEmpty() || recipientPoolAccount == fromId) return@LaunchedEffect
        val sourceAccount = if (fromId > 0) fromId else accountId
        val pool = runCatching {
            loadRecipientCandidates(ctx, sourceAccount, fromEmail)
        }.getOrDefault(emptyList())
        recipientPool = pool
        recipientPoolAccount = fromId
    }
    LaunchedEffect(toInput, toTags, recipientPool) {
        val q = RecipientSuggest.querySegment(toInput)
        toSuggest = RecipientSuggest.rank(recipientPool, q, toTags)
        toSuggestIndex = 0
    }

    fun wrap(prefix: String, suffix: String) {
        val sel = body.selection
        val start = minOf(sel.start, sel.end)
        val end = maxOf(sel.start, sel.end)
        val txt = body.text
        val newText = txt.substring(0, start) + prefix + txt.substring(start, end) + suffix + txt.substring(end)
        body = TextFieldValue(newText, TextRange(start + prefix.length, start + prefix.length + (end - start)))
        touched = true
    }

    fun prefixLine(prefix: String) {
        val at = body.text.lastIndexOf('\n', (body.selection.start - 1).coerceAtLeast(0)) + 1
        val newText = body.text.substring(0, at) + prefix + body.text.substring(at)
        body = TextFieldValue(newText, TextRange(body.selection.start + prefix.length))
        touched = true
    }

    /** 插入 [文字](地址)：选中文字当标签，光标落在 https:// 上，用户直接接着输入。 */
    fun insertLink() {
        val sel = body.selection
        val start = minOf(sel.start, sel.end)
        val end = maxOf(sel.start, sel.end)
        val label = body.text.substring(start, end).ifBlank { "链接文字" }
        val url = "https://"
        val snippet = "[$label]($url)"
        val newText = body.text.substring(0, start) + snippet + body.text.substring(end)
        val urlStart = start + 1 + label.length + 2
        body = TextFieldValue(newText, TextRange(urlStart, urlStart + url.length))
        touched = true
    }

    /** 清除格式：去掉轻量标记，保留文字与链接地址对应的标签。 */
    fun clearFormat() {
        val cleaned = body.text
            .replace(Regex("\\[([^\\]\\n]+)\\]\\(([^)\\s]+)\\)"), "$1")
            .replace("**", "")
            .replace("__", "")
            .replace(Regex("^#{1,2} ", RegexOption.MULTILINE), "")
            .replace(Regex("^> ", RegexOption.MULTILINE), "")
            .replace(Regex("^[-*] ", RegexOption.MULTILINE), "")
            .replace(Regex("^\\d+\\. ", RegexOption.MULTILINE), "")
        body = TextFieldValue(cleaned, TextRange(body.selection.start.coerceAtMost(cleaned.length)))
        touched = true
    }

    fun runPolish(start: Int, end: Int, source: String, whole: Boolean) {
        polishBusy = true
        polishNote = ""
        scope.launch {
            runCatching { Api.aiPolish(source) }
                .onSuccess { out ->
                    val before = body
                    val newText = if (whole) out else before.text.substring(0, start) + out + before.text.substring(end)
                    val caret = if (whole) newText.length else start + out.length
                    body = TextFieldValue(newText, TextRange(caret))
                    polishUndo = before
                    polishNote = "已应用 AI 润色"
                    touched = true
                }
                .onFailure {
                    Toast.makeText(ctx, "润色失败：${aiErrorText(it)}", Toast.LENGTH_LONG).show()
                }
            polishBusy = false
        }
    }

    /**
     * AI 润色：有选区只润色选区，无选区润色全文；成功后替换对应文字并留一份撤销快照。
     * 失败不改动原文，只提示错误。
     */
    fun startPolish() {
        if (polishBusy) return
        val sel = body.selection
        val start = minOf(sel.start, sel.end).coerceIn(0, body.text.length)
        val end = maxOf(sel.start, sel.end).coerceIn(0, body.text.length)
        val source = if (start == end) body.text else body.text.substring(start, end)
        if (source.isBlank()) {
            Toast.makeText(ctx, "请先输入要润色的内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (AiConsent.needed(ctx)) {
            polishConsent = true
            return
        }
        runPolish(start, end, source, whole = start == end)
    }

    fun undoPolish() {
        val before = polishUndo ?: return
        body = before
        polishUndo = null
        polishNote = ""
        touched = true
    }

    /** 倒计时结束后的真实发送（带界面反馈）。 */
    suspend fun deliver(p: PendingEmail) {
        delivering = true
        runCatching { submit(p) }
            .onSuccess {
                sentOk = true
                touched = true
                withContext(Dispatchers.IO) { Drafts.delete(ctx, p.draftId) }
                MailSync.changed()
                pendingSend = null
                pendingLeft = 0
                busy = false
                onFinished(true)
            }
            .onFailure { e ->
                // 失败不清空输入：立刻把当前内容落盘，并给出重试入口
                touched = true
                val saved = persist(force = true)
                draftSaved = saved
                sendFailed = true
                pendingSend = null
                pendingLeft = 0
                busy = false
                // 401 的 friendlyError 是空串：这时只显示"发送失败"，交给全局登录失效横幅说明原因
                val reason = friendlyError(e)
                error = if (reason.isEmpty()) "发送失败" else "发送失败：$reason"
            }
        delivering = false
    }

    /** 进入撤销窗口：先倒计时，结束才真正发；窗口内发送按钮不可点，避免连点发出两封。 */
    fun queueSend(p: PendingEmail) {
        pendingSend = p
        pendingLeft = UNDO_SECONDS
        busy = true
        error = ""
        sendFailed = false
        sendJob.value?.cancel()
        sendJob.value = persistScope.launch {
            repeat(UNDO_SECONDS) {
                delay(1000)
                pendingLeft -= 1
            }
            pendingSend?.let { deliver(it) }
        }
    }

    /**
     * 直接发送：跳过剩余倒计时立即提交。
     * 连点保护：进入 delivering 前先清掉倒计时，按钮随提示条一起消失；
     * 失败仍走 deliver 的统一失败分支（落草稿 + 重试入口），不会因为跳过倒计时丢错误处理。
     */
    fun sendNow() {
        val p = pendingSend ?: return
        if (delivering) return
        sendJob.value?.cancel()
        sendJob.value = null
        pendingLeft = 0
        busy = true
        persistScope.launch { deliver(p) }
    }

    /** 撤销：取消倒计时，内容与附件原样留在写信页，草稿也还在。 */
    fun undoSend() {
        sendJob.value?.cancel()
        sendJob.value = null
        pendingSend = null
        pendingLeft = 0
        busy = false
        Toast.makeText(ctx, "已撤销发送，内容仍在写信页", Toast.LENGTH_SHORT).show()
    }

    /**
     * 发送/定时发送共用的校验与内容组装：地址格式、收件人非空、签名与 HTML 都只在这里做，
     * 两条路径不会各写一遍导致行为不一致。校验失败返回 null，错误已写进对应 error 状态。
     */
    fun collectedSend(): PendingEmail? {
        // 输入框里还没按 Enter 的地址先校验：非法就明确报错，不合规地址不会被悄悄丢掉
        if (toInput.isNotBlank()) {
            if (!emailRe.matches(toInput.trim())) {
                toError = "邮箱格式不正确：${toInput.trim()}"
                return null
            }
            commitAddresses(toInput, toTags, { toTags = it }, { toInput = it }, { toError = it })
        }
        if (ccInput.isNotBlank()) {
            if (!emailRe.matches(ccInput.trim())) {
                ccError = "邮箱格式不正确：${ccInput.trim()}"
                return null
            }
            commitAddresses(ccInput, ccTags, { ccTags = it }, { ccInput = it }, { ccError = it })
        }
        if (bccInput.isNotBlank()) {
            if (!emailRe.matches(bccInput.trim())) {
                bccError = "邮箱格式不正确：${bccInput.trim()}"
                return null
            }
            commitAddresses(bccInput, bccTags, { bccTags = it }, { bccInput = it }, { bccError = it })
        }
        val recipients = toTags
        if (recipients.isEmpty()) {
            error = "请填写收件人"
            return null
        }
        val sig = signature.trim()
        val plain = buildString {
            append(body.text)
            if (sig.isNotEmpty()) append(if (body.text.isEmpty()) sig else "\n\n$sig")
        }.ifEmpty { " " }
        // 显式声明 charset=utf-8：引用里可能带原信的旧 charset 声明，缺省声明会让收件端按错编码解
        val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>" +
            textToHtml(body.text) +
            (if (sig.isEmpty()) "" else "<p>${escapeHtml(sig).replace("\n", "<br>")}</p>") +
            quotedHtml +
            "</body></html>"
        return PendingEmail(
            accountId = fromId,
            to = recipients,
            cc = ccTags,
            bcc = bccTags,
            subject = subjectText,
            text = plain,
            html = html,
            attachments = attachments.toList(),
            draftId = draftIdState,
            extId = extId,
            extAddress = if (isExt) extAddress else "",
        )
    }

    fun send() {
        if (busy) return
        val p = collectedSend() ?: return
        busy = true
        error = ""
        sendFailed = false
        // 先落草稿再进撤销窗口：8 秒内进程被杀，重进还能从草稿接回来，不是静默丢失
        persistScope.launch {
            persist(force = true)
            queueSend(p)
        }
    }

    /**
     * 定时发送：先落草稿兜底，再写入加密的定时队列并排闹钟，最后删掉写信页草稿
     * （内容已在定时队列里，取消定时时会再转回草稿）。
     * 同一封（账号+收件人+主题+正文）重复定时只保留一条：upsert 复用旧 id 并更新闹钟。
     */
    fun scheduleSend(atMillis: Long) {
        if (busy) return
        val at = ScheduledSendTime.atLeastOneMinuteAhead(atMillis)
        val p = collectedSend() ?: return
        busy = true
        error = ""
        sendFailed = false
        persistScope.launch {
            persist(force = true)
            val attachmentsJson = JSONArray().apply { p.attachments.forEach { put(it) } }.toString()
            val mail = ScheduledMail(
                id = UUID.randomUUID().toString(),
                key = ScheduledSendTime.dedupeKey(p.accountId, p.to, p.subject, p.html),
                accountId = p.accountId,
                to = p.to,
                cc = p.cc,
                bcc = p.bcc,
                subject = p.subject,
                text = p.text,
                html = p.html,
                body = body.text,
                quoted = quotedHtml,
                attachmentsJson = attachmentsJson,
                atMillis = at,
                atLocal = ScheduledSendTime.localStamp(at),
                draftId = p.draftId,
                fromEmail = fromEmail,
            )
            val saved = withContext(Dispatchers.IO) {
                if (!ScheduledSendStore.upsert(ctx, mail)) return@withContext null
                // upsert 可能复用旧条目的 id（同内容只改时间），以存储里的实际记录来排闹钟
                ScheduledSendStore.list(ctx).firstOrNull { it.key == mail.key && it.pending }
            }
            busy = false
            if (saved == null) {
                // 单封 4MB 上限：超了明确告知，草稿仍在，可直接发送，不静默丢
                error = "邮件太大，无法定时发送；内容已存草稿，请直接发送"
                return@launch
            }
            ScheduledSendScheduler.schedule(ctx, saved)
            scheduledOk = true
            withContext(Dispatchers.IO) { Drafts.delete(ctx, p.draftId) }
            Toast.makeText(ctx, "已定时：${ScheduledSendTime.display(saved.atMillis)}", Toast.LENGTH_SHORT).show()
            onFinished(false)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgCard).statusBarsPadding().imePadding().navigationBarsPadding()) {
        // 头部
        Row(
            Modifier.fillMaxWidth().height(44.dp).background(c.bgBar).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(64.dp).height(44.dp).clip(RoundedCornerShape(OT.dimens.rMd.dp)).tapArea {
                    persistScope.launch { persist() }
                    onFinished(false)
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("取消", style = t.body, color = c.blue, modifier = Modifier.padding(start = 12.dp))
            }
            Text(
                when {
                    mode == "replyAll" -> "回复全部"
                    mode == "forward" -> "转发"
                    quotedHtml.isEmpty() -> "写邮件"
                    else -> "回复"
                },
                style = t.body.copy(fontWeight = FontWeight.SemiBold),
                color = c.label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            // 第三方账户：定时发送与草稿箱都只支持 otsmail 账号，这里不摆出点了会失败的入口
            if (!isExt) {
                Box(
                    Modifier.width(40.dp).height(44.dp).tapArea { if (!busy) scheduleOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = "定时发送",
                        tint = if (busy) c.label3 else c.blue,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box(
                    Modifier.width(40.dp).height(44.dp).tapArea {
                        persistScope.launch { persist() }
                        onOpenDrafts()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("草稿", style = t.footnote, color = c.blue)
                }
            }
            Box(
                Modifier.width(56.dp).height(44.dp).clip(RoundedCornerShape(OT.dimens.rMd.dp)).tapArea { send() },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    if (busy) "发送中…" else "发送",
                    style = t.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (busy) c.label3 else c.blue,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
        OTDivider(strong = true)

        // 未完成草稿恢复提示
        offer?.let { draft ->
            Row(
                Modifier.fillMaxWidth().background(c.bgInput).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("有一份未完成的草稿", style = t.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                    Text(
                        draft.subject.ifEmpty { draft.body.take(28).ifEmpty { "（无主题）" } },
                        style = t.caption1, color = c.label2, maxLines = 1,
                    )
                }
                Text(
                    "继续编辑",
                    style = t.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = c.blue,
                    modifier = Modifier.tapArea { applyDraft(draft) }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Text(
                    "忽略",
                    style = t.footnote,
                    color = c.label2,
                    modifier = Modifier.tapArea { offer = null }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            OTDivider(strong = true)
        }

        if (error.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error, style = t.footnote, color = c.red, modifier = Modifier.weight(1f))
                if (sendFailed) {
                    Text(
                        "重试发送",
                        style = t.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = c.blue,
                        modifier = Modifier.tapArea { if (!busy) send() }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (draftSaved && sendFailed) {
            Text(
                "已存草稿，可稍后重试",
                style = t.footnote,
                color = c.label2,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (restoreNote.isNotEmpty()) {
            Text(
                restoreNote,
                style = t.footnote,
                color = c.label2,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // 发件人
            if (isExt) {
                // 第三方邮箱只能用自己这一个地址发：只读展示，不给会切到 otsmail 账号的入口
                Column(Modifier.fillMaxWidth().background(c.bgCard)) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("发件人", style = t.body, color = c.label, modifier = Modifier.width(56.dp))
                        Text(
                            fromEmail, style = t.body, color = c.label2, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
                OTDivider(strong = true)
            } else if (accounts.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(c.bgCard)) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).tapArea { pickingFrom = !pickingFrom }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("发件人", style = t.body, color = c.label, modifier = Modifier.width(56.dp))
                        Text(
                            fromEmail, style = t.body, color = c.label2, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                        )
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.label3, modifier = Modifier.size(14.dp))
                    }
                    if (pickingFrom) {
                        accounts.forEach { (id, email) ->
                            OTDivider(inset = 16)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 44.dp).tapArea {
                                    fromId = id
                                    fromEmail = email
                                    pickingFrom = false
                                    touched = true
                                }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    email, style = t.body,
                                    color = if (id == fromId) c.blue else c.label,
                                    modifier = Modifier.weight(1f),
                                )
                                if (id == fromId) Text("当前", style = t.caption1, color = c.blue)
                            }
                        }
                    }
                }
                OTDivider(strong = true)
            }

            // 收件人
            OTTagField(
                label = "收件人",
                tags = toTags,
                input = toInput,
                error = toError,
                onInput = { v ->
                    touched = true
                    onAddressInput(v, toTags, { toTags = it }, { toInput = it }, { toError = it })
                },
                onCommit = { commitTypedTo() },
                onRemove = {
                    toTags = toTags.filterIndexed { i, _ -> i != it }
                    toError = ""
                    touched = true
                },
                onKeyEvent = { e ->
                    if (e.type == KeyEventType.KeyDown) {
                        when (e.key) {
                            Key.DirectionDown -> if (toSuggest.isNotEmpty()) {
                                toSuggestIndex = (toSuggestIndex + 1) % toSuggest.size
                                true
                            } else false
                            Key.DirectionUp -> if (toSuggest.isNotEmpty()) {
                                toSuggestIndex = (toSuggestIndex - 1 + toSuggest.size) % toSuggest.size
                                true
                            } else false
                            Key.Enter, Key.NumPadEnter -> {
                                commitTypedTo()
                                true
                            }
                            else -> false
                        }
                    } else false
                },
            )

            // 通讯录候选：点选或键盘上下 + Enter 都锁定成 tag
            if (toSuggest.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(c.bgCard)) {
                    OTDivider(inset = 16)
                    toSuggest.forEachIndexed { i, item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(if (i == toSuggestIndex) c.bgInput else c.bgCard)
                                .tapArea { addSuggest(i) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Person, contentDescription = null,
                                tint = c.label3, modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = t.subhead, color = c.label, maxLines = 1)
                                Text(item.email, style = t.caption1, color = c.label2, maxLines = 1)
                            }
                        }
                        if (i < toSuggest.lastIndex) OTDivider(inset = 16)
                    }
                }
            }

            // 抄送 / 密送控制
            Row(
                Modifier.fillMaxWidth().background(c.bgCard).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(c.bgInput),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = c.blue, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "抄送",
                    style = t.subhead,
                    color = c.blue,
                    modifier = Modifier.height(34.dp).tapArea { showCc = !showCc }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    "密送",
                    style = t.subhead,
                    color = c.blue,
                    modifier = Modifier.height(34.dp).tapArea { showBcc = !showBcc }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (showCc) {
                OTDivider(strong = true)
                OTTagField(
                    label = "抄送",
                    tags = ccTags,
                    input = ccInput,
                    error = ccError,
                    onInput = { v ->
                        touched = true
                        onAddressInput(v, ccTags, { ccTags = it }, { ccInput = it }, { ccError = it })
                    },
                    onCommit = {
                        if (ccInput.isNotBlank()) {
                            commitAddresses(ccInput, ccTags, { ccTags = it }, { ccInput = it }, { ccError = it })
                        }
                    },
                    onRemove = {
                        ccTags = ccTags.filterIndexed { i, _ -> i != it }
                        ccError = ""
                        touched = true
                    },
                )
            }
            if (showBcc) {
                OTDivider(strong = true)
                OTTagField(
                    label = "密送",
                    tags = bccTags,
                    input = bccInput,
                    error = bccError,
                    onInput = { v ->
                        touched = true
                        onAddressInput(v, bccTags, { bccTags = it }, { bccInput = it }, { bccError = it })
                    },
                    onCommit = {
                        if (bccInput.isNotBlank()) {
                            commitAddresses(bccInput, bccTags, { bccTags = it }, { bccInput = it }, { bccError = it })
                        }
                    },
                    onRemove = {
                        bccTags = bccTags.filterIndexed { i, _ -> i != it }
                        bccError = ""
                        touched = true
                    },
                )
            }

            // 主题
            OTDivider(strong = true)
            Row(
                Modifier.fillMaxWidth().height(44.dp).background(c.bgCard).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (subjectText.isEmpty()) Text("主题", style = t.body, color = c.label3)
                    BasicTextField(
                        value = subjectText,
                        onValueChange = { subjectText = it; touched = true },
                        singleLine = true,
                        textStyle = t.body.copy(color = c.label),
                        cursorBrush = SolidColor(c.blue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 正文
            OTDivider(strong = true)
            Column(Modifier.fillMaxWidth().background(c.bgCard).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 260.dp)) {
                    if (body.text.isEmpty()) Text("正文", style = t.body, color = c.label3)
                    BasicTextField(
                        value = body,
                        onValueChange = {
                            body = it
                            touched = true
                            // 用户一旦继续编辑，之前那次润色的撤销快照就失效，避免撤销覆盖新输入
                            if (polishUndo != null) {
                                polishUndo = null
                                polishNote = ""
                            }
                        },
                        textStyle = t.body.copy(color = c.label, lineHeight = 22.sp),
                        cursorBrush = SolidColor(c.blue),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    )
                }
                // 预览只取纯文本：先去掉 style/script 和标签，再交给 Html 解码实体，
                // 否则 &nbsp;、&#160; 这类实体会被当成正文原样显示（“字面 &amp;lt;”那种表现）
                val quote = remember(quotedHtml) {
                    if (quotedHtml.isBlank()) "" else runCatching {
                        Html.fromHtml(
                            quotedHtml
                                .replace(Regex("(?is)<(style|script)[^>]*>.*?</\\1>"), "")
                                .replace(Regex("(?is)<img[^>]*>"), "")
                                .take(6000),
                            Html.FROM_HTML_MODE_LEGACY,
                        ).toString().replace(Regex("\\s+"), " ").trim().take(300)
                    }.getOrDefault("")
                }
                if (quote.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(c.separator))
                        Text(
                            quote.take(300),
                            style = t.footnote,
                            color = c.label2,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
                if (signature.isNotEmpty()) {
                    OTDivider(strong = true)
                    Spacer(Modifier.height(8.dp))
                    Text(signature, style = t.footnote, color = c.label2)
                }
            }

            // 附件
            if (picks.isNotEmpty()) {
                OTDivider(strong = true)
                Column(Modifier.fillMaxWidth().background(c.bgCard)) {
                    picks.forEachIndexed { i, name ->
                        if (i > 0) OTDivider(inset = 16)
                        val kb = (attachments.getOrNull(i)?.optInt("size") ?: 0) / 1024
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ot_attach), contentDescription = null, tint = c.label2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = t.subhead, color = c.label, maxLines = 1)
                                Text("${kb.coerceAtLeast(1)} KB", style = t.caption1, color = c.label2)
                            }
                            Box(
                                Modifier.size(28.dp).tapArea {
                                    picks = picks.filterIndexed { idx, _ -> idx != i }
                                    attachments.removeAt(i)
                                    touched = true
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "移除", tint = c.label3, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 可撤销发送条：倒计时期间可撤回或直接发送；点「直接发送」后换成进行中状态，直到发送结束
        if (pendingLeft > 0 || delivering) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                    .background(c.toastBg)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (delivering) "正在发送…" else "$pendingLeft 秒后发送",
                    style = t.footnote,
                    color = c.onAccent,
                    modifier = Modifier.weight(1f),
                )
                if (!delivering) {
                    Box(
                        Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                            .tapArea { undoSend() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "撤销",
                            style = t.subhead,
                            color = c.onAccent,
                        )
                    }
                    Box(
                        Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                            .tapArea { sendNow() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "直接发送",
                            style = t.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = c.onAccent,
                        )
                    }
                }
            }
        }

        // 润色状态条：正在思考 / 已应用（可撤销替换）
        if (polishBusy || polishNote.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                    .background(c.toastBg)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (polishBusy) "正在思考…" else polishNote,
                    style = t.footnote,
                    color = c.onAccent,
                    modifier = Modifier.weight(1f),
                )
                if (!polishBusy && polishUndo != null) {
                    Box(
                        Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                            .tapArea { undoPolish() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "撤销替换",
                            style = t.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = c.onAccent,
                        )
                    }
                }
            }
        }

        // 格式工具条：56dp 高、每项 48dp 点击区、24dp 图标；项多时横向滑动，不换行
        OTDivider(strong = true)
        val toolsScroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxWidth()
                .background(c.bgBar)
                .height(56.dp),
        ) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .horizontalScroll(toolsScroll)
                    .padding(start = 8.dp, end = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(text = "B", weight = FontWeight.Bold) { wrap("**", "**") }
                ToolbarButton(text = "I", italic = true) { wrap("*", "*") }
                ToolbarButton(text = "U", underline = true) { wrap("__", "__") }
                ToolbarButton(text = "H", weight = FontWeight.Bold) { prefixLine("# ") }
                ToolbarIcon(R.drawable.ot_list, "项目符号列表") { prefixLine("- ") }
                ToolbarButton(text = "1.") { prefixLine("1. ") }
                ToolbarIcon(R.drawable.ot_quote, "引用") { prefixLine("> ") }
                ToolbarIcon(R.drawable.ot_link, "插入链接") { insertLink() }
                ToolbarIcon(R.drawable.ot_image, "插入图片（作为附件）") { imagePicker.launch(arrayOf("image/*")) }
                ToolbarIcon(R.drawable.ot_attach, "添加附件") {
                    picker.launch(arrayOf("image/*", "application/pdf", "text/plain", "*/*"))
                }
                ToolbarIcon(R.drawable.ot_pencil, "插入签名") {
                    if (signature.isNotEmpty()) {
                        val add = (if (body.text.isEmpty()) "" else "\n\n") + signature
                        body = TextFieldValue(body.text + add, TextRange(body.text.length + add.length))
                        touched = true
                    }
                }
                ToolbarIcon(R.drawable.ot_clear, "清除格式") { clearFormat() }
                // AI 润色：优先作用于选区；没有选区时润色全文
                ToolbarButton(text = "AI", weight = FontWeight.SemiBold) { startPolish() }
                if (polishBusy) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = c.blue,
                    )
                }
            }
            // 右侧渐隐：还有未露出的工具时提示可横向滑动
            if (toolsScroll.value < toolsScroll.maxValue) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(40.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, c.bgBar))),
                )
            }
        }
    }

    // 首次使用 AI 润色前的隐私说明（确认后本机不再提示）
    if (polishConsent) {
        AiConsentSheet(
            onAllow = {
                AiConsent.accept(ctx)
                polishConsent = false
                startPolish()
            },
            onCancel = { polishConsent = false },
        )
    }

    // 定时发送：快捷时间（1 小时后 / 下班后 / 明早 8 点）+ 自定义日期时间
    if (scheduleOpen) {
        ScheduleTimeSheet(
            onDismiss = { scheduleOpen = false },
            onPick = { at ->
                scheduleOpen = false
                scheduleSend(at)
            },
        )
    }
}

/** 收件人 tagfield：白底、标签 pill、输入框；Enter/IME 完成把当前地址锁定成 tag */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OTTagField(
    label: String,
    tags: List<String>,
    input: String,
    onInput: (String) -> Unit,
    onCommit: () -> Unit,
    onRemove: (Int) -> Unit,
    error: String = "",
    placeholder: String = "再加一个",
    onKeyEvent: (KeyEvent) -> Boolean = { false },
) {
    val t = OT.type
    val c = OT.colors
    Column(Modifier.fillMaxWidth().background(c.bgCard)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            // 标签跟第一行 chip 对齐：chip 30dp、行内文字居中，标签补 5dp 上边距后基线一致；
            // 多行地址时标签停在顶部，不再垂直居中
            verticalAlignment = Alignment.Top,
        ) {
            Text(label, style = t.body, color = c.label, modifier = Modifier.width(56.dp).padding(top = 5.dp))
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEachIndexed { i, tag ->
                    Row(
                        Modifier
                            .heightIn(min = 30.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(c.blue.copy(alpha = 0.12f))
                            .tapArea { onRemove(i) }
                            .padding(start = 10.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tag, style = t.footnote, color = c.blue, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.Close, contentDescription = "移除 $tag",
                            tint = c.blue, modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Box(Modifier.widthIn(min = 140.dp, max = 260.dp)) {
                    if (input.isEmpty()) Text(placeholder, style = t.footnote, color = c.label3)
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        singleLine = true,
                        textStyle = t.footnote.copy(color = c.label),
                        cursorBrush = SolidColor(c.blue),
                        // 软键盘声明「下一步/完成」：两种动作都按 Enter 处理，不依赖硬键盘
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { onCommit() },
                            onDone = { onCommit() },
                        ),
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent(onKeyEvent),
                    )
                }
            }
        }
        if (error.isNotEmpty()) {
            Text(
                error,
                style = t.caption1,
                color = c.red,
                modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun ToolbarButton(
    text: String,
    weight: FontWeight = FontWeight.Normal,
    italic: Boolean = false,
    underline: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(OT.dimens.rSm.dp)).tapArea { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = OT.type.subhead.copy(
                fontSize = 17.sp,
                fontWeight = weight,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
            ),
            color = OT.colors.label2,
        )
    }
}

@Composable
private fun ToolbarIcon(@DrawableRes resId: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(OT.dimens.rSm.dp)).tapArea { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(resId), contentDescription = description, tint = OT.colors.label2, modifier = Modifier.size(24.dp))
    }
}

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * 引用原文：去掉 <style>/<script>/注释/文档外壳，只留正文。
 *
 * 乱码根因：
 * 1) 原信 HTML 里常带 `<meta charset="gb2312">`（Outlook/国内客户端常见）。它跟着引用被塞进
 *    新邮件正文，而新邮件实际是 UTF-8；收件端看到这个旧声明就按 GBK 解 UTF-8 字节，中文成 `�`。
 * 2) `<html>/<head>/<body>` 外壳、`<title>`、`<base>` 嵌进 <blockquote> 会打乱解析。
 * 3) 详情页旧路径直接把 content 拼进 blockquote，CSS 会当正文显示，name 也没转义。
 * 所以这里必须剔除 charset/Content-Type 声明与文档外壳，name 统一 escape。
 */
internal fun buildQuoteHtml(name: String, content: String): String {
    val cleaned = content
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
        .replace(Regex("(?is)<!--.*?-->"), "")
        .replace(Regex("(?is)<meta[^>]*charset[^>]*>"), "")
        .replace(Regex("(?is)<meta[^>]*http-equiv\\s*=\\s*[\"']?content-type[^>]*>"), "")
        .replace(Regex("(?is)<base[^>]*>"), "")
        .replace(Regex("(?is)<title[^>]*>.*?</title>"), "")
        .replace(Regex("(?is)</?(html|head|body)[^>]*>"), "")
        .trim()
    return "<blockquote><p>${escapeHtml(name)} 写道：</p>$cleaned</blockquote>"
}

/** 发送撤销窗口的秒数：点发送后 8 秒内可撤回，超时才真正调 /email/send。 */
private const val UNDO_SECONDS = 8

/** 撤销窗口内的待发载荷：拍下点击发送那一刻的内容，期间界面继续编辑不影响这一封。 */
private data class PendingEmail(
    val accountId: Long,
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val text: String,
    val html: String,
    val attachments: List<JSONObject>,
    val draftId: String,
    /** >0 = 用这个第三方邮箱自己的 SMTP 直连发信；此时 [extAddress] 固定为该地址 */
    val extId: Long = 0L,
    val extAddress: String = "",
)

/** 轻量格式：**粗体**、*斜体*、__下划线__、[文字](链接)、# 标题、- / 1. 列表、> 引用 */
private fun textToHtml(text: String): String {
    fun inline(raw: String): String = escapeHtml(raw)
        .replace(Regex("\\[([^\\]\\n]+)\\]\\((https?://[^)\\s]+)\\)"), "<a href=\"$2\">$1</a>")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("__([^_]+)__"), "<u>$1</u>")
        .replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")

    val lines = text.replace("\r\n", "\n").split("\n")
    val out = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("## ") -> {
                out.append("<h3>${inline(line.removePrefix("## "))}</h3>")
                i++
            }
            line.startsWith("# ") -> {
                out.append("<h2>${inline(line.removePrefix("# "))}</h2>")
                i++
            }
            line.startsWith("> ") -> {
                out.append("<blockquote>${inline(line.removePrefix("> "))}</blockquote>")
                i++
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                out.append("<ul>")
                while (i < lines.size && (lines[i].startsWith("- ") || lines[i].startsWith("* "))) {
                    out.append("<li>${inline(lines[i].substring(2))}</li>")
                    i++
                }
                out.append("</ul>")
            }
            Regex("^\\d+\\. ").containsMatchIn(line) -> {
                out.append("<ol>")
                while (i < lines.size && Regex("^\\d+\\. ").containsMatchIn(lines[i])) {
                    out.append("<li>${inline(lines[i].replaceFirst(Regex("^\\d+\\. "), ""))}</li>")
                    i++
                }
                out.append("</ol>")
            }
            else -> {
                out.append("<p>${inline(line).ifEmpty { "<br>" }}</p>")
                i++
            }
        }
    }
    return out.toString()
}
