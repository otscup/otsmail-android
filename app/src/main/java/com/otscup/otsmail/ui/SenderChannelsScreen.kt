package com.otscup.otsmail.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 发信渠道：与网页端「系统设置 → 发信渠道」同一套接口（GET/PUT /setting/senderProviders）。
 * 服务端已按渠道 × 自然日/自然月在 send_quota 里原子计数，这里的用量与剩余额度都是真实值，
 * 不额外造接口，也不在客户端另存一份计数。
 */
private class ChannelRow(
    val id: String,
    val name: String,
    val signupUrl: String,
    val note: String,
    val spfInclude: String,
    val needSecret: Boolean,
    /** 渠道目录里的 DNS 模板：type + 记录名 + 用途 */
    val dnsTemplates: List<Triple<String, String, String>>,
) {
    var enabled by mutableStateOf(false)
    var order by mutableStateOf("1")
    var apiKey by mutableStateOf("")
    var secret by mutableStateOf("")
    var dailyLimit by mutableStateOf("")
    var monthlyLimit by mutableStateOf("")
    /** 渠道目录里的默认额度：手填值优先，留空/恢复默认时回落到这里 */
    var defaultDailyLimit by mutableStateOf("")
    var defaultMonthlyLimit by mutableStateOf("")
    var dailyLimitManual by mutableStateOf(false)
    var monthlyLimitManual by mutableStateOf(false)
    var configured by mutableStateOf(false)
    var cooling by mutableStateOf(false)
    var expanded by mutableStateOf(false)
    var usageDay by mutableStateOf(0)
    var usageMonth by mutableStateOf(0)
    var remainDay by mutableStateOf(0)
    var remainMonth by mutableStateOf(0)

    /** 手填上限改动后按同一口径重算剩余，保存前也能看到变化 */
    fun syncRemaining() {
        remainDay = ((dailyLimit.toIntOrNull() ?: 0) - usageDay).coerceAtLeast(0)
        remainMonth = ((monthlyLimit.toIntOrNull() ?: 0) - usageMonth).coerceAtLeast(0)
    }

    /** 用户填过的 DNS 记录值，键是记录名（相对或完整域名） */
    val dnsValues = mutableStateMapOf<String, String>()
    var testing by mutableStateOf(false)
}

private class ProviderSummary {
    var total by mutableStateOf(0)
    var synced by mutableStateOf(0)
    var routable by mutableStateOf(0)
    var usedDay by mutableStateOf(0)
    var usedMonth by mutableStateOf(0)
    var limitDay by mutableStateOf(0)
    var limitMonth by mutableStateOf(0)
    var remainDay by mutableStateOf(0)
    var remainMonth by mutableStateOf(0)
}

