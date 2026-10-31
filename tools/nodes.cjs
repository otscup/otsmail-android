// nodes.cjs — print every text node with bounds from tools/q.xml (already pulled)
const fs = require('fs')
const xml = fs.readFileSync('tools/q.xml', 'utf8')
const re = /text="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g
for (const m of xml.matchAll(re)) {
  const [, text, x1, y1, x2, y2] = m
  const cy = (Number(y1) + Number(y2)) / 2
  if (cy < 120 || cy > 2180) continue
  console.log(`${text}\t${Math.round((Number(x1) + Number(x2)) / 2)}\t${Math.round(cy)}`)
}
