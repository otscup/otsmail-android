// nodes.cjs — print every text / content-desc node with center coords from tools/q.xml
const fs = require('fs')
const xml = fs.readFileSync('tools/q.xml', 'utf8')
const parts = [/text="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g,
  /content-desc="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g]
for (const re of parts) {
  for (const m of xml.matchAll(re)) {
    const [, text, x1, y1, x2, y2] = m
    const cy = (Number(y1) + Number(y2)) / 2
    if (cy < 120 || cy > 2180) continue
    console.log(`${text}\t${Math.round((Number(x1) + Number(x2)) / 2)}\t${Math.round(cy)}`)
  }
}
