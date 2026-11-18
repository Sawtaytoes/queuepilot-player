package dev.queuepilot.player;

/**
 * Whether the connection sustains the media, for {@link PlaybackStats}.
 *
 * <p>The media bitrate is Plex's stated bitrate for the file when known, otherwise the mean of the
 * last ten seconds of the stream measured at the playhead, every track included, since the
 * network carries them all. Throughput is counted only over seconds in which Media3 was loading at
 * both ends, because a full buffer stops loading and then the network only keeps pace with
 * playback. "Over the last 10 s" uses only samples taken in a row since the last seek or gap in
 * sampling; without ten of them, the buffer is not known to be falling. In order:
 *
 * <ol>
 *   <li><b>Unknown</b> with no media bitrate yet.
 *   <li><b>Not sustainable</b> while rebuffering.
 *   <li><b>Sustainable</b> when the rest of the title is already buffered: nothing is left to
 *       load, so a shrinking buffer near the end is not the network's doing.
 *   <li><b>Not sustainable</b> while playing when the buffer is under 15 s and fell by 3 s or
 *       more over the last 10 s (the network delivers under about 70% of the rate the picture
 *       plays at).
 *   <li><b>Sustainable</b> when throughput is at least 1.2 times the media bitrate.
 *   <li><b>Sustainable</b> when 10 s or more are buffered and the buffer did not fall by 3 s or
 *       more over the last 10 s: it is at its size or time limit and refills about as fast as it
 *       plays.
 *   <li><b>Marginal</b> otherwise: less than 10 s in hand, or a larger buffer that is falling.
 * </ol>
 */
enum Sustainability {
  UNKNOWN,
  SUSTAINABLE,
  MARGINAL,
  NOT_SUSTAINABLE;

  static final double HEADROOM = 1.2;
  static final float FULL_BUFFER_SECONDS = 10, LOW_BUFFER_SECONDS = 15, DRAIN_SECONDS = 3;

  /**
   * The rule in the class comment. {@code bufferedBefore} is the buffer {@link
   * PlaybackStats#DRAIN_WINDOW} seconds ago. {@code NaN} throughput or buffer means not measured
   * yet.
   */
  static Sustainability of(
      double mediaBps,
      boolean isRestBuffered,
      double throughputBps,
      float bufferedNow,
      float bufferedBefore,
      boolean isPlaying,
      boolean isRebuffering) {
    if (!(mediaBps > 0)) return UNKNOWN;
    if (isRebuffering) return NOT_SUSTAINABLE;
    if (isRestBuffered) return SUSTAINABLE;
    boolean isFalling =
        isPlaying
            && !Float.isNaN(bufferedNow)
            && !Float.isNaN(bufferedBefore)
            && bufferedBefore - bufferedNow >= DRAIN_SECONDS;
    if (isFalling && bufferedNow < LOW_BUFFER_SECONDS) return NOT_SUSTAINABLE;
    if (!Double.isNaN(throughputBps) && throughputBps >= HEADROOM * mediaBps) return SUSTAINABLE;
    if (!Float.isNaN(bufferedNow) && bufferedNow >= FULL_BUFFER_SECONDS && !isFalling)
      return SUSTAINABLE;
    return MARGINAL;
  }
}
