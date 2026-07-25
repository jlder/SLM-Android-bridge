param(
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$OAuthFile = (Join-Path $env:LOCALAPPDATA 'SLMUploadGateway\oauth.json'),

    [string]$OutputFile = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'slm-drive-config.private.json')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$oauth = Get-Content -LiteralPath $OAuthFile -Raw | ConvertFrom-Json
$required = @('client_id', 'client_secret', 'refresh_token', 'root_folder_id')
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace([string]$oauth.$name)) {
        throw "OAuth credential is missing $name"
    }
}

$config = [ordered]@{
    version = 1
    client_id = [string]$oauth.client_id
    client_secret = [string]$oauth.client_secret
    refresh_token = [string]$oauth.refresh_token
    token_uri = 'https://oauth2.googleapis.com/token'
    root_folder_id = [string]$oauth.root_folder_id
}

$parent = Split-Path -Parent $OutputFile
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$config | ConvertTo-Json | Set-Content -LiteralPath $OutputFile -Encoding UTF8

Write-Host ''
Write-Host 'Recorder Drive configuration created.' -ForegroundColor Green
Write-Host "Private file: $OutputFile"
Write-Host 'Do not commit this file, include it in a public firmware image, or share it with pilots.'
Write-Host 'If the OAuth application was still in Testing when the token was issued, publish it and run the OAuth bootstrap again first.'
