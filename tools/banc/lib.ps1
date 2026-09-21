# Banc central Lanterne - fonctions communes (dot-source : . C:\bench\lib.ps1)
$script:B = 'C:\bench'
$script:JDK_RTX = 'C:\Users\trufa\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
$script:JDK_IGPU = 'C:\igpubench\jdk25'
$script:SUIVI = 'C:\Users\trufa\Documents\Lanterne\OBJECTIFS-FPS.md'

Add-Type -AssemblyName System.Drawing
if (-not ('BenchWin' -as [type])) {
Add-Type @"
using System; using System.Runtime.InteropServices;
public class BenchWin {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, int flags, int extra);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
"@
}

function Get-Sha256($p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLower() }

function Set-GpuPref([string]$exe, [int]$pref) {
  $k = 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences'
  if (-not (Test-Path $k)) { New-Item -Path $k -Force | Out-Null }
  Set-ItemProperty -Path $k -Name $exe -Value "GpuPreference=$pref;"
}

function Get-Javaw([string]$gpu) {
  if ($gpu -eq 'igpu') { $dir = $script:JDK_IGPU; $pref = 1 } else { $dir = $script:JDK_RTX; $pref = 2 }
  foreach ($e in 'java.exe', 'javaw.exe') { Set-GpuPref "$dir\bin\$e" $pref }
  "$dir\bin\javaw.exe"
}

function Get-OtherClients {
  # clients Minecraft qui ne sont pas lances par ce banc (contamination des mesures)
  Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match '--gameDir' -and $_.CommandLine -notmatch [regex]::Escape('C:\bench\run') -and $_.CommandLine -notmatch [regex]::Escape('C:/bench/run') }
}

function Get-CpuLoad { [int](Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average }

function Focus-Window($p) {
  $p.Refresh()
  if ($p.MainWindowHandle -ne 0) {
    [BenchWin]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
    [BenchWin]::keybd_event(0x12, 0, 0, 0); [BenchWin]::keybd_event(0x12, 0, 2, 0)   # touche ALT : autorise le changement de premier plan
    [BenchWin]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
  }
}

function Save-Shot($p, [string]$path) {
  $p.Refresh()
  if ($p.MainWindowHandle -eq 0) { return $false }
  $r = New-Object BenchWin+RECT
  [BenchWin]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
  $w = $r.R - $r.L; $h = $r.B - $r.T
  if ($w -lt 100 -or $h -lt 100) { return $false }
  $bmp = New-Object System.Drawing.Bitmap $w, $h
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
  $true
}

function Get-GpuFromLog([string]$log) {
  if (-not (Test-Path $log)) { return $null }
  $m = Select-String -Path $log -Pattern 'Using graphics device: (.+?) \(' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($m) { return $m.Matches[0].Groups[1].Value }
  $m = Select-String -Path $log -Pattern '\[BILAN\] type=boot.* gpu="?([^"]+?)"? gpu_vendor' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($m) { return $m.Matches[0].Groups[1].Value }
  $null
}

function Test-GpuMatch([string]$want, [string]$got) {
  if (-not $got) { return $false }
  if ($want -eq 'igpu') { return ($got -match 'AMD|Radeon|Intel') -and ($got -notmatch 'NVIDIA|GeForce') }
  $got -match 'NVIDIA|GeForce'
}


function Set-IrisProp([string]$file, [string]$key, [string]$value) {
  $lines = if (Test-Path $file) { @(Get-Content $file) } else { @() }
  $found = $false
  $out = foreach ($l in $lines) { if ($l -match "^$([regex]::Escape($key))=") { $found = $true; "$key=$value" } else { $l } }
  if (-not $found) { $out = @($out) + "$key=$value" }
  Set-Content -Path $file -Value $out
}

function Set-OptionsTxt([string]$file, [hashtable]$kv) {
  $lines = @(Get-Content $file)
  foreach ($k in $kv.Keys) {
    $hit = $false
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match "^$([regex]::Escape($k)):") { $lines[$i] = "${k}:$($kv[$k])"; $hit = $true } }
    if (-not $hit) { $lines += "${k}:$($kv[$k])" }
  }
  Set-Content -Path $file -Value $lines
}

function Parse-Radiographie([string]$file) {
  $o = @{}
  if (-not (Test-Path $file)) { return $o }
  $c = Get-Content $file
  $l = $c | Select-String '^FPS moyen : ([\d.]+) \(([\d.]+) ms' | Select-Object -First 1
  if ($l) { $o.fps_avg = [double]$l.Matches[0].Groups[1].Value; $o.ms_avg = [double]$l.Matches[0].Groups[2].Value }
  $l = $c | Select-String '^FPS min/max.*: ([\d.]+) / ([\d.]+)' | Select-Object -First 1
  if ($l) { $o.fps_min_window = [double]$l.Matches[0].Groups[1].Value; $o.fps_max_window = [double]$l.Matches[0].Groups[2].Value }
  $l = $c | Select-String '^Dur.e observ.e\s*: ([\d.]+) s' | Select-Object -First 1
  if ($l) { $o.observed_s = [double]$l.Matches[0].Groups[1].Value }
  $o.histogram = @($c | Select-String '^  (< 20|20-40|40-60|60-90|90-120|120\+)' | ForEach-Object { $_.Line.Trim() })
  $o
}

