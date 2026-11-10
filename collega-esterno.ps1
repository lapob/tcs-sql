# Accesso da fuori ufficio con Cloudflare Tunnel (nessuna porta aperta sul router, il PC non e' visibile su Internet).
# Da eseguire sul PC server, in PowerShell "Esegui come amministratore", DOPO aver messo il dominio su Cloudflare:
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\collega-esterno.ps1                 (tutto in automatico per tcsql.it)
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\collega-esterno.ps1 -Dominio altro.it
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\collega-esterno.ps1 -Manuale        (incolli il token del tunnel creato a mano)
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\collega-esterno.ps1 -Rimuovi        (spegne l'accesso da fuori)
#
# In automatico chiede UNA cosa: un "API token" Cloudflare (vedi la guida, sezione "Accesso da fuori ufficio"), che
# viene usato solo per questa configurazione e non viene salvato. Poi crea o riusa il tunnel "topcash", punta
# tcsql.it e www.tcsql.it al server (porta del tunnel 8472, solo da questo PC), attiva HTTPS obbligatorio e TLS moderno,
# installa cloudflared come servizio e comunica l'indirizzo alle app: PC e telefoni lo imparano da soli in ufficio.
#Requires -RunAsAdministrator
param(
  [string]$Dominio = 'tcsql.it',
  [string]$Tunnel = 'topcash',
  [int]$PortaTunnel = 8472,
  [switch]$Manuale,
  [switch]$Rimuovi
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$dir = Join-Path $PSScriptRoot '.tools\cloudflared'
$exe = Join-Path $dir 'cloudflared.exe'
$pubFile = Join-Path $PSScriptRoot 'data\indirizzo-pubblico.txt'
$Dominio = $Dominio.Trim().ToLowerInvariant() -replace '^https?://', '' -replace '/.*$', ''
if ($Dominio -notmatch '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$') { throw "Dominio non valido: $Dominio" }

if ($Rimuovi) {
  if (Test-Path $exe) { & $exe service uninstall }
  if (Test-Path $pubFile) { Remove-Item $pubFile -Force }
  Write-Host "Accesso da fuori ufficio spento. In ufficio tutto funziona come prima (http://IP:8470)." -ForegroundColor Green
  Write-Host "Il tunnel e i record DNS restano su Cloudflare: se vuoi, eliminali dal sito (Zero Trust -> Networks -> Tunnels)."
  return
}

# 1. il server Top Cash deve rispondere sulla porta del tunnel (solo da questo PC)
try { $h = Invoke-RestMethod "http://127.0.0.1:$PortaTunnel/api/health" -TimeoutSec 5; Write-Host "Server Top Cash $($h.server) pronto sulla porta del tunnel $PortaTunnel." -ForegroundColor Green }
catch { throw "Il server Top Cash non risponde sulla porta del tunnel ($PortaTunnel). Esegui prima aggiorna-server.ps1 come amministratore." }

# 2. cloudflared dal repository ufficiale di Cloudflare, verificato prima dell'uso (firma digitale o impronta SHA-256 pubblicata)
function Install-Cloudflared {
  New-Item -ItemType Directory -Force $dir | Out-Null
  if (Test-Path $exe) { return }
  Write-Host "Scarico cloudflared dal repository ufficiale di Cloudflare..."
  $tmp = "$exe.download"
  Invoke-WebRequest 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile $tmp -UseBasicParsing
  $sig = Get-AuthenticodeSignature $tmp
  $ok = $sig.Status -eq 'Valid' -and $sig.SignerCertificate.Subject -match 'Cloudflare'
  if (-not $ok) {
    $rel = Invoke-RestMethod 'https://api.github.com/repos/cloudflare/cloudflared/releases/latest' -Headers @{ 'User-Agent' = 'TopCashStudio' }
    $hash = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
    $ok = $rel.body -match "cloudflared-windows-amd64\.exe:\s*$hash"
  }
  if (-not $ok) { Remove-Item $tmp -Force; throw "Il file scaricato non risulta firmato da Cloudflare: scartato per sicurezza." }
  Move-Item $tmp $exe -Force
}
Install-Cloudflared
& $exe --version

# 3. token del connettore: in automatico tramite le API Cloudflare, oppure incollato a mano
if ($Manuale) {
  Write-Host ""
  Write-Host "Incolla il token del tunnel (Cloudflare -> Zero Trust -> Networks -> Tunnels -> $Tunnel -> 'Install connector')." -ForegroundColor Cyan
  Write-Host "Puoi incollare anche tutto il comando mostrato da Cloudflare: prendo solo il token."
  Write-Host "Nel tunnel aggiungi i Public Hostname $Dominio e www.$Dominio con Service HTTP e URL localhost:$PortaTunnel."
  $raw = Read-Host 'Token'
  if ($raw -match '(eyJ[A-Za-z0-9_\-=\.]+)') { $connToken = $Matches[1] } else { throw "Token non riconosciuto: deve iniziare con eyJ" }
} else {
  Write-Host ""
  Write-Host "Serve un API token Cloudflare con questi permessi (guida, 'Accesso da fuori ufficio'):" -ForegroundColor Cyan
  Write-Host "  Account > Cloudflare Tunnel > Edit    Zone > DNS > Edit    Zone > Zone Settings > Edit    Zone > Zone > Read"
  Write-Host "Viene usato solo adesso e non viene salvato da nessuna parte."
  $sec = Read-Host 'API token' -AsSecureString
  $api = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)).Trim()
  if ($api.Length -lt 20) { throw "API token mancante." }
  $hdr = @{ Authorization = "Bearer $api" }
  function CF([string]$Method, [string]$Path, $Body = $null) {
    $p = @{ Method = $Method; Uri = "https://api.cloudflare.com/client/v4$Path"; Headers = $hdr; ContentType = 'application/json'; TimeoutSec = 30 }
    if ($null -ne $Body) { $p.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress)) }
    try { $r = Invoke-RestMethod @p } catch {
      $msg = $_.ErrorDetails.Message; try { $j = $msg | ConvertFrom-Json; $msg = ($j.errors | ForEach-Object { "$($_.code): $($_.message)" }) -join '; ' } catch {}
      throw "Cloudflare ha risposto con un errore ($Method $Path): $msg"
    }
    if (-not $r.success) { throw "Cloudflare: $(($r.errors | ForEach-Object { $_.message }) -join '; ')" }
    return $r.result
  }

  # dominio su Cloudflare
  $zone = @(CF GET "/zones?name=$Dominio") | Select-Object -First 1
  if (-not $zone) { throw "Il dominio $Dominio non e' nel tuo account Cloudflare (o il token non lo vede): aggiungilo prima su dash.cloudflare.com." }
  $acc = $zone.account.id
  if ($zone.status -ne 'active') {
    Write-Host "Attenzione: il dominio su Cloudflare e' ancora '$($zone.status)'. Dal registrar imposta questi nameserver:" -ForegroundColor Yellow
    $zone.name_servers | ForEach-Object { Write-Host "   $_" -ForegroundColor Yellow }
    Write-Host "Continuo lo stesso: l'indirizzo funzionera' appena Cloudflare attiva il dominio (di solito da pochi minuti a qualche ora)." -ForegroundColor Yellow
  }

  # tunnel: riusa quello esistente con lo stesso nome, altrimenti lo crea (configurato da Cloudflare, nessun file sul PC)
  $t = @(CF GET "/accounts/$acc/cfd_tunnel?name=$Tunnel&is_deleted=false") | Where-Object { $_.name -eq $Tunnel } | Select-Object -First 1
  if ($t) { Write-Host "Tunnel '$Tunnel' gia' presente: lo riuso." }
  else { $t = CF POST "/accounts/$acc/cfd_tunnel" @{ name = $Tunnel; config_src = 'cloudflare' }; Write-Host "Tunnel '$Tunnel' creato." -ForegroundColor Green }
  $tid = $t.id

  # cosa passa dal tunnel: solo il dominio e www, verso la porta del tunnel; tutto il resto 404
  $origin = @{ connectTimeout = 15; keepAliveTimeout = 90; noHappyEyeballs = $true }
  $ingress = @(
    @{ hostname = $Dominio; service = "http://localhost:$PortaTunnel"; originRequest = $origin },
    @{ hostname = "www.$Dominio"; service = "http://localhost:$PortaTunnel"; originRequest = $origin },
    @{ service = 'http_status:404' }
  )
  CF PUT "/accounts/$acc/cfd_tunnel/$tid/configurations" @{ config = @{ ingress = $ingress } } | Out-Null
  Write-Host "Instradamento: https://$Dominio e https://www.$Dominio -> server Top Cash (porta $PortaTunnel)." -ForegroundColor Green

  # DNS: CNAME (con proxy Cloudflare) verso il tunnel; se c'e' gia' un record diverso (es. parcheggio del registrar) chiede prima di sostituirlo
  $target = "$tid.cfargotunnel.com"
  foreach ($name in @($Dominio, "www.$Dominio")) {
    $recs = @(CF GET "/zones/$($zone.id)/dns_records?name=$name") | Where-Object { $_.type -in 'A', 'AAAA', 'CNAME' }
    if ($recs | Where-Object { $_.type -eq 'CNAME' -and $_.content -eq $target }) { Write-Host "DNS $name gia' corretto."; continue }
    if ($recs) {
      Write-Host "Su $name ci sono gia' questi record:" -ForegroundColor Yellow
      $recs | ForEach-Object { Write-Host "   $($_.type) $($_.content)" -ForegroundColor Yellow }
      if ((Read-Host "Li sostituisco con il tunnel Top Cash? (S/N)") -notmatch '^[sS]') { Write-Host "Lasciati com'erano: $name non puntera' al server."; continue }
      $recs | ForEach-Object { CF DELETE "/zones/$($zone.id)/dns_records/$($_.id)" | Out-Null }
    }
    CF POST "/zones/$($zone.id)/dns_records" @{ type = 'CNAME'; name = $name; content = $target; proxied = $true; ttl = 1; comment = 'Top Cash Studio (tunnel)' } | Out-Null
    Write-Host "DNS $name -> tunnel creato." -ForegroundColor Green
  }

  # sicurezza del dominio come fanno le aziende: solo HTTPS, TLS 1.2 minimo, TLS 1.3, riscrittura automatica dei link http
  foreach ($s in @(@('always_use_https', 'on'), @('min_tls_version', '1.2'), @('tls_1_3', 'on'), @('automatic_https_rewrites', 'on'))) {
    try { CF PATCH "/zones/$($zone.id)/settings/$($s[0])" @{ value = $s[1] } | Out-Null; Write-Host "Impostazione $($s[0]) = $($s[1])" }
    catch { Write-Host "Impostazione $($s[0]) non applicata ($($_.Exception.Message)): non e' indispensabile." -ForegroundColor Yellow }
  }

  $connToken = CF GET "/accounts/$acc/cfd_tunnel/$tid/token"
  $api = $null; $hdr = $null
}

