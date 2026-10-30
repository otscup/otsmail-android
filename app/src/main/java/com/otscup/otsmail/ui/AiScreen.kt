package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.AiAction
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon

private data class Msg(
    val role: String,            // "me" | "ai"
    val text: String,
    val actions: List<AiAction> = emptyList(),
)

/**
 * AI 对话：真调 /ai/command（服务端用管理员配的模型 Key）。
 * 回复里的 actions 逐条显示执行回执，失败的要如实标红——样张 14-ai 就是这么画的。
 */
@Composable
fun AiScreen(session: Session, onBack: () -> Unit, onOpenCompose: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val msgs = remember { mutableStateListOf(Msg("ai", "你好，我可以在这台账户的所有邮件里找东西、改状态、起草回复。")) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        busy = true
        input = ""
        msgs.add(Msg("me", q))
        scope.launch {
            runCatching {
                val history = msgs.map { it.role to it.text }
                Api.ai(q, session.accountId, history)
            }.onSuccess { (reply, acts) ->
                msgs.add(Msg("ai", reply.ifEmpty { "（没有文字回复）" }, acts))
            }.onFailure {
                msgs.add(Msg("ai", "AI 没回话：" + (it.message ?: "未知错误")))
            }
            busy = false
            listState.animateScrollToItem(msgs.lastIndex.coerceAtLeast(0))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgCard)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        OTNavBar(title = "AI 助手", onLeading = onBack) {
            Box(
                Modifier.height(OTSpace.s8).padding(horizontal = OTSpace.s2).tapArea { onOpenCompose() },
                contentAlignment = Alignment.Center,
            ) { Text("写邮件", style = t.footnote, color = c.blue) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = d.sp3.dp),
            verticalArrangement = Arrangement.spacedBy(d.sp2.dp),
        ) {
            items(msgs) { m -> Bubble(m) }
            item { Spacer(Modifier.height(d.sp2.dp)) }
        }

        // 快捷指令（样张 .aichip）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.sp3.dp, vertical = d.sp1.dp),
            horizontalArrangement = Arrangement.spacedBy(d.sp2.dp),
        ) {
            listOf("汇总今天的未读").forEach { label ->
                Box(
                    modifier = Modifier
                        .background(c.bgInput, RoundedCornerShape(50))
                        .pressable(shape = RoundedCornerShape(50)) { send(label) }
                        .padding(horizontal = OTSpace.s3, vertical = OTSpace.s2),
                ) { Text(label, style = t.caption1, color = c.label2) }
            }
        }

        // 输入条（样张 .aiinput）：灰底圆角 + 蓝色发送
        Row(
            modifier = Modifier.fillMaxWidth().padding(d.sp3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.sp2.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(c.bgInput, RoundedCornerShape(20.dp))
                    .padding(horizontal = d.sp3.dp, vertical = d.sp2.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = t.body.copy(color = c.label),
                    cursorBrush = SolidColor(c.blue),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("让 AI 帮你找或做点什么", style = t.body, color = c.label3)
                        inner()
                    },
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(if (busy || input.isBlank()) c.label3 else c.blue, CircleShape)
                    .pressable(scale = 0.9f, shape = CircleShape) { send(input) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.ArrowUpward, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun Bubble(m: Msg) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val mine = m.role == "me"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(if (mine) c.blue else c.bgInput, RoundedCornerShape(18.dp))
                .padding(horizontal = d.sp3.dp, vertical = d.sp2.dp),
        ) {
            Text(m.text, style = t.body, color = if (mine) androidx.compose.ui.graphics.Color.White else c.label)
        }
        // 动作回执：成功的灰、失败的蓝底红字（样张 .chat__act--ok / --fail）
        m.actions.forEach { a ->
            Row(
                modifier = Modifier
                    .padding(top = OTSpace.s1)
                    .background(if (a.ok) c.bgInput else c.bgInput, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (a.ok) "✓ ${a.type}" else "✕ ${a.type}${if (a.error.isEmpty()) "" else " · " + a.error}",
                    style = t.caption1,
                    color = if (a.ok) c.label2 else c.red,
                )
            }
        }
    }
}
