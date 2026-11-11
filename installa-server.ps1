# Top Cash Studio - installazione del server condiviso come servizio Windows.
# Da eseguire UNA volta sul PC dell'ufficio che fara' da server, in PowerShell "Esegui come amministratore":
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\installa-server.ps1
param(
  [int]$Porta = 8470,
  [string]$Cartella = $PSScriptRoot
)
$ErrorActionPreference = 'Stop'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  Write-Host "Apri PowerShell con 'Esegui come amministratore' e rilancia lo script." -ForegroundColor Yellow; exit 1
}
$exe  = Join-Path $Cartella 'server-app\TopCash.Server.exe'
$data = Join-Path $Cartella 'data'
if (-not (Test-Path $exe)) { throw "Non trovo $exe" }

# 1. Servizio Windows: parte con il PC e si riavvia da solo in caso di errore
if (Get-Service TopCashStudio -ErrorAction SilentlyContinue) { Stop-Service TopCashStudio -Force; sc.exe delete TopCashStudio | Out-Null; Start-Sleep 2 }
New-Service -Name TopCashStudio -DisplayName 'Top Cash Studio Server' -Description 'Archivio condiviso Top Cash Service (clienti, licenze, ordini).' `
  -BinaryPathName ("`"$exe`" --data=`"$data`" --urls=http://0.0.0.0:$Porta") -StartupType Automatic | Out-Null
sc.exe failure TopCashStudio reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null
Start-Service TopCashStudio

# 2. Firewall: porta aperta SOLO sulla rete privata dell'ufficio (non su reti pubbliche)
Get-NetFirewallRule -DisplayName 'Top Cash Studio Server' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName 'Top Cash Studio Server' -Direction Inbound -Protocol TCP -LocalPort $Porta -Action Allow -Profile Private | Out-Null

$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notmatch 'Loopback|vEthernet|Tailscale|VPN|Bluetooth' -and $_.IPAddress -notlike '169.*' } | Select-Object -First 1).IPAddress
Write-Host ""
Write-Host "Server Top Cash installato e avviato." -ForegroundColor Green
Write-Host "Apri  http://localhost:$Porta  su questo PC per creare l'account amministratore."
Write-Host "Dagli altri dispositivi in ufficio:  http://$($ip):$Porta"

# automatic updates: the server takes new versions from .\server-update by itself
$upd = Join-Path $PSScriptRoot 'aggiorna-server.ps1'
if (Test-Path $upd) { & $upd -Installa }
