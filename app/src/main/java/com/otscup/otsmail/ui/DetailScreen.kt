package com.otscup.otsmail.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.otscup.otsmail.R
import com.otscup.otsmail.data.AiConsent
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.AttachmentTooLarge
import com.otscup.otsmail.data.AttachmentUnavailable
import com.otscup.otsmail.data.Attachments
import com.otscup.otsmail.data.MailAtt
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.data.MailDetail
import com.otscup.otsmail.data.aiErrorText
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 详情里「更多」弹出的分类（网页端 emailMoveCategory 的目标分类）。 */
private val MOVE_TARGETS = listOf(
    "primary" to "主要",
    "social" to "社交",
    "promotion" to "推广",
    "updates" to "动态",
)

/** 详情底栏的写信入口：回复 / 回复全部 / 转发（App 按它决定收件人、主题与正文）。 */
enum class ReplyAction { REPLY, REPLY_ALL, FORWARD }

/**
 * 外链图片加载器：点「显示图片」后由 App 自己取图再交给 WebView。
 * 解决三件事（都是真机上"图片加载不出来"的常见原因）：
 * 1) https 正文里的 http 图片被混合内容策略拦掉；
 * 2) 站点防盗链要求同源 Referer；
 * 3) 部分站点拒绝带 "; wv" 的 WebView UA（换普通 Chrome UA 再试一次）。
 * 隐私口径不变：默认仍然屏蔽，只有用户主动点「显示图片」才会走到这里。
 */
private object InlineImages {
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    /** 透明 1px PNG：取图失败时先顶上，避免 WebView 画出破图图标。 */
    val transparentPng: ByteArray = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        android.util.Base64.DEFAULT,
    )

    private val cache = object : LruCache<String, Pair<String, ByteArray>>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pair<String, ByteArray>): Int = value.second.size
    }

    fun looksLikeImage(request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) return false
        val url = request.url?.toString().orEmpty()
        if (!url.startsWith("http", ignoreCase = true)) return false
        val path = request.url?.path.orEmpty().lowercase()
        // WebView 会额外请求站点 favicon（返回 text/html），它不是正文图片，不能计入失败
        if (path.endsWith(".ico") || path.contains("favicon")) return false
        // WebView 一般不会给子资源带 Accept，按扩展名兜底
        if (request.requestHeaders?.get("Accept")?.contains("image/", ignoreCase = true) == true) return true
        return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".bmp") ||
            path.endsWith(".svg") || path.endsWith(".avif")
    }

    fun load(url: String, webViewUa: String): Pair<String, ByteArray>? {
        cache.get(url)?.let { return it }
        val referer = runCatching { URL(url).let { "${it.protocol}://${it.host}/" } }.getOrNull()
        // 默认 UA 已带 "; wv"，wispbyte 这类站反而只放行它；明确拒 wv 的站靠第二档
        val attempts = listOf(webViewUa to referer, BROWSER_UA to referer)
        for ((ua, ref) in attempts) {
            val fetched = tryFetch(url, ua, ref) ?: continue
            cache.put(url, fetched)
            return fetched
        }
        return null
    }

    private fun tryFetch(url: String, ua: String, referer: String?): Pair<String, ByteArray>? {
        val conn = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            }
        }.getOrNull() ?: return null
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val type = conn.contentType.orEmpty()
            if (!type.startsWith("image/", ignoreCase = true)) return null
            val bytes = conn.inputStream.use { readLimited(it, MAX_BYTES) } ?: return null
            type.substringBefore(';').ifBlank { "image/*" } to bytes
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun readLimited(stream: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }
}

private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val IMG_SRC = Regex("(?i)src\\s*=\\s*[\"']([^\"']+)[\"']")
private val IMG_SRCSET = Regex("(?i)srcset\\s*=\\s*[\"']([^\"']+)[\"']")

/** 把加载失败的 <img> 换成明确的占位卡片（带「在浏览器打开」），而不是静默空白。 */
private fun replaceFailedImages(html: String, failed: Set<String>, baseUrl: String): String {
    if (failed.isEmpty()) return html
    return IMG_TAG.replace(html) { tag ->
        val tagHtml = tag.value
        val candidates = buildList {
            IMG_SRC.find(tagHtml)?.groupValues?.getOrNull(1)?.let(::add)
            IMG_SRCSET.find(tagHtml)?.groupValues?.getOrNull(1)?.let { set ->
                set.split(',').forEach { part -> add(part.trim().substringBefore(' ')) }
            }
        }
        val hit = candidates.firstNotNullOfOrNull { raw -> resolveImageUrl(baseUrl, raw)?.takeIf { it in failed } }
        if (hit != null) imageFailurePlaceholder(hit) else tagHtml
    }
}

private fun resolveImageUrl(baseUrl: String, raw: String): String? {
    if (raw.isBlank() || raw.startsWith("cid:", ignoreCase = true) ||
        raw.startsWith("data:", ignoreCase = true)
    ) {
        return null
    }
    return runCatching { URL(URL(baseUrl), raw.replace("&amp;", "&")).toString() }.getOrNull()
}

private fun imageFailurePlaceholder(url: String): String {
    val href = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "%3C").replace(">", "%3E")
    return """<div style="margin:10px 0;padding:16px 12px;border:1px solid #D1D1D6;border-radius:10px;""" +
        """background:#F2F2F7;text-align:center;color:#636366;font:14px/20px -apple-system,sans-serif">""" +
        """图片加载失败<br><a href="$href" style="color:#007AFF;text-decoration:none;font-weight:600">在浏览器打开</a></div>"""
}


/**
 * 读信页：对标三星/Google 邮件——通栏无卡片、正文左右 16dp、HTML 图片不超出内容区。
 * 头部只有返回 + 上/下封切换；标题常规字重，日期灰色小字；发件人 outline chip + 蓝色「详细信息」。
 * 底栏固定五项，长正文在 WebView 内滚动。
 */
