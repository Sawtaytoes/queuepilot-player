package dev.queuepilot.player;

import android.content.Context;
import android.provider.Settings;

/**
 * The name Plex shows for this TV in Now Playing and the account's device list. Uses the name
 * set under Android TV's Device Preferences, so two TVs on one account stay distinguishable.
 */
final class DeviceName {
  static final String FALLBACK = "QueuePilot TV";
  private static final int MAX_LENGTH = 64;

  private DeviceName() {}

  static String read(Context context) {
    String chosen = null;
    try {
      chosen = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
    } catch (RuntimeException ignored) {
    }
    return choose(chosen, android.os.Build.MODEL);
  }

  /** The user-chosen name, else the hardware model, else the product name. */
  static String choose(String chosen, String model) {
    for (String candidate : new String[] {chosen, model}) {
      String clean = clean(candidate);
      if (!clean.isEmpty()) return clean;
    }
    return FALLBACK;
  }

  private static String clean(String value) {
    if (value == null) return "";
    String clean = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
    if (clean.codePointCount(0, clean.length()) <= MAX_LENGTH) return clean;
    return clean.substring(0, clean.offsetByCodePoints(0, MAX_LENGTH)).trim();
  }
}
