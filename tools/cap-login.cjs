// 拍登录页（先退出登录），再重新登录复原
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
const tap = (x, y, w = 1700) => { sh(`shell input tap ${x} ${y}`); sleep(w); };
function tapText(needle, swipes = 4) {
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
const onLogin = () => texts().some((n) => n.text.includes('登录到你的邮箱'));
if (!onLogin()) {
  const drawerOpen = () => texts().some((n) => n.text.includes('未读邮件'));
  for (let i = 0; i < 4; i++) { sh('shell input swipe 540 900 540 1900 300'); sleep(400); }
  if (!drawerOpen()) { tap(100, 204); }
  if (!drawerOpen()) { console.log('抽屉没打开'); process.exit(1); }
  if (!tapText('设置', 2)) { console.log('MISS 设置'); process.exit(1); }
  sleep(1500);
  if (!tapText('退出登录', 4)) { console.log('MISS 退出登录'); process.exit(1); }
  sleep(2500);
  shot('01-login');
}

// 重新登录：邮箱 → 密码 → 登录
const fields = texts().filter((n) => n.text.includes('@') || n.text === '登录');
console.log('login nodes:', fields.map((n) => `${n.text}@${n.x},${n.y}`).join(' | '));
const emailRow = texts().find((n) => n.text.includes('you@example.com') || n.text.includes('邮箱地址'));
if (!emailRow) { console.log('MISS 输入行'); process.exit(1); }
tap(emailRow.x, emailRow.y + 80, 900);
for (let i = 0; i < 40; i++) sh('shell input keyevent 67');
sh('shell input text "admin@example.com"');
sleep(600);
sh('shell input keyevent 61');
sleep(600);
sh('shell input text "123456"');
sleep(600);
sh('shell input keyevent 111');
sleep(800);
tapText('登录', 1);
sleep(4500);
shot('01b-after-login');
console.log(texts().slice(0, 6).map((n) => n.text).join(' | '));
