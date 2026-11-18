package dev.queuepilot.player;

import android.app.Activity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * What a screen part needs from the activity, so a part can live in its own small file. Every
 * method runs on the main thread except {@link #background}'s work.
 */
interface ScreenHost {
  /**
   * A view's identity for focus memory, set as its tag when its spoken name may repeat (two
   * extras both called "Trailer"). Without it, focus memory goes by the spoken name or text.
   */
  record FocusKey(String value) {}

  Activity activity();

  TvUi ui();

  /** The screen's request number; a result started under an older one is dropped. */
  int generation();

  /** The chosen profile's server origin and token, and its name. */
  String server();

  String token();

  String profileName();

  /** Runs network work off the main thread. */
  void background(Runnable work);

  /** Opens a library listing (a show's seasons, a season's episodes, a collection). */
  void openLibrary(String path, String title);

  void message(String title, String text);

  TvPanel.Builder panel();

  void loadArt(ImageView view, String thumb, int width);

  /** A sideways-scrolling row added to {@code parent}. */
  LinearLayout horizontalIn(LinearLayout parent);

  /** The screen's status line. */
  void status(String text);

  void fillDetailFacts(LinearLayout facts, PlexCatalog.Entry entry);

  /** Plays an extra and returns to this screen with focus on {@code from}. */
  void playExtra(PlexCatalog.Entry extra, View from);

  /** The view the screen on show remembered focusing before it was left, or null. */
  View rememberedView();

  /** True, once, when the screen on show was reopened because its title ended. */
  boolean takeExtrasFocus();

  /** A library poster card that opens its title, as the grids draw them. */
  View poster(PlexCatalog.Entry entry);

  /** The width of a poster card in dp at the current Display size. */
  int posterWidth();
}
