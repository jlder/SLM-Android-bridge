# SLM Bridge v0.3.37 integrity diagnostics validation

## Purpose

Confirm that the Bridge emits explicit, grep-friendly evidence for every stage of the recording integrity chain without changing the normal user interface or transfer behavior.

## Log tag

All new messages use the Android log tag:

```text
SLMIntegrity
```

View only these messages with Android Studio Logcat by filtering on `tag:SLMIntegrity`, or with ADB:

```powershell
adb logcat -s SLMIntegrity:I *:S
```

Clear old evidence before a test:

```powershell
adb logcat -c
```

## Expected new immutable-file sequence

For a valid recorder-created `.bin`/`.sha` pair, expect the same filename, size and SHA-256 in this order:

```text
event=RECORDER_CREATION_SHA_VERIFIED
event=RECORDER_DOWNLOAD_COMPLETE
event=DRIVE_SHA_VERIFIED_UPLOAD
event=RECORDER_ARCHIVE_COMPLETE
```

If an identical file already exists in Drive, the Drive event is:

```text
event=DRIVE_SHA_VERIFIED_EXISTING
```

## Expected legacy-file sequence

For a legacy `.bin` without creation metadata:

```text
event=LEGACY_CREATION_SHA_UNAVAILABLE
event=RECORDER_DOWNLOAD_COMPLETE
event=DRIVE_SHA_VERIFIED_UPLOAD
event=RECORDER_ARCHIVE_COMPLETE
```

The download event must show `mode=legacy`.

## Failure tests

Intentional integrity or workflow failures emit:

```text
event=INTEGRITY_FAILURE stage=RECORDER-DOWNLOAD
event=INTEGRITY_FAILURE stage=DRIVE-UPLOAD-OR-VERIFICATION
event=INTEGRITY_FAILURE stage=RECORDER-ARCHIVE
```

Expected behavior remains unchanged: failed files are not falsely marked complete or archived.

## Regression checks

- The normal Bridge UI remains unchanged.
- Recorder Web processing events remain unchanged.
- Queue retry behavior from v0.3.34 remains present.
- Compact status UI from v0.3.35 remains present.
- `/api/archive` from v0.3.36 remains present.
- No `.sha` or diagnostic status file is uploaded to Drive.
