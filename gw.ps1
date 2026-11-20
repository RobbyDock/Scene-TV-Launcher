# Runs gradlew.bat with Gradle and Kotlin caches under LOCALAPPDATA.
# Usage: .\gw.ps1 :app:assembleDebug
$extraArgs = @()
if ($env:LOCALAPPDATA) {
    $projectCacheDir = Join-Path $env:LOCALAPPDATA "GradleProjectCache\Scene"
    $kotlinCacheDir = Join-Path $env:LOCALAPPDATA "KotlinCache\Scene"
    $extraArgs = @("--project-cache-dir", $projectCacheDir, "-Pkotlin.project.persistent.dir=$kotlinCacheDir")
}

& "$PSScriptRoot\gradlew.bat" @extraArgs @args
exit $LASTEXITCODE
