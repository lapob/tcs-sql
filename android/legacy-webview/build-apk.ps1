# Builds TopCash-Studio.apk with the Android SDK build-tools only (no Gradle, no downloads).
# Usage:  powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\android\build-apk.ps1
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$sdk  = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt   = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^\d.]','') } | Select-Object -Last 1
$plat = Get-ChildItem "$sdk\platforms" -Directory | Where-Object { Test-Path "$($_.FullName)\android.jar" } | Sort-Object Name | Select-Object -Last 1
$jar  = "$($plat.FullName)\android.jar"
$out  = Join-Path $here 'build'; Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue; New-Item -ItemType Directory $out,"$out\res","$out\gen","$out\classes","$out\dex" | Out-Null
Write-Host "build-tools $($bt.Name) - $($plat.Name)"

# 1. launcher icons from the Top Cash tile (all densities)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile((Resolve-Path "$here\..\src\ui\assets\tcs-app@2x.png"))
$res = Join-Path $here 'res'
foreach ($d in @(@('mdpi',48),@('hdpi',72),@('xhdpi',96),@('xxhdpi',144),@('xxxhdpi',192))) {
  $dir = "$res\mipmap-$($d[0])"; New-Item -ItemType Directory -Force $dir | Out-Null
  $b = New-Object System.Drawing.Bitmap $d[1],$d[1]; $g = [System.Drawing.Graphics]::FromImage($b)
  $g.InterpolationMode='HighQualityBicubic'; $g.PixelOffsetMode='HighQuality'; $g.Clear([System.Drawing.Color]::Transparent); $g.DrawImage($src,0,0,$d[1],$d[1]); $g.Dispose()
  $b.Save("$dir\ic_launcher.png",[System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose()
}
$src.Dispose()

# 2. resources -> R.java + base apk
& "$($bt.FullName)\aapt2.exe" compile --dir $res -o "$out\res\compiled.zip"; if ($LASTEXITCODE) { throw 'aapt2 compile' }
& "$($bt.FullName)\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$here\AndroidManifest.xml" --java "$out\gen" "$out\res\compiled.zip" --auto-add-overlay; if ($LASTEXITCODE) { throw 'aapt2 link' }

# 3. Java -> dex
$java = Get-ChildItem "$here\src","$out\gen" -Recurse -Filter *.java | ForEach-Object FullName
& javac --release 11 -encoding UTF-8 -Xlint:-options -classpath $jar -d "$out\classes" @java; if ($LASTEXITCODE) { throw 'javac' }
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$($bt.FullName)\d8.bat" --release --min-api 24 --lib $jar --output "$out\dex" @classes; if ($LASTEXITCODE) { throw 'd8' }

# 4. package, align, sign
Copy-Item "$out\base.apk" "$out\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk",'Update'); [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$out\dex\classes.dex",'classes.dex') | Out-Null; $zip.Dispose()
& "$($bt.FullName)\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'zipalign' }
$ks = Join-Path $here 'topcash-release.keystore'
if (-not (Test-Path $ks)) {
  # Signing key for updates: KEEP THIS FILE (and its password file) — a new key means uninstalling the app first.
  $rb = New-Object byte[] 18; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($rb); $pw = [Convert]::ToBase64String($rb)
  Set-Content (Join-Path $here 'keystore-password.txt') $pw -NoNewline
  $ErrorActionPreference = 'Continue' # keytool prints progress on stderr
  & keytool -genkeypair -keystore $ks -alias topcash -keyalg RSA -keysize 3072 -validity 10000 -storepass $pw -keypass $pw -dname "CN=Top Cash Service S.r.l., O=Top Cash Service S.r.l., L=Firenze, C=IT" 2>&1 | Out-Null
  $ErrorActionPreference = 'Stop'; if (-not (Test-Path $ks)) { throw 'keytool' }
}
$pw = Get-Content (Join-Path $here 'keystore-password.txt') -Raw
$apk = Join-Path $here 'TopCash-Studio.apk'
& "$($bt.FullName)\apksigner.bat" sign --ks $ks --ks-key-alias topcash --ks-pass "pass:$pw" --key-pass "pass:$pw" --out $apk "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'apksigner' }
& "$($bt.FullName)\apksigner.bat" verify --print-certs $apk | Select-Object -First 2
Write-Host "APK pronto: $apk ($([math]::Round((Get-Item $apk).Length/1KB)) KB)" -ForegroundColor Green
