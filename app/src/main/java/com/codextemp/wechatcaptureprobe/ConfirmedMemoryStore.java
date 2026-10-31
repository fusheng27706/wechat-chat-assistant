package com.codextemp.wechatcaptureprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ConfirmedMemoryStore {
    private static final Object LOCK = new Object();
    private static final String ALIAS = "wechat_assistant_confirmed_memory_v1";
    private static final byte[] ASSOCIATED_DATA = "wechat-assistant:confirmed-memory:v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences preferences;

    static final class Snapshot {
        final long revision;
        final List<MemoryProfile> profiles;

        Snapshot(long revision, List<MemoryProfile> profiles) {
            this.revision = revision;
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
        }
    }

    ConfirmedMemoryStore(Context context) {
        preferences = context.getSharedPreferences("confirmed_memory", Context.MODE_PRIVATE);
    }

    long revision() {
        return preferences.getLong("revision", 0);
    }

    Snapshot load() throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            if (!preferences.contains("ciphertext") && !preferences.contains("iv")) {
                return new Snapshot(revision(), Collections.emptyList());
            }
            try {
                String encoded = preferences.getString("ciphertext", "");
                if (encoded.length() > 3000000) {
                    throw new GeneralSecurityException("Invalid memory size");
                }
                byte[] ciphertext = Base64.decode(encoded, Base64.NO_WRAP);
                byte[] initialization = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP);
                if (initialization.length != 12 || ciphertext.length > 2097152) {
                    throw new GeneralSecurityException("Invalid memory envelope");
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, initialization));
                cipher.updateAAD(ASSOCIATED_DATA);
                byte[] plaintext = cipher.doFinal(ciphertext);
                try {
                    JSONObject document = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
                    if (document.getInt("version") != 1) {
                        throw new JSONException("Unknown memory version");
                    }
                    JSONArray entries = document.getJSONArray("profiles");
                    if (entries.length() > 100) {
                        throw new JSONException("Too many profiles");
                    }
                    List<MemoryProfile> profiles = new ArrayList<>();
                    Set<String> identities = new HashSet<>();
                    for (int index = 0; index < entries.length(); index++) {
                        JSONObject entry = entries.getJSONObject(index);
                        if (!entry.getBoolean("user_confirmed")) {
                            throw new JSONException("Unconfirmed memory");
                        }
                        MemoryProfile profile = new MemoryProfile(entry.getString("id"), ConversationContext.Mode.valueOf(entry.getString("kind")),
                                entry.getString("label"), entry.getString("relationship"), entry.getString("style"), entry.getString("facts"));
                        if (!identities.add(profile.id)) {
                            throw new JSONException("Duplicate memory identity");
                        }
                        profiles.add(profile);
                    }
                    return new Snapshot(revision(), profiles);
                } finally {
                    Arrays.fill(plaintext, (byte) 0);
                }
            } catch (JSONException | IllegalArgumentException exception) {
                throw new GeneralSecurityException("Confirmed memory is not readable", exception);
            }
        }
    }

    long save(MemoryProfile profile, long expectedRevision, boolean confirmed) throws GeneralSecurityException, IOException {
        if (!confirmed) {
            throw new IllegalArgumentException("必须由用户明确确认后才能保存记忆");
        }
        synchronized (LOCK) {
            Snapshot snapshot = load();
            checkRevision(snapshot.revision, expectedRevision);
            List<MemoryProfile> profiles = new ArrayList<>(snapshot.profiles);
            int existing = -1;
            for (int index = 0; index < profiles.size(); index++) {
                if (profiles.get(index).id.equals(profile.id)) {
                    existing = index;
                    if (profiles.get(index).kind != profile.kind) {
                        throw new IllegalArgumentException("编辑不能改变档案类型，请新建另一份私聊或群聊档案");
                    }
                    break;
                }
            }
            if (existing >= 0) {
                profiles.set(existing, profile);
            } else if (profiles.size() < 100) {
                profiles.add(profile);
            } else {
                throw new IllegalArgumentException("最多保存 100 份档案，请先删除不再需要的记忆");
            }
            return write(profiles);
        }
    }

    long remove(String id, long expectedRevision) throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            Snapshot snapshot = load();
            checkRevision(snapshot.revision, expectedRevision);
            List<MemoryProfile> profiles = new ArrayList<>(snapshot.profiles);
            profiles.removeIf(profile -> profile.id.equals(id));
            return write(profiles);
        }
    }

    long clear(long expectedRevision) throws IOException {
        synchronized (LOCK) {
            checkRevision(revision(), expectedRevision);
            long next = revision() + 1;
            if (!preferences.edit().remove("ciphertext").remove("iv").putLong("revision", next).commit()) {
                throw new IOException("Memory not cleared");
            }
            AssistantState.memoryChanged(next);
            return next;
        }
    }

    private long write(List<MemoryProfile> profiles) throws GeneralSecurityException, IOException {
        if (profiles.isEmpty()) {
            return clear(revision());
        }
        try {
            JSONArray entries = new JSONArray();
            for (MemoryProfile profile : profiles) {
                entries.put(new JSONObject().put("id", profile.id).put("kind", profile.kind.name()).put("label", profile.label)
                        .put("relationship", profile.relationship).put("style", profile.style).put("facts", profile.facts).put("user_confirmed", true));
            }
            byte[] plaintext = new JSONObject().put("version", 1).put("profiles", entries).toString().getBytes(StandardCharsets.UTF_8);
            try {
                if (plaintext.length > 2097000) {
                    throw new IOException("Memory size limit exceeded");
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key(true));
                cipher.updateAAD(ASSOCIATED_DATA);
                long next = revision() + 1;
                if (!preferences.edit().putString("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
                        .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).putLong("revision", next).commit()) {
                    throw new IOException("Memory not saved");
                }
                AssistantState.memoryChanged(next);
                return next;
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (JSONException exception) {
            throw new IOException("Memory encoding failed", exception);
        }
    }

    private void checkRevision(long actual, long expected) throws IOException {
        if (actual != expected) {
            throw new IOException("Memory changed; reload required");
        }
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return (SecretKey) store.getKey(ALIAS, null);
        }
        if (!create) {
            throw new GeneralSecurityException("Memory key unavailable");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
