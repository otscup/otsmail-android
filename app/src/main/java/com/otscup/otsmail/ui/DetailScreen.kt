package com.otscup.otsmail.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.otscup.otsmail.R
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.data.MailDetail
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

/** 详情里「更多」弹出的分类（网页端 emailMoveCategory 的目标分类）。 */
private val MOVE_TARGETS = listOf(
    "primary" to "主要",
    "social" to "社交",
    "promotion" to "推广",
    "updates" to "动态",
)

/**
 * 读信页：对标三星/Google 邮件——通栏无卡片、正文左右 16dp、HTML 图片不超出内容区。
 * 头部只有返回 + 上/下封切换；标题常规字重，日期灰色小字；发件人 outline chip + 蓝色「详细信息」。
 * 底栏固定五项，长正文在 WebView 内滚动。
 */
@Composable
fun DetailScreen(
    emailId: Long,
    onBack: () -> Unit,
    onReply: (MailDetail) -> Unit,
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
    var busy by remember { mutableStateOf(false) }
    var detailSheet by remember { mutableStateOf(false) }
    var translatedError by remember { mutableStateOf("") }
    var bodyHeightDp by remember { mutableStateOf(0) }
    var moreOpen by remember { mutableStateOf(false) }
    var moveSheet by remember { mutableStateOf(false) }
    /** 无缓存 + 拉正文失败（服务端 501 / 超时）：给「重试」，别把失败伪装成「没有正文」 */
    var loadFailed by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }

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
                // 正文：译文命中时看译文，否则看原文；两者都空才显示「没有正文」占位，避免白屏
                val bodyHtml = sanitizeEmailHtml(
                    if (translated.isNotEmpty() && !showingOriginal) translated else m.content,
                )
                val extrasHeight = (if (translated.isNotEmpty()) 30.dp else 0.dp) +
                    (if (bodyHtml.isNotBlank()) 30.dp else 0.dp) +
                    (if (m.attachments.isNotEmpty()) (1.dp + 44.dp * m.attachments.size) else 0.dp) +
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
                // 图片开关：默认屏蔽远程图片（防 IP/已读回执泄露），用户显式点开才加载
                if (bodyHtml.isNotBlank()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (showImages) "外部图片已显示" else "外部图片已屏蔽",
                            style = t.caption1,
                            color = c.label2,
                        )
                        Spacer(Modifier.weight(1f))
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
                                    settings.useWideViewPort = false
                                    settings.loadWithOverviewMode = false
                                    settings.setSupportZoom(false)
                                    settings.builtInZoomControls = false
                                    settings.textZoom = 100
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

                // 附件：平铺列表，不再套卡片；点按走系统下载（服务端只给 key，地址在本端拼）
                if (m.attachments.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OTDivider(inset = 16, strong = true)
                        m.attachments.forEach { att ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .tapArea { downloadAttachment(context, att.key, att.filename, onToast) }
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(painterResource(R.drawable.ot_link), contentDescription = null, tint = c.label2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(att.filename.ifEmpty { "附件" }, style = t.subhead, color = c.label, maxLines = 1, modifier = Modifier.weight(1f))
                            }
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
                    Triple("↩", "回复", { onReply(m) }),
                    Triple("⇉", "回复全部", { onReply(m) }),
                    Triple("↪", "转发", { onReply(m) }),
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
                                    .onSuccess { MailCache.removeItem(m.emailId); MailSync.changed(); onToast("已标记为垃圾邮件"); onBack() }
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
    key: String,
    filename: String,
    onToast: (String) -> Unit,
) {
    val url = Api.fileUrl(key)
    if (url.isEmpty()) {
        onToast("附件地址缺失，无法下载")
        return
    }
    runCatching {
        // 文件名来自邮件，可能是 "../../evil" 或带控制字符；只取 basename，交给 DownloadManager 的
        // 只能是安全的基本名，避免路径穿越/覆盖到别的目录。
        val name = filename
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
            .trim()
            .trimStart('.')
            .take(120)
            .ifEmpty { "attachment" }
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
