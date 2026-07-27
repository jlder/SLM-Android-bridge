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
an Android scan for recorder Wi-Fi names matching `SLM-` plus five uppercase alphanumeric
characters, for example `SLM-FCJAF`. The title shows `Not Connected` before **CONNECT**,
blinking `Searching` while Android refreshes Wi-Fi scan results, blinking
`Connecting F-CJAF` while Android joins/probes the selected recorder, and `F-CJAF`
once the recorder answers. If several
recorders are visible, select the recorder to use. The Wi-Fi password is generated
automatically as `SLM` plus the reversed five-character registration from the SSID, for
example `SLM-FCJAF` -> `SLMFAJCF`. The bridge header has no **CONF** or **RELOAD** button; it
shows a recorder-style black title bar, one centered blue **CONNECT/STOP** button, and two
server-upload fields below the button: server availability on the left and file-queue
progress on the right. The bridge periodically checks the recorder after
connection and returns to `Not Connected` if the recorder stops answering.

The recorder UI needs the integration change in
`recorder-patch/web-ui-android-bridge.patch`. The recorder firmware must also
provide the private Drive configuration endpoint described in
`recorder-patch/drive-credential-endpoint.md` before upload can work end to end.

Bridge 0.3.10 keeps the `SLM BRIDGE -` title prefix fixed and blinks only the searching/connecting suffix. Bridge 0.3.9 splits server-upload status into server availability plus file-queue progress. Rebuild/reinstall before repeating Wi-Fi discovery tests.

For release 0.3.11, verify that unavailable recorder Wi-Fi connection attempts time out after about 30 s.

For release 0.3.12, rebuild the signed APK and confirm that the installed app uses the SLM Bridge launcher icon and label.

For release 0.3.13, rebuild the signed APK and confirm that the installed app icon is not cropped by the Android home-screen mask.

After using Android Studio **Generate Signed Bundle / APK**, the signed APK may be placed in `app\release\app-release.apk` or in `app\build\outputs\apk\release\app-release.apk`. Use the Android Studio **locate** link to confirm the exact output location. Distribute `app-release.apk`, not `output-metadata.json`.
