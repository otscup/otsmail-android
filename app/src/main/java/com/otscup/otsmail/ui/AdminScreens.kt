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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.MailItem
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOff

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

    LaunchedEffect(Unit) {
        runCatching { Api.allEmails(1) }
            .onSuccess { (list, n) -> items.clear(); items.addAll(list); total = n }
            .onFailure { error = it.message ?: "读取失败"; onToast(error) }
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("全部邮件", onBack = onBack)
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Text(
                    "共 $total 封 · 前 ${items.size} 封",
                    style = t.footnote, color = c.label2,
                    modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp2.dp),
                )
            }
            items(items, key = { it.emailId }) { m ->
                Row(
                    Modifier.fillMaxWidth()
                        .pressable(pressedColor = c.bgInput) { onOpen(m.emailId) }
                        .padding(horizontal = d.sp4.dp, vertical = d.sp3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.from, style = t.footnote, color = c.label2)
                        Text(m.subject.ifEmpty { "(无主题)" }, style = t.body, color = c.label, maxLines = 1)
                    }
                    Text(m.time.takeLast(5), style = t.caption1, color = c.label3)
                    Spacer(Modifier.size(d.sp2.dp))
                    Text(
                        "删", style = t.footnote, color = c.red,
                        modifier = Modifier.pressable(scale = 0.94f, pressedColor = c.bgInput) {
                            scope.launch {
                                runCatching { Api.trash(listOf(m.emailId)) }
                                    .onSuccess { items.remove(m); total -= 1; onToast("已移入已删除") }
                                    .onFailure { onToast(it.message ?: "删除失败") }
                            }
                        }.padding(d.sp2.dp),
                    )
                }
                HorizontalDivider(color = c.separator, modifier = Modifier.padding(start = d.sp4.dp))
            }
        }
    }
}

/* ---------------- 用户管理 ---------------- */

