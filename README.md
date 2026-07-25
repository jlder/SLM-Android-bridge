# SLM Android Bridge — reference implementation

This is a thin Android shell for the recorder-hosted SLM Web application. The
recorder remains the source of truth for UI, workflow, analysis, and versioning;
Android supplies network routing, durable storage, and cellular upload.

## Implemented

- Android 10+ `WifiNetworkSpecifier` connection to the recorder.
- Explicit cellular network request for Google Drive uploads.
- Existing recorder UI in a recorder-origin-restricted WebView.
- Recorder-origin JavaScript alerts, confirmations, and prompts required by the
  existing recorder UI.
- Standard recorder downloads exported through Android's user-controlled document
  picker, without broad storage permission.
- Redundant recorder-origin `blob:` downloads from the desktop-browser fallback
  suppressed after the native bridge has taken ownership of the private copy.
- Recorder-origin Web file inputs open Android's file picker for firmware OTA and
  similar uploads, without exposing arbitrary phone files to the WebView.
- Versioned `SLMAndroid` JavaScript bridge.
- Streamed recorder download over Wi-Fi into app-private storage.
- Restricted `https://slm-app.local/transfers/<id>/content` access, allowing the
  recorder-delivered JS to run its existing `ArrayBuffer` analysis.
- Restart-safe app-private upload queue with resumable Google Drive upload over
  cellular.
- Google Drive OAuth refresh authorization supplied by the connected recorder and
  encrypted with Android Keystore while uploads are pending.
- Automatic Drive subfolders based on the registration derived from the recorder
  SSID.
- SHA-256 duplicate detection before Drive upload.
- Progress/completion events returned to the recorder Web application.
- Configurable recorder SSID/password/address. No server URL or bearer-token field
  is needed for the direct Drive demonstrator.

## Status

This is a reviewable reference implementation, not a production release. The
earlier bridge baseline was compiled, installed, and launched on an Android 11
phone. Version 0.2's direct Drive path still requires an Android Studio build and
end-to-end testing with the recorder endpoint and target Drive account.

## Open in Android Studio

1. Install current Android Studio and Android SDK 35.
2. Open this directory.
3. Let Android Studio create/use a Gradle wrapper if prompted and sync.
4. Connect an Android 10+ phone and run the `app` configuration.
5. Open **CONF** and enter the recorder details. The recorder SSID must contain the
   glider registration, for example `SLM-F-ABCD`.
6. Select **CONNECT** and approve Android's recorder Wi-Fi request.
7. Once the recorder status is connected, select **LAUNCH**.

The app deliberately remains disconnected on startup. Saving configuration also
leaves it disconnected, so configuration, network connection, and recorder UI
launch are separate user actions. The three controls share one compact row. After
connection, **CONNECT** becomes **STOP**; while searching, it becomes **CANCEL**.

Recorder transfers are streamed into app-private phone storage and recorded in a
durable queue before upload. When cellular data is ready, the app refreshes a
Google access token and uploads directly to Drive using resumable 1 MiB chunks.
Pending transfers survive an app or phone restart and retry after the user
reconnects to the recorder. Once authorization has been collected, stopping the
recorder Wi-Fi does not stop the cellular upload request, so a queued upload may
finish as the pilot leaves the recorder. The recorder authorization is retained
only while the queue contains pending uploads.

## Security status

This revision validates recorder URLs, restricts Drive traffic to Google HTTPS
endpoints, disables WebView file/content access and third-party cookies,
blocks navigation outside the recorder's exact origin, and limits transfer-content
CORS responses to that origin. Recorder file exports retain the WebView session,
stay on the recorder network, reject cross-origin redirects, and use Android's
document picker. Debug builds record recorder Web errors under the `SLM-Web`
Logcat tag; release builds keep WebView debugging disabled.

Before a production release:

- Serve the recorder UI/API over authenticated HTTPS; the current local recorder
  default is cleartext `http://192.168.4.1`.
- Replace the globally enabled cleartext manifest policy with a narrowly scoped
  recorder policy, or remove cleartext support after recorder HTTPS is available.
- Encrypt the saved recorder Wi-Fi password using Android Keystore.
- Replace the recorder-distributed shared OAuth refresh authorization with device
  enrollment and short-lived, narrowly scoped credentials.
- Replace `addJavascriptInterface` with an origin-scoped WebMessage channel, and
  define the recorder bridge's allowed methods and message schema explicitly.
- Add download-size limits, retention/deletion rules, audit-safe error handling,
  release signing, and end-to-end hostile-input tests.

## Recorder integration

Use `recorder-patch/web-ui-android-bridge.patch` as the integration change for
`src/tasks/web_ui/11_script_files_download.inc`. The patch is deliberately kept
separate from the supplied recorder ZIP so the original project remains intact. The
desktop-browser path remains unchanged. With bridge version 2, Android downloads
once, exposes the saved bytes to the existing recorder-controlled analysis JS,
queues the same file, and uploads it directly to Drive over cellular.

The recorder must also implement `GET /api/slm-drive-config`. See
`recorder-patch/drive-credential-endpoint.md` for the exact response contract and
SSID registration rules. The recorder firmware source is not included here, so
that endpoint remains a recorder-project integration step.

## Legacy upload gateway

The previous Cloud Run gateway remains in `gateway/` as a reference and fallback,
but the Android demonstrator no longer calls it. The direct Drive design avoids
the unresolved Cloud Run routing problem and the cost/operation of another host.

## Google Drive OAuth bootstrap

`tools/oauth-bootstrap.ps1` performs the administrator-only OAuth flow. It
requires a Google **Web application** client whose
redirect URI is exactly `http://localhost:8080/oauth2/callback` and requests only
the `https://www.googleapis.com/auth/drive.file` scope. It stores the refresh
credential outside the source tree under the current Windows user's Local AppData
directory and creates the app-managed `SLM-STC-DATA` folder.

Before generating the demonstrator's final refresh token, change the OAuth app
from **Testing** to **In production**; otherwise Google normally expires the
refresh token after seven days. Then run `tools/export-recorder-drive-config.ps1`
and provision the resulting private JSON to each recorder. Never commit the
downloaded client JSON, OAuth output, or recorder private JSON.

## Production work still required

- Replace shared recorder OAuth authorization with device enrollment and
  short-lived credentials.
- Add an Android scheduled/background worker if uploads must continue while the
  app is fully closed; the durable queue itself is already implemented.
- Server idempotency, checksum, metadata, and duplicate rules.
- Confirm recorder authorization lifetime for native `/api/download` requests.
- Test Wi-Fi/cellular concurrency across supported phones.
- Validate large-file WebView analysis memory limits.
- Narrow cleartext policy or add recorder HTTPS.
- Automated tests and signed release configuration.

## Bridge contract

```javascript
JSON.parse(SLMAndroid.getCapabilities())
SLMAndroid.downloadRecorderFile(JSON.stringify({
  filename: 'recording.bin', analyze: true, upload: true
}))
SLMAndroid.deleteStoredFile(transferId)
```

Progress arrives as `slm-transfer-event`. States are `download-started`,
`downloading`, `download-complete`, `upload-pending`, `upload-started`, `uploading`,
`upload-complete`, and `failed`.
