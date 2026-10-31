package com.codextemp.wechatcaptureprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ModelSettingsStore {
    private static final String ALIAS = "wechat_assistant_model_settings_v1";
    private static final byte[] ASSOCIATED_DATA = "wechat-assistant:model-key:v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences preferences;

    ModelSettingsStore(Context context) {
        preferences = context.getSharedPreferences("model_settings", Context.MODE_PRIVATE);
    }

    ModelConfig load() {
        return new ModelConfig(preferences.getString("endpoint", ""), preferences.getString("model", ""),
                preferences.getInt("timeout", 30), preferences.getInt("output_tokens", 4096),
                preferences.getBoolean("json_mode", true), preferences.getBoolean("completion_tokens", false));
    }

    boolean hasKey() {
        return preferences.contains("key_ciphertext") && preferences.contains("key_iv");
    }

    long revision() {
        return preferences.getLong("config_revision", 0);
    }

    boolean cloudAllowed() {
        return hasKey() && preferences.getBoolean("chat_cloud_enabled", false)
                && preferences.getLong("consent_revision", -1) == revision();
    }

    boolean partialAnalysisAllowed() {
        return preferences.getBoolean("partial_analysis_allowed", false);
    }

    boolean setCloudAllowed(boolean allowed, long expectedRevision) {
        if (allowed) {
            load().validate(true);
            if (!hasKey() || revision() != expectedRevision) {
                return false;
            }
        }
        long nextRevision = revision() + 1;
        return preferences.edit().putBoolean("chat_cloud_enabled", allowed)
                .putLong("config_revision", nextRevision)
                .putLong("consent_revision", allowed ? nextRevision : -1).commit();
    }

    void save(ModelConfig config, String replacement) throws GeneralSecurityException, IOException {
        save(config, replacement, partialAnalysisAllowed());
    }

    void save(ModelConfig config, String replacement, boolean allowPartial) throws GeneralSecurityException, IOException {
        config.validate(false);
        ModelConfig.validateKey(replacement);
        SharedPreferences.Editor editor = preferences.edit().putString("endpoint", config.endpoint)
                .putString("model", config.model).putInt("timeout", config.timeoutSeconds)
                .putInt("output_tokens", config.outputTokens).putBoolean("json_mode", config.jsonMode)
                .putBoolean("completion_tokens", config.completionTokens)
                .putBoolean("partial_analysis_allowed", allowPartial)
                .putLong("config_revision", revision() + 1).putBoolean("chat_cloud_enabled", false)
                .remove("consent_revision");
        if (!replacement.isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] plaintext = replacement.getBytes(StandardCharsets.UTF_8);
            try {
                editor.putString("key_ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
                        .putString("key_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
        if (!editor.commit()) {
            throw new IOException("Settings not saved");
        }
    }

    String readKey() throws GeneralSecurityException, IOException {
        if (!hasKey()) {
            return "";
        }
        try {
            byte[] ciphertext = Base64.decode(preferences.getString("key_ciphertext", ""), Base64.NO_WRAP);
            byte[] initialization = Base64.decode(preferences.getString("key_iv", ""), Base64.NO_WRAP);
            if (initialization.length != 12 || ciphertext.length > 8192) {
                throw new GeneralSecurityException("Invalid encrypted key");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, initialization));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] plaintext = cipher.doFinal(ciphertext);
            try {
                String result = new String(plaintext, StandardCharsets.UTF_8);
                ModelConfig.validateKey(result);
                return result;
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Invalid encrypted key", exception);
        }
    }

    void clearKey() throws IOException {
        if (!preferences.edit().remove("key_ciphertext").remove("key_iv")
                .putBoolean("chat_cloud_enabled", false).remove("consent_revision")
                .putLong("config_revision", revision() + 1).commit()) {
            throw new IOException("Key not cleared");
        }
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return (SecretKey) store.getKey(ALIAS, null);
        }
        if (!create) {
            throw new GeneralSecurityException("Encryption key unavailable");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
