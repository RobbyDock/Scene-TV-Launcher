# Pushes a backup file to the device. Restore Settings in Scene reads it.
param([string]$Device, [string]$In = '.\scene-backup.json')
. "$PSScriptRoot\common.ps1"
$Device = Resolve-Device $Device
Connect-Device $Device
if (-not (Test-Path $In)) { throw "File not found: $In" }
Invoke-Adb $Device @('push', $In, "/sdcard/Android/data/$Pkg/files/scene-backup.json")
