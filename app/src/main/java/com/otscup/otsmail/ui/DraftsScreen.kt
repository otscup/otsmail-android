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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Drafts
import com.otscup.otsmail.ui.theme.OT
import org.json.JSONObject
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon

/** 草稿箱：读本机 SharedPreferences（网页版的草稿在浏览器 IndexedDB，后端没有表）。 */
@Composable
fun DraftsScreen(onBack: () -> Unit, onOpen: (JSONObject) -> Unit) {
    val c = OT.colors
    val t = OT.type
    val ctx = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    val list = remember(ver) { Drafts.list(ctx) }

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
                Text("写信页点「取消」会自动存一份到这里", style = t.subhead, color = c.label2)
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
                                .tapArea { onOpen(dr) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    dr.optString("subject").ifEmpty { "（无主题）" },
                                    style = t.body, color = c.label, maxLines = 1,
                                )
                                Text(
                                    dr.optString("to").ifEmpty { "（无收件人）" } + " · " + dr.optString("text").take(24),
                                    style = t.caption1, color = c.label2, maxLines = 1,
                                )
                            }
                            Box(
                                Modifier.tapArea {
                                    Drafts.remove(ctx, dr.optLong("at"))
                                    ver += 1
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
