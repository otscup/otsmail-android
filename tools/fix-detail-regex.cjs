// 修回被 PowerShell 吃掉的双引号：正则字面量。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/DetailScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const bad = '!Regex(<[a-zA-Z/]).containsMatchIn'
const good = '!Regex("<[a-zA-Z/]").containsMatchIn'
if (!s.includes(bad)) { console.log('MISS bad regex'); process.exit(1) }
s = s.replace(bad, good)
fs.writeFileSync(p, s, 'utf8')
console.log('regex quotes restored')
