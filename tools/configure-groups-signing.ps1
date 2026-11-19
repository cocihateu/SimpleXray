param(
    [string]$Repository = "cocihateu/SimpleXray",
    [string]$Keytool = "$env:LOCALAPPDATA\SimpleXrayBuild\jdk-21\bin\keytool.exe"
)

$ErrorActionPreference = "Stop"
$signingDirectory = Join-Path $env:LOCALAPPDATA "SimpleXrayBuild\signing"
$keystorePath = Join-Path $signingDirectory "simplexray-groups.p12"
$credentialPath = Join-Path $signingDirectory "simplexray-groups.credential.xml"
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null

if (Test-Path -LiteralPath $keystorePath) {
    $credential = Import-Clixml -LiteralPath $credentialPath
    $signingPassword = $credential.GetNetworkCredential().Password
} else {
    $randomBytes = [byte[]]::new(32)
    $randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $randomGenerator.GetBytes($randomBytes)
    $randomGenerator.Dispose()
    $signingPassword = [Convert]::ToBase64String($randomBytes)
    $env:GROUPS_SIGNING_PASSWORD = $signingPassword
    & $Keytool -genkeypair -keystore $keystorePath -storetype PKCS12 -alias simplexray-groups `
        -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=SimpleXray Groups" `
        -storepass:env GROUPS_SIGNING_PASSWORD -keypass:env GROUPS_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "Signing key creation failed." }
    $credential = [pscredential]::new("simplexray-groups", (ConvertTo-SecureString $signingPassword -AsPlainText -Force))
    $credential | Export-Clixml -LiteralPath $credentialPath
    Remove-Item Env:\GROUPS_SIGNING_PASSWORD
}

[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath)) |
    gh secret set GROUPS_KEYSTORE_BASE64 --repo $Repository
if ($LASTEXITCODE -ne 0) { throw "Keystore secret upload failed." }
$signingPassword | gh secret set GROUPS_KEYSTORE_PASSWORD --repo $Repository
if ($LASTEXITCODE -ne 0) { throw "Signing credential upload failed." }
Write-Output "Persistent signing configured for $Repository."
Write-Output "Local signing key: $keystorePath"
