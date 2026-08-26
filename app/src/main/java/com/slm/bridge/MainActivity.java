package com.slm.bridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.Network;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;
import java.util.Set;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.UpdateAvailability;

import org.json.JSONObject;


public final class MainActivity extends Activity implements NetworkCoordinator.Listener {
    private static final int PERMISSION_REQUEST = 41;
    private static final int CREATE_DOCUMENT_REQUEST = 42;
    private static final int OPEN_DOCUMENT_REQUEST = 43;
    private static final String LOG_TAG = "SLM-Web";
    private static final String RECORDER_NATIVE_MESSAGE_OBJECT = "SLMNative";
    private static final int COLOR_BLUE = Color.rgb(34, 85, 170);
    private static final int COLOR_GREY = Color.rgb(145, 145, 145);
    private static final int COLOR_GREEN = Color.rgb(142, 214, 82);
    private static final int COLOR_AMBER = Color.rgb(255, 196, 0);
    private static final int COLOR_STATUS_BLINK_DIM = Color.rgb(80, 80, 80);
    private static final long RECORDER_SCAN_FRESH_TIMEOUT_MS = 8_000L;
    private static final long RECORDER_SCAN_FRESH_MARGIN_MS = 10_000L;
    private static final long RECORDER_SCAN_MAX_AGE_MS = 60_000L;
    private static final long RECORDER_UNAVAILABLE_BLOCK_MS = 90_000L;
    private static final long RECORDER_HEALTH_INTERVAL_MS = 3_000L;
    private static final long TITLE_BLINK_INTERVAL_MS = 550L;
    private static final int SERVICE_DIAGNOSTICS_TAP_COUNT = 5;
    private static final long SERVICE_DIAGNOSTICS_TAP_WINDOW_MS = 3_000L;
    private static final int RECORDER_HEALTH_MAX_FAILURES = 3;
    private AppSettings settings;
    private NetworkCoordinator networks;
    private TransferManager transfers;
    private FirmwareManager firmwareManager;
    private RecorderWebMessageBridge recorderWebMessageBridge;
    private boolean recorderWebMessageBridgeConfigured;
    private AppUpdateManager playUpdateManager;
    private boolean playUpdateCheckInFlight;
    private boolean playUpdateCheckComplete;
    private boolean playUpdateDialogShowing;
    private OtaActivityTracker otaActivity;
    private RecorderFileExporter fileExporter;
    private RecorderFileExporter.Request pendingDownload;
    private ValueCallback<Uri[]> pendingFileChooser;
    private WebView webView;
    private TextView recorderStatus;
    private TextView bridgeTitle;
    private TextView bridgeVersion;
    private TextView serverStatus;
    private TextView fileQueueStatus;
    private ProgressBar fileTransferProgress;
    private Button connectButton;
    private boolean recorderConnectionRequested;
    private boolean recorderScanPending;
    private boolean recorderScanReceiverRegistered;
    private boolean resumeRecorderConnectAfterLocationSettings;
    private boolean debugBuild;
    private long recorderScanStartedMs;
    private int recorderHealthFailures;
    private volatile boolean activityForeground;
    private boolean recorderHealthSuspendedForBackground;
    private volatile Network recorderProbeNetwork;
    private volatile boolean recorderReady;
    private volatile boolean recorderUsbPresentValid;
    private volatile boolean recorderUsbPresent;
    private volatile boolean recorderUpdateOnlyMode;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger recorderScanGeneration = new AtomicInteger();
    private final AtomicInteger recorderProbeGeneration = new AtomicInteger();
    private final AtomicInteger recorderHealthGeneration = new AtomicInteger();
    private final ExecutorService recorderProbeExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Long> unavailableRecorderSsids = new HashMap<>();
    private BroadcastReceiver recorderScanReceiver;
    private TransferManager.QueueStatus latestQueueStatus;
    private Network latestUploadNetwork;
    private boolean recorderStatusBlinking;
    private boolean showDisconnectedRecorder;
    private boolean recorderStatusTextVisible = true;
    private String recorderStatusText = "";
    private int recorderStatusColor = COLOR_AMBER;
    private int serviceDiagnosticsTapCount;
    private long serviceDiagnosticsFirstTapMs;
    private final Runnable titleBlinkRunnable = new Runnable() {
        @Override public void run() {
            if (!recorderStatusBlinking) return;
            recorderStatusTextVisible = !recorderStatusTextVisible;
            renderRecorderStatus();
            mainHandler.postDelayed(this, TITLE_BLINK_INTERVAL_MS);
        }
    };


    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.bridgeRoot));
        IntegrityDiagnostics.initialize(this);
        settings = new AppSettings(this);
        debugBuild = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        webView = findViewById(R.id.webView);
        recorderStatus = findViewById(R.id.recorderStatus);
        bridgeTitle = findViewById(R.id.bridgeTitle);
        bridgeVersion = findViewById(R.id.bridgeVersion);
        bridgeVersion.setText("v" + installedVersionName());
        serverStatus = findViewById(R.id.serverStatus);
        fileQueueStatus = findViewById(R.id.fileQueueStatus);
        fileTransferProgress = findViewById(R.id.fileTransferProgress);
        connectButton = findViewById(R.id.connectButton);
        recorderStatusText = getString(R.string.status_no_recorder);
        networks = new NetworkCoordinator(this, this);
        playUpdateManager = AppUpdateManagerFactory.create(this);
        otaActivity = new OtaActivityTracker();
        fileExporter = new RecorderFileExporter(this, networks, settings, this::onExportFinished);
        TransferStore store = new TransferStore(this);
        DriveCredentialStore driveCredentialStore = new DriveCredentialStore(this);
        driveCredentialStore.ensureBackgroundRefreshScheduled();
        transfers = new TransferManager(networks, settings, store,
                driveCredentialStore, webView,
                status -> runOnUiThread(() -> updateServerQueueUi(status)));
        firmwareManager = new FirmwareManager(networks, settings, driveCredentialStore, webView, otaActivity);
        configureWebView(store, transfers, firmwareManager);
        connectButton.setOnClickListener(v -> {
            resetServiceDiagnosticsTapSequence();
            toggleConnection();
        });
        bridgeTitle.setOnClickListener(v -> onBridgeTitleTapped());
        updateConnectionUi(networks.recorderNetwork(), networks.uploadNetwork());
        maybeCheckPlayStoreUpdate();
    }

    @Override protected void onStart() {
        super.onStart();
        activityForeground = true;
        Network healthNetwork = recorderProbeNetwork;
        boolean resumeHealth = recorderHealthSuspendedForBackground
                && recorderReady
                && healthNetwork != null
                && healthNetwork.equals(networks.recorderNetwork());
        if (recorderHealthSuspendedForBackground) {
            recorderHealthSuspendedForBackground = false;
            recorderHealthFailures = 0;
            IntegrityDiagnostics.bridgeEvent("HTTP", "HEALTH_MONITOR_RESUMED",
                    "reason=APP_FOREGROUND");
        }
        if (resumeHealth) startRecorderHealthMonitor(healthNetwork);
    }

    @Override protected void onStop() {
        activityForeground = false;
        if (recorderReady && recorderProbeNetwork != null) {
            suspendRecorderHealthForBackground();
        }
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        maybeCheckPlayStoreUpdate();
        if (!resumeRecorderConnectAfterLocationSettings) return;

        resumeRecorderConnectAfterLocationSettings = false;
        if (hasRecorderConnectionPermission() && isSystemLocationEnabled()) {
            mainHandler.post(this::beginRecorderScan);
        }
    }


    private String installedVersionName() {
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
            return versionName != null && !versionName.isEmpty() ? versionName : "unknown";
        } catch (PackageManager.NameNotFoundException error) {
            Log.w(LOG_TAG, "Unable to read installed Bridge version", error);
            return "unknown";
        }
    }

    private long installedVersionCode() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .getLongVersionCode();
        } catch (PackageManager.NameNotFoundException error) {
            Log.w(LOG_TAG, "Unable to read installed Bridge version code", error);
            return -1L;
        }
    }


    private void onBridgeTitleTapped() {
        long now = SystemClock.elapsedRealtime();
        if (serviceDiagnosticsTapCount == 0
                || now - serviceDiagnosticsFirstTapMs > SERVICE_DIAGNOSTICS_TAP_WINDOW_MS) {
            serviceDiagnosticsTapCount = 1;
            serviceDiagnosticsFirstTapMs = now;
        } else {
            serviceDiagnosticsTapCount++;
        }

        if (serviceDiagnosticsTapCount >= SERVICE_DIAGNOSTICS_TAP_COUNT) {
            resetServiceDiagnosticsTapSequence();
            showIntegrityDiagnostics();
        }
    }

    private void resetServiceDiagnosticsTapSequence() {
        serviceDiagnosticsTapCount = 0;
        serviceDiagnosticsFirstTapMs = 0L;
    }


    private void showIntegrityDiagnostics() {
        TextView content = new TextView(this);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        content.setTextIsSelectable(true);
        content.setTextSize(12f);
        content.setText(IntegrityDiagnostics.readAll());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Integrity diagnostics")
                .setView(scroll)
                .setNeutralButton("Export", null)
                .setNegativeButton("Clear", null)
                .setPositiveButton("Close", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    exportIntegrityDiagnostics());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle("Clear diagnostics")
                            .setMessage("Remove all locally stored integrity events?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Clear", (confirm, which) -> {
                                IntegrityDiagnostics.clear();
                                content.setText(IntegrityDiagnostics.readAll());
                            })
                            .show());
        });
        dialog.show();
    }

    private void exportIntegrityDiagnostics() {
        String diagnostics = IntegrityDiagnostics.readAll();
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "SLM Bridge integrity diagnostics");
        share.putExtra(Intent.EXTRA_TEXT, diagnostics);
        try {
            startActivity(Intent.createChooser(share, "Export integrity diagnostics"));
        } catch (ActivityNotFoundException e) {
            showMessageDialog("Export unavailable",
                    "No application is available to export the diagnostics.");
        }
    }

    private void applySystemBarInsets(View root) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int insetLeft;
            int insetTop;
            int insetRight;
            int insetBottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                Insets tappableElement = windowInsets.getInsets(WindowInsets.Type.tappableElement());
                insetLeft = Math.max(systemBars.left, tappableElement.left);
                insetTop = Math.max(systemBars.top, tappableElement.top);
                insetRight = Math.max(systemBars.right, tappableElement.right);
                insetBottom = Math.max(systemBars.bottom, tappableElement.bottom);
            } else {
                insetLeft = windowInsets.getSystemWindowInsetLeft();
                insetTop = windowInsets.getSystemWindowInsetTop();
                insetRight = windowInsets.getSystemWindowInsetRight();
                insetBottom = windowInsets.getSystemWindowInsetBottom();
            }
            view.setPadding(left + insetLeft, top + insetTop,
                    right + insetRight, bottom + insetBottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void configureWebView(TransferStore store, TransferManager transfers,
                                  FirmwareManager firmwareManager) {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        webSettings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(debugBuild);
        webView.setWebViewClient(new BridgeWebViewClient(
                store, settings, debugBuild, () -> recorderUpdateOnlyMode));
        webView.setWebChromeClient(createRecorderWebChromeClient());
        webView.setDownloadListener(this::onDownloadRequested);
        configureRecorderWebMessageBridge(transfers, firmwareManager);
    }

    private void configureRecorderWebMessageBridge(TransferManager transfers,
                                                   FirmwareManager firmwareManager) {
        recorderWebMessageBridge = new RecorderWebMessageBridge(
                transfers, networks, firmwareManager, otaActivity);

        boolean listenerSupported = WebViewFeature.isFeatureSupported(
                WebViewFeature.WEB_MESSAGE_LISTENER);
        boolean documentStartSupported = WebViewFeature.isFeatureSupported(
                WebViewFeature.DOCUMENT_START_SCRIPT);
        if (!listenerSupported || !documentStartSupported) {
            IntegrityDiagnostics.bridgeEvent("WEB", "SECURE_JS_BRIDGE_UNAVAILABLE",
                    "web_message_listener=" + listenerSupported
                            + " document_start_script=" + documentStartSupported);
            Log.e(LOG_TAG, "Secure recorder JavaScript bridge unavailable; "
                    + "Android System WebView must be updated");
            return;
        }

        String allowedOrigin = RecorderUrlPolicy.origin(settings.recorderBaseUrl());
        Set<String> allowedOrigins = Collections.singleton(allowedOrigin);
        WebViewCompat.addWebMessageListener(webView, RECORDER_NATIVE_MESSAGE_OBJECT,
                allowedOrigins, (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame
                            || sourceOrigin == null
                            || !RecorderUrlPolicy.isAllowed(
                                    sourceOrigin.toString(), settings.recorderBaseUrl())) {
                        if (debugBuild) {
                            Log.w(LOG_TAG, "Blocked recorder bridge message from "
                                    + sourceOrigin + " mainFrame=" + isMainFrame);
                        }
                        return;
                    }
                    String messageData = message.getData();
                    if (messageData == null) {
                        if (debugBuild) Log.w(LOG_TAG, "Blocked non-string recorder bridge message");
                        return;
                    }
                    try {
                        String response = recorderWebMessageBridge.handleMessage(messageData);
                        if (response != null) replyProxy.postMessage(response);
                    } catch (Exception error) {
                        IntegrityDiagnostics.bridgeEvent("WEB", "JS_BRIDGE_REQUEST_REJECTED",
                                "error=" + error.getClass().getSimpleName());
                        if (debugBuild) Log.w(LOG_TAG, "Recorder bridge request rejected", error);
                    }
                });
        WebViewCompat.addDocumentStartJavaScript(webView,
                recorderWebMessageBridge.compatibilityScript(), allowedOrigins);
        recorderWebMessageBridgeConfigured = true;
        transfers.setRecorderStateListener(this::pushRecorderTransferStates);
        IntegrityDiagnostics.bridgeEvent("WEB", "SECURE_JS_BRIDGE_READY",
                "origin=" + allowedOrigin);
    }

    private void pushRecorderTransferStates(String statesJson) {
        if (!recorderWebMessageBridgeConfigured || statesJson == null) return;
        mainHandler.post(() -> {
            if (webView == null
                    || !RecorderUrlPolicy.isAllowed(webView.getUrl(), settings.recorderBaseUrl())) {
                return;
            }
            String script = "if(window.__slmAndroidSetTransferStates)"
                    + "window.__slmAndroidSetTransferStates("
                    + JSONObject.quote(statesJson) + ");";
            webView.evaluateJavascript(script, null);
        });
    }

    private WebChromeClient createRecorderWebChromeClient() {
        return new WebChromeClient() {
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (RecorderUrlPolicy.isAllowed(url, settings.recorderBaseUrl())) return false;
                result.confirm();
                return true;
            }

            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (RecorderUrlPolicy.isAllowed(url, settings.recorderBaseUrl())) return false;
                result.cancel();
                return true;
            }

            @Override public boolean onJsPrompt(WebView view, String url, String message,
                                                String defaultValue, JsPromptResult result) {
                if (RecorderUrlPolicy.isAllowed(url, settings.recorderBaseUrl())) return false;
                result.cancel();
                return true;
            }

            @Override public boolean onShowFileChooser(WebView requestingView,
                                                       ValueCallback<Uri[]> filePathCallback,
                                                       FileChooserParams params) {
                if (!RecorderUrlPolicy.isAllowed(requestingView.getUrl(), settings.recorderBaseUrl())) {
                    filePathCallback.onReceiveValue(null);
                    return true;
                }
                if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
                pendingFileChooser = filePathCallback;
                try {
                    startActivityForResult(params.createIntent(), OPEN_DOCUMENT_REQUEST);
                } catch (ActivityNotFoundException e) {
                    pendingFileChooser = null;
                    filePathCallback.onReceiveValue(null);
                    showMessageDialog(getString(R.string.file_selection_unavailable_title),
                            getString(R.string.file_selection_unavailable_message));
                }
                return true;
            }

            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (debugBuild) {
                    Log.d(LOG_TAG, message.message() + " (" + message.sourceId()
                            + ":" + message.lineNumber() + ")");
                }
                return true;
            }
        };
    }

    private void onDownloadRequested(String url, String userAgent, String contentDisposition,
                                     String mimeType, long contentLength) {
        if (pendingDownload != null) {
            showMessageDialog(getString(R.string.file_selection_in_progress_title),
                    getString(R.string.file_selection_in_progress_message));
            return;
        }
        if (networks.recorderNetwork() == null) {
            showMessageDialog(getString(R.string.recorder_not_connected_title),
                    getString(R.string.recorder_not_connected_download_message));
            return;
        }
        String safeMimeType = mimeType == null || mimeType.trim().isEmpty()
                ? "application/octet-stream" : mimeType.split(";", 2)[0].trim();
        String filename;
        String resolvedUrl = RecorderUrlPolicy.resolveAllowedDownload(url,
                webView.getUrl(), settings.recorderBaseUrl());
        if (RecorderUrlPolicy.isAllowedBlob(url, webView.getUrl(), settings.recorderBaseUrl())) {
            // The recorder's desktop-browser path may request a second blob
            // download after analysis. Android's native transfer already owns
            // the app-private copy, so no user-visible export is needed here.
            if (debugBuild) Log.d(LOG_TAG, "Suppressed redundant recorder blob download");
            return;
        } else if (resolvedUrl == null) {
            if (debugBuild) {
                Log.w(LOG_TAG, "Blocked recorder download: url=" + url
                        + ", page=" + webView.getUrl()
                        + ", expectedOrigin=" + RecorderUrlPolicy.origin(settings.recorderBaseUrl()));
            }
            showMessageDialog(getString(R.string.download_blocked_title),
                    getString(R.string.download_blocked_message));
            return;
        } else {
            String recorderFilename = RecorderUrlPolicy.filenameFromDownloadUrl(resolvedUrl);
            filename = sanitizeFilename(recorderFilename.isEmpty()
                    ? URLUtil.guessFileName(url, contentDisposition, safeMimeType)
                    : recorderFilename);
            String cookie = CookieManager.getInstance().getCookie(resolvedUrl);
            pendingDownload = new RecorderFileExporter.Request(resolvedUrl,
                    userAgent == null ? "" : userAgent,
                    cookie == null ? "" : cookie,
                    safeMimeType, filename);
        }

        Intent destination = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(safeMimeType)
                .putExtra(Intent.EXTRA_TITLE, filename);
        try {
            startActivityForResult(destination, CREATE_DOCUMENT_REQUEST);
        } catch (ActivityNotFoundException e) {
            pendingDownload = null;
            showMessageDialog(getString(R.string.file_save_unavailable_title),
                    getString(R.string.file_save_unavailable_message));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_DOCUMENT_REQUEST) {
            ValueCallback<Uri[]> callback = pendingFileChooser;
            pendingFileChooser = null;
            if (callback != null) {
                callback.onReceiveValue(filterContentUris(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data)));
            }
            return;
        }
        if (requestCode != CREATE_DOCUMENT_REQUEST) return;
        RecorderFileExporter.Request request = pendingDownload;
        pendingDownload = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || request == null) return;
        Toast.makeText(this, getString(R.string.saving_file, request.filename), Toast.LENGTH_SHORT).show();
        fileExporter.export(request, data.getData());
    }

    private void onExportFinished(RecorderFileExporter.Request request, String error) {
        boolean calibrationReport = error == null
                && RecorderUrlPolicy.isCalibrationReportDownload(request.url);
        TransferManager manager = transfers;
        if (calibrationReport && manager != null) manager.enqueueReport(request);
        if (error == null) {
            String message = calibrationReport
                    ? getString(R.string.file_saved_queued, request.filename)
                    : getString(R.string.file_saved, request.filename);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } else {
            showMessageDialog(getString(R.string.download_failed_title), error);
        }
    }

    private void showMessageDialog(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.button_ok), null)
                .show();
    }

    private static String sanitizeFilename(String value) {
        String result = value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return result.isEmpty() ? "recorder-download" : result;
    }

    private static Uri[] filterContentUris(Uri[] values) {
        if (values == null || values.length == 0) return null;
        java.util.ArrayList<Uri> accepted = new java.util.ArrayList<>();
        for (Uri value : values) {
            if (value != null && "content".equalsIgnoreCase(value.getScheme())) accepted.add(value);
        }
        return accepted.isEmpty() ? null : accepted.toArray(new Uri[0]);
    }

    private void requestPermissionAndConnect() {
        if (hasRecorderConnectionPermission()) {
            beginRecorderScan();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_REQUEST);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST);
        }
    }

    private void toggleConnection() {
        if (recorderScanPending || networks.recorderNetwork() != null
                || recorderConnectionRequested || recorderReady) {
            recorderConnectionRequested = false;
            showDisconnectedRecorder = false;
            resetRecorderCompatibilityMode();
            cancelRecorderScan();
            resetRecorderProbe();
            networks.disconnectRecorder();
            updateConnectionUi(null, networks.uploadNetwork());
            return;
        }
        requestPermissionAndConnect();
    }

    @SuppressWarnings("deprecation")
    private void beginRecorderScan() {
        if (!isSystemLocationEnabled()) {
            showLocationRequiredDialog();
            return;
        }

        cancelRecorderScan();
        resetRecorderProbe();
        resetRecorderCompatibilityMode();
        recorderConnectionRequested = false;
        showDisconnectedRecorder = false;
        recorderScanPending = true;
        recorderScanStartedMs = SystemClock.elapsedRealtime();
        final int generation = recorderScanGeneration.incrementAndGet();
        updateConnectionUi(null, networks.uploadNetwork());

        WifiManager wifi;
        try {
            wifi = getSystemService(WifiManager.class);
            if (wifi == null) {
                recorderScanPending = false;
                updateConnectionUi(null, networks.uploadNetwork());
                showMessageDialog(getString(R.string.wifi_unavailable_title),
                        getString(R.string.wifi_unavailable_message));
                return;
            }
            registerRecorderScanReceiver(generation);
            boolean requested = false;
            try {
                requested = wifi.startScan();
            } catch (RuntimeException error) {
                Log.w(LOG_TAG, "Wi-Fi scan request failed", error);
            }
            if (!requested) {
                Log.w(LOG_TAG, "Wi-Fi scan request was not accepted; trying recent cached results");
            }
            mainHandler.postDelayed(
                    () -> finishRecorderScan(generation, false),
                    RECORDER_SCAN_FRESH_TIMEOUT_MS);
        } catch (SecurityException security) {
            recorderScanPending = false;
            unregisterRecorderScanReceiver();
            updateConnectionUi(null, networks.uploadNetwork());
            showMessageDialog(getString(R.string.wifi_permission_required_title),
                    getString(R.string.wifi_scan_permission_message));
        }
    }

    private void registerRecorderScanReceiver(int generation) {
        unregisterRecorderScanReceiver();
        recorderScanReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (generation != recorderScanGeneration.get() || !recorderScanPending) return;
                boolean updated = intent != null
                        && intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                if (!updated) {
                    Log.d(LOG_TAG, "Ignoring Wi-Fi scan broadcast without updated results");
                    return;
                }
                finishRecorderScan(generation, true);
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(recorderScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recorderScanReceiver, filter);
        }
        recorderScanReceiverRegistered = true;
    }

    private void unregisterRecorderScanReceiver() {
        if (!recorderScanReceiverRegistered || recorderScanReceiver == null) return;
        try { unregisterReceiver(recorderScanReceiver); } catch (RuntimeException ignored) {}
        recorderScanReceiver = null;
        recorderScanReceiverRegistered = false;
    }

    private void finishRecorderScan(int generation, boolean freshResultsAvailable) {
        if (generation != recorderScanGeneration.get() || !recorderScanPending) return;
        recorderScanPending = false;
        unregisterRecorderScanReceiver();
        List<DiscoveredRecorder> recorders = scanSlmRecorders(!freshResultsAvailable);
        if (recorders.isEmpty()) {
            updateConnectionUi(null, networks.uploadNetwork());
            if (!freshResultsAvailable) {
                showScanTemporarilyUnavailableDialog();
            } else {
                showMessageDialog(getString(R.string.recorder_not_found_title),
                        getString(R.string.recorder_not_found_scan_message));
            }
            return;
        }
        ArrayList<DiscoveredRecorder> connectableRecorders = new ArrayList<>();
        for (DiscoveredRecorder recorder : recorders) {
            if (GliderRegistration.isConnectableRecorderSsid(recorder.ssid)) {
                connectableRecorders.add(recorder);
            }
        }
        if (connectableRecorders.isEmpty()) {
            updateConnectionUi(null, networks.uploadNetwork());
            showWifiGenerationConflict(recorders);
            return;
        }
        if (connectableRecorders.size() == 1) {
            connectToRecorder(connectableRecorders.get(0).ssid);
            return;
        }
        updateConnectionUi(null, networks.uploadNetwork());
        showRecorderSelection(connectableRecorders);
    }


    private void showWifiGenerationConflict(List<DiscoveredRecorder> recorders) {
        ArrayList<DiscoveredRecorder> incompatible = new ArrayList<>();
        for (DiscoveredRecorder recorder : recorders) {
            int generation = GliderRegistration.wifiGenerationFromSsid(recorder.ssid);
            if (generation > GliderRegistration.SUPPORTED_WIFI_GENERATION) {
                incompatible.add(recorder);
            }
        }

        if (incompatible.size() == 1) {
            DiscoveredRecorder recorder = incompatible.get(0);
            showWifiGenerationConflictFor(
                    GliderRegistration.wifiGenerationFromSsid(recorder.ssid), recorder.ssid);
            return;
        }

        StringBuilder registrations = new StringBuilder();
        for (DiscoveredRecorder recorder : incompatible) {
            String registration = recorderDisplayRegistration(recorder.ssid);
            if (registration.isEmpty()) continue;
            if (registrations.length() > 0) registrations.append("\n");
            registrations.append("• ").append(registration);
        }

        String message = getString(R.string.wifi_version_conflict_message);
        if (registrations.length() > 0) {
            message += "\n\n" + getString(R.string.recorders_label) + "\n" + registrations;
        }
        message += "\n\n" + getString(R.string.update_bridge_before_connecting);

        showMessageDialog(getString(R.string.wifi_version_conflict_title), message);
    }

    private void showWifiGenerationConflictFor(int generation) {
        showWifiGenerationConflictFor(generation, null);
    }

    private void showWifiGenerationConflictFor(int generation, String ssid) {
        String registration = recorderDisplayRegistration(ssid);
        String recorderName = registration.isEmpty()
                ? getString(R.string.this_recorder)
                : getString(R.string.recorder_named, registration);
        final String title;
        final String message;
        if (generation > GliderRegistration.SUPPORTED_WIFI_GENERATION) {
            title = getString(R.string.bridge_update_required_title);
            message = getString(R.string.newer_wifi_generation_message, recorderName);
        } else if (generation > 0) {
            title = getString(R.string.recorder_update_required_title);
            message = getString(R.string.older_wifi_generation_message, recorderName);
        } else {
            title = getString(R.string.invalid_recorder_wifi_title);
            message = getString(R.string.invalid_slm_wifi_message);
        }
        showMessageDialog(title, message);
    }

    private static String recorderDisplayRegistration(String ssid) {
        return GliderRegistration.displayRegistration(GliderRegistration.fromSsid(ssid));
    }

    private void cancelRecorderScan() {
        recorderScanGeneration.incrementAndGet();
        recorderScanPending = false;
        unregisterRecorderScanReceiver();
    }

    private void connectToRecorder(String ssid) {
        int generation = GliderRegistration.wifiGenerationFromSsid(ssid);
        if (generation <= 0 || generation > GliderRegistration.SUPPORTED_WIFI_GENERATION) {
            showWifiGenerationConflictFor(generation);
            return;
        }
        String password = settings.wifiPasswordForRecorder(ssid);
        if (password.isEmpty()) {
            showMessageDialog(getString(R.string.invalid_recorder_wifi_title),
                    getString(R.string.invalid_recorder_wifi_named_message, ssid));
            return;
        }
        cancelRecorderScan();
        settings.selectRecorder(ssid);
        resetRecorderProbe();
        recorderUpdateOnlyMode = generation < GliderRegistration.SUPPORTED_WIFI_GENERATION;
        showDisconnectedRecorder = false;
        recorderConnectionRequested = true;
        updateConnectionUi(null, networks.uploadNetwork());
        IntegrityDiagnostics.bridgeEvent("NET", "CONNECT_REQUEST",
                "ssid=" + ssid + " vpn_active=" + networks.isVpnActive());
        networks.connect(ssid, password);
        Toast.makeText(this, getString(R.string.connecting_to_ssid, ssid), Toast.LENGTH_SHORT).show();
    }

    private List<DiscoveredRecorder> scanSlmRecorders(boolean acceptCachedResults) {
        ArrayList<DiscoveredRecorder> result = new ArrayList<>();
        long nowMs = SystemClock.elapsedRealtime();
        pruneUnavailableRecorders(nowMs);
        try {
            WifiManager wifi = getSystemService(WifiManager.class);
            if (wifi == null) return result;
            for (ScanResult scan : wifi.getScanResults()) {
                if (!scanResultIsUsable(scan, acceptCachedResults, nowMs)) continue;
                String ssid = scan.SSID == null ? "" : scan.SSID.trim();
                if (!GliderRegistration.isRecorderSsid(ssid)) continue;
                if (unavailableRecorderSsids.containsKey(ssid)) {
                    if (acceptCachedResults) continue;
                    unavailableRecorderSsids.remove(ssid);
                }
                int existingIndex = findDiscoveredRecorder(result, ssid);
                if (existingIndex < 0) {
                    result.add(new DiscoveredRecorder(ssid, scan.level));
                } else if (scan.level > result.get(existingIndex).level) {
                    result.set(existingIndex, new DiscoveredRecorder(ssid, scan.level));
                }
            }
        } catch (SecurityException security) {
            showMessageDialog(getString(R.string.wifi_permission_required_title),
                    getString(R.string.wifi_scan_permission_message));
        } catch (RuntimeException ignored) {
            // Use an empty result and let the normal user message explain that no recorder was found.
        }
        result.sort((left, right) -> Integer.compare(right.level, left.level));
        return result;
    }

    private boolean scanResultIsUsable(ScanResult scan, boolean acceptCachedResults, long nowMs) {
        if (scan == null) return false;
        if (scan.timestamp <= 0L) return true;
        long resultMs = scan.timestamp / 1000L;
        if (resultMs <= 0L || resultMs > nowMs + 5_000L) return acceptCachedResults;
        if (acceptCachedResults) return nowMs - resultMs <= RECORDER_SCAN_MAX_AGE_MS;
        return resultMs >= recorderScanStartedMs - RECORDER_SCAN_FRESH_MARGIN_MS;
    }


    private void markRecorderUnavailable(String ssid) {
        if (!GliderRegistration.isRecorderSsid(ssid)) return;
        unavailableRecorderSsids.put(ssid, SystemClock.elapsedRealtime());
    }

    private void pruneUnavailableRecorders(long nowMs) {
        Iterator<Map.Entry<String, Long>> iterator = unavailableRecorderSsids.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (nowMs - entry.getValue() > RECORDER_UNAVAILABLE_BLOCK_MS) iterator.remove();
        }
    }

    private static int findDiscoveredRecorder(List<DiscoveredRecorder> recorders, String ssid) {
        for (int index = 0; index < recorders.size(); index++) {
            if (recorders.get(index).ssid.equals(ssid)) return index;
        }
        return -1;
    }

    private void showRecorderSelection(List<DiscoveredRecorder> recorders) {
        String[] items = new String[recorders.size()];
        for (int index = 0; index < recorders.size(); index++) {
            items[index] = recorderDisplayRegistration(recorders.get(index).ssid);
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_slm_recorder_title))
                .setItems(items, (dialog, which) -> connectToRecorder(recorders.get(which).ssid))
                .setNegativeButton(getString(R.string.button_cancel), (dialog, which) ->
                        updateConnectionUi(networks.recorderNetwork(), networks.uploadNetwork()))
                .setOnCancelListener(dialog ->
                        updateConnectionUi(networks.recorderNetwork(), networks.uploadNetwork()))
                .show();
    }

    @Override public void onNetworksChanged(Network recorder, Network internet) {
        if (transfers != null) transfers.onNetworksChanged();
        boolean beginProbe = false;
        synchronized (this) {
            if (recorder == null) {
                if (recorderProbeNetwork != null || recorderReady) {
                    boolean otaActive = otaActivity != null && otaActivity.isActive();
                    boolean otaGrace = otaActivity != null && otaActivity.isInGrace();
                    IntegrityDiagnostics.bridgeEvent("NET", "RECORDER_NETWORK_LOST",
                            "ota_active=" + otaActive
                                    + " ota_grace=" + otaGrace
                                    + " expected_reboot=" + otaGrace);
                }
                if (recorderReady) showDisconnectedRecorder = true;
                if (recorderProbeNetwork != null || recorderReady) {
                    recorderProbeGeneration.incrementAndGet();
                    stopRecorderHealthMonitor();
                }
                recorderProbeNetwork = null;
                recorderReady = false;
                recorderUsbPresentValid = false;
                recorderUsbPresent = false;
            } else if (!recorder.equals(recorderProbeNetwork)) {
                recorderProbeNetwork = recorder;
                recorderReady = false;
                recorderUsbPresentValid = false;
                recorderUsbPresent = false;
                stopRecorderHealthMonitor();
                IntegrityDiagnostics.bridgeEvent("NET", "RECORDER_NETWORK_AVAILABLE",
                        "ssid=" + settings.recorderSsid()
                                + " vpn_active=" + networks.isVpnActive());
                beginProbe = true;
            }
        }
        runOnUiThread(() -> {
            updateConnectionUi(recorder, internet);
            maybeCheckPlayStoreUpdate();
        });
        if (beginProbe) startRecorderProbe(recorder);
    }

    @Override public void onRecorderConnectionUnavailable() {
        runOnUiThread(() -> {
            boolean wasRecorderReady = recorderReady;
            markRecorderUnavailable(settings.recorderSsid());
            recorderConnectionRequested = false;
            showDisconnectedRecorder = wasRecorderReady;
            cancelRecorderScan();
            resetRecorderProbe();
            IntegrityDiagnostics.bridgeEvent("NET", "RECORDER_NETWORK_UNAVAILABLE",
                    "ssid=" + settings.recorderSsid()
                            + " vpn_active=" + networks.isVpnActive());
            updateConnectionUi(networks.recorderNetwork(), networks.uploadNetwork());
            showMessageDialog(getString(R.string.recorder_not_found_title),
                    getString(R.string.recorder_not_found_selected_message));
        });
    }

    private void startRecorderProbe(Network network) {
        final int generation = recorderProbeGeneration.incrementAndGet();
        IntegrityDiagnostics.bridgeEvent("HTTP", "WAITING_FOR_RECORDER",
                "ssid=" + settings.recorderSsid()
                        + " vpn_active=" + networks.isVpnActive());
        recorderProbeExecutor.execute(() -> {
            boolean answered = false;
            final boolean vpnFastFail = networks.isVpnActive();
            final int maxAttempts = vpnFastFail ? 4 : 40;
            final int probeTimeoutMs = vpnFastFail ? 1_000 : 1_500;
            final long retryDelayMs = vpnFastFail ? 500L : 750L;
            IntegrityDiagnostics.bridgeEvent("HTTP", "RECORDER_PROBE_POLICY",
                    "vpn_fast_fail=" + vpnFastFail
                            + " attempts=" + maxAttempts
                            + " timeout_ms=" + probeTimeoutMs
                            + " retry_ms=" + retryDelayMs);
            for (int attempt = 0; attempt < maxAttempts
                    && generation == recorderProbeGeneration.get(); attempt++) {
                answered = probeRecorder(network, probeTimeoutMs);
                if (answered) break;
                if (attempt + 1 >= maxAttempts) break;
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            final boolean recorderAnswered = answered;
            runOnUiThread(() -> {
                if (generation != recorderProbeGeneration.get()
                        || !network.equals(networks.recorderNetwork())) return;
                if (recorderAnswered) {
                    recorderReady = true;
                    recorderConnectionRequested = false;
                    showDisconnectedRecorder = false;
                    IntegrityDiagnostics.bridgeEvent("HTTP", "RECORDER_READY",
                            "ssid=" + settings.recorderSsid()
                                    + " vpn_active=" + networks.isVpnActive());
                    startRecorderHealthMonitor(network);
                    updateConnectionUi(network, networks.uploadNetwork());
                    if (!recorderUpdateOnlyMode && transfers != null) transfers.onRecorderReady();
                    launchInterface();
                    if (recorderUpdateOnlyMode) {
                        showMessageDialog(getString(R.string.recorder_update_required_title),
                                getString(R.string.old_recorder_update_only_message));
                    }
                } else {
                    recorderReady = false;
                    recorderConnectionRequested = false;
                    showDisconnectedRecorder = false;
                    markRecorderUnavailable(settings.recorderSsid());
                    boolean vpnActive = networks.isVpnActive();
                    IntegrityDiagnostics.bridgeEvent("HTTP", "RECORDER_UNREACHABLE",
                            "ssid=" + settings.recorderSsid() + " vpn_active=" + vpnActive);
                    showMessageDialog(getString(R.string.recorder_not_responding_title),
                            getString(vpnActive
                                    ? R.string.recorder_not_responding_vpn_message
                                    : R.string.recorder_not_responding_message));
                            networks.disconnectRecorder();
                }
            });
        });
    }

    private boolean probeRecorder(Network network) {
        return probeRecorder(network, 1_500);
    }

    private boolean probeRecorder(Network network, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            URL statusUrl = new URL(settings.recorderBaseUrl() + "/api/status");
            connection = (HttpURLConnection) network.openConnection(statusUrl);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return false;

            // Current recorder firmware publishes USB power state in /api/status.
            // Keep recorder reachability independent from this optional metadata so
            // older recorder firmware remains compatible with update-only mode.
            try {
                JSONObject body = new JSONObject(readRecorderStatusBody(connection.getInputStream()));
                boolean usbValid = body.optBoolean("usb_present_valid", false);
                recorderUsbPresentValid = usbValid;
                recorderUsbPresent = usbValid && body.optBoolean("usb_present", false);
            } catch (Exception ignored) {
                recorderUsbPresentValid = false;
                recorderUsbPresent = false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readRecorderStatusBody(InputStream in) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (out.size() + count > 4096) {
                    throw new IllegalStateException("Recorder status response is too large");
                }
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void startRecorderHealthMonitor(Network network) {
        final int generation = recorderHealthGeneration.incrementAndGet();
        recorderHealthFailures = 0;
        scheduleRecorderHealthCheck(generation, network);
    }

    private void stopRecorderHealthMonitor() {
        recorderHealthGeneration.incrementAndGet();
        recorderHealthFailures = 0;
        recorderHealthSuspendedForBackground = false;
    }

    private void scheduleRecorderHealthCheck(int generation, Network network) {
        mainHandler.postDelayed(() -> {
            if (generation != recorderHealthGeneration.get()
                    || !recorderReady
                    || !network.equals(networks.recorderNetwork())) return;
            if (!activityForeground) {
                suspendRecorderHealthForBackground();
                return;
            }
            if (otaActivity != null && otaActivity.isProtected()) {
                scheduleRecorderHealthCheck(generation, network);
                return;
            }
            recorderProbeExecutor.execute(() -> {
                boolean answered = probeRecorder(network);
                runOnUiThread(() -> onRecorderHealthChecked(generation, network, answered));
            });
        }, RECORDER_HEALTH_INTERVAL_MS);
    }

    private void onRecorderHealthChecked(int generation, Network network, boolean answered) {
        if (generation != recorderHealthGeneration.get()
                || !recorderReady
                || !network.equals(networks.recorderNetwork())) return;
        if (!activityForeground) {
            suspendRecorderHealthForBackground();
            return;
        }
        if (answered) {
            recorderHealthFailures = 0;
            scheduleRecorderHealthCheck(generation, network);
            return;
        }
        if (otaActivity != null && otaActivity.isProtected()) {
            recorderHealthFailures = 0;
            IntegrityDiagnostics.bridgeEvent("HTTP", "HEALTH_FAILURE_IGNORED",
                    "ota_active=true action=keep_recorder_network");
            scheduleRecorderHealthCheck(generation, network);
            return;
        }
        recorderHealthFailures++;
        if (recorderHealthFailures < RECORDER_HEALTH_MAX_FAILURES) {
            scheduleRecorderHealthCheck(generation, network);
            return;
        }
        IntegrityDiagnostics.bridgeEvent("HTTP", "HEALTH_DISCONNECT",
                "failures=" + recorderHealthFailures + " action=disconnect_recorder");
        recorderReady = false;
        recorderConnectionRequested = false;
        showDisconnectedRecorder = true;
        markRecorderUnavailable(settings.recorderSsid());
        stopRecorderHealthMonitor();
        showMessageDialog(getString(R.string.recorder_disconnected_title),
                getString(R.string.recorder_disconnected_message));
        networks.disconnectRecorder();
        updateConnectionUi(null, networks.uploadNetwork());
    }

    private void suspendRecorderHealthForBackground() {
        recorderHealthFailures = 0;
        if (recorderHealthSuspendedForBackground) return;
        recorderHealthSuspendedForBackground = true;
        recorderHealthGeneration.incrementAndGet();
        IntegrityDiagnostics.bridgeEvent("HTTP", "HEALTH_MONITOR_SUSPENDED",
                "reason=APP_BACKGROUND");
    }


    private void resetRecorderProbe() {
        recorderProbeGeneration.incrementAndGet();
        stopRecorderHealthMonitor();
        recorderProbeNetwork = null;
        recorderReady = false;
        recorderUsbPresentValid = false;
        recorderUsbPresent = false;
    }

    private void resetRecorderCompatibilityMode() {
        recorderUpdateOnlyMode = false;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        if (hasRecorderConnectionPermission()) {
            beginRecorderScan();
        } else {
            showDisconnectedRecorder = false;
            setRecorderStatus(null, false, false);
            showMessageDialog(getString(R.string.wifi_permission_required_title),
                    getString(R.string.wifi_connect_permission_message));
        }
    }

    private void maybeCheckPlayStoreUpdate() {
        if (playUpdateManager == null || playUpdateCheckComplete || playUpdateCheckInFlight) return;
        if (networks == null || networks.recorderNetwork() != null) return;

        Network internet = networks.uploadNetwork();
        if (internet == null || !networks.serverReachable(internet)) return;

        playUpdateCheckInFlight = true;
        IntegrityDiagnostics.bridgeEvent("PLAY", "UPDATE_CHECK_START",
                "installed_version=" + installedVersionName()
                        + " installed_code=" + installedVersionCode());
        playUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(info -> runOnUiThread(() -> onPlayUpdateInfo(info)))
                .addOnFailureListener(error -> runOnUiThread(() -> {
                    playUpdateCheckInFlight = false;
                    String detail = error == null ? "unknown"
                            : error.getClass().getSimpleName() + ":"
                            + String.valueOf(error.getMessage()).replace('\n', ' ').replace('\r', ' ');
                    IntegrityDiagnostics.bridgeEvent("PLAY", "UPDATE_CHECK_FAILED",
                            "error=" + detail);
                }));
    }

    private void onPlayUpdateInfo(AppUpdateInfo info) {
        playUpdateCheckInFlight = false;
        playUpdateCheckComplete = true;
        if (info == null) return;

        int availability = info.updateAvailability();
        int availableCode = availability == UpdateAvailability.UPDATE_AVAILABLE
                ? info.availableVersionCode() : -1;
        IntegrityDiagnostics.bridgeEvent("PLAY", "UPDATE_CHECK_RESULT",
                "availability=" + availability
                        + " available_code=" + availableCode);

        if (availability != UpdateAvailability.UPDATE_AVAILABLE
                || availableCode <= installedVersionCode()) {
            return;
        }
        showPlayStoreUpdateDialog();
    }

    private void showPlayStoreUpdateDialog() {
        if (playUpdateDialogShowing || isFinishing() || isDestroyed()) return;
        playUpdateDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.play_update_available_title))
                .setMessage(getString(R.string.play_update_available_message))
                .setPositiveButton(getString(R.string.button_update),
                        (dialog, which) -> openPlayStorePage())
                .setNegativeButton(getString(R.string.button_later), null)
                .setOnDismissListener(dialog -> playUpdateDialogShowing = false)
                .show();
    }

    private void openPlayStorePage() {
        String packageName = getPackageName();
        Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + packageName));
        marketIntent.setPackage("com.android.vending");
        try {
            startActivity(marketIntent);
            return;
        } catch (ActivityNotFoundException ignored) {
            // Fall back to the Web Play Store when the Play Store app is unavailable.
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://play.google.com/store/apps/details?id=" + packageName)));
        } catch (ActivityNotFoundException error) {
            IntegrityDiagnostics.bridgeEvent("PLAY", "UPDATE_OPEN_FAILED",
                    "error=" + error.getClass().getSimpleName());
        }
    }

    private void updateConnectionUi(Network recorder, Network internet) {
        latestUploadNetwork = internet;
        updateServerUploadUi();

        boolean recorderConnected = recorder != null;
        if (recorderScanPending) {
            setRecorderStatus("Searching", true, false);
        } else if (recorderConnected && recorderReady) {
            setRecorderStatus(settings.recorderSsid(), false, true);
        } else if (recorderConnected) {
            setRecorderWaitingStatus(settings.recorderSsid());
        } else if (recorderConnectionRequested) {
            setRecorderStatus(settings.recorderSsid(), true, false);
        } else if (showDisconnectedRecorder) {
            setRecorderStatus(settings.recorderSsid(), false, false);
        } else {
            setRecorderStatus(null, false, false);
        }

        setButtonAvailable(connectButton, true);
        connectButton.setText(recorderConnected || recorderConnectionRequested || recorderScanPending
                ? R.string.button_stop : R.string.button_connect);
        if (!recorderConnected && webView.getUrl() != null && !"about:blank".equals(webView.getUrl())) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
        }
    }

    private void updateServerQueueUi(TransferManager.QueueStatus status) {
        if (status == null) return;
        latestQueueStatus = status;
        updateServerUploadUi();
    }

    private void updateServerUploadUi() {
        boolean serverConnected = latestUploadNetwork != null
                && networks.serverReachable(latestUploadNetwork);
        serverStatus.setText(serverConnected ? R.string.server_connected : R.string.server_offline);
        serverStatus.setTextColor(serverConnected ? COLOR_GREEN : COLOR_AMBER);

        TransferManager.QueueStatus status = latestQueueStatus;
        if (status == null || status.isEmpty()) {
            fileQueueStatus.setText(R.string.file_queue_empty);
            fileQueueStatus.setTextColor(Color.BLACK);
            fileTransferProgress.setVisibility(View.GONE);
            fileTransferProgress.setProgress(0);
            return;
        }

        int total = Math.max(1, status.totalFiles);
        int current = Math.max(0, Math.min(status.currentFile, total));
        int percent = Math.max(0, Math.min(100, status.percent));
        boolean interruptedUpload = status.state == TransferManager.QueueStatus.WAITING
                && current > 0;
        if (status.state == TransferManager.QueueStatus.UPLOADING || interruptedUpload) {
            fileQueueStatus.setText(getString(R.string.file_queue_uploading, percent, current, total));
            fileTransferProgress.setVisibility(View.VISIBLE);
            fileTransferProgress.setProgress(percent);
        } else {
            fileQueueStatus.setText(getString(R.string.file_queue_complete, total));
            fileTransferProgress.setVisibility(View.GONE);
            fileTransferProgress.setProgress(0);
        }
        fileQueueStatus.setTextColor(Color.BLACK);
    }

    private void setRecorderWaitingStatus(String recorderSsid) {
        String registration = GliderRegistration.fromSsid(recorderSsid == null ? "" : recorderSsid);
        String display = GliderRegistration.displayRegistration(registration);
        recorderStatusText = display.isEmpty()
                ? getString(R.string.status_waiting_for_recorder)
                : display + "\n" + getString(R.string.status_waiting_for_recorder);
        recorderStatusColor = COLOR_AMBER;
        setRecorderStatusBlinking(true);
    }

    private void setRecorderStatus(String recorderSsid, boolean connecting, boolean connected) {
        String text;
        int color;
        boolean blink;
        if ("Searching".equals(recorderSsid)) {
            text = getString(R.string.status_searching_recorder);
            color = COLOR_AMBER;
            blink = true;
        } else {
            String registration = GliderRegistration.fromSsid(recorderSsid == null ? "" : recorderSsid);
            String display = GliderRegistration.displayRegistration(registration);
            if (registration.isEmpty()) {
                text = connecting ? getString(R.string.status_connecting_recorder)
                        : getString(R.string.status_no_recorder);
            } else if (connected) {
                text = display + "\n" + getString(R.string.status_connected);
            } else if (connecting) {
                text = getString(R.string.status_connecting) + "\n" + display;
            } else {
                text = display + "\n" + getString(R.string.status_disconnected);
            }
            color = connected ? COLOR_GREEN : COLOR_AMBER;
            blink = connecting;
        }
        recorderStatusText = text;
        recorderStatusColor = color;
        setRecorderStatusBlinking(blink);
    }

    private void setRecorderStatusBlinking(boolean blink) {
        if (blink != recorderStatusBlinking) {
            recorderStatusBlinking = blink;
            recorderStatusTextVisible = true;
            mainHandler.removeCallbacks(titleBlinkRunnable);
            if (blink) mainHandler.postDelayed(titleBlinkRunnable, TITLE_BLINK_INTERVAL_MS);
        } else if (!blink) {
            recorderStatusTextVisible = true;
        }
        renderRecorderStatus();
    }

    private void renderRecorderStatus() {
        recorderStatus.setText(recorderStatusText);
        int color = (!recorderStatusBlinking || recorderStatusTextVisible)
                ? recorderStatusColor : COLOR_STATUS_BLINK_DIM;
        recorderStatus.setTextColor(color);
    }

    private static void setButtonAvailable(Button button, boolean available) {
        setButtonAvailable(button, available, COLOR_BLUE);
    }

    private static void setButtonAvailable(Button button, boolean available, int availableColor) {
        button.setEnabled(available);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(available ? availableColor : COLOR_GREY));
    }

    private void launchInterface() {
        if (networks.recorderNetwork() == null || !recorderReady) {
            showMessageDialog(getString(R.string.recorder_not_ready_title),
                    getString(R.string.recorder_not_ready_message));
            return;
        }
        if (recorderUsbPresentValid && !recorderUsbPresent) {
            showUsbPowerWarningBeforeInterface();
            return;
        }
        webView.loadUrl(settings.recorderBaseUrl());
    }

    private void showUsbPowerWarningBeforeInterface() {
        if (isFinishing() || isDestroyed()) return;
        IntegrityDiagnostics.bridgeEvent("POWER", "RECORDER_USB_WARNING_SHOWN",
                "usb_present=false action=await_acknowledgement");
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.recorder_usb_power_required_title))
                .setMessage(getString(R.string.recorder_usb_power_required_message))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.button_ok), (dialog, which) -> {
                    IntegrityDiagnostics.bridgeEvent("POWER", "RECORDER_USB_WARNING_ACKNOWLEDGED",
                            "action=open_recorder_interface");
                    webView.loadUrl(settings.recorderBaseUrl());
                })
                .show();
    }

    private boolean isSystemLocationEnabled() {
        LocationManager location = getSystemService(LocationManager.class);
        return location != null && location.isLocationEnabled();
    }

    private void showLocationRequiredDialog() {
        if (isFinishing() || isDestroyed()) return;
        resumeRecorderConnectAfterLocationSettings = false;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.location_required_title))
                .setMessage(getString(R.string.location_required_message))
                .setNegativeButton(getString(R.string.button_cancel), null)
                .setPositiveButton(getString(R.string.button_location_settings), (dialog, which) -> {
                    resumeRecorderConnectAfterLocationSettings = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    } catch (ActivityNotFoundException error) {
                        resumeRecorderConnectAfterLocationSettings = false;
                        showMessageDialog(getString(R.string.location_settings_unavailable_title),
                                getString(R.string.location_settings_unavailable_message));
                    }
                })
                .show();
    }

    private void showScanTemporarilyUnavailableDialog() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.wifi_scan_unavailable_title))
                .setMessage(getString(R.string.wifi_scan_unavailable_message))
                .setNegativeButton(getString(R.string.button_cancel), null)
                .setPositiveButton(getString(R.string.button_retry), (dialog, which) -> requestPermissionAndConnect())
                .show();
    }

    private boolean hasRecorderConnectionPermission() {
        boolean fineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 33) {
            return fineLocation
                    && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return fineLocation;
    }

    private static final class DiscoveredRecorder {
        final String ssid;
        final int level;

        DiscoveredRecorder(String ssid, int level) {
            this.ssid = ssid;
            this.level = level;
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        cancelRecorderScan();
        resetRecorderProbe();
        mainHandler.removeCallbacksAndMessages(null);
        recorderProbeExecutor.shutdownNow();
        TransferManager manager = transfers;
        FirmwareManager firmware = firmwareManager;
        transfers = null;
        firmwareManager = null;
        if (manager != null) manager.close();
        if (firmware != null) firmware.close();
        networks.stop();
        fileExporter.close();
        if (pendingFileChooser != null) {
            pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = null;
        }
        if (recorderWebMessageBridgeConfigured
                && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, RECORDER_NATIVE_MESSAGE_OBJECT);
        }
        webView.destroy();
        super.onDestroy();
    }
}
