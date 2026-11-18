package dev.queuepilot.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.BandwidthMeter;

/** Reads one ExoPlayer for a {@link PlaybackStats} sample. Main thread. */
@androidx.media3.common.util.UnstableApi
final class ExoPlayerProbe implements PlaybackStats.Probe {
  private final ExoPlayer player;
  @Nullable private final BandwidthMeter meter;

  ExoPlayerProbe(ExoPlayer player, @Nullable BandwidthMeter meter) {
    this.player = player;
    this.meter = meter;
  }

  @Override
  public long positionMs() {
    return player.getCurrentPosition();
  }

  @Override
  public long durationMs() {
    long duration = player.getDuration();
    return duration == C.TIME_UNSET ? -1 : duration;
  }

  @Override
  public long bufferedMs() {
    return player.getTotalBufferedDuration();
  }

  @Override
  public boolean isPlaying() {
    return player.isPlaying();
  }

  @Override
  public boolean isLoading() {
    return player.isLoading();
  }

  @Override
  public long bandwidthEstimateBps() {
    return meter == null ? -1 : meter.getBitrateEstimate();
  }

  @Override
  @Nullable
  public Object videoCounters() {
    return player.getVideoDecoderCounters();
  }

  @Override
  public long droppedFrames() {
    DecoderCounters counters = player.getVideoDecoderCounters();
    if (counters == null) return 0;
    counters.ensureUpdated();
    return counters.droppedBufferCount;
  }

  @Override
  public long renderedFrames() {
    DecoderCounters counters = player.getVideoDecoderCounters();
    if (counters == null) return 0;
    counters.ensureUpdated();
    return counters.renderedOutputBufferCount;
  }

  @Override
  public long formatVideoBps() {
    return statedBitrate(player.getVideoFormat());
  }

  @Override
  public long formatAudioBps() {
    return statedBitrate(player.getAudioFormat());
  }

  /** A track's own stated bitrate: its average when known, otherwise its peak, or -1. */
  static long statedBitrate(@Nullable Format format) {
    if (format == null) return -1;
    if (format.averageBitrate != Format.NO_VALUE) return format.averageBitrate;
    return format.bitrate != Format.NO_VALUE ? format.bitrate : -1;
  }
}
