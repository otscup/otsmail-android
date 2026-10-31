Add-Type -AssemblyName System.Drawing

$dir = 'F:\Desktop\MFiles\otsmail-android-shots\acc'
$out = 'F:\Desktop\MFiles\otsmail-android-shots\contact-sheet.png'

$names = @(
  '01-login','02-inbox','03-drawer','04-settings','05-syssettings',
  '06-contacts','07-ai','08-detail','09-compose','10-drafts',
  '11-allmail','12-users','13-roles','14-regkeys','15-analysis'
)

$cellW = 216; $cellH = 456; $labelH = 18; $cols = 5
$rows = [math]::Ceiling($names.Count / $cols)
$pad = 12
$tileW = $cellW + $pad * 2
$tileH = $cellH + $labelH + $pad * 2
$W = $cols * $tileW
$H = $rows * $tileH

$sheet = New-Object System.Drawing.Bitmap $W, $H
$g = [System.Drawing.Graphics]::FromImage($sheet)
$g.Clear([System.Drawing.Color]::FromArgb(242,242,247))
$g.InterpolationMode = 'HighQualityBicubic'
$font = New-Object System.Drawing.Font('Segoe UI', 9)
$brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(60,60,67))

for ($i = 0; $i -lt $names.Count; $i++) {
  $path = Join-Path $dir ($names[$i] + '.png')
  if (-not (Test-Path $path)) { continue }
  $img = [System.Drawing.Image]::FromFile($path)
  $col = $i % $cols
  $row = [math]::Floor($i / $cols)
  $x = $col * $tileW + $pad
  $y = $row * $tileH + $pad
  $g.DrawImage($img, (New-Object System.Drawing.Rectangle $x, $y, $cellW, $cellH))
  $g.DrawString($names[$i], $font, $brush, ($x), ($y + $cellH + 2))
  $img.Dispose()
}

$g.Dispose()
$sheet.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()
Write-Output "$out $((Get-Item $out).Length) bytes $W x $H"
