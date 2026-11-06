# Top Cash Studio - aggiorna il server installato con la versione in .\server-update (dati e utenti restano invariati).
# Esegui come amministratore:  powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\aggiorna-server.ps1
$ErrorActionPreference = 'Stop'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  Write-Host "Apri PowerShell con 'Esegui come amministratore' e rilancia lo script." -ForegroundColor Yellow; exit 1
}
$new = Join-Path $PSScriptRoot 'server-update'; $dst = Join-Path $PSScriptRoot 'server-app'
if (-not (Test-Path (Join-Path $new 'TopCash.Server.exe'))) { throw "Nessun aggiornamento in $new" }
Stop-Service TopCashStudio -Force; Start-Sleep 2
robocopy $new $dst /MIR /NFL /NDL /NJH /NJS /R:3 /W:2 | Out-Null
Start-Service TopCashStudio; Start-Sleep 2
try { $h = Invoke-RestMethod http://localhost:8470/api/health -TimeoutSec 10; Write-Host "Server aggiornato e in funzione." -ForegroundColor Green }
catch { Write-Host "Il servizio non risponde: controlla in services.msc 'Top Cash Studio Server'." -ForegroundColor Red }
