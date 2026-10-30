// 真机巡检：按文字找节点 → 点 → 截图 → 返回。用法：node tools/walk.mjs
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

const ADB = 'E:/tools/android-sdk/platform-tools/adb.exe'
const OUT = 'F:/Desktop/MFiles/otsmail-android-shots'
const sh = (...a) => execFileSync(ADB, a, { encoding: 'utf8' })
const sleep = ms => new Promise(r => setTimeout(r, ms))

function dump() {
  sh('shell', 'uiautomator', 'dump', '/sdcard/w.xml')
  sh('pull', '/sdcard/w.xml', 'E:/DeepSeek workers/otsmail-android/tools/w.xml')
  return readFileSync('E:/DeepSeek workers/otsmail-android/tools/w.xml', 'utf8')
}
function center(xml, text) {
  const re = new RegExp('<node[^>]*text="' + text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"')
  const m = xml.match(re)
  if (!m) return null
  return [Math.round((+m[1] + +m[3]) / 2), Math.round((+m[2] + +m[4]) / 2)]
}
function shot(name) {
  sh('shell', 'screencap', '-p', '/sdcard/s.png')
  sh('pull', '/sdcard/s.png', `${OUT}/${name}.png`)
  console.log('shot', name)
}

const steps = process.argv.slice(2)
await sleep(500)
// 首屏
shot('60-walk-inbox')
const xml0 = dump()
console.log('首屏文字:', (xml0.match(/text="([^"]{2,14})"/g) || []).slice(0, 12).join(' '))

const flow = steps.length ? steps : ['设置', '系统设置', '全部邮件', '用户管理', '权限控制', '注册密钥', '分析']
for (const label of flow) {
  const xml = dump()
  const pt = center(xml, label)
  if (!pt) { console.log('miss', label); continue }
  sh('shell', 'input', 'tap', String(pt[0]), String(pt[1]))
  await sleep(2600)
  shot('61-' + label)
  sh('shell', 'input', 'keyevent', '4')
  await sleep(900)
}

const log = sh('logcat', '-d', '-s', 'AndroidRuntime:E')
console.log(log.trim().split('\n').slice(-4).join('\n') || 'no crash')
