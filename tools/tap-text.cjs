// tap-text.cjs <text> [swipeRetries] — open drawer, find node by text, tap it
const { execFileSync } = require('child_process')
const ADB = 'E:\\tools\\android-sdk\\platform-tools\\adb.exe'
const adb = (...a) => execFileSync(ADB, a, { encoding: 'utf8' })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function dump() {
  adb('shell', 'uiautomator', 'dump', '/sdcard/q.xml')
  adb('pull', '/sdcard/q.xml', 'tools/q.xml')
  const xml = require('fs').readFileSync('tools/q.xml', 'utf8')
  return [...xml.matchAll(/text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g)]
    .map((m) => ({ text: m[1], x: (Number(m[2]) + Number(m[4])) / 2, y: (Number(m[3]) + Number(m[5])) / 2 }))
    .filter((n) => n.text && n.y > 120 && n.y < 2180)
}

;(async () => {
  const needle = process.argv[2]
  for (let i = 0; i < 4; i++) {
    const hit = (await dump()).find((n) => n.text.includes(needle))
    if (hit) { adb('shell', 'input', 'tap', String(Math.round(hit.x)), String(Math.round(hit.y))); console.log('tap', needle, Math.round(hit.x), Math.round(hit.y)); return }
    adb('shell', 'input', 'swipe', '540', '1500', '540', '1000')
    await sleep(700)
  }
  console.log('MISS', needle)
})()
