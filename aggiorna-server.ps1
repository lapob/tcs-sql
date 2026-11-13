# Top Cash Studio - aggiorna il server installato con la versione in .\server-update (dati e utenti restano invariati).
#
#   Una volta sola, come amministratore (attiva l'aggiornamento automatico):
#     powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\aggiorna-server.ps1 -Installa
#   Aggiornare subito a mano, come amministratore:
#     powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\aggiorna-server.ps1
#
# Con -Installa viene creata l'attività pianificata "Top Cash Studio - Aggiornamento server" (account SYSTEM):
# ogni 10 minuti e all'avvio del PC controlla .\server-update e, solo se la versione è più nuova di quella in uso,
# ferma il servizio, copia i file, lo riavvia e verifica che risponda. Se non risponde rimette la versione precedente.
param([switch]$Installa, [switch]$Auto, [switch]$Rimuovi)
$ErrorActionPreference = 'Stop'
$task = 'Top Cash Studio - Aggiornamento server'
$new  = Join-Path $PSScriptRoot 'server-update'
$dst  = Join-Path $PSScriptRoot 'server-app'
$bak  = Join-Path $PSScriptRoot '.runtime\server-precedente'
$log  = Join-Path $PSScriptRoot '.runtime\aggiorna-server.log'
$bad  = Join-Path $PSScriptRoot '.runtime\server-scartata.txt'   # a version that did not start is not retried every 10 minutes
New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
# the log never stops an update (a file held open for a moment is retried, then skipped)
function Log($t) { $l = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $t"; if (-not $Auto) { Write-Host $t }
  for ($i = 0; $i -lt 5; $i++) { try { Add-Content -Path $log -Value $l -Encoding UTF8 -ErrorAction Stop; return } catch { Start-Sleep -Milliseconds 400 } } }
# the service is really gone before its files are replaced (a process still closing keeps them locked)
function StopServer { Stop-Service TopCashStudio -Force -ErrorAction SilentlyContinue
  for ($i = 0; $i -lt 30 -and (Get-Process TopCash.Server -ErrorAction SilentlyContinue); $i++) { Start-Sleep 1 }
  Get-Process TopCash.Server -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep 1 }
function CopyAll($from, $to) { for ($i = 0; $i -lt 3; $i++) { robocopy $from $to /MIR /NFL /NDL /NJH /NJS /R:5 /W:2 | Out-Null; if ($LASTEXITCODE -lt 8) { return $true }; Start-Sleep 3 }; return $false }
function Ver($dir) { $f = Join-Path $dir 'TopCash.Server.exe'; if (Test-Path $f) { try { [version]((Get-Item $f).VersionInfo.FileVersion) } catch { [version]'0.0' } } else { $null } }
function Healthy { for ($i = 0; $i -lt 15; $i++) { try { Invoke-RestMethod http://127.0.0.1:8470/api/health -TimeoutSec 5 | Out-Null; return $true } catch { Start-Sleep 2 } }; return $false }

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { Write-Host "Apri PowerShell con 'Esegui come amministratore' e rilancia lo script." -ForegroundColor Yellow; exit 1 }

if ($Rimuovi) { Unregister-ScheduledTask -TaskName $task -Confirm:$false -ErrorAction SilentlyContinue; Write-Host 'Aggiornamento automatico disattivato.' -ForegroundColor Green; exit 0 }

if ($Installa) {
  $act  = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Auto"
  $trig = @((New-ScheduledTaskTrigger -AtStartup), (New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 10)))
  $set  = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 15)
  Register-ScheduledTask -TaskName $task -Action $act -Trigger $trig -Settings $set -User 'SYSTEM' -RunLevel Highest -Force | Out-Null
  Write-Host "Aggiornamento automatico attivo: ogni 10 minuti il server prende da solo le nuove versioni." -ForegroundColor Green
  Write-Host "Registro: $log"
}

if ($Auto) { Set-Content -Path (Join-Path $PSScriptRoot '.runtime\aggiorna-server.ultimo') -Value (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') -Encoding UTF8 } # proof the scheduled task runs
$vNew = Ver $new; $vCur = Ver $dst
if (-not $vNew) { if (-not $Installa) { Log "Nessun aggiornamento in $new" }; exit 0 }
if ($vCur -and $vNew -le $vCur) { if (-not $Auto) { Log "Il server è già aggiornato ($vCur)." }; exit 0 }
if ($Auto -and (Test-Path $bad) -and (Get-Content $bad -Raw).Trim() -eq "$vNew") { exit 0 }

# one update at a time (the scheduled round, the server's own request and a manual run may meet)
$lock = New-Object System.Threading.Mutex($false, 'Global\TopCashStudioServerUpdate')
$mine = try { $lock.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $true }
if (-not $mine) { exit 0 }

# the new build must be complete: a folder still being copied changes between two looks 20 seconds apart
function Sign { $f = Get-ChildItem $new -Recurse -File; "$($f.Count)|$(($f | Measure-Object Length -Sum).Sum)|$(($f | Measure-Object LastWriteTimeUtc -Maximum).Maximum.Ticks)" }
$s1 = Sign; Start-Sleep 20; if ((Sign) -ne $s1) { $lock.ReleaseMutex(); exit 0 }

Log "Aggiorno il server da $vCur a $vNew"
if (Test-Path (Join-Path $dst 'TopCash.Server.exe')) { robocopy $dst $bak /MIR /NFL /NDL /NJH /NJS /R:2 /W:1 | Out-Null }
StopServer
$copied = CopyAll $new $dst
if ($copied) {
  Start-Service TopCashStudio -ErrorAction SilentlyContinue
  if (Healthy) { $now = Ver $dst; if ($now -eq $vNew) { Log "Server aggiornato a $vNew e in funzione."; exit 0 } }
  Log "Il server $vNew non risponde: rimetto la versione precedente ($vCur)."
} else { Log "Copia non riuscita anche dopo 3 tentativi: rimetto la versione precedente ($vCur)." }

Set-Content -Path $bad -Value "$vNew" -Encoding UTF8
if (Test-Path (Join-Path $bak 'TopCash.Server.exe')) {
  StopServer
  CopyAll $bak $dst | Out-Null
  Start-Service TopCashStudio -ErrorAction SilentlyContinue
  if (Healthy) { Log "Versione precedente $vCur ripristinata e in funzione." } else { Log "Anche la versione precedente non risponde: controlla 'Top Cash Studio Server' in services.msc." }
}
exit 1
