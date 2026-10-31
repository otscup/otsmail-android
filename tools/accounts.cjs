// 多账户切换：Api.accounts() + 抽屉帐户区 + 切换后重载
const fs = require('fs')
const A = 'app/src/main/java/com/otscup/otsmail/data/Api.kt'
const I = 'app/src/main/java/com/otscup/otsmail/ui/InboxScreen.kt'

let a = fs.readFileSync(A, 'utf8')
if (!a.includes('suspend fun accounts()')) {
  const anchor = '    suspend fun emails('
  a = a.replace(anchor, `    /** 多账户：/account/list 的 data 直接是数组。 */
    suspend fun accounts(): List<Pair<Long, String>> {
        val arr = arr("GET", "/account/list")
        val out = ArrayList<Pair<Long, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("accountId")
            if (id == 0L) continue
            out.add(id to o.optString("email").ifEmpty { o.optString("name") })
        }
        return out
    }

${anchor}`)
  fs.writeFileSync(A, a)
  console.log('Api.accounts added')
} else console.log('Api.accounts exists')

let s = fs.readFileSync(I, 'utf8')
if (!s.includes('var accounts by remember')) {
  s = s.replace('    var unread by remember { mutableStateOf(0) }',
    `    var unread by remember { mutableStateOf(0) }
    var accounts by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var accVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { runCatching { Api.accounts() }.onSuccess { accounts = it } }`)
  console.log('accounts state added')
} else console.log('accounts state exists')

s = s.replace('LaunchedEffect(current.key, keyword) {', 'LaunchedEffect(current.key, keyword, accVersion) {')

const anchor2 = '                    OTGroupHeader("邮箱")'
if (!s.includes('OTGroupHeader("帐户")')) {
  s = s.replace(anchor2, `                    OTGroupHeader("帐户")
                    OTGroup {
                        accounts.forEachIndexed { i, acc ->
                            if (i > 0) OTDivider(inset = 16)
                            val active = acc.first == session.accountId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .tapArea {
                                        if (!active) {
                                            session.accountId = acc.first
                                            session.apply()
                                            accVersion++
                                            didTop = false
                                        }
                                        scope.launch { drawer.close() }
                                    }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(if (active) c.blue else c.tintBlue),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        acc.second.take(1).uppercase(),
                                        style = t.caption1,
                                        color = if (active) Color.White else c.blue,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(acc.second, style = t.body, color = if (active) c.blue else c.label, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(OTSpace.s4))
${anchor2}`)
  fs.writeFileSync(I, s)
  console.log('drawer accounts added')
} else {
  fs.writeFileSync(I, s)
  console.log('drawer accounts exists')
}
