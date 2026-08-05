# SLM Bridge v0.3.40 validation

## Scope

This release adds a visible Bridge version below the `SLM / BRIDGE` title and slightly reduces the title font size. The hidden five-tap diagnostics trigger remains attached to the large title.

## Checks

1. Build and install the signed release APK.
2. Confirm the center header shows:
   - `SLM` and `BRIDGE` on two lines;
   - `v0.3.40` directly below.
3. Confirm the recorder and server status blocks remain readable and aligned.
4. Tap the large `SLM / BRIDGE` title five times within three seconds.
5. Confirm the Integrity diagnostics dialog opens.
6. Tap the displayed version five times and confirm it does not open diagnostics.
7. Confirm Connect, recorder connection, file processing and upload behavior are unchanged.
8. Verify the APK signer certificate is unchanged from the permanent release certificate.

## Compile compatibility correction

The visible version is read from Android package metadata rather than the generated `BuildConfig` class. This avoids build configurations where `BuildConfig` generation is unavailable.
