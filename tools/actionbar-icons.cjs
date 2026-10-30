const fs = require('fs')
const p = 'E:/DeepSeek workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/OTMore.kt'
let s = fs.readFileSync(p, 'utf8')
const oldLine = '                Text(item.first, style = t.title3, color = if (i == dangerIndex) c.red else c.blue)'
if (!s.includes(oldLine)) { console.log('anchor missing'); process.exit(0) }
s = s.replace(oldLine, [
  '                val glyph = actionIcon(item.first)',
  '                if (glyph != null) {',
  '                    Icon(glyph, contentDescription = item.second, tint = if (i == dangerIndex) c.red else c.blue, modifier = Modifier.size(22.dp))',
  '                } else {',
  '                    Text(item.first, style = t.title3, color = if (i == dangerIndex) c.red else c.blue)',
  '                }',
].join('\n'))
if (!s.includes('private fun actionIcon')) {
  s += [
    '',
    'private fun actionIcon(glyph: String) = when (glyph) {',
    '    "↩" -> Icons.Outlined.Reply',
    '    "⇉" -> Icons.Outlined.ReplyAll',
    '    "↪" -> Icons.Outlined.Forward',
    '    "✕" -> Icons.Outlined.Delete',
    '    else -> null',
    '}',
    '',
  ].join('\n')
}
const need = [
  'androidx.compose.material.icons.outlined.Reply',
  'androidx.compose.material.icons.outlined.ReplyAll',
  'androidx.compose.material.icons.outlined.Forward',
  'androidx.compose.material.icons.outlined.Delete',
  'androidx.compose.foundation.layout.size',
]
const miss = need.filter(i => !s.includes('import ' + i + '\n'))
if (miss.length) {
  const a = s.indexOf('\npackage ')
  const b = s.indexOf('\n', a + 1)
  s = s.slice(0, b + 1) + miss.map(i => 'import ' + i + '\n').join('') + s.slice(b + 1)
}
fs.writeFileSync(p, s)
console.log('ok imports', miss.length)
