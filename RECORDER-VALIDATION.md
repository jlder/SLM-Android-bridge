# Recorder-local validation checklist

Use a disposable recording/file for archive tests. Do not use the only
copy of important recorder data.

## Connection and interface

- Start the app: it must remain disconnected and the title must read
  `No / Recorder` in the left recorder-status field.
- Switch the recorder Wi-Fi on and confirm that the recorder SSID is visible as
  `SLM-` plus five uppercase alphanumeric characters, for example `SLM-FCJAF`.
- Select **CONNECT** and confirm the left status changes to blinking `Searching / Recorder` while the app scans. If several matching SLM recorders are visible, select the
  recorder under test from the list.
- Accept Android's Wi-Fi request if shown.
- Confirm the left status becomes blinking `Connecting / F-CJAF`
  while Android connects and the bridge waits for `/api/status`.
- Confirm the left status then becomes `F-CJAF / Connected` style text and the recorder
  home page opens automatically.
- Confirm there is no **RELOAD** or **CONF** button in the bridge header.
- Confirm the server-upload status is displayed below the centered **CONNECT/STOP**
  button as two fields on a white background: left `Server Off-line` in amber or
  `Server Connected` in green, right `File Queue Empty`, `File Queue: n files waiting`, or `Transferring File (x/y)` with the progress bar active.
  With cellular/Internet already available before pressing **CONNECT**, this
  server field should remain connected while the bridge searches for and joins
  the recorder Wi-Fi.
- Select **STOP** and confirm the interface closes. Press **CONNECT** again and
  confirm a new scan/connection attempt starts.
- With the bridge connected, stop recorder Wi-Fi. Confirm that the bridge reports
  the lost recorder connection, clears the recorder WebView, and returns to
  `No / Recorder` in the left recorder-status field.
- With recorder Wi-Fi stopped, press **CONNECT** again and confirm that a fresh
  scan does not offer the stopped recorder. If Android returns cached scan results
  and the stopped recorder is selected, confirm that after connection failure the
  same SSID is temporarily suppressed on the next **CONNECT**.

## Configuration and calibration

- Open each configuration screen and read its current values.
- Change a harmless test value, save it, reload the page, and confirm persistence.
- Run each calibration action used in normal operation.
- Confirm its success/failure message appears and its result survives a page reload.

## Recorder SD file management

- Refresh/list files and confirm the displayed list matches the recorder SD card.
- Open an existing analysis log and confirm its contents display.
- Analyze a disposable recording and confirm the analysis and resulting log.
- Archive a disposable file, accept the JavaScript confirmation, refresh the list,
  and confirm the recorder performed the intended SD-card state change.
- Archive a disposable file through the verified upload workflow, refresh the list,
  and confirm it is absent.

## Downloads to the phone

- Select a normal recorder download action.
- Confirm Android opens a **Save as** document picker.
- Select a destination and wait for the `<filename> saved` message.
- Open the saved file using Android's Files application and compare its size with
  the recorder file.
- Cancel a second **Save as** operation and confirm no download is performed.
- Confirm recorder analysis does not open a second **Save as** picker for the
  desktop-browser blob after Android's native transfer has completed.

## App-private analysis and Drive upload

- Confirm the recorder provides `GET /api/slm-drive-config` as documented in
  `recorder-patch/drive-credential-endpoint.md` before testing uploads.
- Start an analysis/download action that uses `SLMAndroid.downloadRecorderFile`.
- Confirm the file is analyzed without an Android **Save as** dialog. This native
  copy is intentionally private to the app and transparent to the pilot.
- With cellular data unavailable, confirm the recorder UI reports that the Drive
  upload is pending while local analysis remains usable.
- Restore cellular data and confirm the file appears under
  `SLM-STC-DATA/<registration>` in Drive with its original recorder filename.
- Repeat the same transfer and confirm SHA-256 duplicate detection does not create
  another Drive file.
- Restart the phone with an upload pending, reconnect to the recorder, and confirm
  the durable queue resumes the upload when cellular data is ready.

## Firmware OTA file selection

- Open the recorder's firmware OTA page and select its file button.
- Confirm Android's file picker opens.
- Select a disposable/known-valid firmware test file from the phone.
- Confirm the recorder UI displays the selected filename before starting any update.
- Cancel once and confirm the recorder does not begin an OTA update.
- Perform an actual OTA only under the recorder project's normal recovery and power
  precautions; file-picker validation alone does not require flashing firmware.

## Failure checks

- Cancel an archive confirmation and confirm no recorder change occurs.
- Disconnect during a disposable download and confirm a failure message appears.
- Try a function with the recorder SD card unavailable/read-only, if the recorder
  supports that safe test, and confirm the Web UI reports the failure.

## Diagnostics

For any failure, open Android Studio **Logcat**, select the phone and the app, and
filter for `SLM-Web`. Record the button pressed, visible message, and the matching
console/HTTP error. Debug logging is compiled out of release builds.

- Confirm that `CONNECT` uses fresh Android scan results when available, while still detecting a visible SLM recorder through recent cached results if Android throttles the app-initiated scan.

