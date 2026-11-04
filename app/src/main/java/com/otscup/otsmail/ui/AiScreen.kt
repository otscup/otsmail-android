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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.otscup.otsmail.data.ApiException
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
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
@OptIn(ExperimentalLayoutApi::class)
fun AiScreen(session: Session, onBack: () -> Unit, onOpenCompose: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val msgs = remember { mutableStateListOf(Msg("ai", "你好，我可以在这台账户的所有邮件里找东西、改状态、起草回复。")) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // 出现「正在思考」气泡时把它滚进视野，用户才知道请求已经在跑
    androidx.compose.runtime.LaunchedEffect(busy) {
        if (busy) listState.animateScrollToItem((msgs.lastIndex + 1).coerceAtLeast(0))
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        busy = true
        input = ""
        msgs.add(Msg("me", q))
        scope.launch {
            // 服务端一次要 20 秒以上才回；busy 期间必须有「正在思考」的可见反馈，
            // 否则用户看到的就是「点了没反应」。
            runCatching {
                val history = msgs.map { it.role to it.text }
                Api.ai(q, session.accountId, history)
            }.onSuccess { (reply, acts) ->
                msgs.add(Msg("ai", reply.ifEmpty { "（没有文字回复）" }, acts))
            }.onFailure { e ->
                val reason = when {
                    e is ApiException && (e.httpCode == 503 || e.message.contains("未配置")) ->
                        "AI 服务未配置，请联系管理员"
                    // 401 不再单独弹窗，只在这里指向上方的全局提示
                    e is ApiException && e.httpCode == 401 -> "登录已过期，点顶部提示重新登录"
                    else -> friendlyError(e)
                }
                msgs.add(Msg("ai", "AI 没回话：$reason"))
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
            if (busy) {
                item(key = "thinking") { Bubble(Msg("ai", "正在思考…")) }
            }
            item { Spacer(Modifier.height(d.sp2.dp)) }
        }

        // 快捷指令（样张 .aichips：FlowRow 换行 + 4 个预设）
        val presets = listOf("汇总今天的未读", "找最近 7 天的验证码", "清理推广邮件", "起草一封新邮件")
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.sp3.dp, vertical = d.sp1.dp),
            horizontalArrangement = Arrangement.spacedBy(d.sp2.dp),
            verticalArrangement = Arrangement.spacedBy(d.sp2.dp),
        ) {
            presets.forEach { label ->
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .background(c.bgInput, RoundedCornerShape(50))
                        .pressable(shape = RoundedCornerShape(50)) { send(label) }
                        .padding(horizontal = OTSpace.s3),
                    contentAlignment = Alignment.Center,
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
                        if (input.isEmpty()) Text("让 AI 在所有邮件里找或做点什么", style = t.body, color = c.label3)
                        inner()
                    },
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (busy || input.isBlank()) c.label3 else c.blue, CircleShape)
                    .pressable(scale = 0.9f, shape = CircleShape) { send(input) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.ArrowUpward, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(17.dp)) }
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
                .background(
                    if (mine) c.blue else c.bgInput,
                    RoundedCornerShape(
                        topStart = 20.dp, topEnd = 20.dp,
                        bottomStart = if (mine) 20.dp else 6.dp,
                        bottomEnd = if (mine) 6.dp else 20.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(m.text, style = t.body, color = if (mine) androidx.compose.ui.graphics.Color.White else c.label)
        }
        // 动作回执：纯文字，成功绿、失败红（样张 .chat__acts）
        m.actions.forEach { a ->
            Text(
                text = if (a.ok) a.type else "${a.type}${if (a.error.isEmpty()) "" else " · " + a.error}",
                style = t.caption1,
                color = if (a.ok) c.green else c.red,
                modifier = Modifier.padding(top = OTSpace.s1),
            )
        }
    }
}
