// 读信正文：一律走 Compose Text（WebView 在真机上有 CSS px 与视口宽度不匹配的右裁问题）。
// 规则简单可靠：先保留段落换行，再剥标签、解实体。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/DetailScreen.kt'
let s = fs.readFileSync(p, 'utf8')

const cond = 'if (!Regex("<[a-zA-Z/]").containsMatchIn(translated.ifEmpty { m.content })) {'
if (!s.includes(cond)) { console.log('MISS condition'); process.exit(1) }
s = s.replace(cond, 'if (true) { // 正文统一走 Text：WebView 在真机上会因 CSS px 与视口不匹配而右侧被裁')

const txt = 'text = translated.ifEmpty { m.content }.trim(),'
if (!s.includes(txt)) { console.log('MISS text arg'); process.exit(1) }
s = s.replace(txt, 'text = htmlToText(translated.ifEmpty { m.content }),')

if (!s.includes('fun htmlToText(')) {
  s += `
/** 把邮件 HTML 拍平成纯文本：保留段落与换行，丢掉标签与样式。 */
private fun htmlToText(html: String): String {
    var t = html
        .replace(Regex("(?i)<br\\\\s*/?>"), "\\n")
        .replace(Regex("(?i)</p>"), "\\n\\n")
        .replace(Regex("(?i)</div>"), "\\n")
        .replace(Regex("(?i)<li[^>]*>"), "\\n• ")
        .replace(Regex("(?is)<[^>]+>"), "")
    val pairs = listOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\\"", "&#39;" to "'")
    for ((a, b) in pairs) t = t.replace(a, b)
    return t.replace(Regex("\\n{3,}"), "\\n\\n").trim()
}
`
}
fs.writeFileSync(p, s, 'utf8')
console.log('detail body -> Text, htmlToText appended')
