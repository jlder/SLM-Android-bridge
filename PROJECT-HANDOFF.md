# SLM Android Bridge — project handoff

## Purpose

This Android application connects a pilot's phone to an SLM recorder Wi-Fi
network, displays the recorder Web interface, stores downloaded recorder files
in app-private storage, and uploads queued files to the SLM Google Drive when
an Internet connection is available.

The recorder remains the source of truth for its Web interface and supplies
the Google Drive OAuth configuration through its restricted
`/api/slm-drive-config` endpoint. Glider registration is derived from the
recorder SSID, for example `SLM-FCJAF` becomes `F-CJAF`.

## Current Android configuration

- Application ID: `com.slm.bridge`
- Minimum Android API: 29
- Target/compile API: 35
- Java: 17
- Android Gradle Plugin: 8.13.2
- Current source version: 0.3.25 (`versionCode` 28)

## Implemented behavior

- **CONNECT** requests an Android Wi-Fi scan and waits for the scan-results
  broadcast. When Android reports fresh results, only fresh recorder SSIDs
  matching `SLM-` followed by five uppercase alphanumeric characters are offered.
  If Android throttles the scan, the bridge may use recent cached results, but
  SSIDs that just failed connection are temporarily suppressed to avoid
  immediately re-offering a stopped recorder.
- If one matching recorder is visible, it is selected automatically. If more than
  one matching recorder is visible, the app shows a selection dialog sorted by
  signal level.
- The recorder WPA2 password is generated from the selected SSID as `SLM` plus the
  reversed five-character registration, for example `SLM-FCJAF` -> `SLMFAJCF`.
- There is no recorder connection configuration menu and no stored Wi-Fi password.
- One Android recorder-network request is made per Connect action; the app does
  not automatically cycle through recorders and reopen the system dialog.
- The top bridge header uses the recorder-style colors: white title text on a
  black background, a centered blue **CONNECT/STOP** button with white text, and
  a white server-upload status area below the button.
- The header displays `No / Recorder` in the left status field when no recorder is selected, blinking `Searching / Recorder` during the Wi-Fi scan, blinking `Connecting / F-CJAF` while Android is joining/probing the recorder, and steady `F-CJAF / Connected` after the recorder answers. The center `SLM / BRIDGE` title remains fixed on two lines.
- After Wi-Fi connects, the app probes `/api/status` and opens the recorder
  interface automatically when the recorder answers. It then continues periodic
  `/api/status` checks and disconnects if the recorder stops answering.
- Repeated **STOP** then **CONNECT** actions invalidate any stale Android network
  callbacks from the previous connection request.
- Recorder downloads are retained in app-private storage and queued by
  registration.
- Pending uploads continue when Internet becomes available, including when
  the phone is away from the recorder. Recorder Wi-Fi and server/Drive traffic
  are routed separately: the WebView uses the recorder network, while uploads
  and server availability use an explicitly selected validated Internet network.
- Successful binary uploads trigger recorder-side archive handling.
- The recorder Firmware page can ask the bridge to list and install firmware from the server; the bridge searches `<registration>/FIRMWARE` first and falls back to `SLM-STC-DATA/FIRMWARE`.
- Calibration reports are queued to the registration's reports folder.
- Server status is shown in the right header field as `Server / Off-line` in amber or `Server / Connected` in green. The compact queue line below CONNECT/STOP shows `File Queue Empty`, `File Queue: n files waiting`, or `Transferring File (x/y)` with the progress bar active.

## Current validation state

Version 0.3.18 uses a fixed two-line `SLM / BRIDGE` center title, gives the side status fields more room, and returns to `No / Recorder` after an intentional STOP. Version 0.3.15 introduced the three-zone header with recorder status on the left, fixed title in the center, and server status on the right. Version 0.3.9 added blinking feedback for the active recorder status. Version 0.3.8 waits for Android's scan-results broadcast and temporarily suppresses recorder SSIDs that just failed connection.
Version 0.3.7 relaxed scan filtering to tolerate Android scan throttling and
monitors the recorder after connection. Version 0.3.5 added the simplified
recorder-style bridge header, and version 0.3.4 introduced automatic recorder
discovery. Version 0.3.3 was built successfully before these changes using the
normal Gradle `:app:assembleRelease` pipeline and then signed with the existing
external demonstrator key. The updated connection workflow still requires an
Android Studio build and device validation before pilot deployment.

Earlier manually assembled 0.3.1 and 0.3.2 APKs should not be used.

## Opening and building

1. Extract the ZIP to a normal writable directory.
2. In Android Studio, select **Open** and choose the extracted
   `slm-android-bridge` directory.
