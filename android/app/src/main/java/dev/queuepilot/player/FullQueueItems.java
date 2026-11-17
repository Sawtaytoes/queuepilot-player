package dev.queuepilot.player;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The items of a full queue: list rows and grid tiles with their watched, in-progress, and
 * next badges.
 */
@androidx.media3.common.util.UnstableApi
final class FullQueueItems {
  private final MainActivity host;

  FullQueueItems(MainActivity host) {
    this.host = host;
  }

  /**
   * Draws every entry and its items. Returns the view to focus: the item chosen last, or the next
   * item. The lineup line follows the last lineup item, at {@code lineup.endPosition}.
   *
   * <p>THE GRID IS ONE WRAPPING GRID (decision 2026-10-23, item 5). Items flow across entries into
   * shared rows of posters, as many columns as the width and Display size allow. Only an entry
   * with more than one item gets a heading, and only a heading or the lineup line starts a new
   * row; the next item is marked by its own "Next" badge, not by a line that breaks the row. One
   * poster per row was the defect: every one-movie entry drew its own heading and its own row.
   * The list layout keeps one compact row per item, under every entry's heading.
   */
  View drawFullQueueItems(JSONObject full, JSONObject queue, LinearLayout area) {
    area.removeAllViews();
    boolean isGrid = "grid".equals(full.optString("layout"));
    JSONArray entries = full.optJSONArray("entries");
    JSONObject lineup = full.optJSONObject("lineup");
    int endPosition =
        lineup == null || lineup.isNull("endPosition") ? -1 : lineup.optInt("endPosition", -1);
    long lineupMs = 0;
    for (int e = 0; entries != null && e < entries.length(); e++) {
      JSONArray items = entries.optJSONObject(e).optJSONArray("items");
      for (int i = 0; items != null && i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item.isNull("lineupIndex") || item.optString("state").equals("watched")) continue;
        long duration = Math.max(0, item.optLong("durationMs", 0));
        lineupMs += Math.max(0, duration - Math.max(0, item.optLong("offsetMs", 0)));
      }
    }
    int columns = host.columns(5), width = host.itemWidth(columns);
    boolean isDividerPlaced = endPosition < 0;
    View focus = null, nextView = null;
    int drawn = 0;
    LinearLayout row = null;
    int inRow = 0;
    for (int e = 0; entries != null && e < entries.length(); e++) {
      JSONObject entry = entries.optJSONObject(e);
      JSONArray items = entry.optJSONArray("items");
      List<JSONObject> visible = new ArrayList<>();
      for (int i = 0; items != null && i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (!isGrid && !host.isWatchedShown && item.optString("state").equals("watched")) continue;
        visible.add(item);
      }
      if (visible.isEmpty()) continue;
      boolean hasHeading = !isGrid || visible.size() > 1;
      if (hasHeading) {
        row = null;
        host.ui.gap(area, 22);
        LinearLayout heading = host.ui.row();
        heading.setGravity(Gravity.BOTTOM);
        TextView name = host.ui.title(QueueDetail.text(entry, "title"), 20);
        name.setTag("queue-entry");
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(
            host.ui.text(QueueDetail.entryNote(entry, e, entries.length()), 13, TvUi.MUTED));
        area.addView(heading);
        host.ui.gap(area, 12);
      }
      for (JSONObject item : visible) {
        int position = item.optInt("position");
        if (!isDividerPlaced && position > endPosition) {
          area.addView(host.fullQueueScreen.lineupDivider(full, lineupMs));
          isDividerPlaced = true;
          row = null;
        }
        if (item.optBoolean("isNext") && !isGrid) {
          TextView tonight =
              host.ui.text("TONIGHT'S LINEUP · STARTS AT YOUR NEXT ITEM", 12, TvUi.ACCENT);
          host.ui.withIcon(tonight, R.drawable.lucide_play);
          if (row != null) host.ui.gap(area, 4);
          area.addView(tonight);
          host.ui.gap(area, 10);
          row = null;
        }
        Runnable open = () -> host.itemPanel.queueItemPanel(full, entry, item, queue);
        View view = isGrid ? queueTile(item, width, open) : queueRow(item, open);
        if (isGrid) {
          if (row == null || inRow == columns) {
            if (row == null && drawn == 0 && !hasHeading) host.ui.gap(area, 22);
            row = host.ui.row();
            row.setTag("queue-grid-row");
            area.addView(row);
            host.ui.gap(area, 18);
            inRow = 0;
          }
          row.addView(view);
          inRow++;
        } else {
          area.addView(view, new LinearLayout.LayoutParams(-1, -2));
          host.ui.gap(area, 8);
        }
        drawn++;
        if (item.optBoolean("isNext")) nextView = view;
        if (focus == null
            && !host.fullQueueFocus.isEmpty()
            && host.fullQueueFocus.equals(QueueDetail.text(item, "ratingKey"))) focus = view;
        if (!isDividerPlaced && position == endPosition) {
          area.addView(host.fullQueueScreen.lineupDivider(full, lineupMs));
          isDividerPlaced = true;
          row = null;
        }
      }
      // A headed entry owns its rows, so whatever follows it starts below it.
      if (hasHeading) row = null;
    }
    if (!isDividerPlaced && drawn > 0) area.addView(host.fullQueueScreen.lineupDivider(full, lineupMs));
    if (drawn == 0)
      area.addView(
          host.ui.text(
              entries == null || entries.length() == 0
                  ? "This queue has no items yet."
                  : "Every item in this queue is watched.",
              17,
              TvUi.MUTED));
    return focus != null ? focus : nextView;
  }

  /** The item's own picture from its server; items on another server keep the plain tile. */
  private void loadQueueArt(ImageView view, JSONObject item, int width) {
    if (host.profile == null) return;
    String serverId = QueueDetail.text(item, "serverId");
    if (!serverId.isEmpty() && !serverId.equals(host.profile.optString("serverId"))) return;
    JSONObject art = item.optJSONObject("artwork");
    String thumb = "";
    for (String key : new String[] {"thumb", "parentThumb", "grandparentThumb"})
      if (thumb.isEmpty()) thumb = QueueDetail.text(art, key);
    if (!thumb.isEmpty()) host.loadArt(view, thumb, width);
  }

  private static String itemDescription(JSONObject item) {
    String section = QueueDetail.section(item);
    return (item.optInt("position") + 1)
        + ". "
        + QueueDetail.spoken(item)
        + ". "
        + QueueDetail.state(item)
        + (section == null || QueueDetail.state(item).equals(section) ? "" : ". " + section);
  }

  /** The state badge: words and an icon, so watched, in progress and next need no color. */
  private TextView queueBadge(JSONObject item) {
    String state = item.optString("state");
    boolean isNext = item.optBoolean("isNext"), isWatched = state.equals("watched");
    String label = QueueDetail.state(item);
    int icon = 0;
    if (isWatched) icon = R.drawable.lucide_circle_check;
    else if (isNext) icon = R.drawable.lucide_play;
    else if (state.equals("in_progress") || QueueDetail.section(item) != null)
      icon = R.drawable.lucide_clock;
    else if (item.optBoolean("isSpecial")) icon = R.drawable.lucide_sparkles;
    TextView badge =
        host.ui.text(
            label, 13, isNext ? TvTheme.ACCENT_ON_SOLID : isWatched ? TvUi.MUTED : TvUi.WHITE);
    badge.setPadding(host.dp(10), host.dp(6), host.dp(10), host.dp(6));
    badge.setBackground(host.ui.shape(isNext ? TvTheme.ACCENT_SOLID : TvTheme.NEUTRAL_SURFACE, 0, 7));
    badge.setSingleLine(true);
    if (icon != 0) host.ui.withIcon(badge, icon);
    badge.setTag("queue-state");
    return badge;
  }

  private View progressBar(float fraction) {
    LinearLayout track = host.ui.row();
    track.setBackground(host.ui.shape(TvTheme.BORDER_DEFAULT, 0, 2));
    View fill = new View(host);
    fill.setBackground(host.ui.shape(TvUi.ACCENT, 0, 2));
    track.addView(fill, new LinearLayout.LayoutParams(0, -1, fraction));
    track.addView(new View(host), new LinearLayout.LayoutParams(0, -1, 1 - fraction));
    track.setLayoutParams(new LinearLayout.LayoutParams(-1, host.dp(4)));
    return track;
  }

  /** 1A: one row per item, with a small square-cornered picture. */
  private View queueRow(JSONObject item, Runnable open) {
    boolean isWatched = item.optString("state").equals("watched");
    boolean isNext = item.optBoolean("isNext");
    LinearLayout row = host.ui.row();
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(host.dp(12), host.dp(10), host.dp(16), host.dp(10));
    TextView number = host.ui.text(String.valueOf(item.optInt("position") + 1), 14, TvUi.MUTED);
    number.setGravity(Gravity.CENTER);
    row.addView(number, new LinearLayout.LayoutParams(host.dp(40), -2));
    ImageView image = new ImageView(host);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackgroundColor(TvTheme.SURFACE_SUNKEN);
    LinearLayout.LayoutParams imageLayout = new LinearLayout.LayoutParams(host.dp(112), host.dp(63));
    imageLayout.rightMargin = host.dp(16);
    row.addView(image, imageLayout);
    loadQueueArt(image, item, 240);
    LinearLayout labels = host.ui.column();
    TextView title = host.ui.title(QueueDetail.rowTitle(item), 17);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(title);
    host.ui.gap(labels, 6);
    TextView detail = host.ui.text(QueueDetail.detail(item), 13, TvUi.MUTED);
    detail.setSingleLine(true);
    detail.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(detail);
    float progress = QueueDetail.progress(item);
    if (progress >= 0) {
      host.ui.gap(labels, 8);
      View bar = progressBar(progress);
      ((LinearLayout.LayoutParams) bar.getLayoutParams()).width = host.dp(220);
      labels.addView(bar);
    }
    LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(0, -2, 1);
    labelLayout.rightMargin = host.dp(14);
    row.addView(labels, labelLayout);
    row.addView(queueBadge(item));
    if (isWatched) {
      image.setAlpha(.5f);
      labels.setAlpha(.6f);
    }
    host.ui.focusable(row, open, isNext ? TvTheme.ACCENT_SURFACE : TvUi.PANEL);
    row.setContentDescription(itemDescription(item));
    row.setTag("queue-item");
    return row;
  }

  /** 1B: a picture card per item; watched cards stay in place, dimmed. */
  private View queueTile(JSONObject item, int width, Runnable open) {
    boolean isWatched = item.optString("state").equals("watched");
    boolean isMovie = !"episode".equals(item.optString("type"));
    LinearLayout card = host.ui.column();
    card.setPadding(host.dp(3), host.dp(3), host.dp(3), host.dp(10));
    FrameLayout frame = new FrameLayout(host);
    frame.setBackgroundColor(TvUi.PANEL);
    ImageView image = new ImageView(host);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
    loadQueueArt(image, item, 720);
    TextView number = host.ui.text(String.valueOf(item.optInt("position") + 1), 12, TvUi.WHITE);
    number.setPadding(host.dp(7), host.dp(4), host.dp(7), host.dp(4));
    number.setBackground(host.ui.shape(TvTheme.ART_LABEL, 0, 5));
    FrameLayout.LayoutParams numberLayout =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    numberLayout.setMargins(host.dp(8), host.dp(8), 0, 0);
    frame.addView(number, numberLayout);
    card.addView(frame, new LinearLayout.LayoutParams(-1, host.dp(isMovie ? width * 3 / 2 : width * 9 / 16)));
    float progress = QueueDetail.progress(item);
    if (progress >= 0) {
      host.ui.gap(card, 6);
      card.addView(progressBar(progress));
    }
    host.ui.gap(card, 10);
    LinearLayout labels = host.ui.column();
    TextView title = host.ui.title(QueueDetail.rowTitle(item), 16);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(title);
    host.ui.gap(labels, 5);
    TextView detail = host.ui.text(QueueDetail.detail(item), 12, TvUi.MUTED);
    detail.setMaxLines(2);
    detail.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(detail);
    host.ui.gap(labels, 8);
    LinearLayout badgeRow = host.ui.row();
    badgeRow.addView(queueBadge(item));
    labels.addView(badgeRow);
    card.addView(labels);
    if (isWatched) {
      frame.setAlpha(.5f);
      labels.setAlpha(.6f);
    }
    host.ui.focusable(card, open, TvUi.BG, 0);
    card.setContentDescription(itemDescription(item));
    card.setTag("queue-item");
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(width), -2);
    lp.rightMargin = host.dp(14);
    card.setLayoutParams(lp);
    return card;
  }
}
