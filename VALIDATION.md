# SLM Bridge v0.3.48 validation

## Scope

This validation file covers the current SLM Bridge v0.3.48 baseline, including the v0.3.42 Android Location prerequisite, the v0.3.43 fresh-scan/retry behavior, the v0.3.44 live queue-total refresh, the v0.3.45 French/English localization and UI cleanup, and the v0.3.46 recorder-reachability/VPN diagnostics. The earlier integrity, durable-queue, upload-verification, and recorder-archive checks remain applicable.

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
- Confirm the current English display uses `File upload: <percent>%        File queue: <current>/<total>` or `File upload: none        File queue: empty`.
- Confirm the current French display uses `Envoi fichier: <percent> %        File d’attente: <current>/<total>` or `Envoi fichier: aucun        File d’attente: vide`.
- Confirm the larger separation between upload and queue fields remains visible and stable.
- Upload and queue behavior are unchanged.

## v0.3.36 — recorder archive endpoint

Confirm a verified upload is archived through `POST /api/archive` and that queue retry behavior remains unchanged.


## v0.3.42 — Location prerequisite and recovery

1. With all Bridge permissions granted, disable Android system Location and leave recorder Wi-Fi on.
2. Press **CONNECT**. Confirm the Bridge does not immediately report `Recorder not found`; it must display **Location must be enabled** and explain that Android requires Location for nearby Wi-Fi discovery.
3. Confirm the message states that SLM Bridge does not use or record the phone's geographical position.
4. Press **LOCATION SETTINGS** and confirm Android opens the system Location settings page.
5. Enable Location and return to SLM Bridge. Confirm recorder discovery resumes automatically and the recorder can be found and connected.
6. Repeat with Location still disabled and press **CANCEL**; confirm the Bridge remains disconnected without starting a scan.

## v0.3.43 — fresh scan, cached fallback, and retry

1. With permissions and system Location enabled and recorder Wi-Fi on, press **CONNECT**. Confirm the Bridge shows the searching state while waiting for scan results rather than immediately declaring that no recorder was found.
2. With a normal fresh scan, confirm the recorder is discovered and connection proceeds normally.
3. Turn recorder Wi-Fi off and perform a fresh scan. Confirm a genuine fresh result with no recorder produces **Recorder not found** with instructions to check recorder Wi-Fi and retry CONNECT.
4. Exercise a case where Android refuses/throttles `startScan()` but a recent valid recorder result exists. Confirm the Bridge can use the recent cached result rather than failing immediately.
5. Exercise a case where Android refuses/throttles the scan and no usable recent recorder result exists. After the scan timeout, confirm the Bridge displays **Wi-Fi scan temporarily unavailable** with **RETRY** and does not misreport `Recorder not found`.
6. Press **RETRY** after scan service becomes available and confirm recorder discovery restarts.
7. After a recorder connection attempt has just failed, repeat discovery while only a stale cached result for that recorder is available. Confirm the recently failed recorder is temporarily suppressed and is not immediately offered again from stale scan data.

## v0.3.44 — live queue-total refresh

1. Process a recorder day containing at least three physical `.bin` files while Internet upload is available.
2. While the first file is still uploading, allow the second and third files to complete recorder transfer and browser analysis.
3. Confirm the Bridge queue display expands immediately as each analysed file becomes upload-ready, e.g. `File Queue 1/1` -> `File Queue 1/2` -> `File Queue 1/3`, without pressing STOP or causing a network change.
4. Confirm the current-file position continues to advance normally as uploads complete and no file is uploaded twice.
5. Repeat with Internet temporarily unavailable and confirm queued items remain durable and upload normally when Internet returns.

## v0.3.45 — French/English localization and cleanup

1. Set the phone/app language to French and restart SLM Bridge. Confirm the main Bridge status, CONNECT/STOP controls, recorder/server status, normal user-facing dialogs, Wi-Fi/location messages, transfer/download messages, and Bridge-generated firmware messages are displayed in French.
2. Set the phone/app language to English and confirm the same user-facing elements are displayed in English.
3. Set the phone to a language other than French (for example German or Spanish) and confirm the Bridge falls back to English.
4. Confirm `SLM BRIDGE`, application version, aircraft registration, recorder SSID, filenames, API/state identifiers, and technical diagnostic records are not translated.
5. Open the hidden integrity diagnostics and confirm its interface and log remain in English.
6. Confirm `File selection in progress` and `Recorder not ready` are acknowledged dialogs with **OK**, not transient Toast messages.
7. Confirm no `Retrying old recorder Wi-Fi password` message can occur and that recorder connection does not retry any historical stored Wi-Fi password.
8. Re-run the v0.3.42/v0.3.43 Location and Wi-Fi scan cases in both English and French; confirm behavior is unchanged apart from localized text.
9. Process at least three recorder files while the first is uploading and confirm the v0.3.44 live queue-total behavior is retained (`1/1` -> `1/2` -> `1/3`) in both languages.

