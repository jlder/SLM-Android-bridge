package com.slm.bridge;

import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RecorderJavascriptBridge {
    private final TransferManager transfers;
    private final NetworkCoordinator networks;

    RecorderJavascriptBridge(TransferManager transfers, NetworkCoordinator networks) {
        this.transfers = transfers;
        this.networks = networks;
    }

    @JavascriptInterface public String getCapabilities() {
        try {
            JSONObject result = new JSONObject();
            result.put("bridgeVersion", 3);
            result.put("features", new JSONArray()
                    .put("recorder-download")
                    .put("local-file-access")
                    .put("direct-drive-upload")
                    .put("durable-upload-queue")
                    .put("background-upload-queue")
                    .put("internet-upload")
                    .put("automatic-recorder-archive")
                    .put("calibration-report-upload")
                    .put("ssid-registration")
                    .put("transfer-progress"));
            result.put("recorderConnected", networks.recorderNetwork() != null);
            result.put("cellularConnected", networks.cellularNetwork() != null);
            result.put("internetConnected", networks.uploadNetwork() != null);
            return result.toString();
        } catch (Exception e) {
            return "{\"bridgeVersion\":3,\"features\":[]}";
        }
    }

    @JavascriptInterface public void downloadRecorderFile(String requestJson) {
        transfers.enqueue(requestJson);
    }

    @JavascriptInterface public void queueCalibrationReport(String recorderPath) {
        transfers.enqueueGeneratedReport(recorderPath);
    }

    @JavascriptInterface public void deleteStoredFile(String transferId) {
        transfers.delete(transferId);
    }

    @JavascriptInterface public void analysisComplete(String transferId) {
        transfers.markAnalysisComplete(transferId);
    }
}
