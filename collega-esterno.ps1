# Accesso da fuori ufficio con Cloudflare Tunnel (nessuna porta aperta sul router).
# Da eseguire UNA volta sul PC server, come amministratore, DOPO aver creato il tunnel su Cloudflare (vedi la guida, sezione "Accesso da fuori ufficio").
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\collega-esterno.ps1            (installa / aggiorna il collegamento)
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\collega-esterno.ps1 -Rimuovi   (spegne l'accesso da fuori)
# Il programma cloudflared resta in D:\TopCashSQL\.tools\cloudflared. Il token viene incollato da te e passato solo al servizio Cloudflare.
#Requires -RunAsAdministrator
param([switch]$Rimuovi)
$ErrorActionPreference = 'Stop'
$dir = Join-Path $PSScriptRoot '.tools\cloudflared'
$exe = Join-Path $dir 'cloudflared.exe'

if ($Rimuovi) {
  if (Test-Path $exe) { & $exe service uninstall }
  Write-Host "Accesso da fuori ufficio spento. In ufficio tutto funziona come prima." -ForegroundColor Green
  return
}

# 1. il server Top Cash deve essere aggiornato: ascolta il tunnel su 127.0.0.1:8472 (solo da questo PC)
try { Invoke-RestMethod http://127.0.0.1:8472/api/health -TimeoutSec 5 | Out-Null }
catch { throw "Il server Top Cash non risponde sulla porta del tunnel (8472). Esegui prima aggiorna-server.ps1 come amministratore." }

# 2. cloudflared dal sito ufficiale Cloudflare (GitHub), verificato prima dell'uso
New-Item -ItemType Directory -Force $dir | Out-Null
if (-not (Test-Path $exe)) {
  Write-Host "Scarico cloudflared dal repository ufficiale di Cloudflare..."
  $tmp = "$exe.download"
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile $tmp -UseBasicParsing
  $sig = Get-AuthenticodeSignature $tmp
  $ok = $sig.Status -eq 'Valid' -and $sig.SignerCertificate.Subject -match 'Cloudflare'
  if (-not $ok) {
    # in alternativa: impronta SHA-256 pubblicata nelle note di rilascio ufficiali
    $rel = Invoke-RestMethod 'https://api.github.com/repos/cloudflare/cloudflared/releases/latest' -Headers @{ 'User-Agent' = 'TopCashStudio' }
    $hash = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
    $ok = $rel.body -match "cloudflared-windows-amd64\.exe:\s*$hash"
  }
  if (-not $ok) { Remove-Item $tmp -Force; throw "Il file scaricato non risulta firmato da Cloudflare: scartato per sicurezza." }
  Move-Item $tmp $exe -Force
}
& $exe --version

# 3. collega il tunnel creato sul sito Cloudflare
Write-Host ""
Write-Host "Incolla il token del tunnel (Cloudflare -> Zero Trust -> Networks -> Tunnels -> topcash -> 'Install connector')." -ForegroundColor Cyan
Write-Host "Puoi incollare anche tutto il comando mostrato da Cloudflare: prendo solo il token."
$raw = Read-Host 'Token'
if ($raw -match '(eyJ[A-Za-z0-9_\-=\.]+)') { $token = $Matches[1] } else { throw "Token non riconosciuto: deve iniziare con eyJ" }
$svc = Get-Service cloudflared -ErrorAction SilentlyContinue
if ($svc) { & $exe service uninstall; Start-Sleep 2 }
& $exe service install $token
Start-Sleep 6
Get-Service cloudflared | Format-Table Status, Name, DisplayName -AutoSize

# 4. prova dall'esterno
$hostName = (Read-Host "Indirizzo pubblico che hai configurato (es. app.tuodominio.it)").Trim() -replace '^https?://', '' -replace '/.*$', ''
if ($hostName) {
  try {
    $h = Invoke-RestMethod "https://$hostName/api/health" -TimeoutSec 20
    Write-Host "Collegamento esterno attivo: https://$hostName" -ForegroundColor Green
    Write-Host "Ora apri l'indirizzo da un telefono fuori dal Wi-Fi: comparira' un codice da autorizzare in Utenti -> Dispositivi."
  } catch { Write-Host "Il tunnel e' installato ma https://$hostName non risponde ancora: controlla che il Public Hostname punti a http://localhost:8472 (puo' servire un minuto)." -ForegroundColor Yellow }
}
