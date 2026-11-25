$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!(Test-Path -LiteralPath (Join-Path $projectRoot 'app\src\main\assets\zashboard\index.html') -PathType Leaf)) {
    throw 'Bundled zashboard is missing. Run tools/fetch-zashboard.ps1 before building.'
}
$toolchainRoot = Join-Path $env:LOCALAPPDATA 'SimpleXrayBuild'
$signingDirectory = Join-Path $toolchainRoot 'signing'
$credential = Import-Clixml -LiteralPath (Join-Path $signingDirectory 'simplexray-groups.credential.xml')

$env:KEYSTORE_PATH = (Resolve-Path -LiteralPath (Join-Path $signingDirectory 'simplexray-groups.p12')).Path
$env:KEYSTORE_PASSWORD = $credential.GetNetworkCredential().Password
$env:KEY_ALIAS = 'simplexray-groups'
$env:KEY_PASSWORD = $env:KEYSTORE_PASSWORD
$env:JAVA_HOME = Join-Path $toolchainRoot 'jdk-25'
$env:ANDROID_HOME = Join-Path $toolchainRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmake\3.22.1\bin;$env:PATH"

$gradlePath = Join-Path $toolchainRoot 'gradle-9.9.1\bin\gradle.bat'
Write-Output "Building LucX 1.0.0-68 with persistent signing alias $env:KEY_ALIAS."
& $gradlePath --project-dir $projectRoot :app:assembleDebug `
    "-Porg.gradle.java.installations.paths=$env:JAVA_HOME,$toolchainRoot\jdk-21" `
    --console=plain --quiet
$buildExitCode = $LASTEXITCODE
$env:KEYSTORE_PASSWORD = $null
$env:KEY_PASSWORD = $null
if ($buildExitCode -ne 0) {
    throw "APK compilation exited with code $buildExitCode."
}

Write-Output "APK: $projectRoot\app\build\outputs\apk\debug\LucX-arm64-v8a.apk"