# 4. connettore come servizio Windows (parte con il PC, si riconnette da solo)
if (Get-Service cloudflared -ErrorAction SilentlyContinue) { & $exe service uninstall; Start-Sleep 3 }
& $exe service install $connToken
$connToken = $null
Start-Sleep 6
Get-Service cloudflared | Format-Table Status, Name, DisplayName -AutoSize

# 5. le app imparano l'indirizzo pubblico dal server (in ufficio) e lo usano da sole fuori
Set-Content -Path $pubFile -Value $Dominio -Encoding ASCII
Write-Host "Indirizzo pubblico registrato sul server: https://$Dominio" -ForegroundColor Green

# 6. prova dall'esterno (Cloudflare puo' metterci un minuto)
$ok = $false
foreach ($i in 1..12) {
  try { Invoke-RestMethod "https://$Dominio/api/health" -TimeoutSec 15 | Out-Null; $ok = $true; break } catch { Start-Sleep 10 }
}
Write-Host ""
if ($ok) {
  Write-Host "Collegamento esterno attivo: https://$Dominio" -ForegroundColor Green
  Write-Host "In ufficio resta http://IP-del-server:8470. Le app (Windows e Android) imparano https://$Dominio al prossimo accesso in ufficio."
  Write-Host "Primo accesso da fuori di ogni dispositivo: compare un codice da autorizzare in Utenti -> Dispositivi."
} else {
  Write-Host "Il tunnel e' installato ma https://$Dominio non risponde ancora." -ForegroundColor Yellow
  Write-Host "Se il dominio e' appena stato acquistato, aspetta che Cloudflare lo attivi (nameserver) e riprova: Invoke-RestMethod https://$Dominio/api/health"
}
