param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9]+$')][string]$TeamId,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9]+$')][string]$KeyId,
    [Parameter(Mandatory)][string]$PrivateKeyPath,
    [ValidateRange(1, 180)][int]$ValidDays = 30,
    [string]$OutputPath = (Join-Path $env:LOCALAPPDATA 'BLANK\developer-token.jwt')
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Run this script with PowerShell 7 or newer.' }
$resolvedKey = (Resolve-Path -LiteralPath $PrivateKeyPath).Path
$destination = [IO.Path]::GetFullPath($OutputPath)
if ($destination -match '(?i)OneDrive') { throw 'Do not store credentials in a OneDrive-synchronized directory.' }
if ($destination -eq $resolvedKey) { throw 'The output file must not overwrite the private key.' }
function ConvertTo-Base64Url([byte[]]$Bytes) {
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
$now = [DateTimeOffset]::UtcNow
$header = @{ alg = 'ES256'; kid = $KeyId; typ = 'JWT' } | ConvertTo-Json -Compress
$payload = @{
    iss = $TeamId
    iat = $now.ToUnixTimeSeconds()
    exp = $now.AddDays($ValidDays).ToUnixTimeSeconds()
} | ConvertTo-Json -Compress
$unsigned = (ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))) + '.' +
    (ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payload)))
$signer = [Security.Cryptography.ECDsa]::Create()
try {
    $signer.ImportFromPem([IO.File]::ReadAllText($resolvedKey))
    if ($signer.KeySize -ne 256) { throw 'MusicKit requires a P-256 / ES256 key.' }
    $signature = $signer.SignData([Text.Encoding]::UTF8.GetBytes($unsigned),
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.DSASignatureFormat]::IeeeP1363FixedFieldConcatenation)
    $token = $unsigned + '.' + (ConvertTo-Base64Url $signature)
    New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
    [IO.File]::WriteAllText($destination, $token, [Text.UTF8Encoding]::new($false))
    Write-Output "Signed Developer Token saved locally to: $destination"
    Write-Output "Expires (UTC): $($now.AddDays($ValidDays).ToString('u'))"
    Write-Output 'Paste the file content into the app settings. Never transfer the .p8 private key to the APK.'
} finally {
    $signer.Dispose()
}