@Composable
fun DetailScreen(
    emailId: Long,
    onBack: () -> Unit,
    onReply: (MailDetail, ReplyAction) -> Unit,
    /** 智能回复点选后带进写信页；点选前绝不自动发出 */
    onAiReply: (MailDetail, String) -> Unit = { _, _ -> },
    onToast: (String) -> Unit = {},
) {
    val t = OT.type
    val c = OT.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current

    var currentId by remember { mutableStateOf(emailId) }
    // 列表里已经有这封邮件的标题/发件人/时间：先拿它占位，点下去立刻有信头，
    // 不再先显示「加载中…」等一次网络。正文为空 → 下方先铺骨架。
    // 内存缓存是同步的：上一封刚看过时，首帧就带着完整正文，WebView 也就不用再等一帧。
    val memDetail = remember(emailId) { MailCache.detailMemory(emailId)?.first }
    val hint = remember(emailId) {
        memDetail ?: MailCache.findItem(emailId)?.let {
            MailDetail(
                emailId = it.emailId,
                subject = it.subject,
                from = it.from,
                name = it.name,
                time = it.time,
                content = "",
                unread = it.unread,
                starred = it.starred,
                attachments = emptyList(),
                category = it.category,
            )
        }
    }
    var mail by remember { mutableStateOf(hint) }
    var bodyReady by remember { mutableStateOf(false) }
    /** 正文还在路上（无缓存）：这时显示骨架，而不是白板/「没有正文」 */
    var loadingBody by remember { mutableStateOf(true) }
    /** WebView 等首帧之后再建，别让初始化把"点下去第一眼"卡住 */
    var webEnabled by remember(emailId) { mutableStateOf(memDetail?.content?.isNotBlank() == true) }
    LaunchedEffect(currentId) {
        if (currentId == emailId && memDetail?.content?.isNotBlank() == true) return@LaunchedEffect
        webEnabled = false
        withFrameNanos { }
        webEnabled = true
    }
    var error by remember { mutableStateOf("") }
    var translated by remember { mutableStateOf("") }
    // 译文默认覆盖原文；false = 显示译文，true = 显示原文。切换只换渲染，不重新请求接口。
    var showingOriginal by remember { mutableStateOf(false) }
    // 默认屏蔽外部图片：图片 URL 往往带追踪参数，直接加载会把 IP 和"已读时间"暴露给发件人。
    // 用户主动点「显示图片」才放行，且只影响当前这封。
    var showImages by remember(emailId) { mutableStateOf(false) }
    // 点「显示图片」后仍然取不到的 URL：正文里换成占位卡片，顶部给一行可读状态
    var failedImages by remember(emailId) { mutableStateOf<Set<String>>(emptySet()) }
    // 后台取图线程只往这里塞，正文重组/重载统一在主线程做，避免边加载边循环重载
    val failedImageSink = remember(emailId) { java.util.Collections.synchronizedSet(mutableSetOf<String>()) }
    LaunchedEffect(showImages, emailId) {
        if (!showImages) {
            failedImages = emptySet()
            failedImageSink.clear()
            return@LaunchedEffect
        }
        while (true) {
            delay(900)
            val fresh = failedImageSink.toSet() - failedImages
            if (fresh.isNotEmpty()) failedImages = failedImages + fresh
        }
    }
    var busy by remember { mutableStateOf(false) }
    var detailSheet by remember { mutableStateOf(false) }
    var translatedError by remember { mutableStateOf("") }
    var bodyHeightDp by remember { mutableStateOf(0) }
    var moreOpen by remember { mutableStateOf(false) }
    var moveSheet by remember { mutableStateOf(false) }
    /** 无缓存 + 拉正文失败（服务端 501 / 超时）：给「重试」，别把失败伪装成「没有正文」 */
    var loadFailed by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    // AI 摘要 / 智能回复 sheet：""=关闭，summary/reply=对应能力
    var aiSheet by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiSummary by remember { mutableStateOf("") }
    var aiReplies by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var aiError by remember { mutableStateOf("") }
    /** 隐私提示只在第一次弹；弹完后接着执行对应动作 */
    var aiConsentFor by remember { mutableStateOf("") }
    // 附件缩略图要等正文画完再取，避免和开信抢解码/下载
    var thumbReady by remember(currentId) { mutableStateOf(false) }
    // 全屏预览：图片按下标在 imageAtts 里切换；PDF 单页预览
    var imageView by remember { mutableStateOf(-1) }
    var pdfView by remember { mutableStateOf<MailAtt?>(null) }
    LaunchedEffect(bodyReady, loadingBody, currentId) {
        if (!loadingBody || bodyReady) {
            delay(300)
            thumbReady = true
        }
    }

    LaunchedEffect(currentId, reloadTick) {
        error = ""
        loadFailed = false
        translatedError = ""
        translated = MailCache.translation(currentId).orEmpty()
        showingOriginal = false
        bodyHeightDp = 0
        // 缓存里有正文：WebView 一画出内容就该露出来，不用等"量到高度"才撤骨架
        bodyReady = MailCache.detailMemory(currentId)?.first?.content?.isNotBlank() == true
        OpenPerf.mark("detail_effect")
        // 先上缓存（内存 → SQLite），有缓存就直接渲染，不再出现「加载中…」
        val cached = MailCache.detailMemory(currentId) ?: MailCache.detailDisk(currentId)
        mail = cached?.first ?: if (currentId == emailId) hint else null
        if (mail != null) OpenPerf.mark("header")
        // 10 分钟内的详情不再重复请求：返回/重建/上下封来回翻都不打网络
        if (cached?.second == true) {
            loadingBody = false
            OpenPerf.mark("cache_hit")
            return@LaunchedEffect
        }
        loadingBody = true
        runCatching { Api.detail(currentId) }
            .onSuccess { m ->
                loadingBody = false
                mail = m
                OpenPerf.mark("network")
                MailCache.saveDetail(m)
                runCatching { Api.markRead(m.emailId) }.onSuccess {
                    // 已读同步回缓存与列表行，返回列表时未读点立刻消失
                    MailCache.markRead(m.emailId)
                    mail = m.copy(unread = false)
                    MailSync.changed()
                }
            }
            .onFailure {
                loadingBody = false
                if (mail == null) {
                    error = friendlyError(it)
                } else if (mail?.content.isNullOrBlank()) {
                    // 有信头但正文没拿到（服务端 5xx/超时）：显式失败态 + 重试
                    loadFailed = true
                }
            }
    }

    /** 上/下封切换：没有相邻邮件时给一句提示，不做假动效。 */
    fun jump(newer: Boolean) {
        scope.launch {
            runCatching { Api.neighbor(currentId, newer) }
                .onSuccess { id -> if (id != 0L) currentId = id else onToast(if (newer) "已经是最后一封" else "已经是第一封") }
                .onFailure { onToast(friendlyError(it)) }
        }
    }

    val m = mail
    /** 同一封邮件里的图片附件：全屏预览可左右切换 */
    val imageAtts = m?.attachments?.filter {
        Attachments.kindOf(it.filename) == Attachments.Kind.IMAGE && it.url.isNotBlank()
    } ?: emptyList()

    /** 智能回复候选：不同语气各请求一次；点选前不发送。 */
    fun runAi(kind: String, d: MailDetail) {
        when (kind) {
            "summary" -> {
                aiSheet = "summary"
                aiBusy = true
                aiSummary = ""
                aiError = ""
                scope.launch {
                    runCatching { Api.aiSummary(d.emailId) }
                        .onSuccess { aiSummary = it }
                        .onFailure { aiError = aiErrorText(it) }
                    aiBusy = false
                }
            }
            "reply" -> {
                aiSheet = "reply"
                aiBusy = true
                aiReplies = emptyList()
                aiError = ""
                scope.launch {
                    val tones = listOf("简洁" to "concise", "友好" to "friendly", "正式" to "formal")
                    // 三个语气并发请求；单个失败不影响其它候选，全部失败才显示错误
                    val results = coroutineScope {
                        tones.map { (label, tone) ->
                            async { label to runCatching { Api.aiReply(d.emailId, tone) } }
                        }.awaitAll()
                    }
                    aiReplies = results.mapNotNull { (label, r) -> r.getOrNull()?.let { label to it.second } }
                    if (aiReplies.isEmpty()) {
                        aiError = aiErrorText(results.mapNotNull { it.second.exceptionOrNull() }.firstOrNull()
                            ?: RuntimeException("AI 未返回候选回复"))
                    }
                    aiBusy = false
                }
            }
        }
    }

    /** 首次使用 AI 前先给隐私说明；确认后不再弹。 */
    fun requestAi(kind: String) {
        val d = mail ?: return
        if (AiConsent.needed(context)) {
            aiConsentFor = kind
        } else {
            runAi(kind, d)
        }
    }

    if (detailSheet && m != null) {
        OTSheet(onDismiss = { detailSheet = false }) {
            Text(
                "详细信息",
                style = t.title3,
                color = c.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            DetailKV("发件人", m.name.ifEmpty { m.from })
            OTDivider(inset = 16)
            DetailKV("邮箱", m.from)
            OTDivider(inset = 16)
            DetailKV("时间", TimeFmt.detailTime(m.time))
            OTDivider(inset = 16)
            DetailKV("分类", categoryLabel(m.category).ifEmpty { "未分类" })
            OTDivider(inset = 16)
            DetailKV("邮件 ID", m.emailId.toString())
            Spacer(Modifier.height(12.dp))
        }
    }

    if (moveSheet && m != null) {
        OTSheet(onDismiss = { moveSheet = false }) {
            Text(
                "移动到",
                style = t.title3,
                color = c.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            MOVE_TARGETS.forEachIndexed { i, (key, label) ->
                if (i > 0) OTDivider(inset = 16)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .tapArea {
                            moveSheet = false
                            scope.launch {
                                runCatching { Api.moveCategory(m.emailId, key) }
                                    .onSuccess {
                                        MailCache.setCategory(m.emailId, key)
                                        MailSync.changed(); onToast("已移动到$label"); onBack()
                                    }
                                    .onFailure { onToast(friendlyError(it)) }
                            }
                        }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = t.body, color = c.label, modifier = Modifier.weight(1f))
                    if (m.category == key) {
                        Text("当前", style = t.footnote, color = c.label3)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // AI 摘要 / 智能回复：结果单独用 sheet 展示，绝不替换正文
    if (aiSheet.isNotEmpty()) {
        OTSheet(onDismiss = { if (!aiBusy) aiSheet = "" }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                if (aiSheet == "summary") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI 摘要", style = t.title3, color = c.label, modifier = Modifier.weight(1f))
                        Text("由 AI 生成", style = t.caption1, color = c.label2)
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        aiBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.blue)
                            Spacer(Modifier.width(10.dp))
                            Text("正在思考…", style = t.body, color = c.label2)
                        }
                        aiError.isNotEmpty() -> Text(aiError, style = t.footnote, color = c.red)
                        else -> SelectionContainer { Text(aiSummary, style = t.body, color = c.label) }
                    }
                    if (!aiBusy && aiError.isEmpty() && aiSummary.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        OTPrimaryButton("复制摘要") {
                            copyToClipboard(context, "AI 摘要", aiSummary)
                            onToast("已复制摘要")
                        }
                    }
                } else {
                    Text("智能回复", style = t.title3, color = c.label)
                    Spacer(Modifier.height(4.dp))
                    Text("选择一条带入写信页，不会自动发送", style = t.footnote, color = c.label2)
                    Spacer(Modifier.height(12.dp))
                    when {
                        aiBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.blue)
                            Spacer(Modifier.width(10.dp))
                            Text("正在思考…（生成 3 条候选）", style = t.body, color = c.label2)
                        }
                        aiError.isNotEmpty() -> Text(aiError, style = t.footnote, color = c.red)
                        else -> aiReplies.forEachIndexed { i, (label, text) ->
                            if (i > 0) OTDivider(inset = 0)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .tapArea {
                                        aiSheet = ""
                                        m?.let { onAiReply(it, text) }
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(label, style = t.caption1.copy(fontWeight = FontWeight.SemiBold), color = c.blue)
                                Spacer(Modifier.height(2.dp))
                                Text(text, style = t.body, color = c.label, maxLines = 4)
                            }
                        }
                    }
                }
            }
        }
    }

    if (aiConsentFor.isNotEmpty()) {
        AiConsentSheet(
            onAllow = {
                AiConsent.accept(context)
                val kind = aiConsentFor
                aiConsentFor = ""
                mail?.let { runAi(kind, it) }
            },
            onCancel = { aiConsentFor = "" },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bgCard)
            .onGloballyPositioned { OpenPerf.mark("first_frame") },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 头部：返回 + 上/下封切换（三星样张只有这排图标，星标/删除收在「更多」里）
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OTIconButton(R.drawable.ot_chevron_left, "返回", c.blue, size = 44) { onBack() }
                Spacer(Modifier.weight(1f))
                OTIconButton(R.drawable.ot_chevron_up, "上一封", c.label2) { jump(newer = false) }
                OTIconButton(R.drawable.ot_chevron_down, "下一封", c.label2) { jump(newer = true) }
                OTIconButton(R.drawable.ot_filter, "详细信息", c.label2, size = 40) { detailSheet = true }
            }

            if (m == null) {
                if (error.isNotEmpty()) {
                    Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(20.dp))
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中…", style = t.subhead, color = c.label2)
                    }
                }
                return@Column
            }

            // 标题 + 信头：标题与「发件人 / 日期」都在 20dp 左边缘起排，
            // 标签列固定 64dp，值和标签按第一行基线对齐；长地址在值列换行/截断。
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp)) {
                Text(
                    m.subject.ifEmpty { "（无主题）" },
                    style = t.title3.copy(fontWeight = FontWeight.Medium),
                    color = c.label,
                )
                Spacer(Modifier.height(8.dp))
                OTKeyValueRow(
                    label = "发件人",
                    value = if (m.name.isBlank() || m.name == m.from) m.from else "${m.name} <${m.from}>",
                    maxLines = 2,
                    trailing = {
                        Text(
                            "详细信息",
                            style = t.subhead,
                            color = c.blue,
                            modifier = Modifier.alignByBaseline().tapArea { detailSheet = true },
                        )
                    },
                )
                Spacer(Modifier.height(6.dp))
                OTKeyValueRow(
                    label = "日期",
                    value = TimeFmt.detailTime(m.time),
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
            }
            OTDivider(strong = true)

            // 内容区占满剩余高度，底部动作条才能贴屏底；正文按可用高度铺满
            BoxWithConstraints(Modifier.weight(1f)) {
                // 正文：译文命中时看译文，否则看原文；两者都空才显示「没有正文」占位，避免白屏。
                // sanitize 对长 HTML 是纯 CPU 工作：按内容 remember，避免每次重组（滚动/展开）重跑一遍。
                val baseHtml = remember(m.content, translated, showingOriginal) {
                    sanitizeEmailHtml(if (translated.isNotEmpty() && !showingOriginal) translated else m.content)
                }
                // 取不到的图片换成占位卡片；这一步只依赖失败集合，单独缓存
                val bodyHtml = remember(baseHtml, failedImages) {
                    replaceFailedImages(
                        baseHtml,
                        failedImages,
                        Api.base.removeSuffix("/api") + "/",
                    )
                }
                val extrasHeight = (if (translated.isNotEmpty()) 30.dp else 0.dp) +
                    (if (bodyHtml.isNotBlank()) 30.dp else 0.dp) +
                    (if (m.attachments.isNotEmpty()) (1.dp + 56.dp * m.attachments.size) else 0.dp) +
                    (if (translatedError.isNotEmpty()) 26.dp else 0.dp)
                // 正文槽位 = 剩余高度 - 12dp 上边距 - 译文提示/附件/错误占位
                val bodySlot = (maxHeight - 12.dp - extrasHeight).coerceAtLeast(64.dp)
                Column(Modifier.fillMaxSize()) {
                // 已翻译时只留一行轻量提示 + 原文/译文切换，不做横幅、不做卡片
                if (translated.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (showingOriginal) "原文" else "已翻译为简体中文",
                            style = t.caption1,
                            color = c.label2,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (showingOriginal) "显示译文" else "显示原文",
                            style = t.caption1.copy(fontWeight = FontWeight.Medium),
                            color = c.blue,
                            modifier = Modifier.tapArea { showingOriginal = !showingOriginal },
                        )
                    }
                }
                // 图片开关：默认屏蔽远程图片（防 IP/已读回执泄露），用户显式点开才加载。
                // 正文里没有 <img> 就不显示这条——避免「外部图片已屏蔽」出现在纯文字邮件里。
                if (baseHtml.isNotBlank() && (baseHtml.contains("<img", ignoreCase = true) || failedImages.isNotEmpty())) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (failedImages.isNotEmpty()) "${failedImages.size} 张图片加载失败"
                            else if (showImages) "外部图片已显示" else "外部图片已屏蔽",
                            style = t.caption1,
                            color = if (failedImages.isNotEmpty()) c.red else c.label2,
                        )
                        Spacer(Modifier.weight(1f))
                        // 取不到的图（含正文外的 CSS 背景图）也给一个明确的去处，不留静默空白
                        if (failedImages.isNotEmpty()) {
                            Text(
                                "在浏览器打开",
                                style = t.caption1,
                                color = c.blue,
                                modifier = Modifier.tapArea {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(failedImages.first())),
                                        )
                                    }
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            if (showImages) "隐藏图片" else "显示图片",
                            style = t.caption1.copy(fontWeight = FontWeight.Medium),
                            color = c.blue,
                            modifier = Modifier.tapArea { showImages = !showImages },
                        )
                    }
                }
                // 量正文高度：不跑 JS（正文来自外部邮件，JS 一律关闭）。
                // WebView.contentHeight 是文档内容高度（CSS px ≈ dp），等价于原来 JS 里的
                // document.body.scrollHeight；computeVerticalScrollRange() 是 View 的 protected 方法，外部不可调。
                // 最多等 10 帧；量不到就按槽位全高显示，不空等。
                fun measureBody(w: WebView) {
                    w.postOnAnimation(object : Runnable {
                        private var tries = 0
                        override fun run() {
                            val h = w.contentHeight
                            if (h > 0) {
                                bodyHeightDp = h
                                bodyReady = true
                                OpenPerf.mark("body_measured")
                            } else if (tries++ < 10) {
                                w.postOnAnimation(this)
                            } else {
                                bodyReady = true
                            }
                        }
                    })
                }
                val showSkeleton = bodyHtml.isBlank() && loadingBody
                if (bodyHtml.isNotBlank() || showSkeleton) {
                    // 正文槽位：短正文按实际高度收缩，长正文铺满槽位并在 WebView 内滚动。
                    // 没测到高度前给 160dp（先看到开头），测到后收缩；不再用 1dp 空等。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            .height(
                                when {
                                    bodyHeightDp > 0 -> bodyHeightDp.dp.coerceIn(1.dp, bodySlot)
                                    // 有缓存：先占满槽位让正文立刻可见，量到高度后再收缩，不拿骨架挡着
                                    bodyReady -> bodySlot
                                    else -> 160.dp.coerceAtMost(bodySlot)
                                },
                            ),
                    ) {
                        // WebView 建到首帧之后再建：创建本身约 100~600ms，
                        // 先让信头和骨架画出来，用户不会觉得点下去没反应。
                        if (webEnabled) AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                OpenPerf.mark("web_created")
                                // 优先用 App 起好后预热好的实例：省掉引擎初始化的 0.9s+
                                (WebViewPrewarm.take() ?: WebView(ctx)).apply {
                                    // 外部邮件正文一律不跑 JS：量高、跳转都已经不用 JS 实现
                                    settings.javaScriptEnabled = false
                                    // 默认只放行内联 data: 图片；远程图片的 URL 会泄露 IP 与阅读时间
                                    settings.blockNetworkImage = !showImages
                                    // 用户点开图片后允许 https 正文里的 http 图片（老邮件常见），
                                    // 屏蔽态仍然 NEVER_ALLOW
                                    settings.mixedContentMode = if (showImages) {
                                        WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    } else {
                                        WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    }
                                    settings.useWideViewPort = false
                                    settings.loadWithOverviewMode = false
                                    settings.setSupportZoom(false)
                                    settings.builtInZoomControls = false
                                    settings.textZoom = 100
                                    // shouldInterceptRequest 在后台线程跑，WebView 方法不能跨线程调用：
                                    // 这里（主线程）先把 UA 读出来给图片取图器用
                                    val webViewUa = settings.userAgentString.orEmpty()
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    webViewClient = object : WebViewClient() {
                                        // 任何跳转都不在应用内打开：http(s) 交给系统浏览器，
                                        // intent://、tel:、自定义 scheme 一律拦截（返回 true 即不加载）
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                        ): Boolean {
                                            val uri = request?.url ?: return true
                                            val scheme = uri.scheme?.lowercase()
                                            if (scheme == "http" || scheme == "https") {
                                                runCatching {
                                                    view?.context?.startActivity(
                                                        Intent(Intent.ACTION_VIEW, uri)
                                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                    )
                                                }
                                            }
                                            return true
                                        }

                                        /**
                                         * 远程图片由 App 自己取（带 UA / 同源 Referer），绕开防盗链与 WebView UA 风控；
                                         * 失败时回 1px 透明图并记入失败集合，900ms 后正文里的 <img> 换成可点的占位卡片。
                                         */
                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                        ): WebResourceResponse? {
                                            val req = request ?: return null
                                            if (!showImages || !InlineImages.looksLikeImage(req)) return null
                                            val url = req.url.toString()
                                            val hit = InlineImages.load(url, webViewUa)
                                            if (hit != null) {
                                                return WebResourceResponse(
                                                    hit.first,
                                                    null,
                                                    200,
                                                    "OK",
                                                    mapOf(
                                                        "Cache-Control" to "max-age=3600",
                                                        "Access-Control-Allow-Origin" to "*",
                                                    ),
                                                    ByteArrayInputStream(hit.second),
                                                )
                                            }
                                            failedImageSink.add(url)
                                            return WebResourceResponse(
                                                "image/png",
                                                null,
                                                200,
                                                "OK",
                                                mapOf("Cache-Control" to "no-store"),
                                                ByteArrayInputStream(InlineImages.transparentPng),
                                            )
                                        }
                                        // 首帧内容已提交就能量正文，不必等 onPageFinished
                                        // （后者要等所有图片下载完，慢网下会白等 1 秒以上）
                                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                                            super.onPageCommitVisible(view, url)
                                            val w = view ?: return
                                            if ((w.tag as? String)?.contains("data-ots=1") == true) {
                                                OpenPerf.mark("body_visible")
                                                measureBody(w)
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            val w = view ?: return
                                            val hasContent = (w.tag as? String)?.contains("data-ots=1") == true
                                            if (hasContent) {
                                                OpenPerf.mark("body_loaded")
                                                measureBody(w)
                                            }
                                        }
                                    }
                                }
                            },
                            update = { wv ->
                                // 覆盖而不是追加：译文态只渲染译文，原文态渲染原文；都已做过清洗
                                val html = bodyHtml
                                wv.settings.blockNetworkImage = !showImages
                                wv.settings.mixedContentMode = if (showImages) {
                                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                } else {
                                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                }
                                val fg = hex(c.label)
                                val sys = hex(c.label2)
                                val link = hex(c.blue)
                                val quote = hex(c.separator)
                                val marker = if (html.isNotBlank()) "1" else "0"
                                val imgFlag = if (showImages) "1" else "0"
                                // 左右留白交给 Compose 的 16dp，HTML 自己不再加 padding，图片才能贴满内容区
                                // 显式声明 UTF-8 必须排在 viewport 之前、也是整篇第一个 meta：
                                // 原信正文自带 <meta charset="gb2312"> 时，浏览器会按那个声明解我们写进去的
                                // UTF-8 字节，中文就成乱码；先出现的 charset 才能锁定解码方式。
                                val shell = """<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>*{max-width:100%!important;box-sizing:border-box}html,body{width:100%;overflow-x:hidden;-webkit-text-size-adjust:100%}
body{margin:0;padding:0;background:transparent;color:$fg;
font:15px/20px -apple-system,"Noto Sans SC",sans-serif;letter-spacing:-0.24px;word-break:break-word}
p{margin:0 0 12px}img,video{max-width:100%;height:auto}a{color:$link}
blockquote{margin:8px 0;padding-left:10px;border-left:3px solid $quote;color:$sys}
</style></head><body data-ots=$marker data-img=$imgFlag>$html</body></html>"""
                                // 内容没变就不重载，避免测量→重组→重载的循环
                                if (wv.tag != shell) {
                                    wv.tag = shell
                                    wv.loadDataWithBaseURL(Api.base.removeSuffix("/api") + "/", shell, "text/html", "utf-8", null)
                                    // 真正"正文画出来"的时间点用系统回调拿，别拿 JS 量高度当基准
                                    wv.postVisualStateCallback(
                                        0L,
                                        object : WebView.VisualStateCallback() {
                                            override fun onComplete(requestId: Long) {
                                                // 空壳（data-ots=0）那一次不算：骨架阶段不该有"正文已画"记录
                                                if ((wv.tag as? String)?.contains("data-ots=1") != true) return
                                                OpenPerf.mark("body_paint")
                                                bodyReady = true
                                                // 画完再量高度：加载后立刻量会和首帧渲染抢主线程
                                                measureBody(wv)
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        if (!bodyReady) {
                            // 静态骨架，不跑动画：正文一旦画出来就把骨架盖掉
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.separatorSoft))
                                Box(Modifier.fillMaxWidth(0.82f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.separatorSoft))
                                Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.separatorSoft))
                            }
                        }
                    }
                } else {
                    // 正文、纯文本都为空时不画白板，给一句明确提示
                    Box(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (loadFailed) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .tapArea {
                                        loadFailed = false
                                        loadingBody = true
                                        reloadTick++
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("正文加载失败", style = t.subhead, color = c.label2)
                                Spacer(Modifier.width(8.dp))
                                Text("重试", style = t.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.blue)
                            }
                        } else {
                            Text("该邮件没有正文", style = t.subhead, color = c.label2)
                        }
                    }
                }

                // 附件：图片/PDF 行内缩略图（正文画完后再懒加载），点开预览；其它类型仍走下载。
                // 地址只用 attList 的签名 url，url 缺失时明确提示，不再拿 key 拼旧路径。
                if (m.attachments.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OTDivider(inset = 16, strong = true)
                        m.attachments.forEach { att ->
                            val kind = Attachments.kindOf(att.filename)
                            val url = Api.attachmentUrl(att.url)
                            AttachmentRow(
                                filename = att.filename,
                                kind = kind,
                                url = url,
                                ready = thumbReady,
                                onPreview = {
                                    when {
                                        url.isEmpty() -> onToast("附件地址不可用")
                                        kind == Attachments.Kind.IMAGE -> {
                                            val i = imageAtts.indexOfFirst { it.url == att.url && it.filename == att.filename }
                                            if (i >= 0) imageView = i else onToast("附件地址不可用")
                                        }
                                        kind == Attachments.Kind.PDF -> pdfView = att
                                        else -> downloadAttachment(context, url, att.filename, onToast, scope)
                                    }
                                },
                                onDownload = {
                                    if (url.isEmpty()) onToast("附件地址不可用")
                                    else downloadAttachment(context, url, att.filename, onToast, scope)
                                },
                            )
                        }
                    }
                }

                if (translatedError.isNotEmpty()) {
                    Text(
                        translatedError,
                        style = t.footnote,
                        color = c.red,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                }
            }

            OTActionBar(
                items = listOf(
                    Triple("↩", "回复", { onReply(m, ReplyAction.REPLY) }),
                    Triple("⇉", "回复全部", { onReply(m, ReplyAction.REPLY_ALL) }),
                    Triple("↪", "转发", { onReply(m, ReplyAction.FORWARD) }),
                    Triple("🗑", "删除", {
                        scope.launch {
                            runCatching { Api.trash(listOf(m.emailId)) }
                                .onSuccess { MailCache.removeItem(m.emailId); MailSync.changed(); onBack() }
                                .onFailure { onToast(friendlyError(it)) }
                        }
                    }),
                    Triple("⋯", "更多", { moreOpen = true }),
                ),
            )
        }

        if (imageView >= 0 && imageAtts.isNotEmpty()) {
            ImagePreviewDialog(
                attachments = imageAtts,
                startIndex = imageView.coerceIn(0, imageAtts.size - 1),
                onDismiss = { imageView = -1 },
                onSave = { att, bmp -> scope.launch { saveImageToGallery(context, att.filename, bmp, onToast) } },
                onDownload = { att -> downloadAttachment(context, Api.attachmentUrl(att.url), att.filename, onToast, scope) },
            )
        }
        pdfView?.let { att ->
            val url = Api.attachmentUrl(att.url)
            PdfPreviewDialog(
                filename = att.filename,
                url = url,
                onDismiss = { pdfView = null },
                onDownload = { downloadAttachment(context, url, att.filename, onToast, scope) },
            )
        }

        // 「更多」浮层：锚在底栏右上方，模仿三星的上下文菜单
        if (moreOpen && m != null) {
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(
                    with(density) { (-12).dp.roundToPx() },
                    with(density) { (-74).dp.roundToPx() },
                ),
                onDismissRequest = { moreOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = c.bgElevated,
                    shadowElevation = 10.dp,
                ) {
                    Column(Modifier.width(228.dp).padding(vertical = 6.dp)) {
                        MoreItem("标记为垃圾邮件") {
                            moreOpen = false
                            scope.launch {
                                runCatching { Api.spam(m.emailId) }
                                    .onSuccess { MailCache.removeItem(m.emailId); MailCache.expireLists(); MailSync.changed(); onToast("已标记为垃圾邮件"); onBack() }
                                    .onFailure { onToast(friendlyError(it)) }
                            }
                        }
                        MoreItem("恢复到收件箱") {
                            moreOpen = false
                            scope.launch {
                                runCatching { Api.restore(m.emailId) }
                                    .onSuccess {
                                        MailCache.removeItem(m.emailId)
                                        MailCache.expireLists()
                                        MailSync.changed()
                                        onToast("已恢复到收件箱")
                                        onBack()
                                    }
                                    .onFailure { onToast(friendlyError(it)) }
                            }
                        }
                        MoreItem("取消垃圾标记") {
                            moreOpen = false
                            scope.launch {
                                runCatching { Api.spamCancel(m.emailId) }
                                    .onSuccess {
                                        MailCache.removeItem(m.emailId)
                                        MailCache.expireLists()
                                        MailSync.changed()
                                        onToast("已取消垃圾标记")
                                        onBack()
                                    }
                                    .onFailure { onToast(friendlyError(it)) }
                            }
                        }
                        MoreItem("标记为未读") {
                            moreOpen = false
                            scope.launch {
                                runCatching { Api.markUnread(m.emailId) }
                                    .onSuccess {
                                        MailCache.markUnread(m.emailId)
                                        mail = m.copy(unread = true)
                                        MailSync.changed(); onToast("已标记为未读"); onBack()
                                    }
                                    .onFailure { onToast(friendlyError(it)) }
                            }
                        }
                        MoreItem(if (m.starred) "取消星标" else "添加星标") {
                            moreOpen = false
                            val next = !m.starred
                            mail = m.copy(starred = next)
                            scope.launch {
                                runCatching { if (next) Api.starAdd(m.emailId) else Api.starCancel(m.emailId) }
                                    .onSuccess {
                                        MailCache.setStar(m.emailId, next)
                                        MailSync.changed()
                                    }
                                    .onFailure {
                                        mail = m
                                        MailCache.setStar(m.emailId, m.starred)
                                        onToast(friendlyError(it))
                                    }
                            }
                        }
                        MoreItem(
                            when {
                                busy -> "翻译中…"
                                translated.isEmpty() -> "将邮件翻译为中文"
                                showingOriginal -> "显示译文"
                                else -> "显示原文"
                            },
                        ) {
                            moreOpen = false
                            if (busy) return@MoreItem
                            if (translated.isNotEmpty()) {
                                // 已翻译过：只切显示，不重新请求
                                showingOriginal = !showingOriginal
                            } else {
                                busy = true
                                scope.launch {
                                    runCatching { Api.translate(m.emailId, "zh") }
                                        .onSuccess { r ->
                                            when {
                                                r.content.isNotEmpty() -> {
                                                    translated = r.content
                                                    translatedError = ""
                                                    showingOriginal = false
                                                    MailCache.putTranslation(m.emailId, r.content)
                                                }
                                                // 只有服务端明确说正文已经是目标语言，才算「无需翻译」
                                                r.alreadyInTargetLang -> onToast("正文已经是中文，无需翻译")
                                                else -> {
                                                    translatedError = "翻译服务未返回译文（source=${r.sourceLang.ifEmpty { "未知" }}）"
                                                    onToast("翻译失败：服务端未返回译文")
                                                }
                                            }
                                        }
                                        .onFailure { onToast(friendlyError(it)) }
                                    busy = false
                                }
                            }
                        }
                        MoreItem("AI 摘要") {
                            moreOpen = false
                            requestAi("summary")
                        }
                        MoreItem("智能回复") {
                            moreOpen = false
                            requestAi("reply")
                        }
                        MoreItem("移动") {
                            moreOpen = false
                            moveSheet = true
                        }
                        MoreItem("打印", dimmed = true) {
                            moreOpen = false
                            onToast("打印暂未支持")
                        }
                        MoreItem("另存为文件", dimmed = true) {
                            moreOpen = false
                            onToast("另存为暂未支持")
                        }
                    }
                }
            }
        }
    }
}

