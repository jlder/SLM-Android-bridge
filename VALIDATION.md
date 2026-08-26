# Validation - SLM Bridge v0.3.55 secure WebView bridge

Build/static checks:
- Build succeeds with versionName 0.3.55 and versionCode 57.
- `androidx.webkit:webkit:1.17.0` is resolved.
- No `addJavascriptInterface(` call remains in app source.
- No `@JavascriptInterface` annotation remains in app source.
- No legacy JavaScript-interface keep rule remains in `app/proguard-rules.pro`.
- The old `RecorderJavascriptBridge.java` file is removed.
- `WebViewCompat.addWebMessageListener()` uses only the recorder origin.
- Messages are rejected unless `isMainFrame` is true and `sourceOrigin` matches the recorder origin.
- No legacy bridge fallback is used when WebMessageListener or document-start injection is unavailable.

Functional checks:
- Connect to a current recorder and open the Web interface.
- File processing still detects Bridge capability and transfers a recorder file.
- Existing transfer state is restored after leaving/re-entering the Files page and after a Bridge restart.
- Analysis complete/failed notifications still update the durable process lock.
- Calibration report upload still works.
- Server firmware list/install still works.
- Phone-selected OTA still suppresses the recorder health monitor during `/api/ota`.
- The v0.3.54 USB-power warning still appears and behaves as before when USB is absent.

Google Play check:
- Build/upload versionCode 57 and verify that Code Analysis no longer reports JavaScript Interface Injection in `MainActivity.configureWebView`.
