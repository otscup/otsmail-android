// 重拍抽屉（等动画结束再截，避免半开状态）
const { execSync } = require('node:child_process');
const fs = require('node:fs');
const ADB = 'E:\\tools\\android-sdk\\platform-tools\\adb.exe';
const OUT = 'F:\\Desktop\\MFiles\\otsmail-android-shots\\acc';
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const sh = (cmd) => execSync(`"${ADB}" ${cmd}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
sh('shell am force-stop com.otscup.otsmail');
sh('shell am start -n com.otscup.otsmail/.MainActivity');
sleep(7000);
sh('shell input tap 100 204');
sleep(2500);
sh('shell screencap -p /sdcard/_d.png');
sh(`pull /sdcard/_d.png "${OUT}\\03-drawer.png"`);
console.log('03-drawer', fs.statSync(`${OUT}\\03-drawer.png`).size + 'B');
