package com.otscup.otsmail.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class Kind { TEXT, SECRET, NUM, SWITCH }

/** switch 的 on/off 取值：服务端多数用 0=开启、1=关闭（样例 sys-setting 的 active-value 就是 0）。 */
private data class Field(
    val key: String,
    val label: String,
    val kind: Kind = Kind.TEXT,
    val hint: String = "",
    val onValue: Int = 0,
    val offValue: Int = 1,
)

private data class Card(val title: String, val fields: List<Field>)

private val CARDS = listOf(
    Card("网站设置", listOf(
        Field("register", "用户注册", Kind.SWITCH),
        Field("loginDomain", "隐藏登录域名", Kind.SWITCH),
        Field("regKey", "注册码", Kind.NUM, "0 关闭 / 1 必填 / 2 可选"),
        Field("addEmail", "添加邮箱", Kind.SWITCH),
        Field("manyEmail", "多号模式", Kind.SWITCH),
        Field("syncDelete", "同步删除", Kind.SWITCH),
        Field("emailPrefixFilter", "邮箱前缀过滤", Kind.TEXT, "留空表示不限制"),
    )),
    Card("个性化", listOf(
        Field("title", "网站标题"),
        Field("subtitle", "登录副标题"),
        Field("loginOpacity", "登录框透明度", Kind.NUM, "0.1 – 1"),
        Field("chatOpacity", "聊天框透明度", Kind.NUM, "0.1 – 1"),
    )),
    Card("邮件设置", listOf(
        Field("receive", "邮件接收", Kind.SWITCH),
        Field("send", "邮件发送", Kind.SWITCH),
        Field("noRecipient", "无人收件", Kind.SWITCH),
        Field("autoRefresh", "自动刷新（秒）", Kind.NUM, "0 关闭"),
        Field("resendToken", "Resend Token", Kind.SECRET, "留空表示原来的能用"),
        Field("blackList", "邮件黑名单", Kind.TEXT, "多个用逗号分隔"),
    )),
    Card("对象存储", listOf(
        Field("storageType", "存储类型", Kind.TEXT, "KV / R2 / S3"),
        Field("r2Domain", "存储域名", Kind.TEXT, "留空用本站域名"),
        Field("bucket", "Bucket"),
        Field("endpoint", "Endpoint"),
        Field("region", "Region"),
        Field("s3AccessKey", "Access Key"),
        Field("s3SecretKey", "Secret Key", Kind.SECRET, "留空表示不改"),
        Field("forcePathStyle", "强制路径风格", Kind.SWITCH),
    )),
    Card("邮件推送", listOf(
        Field("tgBotStatus", "Telegram 机器人", Kind.SWITCH),
        Field("forwardStatus", "邮件转发", Kind.SWITCH),
        Field("ruleType", "转发规则", Kind.SWITCH),
        Field("autoCleanDays", "自动清理（天）", Kind.NUM, "0 不清理"),
        Field("webhookUrl", "Webhook 地址"),
        Field("webhookStatus", "Webhook 启用", Kind.SWITCH),
        Field("webhookRetry", "Webhook 重试次数", Kind.NUM, "0 – 5"),
        Field("webhookSecret", "Webhook 密钥", Kind.SECRET, "留空表示不改"),
    )),
    Card("人机验证", listOf(
        Field("registerVerify", "注册验证", Kind.NUM, "0 启用 / 1 关闭 / 2 规则验证"),
        Field("addEmailVerify", "添加邮箱验证", Kind.NUM, "同上"),
        Field("siteKey", "Site Key"),
        Field("secretKey", "Secret Key", Kind.SECRET, "留空表示不改"),
    )),
    Card("公告", listOf(
        Field("notice", "显示公告", Kind.SWITCH),
        Field("noticeTitle", "标题"),
        Field("noticeContent", "内容"),
        Field("noticePosition", "位置", Kind.NUM, "1 顶 / 2 底"),
        Field("noticeWidth", "宽度（像素）", Kind.NUM),
        Field("noticeOffset", "偏移（像素）", Kind.NUM),
        Field("noticeDuration", "显示时长（秒）", Kind.NUM),
    )),
    Card("AI 与翻译", listOf(
        Field("aiBaseUrl", "接口地址", Kind.TEXT, "填到 /v1 为止"),
        Field("aiApiKey", "API Key", Kind.SECRET, "留空表示不改"),
        Field("aiModel", "模型", Kind.TEXT, "例如 gpt-4o-mini"),
        Field("deeplKey", "DeepL Key", Kind.SECRET, "留空表示不改"),
    )),
)

/**
 * 系统设置：8 张卡都由 CARDS 这张表驱动（同一套表单渲染，不重复写 8 遍）。
 * 只有带 setting:set 权限的账号（默认管理员）能写；接口返回的 Key 是打码值，不回传。
 */
