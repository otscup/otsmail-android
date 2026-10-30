const fs = require('fs')
const x = fs.readFileSync('E:/DeepSeek workers/otsmail-android/tools/r.xml', 'utf8')
const m = x.match(/text="web-walk[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)
if (!m) { console.log('0 0') } else {
  console.log(Math.round((+m[1] + +m[3]) / 2), Math.round((+m[2] + +m[4]) / 2))
}
