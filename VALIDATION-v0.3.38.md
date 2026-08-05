# SLM Bridge v0.3.38 validation

## Build and version

1. Build the release APK.
2. Confirm the app displays version `0.3.38` and `versionCode 39` is present in `app/build.gradle`.
3. Sign the APK with the established SLM Bridge release key.

## Persistent diagnostics normal case

1. Install/start the Bridge and press **DIAGNOSTICS** before processing any new file.
2. Confirm the dialog opens and shows either previous events or `No integrity events recorded.`
3. Create one new recorder file with a valid companion `.sha`.
4. Process the file and allow upload/archive to complete.
5. Disconnect from the recorder and return to normal Internet Wi-Fi if required.
6. Press **DIAGNOSTICS**.
7. Confirm the same filename has these events in order:
   - `RECORDER_CREATION_SHA_VERIFIED`
   - `RECORDER_DOWNLOAD_COMPLETE`
   - `DRIVE_SHA_VERIFIED_UPLOAD` or `DRIVE_SHA_VERIFIED_EXISTING`
   - `RECORDER_ARCHIVE_COMPLETE`
8. Confirm each event contains size, SHA-256 and integrity mode.

## Legacy file

1. Process a legacy `.bin` without creation `.sha`.
2. Confirm the diagnostics contain:
   - `LEGACY_CREATION_SHA_UNAVAILABLE`
   - `RECORDER_DOWNLOAD_COMPLETE`
   - Drive SHA verification
   - recorder archive completion.

## Failure evidence

1. Use a copied test file with deliberately mismatched SHA metadata.
2. Confirm an `INTEGRITY_FAILURE` event identifies `RECORDER-DOWNLOAD`.
3. Confirm the file is not archived.

## Export

1. Open **DIAGNOSTICS** and press **Export**.
2. Select an email, messaging, Drive, or text-capable application.
3. Confirm the exported text includes the complete displayed event history.
4. Cancel the chooser and confirm the Bridge remains functional.

## Clear and retention

1. Press **Clear**, cancel once, and confirm events remain.
2. Press **Clear** again and confirm; verify the dialog displays `No integrity events recorded.`
3. Generate more than 1,000 synthetic/test events if practical or inspect the bounded-retention code.
4. Confirm only the newest 1,000 events are retained.

## Background upload

1. Queue a file and close the Bridge before the Drive upload completes.
2. Allow `UploadJobService` to finish the upload.
3. Reopen the Bridge and confirm Drive-verification events are present in **DIAGNOSTICS**.

## Regression

- Recorder connection and WebView behavior unchanged.
- Upload queue continues without a connectivity toggle.
- Normal UI status line unchanged apart from the added **DIAGNOSTICS** button.
- No diagnostic file is uploaded to Drive.
- Recorder `.sha` metadata format remains unchanged.
