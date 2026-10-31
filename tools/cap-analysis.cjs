// 只重拍分析页
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
const tap = (x, y, w = 1700) => { sh(`shell input tap ${x} ${y}`); sleep(w); };
function tapText(needle, swipes = 3) {
  for (let i = 0; i <= swipes; i++) {
    const hit = nodes().find((n) => n.text.includes(needle) && n.y > 120 && n.y < 2180);
    if (hit) { tap(hit.x, hit.y, 1900); return true; }
    sh('shell input swipe 540 1800 540 900 350'); sleep(1000);
  }
  return false;
}
sh('reverse tcp:8789 tcp:8789');
sh('shell am force-stop com.otscup.otsmail');
sh('shell am start -n com.otscup.otsmail/.MainActivity');
sleep(6500);
tap(100, 204);
tapText('设置');
tapText('分析');
sleep(900);
sh('shell screencap -p /sdcard/_cap.png');
sh(`pull /sdcard/_cap.png "${OUT}\\15-analysis.png"`);
console.log('shot 15-analysis', fs.statSync(`${OUT}\\15-analysis.png`).size + 'B');
