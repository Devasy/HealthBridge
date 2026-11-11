param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$DebugKeystore = (Join-Path $env:USERPROFILE '.android/debug.keystore')
)
$ErrorActionPreference = 'Stop'
if (-not $AndroidSdk) { throw 'Set ANDROID_HOME or provide -AndroidSdk.' }
$signer = Join-Path $AndroidSdk 'build-tools/36.0.0/apksigner.bat'
$apk = Join-Path $PSScriptRoot '../app/build/outputs/apk/release/app-release-unsigned.apk'
$preview = Join-Path $PSScriptRoot '../app/build/outputs/apk/release/health-bridge-preview.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Run ./gradlew.bat :app:assembleRelease first.' }
if (-not (Test-Path -LiteralPath $DebugKeystore)) { throw 'Run ./gradlew.bat :app:assembleDebug to create the standard Android debug key.' }
# Standard Android debug credentials only. Never use this preview identity for publication.
& $signer sign --ks $DebugKeystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $preview $apk
if ($LASTEXITCODE -ne 0) { throw 'Preview signing failed.' }
& $signer verify --verbose $preview
if ($LASTEXITCODE -ne 0) { throw 'Preview signature verification failed.' }
Write-Output "Installable development preview: $preview"
