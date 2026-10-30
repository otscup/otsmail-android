const fs = require('fs')
const p = 'E:/DeepSeek workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/ComposeScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const oldLine = 'Text("📎", style = t.subhead, color = c.label2)'
if (!s.includes(oldLine)) { console.log('anchor missing'); process.exit(0) }
s = s.replace(oldLine, 'Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = c.label2, modifier = Modifier.size(20.dp))')
const need = [
  'androidx.compose.material.icons.Icons',
  'androidx.compose.material.icons.outlined.AttachFile',
  'androidx.compose.material3.Icon',
  'androidx.compose.foundation.layout.size',
]
const miss = need.filter(i => !s.includes('import ' + i + '\n'))
if (miss.length) {
  const a = s.indexOf('\npackage ')
  const b = s.indexOf('\n', a + 1)
  s = s.slice(0, b + 1) + miss.map(i => 'import ' + i + '\n').join('') + s.slice(b + 1)
}
fs.writeFileSync(p, s)
console.log('ok imports', miss.length)