@Composable
fun SenderChannelsScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    val rows = remember { mutableStateListOf<ChannelRow>() }
    val summary = remember { ProviderSummary() }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var dnsDomain by remember { mutableStateOf("") }
    /** 核验结果：fqdn / providerId -> 状态文案 */
    val dnsStatus = remember { mutableStateMapOf<String, String>() }
    val spfStatus = remember { mutableStateMapOf<String, String>() }

    fun applyData(data: JSONObject) {
        val list = data.optJSONArray("providers") ?: return
        if (rows.isNotEmpty()) return
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val meta = item.optJSONObject("meta") ?: JSONObject()
            val templates = meta.optJSONArray("dnsRecords") ?: org.json.JSONArray()
            val dns = mutableListOf<Triple<String, String, String>>()
            for (j in 0 until templates.length()) {
                val rec = templates.optJSONObject(j) ?: continue
                dns += Triple(rec.optString("type", "TXT"), rec.optString("name"), rec.optString("purpose"))
            }
            val row = ChannelRow(
                id = item.optString("id"),
                name = item.optString("name"),
                signupUrl = meta.optString("signupUrl"),
                note = meta.optString("note"),
                spfInclude = meta.optString("spfInclude"),
                needSecret = meta.optJSONArray("keyFields")?.toString()?.contains("secret") == true,
                dnsTemplates = dns,
            )
            row.enabled = item.optBoolean("enabled")
            row.order = item.optInt("order", 1).toString()
            row.apiKey = item.optString("apiKey")
            row.secret = item.optString("secret")
            row.dailyLimit = item.optInt("dailyLimit").toString()
            row.monthlyLimit = item.optInt("monthlyLimit").toString()
            row.defaultDailyLimit = item.optInt("defaultDailyLimit", item.optInt("dailyLimit")).toString()
            row.defaultMonthlyLimit = item.optInt("defaultMonthlyLimit", item.optInt("monthlyLimit")).toString()
            row.dailyLimitManual = item.optBoolean("dailyLimitManual")
            row.monthlyLimitManual = item.optBoolean("monthlyLimitManual")
            row.configured = item.optBoolean("configured")
            row.cooling = item.optBoolean("cooling")
            val usage = item.optJSONObject("usage")
            row.usageDay = usage?.optInt("day") ?: 0
            row.usageMonth = usage?.optInt("month") ?: 0
            val remain = item.optJSONObject("remaining")
            row.remainDay = remain?.optInt("day") ?: 0
            row.remainMonth = remain?.optInt("month") ?: 0
            val entered = item.optJSONObject("dnsRecords")
            entered?.keys()?.forEach { key -> row.dnsValues[key] = entered.optString(key) }
            for (template in dns) if (!row.dnsValues.containsKey(template.second)) row.dnsValues[template.second] = ""
            rows += row
        }

        val s = data.optJSONObject("summary")
        summary.total = s?.optInt("total") ?: rows.size
        summary.synced = s?.optInt("synced") ?: 0
        summary.routable = s?.optInt("routable") ?: 0
        summary.usedDay = s?.optJSONObject("usage")?.optInt("day") ?: 0
        summary.usedMonth = s?.optJSONObject("usage")?.optInt("month") ?: 0
        summary.limitDay = s?.optJSONObject("limits")?.optInt("day") ?: 0
        summary.limitMonth = s?.optJSONObject("limits")?.optInt("month") ?: 0
        summary.remainDay = s?.optJSONObject("remaining")?.optInt("day") ?: 0
        summary.remainMonth = s?.optJSONObject("remaining")?.optInt("month") ?: 0
    }

    fun reload() {
        scope.launch {
            loading = true
            runCatching { Api.senderProviders() }
                .onSuccess {
                    rows.clear()
                    applyData(it)
                    error = ""
                }
                .onFailure { error = friendlyError(it).ifEmpty { "读取发信渠道失败" } }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val payload = JSONObject()
            for (row in rows) {
                val dns = JSONObject()
                for ((key, value) in row.dnsValues) if (value.trim().isNotEmpty()) dns.put(key, value.trim())
                payload.put(row.id, JSONObject()
                    .put("enabled", row.enabled)
                    .put("order", row.order.toIntOrNull() ?: 1)
                    .put("apiKey", row.apiKey)
                    .put("secret", row.secret)
                    // 留空 = 跟随渠道目录默认额度（服务端按默认值计算剩余），不再兜成 1
                    .put("dailyLimit", row.dailyLimit.toIntOrNull() ?: JSONObject.NULL)
                    .put("monthlyLimit", row.monthlyLimit.toIntOrNull() ?: JSONObject.NULL)
                    .put("dnsRecords", dns))
            }
            runCatching { Api.saveSenderProviders(payload) }
                .onSuccess {
                    rows.clear()
                    applyData(it)
                    error = ""
                    onToast("已保存，立即参与轮换")
                }
                .onFailure { onToast(friendlyError(it).ifEmpty { "保存失败" }) }
            saving = false
        }
    }

    fun checkDns() {
        if (checking) return
        checking = true
        scope.launch {
            runCatching { Api.checkSenderProviderDns() }
                .onSuccess { data ->
                    dnsStatus.clear()
                    spfStatus.clear()
                    dnsDomain = data.optString("domain")
                    val providers = data.optJSONObject("providers") ?: JSONObject()
                    providers.keys().forEach { id ->
                        val one = providers.optJSONObject(id) ?: return@forEach
                        val records = one.optJSONArray("records") ?: org.json.JSONArray()
                        for (i in 0 until records.length()) {
                            val rec = records.optJSONObject(i) ?: continue
                            dnsStatus["$id|${rec.optString("name")}"] = rec.optString("status")
                        }
                        val spf = one.optJSONObject("spf")
                        if (spf != null) spfStatus[id] = spf.optString("status")
                    }
                    onToast("DNS 核验完成")
                }
                .onFailure { onToast(friendlyError(it).ifEmpty { "DNS 核验失败" }) }
            checking = false
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
        OTNavBar(title = "发信渠道", onLeading = onBack) {
            Box(
                Modifier.height(OTSpace.s8).tapArea { save() }.padding(horizontal = OTSpace.s2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (saving) "保存中" else "保存",
                    style = t.subhead,
                    color = if (saving || loading) c.label3 else c.blue,
                )
            }
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (loading) {
                Text("读取中…", style = t.footnote, color = c.label2, modifier = Modifier.padding(d.sp4.dp))
                return@Column
            }
            if (error.isNotEmpty()) {
                Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
            }

            Spacer(Modifier.height(OTSpace.s2))
            // 汇总：已同步渠道 / 参与轮换 / 今日剩余 / 本月剩余
            Column(Modifier.padding(horizontal = d.sp4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(OTSpace.s2)) {
                    SummaryTile("已同步渠道", "${summary.synced}", "/${summary.total}", Modifier.weight(1f))
                    SummaryTile("参与轮换", "${summary.routable}", "/${summary.synced}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(OTSpace.s2))
                Row(horizontalArrangement = Arrangement.spacedBy(OTSpace.s2)) {
                    SummaryTile(
                        "今日剩余", "${summary.remainDay}",
                        " · ${summary.usedDay}/${summary.limitDay}", Modifier.weight(1f),
                    )
                    SummaryTile(
                        "本月剩余", "${summary.remainMonth}",
                        " · ${summary.usedMonth}/${summary.limitMonth}", Modifier.weight(1f),
                    )
                }
                Text(
                    "以上只统计已同步渠道（含已暂停/冷却的），未同步渠道不计入；已用量是本站按自然日（UTC）统计的真实发送数。",
                    style = t.caption2, color = c.label3,
                    modifier = Modifier.padding(top = OTSpace.s2),
                )
                Text(
                    "这几个渠道都没有开放额度查询接口，服务商后台的额度请到其控制台查看；每日/每月上限可在渠道详情里手填，手填值优先于默认值。",
                    style = t.caption2, color = c.label3,
                    modifier = Modifier.padding(top = 2.dp, bottom = OTSpace.s4),
                )
            }

            for (row in rows) {
                ChannelCard(
                    row = row,
                    dnsDomain = dnsDomain,
                    dnsStatus = dnsStatus,
                    spfStatus = spfStatus,
                    onOpenSignup = { url -> runCatching { uriHandler.openUri(url) } },
                    onTest = {
                        if (!row.testing) {
                            row.testing = true
                            scope.launch {
                                runCatching { Api.testSenderProvider(row.id) }
                                    .onSuccess { data ->
                                        onToast("${data.optString("provider", row.name)} → ${data.optString("to")}")
                                    }
                                    .onFailure { onToast(friendlyError(it).ifEmpty { "测试发送失败" }) }
                                row.testing = false
                            }
                        }
                    },
                )
            }

            Box(Modifier.padding(horizontal = d.sp4.dp, vertical = OTSpace.s2)) {
                Text(
                    if (checking) "DNS 核验中…" else "核验 DNS",
                    style = t.subhead, color = if (checking) c.label3 else c.blue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.bgCard)
                        .tapArea { checkDns() }
                        .padding(horizontal = OTSpace.s4, vertical = OTSpace.s3),
                )
            }
            Text(
                "填好 Key 并打开开关就是接入一个渠道；保存后立即生效，手机端发信会自动轮换，不需要单独配置。",
                style = t.caption2, color = c.label3,
                modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = OTSpace.s3),
            )
            Spacer(Modifier.height(OTSpace.s6))
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, suffix: String, modifier: Modifier = Modifier) {
    val c = OT.colors
    val t = OT.type
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgCard)
            .padding(horizontal = OTSpace.s3, vertical = OTSpace.s3),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = t.title3, color = c.label)
            Text(suffix, style = t.caption1, color = c.label3, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(label, style = t.caption2, color = c.label2, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ChannelCard(
    row: ChannelRow,
    dnsDomain: String,
    dnsStatus: Map<String, String>,
    spfStatus: Map<String, String>,
    onOpenSignup: (String) -> Unit,
    onTest: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens

    Column(
        Modifier
            .padding(horizontal = d.sp4.dp)
            .padding(bottom = OTSpace.cardGap)
            .clip(RoundedCornerShape(14.dp))
            .background(c.bgCard)
            .padding(horizontal = OTSpace.s4, vertical = OTSpace.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).tapArea { row.expanded = !row.expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (row.expanded) "▾" else "▸", style = t.caption1, color = c.label3)
                Spacer(Modifier.size(6.dp))
                Text(row.name, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                Spacer(Modifier.size(OTSpace.s2))
                Text(
                    if (row.configured) "已同步" else "未同步",
                    style = t.caption2,
                    color = if (row.configured) c.green else c.label3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (row.configured) c.green.copy(alpha = 0.12f) else c.bgInput)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                if (row.cooling) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "冷却中", style = t.caption2, color = c.orange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.orange.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(if (row.expanded) "收起" else "详情", style = t.caption2, color = c.blue)
            }
            OTSwitch(checked = row.enabled, onChange = { row.enabled = it })
        }

        // 折叠态：一条额度摘要，先看清有哪些渠道，点开再看 Key / DNS / 上限
        if (!row.expanded) {
            Text(
                "日 ${row.dailyLimit} · 今日已发 ${row.usageDay} · 今日剩余 ${row.remainDay} · 本月剩余 ${row.remainMonth}",
                style = t.caption2, color = c.label2,
                modifier = Modifier.padding(top = OTSpace.s2),
            )
            return@Column
        }

        Spacer(Modifier.height(OTSpace.s3))
        UsageLine("每日上限 ${row.dailyLimit}", "今日已发", row.usageDay, row.remainDay, row.dailyLimit.toIntOrNull() ?: 0)
        Spacer(Modifier.height(OTSpace.s2))
        UsageLine("月限额 ${row.monthlyLimit}", "本月已发", row.usageMonth, row.remainMonth, row.monthlyLimit.toIntOrNull() ?: 0)

        Spacer(Modifier.height(OTSpace.s3))
        HorizontalDivider(color = c.separatorSoft)
        Spacer(Modifier.height(OTSpace.s2))

        ChannelInput("API Key", row.apiKey, "粘贴渠道后台的 Key") { row.apiKey = it }
        if (row.needSecret) {
            Spacer(Modifier.height(OTSpace.s2))
            ChannelInput("Secret", row.secret, "Mailjet 需要 Key + Secret") { row.secret = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OTSpace.s2)) {
            Box(Modifier.weight(1f)) {
                ChannelInput("日限额", row.dailyLimit, "默认 ${row.defaultDailyLimit}", number = true) { value ->
                    row.dailyLimit = value
                    row.dailyLimitManual = value.toIntOrNull() != null && value.toIntOrNull() != row.defaultDailyLimit.toIntOrNull()
                    row.syncRemaining()
                }
                LimitHint(row.dailyLimitManual, row.defaultDailyLimit) {
                    row.dailyLimit = row.defaultDailyLimit
                    row.dailyLimitManual = false
                    row.syncRemaining()
                }
            }
            Box(Modifier.weight(1f)) {
                ChannelInput("月限额", row.monthlyLimit, "默认 ${row.defaultMonthlyLimit}", number = true) { value ->
                    row.monthlyLimit = value
                    row.monthlyLimitManual = value.toIntOrNull() != null && value.toIntOrNull() != row.defaultMonthlyLimit.toIntOrNull()
                    row.syncRemaining()
                }
                LimitHint(row.monthlyLimitManual, row.defaultMonthlyLimit) {
                    row.monthlyLimit = row.defaultMonthlyLimit
                    row.monthlyLimitManual = false
                    row.syncRemaining()
                }
            }
            Box(Modifier.weight(0.6f)) {
                ChannelInput("优先级", row.order, "1", number = true) { row.order = it }
            }
        }

        Row(
            Modifier.padding(top = OTSpace.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (row.testing) "发送中…" else "测试发送",
                style = t.footnote,
                color = if (row.testing) c.label3 else c.blue,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.bgInput)
                    .tapArea { onTest() }
                    .padding(horizontal = OTSpace.s3, vertical = 6.dp),
            )
            if (row.signupUrl.isNotEmpty()) {
                Spacer(Modifier.size(OTSpace.s3))
                Text(
                    "注册地址", style = t.footnote, color = c.blue,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.tapArea { onOpenSignup(row.signupUrl) },
                )
            }
        }

        if (row.note.isNotEmpty()) {
            Text(
                row.note, style = t.caption2, color = c.label3,
                modifier = Modifier.padding(top = OTSpace.s2),
            )
        }

        if (row.dnsTemplates.isNotEmpty() || row.spfInclude.isNotEmpty()) {
            Text(
                "DNS 记录", style = t.caption2, color = c.label2,
                modifier = Modifier.padding(top = OTSpace.s3, bottom = 4.dp),
            )
            for (template in row.dnsTemplates) {
                val storedKey = row.dnsValues.keys.firstOrNull {
                    it == template.second || it.endsWith(".${template.second}")
                } ?: template.second
                val fqdn = if (dnsDomain.isEmpty() || storedKey.contains(dnsDomain)) storedKey else "$storedKey.$dnsDomain"
                val status = dnsStatus["${row.id}|$fqdn"] ?: (dnsStatus["${row.id}|$storedKey"] ?: "unverified")
                DnsRow(
                    label = "${template.first} $storedKey",
                    value = row.dnsValues[storedKey].orEmpty(),
                    status = status,
                    onChange = { row.dnsValues[storedKey] = it },
                )
            }
            if (row.spfInclude.isNotEmpty()) {
                DnsRow(
                    label = "TXT @ (include:${row.spfInclude})",
                    value = "",
                    status = spfStatus[row.id] ?: "not_required",
                    onChange = {},
                    readOnly = true,
                )
            }
        }
    }
}

@Composable
private fun LimitHint(manual: Boolean, defaultLimit: String, onReset: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    Text(
        if (manual) "默认 $defaultLimit，手填值优先" else "默认 $defaultLimit，填写后以手填为准",
        style = t.caption2, color = c.label3,
        modifier = Modifier.padding(top = 2.dp),
    )
    if (manual) {
        Text(
            "恢复默认", style = t.caption2, color = c.blue,
            modifier = Modifier.padding(top = 1.dp).tapArea { onReset() },
        )
    }
}

@Composable
private fun UsageLine(label: String, usedLabel: String, used: Int, remain: Int, limit: Int) {
    val c = OT.colors
    val t = OT.type
    val fraction = if (limit <= 0) 0f else (used.toFloat() / limit).coerceIn(0f, 1f)
    val barColor = when {
        fraction >= 0.9f -> c.red
        fraction >= 0.7f -> c.orange
        else -> c.green
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = t.caption2, color = c.label2, modifier = Modifier.weight(1f))
            Text("$usedLabel $used", style = t.caption2, color = c.label2)
            Text("  剩余 $remain", style = t.caption2, color = c.label)
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(c.bgInput),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(barColor),
                )
            }
        }
    }
}

