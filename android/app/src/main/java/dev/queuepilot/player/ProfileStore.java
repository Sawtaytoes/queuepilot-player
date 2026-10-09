package dev.queuepilot.player;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit enrollment only. The token and endpoint roster is encrypted with Android Keystore. */
final class ProfileStore {
  private static final String ALIAS = "queuepilot-profile-secrets-v1";
  private final Context context;

  ProfileStore(Context context) {
    this.context = context;
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(ALIAS)) {
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(256)
              .build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS, null);
  }

  JSONArray read() throws Exception {
    String value =
        context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("profiles", "");
    if (value.isEmpty()) return new JSONArray();
    JSONObject envelope = new JSONObject(value);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
    byte[] plain = cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP));
    return new JSONArray(new String(plain, java.nio.charset.StandardCharsets.UTF_8));
  }

  void write(JSONArray profiles) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    byte[] ciphertext =
        cipher.doFinal(profiles.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    JSONObject envelope =
        new JSONObject()
            .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
    if (!context
        .getSharedPreferences("secrets", Context.MODE_PRIVATE)
        .edit()
        .putString("profiles", envelope.toString())
        .commit()) throw new IllegalStateException("Profile storage failed");
  }
}
