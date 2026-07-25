# Windows setup — Android Studio Quail 2

1. In Android Studio, choose **Open** and select this `slm-android-bridge` directory.
2. If Android Studio asks whether to trust the project, choose **Trust Project**.
3. Let the Gradle sync complete. The project includes a Gradle 8.13 wrapper and uses Android Gradle Plugin 8.13.2.
4. If Android Studio asks for a Gradle JDK, select its bundled JetBrains Runtime (`jbr-21`) or `GRADLE_LOCAL_JAVA_HOME`.
5. Confirm that Android SDK Platform 35 and SDK Build-Tools 35.0.0 are installed if prompted.
6. Select the working **Medium Phone API 35** virtual device for a UI smoke test, then run the `app` configuration.

For actual recorder Wi-Fi plus cellular-routing tests, use an Android 10 or newer physical phone. The emulator is suitable for checking that the project builds and launches, but it does not reproduce the required recorder Wi-Fi/cellular concurrency reliably.

On first launch, grant the nearby Wi-Fi permission. Open **Connection settings** and enter:

- Recorder Wi-Fi SSID containing the glider registration (for example
  `SLM-F-ABCD`)
- Recorder Wi-Fi password
- Recorder URL (default: `http://192.168.4.1`)

The recorder UI needs the integration change in
`recorder-patch/web-ui-android-bridge.patch`. The recorder firmware must also
provide the private Drive configuration endpoint described in
`recorder-patch/drive-credential-endpoint.md` before upload can work end to end.