@Composable
fun UsersScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val users = remember { mutableStateListOf<JSONObject>() }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            runCatching { Api.users(1) }
                .onSuccess { (list, _) -> users.clear(); users.addAll(list) }
                .onFailure { error = it.message ?: "读取失败" }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("用户管理", onBack = onBack)
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(users, key = { it.optLong("userId").toString() }) { u ->
                val banned = u.optInt("status") == 1
                OTGroup(Modifier.padding(horizontal = d.sp4.dp, vertical = 4.dp)) {
                    OTRow(
                        title = u.optString("email"),
                        subtitle = "收 ${u.optInt("receiveEmailCount")} · 发 ${u.optInt("sendEmailCount")} · ${u.optInt("accountCount")} 个邮箱",
                        icon = if (banned) Icons.Outlined.PersonOff else Icons.Outlined.Person,
                        iconTint = if (banned) c.red else c.blue,
                        trailing = {
                            Text(
                                text = if (banned) "解封" else "封禁",
                                style = t.footnote, color = c.blue,
                                modifier = Modifier.pressable(scale = 0.94f, pressedColor = c.bgInput) {
                                    if (busy) return@pressable
                                    busy = true
                                    scope.launch {
                                        runCatching { Api.setUserStatus(u.optLong("userId"), if (banned) 0 else 1) }
                                            .onSuccess { onToast(if (banned) "已解封" else "已封禁"); load() }
                                            .onFailure { onToast(it.message ?: "操作失败") }
                                        busy = false
                                    }
                                }.padding(d.sp2.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

/* ---------------- 权限控制 ---------------- */

@Composable
fun RolesScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val roles = remember { mutableStateListOf<JSONObject>() }
    var opened by remember { mutableStateOf<JSONObject?>(null) }
    var tree by remember { mutableStateOf<List<Pair<String, List<JSONObject>>>>(emptyList()) }
    val checked = remember { mutableStateListOf<Long>() }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { Api.roles() }.onSuccess { roles.clear(); roles.addAll(it) }
            .onFailure { error = it.message ?: "读取失败" }
    }

    fun open(role: JSONObject) {
        opened = role
        checked.clear()
        (role.optJSONArray("permIds") ?: org.json.JSONArray()).let { a ->
            for (i in 0 until a.length()) checked.add(a.getLong(i))
        }
        if (tree.isEmpty()) {
            scope.launch {
                runCatching { Api.rolePerms() }.onSuccess { tree = it }
                    .onFailure { error = it.message ?: "权限点读取失败" }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar(
            title = opened?.optString("name") ?: "权限控制",
            action = if (opened != null) "保存" else null,
            onAction = {
                val role = opened ?: return@AdminBar
                scope.launch {
                    runCatching {
                        Api.setRole(
                            JSONObject()
                                .put("roleId", role.optLong("roleId"))
                                .put("name", role.optString("name"))
                                .put("sort", role.optInt("sort"))
                                .put("description", role.optString("description"))
                                .put("permIds", org.json.JSONArray(checked.toList())),
                        )
                    }.onSuccess { onToast("已保存权限") }.onFailure { onToast(it.message ?: "保存失败") }
                }
            },
            onBack = { if (opened != null) opened = null else onBack() },
        )
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))

        if (opened == null) {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(roles, key = { it.optLong("roleId").toString() }) { r ->
                    Row(
                        Modifier.fillMaxWidth()
                            .pressable(pressedColor = c.bgInput) { open(r) }
                            .padding(horizontal = d.sp4.dp, vertical = d.sp3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.optString("name"), style = t.body, color = c.label)
                            Text(
                                (if (r.optBoolean("isDefault")) "默认角色 · " else "") + "排序 ${r.optInt("sort")}",
                                style = t.caption1, color = c.label2,
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = c.label3, modifier = Modifier.size(20.dp))
                    }
                    HorizontalDivider(color = c.separator, modifier = Modifier.padding(start = d.sp4.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                tree.forEach { (group, perms) ->
                    item { GroupTitle(group) }
                    items(perms, key = { it.optLong("permId").toString() }) { p ->
                        val id = p.optLong("permId")
                        val on = checked.contains(id)
                        Row(
                            Modifier.fillMaxWidth()
                                .pressable(pressedColor = c.bgInput) { if (on) checked.remove(id) else checked.add(id) }
                                .padding(horizontal = d.sp4.dp, vertical = d.sp2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                p.optString("name", p.optString("permName", "权限 $id")),
                                style = t.body, color = c.label, modifier = Modifier.weight(1f),
                            )
                            if (on) Icon(Icons.Outlined.Check, null, tint = c.blue, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                item { Spacer(Modifier.height(d.sp6.dp)) }
            }
        }
    }
}

/* ---------------- 注册密钥 ---------------- */

@Composable
fun RegKeysScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    val keys = remember { mutableStateListOf<JSONObject>() }
    var error by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            runCatching { Api.regKeys(1) }
                .onSuccess { (list, _) -> keys.clear(); keys.addAll(list) }
                .onFailure { error = it.message ?: "读取失败" }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("注册密钥", onBack = onBack)
        if (error.isNotEmpty()) Text(error, style = t.footnote, color = c.red, modifier = Modifier.padding(d.sp4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(keys, key = { it.optLong("regKeyId").toString() }) { k ->
                val used = k.optInt("usedCount")
                val count = k.optInt("count")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = d.sp4.dp, vertical = d.sp3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(k.optString("code"), style = t.body, color = c.label)
                        Text(
                            "$used / $count 次 · 到期 ${k.optString("expireTime").take(10)}",
                            style = t.caption1, color = c.label2,
                        )
                    }
                    Text(
                        "删除", style = t.footnote, color = c.red,
                        modifier = Modifier.pressable(scale = 0.94f, pressedColor = c.bgInput) {
                            scope.launch {
                                runCatching { Api.deleteRegKeys(listOf(k.optLong("regKeyId"))) }
                                    .onSuccess { onToast("已删除"); load() }
                                    .onFailure { onToast(it.message ?: "删除失败") }
                            }
                        }.padding(d.sp2.dp),
                    )
                }
                HorizontalDivider(color = c.separator, modifier = Modifier.padding(start = d.sp4.dp))
            }
        }
    }
}

/* ---------------- 分析 ---------------- */

@Composable
fun AnalysisScreen(onBack: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    var counts by remember { mutableStateOf<JSONObject?>(null) }
    var users by remember { mutableStateOf(0L) }
    var emails by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        runCatching { Api.counts() }.onSuccess { counts = it }
        runCatching { Api.users(1) }.onSuccess { (_, n) -> users = n }
        runCatching { Api.allEmails(1) }.onSuccess { (_, n) -> emails = n }
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        AdminBar("分析", onBack = onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            GroupTitle("总览")
            Row(Modifier.fillMaxWidth().padding(horizontal = d.sp4.dp), horizontalArrangement = Arrangement.spacedBy(d.sp2.dp)) {
                Stat("收件", counts?.optInt("inbox") ?: 0, Modifier.weight(1f))
                Stat("发件", counts?.optInt("sent") ?: 0, Modifier.weight(1f))
            }
            Spacer(Modifier.height(d.sp2.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = d.sp4.dp), horizontalArrangement = Arrangement.spacedBy(d.sp2.dp)) {
                Stat("用户", users.toInt(), Modifier.weight(1f))
                Stat("全部邮件", emails.toInt(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(d.sp2.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = d.sp4.dp), horizontalArrangement = Arrangement.spacedBy(d.sp2.dp)) {
                Stat("已删除", counts?.optInt("deleted") ?: 0, Modifier.weight(1f))
                Stat("垃圾", counts?.optInt("spam") ?: 0, Modifier.weight(1f))
            }
            Text(
                "数字来自 /email/menu-counts、/user/list、/email/list 的 total；网页版分析页的图表（增长趋势/今日发送）依赖 /analysis 缓存表，本轮没接。",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = d.sp5.dp, vertical = d.sp3.dp),
            )
            Spacer(Modifier.height(d.sp6.dp))
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    Column(
        modifier.background(c.bgCard, RoundedCornerShape(d.rMd.dp)).padding(d.sp3.dp),
    ) {
        Text(label, style = t.caption1, color = c.label2)
        Text("$value", style = t.title1, color = c.label)
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
        Text("404", style = t.largeTitle, color = c.label3)
        Text("页面不存在", style = t.body, color = c.label, modifier = Modifier.padding(top = d.sp2.dp))
        Text("链接可能已经失效，或者你没有这个页面的权限。", style = t.footnote, color = c.label2, modifier = Modifier.padding(top = d.sp1.dp))
        Box(
            Modifier.padding(top = d.sp5.dp)
                .background(c.blue, RoundedCornerShape(d.rSm.dp))
                .pressable(scale = 0.97f) { onBack() }
                .padding(horizontal = d.sp5.dp, vertical = d.sp3.dp),
        ) { Text("返回收件箱", style = t.body, color = androidx.compose.ui.graphics.Color.White) }
    }
}
