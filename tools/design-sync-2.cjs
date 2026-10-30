const fs = require('fs')
const p = 'E:/DeepSeek workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/InboxScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const pairs = [
  ['                .size(36.dp)\n                .clip(CircleShape)', '                .size(44.dp)\n                .clip(CircleShape)'],
  ['Text(session.name.take(1).uppercase(), style = t.subhead, color = Color.White)', 'Text(session.name.take(1).uppercase(), style = t.body, color = Color.White)'],
]
for (const [a, b] of pairs) {
  if (!s.includes(a)) { console.log('MISS', a.slice(0, 40)); continue }
  s = s.replace(a, b)
}
fs.writeFileSync(p, s)
console.log('ok')
