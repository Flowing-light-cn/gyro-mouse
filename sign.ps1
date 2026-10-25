$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'
$sdk = Join-Path $root '.tools\sdk'
$apksigner = Join-Path $sdk 'build-tools\34.0.0\apksigner.bat'
$keystore = Join-Path $root '.tools\debug.keystore'
$outDir = Join-Path $root 'app\build\outputs\apk\release'
$unsigned = Join-Path $outDir 'app-release-unsigned.apk'
$signed = Join-Path $outDir 'app-release.apk'

foreach ($p in @($apksigner, $keystore, $unsigned)) {
    if (-not (Test-Path -LiteralPath $p)) {
        throw "Missing file: $p"
    }
}

& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out $signed $unsigned
if ($LASTEXITCODE -ne 0) {
    throw "apksigner sign failed with exit code $LASTEXITCODE"
}

& $apksigner verify --print-certs $signed
if ($LASTEXITCODE -ne 0) {
    throw "apksigner verify failed with exit code $LASTEXITCODE"
}

Write-Host "Signed APK: $signed"