3. Allow Android Studio to create `local.properties` for the installed SDK.
4. Install Android SDK Platform 35 and Build Tools 35 if requested.
5. Let Gradle sync finish.
6. Use the `app` run configuration for device testing.

For a command-line release build:

```powershell
.\gradlew.bat :app:assembleRelease
```

The project does not embed a release signing configuration. Deployment
signing credentials must remain outside the source tree.

## Deliberately excluded from the portable package

- `.secrets/`
- OAuth client files, refresh tokens, and Drive credentials
- deployment signing key and password
- `local.properties`
- Android Studio `.idea/` state
- Gradle caches and generated `build/` directories
- manually assembled APK work directories

## Related source areas

- Android application: `app/src/main/`
- Google Drive gateway reference implementation: `gateway/`
- OAuth/deployment helper scripts: `tools/`
- Historical recorder endpoint notes: `recorder-patch/`
- Windows setup notes: `SETUP-WINDOWS.md`
- Recorder validation notes: `RECORDER-VALIDATION.md`


Version 0.3.18 uses a fixed two-line `SLM / BRIDGE` center title, gives the side status fields more room, and returns to `No / Recorder` after an intentional STOP. Version 0.3.15 introduced the three-zone header layout. Version 0.3.10 kept the earlier title prefix steady while only the active status blinked. Version 0.3.8 refines SLM recorder discovery: the bridge requests a scan and uses the scan-results broadcast when Android provides fresh results. Cached results remain a fallback for throttled scans, but a recorder SSID that just failed connection is temporarily hidden until a fresh scan sees it again or the short suppression period expires.

Version 0.3.11 reduces the recorder Wi-Fi connection timeout to 30 s while keeping the existing fresh-scan and stale-SSID suppression behavior.

Version 0.3.12 adds the SLM Bridge launcher icon and updates the Android launcher label to `SLM Bridge`.

Version 0.3.13 reduces the launcher-icon artwork inside the adaptive-icon safe area so the home-screen icon is not cropped by Android launcher masks.

Version 0.3.14 keeps the existing recorder Wi-Fi discovery/connection workflow unchanged and improves the server side of network routing. The bridge now selects a validated non-recorder Internet network for server status and Drive uploads while the recorder WebView remains on the local recorder Wi-Fi.


Version 0.3.18 keeps `No / Recorder` for the normal stopped state, displays the fixed center title on two lines as `SLM / BRIDGE`, gives the side status fields more room, and reserves `Disconnected` for unexpected recorder loss. Version 0.3.15 changed the top window layout: centered recorder state on the left, fixed two-line `SLM / BRIDGE` title in the center, and centered server state on the right. The recorder discovery/connection and Internet-routing behavior from 0.3.14 is unchanged. The queue area below CONNECT/STOP is compact and shows `File Queue Empty`, queued file count, or `Transferring File (x/y)` with progress.

## Upload and queue behavior in 0.3.22

Version 0.3.22 removes the obsolete foreground upload retry from 0.3.19 and keeps the upload-latency and progress improvements. While a file is uploading, the queue line remains `Transferring File (x/y)` and the progress bar is updated while bytes are written. Pending files are displayed as `File Queue: n files waiting`. The bridge keeps Drive authorization and resolved Drive folder IDs for a short idle grace period between files, and uses 8 MiB Drive upload chunks so typical recorder files upload in a single Drive PUT request. Upload attempts are event-driven only; the previous 5-second foreground retry loop was removed to avoid unnecessary queue-status refreshes.

## Firmware from server in 0.3.24

The recorder Firmware page now controls server firmware installation through the Android WebView. The bridge advertises the `server-firmware` capability, lists firmware files from Drive, downloads the selected file over the validated Internet network, and uploads it to the recorder `/api/ota` endpoint over recorder Wi-Fi. The search order is `<registration>/FIRMWARE` first, then `SLM-STC-DATA/FIRMWARE` as fallback. The bridge does not call the feature "latest firmware" because older versions may intentionally be selected for recovery or test work.


### Firmware from server note

The bridge searches recorder-specific firmware folders using both the canonical registration folder name (for example `F-CJAF/FIRMWARE`) and the compact five-character recorder registration folder name (for example `FCJAF/FIRMWARE`) before falling back to `SLM-STC-DATA/FIRMWARE`. Folder-name matching for `FIRMWARE` is case-insensitive. Firmware `.bin` files up to 32 MiB are accepted; the recorder OTA endpoint remains the final authority for whether the image fits the device.

Server firmware files are usually placed in Drive manually, so the recorder Drive OAuth credential must include `drive.readonly` in addition to `drive.file`. Existing credentials minted with only `drive.file` can upload bridge-created files but cannot reliably list or download browser-created firmware files. Regenerate and reprovision the recorder Drive configuration after changing this scope.
