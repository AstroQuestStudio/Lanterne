<# Etat du banc central : file d'attente, job actif, derniers resultats. #>
param([int]$Last = 8)
$B = 'C:\bench'
$rn = Get-Process -Name powershell, pwsh -ErrorAction SilentlyContinue | Where-Object { (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine -match 'bench\\runner\.ps1' }
"Runner : " + $(if ($rn) { "actif (pid $($rn.Id -join ','))" } else { 'ARRETE' })
if (Test-Path "$B\state.json") { "Etat   : " + ((Get-Content "$B\state.json" -Raw) -replace '\s+', ' ') }
"File   :"
$q = @(Get-ChildItem "$B\queue\*.json" -ErrorAction SilentlyContinue | Sort-Object Name)
if ($q.Count -eq 0) { '  (vide)' } else { $q | ForEach-Object { $j = Get-Content $_.FullName -Raw | ConvertFrom-Json; "  {0}  {1}  mode={2} gpu={3} jar={4}" -f $j.id, $j.name, $j.mode, $j.gpu, $j.jar } }
"Derniers resultats :"
Get-ChildItem "$B\results\*.json" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First $Last | ForEach-Object {
  $r = Get-Content $_.FullName -Raw | ConvertFrom-Json
  $fps = if ($r.bilan.perf.fps) { $r.bilan.perf.fps } elseif ($r.radiographie.fps_avg) { $r.radiographie.fps_avg } else { 'n/a' }
  "  {0}  [{1}]  fps={2}  gpu={3}" -f $r.id, $r.status, $fps, $r.gpu_log
}
"Lire un resultat : Get-Content C:\bench\results\<id>.txt   (details : <id>.json, .png, .latest.log, .jfr-top.txt)"
