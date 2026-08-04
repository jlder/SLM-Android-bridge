# Bridge v0.3.35 compact status validation

1. With no queued transfer, confirm the line reads `File upload None        File Queue Empty`.
2. Queue three recorder files.
3. During upload, confirm the line shows `File upload <percent>%        File Queue <current>/<total>`.
4. While files are queued but none is actively uploading, confirm it shows `File upload None        File Queue <count>/<count>`.
5. Confirm the queue-race correction from v0.3.34 remains present: all files upload without toggling Wi-Fi or mobile data.
6. Confirm the progress bar remains visible only while a file is actively uploading.
