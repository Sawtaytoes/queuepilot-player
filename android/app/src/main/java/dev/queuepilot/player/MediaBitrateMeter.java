package dev.queuepilot.player;

import androidx.annotation.Nullable;
import java.util.Arrays;

/**
 * The media's own bitrate, from the size and timestamp of every sample the demuxer reads.
 *
 * <p>Each sample is added to the bucket of the media second it plays in, per track. The bitrate at
 * the playhead is the bucket of the second that has just played, so it is the rate of the picture
 * and sound on screen now, whatever the network does. The average is every sample's bytes over
 * the media time they span.
 *
 * <p>A file can carry several audio tracks (languages, commentary), and the demuxer reads them
 * all, so every track keeps its own counts. {@link #VIDEO} and {@link #AUDIO} read the selected
 * track of their kind, matched by {@code Format.id}: what the renderers consume, also for seconds
 * buffered before a switch of track. Before a selection is known, they read every track of the
 * kind together. {@link #ALL} reads every track: the stream the network has to carry.
 *
 * <p>Samples arrive on the loading thread and are read on the main thread, so every method is
 * synchronized; each call does a few array operations and never allocates.
 */
final class MediaBitrateMeter {
  static final int VIDEO = 0, AUDIO = 1, OTHER = 2, ALL = 3;
  static final long UNKNOWN = -1;
  /** Media seconds kept: far more than the buffer ahead, with room behind the playhead. */
  static final int SECONDS = 512;
  /**
   * Video and audio tracks counted on their own. Tracks past these, tracks without an id, and
   * every other kind (subtitles) share one counter per kind.
   */
  static final int TRACKS = 12;

  private static final int SLOTS = TRACKS + 3;
  private static final long MICROS = 1_000_000L;
  private final int[] slotKind = new int[SLOTS];
  private final String[] slotId = new String[SLOTS];
  private int named;
  private final long[][] bytes = new long[SLOTS][SECONDS];
  private final long[][] seconds = new long[SLOTS][SECONDS];
  /** The discontinuity count when each bucket was last started. */
  private final int[][] epochs = new int[SLOTS][SECONDS];
  private int epoch;
  private final long[] totalBytes = new long[SLOTS];
  private final long[] spanStartUs = new long[SLOTS], spanEndUs = new long[SLOTS];
  private final long[] closedSpanUs = new long[SLOTS];
  /** The selected track's format id for video and audio, or null when not known. */
  private final String[] selected = new String[2];

  MediaBitrateMeter() {
    reset();
  }

  /** Media3's track type (C.TRACK_TYPE_VIDEO is 2, C.TRACK_TYPE_AUDIO is 1) as a kind. */
  static int kindOf(int trackType) {
    return trackType == 2 ? VIDEO : trackType == 1 ? AUDIO : OTHER;
  }

  /**
   * The counter for a track of {@code kind} with format id {@code formatId}: the same one each
   * time for the same track. Loading thread, once per track and format.
   */
  synchronized int track(int kind, @Nullable String formatId) {
    if (kind == OTHER || formatId == null) return TRACKS + kind;
    for (int slot = 0; slot < named; slot++)
      if (slotKind[slot] == kind && formatId.equals(slotId[slot])) return slot;
    if (named == TRACKS) return TRACKS + kind;
    slotKind[named] = kind;
    slotId[named] = formatId;
    return named++;
  }

  /** The selected track of {@code kind} (video or audio) changed; null when not known. */
  synchronized void select(int kind, @Nullable String formatId) {
    selected[kind] = formatId;
  }

  /** A sample of a track of {@code kind}, looking its counter up; for tests. */
  void sample(int kind, @Nullable String formatId, long timeUs, int size) {
    count(track(kind, formatId), timeUs, size);
  }

  /** A sample of a track of {@code kind} without a format id; for tests. */
  void sample(int kind, long timeUs, int size) {
    sample(kind, null, timeUs, size);
  }

  /** One demuxed sample for the counter {@code slot} from {@link #track}. Loading thread. */
  synchronized void count(int slot, long timeUs, int size) {
    if (size <= 0) return;
    long second = Math.floorDiv(timeUs, MICROS);
    int index = (int) Math.floorMod(second, (long) SECONDS);
    if (seconds[slot][index] != second || epochs[slot][index] != epoch) {
      // A second demuxed again after a discontinuity starts from zero: never counted twice.
      seconds[slot][index] = second;
      epochs[slot][index] = epoch;
      bytes[slot][index] = 0;
    }
    bytes[slot][index] += size;
    totalBytes[slot] += size;
    if (spanStartUs[slot] == Long.MIN_VALUE || timeUs < spanStartUs[slot]) spanStartUs[slot] = timeUs;
    if (spanEndUs[slot] == Long.MIN_VALUE || timeUs > spanEndUs[slot]) spanEndUs[slot] = timeUs;
  }

