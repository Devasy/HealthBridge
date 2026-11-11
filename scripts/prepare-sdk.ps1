param(
    [string]$SdkPath = (Join-Path $PSScriptRoot '../../1.1.0/libs/samsung-health-data-api-1.1.0.aar')
)
$ErrorActionPreference = 'Stop'
$expected = 'F5D3D83CF00B97D0BB1B1DB4DA076E861EB1C3E6E704D89A34E68909D2F38654'
$source = (Resolve-Path -LiteralPath $SdkPath).Path
if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $expected) {
    throw 'Unexpected Samsung SDK binary. This build was verified against Samsung Health Data SDK 1.1.0.'
}
$destination = Join-Path $PSScriptRoot '../app/libs'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Copy-Item -LiteralPath $source -Destination (Join-Path $destination 'samsung-health-data-api-1.1.0.aar')
Write-Output 'Samsung Health Data SDK 1.1.0 prepared locally. The proprietary AAR is excluded from Git.'
