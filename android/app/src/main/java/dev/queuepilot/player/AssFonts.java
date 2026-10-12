package dev.queuepilot.player;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Per-playback private font files, avoiding large attachments in the Java heap. */
final class AssFonts {
  static final int MAX_FONT_BYTES = 32 * 1024 * 1024;
  private final Map<String, File> fonts = new LinkedHashMap<>();
  private final File directory;
  private long bytes;
  private boolean closed;

  AssFonts(Context context) {
    File cache = context.getCacheDir();
    File[] stale =
        cache.listFiles(
            file ->
                file.getName().startsWith("qp-ass-fonts-")
                    && file.lastModified() < System.currentTimeMillis() - 86400000L);
    if (stale != null) for (File file : stale) remove(file);
    directory = new File(cache, "qp-ass-fonts-" + UUID.randomUUID());
    directory.mkdirs();
  }

  synchronized File temporary() throws IOException {
    if (closed) throw new IOException("Subtitle session has closed");
    return File.createTempFile("font-", ".data", directory);
  }

  synchronized void add(String name, String type, File data) {
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    if (closed
        || !(lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc"))
        || fonts.containsKey(name)
        || fonts.size() >= 128
        || bytes + data.length() > 96L * 1024 * 1024) {
      data.delete();
      return;
    }
    fonts.put(name, data);
    bytes += data.length();
  }

  synchronized Map<String, File> snapshot() {
    return new LinkedHashMap<>(fonts);
  }

  synchronized void close() {
    closed = true;
    fonts.clear();
    remove(directory);
  }

  private static void remove(File directory) {
    File[] children = directory.listFiles();
    if (children != null) for (File file : children) file.delete();
    directory.delete();
  }
}
