package com.otscup.otsmail.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.MailDetail
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder

@Composable
fun DetailScreen(emailId: Long, onBack: () -> Unit, onReply: (MailDetail) -> Unit) {
    val t = OT.type
    val c = OT.colors
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    var mail by remember { mutableStateOf<MailDetail?>(null) }
    var error by remember { mutableStateOf("") }
    var translated by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // 正文按实际内容高度撑开：短正文下面不再留一大片空白，翻译行紧跟正文
    var bodyH by remember { mutableStateOf(220.dp) }

    LaunchedEffect(emailId) {
        runCatching { Api.detail(emailId) }
            .onSuccess { m ->
                mail = m
                runCatching { Api.markRead(m.emailId) }
            }
            .onFailure { error = it.message ?: "加载失败" }
    }

    val m = mail
    Column(Modifier.fillMaxSize().background(c.bgCard).statusBarsPadding().navigationBarsPadding()) {
        OTNavBar(
            title = m?.name?.ifEmpty { m.from } ?: "",
            onLeading = onBack,
        ) {
            if (m != null) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).tapArea {
                        val next = !m.starred
                        mail = m.copy(starred = next)
                        scope.launch {
                            runCatching { if (next) Api.starAdd(m.emailId) else Api.starCancel(m.emailId) }
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (m.starred) Icons.Filled.Star else Icons.Outlined.StarBorder, null, tint = if (m.starred) c.orange else c.label2, modifier = Modifier.size(22.dp)) }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).tapArea {
                        scope.launch { runCatching { Api.trash(listOf(m.emailId)) }.onSuccess { onBack() } }
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Delete, null, tint = c.label2, modifier = Modifier.size(20.dp)) }
            }
        }
        if (error.isNotEmpty()) {
            Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(16.dp))
            return@Column
        }
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", style = t.subhead, color = c.label2)
            }
            return@Column
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    m.subject.ifEmpty { "（无主题）" },
                    style = t.title2,
                    color = c.label,
                )
                Spacer(Modifier.height(OTSpace.s2))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OTAvatar(m.name.ifEmpty { m.from }, size = 34)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name.ifEmpty { m.from }, style = t.subhead, color = c.label)
                        Text(fmtFull(m.time), style = t.caption1, color = c.label3)
                    }
                    val label = categoryLabel(m.category)
                    if (label.isNotEmpty()) OTChip(label, c.blue)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(bodyH)
                    .padding(top = 8.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    // 用 contentHeight 而不是注入 JS：正文里 JS 是关着的（安全考虑）
                                    val px = view?.contentHeight?.toFloat() ?: 0f
                                    if (px > 0f) {
                                        bodyH = (px / density.density + 8f).coerceIn(120f, 720f).dp
                                    }
                                }
                            }
                        }
                    },
                    update = { wv ->
                        val html = translated.ifEmpty { m.content }
                        // 正文配色跟随主题，否则暗色下黑字压在深色卡上看不见
                        val fg = hex(c.label)
                        val sys = hex(c.label2)
                        val link = hex(c.blue)
                        val quote = hex(c.separator)
                        val shell = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{width:100%;overflow-x:hidden;-webkit-text-size-adjust:100%}
body{margin:0;padding:0 16px 16px;background:transparent;color:$fg;
font:17px/1.5 -apple-system,"Noto Sans SC",sans-serif;word-break:break-word}
img,video{max-width:100%;height:auto}a{color:$link}
blockquote{margin:8px 0;padding-left:10px;border-left:3px solid $quote;color:$sys}
</style></head><body>$html</body></html>"""
                        wv.loadDataWithBaseURL(Api.base.removeSuffix("/api") + "/", shell, "text/html", "utf-8", null)
                    },
                )
            }
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).tapArea {
                        if (busy) return@tapArea
                        busy = true
                        scope.launch {
                            runCatching { Api.translate(m.emailId, "zh") }
                                .onSuccess { translated = it }
                                .onFailure { error = it.message ?: "翻译失败" }
                            busy = false
                        }
                    }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("译", style = t.subhead, color = c.blue)
                    Spacer(Modifier.size(10.dp))
                    Text(if (translated.isEmpty()) "翻译邮件" else "已翻译为简体中文", style = t.body, color = c.label)
                    Spacer(Modifier.weight(1f))
                    Text(if (busy) "…" else "›", style = t.body, color = c.label3)
                }
            }
            if (m.attachments.isNotEmpty()) {
                Spacer(Modifier.height(OTSpace.s3))
                OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                    m.attachments.forEachIndexed { i, att ->
                        if (i > 0) OTDivider(inset = 16)
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.AttachFile, null, tint = c.label2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(att, style = t.subhead, color = c.label, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(OTSpace.s4))
        }

        OTActionBar(
            items = listOf(
                Triple("↩", "回复", { onReply(m) }),
                Triple("⇉", "回复全部", { onReply(m) }),
                Triple("↪", "转发", { onReply(m) }),
                Triple("🗑", "删除", {
                    scope.launch { runCatching { Api.trash(listOf(m.emailId)) }.onSuccess { onBack() } }
                }),
            ),
            dangerIndex = 3,
        )
    }
}

// 主题色转 CSS 十六进制，正文 HTML 里用
private fun hex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())
