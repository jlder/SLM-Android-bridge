package com.slm.bridge;

import org.json.JSONObject;

final class DriveCredentials {
    static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";

    final String clientId;
    final String clientSecret;
    final String refreshToken;
    final String tokenUri;
    final String rootFolderId;

    DriveCredentials(String clientId, String clientSecret, String refreshToken,
                     String tokenUri, String rootFolderId) {
        this.clientId = require(clientId, "client_id");
        this.clientSecret = require(clientSecret, "client_secret");
        this.refreshToken = require(refreshToken, "refresh_token");
        this.tokenUri = require(tokenUri, "token_uri");
        this.rootFolderId = require(rootFolderId, "root_folder_id");
        if (!this.clientId.endsWith(".apps.googleusercontent.com")) {
            throw new IllegalArgumentException("Drive client ID is invalid");
        }
        if (!GOOGLE_TOKEN_URI.equals(this.tokenUri)) {
            throw new IllegalArgumentException("Drive token endpoint is not allowed");
        }
        if (this.refreshToken.length() < 20 || !this.rootFolderId.matches("[A-Za-z0-9_-]{10,}")) {
            throw new IllegalArgumentException("Drive credential is invalid");
        }
    }

    static DriveCredentials fromJson(String value) throws Exception {
        JSONObject object = new JSONObject(value);
        if (object.optInt("version", 1) != 1) {
            throw new IllegalArgumentException("Unsupported Drive credential version");
        }
        return new DriveCredentials(
                object.getString("client_id"),
                object.getString("client_secret"),
                object.getString("refresh_token"),
                object.optString("token_uri", GOOGLE_TOKEN_URI),
                object.getString("root_folder_id"));
    }

    String toJson() {
        try {
            return new JSONObject()
                    .put("version", 1)
                    .put("client_id", clientId)
                    .put("client_secret", clientSecret)
                    .put("refresh_token", refreshToken)
                    .put("token_uri", tokenUri)
                    .put("root_folder_id", rootFolderId)
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize Drive credential", e);
        }
    }

    private static String require(String value, String label) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException("Drive credential is missing " + label);
        return result;
    }
}
