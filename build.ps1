$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'
$env:ANDROID_HOME = Join-Path $root '.tools\sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $root '.tools\gradle-home'
$gradle = Join-Path $root '.tools\gradle-8.7\bin\gradle.bat'

if (-not (Test-Path $gradle)) {
    throw "Gradle not found: $gradle"
}
& $gradle -p $root assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}
Write-Host "APK: $root\app\build\outputs\apk\release\app-release-unsigned.apk"
