package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.MailItem
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/** 管理区的通用内联导航栏（‹ + 标题 + 可选动作）。 */
@Composable
private fun AdminBar(title: String, action: String? = null, onAction: () -> Unit = {}, onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    OTNavBar(title = title, onLeading = onBack) {
        if (action != null) {
            Box(
                Modifier.height(44.dp).tapArea { onAction() }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(action, style = t.subhead, color = c.blue) }
        }
    }
}

/* ---------------- 全部邮件 ---------------- */

@Composable
fun AllMailScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onToast: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<MailItem>() }
    var total by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }

    var keyword by remember { mutableStateOf("") }
    var pill by remember { mutableStateOf("all") }

    fun load(next: Int) {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching {
                when (pill) {
                    "inbox" -> Api.allEmails(next, keyword, type = 0)
                    "sent" -> Api.allEmails(next, keyword, type = 1)
                    "deleted" -> Api.allEmails(next, keyword, mailbox = "deleted")
                    "noone" -> Api.allEmails(next, keyword, type = 1, status = 7)
                    else -> Api.allEmails(next, keyword, mailbox = "all")
                }
            }
            result.onSuccess { (list, n) ->
                if (next == 1) items.clear()
                items.addAll(list.filter { row -> items.none { it.emailId == row.emailId } })
                total = n
                page = next
                error = ""
            }.onFailure { error = it.message ?: "读取失败"; onToast(error) }
            busy = false
        }
    }

    fun pick(p: String) {
        if (p == pill) return
        pill = p
        page = 1
        items.clear()
        error = ""
        load(1)
    }

    LaunchedEffect(keyword) { load(1) }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("全部邮件", onBack = onBack)
        Column(Modifier.padding(horizontal = d.sp4.dp, vertical = d.sp2.dp)) {
            OTSearchPill(value = keyword, placeholder = "搜索发件人、主题、邮箱", onValueChange = { keyword = it })
            Spacer(Modifier.height(d.sp2.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(d.sp2.dp),
            ) {
                listOf("all" to "全部", "inbox" to "收件", "sent" to "发件", "deleted" to "已删除", "noone" to "无人收件").forEach { (k, label) ->
                    val on = pill == k
                    Box(
                        Modifier
                            .background(if (on) c.blue else c.bgInput, RoundedCornerShape(50))
                            .pressable(shape = RoundedCornerShape(50)) { pick(k) }
                            .padding(horizontal = d.sp3.dp, vertical = OTSpace.s1),
                    ) { Text(label, style = t.caption1, color = if (on) Color.White else c.label2) }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Text(
                    "共 $total 封 · 已加载 ${items.size} 封",
                    style = t.footnote, color = c.label2,
                    modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp2.dp),
                )
            }
            items(items, key = { (it as JSONObject).optString("emailId") }) { it ->
                val m = it as JSONObject
                OTMailRow(
                    from = m.optString("from").ifEmpty { m.optString("to") },
                    subject = buildString {
                        val tag = when {
                            m.optInt("status") == 7 -> "无人收件"
                            m.optInt("type") == 1 -> "发件"
                            else -> "收件"
                        }
                        append("[$tag] ").append(m.optString("subject").ifEmpty { "(无主题)" })
                    },
                    snippet = m.optString("snippet"),
                    time = m.optString("time").take(16),
                    unread = m.optBoolean("unread"),
                    starred = false,
                    category = null,
                    onOpen = { onOpen(m.optLong("emailId")) },
                    onStar = {},
                )
                HorizontalDivider(color = c.separatorOpaque, thickness = 0.5.dp, modifier = Modifier.padding(start = 58.dp))
            }
            if (items.isNotEmpty() && items.size < total.toInt()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().tapArea { if (!busy) load(page + 1) }.padding(d.sp4.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (busy) "加载中…" else "加载更多", style = t.subhead, color = c.blue) }
                }
            }
        }
    }
}

