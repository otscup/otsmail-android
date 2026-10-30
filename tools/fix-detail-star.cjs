// 详情页导航栏星标：字符 ★/☆ 改矢量图标（按实际源码文本替换）。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/DetailScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const old = ') { Text(if (m.starred) "★" else "☆", style = t.title3, color = if (m.starred) c.orange else c.label2) }'
if (!s.includes(old)) {
  console.log('MISS star expr')
  process.exit(1)
}
s = s.replace(old, ') { Icon(if (m.starred) Icons.Filled.Star else Icons.Outlined.StarBorder, null, tint = if (m.starred) c.orange else c.label2, modifier = Modifier.size(22.dp)) }')
const want = [
  'import androidx.compose.material.icons.filled.Star',
  'import androidx.compose.material.icons.outlined.StarBorder',
]
const added = []
for (const line of want) {
  if (!s.includes(line + '\n')) {
    const i = s.lastIndexOf('import ')
    const e = s.indexOf('\n', i) + 1
    s = s.slice(0, e) + line + '\n' + s.slice(e)
    added.push(line)
  }
}
fs.writeFileSync(p, s, 'utf8')
console.log('star -> icon ok; imports added:', added.length)
