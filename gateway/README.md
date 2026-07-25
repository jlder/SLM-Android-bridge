# SLM upload gateway

This gateway accepts the Android app's authenticated multipart upload and stores
the file in Google Drive under:

`SLM-STC-DATA / <GLIDER-REGISTRATION> / <filename>`

It keeps the Google OAuth client secret and refresh token on the gateway. Those
credentials must never be copied into the Android project or an APK.

## Local Drive test on Windows

The OAuth bootstrap must have completed first. From the project directory, open
PowerShell window 1 and run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\start-upload-gateway.ps1
```

The first run creates and displays a random Android bearer token. Keep it private
and keep that PowerShell window open.

Open PowerShell window 2 in the same project directory and upload a harmless test
file:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\test-upload-gateway.ps1 -FilePath .\README.md -Registration TEST-GLIDER
```

A successful response reports `"status":"uploaded"`. Google Drive will contain
`SLM-STC-DATA / TEST-GLIDER / README.md`. Uploading the same bytes again reports
`"status":"duplicate"` and does not create a second file.

The local address is intentionally HTTP and is only for a test performed on the
PC. The Android app requires a public HTTPS gateway because its upload is routed
over the phone's cellular connection.

## Deploy to Google Cloud Run

After billing is linked and the Google Cloud CLI is initialized for the
`slm-stc-upload` project, run from the project directory:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\deploy-upload-gateway.ps1
```

The helper enables the required APIs, creates a dedicated service identity,
stores the OAuth credential and Android bearer token as separate Secret Manager
secrets, and deploys in `europe-west1`. It uses request-based scaling with zero
minimum instances and a maximum of two instances. It prints the HTTPS upload URL
after a successful health check. Re-running it reuses the existing secrets; use
`-UpdateSecrets` only after deliberately rotating a credential.

## Service configuration

The container accepts these environment variables:

- `SLM_OAUTH_JSON`: the complete private `oauth.json` value, supplied as a cloud secret.
- `SLM_UPLOAD_TOKEN`: a random value of at least 32 characters, supplied as a separate cloud secret.
- `SLM_MAX_UPLOAD_BYTES`: optional maximum file size; default 20 MiB.
- `PORT`: listening port; Cloud Run supplies this automatically.

The upload endpoint is `POST /v1/uploads`. It requires:

- `Authorization: Bearer <SLM_UPLOAD_TOKEN>`
- `X-SLM-Registration: <registration>`
- multipart field `file`

`GET /healthz` is the unauthenticated health endpoint. The container is ready for
an HTTPS-managed service such as Google Cloud Run, but deployment and cloud-secret
creation are separate administrator operations.
