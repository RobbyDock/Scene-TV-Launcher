# Removes the optional packages for every user profile. Dry run unless -Apply is given.
param(
    [string]$Device,
    [switch]$Apply
)
. "$PSScriptRoot\common.ps1"
$Device = Resolve-Device $Device
Connect-Device $Device

foreach ($u in Get-Users $Device) {
    Write-Host "--- user $u"
    $holder = (Invoke-Shell $Device "cmd role get-role-holders --user $u android.app.role.HOME") -join ''
    if ($Apply -and $holder.Trim() -ne $Pkg) { throw "The launcher is not HOME for user $u ($holder). Run deploy.ps1 -SetHome first." }

    $installed = Invoke-Shell $Device "pm list packages --user $u" | ForEach-Object { $_ -replace '^package:', '' }
    foreach ($p in $OptionalPackages) {
        if ($installed -notcontains $p) { Write-Host "already removed: $p"; continue }
        if (-not $Apply) { Write-Host "would remove: $p"; continue }
        $r = Invoke-Shell $Device "pm uninstall -k --user $u $p"
        Write-Host "$p : $r"
    }
}
if ($Apply) { Write-Host 'Done. Test the mic button, Settings and the Home key.' }
