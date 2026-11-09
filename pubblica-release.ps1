# Pubblica il codice e una Release su GitHub (repository privato lapob/tcs-sql).
# Uso:  powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\pubblica-release.ps1 -Versione 2.3.0 [-Note "cosa cambia"]
# Prima volta: accedi con  .tools\gh\bin\gh.exe auth login  (vedi GUIDA, sezione "Versioni e Release").
# Nella Release vanno SOLO i programmi compilati: mai database, backup, chiavi o password (esclusi da .gitignore).
param(
 [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Versione,
 [string]$Note = ''
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'lapob/tcs-sql'
$env:GH_CONFIG_DIR = Join-Path $root '.tools\gh-config'
$gh = Join-Path $root '.tools\gh\bin\gh.exe'
$git = Join-Path $root '.tools\git\cmd\git.exe'
if (-not (Test-Path $gh)) { throw "GitHub CLI non trovato in $gh" }
& $gh auth status *> $null; if ($LASTEXITCODE) { throw 'Non hai effettuato l''accesso a GitHub: esegui prima  .tools\gh\bin\gh.exe auth login' }

Set-Location $root
if (& $git status --porcelain) { throw 'Ci sono modifiche non salvate in Git: fai prima il commit.' }

# 1. repository privato (creato la prima volta) e invio del codice
& $gh repo view $repo *> $null
if ($LASTEXITCODE) { & $gh repo create $repo --private --description 'Top Cash Studio - gestionale Top Cash Service S.r.l.' ; if ($LASTEXITCODE) { throw 'Creazione repository non riuscita' } }
if (-not (& $git remote | Select-String -SimpleMatch 'origin')) { & $git remote add origin "https://github.com/$repo.git" }
& $gh auth setup-git
& $git push -u origin HEAD:main
if ($LASTEXITCODE) { throw 'Invio del codice non riuscito' }

# 2. pacchetti da scaricare
$out = Join-Path $root ".runtime\release-$Versione"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory $out | Out-Null
$apk = Join-Path $root 'android\TopCash-Studio.apk'
$files = @()
if (Test-Path $apk) { Copy-Item $apk (Join-Path $out "TopCash-Studio-$Versione.apk"); $files += Join-Path $out "TopCash-Studio-$Versione.apk" }
if (Test-Path (Join-Path $root 'app\TopCash.Licenze.exe')) {
 Compress-Archive -Path (Join-Path $root 'app\*') -DestinationPath (Join-Path $out "TopCash-Studio-Windows-$Versione.zip"); $files += Join-Path $out "TopCash-Studio-Windows-$Versione.zip" }
if (Test-Path (Join-Path $root 'server-update\TopCash.Server.exe')) {
 Compress-Archive -Path (Join-Path $root 'server-update\*') -DestinationPath (Join-Path $out "TopCash-Server-$Versione.zip"); $files += Join-Path $out "TopCash-Server-$Versione.zip" }
if (-not $files) { throw 'Nessun programma compilato da pubblicare.' }
# safety net: nothing that looks like data or secrets may leave this PC
foreach ($f in $files | Where-Object { $_ -like '*.zip' }) {
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $bad = [IO.Compression.ZipFile]::OpenRead($f).Entries | Where-Object { $_.FullName -match '\.(sqlite|db|key|pfx|jks|keystore)$|keystore-password|RETE\.ini' }
 if ($bad) { throw "Il pacchetto $f contiene file non pubblicabili: $($bad.FullName -join ', ')" }
}

# 3. Release con note e file
if (-not $Note) { $Note = (& $git log -1 --pretty=%B) -join "`n" }
$tag = "v$Versione"
& $gh release create $tag @files --repo $repo --title "Top Cash Studio $Versione" --notes $Note --target main
if ($LASTEXITCODE) { throw 'Creazione della Release non riuscita' }
Write-Host "Release pubblicata: https://github.com/$repo/releases/tag/$tag" -ForegroundColor Green
