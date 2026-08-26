package com.slm.bridge;

import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Archives a confirmed Drive-backed recording on the connected recorder. */
final class RecorderArchiveClient {
    private final NetworkCoordinator networks;
    private final AppSettings settings;

    RecorderArchiveClient(NetworkCoordinator networks, AppSettings settings) {
        this.networks = networks;
        this.settings = settings;
    }

    List<String> listRootBinFiles() throws Exception {
        Network network = networks.recorderNetwork();
        if (network == null) throw new IllegalStateException("Recorder Wi-Fi is unavailable");
        String endpoint = settings.recorderBaseUrl() + "/api/files";
        if (!RecorderUrlPolicy.isAllowed(endpoint, settings.recorderBaseUrl())) {
            throw new SecurityException("Recorder file-list endpoint is outside the recorder");
        }
        HttpURLConnection connection =
                (HttpURLConnection) network.openConnection(new URL(endpoint));
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                throw new IllegalStateException("Recorder file list returned HTTP " + status);
            }
            JSONObject response = new JSONObject(readLimited(connection.getInputStream()));
            JSONArray files = response.optJSONArray("files");
            List<String> result = new ArrayList<>();
            if (files == null) return result;
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.optJSONObject(i);
                if (entry == null) continue;
                String name = entry.optString("name");
                if (name.isEmpty() || name.contains("/") || name.contains("\\")
                        || !name.toLowerCase(Locale.US).endsWith(".bin")) continue;
                result.add(name);
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    void archive(String filename) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            Network network = networks.recorderNetwork();
            if (network == null) throw new IllegalStateException("Recorder Wi-Fi is unavailable");
            try {
                int status = postArchive(network, filename);
                if (status / 100 == 2 || isAbsentFromRoot(network, filename)) return;
                last = new IllegalStateException("Recorder archive returned HTTP " + status);
            } catch (Exception e) {
                last = e;
            }
            if (attempt < 5) Thread.sleep(200L * attempt);
        }
        throw last == null ? new IllegalStateException("Recorder archive failed") : last;
    }

    private int postArchive(Network network, String filename) throws Exception {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name())
                .replace("+", "%20");
        String endpoint = settings.recorderBaseUrl() + "/api/archive?file=" + encoded;
        if (!RecorderUrlPolicy.isAllowed(endpoint, settings.recorderBaseUrl())) {
            throw new SecurityException("Recorder archive endpoint is outside the recorder");
        }
        HttpURLConnection connection =
                (HttpURLConnection) network.openConnection(new URL(endpoint));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.setFixedLengthStreamingMode(0);
        connection.setDoOutput(true);
        try {
            connection.getOutputStream().close();
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private boolean isAbsentFromRoot(Network network, String filename) {
        HttpURLConnection connection = null;
        try {
            String endpoint = settings.recorderBaseUrl() + "/api/files";
            connection = (HttpURLConnection) network.openConnection(new URL(endpoint));
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() / 100 != 2) return false;
            JSONObject response = new JSONObject(readLimited(connection.getInputStream()));
            JSONArray files = response.optJSONArray("files");
            if (files == null) return false;
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.optJSONObject(i);
                if (entry != null && filename.equals(entry.optString("name"))) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream in) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (out.size() + count > 256 * 1024) {
                    throw new IllegalStateException("Recorder file list is too large");
                }
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
