package dev.queuepilot.player;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Streams authored ASS to libass on a worker, without blocking audio/video on rasterization. */
@androidx.media3.common.util.UnstableApi
final class AssRenderer extends BaseRenderer {
  private final AssFonts fonts;
  private final AssSeekReader seekReader;
  private boolean restorePending = true;
  private final AssOverlay overlay;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final DecoderInputBuffer input =
      new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
  private final FormatHolder holder = new FormatHolder();
  private final AtomicBoolean rendering = new AtomicBoolean();
  private final AtomicInteger queuedBytes = new AtomicInteger();
  private final Set<String> loadedFonts = new HashSet<>();
  private HandlerThread thread;
  private Handler worker;
  private Format format;
  private long nativeHandle;
  private Bitmap cachedBitmap;
  private int[] cachedRectangle;
  private int cachedWidth, cachedHeight, displayedGeneration = -1;
  private boolean pending, ended;
  private volatile int generation;
  private volatile boolean frameDirty = true;
  private volatile int width = 1920, height = 1080;
  private long lastFrameUs = C.TIME_UNSET;
  private int seekSamples;
  private long streamOffsetUs;

  AssRenderer(AssFonts fonts, AssOverlay overlay, AssSeekReader seekReader) {
    super(C.TRACK_TYPE_TEXT);
    this.fonts = fonts;
    this.seekReader = seekReader;
    this.overlay = overlay;
  }

  void videoSize(int width, int height) {
    if (width > 0 && height > 0) {
      this.width = Math.min(width, 3840);
      this.height = Math.max(1, height * this.width / width);
    }
  }

  @Override
  public String getName() {
    return "QueuePilotLibass";
  }

  @Override
  public int supportsFormat(Format format) {
    return RendererCapabilities.create(
        MimeTypes.TEXT_SSA.equals(format.sampleMimeType)
                && MimeTypes.VIDEO_MATROSKA.equals(format.containerMimeType)
                && format.initializationData.size() >= 2
            ? C.FORMAT_HANDLED
            : C.FORMAT_UNSUPPORTED_TYPE);
  }

  @Override
  protected void onEnabled(boolean joining, boolean mayRenderStartOfStream) {
    restorePending = true;
    if (thread == null) {
      thread = new HandlerThread("QueuePilot ASS");
      thread.start();
      worker = new Handler(thread.getLooper());
    }
  }

  @Override
  protected void onStreamChanged(
      Format[] formats, long start, long offset, MediaSource.MediaPeriodId period) {
    streamOffsetUs = offset;
    Format next = formats[0];
    boolean same =
        format != null
            && java.util.Objects.equals(format.id, next.id)
            && java.util.Arrays.equals(
                format.initializationData.get(1), next.initializationData.get(1));
    format = next;
    if (!same) resetTrack();
  }

  private void resetTrack() {
    int revision = ++generation;
    restorePending = true;
    pending = false;
    ended = false;
    input.clear();
    lastFrameUs = C.TIME_UNSET;
    frameDirty = true;
    Format selected = format;
    main.post(
        () -> {
          if (revision == generation) overlay.clear();
        });
    worker.post(
        () -> {
          if (nativeHandle != 0) AssNative.destroy(nativeHandle);
          nativeHandle = AssNative.create();
          cachedBitmap = null;
          cachedRectangle = null;
          displayedGeneration = -1;
          loadedFonts.clear();
          if (nativeHandle == 0) return;
          installFonts();
          if (selected != null) AssNative.header(nativeHandle, selected.initializationData.get(1));
        });
  }

  private void installFonts() {
    fonts
        .snapshot()
        .forEach(
            (name, bytes) -> {
              if (loadedFonts.add(name))
                AssNative.font(nativeHandle, name, bytes.getAbsolutePath());
            });
  }

