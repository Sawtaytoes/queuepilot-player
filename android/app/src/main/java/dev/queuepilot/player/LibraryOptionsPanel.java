package dev.queuepilot.player;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * A library's Sort and options side panel: the five sorts (not for Folders, which keep the
 * server's folder order), View as Titles or Folders, and whether All Movies and Shows includes it.
 * All Movies and Shows itself offers only the sorts. Every choice is kept for the library and
 * profile, and the library is drawn again from its top.
 */
final class LibraryOptionsPanel {
  private LibraryOptionsPanel() {}

  private static final int[] SORT_ICONS = {
    R.drawable.lucide_arrow_down_a_z,
    R.drawable.lucide_calendar_plus,
    R.drawable.lucide_calendar,
    R.drawable.lucide_star,
    R.drawable.lucide_history,
  };

  /** The keys a library's choices are stored under. */
  record Keys(String sort, String view, String include) {}

  static void show(
      ScreenHost host,
      SharedPreferences prefs,
      Keys keys,
      String title,
      boolean isAll,
      LibraryBrowse.Sort sort,
      Runnable redraw) {
    String view = prefs.getString(keys.view(), "");
    boolean isFolders = !isAll && LibraryOptions.isFolders(view);
    boolean isIncluded = LibraryOptions.isIncluded(prefs.getString(keys.include(), ""), view);
    List<String> labels = new ArrayList<>();
    List<Integer> icons = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    if (!isFolders)
      for (int i = 0; i < LibraryBrowse.SORTS.size(); i++) {
        LibraryBrowse.Sort chosen = LibraryBrowse.SORTS.get(i);
        labels.add(chosen.label());
        icons.add(SORT_ICONS[i]);
        actions.add(() -> prefs.edit().putString(keys.sort(), chosen.id()).apply());
      }
    if (!isAll) {
      labels.add(LibraryOptions.viewLabel(view));
      icons.add(isFolders ? R.drawable.lucide_library_big : R.drawable.lucide_folder);
      actions.add(
          () ->
              prefs
                  .edit()
                  .putString(
                      keys.view(), isFolders ? LibraryOptions.TITLES : LibraryOptions.FOLDERS)
                  .apply());
      labels.add(LibraryOptions.includeLabel(isIncluded));
      icons.add(R.drawable.lucide_layers);
      actions.add(
          () -> {
            String stored = LibraryOptions.storedInclude(!isIncluded, isFolders);
            if (stored.isEmpty()) prefs.edit().remove(keys.include()).apply();
            else prefs.edit().putString(keys.include(), stored).apply();
          });
    }
    int[] iconIds = new int[icons.size()];
    for (int i = 0; i < iconIds.length; i++) iconIds[i] = icons.get(i);
    TvPanel panel =
        host.panel()
            .setTitle((isFolders ? "Options for " : "Sort ") + title)
            .setSelected(isFolders || sort == null ? -1 : LibraryBrowse.indexOf(sort))
            .setItemIcons(iconIds)
            .setItems(
                labels.toArray(new String[0]),
                (d, i) -> {
                  actions.get(i).run();
                  redraw.run();
                })
            .create();
    panel.show();
    panel.setPanelSize(host.ui().dp(400), -2);
  }

  /** The library header's button: "Sort · Title", or "Options · Folders" for a folder view. */
  static String buttonLabel(boolean isFolders, LibraryBrowse.Sort sort) {
    return isFolders ? "Options · Folders" : "Sort · " + sort.label();
  }
}
