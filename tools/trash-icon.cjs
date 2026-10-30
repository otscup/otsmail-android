// 补 actionIcon 映射：🗑 走矢量删除图标（node -e 带 -> 会报 bad option，必须用脚本文件）
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/OTMore.kt'
let s = fs.readFileSync(p, 'utf8')
const anchor = '"✕" -> Icons.Outlined.Delete'
if (!s.includes(anchor)) { console.log('MISS: anchor'); process.exit(1) }
if (s.includes('"🗑" ->')) { console.log('SKIP: already mapped'); process.exit(0) }
s = s.replace(anchor, anchor + '\n    "🗑" -> Icons.Outlined.Delete')
fs.writeFileSync(p, s)
console.log('OK: mapped 🗑')
