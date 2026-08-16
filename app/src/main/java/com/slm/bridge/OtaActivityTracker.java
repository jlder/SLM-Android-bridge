package com.slm.bridge;

import android.os.SystemClock;

/**
 * Tracks recorder OTA activity so the normal recorder health monitor does not
 * tear down the Wi-Fi network while firmware is being written or while the
 * recorder is acknowledging/rebooting after an update.
 */
final class OtaActivityTracker {
    private static final long POST_OTA_GRACE_MS = 20_000L;

    private int activeCount;
    private long protectedUntilMs;

    synchronized void begin(String source) {
        activeCount++;
        protectedUntilMs = Long.MAX_VALUE;
        IntegrityDiagnostics.bridgeEvent("OTA", "START",
                "source=" + safeSource(source) + " active=" + activeCount);
    }

    synchronized void finish(String source) {
        if (activeCount > 0) activeCount--;
        if (activeCount == 0) {
            protectedUntilMs = SystemClock.elapsedRealtime() + POST_OTA_GRACE_MS;
        }
        IntegrityDiagnostics.bridgeEvent("OTA", "FINISH",
                "source=" + safeSource(source)
                        + " active=" + activeCount
                        + " grace_ms=" + POST_OTA_GRACE_MS);
    }

    synchronized boolean isProtected() {
        return activeCount > 0 || SystemClock.elapsedRealtime() < protectedUntilMs;
    }

    synchronized boolean isActive() {
        return activeCount > 0;
    }

    synchronized boolean isInGrace() {
        return activeCount == 0 && SystemClock.elapsedRealtime() < protectedUntilMs;
    }

    private static String safeSource(String source) {
        return source == null || source.trim().isEmpty()
                ? "UNKNOWN" : source.trim().replace(' ', '_');
    }
}
