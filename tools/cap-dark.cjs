// 暗色真机检查：打开第一封邮件（正文配色）与收件箱
const { execSync } = require('node:child_process');
const fs = require('node:fs');
const ADB = 'E:\\tools\\android-sdk\\platform-tools\\adb.exe';
const OUT = 'F:\\Desktop\\MFiles\\otsmail-android-shots\\acc';
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const sh = (cmd) => execSync(`"${ADB}" ${cmd}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
const shot = (name) => {
  sh('shell screencap -p /sdcard/_cap.png');
  sh(`pull /sdcard/_cap.png "${OUT}\\${name}.png"`);
  console.log(`shot ${name} ${fs.statSync(`${OUT}\\${name}.png`).size}B`);
};

sh('shell cmd uimode night yes');
sleep(2500);
sh('reverse tcp:8789 tcp:8789');
sh('shell am force-stop com.otscup.otsmail');
sh('shell am start -n com.otscup.otsmail/.MainActivity');
sleep(7000);
shot('16-dark-inbox');
sh('shell input tap 540 620');
sleep(3200);
shot('17-dark-detail');
sh('shell cmd uimode night no');
sleep(1500);
console.log('done');
