package dev.queuepilot.player;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-playback font attachments; bounded and never written into a shared font directory. */
final class AssFonts {
  static final int MAX_FONT_BYTES = 16 * 1024 * 1024;
  private final Map<String, byte[]> fonts = new LinkedHashMap<>();
  private int bytes;

  synchronized void add(String name, String type, byte[] data) {
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    if (!(lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc"))) return;
    if (fonts.containsKey(name) || fonts.size() >= 128 || bytes + data.length > 96 * 1024 * 1024)
      return;
    fonts.put(name, data);
    bytes += data.length;
  }

  synchronized Map<String, byte[]> snapshot() {
    return new LinkedHashMap<>(fonts);
  }
}
