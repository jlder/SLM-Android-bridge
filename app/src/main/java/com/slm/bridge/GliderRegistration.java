package com.slm.bridge;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GliderRegistration {
    static final int SUPPORTED_WIFI_GENERATION = 2;

    // Generation 1 used SLM-FCJAF. Generation 2 and later use SLM2-FCJAF,
    // SLM3-FCJAF, etc. Older generations remain connectable only as a firmware
    // recovery path; newer generations require a newer Bridge.
    private static final Pattern RECORDER_SSID =
            Pattern.compile("SLM(?:(\\d+))?-([A-Z0-9]{5})");
    private static final Pattern EMBEDDED = Pattern.compile("([A-Z0-9]{1,3}-[A-Z0-9]{2,8})");
    private static final Pattern VALID = Pattern.compile("[A-Z0-9]{1,3}-[A-Z0-9]{2,8}");
    private static final Pattern COMPACT_SINGLE_LETTER = Pattern.compile("[A-Z][A-Z0-9]{4}");

    private GliderRegistration() {}

    static boolean isRecorderSsid(String ssid) {
        return wifiGenerationFromSsid(ssid) > 0;
    }

    static boolean isSupportedRecorderSsid(String ssid) {
        return wifiGenerationFromSsid(ssid) == SUPPORTED_WIFI_GENERATION;
    }

    static boolean isConnectableRecorderSsid(String ssid) {
        int generation = wifiGenerationFromSsid(ssid);
        return generation > 0 && generation <= SUPPORTED_WIFI_GENERATION;
    }

    static int wifiGenerationFromSsid(String ssid) {
        Matcher matcher = RECORDER_SSID.matcher(normalizeSsid(ssid));
        if (!matcher.matches()) return 0;
        String generation = matcher.group(1);
        if (generation == null || generation.isEmpty()) return 1;
        try {
            int parsed = Integer.parseInt(generation);
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static String compactFromRecorderSsid(String ssid) {
        Matcher matcher = RECORDER_SSID.matcher(normalizeSsid(ssid));
        return matcher.matches() ? matcher.group(2) : "";
    }

    static String wifiPasswordFromSsid(String ssid) {
        if (!isConnectableRecorderSsid(ssid)) return "";
        String compact = compactFromRecorderSsid(ssid);
        if (compact.isEmpty()) return "";
        return "SLM" + new StringBuilder(compact).reverse();
    }

    static String fromSsid(String ssid) {
        String value = normalizeSsid(ssid);
        String compact = compactFromRecorderSsid(value);
        if (!compact.isEmpty()) return compact.substring(0, 1) + "-" + compact.substring(1);
        if (value.startsWith("SLM_") || value.startsWith("SLM ")) {
            value = value.substring(4).trim();
        }
        if (isValid(value)) return value;
        // Preserve the canonical Drive folder name by restoring F-CJAF.
        if (COMPACT_SINGLE_LETTER.matcher(value).matches()) {
            return value.substring(0, 1) + "-" + value.substring(1);
        }
        Matcher matcher = EMBEDDED.matcher(value);
        String result = "";
        while (matcher.find()) result = matcher.group(1);
        return isValid(result) ? result : "";
    }

    static String displayRegistration(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        String compact = normalized.replace("-", "");
        if (compact.matches("[A-Z0-9]{5}")) {
            return compact.substring(0, 1) + "-" + compact.substring(1);
        }
        return normalized;
    }

    static boolean isValid(String value) {
        return value != null && value.indexOf('-') > 0 && VALID.matcher(value).matches();
    }

    private static String normalizeSsid(String ssid) {
        String value = ssid == null ? "" : ssid.trim().toUpperCase(Locale.ROOT);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
