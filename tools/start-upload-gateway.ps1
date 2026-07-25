[CmdletBinding()]
param(
    [int]$Port = 8787,
    [switch]$ShowToken
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$oauthFile = Join-Path $env:LOCALAPPDATA 'SLMUploadGateway\oauth.json'
$tokenFile = Join-Path $env:LOCALAPPDATA 'SLMUploadGateway\upload-token.txt'
$gatewayScript = Join-Path (Split-Path -Parent $PSScriptRoot) 'gateway\server.mjs'

if (-not (Test-Path -LiteralPath $oauthFile -PathType Leaf)) {
    throw "OAuth credential not found at $oauthFile. Run oauth-bootstrap.ps1 first."
}

$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
$nodePath = if ($null -ne $nodeCommand) {
    $nodeCommand.Source
} else {
    Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
}
if (-not (Test-Path -LiteralPath $nodePath -PathType Leaf)) {
    throw 'Node.js 20 or newer is required to run the local gateway.'
}

$createdToken = $false
if (-not (Test-Path -LiteralPath $tokenFile -PathType Leaf)) {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    $token = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    Set-Content -LiteralPath $tokenFile -Value $token -Encoding ASCII
    $createdToken = $true
}

if ($createdToken -or $ShowToken) {
    Write-Host ''
    Write-Host 'Android Server bearer token (keep private):' -ForegroundColor Yellow
    Get-Content -LiteralPath $tokenFile
    Write-Host ''
}

$env:SLM_OAUTH_FILE = $oauthFile
$env:SLM_UPLOAD_TOKEN_FILE = $tokenFile
$env:PORT = [string]$Port
Write-Host "Starting the local SLM upload gateway on http://localhost:$Port"
Write-Host 'Press Ctrl+C to stop it.'
& $nodePath $gatewayScript
