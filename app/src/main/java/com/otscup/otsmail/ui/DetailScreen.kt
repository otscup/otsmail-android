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
import android.util.Log
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.otscup.otsmail.data.ExtImapActions
import com.otscup.otsmail.data.ExtLocalMail
import com.otscup.otsmail.data.ExtMailStore
import com.otscup.otsmail.data.ExtSyncError
import com.otscup.otsmail.data.MailAtt
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.data.MailDetail
import com.otscup.otsmail.data.SiteMail
import com.otscup.otsmail.data.VerificationCode
import com.otscup.otsmail.data.aiErrorText
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.push.PushSettings
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
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
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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
 * 正文 WebView 的中性 origin：`.invalid` 是 RFC 6761 保留域名，DNS 永不解析。
 * 正文不继承服务端 origin，避免发件人内容里的相对 URL 被当成服务端同源请求；
 * 相对图片/链接在清洗阶段已按 [serverOrigin] 补成绝对地址，功能不受影响。
 */
private const val BODY_BASE_URL = "https://otsmail.invalid/"

/** 服务端 origin（Api.base 去掉 /api）：相对地址补全与图片失败卡片都用它。 */
private fun serverOrigin(): String = Api.base.removeSuffix("/api") + "/"

/**
 * 正文外壳：doctype + 视口 + 主题色。详情页与「全站只读」页共用，避免两份 CSS 漂移。
 * 必须有 doctype：没有 doctype 时 WebView 进入怪异模式，原信里的 body{height:100%}
 * 会把 shell 的 body 压成 0px，正文被整体裁掉；height:auto!important 再兜一层。
 */
internal fun bodyShell(
    html: String,
    fg: String,
    link: String,
    quote: String,
    sys: String,
    marker: String,
    imgFlag: String,
) = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>*{max-width:100%!important;box-sizing:border-box;min-width:0!important}html,body{width:100%;overflow-x:hidden;-webkit-text-size-adjust:100%;height:auto!important;min-height:0!important}
body{margin:0;padding:0;background:transparent;color:$fg;
font:15px/20px -apple-system,"Noto Sans SC",sans-serif;letter-spacing:-0.24px;word-break:break-word}
p{margin:0 0 12px}img,video{max-width:100%;height:auto}a{color:$link}
/* 邮件模板（MJML/营销邮件）普遍把 table/td 写死成 600px，表格单元格不吃 max-width，
   只靠 * 规则压不住；具体像素宽度在 [normalizeFixedWidths] 里清洗成百分比/自适应，
   这里只做兜底：表格与单元格都不允许越过视口。百分比列宽（width="33%"）保持不变。 */
table,td,th{max-width:100%!important;min-width:0!important;box-sizing:border-box}
/* GitHub 状态列表头被折成 Sta/tus：body 上的 word-break:break-word 等价 anywhere，
   会让单元格最小宽度退化到 1 个字符，表格自动布局就把窄列压到放不下整个 "Status"。
   表头改为只在整词放不下时才断；数据单元格不动，长 URL/订单号仍可断行。 */
