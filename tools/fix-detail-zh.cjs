// 修复 DetailScreen.kt 被 PowerShell Set-Content 破坏的中文（第 5 次编码事故的收尾）
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/DetailScreen.kt'
const lines = fs.readFileSync(p, 'utf8').split('\n')

// 1-based 行号 → 正确内容（多条用数组）
const fix = {
  52: ['    // 正文按实际内容高度撑开：短正文下面不再留一大片空白，翻译行紧跟正文'],
  80: ['                ) { Text(if (m.starred) "★" else "☆", style = t.title3, color = if (m.starred) c.orange else c.label2) }'],
  95: ['                Text("加载中…", style = t.subhead, color = c.label2)'],
  103: ['                    m.subject.ifEmpty { "（无主题）" },'],
  133: [
    '                                    // 用 contentHeight 而不是注入 JS：正文里 JS 是关着的（安全考虑）',
    '                                    val px = view?.contentHeight?.toFloat() ?: 0f',
  ],
  143: [
    '                        // 正文配色跟随主题，否则暗色下黑字压在深色卡上看不见',
    '                        val fg = hex(c.label)',
  ],
  172: ['                    Text("译", style = t.subhead, color = c.blue)'],
  174: ['                    Text(if (translated.isEmpty()) "翻译邮件" else "已翻译为简体中文", style = t.body, color = c.label)'],
  176: ['                    Text(if (busy) "…" else "›", style = t.body, color = c.label3)'],
  200: ['                Triple("↩", "回复", { onReply(m) }),'],
  201: ['                Triple("⇉", "回复全部", { onReply(m) }),'],
  202: ['                Triple("↪", "转发", { onReply(m) }),'],
}

// 从后往前改，避免行号位移
for (const n of Object.keys(fix).map(Number).sort((a, b) => b - a)) {
  lines.splice(n - 1, 1, ...fix[n])
}
fs.writeFileSync(p, lines.join('\n'), 'utf8')
const out = fs.readFileSync(p, 'utf8')
console.log('lines', lines.length, '| FFFD', (out.match(/\uFFFD/g) || []).length)
