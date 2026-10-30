// 联系人 / AI 两屏：间距类硬写 dp 收进 OTSpace，联系人行高与其它屏对齐。
const fs = require('fs')
const edits = [
  ['app/src/main/java/com/otscup/otsmail/ui/ContactsScreen.kt', [
    ['.padding(horizontal = 16.dp, vertical = 8.dp)', '.padding(horizontal = OTSpace.s4, vertical = OTSpace.s2)'],
    ['horizontalArrangement = Arrangement.spacedBy(12.dp)', 'horizontalArrangement = Arrangement.spacedBy(OTSpace.s3)'],
    ['Modifier.padding(horizontal = 8.dp)', 'Modifier.padding(horizontal = OTSpace.s2)'],
  ]],
  ['app/src/main/java/com/otscup/otsmail/ui/AiScreen.kt', [
    ['Modifier.height(44.dp).padding(horizontal = 8.dp)', 'Modifier.height(OTSpace.s8).padding(horizontal = OTSpace.s2)'],
    ['.padding(horizontal = d.sp3.dp, vertical = 6.dp)', '.padding(horizontal = OTSpace.s3, vertical = OTSpace.s2)'],
    ['.padding(top = 4.dp)', '.padding(top = OTSpace.s1)'],
  ]],
]
for (const [p, list] of edits) {
  let s = fs.readFileSync(p, 'utf8')
  let n = 0
  for (const [a, b] of list) {
    if (s.includes(a)) { s = s.split(a).join(b); n++ }
  }
  fs.writeFileSync(p, s, 'utf8')
  console.log(p.split('/').pop(), 'replaced', n, '/', list.length)
}
