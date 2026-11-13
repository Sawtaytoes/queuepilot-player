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

/** Tokens and the account/profile roster are encrypted with Android Keystore. */
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
    return new JSONArray(readSecret("profiles", "[]"));
  }

  JSONObject account() throws Exception {
    return new JSONObject(readSecret("account", "{}"));
  }

  void account(JSONObject account) throws Exception {
    writeSecret("account", account.toString(), true);
  }

  /** The pending native playback events as a JSON array; see {@link PendingEvents}. */
  String nativeEvents() throws Exception {
    return readSecret("native-events", "[]");
  }

  /**
   * Saves the pending native playback events. Call it off the main thread: it encrypts the whole
   * outbox. {@code isDurable} waits for the disk; see {@link #writeSecret}.
   */
  void nativeEvents(String events, boolean isDurable) throws Exception {
    writeSecret("native-events", events, isDurable);
  }

  private String readSecret(String name, String fallback) throws Exception {
    String value =
        context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString(name, "");
    if (value.isEmpty()) return fallback;
    JSONObject envelope = new JSONObject(value);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
    byte[] plain = cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP));
    return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
  }

  void write(JSONArray profiles) throws Exception {
    writeSecret("profiles", profiles.toString(), true);
  }

  /**
   * Encrypts and stores one value.
   *
   * <p>{@code isDurable} uses {@code commit()}, which returns only after the disk write and reports
   * whether it succeeded. Credentials and the profile roster need that: sign-in tells the viewer
   * when they could not be saved. A playback event that may be the last before the process ends
   * (stopped, ended, or the pause when the app leaves the screen) needs it too, because an {@code
   * apply()} still waiting when Android kills the process is lost. Everything else uses {@code
   * apply()}: the value is visible at once and reaches the disk in the background.
   */
  private void writeSecret(String name, String value, boolean isDurable) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    byte[] ciphertext = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    JSONObject envelope =
        new JSONObject()
            .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
    android.content.SharedPreferences.Editor editor =
        context
            .getSharedPreferences("secrets", Context.MODE_PRIVATE)
            .edit()
            .putString(name, envelope.toString());
    if (!isDurable) editor.apply();
    else if (!editor.commit()) throw new IllegalStateException("Profile storage failed");
  }
}
