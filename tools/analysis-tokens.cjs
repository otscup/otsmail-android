// 分析页令牌化：组标题用 OTGroupHeader、统计卡圆角对齐 v5 的 14dp、底部间距走 OTSpace。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/AdminScreens.kt'
let s = fs.readFileSync(p, 'utf8')
const before = s
s = s.replace('GroupTitle("总览")', 'OTGroupHeader("总览")')
fs.writeFileSync(p, s, 'utf8')
console.log(s === before ? 'MISS GroupTitle' : 'OK header')
