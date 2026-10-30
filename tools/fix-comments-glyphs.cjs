// 修 PressFeedback.kt 的 GBK 乱码注释；详情页星标、草稿箱空状态改用矢量图标。
const fs = require('fs')

// 1) PressFeedback.kt：按行号整体替换注释块与行内注释
{
  const p = 'app/src/main/java/com/otscup/otsmail/ui/PressFeedback.kt'
  const lines = fs.readFileSync(p, 'utf8').split('\n')
  lines[19] = '/**'
  lines[20] = ' * iOS 式按下反馈：按下瞬间整块微缩 + 压深，抬起才提交。'
  lines[21] = ' * 与 Material 涟漪的区别：涟漪是「水面波纹」，按下反馈是「这块东西被你按进去了」。'
  lines[22] = ' * 关掉 indication（涟漪）是关键，否则两层会叠在一起。'
  lines[23] = ' *'
  lines[24] = ' * @param pressedColor 按下时叠的颜色，深色通常是白色低透明度'
  lines.splice(25, 0,
    ' * @param shape 有圆角的可点区域必须传，否则压色会盖住圆角',
    ' */',
    '/**',
    ' * 导航栏按钮（返回 / 保存 / 关闭）用这个：不做缩放，命中区不随动画变化。',
    ' * 缩放会把按下瞬间的命中矩形往里收，34dp 的边缘点会被吃掉。',
    ' */')
  const idx = lines.findIndex(l => l.includes('val t by animateFloatAsState'))
  if (idx > 0) lines[idx - 1] = '    // 90ms：短到感觉是瞬时的，长到肉眼能看见过渡；不能用 spring，按下态要立刻到位'
  fs.writeFileSync(p, lines.join('\n'), 'utf8')
  console.log('PressFeedback ok')
}

// 2) DetailScreen.kt：导航栏星标用图标
{
  const p = 'app/src/main/java/com/otscup/otsmail/ui/DetailScreen.kt'
  let s = fs.readFileSync(p, 'utf8')
  const old = ') { Text(if (m.starred) "★" else "☆", style = t.title3, color = if (m.starred) c.orange else c.label3) }'
  if (!s.includes(old)) {
    console.log('MISS star line')
  } else {
    s = s.replace(old, ') { Icon(if (m.starred) Icons.Filled.Star else Icons.Outlined.StarBorder, null, tint = if (m.starred) c.orange else c.label3, modifier = Modifier.size(22.dp)) }')
    if (!s.includes('import androidx.compose.material.icons.outlined.StarBorder')) {
      const i = s.lastIndexOf('import ')
      const e = s.indexOf('\n', i) + 1
      s = s.slice(0, e) + 'import androidx.compose.material.icons.outlined.StarBorder\nimport androidx.compose.material.icons.filled.Star\n' + s.slice(e)
    }
    fs.writeFileSync(p, s, 'utf8')
    console.log('Detail star ok')
  }
}

// 3) DraftsScreen.kt：空状态用图标
{
  const p = 'app/src/main/java/com/otscup/otsmail/ui/DraftsScreen.kt'
  let s = fs.readFileSync(p, 'utf8')
  const old = 'Text("✎", style = t.largeTitle, color = c.label3)'
  if (!s.includes(old)) {
    console.log('MISS draft glyph')
  } else {
    s = s.replace(old, 'Icon(Icons.Outlined.Edit, null, tint = c.label3, modifier = Modifier.size(48.dp))')
    if (!s.includes('import androidx.compose.material.icons.outlined.Edit')) {
      const i = s.lastIndexOf('import ')
      const e = s.indexOf('\n', i) + 1
      s = s.slice(0, e) + 'import androidx.compose.material.icons.outlined.Edit\n' + s.slice(e)
    }
    fs.writeFileSync(p, s, 'utf8')
    console.log('Drafts glyph ok')
  }
}
