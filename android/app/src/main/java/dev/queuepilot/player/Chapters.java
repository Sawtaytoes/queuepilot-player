package dev.queuepilot.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A title's chapters, from Plex metadata requested with {@code includeChapters=1}. Each
 * {@code <Chapter>} carries {@code tag}, {@code index}, {@code startTimeOffset}, {@code
 * endTimeOffset}, and, when the server has made chapter images, {@code thumb}.
 */
final class Chapters {
  record Chapter(String title, int index, long startMs, long endMs, String thumb) {
    JSONObject json() throws Exception {
      return new JSONObject()
          .put("title", title)
          .put("index", index)
          .put("start", startMs)
          .put("end", endMs)
          .put("thumb", thumb);
    }
  }

  private Chapters() {}

  /** The chapters in time order; an untitled chapter is named by its number. */
  static List<Chapter> fromJson(JSONArray array) {
    List<Chapter> list = new ArrayList<>();
    for (int i = 0; array != null && i < array.length(); i++) {
      JSONObject j = array.optJSONObject(i);
      if (j == null || j.optLong("start", -1) < 0) continue;
      int index = j.optInt("index", list.size() + 1);
      String title = j.optString("title").trim();
      list.add(
          new Chapter(
              title.isEmpty() ? "Chapter " + index : title,
              index,
              j.optLong("start"),
              j.optLong("end", -1),
              j.optString("thumb")));
    }
    list.sort(Comparator.comparingLong(Chapter::startMs));
    return list;
  }

  /** The chapter playing at {@code positionMs}: the last one that starts at or before it; -1 before the first. */
  static int current(List<Chapter> chapters, long positionMs) {
    int found = -1;
    for (int i = 0; i < chapters.size(); i++)
      if (chapters.get(i).startMs() <= positionMs) found = i;
    return found;
  }

  /**
   * Where choosing a chapter seeks: its start, kept inside a queue's authored section ({@code
   * floorMs} to {@code endMs}, where an end below 0 means none).
   */
  static long seekTarget(long startMs, long floorMs, long endMs) {
    long target = Math.max(Math.max(0, floorMs), startMs);
    return endMs >= 0 ? Math.min(target, endMs) : target;
  }

  /** "12:34 · Opening" for a chapter row. */
  static String label(Chapter chapter) {
    return ResumeChoice.time(chapter.startMs()) + " · " + chapter.title();
  }
}
