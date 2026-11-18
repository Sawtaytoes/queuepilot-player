package dev.queuepilot.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.ui.PlayerView;
import java.util.Locale;

/**
 * The player's controls: Skip Intro and Skip Credits inside Plex markers, the seek preview
 * from BIF frames, and controls scaled and rounded for the TV.
 */
@androidx.media3.common.util.UnstableApi
final class PlayerControls {
  private final MainActivity host;

  PlayerControls(MainActivity host) {
    this.host = host;
  }

  TextView skipButton;

  MarkerSkip.Marker skipMarker;

  private long skipShownAt;

  boolean isSkipDismissed;

  boolean isControllerVisible;

  /**
   * Shows Skip Intro or Skip Credits while the position is inside a Plex marker, and pulls focus
   * to it once per marker. It hides after a few seconds unless the controls are showing, and when
   * playback leaves the marker. Nothing is skipped without the viewer pressing it.
   */
  void updateSkip() {
    if (skipButton == null || host.player == null) return;
    MarkerSkip.Marker marker =
        MarkerSkip.active(host.playingMarkers, host.player.getCurrentPosition(), host.sectionFloor, host.sectionEnd);
    long now = SystemClock.elapsedRealtime();
    boolean isShown = skipButton.getVisibility() == View.VISIBLE;
    if (marker == null) {
      skipMarker = null;
      isSkipDismissed = false;
      if (isShown) hideSkip();
      return;
    }
    if (!marker.equals(skipMarker)) {
      skipMarker = marker;
      isSkipDismissed = false;
      showSkip(marker, true);
    } else if (isShown && !isControllerVisible && now - skipShownAt >= MarkerSkip.VISIBLE_MS) {
      isSkipDismissed = true;
      hideSkip();
    } else if (!isShown && isSkipDismissed && isControllerVisible) {
      showSkip(marker, false);
    }
  }

  private void showSkip(MarkerSkip.Marker marker, boolean isFocusPulled) {
    skipButton.setText(MarkerSkip.label(marker));
    skipButton.setContentDescription(MarkerSkip.label(marker));
    skipButton.setVisibility(View.VISIBLE);
    skipShownAt = SystemClock.elapsedRealtime();
    if (isFocusPulled) skipButton.requestFocus();
  }

  private void hideSkip() {
    boolean hadFocus = skipButton.hasFocus();
    skipButton.setVisibility(View.GONE);
    if (hadFocus && host.playerView != null) host.playerView.requestFocus();
  }

  void skipMarker() {
    if (host.player == null || skipMarker == null) return;
    long target = MarkerSkip.target(skipMarker, host.playerScreen.playingDuration(), host.sectionFloor, host.sectionEnd);
    // Landing on the end of the item or section reuses the normal completion path: Plex gets a
    // finished timeline and a queue reports the item complete and moves on.
    hideSkip();
    skipMarker = null;
    host.player.seekTo(target);
  }

  LinearLayout seekPreview;

  private ImageView seekPreviewImage;

  private TextView seekPreviewTime;

  BifIndex seekIndex;

  private boolean isSeekIndexRequested;

  /**
   * Seek thumbnails: while the time bar is scrubbed, by touch or by D-pad, a rounded preview of
   * the frame at that time sits above the bar with the time beneath it. The BIF is fetched only
   * on the first scrub, and only the frame being shown is decoded.
   */
  void installSeekPreview(FrameLayout root) {
    seekIndex = null;
    isSeekIndexRequested = false;
    host.seekPreviewFrame = -1;
    View bar = host.playerView.findViewById(androidx.media3.ui.R.id.exo_progress);
    if (!(bar instanceof androidx.media3.ui.TimeBar timeBar)) return;
    LinearLayout box = host.ui.column();
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    box.setVisibility(View.GONE);
    ImageView image = new ImageView(host);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackground(host.ui.shape(TvUi.PANEL, TvUi.WHITE, 12));
    image.setClipToOutline(true);
    box.addView(image, new LinearLayout.LayoutParams(host.dp(240), host.dp(135)));
    host.ui.gap(box, 8);
    TextView time = host.ui.text("", 16, TvUi.WHITE);
    time.setPadding(host.dp(10), host.dp(4), host.dp(10), host.dp(4));
    time.setBackground(host.ui.shape(TvUi.BG, 0, 8));
    box.addView(time);
    root.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
    seekPreview = box;
    seekPreviewImage = image;
    seekPreviewTime = time;
    timeBar.addListener(
        new androidx.media3.ui.TimeBar.OnScrubListener() {
          @Override
          public void onScrubStart(androidx.media3.ui.TimeBar t, long position) {
            requestSeekIndex();
            showSeekPreview(bar, position);
          }

          @Override
          public void onScrubMove(androidx.media3.ui.TimeBar t, long position) {
            showSeekPreview(bar, position);
          }

          @Override
          public void onScrubStop(androidx.media3.ui.TimeBar t, long position, boolean canceled) {
            box.setVisibility(View.GONE);
          }
        });
  }

