package dev.queuepilot.player;

import java.nio.charset.StandardCharsets;

/** Converts Media3's Matroska timing prefix back to libass's native packet layout. */
final class AssPacket {
  final byte[] data;
  final long durationMs;
  private AssPacket(byte[] data, long durationMs) { this.data = data; this.durationMs = durationMs; }
  static AssPacket decode(byte[] sample) {
    String text = new String(sample, StandardCharsets.UTF_8);
    if (!text.startsWith("Dialogue: ")) throw new IllegalArgumentException("Expected Matroska ASS dialogue");
    int first = text.indexOf(','), second = text.indexOf(',', first + 1);
    if (first < 0 || second < 0) throw new IllegalArgumentException("Missing ASS timing");
    String[] time = text.substring(first + 1, second).split("[:.]");
    if (time.length != 4) throw new IllegalArgumentException("Invalid ASS duration");
    long duration = Math.addExact(Math.multiplyExact(Long.parseLong(time[0]), 3600000L),
        Long.parseLong(time[1]) * 60000L + Long.parseLong(time[2]) * 1000L + Long.parseLong(time[3]) * 10L);
    if (duration < 0 || duration > 86400000L) throw new IllegalArgumentException("Invalid ASS duration");
    return new AssPacket(text.substring(second + 1).getBytes(StandardCharsets.UTF_8), duration);
  }
}
