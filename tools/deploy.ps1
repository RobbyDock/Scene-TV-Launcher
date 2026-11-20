# Installs the APK over the existing app (install -r only) and optionally sets Scene as HOME.
param(
    [string]$Device,
    [string]$Apk,
    [switch]$SetHome
)
. "$PSScriptRoot\common.ps1"
$Device = Resolve-Device $Device
Connect-Device $Device

if (-not $Apk) {
    $Apk = Join-Path $env:LOCALAPPDATA 'GradleBuilds\Scene\app\outputs\apk\release\app-release.apk'
}
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

Invoke-Adb $Device @('install', '-r', $Apk)
# Compiles the app ahead of time.
Invoke-Shell $Device "cmd package compile -m speed -f $Pkg" | Out-Null

if ($SetHome) {
    foreach ($u in Get-Users $Device) {
        # Installs the app for each user before setting HOME.
        Invoke-Shell $Device "cmd package install-existing --user $u $Pkg" | Out-Null
        Invoke-Shell $Device "cmd package set-home-activity --user $u $HomeAct"
        Write-Host "Launcher set as HOME for user $u."
    }
}
Write-Host 'Deployed.'