/* ---------------- 用户 ---------------- */

@Composable
fun UsersScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<JSONObject>() }
    var total by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf("") }
    var ufilter by remember { mutableStateOf(-1) }
    var roleNames by remember { mutableStateOf(mapOf<Long, String>()) }

    fun load() {
        scope.launch {
            runCatching {
                val roles = Api.roles()
                val map = mutableMapOf<Long, String>()
                for (r in roles) map[r.optLong("roleId")] = r.optString("roleName")
                roleNames = map
                Api.users(1, ufilter)
            }.onSuccess { pair ->
                val list = pair.first
                val n = pair.second
                items.clear()
                for (x in list) items.add(x)
                total = n
            }.onFailure { error = it.message ?: "读取失败" }
        }
    }

    LaunchedEffect(ufilter) { load() }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("用户管理", onBack = onBack)
        Column(Modifier.padding(horizontal = d.sp4.dp, vertical = d.sp2.dp)) {
            OTSearchPill(value = "", placeholder = "搜索邮箱 / 用户名", onValueChange = {})
            Spacer(Modifier.height(d.sp2.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.sp2.dp)) {
                listOf(-1 to "全部", 0 to "活跃", 1 to "封禁").forEach { (k, label) ->
                    val on = ufilter == k
                    Box(Modifier.background(if (on) c.blue else c.bgInput, RoundedCornerShape(50)).pressable(shape = RoundedCornerShape(50)) { ufilter = k }.padding(horizontal = d.sp3.dp, vertical = OTSpace.s1)) {
                        Text(label, style = t.caption1, color = if (on) Color.White else c.label2)
                    }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            item { Text("共 $total 个用户", style = t.footnote, color = c.label2, modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp2.dp)) }
            items(items) { u ->
                val email = u.optString("username").ifEmpty { u.optString("email") }
                val role = roleNames[u.optLong("type")] ?: "普通用户"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OT.dimens.rowMin.dp)
                        .pressable(scale = 0.99f) { }
                        .padding(horizontal = OT.dimens.screenPad.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OTAvatar(email, 34)
                    Spacer(Modifier.width(OT.dimens.sp3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(email, style = t.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(role, style = t.footnote, color = c.label2)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.label3, modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = c.separatorOpaque, thickness = 0.5.dp, modifier = Modifier.padding(start = 58.dp))
            }
        }
    }
}

/* ---------------- 权限 ---------------- */

@Composable
fun RolesScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<JSONObject>() }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch { runCatching { Api.roles() }.onSuccess { list ->
            items.clear()
            for (x in list) items.add(x)
        }.onFailure { error = it.message ?: "读取失败" } }
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("权限控制", onBack = onBack)
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(items) { r ->
                val permCount = (r.optJSONArray("permIds") ?: JSONArray()).length()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OT.dimens.rowMin.dp)
                        .pressable(scale = 0.99f) { }
                        .padding(horizontal = OT.dimens.screenPad.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(34.dp).background(c.purple.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Shield, null, tint = c.purple, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(OT.dimens.sp3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.optString("roleName"), style = t.body, color = c.label)
                        Text("排序 ${r.optInt("sort")} · $permCount 项权限", style = t.footnote, color = c.label2)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = c.label3, modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = c.separatorOpaque, thickness = 0.5.dp, modifier = Modifier.padding(start = 58.dp))
            }
            item { Text("提示：角色权限在样张中以开关树展示，当前仅列表查看。", style = t.footnote, color = c.label2, modifier = Modifier.padding(d.sp5.dp)) }
        }
    }
}

/* ---------------- 注册密钥 ---------------- */

