# Top Cash Studio - versioni del progetto (Git portatile in .tools\git, tutto dentro D:\TopCashSQL)
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\versioni.ps1 -Salva "descrizione della modifica"
#   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\versioni.ps1              (storico e modifiche in sospeso)
# Database, backup, chiavi e programmi compilati NON vengono mai salvati nello storico (vedi .gitignore).
param([string]$Salva)
$git = Join-Path $PSScriptRoot '.tools\git\cmd\git.exe'
if (-not (Test-Path $git)) { Write-Host "Git portatile non trovato in .tools\git" -ForegroundColor Red; exit 1 }
$repo = $PSScriptRoot
if ($Salva) {
  & $git -C $repo add -A
  $pending = @(& $git -C $repo diff --cached --name-only)
  if ($pending.Count -eq 0) { Write-Host "Nessuna modifica da salvare." -ForegroundColor Yellow; exit 0 }
  & $git -C $repo commit -q -m $Salva
  Write-Host "Versione salvata ($($pending.Count) file): $Salva" -ForegroundColor Green
}
Write-Host "`nUltime versioni:" -ForegroundColor Cyan
& $git -C $repo log --date=format:'%d/%m/%Y %H:%M' --pretty=format:'  %h  %ad  %s' -15
$status = @(& $git -C $repo status --short)
Write-Host "`n`nModifiche non ancora salvate: $($status.Count)" -ForegroundColor Cyan
$status | Select-Object -First 20 | ForEach-Object { "  $_" }