function Parse-Bilan([string]$log) {
  # dernieres lignes [BILAN] type=perf (moyenne des lignes) + boot
  $o = @{}
  if (-not (Test-Path $log)) { return $o }
  $lines = @(Select-String -Path $log -Pattern '\[BILAN\] type=perf' -ErrorAction SilentlyContinue | ForEach-Object { $_.Line })
  function kv($line, $k) { if ($line -match "(?:^|\s)$k=(\S+)") { $Matches[1] } else { $null } }
  if ($lines.Count -gt 0) {
    $vals = @{}
    foreach ($k in 'fps', 'low1', 'low01', 'ms_mean', 'ms_p99', 'cpu_ms_mean', 'gpu_ms_mean', 'heap_used_mb') {
      $xs = @($lines | ForEach-Object { kv $_ $k } | Where-Object { $_ -and $_ -ne 'n/a' } | ForEach-Object { [double]$_ })
      if ($xs.Count) { $vals[$k] = [math]::Round(($xs | Measure-Object -Average).Average, 2) }
    }
    $o.perf = $vals; $o.perf_lines = $lines.Count
  }
  $b = Select-String -Path $log -Pattern '\[BILAN\] type=boot' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($b) { $o.boot = $b.Line -replace '^.*\[BILAN\] ', '' }
  $r = Select-String -Path $log -Pattern 'BENCH RESULT' -ErrorAction SilentlyContinue | Select-Object -Last 1
  if ($r) { $o.bench_result = $r.Line -replace '^.*BENCH RESULT', 'BENCH RESULT' }
  $o
}

function Summarize-Jfr([string]$jfr, [string]$out) {
  # top des allocateurs du fil de rendu (best effort) via l'outil jfr du JDK
  $jfrExe = "$script:JDK_RTX\bin\jfr.exe"
  if (-not (Test-Path $jfr) -or -not (Test-Path $jfrExe)) { return $null }
  $txt = & $jfrExe print --events jdk.ObjectAllocationSample --stack-depth 6 $jfr 2>$null
  $agg = @{}; $total = 0.0
  $cur = $null
  foreach ($l in $txt) {
    if ($l -match '^jdk\.ObjectAllocationSample') { $cur = @{ w = 0.0; thr = ''; frames = @() }; continue }
    if (-not $cur) { continue }
    if ($l -match 'weight = ([\d.,]+) (bytes|kB|MB|GB)') {
      $n = [double]($Matches[1] -replace ',', '.'); $u = $Matches[2]
      $cur.w = switch ($u) { 'bytes' { $n } 'kB' { $n * 1e3 } 'MB' { $n * 1e6 } 'GB' { $n * 1e9 } }
    }
    elseif ($l -match 'eventThread = "([^"]+)"') { $cur.thr = $Matches[1] }
    elseif ($l -match '^\s+([\w.$<>]+\([^)]*\))\s+line:') { $cur.frames += $Matches[1] }
    elseif ($l -match '^\}') {
      if ($cur.thr -match 'Render thread') {
        $k = ($cur.frames | Where-Object { $_ -notmatch '^(java|jdk|sun)\.' } | Select-Object -First 1)
        if (-not $k) { $k = ($cur.frames | Select-Object -First 1) }
        if ($k) { $agg[$k] = [double]$agg[$k] + $cur.w; $total += $cur.w }
      }
      $cur = $null
    }
  }
  $top = $agg.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 15 | ForEach-Object { '{0,10:N1} Mo  {1}' -f ($_.Value / 1e6), $_.Key }
  $res = @("Allocations echantillonnees du Render thread : {0:N1} Mo au total (JFR ObjectAllocationSample)" -f ($total / 1e6)) + $top
  Set-Content -Path $out -Value $res
  $res
}

function Add-SuiviRow([string]$row) {
  $f = $script:SUIVI
  if (-not (Test-Path $f)) { return $false }
  $lines = [System.Collections.Generic.List[string]](Get-Content $f)
  $i = $lines.FindIndex({ param($x) $x -match '^## Suivi' })
  if ($i -lt 0) { return $false }
  $j = $i + 1
  while ($j -lt $lines.Count -and $lines[$j] -notmatch '^\|---') { $j++ }
  if ($j -ge $lines.Count) { return $false }
  $k = $j + 1
  while ($k -lt $lines.Count -and $lines[$k] -match '^\|') { $k++ }
  $lines.Insert($k, $row)
  [IO.File]::WriteAllText($f, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))
  $true
}
