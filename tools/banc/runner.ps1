<#
  Lanceur UNIQUE du banc central Lanterne : prend UN job a la fois dans C:\bench\queue, lance UN client
  Minecraft (javaw.exe, sans console), le met au premier plan, mesure/teste, le ferme, ecrit les resultats.
  Demarrage (cache) par le coordinateur :
    Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','C:\bench\runner.ps1' -WindowStyle Hidden
  Options : -Once (un seul job puis sortie), -IdleExitMinutes N (sortie si file vide depuis N min ; 0 = jamais).
  Arret propre : creer le fichier C:\bench\STOP.
#>
param([switch]$Once, [int]$IdleExitMinutes = 0)
$ErrorActionPreference = 'Continue'
. C:\bench\lib.ps1

$mutex = New-Object System.Threading.Mutex($false, 'Global\LanterneBenchRunner')
if (-not $mutex.WaitOne(0)) { Write-Output 'Un runner tourne deja (mutex Global\LanterneBenchRunner).'; exit 5 }
$B = 'C:\bench'
foreach ($d in 'queue', 'done', 'results', 'run', 'payload') { New-Item -ItemType Directory -Force "$B\$d" | Out-Null }
$logf = "$B\runner.log"
function Log([string]$m) { Add-Content -Path $logf -Value ("{0} {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
function Set-State($o) { $o | ConvertTo-Json -Depth 4 | Set-Content "$B\state.json" -Encoding UTF8 }

function Get-LegacyLock([string]$owner) {
  # Le runner est le SEUL lanceur : il ne se bat plus pour C:\bench-lock a chaque tentative de 15 s.
  # 1) il pose C:\bench\WANT (les agents qui respectent le protocole n'ouvrent alors plus de client)
  # 2) il attend que le detenteur manuel actuel ait fini (plafond 20 min, puis verrou perime), puis il PREND le verrou.
  Set-Content 'C:\bench\WANT' "runner $(Get-Date -Format 'HH:mm:ss')"
  $t0 = Get-Date
  while (((Get-Date) - $t0).TotalMinutes -lt 20) {
    if (Test-Path 'C:\bench-lock') {
      $age = ((Get-Date) - (Get-Item 'C:\bench-lock').CreationTime).TotalMinutes
      $own = Get-Content 'C:\bench-lock\owner.txt' -ErrorAction SilentlyContinue
      $alive = @(Get-OtherClients).Count -gt 0
      if ($age -gt 6 -and -not $alive) { Log "verrou manuel perime ($own, $([int]$age) min, aucun client) : repris"; Remove-Item 'C:\bench-lock' -Recurse -Force -ErrorAction SilentlyContinue }
      elseif ($age -gt 15) { Log "verrou manuel > 15 min ($own) : repris"; Remove-Item 'C:\bench-lock' -Recurse -Force -ErrorAction SilentlyContinue }
    }
    try { New-Item -ItemType Directory 'C:\bench-lock' -ErrorAction Stop | Out-Null; Set-Content 'C:\bench-lock\owner.txt' "$owner $(Get-Date -Format 'HH:mm:ss')"; Remove-Item 'C:\bench\WANT' -ErrorAction SilentlyContinue; return $true } catch { Start-Sleep 2 }
  }
  Remove-Item 'C:\bench\WANT' -ErrorAction SilentlyContinue
  $false
}
function Release-LegacyLock { if ((Get-Content 'C:\bench-lock\owner.txt' -ErrorAction SilentlyContinue) -match '^bench-runner') { Remove-Item 'C:\bench-lock' -Recurse -Force -ErrorAction SilentlyContinue } }

function Build-Run($job, [string]$run) {
  if (Test-Path $run) { Remove-Item $run -Recurse -Force }
  New-Item -ItemType Directory -Force $run | Out-Null
  Copy-Item "$B\template\config" "$run\config" -Recurse
  Copy-Item "$B\template\shaderpacks" "$run\shaderpacks" -Recurse
  Copy-Item "$B\template\saves" "$run\saves" -Recurse
  Copy-Item "$B\template\options.txt" "$run\options.txt"
  New-Item -ItemType Directory -Force "$run\mods", "$run\logs" | Out-Null
  # monde fige : heure demandee (defaut 12500 = coucher de soleil), meteo claire, cycles coupes
  $java = "$script:JDK_RTX\bin\java.exe"
  & $java "$B\tools\Freeze.java" "$run\saves\Bench" $job.time 2>&1 | Out-Null
  # mods
  Copy-Item "$B\payload\$($job.id)\*.jar" "$run\mods\"
  $so = 'sodium-neoforge-0.9.2+mc26.3.jar'; $dh = 'DistantHorizons-3.3.0-26.3-fabric-neoforge.jar'
  switch ($job.preset) {
    'lanterne_sodium' { }
    'lanterne_only' { }
    'full' { Get-ChildItem "$B\template\modstore" -Filter '*.jar' | Where-Object { $_.Name -ne $so -and $_.Name -ne $dh } | ForEach-Object { Copy-Item $_.FullName "$run\mods\" } }
  }
  if ($job.preset -ne 'lanterne_only' -and -not $job.no_sodium) { Copy-Item "$B\template\modstore\$so" "$run\mods\" }
  if ($job.dh -eq 'on') { Copy-Item "$B\template\modstore\$dh" "$run\mods\" }
  # Iris
  $ip = "$run\config\iris.properties"
  Set-IrisProp $ip 'enableShaders' $(if ($job.shaders -eq 'on') { 'true' } else { 'false' })
  Set-IrisProp $ip 'shaderPack' $job.shaderpack
  if ($job.shadow_distance -gt 0) { Set-IrisProp $ip 'maxShadowRenderDistance' "$($job.shadow_distance)" }
  # options
  Set-OptionsTxt "$run\options.txt" @{
    'renderDistance' = "$($job.render_distance)"; 'simulationDistance' = "$($job.render_distance)"
    'inactivityFpsLimit' = '"minimized"'; 'maxFps' = "$($job.max_fps)"; 'enableVsync' = 'false'
    'preferredGraphicsBackend' = "`"$($job.backend)`""; 'fullscreen' = 'false'
  }
}

function Build-Args($job, [string]$run) {
  $tpl = (Get-Content "$B\template\launch.tpl" -Raw)
  $line = ($tpl -split "`r?`n" | Where-Object { $_ -match 'net\.neoforged\.fml\.startup\.Client' } | Select-Object -First 1)
  $line = $line -replace '^.*?\.exe"?\s+', ''                                   # retire le chemin de java
  $line = $line.Replace('C:\perfrun5\libraries', 'C:\bench\template\libraries').Replace('C:/perfrun5/libraries', 'C:/bench/template/libraries')
  $line = $line -replace '--gameDir\s+\S+', ("--gameDir " + ($run -replace '\\', '/'))
  $line = $line -replace '--username\s+\S+', ("--username B" + ([Math]::Abs($job.id.GetHashCode()) % 100000))
  $line = $line -replace '--width\s+\d+\s+--height\s+\d+', ''
  $w, $h = $job.res -split 'x'
  $line = $line.Trim() + " --width $w --height $h"
  $jvm = @("-Xmx3G")
  if ($job.jvm_args) { $jvm += $job.jvm_args }
  if ($job.lanterne_bench) { $jvm += '-Dlanterne.bench=true'; $jvm += "-Dlanterne.bench.time=$($job.time)"; $jvm += "-Dlanterne.bench.exit=true" }
  ($jvm -join ' ') + ' ' + $line
}

function Run-One($job, [int]$attempt) {
  $run = "$B\run\$($job.id)"
  $res = [ordered]@{ id = $job.id; name = $job.name; attempt = $attempt; mode = $job.mode; gpu_wanted = $job.gpu; jar = $job.jar; jar_sha256 = $job.jar_sha256
    preset = $job.preset; no_sodium = $job.no_sodium; shaders = $job.shaders; dh = $job.dh; res = $job.res; render_distance = $job.render_distance; time_frozen = $job.time
    inactivityFpsLimit = 'minimized'; sous_file = 'oui'; started = (Get-Date -Format 's'); status = 'running'; note = $job.note }
  Build-Run $job $run
  $javaw = Get-Javaw $job.gpu
  $res.javaw = $javaw
  # le verrou legacy est pris AVANT tout, y compris en mode functional : un client de plus fausserait les mesures des autres
  $lock = Get-LegacyLock 'bench-runner'; $res.verrou_legacy = $lock
  # silence : pas d'autre client, CPU calme (jusqu'a 8 min, puis on note la contamination)
  $quiet = $false
  for ($q = 0; $q -lt 32; $q++) { $oc = @(Get-OtherClients); $cpu = Get-CpuLoad; if ($oc.Count -eq 0 -and $cpu -lt 30) { $quiet = $true; break }; Start-Sleep 15 }
  $res.silence = $quiet; $res.autres_clients = @(Get-OtherClients).Count; $res.cpu_avant = Get-CpuLoad
  try {
    $argl = Build-Args $job $run
    Set-Content "$run\launch-args.txt" "$javaw $argl"
    $t0 = Get-Date
    $p = Start-Process -FilePath $javaw -ArgumentList $argl -WorkingDirectory $run -RedirectStandardOutput "$run\stdout.log" -RedirectStandardError "$run\stderr.log" -PassThru
    $res.pid = $p.Id
    Set-State @{ job = $job.id; pid = $p.Id; phase = 'demarrage'; started = (Get-Date -Format 's') }
    $log = "$run\logs\latest.log"
    # entree en jeu
    $entered = $false; $died = $false
    for ($i = 0; $i -lt 150; $i++) {
      Start-Sleep 2
      if ($p.HasExited) { $died = $true; break }
      if ((Test-Path $log) -and (Select-String -Path $log -Pattern 'logged in with entity id|type=world|BENCH START' -Quiet -ErrorAction SilentlyContinue)) { $entered = $true; break }
    }
    $res.entered_world = $entered
    if ($entered) {
      Set-State @{ job = $job.id; pid = $p.Id; phase = 'en jeu'; started = $t0.ToString('s') }
      $tw = Get-Date
      if ($job.jfr) { $jcmd = "$script:JDK_RTX\bin\jcmd.exe"; & $jcmd $p.Id JFR.start name=bench settings=profile "filename=$run\bench.jfr" 2>&1 | Out-Null }
      $shotDone = $false
      $secs = if ($job.mode -eq 'functional') { [Math]::Max(10, $job.seconds) } else { $job.seconds }
      while (((Get-Date) - $tw).TotalSeconds -lt $secs) {
        if ($p.HasExited) { $died = $true; break }
        Focus-Window $p
        if ($job.screenshot -and -not $shotDone -and ((Get-Date) - $tw).TotalSeconds -gt [Math]::Min(20, $secs * 0.6)) {
          $shotDone = Save-Shot $p "$B\results\$($job.id).png"; $res.screenshot = if ($shotDone) { "$B\results\$($job.id).png" } else { $null }
        }
        Start-Sleep 3
      }
      if ($job.screenshot -and -not $shotDone -and -not $p.HasExited) { $res.screenshot = if (Save-Shot $p "$B\results\$($job.id).png") { "$B\results\$($job.id).png" } else { $null } }
      if ($job.jfr -and -not $p.HasExited) { & "$script:JDK_RTX\bin\jcmd.exe" $p.Id JFR.dump name=bench "filename=$run\bench.jfr" 2>&1 | Out-Null }
    }
    # fermeture propre (la Radiographie ecrit son rapport a la sortie du monde), puis force SEULEMENT ce PID
    if (-not $p.HasExited) {
      Set-State @{ job = $job.id; pid = $p.Id; phase = 'fermeture' }
      $p.CloseMainWindow() | Out-Null
      for ($i = 0; $i -lt 45; $i++) { Start-Sleep 2; if ($p.HasExited) { break } }
      if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; $res.arret_force = $true }
    }
    Start-Sleep 3
    $res.ended = (Get-Date -Format 's'); $res.wall_s = [int]((Get-Date) - $t0).TotalSeconds
    # resultats
    $gpu = Get-GpuFromLog $log; $res.gpu_log = $gpu; $res.gpu_ok = Test-GpuMatch $job.gpu $gpu
    $rep = Get-ChildItem "$run\config\lanterne\profil-*.txt" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($rep) { Copy-Item $rep.FullName "$B\results\$($job.id).radiographie.txt" -Force; $res.radiographie = Parse-Radiographie $rep.FullName }
    $res.bilan = Parse-Bilan $log
    if (Test-Path "$run\bench.jfr") { Copy-Item "$run\bench.jfr" "$B\results\$($job.id).jfr" -Force; $res.jfr = "$B\results\$($job.id).jfr"; $res.jfr_top = Summarize-Jfr "$run\bench.jfr" "$B\results\$($job.id).jfr-top.txt" }
    Copy-Item $log "$B\results\$($job.id).latest.log" -Force -ErrorAction SilentlyContinue
    $crash = Get-ChildItem "$run\crash-reports\crash-*.txt" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($crash) { Copy-Item $crash.FullName "$B\results\$($job.id).crash.txt" -Force; $res.crash = "$B\results\$($job.id).crash.txt"
      $res.crash_resume = (Get-Content $crash.FullName -TotalCount 30 | Select-String -Pattern 'Description:|Exception|Error|Caused by' | Select-Object -First 4 | ForEach-Object { $_.Line.Trim() }) }
    if ($log -and (Test-Path $log)) { $res.erreurs_log = @(Select-String -Path $log -Pattern '/(ERROR|FATAL)\]' -ErrorAction SilentlyContinue).Count
      $res.iris_lignes = @(Select-String -Path $log -Pattern '\[Iris\]|Iris/|Shader' -ErrorAction SilentlyContinue | Select-Object -First 8 | ForEach-Object { $_.Line.Trim() }) }
    if ($died -and -not $entered) { $res.status = 'CRASH_AU_DEMARRAGE' }
    elseif (-not $entered) { $res.status = 'JAMAIS_ENTRE_EN_JEU' }
    elseif ($crash -and $crash.LastWriteTime -gt $t0) { $res.status = 'CRASH_EN_JEU' }
    elseif (-not $res.gpu_ok) { $res.status = 'INVALIDE_GPU' }
    elseif ($job.mode -eq 'bench' -and -not $res.radiographie.fps_avg -and -not $res.bilan.perf) { $res.status = 'SANS_MESURE' }
    else { $res.status = 'OK' }
  } finally {
    if ($lock) { Release-LegacyLock }
  }
  $res
}

function Finish-Job($job, $res) {
  $id = $job.id
  ($res | ConvertTo-Json -Depth 6) | Set-Content "$B\results\$id.json" -Encoding UTF8
  $r = $res.radiographie; $bp = $res.bilan.perf
  $fps = if ($bp -and $bp.fps) { $bp.fps } elseif ($r) { $r.fps_avg } else { $null }
  $ms = if ($bp -and $bp.ms_mean) { $bp.ms_mean } elseif ($r) { $r.ms_avg } else { $null }
  $low = if ($bp -and $bp.low1) { $bp.low1 } else { 'n/a' }
  $alloc = if ($res.jfr_top) { ($res.jfr_top | Select-Object -First 1) } else { '' }
  $txt = @(
    "JOB $id  [$($res.status)]", ('=' * 70),
    "nom            : $($job.name)   mode : $($job.mode)   tentative : $($res.attempt)",
    "jar            : $($job.jar)  sha256 $($job.jar_sha256.Substring(0,12))...",
    "mods           : preset=$($job.preset) sans_sodium=$($job.no_sodium) dh=$($job.dh) extra=$($job.extra_mods -join ',')",
    "shaders        : $($job.shaders) ($($job.shaderpack)) ombre=$($job.shadow_distance) rd=$($job.render_distance) res=$($job.res) backend=$($job.backend)",
    "GPU demande    : $($job.gpu)   GPU log : $($res.gpu_log)   ok : $($res.gpu_ok)",
    "heure figee    : $($job.time) ticks   inactivityFpsLimit : minimized   sous file : oui   silence : $($res.silence) (autres clients : $($res.autres_clients), CPU avant : $($res.cpu_avant) %)",
    "FPS moyen      : $fps   ms/image : $ms   1 % low : $low",
    "fenetres 1 s   : min $($r.fps_min_window) / max $($r.fps_max_window)    duree observee : $($r.observed_s) s",
    "histogramme    : $($r.histogram -join ' | ')",
    "BILAN perf     : $(if ($bp) { ($bp.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ' } else { 'aucun (jar sans [BILAN])' })",
    "BENCH RESULT   : $($res.bilan.bench_result)",
    "erreurs log    : $($res.erreurs_log)   crash : $($res.crash)   capture : $($res.screenshot)",
    "JFR allocations: $alloc",
    "duree totale   : $($res.wall_s) s   note : $($job.note)"
  )
  if ($res.crash_resume) { $txt += "crash (resume)  : " + ($res.crash_resume -join ' || ') }
  $txt | Set-Content "$B\results\$id.txt" -Encoding UTF8
  # ligne du tableau Suivi de OBJECTIFS-FPS.md (le coordinateur commite)
  if ($res.status -eq 'OK' -and $job.mode -eq 'bench') {
    $conf = "$($job.jar) $(if($job.no_sodium){'sans Sodium'}else{'+Sodium'}) sh=$($job.shaders) dh=$($job.dh) rd=$($job.render_distance) $($job.res)"
    $rem = "GPU=$($res.gpu_log); sous verrou : oui; inactivityFpsLimit=minimized; heure $($job.time) figee; silence=$($res.silence); banc central $id"
    $row = "| $(Get-Date -Format 'dd/MM HH:mm') | $($job.jar) | $conf | $fps | $low | $ms | $rem |"
    $ok = Add-SuiviRow $row; Log "suivi ajoute=$ok"
  }
  Move-Item "$B\queue\$id.json" "$B\done\$id.json" -Force
  Log "job $id -> $($res.status)"
}

Log "runner demarre (pid $PID)"
$idleSince = Get-Date
try {
  while ($true) {
    if (Test-Path "$B\STOP") { Log 'STOP demande'; Remove-Item "$B\STOP" -Force; break }
    $next = Get-ChildItem "$B\queue\*.json" -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -First 1
    if (-not $next) {
      Set-State @{ phase = 'inactif'; depuis = $idleSince.ToString('s') }
      if ($Once) { break }
      if ($IdleExitMinutes -gt 0 -and ((Get-Date) - $idleSince).TotalMinutes -gt $IdleExitMinutes) { Log 'inactif : sortie'; break }
      Start-Sleep 5; continue
    }
    $idleSince = Get-Date
    $job = Get-Content $next.FullName -Raw | ConvertFrom-Json
    Log "job $($job.id) ($($job.name)) mode=$($job.mode) gpu=$($job.gpu)"
    Set-State @{ job = $job.id; phase = 'preparation' }
    $res = $null
    try {
      $res = Run-One $job 1
      if ($res.status -eq 'INVALIDE_GPU') { Log "GPU invalide ($($res.gpu_log)) : nouvelle tentative"; Start-Sleep 5; $res = Run-One $job 2 }
    } catch { $res = [ordered]@{ id = $job.id; status = 'ERREUR_RUNNER'; erreur = $_.Exception.Message; attempt = 1 }; Log "ERREUR $($_.Exception.Message)" }
    Finish-Job $job $res
    Release-LegacyLock
    Set-State @{ phase = 'inactif'; dernier = $job.id; statut = $res.status }
    if ($Once) { break }
  }
} finally { $mutex.ReleaseMutex(); Log 'runner arrete' }
