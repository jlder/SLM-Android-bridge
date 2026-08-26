package com.slm.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dispatches commands received through WebViewCompat.addWebMessageListener().
 *
 * This class intentionally exposes no Java object methods directly to JavaScript.
 * reach it only through the origin-restricted WebMessageListener configured by
 * MainActivity, and MainActivity rejects non-main-frame callers.
 */
final class RecorderWebMessageBridge {
    private static final int MAX_MESSAGE_LENGTH = 131_072;
    private static final int MAX_ARGUMENT_LENGTH = 120_000;

    private final TransferManager transfers;
    private final NetworkCoordinator networks;
    private final FirmwareManager firmware;
    private final OtaActivityTracker otaActivity;

    RecorderWebMessageBridge(TransferManager transfers, NetworkCoordinator networks,
                             FirmwareManager firmware, OtaActivityTracker otaActivity) {
        this.transfers = transfers;
        this.networks = networks;
        this.firmware = firmware;
        this.otaActivity = otaActivity;
    }

    String compatibilityScript() {
        String capabilities = JSONObject.quote(capabilitiesJson());
        String initialStates = JSONObject.quote(transfers.recorderTransferStates());
        return "(function(){"
                + "if(window.__slmAndroidCompatInstalled)return;"
                + "window.__slmAndroidCompatInstalled=true;"
                + "var capabilities=" + capabilities + ";"
                + "var transferStates=" + initialStates + ";"
                + "function post(method,arg){try{var m={method:method};"
                + "if(arg!==undefined)m.arg=String(arg);"
                + "window.SLMNative.postMessage(JSON.stringify(m));return true;}"
                + "catch(e){console.warn('SLM Bridge message failed',e);return false;}}"
                + "window.__slmAndroidSetTransferStates=function(value){"
                + "try{transferStates=(typeof value==='string')?value:JSON.stringify(value||{files:[]});}"
                + "catch(e){transferStates='{\\\"files\\\":[]}';}};"
                + "window.SLMNative.onmessage=function(event){try{"
                + "var m=JSON.parse(String(event.data||''));"
                + "if(m&&m.type==='transferStates'&&typeof m.value==='string')transferStates=m.value;"
                + "}catch(e){console.warn('SLM Bridge reply ignored',e);}};"
                + "window.SLMAndroid={"
                + "getCapabilities:function(){return capabilities;},"
                + "getRecorderTransferStates:function(){return transferStates;},"
                + "downloadRecorderFile:function(v){post('downloadRecorderFile',v);},"
                + "queueCalibrationReport:function(v){post('queueCalibrationReport',v);},"
                + "deleteStoredFile:function(v){post('deleteStoredFile',v);},"
                + "analysisComplete:function(v){post('analysisComplete',v);},"
                + "analysisFailed:function(v){post('analysisFailed',v);},"
                + "listServerFirmware:function(){post('listServerFirmware');},"
                + "installServerFirmware:function(v){post('installServerFirmware',v);},"
                + "recorderOtaStarted:function(){post('recorderOtaStarted');},"
                + "recorderOtaFinished:function(){post('recorderOtaFinished');}"
                + "};"
                + "post('getRecorderTransferStates');"
                + "})();";
    }

    /**
     * Handles one allow-listed command. The return value, when non-null, is
     * posted back to the sending JavaScript context through JavaScriptReplyProxy.
     */
    String handleMessage(String messageJson) throws Exception {
        if (messageJson == null || messageJson.isEmpty()
                || messageJson.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Invalid recorder bridge message");
        }

        JSONObject request = new JSONObject(messageJson);
        String method = request.optString("method", "");
        String argument = request.has("arg") ? request.optString("arg", "") : "";
        if (argument.length() > MAX_ARGUMENT_LENGTH) {
            throw new IllegalArgumentException("Recorder bridge argument too large");
        }

        switch (method) {
            case "getRecorderTransferStates":
                return transferStatesReply();
            case "downloadRecorderFile":
                transfers.enqueue(argument);
                return null;
            case "queueCalibrationReport":
                transfers.enqueueGeneratedReport(argument);
                return null;
            case "deleteStoredFile":
                transfers.delete(argument);
                return null;
            case "analysisComplete":
                transfers.markAnalysisComplete(argument);
                return null;
            case "analysisFailed":
                transfers.markAnalysisFailed(argument);
                return null;
            case "listServerFirmware":
                firmware.listServerFirmware();
                return null;
            case "installServerFirmware":
                firmware.installServerFirmware(argument);
                return null;
            case "recorderOtaStarted":
                if (otaActivity != null) otaActivity.begin("PHONE");
                return null;
            case "recorderOtaFinished":
                if (otaActivity != null) otaActivity.finish("PHONE");
                return null;
            default:
                throw new SecurityException("Recorder bridge command not allowed");
        }
    }

    String transferStatesReply() throws Exception {
        JSONObject response = new JSONObject();
        response.put("type", "transferStates");
        response.put("value", transfers.recorderTransferStates());
        return response.toString();
    }

    private String capabilitiesJson() {
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
}
