package com.slm.bridge;

import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
final class GoogleDriveUploader {
    private static final class DriveFileIntegrity {
        final String id;
        final long size;
        final String sha256;

        DriveFileIntegrity(String id, long size, String sha256) {
            this.id = id == null ? "" : id;
            this.size = size;
            this.sha256 = sha256 == null ? "" : sha256.toLowerCase(Locale.US);
        }
    }

    static final class ValidatedRecording {
        final String registration;
        final String filename;
        final long size;
        final String sha256;
        final long serverCreatedAt;

        ValidatedRecording(String registration, String filename, long size, String sha256,
                           long serverCreatedAt) {
            this.registration = registration == null ? "" : registration.trim().toUpperCase(Locale.US);
            this.filename = filename == null ? "" : filename;
            this.size = size;
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.US);
            this.serverCreatedAt = serverCreatedAt;
        }
    }
    interface Progress { void update(int percent) throws Exception; }
    interface NetworkProvider { Network uploadNetwork(); }

    private static final String DRIVE_FILES = "https://www.googleapis.com/drive/v3/files";
    private static final String DRIVE_UPLOADS = "https://www.googleapis.com/upload/drive/v3/files";
    private static final int CHUNK_SIZE = 8 * 1024 * 1024;
    private static final int UPLOAD_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_RESPONSE = 256 * 1024;
    private final NetworkProvider networks;
    private final TransferStore store;
    private final ServerValidationCache validationCache;
    private final Map<String, String> registrationFolderCache = new HashMap<>();
    private final Map<String, String> childFolderCache = new HashMap<>();
    private String accessToken = "";
    private long accessTokenExpiry;

    GoogleDriveUploader(NetworkCoordinator networks, TransferStore store,
                        ServerValidationCache validationCache) {
        this(networks::uploadNetwork, store, validationCache);
    }

    GoogleDriveUploader(NetworkProvider networks, TransferStore store) {
        this(networks, store, null);
    }

    GoogleDriveUploader(NetworkProvider networks, TransferStore store,
                        ServerValidationCache validationCache) {
        this.networks = networks;
        this.store = store;
        this.validationCache = validationCache;
    }

    synchronized void clearAuthorization() {
        accessToken = "";
        accessTokenExpiry = 0;
        registrationFolderCache.clear();
        childFolderCache.clear();
    }

    synchronized void prepare(DriveCredentials credentials, String registration) throws Exception {
        if (credentials == null || registration == null || registration.isEmpty()) return;
        Network network = requireInternet();
        String token = accessToken(network, credentials);
        registrationFolder(network, token, credentials, registration);
    }

    synchronized void upload(TransferStore.Item item, DriveCredentials credentials, Progress progress) throws Exception {
        Network network = requireInternet();
        String token = accessToken(network, credentials);
        String folder = registrationFolder(network, token, credentials, item.registration);
        if (!item.driveSubfolder.isEmpty()) {
            folder = childFolder(network, token, folder, item.driveSubfolder, item.registration);
        }
        DriveFileIntegrity duplicate = findBySha256(network, token, folder, item.sha256);
        if (duplicate != null) {
            verifyDriveIntegrity(duplicate, item.file.length(), item.sha256);
            IntegrityDiagnostics.driveShaVerified(
                    item.filename, item.file.length(), item.sha256, true);
            store.markUploaded(item);
            rememberValidationReceipt(item);
            progress.update(100);
            return;
        }

        long offset = item.uploadedBytes;
        if (!item.uploadSession.isEmpty()) {
            try {
                offset = queryOffset(network, token, item.uploadSession, item.file.length());
                store.updateSession(item, item.uploadSession, offset);
            } catch (ExpiredSessionException ignored) {
                store.updateSession(item, "", 0);
                offset = 0;
            }
        }
        if (item.uploadSession.isEmpty()) {
            String session = createSession(network, token, item, folder);
            store.updateSession(item, session, 0);
            offset = 0;
        }

        if (item.file.length() > 0) {
            int preparedPercent = (int) Math.min(99, Math.max(1, offset * 100 / item.file.length()));
            progress.update(preparedPercent);
        }
        uploadChunks(network, token, item, offset, progress);
        DriveFileIntegrity uploaded = waitForBySha256(network, token, folder, item.sha256);
        if (uploaded == null) {
            throw new IllegalStateException("Drive upload completed but the stored file was not found");
        }
        verifyDriveIntegrity(uploaded, item.file.length(), item.sha256);
        IntegrityDiagnostics.driveShaVerified(
                item.filename, item.file.length(), item.sha256, false);
        store.markUploaded(item);
        rememberValidationReceipt(item);
        progress.update(100);
    }

    synchronized int refreshValidationCache(DriveCredentials credentials) throws Exception {
        if (validationCache == null || credentials == null) return 0;
        Network network = requireInternet();
        String token = accessToken(network, credentials);
        long refreshedAt = System.currentTimeMillis();
        long cutoff = refreshedAt - ServerValidationCache.RETENTION_MS;
        List<ValidatedRecording> recordings = listValidatedRecordings(
                network, token, credentials, cutoff);
        validationCache.replaceFromServer(recordings, refreshedAt);
        IntegrityDiagnostics.bridgeEvent("SYNC", "VALIDATION_CACHE_REFRESHED",
                "entries=" + recordings.size() + " retention_days=30");
        return recordings.size();
    }

    private List<ValidatedRecording> listValidatedRecordings(Network network, String token,
                                                              DriveCredentials credentials,
                                                              long cutoff) throws Exception {
        Map<String, String> registrationFolders = listRegistrationFolders(
                network, token, credentials.rootFolderId);
        List<ValidatedRecording> result = new ArrayList<>();
        if (registrationFolders.isEmpty()) return result;

        String cutoffText = Instant.ofEpochMilli(cutoff).toString();
        String q = "trashed = false and createdTime >= '" + escapeQuery(cutoffText)
                + "' and appProperties has { key='source' and value='slm-android' }";
        String pageToken = "";
        do {
            String url = DRIVE_FILES
                    + "?spaces=drive&pageSize=1000&orderBy=createdTime%20desc"
                    + "&fields=nextPageToken,files(name,size,sha256Checksum,createdTime,parents,appProperties)"
                    + "&q=" + query(q)
                    + (pageToken.isEmpty() ? "" : "&pageToken=" + query(pageToken));
            JSONObject response = authorizedJson(network, token, url, "GET", null,
                    "Drive validation cache refresh");
            JSONArray files = response.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.optJSONObject(i);
                    if (file == null) continue;
                    JSONObject properties = file.optJSONObject("appProperties");
                    if (properties == null
                            || !"recordings".equals(properties.optString("slmCollection"))) continue;

                    String folderRegistration = parentRegistration(
                            file.optJSONArray("parents"), registrationFolders);
                    String propertyRegistration = properties.optString("slmRegistration")
                            .trim().toUpperCase(Locale.US);
                    if (folderRegistration.isEmpty()
                            || !folderRegistration.equals(propertyRegistration)) continue;

                    String filename = file.optString("name");
                    if (!filename.toLowerCase(Locale.US).endsWith(".bin")) continue;
                    long size = parseLong(file.optString("size"), -1L);
                    String propertySha = properties.optString("sha256")
                            .trim().toLowerCase(Locale.US);
                    String driveSha = file.optString("sha256Checksum")
                            .trim().toLowerCase(Locale.US);
                    if (size < 0 || !propertySha.matches("[0-9a-f]{64}")
                            || !propertySha.equals(driveSha)) continue;

                    long createdAt;
                    try {
                        createdAt = Instant.parse(file.optString("createdTime")).toEpochMilli();
                    } catch (Exception ignored) {
                        continue;
                    }
                    if (createdAt < cutoff) continue;
                    result.add(new ValidatedRecording(folderRegistration, filename, size,
                            driveSha, createdAt));
                }
            }
            pageToken = response.optString("nextPageToken");
        } while (!pageToken.isEmpty());
        return result;
    }

    private Map<String, String> listRegistrationFolders(Network network, String token,
                                                         String rootFolderId) throws Exception {
        Map<String, String> result = new HashMap<>();
        String q = "'" + escapeQuery(rootFolderId)
                + "' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        String pageToken = "";
        do {
            String url = DRIVE_FILES + "?spaces=drive&pageSize=1000"
                    + "&fields=nextPageToken,files(id,name)&q=" + query(q)
                    + (pageToken.isEmpty() ? "" : "&pageToken=" + query(pageToken));
            JSONObject response = authorizedJson(network, token, url, "GET", null,
                    "Drive validation folder lookup");
            JSONArray files = response.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject folder = files.optJSONObject(i);
                    if (folder == null) continue;
                    String registration = folder.optString("name").trim().toUpperCase(Locale.US);
                    if (!GliderRegistration.isValid(registration)) continue;
                    String id = folder.optString("id");
                    if (!id.isEmpty()) result.put(id, registration);
                }
            }
            pageToken = response.optString("nextPageToken");
        } while (!pageToken.isEmpty());
        return result;
    }

    private static String parentRegistration(JSONArray parents, Map<String, String> folders) {
        if (parents == null) return "";
        for (int i = 0; i < parents.length(); i++) {
            String registration = folders.get(parents.optString(i));
            if (registration != null && !registration.isEmpty()) return registration;
        }
        return "";
    }

    private void rememberValidationReceipt(TransferStore.Item item) {
        if (validationCache == null || item == null || !item.driveSubfolder.isEmpty()) return;
        try {
            validationCache.recordValidated(item.registration, item.filename, item.file.length(),
                    item.sha256, System.currentTimeMillis());
            IntegrityDiagnostics.bridgeEvent("SYNC", "VALIDATION_RECEIPT_RECORDED",
                    "registration=" + item.registration + " file=" + item.filename);
        } catch (Exception error) {
            IntegrityDiagnostics.bridgeEvent("SYNC", "VALIDATION_RECEIPT_SAVE_FAILED",
                    "registration=" + item.registration + " file=" + item.filename
                            + " error=" + safe(error));
        }
    }

    private static String safe(Exception error) {
        String value = error == null ? "unknown"
                : error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
        return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    private String accessToken(Network network, DriveCredentials credentials) throws Exception {
        if (!accessToken.isEmpty() && System.currentTimeMillis() < accessTokenExpiry - 60_000) {
            return accessToken;
        }
        String body = "client_id=" + form(credentials.clientId)
                + "&client_secret=" + form(credentials.clientSecret)
                + "&refresh_token=" + form(credentials.refreshToken)
                + "&grant_type=refresh_token";
        HttpURLConnection connection = open(network, credentials.tokenUri, "POST", false);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            connection.connect();
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            JSONObject response = responseJson(connection, "Google authorization");
            accessToken = response.getString("access_token");
            accessTokenExpiry = System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000L;
            return accessToken;
        } finally {
            connection.disconnect();
        }
    }

    private String registrationFolder(Network network, String token, DriveCredentials credentials,
                                      String registration) throws Exception {
        String cacheKey = credentials.rootFolderId + "\n" + registration;
        String cached = registrationFolderCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) return cached;

        String q = "'" + escapeQuery(credentials.rootFolderId) + "' in parents and name = '"
                + escapeQuery(registration) + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        String url = DRIVE_FILES + "?spaces=drive&pageSize=2&fields=files(id,name)&q=" + query(q);
        JSONObject found = authorizedJson(network, token, url, "GET", null, "Drive folder lookup");
        JSONArray files = found.optJSONArray("files");
        if (files != null && files.length() > 0) {
            String id = files.getJSONObject(0).getString("id");
            registrationFolderCache.put(cacheKey, id);
            return id;
        }

        JSONObject body = new JSONObject()
                .put("name", registration)
                .put("mimeType", "application/vnd.google-apps.folder")
                .put("parents", new JSONArray().put(credentials.rootFolderId))
                .put("appProperties", new JSONObject().put("slmRegistration", registration));
        String id = authorizedJson(network, token, DRIVE_FILES + "?fields=id", "POST", body,
                "Drive folder creation").getString("id");
        registrationFolderCache.put(cacheKey, id);
        return id;
    }

    private String childFolder(Network network, String token, String parent, String name,
                               String registration) throws Exception {
        String cacheKey = parent + "\n" + name;
        String cached = childFolderCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) return cached;

        String q = "'" + escapeQuery(parent) + "' in parents and name = '"
                + escapeQuery(name)
                + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        String url = DRIVE_FILES + "?spaces=drive&pageSize=2&fields=files(id,name)&q=" + query(q);
        JSONArray files = authorizedJson(network, token, url, "GET", null,
                "Drive report folder lookup").optJSONArray("files");
        if (files != null && files.length() > 0) {
            String id = files.getJSONObject(0).getString("id");
            childFolderCache.put(cacheKey, id);
            return id;
        }

        JSONObject body = new JSONObject()
                .put("name", name)
                .put("mimeType", "application/vnd.google-apps.folder")
                .put("parents", new JSONArray().put(parent))
                .put("appProperties", new JSONObject()
                        .put("slmRegistration", registration)
                        .put("slmCollection", name));
        String id = authorizedJson(network, token, DRIVE_FILES + "?fields=id", "POST", body,
                "Drive report folder creation").getString("id");
        childFolderCache.put(cacheKey, id);
        return id;
    }

    private DriveFileIntegrity findBySha256(Network network, String token, String folder,
                                               String sha256) throws Exception {
        String q = "'" + escapeQuery(folder)
                + "' in parents and trashed = false and appProperties has { key='sha256' and value='"
                + escapeQuery(sha256) + "' }";
        String url = DRIVE_FILES
                + "?spaces=drive&pageSize=1&orderBy=createdTime%20desc"
                + "&fields=files(id,size,sha256Checksum)&q=" + query(q);
        JSONArray files = authorizedJson(network, token, url, "GET", null,
                "Drive integrity lookup").optJSONArray("files");
        if (files == null || files.length() == 0) return null;

        JSONObject file = files.getJSONObject(0);
        return new DriveFileIntegrity(
                file.optString("id"),
                parseLong(file.optString("size"), -1),
                file.optString("sha256Checksum"));
    }

    private DriveFileIntegrity waitForBySha256(Network network, String token, String folder,
                                                String sha256) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            DriveFileIntegrity result = findBySha256(network, token, folder, sha256);
            if (result != null) return result;
            if (attempt < 4) Thread.sleep(500L);
        }
        return null;
    }

    private static void verifyDriveIntegrity(DriveFileIntegrity remote, long localSize,
                                             String localSha256) {
        if (remote.size != localSize) {
            throw new IllegalStateException("Drive size verification failed");
        }
        if (remote.sha256.isEmpty()) {
            throw new IllegalStateException("Drive SHA-256 verification is unavailable");
        }
        if (!remote.sha256.equalsIgnoreCase(localSha256)) {
            throw new IllegalStateException("Drive SHA-256 verification failed");
        }
    }

    private String createSession(Network network, String token, TransferStore.Item item,
                                 String folder) throws Exception {
        JSONObject body = new JSONObject()
                .put("name", item.filename)
                .put("parents", new JSONArray().put(folder))
                .put("appProperties", new JSONObject()
                        .put("sha256", item.sha256)
                        .put("slmRegistration", item.registration)
                        .put("slmCollection", item.driveSubfolder.isEmpty()
                                ? "recordings" : item.driveSubfolder)
                        .put("source", "slm-android"));
        HttpURLConnection connection = open(network,
                DRIVE_UPLOADS + "?uploadType=resumable&fields=id,name,webViewLink", "POST", false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("X-Upload-Content-Type", "application/octet-stream");
        connection.setRequestProperty("X-Upload-Content-Length", Long.toString(item.file.length()));
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            connection.connect();
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            if (status / 100 != 2) throw httpError(connection, "Drive upload session creation");
            String location = connection.getHeaderField("Location");
            validateSessionUrl(location);
            return location;
        } finally {
            connection.disconnect();
        }
    }

    private long queryOffset(Network network, String token, String session, long total) throws Exception {
        validateSessionUrl(session);
        HttpURLConnection connection = open(network, session, "PUT", false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Range", "bytes */" + total);
        connection.setFixedLengthStreamingMode(0);
        try {
            connection.connect();
            try (OutputStream ignored = connection.getOutputStream()) {}
            int status = connection.getResponseCode();
            if (status == 200 || status == 201) return total;
            if (status == 404 || status == 410) throw new ExpiredSessionException();
            if (status != 308) throw httpError(connection, "Drive upload resume");
            return parseRange(connection.getHeaderField("Range"));
        } finally {
            connection.disconnect();
        }
    }

    private void uploadChunks(Network network, String token, TransferStore.Item item, long offset,
                              Progress progress) throws Exception {
        long total = item.file.length();
        if (offset >= total) return;
        try (RandomAccessFile file = new RandomAccessFile(item.file, "r")) {
            file.seek(offset);
            byte[] buffer = new byte[UPLOAD_BUFFER_SIZE];
            int lastPercent = (int) Math.min(99, offset * 100 / total);
            while (offset < total) {
                long chunkStart = offset;
                int wanted = (int) Math.min(CHUNK_SIZE, total - chunkStart);
                long end = chunkStart + wanted - 1;
                HttpURLConnection connection = open(network, item.uploadSession, "PUT", false);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/octet-stream");
                connection.setRequestProperty("Content-Range", "bytes " + chunkStart + "-" + end + "/" + total);
                connection.setFixedLengthStreamingMode(wanted);
                try {
                    connection.connect();
                    long written = 0;
                    try (OutputStream out = connection.getOutputStream()) {
                        while (written < wanted) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                            int count = (int) Math.min(buffer.length, wanted - written);
                            file.readFully(buffer, 0, count);
                            out.write(buffer, 0, count);
                            written += count;
                            int percent = (int) Math.min(99, (chunkStart + written) * 100 / total);
                            if (percent > lastPercent) {
                                lastPercent = percent;
                                progress.update(percent);
                            }
                        }
                    }
                    int status = connection.getResponseCode();
                    if (status != 308 && status != 200 && status != 201) {
                        throw httpError(connection, "Drive file upload");
                    }
                    offset = status == 308 ? parseRange(connection.getHeaderField("Range")) : total;
                    store.updateSession(item, item.uploadSession, offset);
                    int confirmedPercent = (int) Math.min(99, offset * 100 / total);
                    if (confirmedPercent > lastPercent) {
                        lastPercent = confirmedPercent;
                        progress.update(confirmedPercent);
                    }
                } finally {
                    connection.disconnect();
                }
            }
        }
    }

    private JSONObject authorizedJson(Network network, String token, String url, String method,
                                      JSONObject body, String operation) throws Exception {
        HttpURLConnection connection = open(network, url, method, false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.connect();
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
        }
        try {
            return responseJson(connection, operation);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject responseJson(HttpURLConnection connection, String operation) throws Exception {
        int status = connection.getResponseCode();
        if (status / 100 != 2) throw httpError(connection, operation);
        String value = readLimited(connection.getInputStream());
        return value.isEmpty() ? new JSONObject() : new JSONObject(value);
    }

    private static IllegalStateException httpError(HttpURLConnection connection, String operation) throws Exception {
        InputStream stream = connection.getErrorStream();
        String detail = stream == null ? "" : readLimited(stream);
        if (detail.length() > 300) detail = detail.substring(0, 300);
        return new IllegalStateException(operation + " returned HTTP " + connection.getResponseCode()
                + (detail.isEmpty() ? "" : ": " + detail));
    }

    private static HttpURLConnection open(Network network, String value, String method,
                                          boolean redirects) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL(value));
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(redirects);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        if ("POST".equals(method) || "PUT".equals(method)) connection.setDoOutput(true);
        return connection;
    }

    private Network requireInternet() {
        Network network = networks.uploadNetwork();
        if (network == null) throw new IllegalStateException("Internet connection is unavailable");
        return network;
    }

    private static long parseRange(String range) {
        if (range == null || range.isEmpty()) return 0;
        int dash = range.lastIndexOf('-');
        if (dash < 0) return 0;
        try { return Long.parseLong(range.substring(dash + 1).trim()) + 1; }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static void validateSessionUrl(String value) throws Exception {
        URL url = new URL(value == null ? "" : value);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !"www.googleapis.com".equalsIgnoreCase(url.getHost())) {
            throw new IllegalStateException("Drive upload session URL is invalid");
        }
    }

    private static String readLimited(InputStream in) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (out.size() + count > MAX_RESPONSE) throw new IllegalStateException("Google response is too large");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return fallback; }
    }

    private static String escapeQuery(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String query(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static String form(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static final class ExpiredSessionException extends Exception {}
}
