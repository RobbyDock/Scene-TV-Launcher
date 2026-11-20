# Read-only. Reports HOME, returned packages, start time and memory.
param([string]$Device)
. "$PSScriptRoot\common.ps1"
$Device = Resolve-Device $Device
Connect-Device $Device

foreach ($u in Get-Users $Device) {
    Write-Host "--- user ${u}: HOME role holder"
    Invoke-Shell $Device "cmd role get-role-holders --user $u android.app.role.HOME"

    Write-Host "--- user ${u}: removed packages that came back (drift). Fix with lockdown.ps1 -Apply"
    $installed = Invoke-Shell $Device "pm list packages --user $u" | ForEach-Object { $_ -replace '^package:', '' }
    $drift = @($OptionalPackages | Where-Object { $installed -contains $_ })
    if ($drift.Count) { $drift | ForEach-Object { Write-Host "back: $_" } } else { Write-Host 'none' }
}

Write-Host '--- Cold start'
Invoke-Shell $Device "am force-stop $Pkg" | Out-Null
Invoke-Shell $Device "am start -W -n $HomeAct" | Select-String 'TotalTime|WaitTime|Status'

Write-Host '--- Memory (Scene)'
Invoke-Shell $Device "dumpsys meminfo $Pkg" | Select-String 'TOTAL PSS|TOTAL RSS|Java Heap|Native Heap'

Write-Host '--- Mic path'
Invoke-Shell $Device 'settings get secure voice_interaction_service'
