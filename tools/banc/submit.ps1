<#
  Depose un job dans la file du banc central Lanterne. Retourne l'identifiant du job.
  Exemple (mesure RTX, Lanterne 106 + Sodium, 60 s) :
    powershell -NoProfile -File C:\bench\submit.ps1 -Name l106_sodium -Jar C:\lanterne-jar\build\libs\lanterne-26.3.106.jar -Seconds 60
  Exemple (test fonctionnel sans Sodium, avec capture) :
    powershell -NoProfile -File C:\bench\submit.ps1 -Name integ230 -Jar C:\lanterne-wt-integ\build\libs\lanterne-26.3.230.jar -NoSodium -JvmArgs '-Dlanterne.irisvanilla=true' -Mode functional -Screenshot
#>
param(
  [Parameter(Mandatory)][string]$Name,
  [Parameter(Mandatory)][string]$Jar,
  [string[]]$ExtraMods = @(),
  [ValidateSet('lanterne_only', 'lanterne_sodium', 'full')][string]$Preset = 'lanterne_sodium',
  [switch]$NoSodium,
  [ValidateSet('on', 'off')][string]$Shaders = 'off',
  [string]$ShaderPack = 'ComplementaryReimagined_r5.9.zip',
  [int]$ShadowDistance = 0,
  [ValidateSet('on', 'off')][string]$Dh = 'off',
  [ValidateSet('rtx', 'igpu')][string]$Gpu = 'rtx',
  [int]$Time = 12500,
  [string]$Res = '1280x720',
  [int]$RenderDistance = 12,
  [int]$MaxFps = 260,
  [ValidateSet('opengl', 'vulkan')][string]$Backend = 'opengl',
  [string]$JvmArgs = '',
  [ValidateSet('bench', 'functional')][string]$Mode = 'bench',
  [int]$Seconds = 60,
  [switch]$Screenshot,
  [switch]$Jfr,
  [switch]$LanterneBench,          # utilise -Dlanterne.bench=true (mode banc de Lanterne, branche feat-bilan) au lieu de la seule Radiographie
  [string]$Note = ''
)
$ErrorActionPreference = 'Stop'
. C:\bench\lib.ps1
if (-not (Test-Path -LiteralPath $Jar)) { throw "Jar introuvable : $Jar" }
$id = '{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ($Name -replace '[^\w.-]', '_')
$pay = "C:\bench\payload\$id"
New-Item -ItemType Directory -Force $pay | Out-Null
Copy-Item -LiteralPath $Jar -Destination "$pay\$(Split-Path $Jar -Leaf)"
$extra = @()
foreach ($m in $ExtraMods) { if (-not (Test-Path -LiteralPath $m)) { throw "Mod introuvable : $m" }; Copy-Item -LiteralPath $m -Destination $pay; $extra += (Split-Path $m -Leaf) }
$job = [ordered]@{
  id = $id; name = $Name; status = 'queued'; submitted = (Get-Date -Format 's')
  jar = (Split-Path $Jar -Leaf); jar_src = $Jar; jar_sha256 = (Get-Sha256 $Jar); extra_mods = $extra
  preset = $Preset; no_sodium = [bool]$NoSodium; shaders = $Shaders; shaderpack = $ShaderPack; shadow_distance = $ShadowDistance
  dh = $Dh; gpu = $Gpu; time = $Time; res = $Res; render_distance = $RenderDistance; max_fps = $MaxFps; backend = $Backend
  jvm_args = $JvmArgs; mode = $Mode; seconds = $Seconds; screenshot = [bool]$Screenshot; jfr = [bool]$Jfr; lanterne_bench = [bool]$LanterneBench; note = $Note
}
$job | ConvertTo-Json -Depth 5 | Set-Content -Path "C:\bench\queue\$id.json" -Encoding UTF8
Write-Output $id
