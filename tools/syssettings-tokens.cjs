// 系统设置令牌化：组标题统一 OTGroupHeader、底部间距走 OTSpace。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/SysSettingsScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const before = s
s = s.replaceAll('GroupTitle("站点")', 'OTGroupHeader("站点")')
s = s.replaceAll('GroupTitle("存储与推送")', 'OTGroupHeader("存储与推送")')
s = s.replaceAll('GroupTitle("AI")', 'OTGroupHeader("AI")')
s = s.replaceAll('Spacer(Modifier.height(d.sp6.dp))', 'Spacer(Modifier.height(OTSpace.s6))')
fs.writeFileSync(p, s, 'utf8')
console.log(before === s ? 'MISS' : 'OK', 'GroupTitle left:', (s.match(/GroupTitle\(/g) || []).length)
