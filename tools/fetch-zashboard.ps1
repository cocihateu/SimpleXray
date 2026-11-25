$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$destination = Join-Path $projectRoot 'app\src\main\assets\zashboard'
$version = 'v3.30.0'
$expectedHash = '1f91c014e61e768240a86ad0a9bd565b247ec5a0e26b125fb5d19076682905b7'
$tempDirectory = Join-Path ([IO.Path]::GetTempPath()) ('zashboard-' + [Guid]::NewGuid().ToString('N'))
$previousSecurityProtocol = [Net.ServicePointManager]::SecurityProtocol

try {
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    $archive = Join-Path $tempDirectory 'dist.zip'
    $unpacked = Join-Path $tempDirectory 'unpacked'
    [Net.ServicePointManager]::SecurityProtocol = $previousSecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Write-Output "Downloading zashboard $version."
    Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/Zephyruso/zashboard/releases/download/$version/dist.zip" -OutFile $archive
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    if ($actualHash -ne $expectedHash) {
        throw "zashboard SHA256 mismatch: expected $expectedHash, got $actualHash."
    }

    Expand-Archive -LiteralPath $archive -DestinationPath $unpacked
    $dist = Join-Path $unpacked 'dist'
    if (!(Test-Path -LiteralPath (Join-Path $dist 'index.html') -PathType Leaf) -or
        !(Test-Path -LiteralPath (Join-Path $dist 'THIRD_PARTY_NOTICES.md') -PathType Leaf)) {
        throw 'zashboard archive is missing index.html or THIRD_PARTY_NOTICES.md.'
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    if (Test-Path -LiteralPath $destination) {
        Remove-Item -LiteralPath $destination -Recurse -Force
    }
    Move-Item -LiteralPath $dist -Destination $destination
    Write-Output "Bundled zashboard ${version}: $destination"
} finally {
    [Net.ServicePointManager]::SecurityProtocol = $previousSecurityProtocol
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
