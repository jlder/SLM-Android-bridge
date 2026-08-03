# SLM Bridge v0.3.32 validation

## Scope

This release adds Google Drive stored-file verification after upload. It does not change recorder SHA metadata, browser analysis, or File Management grouping.

## Normal immutable file

1. Record a new immutable `.bin` with companion `.sha`.
2. Process it through the Bridge.
3. Confirm recorder SHA verification succeeds.
4. Confirm upload reaches 100%.
5. Confirm the recorder archives the `.bin` and `.sha` only after Drive verification.
6. Confirm the Drive file size equals the local file size.
7. Confirm the Drive `md5Checksum` equals a PC-computed MD5 of the `.bin`.

## Legacy file

1. Place a valid legacy `.bin` without `.sha` in the recorder root.
2. Process it.
3. Confirm it is accepted as legacy.
4. Confirm Drive size and MD5 are still verified before recorder archiving.

## Failure cases

- Simulate a Drive size mismatch: upload must not be marked complete and recorder archive must not be requested.
- Simulate a Drive MD5 mismatch: upload must remain pending and the local transfer file must be retained.
- Simulate unavailable `md5Checksum`: upload must remain pending.
- Restart the Bridge after a completed upload but before local completion state is stored: the duplicate lookup must verify Drive size/MD5 and then complete without re-uploading.
- Temporarily remove Internet access after recorder download: the item must remain queued and later upload/verify normally.

## Expected integrity chain

- New immutable files: recorder SHA-256 = Bridge read SHA-256; Bridge local MD5 = Drive server MD5.
- Legacy files: Bridge read SHA-256 establishes the transfer identity; Bridge local MD5 = Drive server MD5.
