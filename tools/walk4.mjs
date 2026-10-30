// 真机巡检 v4：用页面左上角 ‹ 返回，不再按系统返回键。
import { execFileSync } from 'node:child_process'
import { readFileSync, existsSync, unlinkSync } from 'node:fs'

const ADB = 'E:/tools/android-sdk/platform-tools/adb.exe'
const OUT = 'F:/Desktop/MFiles/otsmail-android-shots'
const TMP = 'E:/DeepSeek workers/otsmail-android/tools/y.xml'
const sh = (...a) => execFileSync(ADB, a, { encoding: 'utf8' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
let seq = 0

function nodes() {
  seq += 1
  const remote = `/sdcard/y${seq}.xml`
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
function shot(name) { sh('shell', 'screencap', '-p', '/sdcard/s.png'); sh('pull', '/sdcard/s.png', `${OUT}/${name}.png`) }

async function tap(label, name, wait = 2600) {
  const list = nodes()
  const hit = list.find(n => n.label === label) || list.find(n => n.label.includes(label))
  if (!hit) { console.log('MISS', label, '|', list.map(n => n.label).slice(0, 10).join(',')); return false }
  sh('shell', 'input', 'tap', String(hit.x), String(hit.y))
  await sleep(wait)
  if (name) { shot(name); console.log('ok  ', label, '->', name) }
  return true
}
const back = () => tap('‹', null, 1500)

await tap('A', '62-drawer')
await tap('设置', '63-settings')
await tap('系统设置', '64-syssettings')
await tap('网站设置', '65-sys-site')
await back(); await back()
await tap('全部邮件', '66-allmail')
await back()
await tap('用户管理', '67-users')
await back()
await tap('权限控制', '68-roles')
await tap('普通用户', '69-role-edit')
await back(); await back()
await tap('注册密钥', '70-regkeys')
await back()
await tap('分析', '71-analysis')
await back()
await tap('AI 与翻译', '72-sys-ai')
await back()
await back()
await tap('写邮件', '73-compose', 2400)

const err = sh('logcat', '-d', '-s', 'AndroidRuntime:E').trim()
console.log(err ? 'CRASH:\n' + err.split('\n').slice(-2).join('\n') : 'no crash')
