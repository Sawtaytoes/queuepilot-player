package dev.queuepilot.player;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Playback statistics for a small corner panel: the media's video and audio bitrates (now and on
 * average), the network's throughput and Media3's bandwidth estimate, the seconds buffered ahead,
 * whether the connection sustains the media, rebuffers, and dropped frames.
 *
 * <p>While playback is paused, sampling continues: throughput and the seconds buffered keep
 * moving, and the media bitrates hold their last values.
 *
 * <p>Counting is always on while attached, and costs a few array operations per demuxed sample
 * and one atomic add per network read. Sampling runs once a second, on the main thread, only
 * between {@link #start} and {@link #stop}: a panel calls them when it is shown and hidden. A
 * sample allocates nothing; each series keeps the last {@link #HISTORY} seconds for a graph. The
 * verdict follows the rule in {@link Sustainability}.
 */
@androidx.media3.common.util.UnstableApi
final class PlaybackStats implements AnalyticsListener {
  static final int HISTORY = 60;
  static final int DRAIN_WINDOW = 10, MEDIA_WINDOW = 10, THROUGHPUT_WINDOW = 5;
  /** The last frames' timestamps fall short of the duration by up to about a second. */
  static final long END_SLACK_MS = 1_000;

  /** What one sample reads from the player. The real one reads an ExoPlayer; tests fake it. */
  interface Probe {
    long positionMs();

    /** The title's length, or a negative value when not known yet. */
    long durationMs();

    long bufferedMs();

    boolean isPlaying();

    boolean isLoading();

    /** Media3's bandwidth estimate in bits per second, or a negative value. */
    long bandwidthEstimateBps();

    /**
     * The video renderer's counters: a new object each time the renderer is enabled, whose counts
     * start again from zero; null while it is disabled. Compared by identity only.
     */
    @Nullable
    Object videoCounters();

    /** Video frames dropped, counted by {@link #videoCounters()}. */
    long droppedFrames();

    /** Video frames rendered, counted by {@link #videoCounters()}. */
    long renderedFrames();

    /** The selected tracks' own stated bitrates (Format.averageBitrate or bitrate), or -1. */
    long formatVideoBps();

    long formatAudioBps();
  }

  interface Listener {
    void onSample(PlaybackStats stats);
  }

  final MediaBitrateMeter media = new MediaBitrateMeter();
  final StatsRing videoBps = new StatsRing(HISTORY), audioBps = new StatsRing(HISTORY);
  final StatsRing mediaBps = new StatsRing(HISTORY), networkBps = new StatsRing(HISTORY);
  final StatsRing estimateBps = new StatsRing(HISTORY), bufferedSeconds = new StatsRing(HISTORY);
  private final StatsRing loadingThroughputBps = new StatsRing(THROUGHPUT_WINDOW);
  private final AtomicLong networkBytes = new AtomicLong();

  private long statedTotalBps = -1, statedVideoBps = -1, statedAudioBps = -1;
  /** The playing tracks' own stated bitrates, read each sample, so a switch of track shows. */
  private long formatVideoBps = -1, formatAudioBps = -1;
  /** The averages as of the last sample while playing: they hold while paused, as the rates do. */
  private long averageVideoBps = MediaBitrateMeter.UNKNOWN;
  private long averageAudioBps = MediaBitrateMeter.UNKNOWN;
  private long lastTickAt = -1, lastNetworkBytes, lastRendered = -1;
  private boolean wasLoading;
  /** Samples in a row since the last seek or gap in sampling: the history the drain rule uses. */
  private int steadySamples;
  private final DroppedFrameCounter drops = new DroppedFrameCounter();
  private float framesPerSecond = Float.NaN;
  private Sustainability verdict = Sustainability.UNKNOWN;

  final RebufferCounter rebuffers = new RebufferCounter();

  private long ticks, tickCpuNanos, lastTickCpuNanos;
  @Nullable private ExoPlayer player;
  @Nullable private Handler handler;
  @Nullable private Listener listener;
  @Nullable private Probe probe;
  @Nullable private BandwidthMeter meter;
  private final Runnable sampler = this::sampleNow;
  private boolean isSampling, isReleased;
  /** The one stats object sampling now, if any. Main thread. */
  @Nullable private static PlaybackStats sampling;
  /** When the sampler last ran, kept across a stop; -1 before the first sample. */
  private long lastSampleAt = -1;

  // Counting, from the taps.

  /** Bytes read from the network by the player's data sources. Any thread. */
  void countNetworkBytes(int count) {
    if (count > 0) networkBytes.addAndGet(count);
  }

  /** Plex's stated bitrates, in bits per second; -1 where it states none. */
  void setStated(long totalBps, long videoBps, long audioBps) {
    statedTotalBps = totalBps;
    statedVideoBps = videoBps;
    statedAudioBps = audioBps;
  }

  // Sampling.

  /**
   * The player's bandwidth meter, for its estimate. Set by {@link PlaybackStatsTaps} before
   * {@link #attach}.
   */
  void setBandwidthMeter(BandwidthMeter meter) {
    this.meter = meter;
  }

  void attach(ExoPlayer player) {
    this.player = player;
    probe = new ExoPlayerProbe(player, meter);
    player.addAnalyticsListener(this);
  }

  /**
   * Samples once a second until {@link #stop}. Main thread. A start soon after a stop waits for
   * the rest of the second, so showing and hiding the readout never samples faster. Only one
   * stats object samples at a time: starting this one stops any other, such as the previous
   * item's while a queue moves on. A released one never starts again.
   */
  void start(Listener listener) {
    if (!claimSampling()) return;
    this.listener = listener;
    if (handler == null) handler = new Handler(Looper.getMainLooper());
    handler.removeCallbacks(sampler);
    long sinceLast = lastSampleAt < 0 ? 1000 : SystemClock.elapsedRealtime() - lastSampleAt;
    handler.postDelayed(sampler, Math.max(0, 1000 - sinceLast));
  }

  /**
   * Makes this the one stats object that samples, stopping the one before; false when this one is
   * released. Main thread.
   */
  boolean claimSampling() {
    if (isReleased) return false;
    PlaybackStats previous = sampling;
    if (previous != null && previous != this) previous.stop();
    sampling = this;
    isSampling = true;
    return true;
  }

  boolean isSampling() {
    return isSampling;
  }

  /** Stops sampling. The counters keep counting; the next start continues the history. */
  void stop() {
    if (sampling == this) sampling = null;
    isSampling = false;
    if (handler != null) handler.removeCallbacks(sampler);
    listener = null;
    // A gap in sampling is not a second of throughput.
    lastTickAt = -1;
  }

  /** Stops for good: the player is released, or its readout is gone. */
  void release() {
    isReleased = true;
    stop();
    if (player != null) player.removeAnalyticsListener(this);
    player = null;
    probe = null;
  }

  private void sampleNow() {
    Probe current = probe;
    if (current == null || handler == null || !isSampling) return;
    long cpuBefore = android.os.Debug.threadCpuTimeNanos();
    lastSampleAt = SystemClock.elapsedRealtime();
    tick(current, lastSampleAt);
    long cost = android.os.Debug.threadCpuTimeNanos() - cpuBefore;
    lastTickCpuNanos = cost;
    tickCpuNanos += cost;
    ticks++;
    Listener shown = listener;
    if (shown != null) shown.onSample(this);
    // The listener may have stopped sampling.
    if (isSampling) handler.postDelayed(sampler, 1000);
  }

  /** One sample, at {@code nowMs} of elapsed time. Allocates nothing. */
  void tick(Probe p, long nowMs) {
    long position = p.positionMs();
    boolean isPlaying = p.isPlaying(), isLoading = p.isLoading();
    if (isPlaying) {
      videoBps.add(known(media.currentBps(MediaBitrateMeter.VIDEO, position)));
      audioBps.add(known(media.currentBps(MediaBitrateMeter.AUDIO, position)));
      // Every track the stream carries, selected or not: what the network has to deliver.
      mediaBps.add(known(media.currentBps(MediaBitrateMeter.ALL, position)));
      averageVideoBps = media.averageBps(MediaBitrateMeter.VIDEO);
      averageAudioBps = media.averageBps(MediaBitrateMeter.AUDIO);
    } else {
      // Paused or waiting: nothing is consumed, so the rates hold their last values. Throughput
      // and the buffer keep sampling, so a paused viewer can watch the buffer fill.
      videoBps.add(videoBps.latest());
      audioBps.add(audioBps.latest());
      mediaBps.add(mediaBps.latest());
    }

    long bytes = networkBytes.get();
    steadySamples = lastTickAt >= 0 && nowMs > lastTickAt ? steadySamples + 1 : 1;
    // After a gap in sampling, throughput measured before it says nothing about the network now.
    if (lastTickAt < 0) loadingThroughputBps.clear();
    if (lastTickAt >= 0 && nowMs > lastTickAt) {
      double rate = (bytes - lastNetworkBytes) * 8000.0 / (nowMs - lastTickAt);
      networkBps.add(rate);
      if (wasLoading && isLoading) loadingThroughputBps.add(rate);
    } else networkBps.add(Float.NaN);
    lastNetworkBytes = bytes;

    long estimate = p.bandwidthEstimateBps();
    estimateBps.add(estimate > 0 ? estimate : Float.NaN);
    bufferedSeconds.add(Math.max(0, p.bufferedMs()) / 1000f);

    long dropped = p.droppedFrames(), rendered = p.renderedFrames();
    // A re-enabled renderer counts again from zero: no frame rate from the old one's count.
    if (drops.sample(p.videoCounters(), dropped)) lastRendered = -1;
    if (lastRendered >= 0 && lastTickAt >= 0 && nowMs > lastTickAt && rendered >= lastRendered)
      framesPerSecond = (rendered - lastRendered) * 1000f / (nowMs - lastTickAt);
    lastRendered = rendered;

    formatVideoBps = p.formatVideoBps();
    formatAudioBps = p.formatAudioBps();

    lastTickAt = nowMs;
    wasLoading = isLoading;
    long duration = p.durationMs();
    boolean isRestBuffered = duration > 0 && position + p.bufferedMs() >= duration - END_SLACK_MS;
    verdict =
        Sustainability.of(
            mediaBitrateForVerdict(),
            isRestBuffered,
            loadingThroughputBps.mean(THROUGHPUT_WINDOW),
            bufferedSeconds.latest(),
            // Only buffer history from after the last seek or gap: the buffer before a seek
            // belongs to another place in the title.
            steadySamples > DRAIN_WINDOW ? bufferedSeconds.ago(DRAIN_WINDOW) : Float.NaN,
            isPlaying,
            rebuffers.isRebuffering());
  }

  /**
   * Plex's stated bitrate, else the stream measured at the playhead, else the tracks' own stated
   * bitrates: an adaptive stream (HLS) is demuxed outside the counting extractors, so only its
   * variant's stated rates are known. This preview plays files directly, never HLS.
   */
  private double mediaBitrateForVerdict() {
    if (statedTotalBps > 0) return statedTotalBps;
    float measured = mediaBps.mean(MEDIA_WINDOW);
    if (!Float.isNaN(measured)) return measured;
    long tracks = Math.max(0, statedVideoBps()) + Math.max(0, statedAudioBps());
    return tracks > 0 ? tracks : -1;
  }

  private static float known(long value) {
    return value < 0 ? Float.NaN : value;
  }

  // Media3 events, on the main thread.

  /**
   * The player is released, as when a queue moves on to its next item: sampling stops now, not
   * when the old player screen is replaced, which can wait for the next item's details.
   */
  /** A disabled renderer's last drops, after the last sample, still count. */
  @Override
  public void onVideoDisabled(EventTime eventTime, DecoderCounters counters) {
    counters.ensureUpdated();
    drops.disabled(counters, counters.droppedBufferCount);
  }

  @Override
  public void onPlayerReleased(EventTime eventTime) {
    release();
  }

  @Override
  public void onTracksChanged(EventTime eventTime, Tracks tracks) {
    media.select(MediaBitrateMeter.VIDEO, selectedFormatId(tracks, C.TRACK_TYPE_VIDEO));
    media.select(MediaBitrateMeter.AUDIO, selectedFormatId(tracks, C.TRACK_TYPE_AUDIO));
  }

  /** The format id of the selected track of {@code type}, or null when there is none or no id. */
  @Nullable
  static String selectedFormatId(Tracks tracks, int type) {
    for (Tracks.Group group : tracks.getGroups()) {
      if (group.getType() != type || !group.isSelected()) continue;
      for (int index = 0; index < group.length; index++)
        if (group.isTrackSelected(index)) return group.getTrackFormat(index).id;
    }
    return null;
  }

  @Override
  public void onPlaybackStateChanged(EventTime eventTime, int state) {
    rebuffers.playbackStateChanged(state, eventTime.realtimeMs);
  }

  @Override
  public void onPlayWhenReadyChanged(EventTime eventTime, boolean playWhenReady, int reason) {
    rebuffers.playWhenReadyChanged(playWhenReady, eventTime.realtimeMs);
  }

  @Override
  public void onPositionDiscontinuity(
      EventTime eventTime,
      Player.PositionInfo oldPosition,
      Player.PositionInfo newPosition,
      int reason) {
    if (reason == Player.DISCONTINUITY_REASON_SEEK
        || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) seeked(eventTime.realtimeMs);
  }

  void seeked(long nowMs) {
    steadySamples = 0;
    rebuffers.seeked(nowMs);
  }

  // Readings for a panel.

  long droppedFrames() {
    return drops.total();
  }

  float framesPerSecond() {
    return framesPerSecond;
  }

  Sustainability verdict() {
    return verdict;
  }

  long averageVideoBps() {
    return averageVideoBps;
  }

  long averageAudioBps() {
    return averageAudioBps;
  }

  long statedTotalBps() {
    return statedTotalBps;
  }

  /** Plex's stated video bitrate when it gives one, otherwise the playing track's own. */
  long statedVideoBps() {
    return statedVideoBps > 0 ? statedVideoBps : formatVideoBps;
  }

  long statedAudioBps() {
    return statedAudioBps > 0 ? statedAudioBps : formatAudioBps;
  }

  /** CPU time of the last sample, and the mean over every sample, in nanoseconds. */
  long lastTickCpuNanos() {
    return lastTickCpuNanos;
  }

  long meanTickCpuNanos() {
    return ticks == 0 ? 0 : tickCpuNanos / ticks;
  }
}
