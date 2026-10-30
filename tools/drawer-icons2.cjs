const fs = require('fs')
const p = 'E:/DeepSeek workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/InboxScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const oldLine = 'Text(label, style = t.body, color = c.label)'
if (!s.includes(oldLine)) { console.log('anchor missing'); process.exit(0) }
s = s.replace(oldLine, [
  'Icon(otherIcon(label), contentDescription = null, tint = c.label2, modifier = Modifier.size(22.dp))',
  '                                Spacer(Modifier.width(12.dp))',
  oldLine,
].join('\n'))
if (!s.includes('private fun otherIcon')) {
  s += [
    '',
    'private fun otherIcon(label: String) = when {',
    '    label.contains("联系人") -> Icons.Outlined.People',
    '    label.contains("AI") -> Icons.Outlined.AutoAwesome',
    '    label.contains("系统") -> Icons.Outlined.Tune',
    '    else -> Icons.Outlined.Settings',
    '}',
    '',
  ].join('\n')
}
const need = [
  'androidx.compose.material.icons.outlined.People',
  'androidx.compose.material.icons.outlined.AutoAwesome',
  'androidx.compose.material.icons.outlined.Tune',
  'androidx.compose.material.icons.outlined.Settings',
]
const miss = need.filter(i => !s.includes('import ' + i + '\n'))
if (miss.length) {
  const a = s.indexOf('\npackage ')
  const b = s.indexOf('\n', a + 1)
  s = s.slice(0, b + 1) + miss.map(i => 'import ' + i + '\n').join('') + s.slice(b + 1)
}
fs.writeFileSync(p, s)
console.log('ok imports', miss.length)
