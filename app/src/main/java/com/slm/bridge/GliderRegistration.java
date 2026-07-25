package com.slm.bridge;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GliderRegistration {
    private static final Pattern EMBEDDED = Pattern.compile("([A-Z0-9]{1,3}-[A-Z0-9]{2,8})");
    private static final Pattern VALID = Pattern.compile("[A-Z0-9]{1,3}-[A-Z0-9]{2,8}");
    private static final Pattern COMPACT_SINGLE_LETTER = Pattern.compile("[A-Z][A-Z0-9]{4}");

    private GliderRegistration() {}

    static String fromSsid(String ssid) {
        String value = ssid == null ? "" : ssid.trim().toUpperCase(Locale.ROOT);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.startsWith("SLM-") || value.startsWith("SLM_") || value.startsWith("SLM ")) {
            value = value.substring(4).trim();
        }
        if (isValid(value)) {
            return value;
        }
        // Recorder SSIDs use a compact registration, for example SLM-FCJAF.
        // Preserve the canonical Drive folder name by restoring F-CJAF.
        if (COMPACT_SINGLE_LETTER.matcher(value).matches()) {
            return value.substring(0, 1) + "-" + value.substring(1);
        }
        Matcher matcher = EMBEDDED.matcher(value);
        String result = "";
        while (matcher.find()) result = matcher.group(1);
        return isValid(result) ? result : "";
    }

    static boolean isValid(String value) {
        return value != null && value.indexOf('-') > 0 && VALID.matcher(value).matches();
    }
}
