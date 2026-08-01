package com.slm.bridge;

import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RecorderJavascriptBridge {
    private final TransferManager transfers;
    private final NetworkCoordinator networks;
    private final FirmwareManager firmware;

    RecorderJavascriptBridge(TransferManager transfers, NetworkCoordinator networks,
                             FirmwareManager firmware) {
        this.transfers = transfers;
        this.networks = networks;
        this.firmware = firmware;
    }

    @JavascriptInterface public String getCapabilities() {
        try {
            JSONObject result = new JSONObject();
            result.put("bridgeVersion", 4);
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
                    .put("transfer-progress")
                    .put("recorder-process-lock")
                    .put("server-firmware"));
            result.put("recorderConnected", networks.recorderNetwork() != null);
            result.put("cellularConnected", networks.cellularNetwork() != null);
            result.put("internetConnected", networks.uploadNetwork() != null);
            return result.toString();
        } catch (Exception e) {
            return "{\"bridgeVersion\":4,\"features\":[]}";
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

    @JavascriptInterface public void analysisFailed(String transferId) {
        transfers.markAnalysisFailed(transferId);
    }

    @JavascriptInterface public String getRecorderTransferStates() {
        return transfers.recorderTransferStates();
    }

    @JavascriptInterface public void listServerFirmware() {
        firmware.listServerFirmware();
    }

    @JavascriptInterface public void installServerFirmware(String requestJson) {
        firmware.installServerFirmware(requestJson);
    }
}
