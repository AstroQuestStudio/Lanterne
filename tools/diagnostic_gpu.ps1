# Releve ce que Windows a enregistre autour d'un plantage graphique.
#
# Pourquoi ce script existe
# -------------------------
# Un jeu tue par le pilote graphique ne laisse RIEN dans ses propres journaux : Java
# n'a pas la main pour ecrire. La seule trace est du cote de Windows. Chercher dans
# les logs du jeu un evenement qui n'a jamais pu y etre ecrit est la premiere fausse
# piste de ce genre de panne.
#
# Les sources qui comptent :
#   nvlddmkm       le pilote NVIDIA lui-meme
#   Display        le gestionnaire d'affichage, qui journalise les TDR
#   Kernel-Power   un arret brutal (evenement 41)
#   WHEA-Logger    une erreur materielle corrigee ou non
#   BugCheck       un ecran bleu

$heures = 8
$depuis = (Get-Date).AddHours(-$heures)

Write-Output "=== Evenements systeme des $heures dernieres heures ==="
$sources = 'nvlddmkm|Display|Kernel-Power|BugCheck|WHEA|amdkmdag|LiveKernelEvent'
try {
    $evenements = Get-WinEvent -FilterHashtable @{LogName = 'System'; StartTime = $depuis} -ErrorAction Stop |
        Where-Object { $_.ProviderName -match $sources }
    if ($evenements) {
        foreach ($e in $evenements | Select-Object -First 15) {
            $premiere = ($e.Message -split "`r?`n")[0]
            Write-Output ("{0}  [{1}]  id={2}" -f $e.TimeCreated, $e.ProviderName, $e.Id)
            Write-Output ("    {0}" -f $premiere)
        }
    } else {
        Write-Output "  aucun evenement de ces sources."
    }
} catch {
    Write-Output ("  lecture impossible : {0}" -f $_.Exception.Message)
}

Write-Output ""
Write-Output "=== Rapports d'erreur applicative (Application, id 1000/1001) ==="
try {
    Get-WinEvent -FilterHashtable @{LogName = 'Application'; StartTime = $depuis; Id = 1000, 1001} -ErrorAction Stop |
        Select-Object -First 8 |
        ForEach-Object {
            $premiere = ($_.Message -split "`r?`n")[0]
            Write-Output ("{0}  id={1}" -f $_.TimeCreated, $_.Id)
            Write-Output ("    {0}" -f $premiere)
        }
} catch {
    Write-Output "  aucun, ou lecture impossible."
}

Write-Output ""
Write-Output "=== Carte graphique et pilote ==="
Get-CimInstance Win32_VideoController |
    Select-Object Name, DriverVersion, DriverDate, AdapterRAM |
    Format-List

Write-Output "=== Reglage TDR (0 ou absent = defaut de 2 secondes) ==="
$cle = 'HKLM:\SYSTEM\CurrentControlSet\Control\GraphicsDrivers'
foreach ($nom in 'TdrDelay', 'TdrLevel', 'TdrDdiDelay') {
    $valeur = (Get-ItemProperty -Path $cle -Name $nom -ErrorAction SilentlyContinue).$nom
    if ($null -eq $valeur) { $valeur = '(absent)' }
    Write-Output ("  {0} = {1}" -f $nom, $valeur)
}
