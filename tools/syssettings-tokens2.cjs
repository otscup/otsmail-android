// 系统设置：把间距类硬写 dp 收进 OTSpace（开关几何 51/31/27/2 属组件尺寸，保留）。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/SysSettingsScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const before = s
s = s.replaceAll('Modifier.height(44.dp)', 'Modifier.height(OTSpace.s8)')
s = s.replaceAll('padding(horizontal = 8.dp)', 'padding(horizontal = OTSpace.s2)')
s = s.replaceAll('padding(horizontal = d.sp4.dp, vertical = d.sp3.dp)', 'padding(horizontal = OTSpace.s4, vertical = OTSpace.s3)')
s = s.replaceAll('padding(horizontal = d.sp4.dp, vertical = d.sp2.dp)', 'padding(horizontal = OTSpace.s4, vertical = OTSpace.s2)')
fs.writeFileSync(p, s, 'utf8')
console.log(before === s ? 'MISS' : 'OK')
