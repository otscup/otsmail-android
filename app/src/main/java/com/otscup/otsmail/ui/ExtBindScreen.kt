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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.ExtBindStep
import com.otscup.otsmail.data.ExtBinder
import com.otscup.otsmail.data.ExtProviders
import com.otscup.otsmail.push.ExtIdleManager
import com.otscup.otsmail.push.PushNotifications
import com.otscup.otsmail.push.PushSettings
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

private data class BoundAccount(
    val extId: Long,
    val accountId: Long,
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
    /** 用该第三方邮箱自己的 SMTP 写一封新邮件（extId / 归属 accountId / 发件地址） */
    onCompose: (Long, Long, String) -> Unit = { _, _, _ -> },
    onToast: (String) -> Unit = {},
) {
    val c = OT.colors
    val t = OT.type
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val binder = remember { ExtBinder() }

    var bound by remember { mutableStateOf<List<BoundAccount>>(emptyList()) }
    var pendingRemove by remember { mutableStateOf<BoundAccount?>(null) }
    var pushMode by remember { mutableStateOf(PushSettings.extPushMode(context)) }
    var modeSheet by remember { mutableStateOf(false) }
    // 通知权限被系统关掉时，绑定成功也收不到提醒：这里与绑定结果一起明确提示
    var notifEnabled by remember { mutableStateOf(PushNotifications.enabled(context)) }

    suspend fun reload() {
        bound = runCatching {
            val arr = Api.extAccounts()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        BoundAccount(
                            extId = o.optLong("ext_id"),
                            accountId = o.optLong("account_id"),
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
        OTNavBar(title = "外部邮箱", onLeading = onBack)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            if (!notifEnabled) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp)
                        .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                        .background(c.bgInput)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "系统通知权限未开启：绑定后能同步邮件，但新邮件提醒不会弹出",
                        style = t.caption1,
                        color = c.label,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.height(36.dp).tapArea {
                            PushNotifications.openSystemSettings(context)
                            notifEnabled = PushNotifications.enabled(context)
                        }.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("去开启", style = t.caption1, color = c.blue)
                    }
                }
            }
            when (binder.state.step) {
                ExtBindStep.ADDRESS -> AddressStep(binder)

                ExtBindStep.CREDENTIAL, ExtBindStep.SAVING -> CredentialStep(
                    binder = binder,
                    busy = binder.state.busy || binder.state.step == ExtBindStep.SAVING,
                    onSubmit = {
                        scope.launch {
                            val result = binder.verifyAndBind()
                            if (binder.state.step == ExtBindStep.DONE) {
                                notifEnabled = PushNotifications.enabled(context)
                                onToast(
                                    if (notifEnabled) {
                                        "绑定成功"
                                    } else {
                                        "绑定成功，但通知权限未开启：新邮件提醒不会弹出"
                                    },
                                )
                                // 事件驱动：让常驻 IDLE 服务立刻为这个新账户建连（不再等每分钟轮询）
                                ExtIdleManager.onAccountsChanged(context)
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

            OTGroupHeader("新邮件提醒")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                SettingRow(
                    title = "提醒方式",
                    value = extPushModeLabel(pushMode),
                    showChevron = true,
                    onClick = { modeSheet = true },
                )
            }
            Text(
                extPushModeSummary(pushMode),
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            if (bound.isNotEmpty()) {
                OTGroupHeader("已绑定")
                OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                    bound.forEachIndexed { index, item ->
                        if (index > 0) OTDivider(inset = 16)
                        SettingRow(
                            title = item.address,
                            value = "",
                            leading = { ExtProviderBadge(item.provider, item.address) },
                            trailing = {
                                // 写邮件：走该邮箱自己的 SMTP，不经 otsmail 后端代发
                                Text(
                                    "写邮件",
                                    style = t.subhead, color = c.blue,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                                        .tapArea { onCompose(item.extId, item.accountId, item.address) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                                Text(
                                    "移除",
                                    style = t.subhead, color = c.red,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                                        .tapArea { pendingRemove = item }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            },
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

    if (modeSheet) {
        AlertDialog(
            onDismissRequest = { modeSheet = false },
            title = { Text("新邮件提醒方式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ExtPushModeChoice(
                        title = "自动（推荐）",
                        subtitle = "服务端支持第三方邮件推送时用设备推送，否则用长连接。当前实际使用长连接。",
                        selected = pushMode == PushSettings.EXT_PUSH_AUTO,
                    ) {
                        PushSettings.setExtPushMode(context, PushSettings.EXT_PUSH_AUTO)
                        pushMode = PushSettings.EXT_PUSH_AUTO
                        modeSheet = false
                        ExtIdleManager.applyModeChange(context)
                    }
                    ExtPushModeChoice(
                        title = "始终用长连接",
                        subtitle = "IMAP 长连接保持在线，收到新邮件立刻提醒；通知栏会常驻一条服务提示。",
                        selected = pushMode == PushSettings.EXT_PUSH_IDLE,
                    ) {
                        PushSettings.setExtPushMode(context, PushSettings.EXT_PUSH_IDLE)
                        pushMode = PushSettings.EXT_PUSH_IDLE
                        modeSheet = false
                        ExtIdleManager.applyModeChange(context)
                    }
                    ExtPushModeChoice(
                        title = "只用设备推送（调试）",
                        subtitle = "仅用于对照验证。服务端暂不支持第三方邮件推送，选这个收不到提醒。",
                        selected = pushMode == PushSettings.EXT_PUSH_FCM,
                    ) {
                        PushSettings.setExtPushMode(context, PushSettings.EXT_PUSH_FCM)
                        pushMode = PushSettings.EXT_PUSH_FCM
                        modeSheet = false
                        ExtIdleManager.applyModeChange(context)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modeSheet = false }) { Text("关闭") }
            },
        )
    }

    pendingRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除 ${item.address}？") },
            text = { Text("移除后不会再收取这个邮箱，需要重新绑定才能恢复。原邮箱里的邮件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = item
                    pendingRemove = null
                    scope.launch {
                        runCatching { Api.deleteExtAccount(target.extId) }
                            .onSuccess {
                                onToast("已移除")
                                // 事件驱动：立刻断开这个账户的 IDLE 连接，不必等它下次重连才发现
                                ExtIdleManager.onAccountsChanged(context)
                                reload()
                            }
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
            SettingRow(
                title = "服务商",
                value = provider.name,
                leading = { ExtProviderBadge(provider.id, binder.state.address) },
            )
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
        "绑定后 App 会在后台保持 IMAP 长连接，这个邮箱收到新邮件时立即提醒；不需要等服务端推送。",
        style = t.footnote, color = c.label2,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
    Spacer(Modifier.height(16.dp))
    Box(Modifier.padding(horizontal = OTSpace.s4)) {
        OTPrimaryButton(text = "再绑一个", onClick = onAgain)
    }
    Spacer(Modifier.height(20.dp))
}

private fun extPushModeLabel(mode: String): String = when (mode) {
    PushSettings.EXT_PUSH_IDLE -> "始终用长连接"
    PushSettings.EXT_PUSH_FCM -> "只用设备推送（调试）"
    else -> "自动（推荐）"
}

private fun extPushModeSummary(mode: String): String = when (mode) {
    PushSettings.EXT_PUSH_IDLE ->
        "强制使用 IMAP 长连接：通知栏会常驻一条「外部邮箱实时收取服务」，收到新邮件立刻提醒。"
    PushSettings.EXT_PUSH_FCM ->
        "强制走设备推送（调试用）：服务端还没有第三方邮件的推送触发点，这个模式下收不到外部邮箱提醒。"
    else ->
        "自动：服务端支持第三方邮件推送时用设备推送，否则用 IMAP 长连接。当前服务端只推送 otsmail 邮件，所以实际走长连接。"
}

@Composable
private fun ExtPushModeChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    Column(
        Modifier.fillMaxWidth().tapArea(onSelect).padding(vertical = 8.dp),
    ) {
        Text(
            if (selected) "✓ $title" else title,
            style = t.body,
            color = if (selected) c.blue else c.label,
        )
        Text(subtitle, style = t.caption1, color = c.label2)
    }
}
