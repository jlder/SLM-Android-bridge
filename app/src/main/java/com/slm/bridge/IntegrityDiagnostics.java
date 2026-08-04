package com.slm.bridge;

import android.util.Log;

import java.util.Locale;

/**
 * Emits durable, grep-friendly evidence for the recording integrity chain.
 *
 * These messages are diagnostic only. They do not alter transfer state and are
 * intentionally kept out of the normal user interface.
 */
final class IntegrityDiagnostics {
    static final String LOG_TAG = "SLMIntegrity";

    private IntegrityDiagnostics() {}

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

    static void failure(String filename, String stage, Exception error) {
        String safeFilename = filename == null ? "" : filename;
        String safeStage = stage == null ? "UNKNOWN" : stage.toUpperCase(Locale.US);
        String detail = error == null || error.getMessage() == null
                ? (error == null ? "unknown" : error.getClass().getSimpleName())
                : error.getMessage();
        Log.e(LOG_TAG, "event=INTEGRITY_FAILURE stage=" + safeStage
                + " file=" + safeFilename + " error=" + detail, error);
    }

    private static void event(String event, String filename, long size, String sha256,
                              String integrityMode) {
        Log.i(LOG_TAG, "event=" + event
                + " file=" + safe(filename)
                + " size=" + size
                + " sha256=" + safe(sha256).toLowerCase(Locale.US)
                + " mode=" + safe(integrityMode));
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace(' ', '_');
    }
}
