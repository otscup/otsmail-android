package com.otscup.otsmail.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.AiAction
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.ApiException
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.motion.rememberReduceMotion
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon

private data class Msg(
    val role: String,            // "me" | "ai"
    val text: String,
    val actions: List<AiAction> = emptyList(),
)

/**
 * AI 对话：从屏幕底部弹出的 3/4 高面板，真调 /ai/command（服务端用管理员配的模型 Key）。
 * 回复里的 actions 逐条显示执行回执，失败的要如实标红；每条 AI 输出可复制或带入写信页。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AiScreen(
    session: Session,
    onClose: () -> Unit,
    onOpenCompose: () -> Unit,
    onReply: (String) -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 底部面板的出现/收起：动画跑完再让宿主移除本层，避免收起时被硬切
    val vis = remember { MutableTransitionState(false) }
    val reduce = rememberReduceMotion()
    var closing by remember { mutableStateOf(false) }
    var pendingNav by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(Unit) { vis.targetState = true }
    LaunchedEffect(vis.isIdle, vis.currentState, closing) {
        if (closing && vis.isIdle && !vis.currentState) {
            val nav = pendingNav
            pendingNav = null
            if (nav != null) nav() else onClose()
        }
    }

    /** 先播收起动画，结束再执行导航（写邮件/回复）或通知宿主关闭。 */
    fun closeThen(action: (() -> Unit)? = null) {
        if (closing) return
        pendingNav = action
        closing = true
        vis.targetState = false
    }
    BackHandler { closeThen() }

    val msgs = remember { mutableStateListOf(Msg("ai", "你好，我可以在这台账户的所有邮件里找东西、改状态、起草回复。")) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // 出现「正在思考」气泡时把它滚进视野，用户才知道请求已经在跑
    androidx.compose.runtime.LaunchedEffect(busy) {
        if (busy) listState.animateScrollToItem((msgs.lastIndex + 1).coerceAtLeast(0))
    }

    fun runAutoClassify() {
        if (busy) return
        busy = true
        msgs.add(Msg("me", "执行全箱 AI 智能分类整理"))
        scope.launch {
            runCatching {
                Api.aiClassify()
            }.onSuccess { res ->
                val count = res.optInt("count", res.optInt("classifiedCount", 0))
                msgs.add(Msg("ai", if (count > 0) "已成功完成全箱智能分类，整理了 $count 封邮件。" else "全箱智能分类已完成，当前邮件已处于最新归类。"))
            }.onFailure { e ->
                msgs.add(Msg("ai", "智能分类未能完成：${friendlyError(e)}"))
            }
            busy = false
            listState.animateScrollToItem(msgs.lastIndex.coerceAtLeast(0))
        }
    }

    fun send(text: String) {
        if (text == "🤖 智能分类整理全箱") {
            runAutoClassify()
            return
        }
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

    Box(Modifier.fillMaxSize().imePadding()) {
        // 遮罩：点空白处也能收起
        AnimatedVisibility(
            visibleState = vis,
            enter = fadeIn(tween(if (reduce) 0 else 180)),
            exit = fadeOut(tween(if (reduce) 0 else 160)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .pointerInput(Unit) { detectTapGestures { closeThen() } },
            )
        }

        // 底部面板：约 3/4 屏高，顶部圆角，滑入滑出
        AnimatedVisibility(
            visibleState = vis,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (reduce) {
                fadeIn(tween(0))
            } else {
                slideInVertically(tween(260)) { it } + fadeIn(tween(180))
            },
            exit = if (reduce) {
                fadeOut(tween(0))
            } else {
                slideOutVertically(tween(220)) { it } + fadeOut(tween(160))
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(c.bgCard)
                    .navigationBarsPadding()
                    // 面板空白处吞掉点击，避免穿透到遮罩把面板误关
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                // 标题栏：写邮件入口 + 明确的关闭按钮
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("AI 助手", style = t.title3, color = c.label, modifier = Modifier.weight(1f))
                    Text(
                        "写邮件",
                        style = t.footnote,
                        color = c.blue,
                        modifier = Modifier.tapArea { closeThen(onOpenCompose) },
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(c.bgInput)
                            .tapArea { closeThen() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "关闭",
                            tint = c.label2,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HorizontalDivider(color = c.separatorSoft)

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = d.sp3.dp),
                    verticalArrangement = Arrangement.spacedBy(d.sp2.dp),
                ) {
                    items(msgs) { m ->
                        Bubble(m, onReply = { closeThen { onReply(it) } })
                    }
                    if (busy) {
                        item(key = "thinking") { Bubble(Msg("ai", "正在思考…"), actionable = false) }
                    }
                    item { Spacer(Modifier.height(d.sp2.dp)) }
                }

                // 快捷指令（样张 .aichips：FlowRow 换行 + 预设）
                val presets = listOf("汇总今天的未读", "找最近 7 天的验证码", "🤖 智能分类整理全箱", "清理推广邮件", "起草一封新邮件")
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

                // 输入条（样张 .aiinput）：灰底圆角 + 蓝色发送；固定在面板底部
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
                    ) { Icon(Icons.Outlined.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(17.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg, onReply: (String) -> Unit = {}, actionable: Boolean = true) {
    val c = OT.colors
    val t = OT.type
    val context = LocalContext.current
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
            Text(m.text, style = t.body, color = if (mine) Color.White else c.label)
        }
        // 每条 AI 输出：复制原文 / 作为回信草稿带入写信页（不自动发送）
        if (!mine && actionable) {
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "复制",
                    style = t.caption1,
                    color = c.blue,
                    modifier = Modifier.tapArea {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("AI 回复", m.text))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    },
                )
                Text(
                    "回复",
                    style = t.caption1,
                    color = c.blue,
                    modifier = Modifier.tapArea { onReply(m.text) },
                )
            }
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
