package com.slm.bridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Durable offline receipts proving that a recorder binary was present on Drive
 * with the expected size and SHA-256 during a successful server refresh.
 */
final class ServerValidationCache {
    static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final String FILE_NAME = "server-validation-cache.json";
    private static final Object FILE_LOCK = new Object();

    static final class Entry {
        final String registration;
        final String filename;
        final long size;
        final String sha256;
        final long serverCreatedAt;

        Entry(String registration, String filename, long size, String sha256,
              long serverCreatedAt) {
            this.registration = normalizeRegistration(registration);
            this.filename = filename == null ? "" : filename;
            this.size = size;
            this.sha256 = normalizeSha(sha256);
            this.serverCreatedAt = serverCreatedAt;
        }
    }

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long lastSuccessfulRefreshAt;

    ServerValidationCache(Context context) {
        file = new File(context.getFilesDir(), FILE_NAME);
        reloadFromDisk();
    }

    synchronized void reloadFromDisk() {
        synchronized (FILE_LOCK) {
            entries.clear();
            lastSuccessfulRefreshAt = 0L;
            load();
            prune(System.currentTimeMillis());
        }
    }

    synchronized boolean contains(String registration, String filename, long size, String sha256) {
        prune(System.currentTimeMillis());
        Entry entry = entries.get(key(registration, filename, size, sha256));
        return entry != null;
    }

    synchronized int size() {
        prune(System.currentTimeMillis());
        return entries.size();
    }

    synchronized long lastSuccessfulRefreshAt() {
        return lastSuccessfulRefreshAt;
    }

    /**
     * Records an upload that this Bridge has just independently verified on Drive.
     * Failure to persist the auxiliary receipt must never invalidate the upload itself.
     */
    synchronized void recordValidated(String registration, String filename, long size,
                                      String sha256, long serverCreatedAt) throws Exception {
        Entry entry = new Entry(registration, filename, size, sha256, serverCreatedAt);
        if (!valid(entry)) return;
        synchronized (FILE_LOCK) {
            entries.clear();
            lastSuccessfulRefreshAt = 0L;
            load();
            entries.put(key(entry), entry);
            prune(System.currentTimeMillis());
            persist(entries, lastSuccessfulRefreshAt);
        }
    }

    /**
     * Replaces the cache with the server's current 30-day validated recording set.
     * The in-memory set is changed only after the new file was written successfully.
     */
    synchronized void replaceFromServer(List<GoogleDriveUploader.ValidatedRecording> recordings,
                                        long refreshedAt) throws Exception {
        long cutoff = refreshedAt - RETENTION_MS;
        Map<String, Entry> replacement = new LinkedHashMap<>();
        if (recordings != null) {
            for (GoogleDriveUploader.ValidatedRecording recording : recordings) {
                if (recording == null) continue;
                Entry entry = new Entry(recording.registration, recording.filename,
                        recording.size, recording.sha256, recording.serverCreatedAt);
                if (!valid(entry) || entry.serverCreatedAt < cutoff) continue;
                replacement.put(key(entry), entry);
            }
        }
        synchronized (FILE_LOCK) {
            persist(replacement, refreshedAt);
            entries.clear();
            entries.putAll(replacement);
            lastSuccessfulRefreshAt = refreshedAt;
        }
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            JSONObject root = new JSONObject(readText(file));
            if (root.optInt("version", 0) != 1) return;
            lastSuccessfulRefreshAt = root.optLong("lastSuccessfulRefreshAt", 0L);
            JSONArray array = root.optJSONArray("entries");
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value == null) continue;
                Entry entry = new Entry(value.optString("registration"),
                        value.optString("filename"), value.optLong("size", -1L),
                        value.optString("sha256"), value.optLong("serverCreatedAt", 0L));
                if (valid(entry)) entries.put(key(entry), entry);
            }
        } catch (Exception error) {
            entries.clear();
            lastSuccessfulRefreshAt = 0L;
            IntegrityDiagnostics.bridgeEvent("SYNC", "VALIDATION_CACHE_LOAD_FAILED",
                    "error=" + safe(error));
        }
    }

    private void prune(long now) {
        long cutoff = now - RETENTION_MS;
        entries.entrySet().removeIf(item -> item.getValue().serverCreatedAt < cutoff);
    }

    private void persist(Map<String, Entry> values, long refreshAt) throws Exception {
        JSONArray array = new JSONArray();
        for (Entry entry : values.values()) {
            array.put(new JSONObject()
                    .put("registration", entry.registration)
                    .put("filename", entry.filename)
                    .put("size", entry.size)
                    .put("sha256", entry.sha256)
                    .put("serverCreatedAt", entry.serverCreatedAt));
        }
        JSONObject root = new JSONObject()
                .put("version", 1)
                .put("retentionDays", 30)
                .put("lastSuccessfulRefreshAt", refreshAt)
                .put("entries", array);

        File temporary = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (file.exists() && !file.delete()) {
            temporary.delete();
            throw new IllegalStateException("Cannot replace server validation cache");
        }
        if (!temporary.renameTo(file)) {
            temporary.delete();
            throw new IllegalStateException("Cannot save server validation cache");
        }
    }

    private static boolean valid(Entry entry) {
        return entry != null
                && !entry.registration.isEmpty()
                && !entry.filename.isEmpty()
                && entry.size >= 0
                && entry.sha256.matches("[0-9a-f]{64}")
                && entry.serverCreatedAt > 0L;
    }

    private static String key(Entry entry) {
        return key(entry.registration, entry.filename, entry.size, entry.sha256);
    }

    private static String key(String registration, String filename, long size, String sha256) {
        return normalizeRegistration(registration) + "\n"
                + (filename == null ? "" : filename) + "\n"
                + size + "\n" + normalizeSha(sha256);
    }

    private static String normalizeRegistration(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.US);
    }

    private static String normalizeSha(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String readText(File source) throws Exception {
        try (FileInputStream in = new FileInputStream(source);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (out.size() + count > 2 * 1024 * 1024) {
                    throw new IllegalStateException("Server validation cache is too large");
                }
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String safe(Exception error) {
        String value = error == null ? "unknown"
                : error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
        return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }
}