@Composable
private fun ChannelInput(
    label: String,
    value: String,
    hint: String,
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    Column(Modifier.padding(top = OTSpace.s2)) {
        Text(label, style = t.caption2, color = c.label2)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = t.subhead.copy(color = c.label),
            cursorBrush = SolidColor(c.blue),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.bgInput)
                .padding(horizontal = OTSpace.s2, vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(hint, style = t.subhead, color = c.label3)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun DnsRow(
    label: String,
    value: String,
    status: String,
    onChange: (String) -> Unit,
    readOnly: Boolean = false,
) {
    val c = OT.colors
    val t = OT.type
    val (statusText, statusColor) = when (status) {
        "ok" -> "已生效" to c.green
        "missing" -> "未找到" to c.red
        "mismatch" -> "值不匹配" to c.orange
        "error" -> "查询失败" to c.red
        "not_required" -> "不需要" to c.label3
        else -> "待填写" to c.label3
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = t.caption2, color = c.label2)
            if (!readOnly) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = t.caption2.copy(color = c.label),
                    cursorBrush = SolidColor(c.blue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.bgInput)
                        .padding(horizontal = OTSpace.s2, vertical = 7.dp),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Text("粘贴渠道后台给出的记录值", style = t.caption2, color = c.label3)
                            }
                            inner()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.size(OTSpace.s2))
        Text(
            statusText, style = t.caption2, color = statusColor,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(statusColor.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
