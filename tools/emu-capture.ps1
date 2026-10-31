# 用本地模拟器给最新 APK 拍 15 屏截图（无真机时的替代验证）。
# 依赖：E:\tools\android-sdk（platform-tools/emulator）、系统镜像 android-33;default;x86_64、
#       tools/ui-bounds.mjs（解析 uiautomator XML 按文本找坐标）。
param(
  [string]$Avd = 'ots',
  [string]$Out = 'F:\Desktop\MFiles\otsmail-android-shots\emu',
  [string]$WorkerPort = '8789'
)
$ErrorActionPreference = 'Stop'
$sdk = 'E:\tools\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$app = 'com.otscup.otsmail'
New-Item -ItemType Directory -Force -Path $Out | Out-Null

# 1) 确保 AVD 存在
$avds = & "$sdk\cmdline-tools\latest\bin\avdmanager.bat" list avd 2>$null
if ($avds -notmatch "Name:\s+$Avd\b") {
  'no' | & "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n $Avd -k 'system-images;android-33;default;x86_64' -d pixel_5 --force 2>&1 | Select-Object -Last 2
}

# 2) 启动模拟器（无窗口、软件渲染）
& $adb kill-server 2>$null | Out-Null
$emuProc = Start-Process -FilePath $emu -ArgumentList @('-avd', $Avd, '-no-window', '-no-audio', '-no-boot-anim', '-gpu', 'swiftshader_indirect', '-no-snapshot-save', '-port', '5554') -PassThru
& $adb wait-for-device
for ($i = 0; $i -lt 180; $i++) {
  $boot = (& $adb shell getprop sys.boot_completed 2>$null) -join ''
  if ($boot.Trim() -eq '1') { break }
  Start-Sleep -Seconds 2
}
"booted after $i polls"

# 3) 装包 + 转发端口（模拟器里 127.0.0.1 就是它自己，需要 reverse 才能打到宿主机 worker）
& $adb reverse "tcp:$WorkerPort" "tcp:$WorkerPort"
& $adb install -r 'E:\DeepSeek workers\otsmail-android\app\build\outputs\apk\debug\app-debug.apk' | Select-Object -Last 1
& $adb shell am force-stop $app
& $adb shell am start -n "$app/.MainActivity" | Out-Null
Start-Sleep -Seconds 6

function Shot([string]$name) {
  $remote = "/sdcard/_$name.png"
  & $adb shell screencap -p $remote | Out-Null
  & $adb pull $remote "$Out\$name.png" 2>$null | Out-Null
  & $adb shell rm $remote | Out-Null
  Write-Host "shot: $name"
}

function TapText([string]$text) {
  $xml = '/sdcard/ui.xml'
  & $adb shell uiautomator dump $xml | Out-Null
  & $adb pull $xml "$env:TEMP\ui.xml" 2>$null | Out-Null
  $lines = node 'E:\DeepSeek workers\otsmail-android\tools\ui-bounds.mjs' "$env:TEMP\ui.xml" 2>$null
  $line = $lines | Where-Object { $_ -like "*$text*" } | Select-Object -First 1
  if (-not $line) { Write-Host "MISS tap: $text"; return $false }
  if ($line -match 'x=(\d+)\s+y=(\d+)') {
    & $adb shell input tap $Matches[1] $Matches[2] | Out-Null
    Start-Sleep -Milliseconds 1200
    return $true
  }
  Write-Host "no coords in: $line"; return $false
}

# 4) 逐屏截图：先登录一次，再按可见文本点进去
Shot '01-login'
TapText '登录' | Out-Null
Start-Sleep -Seconds 3
Shot '02-inbox'
TapText '收件箱  ' | Out-Null            # 抽屉按钮
Shot '03-drawer'
TapText '设置' | Out-Null
Shot '04-settings'
TapText '系统设置' | Out-Null
Shot '05-syssettings'
TapText '联系人' | Out-Null
Shot '06-contacts'
TapText 'AI 助手' | Out-Null
Shot '07-ai'
Shot '08-current'

# 5) 收尾
& $adb shell am force-stop $app | Out-Null
Stop-Process -Id $emuProc.Id -ErrorAction SilentlyContinue
Write-Host "done -> $Out"
