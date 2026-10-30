const fs = require('fs')
const p = 'E:/DeepSeek workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/InboxScreen.kt'
const L = fs.readFileSync(p, 'utf8').split('\n')
let n = 0
for (let i = 0; i < L.length; i++) {
  if (L[i].includes('"updates" ->')) { L[i] = '    "updates" -> "动态"'; n++ }
  if (L[i].includes('.format(dt.monthValue')) {
    L[i] = '        else -> "%02d-%02d".format(dt.monthValue, dt.dayOfMonth)'
    n++
  }
}
fs.writeFileSync(p, L.join('\n'))
console.log('fixed', n)