th{word-break:normal;overflow-wrap:break-word}
/* 纯文本兜底：<pre> 的长行在窄屏同样需要折行，不能让整页横向溢出 */
pre{white-space:pre-wrap!important;overflow-wrap:anywhere}
blockquote{margin:8px 0;padding-left:10px;border-left:3px solid $quote;color:$sys}
</style></head><body data-ots=$marker data-img=$imgFlag>$html</body></html>"""

/**
 * 外链图片加载器：远程图片默认由 App 自己取图再交给 WebView。
 * 解决三件事（都是真机上"图片加载不出来"的常见原因）：
 * 1) https 正文里的 http 图片被混合内容策略拦掉；
 * 2) 站点防盗链要求同源 Referer；
 * 3) 部分站点拒绝带 "; wv" 的 WebView UA（换普通 Chrome UA 再试一次）。
 * 隐私口径：远程图片默认自动加载，追踪像素（1×1/极小/无有效宽高）另行拦掉。
 */
private object InlineImages {
    private const val MAX_BYTES = 20 * 1024 * 1024

    /**
     * 服务端图片代理基地址：null = App 直连取图（当前行为）。
     * 代理只是换成图基地址：渲染、缓存、失败占位、追踪像素拦截都不需要跟着改；
     * 代理模式下内网/本机目标由服务端校验，客户端不再重复判断。
     */
    private val PROXY_BASE: String? = null

    private fun fetchUrl(original: String): String =
        PROXY_BASE?.trimEnd('/')?.let {
            "$it?url=" + java.net.URLEncoder.encode(original, "UTF-8")
        } ?: original

    /** 重定向上限：短链/CDN 一般 1~2 跳，4 跳足够；每跳都重新校验 scheme 与目标地址。 */
    private const val MAX_REDIRECTS = 4

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

    /**
     * 「这是一个图片资源」的判定，用于 HTTP 失败计数。
     * 比 [looksLikeImage] 多认一种：服务端签名代理的 /api/proxy/image?url=... 没有扩展名，
     * WebView 也可能不带 Accept 头，靠扩展名会全部漏掉，SVG/egress 被拒就只剩破图图标。
     * 只影响失败统计，不改变是否由 App 代取。
     */
    fun isImageRequest(request: WebResourceRequest): Boolean {
        if (looksLikeImage(request)) return true
        if (request.isForMainFrame) return false
        return request.url?.path.orEmpty().lowercase().endsWith("/api/proxy/image")
    }

    fun load(url: String, webViewUa: String): Pair<String, ByteArray>? {
        cache.get(url)?.let { return it }
        val target = fetchUrl(url)
        val proxied = target != url
        val referer = runCatching { URL(url).let { "${it.protocol}://${it.host}/" } }.getOrNull()
        // 默认 UA 已带 "; wv"，wispbyte 这类站反而只放行它；明确拒 wv 的站靠第二档
        val attempts = listOf(webViewUa to referer, BROWSER_UA to referer)
        for ((ua, ref) in attempts) {
            val fetched = tryFetch(target, ua, ref, checkInternal = !proxied) ?: continue
            cache.put(url, fetched)
            return fetched
        }
        return null
    }

    /** 只允许 http/https；file、content、intent 等一律不代取。 */
    private fun allowed(url: URL): Boolean {
        val scheme = url.protocol.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /**
     * 内网/本机目标一律拒绝：先做 DNS 解析（域名与 IP 字面量走同一条路径），
     * 再看每个解析结果是否落在回环、私网、链路本地、组播或 IPv6 ULA 段。
     * 图片 URL 完全由发件人控制，不拦就能借这台设备扫用户家里的路由器/内网服务。
     */
    private fun isInternalTarget(host: String): Boolean {
        // URL.getHost() 对 IPv6 字面量会带方括号，去掉后再交给 InetAddress 解析
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isBlank()) return true
        val addrs = runCatching { InetAddress.getAllByName(h) }.getOrNull() ?: return true
        if (addrs.isEmpty()) return true
        return addrs.any { isInternalAddress(it) }
    }

    private fun isInternalAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress
        ) {
            return true
        }
        val b = a.address
        if (a is Inet4Address) {
            val first = b[0].toInt() and 0xff
            val second = b[1].toInt() and 0xff
            // 0.0.0.0/8 与 100.64.0.0/10（运营商级 NAT）不在 isSiteLocalAddress 里
            if (first == 0) return true
            if (first == 100 && second in 64..127) return true
        } else if (a is Inet6Address) {
            // fc00::/7 唯一本地地址；fe80::/10 已由 isLinkLocalAddress 覆盖
            if ((b[0].toInt() and 0xfe) == 0xfc) return true
        }
        return false
    }

    private fun tryFetch(url: String, ua: String, referer: String?, checkInternal: Boolean): Pair<String, ByteArray>? {
        var current = url
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            val parsed = runCatching { URL(current) }.getOrNull() ?: return null
            if (!allowed(parsed) || (checkInternal && isInternalTarget(parsed.host.orEmpty()))) {
                Log.w("OtsImg", "blocked scheme/host: $current")
                return null
            }
            val conn = runCatching {
                (parsed.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 6000
                    // 自己跟跳转：每一跳都要先过 scheme / 内网校验，不能让底层连接器替我们跳
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", ua)
                    setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
                }
            }.getOrNull() ?: return null
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")?.takeIf { it.isNotBlank() } ?: return null
                    current = runCatching { URL(parsed, location).toString() }.getOrNull() ?: return null
                    hops++
                    continue
                }
                if (code !in 200..299) return null
                val type = conn.contentType.orEmpty()
                if (!type.startsWith("image/", ignoreCase = true)) return null
                val bytes = conn.inputStream.use { readLimited(it, MAX_BYTES) } ?: return null
                return type.substringBefore(';').ifBlank { "image/*" } to bytes
            } catch (_: Throwable) {
                return null
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        return null
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

private val IMG_TAG = Regex("(?is)<img\\b[^>]*>")
private val IMG_SRC_QUOTED = Regex("(?i)\\bsrc\\s*=\\s*([\"'])(.*?)\\1")

/** 把加载失败的 <img> 换成明确的占位卡片（带「在浏览器打开」），而不是静默空白。 */
private fun replaceFailedImages(html: String, failed: Set<String>, baseUrl: String): String {
    if (failed.isEmpty()) return html
    return IMG_TAG.replace(html) { tag ->
        val tagHtml = tag.value
        val candidates = buildList {
            IMG_SRC_QUOTED.find(tagHtml)?.groupValues?.getOrNull(2)?.let(::add)
            IMG_SRCSET.find(tagHtml)?.groupValues?.getOrNull(1)?.let { set ->
                set.split(',').forEach { part -> add(part.trim().substringBefore(' ')) }
            }
        }
        val hit = candidates.firstNotNullOfOrNull { raw -> resolveImageUrl(baseUrl, raw)?.takeIf { it in failed } }
        if (hit != null) imageFailurePlaceholder(hit, tagHtml) else tagHtml
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

/** 声明尺寸不超过这个边长（px）的图算「小图」：用紧凑占位，不铺卡片 */
private const val SMALL_IMAGE_EDGE = 96

private val IMG_WIDTH_ATTR = Regex("(?i)\\bwidth\\s*=\\s*[\"']?(\\d+)")
private val IMG_HEIGHT_ATTR = Regex("(?i)\\bheight\\s*=\\s*[\"']?(\\d+)")
private val IMG_STYLE_SIZE = Regex("(?i)\\b(?:width|height)\\s*:\\s*(\\d+)\\.?\\d*px")

/** 从 <img> 标签里读声明尺寸（width/height 属性或 style）；读不到返回 null */
private fun declaredImageEdge(tagHtml: String): Int? {
    val attr = listOf(IMG_WIDTH_ATTR, IMG_HEIGHT_ATTR)
        .mapNotNull { it.find(tagHtml)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    val style = IMG_STYLE_SIZE.findAll(tagHtml)
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .toList()
    // 用最长边判断：50×50、SVG logo 这类走紧凑占位；600×20 的分隔条仍按大图卡片
    return (attr + style).maxOrNull()
}

/**
 * 失败占位：大图用卡片；小图（50×50 图标 / SVG logo）只给一个居中的「×」。
 * 小图不能铺卡片：图片位置可能只有几十 px 宽，卡片里的中文会被挤成一字一行。
 * 卡片本身也强制单行省略，尺寸读不到时同样不会竖排；文字提示由正文底部
 * 「N 张图片加载失败」那一行承担。
 */
private fun imageFailurePlaceholder(url: String, tagHtml: String): String {
    val href = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "%3C").replace(">", "%3E")
    val edge = declaredImageEdge(tagHtml)
    if (edge != null && edge <= SMALL_IMAGE_EDGE) {
        // 占位框贴着原图尺寸（18–40px），既不撑破图标槽位，也不会把中文挤成竖排
        val box = edge.coerceIn(18, 40)
        val font = maxOf(9, (box * 0.45).toInt())
        return """<span title="图片加载失败" style="display:inline-flex;align-items:center;""" +
            """justify-content:center;width:${box}px;height:${box}px;max-width:100%;vertical-align:middle;""" +
            """border:1px solid #D1D1D6;border-radius:${box / 5}px;background:#F2F2F7;overflow:hidden;""" +
            """font:600 ${font}px/1 -apple-system,sans-serif;color:#8E8E93">""" +
            """<a href="$href" style="color:#8E8E93;text-decoration:none;white-space:nowrap">&#215;</a></span>"""
    }
    return """<div style="margin:10px 0;padding:14px 12px;border:1px solid #D1D1D6;border-radius:10px;""" +
        """background:#F2F2F7;text-align:center;color:#636366;font:14px/20px -apple-system,sans-serif;""" +
        """white-space:nowrap;overflow:hidden;text-overflow:ellipsis">""" +
        """图片加载失败 <a href="$href" style="color:#007AFF;text-decoration:none;font-weight:600">""" +
        """在浏览器打开</a></div>"""
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
    // 远程图片默认自动加载（与 Gmail 一致）；追踪像素另有独立拦截（见 sanitize）。
    // 自动加载后仍然取不到的 URL：正文里换成占位卡片，顶部给一行可读状态
    var failedImages by remember(emailId) { mutableStateOf<Set<String>>(emptySet()) }
    // 后台取图线程只往这里塞，正文重组/重载统一在主线程做，避免边加载边循环重载
    val failedImageSink = remember(emailId) { java.util.Collections.synchronizedSet(mutableSetOf<String>()) }
    LaunchedEffect(emailId) {
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
                // 只有原本未读才回写已读：已读邮件再发一次 markRead 是纯多余请求，
                // 且 MailSync.changed() 会连带整页列表 + 两套计数共约 6 个请求（路线图 P6）
                if (m.unread) {
                    runCatching { Api.markRead(m.emailId) }.onSuccess {
                        // 已读同步回缓存与列表行，返回列表时未读点立刻消失
                        MailCache.markRead(m.emailId)
                        mail = m.copy(unread = false)
                        MailSync.changed()
                    }
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

            // 验证码只做「识别 + 一键复制」：没识别到就完全不占位，不放灰按钮。
            // 与通知栏共用 VerificationCode.find，避免两套正则给出不同结果。
            val vcode = remember(m.subject, m.content, translated, showingOriginal) {
                VerificationCode.find(
                    m.subject + "\n" +
                        if (translated.isNotEmpty() && !showingOriginal) translated else m.content,
                )
            }
            if (vcode != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.bgGrouped)
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("识别到验证码", style = t.caption1, color = c.label2)
                        Text(
                            vcode.code,
                            style = t.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = c.label,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(100))
                            .background(c.blue)
                            .tapArea {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("验证码", vcode.code))
                                onToast("已复制验证码")
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "复制验证码",
                            style = t.caption1.copy(fontWeight = FontWeight.Medium),
                            color = Color.White,
                        )
                    }
                }
            }

            // 内容区占满剩余高度，底部动作条才能贴屏底；正文按可用高度铺满
            BoxWithConstraints(Modifier.weight(1f)) {
                // 正文：译文命中时看译文，否则看原文；两者都空才显示「没有正文」占位，避免白屏。
                // sanitize 对长 HTML 是纯 CPU 工作：按内容 remember，避免每次重组（滚动/展开）重跑一遍。
                val baseHtml = remember(m.content, translated, showingOriginal) {
                    // 相对地址按服务端 origin 补全：WebView 不再继承服务端 origin（见 BODY_BASE_URL）
                    sanitizeEmailHtml(
                        if (translated.isNotEmpty() && !showingOriginal) translated else m.content,
                        serverOrigin(),
                    )
                }
                // 取不到的图片换成占位卡片；这一步只依赖失败集合，单独缓存
                val bodyHtml = remember(baseHtml, failedImages) {
                    replaceFailedImages(
                        baseHtml,
                        failedImages,
                        serverOrigin(),
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
                // 图片状态：远程图片默认自动加载；只把「取不到」的图报出来，给一个明确的去处。
                if (baseHtml.isNotBlank() && failedImages.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${failedImages.size} 张图片加载失败",
                            style = t.caption1,
                            color = c.red,
                        )
                        Spacer(Modifier.weight(1f))
                        // 取不到的图（含正文外的 CSS 背景图）也给一个明确的去处，不留静默空白
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
                                    // 正文来自外部邮件，不跑 JS
                                    settings.javaScriptEnabled = false
                                    // 远程图片默认加载；追踪像素已在清洗阶段换成透明图，不会上报
                                    settings.blockNetworkImage = false
                                    // https 正文里的 http 图片（老邮件常见）也放行，交给取图器统一处理
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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
                                            if (!InlineImages.looksLikeImage(req)) return null
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

                                        /**
                                         * 签名代理对 SVG / 目标站拒绝 Cloudflare egress 回 400/415/502，
                                         * 这类图走不到上面的代取失败分支（WebView 直接请求），
                                         * 只能在这里收：记进失败集合，900ms 后换成可点的占位卡片，不会重试。
                                         */
                                        override fun onReceivedHttpError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            errorResponse: WebResourceResponse?,
                                        ) {
                                            if (request == null || errorResponse == null) return
                                            if (!InlineImages.isImageRequest(request)) return
                                            failedImageSink.add(request.url.toString())
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
                                            if ((w.tag as? String)?.contains("data-ots=1") == true) {
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
                                val fg = hex(c.label)
                                val sys = hex(c.label2)
                                val link = hex(c.blue)
                                val quote = hex(c.separator)
                                val marker = if (html.isNotBlank()) "1" else "0"
                                val imgFlag = "1"
                                // 左右留白交给 Compose 的 16dp，HTML 自己不再加 padding，图片才能贴满内容区
                                // 显式声明 UTF-8 必须排在 viewport 之前、也是整篇第一个 meta：
                                // 原信正文自带 <meta charset="gb2312"> 时，浏览器会按那个声明解我们写进去的
                                // UTF-8 字节，中文就成乱码；先出现的 charset 才能锁定解码方式。
                                // 必须有 doctype：没有 doctype 时 WebView 进入怪异模式，原信里的
                                // body{height:100%} 会把 shell 的 body 压成 0px，正文被整体裁掉（Kimi 那封）。
                                // height:auto!important 再兜一层：原信 CSS 里的 body 高度声明一律不生效。
                                val shell = bodyShell(html, fg, link, quote, sys, marker, imgFlag)
                                // 内容没变就不重载，避免测量→重组→重载的循环
                                if (wv.tag != shell) {
                                    wv.tag = shell
                                    // 中性 origin：正文完全不继承服务端 origin，避免正文里的相对 URL
                                    // 把请求/同源假设带到服务端；相对图片与相对链接已在清洗阶段补成绝对地址。
                                    wv.loadDataWithBaseURL(BODY_BASE_URL, shell, "text/html", "utf-8", null)
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
                        // 通知栏最多 3 个按钮，静音入口放这里才点得到
                        MoreItem(if (PushSettings.isMuted(context, m.from)) "取消静音发件人" else "静音此发件人") {
                            moreOpen = false
                            val sender = m.from.trim()
                            if (sender.isEmpty()) {
                                onToast("该邮件没有发件人地址，无法静音")
                            } else if (PushSettings.isMuted(context, sender)) {
                                PushSettings.unmute(context, sender)
                                onToast("已取消静音，该发件人之后会恢复弹通知")
                            } else {
                                PushSettings.mute(context, sender)
                                onToast("已静音该发件人；静音只影响弹通知，邮件照常进入列表")
                            }
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
 * - [baseUrl] 非空时，把所有相对 src/href/srcset 补成绝对地址：
 *   正文用中性 origin 渲染，不补的话相对图片与相对链接都会指向一个不存在的主机
 * 说明：用正则做白名单式清洗，对"邮件正文"这种非对抗性场景足够；不追求完整的 HTML 解析。
 */
internal fun sanitizeEmailHtml(raw: String, baseUrl: String = ""): String {
    if (raw.isBlank()) return raw
    val cleaned = raw
        .replace(Regex("(?is)<(script|iframe|object|embed|form|base|link|meta)\\b[^>]*>.*?</\\1\\s*>"), "")
        .replace(Regex("(?is)<(script|iframe|object|embed|form|base|link|meta)\\b[^>]*/?>"), "")
        .replace(Regex("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
        .replace(Regex("(?i)javascript:"), "#")
        .replace(Regex("(?i)vbscript:"), "#")
    val normalized = normalizeFixedWidths(cleaned)
    val safe = neutralizeTrackingPixels(normalized)
    return if (baseUrl.isBlank()) safe else absolutizeHtmlUrls(safe, baseUrl)
}

/**
 * 把营销邮件里写死的像素宽度改成能自适应的写法，避免 600px 版面被窄屏裁掉右侧。
 *
 * 只处理 **≥ [FIXED_WIDTH_MIN] 的像素值**：图标/间距这类小宽度（width="1"、width:24px）保持原样；
 * 百分比宽度（width="50%"、width="33%"）不匹配正则，也不会被改。
 * - `style="width:600px"` → `max-width:100%`（去掉固定宽度，交给表格自动布局）
 * - `width="600"` → `width="100%"`（对单列表格等价于铺满，多列里的像素列宽本就少见）
 */
private const val FIXED_WIDTH_MIN = 360

/**
 * 单元格侧的最小阈值：多列表格常把每列写成 width="200"（200 < 360，不会被改成 100%），
 * 三列累加 600px 后在 360–400dp 窄屏上仍会横向溢出、被 WebView 裁掉右侧。
 * 因此 td/th 上的像素列宽（≥40）直接去掉，交给表格自动布局按内容分配；
 * 1–39px 的间距/图标单元格、百分比列宽保持不动，避免破坏模板对齐。
 */
private const val CELL_WIDTH_MIN = 40

private val CSS_PX_WIDTH = Regex("(?i)(?<![-\\w])width\\s*:\\s*(\\d{2,4})\\s*px")
private val ATTR_PX_WIDTH = Regex("(?i)\\bwidth\\s*=\\s*[\"']?\\s*(\\d{2,4})")
private val TABLE_LIKE_TAG = Regex("(?is)<(table|td|th)\\b[^>]*>")

internal fun normalizeFixedWidths(html: String): String {
    val cssFixed = CSS_PX_WIDTH.replace(html) { m ->
        if (m.groupValues[1].toInt() >= FIXED_WIDTH_MIN) "max-width:100%" else m.value
    }
    // 只改表格/单元格的属性宽度：<img width="600"> 保持 600，避免在大屏上被拉成 100% 放大
    return TABLE_LIKE_TAG.replace(cssFixed) { tag ->
        val isCell = !tag.value.startsWith("<table", ignoreCase = true)
        var value = ATTR_PX_WIDTH.replace(tag.value) { m ->
            val w = m.groupValues[1].toInt()
            when {
                isCell && w >= CELL_WIDTH_MIN -> ""          // 列宽去掉，避免多列累加后溢出
                w >= FIXED_WIDTH_MIN -> "width=\"100%\""      // 表格整体铺满视口
                else -> m.value
            }
        }
        // 单元格 style 里的像素列宽同样去掉；≥360 的已在上面统一换成 max-width:100%
        if (isCell) {
            value = CSS_PX_WIDTH.replace(value) { m ->
                if (m.groupValues[1].toInt() >= CELL_WIDTH_MIN) "" else m.value
            }
        }
        value
    }
}

private val IMG_SRCSET = Regex("(?i)srcset\\s*=\\s*[\"']([^\"']+)[\"']")

private val SIZE_ATTR = Regex("(?i)\\b(width|height)\\s*=\\s*[\"']?\\s*(\\d+)")
private val SIZE_STYLE = Regex("(?i)(?:^|[;\"'\\s])(width|height)\\s*:\\s*(\\d+)(?:px|%)")
private const val TRANSPARENT_GIF =
    "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"

/**
 * 追踪像素默认不加载：1×1 / 极小尺寸 / 完全没有有效宽高的 <img> 直接把 src 换成 1px 透明图，
 * WebView 就不会替发件人上报「已读」。用户要的是看图，不是被统计。
 * 判定只看尺寸声明：任一维 ≤3 视为追踪；两维都没有任何声明视为无有效宽高。
 */
private fun neutralizeTrackingPixels(html: String): String = html.replace(IMG_TAG) { m ->
    val tag = m.value
    if (!isTrackingPixel(tag)) tag
    else tag.replace(IMG_SRC_QUOTED) { s ->
        val quote = s.groupValues[1]
        "src=$quote$TRANSPARENT_GIF$quote"
    }
}

private fun isTrackingPixel(tag: String): Boolean {
    val lower = tag.lowercase()
    if (lower.contains("display:none") || lower.contains("display: none")) return true
    val sizes = SIZE_ATTR.findAll(lower).map { it.groupValues[2].toIntOrNull() }.toList() +
        SIZE_STYLE.findAll(lower).map { it.groupValues[2].toIntOrNull() }.toList()
    if (sizes.any { it != null && it <= 3 }) return true
    // 一个宽高声明都没有：无法判断呈现尺寸，按「无有效宽高」拦掉
    return sizes.isEmpty()
}

private val URL_ATTR = Regex("(?i)(?<![-\\w])(src|href|poster)\\s*=\\s*([\"'])(.*?)\\2")
private val SRCSET_ATTR = Regex("(?i)(?<![-\\w])srcset\\s*=\\s*([\"'])(.*?)\\1")
/** 内联 style 与 <style> 里的 url(...)：服务端签名代理返回的是 /api/... 相对地址，也要补全 */
private val CSS_URL = Regex("(?i)url\\(\\s*([\"']?)(.*?)\\1\\s*\\)")

/** 已带协议、纯锚点都不需要补全；`//host` 协议相对地址另行补成 https。 */
private val ABSOLUTE_URL = Regex("(?i)^(?:[a-z][a-z0-9+.\\-]*:|#)")

/**
 * 相对地址补全。邮件正文里的内联图片通常是服务端返回的 /api/... 相对路径，
 * 相对链接也以服务端为基准；补成绝对地址后，中性 origin 下图片代取与点击跳转都还能正常工作。
 */
private fun absolutizeHtmlUrls(html: String, baseUrl: String): String {
    val base = runCatching { URL(baseUrl) }.getOrNull() ?: return html
    fun resolve(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty() || ABSOLUTE_URL.containsMatchIn(v)) return raw
        // 协议相对地址：现在按正文文档的 https 协议补全，不能留给中性 origin 去解析
        if (v.startsWith("//")) return "https:$v"
        return runCatching { URL(base, v.replace("&amp;", "&")).toString() }.getOrNull() ?: raw
    }
    return html
        .replace(URL_ATTR) { m ->
            val quote = m.groupValues[2]
            "${m.groupValues[1]}=$quote${resolve(m.groupValues[3])}$quote"
        }
        .replace(SRCSET_ATTR) { m ->
            val quote = m.groupValues[1]
            val parts = m.groupValues[2].split(',').joinToString(", ") { part ->
                val trimmed = part.trim()
                val url = trimmed.substringBefore(' ')
                val descriptor = trimmed.removePrefix(url).trim()
                if (descriptor.isEmpty()) resolve(url) else resolve(url) + " " + descriptor
            }
            "srcset=$quote$parts$quote"
        }
        // CSS 背景图 / list-style-image：url("/api/proxy/image?...") 也要按服务端 origin 补全
        .replace(CSS_URL) { m ->
            val quote = m.groupValues[1]
            "url($quote${resolve(m.groupValues[2])}$quote)"
        }
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

/**
 * 「全部邮件」里非本人邮件的详情（含 userId=0 的「无人收件」）。
 *
 * 服务端 /email/detail 按 userId 过滤，这类邮件必然 404，所以正文走 /allEmail/list 的整行返回
 * （与网页端 /all-email 用列表正文渲染是同一份数据）。
 * 交互按正常邮件处理：回复/回复全部/转发走当前账号的发送接口；删除走 /allEmail/delete；
 * 已读、星标、翻译按服务端实际能力执行，服务端拒绝时如实提示，不伪造成功。
 * HTML 仍过 sanitizeEmailHtml，图片沿用自动加载 + 追踪像素拦截。
 */
@Composable
internal fun SiteMailDetailScreen(
    emailId: Long,
    onBack: () -> Unit,
    onReply: (MailDetail, ReplyAction) -> Unit,
    onToast: (String) -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    var mail by remember(emailId) { mutableStateOf<SiteMail?>(null) }
    var error by remember(emailId) { mutableStateOf("") }
    LaunchedEffect(emailId) {
        error = ""
        runCatching { Api.siteMail(emailId) }
            .onSuccess { mail = it }
            .onFailure { error = friendlyError(it) }
    }
    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        OTNavBar(title = "全部邮件", onLeading = onBack)
        val m = mail
        when {
            m == null && error.isNotEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(error, style = t.body, color = c.label2, modifier = Modifier.padding(24.dp))
            }

            m == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else -> SiteMailBody(m, onBack = onBack, onReply = onReply, onToast = onToast)
        }
    }
}

@Composable
private fun SiteMailBody(
    initial: SiteMail,
    onBack: () -> Unit,
    onReply: (MailDetail, ReplyAction) -> Unit,
    onToast: (String) -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var m by remember(initial) { mutableStateOf(initial) }
    val d = m.detail
    var moreOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var translated by remember(m.detail.emailId) { mutableStateOf("") }
    var showingOriginal by remember { mutableStateOf(false) }
    // 正文来自全站列表，同样是外部发件人控制的 HTML：与普通详情走同一套清洗
    val html = remember(m, translated, showingOriginal) {
        val body = if (translated.isNotEmpty() && !showingOriginal) translated else d.content
        sanitizeEmailHtml(body, serverOrigin())
    }
    val copyText = m.plain.ifBlank {
        d.content.replace(Regex("(?is)<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    }
    // 代理/防盗链/目标站拒绝 Cloudflare egress/SVG 都会让某张图取不到：
    // 取不到就换占位卡片，不做无限重试，也不当成致命错误。
    var failedImages by remember(d.emailId) { mutableStateOf<Set<String>>(emptySet()) }
    val failedSink = remember(d.emailId) { java.util.Collections.synchronizedSet(mutableSetOf<String>()) }
    LaunchedEffect(d.emailId) {
        while (true) {
            delay(900)
            val fresh = failedSink.toSet() - failedImages
            if (fresh.isNotEmpty()) failedImages = failedImages + fresh
        }
    }
    val bodyHtml = remember(html, failedImages) { replaceFailedImages(html, failedImages, serverOrigin()) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            Text(
                d.subject.ifBlank { "（无主题）" },
                style = t.title3,
                color = c.label,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${d.name}  ${TimeFmt.detailTime(d.time)}",
                style = t.footnote,
                color = c.label2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 收件人优先显示姓名（服务端 toName）；没有姓名才退回地址
            val toLabel = m.toName.ifBlank { m.toEmail }
            if (toLabel.isNotBlank()) {
                Text(
                    "收件：$toLabel",
                    style = t.caption2,
                    color = c.label2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        if (html.isBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), Alignment.TopStart) {
                Text("该邮件没有正文（服务端只存了纯文本或内容为空）", style = t.body, color = c.label2)
            }
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                factory = { viewCtx ->
                    WebView(viewCtx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.blockNetworkImage = false
                        // 正文基址是 https 中性 origin，http 图片由 App 代取后回填，这里放开混合内容
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.textZoom = 100
                        val webViewUa = settings.userAgentString.orEmpty()
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webViewClient = object : WebViewClient() {
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

                            // 远程图片与普通详情同一套代取：带 UA/Referer、拦内网、失败回透明图
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val req = request ?: return null
                                if (!InlineImages.looksLikeImage(req)) return null
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
                                return WebResourceResponse(
                                    "image/png",
                                    null,
                                    200,
                                    "OK",
                                    mapOf("Cache-Control" to "no-store"),
                                    ByteArrayInputStream(InlineImages.transparentPng),
                                ).also { failedSink.add(url) }
                            }

                            // 直接由 WebView 请求的代理图（SVG / egress 被拒）回 4xx/5xx：
                            // 记进失败集合换成占位卡片，避免只留一个破图图标
                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                if (request == null || errorResponse == null) return
                                if (!InlineImages.isImageRequest(request)) return
                                failedSink.add(request.url.toString())
                            }
                        }
                    }
                },
                update = { wv ->
                    val shell = bodyShell(
                        bodyHtml,
                        hex(c.label),
                        hex(c.blue),
                        hex(c.separator),
                        hex(c.label2),
                        "1",
                        "1",
                    )
                    if (wv.tag != shell) {
                        wv.tag = shell
                        wv.loadDataWithBaseURL(BODY_BASE_URL, shell, "text/html", "UTF-8", null)
                    }
                },
            )
        }
        // 有图取不到时给一行可读状态（代理对 SVG/拒绝 egress 的站点会失败，属预期）
        if (failedImages.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${failedImages.size} 张图片加载失败",
                    style = t.caption2,
                    color = c.label2,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "在浏览器打开",
                    style = t.caption2,
                    color = c.blue,
                    modifier = Modifier.tapArea {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(failedImages.first()))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { onToast("没有可用的浏览器") }
                    },
                )
            }
        }
        // 底栏与普通详情一致：回复/回复全部/转发/删除/更多
        OTActionBar(
            items = listOf(
                Triple("↩", "回复", { onReply(d, ReplyAction.REPLY) }),
                Triple("⇉", "回复全部", { onReply(d, ReplyAction.REPLY_ALL) }),
                Triple("↪", "转发", { onReply(d, ReplyAction.FORWARD) }),
                Triple("🗑", "删除", { confirmDelete = true }),
                Triple("⋯", "更多", { moreOpen = true }),
            ),
        )

        // 「更多」浮层：锚在底栏右上方，与普通详情同一位置
        if (moreOpen) {
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
                        MoreItem(if (d.unread) "标记为已读" else "标记为未读") {
                            moreOpen = false
                            scope.launch {
                                val wantUnread = !d.unread
                                runCatching {
                                    if (wantUnread) Api.markUnread(d.emailId) else Api.markRead(d.emailId)
                                }.onSuccess {
                                    // 服务端按 userId 过滤：操作 userId=0 的邮件会静默不生效。
                                    // 重新拉这一行对齐真实状态，变化了才提示成功，避免假成功。
                                    val fresh = runCatching { Api.siteMail(d.emailId) }.getOrNull()
                                    if (fresh != null && fresh.detail.unread == wantUnread) {
                                        m = fresh
                                        MailSync.changed()
                                        onToast(if (wantUnread) "已标记为未读" else "已标记为已读")
                                    } else {
                                        onToast("服务端未更新：该邮件不属于任何账号，已读状态按 userId 过滤，需要服务端支持")
                                    }
                                }.onFailure { onToast(friendlyError(it)) }
                            }
                        }
                        MoreItem(if (d.starred) "取消星标" else "添加星标") {
                            moreOpen = false
                            val next = !d.starred
                            scope.launch {
                                runCatching {
                                    if (next) Api.starAdd(d.emailId) else Api.starCancel(d.emailId)
                                }.onSuccess {
                                    m = m.copy(detail = d.copy(starred = next))
                                    MailSync.changed()
                                    onToast(if (next) "已加星标" else "已取消星标")
                                }.onFailure {
                                    // 服务端 star-service 会校验 email.userId，非本人邮件直接拒绝
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
                                showingOriginal = !showingOriginal
                            } else {
                                busy = true
                                scope.launch {
                                    runCatching { Api.translate(d.emailId, "zh") }
                                        .onSuccess { r ->
                                            when {
                                                r.content.isNotEmpty() -> {
                                                    translated = r.content
                                                    showingOriginal = false
                                                }
                                                r.alreadyInTargetLang -> onToast("正文已经是中文，无需翻译")
                                                else -> onToast("翻译失败：服务端未返回译文")
                                            }
                                        }
                                        .onFailure {
                                            // 翻译服务对非本人邮件返回 emailNotExist
                                            onToast(friendlyError(it))
                                        }
                                    busy = false
                                }
                            }
                        }
                        MoreItem("复制正文") {
                            moreOpen = false
                            if (copyText.isBlank()) {
                                onToast("没有可复制的正文")
                            } else {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("邮件正文", copyText))
                                onToast("已复制正文")
                            }
                        }
                        MoreItem("在浏览器打开") {
                            moreOpen = false
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(serverOrigin() + "all-email"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure { onToast("没有可用的浏览器") }
                        }
                    }
                }
            }
        }

        // 全站物理删除：/allEmail/delete 不进回收站，删前必须说明
        if (confirmDelete) {
            Dialog(
                onDismissRequest = { confirmDelete = false },
                properties = DialogProperties(),
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = c.bgElevated) {
                    Column(Modifier.padding(20.dp)) {
                        Text("永久删除这封邮件？", style = t.title3, color = c.label)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "「全部邮件」没有回收站，删除会从服务端直接移除这封邮件，不可恢复。",
                            style = t.footnote,
                            color = c.label2,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                "取消",
                                style = t.subhead,
                                color = c.blue,
                                modifier = Modifier.tapArea { confirmDelete = false },
                            )
                            Spacer(Modifier.width(24.dp))
                            Text(
                                "删除",
                                style = t.subhead,
                                color = c.red,
                                modifier = Modifier.tapArea {
                                    confirmDelete = false
                                    scope.launch {
                                        runCatching { Api.siteDelete(d.emailId) }
                                            .onSuccess {
                                                MailCache.removeItem(d.emailId)
                                                MailCache.expireLists()
                                                MailSync.changed()
                                                onToast("已删除")
                                                onBack()
                                            }
                                            // 权限不足（非管理员且没有 all-email:delete）时原样提示
                                            .onFailure { onToast(friendlyError(it)) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 第三方邮件详情（**只读**）。
 *
 * 数据来自本地 `otsmail_ext.db`（与服务端归档同源），不请求任何 IMAP 写接口；
 * 正文清洗与图片代取沿用普通详情的 [sanitizeEmailHtml] + [InlineImages]，不另开渲染路径。
 */
@Composable
internal fun ExtMailDetailScreen(
    localId: Long,
    sent: Boolean = false,
    onBack: () -> Unit,
    onReply: (ExtLocalMail, ReplyAction, String, Long) -> Unit = { _, _, _, _ -> },
    onToast: (String) -> Unit = {},
) {
    val c = OT.colors
    val t = OT.type
    var mail by remember(localId) { mutableStateOf<ExtLocalMail?>(null) }
    var accountAddress by remember(localId) { mutableStateOf("") }
    var accountId by remember(localId) { mutableStateOf(0L) }
    LaunchedEffect(localId, sent) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (sent) ExtMailStore.sentByLocalId(localId) else ExtMailStore.byLocalId(localId)
            }.getOrNull()
        }
        mail = loaded
        if (loaded != null) {
            val arr = runCatching { Api.extAccounts() }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optLong("ext_id", 0L) == loaded.extId) {
                        accountAddress = o.optString("address")
                        accountId = o.optLong("account_id", 0L)
                        break
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        OTNavBar(title = if (sent) "已发送（本地副本）" else "第三方邮件", onLeading = onBack)
        val m = mail
        if (m == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    if (sent) "这封已发送邮件不在本地记录里" else "这封邮件已不在本地，回到列表同步后再打开",
                    style = t.body,
                    color = c.label2,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            ExtMailBody(
                m = m,
                sent = sent,
                accountAddress = accountAddress,
                accountId = accountId,
                onDeleted = onBack,
                onReply = onReply,
                onToast = onToast,
            )
        }
    }
}

@Composable
private fun ExtMailBody(
    m: ExtLocalMail,
    sent: Boolean = false,
    accountAddress: String = "",
    accountId: Long = 0L,
    onDeleted: () -> Unit = {},
    onReply: (ExtLocalMail, ReplyAction, String, Long) -> Unit = { _, _, _, _ -> },
    onToast: (String) -> Unit = {},
) {
    val c = OT.colors
    val t = OT.type
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(m.localId) { mutableStateOf("") }
    var seenNow by remember(m.localId) { mutableStateOf(m.seen) }
    var starredNow by remember(m.localId) { mutableStateOf(m.starred) }
    var downloadBusy by remember(m.localId) { mutableIntStateOf(-1) }
    var confirmDelete by remember(m.localId) { mutableStateOf(false) }

    val atts = remember(m.attachmentsJson) {
        runCatching {
            val arr = JSONArray(m.attachmentsJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Triple(
                            o.optString("filename").ifBlank { "附件" },
                            o.optString("mime"),
                            o.optLong("size", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun action(scopeName: String, body: suspend () -> String) {
        if (busy.isNotEmpty()) return
        busy = scopeName
        scope.launch {
            runCatching { body() }
                .onSuccess { onToast(it) }
                .onFailure { onToast(ExtSyncError.reason(it)) }
            busy = ""
        }
    }

    val rawHtml = remember(m.bodyHtml, m.bodyText) {
        val text = m.bodyText
        when {
            m.bodyHtml.isNotBlank() -> m.bodyHtml
            text.isBlank() -> ""
            // 只有纯文本时包一层 pre，保留换行与缩进
            else -> "<pre style=\"white-space:pre-wrap;word-break:break-word;font:inherit\">" +
                text.escapeHtmlForBody() + "</pre>"
        }
    }
    val html = remember(rawHtml) { sanitizeEmailHtml(rawHtml, serverOrigin()) }

    var failedImages by remember(m.localId) { mutableStateOf(false) }
    val failedSink = remember(m.localId) { java.util.concurrent.atomic.AtomicBoolean(false) }
    LaunchedEffect(m.localId) {
        while (true) {
            delay(900)
            if (failedSink.get()) failedImages = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            Text(
                m.subject.ifBlank { "（无主题）" },
                style = t.title3,
                color = c.label,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${m.fromName.ifBlank { m.fromAddr }}  ${TimeFmt.detailTime(m.dateMs.toString())}",
                style = t.footnote,
                color = c.label2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val toLabel = m.toName.ifBlank { m.toAddr }
            if (toLabel.isNotBlank()) {
                Text(
                    "收件：$toLabel",
                    style = t.caption2,
                    color = c.label2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            // 说明这封信的数据边界：收到的信能对上游做已读/星标/删除；本地已发送副本不能
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                    .background(c.bgInput)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ot_info),
                    contentDescription = null,
                    tint = c.label2,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (sent) {
                        "已发送 · 本机保存的副本，不会同步到其他设备；回信/转发会使用 ${accountAddress.ifBlank { "这个外部邮箱" }}"
                    } else {
                        "第三方邮箱 · 已读 / 星标 / 删除直接作用在 ${accountAddress.ifBlank { "对方服务器" }} 上"
                    },
                    style = t.caption1,
                    color = c.label2,
                    modifier = Modifier.weight(1f),
                )
            }
            if (atts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                atts.forEachIndexed { index, (name, _, size) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (sent) Modifier
                                else Modifier.clip(RoundedCornerShape(OT.dimens.rSm.dp))
                                    .clickable(enabled = downloadBusy < 0) {
                                        downloadBusy = index
                                        scope.launch {
                                            runCatching {
                                                ExtImapActions.downloadAttachment(
                                                    ctx, m.extId, m.uid, index, name,
                                                )
                                            }
                                                .onSuccess { f ->
                                                    onToast("已保存到 ${f.parentFile?.name ?: "下载"}/${f.name}")
                                                }
                                                .onFailure { onToast(ExtSyncError.reason(it)) }
                                            downloadBusy = -1
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AttachFile, null, tint = c.label2, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            name,
                            style = t.caption1,
                            color = c.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        when {
                            sent -> Text("副本不含附件内容", style = t.caption2, color = c.label2)
                            downloadBusy == index -> CircularProgressIndicator(
                                Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.label2,
                            )
                            else -> Text(bytesLabel(size) + " · 点按下载", style = t.caption2, color = c.blue)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (html.isBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), Alignment.TopStart) {
                Text("这封邮件没有可显示的正文", style = t.body, color = c.label2)
            }
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                factory = { viewCtx ->
                    WebView(viewCtx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.blockNetworkImage = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.textZoom = 100
                        val webViewUa = settings.userAgentString.orEmpty()
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webViewClient = object : WebViewClient() {
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

                            // 与普通详情共用一套图片代取：带 UA/Referer、拦内网、失败回透明图
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val req = request ?: return null
                                if (!InlineImages.looksLikeImage(req)) return null
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
                                failedSink.set(true)
                                return WebResourceResponse(
                                    "image/png",
                                    null,
                                    200,
                                    "OK",
                                    mapOf("Cache-Control" to "no-store"),
                                    ByteArrayInputStream(InlineImages.transparentPng),
                                )
                            }
                        }
                    }
                },
                update = { wv ->
                    val shell = bodyShell(
                        html,
                        hex(c.label),
                        hex(c.blue),
                        hex(c.separator),
                        hex(c.label2),
                        "1",
                        "1",
                    )
                    if (wv.tag != shell) {
                        wv.tag = shell
                        wv.loadDataWithBaseURL(BODY_BASE_URL, shell, "text/html", "UTF-8", null)
                    }
                },
            )
        }
        if (failedImages) {
            Text(
                "部分图片加载失败",
                style = t.caption2,
                color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (!sent) {
            // 写操作底栏：全部直连 IMAP，失败时给出具体原因，不假装成功
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExtActionChip("回复") { onReply(m, ReplyAction.REPLY, accountAddress, accountId) }
                ExtActionChip("回复全部") { onReply(m, ReplyAction.REPLY_ALL, accountAddress, accountId) }
                ExtActionChip("转发") { onReply(m, ReplyAction.FORWARD, accountAddress, accountId) }
                ExtActionChip(if (starredNow) "取消星标" else "星标", busy = busy == "star") {
                    action("star") {
                        ExtImapActions.setStarred(m.extId, m.localId, m.uid, !starredNow)
                        starredNow = !starredNow
                        if (starredNow) "已在服务器加星标" else "已取消星标"
                    }
                }
                ExtActionChip(if (seenNow) "标记未读" else "标记已读", busy = busy == "seen") {
                    action("seen") {
                        ExtImapActions.setSeen(m.extId, m.localId, m.uid, !seenNow)
                        seenNow = !seenNow
                        if (seenNow) "已标记为已读" else "已标记为未读"
                    }
                }
                if (busy == "delete") {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.label2)
                } else {
                    ExtActionChip("删除", tint = c.red) { confirmDelete = true }
                }
            }
        }
    }

    if (confirmDelete) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            Column(
                Modifier.clip(RoundedCornerShape(OT.dimens.rLg.dp)).background(c.bgGrouped)
                    .padding(20.dp),
            ) {
                Text("删除这封邮件？", style = t.title3, color = c.label)
                Spacer(Modifier.height(8.dp))
                Text(
                    "邮件会从收件箱移动到服务器回收站（Trash），可在邮箱网页版恢复。",
                    style = t.body,
                    color = c.label2,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "取消",
                        style = t.body.copy(fontWeight = FontWeight.Medium),
                        color = c.label2,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { confirmDelete = false }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "删除",
                        style = t.body.copy(fontWeight = FontWeight.Medium),
                        color = c.red,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable {
                                confirmDelete = false
                                action("delete") {
                                    val r = ExtImapActions.deleteToTrash(m.extId, m.localId, m.uid)
                                    onDeleted()
                                    "已移动到回收站（${r.trashName}）"
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 外部邮件详情底栏的小胶囊按钮；busy 时替换成转圈，避免连点。 */
@Composable
private fun ExtActionChip(
    label: String,
    tint: Color? = null,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val c = OT.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.bgInput)
            .clickable(enabled = !busy) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.label2)
        } else {
            Text(
                label,
                style = OT.type.footnote.copy(fontWeight = FontWeight.Medium),
                color = tint ?: c.label,
            )
        }
    }
}

private fun String.escapeHtmlForBody(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\n", "<br>")

private fun bytesLabel(n: Long): String = when {
    n <= 0 -> ""
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format("%.1f MB", n / 1024.0 / 1024.0)
}
