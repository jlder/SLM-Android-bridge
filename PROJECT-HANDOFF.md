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
- Current source version: 0.3.3 (`versionCode` 6)

## Implemented behavior

- Up to two recorder SSID/password profiles.
- A usable profile requires a recognizable `SLM-...` registration and a
  WPA2 password containing 8–63 characters.
- Incomplete profiles are excluded from connection selection.
- Available configured recorders are ranked by Wi-Fi signal when scan
  permission is available.
- One Android recorder-network request is made per Connect action; the app
  does not automatically cycle through profiles and reopen the system dialog.
- After Wi-Fi connects, the app displays `RECORDER CONNECTING` until
  `/api/status` answers, then displays `RECORDER READY <registration>` and
  opens the recorder interface automatically.
- `RELOAD` reloads the recorder interface without rebooting the recorder.
- Recorder downloads are retained in app-private storage and queued by
  registration.
- Pending uploads continue when Internet becomes available, including when
  the phone is away from the recorder.
- Successful binary uploads trigger recorder-side archive handling.
- Calibration reports are queued to the registration's reports folder.
- Queue status is shown as `SERVER UPLOAD: ...`.

## Current validation state

Version 0.3.3 was built successfully using the normal Gradle
`:app:assembleRelease` pipeline and then signed with the existing external
demonstrator key. The package and manifest were verified. Device launch and
workflow validation should be repeated before pilot deployment.

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

