# Pulls the launcher backup file to this PC. Export Settings in Scene creates it.
param([string]$Device, [string]$Out = '.\scene-backup.json')
. "$PSScriptRoot\common.ps1"
$Device = Resolve-Device $Device
Connect-Device $Device
Invoke-Adb $Device @('pull', "/sdcard/Android/data/$Pkg/files/scene-backup.json", $Out)
