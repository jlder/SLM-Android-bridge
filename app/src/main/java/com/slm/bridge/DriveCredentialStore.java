package com.slm.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class DriveCredentialStore {
    private static final String PREFS = "slm_drive_private";
    private static final String KEY_ALIAS = "slm_drive_oauth_v1";
    private static final String VALUE = "encrypted_config";
    private static final String IV = "encrypted_config_iv";
    private final SharedPreferences prefs;

    DriveCredentialStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void save(DriveCredentials credentials) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(credentials.toJson().getBytes(StandardCharsets.UTF_8));
        if (!prefs.edit()
                .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .commit()) {
            throw new IllegalStateException("Cannot save the Drive authorization");
        }
    }

    synchronized DriveCredentials load() throws Exception {
        String encoded = prefs.getString(VALUE, "");
        String encodedIv = prefs.getString(IV, "");
        if (encoded.isEmpty() || encodedIv.isEmpty()) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)));
            byte[] clear = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP));
            return DriveCredentials.fromJson(new String(clear, StandardCharsets.UTF_8));
        } catch (Exception e) {
            clear();
            throw new IllegalStateException("Saved Drive authorization is unreadable", e);
        }
    }

    synchronized void clear() {
        prefs.edit().remove(VALUE).remove(IV).commit();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
