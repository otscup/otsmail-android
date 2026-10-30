// 第 4 步（提前做，低风险）：把硬写间距换成 OTSpace 令牌，统一卡间距
const fs = require('fs')
const path = require('path')

const dir = 'app/src/main/java/com/otscup/otsmail/ui'
const files = fs.readdirSync(dir).filter(f => f.endsWith('.kt'))
let touched = 0, reps = 0

for (const f of files) {
  const p = path.join(dir, f)
  let s = fs.readFileSync(p, 'utf8')
  const before = s
  // OTGroup 的水平边距：16dp → OTSpace.s4
  s = s.replace(/Modifier\.padding\(horizontal = 16\.dp, vertical = 8\.dp\)/g, 'Modifier.padding(horizontal = OTSpace.s4, vertical = OTSpace.cardGap)')
  s = s.replace(/Modifier\.padding\(horizontal = 16\.dp\)/g, 'Modifier.padding(horizontal = OTSpace.s4)')
  // 常见竖向间隙令牌化
  s = s.replace(/Spacer\(Modifier\.height\(8\.dp\)\)/g, 'Spacer(Modifier.height(OTSpace.s2))')
  s = s.replace(/Spacer\(Modifier\.height\(12\.dp\)\)/g, 'Spacer(Modifier.height(OTSpace.s3))')
  s = s.replace(/Spacer\(Modifier\.height\(16\.dp\)\)/g, 'Spacer(Modifier.height(OTSpace.s4))')
  s = s.replace(/Spacer\(Modifier\.height\(20\.dp\)\)/g, 'Spacer(Modifier.height(OTSpace.s5))')
  s = s.replace(/Spacer\(Modifier\.height\(24\.dp\)\)/g, 'Spacer(Modifier.height(OTSpace.s6))')
  if (s !== before) {
    fs.writeFileSync(p, s, 'utf8')
    touched++
    reps += (before.match(/OTSpace\./g) || []).length === 0 ? 1 : 1
    console.log('updated', f)
  }
}
console.log('files touched', touched)
