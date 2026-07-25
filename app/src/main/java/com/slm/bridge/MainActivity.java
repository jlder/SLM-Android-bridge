package com.slm.bridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import android.text.InputType;
import android.util.Log;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity implements NetworkCoordinator.Listener {
    private static final int PERMISSION_REQUEST = 41;
    private static final int CREATE_DOCUMENT_REQUEST = 42;
    private static final int OPEN_DOCUMENT_REQUEST = 43;
    private static final String LOG_TAG = "SLM-Web";
    private AppSettings settings;
    private NetworkCoordinator networks;
    private TransferManager transfers;
    private RecorderFileExporter fileExporter;
    private RecorderFileExporter.Request pendingDownload;
    private ValueCallback<Uri[]> pendingFileChooser;
    private WebView webView;
    private TextView wifiStatus;
    private TextView cellStatus;
    private TextView serverQueueStatus;
    private Button settingsButton;
    private Button connectButton;
    private Button launchButton;
    private boolean recorderConnectionRequested;
    private boolean debugBuild;
    private volatile Network recorderProbeNetwork;
    private volatile boolean recorderReady;
    private final AtomicInteger recorderProbeGeneration = new AtomicInteger();
    private final ExecutorService recorderProbeExecutor = Executors.newSingleThreadExecutor();

    private static final int COLOR_BLUE = Color.rgb(34, 85, 170);
    private static final int COLOR_GREEN = Color.rgb(24, 128, 56);
    private static final int COLOR_AMBER = Color.rgb(178, 106, 0);
    private static final int COLOR_GREY = Color.rgb(145, 145, 145);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(android.R.id.content));
        settings = new AppSettings(this);
        debugBuild = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        webView = findViewById(R.id.webView);
        wifiStatus = findViewById(R.id.wifiStatus);
        cellStatus = findViewById(R.id.cellStatus);
        serverQueueStatus = findViewById(R.id.serverQueueStatus);
        settingsButton = findViewById(R.id.settingsButton);
        connectButton = findViewById(R.id.connectButton);
        launchButton = findViewById(R.id.launchButton);
        networks = new NetworkCoordinator(this, this);
        fileExporter = new RecorderFileExporter(this, networks, settings, this::onExportFinished);
        TransferStore store = new TransferStore(this);
        transfers = new TransferManager(networks, settings, store,
                new DriveCredentialStore(this), webView,
                status -> runOnUiThread(() -> updateServerQueueUi(status)));
        configureWebView(store, transfers);
        settingsButton.setOnClickListener(v -> showSettings());
        connectButton.setOnClickListener(v -> toggleConnection());
        launchButton.setOnClickListener(v -> launchInterface());
        updateConnectionUi(networks.recorderNetwork(), networks.uploadNetwork());
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
                insetLeft = systemBars.left;
                insetTop = systemBars.top;
                insetRight = systemBars.right;
                insetBottom = systemBars.bottom;
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

    private void configureWebView(TransferStore store, TransferManager transfers) {
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
        webView.addJavascriptInterface(new RecorderJavascriptBridge(transfers, networks), "SLMAndroid");
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
                    Toast.makeText(MainActivity.this, "No file picker is available", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Connect to the recorder before downloading", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Blocked a download outside the recorder", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "No document application is available", Toast.LENGTH_LONG).show();
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
        String message = error == null
                ? request.filename + (calibrationReport
                        ? " saved and queued for SLM server" : " saved")
                : "Download failed: " + error;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
            if (Build.VERSION.SDK_INT >= 33
                    && settings.validRecorderProfiles().size() > 1
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                    && !settings.scanPermissionPrompted()) {
                settings.markScanPermissionPrompted();
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        PERMISSION_REQUEST);
            } else {
                connect();
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            settings.markScanPermissionPrompted();
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
        if (networks.recorderNetwork() != null || recorderConnectionRequested) {
            recorderConnectionRequested = false;
            resetRecorderProbe();
            networks.disconnectRecorder();
            return;
        }
        requestPermissionAndConnect();
    }

    private void connect() {
        if (!hasValidRecorderSettings()) {
            Toast.makeText(this, "Configure a valid recorder Wi-Fi and URL first", Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        List<AppSettings.RecorderProfile> candidates = rankedRecorderProfiles();
        if (candidates.isEmpty()) {
            recorderConnectionRequested = false;
            updateConnectionUi(null, networks.uploadNetwork());
            Toast.makeText(this, "No SLM recorder is configured",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Request only the strongest configured recorder. Automatically cycling
        // profiles can repeatedly reopen Android's Wi-Fi confirmation screen.
        AppSettings.RecorderProfile profile = candidates.get(0);
        settings.selectRecorder(profile.ssid);
        resetRecorderProbe();
        recorderConnectionRequested = true;
        networks.connect(profile.ssid, profile.password);
        updateConnectionUi(null, networks.uploadNetwork());
        Toast.makeText(this, "Looking for " + profile.ssid, Toast.LENGTH_SHORT).show();
    }

    @Override public void onNetworksChanged(Network recorder, Network internet) {
        if (transfers != null) transfers.onNetworksChanged();
        boolean beginProbe = false;
        synchronized (this) {
            if (recorder == null) {
                if (recorderProbeNetwork != null || recorderReady) {
                    recorderProbeGeneration.incrementAndGet();
                }
                recorderProbeNetwork = null;
                recorderReady = false;
            } else if (!recorder.equals(recorderProbeNetwork)) {
                recorderProbeNetwork = recorder;
                recorderReady = false;
                beginProbe = true;
            }
        }
        runOnUiThread(() -> updateConnectionUi(recorder, internet));
        if (beginProbe) startRecorderProbe(recorder);
    }

    @Override public void onRecorderConnectionUnavailable() {
        runOnUiThread(() -> {
            recorderConnectionRequested = false;
            resetRecorderProbe();
            updateConnectionUi(networks.recorderNetwork(), networks.uploadNetwork());
            Toast.makeText(this, "The selected SLM recorder was not found. Check that its Wi-Fi is on, then try Connect again.",
                    Toast.LENGTH_LONG).show();
        });
    }

    private void startRecorderProbe(Network network) {
        final int generation = recorderProbeGeneration.incrementAndGet();
        recorderProbeExecutor.execute(() -> {
            boolean answered = false;
            for (int attempt = 0; attempt < 40 && generation == recorderProbeGeneration.get(); attempt++) {
                HttpURLConnection connection = null;
                try {
                    URL statusUrl = new URL(settings.recorderBaseUrl() + "/api/status");
                    connection = (HttpURLConnection) network.openConnection(statusUrl);
                    connection.setConnectTimeout(1500);
                    connection.setReadTimeout(1500);
                    connection.setUseCaches(false);
                    connection.setRequestMethod("GET");
                    int status = connection.getResponseCode();
                    answered = status >= 200 && status < 300;
                } catch (Exception ignored) {
                    answered = false;
                } finally {
                    if (connection != null) connection.disconnect();
                }
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
                    updateConnectionUi(network, networks.uploadNetwork());
                    launchInterface();
                } else {
                    recorderReady = false;
                    recorderConnectionRequested = false;
                    Toast.makeText(this,
                            "Recorder Wi-Fi connected, but the recorder did not answer. Check the recorder and try Connect again.",
                            Toast.LENGTH_LONG).show();
                    networks.disconnectRecorder();
                }
            });
        });
    }

    private void resetRecorderProbe() {
        recorderProbeGeneration.incrementAndGet();
        recorderProbeNetwork = null;
        recorderReady = false;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        if (hasRecorderConnectionPermission()) {
            connect();
        } else {
            setStatus(wifiStatus, "RECORDER PERMISSION", COLOR_AMBER);
            Toast.makeText(this, "Wi-Fi permission is required to connect to the recorder", Toast.LENGTH_LONG).show();
        }
    }

    private void updateConnectionUi(Network recorder, Network internet) {
        boolean recorderConnected = recorder != null;
        boolean ready = recorderConnected && recorderReady;
        setStatus(wifiStatus,
                ready ? "RECORDER READY " + settings.gliderRegistration()
                        : recorderConnected || recorderConnectionRequested
                        ? "RECORDER CONNECTING " + settings.recorderSsid()
                        : "RECORDER OFF",
                ready ? COLOR_GREEN : COLOR_AMBER);
        setStatus(cellStatus,
                internet == null ? "SLM SERVER WAITING" : "SLM SERVER READY",
                internet == null ? COLOR_AMBER : COLOR_GREEN);

        boolean configured = hasValidRecorderSettings();
        boolean settingsAvailable = !recorderConnected && !recorderConnectionRequested;
        setButtonAvailable(settingsButton, settingsAvailable,
                configured ? COLOR_BLUE : COLOR_AMBER);
        setButtonAvailable(connectButton, recorderConnected || recorderConnectionRequested || configured);
        connectButton.setText(recorderConnected
                ? "STOP" : recorderConnectionRequested ? "CANCEL" : "CONNECT");
        setButtonAvailable(launchButton, ready);
        if (!recorderConnected && webView.getUrl() != null && !"about:blank".equals(webView.getUrl())) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
        }
    }

    private void updateServerQueueUi(TransferManager.QueueStatus status) {
        if (status == null) return;
        serverQueueStatus.setText(status.text);
        int color = status.state == TransferManager.QueueStatus.UPLOADING
                ? COLOR_BLUE : status.state == TransferManager.QueueStatus.WAITING
                ? COLOR_AMBER : COLOR_BLUE;
        serverQueueStatus.setTextColor(color);
    }

    private static void setStatus(TextView view, String text, int color) {
        view.setText(text);
        view.setTextColor(color);
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

    private void showSettings() {
        if (networks.recorderNetwork() != null || recorderConnectionRequested) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, 0, pad, 0);
        EditText ssid = field(form, "Recorder Wi-Fi SSID", settings.recorderSsid(), false);
        List<AppSettings.RecorderProfile> profiles = settings.recorderProfiles();
        AppSettings.RecorderProfile first = profiles.isEmpty()
                ? new AppSettings.RecorderProfile("", "") : profiles.get(0);
        AppSettings.RecorderProfile second = profiles.size() < 2
                ? new AppSettings.RecorderProfile("", "") : profiles.get(1);
        ssid.setHint("Recorder 1 Wi-Fi SSID");
        ssid.setText(first.ssid);
        EditText password = field(form, "Recorder 1 Wi-Fi password", first.password, true);
        EditText ssid2 = field(form, "Recorder 2 Wi-Fi SSID (optional)", second.ssid, false);
        EditText password2 = field(form, "Recorder 2 Wi-Fi password", second.password, true);
        TextView registration = new TextView(this);
        registration.setText("Registrations are derived from the configured SLM Wi-Fi names.");
        registration.setPadding(0, pad / 2, 0, pad / 2);
        form.addView(registration);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("SLM Recorder(s) connections")
                .setView(form)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ssidValue = ssid.getText().toString().trim();
            String ssid2Value = ssid2.getText().toString().trim();
            if (ssidValue.isEmpty()) {
                ssid.setError("At least Recorder 1 Wi-Fi name is required");
                return;
            }
            String registrationValue = GliderRegistration.fromSsid(ssidValue);
            if (registrationValue.isEmpty()) {
                ssid.setError("Include the registration in the Wi-Fi name, for example SLM-FCJAF");
                return;
            }
            String passwordValue = password.getText().toString();
            String password2Value = password2.getText().toString();
            if (passwordValue.length() < 8 || passwordValue.length() > 63) {
                password.setError("The Wi-Fi password must contain 8 to 63 characters");
                return;
            }
            boolean secondEntered = !ssid2Value.isEmpty() || !password2Value.isEmpty();
            if (secondEntered && GliderRegistration.fromSsid(ssid2Value).isEmpty()) {
                ssid2.setError("Enter a complete SLM Wi-Fi name, for example SLM-FABCD");
                return;
            }
            if (secondEntered && (password2Value.length() < 8 || password2Value.length() > 63)) {
                password2.setError("Enter the complete 8 to 63 character Wi-Fi password");
                return;
            }
            if (ssidValue.equals(ssid2Value)) {
                ssid2.setError("Recorder 2 must have a different Wi-Fi name");
                return;
            }
            settings.saveProfiles(
                    new AppSettings.RecorderProfile(ssidValue, passwordValue),
                    new AppSettings.RecorderProfile(ssid2Value, password2Value));
            recorderConnectionRequested = false;
            resetRecorderProbe();
            networks.disconnectRecorder();
            webView.clearHistory();
            webView.loadUrl("about:blank");
            dialog.dismiss();
            Toast.makeText(this, "SLM recorder configurations saved. Select Connect when ready.",
                    Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private boolean hasValidRecorderSettings() {
        return !settings.validRecorderProfiles().isEmpty();
    }

    private boolean hasRecorderConnectionPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private List<AppSettings.RecorderProfile> rankedRecorderProfiles() {
        ArrayList<AppSettings.RecorderProfile> result =
                new ArrayList<>(settings.validRecorderProfiles());
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return result;

        Map<String, Integer> levels = new HashMap<>();
        try {
            WifiManager wifi = getSystemService(WifiManager.class);
            for (ScanResult scan : wifi.getScanResults()) {
                String ssid = scan.SSID == null ? "" : scan.SSID;
                Integer current = levels.get(ssid);
                if (current == null || scan.level > current) levels.put(ssid, scan.level);
            }
        } catch (RuntimeException ignored) {
            return result;
        }
        result.sort((left, right) -> {
            Integer leftLevel = levels.get(left.ssid);
            Integer rightLevel = levels.get(right.ssid);
            if (leftLevel == null && rightLevel == null) return 0;
            if (leftLevel == null) return 1;
            if (rightLevel == null) return -1;
            return Integer.compare(rightLevel, leftLevel);
        });
        return result;
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        resetRecorderProbe();
        recorderProbeExecutor.shutdownNow();
        TransferManager manager = transfers;
        transfers = null;
        if (manager != null) manager.close();
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
