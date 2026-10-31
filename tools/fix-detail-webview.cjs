// 读信页正文右侧被裁：关掉 wide viewport / overview 缩放，并固定文字缩放 100%。
const fs = require('fs')
const p = 'app/src/main/java/com/otscup/otsmail/ui/DetailScreen.kt'
let s = fs.readFileSync(p, 'utf8')
const anchor = 'settings.javaScriptEnabled = false'
if (!s.includes(anchor)) { console.log('MISS anchor'); process.exit(1) }
const add = [
  'settings.javaScriptEnabled = false',
  'settings.useWideViewPort = false',
  'settings.loadWithOverviewMode = false',
  'settings.setSupportZoom(false)',
  'settings.builtInZoomControls = false',
  'settings.textZoom = 100',
].join('\n                            ')
s = s.replace(anchor, add)
fs.writeFileSync(p, s, 'utf8')
console.log('webview settings patched')
