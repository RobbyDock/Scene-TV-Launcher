# Read-only. Saves device state to snapshots/<timestamp>/.
param([string]$Device)
. "$PSScriptRoot\common.ps1"
$Device = Resolve-Device $Device
Connect-Device $Device

$dir = Join-Path (Split-Path $PSScriptRoot -Parent) ("snapshots\" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $dir | Out-Null

Invoke-Shell $Device 'pm list packages -d --user 0' | Set-Content "$dir\disabled.txt" -Encoding utf8
Invoke-Shell $Device 'pm list packages -s' | Set-Content "$dir\system.txt" -Encoding utf8
Invoke-Shell $Device 'pm list packages -3' | Set-Content "$dir\user.txt" -Encoding utf8
Invoke-Shell $Device 'cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME' | Set-Content "$dir\home-activities.txt" -Encoding utf8
Invoke-Shell $Device 'cmd role get-role-holders --user 0 android.app.role.HOME' | Set-Content "$dir\home-role.txt" -Encoding utf8
Invoke-Shell $Device 'dumpsys meminfo' | Set-Content "$dir\meminfo.txt" -Encoding utf8

$keys = 'assistant', 'voice_interaction_service', 'voice_recognition_service', 'screensaver_enabled', 'screensaver_components'
$lines = foreach ($k in $keys) { "secure.$k=" + (Invoke-Shell $Device "settings get secure $k") }
$lines += 'global.private_dns_mode=' + (Invoke-Shell $Device 'settings get global private_dns_mode')
$lines += 'global.private_dns_specifier=' + (Invoke-Shell $Device 'settings get global private_dns_specifier')
$lines | Set-Content "$dir\settings.txt" -Encoding utf8

Write-Host "Snapshot saved to $dir"
