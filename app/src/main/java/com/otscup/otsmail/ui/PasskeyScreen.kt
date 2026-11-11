package com.otscup.otsmail.ui

import android.widget.Toast
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.PasskeyManager
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

/**
 * 通行密钥（Passkey）凭据管理界面。
 * 支持列出已绑定的设备凭据、在当前设备创建新的 FIDO2/WebAuthn 免密凭据，以及删除旧凭据。
 */
@Composable
fun PasskeyScreen(
    onBack: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var passkeys by remember { mutableStateOf<List<Api.PasskeyItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var registering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Api.PasskeyItem?>(null) }
    var deleting by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            error = ""
            runCatching { Api.passkeyList() }
                .onSuccess { passkeys = it }
                .onFailure { error = friendlyError(it) }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    fun startRegister() {
        if (registering) return
        scope.launch {
            registering = true
            error = ""
            runCatching {
                val options = Api.passkeyRegisterOptions()
                val credential = PasskeyManager.registerPasskey(context, options)
                Api.passkeyRegisterVerify(credential)
            }.onSuccess {
                Toast.makeText(context, "通行密钥添加成功", Toast.LENGTH_SHORT).show()
                refresh()
            }.onFailure {
                val msg = friendlyError(it)
                error = if (msg.isNotBlank()) msg else (it.message ?: "添加失败")
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
            registering = false
        }
    }

    fun executeDelete(item: Api.PasskeyItem) {
        scope.launch {
            deleting = true
            runCatching { Api.passkeyDelete(item.id) }
                .onSuccess {
                    Toast.makeText(context, "已删除通行密钥", Toast.LENGTH_SHORT).show()
                    deleteTarget = null
                    refresh()
                }
                .onFailure {
                    Toast.makeText(context, "删除失败：${friendlyError(it)}", Toast.LENGTH_SHORT).show()
                }
            deleting = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgGrouped)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        OTNavBar(
            title = "通行密钥 (Passkey)",
            onLeading = onBack,
        ) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable { refresh() }
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = c.blue,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 说明卡片
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(OT.dimens.rLg.dp))
                        .background(c.bgCard)
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c.blue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Security,
                            contentDescription = null,
                            tint = c.blue,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("基于 FIDO2 / WebAuthn 标准", style = t.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "通行密钥利用本设备的指纹、面容或锁屏密码进行端到端加密验证，公钥留在服务端，私钥永不离开设备，天然抵御钓鱼网站与弱密码暴力破解。",
                            style = t.footnote,
                            color = c.label2,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            // 添加当前设备通行密钥按钮
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(OT.dimens.rLg.dp))
                        .background(c.bgCard)
                        .clickable(enabled = !registering) { startRegister() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(c.blue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (registering) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = c.blue,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Fingerprint,
                                contentDescription = null,
                                tint = c.blue,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (registering) "正在唤起系统安全认证…" else "添加本设备通行密钥",
                            style = t.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = c.blue,
                        )
                        Text(
                            "注册后即可在登录界面一键生物免密登录",
                            style = t.caption1,
                            color = c.label3,
                        )
                    }
                }
            }

            // 列表标题
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "已绑定的通行密钥 (${passkeys.size})",
                    style = t.caption1.copy(fontWeight = FontWeight.Medium),
                    color = c.label3,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // 错误提示
            if (error.isNotEmpty()) {
                item {
                    Text(
                        error,
                        style = t.footnote,
                        color = c.red,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            // 加载中
            if (loading && passkeys.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = c.blue,
                        )
                    }
                }
            } else if (passkeys.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(OT.dimens.rLg.dp))
                            .background(c.bgCard)
                            .padding(vertical = 36.dp, horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.Key,
                            contentDescription = null,
                            tint = c.label3,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("暂未绑定通行密钥", style = t.subhead, color = c.label2)
                        Spacer(Modifier.height(4.dp))
                        Text("点击上方按钮即可快捷绑定当前设备", style = t.caption1, color = c.label3)
                    }
                }
            } else {
                itemsIndexed(passkeys) { _, item ->
                    PasskeyCard(
                        item = item,
                        onDelete = { deleteTarget = item },
                    )
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 删除确认弹窗
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text("删除通行密钥", style = t.title3, color = c.label) },
            text = {
                Text(
                    "确定要删除该通行密钥「${target.name}」吗？删除后此设备将无法再使用通行密钥免密登录。",
                    style = t.body,
                    color = c.label2,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { executeDelete(target) },
                    enabled = !deleting,
                ) {
                    Text(if (deleting) "删除中…" else "确认删除", color = c.red)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    enabled = !deleting,
                ) {
                    Text("取消", color = c.label2)
                }
            },
            containerColor = c.bgElevated,
        )
    }
}

@Composable
private fun PasskeyCard(
    item: Api.PasskeyItem,
    onDelete: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OT.dimens.rLg.dp))
            .background(c.bgCard)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(c.bgInput),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Key,
                contentDescription = null,
                tint = c.label,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = t.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = c.label,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            val previewId = if (item.credentialId.length > 16) {
                "${item.credentialId.take(8)}...${item.credentialId.takeLast(8)}"
            } else {
                item.credentialId
            }
            Text(
                "凭据: $previewId",
                style = t.caption1,
                color = c.label3,
                maxLines = 1,
            )
            if (item.createdAt.isNotEmpty()) {
                Text(
                    "创建时间: ${item.createdAt}",
                    style = t.caption2,
                    color = c.label3,
                )
            }
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable { onDelete() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "删除凭据",
                tint = c.label3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
