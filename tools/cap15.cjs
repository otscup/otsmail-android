// 15 屏真机截图：每屏都从冷启动开始，保证状态确定
const { execSync } = require('node:child_process');
const fs = require('node:fs');

const ADB = 'E:\\tools\\android-sdk\\platform-tools\\adb.exe';
const OUT = 'F:\\Desktop\\MFiles\\otsmail-android-shots\\acc';
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const sh = (cmd) => execSync(`"${ADB}" ${cmd}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });

function dump() {
  sh('shell uiautomator dump /sdcard/_cap.xml');
  sh('pull /sdcard/_cap.xml "E:\\Temp\\_cap.xml"');
  return fs.readFileSync('E:\\Temp\\_cap.xml', 'utf8');
}

function nodes(xml) {
  const out = [];
  const re = /(?:text|content-desc)="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
  let m;
  while ((m = re.exec(xml))) {
    const [, text, x1, y1, x2, y2] = m;
    if (!text) continue;
    out.push({ text, x: Math.round((+x1 + +x2) / 2), y: Math.round((+y1 + +y2) / 2) });
  }
  return out;
}

const tap = (x, y, wait = 1600) => { sh(`shell input tap ${x} ${y}`); sleep(wait); };

function tapText(needle, swipes = 2) {
  for (let i = 0; i <= swipes; i++) {
    const hit = nodes(dump()).find((n) => n.text.includes(needle) && n.y > 120 && n.y < 2180);
    if (hit) { tap(hit.x, hit.y, 1800); console.log(`  tap "${needle}" @${hit.x},${hit.y}`); return true; }
    sh('shell input swipe 540 1800 540 900 350');
    sleep(1000);
  }
  console.log(`  MISS "${needle}"`);
  return false;
}

function shot(name) {
  sh('shell screencap -p /sdcard/_cap.png');
  sh(`pull /sdcard/_cap.png "${OUT}\\${name}.png"`);
  console.log(`shot ${name} ${fs.statSync(`${OUT}\\${name}.png`).size}B`);
}

const coldStart = () => {
  sh('shell am force-stop com.otscup.otsmail');
  sh('shell am start -n com.otscup.otsmail/.MainActivity');
  sleep(6500);
};

/** 冷启动 → 抽屉 → 滑到「其他」组 → 点目标 → 截图 */
function screen(needle, name) {
  coldStart();
  tap(100, 204, 1800);
  if (!tapText(needle, 3)) return;
  sleep(900);
  shot(name);
}

sh('reverse tcp:8789 tcp:8789');

coldStart();
shot('02-inbox');
tap(100, 204, 1800);
shot('03-drawer');

screen('系统设置', '05-syssettings');
screen('联系人', '06-contacts');
screen('AI 助手', '07-ai');

// 设置页里的管理入口：一次性进设置页，逐项进入后返回
coldStart();
tap(100, 204, 1800);
if (tapText('设置', 2)) {
  shot('04-settings');
  const pageItem = (needle, name) => {
    sh('shell input swipe 540 900 540 1800 300');
    sh('shell input swipe 540 900 540 1800 300');
    sleep(800);
    if (!tapText(needle, 3)) return;
    sleep(900);
    shot(name);
    tap(100, 171, 1600);
  };
  pageItem('草稿箱', '10-drafts');
  pageItem('全部邮件', '11-allmail');
  pageItem('用户管理', '12-users');
  pageItem('权限控制', '13-roles');
  pageItem('注册密钥', '14-regkeys');
  pageItem('分析', '15-analysis');
}

coldStart();
tap(540, 620, 2800);
shot('08-detail');

coldStart();
tap(978, 2100, 2400);
shot('09-compose');
console.log('done');
