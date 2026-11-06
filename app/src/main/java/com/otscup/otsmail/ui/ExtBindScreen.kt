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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.ExtBindStep
import com.otscup.otsmail.data.ExtBinder
import com.otscup.otsmail.data.ExtProviders
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

private data class BoundAccount(
    val extId: Long,
    val address: String,
    val provider: String,
    val status: Int,
)

/**
 * 聚合收件：第三方邮箱绑定向导。
 *
 * 第一批只做「绑进来并测通」：
 * 地址自动识别 → 授权方式指引 → 客户端实测 IMAP/SMTP → 加密存服务端。
 * 拉取、统一收件箱展示、双通道推送都在下一批，这里刻意不碰。
 */
@Composable
fun ExtBindScreen(
    onBack: () -> Unit,
    onToast: (String) -> Unit = {},
) {
    val c = OT.colors
    val t = OT.type
    val scope = rememberCoroutineScope()
    val binder = remember { ExtBinder() }

    var bound by remember { mutableStateOf<List<BoundAccount>>(emptyList()) }
    var pendingRemove by remember { mutableStateOf<BoundAccount?>(null) }

    suspend fun reload() {
        bound = runCatching {
            val arr = Api.extAccounts()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        BoundAccount(
                            extId = o.optLong("ext_id"),
                            address = o.optString("address"),
                            provider = o.optString("provider"),
                            status = o.optInt("status", 0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(c.bgGrouped)) {
        OTNavBar(title = "聚合邮箱", onLeading = onBack)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            when (binder.state.step) {
                ExtBindStep.ADDRESS -> AddressStep(binder)

                ExtBindStep.CREDENTIAL, ExtBindStep.SAVING -> CredentialStep(
                    binder = binder,
                    busy = binder.state.busy || binder.state.step == ExtBindStep.SAVING,
                    onSubmit = {
                        scope.launch {
                            val result = binder.verifyAndBind()
                            if (binder.state.step == ExtBindStep.DONE) {
                                onToast("绑定成功")
                                reload()
                            } else if (result != null && result.ok && binder.state.error.isNotEmpty()) {
                                onToast("已验证通过，保存失败")
                            }
                        }
                    },
                )

                ExtBindStep.DONE -> DoneStep(
                    binder = binder,
                    onAgain = { binder.reset() },
                )
            }

            if (bound.isNotEmpty()) {
                OTGroupHeader("已绑定")
                OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                    bound.forEachIndexed { index, item ->
                        if (index > 0) OTDivider(inset = 16)
                        SettingRow(
                            title = item.address,
                            value = "移除",
                            valueColor = c.red,
                            onClick = { pendingRemove = item },
                        )
                    }
                }
                Text(
                    "解绑只删除服务端保存的凭据与本地账户行；原邮箱里的邮件不受影响。",
                    style = t.footnote, color = c.label2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }

    pendingRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除 ${item.address}？") },
            text = { Text("移除后不会再聚合这个邮箱，需要重新绑定才能恢复。原邮箱里的邮件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = item
                    pendingRemove = null
                    scope.launch {
                        runCatching { Api.deleteExtAccount(target.extId) }
                            .onSuccess { onToast("已移除"); reload() }
                            .onFailure { onToast(it.message ?: "移除失败") }
                    }
                }) { Text("移除", color = c.red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AddressStep(binder: ExtBinder) {
    val c = OT.colors
    val t = OT.type

    OTGroupHeader("添加第三方邮箱")
    Text(
        "输入完整邮箱地址，服务器地址会自动填好。授权码只在验证时用一次，服务端加密保存，客户端不留明文。",
        style = t.footnote, color = c.label2,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )

    Spacer(Modifier.height(8.dp))
    OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
        OTFieldRow(
            label = "邮箱地址",
            value = binder.state.address,
            onValueChange = binder::onAddressChanged,
            hint = "name@gmail.com",
        )
    }

    val provider = binder.state.provider
    if (provider != null) {
        Spacer(Modifier.height(12.dp))
        OTGroupHeader("自动识别")
        OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
            SettingRow(title = "服务商", value = provider.name)
            OTDivider(inset = 16)
            SettingRow(title = "IMAP", value = "${provider.imap.host}:${provider.imap.port}")
            OTDivider(inset = 16)
            SettingRow(title = "SMTP", value = "${provider.smtp.host}:${provider.smtp.port}")
        }
        if (provider.note.isNotEmpty()) {
            Text(
                provider.note, style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    } else if (binder.state.address.contains("@")) {
        Text(
            "没有收录这个域名。可以手动填写 IMAP / SMTP 服务器后继续。",
            style = t.footnote, color = c.orange,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
            SettingRow(title = "手动填写服务器", value = "", showChevron = true, onClick = binder::useManualProvider)
        }
    }

    if (binder.state.error.isNotEmpty()) {
        Text(
            binder.state.error, style = t.footnote, color = c.red,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    Box(Modifier.padding(horizontal = OTSpace.s4)) {
        OTPrimaryButton(
            text = "下一步",
            enabled = binder.addressValid,
            onClick = { binder.next() },
        )
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun CredentialStep(binder: ExtBinder, busy: Boolean, onSubmit: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val state = binder.state
    val provider = state.provider ?: return

    OTGroupHeader(provider.name)
    if (provider.authModes.size > 1) {
        OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
            provider.authModes.forEachIndexed { index, mode ->
                if (index > 0) OTDivider(inset = 16)
                SettingRow(
                    title = mode.label,
                    value = when {
                        !mode.enabled -> "下一批"
                        mode == state.authMode -> "已选择"
                        else -> ""
                    },
                    showChevron = false,
                    onClick = if (mode.enabled) ({ binder.onAuthModeChanged(mode) }) else null,
                )
            }
        }
    }

    val mode = state.authMode
    if (mode != null && mode.steps.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        OTGroupHeader("怎么拿到授权码")
        OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                mode.steps.forEachIndexed { index, step ->
                    Text("${index + 1}. $step", style = t.footnote, color = c.label2)
                }
            }
        }
        Text(
            provider.note, style = t.caption1, color = c.label3,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
    if (mode != null && !mode.enabled && mode.note.isNotEmpty()) {
        Text(
            mode.note, style = t.footnote, color = c.orange,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }

    if (state.manual) {
        Spacer(Modifier.height(12.dp))
        OTGroupHeader("服务器")
        OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
            OTFieldRow(
                label = "IMAP 主机",
                value = state.imapHost,
                onValueChange = { binder.onServerChanged(it, state.imapPort, state.smtpHost, state.smtpPort) },
                hint = "imap.example.com",
            )
            OTDivider(inset = 16)
            OTFieldRow(
                label = "IMAP 端口",
                value = state.imapPort,
                number = true,
                onValueChange = { binder.onServerChanged(state.imapHost, it, state.smtpHost, state.smtpPort) },
            )
            OTDivider(inset = 16)
            OTFieldRow(
                label = "SMTP 主机",
                value = state.smtpHost,
                onValueChange = { binder.onServerChanged(state.imapHost, state.imapPort, it, state.smtpPort) },
                hint = "smtp.example.com",
            )
            OTDivider(inset = 16)
            OTFieldRow(
                label = "SMTP 端口",
                value = state.smtpPort,
                number = true,
                onValueChange = { binder.onServerChanged(state.imapHost, state.imapPort, state.smtpHost, it) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    OTGroupHeader("凭据")
    OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
        OTFieldRow(
            label = mode?.label ?: "授权码",
            value = state.secret,
            onValueChange = binder::onSecretChanged,
            hint = "粘贴授权码",
            secret = true,
        )
    }
    Text(
        "验证会真的连一次 ${provider.imap.host}:${provider.imap.port} 和 ${provider.smtp.host}:${provider.smtp.port}，两边都通过才算绑定成功。",
        style = t.caption1, color = c.label3,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )

    if (busy) {
        Text(
            if (state.step == ExtBindStep.SAVING) "验证通过，正在保存到服务端…" else "正在连接 IMAP 与 SMTP…",
            style = t.footnote, color = c.blue,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
    if (state.error.isNotEmpty()) {
        Text(
            state.error, style = t.footnote, color = c.red,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
    if (state.hint.isNotEmpty()) {
        Text(
            state.hint, style = t.footnote, color = c.label2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    Box(Modifier.padding(horizontal = OTSpace.s4)) {
        OTPrimaryButton(
            text = if (busy) "验证中…" else "验证并绑定",
            enabled = !busy && state.secret.isNotBlank() && (state.authMode?.enabled == true),
            onClick = onSubmit,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = OTSpace.s4), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { if (!busy) binder.back() }) { Text("上一步", color = c.blue) }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun DoneStep(binder: ExtBinder, onAgain: () -> Unit) {
    val c = OT.colors
    val t = OT.type

    OTGroupHeader("绑定完成")
    Text(
        binder.state.doneText,
        style = t.body, color = c.label,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    Text(
        "下一批会开始拉取这个邮箱的邮件，并按原有 FCM 链路推送新邮件。",
        style = t.footnote, color = c.label2,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
    Spacer(Modifier.height(16.dp))
    Box(Modifier.padding(horizontal = OTSpace.s4)) {
        OTPrimaryButton(text = "再绑一个", onClick = onAgain)
    }
    Spacer(Modifier.height(20.dp))
}
