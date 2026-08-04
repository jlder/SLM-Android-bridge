# Bridge v0.3.36 validation

1. Process a recorder file with a valid creation SHA.
2. Confirm Drive SHA-256 verification succeeds.
3. Confirm the Bridge calls `POST /api/archive` and the recorder moves the `.bin`, `.sha`, and `.log` companions to `/processed`.
4. Confirm the Bridge uses only the recorder archive endpoint.
5. Confirm retry, queue-resume, and compact status behavior remain unchanged.
