package pl.asuscontrol.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Password is encrypted with a non-exportable per-installation Android Keystore key. */
public final class CredentialVault {
    private static final String PREFS = "asus_control_secure_credentials";
    private static final String ALIAS = "pl.asuscontrol.remote.router.v1";
    private static final String IV = "aes_gcm_iv";
    private static final String DATA = "aes_gcm_payload";
    private final SharedPreferences preferences;

    public static final class Credentials {
        public final String username;
        public final String password;
        Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    public CredentialVault(Context context) {
        this.preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return (SecretKey) store.getKey(ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    public boolean exists() {
        return preferences.contains(IV) && preferences.contains(DATA);
    }

    public void save(String username, String password) throws Exception {
        if (username == null || username.trim().isEmpty() ||
                password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Empty credentials");
        }
        JSONObject payload = new JSONObject();
        payload.put("username", username.trim());
        payload.put("password", password);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] ciphertext = cipher.doFinal(
                payload.toString().getBytes(StandardCharsets.UTF_8));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String data = Base64.encodeToString(ciphertext, Base64.NO_WRAP);
        if (!preferences.edit().putString(IV, iv).putString(DATA, data).commit()) {
            throw new IllegalStateException("Encrypted credentials not saved");
        }
    }

    public Credentials load() throws Exception {
        if (!exists()) return null;
        byte[] iv = Base64.decode(preferences.getString(IV, ""), Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(preferences.getString(DATA, ""), Base64.NO_WRAP);
        if (iv.length != 12 || ciphertext.length < 17) {
            throw new IllegalStateException("Invalid credential payload");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, iv));
        JSONObject json = new JSONObject(new String(
                cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        return new Credentials(json.getString("username"), json.getString("password"));
    }

    /** Delete both encrypted fields immediately when user unchecks save password. */
    public void delete() {
        preferences.edit().remove(IV).remove(DATA).commit();
    }
}
