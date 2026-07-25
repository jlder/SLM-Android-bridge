package com.slm.bridge;

import android.content.Context;
import android.net.Network;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RecorderFileExporter {
    interface Listener { void onFinished(Request request, String error); }

    static final class Request {
        final String url;
        final String userAgent;
        final String cookie;
        final String mimeType;
        final String filename;

        Request(String url, String userAgent, String cookie, String mimeType, String filename) {
            this.url = url;
            this.userAgent = userAgent;
            this.cookie = cookie;
            this.mimeType = mimeType;
            this.filename = filename;
        }
    }

    private final Context context;
    private final NetworkCoordinator networks;
    private final AppSettings settings;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    RecorderFileExporter(Context context, NetworkCoordinator networks, AppSettings settings,
                         Listener listener) {
        this.context = context.getApplicationContext();
        this.networks = networks;
        this.settings = settings;
        this.listener = listener;
    }

    void export(Request request, Uri destination) {
        executor.execute(() -> {
            String error = null;
            try {
                download(request, destination);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String result = error;
            main.post(() -> listener.onFinished(request, result));
        });
    }

    void close() { executor.shutdownNow(); }

    private void download(Request request, Uri destination) throws Exception {
        Network network = networks.recorderNetwork();
        if (network == null) throw new IllegalStateException("Recorder Wi-Fi is unavailable");
        if (!RecorderUrlPolicy.isAllowed(request.url, settings.recorderBaseUrl())) {
            throw new SecurityException("Recorder download URL is outside the allowed origin");
        }

        URL current = new URL(request.url);
        HttpURLConnection connection = null;
        for (int redirects = 0; redirects <= 5; redirects++) {
            connection = (HttpURLConnection) network.openConnection(current);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("Accept", request.mimeType);
            if (!request.userAgent.isEmpty()) connection.setRequestProperty("User-Agent", request.userAgent);
            if (!request.cookie.isEmpty()) connection.setRequestProperty("Cookie", request.cookie);
            int status = connection.getResponseCode();
            if (!isRedirect(status)) break;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            connection = null;
            if (location == null) throw new IllegalStateException("Recorder returned an invalid redirect");
            current = new URL(current, location);
            if (!RecorderUrlPolicy.isAllowed(current.toString(), settings.recorderBaseUrl())) {
                throw new SecurityException("Recorder download redirected outside the allowed origin");
            }
        }

        if (connection == null) throw new IllegalStateException("Too many recorder redirects");
        try {
            int status = connection.getResponseCode();
            if (status / 100 != 2) throw new IllegalStateException("Recorder returned HTTP " + status);
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream raw = context.getContentResolver().openOutputStream(destination, "w")) {
                if (raw == null) throw new IllegalStateException("The selected file cannot be opened");
                try (OutputStream out = new BufferedOutputStream(raw)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307 || status == 308;
    }
}
