package com.slm.bridge;

import android.content.Context;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloads recorder firmware from the Drive server and uploads it to the recorder OTA endpoint. */
final class FirmwareManager {
    private static final String DRIVE_FILES = "https://www.googleapis.com/drive/v3/files";
    private static final int MAX_RESPONSE = 256 * 1024;
    private static final long MAX_FIRMWARE_BYTES = 32L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final NetworkCoordinator networks;
    private final AppSettings settings;
    private final DriveCredentialStore credentialStore;
    private final RecorderDriveCredentialsClient credentialClient;
    private final WebView webView;
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final List<FirmwareFile> cachedFiles = new ArrayList<>();
    private String accessToken = "";
    private long accessTokenExpiry;

    FirmwareManager(NetworkCoordinator networks, AppSettings settings,
                    DriveCredentialStore credentialStore, WebView webView) {
        this.networks = networks;
        this.settings = settings;
        this.credentialStore = credentialStore;
        this.credentialClient = new RecorderDriveCredentialsClient(networks, settings);
        this.webView = webView;
        this.context = webView.getContext().getApplicationContext();
    }

    void listServerFirmware() {
        if (!busy.compareAndSet(false, true)) {
            emit("busy", 0, "Firmware server operation already running", null);
            return;
        }
        executor.execute(() -> {
            try {
                emit("listing", 0, "Searching firmware from server", null);
                FirmwareList list = fetchFirmwareList();
                synchronized (cachedFiles) {
                    cachedFiles.clear();
                    cachedFiles.addAll(list.files);
                }
                if (list.files.isEmpty()) {
                    emit("failed", 0, list.description, null);
                } else {
                    emit("list", 0, list.description, filesJson(list.files));
                }
            } catch (Exception e) {
                emit("failed", 0, message(e), null);
            } finally {
                busy.set(false);
            }
        });
    }

    void installServerFirmware(String requestJson) {
        if (!busy.compareAndSet(false, true)) {
            emit("busy", 0, "Firmware server operation already running", null);
            return;
        }
        executor.execute(() -> {
            File downloaded = null;
            try {
                JSONObject request = new JSONObject(requestJson == null ? "{}" : requestJson);
                FirmwareFile selected = findCachedFile(request.optString("id"), request.optString("name"));
                if (selected == null) throw new IllegalArgumentException("Select a server firmware file first");

                DriveCredentials credentials = availableCredentials();
                if (credentials == null) {
                    throw new IllegalStateException("Reconnect to the recorder to obtain Drive authorization");
                }
                Network internet = requireInternet();
                String token = accessToken(internet, credentials);
                emit("downloading", 0, "Downloading " + selected.name + " from server", null);
                downloaded = downloadFirmware(internet, token, selected);

                Network recorder = networks.recorderNetwork();
                if (recorder == null) throw new IllegalStateException("Recorder Wi-Fi is unavailable");
                emit("uploading", 0, "Uploading firmware to recorder", null);
                String response = uploadToRecorder(recorder, selected, downloaded);
                emit("complete", 100, response.isEmpty() ? "Firmware update OK. Rebooting..." : response, null);
            } catch (Exception e) {
                emit("failed", 0, message(e), null);
            } finally {
                if (downloaded != null) //noinspection ResultOfMethodCallIgnored
                    downloaded.delete();
                busy.set(false);
            }
        });
    }

    void close() {
        executor.shutdownNow();
    }

    private FirmwareList fetchFirmwareList() throws Exception {
        DriveCredentials credentials = availableCredentials();
        if (credentials == null) {
            throw new IllegalStateException("Reconnect to the recorder to obtain Drive authorization");
        }
        Network network = requireInternet();
        String token = accessToken(network, credentials);
        String registration = settings.gliderRegistration();
        if (registration.isEmpty()) throw new IllegalStateException("Recorder registration is unavailable");

        List<String> checkedPaths = new ArrayList<>();
        for (String folderName : registrationFolderNames(registration)) {
            String registrationFolder = findFolder(network, token, credentials.rootFolderId, folderName);
            if (registrationFolder == null) continue;
            String sourcePath = folderName + "/FIRMWARE";
            checkedPaths.add(sourcePath);
            String folder = findFolder(network, token, registrationFolder, "FIRMWARE");
            if (folder != null) {
                List<FirmwareFile> files = listFirmwareFiles(network, token, folder,
                        sourcePath, "recorder");
                if (!files.isEmpty()) {
                    return new FirmwareList(files, "Firmware files from " + sourcePath);
                }
            }
        }

        checkedPaths.add("SLM-STC-DATA/FIRMWARE");
        String commonFolder = findFolder(network, token, credentials.rootFolderId, "FIRMWARE");
        if (commonFolder != null) {
            List<FirmwareFile> files = listFirmwareFiles(network, token, commonFolder,
                    "SLM-STC-DATA/FIRMWARE", "common");
            if (!files.isEmpty()) {
                return new FirmwareList(files, "Firmware files from SLM-STC-DATA/FIRMWARE");
            }
        }
        return new FirmwareList(new ArrayList<>(),
                "No recorder firmware .bin file found in " + joinPaths(checkedPaths)
                        + ". If the file is visible in Google Drive, refresh the recorder Drive authorization with firmware read access.");
    }

