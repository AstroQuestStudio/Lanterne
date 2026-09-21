<#
.SYNOPSIS
  Résume le bilan [BILAN] d'un journal de client Lanterne, sans ouvrir le jeu.

.DESCRIPTION
  Lit un fichier (logs\latest.log ou logs\lanterne-bilan.log) et affiche : la machine et le GPU
  (boot), les options et l'état d'Iris/Sodium/DH (world), un tableau des mesures de performance
  (perf), les phases les plus coûteuses, les évènements (gels, changements de shaderpack) et le
  résumé de session. Format : voir BILAN-FORMAT.md.

.PARAMETER Log
  Chemin du journal. Défaut : logs\latest.log dans le dossier courant.

.PARAMETER Json
  Sort les enregistrements en JSON au lieu du tableau lisible (pour un autre script).

.EXAMPLE
  .\tools\bilan.ps1 C:\lanterne-client-test\logs\latest.log
#>
param(
    [Parameter(Position = 0)][string]$Log = "logs\latest.log",
    [switch]$Json
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $Log)) { throw "Journal introuvable : $Log" }

$pair = [regex]'(\w+)=("(?:[^"\\]|\\.)*"|\S+)'
$records = New-Object System.Collections.Generic.List[object]
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path -LiteralPath $Log))) {
    $at = $line.IndexOf("[BILAN] type=")
    if ($at -lt 0) { continue }
    $rec = [ordered]@{}
    foreach ($m in $pair.Matches($line.Substring($at + 8))) {
        $v = $m.Groups[2].Value
        if ($v.StartsWith('"') -and $v.EndsWith('"') -and $v.Length -ge 2) {
            $v = $v.Substring(1, $v.Length - 2).Replace('\"', '"').Replace('\\', '\')
        }
        $rec[$m.Groups[1].Value] = $v
    }
    if ($rec.Contains("type")) { $records.Add([pscustomobject]$rec) }
}

if ($Json) { $records | ConvertTo-Json -Depth 4; return }
if ($records.Count -eq 0) {
    Write-Host "Aucune ligne [BILAN] dans $Log (jeu lancé avec -Dlanterne.bilan=off, ou version de Lanterne trop ancienne ?)"
    return
}

function Num($v) { $d = 0.0; if ([double]::TryParse("$v", [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$d)) { $d } else { $null } }
function Get($r, $k) { if ($r.PSObject.Properties.Name -contains $k) { $r.$k } else { $null } }

$boot = $records | Where-Object type -eq "boot" | Select-Object -Last 1
if ($boot) {
    Write-Host "== MACHINE ==" -ForegroundColor Cyan
    "Lanterne {0} | MC {1} | NeoForge {2}" -f $boot.lanterne, $boot.minecraft, $boot.neoforge
    "OS {0} | CPU {1} ({2} coeurs) | RAM {3} Mo | Java {4} | tas max {5} Mo | GC {6}" -f $boot.os, $boot.cpu, $boot.cores, $boot.ram_mb, $boot.java, $boot.heap_max_mb, $boot.gc
    "GPU : {0} [{1}] ({2}) | backend {3} | VRAM {4} Mo | GL {5}" -f $boot.gpu, $boot.gpu_kind, $boot.gpu_vendor, $boot.backend, $boot.vram_mb, $boot.gl_version
    "Debug GL : {0} | glsync={1} | mesure GPU : {2}" -f $boot.gl_debug, $boot.glsync_prop, $boot.gpu_time
    "Sodium {0} | Iris {1} | DH {2} | shaders {3} | shaderpack {4}" -f $boot.sodium, $boot.iris, $boot.dh, (Get $boot "shaders"), (Get $boot "shaderpack")
    "JVM : " + $boot.jvm_args
}

$world = $records | Where-Object type -eq "world" | Select-Object -Last 1
if ($world) {
    Write-Host "`n== OPTIONS (dernier monde) ==" -ForegroundColor Cyan
    "distance rendu {0} | simulation {1} | max fps {2} | vsync {3} | résolution {4} | nuages {5} | particules {6}" -f $world.render_distance, $world.simulation_distance, $world.max_fps, $world.vsync, $world.resolution, $world.clouds, $world.particles
    "shaders {0} | pack {1} | actif {2} | distance ombre {3} | DH mode {4}" -f (Get $world "shaders"), (Get $world "shaderpack"), (Get $world "shader_active"), (Get $world "shadow_distance"), (Get $world "dh_mode")
}

$perf = @($records | Where-Object type -eq "perf")
if ($perf.Count -gt 0) {
    Write-Host "`n== PERFORMANCES (une ligne par intervalle) ==" -ForegroundColor Cyan
    $perf | Select-Object @{n = "s"; e = { Num $_.seconds } }, @{n = "FPS"; e = { Num $_.fps } }, @{n = "1%low"; e = { Num $_.low1 } }, @{n = "0.1%low"; e = { Num $_.low01 } },
        @{n = "ms"; e = { $_.ms_mean } }, @{n = "p99"; e = { $_.ms_p99 } }, @{n = "cpu"; e = { $_.cpu_ms_mean } }, @{n = "gpu"; e = { $_.gpu_ms_mean } },
        @{n = "attente%"; e = { $_.limiter_share_pct } }, @{n = "valide"; e = { $_.valid } }, @{n = "sect"; e = { $_.sections_visible } }, @{n = "queue"; e = { $_.mesh_queue } }, @{n = "heapMo"; e = { $_.heap_used_mb } }, @{n = "gc_ms"; e = { $_.gc_ms } }, @{n = "gels"; e = { $_.spikes } } | Format-Table -AutoSize

    # Une mesure faite pendant que le jeu se bride lui-même (AFK, fenêtre sans focus, vsync, plafond de FPS)
    # ne dit rien du rendu : le bilan le signale, et ce script le répète en tête.
    $bad = @($perf | Where-Object { (Get $_ "valid") -eq "false" })
    if ($bad.Count -gt 0) {
        Write-Host ("`n*** AVERTISSEMENT : {0} intervalle(s) sur {1} : FPS NON REPRESENTATIFS, jeu bridé ***" -f $bad.Count, $perf.Count) -ForegroundColor Yellow
        $bad | ForEach-Object { "   - {0} s, {1} FPS mesurés, part d'attente du limiteur {2} %, ms utile {3} : {4}" -f $_.seconds, $_.fps, $_.limiter_share_pct, $_.ms_useful, $_.invalid_causes }
        Write-Host "   (limites : inactivity_fps_limit=$($boot.inactivity_fps_limit) ; refaire la mesure fenêtre au premier plan, sans vsync, sans plafond, sans rester inactif > 60 s)" -ForegroundColor Yellow
    }
    $frames = ($perf | ForEach-Object { Num $_.frames } | Measure-Object -Sum).Sum
    $wfps = 0.0; foreach ($p in $perf) { $wfps += (Num $p.fps) * (Num $p.frames) }
    if ($frames -gt 0) { "FPS moyen pondéré (toutes les mesures) : {0:N1} sur {1} images" -f ($wfps / $frames), $frames }
    $low = ($perf | ForEach-Object { Num $_.low1 } | Measure-Object -Minimum).Minimum
    "1 % low le plus bas : {0:N1}" -f $low

    $phases = @{}
    foreach ($p in $perf) {
        foreach ($prop in $p.PSObject.Properties) {
            if ($prop.Name -like "phase_*_ms") {
                $n = $prop.Name.Substring(6, $prop.Name.Length - 9)
                if (-not $phases.ContainsKey($n)) { $phases[$n] = @() }
                $phases[$n] += (Num $prop.Value)
            }
        }
    }
    if ($phases.Count -gt 0) {
        Write-Host "`nPhases les plus coûteuses (ms par image, moyenne) :" -ForegroundColor Cyan
        $phases.GetEnumerator() | ForEach-Object { [pscustomobject]@{ phase = $_.Key; ms = [math]::Round(($_.Value | Measure-Object -Average).Average, 3) } } | Sort-Object ms -Descending | Format-Table -AutoSize
    }
}

$events = @($records | Where-Object type -eq "event")
if ($events.Count -gt 0) {
    Write-Host "`n== EVENEMENTS ==" -ForegroundColor Cyan
    $spikes = @($events | Where-Object event -eq "spike")
    "{0} gel(s) d'image > 100 ms journalisé(s){1}" -f $spikes.Count, $(if ($spikes.Count) { " ; le pire : " + (($spikes | ForEach-Object { Num $_.ms } | Measure-Object -Maximum).Maximum) + " ms" } else { "" })
    $events | Where-Object event -ne "spike" | Format-Table event, shaderpack, shaders, shader_active, shadow_distance, to -AutoSize
}

$summary = $records | Where-Object type -eq "summary" | Select-Object -Last 1
if ($summary) {
    Write-Host "`n== SESSION ==" -ForegroundColor Cyan
    "{0} s | {1} images | {2} FPS | 1 % low {3} | 0,1 % low {4} | p99 {5} ms | max {6} ms | {7} gel(s) | GC {8} fois, {9} ms | fin : {10}" -f $summary.seconds, $summary.frames, $summary.fps, $summary.low1, $summary.low01, $summary.ms_p99, $summary.ms_max, $summary.spikes, $summary.gc_count, $summary.gc_ms, $summary.reason
}