@Composable
fun RegKeysScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<JSONObject>() }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch {
            runCatching { Api.regKeys(1) }.onSuccess { pair ->
                val list = pair.first
                items.clear()
                for (x in list) items.add(x)
            }.onFailure { error = it.message ?: "读取失败" }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("注册密钥", onBack = onBack)
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(items) { rk ->
                val code = rk.optString("key")
                val used = rk.optInt("used")
                val count = rk.optInt("count")
                val expireTime = rk.optString("expireTime")
                val exp = if (expireTime.length >= 10) expireTime.substring(0, 10) else ""
                val expired = used >= count || exp.isEmpty()
                val roleName = rk.optString("roleName").ifEmpty { "普通角色" }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OT.dimens.rowMin.dp)
                        .padding(horizontal = OT.dimens.screenPad.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(34.dp).background(c.green.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.VpnKey, null, tint = c.green, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(OT.dimens.sp3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(code, style = t.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$roleName · 已用 $used / $count 次 · ${if (exp.isEmpty()) "已过期" else "$exp 到期"}", style = t.footnote, color = c.label2)
                    }
                    Box(Modifier.background((if (expired) c.label3 else c.green).copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 4.dp)) {
                        Text(if (expired) "失效" else "有效", style = t.caption2, color = if (expired) c.label3 else c.green)
                    }
                }
                HorizontalDivider(color = c.separatorOpaque, thickness = 0.5.dp, modifier = Modifier.padding(start = 58.dp))
            }
        }
    }
}

/* ---------------- 分析 ---------------- */

private val nf = NumberFormat.getNumberInstance(Locale.US)
private fun fmt(n: Int): String = nf.format(n.toLong())

@Composable
private fun Stat(label: String, value: Int, subOk: Int? = null, subDel: Int? = null, modifier: Modifier = Modifier) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    Column(
        modifier.background(c.bgCard, RoundedCornerShape(16.dp)).padding(d.sp3.dp),
    ) {
        Text(label, style = t.caption1, color = c.label2)
        Text(fmt(value), style = t.title1.copy(fontWeight = FontWeight.SemiBold), color = c.label)
        if (subOk != null || subDel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (subOk != null) Text("活跃 ${fmt(subOk)}", style = t.caption1, color = c.green)
                if (subOk != null && subDel != null) Spacer(Modifier.size(OTSpace.s1))
                if (subDel != null) Text("已删除 ${fmt(subDel)}", style = t.caption1, color = c.label2)
            }
        }
    }
}

@Composable
private fun HBar(name: String, total: Int, max: Int, pct: Int) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = d.sp5.dp, vertical = OTSpace.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name, style = t.caption1, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(84.dp).padding(end = d.sp2.dp),
        )
        Box(Modifier.weight(1f).height(6.dp).background(c.bgInput, RoundedCornerShape(3.dp))) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((total.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f))
                    .background(c.blue, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.size(d.sp2.dp))
        Text("$pct%", style = t.caption1, color = c.label2, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun Bars(data: JSONArray, label: String) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val max = (0 until data.length()).maxOfOrNull { data.optJSONObject(it).optInt("total") } ?: 1
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = d.sp4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until data.length()) {
            val v = data.optJSONObject(i).optInt("total")
            val f = (v.toFloat() / max.coerceAtLeast(1)).coerceAtLeast(0.05f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(f)
                    .background(
                        if (i == data.length() - 1) c.blue else c.blue.copy(alpha = 0.32f),
                        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                    ),
            )
        }
    }
    Text(label, style = t.caption1, color = c.label3, modifier = Modifier.padding(start = OT.dimens.sp5.dp).padding(top = OTSpace.s1))
}

