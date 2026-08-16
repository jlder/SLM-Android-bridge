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
}
