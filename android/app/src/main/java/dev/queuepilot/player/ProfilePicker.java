package dev.queuepilot.player;

import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Who's watching: the Plex Home profile picker with its PIN pad, and server discovery for the
 * chosen profile, which connects directly to the best server address.
 */
@androidx.media3.common.util.UnstableApi
final class ProfilePicker {
  private final MainActivity host;

  ProfilePicker(MainActivity host) {
    this.host = host;
  }

  /**
   * After sign-in, opens the signed-in account's own Plex profile without a choice: queues play
   * as the profile each queue names, so nobody has to pick a profile to reach them. The picker
   * stays one press away on the rail avatar, for library browsing, search, and single titles,
   * which use the chosen profile. A PIN-protected own profile still shows the picker.
   */
  void openOwnProfile() {
    JSONObject own = null;
    JSONArray users = host.account.optJSONArray("users");
    String uuid = host.account.optString("uuid");
    for (int i = 0; users != null && i < users.length(); i++) {
      JSONObject user = users.optJSONObject(i);
      if (user == null) continue;
      boolean isOwn = uuid.isEmpty() ? user.optBoolean("admin") : uuid.equals(user.optString("uuid"));
      if (isOwn) own = user;
    }
    if (own == null || own.optBoolean("protected")) host.render(new Screen("profiles"));
    else selectUser(own);
  }

  private static String profileBadge(JSONObject user) {
    if (user.optBoolean("protected")) return "🔒 PIN";
    return user.optBoolean("admin") ? "Owner" : "";
  }

  void chooseProfile() {
    host.shell("");
    host.heading("", "Who's watching?");
    host.status.setText("Choose a profile to use its library access.");
    LinearLayout row = host.horizontal();
    JSONArray users = host.account.optJSONArray("users");
    if (users != null)
      for (int i = 0; i < users.length(); i++) {
        JSONObject user = users.optJSONObject(i);
        if (user == null) continue;
        String name = user.optString("title", user.optString("username", "Profile"));
        // Only what differs between profiles: a PIN lock, or the account owner.
        LinearLayout profileCard =
            host.ui.card(
                profileBadge(user),
                name,
                "",
                "Choose profile",
                () -> selectUser(user),
                220);
        String badge = profileBadge(user);
        profileCard.setContentDescription(
            name + (badge.isEmpty() ? "" : ". " + badge.replace("🔒 ", "")) + ". Choose profile");
        ImageView face = host.avatar(70);
        host.loadProfileAvatar(face, user.optString("thumb"));
        profileCard.addView(face, 0);
        row.addView(profileCard);
      }
    else
      for (int i = 0; i < host.profiles.length(); i++) {
        JSONObject p = host.profiles.optJSONObject(i);
        row.addView(
            host.ui.card(
                "",
                p.optString("name"),
                "Available on this device.",
                "Choose profile",
                () -> {
                  host.setProfile(p.optString("id"));
                  host.home();
                },
                220));
      }
    if (host.account.optString("token").isEmpty()) {
      host.section("Plex account");
      host.content.addView(host.ui.button("Sign in with Plex", () -> host.navigate(new Screen("signin")), true));
    }
    if (host.prefs().getBoolean("preview", false)) {
      host.section("Demo profiles");
      LinearLayout demos = host.horizontal();
      for (String name : List.of("Ada", "Grace"))
        demos.addView(
            host.ui.card(
                "Fictional profile",
                name,
                "Separate demo viewing progress.",
                "Choose demo profile",
                () -> {
                  host.setProfile("demo-" + name.toLowerCase(Locale.ROOT));
                  host.home();
                },
                220));
    }
    if (row.getChildCount() > 0) host.focusFirst(row.getChildAt(0));
  }

  String userId(JSONObject user) {
    return user.optString("uuid", user.optString("id"));
  }