@Composable
fun AnalysisScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    var e by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { Api.analysisEcharts() }.onSuccess { e = it }.onFailure { error = it.message ?: "读取失败" }
    }

    val nc = e?.optJSONObject("numberCount")
    val names = e?.optJSONObject("receiveRatio")?.optJSONArray("nameRatio") ?: JSONArray()
    val userDay = e?.optJSONArray("userDayCount") ?: JSONArray()
    val receiveDay = e?.optJSONObject("emailDayCount")?.optJSONArray("receiveDayCount") ?: JSONArray()
    val receiveTotal = nc?.optInt("receiveTotal") ?: 0
    val maxName = (0 until names.length()).maxOfOrNull { names.optJSONObject(it).optInt("total") } ?: 1

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("分析", onBack = onBack)
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OTGroupHeader("总览")
            Row(Modifier.fillMaxWidth().padding(horizontal = d.sp4.dp), horizontalArrangement = Arrangement.spacedBy(d.sp2.dp)) {
                Stat("总收件", nc?.optInt("receiveTotal") ?: 0, nc?.optInt("normalReceiveTotal"), nc?.optInt("delReceiveTotal"), Modifier.weight(1f))
                Stat("总发件", nc?.optInt("sendTotal") ?: 0, nc?.optInt("normalSendTotal"), nc?.optInt("delSendTotal"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(d.sp2.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = d.sp4.dp), horizontalArrangement = Arrangement.spacedBy(d.sp2.dp)) {
                Stat("邮箱", nc?.optInt("accountTotal") ?: 0, nc?.optInt("normalAccountTotal"), nc?.optInt("delAccountTotal"), Modifier.weight(1f))
                Stat("总用户", nc?.optInt("userTotal") ?: 0, nc?.optInt("normalUserTotal"), nc?.optInt("delUserTotal"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(d.sp3.dp))
            OTGroupHeader("邮件来源")
            if (names.length() == 0) {
                Text("暂无数据", style = t.footnote, color = c.label3, modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp2.dp))
            } else {
                for (i in 0 until names.length()) {
                    val r = names.optJSONObject(i)
                    val total = r.optInt("total")
                    HBar(name = r.optString("name"), total = total, max = maxName, pct = if (receiveTotal > 0) total * 100 / receiveTotal else 0)
                }
            }
            Spacer(Modifier.height(d.sp3.dp))
            OTGroupHeader("用户增长")
            Bars(userDay, "最近 14 天")
            Spacer(Modifier.height(d.sp3.dp))
            OTGroupHeader("邮件增长")
            Bars(receiveDay, "最近 14 天")
            Spacer(Modifier.height(d.sp3.dp))
            OTGroupHeader("今日发送")
            Text(
                fmt(e?.optInt("daySendTotal") ?: 0),
                style = t.title1.copy(fontWeight = FontWeight.SemiBold),
                color = c.label,
                modifier = Modifier.padding(horizontal = d.sp5.dp),
            )
            Text(
                "数据来自服务端分析缓存（/analysis/echarts）。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp3.dp),
            )
            Spacer(Modifier.height(d.sp6.dp))
        }
    }
}

/* ---------------- 404 ---------------- */

@Composable
fun NotFoundScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    Column(
        Modifier.fillMaxSize().background(c.bgCard).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MailOutline, contentDescription = null, tint = c.label3, modifier = Modifier.size(64.dp))
            Icon(
                Icons.Outlined.Close, contentDescription = null, tint = c.label3,
                modifier = Modifier.size(22.dp).offset(x = 16.dp, y = 16.dp),
            )
        }
        Text(
            "404",
            style = t.largeTitle.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            color = c.label3,
        )
        Text("页面不存在", style = t.title3, color = c.label, modifier = Modifier.padding(top = d.sp2.dp))
        Text("链接可能已经失效，或者你没有这个页面的权限。", style = t.subhead, color = c.label2, modifier = Modifier.padding(top = OTSpace.s1))
        Box(
            Modifier
                .padding(top = d.sp5.dp)
                .widthIn(min = 180.dp)
                .background(c.blue, RoundedCornerShape(10.dp))
                .pressable(scale = 0.97f) { onBack() }
                .padding(horizontal = d.sp5.dp, vertical = d.sp3.dp),
        ) { Text("返回收件箱", style = t.body, color = Color.White, textAlign = TextAlign.Center) }
    }
}
