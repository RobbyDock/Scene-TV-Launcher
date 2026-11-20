# Shared helpers for the device scripts.
$ErrorActionPreference = 'Stop'

$script:Pkg = 'com.robbydock.scene'
$script:HomeAct = 'com.robbydock.scene/.HomeActivity'
$script:StockHome = 'com.google.android.apps.tv.launcherx'

function Get-Adb {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
    $adb
}

function Resolve-Device([string]$Device) {
    if (-not $Device) { $Device = $env:SCENE_DEVICE }
    if (-not $Device) {
        $f = Join-Path $PSScriptRoot 'device.local'
        if (Test-Path $f) { $Device = (Get-Content $f -Raw).Trim() }
    }
    if (-not $Device) { throw 'Pass -Device <ip:port>, set SCENE_DEVICE, or put it in tools/device.local' }
    $Device
}

function Connect-Device([string]$Device) {
    $adb = Get-Adb
    $state = & $adb -s $Device get-state 2>$null
    if ($state -ne 'device') {
        & $adb connect $Device | Out-Null
        $state = & $adb -s $Device get-state 2>$null
        if ($state -ne 'device') { throw "Cannot reach $Device. The wireless debugging port changes after a reboot." }
    }
}

# Runs an adb shell command on the chosen device and returns the output lines.
function Invoke-Shell([string]$Device, [string]$Command) {
    $adb = Get-Adb
    & $adb -s $Device shell $Command
}

# Every user profile on the device.
function Get-Users([string]$Device) {
    Invoke-Shell $Device 'pm list users' | ForEach-Object {
        if ($_ -match 'UserInfo\{(\d+):') { [int]$Matches[1] }
    }
}

function Invoke-Adb([string]$Device, [string[]]$AdbArgs) {
    $adb = Get-Adb
    & $adb -s $Device @AdbArgs
}

# Packages removed for user 0 (pm uninstall -k --user 0). The system copy stays on the device,
# so cmd package install-existing brings any of them back. The stock launcher and setupwraith
# are removed together.
$script:OptionalPackages = @(
    'com.google.android.apps.tv.launcherx',
    'com.google.android.tungsten.setupwraith',
    'com.netflix.ninja',
    'com.netflix.tokenmanager',
    'com.google.android.apps.mediashell',
    'com.android.settings.intelligence',
    'com.android.localtransport',
    'com.android.sharedstoragebackup',
    'com.android.backupconfirm',
    'com.android.dreams.basic',
    'com.android.mtp',
    'com.android.soundpicker',
    'com.android.providers.userdictionary',
    'com.android.hotspot2.osulogin',
    'com.android.cameraextensions',
    'com.android.dynsystem',
    'com.gretzky.hwinfo',
    'com.onn.updatenotification',
    'com.google.android.marvin.talkback',
    'com.google.android.apps.tv.dreamx',
    'com.google.android.youtube.tvmusic',
    'com.google.android.play.games',
    'com.google.android.feedback',
    'com.android.tv.feedbackconsent',
    'com.google.android.partnersetup',
    'com.android.adservices.api',
    'com.android.ondevicepersonalization.services',
    'com.android.federatedcompute.services',
    'com.google.android.syncadapters.calendar',
    'com.android.providers.calendar',
    'com.android.healthconnect.controller',
    'com.android.health.connect.backuprestore',
    'com.android.printspooler',
    'com.android.htmlviewer',
    'com.android.wallpaperbackup',
    'com.android.nearby.halfsheet'
)
