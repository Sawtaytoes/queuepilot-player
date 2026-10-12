package dev.queuepilot.player;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Fetches only overlapping subtitle blocks, with the same origin/auth policy as video. */
@androidx.media3.common.util.UnstableApi
final class AssSeekReader {
  final AssCueIndex index = new AssCueIndex();
  volatile long scaleNs = 1000000;
  private final DataSource.Factory factory;
  private final Uri uri;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private volatile boolean closed;
  private final java.util.Map<AssCueIndex.Cue, Long> durations = new java.util.HashMap<>();

  AssSeekReader(DataSource.Factory factory, Uri uri) {
    this.factory = factory;
    this.uri = uri;
  }

  void restore(int track, long time, BooleanSupplier current, Consumer<AssCueIndex.Packet> accept) {
    executor.execute(
        () -> {
          int restored = 0;
          try {
            for (AssCueIndex.Cue cue : index.active(track, time)) {
              if (closed || !current.getAsBoolean()) return;
              Long knownDuration = durations.get(cue);
              if (knownDuration != null && time - cue.startMs() >= knownDuration) continue;
              AssCueIndex.Packet packet = AssCueIndex.read(cue, this::read, scaleNs);
              if (cue.durationMs() < 0) durations.put(cue, packet.durationMs());
              if (time >= packet.startMs() && time - packet.startMs() < packet.durationMs()) {
                if (closed || !current.getAsBoolean()) return;
                accept.accept(packet);
                restored++;
              }
            }
            android.util.Log.d(
                "QueuePilotLibass", "Restored overlapping cues=" + restored + " at=" + time);
          } catch (IOException | ArithmeticException failure) {
            android.util.Log.w("QueuePilotLibass", "Subtitle overlap recovery failed", failure);
          }
        });
  }

  private byte[] read(long position, int length) throws IOException {
    if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Playback closed");
    DataSource source = factory.createDataSource();
    try {
      source.open(
          new DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build());
      byte[] bytes = new byte[length];
      int offset = 0;
      while (offset < length) {
        int count = source.read(bytes, offset, length - offset);
        if (count <= 0) throw new IOException("Truncated subtitle range");
        offset += count;
      }
      return bytes;
    } finally {
      source.close();
    }
  }

  void close() {
    closed = true;
    executor.shutdownNow();
  }
}
