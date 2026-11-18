package dev.queuepilot.player;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.Locale;

/**
 * A plain text readout of {@link PlaybackStats} in the top right corner of the player, for
 * checking the numbers on a test device. It is not the viewer's panel, which comes with the new
 * player design.
 *
 * <p>Hidden: only a debuggable build shows it, and only after it is launched once with {@code am
 * start -n dev.queuepilot.player.prototype/dev.queuepilot.player.MainActivity --ez playback-stats
 * true} ({@code false} turns it off again). Without it the player has no stats at all. While it is
 * shown the stats sample once a second; when it is hidden, behind another screen or with the app
 * in the background, sampling stops. Each sample is also logged under the {@code PlaybackStats}
 * tag for scripts.
 */
@androidx.media3.common.util.UnstableApi
@android.annotation.SuppressLint("ViewConstructor") // built in code with its stats, never inflated
final class PlaybackStatsOverlay extends TextView implements PlaybackStats.Listener {
  static final String EXTRA = "playback-stats";
  private static final String PREFERENCES = "developer", KEY = "playback-stats";
  private static final String TAG = "PlaybackStats";

  private final PlaybackStats stats;
  private final StringBuilder line = new StringBuilder(512);
  private boolean isSampling;
  /** Pausing comes at once; on a TV, stopping waits for the launcher to settle. */
  private final androidx.lifecycle.LifecycleEventObserver lifecycleObserver =
      (owner, event) -> updateSampling();

  /** New stats for a player about to be built, or null when the readout is off. */
  @Nullable
  static PlaybackStats create(Activity activity) {
    if ((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
    SharedPreferences preferences =
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    if (activity.getIntent().hasExtra(EXTRA)) {
      boolean isOn = activity.getIntent().getBooleanExtra(EXTRA, false);
      if (isOn != preferences.getBoolean(KEY, false))
        preferences.edit().putBoolean(KEY, isOn).apply();
      return isOn ? new PlaybackStats() : null;
    }
    return preferences.getBoolean(KEY, false) ? new PlaybackStats() : null;
  }

  /**
   * Attaches {@code stats} to {@code player} and shows the readout over {@code root}. Plex's
   * stated bitrate for the file, in kilobits per second, is in the entry's facts when known.
   */
  static void show(
      @Nullable PlaybackStats stats,
      ExoPlayer player,
      FrameLayout root,
      @Nullable PlexCatalog.Entry entry) {
    if (stats == null) return;
    long statedKbps = entry == null ? -1 : entry.facts().optLong("bitrate", -1);
    stats.setStated(statedKbps > 0 ? statedKbps * 1000 : -1, -1, -1);
    stats.attach(player);
    PlaybackStatsOverlay overlay = new PlaybackStatsOverlay(root.getContext(), stats);
    float density = root.getResources().getDisplayMetrics().density;
    FrameLayout.LayoutParams layout =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
    layout.setMargins(0, Math.round(24 * density), Math.round(24 * density), 0);
    root.addView(overlay, layout);
  }

  private PlaybackStatsOverlay(Context context, PlaybackStats stats) {
    super(context);
    this.stats = stats;
    float density = context.getResources().getDisplayMetrics().density;
    setTag("playback-stats");
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    setTextColor(TvTheme.CONTENT_PRIMARY);
    setBackgroundColor(TvTheme.PLAYER_BAR);
    setTypeface(android.graphics.Typeface.MONOSPACE);
    int padding = Math.round(10 * density);
    setPadding(padding, padding, padding, padding);
    setElevation(8 * density);
  }

  @Override
  public void onVisibilityAggregated(boolean isVisible) {
    super.onVisibilityAggregated(isVisible);
    updateSampling();
  }

  /** The app going to the background hides the window without changing this view. */
  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    updateSampling();
  }

  /**
   * Samples only while the readout can be seen: attached, shown, in a visible window, and with
   * the activity resumed.
   */
  private void updateSampling() {
    boolean isVisible =
        isAttachedToWindow()
            && isShown()
            && getWindowVisibility() == VISIBLE
            && isResumed();
    if (isVisible == isSampling) return;
    isSampling = isVisible;
    if (isVisible) stats.start(this);
    else stats.stop();
  }

  private boolean isResumed() {
    return !(getContext() instanceof androidx.lifecycle.LifecycleOwner owner)
        || owner
            .getLifecycle()
            .getCurrentState()
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (getContext() instanceof androidx.lifecycle.LifecycleOwner owner)
      owner.getLifecycle().addObserver(lifecycleObserver);
  }

  @Override
  protected void onDetachedFromWindow() {
    if (getContext() instanceof androidx.lifecycle.LifecycleOwner owner)
      owner.getLifecycle().removeObserver(lifecycleObserver);
    super.onDetachedFromWindow();
    isSampling = false;
    stats.release();
  }

  @Override
  public void onSample(PlaybackStats s) {
    if (getWindowVisibility() != VISIBLE || !isShown() || !isResumed()) {
      // A hidden window that sent no event: stop here rather than keep sampling.
      isSampling = false;
      s.stop();
      return;
    }
    long now = SystemClock.elapsedRealtime();
    line.setLength(0);
    line.append("Video  ").append(mbps(s.videoBps.latest())).append(" now  ")
        .append(mbps(s.averageVideoBps())).append(" avg  ").append(mbps(s.statedVideoBps()))
        .append(" stated\n");
    line.append("Audio  ").append(mbps(s.audioBps.latest())).append(" now  ")
        .append(mbps(s.averageAudioBps())).append(" avg  ").append(mbps(s.statedAudioBps()))
        .append(" stated\n");
    line.append("Media  ").append(mbps(s.mediaBps.latest())).append(" now  ")
        .append(mbps(s.statedTotalBps())).append(" stated\n");
    line.append("Network ").append(mbps(s.networkBps.latest())).append(" now  ")
        .append(mbps(s.estimateBps.latest())).append(" estimate\n");
    line.append("Buffered ").append(format("%.1f s", s.bufferedSeconds.latest()))
        .append("  ").append(s.verdict().name().toLowerCase(Locale.ROOT).replace('_', ' '))
        .append('\n');
    line.append("Rebuffers ").append(s.rebuffers.count())
        .append(format(" (%.1f s)", s.rebuffers.ms(now) / 1000f))
        .append("  dropped ").append(s.droppedFrames())
        .append(format("  %.2f fps\n", s.framesPerSecond()));
    line.append(format("Cost %d µs/sample, mean %d µs", s.lastTickCpuNanos() / 1000,
        s.meanTickCpuNanos() / 1000));
    setText(line);
    Log.i(TAG, line.toString().replace('\n', '|'));
  }

  /** Megabits per second with two decimals, or a dash when not known. */
  private static String mbps(double bitsPerSecond) {
    return Double.isNaN(bitsPerSecond) || bitsPerSecond < 0
        ? "—"
        : format("%.2f Mb/s", bitsPerSecond / 1e6);
  }

  private static String format(String pattern, Object... values) {
    return String.format(Locale.ROOT, pattern, values);
  }
}