  private void selectUser(JSONObject user) {
    String id = userId(user);
    if (!user.optBoolean("protected"))
      for (int i = 0; i < host.profiles.length(); i++) {
        JSONObject p = host.profiles.optJSONObject(i);
        if (p != null && p.optString("id").equals(id)) {
          host.setProfile(id);
          host.home();
          return;
        }
      }
    if (user.optBoolean("protected")) {
      LinearLayout form = host.ui.column();
      form.setPadding(host.dp(24), host.dp(8), host.dp(24), host.dp(16));
      StringBuilder pin = new StringBuilder();
      TextView dots = host.ui.title("○  ○  ○  ○", 30);
      dots.setGravity(Gravity.CENTER);
      form.addView(dots);
      host.ui.gap(form, 18);
      TvPanel dialog =
          host.panel()
              .setTitle("Unlock " + user.optString("title", "profile"))
              .setMessage("Enter this profile's four-digit Plex Home PIN.")
              .setView(form)
              .setPositiveButton("Continue", null)
              .setNegativeButton("Cancel", null)
              .create();
      Runnable update =
          () -> {
            StringBuilder display = new StringBuilder();
            for (int i = 0; i < 4; i++) display.append(i < pin.length() ? "●  " : "○  ");
            dots.setText(display.toString().trim());
            dialog.getButton(TvPanel.BUTTON_POSITIVE).setEnabled(pin.length() == 4);
          };
      GridLayout keypad = new GridLayout(host);
      keypad.setColumnCount(3);
      LinearLayout.LayoutParams padLayout = new LinearLayout.LayoutParams(-2, -2);
      padLayout.gravity = Gravity.CENTER_HORIZONTAL;
      form.addView(keypad, padLayout);
      for (String key :
          List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "Delete", "0", "Clear")) {
        TextView button =
            host.ui.button(
                key,
                () -> {
                  if (key.equals("Delete")) {
                    if (pin.length() > 0) pin.deleteCharAt(pin.length() - 1);
                  } else if (key.equals("Clear")) pin.setLength(0);
                  else if (pin.length() < 4) pin.append(key);
                  update.run();
                },
                false);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = host.dp(90);
        lp.height = host.dp(48);
        lp.setMargins(host.dp(4), host.dp(4), host.dp(4), host.dp(4));
        keypad.addView(button, lp);
      }
      dialog.setOnShowListener(
          v -> {
            dialog.setPanelSize(host.dp(440), -2);
            dialog.getButton(TvPanel.BUTTON_POSITIVE).setEnabled(false);
            dialog
                .getButton(TvPanel.BUTTON_POSITIVE)
                .setOnClickListener(
                    view -> {
                      if (pin.length() == 4) {
                        String value = pin.toString();
                        pin.setLength(0);
                        dialog.dismiss();
                        connectUser(user, value);
                      }
                    });
            keypad.getChildAt(0).requestFocus();
          });
      dialog.setOnKeyListener(
          (d, keyCode, event) -> {
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
              if (event.getAction() == KeyEvent.ACTION_UP && pin.length() < 4) {
                pin.append(keyCode - KeyEvent.KEYCODE_0);
                update.run();
              }
              return true;
            }
            return false;
          });
      dialog.show();
    } else connectUser(user, "");
  }

  private void connectUser(JSONObject user, String pin) {
    host.reset();
    host.shell("");
    host.heading("Plex Home", "Opening " + user.optString("title", "your profile"));
    host.status.setText("Finding your Plex servers…");
    int request = host.generation;
    host.network.execute(
        () -> {
          try {
            String userToken = host.auth.switchUser(host.account.getString("token"), userId(user), pin);
            JSONArray servers = host.auth.servers(userToken);
            for (int i = 0; i < servers.length(); i++)
              servers.getJSONObject(i).put("profileProof", userToken);
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  if (servers.length() == 0) {
                    host.status.setText("This profile has no Plex Media Servers available.");
                    host.content.addView(
                        host.ui.button(
                            "Choose another profile", () -> host.render(new Screen("profiles")), false));
                  } else if (servers.length() == 1) connectServer(user, servers.optJSONObject(0));
                  else {
                    host.status.setText("Choose a Plex server.");
                    LinearLayout row = host.horizontal();
                    for (int i = 0; i < servers.length(); i++) {
                      JSONObject s = servers.optJSONObject(i);
                      row.addView(
                          host.ui.card(
                              "Plex server",
                              s.optString("name"),
                              "",
                              "Connect",
                              () -> connectServer(user, s),
                              260));
                    }
                    host.focusFirst(row.getChildAt(0));
                  }
                });
          } catch (Exception e) {
            host.runOnUiThread(
                () -> {
                  if (request == host.generation) {
                    host.status.setText(
                        e instanceof PlexAccount.Failure
                                && Set.of(401, 403).contains(((PlexAccount.Failure) e).status)
                            ? "Plex couldn't unlock that profile. Check the PIN and try again."
                            : "Plex couldn't load this profile. Check your connection and try"
                                + " again.");
                    host.content.addView(
                        host.ui.button("Choose profile", () -> host.render(new Screen("profiles")), true));
                  }
                });
          }
        });
  }

  private void connectServer(JSONObject user, JSONObject selected) {
    host.reset();
    host.shell("");
    host.heading("Plex server", selected.optString("name", "Connecting"));
    host.status.setText("Connecting directly to your server…");
    int request = host.generation;
    host.network.execute(
        () -> {
          try {
            JSONArray connections = selected.optJSONArray("connections");
            List<JSONObject> candidates = new ArrayList<>();
            if (connections != null)
              for (int i = 0; i < connections.length(); i++) {
                JSONObject c = connections.optJSONObject(i);
                if (c != null
                    && ServerConnectionPolicy.allowed(
                        c.optString("uri"), c.optBoolean("local"), c.optBoolean("relay")))
                  candidates.add(c);
              }
            candidates.sort(
                Comparator.comparingInt(
                    c ->
                        (c.optBoolean("local") ? 0 : 2)
                            + (c.optString("uri").startsWith("https:") ? 0 : 1)));
            String secret = selected.getString("accessToken"), origin = "";
            for (JSONObject connection : candidates) {
              if (request != host.generation) return;
              try {
                String candidate = PlexCatalog.base(connection.getString("uri"));
                PlexCatalog.list(
                    PlexCatalog.get(candidate + "/library/sections", secret, 16 * 1024 * 1024));
                origin = candidate;
                break;
              } catch (Exception ignored) {
              }
            }
            if (origin.isEmpty()) throw new IllegalStateException("No direct connection");
            JSONObject p =
                new JSONObject()
                    .put("id", userId(user))
                    .put("name", user.optString("title", user.optString("username", "Profile")))
                    .put("server", origin)
                    .put("serverName", selected.optString("name"))
                    .put("serverId", selected.optString("clientIdentifier"))
                    .put("token", secret)
                    .put("profileProof", selected.optString("profileProof"))
                    .put("thumb", user.optString("thumb"));
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  try {
                    JSONArray updated = new JSONArray();
                    for (int i = 0; i < host.profiles.length(); i++) {
                      JSONObject existing = host.profiles.optJSONObject(i);
                      if (!existing.optString("id").equals(p.optString("id")))
                        updated.put(existing);
                    }
                    updated.put(p);
                    host.store.write(updated);
                    host.profiles = updated;
                    host.setProfile(p.optString("id"));
                    host.prefs().edit().putBoolean("preview", false).apply();
                    host.home();
                  } catch (Exception e) {
                    host.status.setText("Credentials could not be saved. Please try again.");
                  }
                });
          } catch (Exception e) {
            host.runOnUiThread(
                () -> {
                  if (request == host.generation) {
                    host.status.setText(
                        "A direct connection to this server isn't available. Check that the server"
                            + " is running and reachable.");
                    host.content.addView(
                        host.ui.button("Try again", () -> connectServer(user, selected), true));
                  }
                });
          }
        });
  }
}
