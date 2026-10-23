package dev.queuepilot.player;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Labels for the full queue ({@code GET /api/native/queues/:setId/full}) and the watch history
 * ({@code GET /api/native/history}). The server decides what a queue contains, its order, its
 * layout and each item's state; this class only words what it sent.
 */
final class QueueDetail {
  private QueueDetail() {}

  /** Counts over the whole queue, and the time left in everything not yet watched. */
  record Summary(int items, int watched, int inProgress, int lineup, long remainingMs) {}

  static Summary summarize(JSONObject full) {
    int items = 0, watched = 0, inProgress = 0;
    long remaining = 0;
    JSONArray entries = full.optJSONArray("entries");
    for (int e = 0; entries != null && e < entries.length(); e++) {
      JSONArray list = entries.optJSONObject(e).optJSONArray("items");
      for (int i = 0; list != null && i < list.length(); i++) {
        JSONObject item = list.optJSONObject(i);
        items++;
        String state = item.optString("state");
        long duration = Math.max(0, item.optLong("durationMs", 0));
        if (state.equals("watched")) watched++;
        else if (state.equals("in_progress")) {
          inProgress++;
          remaining += Math.max(0, duration - Math.max(0, item.optLong("offsetMs", 0)));
        } else remaining += duration;
      }
    }
    JSONObject lineup = full.optJSONObject("lineup");
    return new Summary(
        items, watched, inProgress, lineup == null ? 0 : lineup.optInt("count"), remaining);
  }

  /** "39 items · 8 watched · about 17 h 4 min left". */
  static String summaryLine(Summary summary) {
    StringBuilder line = new StringBuilder(count(summary.items(), "item"));
    line.append(" · ").append(summary.watched()).append(" watched");
    if (summary.remainingMs() > 0)
      line.append(" · about ").append(duration(summary.remainingMs())).append(" left");
    return line.toString();
  }

  static String count(int value, String noun) {
    return value + " " + noun + (value == 1 ? "" : "s");
  }

  /** "24 min", "1 h 52 min", "2 h". Under a minute reads "1 min" rather than nothing. */
  static String duration(long milliseconds) {
    long minutes = Math.max(1, Math.round(milliseconds / 60000.0));
    if (minutes < 60) return minutes + " min";
    long hours = minutes / 60, rest = minutes % 60;
    return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
  }

  /** "41:10", "1:02:05": a section boundary inside an item. */
  static String clock(long milliseconds) {
    long seconds = Math.max(0, milliseconds / 1000);
    long hours = seconds / 3600, minutes = seconds / 60 % 60, rest = seconds % 60;
    return hours > 0
        ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
        : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
  }

  /** A string field, with JSON null and a missing key both read as empty. */
  static String text(JSONObject value, String key) {
    return value == null || value.isNull(key) ? "" : value.optString(key);
  }

  static int season(JSONObject item) {
    return item.isNull("season") ? EpisodeLabel.UNKNOWN : item.optInt("season", -1);
  }

  static int episode(JSONObject item) {
    return item.isNull("episode") ? 0 : item.optInt("episode", 0);
  }

  static String title(JSONObject item) {
    return item.isNull("title") ? "" : item.optString("title");
  }

  static String show(JSONObject item) {
    return item.isNull("show") ? "" : item.optString("show");
  }

  /** The large line: the show for an episode, otherwise the item's own title. */
  static String primary(JSONObject item) {
    String value = EpisodeLabel.primary(item.optString("type"), title(item), show(item));
    return value.isEmpty() ? "Untitled item" : value;
  }

  /** "S1 · E9 · The signal" for an episode; empty for a movie. */
  static String secondary(JSONObject item) {
    return EpisodeLabel.secondary(item.optString("type"), season(item), episode(item), title(item));
  }

  static String spoken(JSONObject item) {
    return EpisodeLabel.spoken(
        item.optString("type"), title(item), show(item), season(item), episode(item));
  }

  /**
   * The large line of a full-queue row. The entry heading above already names the show, so an
   * episode leads with its own title, as the selected design does; a movie with its title.
   */
  static String rowTitle(JSONObject item) {
    String title = title(item);
    return title.isEmpty() ? primary(item) : title;
  }

  /** The line under it: "Orbit Nine · S1 · E9 · 24 min", "Special · 18 min", or "1 h 52 min". */
  static String detail(JSONObject item) {
    StringBuilder line = new StringBuilder();
    if ("episode".equals(item.optString("type"))) {
      append(line, show(item));
      append(line, EpisodeLabel.secondary("episode", season(item), episode(item), null));
    }
    long length = item.optLong("durationMs", 0);
    if (length > 0) append(line, duration(length));
    return line.toString();
  }

  private static void append(StringBuilder line, String part) {
    if (part == null || part.isEmpty()) return;
    if (line.length() > 0) line.append(" · ");
    line.append(part);
  }

  /** The section window an authored entry plays, or null when the item plays whole. */
  static String section(JSONObject item) {
    JSONObject section = item.optJSONObject("section");
    if (section == null) return null;
    boolean hasStart = !section.isNull("startMs"), hasEnd = !section.isNull("endMs");
    if (!hasStart && !hasEnd) return null;
    String start = hasStart ? clock(section.optLong("startMs")) : "the start";
    String end = hasEnd ? clock(section.optLong("endMs")) : "the end";
    return "Section " + start + " to " + end;
  }

