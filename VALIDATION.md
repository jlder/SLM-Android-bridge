# SLM Bridge v0.3.31 validation

## Purpose

Validate recorder creation-SHA verification before browser analysis and upload.

## New immutable file with SHA

1. Record and stop a new session with recorder v1.32 or later.
2. Confirm matching `.bin` and `.sha` files are present in the recorder root.
3. Press **Process** for the `.bin` file.
4. Confirm download completes, analysis starts, and normal queue/upload behavior follows.
5. Confirm Bridge transfer metadata records `integrityStatus=creation-verified`.

## Legacy file without SHA

1. Place or retain a legacy `.bin` without a companion `.sha` in the root.
2. Press **Process**.
3. Confirm processing continues normally.
4. Confirm Bridge transfer metadata records `integrityStatus=legacy`.

## SHA mismatch

1. Create a valid `.bin`/`.sha` pair.
2. Change one byte in the `.bin` without changing `.sha`.
3. Press **Process**.
4. Confirm the Bridge reports `Recorder file SHA changed since creation`.
5. Confirm analysis and upload do not start and the recorder file remains available.

## Size mismatch

1. Edit the `size=` value in a valid `.sha` file.
2. Press **Process**.
3. Confirm the Bridge reports `Recorder file size changed since creation`.
4. Confirm analysis and upload do not start.

## Invalid metadata

Test an invalid format, filename, size, and SHA text. Each must stop processing before analysis/upload.

## Real-file parser validation

The four supplied `FCBBB_20260803_1` through `_4` `.bin`/`.sha` pairs were independently checked. All metadata filenames, sizes, and SHA-256 values match.
