// compose-cc-steps.cjs — 分步发一封带抄送的邮件，每步打印界面状态
const { execFileSync } = require('child_process')
const fs = require('fs')
const ADB = 'E:\\tools\\android-sdk\\platform-tools\\adb.exe'
const adb = (...a) => execFileSync(ADB, a, { encoding: 'utf8' })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const RE = /(?:text|content-desc)="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g
const PKG = 'com.otscup.otsmail'

function nodes() {
  adb('shell', 'uiautomator', 'dump', '/sdcard/q.xml')
  adb('pull', '/sdcard/q.xml', 'tools/q.xml')
  const xml = fs.readFileSync('tools/q.xml', 'utf8')
  return [...xml.matchAll(RE)]
    .map((m) => ({ text: m[1], x: (Number(m[2]) + Number(m[4])) / 2, y: (Number(m[3]) + Number(m[5])) / 2 }))
    .filter((n) => n.text && n.y > 120 && n.y < 2180)
}
function show(tag) {
  const ns = nodes()
  console.log(`-- ${tag}:`, ns.map((n) => `${n.text}@${Math.round(n.y)}`).slice(0, 14).join(' | '))
  return ns
}
async function tapNode(needle, pick = 'first', xOverride = 0) {
  const ns = show('find ' + needle)
  const hits = ns.filter((n) => n.text.includes(needle))
  const hit = pick === 'last' ? hits[hits.length - 1] : hits[0]
  if (!hit) return console.log('MISS', needle)
  const x = Math.round(xOverride || hit.x)
  adb('shell', 'input', 'tap', String(x), String(Math.round(hit.y)))
  await sleep(1200)
}

;(async () => {
  adb('shell', 'am', 'force-stop', PKG)
  adb('shell', 'am', 'start', '-n', `${PKG}/.MainActivity`)
  await sleep(9000)
  adb('shell', 'input', 'tap', '959', '2123')
  await sleep(2500)
  // 左侧 x<200 区域有手机自带的工行悬浮球，点那里会跳去工行 App，所以一律点行中间
  await tapNode('someone@example.com', 'first', 540)

  adb('shell', 'input', 'text', 'admin@example.com')
  await sleep(900)
  await tapNode('抄送')
  await tapNode('多个用逗号分隔', 'first', 540)
  adb('shell', 'input', 'text', 'ops@example.com')
  await sleep(900)
  await tapNode('主题', 'last', 540)
  adb('shell', 'input', 'text', 'cc-ui-3105')
  await sleep(900)
  show('before send')
  await tapNode('发送')
  await sleep(6000)
  show('after send')
})()
