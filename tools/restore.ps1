# Pushes a backup file to the device. Restore Settings in Scene reads it.
# Open Scene once before running this, so Android creates Scene's own files folder.
param([string]$Device, [string]$In = '.\scene-backup.json')
. "$PSScriptRoot\common.ps1"
$Device = Resolve-Device $Device
Connect-Device $Device
if (-not (Test-Path $In)) { throw "File not found: $In" }
$dir = "/sdcard/Android/data/$Pkg/files"
Invoke-Adb $Device @('push', $In, "$dir/scene-backup.json")
# Files pushed by adb belong to the shell user. Make the folder and file readable by Scene.
Invoke-Adb $Device @('shell', "chmod 755 $dir 2>/dev/null; chmod 644 $dir/scene-backup.json")
