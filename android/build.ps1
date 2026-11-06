# Builds the native Android app (Kotlin + Jetpack Compose) → D:\TopCashSQL\android\TopCash-Studio.apk
# Everything (Gradle, libraries, SDK, caches, temp files) stays inside D:\TopCashSQL.
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\android\build.ps1            (release, signed)
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\android\build.ps1 -Install   (also installs on the connected phone)
param([switch]$Install)
$ErrorActionPreference = 'Stop'
$root  = Split-Path $PSScriptRoot -Parent            # D:\TopCashSQL
$tools = Join-Path $root '.tools'
$env:GRADLE_USER_HOME  = Join-Path $tools 'gradle-home'
$env:ANDROID_HOME      = Join-Path $tools 'android-sdk'
$env:ANDROID_SDK_ROOT  = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $tools 'android-user'
$tmp = Join-Path $root '.runtime\tmp'; New-Item -ItemType Directory -Force $tmp, $env:ANDROID_USER_HOME | Out-Null
$env:TEMP = $tmp; $env:TMP = $tmp
$javaHome = Join-Path $tools 'home'; New-Item -ItemType Directory -Force $javaHome | Out-Null
# user.home redirected too: Kotlin / Java tooling write their profile files there instead of C:\Users\...
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$($tmp.Replace('\','/')) -Duser.home=$($javaHome.Replace('\','/'))"
$gradle = Get-ChildItem $tools -Directory -Filter 'gradle-*' | Where-Object { Test-Path "$($_.FullName)\bin\gradle.bat" } | Sort-Object Name | Select-Object -Last 1
Push-Location $PSScriptRoot
try {
  & "$($gradle.FullName)\bin\gradle.bat" --no-daemon --console=plain :app:assembleRelease
  if ($LASTEXITCODE) { throw "Build non riuscita" }
  $apk = Join-Path $PSScriptRoot 'TopCash-Studio.apk'
  Copy-Item 'app\build\outputs\apk\release\app-release.apk' $apk -Force
  Write-Host "APK pronto: $apk ($([math]::Round((Get-Item $apk).Length/1MB,1)) MB)" -ForegroundColor Green
  if ($Install) { & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r $apk }
} finally { Pop-Location }
