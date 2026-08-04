# SLM Android Bridge — reference implementation

This is a thin Android shell for the recorder-hosted SLM Web application. The
recorder remains the source of truth for UI, workflow, analysis, and versioning;
Android supplies network routing, durable storage, and cellular upload.

## Implemented

- Android 10+ `WifiNetworkSpecifier` connection to the recorder.
- Explicit cellular/validated-Internet routing for Google Drive uploads and server status.
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
- Simplified bridge header: black header with centered recorder status on the left, fixed white two-line `SLM / BRIDGE` title in the center, centered server status on the right, and one centered blue **CONNECT/STOP** button.
- Connection-state feedback in the left recorder-status field: `No / Recorder` before connection, blinking `Searching / Recorder` during Wi-Fi discovery, blinking `Connecting / F-CJAF` while joining/probing the recorder, and steady `F-CJAF / Connected` after the recorder answers.
- Periodic recorder health checks after connection. If the recorder Wi-Fi or Web
  service disappears, the bridge disconnects, clears the WebView, and returns to
  `No / Recorder`.
- Server status is shown in the right header field as `Server / Off-line` or `Server / Connected`. The compact line below CONNECT/STOP shows `File Queue Empty`, `File Queue: n files waiting`, or `Transferring File (x/y)` with a progress bar during file transfer.
  The server side uses an explicitly selected validated Internet network and is
  kept separate from the local recorder Wi-Fi network used by the WebView.

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
   `SLM2-` followed by five uppercase alphanumeric characters, for example
   `SLM2-FCJAF`.
6. If several matching recorders are visible, select the recorder to use.
7. Approve Android's recorder Wi-Fi request. When the recorder answers, the app
   opens the recorder Web interface automatically.

The app deliberately remains disconnected on startup. **CONNECT** performs recorder
discovery and connection in one operator action. Before **CONNECT** is pressed, the
left recorder status shows `No / Recorder`. After **CONNECT**, it shows blinking `Searching / Recorder` while Android is asked to refresh Wi-Fi scan results. After a recorder SSID is selected, it shows blinking `Connecting / F-CJAF` while Android associates with the recorder and the bridge waits for `/api/status`. When the recorder answers, the app opens the recorder interface automatically and the left status becomes `F-CJAF / Connected`. Pressing **STOP** returns the left status to `No / Recorder`. If the recorder disappears after connection without the user pressing STOP, periodic health checks show `F-CJAF / Disconnected` and clear the stale WebView page.

