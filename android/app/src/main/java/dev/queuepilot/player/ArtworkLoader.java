package dev.queuepilot.player;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Loads card and title artwork off the main thread.
 *
 * <ul>
 *   <li>A picture is decoded no larger than the view that shows it: a 720-pixel poster derivative
 *       shown 450 pixels tall is decoded at half size, a quarter of the memory.
 *   <li>Recent pictures stay in memory (at most {@link #MAX_CACHE_BYTES}), so Back to a grid or
 *       Home draws its artwork at once instead of fetching and decoding it again.
 *   <li>Artwork has its own threads, so a library page or a queue list never waits behind a
 *       screen's worth of poster downloads.
 *   <li>A request for a screen that is already gone is dropped before it is fetched.
 * </ul>
 */
final class ArtworkLoader {
  static final int MAX_CACHE_BYTES = 32 * 1024 * 1024;
  static final int MAX_WIDTH = 4096, MAX_HEIGHT = 6144;

  private ArtworkLoader() {}

  /** Android parts, created on first use so the pure methods below run in JVM unit tests. */
  private static final class Runtime {
    static final Handler main = new Handler(Looper.getMainLooper());
    static final ExecutorService images =
        Executors.newFixedThreadPool(
            3,
            work -> {
              Thread thread =
                  new Thread(
                      () -> {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        work.run();
                      },
                      "artwork");
              thread.setDaemon(true);
              return thread;
            });
    static final LruCache<String, Bitmap> cache =
        new LruCache<>(MAX_CACHE_BYTES) {
          @Override
          protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
          }
        };
    static boolean isTrimRegistered;
  }

  /**
   * Shows {@code thumb}'s {@code width}-pixel derivative in {@code view}. The view's layout size,
   * when it has one, bounds the decoded size. {@code isWanted} is false once the screen that asked
   * is gone. Call on the main thread.
   */
  static void load(
      ImageView view,
      String origin,
      String secret,
      String thumb,
      int width,
      BooleanSupplier isWanted) {
    if (thumb.isEmpty()) return;
    registerTrim(view.getContext());
    ViewGroup.LayoutParams layout = view.getLayoutParams();
    int targetWidth = layout != null && layout.width > 0 ? layout.width : 0;
    int targetHeight = layout != null && layout.height > 0 ? layout.height : 0;
    String key = cacheKey(origin, secret, thumb, width, targetWidth, targetHeight);
    Bitmap cached = Runtime.cache.get(key);
    if (cached != null) {
      view.setImageBitmap(cached);
      return;
    }
    Runtime.images.execute(
        () -> {
          if (!isWanted.getAsBoolean()) return;
          try {
            byte[] bytes =
                PlexCatalog.get(
                    PlexCatalog.url(origin, TitleCast.transcodePath(thumb, width)),
                    secret,
                    12 * 1024 * 1024);
            Bitmap image = decode(bytes, targetWidth, targetHeight);
            Runtime.cache.put(key, image);
            Runtime.main.post(
                () -> {
                  if (isWanted.getAsBoolean()) view.setImageBitmap(image);
                });
          } catch (Exception ignored) {
            // The card keeps its plain background.
          }
        });
  }

  /** Decodes a bounded image, subsampled to the smallest size that still covers the target. */
  static Bitmap decode(byte[] bytes, int targetWidth, int targetHeight) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (!isAcceptable(bounds.outWidth, bounds.outHeight))
      throw new IllegalArgumentException("Invalid image");
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize =
        sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
    Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (image == null) throw new IllegalArgumentException("Invalid image");
    return image;
  }

  static boolean isAcceptable(int width, int height) {
    return width > 0 && height > 0 && width <= MAX_WIDTH && height <= MAX_HEIGHT;
  }

  /**
   * The largest power of two that keeps the decoded picture at least as large as the target in
   * every dimension the target gives (0 means that dimension is not known). A picture already
   * smaller than its view, or one with no known target, decodes at full size: never upscaled.
   */
  static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
    if (width <= 0 || height <= 0 || (targetWidth <= 0 && targetHeight <= 0)) return 1;
    int size = 1;
    while (covers(width / (size * 2), targetWidth) && covers(height / (size * 2), targetHeight))
      size *= 2;
    return size;
  }

  private static boolean covers(int decoded, int target) {
    return target <= 0 || decoded >= target;
  }

  /**
   * One entry per server, profile credential, picture and size. The credential is reduced to its
   * SHA-256 digest, so the key holds no credential and two credentials never share an entry: a
   * picture fetched for one profile is not shown to another from memory.
   */
  static String cacheKey(
      String origin, String secret, String thumb, int width, int targetWidth, int targetHeight) {
    return origin
        + '\n'
        + digest(secret)
        + '\n'
        + width
        + '\n'
        + targetWidth
        + 'x'
        + targetHeight
        + '\n'
        + thumb;
  }

  /** The last credential and its digest, as one pair: every card on a screen shares one. */
  private static volatile String[] lastDigest;

  static String digest(String secret) {
    String[] last = lastDigest;
    if (last != null && secret.equals(last[0])) return last[1];
    try {
      byte[] hash =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
      }
      String digest = hex.toString();
      lastDigest = new String[] {secret, digest};
      return digest;
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Android device has SHA-256", e);
    }
  }

  /** Releases the remembered pictures when Android needs the memory back. */
  private static void registerTrim(Context context) {
    if (Runtime.isTrimRegistered) return;
    Runtime.isTrimRegistered = true;
    context
        .getApplicationContext()
        .registerComponentCallbacks(
            new ComponentCallbacks2() {
              @Override
              public void onTrimMemory(int level) {
                // The app is in the background: its next screen can fetch again.
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) Runtime.cache.evictAll();
              }

              @Override
              public void onConfigurationChanged(Configuration configuration) {}

              @Override
              public void onLowMemory() {
                Runtime.cache.evictAll();
              }
            });
  }
}
