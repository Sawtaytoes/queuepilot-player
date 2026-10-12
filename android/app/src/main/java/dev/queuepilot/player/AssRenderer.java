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
  private boolean pending, ended;
  private volatile int generation;
  private volatile int width = 1920, height = 1080;
  private long lastFrameUs = C.TIME_UNSET;

  AssRenderer(AssFonts fonts, AssOverlay overlay) {
    super(C.TRACK_TYPE_TEXT);
    this.fonts = fonts;
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
    if (thread == null) {
      thread = new HandlerThread("QueuePilot ASS");
      thread.start();
      worker = new Handler(thread.getLooper());
    }
  }

  @Override
  protected void onStreamChanged(
      Format[] formats, long start, long offset, MediaSource.MediaPeriodId period) {
    format = formats[0];
    resetTrack();
  }

  private void resetTrack() {
    int revision = ++generation;
    pending = false;
    ended = false;
    input.clear();
    lastFrameUs = C.TIME_UNSET;
    Format selected = format;
    main.post(
        () -> {
          if (revision == generation) overlay.clear();
        });
    worker.post(
        () -> {
          if (nativeHandle != 0) AssNative.destroy(nativeHandle);
          nativeHandle = AssNative.create();
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
              if (loadedFonts.add(name)) AssNative.font(nativeHandle, name, bytes);
            });
  }

  @Override
  protected void onPositionReset(long position, boolean joining, boolean resetToKeyframe) {
    // libass handles time moving backwards. Retain already-decoded, still-active events:
    // a long sign can start before the container's video seek point.
    int revision = ++generation;
    pending = false; ended = false; input.clear(); lastFrameUs = C.TIME_UNSET;
    main.post(() -> { if (revision == generation) overlay.clear(); });
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) {
    int revision = generation;
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
      queuedBytes.addAndGet(bytes.length);
      worker.post(
          () -> {
            try {
              if (revision != generation || nativeHandle == 0) return;
              AssPacket packet = AssPacket.decode(bytes);
              AssNative.packet(nativeHandle, packet.data, timeMs, packet.durationMs);
            } catch (IllegalArgumentException | ArithmeticException ignored) {
              /* Malformed subtitle must not stop video. */
            } finally {
              queuedBytes.addAndGet(-bytes.length);
            }
          });
    }
    if (lastFrameUs != C.TIME_UNSET && Math.abs(positionUs - lastFrameUs) < 30000L) return;
    if (!rendering.compareAndSet(false, true)) return;
    lastFrameUs = positionUs;
    int renderWidth = width, renderHeight = height;
    worker.post(
        () -> {
          try {
            if (revision != generation || nativeHandle == 0) return;
            installFonts();
            int[] rectangle = new int[4];
            Bitmap bitmap =
                AssNative.render(
                    nativeHandle, positionUs / 1000, renderWidth, renderHeight, rectangle);
            if (rectangle[0] == -2) return; // unchanged
            main.post(
                () -> {
                  if (revision == generation)
                    overlay.frame(bitmap, rectangle, renderWidth, renderHeight);
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
    worker.post(
        () -> {
          if (nativeHandle != 0) AssNative.destroy(nativeHandle);
          nativeHandle = 0;
          loadedFonts.clear();
        });
  }

  @Override
  protected void onRelease() {
    onReset();
  }

  @Override
  protected void onReset() {
    if (thread != null) {
      thread.quitSafely();
      thread = null;
      worker = null;
    }
  }
}