Additional validation for 0.3.11: with a stale recorder SSID selected, the bridge should leave `Connecting / ...` and return to a disconnected recorder state after about 30 s, not 60 s.

Additional validation for 0.3.12: after installing the APK, confirm that Android shows the SLM Bridge launcher icon and the app label `SLM Bridge`.

Additional validation for 0.3.13: after installing the APK, confirm that the launcher icon is no longer cropped by the Android home-screen mask.


Additional validation for 0.3.14: with mobile data available and showing `Server Connected`, press **CONNECT** and connect to recorder Wi-Fi. The recorder discovery/selection behavior should be unchanged, and `Server Connected` should not drop merely because the WebView is joining the recorder Wi-Fi.


Additional validation for 0.3.18: verify the black header shows `No / Recorder` before connection, the center title appears as two fixed lines `SLM / BRIDGE`, and the side status fields are wide enough for `Disconnected`. During search/connection only the left recorder status blinks. Press STOP and confirm the left status returns to `No / Recorder`; reserve `F-CJAF / Disconnected` for unexpected recorder loss. Confirm the area below CONNECT/STOP is compact and shows `File Queue Empty` when idle and `Transferring File (x/y)` with a progress bar during upload.

## Additional validation for 0.3.22

Download one recorder file while cellular/Internet is available and confirm that the queue changes from `File Queue: 1 file waiting` to `Transferring File (1/1)` without requiring another recorder reconnect or Android network-change event. Download a second file while the first upload is active and confirm the display remains `Transferring File (1/2)` then `Transferring File (2/2)`, with progress movement during upload. Repeat after temporarily disabling and re-enabling cellular data; the queued upload should retry automatically once the server status returns to connected. Confirm that the queue status does not flicker every 5 seconds while waiting.

## Firmware from server validation — 0.3.24

1. Connect the bridge to a recorder and unlock recorder Maintenance.
2. Open Firmware Update in the recorder Web UI.
3. Press **Firmware from Server**.
4. Verify that files from `<registration>/FIRMWARE` are listed when that folder exists and contains recorder `.bin` files.
5. Remove or empty that folder and verify fallback to `SLM-STC-DATA/FIRMWARE`.
6. Select a firmware file and press **Upload Selected Firmware** with USB power connected to the recorder.
7. Verify download progress, recorder upload progress, recorder OTA success, and automatic recorder reboot.
8. Repeat with USB power disconnected and verify that the recorder rejects the OTA upload.


### Firmware from server note

The bridge searches recorder-specific firmware folders using both the canonical registration folder name (for example `F-CJAF/FIRMWARE`) and the compact five-character recorder registration folder name (for example `FCJAF/FIRMWARE`) before falling back to `SLM-STC-DATA/FIRMWARE`. Folder-name matching for `FIRMWARE` is case-insensitive. Firmware `.bin` files up to 32 MiB are accepted; the recorder OTA endpoint remains the final authority for whether the image fits the device.

Before testing with firmware files added through the Google Drive browser, confirm that the recorder Drive credential was regenerated with both `drive.file` and `drive.readonly`. A credential minted with only `drive.file` may still upload recorder files but may not see manually staged firmware files.

## Process-button lock validation for 0.3.28

1. Press **Process** for one recorder file and confirm only that file's button becomes grey immediately.
2. Confirm the label follows Downloading, Analyzing, Queued, Uploading, and Finalizing as applicable.
3. Reload the recorder page while the item is queued and confirm the button remains grey.
4. Attempt a duplicate request from another WebView/page and confirm the bridge rejects it.
5. Interrupt Internet access while queued or uploading and confirm the button remains grey while automatic retry is pending.
6. Force a download or analysis failure and confirm the transfer is cleared and the blue **Process** button returns.
7. Complete upload and archive and confirm the root-file row disappears without briefly becoming active again.

## Android navigation-bar clearance validation for 0.3.29

1. Configure the Android device to use three-button navigation.
2. Open SLM Bridge in portrait orientation.
3. Verify that the bottom of the recorder Web page stops above the Back, Home, and Recent Apps buttons.
4. Verify that the recorder page can still be scrolled to its last control.
5. Repeat with gesture navigation and verify that no unnecessary fixed blank area is added beyond the inset reported by Android.

### Message-dialog and file-processing validation (0.3.30)

- Confirm that permission, connection, recorder-not-found, recorder-not-responding, disconnection, invalid-Wi-Fi, file-selection and download-failure messages remain visible until OK is pressed and wrap within the dialog.
- With two root `.bin` files, start Process on one file. Confirm the other Process button is grey during Downloading and Analyzing, becomes blue again at Queued, and also becomes blue again after a download or analysis failure.


## v0.3.31 recorder creation-SHA verification

Before downloading a recorder `.bin`, the Bridge requests the companion `.sha`. If present, it validates metadata, file length, and SHA-256 before analysis or upload. A missing `.sha` is accepted as a legacy file and remains protected by the Bridge-to-server transfer SHA. Malformed metadata or a size/SHA mismatch stops processing and leaves the recorder file available for investigation.
