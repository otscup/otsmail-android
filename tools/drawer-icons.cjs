const fs = require('fs')
const p = 'E:/DeepSeek workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/InboxScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const oldLine = 'Text(box.label, style = t.body, color = if (box == current) c.blue else c.label)'
const newLines = [
  'Icon(boxIcon(box.label), contentDescription = null, tint = if (box == current) c.blue else c.label2, modifier = Modifier.size(22.dp))',
  '                                Spacer(Modifier.width(12.dp))',
  oldLine,
].join('\n')
if (!s.includes(oldLine)) { console.log('anchor missing'); process.exit(0) }
s = s.replace(oldLine, newLines)
if (!s.includes('private fun boxIcon')) {
  s += [
    '',
    'private fun boxIcon(label: String) = when {',
    '    label.startsWith("收件") -> Icons.Outlined.Inbox',
    '    label.startsWith("未读") -> Icons.Outlined.MarkEmailUnread',
    '    label.contains("星") -> Icons.Outlined.StarBorder',
    '    label.contains("删除") -> Icons.Outlined.Delete',
    '    label.contains("垃圾") -> Icons.Outlined.Report',
    '    label.contains("草稿") -> Icons.Outlined.Drafts',
    '    else -> Icons.Outlined.Mail',
    '}',
    '',
  ].join('\n')
}
const need = [
  'androidx.compose.material.icons.Icons',
  'androidx.compose.material3.Icon',
  'androidx.compose.material.icons.outlined.Inbox',
  'androidx.compose.material.icons.outlined.MarkEmailUnread',
  'androidx.compose.material.icons.outlined.StarBorder',
  'androidx.compose.material.icons.outlined.Delete',
  'androidx.compose.material.icons.outlined.Report',
  'androidx.compose.material.icons.outlined.Drafts',
  'androidx.compose.material.icons.outlined.Mail',
  'androidx.compose.foundation.layout.width',
]
const miss = need.filter(i => !s.includes('import ' + i + '\n'))
if (miss.length) {
  const a = s.indexOf('\npackage ')
  const b = s.indexOf('\n', a + 1)
  s = s.slice(0, b + 1) + miss.map(i => 'import ' + i + '\n').join('') + s.slice(b + 1)
}
fs.writeFileSync(p, s)
console.log('ok imports', miss.length)
