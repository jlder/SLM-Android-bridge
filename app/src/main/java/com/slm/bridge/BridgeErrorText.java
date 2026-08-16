package com.slm.bridge;

import android.content.Context;

/** Localizes user-visible errors raised by lower-level Bridge components. */
final class BridgeErrorText {
    private BridgeErrorText() {}

    static String localize(Context context, String value) {
        if (context == null || value == null || value.trim().isEmpty()) return value;

        switch (value) {
            case "Cannot create transfer directory":
                return context.getString(R.string.transfer_directory_create_failed);
            case "File is already being processed":
                return context.getString(R.string.file_already_processing);
            case "Cannot update transfer metadata":
                return context.getString(R.string.transfer_metadata_update_failed);
            case "Cannot save transfer metadata":
                return context.getString(R.string.transfer_metadata_save_failed);
            case "Drive upload completed but the stored file was not found":
                return context.getString(R.string.drive_upload_stored_file_missing);
            case "Drive size verification failed":
                return context.getString(R.string.drive_size_verification_failed);
            case "Drive SHA-256 verification is unavailable":
                return context.getString(R.string.drive_sha_unavailable);
            case "Drive SHA-256 verification failed":
                return context.getString(R.string.drive_sha_failed);
            case "Internet connection is unavailable":
                return context.getString(R.string.internet_unavailable);
            case "Drive upload session URL is invalid":
                return context.getString(R.string.drive_upload_session_invalid);
            case "Google response is too large":
                return context.getString(R.string.google_response_too_large);
            case "Recorder Wi-Fi is unavailable":
                return context.getString(R.string.recorder_wifi_unavailable);
            case "Drive configuration endpoint is outside the recorder":
                return context.getString(R.string.drive_config_endpoint_invalid);
            case "Recorder Drive configuration is too large":
                return context.getString(R.string.drive_config_too_large);
            case "Cannot save the Drive authorization":
                return context.getString(R.string.drive_auth_save_failed);
            case "Saved Drive authorization is unreadable":
                return context.getString(R.string.drive_auth_unreadable);
            case "Drive client ID is invalid":
                return context.getString(R.string.drive_client_id_invalid);
            case "Drive token endpoint is not allowed":
                return context.getString(R.string.drive_token_endpoint_invalid);
            case "Drive credential is invalid":
                return context.getString(R.string.drive_credential_invalid);
            case "Unsupported Drive credential version":
                return context.getString(R.string.drive_credential_version_unsupported);
            case "Cannot serialize Drive credential":
                return context.getString(R.string.drive_credential_serialize_failed);
            case "Recorder archive endpoint is outside the recorder":
                return context.getString(R.string.recorder_archive_endpoint_invalid);
            case "Recorder archive failed":
                return context.getString(R.string.recorder_archive_failed);
            case "Recorder file list is too large":
                return context.getString(R.string.recorder_file_list_too_large);
            default:
                break;
        }

        String suffix = suffixAfter(value, "Recorder Drive configuration returned HTTP ");
        if (suffix != null) return context.getString(R.string.drive_config_http_error, suffix);

        suffix = suffixAfter(value, "Drive credential is missing ");
        if (suffix != null) return context.getString(R.string.drive_credential_missing, suffix);

        suffix = suffixAfter(value, "Recorder archive returned HTTP ");
        if (suffix != null) return context.getString(R.string.recorder_archive_http_error, suffix);

        String[] operations = {
                "Google authorization",
                "Drive folder lookup",
                "Drive folder creation",
                "Drive report folder lookup",
                "Drive report folder creation",
                "Drive integrity lookup",
                "Drive upload session creation",
                "Drive upload resume",
                "Drive file upload",
                "Drive firmware download",
                "Drive firmware folder lookup",
                "Drive firmware list"
        };
        int[] labels = {
                R.string.op_google_authorization,
                R.string.op_drive_folder_lookup,
                R.string.op_drive_folder_creation,
                R.string.op_drive_report_folder_lookup,
                R.string.op_drive_report_folder_creation,
                R.string.op_drive_integrity_lookup,
                R.string.op_drive_upload_session_creation,
                R.string.op_drive_upload_resume,
                R.string.op_drive_file_upload,
                R.string.op_drive_firmware_download,
                R.string.op_drive_firmware_folder_lookup,
                R.string.op_drive_firmware_list
        };
        for (int index = 0; index < operations.length; index++) {
            String prefix = operations[index] + " returned HTTP ";
            if (value.startsWith(prefix)) {
                return context.getString(R.string.http_operation_error,
                        context.getString(labels[index]), value.substring(prefix.length()));
            }
        }
        return value;
    }

    private static String suffixAfter(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : null;
    }
}
