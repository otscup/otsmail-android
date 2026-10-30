// 把最后的「文字字形冒充图标」换成矢量图标（本地可验证，无需真机）
const fs = require('fs')

const P = 'app/src/main/java/com/otscup/otsmail/ui/'
const jobs = [
  {
    file: 'AdminScreens.kt',
    imports: ['androidx.compose.material.icons.Icons', 'androidx.compose.material.icons.outlined.ChevronRight',
      'androidx.compose.material.icons.outlined.Check', 'androidx.compose.material3.Icon',
      'androidx.compose.foundation.layout.size'],
    reps: [
      ['Text("›", style = t.body, color = c.label3)',
        'Icon(Icons.Outlined.ChevronRight, null, tint = c.label3, modifier = Modifier.size(20.dp))'],
      ['if (on) Text("✓", style = t.body, color = c.blue)',
        'if (on) Icon(Icons.Outlined.Check, null, tint = c.blue, modifier = Modifier.size(20.dp))'],
    ],
  },
  {
    file: 'AiScreen.kt',
    imports: ['androidx.compose.material.icons.Icons', 'androidx.compose.material.icons.outlined.ArrowUpward',
      'androidx.compose.material3.Icon'],
    reps: [
      ['Text("↑", style = t.body, color = androidx.compose.ui.graphics.Color.White)',
        'Icon(Icons.Outlined.ArrowUpward, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))'],
    ],
  },
  {
    file: 'DetailScreen.kt',
    imports: ['androidx.compose.material.icons.Icons', 'androidx.compose.material.icons.outlined.Delete',
      'androidx.compose.material.icons.outlined.AttachFile', 'androidx.compose.material3.Icon'],
    reps: [
      ['Text("🗑", style = t.subhead, color = c.label2)',
        'Icon(Icons.Outlined.Delete, null, tint = c.label2, modifier = Modifier.size(20.dp))'],
      ['Text("📎", style = t.subhead, color = c.label2)',
        'Icon(Icons.Outlined.AttachFile, null, tint = c.label2, modifier = Modifier.size(18.dp))'],
    ],
  },
  {
    file: 'SettingsScreen.kt',
    imports: ['androidx.compose.material.icons.Icons', 'androidx.compose.material.icons.outlined.Check',
      'androidx.compose.material3.Icon'],
    reps: [
      ['if (sendOnEnter == mode.first) Text("✓", style = t.body, color = c.blue)',
        'if (sendOnEnter == mode.first) Icon(Icons.Outlined.Check, null, tint = c.blue, modifier = Modifier.size(20.dp))'],
    ],
  },
]

for (const job of jobs) {
  const p = P + job.file
  let s = fs.readFileSync(p, 'utf8')
  let changed = 0
  for (const [from, to] of job.reps) {
    if (!s.includes(from)) { console.log('MISS', job.file, from.slice(0, 30)); continue }
    s = s.replace(from, to)
    changed++
  }
  for (const imp of job.imports) {
    const line = 'import ' + imp + '\n'
    if (!s.includes(line)) {
      const idx = s.lastIndexOf('import ')
      const end = s.indexOf('\n', idx) + 1
      s = s.slice(0, end) + line + s.slice(end)
    }
  }
  fs.writeFileSync(p, s, 'utf8')
  console.log(job.file, 'replaced', changed)
}
