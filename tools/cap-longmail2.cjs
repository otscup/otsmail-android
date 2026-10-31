// 全部邮件 → 加载更多 → 找「排版检查」→ 打开并截图长正文/宽表格
const { execSync } = require('node:child_process');
const fs = require('node:fs');
const ADB = 'E:\\tools\\android-sdk\\platform-tools\\adb.exe';
const OUT = 'F:\\Desktop\\MFiles\\otsmail-android-shots\\acc';
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const sh = (cmd) => execSync(`"${ADB}" ${cmd}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
function nodes() {
  sh('shell uiautomator dump /sdcard/_cap.xml');
  sh('pull /sdcard/_cap.xml "E:\\Temp\\_cap.xml"');
  const xml = fs.readFileSync('E:\\Temp\\_cap.xml', 'utf8');
  const re = /(?:text|content-desc)="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
  const out = []; let m;
  while ((m = re.exec(xml))) if (m[1]) out.push({ text: m[1], x: Math.round((+m[2] + +m[4]) / 2), y: Math.round((+m[3] + +m[5]) / 2) });
  return out;
}
const tap = (x, y, w = 1600) => { sh(`shell input tap ${x} ${y}`); sleep(w); };
function find(needle) { return nodes().find((n) => n.text.includes(needle) && n.y > 120 && n.y < 2180); }
const shot = (name) => {
  sh('shell screencap -p /sdcard/_cap.png');
  sh(`pull /sdcard/_cap.png "${OUT}\\${name}.png"`);
  console.log(`shot ${name} ${fs.statSync(`${OUT}\\${name}.png`).size}B`);
};

// 当前就在「全部邮件」底部
const more = find('加载更多');
if (more) { tap(more.x, more.y, 2500); console.log('加载更多'); }

let hit = null;
for (let i = 0; i < 12 && !hit; i++) {
  hit = find('排版检查');
  if (!hit) { sh('shell input swipe 540 1900 540 700 400'); sleep(1200); }
}
if (!hit) { console.log('MISS'); shot('11c-allmail-end'); process.exit(1); }
sh(`shell input tap ${hit.x} ${hit.y}`);
sleep(3500);
shot('08-detail-long');
for (let i = 0; i < 3; i++) { sh('shell input swipe 540 1700 540 800 400'); sleep(1200); }
shot('08b-detail-long-scrolled');
console.log('done');
