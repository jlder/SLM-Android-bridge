[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$FilePath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]{1,15}$')]
    [string]$Registration,

    [string]$GatewayUrl = 'http://localhost:8787/v1/uploads'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$tokenFile = Join-Path $env:LOCALAPPDATA 'SLMUploadGateway\upload-token.txt'
if (-not (Test-Path -LiteralPath $tokenFile -PathType Leaf)) {
    throw "Upload token not found at $tokenFile. Start the local gateway first."
}

$token = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
$client = [Net.Http.HttpClient]::new()
$form = [Net.Http.MultipartFormDataContent]::new()
$stream = $null
$fileContent = $null
$response = $null
try {
    $client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    $client.DefaultRequestHeaders.Add('X-SLM-Registration', $Registration.ToUpperInvariant())
    $stream = [IO.File]::OpenRead((Resolve-Path -LiteralPath $FilePath).Path)
    $fileContent = [Net.Http.StreamContent]::new($stream)
    $fileContent.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
    $form.Add($fileContent, 'file', [IO.Path]::GetFileName($FilePath))
    $response = $client.PostAsync($GatewayUrl, $form).GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "Gateway returned HTTP $([int]$response.StatusCode): $body"
    }
    $body
} finally {
    if ($null -ne $response) { $response.Dispose() }
    if ($null -ne $fileContent) { $fileContent.Dispose() }
    if ($null -ne $stream) { $stream.Dispose() }
    $form.Dispose()
    $client.Dispose()
}
