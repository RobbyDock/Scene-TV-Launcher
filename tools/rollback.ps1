# Brings removed packages back from the system image for every user profile. With -Package it
# restores one. Without it, it restores every optional package and makes the stock launcher HOME.
param(
    [string]$Device,
    [string]$Package
)
. "$PSScriptRoot\common.ps1"
$Device = Resolve-Device $Device
Connect-Device $Device

$list = if ($Package) { @($Package) } else { @($OptionalPackages) }
foreach ($u in Get-Users $Device) {
    Write-Host "--- user $u"
    foreach ($p in $list) {
        $r = Invoke-Shell $Device "cmd package install-existing --user $u $p"
        Invoke-Shell $Device "pm enable --user $u $p" | Out-Null
        Write-Host "$p : $r"
    }
    if (-not $Package) {
        Invoke-Shell $Device "cmd package set-home-activity --user $u com.google.android.apps.tv.launcherx/.home.HomeActivity"
        Write-Host "Stock launcher restored as HOME for user $u."
    }
}
