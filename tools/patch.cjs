// 用法: node tools/patch.cjs <file> <old> <new>
// 只在首个匹配处替换；找不到就报错退出（避免静默未生效）。
const fs = require('fs')
const [file, oldText, newText] = process.argv.slice(2)
if (!file || oldText === undefined || newText === undefined) {
  console.error('usage: node tools/patch.cjs <file> <old> <new>')
  process.exit(2)
}
const src = fs.readFileSync(file, 'utf8')
const i = src.indexOf(oldText)
if (i < 0) {
  console.error('MISS: ' + oldText.slice(0, 60))
  process.exit(1)
}
fs.writeFileSync(file, src.slice(0, i) + newText + src.slice(i + oldText.length), 'utf8')
console.log('OK (' + src.length + ' -> ' + (src.length - oldText.length + newText.length) + ')')
