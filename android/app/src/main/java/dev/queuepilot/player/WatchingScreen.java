package dev.queuepilot.player;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Watching: every queue, with the people chooser that filters them by their audience. */
@androidx.media3.common.util.UnstableApi
final class WatchingScreen {
  private final MainActivity host;

  WatchingScreen(MainActivity host) {
    this.host = host;
  }

  void queues() {
    host.shell("Watching");
    host.heading("QueuePilot", "Your watching queues");
    String origin = host.prefs().getString("queue-origin", "");
    if (origin.isEmpty()) {
      host.status.setText("Connect your QueuePilot server to see your queues here.");
      host.content.addView(host.ui.button("Connect QueuePilot", host::queueSetup, true));
      return;
    }
    Set<String> selected = new HashSet<>(host.prefs().getStringSet("queue-people", Set.of()));
    host.ui.action(host.content, "Choose people", R.drawable.lucide_users, this::chooseQueuePeople, false);
    host.status.setText(selected.isEmpty() ? "All queues · loading…" : "Finding your queues…");
    LinearLayout area = host.ui.column();
    host.ui.gap(host.content, 16);
    host.content.addView(area);
    int request = host.generation;
    host.queueLoader.loadLiveQueueLists(
        origin,
        (all, shown) -> {
          if (request != host.generation) return;
          JSONArray queues = shown.optJSONArray("queues");
          host.status.setText(
              (selected.isEmpty() ? "All people" : selectedPeopleLabel(selected))
                  + " · "
                  + (queues == null ? 0 : queues.length())
                  + " queues");
          // A live answer after saved queues replaces them, keeping focus in the grid.
          View focused = host.getWindow().getDecorView().findFocus();
          boolean isFocusInside = focused != null && HomeScreen.isWithin(focused, area);
          area.removeAllViews();
          View first = host.queueCards.renderQueueGroups(area, all, shown, origin, false);
          if (isFocusInside && first != null) first.requestFocus();
        },
        error -> {
          if (request == host.generation)
            host.status.setText("QueuePilot couldn't load the TV queues. " + error.getMessage());
        });
  }

  private String selectedPeopleLabel(Set<String> ids) {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < host.queuePeople.length(); i++) {
      JSONObject p = host.queuePeople.optJSONObject(i);
      if (p != null && ids.contains(p.optString("id"))) names.add(p.optString("displayName"));
    }
    return String.join(" + ", names);
  }

  private void chooseQueuePeople() {
    Set<String> selected = new HashSet<>(host.prefs().getStringSet("queue-people", Set.of()));
    LinearLayout rows = host.ui.column();
    rows.setPadding(host.dp(20), host.dp(12), host.dp(20), host.dp(12));
    ScrollView scroll = new ScrollView(host);
    scroll.addView(rows);
    TvPanel dialog =
        host.panel()
            .setTitle("Who are these queues for?")
            .setMessage(
                "Select people to narrow the queues. This does not change the Plex playback"
                    + " profile.")
            .setView(scroll)
            .setPositiveButton(
                "Apply",
                (d, w) -> {
                  host.prefs().edit().putStringSet("queue-people", selected).apply();
                  host.render(new Screen("queues"));
                })
            .setNeutralButton(
                "All people",
                (d, w) -> {
                  host.prefs().edit().remove("queue-people").apply();
                  host.render(new Screen("queues"));
                })
            .setNegativeButton("Cancel", null)
            .create();
    for (int i = 0; i < host.queuePeople.length(); i++) {
      JSONObject person = host.queuePeople.optJSONObject(i);
      if (person == null) continue;
      String id = person.optString("id");
      LinearLayout row = host.ui.row();
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8));
      ImageView face = host.avatar(42);
      host.loadQueueAvatar(face, id, host.prefs().getString("queue-origin", ""));
      row.addView(face);
      TextView choice = host.ui.text(person.optString("displayName"), 16, TvUi.WHITE);
      choice.setPadding(host.dp(12), 0, host.dp(8), 0);
      row.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
      TextView check = host.ui.text(selected.contains(id) ? "✓" : "○", 20, TvUi.ACCENT);
      check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      row.addView(check);
      row.setContentDescription(person.optString("displayName"));
      row.setStateDescription(selected.contains(id) ? "Selected" : "Not selected");
      host.ui.focusable(
          row,
          () -> {
            if (!selected.add(id)) selected.remove(id);
            check.setText(selected.contains(id) ? "✓" : "○");
            row.setStateDescription(selected.contains(id) ? "Selected" : "Not selected");
          },
          TvUi.PANEL);
      rows.addView(row);
      host.ui.gap(rows, 6);
    }
    dialog.show();
    dialog.setPanelSize(host.dp(390), -2);
  }
}
