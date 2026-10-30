// 真机巡检 v3：不盲目按返回；每步先 dump 再按标签点，逐屏截图。
import { execFileSync } from 'node:child_process'
import { readFileSync, existsSync, unlinkSync } from 'node:fs'

const ADB = 'E:/tools/android-sdk/platform-tools/adb.exe'
const OUT = 'F:/Desktop/MFiles/otsmail-android-shots'
const TMP = 'E:/DeepSeek workers/otsmail-android/tools/x.xml'
const sh = (...a) => execFileSync(ADB, a, { encoding: 'utf8' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
let seq = 0

function nodes() {
  seq += 1
  const remote = `/sdcard/d${seq}.xml`
  sh('shell', 'rm', '-f', remote)
  sh('shell', 'uiautomator', 'dump', remote)
  if (existsSync(TMP)) unlinkSync(TMP)
  sh('pull', remote, TMP)
  const xml = readFileSync(TMP, 'utf8')
  return [...xml.matchAll(/<node[^>]*>/g)].map(m => {
    const t = m[0]
    const text = (t.match(/ text="([^"]*)"/) || [])[1] || ''
    const desc = (t.match(/ content-desc="([^"]*)"/) || [])[1] || ''
    const b = (t.match(/ bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) || []).slice(1).map(Number)
    return { label: text || desc, x: b.length ? Math.round((b[0] + b[2]) / 2) : -1, y: b.length ? Math.round((b[1] + b[3]) / 2) : -1 }
  }).filter(n => n.label && n.x >= 0)
}

function labels() { return nodes().map(n => n.label) }

async function tap(label, shotName, wait = 2500, exact = false) {
  const list = nodes()
  const hit = (exact ? list.find(n => n.label === label) : list.find(n => n.label === label) || list.find(n => n.label.includes(label)))
  if (!hit) { console.log('MISS', label, '| 可见:', list.map(n => n.label).slice(0, 12).join(' / ')); return false }
  sh('shell', 'input', 'tap', String(hit.x), String(hit.y))
  await sleep(wait)
  if (shotName) { shot(shotName); console.log('ok  ', label, '->', shotName) }
  return true
}
const back = async (wait = 1200) => { sh('shell', 'input', 'keyevent', '4'); await sleep(wait) }
function shot(name) {
  sh('shell', 'screencap', '-p', '/sdcard/s.png')
  sh('pull', '/sdcard/s.png', `${OUT}/${name}.png`)
}

let pass = 0, total = 0
const step = async (fn) => { total += 1; if (await fn()) pass += 1 }

await step(() => tap('A', '62-drawer'))            // 抽屉
await step(() => tap('设置', '63-settings'))        // 设置
await step(() => tap('系统设置', '64-syssettings'))  // 系统设置根
await step(() => tap('网站设置', '65-sys-site'))     // 一张卡
await back(); await back()                          // 回设置
await step(() => tap('全部邮件', '66-admin-allmail'))
await back()
await step(() => tap('用户管理', '67-admin-users'))
await back()
await step(() => tap('权限控制', '68-admin-roles'))
await step(() => tap('普通用户', '69-admin-role-edit'))
await back(); await back()
await step(() => tap('注册密钥', '70-admin-regkeys'))
await back()
await step(() => tap('分析', '71-admin-analysis'))
await back()
await step(() => tap('AI 助手', '72-ai'))
await back(); await back()

console.log(`\n到达 ${pass}/${total} 屏`)
const err = sh('logcat', '-d', '-s', 'AndroidRuntime:E').trim()
console.log(err ? 'CRASH:\n' + err.split('\n').slice(-3).join('\n') : 'no crash')
