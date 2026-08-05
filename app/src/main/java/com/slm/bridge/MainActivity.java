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
import android.net.Network;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity implements NetworkCoordinator.Listener {
    private static final int PERMISSION_REQUEST = 41;
    private static final int CREATE_DOCUMENT_REQUEST = 42;
    private static final int OPEN_DOCUMENT_REQUEST = 43;
    private static final String LOG_TAG = "SLM-Web";
    private static final int COLOR_BLUE = Color.rgb(34, 85, 170);
    private static final int COLOR_GREY = Color.rgb(145, 145, 145);
    private static final int COLOR_GREEN = Color.rgb(142, 214, 82);
    private static final int COLOR_AMBER = Color.rgb(255, 196, 0);
    private static final int COLOR_STATUS_BLINK_DIM = Color.rgb(80, 80, 80);
    private static final long RECORDER_SCAN_SETTLE_MS = 2_000L;
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
    private boolean debugBuild;
    private long recorderScanStartedMs;
    private int recorderHealthFailures;
    private volatile Network recorderProbeNetwork;
    private volatile boolean recorderReady;
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
    private String recorderStatusText = "No\nRecorder";
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
        networks = new NetworkCoordinator(this, this);
        fileExporter = new RecorderFileExporter(this, networks, settings, this::onExportFinished);
        TransferStore store = new TransferStore(this);
        DriveCredentialStore driveCredentialStore = new DriveCredentialStore(this);
        transfers = new TransferManager(networks, settings, store,
                driveCredentialStore, webView,
                status -> runOnUiThread(() -> updateServerQueueUi(status)));
        firmwareManager = new FirmwareManager(networks, settings, driveCredentialStore, webView);
        configureWebView(store, transfers, firmwareManager);
        connectButton.setOnClickListener(v -> {
            resetServiceDiagnosticsTapSequence();
            toggleConnection();
        });
        bridgeTitle.setOnClickListener(v -> onBridgeTitleTapped());
        updateConnectionUi(networks.recorderNetwork(), networks.uploadNetwork());
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
        webView.setWebViewClient(new BridgeWebViewClient(store, settings, debugBuild));
        webView.setWebChromeClient(createRecorderWebChromeClient());
        webView.setDownloadListener(this::onDownloadRequested);
        webView.addJavascriptInterface(new RecorderJavascriptBridge(transfers, networks, firmwareManager), "SLMAndroid");
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
                    showMessageDialog("File selection unavailable", "No file picker is available on this phone.");
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
            Toast.makeText(this, "Finish the current file selection first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (networks.recorderNetwork() == null) {
            showMessageDialog("Recorder not connected", "Connect to the recorder before downloading a file.");
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
            showMessageDialog("Download blocked", "The requested download did not come from the connected recorder.");
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
            showMessageDialog("File save unavailable", "No document application is available to save this file.");
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
        Toast.makeText(this, "Saving " + request.filename + "...", Toast.LENGTH_SHORT).show();
        fileExporter.export(request, data.getData());
    }

    private void onExportFinished(RecorderFileExporter.Request request, String error) {
        boolean calibrationReport = error == null
                && RecorderUrlPolicy.isCalibrationReportDownload(request.url);
        TransferManager manager = transfers;
        if (calibrationReport && manager != null) manager.enqueueReport(request);
        if (error == null) {
            String message = request.filename + (calibrationReport
                    ? " saved and queued for SLM server" : " saved");
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } else {
            showMessageDialog("Download failed", error);
        }
    }

    private void showMessageDialog(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
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
        cancelRecorderScan();
        resetRecorderProbe();
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
                showMessageDialog("Wi-Fi unavailable", "Wi-Fi management is not available on this phone.");
                return;
            }
            registerRecorderScanReceiver(generation);
            boolean requested = false;
            try { requested = wifi.startScan(); } catch (RuntimeException ignored) {}
            long fallbackDelay = requested ? RECORDER_SCAN_FRESH_TIMEOUT_MS : RECORDER_SCAN_SETTLE_MS;
            mainHandler.postDelayed(() -> finishRecorderScan(generation, false), fallbackDelay);
        } catch (SecurityException security) {
            recorderScanPending = false;
            unregisterRecorderScanReceiver();
            updateConnectionUi(null, networks.uploadNetwork());
            showMessageDialog("Wi-Fi permission required",
                    "Allow Wi-Fi scanning permission so SLM Bridge can find recorders.");
        }
    }

    private void registerRecorderScanReceiver(int generation) {
        unregisterRecorderScanReceiver();
        recorderScanReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (generation != recorderScanGeneration.get() || !recorderScanPending) return;
                boolean updated = intent != null
                        && intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                finishRecorderScan(generation, updated);
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
            showMessageDialog("Recorder not found",
                    freshResultsAvailable
                            ? "No SLM recorder Wi-Fi was found. Check that recorder Wi-Fi is on, then try Connect again."
                            : "No fresh SLM recorder Wi-Fi scan was available. Check that recorder Wi-Fi is on, then try Connect again.");
            return;
        }
        ArrayList<DiscoveredRecorder> supportedRecorders = new ArrayList<>();
        for (DiscoveredRecorder recorder : recorders) {
            if (GliderRegistration.isSupportedRecorderSsid(recorder.ssid)) {
                supportedRecorders.add(recorder);
            }
        }
        if (supportedRecorders.isEmpty()) {
            updateConnectionUi(null, networks.uploadNetwork());
            showWifiGenerationConflict(recorders);
            return;
        }
        if (supportedRecorders.size() == 1) {
            connectToRecorder(supportedRecorders.get(0).ssid);
            return;
        }
        updateConnectionUi(null, networks.uploadNetwork());
        showRecorderSelection(supportedRecorders);
    }


    private void showWifiGenerationConflict(List<DiscoveredRecorder> recorders) {
        ArrayList<DiscoveredRecorder> incompatible = new ArrayList<>();
        for (DiscoveredRecorder recorder : recorders) {
            int generation = GliderRegistration.wifiGenerationFromSsid(recorder.ssid);
            if (generation > 0 && generation != GliderRegistration.SUPPORTED_WIFI_GENERATION) {
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

        String message = "Incompatible SLM Wi-Fi generations were found.";
        if (registrations.length() > 0) {
            message += "\n\nRecorders:\n" + registrations;
        }
        message += "\n\nUpdate the recorder firmware and SLM Bridge before connecting.";

        showMessageDialog("Wi-Fi version conflict", message);
    }

    private void showWifiGenerationConflictFor(int generation) {
        showWifiGenerationConflictFor(generation, null);
    }

    private void showWifiGenerationConflictFor(int generation, String ssid) {
        String registration = recorderDisplayRegistration(ssid);
        String recorderName = registration.isEmpty() ? "This recorder" : "Recorder " + registration;
        final String title;
        final String message;
        if (generation > GliderRegistration.SUPPORTED_WIFI_GENERATION) {
            title = "SLM Bridge update required";
            message = recorderName
                    + " uses a newer Wi-Fi connection generation.\n\nUpdate SLM Bridge before connecting.";
        } else if (generation > 0) {
            title = "Recorder update required";
            message = recorderName
                    + " uses an older Wi-Fi connection generation.\n\nUpdate the recorder firmware before connecting.";
        } else {
            title = "Invalid recorder Wi-Fi";
            message = "The detected SLM recorder Wi-Fi name is invalid.";
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
        if (generation != GliderRegistration.SUPPORTED_WIFI_GENERATION) {
            showWifiGenerationConflictFor(generation);
            return;
        }
        String password = GliderRegistration.wifiPasswordFromSsid(ssid);
        if (password.isEmpty()) {
            showMessageDialog("Invalid recorder Wi-Fi",
                    "The detected recorder Wi-Fi name is invalid: " + ssid);
            return;
        }
        cancelRecorderScan();
        settings.selectRecorder(ssid);
        resetRecorderProbe();
        showDisconnectedRecorder = false;
        recorderConnectionRequested = true;
        updateConnectionUi(null, networks.uploadNetwork());
        networks.connect(ssid, password);
        Toast.makeText(this, "Connecting to " + ssid, Toast.LENGTH_SHORT).show();
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
            showMessageDialog("Wi-Fi permission required",
                    "Allow Wi-Fi scanning permission so SLM Bridge can find recorders.");
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
                .setTitle("Select SLM recorder")
                .setItems(items, (dialog, which) -> connectToRecorder(recorders.get(which).ssid))
                .setNegativeButton("Cancel", (dialog, which) ->
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
                if (recorderReady) showDisconnectedRecorder = true;
                if (recorderProbeNetwork != null || recorderReady) {
                    recorderProbeGeneration.incrementAndGet();
                    stopRecorderHealthMonitor();
                }
                recorderProbeNetwork = null;
                recorderReady = false;
            } else if (!recorder.equals(recorderProbeNetwork)) {
                recorderProbeNetwork = recorder;
                recorderReady = false;
                stopRecorderHealthMonitor();
                beginProbe = true;
            }
        }
        runOnUiThread(() -> updateConnectionUi(recorder, internet));
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
            updateConnectionUi(networks.recorderNetwork(), networks.uploadNetwork());
            showMessageDialog("Recorder not found",
                    "The selected SLM recorder was not found. Check that its Wi-Fi is on, then try Connect again.");
        });
    }

    private void startRecorderProbe(Network network) {
        final int generation = recorderProbeGeneration.incrementAndGet();
        recorderProbeExecutor.execute(() -> {
            boolean answered = false;
            for (int attempt = 0; attempt < 40 && generation == recorderProbeGeneration.get(); attempt++) {
                answered = probeRecorder(network);
                if (answered) break;
                try {
                    Thread.sleep(750);
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
                    startRecorderHealthMonitor(network);
                    updateConnectionUi(network, networks.uploadNetwork());
                    launchInterface();
                } else {
                    recorderReady = false;
                    recorderConnectionRequested = false;
                    showDisconnectedRecorder = false;
                    markRecorderUnavailable(settings.recorderSsid());
                    showMessageDialog("Recorder not responding",
                            "Recorder Wi-Fi connected, but the recorder did not answer. Check the recorder and try Connect again.");
                    networks.disconnectRecorder();
                }
            });
        });
    }

    private boolean probeRecorder(Network network) {
        HttpURLConnection connection = null;
        try {
            URL statusUrl = new URL(settings.recorderBaseUrl() + "/api/status");
            connection = (HttpURLConnection) network.openConnection(statusUrl);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
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
    }

    private void scheduleRecorderHealthCheck(int generation, Network network) {
        mainHandler.postDelayed(() -> {
            if (generation != recorderHealthGeneration.get()
                    || !recorderReady
                    || !network.equals(networks.recorderNetwork())) return;
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
        if (answered) {
            recorderHealthFailures = 0;
            scheduleRecorderHealthCheck(generation, network);
            return;
        }
        recorderHealthFailures++;
        if (recorderHealthFailures < RECORDER_HEALTH_MAX_FAILURES) {
            scheduleRecorderHealthCheck(generation, network);
            return;
        }
        recorderReady = false;
        recorderConnectionRequested = false;
        showDisconnectedRecorder = true;
        markRecorderUnavailable(settings.recorderSsid());
        stopRecorderHealthMonitor();
        showMessageDialog("Recorder disconnected",
                "Recorder connection lost. Check that recorder Wi-Fi is on, then press Connect.");
        networks.disconnectRecorder();
        updateConnectionUi(null, networks.uploadNetwork());
    }

    private void resetRecorderProbe() {
        recorderProbeGeneration.incrementAndGet();
        stopRecorderHealthMonitor();
        recorderProbeNetwork = null;
        recorderReady = false;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        if (hasRecorderConnectionPermission()) {
            beginRecorderScan();
        } else {
            showDisconnectedRecorder = false;
            setRecorderStatus(null, false, false);
            showMessageDialog("Wi-Fi permission required",
                    "Allow Wi-Fi permission so SLM Bridge can connect to the recorder.");
        }
    }

    private void updateConnectionUi(Network recorder, Network internet) {
        latestUploadNetwork = internet;
        updateServerUploadUi();

        boolean recorderConnected = recorder != null;
        if (recorderScanPending) {
            setRecorderStatus("Searching", true, false);
        } else if (recorderConnectionRequested) {
            setRecorderStatus(settings.recorderSsid(), true, false);
        } else if (recorderConnected && recorderReady) {
            setRecorderStatus(settings.recorderSsid(), false, true);
        } else if (recorderConnected) {
            setRecorderStatus(settings.recorderSsid(), true, false);
        } else if (showDisconnectedRecorder) {
            setRecorderStatus(settings.recorderSsid(), false, false);
        } else {
            setRecorderStatus(null, false, false);
        }

        setButtonAvailable(connectButton, true);
        connectButton.setText(recorderConnected || recorderConnectionRequested || recorderScanPending
                ? "STOP" : "CONNECT");
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
        boolean serverConnected = latestUploadNetwork != null;
        serverStatus.setText(serverConnected ? "Server\nConnected" : "Server\nOff-line");
        serverStatus.setTextColor(serverConnected ? COLOR_GREEN : COLOR_AMBER);

        TransferManager.QueueStatus status = latestQueueStatus;
        if (status == null || status.isEmpty()) {
            fileQueueStatus.setText("File upload None        File Queue Empty");
            fileQueueStatus.setTextColor(Color.BLACK);
            fileTransferProgress.setVisibility(View.GONE);
            fileTransferProgress.setProgress(0);
            return;
        }

        int total = Math.max(1, status.totalFiles);
        int current = Math.max(0, Math.min(status.currentFile, total));
        int percent = Math.max(0, Math.min(100, status.percent));
        if (status.state == TransferManager.QueueStatus.UPLOADING) {
            fileQueueStatus.setText("File upload " + percent + "%        File Queue "
                    + current + "/" + total);
            fileTransferProgress.setVisibility(View.VISIBLE);
            fileTransferProgress.setProgress(percent);
        } else {
            fileQueueStatus.setText("File upload None        File Queue " + total + "/" + total);
            fileTransferProgress.setVisibility(View.GONE);
            fileTransferProgress.setProgress(0);
        }
        fileQueueStatus.setTextColor(Color.BLACK);
    }

    private void setRecorderStatus(String recorderSsid, boolean connecting, boolean connected) {
        String text;
        int color;
        boolean blink;
        if ("Searching".equals(recorderSsid)) {
            text = "Searching\nRecorder";
            color = COLOR_AMBER;
            blink = true;
        } else {
            String registration = GliderRegistration.fromSsid(recorderSsid == null ? "" : recorderSsid);
            String display = GliderRegistration.displayRegistration(registration);
            if (registration.isEmpty()) {
                text = connecting ? "Connecting\nRecorder" : "No\nRecorder";
            } else if (connected) {
                text = display + "\nConnected";
            } else if (connecting) {
                text = "Connecting\n" + display;
            } else {
                text = display + "\nDisconnected";
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
            Toast.makeText(this, "Wait until the recorder is ready", Toast.LENGTH_SHORT).show();
            return;
        }
        webView.loadUrl(settings.recorderBaseUrl());
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
        webView.removeJavascriptInterface("SLMAndroid");
        webView.destroy();
        super.onDestroy();
    }
}