  /** The badge on a full-queue item: its state, in words that need no color to read. */
  static String state(JSONObject item) {
    String state = item.optString("state");
    if (state.equals("watched")) return "Watched";
    long duration = item.optLong("durationMs", 0), offset = item.optLong("offsetMs", 0);
    String left =
        state.equals("in_progress") && duration > offset && offset > 0
            ? duration(duration - offset) + " left"
            : "";
    if (item.optBoolean("isNext")) return left.isEmpty() ? "Next" : "Next · " + left;
    if (!left.isEmpty()) return "In progress · " + left;
    String section = section(item);
    if (section != null) return section;
    if (item.optBoolean("isSpecial")) return "Special";
    return "Not watched";
  }

  /** Progress through an in-progress item, 0 to 1, or -1 when there is none to draw. */
  static float progress(JSONObject item) {
    if (!item.optString("state").equals("in_progress")) return -1;
    long duration = item.optLong("durationMs", 0), offset = item.optLong("offsetMs", 0);
    if (duration <= 0 || offset <= 0) return -1;
    return Math.min(1f, offset / (float) duration);
  }

  /**
   * Why a queue cannot start from a chosen item, or null when it can. A Rules queue has no stored
   * order, so the server refuses a start (409) and the TV says so instead of offering it.
   */
  static String startUnavailable(JSONObject full) {
    if (!"rules".equals(full.optString("kind"))) return null;
    return "A Rules queue chooses its own order each time, so it always starts from the top.";
  }

  /** Why an item cannot be marked here, or null when it can. */
  static String markUnavailable(JSONObject item) {
    if (item.optBoolean("canMarkWatched")) return null;
    return "This item's watched state is kept by its media server, so it can't be changed here.";
  }

  /** The heading note beside an entry: "Entry 2 of 5 · Priority · Shuffled". */
  static String entryNote(JSONObject entry, int index, int total) {
    StringBuilder note = new StringBuilder();
    if (!"lineup".equals(entry.optString("type")))
      note.append("Entry ").append(index + 1).append(" of ").append(total);
    if ("priority".equals(entry.optString("lane"))) append(note, "Priority");
    if (entry.optBoolean("isShuffled")) append(note, "Shuffled");
    if (entry.optBoolean("isUnresolved")) append(note, "Not found on the server");
    return note.toString();
  }

  /** The note under "Tonight's lineup ends here". */
  static String lineupNote(JSONObject full, long lineupMs) {
    JSONObject lineup = full.optJSONObject("lineup");
    int count = lineup == null ? 0 : lineup.optInt("count");
    String line = count(count, "item");
    if (lineupMs > 0) line += " · about " + duration(lineupMs);
    line += ". When they end, QueuePilot picks the next window.";
    if (full.optBoolean("isLineupShuffled"))
      line += " This queue plays in random order, so another sitting can draw different items.";
    return line;
  }

  // History

  /** "Today", "Yesterday", "Tuesday, October 20", or with the year when it is not this year. */
  static String day(long epochMs, long nowMs, ZoneId zone) {
    LocalDate date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate();
    LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
    if (date.equals(today)) return "Today";
    if (date.equals(today.minusDays(1))) return "Yesterday";
    String pattern = date.getYear() == today.getYear() ? "EEEE, MMMM d" : "EEEE, MMMM d, yyyy";
    return DateTimeFormatter.ofPattern(pattern, Locale.US).format(date);
  }

  /** "today", "yesterday", or "Tuesday, October 20": mid-sentence after "last watched". */
  static String lastWatched(long epochMs, long nowMs, ZoneId zone) {
    String day = day(epochMs, nowMs, zone);
    return day.equals("Today") || day.equals("Yesterday") ? day.toLowerCase(Locale.US) : day;
  }

  /** "7:42 PM". */
  static String time(long epochMs, ZoneId zone) {
    ZonedDateTime at = Instant.ofEpochMilli(epochMs).atZone(zone);
    return DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(at);
  }

  /**
   * "Today · 7:42 PM to 8:44 PM". A sitting that holds a play recorded before exact times were
   * kept says "about", because its times were estimated from the session.
   */
  static String sittingSpan(
      long startMs, long endMs, boolean isApproximate, long nowMs, ZoneId zone) {
    String about = isApproximate ? "about " : "";
    String span = day(startMs, nowMs, zone) + " · " + about + time(startMs, zone);
    if (endMs > startMs) span += " to " + time(endMs, zone);
    return span;
  }

  static boolean isApproximate(JSONObject sitting) {
    JSONArray items = sitting.optJSONArray("items");
    for (int i = 0; items != null && i < items.length(); i++)
      if (items.optJSONObject(i).optBoolean("isTimeApproximate")) return true;
    return false;
  }

  /** One watched item in a sitting: "S1 · E9 · The signal · 62%" or "The Last Beacon". */
  static String historyItem(JSONObject item) {
    String line = secondary(item);
    if (line.isEmpty()) line = primary(item);
    else if (!show(item).isEmpty()) line = show(item) + " · " + line;
    if (item.optString("state").equals("partial")) {
      long duration = item.optLong("durationMs", 0), position = item.optLong("positionMs", 0);
      if (duration > 0 && position > 0)
        line += " · " + Math.min(99, Math.round(position * 100f / duration)) + "%";
      else line += " · partly watched";
    }
    return line;
  }

  /** Where a history item was recorded, when it was not a play on this TV app. */
  static String source(JSONObject item) {
    return "queue-ledger".equals(item.optString("source")) ? "Recorded by the queue" : "";
  }

  /** "4 items · 58 min": the sitting's item count and its length from first start to last end. */
  static String sittingFooter(JSONObject sitting) {
    JSONArray items = sitting.optJSONArray("items");
    int count = items == null ? 0 : items.length();
    long length = sitting.optLong("endedAt") - sitting.optLong("startedAt");
    String footer = count(count, "item");
    if (length > 0) footer += " · " + duration(length);
    return footer;
  }

  static String days(int days) {
    return days == 1 ? "Last day" : "Last " + days + " days";
  }
}
