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
- Current source version: 0.3.13 (`versionCode` 16)

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
- The title displays `SLM BRIDGE - Not Connected` when no recorder is selected,
  blinking `SLM BRIDGE - Searching` during the Wi-Fi scan, blinking
  `SLM BRIDGE - Connecting F-CJAF` while Android is joining/probing the recorder,
  and steady `SLM BRIDGE - F-CJAF` after the recorder answers.
- After Wi-Fi connects, the app probes `/api/status` and opens the recorder
  interface automatically when the recorder answers. It then continues periodic
  `/api/status` checks and disconnects if the recorder stops answering.
- Repeated **STOP** then **CONNECT** actions invalidate any stale Android network
  callbacks from the previous connection request.
- Recorder downloads are retained in app-private storage and queued by
  registration.
- Pending uploads continue when Internet becomes available, including when
  the phone is away from the recorder.
- Successful binary uploads trigger recorder-side archive handling.
- Calibration reports are queued to the registration's reports folder.
- Server-upload status is split into a left server-availability field and a right
  queue-progress field. The left field shows `Server Off-line` in amber or
  `Server Connected` in green. The right field shows `File Queue Empty` or
  `File Queue x/y (z%)`.

## Current validation state

Version 0.3.9 adds blinking feedback for the `Searching` and `Connecting ...`
title states and splits server-upload status into server availability plus queue
progress. Version 0.3.8 waits for Android's scan-results broadcast, keeps the
explicit `Not Connected`, `Searching`, `Connecting ...`, and connected-registration
title states, and temporarily suppresses recorder SSIDs that just failed connection.
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


Version 0.3.10 refines the operator status area: the fixed `SLM BRIDGE -` prefix stays steady while only the `Searching` or `Connecting ...` suffix blinks. Version 0.3.9 splits server-upload status into server availability and file-queue progress. Version 0.3.8 refines SLM recorder discovery: the bridge requests a scan and uses the scan-results broadcast when Android provides fresh results. Cached results remain a fallback for throttled scans, but a recorder SSID that just failed connection is temporarily hidden until a fresh scan sees it again or the short suppression period expires.

Version 0.3.11 reduces the recorder Wi-Fi connection timeout to 30 s while keeping the existing fresh-scan and stale-SSID suppression behavior.

Version 0.3.12 adds the SLM Bridge launcher icon and updates the Android launcher label to `SLM Bridge`.

Version 0.3.13 reduces the launcher-icon artwork inside the adaptive-icon safe area so the home-screen icon is not cropped by Android launcher masks.
