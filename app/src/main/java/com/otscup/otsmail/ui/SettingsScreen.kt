package com.otscup.otsmail.ui
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Check

/** 回车键行为三档（存服务端 sendOnEnter） */
private val SEND_MODES = listOf(
    "enter" to "回车发送",
    "ctrl" to "Ctrl + Enter 发送",
    "off" to "只点发送按钮",
)

@Composable
fun SettingsScreen(
    session: Session,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onOpenSysSettings: () -> Unit = {},
    onOpenAdmin: (String) -> Unit = {},
) {
    val c = OT.colors
    val t = OT.type
    val scope = rememberCoroutineScope()

    var signature by remember { mutableStateOf(session.signature) }
    var sendOnEnter by remember { mutableStateOf(session.sendOnEnter.ifEmpty { "enter" }) }
    var saving by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    // 进页面拉一次最新值（别的地方可能改过）
    LaunchedEffect(Unit) {
        runCatching { Api.me() }.onSuccess {
            signature = it.signature
            sendOnEnter = it.sendOnEnter.ifEmpty { "enter" }
            session.signature = it.signature
            session.sendOnEnter = it.sendOnEnter
            loaded = true
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding(),
    ) {
        OTNavBar(title = "设置", onLeading = onBack) {
            Box(
                Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).tapArea {
                    if (loaded && !saving) {
                        saving = true
                        scope.launch {
                            runCatching { Api.updateMySetting(signature, sendOnEnter) }
                                .onSuccess {
                                    session.signature = it.signature
                                    session.sendOnEnter = it.sendOnEnter
                                    onToast("已保存")
                                }
                                .onFailure { onToast(it.message ?: "保存失败") }
                            saving = false
                        }
                    }
                }.padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(
                    if (saving) "保存中…" else "保存",
                    style = t.body,
                    color = if (!loaded || saving) c.label3 else c.blue,
                )
            }
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            // 账号抬头：头像 + 名称 + 邮箱
            OTGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(c.blue),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (session.name.ifEmpty { session.email }).take(1).uppercase(),
                            style = t.title3, color = Color.White,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(session.name.ifEmpty { "邮箱账户" }, style = t.title3, color = c.label)
                        Text(session.email, style = t.footnote, color = c.label2)
                    }
                }
            }

            OTGroupHeader("签名")
            OTGroup(Modifier.padding(horizontal = 16.dp)) {
                BasicTextField(
                    value = signature,
                    onValueChange = { if (it.length <= 2000) signature = it },
                    textStyle = t.body.copy(color = c.label),
                    cursorBrush = SolidColor(c.blue),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(16.dp),
                    decorationBox = { inner ->
                        if (signature.isEmpty()) {
                            Text("写邮件时自动附在正文末尾", style = t.body, color = c.label3)
                        }
                        inner()
                    },
                )
            }

            OTGroupHeader("回车键发送")
            OTGroup(Modifier.padding(horizontal = 16.dp)) {
                SEND_MODES.forEachIndexed { i, mode ->
                    if (i > 0) OTDivider(inset = 16)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).tapArea { sendOnEnter = mode.first }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(mode.second, style = t.body, color = c.label, modifier = Modifier.weight(1f))
                        if (sendOnEnter == mode.first) Icon(Icons.Outlined.Check, null, tint = c.blue, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Text(
                "只影响 AI 对话与聊天输入框；换行始终是 Shift + Enter。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            OTGroupHeader("管理")
            OTGroup(Modifier.padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).tapArea { onOpenSysSettings() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(30.dp).background(c.gray.copy(alpha = 0.14f), RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, tint = c.gray, modifier = Modifier.size(18.dp))
                    }
                    Text("系统设置", style = t.body, color = c.label, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.label3, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.size(8.dp))
            AdminLinks(onOpenAdmin)
            Text(
                "只有带 setting:set 权限的账号（默认管理员）能改服务端的 AI 与翻译配置。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            OTGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).tapArea {
                        session.clear()
                        onBack()
                    }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text("退出登录", style = t.body, color = c.red) }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** 分组标题（旧调用点仍在使用，转调 OTGroupHeader） */
@Composable
internal fun GroupTitle(text: String) {
    OTGroupHeader(text)
}
