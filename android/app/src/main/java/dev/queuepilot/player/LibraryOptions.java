package dev.queuepilot.player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A library's own options, kept per library and profile like its sort: whether it is viewed as
 * Titles or as Folders, and whether All Movies and Shows includes it. A library viewed as Folders
 * is left out of All Movies and Shows until it is included by hand, so demo and other
 * folder-organized content stays separate without naming any library.
 */
final class LibraryOptions {
  private LibraryOptions() {}

  static final String TITLES = "titles", FOLDERS = "folders";

  /** The stored-view key, beside the sort's {@code library-sort:} key. */
  static String viewKey(String profile, String server, String section) {
    return "library-view:" + profile + ":" + server + ":" + section;
  }

  static String includeKey(String profile, String server, String section) {
    return "library-all:" + profile + ":" + server + ":" + section;
  }

  /** A stored view, or Titles for one this version does not know. */
  static String view(String stored) {
    return FOLDERS.equals(stored) ? FOLDERS : TITLES;
  }

  static boolean isFolders(String stored) {
    return FOLDERS.equals(view(stored));
  }

  /**
   * Whether All Movies and Shows includes a library: the stored choice ("1" or "0") when there is
   * one, otherwise yes for Titles and no for Folders.
   */
  static boolean isIncluded(String storedInclude, String storedView) {
    if ("1".equals(storedInclude)) return true;
    if ("0".equals(storedInclude)) return false;
    return !isFolders(storedView);
  }

  /**
   * What to store for an Include in All choice: nothing when it equals the view's default, so the
   * default keeps following the view (a library switched to Folders later still leaves All unless
   * included by hand), otherwise "1" or "0".
   */
  static String storedInclude(boolean isIncluded, boolean isFolders) {
    if (isIncluded == !isFolders) return "";
    return isIncluded ? "1" : "0";
  }

  /** A library's folder listing, from its top. */
  static String folderRoot(String section) {
    return section.matches("[0-9]+") ? "/library/sections/" + section + "/folder" : "";
  }

  private static final Pattern FOLDER = Pattern.compile("/library/sections/(\\d+)/folder(\\?.*)?");

  /** True for a folder listing at any depth. */
  static boolean isFolderPath(String path) {
    return FOLDER.matcher(path).matches();
  }

  /** The library a folder listing belongs to, or empty. */
  static String folderSection(String path) {
    Matcher m = FOLDER.matcher(path);
    return m.matches() ? m.group(1) : "";
  }

  /** "View as · Folders", the options panel's view row. */
  static String viewLabel(String stored) {
    return "View as · " + (isFolders(stored) ? "Folders" : "Titles");
  }

  /** "In All Movies and Shows · Included", the options panel's include row. */
  static String includeLabel(boolean isIncluded) {
    return "In " + AllLibraries.TITLE + " · " + (isIncluded ? "Included" : "Not included");
  }
}
