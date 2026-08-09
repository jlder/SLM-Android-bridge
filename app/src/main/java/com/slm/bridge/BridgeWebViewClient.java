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
import java.util.function.BooleanSupplier;

final class BridgeWebViewClient extends WebViewClient {
    private static final String LOG_TAG = "SLM-Web";
    private static final String UPDATE_ONLY_SCRIPT =
            "(function(){"
            + "if(window.__slmBridgeUpdateOnlyApplied)return;"
            + "window.__slmBridgeUpdateOnlyApplied=true;"
            + "var maintenance=function(){"
            + "if(typeof window.openMaintenancePage==='function'){window.openMaintenancePage();return;}"
            + "};"
            + "['showHome','openFilesPage','openLogbookPage','openAccelCal','openInstallCal',"
            + "'openHealthPage','openReportPage'].forEach(function(name){"
            + "if(typeof window[name]==='function'){window[name]=maintenance;}"
            + "});"
            + "if(typeof window.openMaintenancePage==='function'){window.openMaintenancePage();return;}"
            + "if(typeof window.showTab==='function'){"
            + "var originalShowTab=window.showTab;"
            + "window.showTab=function(name){if(String(name).toLowerCase()==='ota')return originalShowTab(name);};"
            + "originalShowTab('ota');"
            + "}"
            + "})();";
    private final TransferStore store;
    private final AppSettings settings;
    private final boolean debugBuild;
    private final BooleanSupplier updateOnlyMode;

    BridgeWebViewClient(TransferStore store, AppSettings settings, boolean debugBuild,
                        BooleanSupplier updateOnlyMode) {
        this.store = store;
        this.settings = settings;
        this.debugBuild = debugBuild;
        this.updateOnlyMode = updateOnlyMode;
    }

    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        boolean blocked = !RecorderUrlPolicy.isAllowed(
                request.getUrl().toString(), settings.recorderBaseUrl());
        if (blocked && debugBuild) Log.w(LOG_TAG, "Blocked navigation to " + request.getUrl());
        return blocked;
    }

    @Override public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        if (updateOnlyMode == null || !updateOnlyMode.getAsBoolean()) return;
        if (!RecorderUrlPolicy.isAllowed(url, settings.recorderBaseUrl())) return;
        // Recorder pages are single-page applications: Maintenance/Firmware
        // navigation normally changes visible sections without changing URL.
        // Restrict those page-switch functions after load rather than relying
        // on shouldOverrideUrlLoading(), which would not see the transitions.
        view.evaluateJavascript(UPDATE_ONLY_SCRIPT, null);
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
