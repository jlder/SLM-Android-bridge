package com.slm.bridge;

import android.net.Uri;
import java.net.URI;
import java.util.Locale;

final class RecorderUrlPolicy {
    private RecorderUrlPolicy() {}

    static boolean isAllowed(String candidate, String recorderBaseUrl) {
        return sameOrigin(Uri.parse(candidate == null ? "" : candidate),
                Uri.parse(recorderBaseUrl == null ? "" : recorderBaseUrl));
    }

    static String resolveAllowedDownload(String candidate, String currentPageUrl,
                                         String recorderBaseUrl) {
        String value = candidate == null ? "" : candidate.trim();
        if (value.isEmpty()) return null;
        if (isAllowed(value, recorderBaseUrl)) return value;

        // WebView normally supplies an absolute download URL, but older recorder
        // pages can expose root-relative or page-relative links. Resolve only
        // genuinely relative references and then apply the same-origin check.
        try {
            URI relative = new URI(value);
            if (relative.isAbsolute()) return null;
            String base = isAllowed(currentPageUrl, recorderBaseUrl)
                    ? currentPageUrl : recorderBaseUrl + "/";
            String resolved = new URI(base).resolve(relative).toString();
            return isAllowed(resolved, recorderBaseUrl) ? resolved : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isAllowedBlob(String candidate, String currentPageUrl,
                                 String recorderBaseUrl) {
        String value = candidate == null ? "" : candidate.trim();
        if (!value.regionMatches(true, 0, "blob:", 0, 5)) return false;
        if (!isAllowed(currentPageUrl, recorderBaseUrl)) return false;
        return isAllowed(value.substring(5), recorderBaseUrl);
    }

    static String filenameFromDownloadUrl(String candidate) {
        try {
            Uri uri = Uri.parse(candidate == null ? "" : candidate);
            String requested = uri.getQueryParameter("file");
            if (requested == null || requested.trim().isEmpty()) return "";
            requested = requested.replace('\\', '/');
            int slash = requested.lastIndexOf('/');
            return slash >= 0 ? requested.substring(slash + 1) : requested;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    static boolean isCalibrationReportDownload(String candidate) {
        try {
            Uri uri = Uri.parse(candidate == null ? "" : candidate);
            String path = uri.getPath();
            if (path != null && path.startsWith("/api/cal/report/")) return true;
            if (!"/api/download".equals(path)) return false;
            String requested = uri.getQueryParameter("file");
            if (requested == null) return false;
            requested = requested.replace('\\', '/');
            return requested.startsWith("/calibration_reports/");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean isHttpUrl(String value) {
        Uri uri = Uri.parse(value == null ? "" : value.trim());
        String scheme = uri.getScheme();
        return uri.getHost() != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    }

    static String origin(String value) {
        Uri uri = Uri.parse(value == null ? "" : value.trim());
        if (!isHttpUrl(value)) return "null";
        int port = effectivePort(uri);
        boolean defaultPort = ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(Locale.ROOT)
                + (defaultPort ? "" : ":" + port);
    }

    private static boolean sameOrigin(Uri first, Uri second) {
        if (!isHttpUrl(first.toString()) || !isHttpUrl(second.toString())) return false;
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(Uri uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
