# Depose les 4 jobs A/B de la specialisation d'etat neutre dans le banc central (alternes A B A B).
# Prerequis : les deux zips sont dans C:\bench\template\shaderpacks\ (copie de test locale, non redistribuee) :
#   ComplementaryReimagined_r5.9.3.zip et ComplementaryReimagined_r5.9.3_NEUTRAL_TESTONLY.zip
# Le pack "neutre" = l'original ou les 7 uniforms d'etat de lib/uniforms.glsl (isEyeInWater, blindness, darknessFactor,
# darknessLightFactor, nightVision, rainStrength, wetness) sont des constantes (0 / 0.0). Voir SHADER-ANALYSE-COMPLEMENTARY.md §6.
param([string]$Jar = 'C:\lanterne-jar\build\libs\lanterne-26.3.106.jar', [string]$Gpu = 'rtx')
$plan = @(
  @('A1_orig',   'ComplementaryReimagined_r5.9.3.zip'),
  @('B1_neutre', 'ComplementaryReimagined_r5.9.3_NEUTRAL_TESTONLY.zip'),
  @('A2_orig',   'ComplementaryReimagined_r5.9.3.zip'),
  @('B2_neutre', 'ComplementaryReimagined_r5.9.3_NEUTRAL_TESTONLY.zip')
)
foreach ($p in $plan) {
  & powershell -NoProfile -File C:\bench\submit.ps1 -Name ("shopt_" + $p[0]) -Jar $Jar -Mode bench -Seconds 60 `
    -Preset lanterne_sodium -Shaders on -ShaderPack $p[1] -Time 12500 -Gpu $Gpu `
    -Note 'A/B specialisation etat neutre - pack local de test, non redistribue'
}
