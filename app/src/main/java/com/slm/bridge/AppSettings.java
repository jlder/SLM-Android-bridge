package com.slm.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AppSettings {
    private static final String PREFS = "slm_bridge";
    private static final String RECORDER_BASE_URL = "http://192.168.4.1";
    private final SharedPreferences prefs;

    static final class RecorderProfile {
        final String ssid;
        final String password;

        RecorderProfile(String ssid, String password) {
            this.ssid = ssid == null ? "" : ssid.trim();
            this.password = password == null ? "" : password;
        }
    }

    AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String recorderSsid() {
        List<RecorderProfile> profiles = recorderProfiles();
        if (profiles.isEmpty()) return "";
        String active = prefs.getString("active_ssid", "");
        for (RecorderProfile profile : profiles) {
            if (profile.ssid.equals(active)) return profile.ssid;
        }
        return profiles.get(0).ssid;
    }

    String recorderPassword() {
        String active = recorderSsid();
        for (RecorderProfile profile : recorderProfiles()) {
            if (profile.ssid.equals(active)) return profile.password;
        }
        return "";
    }

    List<RecorderProfile> recorderProfiles() {
        ArrayList<RecorderProfile> result = new ArrayList<>();
        addProfile(result, prefs.getString("ssid_1", prefs.getString("ssid", "SLM-")),
                prefs.getString("wifi_password_1", prefs.getString("wifi_password", "")));
        addProfile(result, prefs.getString("ssid_2", ""),
                prefs.getString("wifi_password_2", ""));
        return Collections.unmodifiableList(result);
    }

    List<RecorderProfile> validRecorderProfiles() {
        ArrayList<RecorderProfile> result = new ArrayList<>();
        for (RecorderProfile profile : recorderProfiles()) {
            if (isValidRecorderProfile(profile)) result.add(profile);
        }
        return Collections.unmodifiableList(result);
    }

    static boolean isValidRecorderProfile(RecorderProfile profile) {
        if (profile == null || GliderRegistration.fromSsid(profile.ssid).isEmpty()) return false;
        int passwordLength = profile.password.length();
        return passwordLength >= 8 && passwordLength <= 63;
    }

    String recorderBaseUrl() { return RECORDER_BASE_URL; }
    String gliderRegistration() { return GliderRegistration.fromSsid(recorderSsid()); }
    String driveConfigurationUrl() { return recorderBaseUrl() + "/api/slm-drive-config"; }
    boolean scanPermissionPrompted() { return prefs.getBoolean("scan_permission_prompted", false); }

    void markScanPermissionPrompted() {
        prefs.edit().putBoolean("scan_permission_prompted", true).apply();
    }

    void saveProfiles(RecorderProfile first, RecorderProfile second) {
        String active = first.ssid.isEmpty() ? second.ssid : first.ssid;
        prefs.edit()
                .putString("ssid_1", first.ssid)
                .putString("wifi_password_1", first.password)
                .putString("ssid_2", second.ssid)
                .putString("wifi_password_2", second.password)
                .putString("active_ssid", active)
                .remove("recorder_url")
                .remove("ssid")
                .remove("wifi_password")
                .remove("registration")
                .remove("upload_url")
                .remove("token")
                .apply();
    }

    void selectRecorder(String ssid) {
        for (RecorderProfile profile : recorderProfiles()) {
            if (profile.ssid.equals(ssid)) {
                prefs.edit().putString("active_ssid", ssid).apply();
                return;
            }
        }
    }

    private static void addProfile(List<RecorderProfile> profiles, String ssid, String password) {
        RecorderProfile profile = new RecorderProfile(ssid, password);
        if (profile.ssid.isEmpty()) return;
        for (RecorderProfile existing : profiles) {
            if (existing.ssid.equals(profile.ssid)) return;
        }
        profiles.add(profile);
    }

}