  /**
   * The demuxer starts again elsewhere: a seek outside the buffer, or a retry after a network
   * error. A second it demuxes again starts its bucket from zero, so it is not counted twice;
   * the seconds already buffered keep theirs, because their samples are not demuxed again. The
   * second the demuxer restarts in may undercount, since it restarts at a key frame. The average
   * keeps the time spanned so far.
   */
  synchronized void discontinuity() {
    epoch++;
    for (int slot = 0; slot < SLOTS; slot++) {
      closedSpanUs[slot] += openSpanUs(slot);
      spanStartUs[slot] = spanEndUs[slot] = Long.MIN_VALUE;
    }
  }

  synchronized void reset() {
    epoch = 0;
    named = 0;
    selected[VIDEO] = selected[AUDIO] = null;
    for (int slot = 0; slot < SLOTS; slot++) {
      slotKind[slot] = slot >= TRACKS ? slot - TRACKS : -1;
      slotId[slot] = null;
      Arrays.fill(epochs[slot], 0);
      Arrays.fill(seconds[slot], Long.MIN_VALUE);
      Arrays.fill(bytes[slot], 0);
      totalBytes[slot] = closedSpanUs[slot] = 0;
      spanStartUs[slot] = spanEndUs[slot] = Long.MIN_VALUE;
    }
  }

  /** Bits per second of the media second {@code second}, or {@link #UNKNOWN}. */
  synchronized long bitsPerSecondAt(int kind, long second) {
    int index = (int) Math.floorMod(second, (long) SECONDS);
    int only = selectedSlot(kind);
    if (only != SLOTS) return only < 0 ? UNKNOWN : bucket(only, index, second);
    long sum = UNKNOWN;
    for (int slot = 0; slot < SLOTS; slot++) {
      if (!isOfKind(slot, kind)) continue;
      long value = bucket(slot, index, second);
      if (value != UNKNOWN) sum = Math.max(0, sum) + value;
    }
    return sum;
  }

  /**
   * Bits per second of the second that has just played at {@code positionMs}, or {@link
   * #UNKNOWN} when that second was not demuxed since the last seek.
   */
  synchronized long currentBps(int kind, long positionMs) {
    return bitsPerSecondAt(kind, Math.floorDiv(positionMs, 1000L) - 1);
  }

  /**
   * Bits per second over every sample of this kind since the start, or {@link #UNKNOWN} before
   * a full second of media has been seen.
   */
  synchronized long averageBps(int kind) {
    int only = selectedSlot(kind);
    if (only < 0) return UNKNOWN;
    long total = 0, spanUs = 0;
    for (int slot = 0; slot < SLOTS; slot++) {
      if (only == SLOTS ? !isOfKind(slot, kind) : slot != only) continue;
      total += totalBytes[slot];
      spanUs = Math.max(spanUs, closedSpanUs[slot] + openSpanUs(slot));
    }
    return spanUs < MICROS ? UNKNOWN : total * 8 * MICROS / spanUs;
  }

  /**
   * The one counter to read for {@code kind}: the selected track's, -1 when the selected track
   * has not been counted, or {@code SLOTS} to read every track of the kind together.
   */
  private int selectedSlot(int kind) {
    if (kind != VIDEO && kind != AUDIO) return SLOTS;
    String want = selected[kind];
    if (want == null) return SLOTS;
    for (int slot = 0; slot < named; slot++)
      if (slotKind[slot] == kind && want.equals(slotId[slot])) return slot;
    return -1;
  }

  private boolean isOfKind(int slot, int kind) {
    return kind == ALL ? slot >= TRACKS || slot < named : slotKind[slot] == kind;
  }

  private long bucket(int slot, int index, long second) {
    return seconds[slot][index] == second ? bytes[slot][index] * 8 : UNKNOWN;
  }

  private long openSpanUs(int slot) {
    return spanStartUs[slot] == Long.MIN_VALUE ? 0 : spanEndUs[slot] - spanStartUs[slot];
  }
}
