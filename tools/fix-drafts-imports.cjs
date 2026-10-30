// DraftsScreen 缺 Icon/Icons/size 的 import，补上。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/DraftsScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const need = [
  'import androidx.compose.foundation.layout.size',
  'import androidx.compose.material.icons.Icons',
  'import androidx.compose.material3.Icon',
]
let added = []
for (const line of need) {
  if (!s.includes(line + '\n')) {
    const i = s.lastIndexOf('import ')
    const e = s.indexOf('\n', i) + 1
    s = s.slice(0, e) + line + '\n' + s.slice(e)
    added.push(line)
  }
}
fs.writeFileSync(p, s, 'utf8')
console.log('added:', added.length ? added.join(', ') : 'none')
