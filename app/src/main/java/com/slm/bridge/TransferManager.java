package com.slm.bridge;

import android.content.Context;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class TransferManager {
    interface QueueListener { void onQueueStatus(QueueStatus status); }

    static final class QueueStatus {
        static final int IDLE = 0;
        static final int WAITING = 1;
        static final int UPLOADING = 2;

        final String text;
        final int state;

        QueueStatus(String text, int state) {
            this.text = text;
            this.state = state;
        }
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
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicBoolean archiveScheduled = new AtomicBoolean();
    // These counters remain on the serial upload executor. They keep a batch
    // labelled 1 of N, 2 of N, etc. even though completed items disappear
    // from TransferStore.pendingUploads().
    private int uploadBatchTotal;
    private int uploadBatchCompleted;
    private int activeUploadPosition = 1;
    private int activeUploadTotal = 1;

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
        if (!store.hasPendingUploads() || !retryScheduled.compareAndSet(false, true)) return;
        uploadExecutor.execute(() -> {
            try { retryPending(); }
            finally { retryScheduled.set(false); }
        });
    }

    void markAnalysisComplete(String transferId) {
        downloadExecutor.execute(() -> {
            try {
                TransferStore.Item item = store.get(transferId);
                if (item == null) return;
                store.markAnalysisComplete(item);
                schedulePendingArchives();
            } catch (Exception e) {
                emit(null, "archive-pending", 0, message(e));
            }
        });
    }

    void close() {
        downloadExecutor.shutdownNow();
        uploadExecutor.shutdownNow();
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
            String sha256 = download(item);
            store.markDownloaded(item, sha256);
            emit(item, "download-complete", 100, null);
            publishQueueSnapshot(null);
            if (upload) {
                TransferStore.Item downloaded = item;
                uploadExecutor.execute(() -> uploadOrQueue(downloaded));
            }
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
            store.markDownloaded(item, sha256);
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

    private String download(TransferStore.Item item) throws Exception {
        String encoded = URLEncoder.encode(item.filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        URL url = new URL(settings.recorderBaseUrl() + "/api/download?file=" + encoded);
        return downloadFromUrl(item, url.toString(), "application/octet-stream", "", "");
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
            DriveCredentials credentials = availableCredentials();
            if (credentials == null) {
                emit(item, "upload-pending", 0,
                        networks.recorderNetwork() == null
                                ? "Reconnect to the recorder to obtain Drive authorization"
                                : "Recorder Drive authorization is unavailable");
                publishQueueSnapshot("Drive authorization unavailable");
                return;
            }
            if (networks.uploadNetwork() == null) {
                emit(item, "upload-pending", 0, "Waiting for Internet");
                publishQueueSnapshot(null);
                return;
            }
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
            publishQueueSnapshot(null);
            schedulePendingArchives();
            clearCredentialsWhenIdle();
        } catch (Exception e) {
            emit(item, "upload-pending", 0, message(e));
            publishQueueSnapshot(message(e));
        }
    }

    private void retryPending() {
        if (!store.hasPendingUploads()) {
            clearCredentialsWhenIdle();
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
        if (networks.uploadNetwork() == null) {
            emitPendingError("Waiting for Internet");
            publishQueueSnapshot(null);
            return;
        }
        for (TransferStore.Item item : store.pendingUploads()) {
            try {
                if (item.uploaded) continue;
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
                publishQueueSnapshot(null);
                schedulePendingArchives();
            } catch (Exception e) {
                emit(item, "upload-pending", 0, message(e));
                publishQueueSnapshot(message(e));
                break;
            }
        }
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

    private DriveCredentials availableCredentials() throws Exception {
        DriveCredentials credentials = credentialStore.load();
        if (credentials != null) return credentials;
        if (networks.recorderNetwork() == null) return null;
        credentials = credentialClient.fetch();
        credentialStore.save(credentials);
        return credentials;
    }

    private void clearCredentialsWhenIdle() {
        if (!store.hasPendingUploads()) {
            uploadBatchTotal = 0;
            uploadBatchCompleted = 0;
            credentialStore.clear();
            drive.clearAuthorization();
            UploadJobService.cancel(context);
        }
    }

    private void emitPendingError(String error) {
        for (TransferStore.Item item : store.pendingUploads()) emit(item, "upload-pending", 0, error);
    }

    private void publishUploading(int percent) {
        publish(new QueueStatus("SERVER UPLOAD: Uploading " + activeUploadPosition
                + " of " + activeUploadTotal
                + " \u2014 " + Math.max(0, Math.min(100, percent)) + "%", QueueStatus.UPLOADING));
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
        if (count == 0) {
            publish(new QueueStatus("SERVER UPLOAD: Queue empty", QueueStatus.IDLE));
            return;
        }
        String files = count == 1 ? "file" : "files";
        if (networks.uploadNetwork() == null) {
            publish(new QueueStatus("SERVER UPLOAD: " + count + " " + files
                    + " waiting for Internet", QueueStatus.WAITING));
        } else if (error != null && !error.isEmpty()) {
            publish(new QueueStatus("SERVER UPLOAD: " + count + " " + files
                    + " queued \u2014 retry pending", QueueStatus.WAITING));
        } else {
            publish(new QueueStatus("SERVER UPLOAD: " + count + " " + files
                    + " queued", QueueStatus.WAITING));
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
            if (item != null && item.downloadComplete) detail.put("localUrl", item.localUrl());
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
