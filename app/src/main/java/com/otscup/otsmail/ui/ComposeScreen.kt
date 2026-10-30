package com.otscup.otsmail.ui
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.Icon

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val attachments = remember { mutableListOf<JSONObject>() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.take(10).forEach { uri ->
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                if (bytes.size > 5 * 1024 * 1024) return@runCatching
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
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
        val text = body.ifEmpty { " " }
        val html = text.split("\n\n").joinToString("") { "<p>${it.replace("\n", "<br>")}</p>" } + quoted
        scope.launch {
            runCatching { Api.send(accountId, toText.split(",", ";", " ").filter { it.isNotBlank() }, subjectText, text, html, attachments) }
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
            OTFieldRow("收件人", toText, { toText = it }, hint = "someone@example.com")
            OTDivider(inset = 16)
            OTFieldRow("主题", subjectText, { subjectText = it }, hint = "主题")
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
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
        }
        OTGroup(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).tapArea {
                    picker.launch(arrayOf("image/*", "application/pdf", "text/plain", "*/*"))
                }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = c.label2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (picks.isEmpty()) "添加附件" else "已选 ${picks.size} 个附件", style = t.body, color = c.label)
                Spacer(Modifier.weight(1f))
                Text("最多 10 个，单个 ≤5MB", style = t.caption1, color = c.label3)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
