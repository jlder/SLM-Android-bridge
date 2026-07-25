# Recorder Drive authorization endpoint

The Android demonstrator now uploads directly to Google Drive. The recorder must
provide the shared SLM OAuth refresh authorization after the phone has connected
to the recorder Wi-Fi.

## Required endpoint

Serve this exact same-origin endpoint:

```text
GET /api/slm-drive-config
```

Successful response:

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store
Pragma: no-cache

{
  "version": 1,
  "client_id": "...apps.googleusercontent.com",
  "client_secret": "...",
  "refresh_token": "1//...",
  "token_uri": "https://oauth2.googleapis.com/token",
  "root_folder_id": "..."
}
```

Do not put this object in the recorder HTML or JavaScript. Keep it in recorder
configuration/storage and return it only from the API route. The Android client:

- accepts no redirects;
- accepts at most 16 KiB;
- requires the Google token endpoint and a Google OAuth client ID;
- encrypts the object with Android Keystore while an upload is pending; and
- removes its encrypted copy after the pending upload queue is empty.

For this small demonstrator, anyone with access to the recorder network and API
could extract the refresh authorization. That accepted limitation must not carry
over to a production or fleet-wide design.

## Registration source

The glider registration comes from the recorder SSID, not this endpoint. Supported
examples include:

```text
F-ABCD
SLM-F-ABCD
SLM_F-ABCD
```

The resulting Drive subfolder is `F-ABCD`. Use one to three uppercase
letters/numbers, a hyphen, then two to eight uppercase letters/numbers.

## Preparing the recorder JSON

After the Google OAuth application has been moved from **Testing** to
**In production**, run the OAuth bootstrap again to mint a non-testing refresh
token. Then create the recorder-ready JSON outside the Android source tree:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\export-recorder-drive-config.ps1
```

Copy the generated private JSON into the recorder's protected configuration using
the recorder project's normal provisioning method. Do not commit it to source
control and do not include it in a public firmware image.

## Minimal firmware behavior

Pseudocode:

```text
when GET /api/slm-drive-config:
    if private Drive configuration is missing:
        return 503 JSON error
    set Content-Type: application/json
    set Cache-Control: no-store
    return the private configuration bytes
```

The Android implementation is ready for this contract. The recorder firmware
source is not present in this workspace, so the endpoint must be added in the
recorder project itself.
