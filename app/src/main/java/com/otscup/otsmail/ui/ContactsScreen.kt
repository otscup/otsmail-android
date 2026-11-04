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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.otscup.otsmail.R
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

private data class Contact(val name: String, val email: String, val group: String = "") {
    val letter: String
        get() {
            val ch = name.trim().take(1).uppercase()
            return if (ch.isNotEmpty() && ch[0] in 'A'..'Z') ch else "#"
        }
}

/**
 * 联系人：优先用后端通讯录（/contact/list）；通讯录为空时退回「从收件箱抽出不重复发件人」。
 * 点一行直接写信给这个地址。
 */
@Composable
fun ContactsScreen(session: Session, onBack: () -> Unit, onWrite: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val scope = rememberCoroutineScope()

    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        runCatching {
            val book = runCatching { Api.contacts() }.getOrDefault(emptyList())
            if (book.isNotEmpty()) {
                contacts = book.map { (name, email, group) -> Contact(name, email, group) }
                    .sortedBy { it.name.lowercase() }
                return@runCatching
            }
            val seen = LinkedHashMap<String, Contact>()
            // 两页足够凑出 30–40 个联系人；再多等后端接口
            for (page in 1..2) {
                val (list, _) = Api.emails(session.accountId, page)
                list.forEach { m ->
                    val email = m.from.trim()
                    if (email.contains("@") && !seen.containsKey(email)) {
                        seen[email] = Contact(m.name.ifEmpty { email.substringBefore("@") }, email)
                    }
                }
                if (list.isEmpty()) break
            }
            contacts = seen.values.sortedBy { it.name.lowercase() }
        }.onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding(),
    ) {
        OTNavBar(title = "联系人", onLeading = onBack) {
            Text(
                if (contacts.isEmpty()) "" else "${contacts.size}",
                style = t.footnote, color = c.label2,
                modifier = Modifier.padding(horizontal = OTSpace.s2),
            )
        }

        when {
            error.isNotEmpty() -> Text(error, style = t.body, color = c.red, modifier = Modifier.padding(16.dp))
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", style = t.footnote, color = c.label2)
            }
            contacts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有联系人", style = t.footnote, color = c.label2)
            }
            else -> {
                val groups = contacts.groupBy { it.letter }.toList()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        groups.forEach { (letter, rows) ->
                            item(key = "h-$letter") { OTGroupHeader(letter) }
                            item(key = "g-$letter") {
                                OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                                    rows.forEachIndexed { i, ct ->
                                        if (i > 0) OTDivider(inset = 60)
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 60.dp)
                                                .tapArea { onWrite(ct.email) }
                                                .padding(horizontal = OTSpace.s4, vertical = OTSpace.s2),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(OTSpace.s3),
                                        ) {
                                            OTAvatar(ct.name, size = 36)
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    ct.name, style = t.body, color = c.label,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    if (ct.group.isEmpty()) ct.email else "${ct.email} · ${ct.group}",
                                                    style = t.footnote, color = c.label2,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Icon(
                                                painterResource(R.drawable.ot_chevron_right), contentDescription = null,
                                                tint = c.label3, modifier = Modifier.size(18.dp),
                                            )
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
                        groups.forEach { (letter, _) ->
                            Box(
                                modifier = Modifier.width(24.dp).height(18.dp).tapArea {
                                    val idx = groups.indexOfFirst { it.first == letter }
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
}
