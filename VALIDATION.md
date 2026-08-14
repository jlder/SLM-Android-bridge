# SLM Bridge v0.3.44 validation

## Scope

This validation file covers the current SLM Bridge v0.3.44 baseline. The v0.3.44 change is the live queue-total refresh when additional recorder files complete analysis and become upload-ready; the earlier integrity, durable-queue, upload-verification, and recorder-archive checks remain applicable.

## Normal immutable file

1. Record a new immutable `.bin` with companion `.sha`.
2. Process it through the Bridge.
3. Confirm recorder SHA verification succeeds.
4. Confirm upload reaches 100%.
5. Confirm the recorder archives the `.bin` and `.sha` only after Drive verification.
6. Confirm the Drive file size equals the local file size.
7. Confirm the Drive `sha256Checksum` equals the recorder/Bridge SHA-256 of the `.bin`.

## Legacy file

1. Place a valid legacy `.bin` without `.sha` in the recorder root.
2. Process it.
3. Confirm it is accepted as legacy.
4. Confirm Drive size and SHA-256 are still verified before recorder archiving.

## Failure cases

- Simulate a Drive size mismatch: upload must not be marked complete and recorder archive must not be requested.
- Simulate a Drive SHA-256 mismatch: upload must remain pending and the local transfer file must be retained.
- Simulate unavailable `sha256Checksum`: upload must remain pending.
- Restart the Bridge after a completed upload but before local completion state is stored: the duplicate lookup must verify Drive size/SHA-256 and then complete without re-uploading.
- Temporarily remove Internet access after recorder download: the item must remain queued and later upload/verify normally.

## Expected integrity chain

- New immutable files: recorder SHA-256 = Bridge read SHA-256; Bridge local SHA-256 = Drive server SHA-256.
- Legacy files: Bridge read SHA-256 establishes the transfer identity; Bridge local SHA-256 = Drive server SHA-256.


## v0.3.35 — compact transfer status

- The Bridge transfer information is displayed on one line.
- The left field reports `File upload <percent>%` or `File upload None`.
- The right field reports `File Queue <current>/<total>` or `File Queue Empty`.
- Upload and queue behavior are unchanged.

## v0.3.36 — recorder archive endpoint

Confirm a verified upload is archived through `POST /api/archive` and that queue retry behavior remains unchanged.


## v0.3.44 — live queue-total refresh

1. Process a recorder day containing at least three physical `.bin` files while Internet upload is available.
2. While the first file is still uploading, allow the second and third files to complete recorder transfer and browser analysis.
3. Confirm the Bridge queue display expands immediately as each analysed file becomes upload-ready, e.g. `File Queue 1/1` -> `File Queue 1/2` -> `File Queue 1/3`, without pressing STOP or causing a network change.
4. Confirm the current-file position continues to advance normally as uploads complete and no file is uploaded twice.
5. Repeat with Internet temporarily unavailable and confirm queued items remain durable and upload normally when Internet returns.
