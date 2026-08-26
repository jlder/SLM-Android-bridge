# SLM Bridge v0.3.55 - secure recorder WebView bridge

Version:
- versionName: 0.3.55
- versionCode: 57

Changes from v0.3.54:
- Removed the legacy `WebView.addJavascriptInterface(..., "SLMAndroid")` mechanism flagged by Google Play as JavaScript Interface Injection.
- Added AndroidX WebKit 1.17.0 and an origin-restricted `WebViewCompat.addWebMessageListener()` channel.
- Native messages are accepted only from the main frame and only from the configured recorder origin (`http://192.168.4.1`).
- Added a document-start compatibility layer that preserves the recorder's existing `window.SLMAndroid` API, so recorder firmware does not need to change.
- `getCapabilities()` remains synchronous in JavaScript.
- `getRecorderTransferStates()` remains synchronous using a JavaScript cache that is initialized from Android and refreshed when durable transfer state changes.
- No fallback to `addJavascriptInterface()` is present. If the required secure WebView features are unavailable, the native recorder integration stays disabled rather than falling back to the vulnerable mechanism.

The v0.3.54 USB-power warning is retained unchanged.
