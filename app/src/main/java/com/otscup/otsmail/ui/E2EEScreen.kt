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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otscup.otsmail.data.E2EEManager
import com.otscup.otsmail.ui.theme.OT

/**
 * 端到端加密（E2EE）密钥管理页
 * 当前仅提供本地 RSA-2048 密钥对的查看、导出与重建；加密发送与接收解密尚未接入收发链路，
 * 跨设备密钥同步亦未开发，页面上如实标注为「未启用 / 待开发」。
 */
@Composable
fun E2EEScreen(
    onBack: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val context = LocalContext.current

    var fingerprint by remember { mutableStateOf(E2EEManager.getFingerprint(context)) }
    var publicKey by remember { mutableStateOf(E2EEManager.getOrCreatePublicKey(context)) }
    var showResetDialog by remember { mutableStateOf(false) }

    fun refreshKeys() {
        fingerprint = E2EEManager.getFingerprint(context)
        publicKey = E2EEManager.getOrCreatePublicKey(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgGrouped)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        OTNavBar(title = "端到端加密 (E2EE)", onLeading = onBack)

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                // 说明卡片
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
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(c.bgInput),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Security, contentDescription = null, tint = c.label2, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("端到端加密", style = t.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "未启用",
                                style = t.caption2.copy(fontWeight = FontWeight.Medium),
                                color = c.label3,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(OT.dimens.rPill.dp))
                                    .background(c.bgInput)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "这里目前只管理本机的一对加密密钥，可查看指纹、复制公钥、重新生成。加密发送与接收解密尚未接入邮件的收发流程，当前不会对任何邮件加密，服务器上的邮件与以往一致。",
                            style = t.footnote,
                            color = c.label2,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            item {
                // 本机身份公钥与指纹
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(OT.dimens.rLg.dp))
                        .background(c.bgCard)
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, contentDescription = null, tint = c.blue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("本机公钥指纹", style = t.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        fingerprint.ifEmpty { "尚未生成" },
                        style = t.caption1.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                        color = c.label,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(OT.dimens.rPill.dp))
                                .background(c.bgInput)
                                .clickable {
                                    copyToClipboard(context, publicKey, "E2EE 公钥")
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = c.blue, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制公钥", style = t.caption1.copy(fontWeight = FontWeight.Medium), color = c.blue)
                        }
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(OT.dimens.rPill.dp))
                                .background(c.bgInput)
                                .clickable {
                                    showResetDialog = true
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = c.red, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重新生成密钥", style = t.caption1.copy(fontWeight = FontWeight.Medium), color = c.red)
                        }
                    }
                }
            }

            item {
                // 跨设备同步（待开发，暂不可操作）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(OT.dimens.rLg.dp))
                        .background(c.bgCard)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(c.bgInput),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = null, tint = c.label3, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("跨设备安全密钥同步", style = t.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.label2)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "尚未开发。完成后可通过本地加密二维码把本机私钥迁移到新设备，当前不可使用。",
                            style = t.caption1,
                            color = c.label3,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "待开发",
                        style = t.caption2.copy(fontWeight = FontWeight.Medium),
                        color = c.label3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(OT.dimens.rPill.dp))
                            .background(c.bgInput)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重新生成加密密钥？", style = t.title3, color = c.label) },
            text = {
                Text(
                    "重新生成密钥后，旧设备或过去发给本设备的加密邮件将无法再被解密，确定要继续吗？",
                    style = t.body,
                    color = c.label2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    E2EEManager.generateKeyPair(context)
                    refreshKeys()
                    showResetDialog = false
                    Toast.makeText(context, "已重新生成 E2EE 密钥对", Toast.LENGTH_SHORT).show()
                }) {
                    Text("确认重置", color = c.red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消", color = c.label2)
                }
            },
            containerColor = c.bgElevated,
        )
    }
}
