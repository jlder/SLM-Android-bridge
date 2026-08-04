package com.slm.bridge;

import android.content.Context;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class TransferManager {
    private static final long CREDENTIAL_IDLE_GRACE_MS = 120_000L;

    interface QueueListener { void onQueueStatus(QueueStatus status); }

    private static final class RecorderShaMetadata {
        final String filename;
        final long size;
        final String sha256;

        RecorderShaMetadata(String filename, long size, String sha256) {
            this.filename = filename;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class DownloadResult {
        final String sha256;
        final long size;

        DownloadResult(String sha256, long size) {
            this.sha256 = sha256;
            this.size = size;
        }
    }

    static final class QueueStatus {
        static final int IDLE = 0;
        static final int WAITING = 1;
        static final int UPLOADING = 2;

        final String text;
        final int state;
        final int currentFile;
        final int totalFiles;
        final int percent;

        QueueStatus(String text, int state) {
            this(text, state, 0, 0, 0);
        }

        QueueStatus(String text, int state, int currentFile, int totalFiles, int percent) {
            this.text = text;
            this.state = state;
            this.currentFile = currentFile;
            this.totalFiles = totalFiles;
            this.percent = percent;
        }

        boolean isEmpty() { return state == IDLE || totalFiles <= 0; }
    }

    private final NetworkCoordinator networks;
    private final Context context;
    private final AppSettings settings;
    private final TransferStore store;
    private final WebView webView;
    private final DriveCredentialStore credentialStore;
    private final RecorderDriveCredentialsClient credentialClient;
    private final RecorderArchiveClient archiveClient;
    private final GoogleDriveUploader drive;
    private final QueueListener queueListener;
    // Recorder downloads and cellular uploads have independent serial queues.
    // This keeps SD access single-file-at-a-time while allowing Drive uploads
    // to continue in the background during the next recorder download.
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService preparationExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    // Remember upload work that becomes ready while a retry pass is already running.
    // Without this latch, the request is lost until another network-change event.
    private final AtomicBoolean retryRequested = new AtomicBoolean();
    private final AtomicBoolean archiveScheduled = new AtomicBoolean();
    private final AtomicBoolean credentialPrefetchScheduled = new AtomicBoolean();
    private final AtomicBoolean credentialClearScheduled = new AtomicBoolean();
    private volatile boolean uploadActive;
    private volatile int activeUploadPercent;
    // These counters remain on the serial upload executor. They keep a batch
    // labelled 1 of N, 2 of N, etc. even though completed items disappear
    // from TransferStore.pendingUploads().
    private int uploadBatchTotal;
    private int uploadBatchCompleted;
    private int activeUploadPosition = 1;
    private int activeUploadTotal = 1;

    private final Runnable credentialIdleClearRunnable = () -> {
        credentialClearScheduled.set(false);
        uploadExecutor.execute(this::clearCredentialsIfStillIdle);
    };

    TransferManager(NetworkCoordinator networks, AppSettings settings, TransferStore store,
                    DriveCredentialStore credentialStore, WebView webView,
                    QueueListener queueListener) {
        this.networks = networks;
        this.context = webView.getContext().getApplicationContext();
        this.settings = settings;
        this.store = store;
        this.webView = webView;
        this.credentialStore = credentialStore;
        this.credentialClient = new RecorderDriveCredentialsClient(networks, settings);
        this.archiveClient = new RecorderArchiveClient(networks, settings);
        this.drive = new GoogleDriveUploader(networks, store);
        this.queueListener = queueListener;
        UploadJobService.cancel(context);
        publishQueueSnapshot(null);
        requestUploadRetry();
    }

    void enqueue(String requestJson) { downloadExecutor.execute(() -> execute(requestJson)); }

    void enqueueReport(RecorderFileExporter.Request request) {
        downloadExecutor.execute(() -> executeReport(request));
    }

    void enqueueGeneratedReport(String recorderPath) {
        downloadExecutor.execute(() -> {
            try {
                String normalized = recorderPath == null
                        ? "" : recorderPath.trim().replace('\\', '/');
                String prefix = "/calibration_reports/";
                if (!normalized.startsWith(prefix)
                        || normalized.substring(prefix.length()).contains("/")
                        || normalized.contains("..")) {
                    throw new SecurityException("Calibration report path is not allowed");
                }
                String filename = validateFilename(normalized.substring(prefix.length()));
                String encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name())
                        .replace("+", "%20");
                RecorderFileExporter.Request request = new RecorderFileExporter.Request(
                        settings.recorderBaseUrl() + "/api/download?file=" + encoded,
                        "", "", "text/plain", filename);
                executeReport(request);
            } catch (Exception e) {
                publishQueueSnapshot(message(e));
            }
        });
    }

    void delete(String transferId) {
        // Wait until preceding recorder downloads have handed their files to
        // the upload queue, then delete only after earlier uploads finish.
        downloadExecutor.execute(() -> uploadExecutor.execute(() -> {
                store.delete(transferId);
                clearCredentialsWhenIdle();
            }));
    }

    void onNetworksChanged() {
        publishQueueSnapshot(null);
        schedulePendingArchives();
        prefetchCredentialsIfPossible();
        requestUploadRetry();
        clearCredentialsWhenIdle();
    }

    void markAnalysisComplete(String transferId) {
        downloadExecutor.execute(() -> {
            try {
                TransferStore.Item item = store.get(transferId);
                if (item == null) return;
                store.markAnalysisComplete(item);
                requestUploadRetry();
                schedulePendingArchives();
            } catch (Exception e) {
                emit(null, "archive-pending", 0, message(e));
            }
        });
    }

    void markAnalysisFailed(String transferId) {
        downloadExecutor.execute(() -> {
            store.delete(transferId);
            publishQueueSnapshot(null);
            clearCredentialsWhenIdle();
        });
    }

    String recorderTransferStates() {
        String registration = settings.gliderRegistration();
        if (registration.isEmpty()) return "{\"files\":[]}";
        return store.recorderTransferStates(registration);
    }

    void close() {
        main.removeCallbacksAndMessages(null);
        downloadExecutor.shutdownNow();
        uploadExecutor.shutdownNow();
        preparationExecutor.shutdownNow();
        if (store.hasPendingUploads()) UploadJobService.schedule(context);
        else UploadJobService.cancel(context);
    }

    private void execute(String requestJson) {
        TransferStore.Item item = null;
        try {
            JSONObject request = new JSONObject(requestJson);
            String filename = validateFilename(request.getString("filename"));
            boolean upload = request.optBoolean("upload", true);
            String registration = settings.gliderRegistration();
            if (registration.isEmpty()) {
                throw new IllegalStateException("The recorder SSID does not contain a glider registration");
            }
            item = store.create(filename, registration, upload);
            emit(item, "download-started", 0, null);
            RecorderShaMetadata expected = fetchRecorderShaMetadata(item.filename);
            DownloadResult downloaded = download(item);
            String integrityStatus = "legacy";
            if (expected != null) {
                verifyRecorderSha(item.filename, expected, downloaded);
                integrityStatus = "creation-verified";
            }
            store.markDownloaded(item, downloaded.sha256, integrityStatus);
            emit(item, "download-complete", 100, null);
            publishQueueSnapshot(null);
        } catch (Exception e) {
            if (item != null && !item.downloadComplete) store.delete(item.id);
            emit(item, "failed", 0, message(e));
            publishQueueSnapshot(null);
        }
    }

    private void executeReport(RecorderFileExporter.Request request) {
        TransferStore.Item item = null;
        try {
            String filename = validateFilename(request.filename);
            String registration = settings.gliderRegistration();
            if (registration.isEmpty()) {
                throw new IllegalStateException("The recorder SSID does not contain a glider registration");
            }
            if (!RecorderUrlPolicy.isAllowed(request.url, settings.recorderBaseUrl())
                    || !RecorderUrlPolicy.isCalibrationReportDownload(request.url)) {
                throw new SecurityException("Calibration report URL is not allowed");
            }

            item = store.createReport(filename, registration);
            String sha256 = downloadReportWithRetry(item, request);
            store.markDownloaded(item, sha256, "generated-report");
            publishQueueSnapshot(null);
            TransferStore.Item downloaded = item;
            uploadExecutor.execute(() -> uploadOrQueue(downloaded));
        } catch (Exception e) {
            if (item != null && !item.downloadComplete) store.delete(item.id);
            publishQueueSnapshot(message(e));
        }
    }

    private String downloadReportWithRetry(TransferStore.Item item,
                                           RecorderFileExporter.Request request) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                return downloadFromUrl(item, request.url, request.mimeType,
                        request.userAgent, request.cookie);
            } catch (Exception e) {
                last = e;
                String detail = message(e);
                if (!detail.contains("HTTP 409") || attempt == 6) throw e;
                Thread.sleep(200L * attempt);
            }
        }
        throw last == null ? new IllegalStateException("Calibration report download failed") : last;
    }

    private RecorderShaMetadata fetchRecorderShaMetadata(String filename) throws Exception {
        if (!filename.toLowerCase(Locale.US).endsWith(".bin")) return null;
        String shaFilename = filename.substring(0, filename.length() - 4) + ".sha";
        String encoded = URLEncoder.encode(shaFilename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        URL url = new URL(settings.recorderBaseUrl() + "/api/download?file=" + encoded);
        Network network = networks.recorderNetwork();
        if (network == null) throw new IllegalStateException("Recorder Wi-Fi is unavailable");

        HttpURLConnection connection = (HttpURLConnection) network.openConnection(url);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept", "text/plain");
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (status / 100 != 2) {
                throw new IllegalStateException("Recorder SHA metadata returned HTTP " + status);
            }
            long length = connection.getContentLengthLong();
            if (length > 4096) throw new IllegalStateException("Recorder SHA metadata is too large");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                byte[] buffer = new byte[512];
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    if (out.size() + count > 4096) {
                        throw new IllegalStateException("Recorder SHA metadata is too large");
                    }
                    out.write(buffer, 0, count);
                }
            }
            return parseRecorderShaMetadata(filename, out.toString(StandardCharsets.UTF_8.name()));
        } finally {
            connection.disconnect();
        }
    }

    private static RecorderShaMetadata parseRecorderShaMetadata(String requestedFilename,
                                                                 String text) {
        String format = "";
        String filename = "";
        String sizeText = "";
        String sha256 = "";
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator <= 0) throw new IllegalStateException("Invalid recorder SHA metadata");
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if ("format".equals(key)) format = value;
            else if ("filename".equals(key)) filename = value;
            else if ("size".equals(key)) sizeText = value;
            else if ("sha256".equals(key)) sha256 = value.toLowerCase(Locale.US);
        }
        if (!"1".equals(format) || !requestedFilename.equals(filename)) {
            throw new IllegalStateException("Recorder SHA metadata does not match the file");
        }
        long size;
        try {
            size = Long.parseLong(sizeText);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Recorder SHA metadata has an invalid size");
        }
        if (size < 0 || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Recorder SHA metadata is invalid");
        }
        return new RecorderShaMetadata(filename, size, sha256);
    }

    private static void verifyRecorderSha(String filename, RecorderShaMetadata expected,
                                           DownloadResult downloaded) {
        if (downloaded.size != expected.size) {
            throw new IllegalStateException("Recorder file size changed since creation: " + filename);
        }
        if (!downloaded.sha256.equalsIgnoreCase(expected.sha256)) {
            throw new IllegalStateException("Recorder file SHA changed since creation: " + filename);
        }
    }

    private DownloadResult download(TransferStore.Item item) throws Exception {
        String encoded = URLEncoder.encode(item.filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        URL url = new URL(settings.recorderBaseUrl() + "/api/download?file=" + encoded);
        String sha256 = downloadFromUrl(item, url.toString(), "application/octet-stream", "", "");
        return new DownloadResult(sha256, item.file.length());
    }

    private String downloadFromUrl(TransferStore.Item item, String sourceUrl, String mimeType,
                                   String userAgent, String cookie) throws Exception {
        Network network = networks.recorderNetwork();
        if (network == null) throw new IllegalStateException("Recorder Wi-Fi is unavailable");
        if (!RecorderUrlPolicy.isAllowed(sourceUrl, settings.recorderBaseUrl())) {
            throw new SecurityException("Recorder download URL is outside the allowed origin");
        }

        URL current = new URL(sourceUrl);
        HttpURLConnection connection = null;
        for (int redirects = 0; redirects <= 5; redirects++) {
            connection = (HttpURLConnection) network.openConnection(current);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("Accept",
                    mimeType == null || mimeType.isEmpty()
                            ? "application/octet-stream" : mimeType);
            if (userAgent != null && !userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            int status = connection.getResponseCode();
            if (!isRedirect(status)) break;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            connection = null;
            if (location == null) throw new IllegalStateException("Recorder returned an invalid redirect");
            current = new URL(current, location);
            if (!RecorderUrlPolicy.isAllowed(current.toString(), settings.recorderBaseUrl())) {
                throw new SecurityException("Recorder download redirected outside the allowed origin");
            }
        }

        if (connection == null) throw new IllegalStateException("Too many recorder redirects");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            int status = connection.getResponseCode();
            if (status / 100 != 2) throw new IllegalStateException("Recorder returned HTTP " + status);
            long total = connection.getContentLengthLong();
            long copied = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(item.file))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                int lastPercent = -1;
                while ((count = in.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    copied += count;
                    int percent = total > 0 ? (int) Math.min(99, copied * 100 / total) : 0;
                    if (percent != lastPercent) {
                        emit(item, "downloading", percent, null);
                        lastPercent = percent;
                    }
                }
            }
            if (total >= 0 && item.file.length() != total) {
                throw new IllegalStateException("Incomplete recorder download");
            }
            return hex(digest.digest());
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307 || status == 308;
    }

    private void uploadOrQueue(TransferStore.Item item) {
        try {
            // A network-change retry may already have completed this item
            // before its normal upload task reached the serial upload queue.
            if (item.uploaded) {
                if (!item.archiveRequested) store.delete(item.id);
                publishQueueSnapshot(null);
                return;
            }
            if (networks.uploadNetwork() == null) {
                emit(item, "upload-pending", 0, "Waiting for Internet");
                publishQueueSnapshot(null);
                return;
            }

            cancelScheduledCredentialClear();
            beginUploadBatchItem();
            emit(item, "upload-started", 0, null);
            publishUploading(0);

            DriveCredentials credentials = availableCredentials();
            if (credentials == null) {
                clearActiveUpload();
                emit(item, "upload-pending", 0,
                        networks.recorderNetwork() == null
                                ? "Reconnect to the recorder to obtain Drive authorization"
                                : "Recorder Drive authorization is unavailable");
                publishQueueSnapshot("Drive authorization unavailable");
                return;
            }

            drive.upload(item, credentials, percent -> {
                emit(item, "uploading", percent, null);
                publishUploading(percent);
            });
            completeUploadBatchItem();
            emit(item, "upload-complete", 100, null);
            if (!item.archiveRequested) store.delete(item.id);
            clearActiveUpload();
            if (store.hasPendingUploads()) requestUploadRetry();
            else publishQueueSnapshot(null);
            schedulePendingArchives();
            clearCredentialsWhenIdle();
        } catch (Exception e) {
            clearActiveUpload();
            emit(item, "upload-pending", 0, message(e));
            publishQueueSnapshot(message(e));
        }
    }


    private void requestUploadRetry() {
        if (!store.hasPendingUploads()) return;
        cancelScheduledCredentialClear();
        retryRequested.set(true);
        scheduleUploadRetryWorker();
    }

    private void scheduleUploadRetryWorker() {
        if (!retryScheduled.compareAndSet(false, true)) return;
        uploadExecutor.execute(() -> {
            try {
                // Drain every retry request that arrived while this serial worker
                // was active. New analysis-complete events therefore continue the
                // same upload batch without waiting for a network callback.
                while (retryRequested.getAndSet(false)) retryPending();
            } finally {
                retryScheduled.set(false);
                // Close the small race between the last loop test and clearing the
                // scheduled flag. If work arrived there, schedule a fresh worker.
                if (retryRequested.get() && store.hasPendingUploads()) {
                    scheduleUploadRetryWorker();
                }
            }
        });
    }

    private void retryPending() {
        if (!store.hasPendingUploads()) {
            clearCredentialsWhenIdle();
            return;
        }
        if (networks.uploadNetwork() == null) {
            emitPendingError("Waiting for Internet");
            publishQueueSnapshot(null);
            return;
        }

        DriveCredentials credentials;
        try { credentials = availableCredentials(); }
        catch (Exception e) {
            emitPendingError(message(e));
            publishQueueSnapshot(message(e));
            return;
        }
        if (credentials == null) {
            emitPendingError("Reconnect to the recorder to obtain Drive authorization");
            publishQueueSnapshot("Drive authorization unavailable");
            return;
        }

        for (TransferStore.Item item : store.pendingUploads()) {
            try {
                if (item.uploaded) continue;
                cancelScheduledCredentialClear();
                beginUploadBatchItem();
                emit(item, "upload-started", 0, null);
                publishUploading(0);
                drive.upload(item, credentials, percent -> {
                    emit(item, "uploading", percent, null);
                    publishUploading(percent);
                });
                completeUploadBatchItem();
                emit(item, "upload-complete", 100, null);
                if (!item.archiveRequested) store.delete(item.id);
                schedulePendingArchives();
            } catch (Exception e) {
                clearActiveUpload();
                emit(item, "upload-pending", 0, message(e));
                publishQueueSnapshot(message(e));
                break;
            }
        }
        clearActiveUpload();
        publishQueueSnapshot(null);
        clearCredentialsWhenIdle();
    }


    private void schedulePendingArchives() {
        if (networks.recorderNetwork() == null || store.pendingArchives().isEmpty()
                || !archiveScheduled.compareAndSet(false, true)) return;
        downloadExecutor.execute(() -> {
            try { processPendingArchives(); }
            finally { archiveScheduled.set(false); }
        });
    }

    private void processPendingArchives() {
        Network connectedNetwork = networks.recorderNetwork();
        if (connectedNetwork == null) return;

        String connectedRegistration = settings.gliderRegistration();
        if (connectedRegistration.isEmpty()) return;

        for (TransferStore.Item item : store.pendingArchives()) {
            // An upload may complete after the phone has left this recorder.
            // Archive only files belonging to the recorder that is connected
            // now; other registrations remain queued for their next connection.
            if (!connectedRegistration.equals(item.registration)) continue;
            if (networks.recorderNetwork() != connectedNetwork) return;
            try {
                archiveClient.archive(item.filename);
                store.markArchived(item);
                emit(item, "archive-complete", 100, null);
                store.delete(item.id);
            } catch (Exception e) {
                emit(item, "archive-pending", 0, message(e));
                return;
            }
        }
    }

    private void prefetchCredentialsIfPossible() {
        if (networks.recorderNetwork() == null) return;
        if (!credentialPrefetchScheduled.compareAndSet(false, true)) return;
        preparationExecutor.execute(() -> {
            try {
                DriveCredentials credentials = credentialStore.load();
                if (credentials == null && networks.recorderNetwork() != null) {
                    credentials = credentialClient.fetch();
                    credentialStore.save(credentials);
                }
                String registration = settings.gliderRegistration();
                if (credentials != null && !registration.isEmpty() && networks.uploadNetwork() != null) {
                    drive.prepare(credentials, registration);
                }
            } catch (Exception ignored) {
                // Upload handling reports the authorization error if credentials
                // are still unavailable when a file actually needs uploading.
            } finally {
                credentialPrefetchScheduled.set(false);
            }
        });
    }

    private DriveCredentials availableCredentials() throws Exception {
        DriveCredentials credentials = credentialStore.load();
        if (credentials != null) return credentials;
        if (networks.recorderNetwork() == null) return null;
        credentials = credentialClient.fetch();
        credentialStore.save(credentials);
        return credentials;
    }

    private void clearCredentialsWhenIdle() {
        if (store.hasPendingUploads() || uploadActive) {
            cancelScheduledCredentialClear();
            return;
        }
        uploadBatchTotal = 0;
        uploadBatchCompleted = 0;
        UploadJobService.cancel(context);
        if (credentialClearScheduled.compareAndSet(false, true)) {
            main.postDelayed(credentialIdleClearRunnable, CREDENTIAL_IDLE_GRACE_MS);
        }
    }

    private void clearCredentialsIfStillIdle() {
        if (store.hasPendingUploads() || uploadActive) return;
        uploadBatchTotal = 0;
        uploadBatchCompleted = 0;
        drive.clearAuthorization();
        if (networks.recorderNetwork() == null) credentialStore.clear();
        UploadJobService.cancel(context);
    }

    private void cancelScheduledCredentialClear() {
        if (credentialClearScheduled.getAndSet(false)) {
            main.removeCallbacks(credentialIdleClearRunnable);
        }
    }

    private void emitPendingError(String error) {
        for (TransferStore.Item item : store.pendingUploads()) emit(item, "upload-pending", 0, error);
    }

    private void publishUploading(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        uploadActive = true;
        activeUploadPercent = safePercent;
        int dynamicTotal = Math.max(activeUploadTotal,
                uploadBatchCompleted + Math.max(1, store.pendingUploads().size()));
        activeUploadTotal = dynamicTotal;
        publish(new QueueStatus("SERVER UPLOAD: Uploading " + activeUploadPosition
                + " of " + activeUploadTotal
                + " — " + safePercent + "%", QueueStatus.UPLOADING,
                activeUploadPosition, activeUploadTotal, safePercent));
    }

    private void clearActiveUpload() {
        uploadActive = false;
        activeUploadPercent = 0;
    }


    private void beginUploadBatchItem() {
        int pending = Math.max(1, store.pendingUploads().size());
        if (uploadBatchTotal <= uploadBatchCompleted) {
            uploadBatchCompleted = 0;
            uploadBatchTotal = pending;
        } else {
            uploadBatchTotal = Math.max(uploadBatchTotal, uploadBatchCompleted + pending);
        }
        activeUploadPosition = uploadBatchCompleted + 1;
        activeUploadTotal = uploadBatchTotal;
    }

    private void completeUploadBatchItem() {
        uploadBatchCompleted++;
        uploadBatchTotal = Math.max(uploadBatchTotal,
                uploadBatchCompleted + store.pendingUploads().size());
    }

    private void publishQueueSnapshot(String error) {
        int count = store.pendingUploads().size();
        if (uploadActive && count > 0) {
            publishUploading(activeUploadPercent);
            return;
        }
        if (count == 0) {
            publish(new QueueStatus("SERVER UPLOAD: Queue empty", QueueStatus.IDLE, 0, 0, 0));
            return;
        }
        String files = count == 1 ? "file" : "files";
        if (networks.uploadNetwork() == null) {
            publish(new QueueStatus("SERVER UPLOAD: " + count + " " + files
                    + " waiting for Internet", QueueStatus.WAITING, 0, count, 0));
        } else if (error != null && !error.isEmpty()) {
            publish(new QueueStatus("SERVER UPLOAD: " + count + " " + files
                    + " queued — retry pending", QueueStatus.WAITING, 0, count, 0));
        } else {
            publish(new QueueStatus("SERVER UPLOAD: " + count + " " + files
                    + " queued", QueueStatus.WAITING, 0, count, 0));
        }
    }


    private void publish(QueueStatus status) {
        if (queueListener != null) queueListener.onQueueStatus(status);
    }

    private void emit(TransferStore.Item item, String state, int percent, String error) {
        // Calibration reports use the native queue-status line and must not
        // drive the recorder binary-analysis window in the Web application.
        if (item != null && !item.driveSubfolder.isEmpty()) return;
        try {
            JSONObject detail = new JSONObject();
            detail.put("transferId", item == null ? "" : item.id);
            detail.put("filename", item == null ? "" : item.filename);
            detail.put("state", state);
            detail.put("percent", percent);
            if (item != null && item.downloadComplete) {
                detail.put("localUrl", item.localUrl());
                detail.put("integrityStatus", item.integrityStatus);
            }
            if (error != null && !error.isEmpty()) detail.put("error", error);
            String script = "window.dispatchEvent(new CustomEvent('slm-transfer-event',{detail:"
                    + detail + "}));";
            main.post(() -> webView.evaluateJavascript(script, null));
        } catch (Exception ignored) {}
    }

    private static String validateFilename(String value) {
        if (value == null || value.isEmpty() || value.length() > 160 || value.contains("/")
                || value.contains("\\") || value.contains("..") || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid recorder filename");
        }
        return value;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte part : value) result.append(String.format("%02x", part & 0xff));
        return result.toString();
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
