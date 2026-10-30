const fs = require('fs')
const base = 'E:/DeepSeek workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/'
function patch(file, edits) {
  const p = base + file
  let s = fs.readFileSync(p, 'utf8')
  for (const [from, to] of edits) {
    if (!s.includes(from)) { console.log(file, 'MISS:', from.slice(0, 40)); continue }
    s = s.split(from).join(to)
  }
  return [p, s]
}
function addImports(s, need) {
  const miss = need.filter(i => !s.includes('import ' + i + '\n'))
  if (!miss.length) return s
  const a = s.indexOf('\npackage ')
  const b = s.indexOf('\n', a + 1)
  return s.slice(0, b + 1) + miss.map(i => 'import ' + i + '\n').join('') + s.slice(b + 1)
}

let [p1, s1] = patch('OTComponents.kt', [
  ['Text("⌕", style = OT.type.subhead, color = c.label3)', 'Icon(Icons.Outlined.Search, contentDescription = null, tint = c.label3, modifier = Modifier.size(18.dp))'],
  ['.padding(start = 8.dp, end = 4.dp),', '.padding(start = 12.dp, end = 6.dp),'],
  ['Spacer(Modifier.width(4.dp))', 'Spacer(Modifier.width(8.dp))'],
  ['heightIn(min = 86.dp)', 'heightIn(min = 78.dp)'],
])
s1 = addImports(s1, [
  'androidx.compose.material.icons.Icons',
  'androidx.compose.material.icons.outlined.Search',
  'androidx.compose.material3.Icon',
  'androidx.compose.foundation.layout.size',
])
fs.writeFileSync(p1, s1)

let [p2, s2] = patch('InboxScreen.kt', [
  ['if (index > 0) OTDivider(inset = 58)', '// 设计稿行间无分隔线，靠留白分组'],
])
fs.writeFileSync(p2, s2)
console.log('done')
