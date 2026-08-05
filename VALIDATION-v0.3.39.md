# SLM Bridge v0.3.39 validation

## Purpose

Hide integrity diagnostics from normal users while retaining service access.

## Expected UI

- The visible `DIAGNOSTICS` button is absent.
- `CONNECT` uses the full button row width.
- Normal connection, transfer and queue behaviour is unchanged.

## Hidden diagnostics access

1. Open SLM Bridge.
2. Tap the large `SLM / BRIDGE` title five times within three seconds.
3. Confirm the existing Integrity diagnostics dialog opens.
4. Confirm Export, Clear and Close still work.

## Reset behaviour

- Four taps followed by a delay longer than three seconds must not open diagnostics.
- Pressing Connect resets a partially completed tap sequence.
- After the dialog opens, a new sequence of five taps is required to open it again.

## Regression checks

- Connect to a recorder.
- Process one immutable recording.
- Confirm the transfer completes and the file is archived.
- Reopen diagnostics using five title taps and confirm the events are present.
