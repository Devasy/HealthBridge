param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
$package = 'com.repforge.healthbridge'
# Test the installed optimized preview, not just the unshrunk debug build.
& adb -s $Serial shell am force-stop $package
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the bridge before cold start.' }
& adb -s $Serial shell am start -W -n "$package/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw 'Bridge activity launch failed.' }
Start-Sleep -Seconds 3
$bridgePid = & adb -s $Serial shell pidof $package
if ($LASTEXITCODE -ne 0 -or -not $bridgePid) {
    throw 'Bridge exited during cold start. Inspect adb logcat -b crash -d.'
}
$resumed = $false
for ($attempt = 0; $attempt -lt 4; $attempt++) {
    $activityState = & adb -s $Serial shell dumpsys activity activities
    $resumed = [bool]($activityState -match '(?:mResumedActivity:|ResumedActivity:|topResumedActivity=).*com\.repforge\.healthbridge/\.MainActivity')
    if ($resumed) { break }
    Start-Sleep -Seconds 2
}
if (-not $resumed) {
    throw 'Bridge is running but its dashboard is not the resumed activity. Unlock the phone and retry.'
}
Write-Output "Cold start passed: bridge process $bridgePid is alive and MainActivity is resumed."