## v0.3.46 — recorder reachability and VPN diagnostics

1. With no VPN active, connect normally. Confirm the left status progresses through the connecting state, then a recorder-waiting state, then steady green `F-CJAF / Connected` once `/api/status` answers.
2. Repeat with the phone/app in French and confirm the corresponding recorder-waiting state is localized.
3. Open the hidden diagnostics and confirm the sequence contains `src=NET event=CONNECT_REQUEST`, `src=NET event=RECORDER_NETWORK_AVAILABLE`, `src=HTTP event=WAITING_FOR_RECORDER`, and finally `src=HTTP event=RECORDER_READY`.
4. Confirm these events include `vpn_active=true` or `vpn_active=false` as appropriate.
5. With a VPN active that blocks local-network access, allow Android to connect to the recorder Wi-Fi and confirm the recorder never reaches ready. After the normal readiness attempts fail, confirm an acknowledged **Recorder not responding** dialog states that a VPN is active and advises disabling it temporarily or allowing SLM Bridge local-network access.
6. Repeat in French and confirm the acknowledged message reads: `Le Wi-Fi de l’enregistreur est connecté mais l’enregistreur ne communique pas. Un VPN est actif ! Désactivez temporairement le VPN ou configurez-le pour autoriser l’accès au réseau local pour SLM BRIDGE.`
7. Confirm the failed case records `src=HTTP event=RECORDER_UNREACHABLE ... vpn_active=true`.
8. With the same recorder-unreachable condition but no VPN active, confirm the existing generic recorder-not-responding message is used instead.
9. Confirm existing integrity-chain records remain present in the same persistent diagnostics log and new records are prefixed `src=INTEGRITY`.

## v0.3.47 — OTA health-monitor protection

1. Connect to a recorder and confirm the normal health monitor still detects an ordinary recorder loss when no OTA is active.
2. From the recorder Web Firmware page, select a local phone `.bin` and start OTA. Confirm diagnostics contain `src=OTA event=START source=PHONE`.
3. While the local OTA is active, confirm the Bridge does not disconnect the recorder because of transient `/api/status` failures. If a health probe races with OTA start/completion, confirm any failure is logged as `HEALTH_FAILURE_IGNORED` with `action=keep_recorder_network`.
4. Confirm the protection works when the connected recorder is an older firmware version (for example v1.44) because the Bridge injects the `/api/ota` XHR monitor into the recorder page.
5. Repeat using Firmware from Server and confirm diagnostics contain `src=OTA event=START source=SERVER`; the recorder network remains protected while the Bridge uploads the firmware.
6. After the OTA transaction completes, confirm diagnostics contain `src=OTA event=FINISH ... grace_ms=20000`.
7. When the recorder intentionally reboots after OTA completion, confirm recorder network loss during the post-OTA grace period is logged with `expected_reboot=true`. If the network is lost while firmware upload is still active, confirm the log shows `ota_active=true` and `expected_reboot=false`.
8. Confirm the normal health monitor resumes after the post-OTA grace period if the recorder does not reboot/disconnect.
9. Pair with Recorder v1.49 and confirm the recorder's 2000 ms acknowledgement-to-reboot delay gives the Bridge/WebView time to receive the final OTA success response before Wi-Fi disappears.


## v0.3.48 — three-line recorder-waiting status

1. With no VPN active, connect normally and pause during the interval after Android has established recorder Wi-Fi but before `/api/status` responds. Confirm the English left status is displayed on three lines: `F-CJAF` / `Waiting` / `for recorder`.
2. Repeat with the phone/app in French and confirm the status is displayed on three lines: `F-CJAF` / `Attente` / `Enregistreur`.
3. Confirm the shorter three-line presentation retains the normal larger recorder-status font and does not shrink to the small font previously caused by `Waiting for recorder` / `Attente enregistreur` on one line.
4. Confirm the v0.3.46 VPN-specific acknowledged error dialog and persistent NET/HTTP diagnostics are unchanged.
5. Confirm the v0.3.47 OTA health-monitor protection remains active and unchanged.
