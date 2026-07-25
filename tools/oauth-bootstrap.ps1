[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$ClientJson,

    [string]$OutputFile = (Join-Path $env:LOCALAPPDATA 'SLMUploadGateway\oauth.json')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$redirectUri = 'http://localhost:8080/oauth2/callback'
$driveScope = 'https://www.googleapis.com/auth/drive.file'
$folderName = 'SLM-STC-DATA'

function ConvertTo-UrlValue([string]$Value) {
    return [Uri]::EscapeDataString($Value)
}

function Read-Query([string]$Query) {
    $result = @{}
    foreach ($entry in $Query.TrimStart('?').Split('&', [StringSplitOptions]::RemoveEmptyEntries)) {
        $parts = $entry.Split('=', 2)
        $key = [Uri]::UnescapeDataString($parts[0].Replace('+', ' '))
        $value = if ($parts.Length -eq 2) {
            [Uri]::UnescapeDataString($parts[1].Replace('+', ' '))
        } else {
            ''
        }
        $result[$key] = $value
    }
    return $result
}

function Send-BrowserResponse($Stream, [string]$Title, [string]$Message) {
    $html = @"
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>$Title</title></head>
<body style="font-family:sans-serif;margin:3rem"><h1>$Title</h1><p>$Message</p></body></html>
"@
    $body = [Text.Encoding]::UTF8.GetBytes($html)
    $header = "HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
    $headerBytes = [Text.Encoding]::ASCII.GetBytes($header)
    $Stream.Write($headerBytes, 0, $headerBytes.Length)
    $Stream.Write($body, 0, $body.Length)
    $Stream.Flush()
}

$clientDocument = Get-Content -LiteralPath $ClientJson -Raw | ConvertFrom-Json
if ($null -eq $clientDocument.web) {
    throw 'The JSON is not a Web application OAuth client. Create a Web application client in Google Auth Platform.'
}
$oauthClient = $clientDocument.web
if (-not (@($oauthClient.redirect_uris) -contains $redirectUri)) {
    throw "The OAuth client does not contain the exact redirect URI $redirectUri"
}

$stateBytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($stateBytes)
$random.Dispose()
$state = [Convert]::ToBase64String($stateBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

$authorizationUrl = $oauthClient.auth_uri + '?' + (@(
    'client_id=' + (ConvertTo-UrlValue $oauthClient.client_id)
    'redirect_uri=' + (ConvertTo-UrlValue $redirectUri)
    'response_type=code'
    'scope=' + (ConvertTo-UrlValue $driveScope)
    'access_type=offline'
    'prompt=consent'
    'include_granted_scopes=true'
    'state=' + (ConvertTo-UrlValue $state)
) -join '&')

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 8080)
try {
    $listener.Start()
} catch {
    throw 'Cannot start localhost:8080. Close any program already using port 8080 and try again.'
}

Write-Host 'Opening Google authorization in your browser...'
Write-Host 'Keep this window open until authorization completes.'
Start-Process $authorizationUrl

$authorizationCode = $null
try {
    $tcpClient = $listener.AcceptTcpClient()
    try {
        $stream = $tcpClient.GetStream()
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII, $false, 1024, $true)
        $requestLine = $reader.ReadLine()
        while ($null -ne ($line = $reader.ReadLine()) -and $line.Length -gt 0) { }
        if ([string]::IsNullOrWhiteSpace($requestLine)) {
            throw 'The browser returned an empty callback request.'
        }
        $requestParts = $requestLine.Split(' ')
        if ($requestParts.Length -lt 2) {
            throw 'The browser returned an invalid callback request.'
        }
        $callbackUri = [Uri]('http://localhost' + $requestParts[1])
        $query = Read-Query $callbackUri.Query
        if ($query.ContainsKey('error')) {
            Send-BrowserResponse $stream 'SLM authorization cancelled' 'Google did not grant Drive access. You may close this tab.'
            throw ('Google authorization failed: ' + $query['error'])
        }
        if (-not $query.ContainsKey('state') -or $query['state'] -ne $state) {
            Send-BrowserResponse $stream 'SLM authorization rejected' 'The authorization response could not be validated. You may close this tab.'
            throw 'The OAuth state value did not match.'
        }
        if (-not $query.ContainsKey('code')) {
            Send-BrowserResponse $stream 'SLM authorization rejected' 'Google did not return an authorization code. You may close this tab.'
            throw 'Google did not return an authorization code.'
        }
        $authorizationCode = $query['code']
        Send-BrowserResponse $stream 'SLM authorization received' 'The local helper is completing the Drive setup. You may close this tab.'
    } finally {
        if ($null -ne $tcpClient) { $tcpClient.Close() }
    }
} finally {
    $listener.Stop()
}

$token = Invoke-RestMethod -Method Post -Uri $oauthClient.token_uri `
    -ContentType 'application/x-www-form-urlencoded' -Body @{
        code = $authorizationCode
        client_id = $oauthClient.client_id
        client_secret = $oauthClient.client_secret
        redirect_uri = $redirectUri
        grant_type = 'authorization_code'
    }

if ([string]::IsNullOrWhiteSpace($token.refresh_token)) {
    throw 'Google did not return a refresh token. Revoke the app grant and run this helper again.'
}

$driveHeaders = @{ Authorization = 'Bearer ' + $token.access_token }
$folderQuery = "name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
$searchUrl = 'https://www.googleapis.com/drive/v3/files?spaces=drive&pageSize=10&fields=files(id%2Cname)&q=' + `
    (ConvertTo-UrlValue $folderQuery)
$folderSearch = Invoke-RestMethod -Method Get -Uri $searchUrl -Headers $driveHeaders

if (@($folderSearch.files).Count -gt 0) {
    $folder = @($folderSearch.files)[0]
    Write-Host "Using the existing gateway-owned $folderName folder."
} else {
    $folderBody = @{
        name = $folderName
        mimeType = 'application/vnd.google-apps.folder'
        parents = @('root')
    } | ConvertTo-Json
    $folder = Invoke-RestMethod -Method Post `
        -Uri 'https://www.googleapis.com/drive/v3/files?fields=id%2Cname' `
        -Headers $driveHeaders -ContentType 'application/json' -Body $folderBody
    Write-Host "Created the gateway-owned $folderName folder."
}

$outputDirectory = Split-Path -Parent $OutputFile
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$savedCredential = [ordered]@{
    version = 1
    project_id = $oauthClient.project_id
    client_id = $oauthClient.client_id
    client_secret = $oauthClient.client_secret
    token_uri = $oauthClient.token_uri
    refresh_token = $token.refresh_token
    scope = $driveScope
    root_folder_id = $folder.id
    root_folder_name = $folder.name
    authorized_at_utc = [DateTime]::UtcNow.ToString('o')
}
$savedCredential | ConvertTo-Json | Set-Content -LiteralPath $OutputFile -Encoding UTF8

Write-Host ''
Write-Host 'OAuth setup completed successfully.' -ForegroundColor Green
Write-Host "Private gateway credential: $OutputFile"
Write-Host "Drive root folder: $($folder.name) ($($folder.id))"
Write-Host 'Do not copy the private credential into the Android project or share it.'
