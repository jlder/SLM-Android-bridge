# Recorder-local validation checklist

Use a disposable recording/file for archive and delete tests. Do not use the only
copy of important recorder data.

## Connection and interface

- Start the app: it must remain disconnected.
- In **CONF**, confirm the registration shown by the app matches the registration
  embedded in the recorder SSID (for example `SLM-F-ABCD` becomes `F-ABCD`).
- Select **Connect** and accept Android's Wi-Fi request if shown.
- Confirm `Recorder: connected`.
- Select **Launch interface** and confirm the recorder home page loads.
- Select **Disconnect** and confirm the interface closes.

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
- Delete a disposable file, accept the JavaScript confirmation, refresh the list,
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

- Reject an Archive/Delete confirmation and confirm no recorder change occurs.
- Disconnect during a disposable download and confirm a failure message appears.
- Try a function with the recorder SD card unavailable/read-only, if the recorder
  supports that safe test, and confirm the Web UI reports the failure.

## Diagnostics

For any failure, open Android Studio **Logcat**, select the phone and the app, and
filter for `SLM-Web`. Record the button pressed, visible message, and the matching
console/HTTP error. Debug logging is compiled out of release builds.
