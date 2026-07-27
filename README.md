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
- Automatic recorder discovery: **CONNECT** scans for Wi-Fi names matching
  `SLM-` plus five uppercase alphanumeric characters, for example `SLM-FCJAF`.
  The WPA2 password is generated as `SLM` plus the reversed five-character
  registration, for example `SLMFAJCF`. No recorder SSID/password setup menu is
  required.
- Simplified bridge header: black title bar with white `SLM BRIDGE - ...` text,
  one centered blue **CONNECT/STOP** button, and a two-field server-upload
  status line below the button.
- Connection-state feedback in the title bar: the fixed `SLM BRIDGE -` prefix remains steady while only `Searching` blinks when Android
  is refreshing Wi-Fi scan results, and only `Connecting F-CJAF` blinks after a recorder
  SSID is selected and the bridge is joining/probing it, and `F-CJAF` is steady
  after the recorder answers.
- Periodic recorder health checks after connection. If the recorder Wi-Fi or Web
  service disappears, the bridge disconnects, clears the WebView, and returns to
  `Not Connected`.
- Server-upload status is split into `Server Off-line` / `Server Connected` on
  the left and `File Queue Empty` or `File Queue x/y (z%)` on the right.

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
5. Select **CONNECT**. The app scans for recorder Wi-Fi names matching
   `SLM-` followed by five uppercase alphanumeric characters, for example
   `SLM-FCJAF`.
6. If several matching recorders are visible, select the recorder to use.
7. Approve Android's recorder Wi-Fi request. When the recorder answers, the app
   opens the recorder Web interface automatically.

The app deliberately remains disconnected on startup. **CONNECT** performs recorder
discovery and connection in one operator action. Before **CONNECT** is pressed, the
title is `SLM BRIDGE - Not Connected`. After **CONNECT**, the title becomes
`SLM BRIDGE - Searching` while Android is asked to refresh Wi-Fi scan results.
Only `Searching` blinks to show that discovery is in progress; the `SLM BRIDGE -` prefix stays fixed. After a recorder SSID
is selected, only the `Connecting F-CJAF` suffix blinks
text while Android associates with the recorder and the bridge waits
for `/api/status`. When the recorder answers, the app opens the recorder interface
automatically and the title becomes `SLM BRIDGE - F-CJAF`. If the recorder
disappears after connection, periodic health checks return the bridge to
`SLM BRIDGE - Not Connected` and clear the stale WebView page.

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


Wi-Fi discovery first requests a fresh Android scan and waits for `SCAN_RESULTS_AVAILABLE_ACTION`. When Android reports fresh results, only fresh recorder SSIDs are offered. If Android throttles the scan, the bridge can fall back to recent cached results, but SSIDs that just failed to connect are temporarily suppressed so a stopped recorder is not immediately offered again from stale scan data. Version 0.3.10 keeps the `SLM BRIDGE -` title prefix steady and blinks only the active `Searching` or `Connecting ...` suffix. Version 0.3.9 splits server-upload status into server availability and queue progress.

Version 0.3.11 reduces the Android recorder Wi-Fi connection timeout from 60 s to 30 s so a stopped or unavailable recorder returns to `Not Connected` more quickly.

Version 0.3.12 adds the SLM Bridge launcher icon and sets the Android launcher label to `SLM Bridge`.

Version 0.3.13 reduces the launcher-icon artwork inside the adaptive-icon safe area so the home-screen icon is not cropped by Android launcher masks.
