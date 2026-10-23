package dev.queuepilot.player;

/**
 * A Roku BIF file of seek thumbnails, as Plex serves at /library/parts/<id>/indexes/sd.
 *
 * <p>Layout: an 8-byte magic, a version at 8, the frame count at 12, a timestamp multiplier in
 * milliseconds at 16 (0 means 1000), reserved bytes to 64, then count + 1 index entries of two
 * little-endian uint32 values: the frame's timestamp in multiplier units and its byte offset.
 * The last entry's timestamp is 0xffffffff and its offset is the end of the final frame. Every
 * value is bounds-checked; a file that does not hold together is rejected, never partly read.
 */
final class BifIndex {
  static final int MAX_BYTES = 32 * 1024 * 1024;
  private static final byte[] MAGIC = {
    (byte) 0x89, 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a
  };
  private static final int HEADER = 64;
  private static final long END = 0xffffffffL;

  private final byte[] data;
  private final long[] times;
  private final int[] offsets;

  private BifIndex(byte[] data, long[] times, int[] offsets) {
    this.data = data;
    this.times = times;
    this.offsets = offsets;
  }

  /** Parses a whole BIF, or throws IllegalArgumentException naming what is wrong. */
  static BifIndex parse(byte[] data) {
    if (data == null || data.length < HEADER) throw new IllegalArgumentException("Too short");
    if (data.length > MAX_BYTES) throw new IllegalArgumentException("Too large");
    for (int i = 0; i < MAGIC.length; i++)
      if (data[i] != MAGIC[i]) throw new IllegalArgumentException("Not a BIF");
    long count = uint32(data, 12);
    long multiplier = uint32(data, 16);
    if (multiplier == 0) multiplier = 1000;
    if (count == 0) throw new IllegalArgumentException("No frames");
    long indexEnd = HEADER + (count + 1) * 8;
    if (indexEnd > data.length) throw new IllegalArgumentException("Truncated index");
    long[] times = new long[(int) count];
    int[] offsets = new int[(int) count + 1];
    for (int i = 0; i <= count; i++) {
      int at = HEADER + i * 8;
      long time = uint32(data, at), offset = uint32(data, at + 4);
      if (i == count) {
        if (time != END) throw new IllegalArgumentException("Missing index terminator");
      } else {
        if (time == END) throw new IllegalArgumentException("Early index terminator");
        if (i > 0 && time * multiplier < times[i - 1])
          throw new IllegalArgumentException("Timestamps go backward");
        times[i] = time * multiplier;
      }
      if (offset < indexEnd || offset > data.length)
        throw new IllegalArgumentException("Frame outside the file");
      if (i > 0 && offset <= offsets[i - 1])
        throw new IllegalArgumentException("Empty or overlapping frame");
      offsets[i] = (int) offset;
    }
    return new BifIndex(data, times, offsets);
  }

  int frameCount() {
    return times.length;
  }

  long frameTime(int frame) {
    return times[frame];
  }

  /** The frame shown at {@code positionMs}: the last one that starts at or before it. */
  int frameAt(long positionMs) {
    int low = 0, high = times.length - 1, found = 0;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      if (times[middle] <= positionMs) {
        found = middle;
        low = middle + 1;
      } else high = middle - 1;
    }
    return found;
  }

  int frameOffset(int frame) {
    return offsets[frame];
  }

  int frameLength(int frame) {
    return offsets[frame + 1] - offsets[frame];
  }

  /** The file's bytes; a caller decodes one frame from offset and length, not the whole file. */
  byte[] data() {
    return data;
  }

  private static long uint32(byte[] data, int at) {
    return (data[at] & 0xffL)
        | (data[at + 1] & 0xffL) << 8
        | (data[at + 2] & 0xffL) << 16
        | (data[at + 3] & 0xffL) << 24;
  }
}