Recorder transfers are streamed into app-private phone storage and recorded in a
durable queue before upload. When cellular data is ready, the app refreshes a
Google access token and uploads directly to Drive using resumable 8 MiB chunks.
Pending transfers survive an app or phone restart and retry after the user
reconnects to the recorder. Once authorization has been collected, stopping the
recorder Wi-Fi does not stop the explicitly routed Internet upload request, so a
queued upload may finish as the pilot leaves the recorder. The recorder authorization is retained
while the recorder remains connected, and is cleared once the recorder is disconnected and the queue is idle.

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
redirect URI is exactly `http://localhost:8080/oauth2/callback`. The bootstrap
requests `https://www.googleapis.com/auth/drive.file` for bridge-created upload
files and `https://www.googleapis.com/auth/drive.readonly` so server firmware
files staged manually in Google Drive can be listed and downloaded. It stores the refresh
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
SLMAndroid.listServerFirmware()
SLMAndroid.installServerFirmware(JSON.stringify(selectedFirmware))
```

Progress arrives as `slm-transfer-event`. Firmware-from-server events arrive as `slm-firmware-event`. States are `download-started`,
`downloading`, `download-complete`, `upload-pending`, `upload-started`, `uploading`,
`upload-complete`, and `failed`.


Wi-Fi discovery first requests a fresh Android scan and waits for `SCAN_RESULTS_AVAILABLE_ACTION`. When Android reports fresh results, only fresh recorder SSIDs are offered. If Android throttles the scan, the bridge can fall back to recent cached results, but SSIDs that just failed to connect are temporarily suppressed so a stopped recorder is not immediately offered again from stale scan data. Version 0.3.18 uses a fixed two-line `SLM / BRIDGE` center title, gives the side status fields more room, and returns to `No / Recorder` after an intentional STOP. Version 0.3.15 introduced the three-zone header layout. Version 0.3.10 kept the earlier title prefix steady while only the active status blinked.

Version 0.3.11 reduces the Android recorder Wi-Fi connection timeout from 60 s to 30 s so a stopped or unavailable recorder returns to the disconnected recorder state more quickly.

Version 0.3.12 adds the SLM Bridge launcher icon and sets the Android launcher label to `SLM Bridge`.

Version 0.3.13 reduces the launcher-icon artwork inside the adaptive-icon safe area so the home-screen icon is not cropped by Android launcher masks.


Version 0.3.14 keeps recorder Wi-Fi discovery and connection behavior unchanged, but improves Internet routing for the server-upload status and Drive transfers. While the WebView is bound to recorder Wi-Fi, server checks and uploads explicitly use a validated Internet network, preferring cellular and falling back to any validated non-recorder Internet network.


Version 0.3.18 refines the main bridge header: the no-recorder state is shown as `No / Recorder`, the center title is displayed as two fixed lines `SLM / BRIDGE`, the side status fields are wider, and an intentional STOP returns to `No / Recorder`. Version 0.3.15 introduced the three-zone header with centered recorder status on the left, fixed two-line `SLM / BRIDGE` title in the center, centered server status on the right, and a compact queue/transfer line below CONNECT/STOP.

### Upload and queue behavior in 0.3.22

Version 0.3.22 removes the obsolete 5-second foreground upload retry from 0.3.19 and keeps the upload-latency improvements. The bridge keeps Drive authorization and resolved recorder folder IDs in memory for a short idle grace period instead of clearing them as soon as the queue becomes empty, uses 8 MiB Drive chunks, and streams upload progress while data is written. Pending files are displayed as `File Queue: n files waiting`; active uploads remain `Transferring File (x/y)` with the progress bar active. Uploads are started by explicit events: file download completion, Internet availability, and completion of the previous queued upload; there is no periodic 5-second retry loop.

### Firmware from server in 0.3.24

Version 0.3.24 adds the Android-side support for the recorder Web Firmware page to request firmware from the server. The operator remains in the recorder Web UI. The bridge only executes the network work that the recorder cannot do itself while operating as a local Wi-Fi access point: it lists firmware files from Google Drive over the validated Internet network, downloads the selected `.bin` file, then uploads it to the recorder OTA endpoint over recorder Wi-Fi.

Firmware lookup first checks the connected recorder's Drive folder, `<registration>/FIRMWARE`. If that folder does not exist or contains no accepted recorder firmware `.bin`, the bridge falls back to the common `SLM-STC-DATA/FIRMWARE` folder. Multiple firmware versions may be listed so the operator can deliberately select a previous version when needed.


### Firmware from server note

The bridge searches recorder-specific firmware folders using both the canonical registration folder name (for example `F-CJAF/FIRMWARE`) and the compact five-character recorder registration folder name (for example `FCJAF/FIRMWARE`) before falling back to `SLM-STC-DATA/FIRMWARE`. Folder-name matching for `FIRMWARE` is case-insensitive. Firmware `.bin` files up to 32 MiB are accepted; the recorder OTA endpoint remains the final authority for whether the image fits the device.

Server firmware files are expected to be staged manually in Google Drive. The recorder Drive authorization must therefore be generated with firmware read access (`drive.readonly`) in addition to the existing app-file upload access (`drive.file`). If a firmware `.bin` is visible in the Google Drive browser but not in SLM Bridge, regenerate the OAuth credential with `tools/oauth-bootstrap.ps1`, export the recorder Drive configuration again, and reprovision/rebuild the recorder.

### Recorder Process lock in 0.3.28

Version 0.3.28 exposes durable recorder-transfer states to the recorder Web page and rejects a second request for the same registration and filename. Recorder uploads now become eligible only after browser analysis reports success. A failed analysis removes the incomplete transfer, allowing the blue **Process** action to be used again. Pending or retrying uploads remain locked across WebView refreshes, app reconnection, and application restart until automatic recorder archive succeeds.

### Android system-navigation clearance in 0.3.29

Version 0.3.29 applies the Android system-bar insets to the Bridge root layout. The usable content area now excludes both the normal system bars and the bottom tappable navigation area, so the Bridge does not cover the Android three-button navigation controls. Gesture-navigation layouts remain unchanged apart from the inset reported by Android.

### Version 0.3.30

- User-action and error messages now use persistent, wrapped dialogs with an OK button instead of short-lived large Toast messages. Brief progress confirmations such as Connecting and Saving remain transient.
- Recorder web UI integration supports the recorder-side global Process-button lock during Downloading and Analyzing.


## v0.3.31 recorder creation-SHA verification

Before downloading a recorder `.bin`, the Bridge requests the companion `.sha`. If present, it validates metadata, file length, and SHA-256 before analysis or upload. A missing `.sha` is accepted as a legacy file and remains protected by the Bridge-to-server transfer SHA. Malformed metadata or a size/SHA mismatch stops processing and leaves the recorder file available for investigation.

## v0.3.33 end-to-end SHA-256 verification

After the recorder/Bridge SHA-256 check, the Bridge verifies the copy stored in Google Drive before requesting recorder archiving. It requests Drive's output-only `size` and `sha256Checksum` fields and requires both to match the local file and the SHA-256 already associated with the transfer. A missing or mismatched Drive SHA-256 leaves the transfer queued and the recorder file unarchived. Legacy files remain supported: their SHA-256 is established when the Bridge reads the SD file, then compared directly with Drive's server-computed SHA-256.


## v0.3.35 — compact transfer status

- The Bridge transfer information is displayed on one line.
- The left field reports `File upload <percent>%` or `File upload None`.
- The right field reports `File Queue <current>/<total>` or `File Queue Empty`.
- Upload and queue behavior are unchanged.

## v0.3.36 recorder archive endpoint

After Drive SHA-256 verification, the Bridge calls `POST /api/archive` on the recorder.
