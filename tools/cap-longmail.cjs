// 在「全部邮件」里找「排版检查」那封（收件箱看不到，它在全量列表里）并截图
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
function tapText(needle, swipes = 3) {
  for (let i = 0; i <= swipes; i++) {
    const hit = nodes().find((n) => n.text.includes(needle) && n.y > 120 && n.y < 2180);
    if (hit) { tap(hit.x, hit.y, 1800); return true; }
    sh('shell input swipe 540 1800 540 900 350'); sleep(1000);
  }
  return false;
}
const shot = (name) => {
  sh('shell screencap -p /sdcard/_cap.png');
  sh(`pull /sdcard/_cap.png "${OUT}\\${name}.png"`);
  console.log(`shot ${name} ${fs.statSync(`${OUT}\\${name}.png`).size}B`);
};

sh('reverse tcp:8789 tcp:8789');
sh('shell am force-stop com.otscup.otsmail');
sh('shell am start -n com.otscup.otsmail/.MainActivity');
sleep(7000);
tap(100, 204);                    // 抽屉
tapText('设置');
tapText('全部邮件');

let hit = null;
for (let i = 0; i < 14 && !hit; i++) {
  hit = nodes().find((n) => n.text.includes('排版检查') && n.y > 200 && n.y < 2100);
  if (!hit) { sh('shell input swipe 540 1900 540 700 400'); sleep(1200); }
}
if (!hit) { console.log('MISS 排版检查'); shot('11b-allmail-bottom'); process.exit(1); }
sh(`shell input tap ${hit.x} ${hit.y}`);
sleep(3500);
shot('08-detail-long');
sh('shell input swipe 540 1700 540 900 400');
sleep(1200);
shot('08b-detail-long-scrolled');
console.log('done');
