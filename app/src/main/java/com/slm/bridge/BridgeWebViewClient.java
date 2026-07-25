package com.slm.bridge;

import android.util.Log;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.FileInputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

final class BridgeWebViewClient extends WebViewClient {
    private static final String LOG_TAG = "SLM-Web";
    private final TransferStore store;
    private final AppSettings settings;
    private final boolean debugBuild;

    BridgeWebViewClient(TransferStore store, AppSettings settings, boolean debugBuild) {
        this.store = store;
        this.settings = settings;
        this.debugBuild = debugBuild;
    }

    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        boolean blocked = !RecorderUrlPolicy.isAllowed(
                request.getUrl().toString(), settings.recorderBaseUrl());
        if (blocked && debugBuild) Log.w(LOG_TAG, "Blocked navigation to " + request.getUrl());
        return blocked;
    }

    @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                          WebResourceError error) {
        if (debugBuild) {
            Log.e(LOG_TAG, "Web request failed: " + request.getUrl() + " — "
                    + error.getErrorCode() + ": " + error.getDescription());
        }
    }

    @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                              WebResourceResponse errorResponse) {
        if (debugBuild) {
            Log.e(LOG_TAG, "Recorder returned HTTP " + errorResponse.getStatusCode()
                    + " for " + request.getUrl());
        }
    }

    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        try {
            URI uri = URI.create(request.getUrl().toString());
            if (!"slm-app.local".equals(uri.getHost())) return null;
            String allowedOrigin = RecorderUrlPolicy.origin(settings.recorderBaseUrl());
            String requestOrigin = request.getRequestHeaders().get("Origin");
            if (requestOrigin != null && !allowedOrigin.equalsIgnoreCase(requestOrigin)) {
                return response(403, "Forbidden", null, allowedOrigin);
            }
            if (!"GET".equalsIgnoreCase(request.getMethod())) {
                return response(405, "Method Not Allowed", null, allowedOrigin);
            }
            String[] parts = uri.getPath().split("/");
            if (parts.length != 4 || !"transfers".equals(parts[1]) || !"content".equals(parts[3])) {
                return response(404, "Not Found", null, allowedOrigin);
            }
            TransferStore.Item item = store.get(parts[2]);
            if (item == null || !item.file.isFile()) return response(404, "Not Found", null, allowedOrigin);
            return response(200, "OK", new FileInputStream(item.file), allowedOrigin);
        } catch (Exception e) {
            return response(500, "Internal Error", null,
                    RecorderUrlPolicy.origin(settings.recorderBaseUrl()));
        }
    }

    private static WebResourceResponse response(int status, String reason, java.io.InputStream body,
                                                String allowedOrigin) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", allowedOrigin);
        headers.put("Cache-Control", "no-store");
        headers.put("Vary", "Origin");
        return new WebResourceResponse("application/octet-stream", null, status, reason, headers, body);
    }
}
