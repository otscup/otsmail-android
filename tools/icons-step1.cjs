const fs = require('fs')
const dir = 'E:/DeepSeek workers/otsmail-android/app/src/main/java/com/otscup/otsmail/ui/'

function addImports(file, imports) {
  let s = fs.readFileSync(dir + file, 'utf8')
  const missing = imports.filter(i => !s.includes('import ' + i))
  if (!missing.length) return 0
  const anchor = s.lastIndexOf('\nimport ')
  const end = s.indexOf('\n', anchor + 1)
  s = s.slice(0, end + 1) + missing.map(i => 'import ' + i + '\n').join('') + s.slice(end + 1)
  fs.writeFileSync(dir + file, s)
  return missing.length
}

// 1) InboxScreen：三个字形 → 矢量图标
let inbox = fs.readFileSync(dir + 'InboxScreen.kt', 'utf8')
const before = inbox
inbox = inbox.replace(
  'Text("✦", style = t.caption1, color = Color.White)',
  'Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))'
)
inbox = inbox.replace(
  'Text("✦", style = t.largeTitle, color = c.label3)',
  'Icon(Icons.Outlined.Inbox, contentDescription = null, tint = c.label3, modifier = Modifier.size(48.dp))'
)
inbox = inbox.replace(
  'Text("✎", style = t.title3, color = Color.White)',
  'Icon(Icons.Outlined.Edit, contentDescription = "写邮件", tint = Color.White, modifier = Modifier.size(24.dp))'
)
if (inbox !== before) fs.writeFileSync(dir + 'InboxScreen.kt', inbox)
console.log('inbox glyphs replaced:', (before.match(/[✦✎]/g) || []).length)

const a = addImports('InboxScreen.kt', [
  'androidx.compose.material.icons.Icons',
  'androidx.compose.material.icons.outlined.AutoAwesome',
  'androidx.compose.material.icons.outlined.Edit',
  'androidx.compose.material.icons.outlined.Inbox',
  'androidx.compose.material3.Icon',
])

// 2) OTNavBar：默认的「‹」换成矢量返回箭头，其它自定义文字（如「取消」）保持原样
let comp = fs.readFileSync(dir + 'OTComponents.kt', 'utf8')
const compBefore = comp
comp = comp.replace(
  'contentAlignment = Alignment.Center,\n            ) { Text(leadingText, style = t.title2, color = c.blue) }',
  'contentAlignment = Alignment.Center,\n            ) {\n                if (leadingText == "‹") {\n                    Icon(\n                        Icons.AutoMirrored.Outlined.KeyboardArrowLeft,\n                        contentDescription = "返回",\n                        tint = c.blue,\n                        modifier = Modifier.size(26.dp),\n                    )\n                } else {\n                    Text(leadingText, style = t.body, color = c.blue)\n                }\n            }'
)
if (comp !== compBefore) fs.writeFileSync(dir + 'OTComponents.kt', comp)
console.log('navbar arrow replaced:', comp !== compBefore)

const b = addImports('OTComponents.kt', [
  'androidx.compose.material.icons.Icons',
  'androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft',
  'androidx.compose.material3.Icon',
])

console.log('imports added:', a, b)
