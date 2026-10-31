// 抽屉对齐设计稿：帐户行未读胶囊 + 邮箱行计数 + 选中灰底 + 抬头齿轮
const fs = require('fs')
const I = 'app/src/main/java/com/otscup/otsmail/ui/InboxScreen.kt'
let s = fs.readFileSync(I, 'utf8')
const orig = s

if (!s.includes('var counts by remember')) {
  s = s.replace('    var accVersion by remember { mutableStateOf(0) }',
    '    var accVersion by remember { mutableStateOf(0) }\n' +
    '    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }')
  s = s.replace(
    '    LaunchedEffect(Unit) { runCatching { Api.accounts() }.onSuccess { accounts = it } }',
    '    LaunchedEffect(Unit) { runCatching { Api.accounts() }.onSuccess { accounts = it } }\n' +
    '    LaunchedEffect(accVersion) { runCatching { Api.menuCounts() }.onSuccess { counts = it } }')
}

s = s.replace('                        Column {\n' +
'                            Text(session.name.ifEmpty { "我" }, style = t.body, color = c.label)\n' +
'                            Text(session.email, style = t.footnote, color = c.label2)\n' +
'                        }',
'                        Column(Modifier.weight(1f)) {\n' +
'                            Text(session.name.ifEmpty { "我" }, style = t.body, color = c.label)\n' +
'                            Text(session.email, style = t.footnote, color = c.label2)\n' +
'                        }\n' +
'                        Box(Modifier.size(36.dp).tapArea { scope.launch { drawer.close() }; onOpenSysSettings() }, contentAlignment = Alignment.Center) {\n' +
'                            Icon(Icons.Outlined.Settings, contentDescription = "系统设置", tint = c.label2, modifier = Modifier.size(20.dp))\n' +
'                        }')

s = s.replace('                                Text(acc.second, style = t.body, color = if (active) c.blue else c.label, maxLines = 1)',
'                                Text(acc.second, style = t.body, color = if (active) c.blue else c.label, maxLines = 1, modifier = Modifier.weight(1f))\n' +
'                                val unreadN = counts["inbox"] ?: 0\n' +
'                                if (active && unreadN > 0) {\n' +
'                                    Text(\n' +
'                                        unreadN.toString(),\n' +
'                                        style = t.caption1,\n' +
'                                        color = Color.White,\n' +
'                                        modifier = Modifier\n' +
'                                            .clip(RoundedCornerShape(10.dp))\n' +
'                                            .background(c.orange)\n' +
'                                            .padding(horizontal = 7.dp, vertical = 2.dp),\n' +
'                                    )\n' +
'                                }')

s = s.replace('                    .tapArea {\n' +
'                                        current = box\n' +
'                                        scope.launch { drawer.close() }\n' +
'                                    }',
'                    .background(if (box == current) c.bgPressed else Color.Transparent)\n' +
'                                    .tapArea {\n' +
'                                        current = box\n' +
'                                        scope.launch { drawer.close() }\n' +
'                                    }')

s = s.replace('Text(box.label, style = t.body, color = if (box == current) c.blue else c.label)',
'Text(box.label, style = t.body, color = if (box == current) c.blue else c.label, modifier = Modifier.weight(1f))\n' +
'                                val boxN = when (box.key) { "receive" -> counts["inbox"] ?: 0; "deleted" -> counts["deleted"] ?: 0; "spam" -> counts["spam"] ?: 0; else -> 0 }\n' +
'                                if (boxN > 0) Text(boxN.toString(), style = t.caption1, color = c.label2)')

if (s === orig) { console.log('NO CHANGE'); process.exit(1) }
fs.writeFileSync(I, s)
console.log('drawer polished')
