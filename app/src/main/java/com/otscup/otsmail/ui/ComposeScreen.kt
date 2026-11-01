package com.otscup.otsmail.ui
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon

import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.Drafts
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ComposeScreen(
    accountId: Long,
    to: String,
    subject: String,
    quoted: String = "",
    onDone: () -> Unit,
) {
    val t = OT.type
    val c = OT.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var toText by remember { mutableStateOf(to) }
    var subjectText by remember { mutableStateOf(subject) }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var picks by remember { mutableStateOf(listOf<String>()) }
    val signature = remember { Session(ctx).signature }
    val attachments = remember { mutableListOf<JSONObject>() }

    var fromId by remember { mutableStateOf(accountId) }
    var fromEmail by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf(listOf<Pair<Long, String>>()) }
    var pickingFrom by remember { mutableStateOf(false) }
    var ccText by remember { mutableStateOf("") }
    var bccText by remember { mutableStateOf("") }
    var showCc by remember { mutableStateOf(false) }
    var showBcc by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { Api.accounts() }.getOrNull()?.let { list ->
            accounts = list
            fromEmail = list.firstOrNull { it.first == fromId }?.second ?: list.firstOrNull()?.second ?: ""
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.take(10).forEach { uri ->
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                if (bytes.size > 5 * 1024 * 1024) return@runCatching
                val name = ctx.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                attachments.add(
                    JSONObject()
                        .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("filename", name)
                        .put("size", bytes.size)
                        .put("contentType", mime),
                )
                picks = picks + name
            }
        }
    }

    fun send() {
        if (busy) return
        busy = true
        error = ""
        val sig = signature.trim()
        val text = (body + if (sig.isEmpty()) "" else if (body.isEmpty()) sig else "\n\n$sig").ifEmpty { " " }
        val html = body.split("\n\n").joinToString("") { "<p>${it.replace("\n", "<br>")}</p>" } +
            if (sig.isEmpty()) "" else "<p>${sig.replace("\n", "<br>")}</p>" + quoted
        scope.launch {
            val parts = { s: String -> s.split(",", ";", " ").map { it.trim() }.filter { it.contains("@") } }
            runCatching {
                Api.send(
                    fromId, parts(toText), subjectText, text, html, attachments,
                    cc = parts(ccText), bcc = parts(bccText),
                )
            }
                .onSuccess { onDone() }
                .onFailure { error = it.message ?: "发送失败" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgCard).statusBarsPadding().navigationBarsPadding().imePadding()) {
        OTNavBar(
            title = if (quoted.isEmpty()) "写邮件" else "回复",
            onLeading = {
                Drafts.save(ctx, toText, subjectText, body.ifEmpty { quoted.take(0) })
                onDone()
            },
            leadingText = "取消",
        ) {
            Box(
                Modifier.height(44.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp))
                    .tapArea { send() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "发送中" else "发送",
                    style = t.body,
                    color = if (busy) c.label3 else c.blue,
                )
            }
        }
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (accounts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).tapArea { pickingFrom = !pickingFrom }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("发件人", style = t.footnote, color = c.label2, modifier = Modifier.width(56.dp))
                    Text(fromEmail, style = t.body, color = c.label, maxLines = 1, modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Outlined.ChevronRight, contentDescription = null,
                        tint = c.label3, modifier = Modifier.size(18.dp),
                    )
                }
                if (pickingFrom) {
                    accounts.forEach { (id, email) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).tapArea {
                                fromId = id
                                fromEmail = email
                                pickingFrom = false
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
                OTDivider(inset = 16)
            }
            OTFieldRow("收件人", toText, { toText = it }, hint = "someone@example.com")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "抄送", style = t.footnote, color = c.blue,
                    modifier = Modifier.tapArea { showCc = !showCc }.padding(end = 20.dp),
                )
                Text(
                    "密送", style = t.footnote, color = c.blue,
                    modifier = Modifier.tapArea { showBcc = !showBcc },
                )
            }
            if (showCc) OTFieldRow("抄送", ccText, { ccText = it }, hint = "多个用逗号分隔")
            if (showBcc) OTFieldRow("密送", bccText, { bccText = it }, hint = "多个用逗号分隔")
            OTDivider(inset = 16)
            OTFieldRow("主题", subjectText, { subjectText = it }, hint = "主题")
            OTDivider(inset = 16)
            // 格式工具栏（样张 02-compose .toolbar）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icons = listOf(
                    Icons.Outlined.FormatBold,
                    Icons.Outlined.FormatItalic,
                    Icons.Outlined.FormatUnderlined,
                    Icons.Outlined.FormatStrikethrough,
                    Icons.Outlined.FormatListBulleted,
                    Icons.Outlined.FormatListNumbered,
                    Icons.Outlined.FormatQuote,
                )
                icons.forEach { ic ->
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(ic, contentDescription = null, tint = c.label2, modifier = Modifier.size(18.dp)) }
                }
            }
            OTDivider(inset = 16)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (body.isEmpty() && quoted.isEmpty()) {
                    Text("正文", style = t.body, color = c.label3)
                }
                BasicTextField(
                    value = body,
                    onValueChange = { body = it },
                    textStyle = t.body.copy(color = c.label),
                    cursorBrush = SolidColor(c.blue),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                if (signature.isNotEmpty()) {
                    Text(signature, style = t.subhead, color = c.label2, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        if (picks.isNotEmpty()) {
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                picks.forEachIndexed { i, name ->
                    if (i > 0) OTDivider(inset = 16)
                    val kb = (attachments.getOrNull(i)?.optInt("size") ?: 0) / 1024
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = c.label2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = t.subhead, color = c.label, maxLines = 1)
                            Text("${kb.coerceAtLeast(1)} KB", style = t.caption1, color = c.label3)
                        }
                        Box(
                            Modifier.size(32.dp).tapArea {
                                picks = picks.filterIndexed { idx, _ -> idx != i }
                                attachments.removeAt(i)
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Close, contentDescription = "移除", tint = c.label3, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
        OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).tapArea {
                    picker.launch(arrayOf("image/*", "application/pdf", "text/plain", "*/*"))
                }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = c.label2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (picks.isEmpty()) "添加附件" else "添加附件（已选 ${picks.size} 个）", style = t.body, color = c.label)
                Spacer(Modifier.weight(1f))
                Text("最多 10 个，单个 ≤5MB", style = t.caption1, color = c.label3)
            }
        }
        Spacer(Modifier.height(OTSpace.s3))
    }
}
