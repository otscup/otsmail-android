# 真机验收：登录后逐屏截图（按可见文本定位点击，避免硬编码坐标）
param(
  [string]$Out = 'F:\Desktop\MFiles\otsmail-android-shots\acc',
  [string]$Email = 'admin@example.com',
  [string]$Password = 'smoke123456'
)
$ErrorActionPreference = 'Continue'
$adb = 'E:\tools\android-sdk\platform-tools\adb.exe'
$pkg = 'com.otscup.otsmail'
New-Item -ItemType Directory -Force -Path $Out | Out-Null

function Shot([string]$name) {
  & $adb shell screencap -p /sdcard/_s.png | Out-Null
  & $adb pull /sdcard/_s.png "$Out\$name.png" 2>$null | Out-Null
  Write-Host "shot $name"
}
function Dump() {
  & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
  & $adb pull /sdcard/ui.xml "$env:TEMP\ui.xml" 2>$null | Out-Null
  return (node 'E:\DeepSeek workers\otsmail-android\tools\ui-bounds.mjs' "$env:TEMP\ui.xml" 2>$null)
}
function TapText([string]$text, [int]$waitMs = 1200) {
  $line = (Dump) | Where-Object { $_ -like "*$text*" } | Select-Object -First 1
  if (-not $line) { Write-Host "MISS '$text'"; return $false }
  if ($line -match 'x=(\d+)\s+y=(\d+)') {
    & $adb shell input tap $Matches[1] $Matches[2] | Out-Null
    Start-Sleep -Milliseconds $waitMs
    return $true
  }
  Write-Host "no coords '$text': $line"; return $false
}
function TypeText([string]$text) {
  & $adb shell input text $text | Out-Null
  Start-Sleep -Milliseconds 600
}

& $adb reverse tcp:8789 tcp:8789 | Out-Null
& $adb shell pm clear $pkg | Out-Null
& $adb shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 5
Shot '01-login'

# 登录：点邮箱行输入，点密码行输入，点登录
TapText 'you@example.com' | Out-Null
TypeText $Email
TapText '密码' | Out-Null
TypeText $Password
TapText '登录' 3000 | Out-Null
Shot '02-inbox'

# 抽屉
$line = (Dump) | Where-Object { $_ -like '*收件箱*' } | Select-Object -First 1
if ($line -match 'x=(\d+)\s+y=(\d+)') { & $adb shell input tap $Matches[1] $Matches[2] | Out-Null; Start-Sleep -Milliseconds 1500 }
Shot '03-drawer'

TapText '设置' 1500 | Out-Null
Shot '04-settings'
TapText '系统设置' 1800 | Out-Null
Shot '05-syssettings'
TapText '联系人' 1800 | Out-Null
Shot '06-contacts'
TapText 'AI 助手' 1800 | Out-Null
Shot '07-ai'

Write-Host "done -> $Out"
