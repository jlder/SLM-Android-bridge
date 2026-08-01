# Windows setup — Android Studio Quail 2

1. In Android Studio, choose **Open** and select this `slm-android-bridge` directory.
2. If Android Studio asks whether to trust the project, choose **Trust Project**.
3. Let the Gradle sync complete. The project includes a Gradle 8.13 wrapper and uses Android Gradle Plugin 8.13.2.
4. If Android Studio asks for a Gradle JDK, select its bundled JetBrains Runtime (`jbr-21`) or `GRADLE_LOCAL_JAVA_HOME`.
5. Confirm that Android SDK Platform 35 and SDK Build-Tools 35.0.0 are installed if prompted.
6. Select the working **Medium Phone API 35** virtual device for a UI smoke test, then run the `app` configuration.

For actual recorder Wi-Fi plus cellular-routing tests, use an Android 10 or newer physical phone. The emulator is suitable for checking that the project builds and launches, but it does not reproduce the required recorder Wi-Fi/cellular concurrency reliably.

On first launch, grant the nearby Wi-Fi and location permissions used for recorder discovery.
No recorder connection settings are entered manually. Select **CONNECT** and the app requests
an Android scan for recorder Wi-Fi names matching `SLM2-` plus five uppercase alphanumeric
characters, for example `SLM2-FCJAF`. The left recorder-status field shows `No / Recorder` before **CONNECT**, blinking `Searching / Recorder` while Android refreshes Wi-Fi scan results, blinking `Connecting / F-CJAF` while Android joins/probes the selected recorder, and `F-CJAF / Connected` once the recorder answers. If several
recorders are visible, select the recorder to use. The Wi-Fi password is generated
automatically as `SLM` plus the reversed five-character registration from the SSID, for
example `SLM2-FCJAF` -> `SLMFAJCF`. The bridge header has no **CONF** or **RELOAD** button; it
shows a recorder-style black header with centered recorder status on the left, fixed two-line `SLM / BRIDGE` title in the center, centered server status on the right, one centered blue **CONNECT/STOP** button, and a compact file-queue/progress line below the button. The bridge periodically checks the recorder after
connection and shows the recorder as disconnected if it stops answering.
Server status and Drive uploads use an explicitly selected validated Internet
network, so they remain separate from the recorder Wi-Fi used by the WebView.

The recorder UI needs the integration change in
`recorder-patch/web-ui-android-bridge.patch`. The recorder firmware must also
provide the private Drive configuration endpoint described in
`recorder-patch/drive-credential-endpoint.md` before upload can work end to end.

Bridge 0.3.18 shows `No / Recorder` before connection, uses a two-line fixed `SLM / BRIDGE` center title, and returns to `No / Recorder` after an intentional STOP. Rebuild/reinstall before repeating Wi-Fi discovery tests.

For release 0.3.11, verify that unavailable recorder Wi-Fi connection attempts time out after about 30 s.

For release 0.3.12, rebuild the signed APK and confirm that the installed app uses the SLM Bridge launcher icon and label.

For release 0.3.13, rebuild the signed APK and confirm that the installed app icon is not cropped by the Android home-screen mask.

After using Android Studio **Generate Signed Bundle / APK**, the signed APK may be placed in `app\release\app-release.apk` or in `app\build\outputs\apk\release\app-release.apk`. Use the Android Studio **locate** link to confirm the exact output location. Distribute `app-release.apk`, not `output-metadata.json`.

For release 0.3.14, verify on a physical phone that connecting to recorder Wi-Fi does not make the server status temporarily fall to `Server Off-line` when mobile data or another validated Internet network is available.


For release 0.3.26, rebuild the signed APK and check the recorder Firmware page on a physical phone. The APK produced by Android Studio may be under `app\release\app-release.apk` or `app\build\outputs\apk\release\app-release.apk`; use the Android Studio locate link to confirm.

## 0.3.22 upload and queue check

After rebuilding the signed APK, download a recorder file and confirm that a queued file starts uploading automatically when the server status is connected. Upload starts are event-driven: file download completion, Internet availability, and completion of the previous queued upload. During an active upload, network-status refreshes should not replace `Transferring File (x/y)` with a queue-only message. Pending files should display as `File Queue: n files waiting`, and the progress bar should move during upload.

## Firmware from server test data

For bridge version 0.3.24 and later, server firmware files are ordinary recorder application `.bin` files stored in Google Drive. Put glider-specific test firmware in `<registration>\FIRMWARE` under the `SLM-STC-DATA` Drive folder. If the recorder-specific folder is absent or empty, the bridge uses the common `SLM-STC-DATA\FIRMWARE` folder. Keep merged binaries out of these folders; the recorder OTA endpoint accepts application `.bin` files only.


### Firmware from server note

The bridge searches recorder-specific firmware folders using both the canonical registration folder name (for example `F-CJAF/FIRMWARE`) and the compact five-character recorder registration folder name (for example `FCJAF/FIRMWARE`) before falling back to `SLM-STC-DATA/FIRMWARE`. Folder-name matching for `FIRMWARE` is case-insensitive. Firmware `.bin` files up to 32 MiB are accepted; the recorder OTA endpoint remains the final authority for whether the image fits the device.

Because these firmware files are placed in Drive manually, the OAuth credential used by the recorder must include firmware read access. Run `tools/oauth-bootstrap.ps1` with the current sources, then `tools/export-recorder-drive-config.ps1`, and reprovision/rebuild the recorder so the bridge receives a credential with both `drive.file` and `drive.readonly`.
