package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.SessionDeviceItem
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

/**
 * 登录设备与会话管理界面。
 * 支持查看当前设备与其他在线终端（IP、浏览器、操作系统、活跃时间），并提供远程下线踢出能力。
 */
@Composable
fun SessionScreen(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val scope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<SessionDeviceItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var kickTarget by remember { mutableStateOf<SessionDeviceItem?>(null) }
    var kicking by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        scope.launch {
            runCatching { Api.getSessions() }
                .onSuccess {
                    sessions = it
                    loading = false
                }
                .onFailure {
                    loading = false
                    onToast(friendlyError(it))
                }
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bgGrouped)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        OTNavBar(
            title = "登录设备管理",
            onLeading = onBack,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { reload() }
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = c.blue,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (loading && sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = c.blue, strokeWidth = 2.5.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    // 顶部安全说明卡片
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.blue.copy(alpha = 0.08f))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = c.blue,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "管理当前账号在各设备上的登录会话。若发现未知或非本人的可疑设备，建议立即将其下线并修改登录密码。",
                            style = t.footnote,
                            color = c.label2,
                            lineHeight = 18.sp,
                        )
                    }
                }

                val currentDevice = sessions.firstOrNull { it.current }
                val otherDevices = sessions.filterNot { it.current }

                if (currentDevice != null) {
                    item {
                        OTGroupHeader("当前设备")
                        OTGroup(Modifier.padding(horizontal = 16.dp)) {
                            DeviceItemRow(
                                item = currentDevice,
                                isCurrent = true,
                                onKick = {},
                            )
                        }
                    }
                }

                item {
                    OTGroupHeader("其他登录设备 (${otherDevices.size})")
                }

                if (otherDevices.isEmpty()) {
                    item {
                        OTGroup(Modifier.padding(horizontal = 16.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "暂无其他在线设备",
                                    style = t.body,
                                    color = c.label3,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        OTGroup(Modifier.padding(horizontal = 16.dp)) {
                            Column {
                                otherDevices.forEachIndexed { index, device ->
                                    if (index > 0) OTDivider(inset = 56)
                                    DeviceItemRow(
                                        item = device,
                                        isCurrent = false,
                                        onKick = { kickTarget = device },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }

    // 确认下线弹窗
    kickTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!kicking) kickTarget = null },
            title = {
                Text(
                    text = "确认下线此设备？",
                    style = t.title3,
                    color = c.label,
                )
            },
            text = {
                Text(
                    text = "下线该设备（${deviceDisplayTitle(target)}）后，该终端上的会话将立即失效，需重新验证密码或密钥后方可访问。",
                    style = t.body,
                    color = c.label2,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        kicking = true
                        scope.launch {
                            runCatching { Api.kickSession(target.id) }
                                .onSuccess {
                                    kicking = false
                                    kickTarget = null
                                    onToast("已成功下线该设备")
                                    reload()
                                }
                                .onFailure {
                                    kicking = false
                                    onToast("下线失败: ${friendlyError(it)}")
                                }
                        }
                    },
                    enabled = !kicking,
                ) {
                    Text(if (kicking) "处理中…" else "确认下线", color = c.red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { kickTarget = null },
                    enabled = !kicking,
                ) {
                    Text("取消", color = c.label2)
                }
            },
            containerColor = c.bgCard,
        )
    }
}

@Composable
private fun DeviceItemRow(
    item: SessionDeviceItem,
    isCurrent: Boolean,
    onKick: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type

    val icon = when {
        item.os.contains("Android", ignoreCase = true) -> Icons.Outlined.PhoneAndroid
        item.os.contains("iOS", ignoreCase = true) || item.os.contains("iPhone", ignoreCase = true) -> Icons.Outlined.PhoneIphone
        item.os.contains("Windows", ignoreCase = true) || item.os.contains("Mac", ignoreCase = true) || item.os.contains("Linux", ignoreCase = true) -> Icons.Outlined.Computer
        else -> Icons.Outlined.Devices
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isCurrent) c.blue.copy(alpha = 0.12f) else c.bgInput),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isCurrent) c.blue else c.label2,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = deviceDisplayTitle(item),
                    style = t.body.copy(fontWeight = FontWeight.SemiBold),
                    color = c.label,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100))
                            .background(c.green.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "当前设备",
                            style = t.caption2.copy(fontWeight = FontWeight.Medium),
                            color = c.green,
                        )
                    }
                }
            }

            Spacer(Modifier.height(3.dp))

            val details = buildList {
                if (item.ip.isNotBlank()) add("IP: ${item.ip}")
                if (item.time.isNotBlank()) add(item.time)
            }.joinToString(" · ")

            Text(
                text = details.ifEmpty { "活跃会话" },
                style = t.footnote,
                color = c.label3,
            )
        }

        if (!isCurrent) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.red.copy(alpha = 0.08f))
                    .clickable { onKick() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "下线",
                    style = t.caption1.copy(fontWeight = FontWeight.Medium),
                    color = c.red,
                )
            }
        }
    }
}

private fun deviceDisplayTitle(item: SessionDeviceItem): String {
    val parts = listOf(item.browser, item.os, item.device).filter { it.isNotBlank() }
    return if (parts.isNotEmpty()) parts.joinToString(" / ") else "未知设备"
}
