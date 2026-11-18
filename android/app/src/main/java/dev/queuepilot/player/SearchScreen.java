package dev.queuepilot.player;

import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Search: Plex's /hubs/search, from the rail for the whole library or from a library's header
 * for that library alone, by the on-screen keyboard or by voice, grouped into Movies, Shows,
 * and Episodes.
 */
@androidx.media3.common.util.UnstableApi
final class SearchScreen {
  private final MainActivity host;

  SearchScreen(MainActivity host) {
    this.host = host;
  }

  // Search: Plex's /hubs/search, grouped into Movies, Shows, and Episodes.

  /** The last query per search screen ("" for the whole library, or a section key). */
  private final Map<String, String> searchQueries = new HashMap<>();

  private int searchSequence;

  EditText searchField;

  private Runnable pendingSearch;

  private boolean isVoiceAvailable() {
    return !host.getPackageManager()
        .queryIntentActivities(
            new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        .isEmpty();
  }

  void search(Screen screen) {
    String section = screen.path(), sectionTitle = screen.title();
    host.shell("Search");
    host.heading(section.isEmpty() ? "Your library" : sectionTitle, "Search");
    if (host.profile == null) {
      host.status.setText("Sign in to search your library.");
      return;
    }
    host.status.setText(
        section.isEmpty() ? "Movies, shows, and episodes" : "Titles in " + sectionTitle);
    LinearLayout bar = host.ui.row();
    bar.setGravity(Gravity.CENTER_VERTICAL);
    EditText field = new EditText(host);
    searchField = field;
    field.setSingleLine(true);
    field.setInputType(InputType.TYPE_CLASS_TEXT);
    // The keyboard stays below the field, so the results above it stay in view.
    field.setImeOptions(
        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
    field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TvUi.typeSize(18) * host.ui.scale);
    field.setTextColor(TvUi.WHITE);
    field.setHintTextColor(TvUi.MUTED);
    field.setHint("Search titles");
    field.setContentDescription("Search titles");
    field.setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(12));
    host.ui.withIcon(field, R.drawable.lucide_search);
    field.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(TvUi.MUTED));
    field.setBackground(host.ui.shape(TvUi.PANEL, 0, 8));
    field.setOnFocusChangeListener(
        (v, focused) -> v.setBackground(host.ui.shape(TvUi.PANEL, focused ? TvUi.ACCENT : 0, 8)));
    bar.addView(field, new LinearLayout.LayoutParams(host.dp(560), -2));
    if (isVoiceAvailable()) {
      TextView mic =
          host.ui.button(
              "Voice",
              R.drawable.lucide_mic,
              () -> {
                try {
                  host.voice.launch(
                      new android.content.Intent(
                              android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                          .putExtra(
                              android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                              android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                          .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a title"));
                } catch (Exception unavailable) {
                  host.message("Search by voice", "Voice search isn't available on this device.");
                }
              },
              false);
      mic.setContentDescription("Search by voice");
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.leftMargin = host.dp(12);
      bar.addView(mic, lp);
    }
    host.content.addView(bar);
    host.ui.gap(host.content, 10);
    LinearLayout results = host.ui.column();
    host.content.addView(results);
    // Back from a result draws the same results again and focuses the result that was opened.
    boolean[] isRestoring = {
      host.focusMemory.containsKey(host.current) && !searchQueries.getOrDefault(section, "").isEmpty()
    };
    android.text.TextWatcher watcher =
        new android.text.TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence t, int a, int b, int c) {}

          @Override
          public void onTextChanged(CharSequence t, int a, int b, int c) {}

          @Override
          public void afterTextChanged(android.text.Editable text) {
            if (pendingSearch != null) host.handler.removeCallbacks(pendingSearch);
            String query = text.toString();
            pendingSearch = () -> runSearch(section, query, results, isRestoring);
            host.handler.postDelayed(pendingSearch, 450);
          }
        };
    field.setOnEditorActionListener(
        (v, action, event) -> {
          boolean isSearch =
              action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                  || (event != null
                      && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                      && event.getAction() == KeyEvent.ACTION_DOWN);
          if (!isSearch) return false;
          if (pendingSearch != null) host.handler.removeCallbacks(pendingSearch);
          runSearch(section, field.getText().toString(), results, isRestoring);
          ((android.view.inputmethod.InputMethodManager) host.getSystemService(MainActivity.INPUT_METHOD_SERVICE))
              .hideSoftInputFromWindow(field.getWindowToken(), 0);
          return true;
        });
    String saved = searchQueries.getOrDefault(section, "");
    if (!saved.isEmpty()) {
      field.setText(saved);
      field.setSelection(saved.length());
      runSearch(section, saved, results, isRestoring);
    }
    field.addTextChangedListener(watcher);
    if (!isRestoring[0]) host.focusFirst(field);
  }