  @Override
  protected void onPositionReset(long position, boolean joining, boolean resetToKeyframe) {
    restorePending = true;
    frameDirty = true;
    seekSamples = 0;
    android.util.Log.d("QueuePilotLibass", "Subtitle seek=" + position / 1000);
    // libass handles time moving backwards. Retain already-decoded, still-active events:
    // a long sign can start before the container's video seek point.
    int revision = ++generation;
    restorePending = true;
    pending = false;
    ended = false;
    input.clear();
    lastFrameUs = C.TIME_UNSET;
    main.post(
        () -> {
          if (revision == generation) overlay.clear();
        });
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) {
    int revision = generation;
    if (restorePending && format != null && seekReader.index.ready()) {
      restorePending = false;
      try {
        int track = Integer.parseInt(format.id);
        Handler recoveryWorker = worker;
        long streamOffsetMs = streamOffsetUs / 1000;
        seekReader.restore(
            track,
            (positionUs - streamOffsetUs) / 1000,
            () -> revision == generation,
            packet -> {
              java.util.concurrent.CountDownLatch applied =
                  new java.util.concurrent.CountDownLatch(1);
              if (!recoveryWorker.post(
                  () -> {
                    try {
                      if (revision != generation || nativeHandle == 0) return;
                      AssNative.packet(
                          nativeHandle,
                          packet.data(),
                          packet.startMs() + streamOffsetMs,
                          packet.durationMs());
                      frameDirty = true;
                    } finally {
                      applied.countDown();
                    }
                  })) return;
              // Backpressure on the I/O thread: at most one recovered packet waits for libass.
              try {
                while (revision == generation
                    && !applied.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {}
              } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
              }
            });
      } catch (NumberFormatException ignored) {
        /* No matching Matroska track. */
      }
    }
    for (int count = 0; count < 32 && !ended && queuedBytes.get() < 8 * 1024 * 1024; count++) {
      if (!pending) {
        input.clear();
        int result = readSource(holder, input, 0);
        if (result == C.RESULT_NOTHING_READ) break;
        if (result == C.RESULT_FORMAT_READ) {
          format = holder.format;
          continue;
        }
        if (input.isEndOfStream()) {
          ended = true;
          break;
        }
        input.flip();
        pending = true;
      }
      if (input.timeUs > positionUs + 1000000L) break;
      byte[] bytes = new byte[input.data.remaining()];
      input.data.get(bytes);
      pending = false;
      long timeMs = input.timeUs / 1000;
      if (seekSamples++ < 3) android.util.Log.d("QueuePilotLibass", "Subtitle sample=" + timeMs);
      queuedBytes.addAndGet(bytes.length);
      worker.post(
          () -> {
            try {
              if (revision != generation || nativeHandle == 0) return;
              AssPacket packet = AssPacket.decode(bytes);
              AssNative.packet(nativeHandle, packet.data, timeMs, packet.durationMs);
              frameDirty = true;
            } catch (IllegalArgumentException | ArithmeticException ignored) {
              /* Malformed subtitle must not stop video. */
            } finally {
              queuedBytes.addAndGet(-bytes.length);
            }
          });
    }
    if (!frameDirty && lastFrameUs != C.TIME_UNSET && Math.abs(positionUs - lastFrameUs) < 30000L)
      return;
    if (!rendering.compareAndSet(false, true)) return;
    lastFrameUs = positionUs;
    int renderWidth = width, renderHeight = height;
    worker.post(
        () -> {
          try {
            if (revision != generation || nativeHandle == 0) return;
            installFonts();
            frameDirty = false;
            int[] rectangle = new int[4];
            Bitmap bitmap =
                AssNative.render(
                    nativeHandle, positionUs / 1000, renderWidth, renderHeight, rectangle);
            if (rectangle[0] != -2) {
              cachedBitmap = bitmap;
              cachedRectangle = rectangle;
              cachedWidth = renderWidth;
              cachedHeight = renderHeight;
            } else if (displayedGeneration == revision || cachedRectangle == null) return;
            Bitmap frame = cachedBitmap;
            int[] bounds = cachedRectangle;
            int frameWidth = cachedWidth, frameHeight = cachedHeight;
            displayedGeneration = revision;
            main.post(
                () -> {
                  if (revision == generation) overlay.frame(frame, bounds, frameWidth, frameHeight);
                });
          } finally {
            rendering.set(false);
          }
        });
  }

  @Override
  public boolean isReady() {
    return true;
  }

  @Override
  public boolean isEnded() {
    return ended && queuedBytes.get() == 0;
  }

  @Override
  protected void onDisabled() {
    ++generation;
    main.post(overlay::clear);
    // Keep active events for Off -> On or plain-text -> ASS. Media3 may reset
    // a disabled renderer and its sample queue can retain only the last overlap.
    // The native state stays bounded and belongs to this playback session.
  }

  @Override
  protected void onRelease() {
    ++generation;
    main.post(overlay::clear);
    if (thread != null) {
      worker.post(
          () -> {
            if (nativeHandle != 0) AssNative.destroy(nativeHandle);
            nativeHandle = 0;
            cachedBitmap = null;
            cachedRectangle = null;
          });
      thread.quitSafely();
      thread = null;
      worker = null;
    }
  }
}
