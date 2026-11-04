package com.otscup.otsmail.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.otscup.otsmail.BuildConfig
import com.otscup.otsmail.R
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 回车键行为三档（存服务端 sendOnEnter） */
private val SEND_MODES = listOf(
    "enter" to "回车发送",
    "ctrl" to "Ctrl + Enter 发送",
    "off" to "只点发送按钮",
)

private fun sendModeLabel(key: String): String = SEND_MODES.firstOrNull { it.first == key }?.second ?: "回车发送"

/**
 * 个人设置：账户相关的内容（对齐网页端 `views/setting/index.vue` 的「个人资料」区）。
 * 头像/密码/两步验证/用户名都走真实接口，第三方绑定与浏览器通知只在网页端有，Android 标注占位。
 */
@Composable
fun SettingsScreen(
    session: Session,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onOpenSystemSettings: () -> Unit = {},
) {
    val c = OT.colors
    val t = OT.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var signature by remember { mutableStateOf(session.signature) }
    var sendOnEnter by remember { mutableStateOf(session.sendOnEnter.ifEmpty { "enter" }) }
    var saving by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    var displayName by remember { mutableStateOf(session.name) }
    var displayEmail by remember { mutableStateOf(session.email) }
    var avatarUrl by remember { mutableStateOf(session.avatar) }
    var avatarLocal by remember { mutableStateOf(session.avatarLocal) }

    var twoFactor by remember { mutableStateOf(false) }
    var twoFactorRequired by remember { mutableStateOf(false) }

    var avatarSheet by remember { mutableStateOf(false) }
    var avatarLink by remember { mutableStateOf("") }
    var nameDialog by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var passwordDialog by remember { mutableStateOf(false) }
    var pwd1 by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var twoFactorSheet by remember { mutableStateOf(false) }
    var twoFactorSecret by remember { mutableStateOf("") }
    var twoFactorUrl by remember { mutableStateOf("") }
    var twoFactorCode by remember { mutableStateOf("") }
    var disableCode by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }

    // 进页面拉一次最新值（别的地方可能改过）
    LaunchedEffect(Unit) {
        runCatching { Api.me() }.onSuccess {
            signature = it.signature
            sendOnEnter = it.sendOnEnter.ifEmpty { "enter" }
            displayName = it.name.ifEmpty { it.email }
            displayEmail = it.email
            avatarUrl = it.avatar
            twoFactor = it.twoFactor
            twoFactorRequired = it.twoFactorRequired
            session.signature = it.signature
            session.sendOnEnter = it.sendOnEnter
            if (it.avatar.isNotEmpty()) session.avatar = it.avatar
            loaded = true
        }
        runCatching { Api.twoFactorStatus() }.onSuccess { (enabled, required) ->
            twoFactor = enabled
            twoFactorRequired = required
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val data = withContext(Dispatchers.IO) { runCatching { avatarDataUrl(context, uri) }.getOrNull() }
                if (data == null) {
                    onToast("读取图片失败")
                    return@launch
                }
                val url = runCatching { Api.uploadAvatar(data) }.getOrNull()
                // 服务端没配 R2 时上传接口仍返回 200，但回落的 /avatar 地址实际是 HTML 页面，
                // 这里校验响应确实是图片，否则按「服务端不可用」处理，落到本机保存
                if (url != null && isImageUrl(url)) {
                    avatarUrl = url
                    avatarLocal = ""
                    session.avatar = url
                    session.avatarLocal = ""
                    onToast("头像已上传到服务端")
                } else {
                    avatarUrl = ""
                    avatarLocal = data
                    session.avatar = ""
                    session.avatarLocal = data
                    onToast("服务端未返回可用图片，已保存为本地头像")
                }
                avatarSheet = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding(),
    ) {
        OTNavBar(
            title = "个人设置",
            onLeading = onBack,
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).tapArea {
                    if (loaded && !saving) {
                        saving = true
                        scope.launch {
                            runCatching { Api.updateMySetting(signature, sendOnEnter) }
                                .onSuccess {
                                    session.signature = it.signature
                                    session.sendOnEnter = it.sendOnEnter
                                    onToast("已保存")
                                }
                                .onFailure { onToast(friendlyError(it)) }
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
            // 头像 + 名称 + 邮箱
            OTGroup(Modifier.padding(horizontal = OTSpace.s4, vertical = OTSpace.cardGap)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .tapArea { avatarSheet = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OTAvatar(
                        name = displayName.ifEmpty { displayEmail },
                        size = 72,
                        imageUrl = avatarLocal.ifEmpty { avatarUrl },
                    )
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayName.ifEmpty { "邮箱账户" }, style = t.title3, color = c.label)
                        Text(displayEmail, style = t.footnote, color = c.label2)
                        if (avatarLocal.isNotEmpty()) {
                            Text("当前为本地头像（未同步到服务端）", style = t.caption2, color = c.label3)
                        }
                    }
                    Text("更换头像", style = t.subhead, color = c.blue)
                }
            }

            OTGroupHeader("账户")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                SettingRow(
                    title = "用户名",
                    value = displayName,
                    onClick = {
                        nameDraft = displayName
                        nameDialog = true
                    },
                    showChevron = true,
                )
                OTDivider(inset = 16)
                SettingRow(title = "邮箱账户", value = displayEmail)
                OTDivider(inset = 16)
                SettingRow(
                    title = "密码",
                    value = "修改",
                    valueColor = c.blue,
                    onClick = {
                        pwd1 = ""; pwd2 = ""
                        passwordDialog = true
                    },
                )
            }

            OTGroupHeader("安全")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                SettingRow(
                    title = "两步验证",
                    value = if (twoFactor) "已开启" else "未开启",
                    onClick = {
                        twoFactorSecret = ""
                        twoFactorUrl = ""
                        twoFactorCode = ""
                        disableCode = ""
                        twoFactorSheet = true
                    },
                    showChevron = true,
                )
                OTDivider(inset = 16)
                OTSwitchRow(
                    title = "强制两步验证",
                    subtitle = if (twoFactor) "开启后密码登录也必须输入验证码" else "先开启两步验证才能打开",
                    checked = twoFactorRequired,
                ) { next ->
                    if (!twoFactor) {
                        onToast("请先开启两步验证")
                    } else {
                        scope.launch {
                            runCatching { Api.setTwoFactorRequired(next) }
                                .onSuccess { twoFactorRequired = next }
                                .onFailure { onToast(friendlyError(it)) }
                        }
                    }
                }
            }

            OTGroupHeader("签名")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
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

            OTGroupHeader("第三方账户")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                SettingRow(title = "GitHub", value = "仅网页端")
                OTDivider(inset = 16)
                SettingRow(title = "Google", value = "仅网页端")
                OTDivider(inset = 16)
                SettingRow(title = "LinuxDo", value = "仅网页端")
            }
            Text(
                "OAuth 绑定/解绑需要浏览器授权，Android 端未接入（占位）；请在网页版完成绑定。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            OTGroupHeader("更多")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                SettingRow(
                    title = "系统设置",
                    value = "",
                    onClick = onOpenSystemSettings,
                    showChevron = true,
                )
                OTDivider(inset = 16)
                SettingRow(title = "语言", value = "简体中文")
            }

            OTGroupHeader("危险操作")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).tapArea { deleteConfirm = true }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text("删除账户", style = t.body, color = c.red) }
                OTDivider(inset = 16)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).tapArea {
                        session.clear()
                        onBack()
                    }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text("退出登录", style = t.body, color = c.red) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (avatarSheet) {
        OTSheet(onDismiss = { avatarSheet = false }) {
            Text(
                "更换头像",
                style = t.title3,
                color = c.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OTAvatar(
                    name = displayName.ifEmpty { displayEmail },
                    size = 64,
                    imageUrl = avatarLocal.ifEmpty { avatarUrl },
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("从相册选一张，或粘贴图片链接", style = t.footnote, color = c.label2)
                    Text(
                        "服务端接口：POST /my/uploadAvatar（传 R2）",
                        style = t.caption2, color = c.label3,
                    )
                }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OTPrimaryButton("从相册选择") { pickAvatar.launch("image/*") }
            }
            OTFieldRow(
                label = "图片链接",
                value = avatarLink,
                onValueChange = { avatarLink = it },
                hint = "https://…（服务端只接受 http/https 外链）",
            )
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                OTPrimaryButton("保存链接", enabled = avatarLink.startsWith("http")) {
                    scope.launch {
                        runCatching { Api.setAvatar(avatarLink.trim()) }
                            .onSuccess { url ->
                                avatarUrl = url.ifEmpty { avatarLink.trim() }
                                avatarLocal = ""
                                session.avatar = avatarUrl
                                session.avatarLocal = ""
                                avatarSheet = false
                                onToast("头像已更新")
                            }
                            .onFailure { onToast(friendlyError(it)) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).tapArea {
                    scope.launch {
                        runCatching { Api.setAvatar("") }
                            .onSuccess {
                                avatarUrl = ""
                                avatarLocal = ""
                                session.avatar = ""
                                session.avatarLocal = ""
                                avatarSheet = false
                                onToast("已移除头像")
                            }
                            .onFailure { onToast(friendlyError(it)) }
                    }
                }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("移除头像", style = t.body, color = c.red) }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (twoFactorSheet) {
        OTSheet(onDismiss = { twoFactorSheet = false }) {
            Text(
                "两步验证",
                style = t.title3,
                color = c.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (twoFactor) {
                Text(
                    "已开启。输入认证器里的 6 位验证码，或输入登录密码，任选其一即可关闭。",
                    style = t.footnote, color = c.label2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                OTFieldRow(
                    label = "验证码或密码",
                    value = disableCode,
                    onValueChange = { disableCode = it },
                    hint = "6 位验证码 / 登录密码",
                )
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OTPrimaryButton("关闭两步验证", enabled = disableCode.isNotEmpty()) {
                        scope.launch {
                            val codeOrPwd = disableCode.trim()
                            runCatching {
                                if (codeOrPwd.length == 6 && codeOrPwd.all { it.isDigit() }) {
                                    Api.twoFactorDisable(code = codeOrPwd)
                                } else {
                                    Api.twoFactorDisable(password = codeOrPwd)
                                }
                            }.onSuccess {
                                twoFactor = false
                                twoFactorRequired = false
                                twoFactorSheet = false
                                onToast("已关闭两步验证")
                            }.onFailure { onToast(friendlyError(it)) }
                        }
                    }
                }
            } else {
                if (twoFactorSecret.isEmpty()) {
                    Text(
                        "开启后登录需要额外输入认证器生成的 6 位验证码。",
                        style = t.footnote, color = c.label2,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OTPrimaryButton("生成密钥") {
                            scope.launch {
                                runCatching { Api.twoFactorSetup() }
                                    .onSuccess { (secret, url) ->
                                        twoFactorSecret = secret
                                        twoFactorUrl = url
                                    }
                                    .onFailure { onToast(friendlyError(it)) }
                            }
                        }
                    }
                } else {
                    DetailKVText("密钥", twoFactorSecret)
                    DetailKVText("otpauth", twoFactorUrl)
                    Text(
                        "把密钥加进认证器（或用 otpauth 链接），再输入 6 位验证码完成开启。",
                        style = t.footnote, color = c.label2,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    OTFieldRow(
                        label = "验证码",
                        value = twoFactorCode,
                        onValueChange = { if (it.length <= 6) twoFactorCode = it.filter { ch -> ch.isDigit() } },
                        hint = "6 位数字",
                        number = true,
                    )
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OTPrimaryButton("开启两步验证", enabled = twoFactorCode.length == 6) {
                            scope.launch {
                                runCatching { Api.twoFactorEnable(twoFactorCode) }
                                    .onSuccess {
                                        twoFactor = true
                                        twoFactorSheet = false
                                        onToast("两步验证已开启")
                                    }
                                    .onFailure { onToast(friendlyError(it)) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (nameDialog) {
        EditDialog(
            title = "修改用户名",
            label = "用户名",
            value = nameDraft,
            onValueChange = { nameDraft = it },
            confirmText = "保存",
            onCancel = { nameDialog = false },
            onConfirm = {
                if (nameDraft.isBlank()) {
                    onToast("用户名不能为空")
                } else {
                    scope.launch {
                        runCatching { Api.setAccountName(session.accountId, nameDraft.trim()) }
                            .onSuccess {
                                displayName = nameDraft.trim()
                                session.name = nameDraft.trim()
                                nameDialog = false
                                onToast("用户名已更新")
                            }
                            .onFailure { onToast(friendlyError(it)) }
                    }
                }
            },
        )
    }

    if (passwordDialog) {
        AlertDialog(
            onDismissRequest = { passwordDialog = false },
            title = { Text("修改密码", style = t.title3, color = c.label) },
            text = {
                Column {
                    PasswordField("新密码", pwd1) { pwd1 = it }
                    Spacer(Modifier.height(10.dp))
                    PasswordField("确认密码", pwd2) { pwd2 = it }
                    if (pwd1.isNotEmpty() && pwd2.isNotEmpty() && pwd1 != pwd2) {
                        Text("两次输入的密码不一致", style = t.caption2, color = c.red, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pwd1.length < 6) {
                        onToast("密码至少 6 位")
                    } else if (pwd1 != pwd2) {
                        onToast("两次输入的密码不一致")
                    } else {
                        scope.launch {
                            runCatching { Api.resetPassword(pwd1) }
                                .onSuccess {
                                    passwordDialog = false
                                    pwd1 = ""; pwd2 = ""
                                    onToast("密码已修改")
                                }
                                .onFailure { onToast(friendlyError(it)) }
                        }
                    }
                }) { Text("保存", color = c.blue) }
            },
            dismissButton = {
                TextButton(onClick = { passwordDialog = false }) { Text("取消", color = c.label2) }
            },
            containerColor = c.bgElevated,
        )
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("删除账户", style = t.title3, color = c.label) },
            text = {
                Text(
                    "会连同这个账户下的邮箱与邮件一起删除，且不可恢复。确定继续？",
                    style = t.subhead, color = c.label2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { Api.deleteMyAccount() }
                            .onSuccess {
                                deleteConfirm = false
                                session.clear()
                                onBack()
                            }
                            .onFailure { onToast(friendlyError(it)) }
                    }
                }) { Text("删除", color = c.red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("取消", color = c.label2) }
            },
            containerColor = c.bgElevated,
        )
    }
}

/**
 * 系统设置：客户端显示项 + 后台数据入口 + 服务端设置（web 的 sys-setting 页面）。
 * 迁移自抽屉里的「AI 助手/系统设置/管理」入口，抽屉只留邮件与联系人。
 */
@Composable
fun SystemSettingsScreen(
    session: Session,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onOpenServerSettings: () -> Unit,
    onOpenAdmin: (String) -> Unit = {},
) {
    val c = OT.colors
    val t = OT.type
    val scope = rememberCoroutineScope()
    var showAvatars by remember { mutableStateOf(session.showAvatars) }
    var sendOnEnter by remember { mutableStateOf(session.sendOnEnter.ifEmpty { "enter" }) }
    var sendSheet by remember { mutableStateOf(false) }

    // 通知状态：从系统设置返回时（ON_RESUME）重新读，避免显示过期状态
    val context = LocalContext.current
    var notifEnabled by remember { mutableStateOf(notificationsEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notifEnabled = notificationsEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding(),
    ) {
        OTNavBar(title = "系统设置", onLeading = onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OTGroupHeader("显示")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                OTSwitchRow(
                    title = "显示发件人头像",
                    subtitle = "关闭后列表改为显示账户彩点",
                    checked = showAvatars,
                ) { next ->
                    showAvatars = next
                    session.showAvatars = next
                }
                OTDivider(inset = 16)
                SettingRow(
                    title = "回车键发送",
                    value = sendModeLabel(sendOnEnter),
                    onClick = { sendSheet = true },
                    showChevron = true,
                )
            }
            Text(
                "「显示发件人头像」只影响这台设备；「回车键发送」同步到服务端（PUT /my/setting）。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            OTGroupHeader("通知")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                SettingRow(
                    title = "新邮件推送",
                    value = if (notifEnabled) "已启用" else "已关闭",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                },
                            )
                        }
                        notifEnabled = notificationsEnabled(context)
                    },
                    showChevron = true,
                )
            }
            Text(
                "推送走单独的「新邮件」通知渠道（低打扰、不响铃）；Android 13+ 首次登录后只问一次权限，关掉后需要在这里去系统设置重新打开。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            OTGroupHeader("后台数据")
            AdminLinks(onOpenAdmin)
            Text(
                "全部邮件 / 用户管理 / 权限控制 / 注册密钥 / 分析；需要对应管理员权限，接口会返回真实错误。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            OTGroupHeader("服务端")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                SettingRow(
                    title = "服务端设置",
                    value = "AI / 翻译 / 存储",
                    onClick = onOpenServerSettings,
                    showChevron = true,
                )
                OTDivider(inset = 16)
                SettingRow(title = "注册码策略", value = "在服务端设置里", onClick = onOpenServerSettings)
            }

            OTGroupHeader("关于")
            OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                SettingRow(title = "应用", value = stringResource(R.string.app_name))
                OTDivider(inset = 16)
                SettingRow(title = "版本", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                OTDivider(inset = 16)
                SettingRow(title = "服务器", value = session.base)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (sendSheet) {
        OTSheet(onDismiss = { sendSheet = false }) {
            Text(
                "回车键发送",
                style = t.title3,
                color = c.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SEND_MODES.forEachIndexed { i, mode ->
                if (i > 0) OTDivider(inset = 16)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .tapArea {
                            sendSheet = false
                            scope.launch {
                                runCatching { Api.updateMySetting(session.signature, mode.first) }
                                    .onSuccess {
                                        session.sendOnEnter = it.sendOnEnter
                                        sendOnEnter = it.sendOnEnter
                                        onToast("已改为${mode.second}")
                                    }
                                    .onFailure { onToast(friendlyError(it)) }
                            }
                        }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(mode.second, style = t.body, color = c.label, modifier = Modifier.weight(1f))
                    if (sendOnEnter == mode.first) Text("当前", style = t.footnote, color = c.label3)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 系统层面该 App 的通知是否打开（Android 13+ 还包含 POST_NOTIFICATIONS 的结果）。 */
private fun notificationsEnabled(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true

/** 设置页标准行：标题 + 右侧值（可选 chevron）。整页统一无行首图标。 */
@Composable
internal fun SettingRow(
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    valueColor: Color? = null,
) {
    val c = OT.colors
    val t = OT.type
    val base = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val clickable = if (onClick != null) base.tapArea(onClick) else base
    Row(
        clickable.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = t.body, color = c.label, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, style = t.subhead, color = valueColor ?: c.label2, maxLines = 1)
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ot_chevron_right), contentDescription = null, tint = c.label3, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DetailKVText(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(label, style = OT.type.caption1, color = OT.colors.label2)
        Text(value, style = OT.type.subhead, color = OT.colors.label)
    }
}

@Composable
private fun EditDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, style = t.title3, color = c.label) },
        text = {
            Column {
                Text(label, style = t.footnote, color = c.label2)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = t.body.copy(color = c.label),
                    cursorBrush = SolidColor(c.blue),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText, color = c.blue) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消", color = c.label2) } },
        containerColor = c.bgElevated,
    )
}

@Composable
private fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    Column {
        Text(label, style = t.footnote, color = c.label2)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = t.body.copy(color = c.label),
            cursorBrush = SolidColor(c.blue),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** 相册图片 → 512px JPEG data URL（服务端 /my/uploadAvatar 只认这种前缀）。 */
private fun avatarDataUrl(context: android.content.Context, uri: Uri): String? {
    val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
    val scale = minOf(1f, 512f / maxOf(src.width, src.height))
    val out = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        src
    }
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 86, bos)
    return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
}

/** 校验服务端返回的头像地址确实是图片（本地 dev 无 R2 时会返回 SPA 的 HTML） */
private suspend fun isImageUrl(url: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            requestMethod = "GET"
        }
        val type = conn.contentType ?: ""
        conn.responseCode == 200 && type.startsWith("image/")
    }.getOrDefault(false)
}

/** 分组标题（旧调用点仍在使用，转调 OTGroupHeader） */
@Composable
internal fun GroupTitle(text: String) {
    OTGroupHeader(text)
}
