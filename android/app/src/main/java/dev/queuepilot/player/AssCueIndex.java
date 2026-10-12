package dev.queuepilot.player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Container cue metadata, independent of the video keyframe map and renderer event cache. */
final class AssCueIndex {
  record Cue(int track, long startMs, long durationMs, long cluster, long relative) {
    boolean mayOverlap(long targetMs) {
      return startMs <= targetMs && (durationMs < 0 || targetMs - startMs < durationMs);
    }
  }

  record Packet(byte[] data, long startMs, long durationMs) {}

  interface Reader {
    byte[] read(long position, int length) throws IOException;
  }

  private volatile List<Cue> cues;

  void publish(List<Cue> value) {
    cues = List.copyOf(value);
  }

  boolean ready() {
    return cues != null;
  }

  List<Cue> active(int track, long time) {
    List<Cue> snapshot = cues;
    List<Cue> result = new ArrayList<>();
    if (snapshot != null)
      for (Cue cue : snapshot) if (cue.track == track && cue.mayOverlap(time)) result.add(cue);
    return result;
  }

  // Cues address a BlockGroup relative to the Cluster payload, not its EBML header.
  static Packet read(Cue cue, Reader reader, long scaleNs) throws IOException {
    byte[] header = reader.read(cue.cluster, 16);
    Element cluster = element(header, 0);
    if (cluster.id != 0x1f43b675L || cue.relative < 0)
      throw new IOException("Subtitle cue lacks a block address");
    long position = Math.addExact(cue.cluster, Math.addExact(cluster.payload, cue.relative));
    byte[] prefix = reader.read(position, 16);
    Element group = element(prefix, 0);
    if (group.id != 0xa0 || group.size > 8 * 1024 * 1024)
      throw new IOException("Invalid subtitle block group");
    byte[] bytes = reader.read(position + group.payload, (int) group.size);
    byte[] packet = null;
    long duration = cue.durationMs;
    for (int offset = 0; offset < bytes.length; ) {
      Element child = element(bytes, offset);
      long end = (long) child.payload + child.size;
      if (end > bytes.length || end <= offset) throw new IOException("Truncated subtitle block");
      if (child.id == 0x9b) {
        long ticks = unsigned(bytes, child.payload, (int) child.size);
        duration = Math.multiplyExact(ticks, scaleNs) / 1000000L;
      }
      if (child.id == 0xa1) {
        if (child.size < 4) throw new IOException("Truncated subtitle block header");
        int n = vintLength(bytes[child.payload]);
        if (child.payload + n + 3 > end) throw new IOException("Truncated subtitle track");
        long track = bytes[child.payload] & (0xff >>> n);
        for (int i = 1; i < n; i++) track = (track << 8) | (bytes[child.payload + i] & 255);
        int start = child.payload + n + 3;
        if (start > end || track != cue.track || (bytes[start - 1] & 6) != 0)
          throw new IOException("Invalid subtitle track or lacing");
        packet = java.util.Arrays.copyOfRange(bytes, start, (int) end);
      }
      offset = (int) end;
    }
    if (packet == null || duration < 0) throw new IOException("Subtitle duration unavailable");
    return new Packet(packet, cue.startMs, duration);
  }

  private record Element(long id, int payload, long size) {}

  private static Element element(byte[] bytes, int offset) throws IOException {
    if (offset < 0 || offset >= bytes.length) throw new IOException("Missing EBML header");
    int idLength = vintLength(bytes[offset]);
    if (idLength > 4 || offset + idLength >= bytes.length) throw new IOException("Invalid EBML ID");
    long id = unsigned(bytes, offset, idLength);
    offset += idLength;
    int sizeLength = vintLength(bytes[offset]);
    if (offset + sizeLength > bytes.length) throw new IOException("Invalid EBML size");
    long size = bytes[offset] & (0xff >>> sizeLength);
    for (int i = 1; i < sizeLength; i++) size = (size << 8) | (bytes[offset + i] & 255);
    return new Element(id, offset + sizeLength, size);
  }

  private static int vintLength(byte first) throws IOException {
    int n = 1;
    while (n <= 8 && ((first & 255) & (0x100 >>> n)) == 0) n++;
    if (n > 8) throw new IOException("Invalid EBML integer");
    return n;
  }

  private static long unsigned(byte[] bytes, int start, int length) throws IOException {
    if (length < 1 || length > 8 || start + length > bytes.length)
      throw new IOException("Invalid EBML unsigned integer");
    long value = 0;
    for (int i = 0; i < length; i++) value = (value << 8) | (bytes[start + i] & 255);
    if (value < 0) throw new IOException("EBML integer overflow");
    return value;
  }
}
