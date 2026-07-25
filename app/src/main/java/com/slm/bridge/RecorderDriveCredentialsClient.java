package com.slm.bridge;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class RecorderDriveCredentialsClient {
    private static final int MAX_RESPONSE = 16 * 1024;
    private final NetworkCoordinator networks;
    private final AppSettings settings;

    RecorderDriveCredentialsClient(NetworkCoordinator networks, AppSettings settings) {
        this.networks = networks;
        this.settings = settings;
    }

    DriveCredentials fetch() throws Exception {
        Network network = networks.recorderNetwork();
        if (network == null) throw new IllegalStateException("Recorder Wi-Fi is unavailable");
        String endpoint = settings.driveConfigurationUrl();
        if (!RecorderUrlPolicy.isAllowed(endpoint, settings.recorderBaseUrl())) {
            throw new IllegalStateException("Drive configuration endpoint is outside the recorder");
        }
        HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL(endpoint));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-store");
        try {
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                throw new IllegalStateException("Recorder Drive configuration returned HTTP " + status);
            }
            return DriveCredentials.fromJson(readLimited(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(InputStream in) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (out.size() + count > MAX_RESPONSE) {
                    throw new IllegalStateException("Recorder Drive configuration is too large");
                }
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
