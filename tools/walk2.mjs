// 真机巡检 v2：每次用新文件名 dump（避免 uiautomator 复用旧快照），按 text/content-desc 找点。
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
    return { label: text || desc, x: b.length ? Math.round((b[0] + b[2]) / 2) : -1, y: b.length ? Math.round((b[1] + b[3]) / 2) : -1, clickable: /clickable="true"/.test(t) }
  }).filter(n => n.label && n.x >= 0)
}

function tapLabel(label) {
  const list = nodes()
  const hit = list.find(n => n.label === label) || list.find(n => n.label.includes(label))
  if (!hit) { console.log('miss', label, '| 可见:', list.map(n => n.label).slice(0, 10).join(',')); return false }
  sh('shell', 'input', 'tap', String(hit.x), String(hit.y))
  return true
}

function shot(name) {
  sh('shell', 'screencap', '-p', '/sdcard/s.png')
  sh('pull', '/sdcard/s.png', `${OUT}/${name}.png`)
}

const steps = [
  ['A', '62-drawer'],
  ['设置', '63-settings'],
  ['系统设置', '64-syssettings'],
  ['AI 与翻译', '65-syssettings-ai'],
  ['全部邮件', '66-admin-allmail'],
]
let ok = 0
for (const [label, name] of steps) {
  if (!tapLabel(label)) continue
  await sleep(2600)
  shot(name); ok += 1
  console.log('ok', label)
  sh('shell', 'input', 'keyevent', '4'); await sleep(800)
}
console.log(`\n进入成功 ${ok}/${steps.length}`)
const err = sh('logcat', '-d', '-s', 'AndroidRuntime:E')
const lines = err.trim().split('\n').filter(Boolean)
console.log(lines.length ? 'CRASH:\n' + lines.slice(-3).join('\n') : 'no crash')