    private DriveCredentials availableCredentials() throws Exception {
        DriveCredentials credentials = credentialStore.load();
        if (credentials != null) return credentials;
        if (networks.recorderNetwork() == null) return null;
        credentials = credentialClient.fetch();
        credentialStore.save(credentials);
        return credentials;
    }

    private FirmwareFile findCachedFile(String id, String name) {
        synchronized (cachedFiles) {
            for (FirmwareFile file : cachedFiles) {
                if (file.id.equals(id) && file.name.equals(name)) return file;
            }
        }
        return null;
    }

    private String accessToken(Network network, DriveCredentials credentials) throws Exception {
        long now = System.currentTimeMillis();
        if (!accessToken.isEmpty() && now + 60_000L < accessTokenExpiry) return accessToken;
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
            accessTokenExpiry = now + response.optLong("expires_in", 3600) * 1000L;
            return accessToken;
        } finally {
            connection.disconnect();
        }
    }

    private static List<String> registrationFolderNames(String registration) {
        List<String> result = new ArrayList<>();
        addUnique(result, registration);
        String compact = registration == null ? "" : registration.replace("-", "").trim();
        addUnique(result, compact);
        addUnique(result, GliderRegistration.displayRegistration(compact));
        return result;
    }

    private static void addUnique(List<String> values, String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) return;
        for (String existing : values) {
            if (existing.equalsIgnoreCase(cleaned)) return;
        }
        values.add(cleaned);
    }

    private static String joinPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "server folders";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) result.append(" or ");
            result.append(paths.get(i));
        }
        return result.toString();
    }

    private String findFolder(Network network, String token, String parent, String name) throws Exception {
        String q = "'" + escapeQuery(parent) + "' in parents and name = '" + escapeQuery(name)
                + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        String url = DRIVE_FILES + "?spaces=drive&pageSize=2&fields=files(id,name)&supportsAllDrives=true&q=" + query(q);
        JSONArray files = authorizedJson(network, token, url, "GET", null,
                "Drive firmware folder lookup").optJSONArray("files");
        if (files != null && files.length() > 0) {
            return files.getJSONObject(0).getString("id");
        }

        String fallbackQ = "'" + escapeQuery(parent)
                + "' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        String fallbackUrl = DRIVE_FILES
                + "?spaces=drive&pageSize=100&fields=files(id,name)&supportsAllDrives=true&q="
                + query(fallbackQ);
        JSONArray fallback = authorizedJson(network, token, fallbackUrl, "GET", null,
                "Drive firmware folder lookup").optJSONArray("files");
        if (fallback == null) return null;
        for (int i = 0; i < fallback.length(); i++) {
            JSONObject folder = fallback.getJSONObject(i);
            if (name.equalsIgnoreCase(folder.optString("name", "").trim())) {
                return folder.getString("id");
            }
        }
        return null;
    }

    private List<FirmwareFile> listFirmwareFiles(Network network, String token, String folder,
                                                 String sourcePath, String sourceType) throws Exception {
        String q = "'" + escapeQuery(folder) + "' in parents and trashed = false";
        String url = DRIVE_FILES
                + "?spaces=drive&pageSize=100&orderBy=modifiedTime%20desc"
                + "&fields=files(id,name,size,modifiedTime,mimeType)&supportsAllDrives=true"
                + "&q=" + query(q);
        JSONArray files = authorizedJson(network, token, url, "GET", null,
                "Drive firmware list").optJSONArray("files");
        List<FirmwareFile> result = new ArrayList<>();
        if (files == null) return result;
        for (int i = 0; i < files.length() && result.size() < 20; i++) {
            JSONObject file = files.getJSONObject(i);
            String mime = file.optString("mimeType", "");
            if ("application/vnd.google-apps.folder".equals(mime)) continue;
            String name = file.optString("name", "");
            if (!isAllowedFirmwareName(name)) continue;
            long size = parseLong(file.optString("size", "0"));
            if (size <= 0 || size > MAX_FIRMWARE_BYTES) continue;
            result.add(new FirmwareFile(file.getString("id"), name, size,
                    file.optString("modifiedTime", ""), sourcePath, sourceType));
        }
        return result;
    }

    private File downloadFirmware(Network network, String token, FirmwareFile selected) throws Exception {
        String url = DRIVE_FILES + "/" + queryPath(selected.id) + "?alt=media&supportsAllDrives=true";
        HttpURLConnection connection = open(network, url, "GET", false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        File target = new File(context.getCacheDir(), "slm_firmware_" + System.currentTimeMillis()
                + "_" + safeFileName(selected.name));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            int status = connection.getResponseCode();
            if (status / 100 != 2) throw httpError(connection, "Drive firmware download");
            long total = connection.getContentLengthLong();
            if (total > MAX_FIRMWARE_BYTES) throw new IllegalStateException("Firmware file is too large");
            long copied = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                int lastPercent = -1;
                while ((count = in.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    copied += count;
                    if (copied > MAX_FIRMWARE_BYTES) throw new IllegalStateException("Firmware file is too large");
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    int percent = total > 0 ? (int) Math.min(99, copied * 100 / total) : 0;
                    if (percent != lastPercent) {
                        emit("downloading", percent, "Downloading firmware from server", null);
                        lastPercent = percent;
                    }
                }
            }
            if (target.length() <= 0) throw new IllegalStateException("Firmware download is empty");
            if (selected.size > 0 && target.length() != selected.size) {
                throw new IllegalStateException("Incomplete firmware download");
            }
            selected.sha256 = hex(digest.digest());
            emit("downloaded", 100, "Firmware downloaded from server", null);
            return target;
        } finally {
            connection.disconnect();
        }
    }

    private String uploadToRecorder(Network network, FirmwareFile selected, File firmware) throws Exception {
        String boundary = "----SLMBridgeFirmware" + System.currentTimeMillis();
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"firmware\"; filename=\""
                + selected.name.replace("\"", "_") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long total = prefix.length + firmware.length() + suffix.length;

        HttpURLConnection connection = (HttpURLConnection) network.openConnection(
                new URL(settings.recorderBaseUrl() + "/api/ota"));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(180_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Accept", "text/plain");
        connection.setFixedLengthStreamingMode(total);
        try {
            connection.connect();
            try (OutputStream out = new BufferedOutputStream(connection.getOutputStream());
                 InputStream in = new BufferedInputStream(new FileInputStream(firmware))) {
                out.write(prefix);
                byte[] buffer = new byte[BUFFER_SIZE];
                long copied = 0;
                int count;
                int lastPercent = -1;
                while ((count = in.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buffer, 0, count);
                    copied += count;
                    int percent = (int) Math.min(99, copied * 100 / Math.max(1L, firmware.length()));
                    if (percent != lastPercent) {
                        emit("uploading", percent, "Uploading firmware to recorder", null);
                        lastPercent = percent;
                    }
                }
                out.write(suffix);
            }
            int status = connection.getResponseCode();
            String response = readLimited(status / 100 == 2
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status / 100 != 2) {
                throw new IllegalStateException("Recorder firmware upload returned HTTP "
                        + status + (response.isEmpty() ? "" : ": " + response));
            }
            return response;
        } finally {
            connection.disconnect();
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
        connection.setReadTimeout(180_000);
        if ("POST".equals(method) || "PUT".equals(method)) connection.setDoOutput(true);
        return connection;
    }

    private Network requireInternet() {
        Network network = networks.uploadNetwork();
        if (network == null) throw new IllegalStateException("Internet connection is unavailable");
        return network;
    }

    private void emit(String state, int percent, String message, JSONArray files) {
        try {
            JSONObject detail = new JSONObject()
                    .put("state", state)
                    .put("percent", Math.max(0, Math.min(100, percent)))
                    .put("message", message == null ? "" : message);
            if (files != null) detail.put("files", files);
            String script = "window.dispatchEvent(new CustomEvent('slm-firmware-event',{detail:"
                    + detail + "}));";
            main.post(() -> webView.evaluateJavascript(script, null));
        } catch (Exception ignored) {}
    }

    private static JSONArray filesJson(List<FirmwareFile> files) throws Exception {
        JSONArray array = new JSONArray();
        for (FirmwareFile file : files) {
            array.put(new JSONObject()
                    .put("id", file.id)
                    .put("name", file.name)
                    .put("size", file.size)
                    .put("modifiedTime", file.modifiedTime)
                    .put("sourcePath", file.sourcePath)
                    .put("sourceType", file.sourceType));
        }
        return array;
    }

    private static boolean isAllowedFirmwareName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".bin") && !lower.contains("merged") && name.indexOf('/') < 0
                && name.indexOf('\\') < 0 && !name.contains("..");
    }

    private static String safeFileName(String value) {
        return value == null ? "firmware.bin" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String query(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static String queryPath(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static String form(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String escapeQuery(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String readLimited(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (out.size() + count > MAX_RESPONSE) throw new IllegalStateException("Server response is too large");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return 0L; }
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

    private static final class FirmwareList {
        final List<FirmwareFile> files;
        final String description;
        FirmwareList(List<FirmwareFile> files, String description) {
            this.files = files;
            this.description = description;
        }
    }

    private static final class FirmwareFile {
        final String id;
        final String name;
        final long size;
        final String modifiedTime;
        final String sourcePath;
        final String sourceType;
        String sha256 = "";
        FirmwareFile(String id, String name, long size, String modifiedTime,
                     String sourcePath, String sourceType) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.modifiedTime = modifiedTime;
            this.sourcePath = sourcePath;
            this.sourceType = sourceType;
        }
    }
}