  private static final String[][] SEARCH_GROUPS = {
    {"movie", "Movies"}, {"show", "Shows"}, {"episode", "Episodes"},
  };

  private void runSearch(String section, String query, LinearLayout results, boolean[] isRestoring) {
    String trimmed = query.trim();
    searchQueries.put(section, trimmed);
    int sequence = ++searchSequence, request = host.generation;
    if (trimmed.isEmpty()) {
      results.removeAllViews();
      host.status.setText(section.isEmpty() ? "Movies, shows, and episodes" : "Titles in this library");
      return;
    }
    host.status.setText("Searching for “" + trimmed + "”…");
    String origin = host.server(), secret = host.token(), id = host.activeId;
    java.util.function.Predicate<String> included = host.includedLibraries();
    // All Movies and Shows searches each included library and merges the results.
    boolean isAll = section.equals(AllLibraries.SEARCH_SECTION);
    String path =
        "/hubs/search?query="
            + PlexAccount.encode(trimmed)
            + "&limit=30"
            + (section.isEmpty() ? "" : "&sectionId=" + PlexAccount.encode(section));
    host.network.execute(
        () -> {
          try {
            JSONArray found =
                isAll
                    ? host.allLibraries.search(
                        origin,
                        secret,
                        id,
                        trimmed,
                        included,
                        () -> request == host.generation && sequence == searchSequence)
                    : PlexCatalog.hubs(
                        PlexCatalog.get(PlexCatalog.url(origin, path), secret, 16 * 1024 * 1024));
            host.runOnUiThread(
                () -> {
                  if (request != host.generation || sequence != searchSequence) return;
                  View first = drawSearchResults(found, results);
                  int count = 0;
                  for (int i = 0; i < found.length(); i++)
                    for (String[] group : SEARCH_GROUPS)
                      if (group[0].equals(found.optJSONObject(i).optString("hub"))) count++;
                  host.status.setText(
                      count == 0
                          ? "No titles match “" + trimmed + "”."
                          : count + (count == 1 ? " result" : " results") + " for “" + trimmed + "”");
                  if (isRestoring[0] && first != null) host.focusFirst(first);
                  isRestoring[0] = false;
                });
          } catch (Exception e) {
            host.runOnUiThread(
                () -> {
                  if (request != host.generation || sequence != searchSequence) return;
                  results.removeAllViews();
                  host.status.setText("Search is unavailable while your server can't be reached.");
                  isRestoring[0] = false;
                });
          }
        });
  }

  /** Movies, then Shows, then Episodes, each a row of posters. Returns the first poster. */
  private View drawSearchResults(JSONArray found, LinearLayout results) {
    results.removeAllViews();
    View first = null;
    for (String[] group : SEARCH_GROUPS) {
      List<PlexCatalog.Entry> entries = new ArrayList<>();
      for (int i = 0; i < found.length(); i++) {
        JSONObject item = found.optJSONObject(i);
        if (item != null && group[0].equals(item.optString("hub")))
          entries.add(PlexCatalog.Entry.fromJson(item));
      }
      if (entries.isEmpty()) continue;
      host.ui.gap(results, 18);
      TextView heading = host.ui.title(group[1], 21);
      heading.setTag("search-group");
      results.addView(heading);
      host.ui.gap(results, 12);
      LinearLayout row = host.horizontalIn(results);
      for (PlexCatalog.Entry entry : entries) {
        View card = host.poster(entry, -1);
        row.addView(card);
        if (first == null) first = card;
      }
    }
    return first;
  }
}