  private void requestSeekIndex() {
    PlexCatalog.Entry entry = host.playingEntry;
    if (isSeekIndexRequested || entry == null) return;
    isSeekIndexRequested = true;
    String partId = entry.facts().optString("partId"), indexes = entry.facts().optString("indexes");
    if (partId.isEmpty() || !java.util.Arrays.asList(indexes.split(",")).contains("sd")) return;
    String origin = host.playingOrigin.isEmpty() ? host.server() : host.playingOrigin, secret = host.playingToken;
    int request = host.generation;
    host.network.execute(
        () -> {
          try {
            byte[] bytes =
                PlexCatalog.get(
                    PlexCatalog.url(origin, "/library/parts/" + partId + "/indexes/sd"),
                    secret,
                    BifIndex.MAX_BYTES);
            BifIndex index = BifIndex.parse(bytes);
            host.runOnUiThread(
                () -> {
                  if (request != host.generation || host.playingEntry != entry) return;
                  seekIndex = index;
                  host.seekPreviewFrame = -1;
                  if (seekPreview != null && seekPreview.getVisibility() == View.VISIBLE) {
                    View bar = host.playerView.findViewById(androidx.media3.ui.R.id.exo_progress);
                    Object position = seekPreview.getTag();
                    if (bar != null && position instanceof Long value) showSeekPreview(bar, value);
                  }
                });
          } catch (Exception e) {
            // No thumbnails: the time alone is still shown while scrubbing.
          }
        });
  }

  private void showSeekPreview(View bar, long requested) {
    if (seekPreview == null || host.player == null) return;
    long duration = Math.max(0, host.playerScreen.playingDuration());
    long floor = Math.max(0, host.sectionFloor);
    long ceiling = host.sectionEnd > 0 ? host.sectionEnd : duration;
    long position = Math.max(floor, Math.min(requested, Math.max(floor, ceiling)));
    seekPreview.setTag(position);
    String label = clockTime(position);
    seekPreviewTime.setText(label);
    String spoken = "Seek preview, " + label;
    if (seekIndex != null) {
      int frame = seekIndex.frameAt(position);
      if (frame != host.seekPreviewFrame) {
        Bitmap image =
            BitmapFactory.decodeByteArray(
                seekIndex.data(), seekIndex.frameOffset(frame), seekIndex.frameLength(frame));
        seekPreviewImage.setImageBitmap(image);
        host.seekPreviewFrame = frame;
      }
      spoken += ", frame " + (frame + 1) + " of " + seekIndex.frameCount();
    }
    seekPreview.setContentDescription(spoken);
    seekPreview.setVisibility(View.VISIBLE);
    View root = (View) seekPreview.getParent();
    seekPreview.measure(
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int width = seekPreview.getMeasuredWidth(), height = seekPreview.getMeasuredHeight();
    int[] barAt = new int[2], rootAt = new int[2];
    bar.getLocationInWindow(barAt);
    root.getLocationInWindow(rootAt);
    float fraction = duration <= 0 ? 0 : position / (float) duration;
    float center = barAt[0] - rootAt[0] + fraction * bar.getWidth();
    int margin = host.dp(24);
    // Clamped so the box never leaves the screen at either end of the bar.
    float x = Math.max(margin, Math.min(center - width / 2f, root.getWidth() - width - margin));
    float y = Math.max(margin, barAt[1] - rootAt[1] - height - host.dp(18));
    seekPreview.setTranslationX(x);
    seekPreview.setTranslationY(y);
  }

  private static String clockTime(long milliseconds) {
    long seconds = Math.max(0, milliseconds / 1000);
    long hours = seconds / 3600, minutes = seconds / 60 % 60, rest = seconds % 60;
    return hours > 0
        ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
        : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
  }

  /**
   * Media3 sizes its controller from its own resources, so the Display size is applied by
   * scaling each control group about the edge it is anchored to. The overlay text and the Skip
   * button are built with TvUi and already follow the size.
   */
  void scalePlayerControls(PlayerView view) {
    if (host.ui.scale == 1f) return;
    int[][] groups = {
      {androidx.media3.ui.R.id.exo_center_controls, Gravity.CENTER},
      {androidx.media3.ui.R.id.exo_time, Gravity.START | Gravity.BOTTOM},
      {androidx.media3.ui.R.id.exo_basic_controls, Gravity.END | Gravity.BOTTOM},
      {androidx.media3.ui.R.id.exo_extra_controls, Gravity.END | Gravity.BOTTOM},
    };
    for (int[] group : groups) {
      View control = view.findViewById(group[0]);
      if (control == null) continue;
      control.addOnLayoutChangeListener(
          (v, l, t, r, b, ol, ot, or, ob) -> {
            boolean isStart = (group[1] & Gravity.START) == Gravity.START,
                isEnd = (group[1] & Gravity.END) == Gravity.END;
            v.setPivotX(isStart ? 0 : isEnd ? v.getWidth() : v.getWidth() / 2f);
            v.setPivotY(group[1] == Gravity.CENTER ? v.getHeight() / 2f : v.getHeight());
          });
      control.setScaleX(host.ui.scale);
      control.setScaleY(host.ui.scale);
    }
  }

  void roundPlayerControls(View view) {
    if (view.isClickable()
        && view.isFocusable()
        && !(view instanceof androidx.media3.ui.DefaultTimeBar)
        && !(view instanceof PlayerView)) {
      view.setForeground(null);
      android.graphics.drawable.StateListDrawable states =
          new android.graphics.drawable.StateListDrawable();
      states.addState(
          new int[] {android.R.attr.state_focused}, host.ui.shape(TvTheme.CONTROL_FOCUSED, TvTheme.FOCUS_RING, 50));
      states.addState(
          new int[] {android.R.attr.state_pressed}, host.ui.shape(TvTheme.CONTROL_PRESSED, 0, 50));
      states.addState(new int[] {}, host.ui.shape(Color.TRANSPARENT, 0, 50));
      if (view.getId() == androidx.media3.ui.R.id.exo_rew_with_amount
          || view.getId() == androidx.media3.ui.R.id.exo_ffwd_with_amount) {
        android.graphics.drawable.Drawable icon = view.getBackground();
        view.setBackgroundTintList(null);
        view.setBackground(
            new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {states, icon}));
      } else view.setBackground(states);
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) roundPlayerControls(group.getChildAt(i));
    }
  }
}