/** 附件行：图片/PDF 有 40dp 缩略图，其它类型保持纸夹图标；右侧固定「下载」。 */
@Composable
private fun AttachmentRow(
    filename: String,
    kind: Attachments.Kind,
    url: String,
    ready: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    val t = OT.type
    val c = OT.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbPx = with(density) { 80.dp.roundToPx() }
    var thumb by remember(url) { mutableStateOf<Bitmap?>(null) }
    var thumbError by remember(url) { mutableStateOf("") }
    // 只在正文画完（ready）后取一次缩略图；不打开详情就预取全部附件
    LaunchedEffect(url, ready, kind) {
        if (!ready || kind == Attachments.Kind.OTHER) return@LaunchedEffect
        if (url.isEmpty()) {
            thumbError = "附件地址不可用"
            return@LaunchedEffect
        }
        runCatching { Attachments.thumbnail(context, url, kind, thumbPx) }
            .onSuccess { if (it == null) thumbError = "无法预览，可下载查看" else thumb = it }
            .onFailure { e ->
                thumbError = when (e) {
                    is AttachmentTooLarge -> "附件超过 10MB，请下载查看"
                    is AttachmentUnavailable -> "附件地址不可用"
                    else -> "预览失败，可下载查看"
                }
            }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // 缩略图超过 10MB 或解码失败时，点按回落到下载，而不是继续进预览
            .tapArea { if (thumbError.isNotEmpty()) onDownload() else onPreview() }
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(c.separatorSoft),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumb
            when {
                bmp != null -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                thumbError.isEmpty() && kind != Attachments.Kind.OTHER && ready ->
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.blue)
                else -> Icon(
                    painterResource(R.drawable.ot_link),
                    contentDescription = null,
                    tint = c.label2,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                filename.ifEmpty { "附件" },
                style = t.subhead,
                color = c.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (thumbError.isNotEmpty()) {
                Text(thumbError, style = t.caption1, color = c.red, maxLines = 1)
            } else if (kind == Attachments.Kind.IMAGE || kind == Attachments.Kind.PDF) {
                Text(
                    if (kind == Attachments.Kind.PDF) "PDF · 点按预览首页" else "图片 · 点按预览",
                    style = t.caption1,
                    color = c.label2,
                    maxLines = 1,
                )
            }
        }
        Text(
            "下载",
            style = t.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = c.blue,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .tapArea(onDownload)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** 图片全屏预览：左右滑动切换同封内的图片，可保存到相册；失败给明确失败态。 */
@Composable
private fun ImagePreviewDialog(
    attachments: List<MailAtt>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onSave: (MailAtt, Bitmap) -> Unit,
    onDownload: (MailAtt) -> Unit,
) {
    val t = OT.type
    val c = OT.colors
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenPx = with(density) { maxOf(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }
    val pager = rememberPagerState(initialPage = startIndex) { attachments.size }
    var error by remember { mutableStateOf("") }
    var loaded by remember(pager.currentPage) { mutableStateOf<Bitmap?>(null) }
    val current = attachments.getOrNull(pager.currentPage)

    LaunchedEffect(pager.currentPage, current?.url) {
        loaded = null
        error = ""
        val att = current ?: return@LaunchedEffect
        val url = Api.attachmentUrl(att.url)
        if (url.isEmpty()) {
            error = "附件地址不可用"
            return@LaunchedEffect
        }
        runCatching { Attachments.image(context, url, screenPx) }
            .onSuccess { if (it == null) error = "图片解码失败，请下载查看" else loaded = it }
            .onFailure { e ->
                error = when (e) {
                    is AttachmentTooLarge -> "图片超过 10MB，请下载查看"
                    is AttachmentUnavailable -> "附件地址不可用"
                    else -> "预览失败，请下载查看"
                }
            }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bmp = loaded
            if (bmp != null) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    val pageBmp = if (page == pager.currentPage) bmp else null
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (pageBmp != null) {
                            Image(
                                bitmap = pageBmp.asImageBitmap(),
                                contentDescription = attachments.getOrNull(page)?.filename,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (error.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error, style = t.body, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "下载查看",
                                style = t.subhead.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x33FFFFFF))
                                    .tapArea { current?.let(onDownload) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
            // 顶部：关闭 + 文件名 + 保存
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color(0x66000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "关闭",
                    style = t.body.copy(fontWeight = FontWeight.Medium),
                    color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).tapArea(onDismiss).padding(6.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${pager.currentPage + 1}/${attachments.size}",
                    style = t.footnote,
                    color = Color(0xB3FFFFFF),
                    modifier = Modifier.weight(1f),
                )
                val cur = current
                val b = loaded
                if (cur != null) {
                    Text(
                        "下载",
                        style = t.footnote,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .tapArea { onDownload(cur) }
                            .padding(6.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (cur != null && b != null) {
                    Text(
                        "保存",
                        style = t.body.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .tapArea { onSave(cur, b) }
                            .padding(6.dp),
                    )
                }
            }
        }
    }
}

/** PDF 预览：内置单页（第 0 页）渲染，成本比「跳系统查看器」低（不依赖外部 App、不用先落盘）。 */
@Composable
private fun PdfPreviewDialog(
    filename: String,
    url: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val t = OT.type
    val c = OT.colors
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val maxPx = with(density) { maxOf(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    var error by remember(url) { mutableStateOf("") }

    LaunchedEffect(url) {
        if (url.isEmpty()) {
            error = "附件地址不可用"
            return@LaunchedEffect
        }
        runCatching { Attachments.pdfPage(context, url, maxPx) }
            .onSuccess { if (it == null) error = "PDF 解析失败，请下载查看" else bmp = it }
            .onFailure { e ->
                error = when (e) {
                    is AttachmentTooLarge -> "附件超过 10MB，请下载查看"
                    is AttachmentUnavailable -> "附件地址不可用"
                    else -> "预览失败，请下载查看"
                }
            }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF2000000))) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "关闭",
                        style = t.body.copy(fontWeight = FontWeight.Medium),
                        color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).tapArea(onDismiss).padding(6.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        filename.ifEmpty { "PDF" },
                        style = t.footnote,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "下载",
                        style = t.body.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).tapArea(onDownload).padding(6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val b = bmp
                    when {
                        b != null -> Image(
                            bitmap = b.asImageBitmap(),
                            contentDescription = filename,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        error.isNotEmpty() -> Text(error, style = t.body, color = Color.White, textAlign = TextAlign.Center)
                        else -> CircularProgressIndicator(color = Color.White)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("仅预览首页，完整内容请下载查看", style = t.caption1, color = Color(0xB3FFFFFF))
            }
        }
    }
}

/** 保存图片到系统相册（MediaStore，Android 10+ 走 RELATIVE_PATH，无需存储权限）。 */
private suspend fun saveImageToGallery(
    context: Context,
    filename: String,
    bmp: Bitmap,
    onToast: (String) -> Unit,
) {
    val msg = withContext(Dispatchers.IO) {
        val name = safeName(filename).substringBeforeLast('.', "image") + "-${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OTSMail")
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法写入相册")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                } ?: error("无法写入相册")
                "已保存到相册"
            } else {
                // Android 9 及以下没有分区存储，MediaStore 写入需要存储权限，改为应用专属相片目录
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
                dir.mkdirs()
                val f = File(dir, name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                "已保存到 ${f.absolutePath}"
            }
        }.getOrElse { "保存失败：${it.message ?: "未知错误"}" }
    }
    withContext(Dispatchers.Main) { onToast(msg) }
}

/** 文件名只保留安全的基本名（邮件里的名字可能带路径或控制字符）。 */
private fun safeName(filename: String): String = filename
    .replace('\\', '/')
    .substringAfterLast('/')
    .replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
    .trim()
    .trimStart('.')
    .take(120)
    .ifEmpty { "attachment" }

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** 「更多」菜单里的一行（44 高，15pt；暂不支持的两项用 label3）。 */
@Composable
private fun MoreItem(text: String, dimmed: Boolean = false, onClick: () -> Unit) {
    val c = OT.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .tapArea(onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = OT.type.subhead, color = if (dimmed) c.label3 else c.label)
    }
}

@Composable
private fun DetailKV(label: String, value: String) {
    OTKeyValueRow(
        label = label,
        value = value,
        maxLines = 2,
        modifier = Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

// 主题色转 CSS 十六进制，正文 HTML 里用
private fun hex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())

/**
 * 正文 HTML 清洗：邮件内容完全由外部发件人控制，进 WebView 前先去掉能执行/外联的东西。
 * - 整段删掉 script / iframe / object / embed / form / base / link / meta（link、meta 会引远程 CSS 或跳转）
 * - 删掉所有 on* 事件属性（onerror、onclick、onmouseover…）
 * - 把 javascript: / vbscript: 链接改成 #
 * 说明：用正则做白名单式清洗，对"邮件正文"这种非对抗性场景足够；不追求完整的 HTML 解析。
 */
internal fun sanitizeEmailHtml(raw: String): String {
    if (raw.isBlank()) return raw
    return raw
        .replace(Regex("(?is)<(script|iframe|object|embed|form|base|link|meta)\\b[^>]*>.*?</\\1\\s*>"), "")
        .replace(Regex("(?is)<(script|iframe|object|embed|form|base|link|meta)\\b[^>]*/?>"), "")
        .replace(Regex("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
        .replace(Regex("(?i)javascript:"), "#")
        .replace(Regex("(?i)vbscript:"), "#")
}

/** 附件下载：key 拼成真实地址后交给系统 DownloadManager，失败给中文提示。 */
private fun downloadAttachment(
    context: Context,
    url: String,
    filename: String,
    onToast: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (url.isEmpty()) {
        onToast("附件地址不可用")
        return
    }
    // 演示包附件是 data URL，DownloadManager 只认 http(s)，改走缓存文件复制
    if (url.startsWith("data:", true)) {
        scope.launch(Dispatchers.IO) {
            val msg = runCatching { Attachments.save(context, url, filename) }
                .fold({ "已保存到 ${it.absolutePath}" }, { "下载失败：${it.message ?: "未知错误"}" })
            withContext(Dispatchers.Main) { onToast(msg) }
        }
        return
    }
    runCatching {
        val name = safeName(filename)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }.onSuccess {
        onToast("已开始下载")
    }.onFailure {
        onToast("下载失败：${it.message ?: "未知错误"}")
    }
}
