// 解析 uiautomator dump，打印 text/desc 与中心坐标
import { readFileSync } from 'node:fs'
const xml = readFileSync(process.argv[2], 'utf8')
const re = /<node([^>]*)>/g
let m, out = []
while ((m = re.exec(xml))) {
  const a = m[1]
  const text = (a.match(/text="([^"]*)"/) || [, ''])[1]
  const desc = (a.match(/content-desc="([^"]*)"/) || [, ''])[1]
  const b = (a.match(/bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/) || [])
  if (!text && !desc) continue
  if (b.length < 5) continue
  const cx = (Number(b[1]) + Number(b[3])) / 2, cy = (Number(b[2]) + Number(b[4])) / 2
  out.push(`${JSON.stringify(text || desc)}  x=${Math.round(cx)} y=${Math.round(cy)}  [${b[1]},${b[2]}][${b[3]},${b[4]}]`)
}
console.log(out.slice(0, 40).join('\n'))
