# SLM Bridge v0.3.34 queue retry validation

## Purpose

Validate that immutable files queued while another upload pass is active continue uploading without a Wi-Fi or cellular network toggle.

## Test

1. Create at least three recorder files for the same day.
2. Connect the Bridge and press **Process** once for the visible daily entry.
3. Leave recorder Wi-Fi and mobile data unchanged during the complete test.
4. Confirm the Bridge progresses continuously through all queued files.
5. Confirm all files upload and move to `/processed` without toggling Wi-Fi or mobile data.
6. Repeat after creating three additional files for the same day.

## Expected result

- The first file may display `Transferring File (1/3)`.
- Files becoming analysis-complete while the upload worker is active are remembered.
- The next upload begins automatically after the current retry pass.
- The queue must not remain indefinitely at `File Queue 2/2` or a similar waiting count while Internet is available.
- All files are Drive SHA-256 verified before recorder archiving.

## Regression checks

- With mobile data disabled, files remain queued and upload after Internet returns.
- A genuine upload error remains queued rather than creating a tight automatic retry loop.
- A recorder SHA mismatch still fails before analysis/upload.
