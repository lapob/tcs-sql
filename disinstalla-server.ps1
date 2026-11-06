# Rimuove il servizio Top Cash Studio Server e la regola firewall. I dati in .\data NON vengono toccati.
# Esegui come amministratore:  powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\disinstalla-server.ps1
$ErrorActionPreference = 'Stop'
if (Get-Service TopCashStudio -ErrorAction SilentlyContinue) { Stop-Service TopCashStudio -Force; sc.exe delete TopCashStudio | Out-Null }
Get-NetFirewallRule -DisplayName 'Top Cash Studio Server' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Write-Host "Servizio rimosso. I dati sono ancora in $PSScriptRoot\data." -ForegroundColor Green
