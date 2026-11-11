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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.R
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.ApiException
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

/** 邮箱格式只在本地做一次拦截，最终仍以服务端校验为准（服务端规则见 contact-service）。 */
private val CONTACT_EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

private data class ContactRow(val item: Api.ContactItem) {
    val letter: String
        get() {
            val ch = item.name.trim().take(1).uppercase()
            return if (ch.isNotEmpty() && ch[0] in 'A'..'Z') ch else "#"
        }
}

/**
 * 联系人：优先用后端通讯录（/contact/list，字段 contactId/name/email/groupName/remark）。
 * 点一行进入编辑（可改名/邮箱/分组/备注、可删除）；右上角「＋」新建；编辑面板里也能直接写邮件。
 * 通讯录为空时退回「从收件箱抽出不重复发件人」，这时没有 contactId，保存会走 /contact/add。
 */
@Composable
fun ContactsScreen(session: Session, onBack: () -> Unit, onWrite: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val scope = rememberCoroutineScope()

    var contacts by remember { mutableStateOf<List<ContactRow>>(emptyList()) }
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reloadTick by remember { mutableIntStateOf(0) }
    var vipOnly by remember { mutableStateOf(false) }
    // null = 面板关闭；Api.ContactItem(id=0) = 新建；否则是编辑已有联系人
    var editing by remember { mutableStateOf<Api.ContactItem?>(null) }
    var deleting by remember { mutableStateOf<Api.ContactItem?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(reloadTick) {
        loading = contacts.isEmpty()
        error = ""
        runCatching { Api.contactList() }
            .onSuccess { (list, gs) ->
                groups = gs
                if (list.isNotEmpty()) {
                    contacts = list.map { ContactRow(it) }.sortedBy { it.item.name.lowercase() }
                } else {
                    val seen = LinkedHashMap<String, ContactRow>()
                    // 没有通讯录时用收件人兜底：两页足够凑出 30–40 个联系人
                    for (page in 1..2) {
                        val (mails, _) = Api.emails(session.accountId, page)
                        mails.forEach { m ->
                            val email = m.from.trim()
                            if (email.contains("@") && !seen.containsKey(email)) {
                                seen[email] = ContactRow(
                                    Api.ContactItem(
                                        id = 0,
                                        name = m.name.ifEmpty { email.substringBefore("@") },
                                        email = email,
                                        group = "",
                                    ),
                                )
                            }
                        }
                        if (mails.isEmpty()) break
                    }
                    contacts = seen.values.sortedBy { it.item.name.lowercase() }
                }
            }
            .onFailure { e ->
                // 401 由 AuthState 统一提示，这里不再重复报错
                if (!(e is ApiException && e.httpCode == 401)) error = friendlyError(e)
            }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding(),
    ) {
        OTNavBar(title = "联系人", onLeading = onBack) {
            if (contacts.isNotEmpty()) {
                Text("${contacts.size}", style = t.footnote, color = c.label2)
            }
            Box(
                Modifier.size(44.dp).tapArea {
                    editing = Api.ContactItem(0, "", "", groups.firstOrNull() ?: "")
                },
                contentAlignment = Alignment.Center,
            ) {
                Text("＋", style = t.title3, color = c.blue)
            }
        }

        val vipCount = contacts.count { it.item.isVip }
        if (contacts.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(OT.dimens.rPill.dp))
                        .background(if (!vipOnly) c.bgPressed else c.bgInput)
                        .tapArea { vipOnly = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("全部 (${contacts.size})", style = t.caption1, color = if (!vipOnly) c.label else c.label2)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(OT.dimens.rPill.dp))
                        .background(if (vipOnly) Color(0xFFFFB300).copy(alpha = 0.18f) else c.bgInput)
                        .tapArea { vipOnly = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "⭐ 特别关注 ($vipCount)",
                        style = t.caption1.copy(fontWeight = if (vipOnly) FontWeight.Bold else FontWeight.Normal),
                        color = if (vipOnly) Color(0xFFD97706) else c.label2,
                    )
                }
            }
        }

        val displayContacts = if (vipOnly) contacts.filter { it.item.isVip } else contacts

        when {
            error.isNotEmpty() -> Text(error, style = t.body, color = c.red, modifier = Modifier.padding(16.dp))
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", style = t.footnote, color = c.label2)
            }
            contacts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有联系人", style = t.footnote, color = c.label2)
            }
            vipOnly && displayContacts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无特别关注联系人，可点击联系人右侧星标添加", style = t.footnote, color = c.label2)
            }
            else -> {
                val grouped = displayContacts.groupBy { it.letter }.toList()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        grouped.forEach { (letter, rows) ->
                            item(key = "h-$letter") { OTGroupHeader(letter) }
                            item(key = "g-$letter") {
                                OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                                    rows.forEachIndexed { i, row ->
                                        if (i > 0) OTDivider(inset = 60)
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 60.dp)
                                                .tapArea { editing = row.item }
                                                .padding(horizontal = OTSpace.s4, vertical = OTSpace.s2),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(OTSpace.s3),
                                        ) {
                                            OTAvatar(row.item.name, size = 36)
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        row.item.name, style = t.body, color = c.label,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    )
                                                    if (row.item.isVip) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Box(
                                                            Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(Color(0xFFFFB300).copy(alpha = 0.15f))
                                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                                        ) {
                                                            Text("⭐ VIP", style = t.caption2.copy(fontWeight = FontWeight.Bold), color = Color(0xFFD97706))
                                                        }
                                                    }
                                                }
                                                Text(
                                                    if (row.item.group.isEmpty()) row.item.email
                                                    else "${row.item.email} · ${row.item.group}",
                                                    style = t.footnote, color = c.label2,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Box(
                                                Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .tapArea {
                                                        scope.launch {
                                                            runCatching { Api.contactToggleVip(row.item) }
                                                                .onSuccess { reloadTick++ }
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (row.item.isVip) {
                                                    Icon(
                                                        Icons.Filled.Star,
                                                        contentDescription = "取消特别关注",
                                                        tint = Color(0xFFFFB300),
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                } else {
                                                    Icon(
                                                        Icons.Outlined.StarBorder,
                                                        contentDescription = "设为特别关注",
                                                        tint = c.label3,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(OTSpace.s6)) }
                    }

                    // 右侧字母索引（样张 .azindex）：点一下滚到该首字母的第一组
                    Column(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        grouped.forEach { (letter, _) ->
                            Box(
                                modifier = Modifier.width(24.dp).height(18.dp).tapArea {
                                    val idx = grouped.indexOfFirst { it.first == letter }
                                    // 每组占两项（标题 + 卡片）
                                    if (idx >= 0) scope.launch { listState.animateScrollToItem(idx * 2) }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(letter, style = t.caption2, color = c.blue)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { contact ->
        ContactEditSheet(
            contact = contact,
            groups = groups,
            onWrite = { email ->
                editing = null
                onWrite(email)
            },
            onSave = { name, email, group, remark, done ->
                scope.launch {
                    val result = runCatching {
                        if (contact.id > 0) {
                            Api.contactSet(contact.id, name, email, group, remark)
                        } else {
                            Api.contactAdd(name, email, group, remark)
                        }
                    }
                    if (result.isSuccess) {
                        editing = null
                        reloadTick++
                        done("")
                    } else {
                        val e = result.exceptionOrNull()
                        done(if (e == null || (e is ApiException && e.httpCode == 401)) "" else friendlyError(e))
                    }
                }
            },
            onDelete = { deleting = contact },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { contact ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除联系人", color = c.label) },
            text = {
                Text("将从通讯录中移除 ${contact.name.ifEmpty { contact.email }}，此操作不可撤销。", color = c.label2)
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { Api.contactDelete(contact.id) }
                            .onSuccess {
                                editing = null
                                reloadTick++
                            }
                            .onFailure { e ->
                                error = if (e is ApiException && e.httpCode == 401) "" else friendlyError(e)
                            }
                    }
                }) { Text("删除", color = c.red) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消", color = c.label2) }
            },
            containerColor = c.bgElevated,
        )
    }
}

/** 联系人编辑面板：只放服务端真实支持的字段（姓名/邮箱/分组/备注）。 */
@Composable
private fun ContactEditSheet(
    contact: Api.ContactItem,
    groups: List<String>,
    onWrite: (String) -> Unit,
    onSave: (String, String, String, String, (String) -> Unit) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    var name by remember(contact) { mutableStateOf(contact.name) }
    var email by remember(contact) { mutableStateOf(contact.email) }
    var group by remember(contact) { mutableStateOf(contact.group) }
    var remark by remember(contact) { mutableStateOf(contact.remark) }
    var isVip by remember(contact) { mutableStateOf(contact.isVip) }
    var err by remember(contact) { mutableStateOf("") }
    var busy by remember(contact) { mutableStateOf(false) }
    val isNew = contact.id <= 0
    val emailOk = CONTACT_EMAIL_RE.matches(email.trim())

    OTSheet(onDismiss = onDismiss) {
        Text(
            if (isNew) "新建联系人" else "编辑联系人",
            style = t.title3,
            color = c.label,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        OTFieldRow(label = "姓名", value = name, onValueChange = { name = it }, hint = "张三")
        OTDivider(inset = 16)
        OTFieldRow(
            label = "邮箱地址",
            value = email,
            onValueChange = { email = it; err = "" },
            hint = "name@example.com",
            singleLine = true,
            trailing = {
                if (email.isNotEmpty() && !emailOk) {
                    Text("格式不正确", style = t.caption1, color = c.red)
                }
            },
        )
        OTDivider(inset = 16)
        OTFieldRow(label = "分组", value = group, onValueChange = { group = it }, hint = "默认")
        if (groups.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.take(4).forEach { g ->
                    val on = g == group
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(OT.dimens.rPill.dp))
                            .background(if (on) c.bgPressed else c.bgInput)
                            .tapArea { group = g }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(g, style = t.caption1, color = if (on) c.label else c.label2)
                    }
                }
            }
        }
        OTDivider(inset = 16)
        OTFieldRow(label = "备注", value = remark, onValueChange = { remark = it }, hint = "可选", singleLine = false)
        OTDivider(inset = 16)
        OTSwitchRow(
            title = "特别关注 (VIP 联系人)",
            subtitle = "在收件箱中突出显示 ⭐ VIP 徽章",
            checked = isVip,
            onChange = { isVip = it },
        )

        if (err.isNotEmpty()) {
            Text(err, style = t.footnote, color = c.red, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                OTPrimaryButton(
                    if (busy) "保存中…" else "保存",
                    enabled = !busy && emailOk,
                ) {
                    busy = true
                    val finalGroup = if (isVip && group.isBlank()) "VIP" else group
                    val finalRemark = if (isVip && !remark.contains("VIP", ignoreCase = true)) {
                        if (remark.isBlank()) "VIP特别关注" else "$remark [VIP]"
                    } else if (!isVip) {
                        remark.replace("[VIP]", "").replace("(VIP)", "").replace("VIP特别关注", "").replace("特别关注", "").trim()
                    } else {
                        remark.trim()
                    }
                    onSave(name.trim(), email.trim(), finalGroup.trim(), finalRemark) { message ->
                        busy = false
                        err = message
                    }
                }
            }
            if (emailOk) {
                Text(
                    "写信",
                    style = t.subhead,
                    color = c.blue,
                    modifier = Modifier.tapArea { onWrite(email.trim()) }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            if (!isNew) {
                Text(
                    "删除",
                    style = t.subhead,
                    color = c.red,
                    modifier = Modifier.tapArea { onDelete() }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
