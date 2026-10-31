// 抽屉「分类」组：/email/list 已支持 category 参数（email-service.js:30,64），只需客户端接线
const fs = require('fs')
const A = 'app/src/main/java/com/otscup/otsmail/data/Api.kt'
const I = 'app/src/main/java/com/otscup/otsmail/ui/InboxScreen.kt'

let a = fs.readFileSync(A, 'utf8')
if (!a.includes('category: String = ""')) {
  a = a.replace('suspend fun emails(accountId: Long, page: Int, mailbox: String? = null, keyword: String = ""): Pair<List<MailItem>, Long> {',
    'suspend fun emails(accountId: Long, page: Int, mailbox: String? = null, keyword: String = "", category: String = ""): Pair<List<MailItem>, Long> {')
  a = a.replace('/email/list?size=20&page=$page&allReceive=0&timeSort=1&type=0"',
    '/email/list?size=20&page=$page&allReceive=0&timeSort=1&type=0" + if (category.isBlank()) "" else "&category=" + category')
  fs.writeFileSync(A, a)
  console.log('Api.emails category added')
} else console.log('Api category exists')

let s = fs.readFileSync(I, 'utf8')
if (!s.includes('var category by remember')) {
  s = s.replace('    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }',
    '    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }\n' +
    '    var category by remember { mutableStateOf("") }\n' +
    '    var catCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }')
  s = s.replace('    LaunchedEffect(accVersion) { runCatching { Api.menuCounts() }.onSuccess { counts = it } }',
    '    LaunchedEffect(accVersion) { runCatching { Api.menuCounts() }.onSuccess { counts = it } }\n' +
    '    LaunchedEffect(accVersion) { runCatching { Api.categoryCounts() }.onSuccess { catCounts = it } }')
  s = s.replace('else Api.emails(session.accountId, next, current.key, keyword)',
    'else Api.emails(session.accountId, next, current.key, keyword, category)')
  s = s.replace('LaunchedEffect(current.key, keyword, accVersion) {', 'LaunchedEffect(current.key, keyword, accVersion, category) {')
  fs.writeFileSync(I, s)
  console.log('category state added')
} else console.log('category state exists')

// 抽屉「分类」组：放在「邮箱」组之后
const anchor = '                    Spacer(Modifier.height(OTSpace.s4))\n                    OTGroupHeader("其他")'
if (!s.includes('OTGroupHeader("分类")')) {
  s = s.replace(anchor,
    '                    Spacer(Modifier.height(OTSpace.s4))\n' +
    '                    OTGroupHeader("分类")\n' +
    '                    OTGroup {\n' +
    '                        val cats = listOf("primary" to "主要", "social" to "社交", "promotion" to "推广", "updates" to "动态", "ads" to "广告")\n' +
    '                        cats.forEachIndexed { i, (key, label) ->\n' +
    '                            if (i > 0) OTDivider(inset = 16)\n' +
    '                            val on = category == key\n' +
    '                            Row(\n' +
    '                                Modifier\n' +
    '                                    .fillMaxWidth()\n' +
    '                                    .height(44.dp)\n' +
    '                                    .background(if (on) c.bgPressed else Color.Transparent)\n' +
    '                                    .tapArea {\n' +
    '                                        category = if (on) "" else key\n' +
    '                                        current = mailboxes[0]\n' +
    '                                        scope.launch { drawer.close() }\n' +
    '                                    }\n' +
    '                                    .padding(horizontal = 16.dp),\n' +
    '                                verticalAlignment = Alignment.CenterVertically,\n' +
    '                            ) {\n' +
    '                                Text(label, style = t.body, color = if (on) c.blue else c.label, modifier = Modifier.weight(1f))\n' +
    '                                val n = catCounts[key] ?: 0\n' +
    '                                if (n > 0) Text(n.toString(), style = t.caption1, color = c.label2)\n' +
    '                            }\n' +
    '                        }\n' +
    '                    }\n' +
    anchor)
  fs.writeFileSync(I, s)
  console.log('category group added')
} else {
  fs.writeFileSync(I, s)
  console.log('category group exists')
}
