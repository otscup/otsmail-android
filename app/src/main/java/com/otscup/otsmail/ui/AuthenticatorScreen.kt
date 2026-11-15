package com.otscup.otsmail.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.OtpScanRelay
import com.otscup.otsmail.data.OtpVault
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.Totp
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 本机验证器：密钥加密存在 Keystore，直接在这里出 6 位码。
 * 老用户（网页/其他验证器已绑定）通过「导入密钥」或「重新绑定」把密钥带到本机。
 * 显示验证码前不做生物识别：用户明确没有这个要求。
 */
@Composable
fun AuthenticatorScreen(
    session: Session,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onOpenQrScan: () -> Unit = {},
) {
    val c = OT.colors
    val t = OT.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var secret by remember { mutableStateOf(OtpVault.load(context, session.email)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var importSheet by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var rebindSheet by remember { mutableStateOf(false) }
    var rebindOld by remember { mutableStateOf("") }
    var rebindSecret by remember { mutableStateOf("") }
    var rebindUrl by remember { mutableStateOf("") }
    var rebindCode by remember { mutableStateOf("") }
    var rebindBusy by remember { mutableStateOf(false) }
    var removeConfirm by remember { mutableStateOf(false) }

    // 每秒刷新一次倒计时与验证码
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }

    // 从扫码页回来时取一次结果（otpauth:// 或原始 base32）
    LaunchedEffect(Unit) {
        OtpScanRelay.poll()?.let { raw ->
            val parsed = Totp.parse(raw)
            if (parsed == null) {
                onToast("二维码不是有效的验证器密钥")
            } else {
                OtpVault.save(context, session.email, parsed)
                secret = parsed
                onToast("已导入本机验证器")
            }
        }
    }

    val code = secret?.let { Totp.code(it, now) }
    val remaining = Totp.secondsRemaining(now)

    Box(Modifier.fillMaxSize().background(c.bgGrouped)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            OTNavBar(title = "验证器", onLeading = onBack)

            if (secret == null) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text("本机还没有验证器", style = t.title3, color = c.label)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "可以从网页端复制密钥（otpauth 链接），或直接扫码，把两步验证密钥导入本机。" +
                            "导入后这里直接显示 6 位验证码，不必再开别的 App。",
                        style = t.subhead, color = c.label2, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(22.dp))
                    OTPrimaryButton("导入密钥") { importSheet = true }
                    Spacer(Modifier.height(10.dp))
                    OTPrimaryButton("扫码导入") { onOpenQrScan() }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        // 老用户没带密钥过来：验证旧验证器/备用码后换一套新密钥
                        rebindOld = ""
                        rebindSecret = ""
                        rebindUrl = ""
                        rebindCode = ""
                        rebindSheet = true
                    }) {
                        Text("验证器丢了？用旧码重新绑定", color = c.blue)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "如果原来的验证器已经丢失，请用备用码重新绑定。",
                        style = t.footnote, color = c.label3, textAlign = TextAlign.Center,
                    )
                }
            } else {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        code ?: "------",
                        style = t.title3.copy(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 6.sp),
                        color = c.label,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("$remaining 秒后刷新", style = t.footnote, color = c.label2)
                }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.padding(horizontal = 20.dp)) {
                    OTPrimaryButton("复制验证码", enabled = code != null) {
                        code?.let { copyToClipboard(context, it) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                OTGroup(Modifier.padding(horizontal = 20.dp)) {
                    SettingRow(
                        title = "重新绑定",
                        value = "换新密钥",
                        onClick = {
                            rebindOld = ""
                            rebindSecret = ""
                            rebindUrl = ""
                            rebindCode = ""
                            rebindSheet = true
                        },
                        showChevron = true,
                    )
                    OTDivider(inset = 16)
                    SettingRow(
                        title = "移除验证器",
                        value = "仅本机",
                        valueColor = c.red,
                        onClick = { removeConfirm = true },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "密钥只保存在本机（Keystore 加密）；退出登录不会删除。\n移除本机验证器不会关闭账号的两步验证。",
                    style = t.footnote, color = c.label3,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.weight(1f))
        }
    }

    if (importSheet) {
        OTSheet(onDismiss = { importSheet = false }) {
            Text("导入密钥", style = t.title3, color = c.label, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text(
                "粘贴 base32 密钥或 otpauth:// 链接。密钥只保存在本机。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            OTFieldRow(
                label = "密钥 / otpauth 链接",
                value = importText,
                onValueChange = { importText = it },
                hint = "JBSWY3DPEHPK3PXP… 或 otpauth://totp/…",
            )
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OTPrimaryButton("保存到本机", enabled = Totp.parse(importText) != null) {
                    val parsed = Totp.parse(importText) ?: return@OTPrimaryButton
                    OtpVault.save(context, session.email, parsed)
                    secret = parsed
                    importText = ""
                    importSheet = false
                    onToast("已导入本机验证器")
                }
            }
            TextButton(
                onClick = { importSheet = false; onOpenQrScan() },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text("改用扫码导入", color = c.blue) }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (rebindSheet) {
        OTSheet(onDismiss = { if (!rebindBusy) rebindSheet = false }) {
            Text("重新绑定验证器", style = t.title3, color = c.label, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text(
                "先用当前验证器验证码或一枚备用码确认身份，再用新密钥完成绑定。旧密钥在确认前仍然有效。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (rebindSecret.isEmpty()) {
                OTFieldRow(
                    label = "当前验证码或备用码",
                    value = rebindOld,
                    onValueChange = { rebindOld = it },
                    hint = "6 位数字 / 10 位备用码",
                )
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OTPrimaryButton("生成新密钥", enabled = rebindOld.isNotBlank() && !rebindBusy) {
                        rebindBusy = true
                        scope.launch {
                            runCatching { Api.twoFactorRebindStart(rebindOld.trim()) }
                                .onSuccess { (newSecret, url) ->
                                    rebindSecret = newSecret
                                    rebindUrl = url
                                }
                                .onFailure { onToast(friendlyError(it)) }
                            rebindBusy = false
                        }
                    }
                }
            } else {
                DetailKVText("新密钥", rebindSecret)
                DetailKVText("otpauth", rebindUrl)
                Text(
                    "把新密钥加进验证器，再输入它生成的 6 位验证码。完成前旧验证器仍可用。",
                    style = t.footnote, color = c.label2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                OTFieldRow(
                    label = "新验证器验证码",
                    value = rebindCode,
                    onValueChange = { rebindCode = it.filter { ch -> ch.isDigit() }.take(6) },
                    hint = "6 位数字",
                    number = true,
                )
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OTPrimaryButton("完成绑定", enabled = rebindCode.length == 6 && !rebindBusy) {
                        rebindBusy = true
                        scope.launch {
                            runCatching { Api.twoFactorRebindConfirm(rebindCode) }
                                .onSuccess { backupCodes ->
                                    OtpVault.save(context, session.email, rebindSecret)
                                    secret = rebindSecret
                                    rebindSheet = false
                                    onToast(
                                        if (backupCodes.isEmpty()) "验证器已更新"
                                        else "验证器已更新，已生成新的备用码，请到设置里查看并保存",
                                    )
                                }
                                .onFailure { onToast(friendlyError(it)) }
                            rebindBusy = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (removeConfirm) {
        AlertDialog(
            onDismissRequest = { removeConfirm = false },
            title = { Text("移除本机验证器？") },
            text = { Text("只会删除本机保存的密钥，不会关闭账号的两步验证。之后可以重新导入或重新绑定。") },
            confirmButton = {
                TextButton(onClick = {
                    OtpVault.remove(context, session.email)
                    secret = null
                    removeConfirm = false
                    onToast("已移除本机验证器")
                }) { Text("移除", color = c.red) }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirm = false }) { Text("取消", color = c.label2) }
            },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("验证码", text))
}
