[CmdletBinding()]
param(
    [string]$ProjectId = 'slm-stc-upload',
    [string]$Region = 'europe-west1',
    [switch]$UpdateSecrets
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$serviceName = 'slm-upload-gateway'
$oauthSecret = 'slm-drive-oauth'
$tokenSecret = 'slm-upload-token'
$oauthFile = Join-Path $env:LOCALAPPDATA 'SLMUploadGateway\oauth.json'
$tokenFile = Join-Path $env:LOCALAPPDATA 'SLMUploadGateway\upload-token.txt'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gatewaySource = Join-Path $projectRoot 'gateway'

foreach ($requiredFile in @($oauthFile, $tokenFile, (Join-Path $gatewaySource 'Dockerfile'))) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file not found: $requiredFile"
    }
}

$gcloudCmd = Join-Path $env:LOCALAPPDATA 'Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd'
if (Test-Path -LiteralPath $gcloudCmd -PathType Leaf) {
    $gcloud = $gcloudCmd
} else {
    $gcloudCommand = Get-Command gcloud -ErrorAction SilentlyContinue
    if ($null -eq $gcloudCommand) {
        throw 'Google Cloud CLI was not found. Close and reopen PowerShell after installing it.'
    }
    $gcloud = $gcloudCommand.Source
}

function Invoke-Gcloud {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $gcloud @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud command failed: gcloud $($Arguments -join ' ')"
    }
}

function Set-SecretFromFile {
    param([string]$Name, [string]$File)
    $listed = @(& $gcloud secrets list --format='value(name)' --project $ProjectId)
    if ($LASTEXITCODE -ne 0) { throw "Could not check protected secret $Name." }
    $exists = @($listed | ForEach-Object { $_.Trim() }) -contains $Name
    if (-not $exists) {
        Write-Host "Creating protected secret $Name..."
        Invoke-Gcloud secrets create $Name "--data-file=$File" --replication-policy=automatic --project $ProjectId --quiet
    } elseif ($UpdateSecrets) {
        Write-Host "Adding a new protected version of $Name..."
        Invoke-Gcloud secrets versions add $Name "--data-file=$File" --project $ProjectId --quiet
    } else {
        Write-Host "Using existing protected secret $Name."
    }
}

Write-Host "Preparing Google Cloud project $ProjectId..."
Invoke-Gcloud config set account slm.stc.easa@gmail.com --quiet
Invoke-Gcloud config set project $ProjectId --quiet
Invoke-Gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com --project $ProjectId --quiet

$projectNumber = (& $gcloud projects describe $ProjectId --format='value(projectNumber)').Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($projectNumber)) {
    throw 'Could not determine the Google Cloud project number.'
}

$serviceAccountId = 'slm-upload-gateway'
$serviceAccount = "$serviceAccountId@$ProjectId.iam.gserviceaccount.com"
$listedServiceAccounts = @(& $gcloud iam service-accounts list --format='value(email)' --project $ProjectId)
if ($LASTEXITCODE -ne 0) { throw 'Could not check the dedicated gateway service identity.' }
if (-not (@($listedServiceAccounts | ForEach-Object { $_.Trim() }) -contains $serviceAccount)) {
    Write-Host 'Creating the dedicated gateway service identity...'
    Invoke-Gcloud iam service-accounts create $serviceAccountId --display-name='SLM upload gateway' --project $ProjectId --quiet
}

Set-SecretFromFile $oauthSecret $oauthFile
Set-SecretFromFile $tokenSecret $tokenFile

foreach ($secret in @($oauthSecret, $tokenSecret)) {
    Invoke-Gcloud secrets add-iam-policy-binding $secret `
        "--member=serviceAccount:$serviceAccount" `
        --role=roles/secretmanager.secretAccessor `
        --project $ProjectId --quiet
}

Write-Host 'Building and deploying the HTTPS gateway. This can take several minutes...'
Invoke-Gcloud run deploy $serviceName `
    "--source=$gatewaySource" `
    "--project=$ProjectId" `
    "--region=$Region" `
    --service-account=$serviceAccount `
    --no-invoker-iam-check `
    --default-url `
    --ingress=all `
    --execution-environment=gen2 `
    --cpu=1 `
    --memory=512Mi `
    --concurrency=4 `
    --min=0 `
    --max=2 `
    --timeout=600 `
    --port=8080 `
    --set-env-vars=SLM_MAX_UPLOAD_BYTES=20971520 `
    "--set-secrets=SLM_OAUTH_JSON=${oauthSecret}:latest,SLM_UPLOAD_TOKEN=${tokenSecret}:latest" `
    --quiet

$serviceDescription = (& $gcloud run services describe $serviceName --project $ProjectId --region $Region --format=json) | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'The deployed service description could not be read.' }
$candidateUrls = @($serviceDescription.status.url)
$annotatedUrls = $serviceDescription.metadata.annotations.'run.googleapis.com/urls'
if (-not [string]::IsNullOrWhiteSpace($annotatedUrls)) {
    try { $candidateUrls += @($annotatedUrls | ConvertFrom-Json) } catch { }
}
$candidateUrls = @($candidateUrls | Where-Object { $_ -and $_.StartsWith('https://') } | Select-Object -Unique)
if ($candidateUrls.Count -eq 0) { throw 'The deployment completed but its HTTPS address could not be read.' }

$serviceUrl = $null
$healthy = $false
Write-Host 'Waiting for the public HTTPS address to become available...'
for ($attempt = 1; $attempt -le 36; $attempt++) {
    foreach ($candidateUrl in $candidateUrls) {
        try {
            $health = Invoke-RestMethod -Uri "$candidateUrl/healthz" -TimeoutSec 15
            if ($health.status -eq 'ok') {
                $serviceUrl = $candidateUrl
                $healthy = $true
                break
            }
        } catch { }
    }
    if ($healthy) { break }
    if ($attempt -eq 1) { Write-Host 'The addresses are still propagating; retrying...' }
    Start-Sleep -Seconds 5
}
if (-not $healthy) {
    throw "The service was deployed, but none of its HTTPS addresses became available within three minutes."
}

Write-Host ''
Write-Host 'SLM upload gateway deployed successfully.' -ForegroundColor Green
Write-Host "Server upload URL: $serviceUrl/v1/uploads"
Write-Host "Region: $Region"
Write-Host 'Minimum instances: 0; maximum instances: 2.'
Write-Host 'The bearer token remains in the protected local and cloud secret files.'
