package dev.queuepilot.player;

import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackAwareSeekMap;
import java.util.function.BooleanSupplier;

/** Decode subtitle pre-roll while retaining the requested playback time. */
@androidx.media3.common.util.UnstableApi
final class AssSeekMap implements TrackAwareSeekMap {
  private final SeekMap delegate;
  private final BooleanSupplier enabled;

  AssSeekMap(SeekMap delegate, BooleanSupplier enabled) {
    this.delegate = delegate;
    this.enabled = enabled;
  }

  private long preroll(long timeUs) {
    return enabled.getAsBoolean() ? Math.max(0, timeUs - 5_000_000L) : timeUs;
  }

  @Override
  public boolean isSeekable() {
    return delegate.isSeekable();
  }

  @Override
  public boolean isSeekable(int id) {
    return delegate instanceof TrackAwareSeekMap
        ? ((TrackAwareSeekMap) delegate).isSeekable(id)
        : delegate.isSeekable();
  }

  @Override
  public long getDurationUs() {
    return delegate.getDurationUs();
  }

  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    SeekPoints points = delegate.getSeekPoints(preroll(timeUs));
    android.util.Log.d(
        "QueuePilotLibass",
        "Seek map: requested="
            + timeUs
            + " preroll="
            + preroll(timeUs)
            + " first="
            + points.first.timeUs);
    return points;
  }

  @Override
  public SeekPoints getSeekPoints(long timeUs, int id) {
    return delegate instanceof TrackAwareSeekMap
        ? ((TrackAwareSeekMap) delegate).getSeekPoints(preroll(timeUs), id)
        : getSeekPoints(timeUs);
  }
}