@Composable
fun SysSettingsScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()

    var opened by remember { mutableStateOf<Card?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val current = remember { mutableStateMapOf<String, String>() }
    val edits = remember { mutableStateMapOf<String, String>() }
    /** 原始值快照，保存时只提交真正改过的字段。 */
    val origin = remember { mutableStateMapOf<String, String>() }

    /** 从服务端回读，灌满 current / origin，并清空未保存的编辑。 */
    fun reload(s: JSONObject) {
        current.clear(); origin.clear(); edits.clear()
        for (card in CARDS) for (f in card.fields) {
            val v = s.optString(f.key, "")
            current[f.key] = v
            origin[f.key] = v
        }
    }

    fun load() {
        scope.launch {
            loading = true
            runCatching { reload(Api.settings()) }
                .onFailure { error = it.message ?: "读取失败（需要 setting:set 权限）" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    fun commit() {
        if (saving) return
        val patch = JSONObject()
        for ((k, v) in edits) {
            if (v == origin[k]) continue
            if (v.isEmpty()) continue // 空 = 不改，避免把别人的配置清空
            patch.put(k, v)
        }
        if (patch.length() == 0) { error = "没有要保存的改动"; return }
        saving = true
        scope.launch {
            runCatching { Api.setSetting(patch) }
                .onSuccess {
                    error = ""
                    onToast("已保存 ${patch.length()} 项，立即生效")
                    runCatching { reload(Api.settings()) }
                }
                .onFailure { error = it.message ?: "保存失败" }
            saving = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgGrouped)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        OTNavBar(
            title = opened?.title ?: "系统设置",
            onLeading = { if (opened != null) opened = null else onBack() },
        ) {
            if (opened != null) {
                Box(
                    Modifier.height(44.dp).tapArea { commit() }.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (saving) "保存中" else "保存", style = t.subhead, color = if (saving) c.label3 else c.blue) }
            }
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (error.isNotEmpty()) {
                Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
            }
            if (loading) {
                Text("读取中…", style = t.footnote, color = c.label2, modifier = Modifier.padding(d.sp4.dp))
                return@Column
            }

            val card = opened
            if (card == null) {
                for ((i, one) in CARDS.withIndex()) {
                    if (i == 0) GroupTitle("站点")
                    if (i == 3) GroupTitle("存储与推送")
                    if (i == 7) GroupTitle("AI")
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = d.sp4.dp, vertical = if (i == 0 || i == 3 || i == 7) 0.dp else OTSpace.cardGap)
                            .clip(RoundedCornerShape(14.dp)).background(c.bgCard),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressable(pressedColor = c.bgInput) { opened = one }
                                .padding(horizontal = d.sp4.dp, vertical = d.sp3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(one.title, style = t.body, color = c.label, modifier = Modifier.weight(1f))
                            Text(summary(one, current), style = t.footnote, color = c.label2)
                            Text("  ›", style = t.body, color = c.label3)
                        }
                    }
                }
                Text(
                    "只有带 setting:set 权限的账号（默认管理员）能改；接口返回的 Key 是打码值，不会回传覆盖。",
                    style = t.footnote, color = c.label2,
                    modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp3.dp),
                )
                Spacer(Modifier.height(d.sp6.dp))
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = d.sp4.dp)
                        .clip(RoundedCornerShape(14.dp)).background(c.bgCard),
                ) {
                    card.fields.forEachIndexed { idx, f ->
                        if (idx > 0) HorizontalDivider(color = c.separator)
                        FieldRow(f, current[f.key].orEmpty(), edits[f.key]) { edits[f.key] = it }
                    }
                }
                Text(
                    "留空 = 不改这一项；开关按下即换状态，点右上「保存」才提交。",
                    style = t.footnote, color = c.label2,
                    modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp2.dp),
                )
                Spacer(Modifier.height(d.sp6.dp))
            }
        }
    }
}

private fun summary(card: Card, current: Map<String, String>): String {
    val on = card.fields.count { it.kind == Kind.SWITCH && current[it.key] == it.onValue.toString() }
    val total = card.fields.count { it.kind == Kind.SWITCH }
    return if (total > 0 && on > 0) "$on/$total 开启" else ""
}

@Composable
private fun FieldRow(f: Field, value: String, editing: String?, onChange: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens

    if (f.kind == Kind.SWITCH) {
        val isOn = (editing ?: value) == f.onValue.toString()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(pressedColor = c.bgInput) {
                    onChange(if (isOn) f.offValue.toString() else f.onValue.toString())
                }
                .padding(horizontal = d.sp4.dp, vertical = d.sp3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.label, style = t.body, color = c.label, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(51.dp, 31.dp)
                    .background(if (isOn) c.green else c.label3, RoundedCornerShape(16.dp)),
            ) {
                Box(
                    Modifier
                        .padding(2.dp)
                        .size(27.dp)
                        .align(if (isOn) Alignment.CenterEnd else Alignment.CenterStart)
                        .background(c.bgCard, CircleShape),
                )
            }
        }
        return
    }

    Column(Modifier.padding(horizontal = d.sp4.dp, vertical = d.sp2.dp)) {
        Text(f.label, style = t.caption1, color = c.label2)
        BasicTextField(
            value = editing ?: "",
            onValueChange = onChange,
            singleLine = true,
            textStyle = t.body.copy(color = c.label),
            cursorBrush = SolidColor(c.blue),
            keyboardOptions = KeyboardOptions(keyboardType = if (f.kind == Kind.NUM) KeyboardType.Number else KeyboardType.Text),
            visualTransformation = if (f.kind == Kind.SECRET) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            decorationBox = { inner ->
                val shown = if (editing.isNullOrEmpty()) value else ""
                if (shown.isEmpty() && editing.isNullOrEmpty()) {
                    Text(f.hint.ifEmpty { "未配置" }, style = t.body, color = c.label3)
                } else if (editing.isNullOrEmpty()) {
                    Text(value, style = t.body, color = c.label)
                } else {
                    inner()
                }
            },
        )
        if (f.hint.isNotEmpty() && !editing.isNullOrEmpty()) {
            Text(f.hint, style = t.caption2, color = c.label3, modifier = Modifier.padding(top = 2.dp))
        }
        Text("当前：${if (value.isEmpty()) "未配置" else value}", style = t.caption2, color = c.label3, modifier = Modifier.padding(top = 2.dp))
    }
}
