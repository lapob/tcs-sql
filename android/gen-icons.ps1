# Generates the Android launcher icons (adaptive foreground, monochrome, legacy) from the official Top Cash logo.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$logo = 'G:\My Drive\DOCUMENTI TOP CASH SERVICE SRL\Sfondi Top Cash Service srl\Logo Top Cash supremo.jpg'
if (-not (Test-Path $logo)) { $logo = Join-Path $PSScriptRoot 'logo-source.jpg' }
$res = Join-Path $PSScriptRoot 'app\src\main\res'
$src = [System.Drawing.Image]::FromFile($logo); $markH = 1930
# transparent mark (white -> alpha), cached as a PNG next to the project
function Get-Mark([int]$w) {
  $h = [int]($markH * $w / $src.Width)
  $b = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($b); $g.InterpolationMode = 'HighQualityBicubic'; $g.PixelOffsetMode = 'HighQuality'
  $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0,0,$w,$h), (New-Object System.Drawing.Rectangle 0,0,$src.Width,$markH), [System.Drawing.GraphicsUnit]::Pixel); $g.Dispose()
  $rect = New-Object System.Drawing.Rectangle 0,0,$w,$h
  $data = $b.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadWrite, $b.PixelFormat)
  $bytes = New-Object byte[] ($data.Stride * $h); [Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
  for ($i = 0; $i -lt $bytes.Length; $i += 4) {
    $bl = $bytes[$i]; $gr = $bytes[$i+1]; $rd = $bytes[$i+2]; $m = [Math]::Min($rd, [Math]::Min($gr, $bl)); $a = 255 - $m
    if ($a -le 3) { $bytes[$i+3] = 0; continue }
    $bytes[$i] = [byte][Math]::Min(255, ($bl - $m) * 255 / $a); $bytes[$i+1] = [byte][Math]::Min(255, ($gr - $m) * 255 / $a); $bytes[$i+2] = [byte][Math]::Min(255, ($rd - $m) * 255 / $a); $bytes[$i+3] = [byte]$a
  }
  [Runtime.InteropServices.Marshal]::Copy($bytes, 0, $data.Scan0, $bytes.Length); $b.UnlockBits($data); return $b
}
$mark = Get-Mark 1200
function Save-Canvas([int]$size, [double]$widthRatio, [string]$path, [bool]$mono, [bool]$tile) {
  $b = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($b); $g.InterpolationMode = 'HighQualityBicubic'; $g.SmoothingMode = 'AntiAlias'; $g.PixelOffsetMode = 'HighQuality'; $g.Clear([System.Drawing.Color]::Transparent)
  if ($tile) { # legacy icon: white rounded tile
    $r = [single]($size * 0.22); $p = New-Object System.Drawing.Drawing2D.GraphicsPath; $d = 2 * $r; $s = [single]$size - 1
    $p.AddArc(0,0,$d,$d,180,90); $p.AddArc($s-$d,0,$d,$d,270,90); $p.AddArc($s-$d,$s-$d,$d,$d,0,90); $p.AddArc(0,$s-$d,$d,$d,90,90); $p.CloseFigure()
    $g.FillPath([System.Drawing.Brushes]::White, $p)
  }
  $w = $size * $widthRatio; $h = $w * $mark.Height / $mark.Width
  $dest = New-Object System.Drawing.RectangleF ([single](($size - $w) / 2)), ([single](($size - $h) / 2)), ([single]$w), ([single]$h)
  if ($mono) { $cm = New-Object System.Drawing.Imaging.ColorMatrix; $cm.Matrix00 = 0; $cm.Matrix11 = 0; $cm.Matrix22 = 0; $cm.Matrix40 = 1; $cm.Matrix41 = 1; $cm.Matrix42 = 1
    $ia = New-Object System.Drawing.Imaging.ImageAttributes; $ia.SetColorMatrix($cm)
    $g.DrawImage($mark, [System.Drawing.Rectangle]::Round($dest), 0, 0, $mark.Width, $mark.Height, [System.Drawing.GraphicsUnit]::Pixel, $ia) }
  else { $g.DrawImage($mark, $dest) }
  $g.Dispose(); New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null; $b.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose()
}
# adaptive icon canvas = 108dp; masks (circle/squircle) keep only a 66dp circle: the wide mark (1.8:1) must fit its diagonal inside it -> 50% of the canvas width
foreach ($d in @(@('mdpi',1),@('hdpi',1.5),@('xhdpi',2),@('xxhdpi',3),@('xxxhdpi',4))) {
  $px = [int](108 * $d[1]); $legacy = [int](48 * $d[1])
  Save-Canvas $px 0.50 "$res\mipmap-$($d[0])\ic_launcher_foreground.png" $false $false
  Save-Canvas $px 0.46 "$res\mipmap-$($d[0])\ic_launcher_monochrome.png" $true $false
  Save-Canvas $legacy 0.86 "$res\mipmap-$($d[0])\ic_launcher.png" $false $true
}
# splash screen icon (Android 12+: 240dp canvas, only a 160dp circle is visible) -> 44% keeps the whole mark inside on every device
Save-Canvas 960 0.44 "$res\drawable-nodpi\splash_logo.png" $false $false
$mark.Dispose(); $src.Dispose()
Write-Host "Icone generate in $res" -ForegroundColor Green
