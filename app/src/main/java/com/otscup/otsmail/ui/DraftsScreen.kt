package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Draft
import com.otscup.otsmail.data.Drafts
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 草稿箱：只列本机、本账号的草稿（服务端没有 draft 表，跨端同步无从谈起）。
 * 点一行回到写信页，整份草稿（收件人/抄送/密送/主题/正文与光标/附件/发件人）都会还原。
 */
@Composable
fun DraftsScreen(accountId: Long, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var ver by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Draft>>(emptyList()) }
    // 草稿文件可能带附件 base64，读盘放到 IO 线程，不阻塞首帧
    LaunchedEffect(ver) {
        list = withContext(Dispatchers.IO) { Drafts.list(ctx, accountId) }
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        OTNavBar(title = "草稿（${list.size}）", onLeading = onBack)
        if (list.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Edit, null, tint = c.label3, modifier = Modifier.size(48.dp))
                Spacer(Modifier.heightIn(min = 12.dp))
                Text("还没有草稿", style = t.body, color = c.label)
                Spacer(Modifier.heightIn(min = 4.dp))
                Text("写信内容会自动存一份到这里", style = t.subhead, color = c.label2)
            }
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OTGroup(Modifier.padding(horizontal = OTSpace.s4, vertical = OTSpace.cardGap)) {
                    list.forEachIndexed { i, dr ->
                        if (i > 0) OTDivider(inset = 16)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .tapArea { onOpen(dr.id) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    dr.subject.ifEmpty { "（无主题）" },
                                    style = t.body, color = c.label, maxLines = 1,
                                )
                                Row {
                                    Text(
                                        dr.toTags.joinToString(", ").ifEmpty {
                                            dr.toInput.ifBlank { "（无收件人）" }
                                        } + " · " + dr.body.take(24),
                                        style = t.caption1, color = c.label2, maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        TimeFmt.rowTime(Instant.ofEpochMilli(dr.updatedAt).toString()),
                                        style = t.caption1, color = c.label3, maxLines = 1,
                                    )
                                }
                            }
                            Box(
                                Modifier.tapArea {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { Drafts.delete(ctx, dr.id) }
                                        ver += 1
                                    }
                                }.padding(8.dp),
                            ) { Text("删", style = t.footnote, color = c.red) }
                        }
                    }
                }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }
}
