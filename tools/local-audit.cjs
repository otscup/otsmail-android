// 本地静态自查：编码损坏 / 写死颜色 / 文字字形冒充图标
const fs = require('fs')
const path = require('path')

const root = 'app/src/main/java/com/otscup/otsmail'
const files = []
;(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p)
    else if (e.name.endsWith('.kt')) files.push(p)
  }
})(root)

const problems = { encoding: [], hardColor: [], glyph: [] }
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8')
  const lines = src.split('\n')
  lines.forEach((l, i) => {
    if (l.includes('\uFFFD')) problems.encoding.push(`${f}:${i + 1} ${l.trim().slice(0, 60)}`)
    if (/Color\(0x/.test(l) && !f.includes('theme')) problems.hardColor.push(`${f}:${i + 1} ${l.trim().slice(0, 70)}`)
    // Text("★") 这类单字符字形（emoji/符号）冒充图标
    if (/Text\(\s*"[^\w\s\u4e00-\u9fa5@]{1,3}"\s*,/.test(l) && !/style = t\.(title|large)/.test(l)) {
      problems.glyph.push(`${f}:${i + 1} ${l.trim().slice(0, 70)}`)
    }
  })
}

console.log('files', files.length)
for (const [k, v] of Object.entries(problems)) {
  console.log(`\n== ${k}: ${v.length}`)
  v.slice(0, 12).forEach(x => console.log('  ' + x))
  if (v.length > 12) console.log(`  … 还有 ${v.length - 12} 条`)
}
