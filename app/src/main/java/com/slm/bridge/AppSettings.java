package com.slm.bridge;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private static final String PREFS = "slm_bridge";
    private static final String RECORDER_BASE_URL = "http://192.168.4.1";
    private final SharedPreferences prefs;

    AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String recorderSsid() { return prefs.getString("active_ssid", ""); }
    String recorderBaseUrl() { return RECORDER_BASE_URL; }
    String gliderRegistration() { return GliderRegistration.fromSsid(recorderSsid()); }
    String driveConfigurationUrl() { return recorderBaseUrl() + "/api/slm-drive-config"; }

    String wifiPasswordForRecorder(String ssid) {
        return GliderRegistration.wifiPasswordFromSsid(ssid);
    }

    /** Return a password saved by the earliest Bridge builds for old-recorder recovery. */
    String legacyWifiPasswordForRecorder(String ssid) {
        return legacyPasswordForSsid(ssid);
    }

    void selectRecorder(String ssid) {
        String value = ssid == null ? "" : ssid.trim();
        if (!GliderRegistration.isRecorderSsid(value)) return;
        prefs.edit()
                .putString("active_ssid", value)
                .remove("recorder_url")
                .remove("registration")
                .remove("upload_url")
                .remove("token")
                .apply();
    }

    private String legacyPasswordForSsid(String ssid) {
        String target = ssid == null ? "" : ssid.trim();
        if (target.isEmpty()) return "";

        String firstSsid = prefs.getString("ssid_1", prefs.getString("ssid", ""));
        if (target.equalsIgnoreCase(firstSsid == null ? "" : firstSsid.trim())) {
            String password = prefs.getString("wifi_password_1", prefs.getString("wifi_password", ""));
            return password == null ? "" : password;
        }

        String secondSsid = prefs.getString("ssid_2", "");
        if (target.equalsIgnoreCase(secondSsid == null ? "" : secondSsid.trim())) {
            String password = prefs.getString("wifi_password_2", "");
            return password == null ? "" : password;
        }
        return "";
    }
}
