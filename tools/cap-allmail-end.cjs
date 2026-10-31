// 全部邮件：加载更多到最后一页，看页脚与是否重复
const { execSync } = require('node:child_process');
const fs = require('node:fs');
const ADB = 'E:\\tools\\android-sdk\\platform-tools\\adb.exe';
const OUT = 'F:\\Desktop\\MFiles\\otsmail-android-shots\\acc';
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const sh = (cmd) => execSync(`"${ADB}" ${cmd}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
function texts() {
  sh('shell uiautomator dump /sdcard/_cap.xml');
  sh('pull /sdcard/_cap.xml "E:\\Temp\\_cap.xml"');
  const xml = fs.readFileSync('E:\\Temp\\_cap.xml', 'utf8');
  const re = /(?:text|content-desc)="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
  const out = []; let m;
  while ((m = re.exec(xml))) if (m[1]) out.push({ text: m[1], x: Math.round((+m[2] + +m[4]) / 2), y: Math.round((+m[3] + +m[5]) / 2) });
  return out;
}
const tap = (x, y, w = 1800) => { sh(`shell input tap ${x} ${y}`); sleep(w); };
function tapText(needle, swipes = 3) {
  for (let i = 0; i <= swipes; i++) {
    const hit = texts().find((n) => n.text.includes(needle) && n.y > 120 && n.y < 2180);
    if (hit) { tap(hit.x, hit.y); return true; }
    sh('shell input swipe 540 1800 540 900 350'); sleep(900);
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
tap(100, 204);
tapText('设置');
tapText('全部邮件');

const rows = () => texts().filter((n) => /@/.test(n.text)).length;
for (let i = 0; i < 4; i++) {
  for (let k = 0; k < 12; k++) { sh('shell input swipe 540 1900 540 700 350'); sleep(250); }
  sleep(600);
  const more = texts().find((n) => n.text.includes('加载更多'));
  if (!more) { console.log('到底了，没有「加载更多」'); break; }
  tap(more.x, more.y, 2200);
  console.log(`第 ${i + 1} 次加载更多后，含 @ 的行 ${rows()}`);
}
// 到底
for (let i = 0; i < 12; i++) { sh('shell input swipe 540 1900 540 700 400'); sleep(350); }
sleep(800);
shot('11c-allmail-end');
console.log(texts().filter((n) => /共 |已经|加载/.test(n.text)).map((n) => n.text).join(' | '));
