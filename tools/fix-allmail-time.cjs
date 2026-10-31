// 全部邮件页：时间走 clockOf（兼容毫秒时间戳），表头文案改「已加载」。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/AdminScreens.kt'
let s = fs.readFileSync(p, 'utf8')
const pairs = [
  ['subtitle = "${m.from} · ${m.time.takeLast(5)}",', 'subtitle = "${m.from} · ${clockOf(m.time)}",'],
  ['"共 $total 封 · 前 ${items.size} 封",', '"共 $total 封 · 已加载 ${items.size} 封",'],
]
for (const [a, b] of pairs) {
  if (s.includes(a)) {
    s = s.replace(a, b)
    console.log('OK', a.slice(0, 28))
  } else {
    console.log('MISS', a.slice(0, 28))
  }
}
fs.writeFileSync(p, s, 'utf8')
