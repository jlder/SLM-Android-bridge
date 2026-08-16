package com.slm.bridge;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Emits grep-friendly Logcat evidence and retains a bounded local audit trail
 * for the recording integrity chain.
 *
 * The local file is application-private, is not uploaded, and does not alter
 * transfer state. It allows field validation after wireless ADB disconnects
 * when the phone joins the recorder access point.
 */
final class IntegrityDiagnostics {
    static final String LOG_TAG = "SLMIntegrity";

    private static final String FILE_NAME = "integrity-diagnostics.log";
    private static final int MAX_EVENTS = 1000;
    private static final Object FILE_LOCK = new Object();
    private static volatile Context applicationContext;

    private IntegrityDiagnostics() {}

    static void initialize(Context context) {
        if (context != null) applicationContext = context.getApplicationContext();
    }

    static void legacyCreationShaUnavailable(String filename, long size, String bridgeSha256) {
        event("LEGACY_CREATION_SHA_UNAVAILABLE", filename, size, bridgeSha256, "legacy");
    }

    static void recorderCreationShaVerified(String filename, long size, String sha256) {
        event("RECORDER_CREATION_SHA_VERIFIED", filename, size, sha256, "creation-verified");
    }

    static void recorderDownloadComplete(String filename, long size, String sha256,
                                         String integrityMode) {
        event("RECORDER_DOWNLOAD_COMPLETE", filename, size, sha256, integrityMode);
    }

    static void driveShaVerified(String filename, long size, String sha256,
                                 boolean existingFile) {
        event(existingFile ? "DRIVE_SHA_VERIFIED_EXISTING" : "DRIVE_SHA_VERIFIED_UPLOAD",
                filename, size, sha256, "drive-verified");
    }

    static void recorderArchiveComplete(String filename, long size, String sha256,
                                        String integrityMode) {
        event("RECORDER_ARCHIVE_COMPLETE", filename, size, sha256, integrityMode);
    }

    static void bridgeEvent(String source, String event, String details) {
        String safeSource = source == null || source.trim().isEmpty()
                ? "BRIDGE" : source.trim().toUpperCase(Locale.US);
        String safeEvent = event == null || event.trim().isEmpty()
                ? "UNKNOWN" : event.trim().toUpperCase(Locale.US);
        String message = "src=" + safe(safeSource) + " event=" + safe(safeEvent);
        if (details != null && !details.trim().isEmpty()) message += " " + details.trim();
        Log.i(LOG_TAG, message);
        persist(message);
    }

    static void failure(String filename, String stage, Exception error) {
        String safeFilename = filename == null ? "" : filename;
        String safeStage = stage == null ? "UNKNOWN" : stage.toUpperCase(Locale.US);
        String detail = error == null || error.getMessage() == null
                ? (error == null ? "unknown" : error.getClass().getSimpleName())
                : error.getMessage();
        String message = "src=INTEGRITY event=INTEGRITY_FAILURE stage=" + safeStage
                + " file=" + safe(safeFilename) + " error=" + safe(detail);
        Log.e(LOG_TAG, message, error);
        persist(message);
    }

    static String readAll() {
        Context context = applicationContext;
        if (context == null) return "Diagnostics are not initialized.";
        synchronized (FILE_LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.isFile() || file.length() == 0L) return "No integrity events recorded.";
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            } catch (Exception e) {
                Log.e(LOG_TAG, "Unable to read local diagnostics", e);
                return "Unable to read integrity diagnostics: " + e.getMessage();
            }
            return output.toString();
        }
    }

    static void clear() {
        Context context = applicationContext;
        if (context == null) return;
        synchronized (FILE_LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (file.exists() && !file.delete()) {
                Log.w(LOG_TAG, "Unable to clear local diagnostics");
            }
        }
    }

    private static void event(String event, String filename, long size, String sha256,
                              String integrityMode) {
        String message = "src=INTEGRITY event=" + event
                + " file=" + safe(filename)
                + " size=" + size
                + " sha256=" + safe(sha256).toLowerCase(Locale.US)
                + " mode=" + safe(integrityMode);
        Log.i(LOG_TAG, message);
        persist(message);
    }

    private static void persist(String message) {
        Context context = applicationContext;
        if (context == null) {
            Log.w(LOG_TAG, "Local diagnostics not initialized");
            return;
        }
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        String line = timestamp + " " + message;
        synchronized (FILE_LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            try {
                Deque<String> lines = new ArrayDeque<>(MAX_EVENTS);
                if (file.isFile()) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            new FileInputStream(file), StandardCharsets.UTF_8))) {
                        String existing;
                        while ((existing = reader.readLine()) != null) {
                            if (lines.size() == MAX_EVENTS - 1) lines.removeFirst();
                            lines.addLast(existing);
                        }
                    }
                }
                if (lines.size() == MAX_EVENTS) lines.removeFirst();
                lines.addLast(line);
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
                    for (String item : lines) {
                        writer.write(item);
                        writer.newLine();
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Unable to persist local diagnostics", e);
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }
}
