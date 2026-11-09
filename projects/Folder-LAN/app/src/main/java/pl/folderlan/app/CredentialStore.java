package pl.folderlan.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only ciphertext persists. The non-exportable AES key is held by Android Keystore. */
final class CredentialStore {
    private static final String ALIAS = "folderlan.credentials.v1";
    private final SharedPreferences preferences;
    CredentialStore(Context context) { preferences = context.getSharedPreferences("paired-computers", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    synchronized List<SmbRepository.Credentials> load() throws Exception {
        List<SmbRepository.Credentials> list = new ArrayList<>();
        String encrypted = preferences.getString("ciphertext", null);
        if (encrypted == null) return list;
        byte[] iv = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP));
        try {
            JSONArray array = new JSONArray(new String(plain, StandardCharsets.UTF_8));
            for (int i=0; i<array.length(); i++) {
                JSONObject item=array.getJSONObject(i);
                list.add(new SmbRepository.Credentials(item.getString("host"), item.getString("share"), item.getString("username"), item.getString("domain"), item.getString("password")));
            }
        } finally { java.util.Arrays.fill(plain, (byte)0); }
        return list;
    }
    synchronized void save(List<SmbRepository.Credentials> list) throws Exception {
        JSONArray array = new JSONArray();
        for (SmbRepository.Credentials c : list) {
            JSONObject item = new JSONObject();
            item.put("host",c.host).put("share",c.share).put("username",c.username).put("domain",c.domain).put("password",c.password);
            array.put(item);
        }
        byte[] plain = array.toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        try {
            byte[] encrypted=cipher.doFinal(plain);
            if (!preferences.edit().putString("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP))
                    .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).commit()) throw new java.io.IOException("Nie zapisano parowania.");
        } finally { java.util.Arrays.fill(plain,(byte)0); }
    }
    synchronized void clear() { preferences.edit().clear().apply(); }
}
