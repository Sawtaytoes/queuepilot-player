package dev.queuepilot.player;

import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The first screens: the welcome screen and Plex's device-link sign-in, which shows a code for
 * plex.tv/link and waits for the account to sign in.
 */
@androidx.media3.common.util.UnstableApi
final class SignInScreen {
  private final MainActivity host;

  SignInScreen(MainActivity host) {
    this.host = host;
  }

  void welcome() {
    host.centered(
        "QueuePilot TV",
        "Your people. Your queues.",
        "Connect your Plex library to start watching. Sign in on your phone with a short TV code.");
    LinearLayout actions = host.ui.row();
    host.content.addView(actions);
    host.ui.action(actions, "Sign in with Plex", () -> host.navigate(new Screen("signin")), true);
    host.ui.action(
        actions,
        "Explore the demo",
        () -> {
          host.prefs().edit().putBoolean("preview", true).apply();
          host.home();
        },
        false);
    host.focusFirst(actions.getChildAt(0));
  }

  void signIn() {
    host.centered(
        "Connect Plex",
        "Sign in on your phone",
        "Visit plex.tv/link in your phone or computer's browser, then enter this code.");
    TextView code = host.ui.title("…", 56);
    code.setLetterSpacing(.16f);
    host.content.addView(code);
    host.ui.gap(host.content, 16);
    TextView hint = host.ui.text("Getting your sign-in code…", 15, TvUi.MUTED);
    host.content.addView(hint);
    host.ui.gap(host.content, 24);
    LinearLayout actions = host.ui.row();
    host.content.addView(actions);
    host.ui.action(actions, "New code", () -> host.render(new Screen("signin")), false);
    host.ui.action(actions, "Back", host::back, false);
    host.focusFirst(actions.getChildAt(1));
    int request = host.generation;
    host.network.execute(
        () -> {
          try {
            JSONObject pin = host.auth.createPin();
            long expires =
                SystemClock.elapsedRealtime() + Math.max(1, pin.optInt("expiresIn", 900)) * 1000L;
            long id = pin.getLong("id");
            String value = pin.getString("code");
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  code.setText(value);
                  poll(request, id, value, expires, hint);
                });
          } catch (Exception e) {
            host.runOnUiThread(
                () -> {
                  if (request == host.generation) {
                    code.setText("—");
                    hint.setText(
                        "Plex couldn't be reached. Check your connection and choose New code.");
                  }
                });
          }
        });
  }

  private void poll(int request, long id, String code, long expires, TextView hint) {
    if (request != host.generation) return;
    long seconds = (expires - SystemClock.elapsedRealtime()) / 1000;
    if (seconds <= 0) {
      hint.setText("This code has expired. Choose New code to try again.");
      return;
    }
    hint.setText(
        "Waiting for you to finish signing in · "
            + (seconds / 60)
            + ":"
            + String.format(Locale.ROOT, "%02d", seconds % 60)
            + " remaining");
    host.network.execute(
        () -> {
          try {
            JSONObject result = host.auth.pollPin(id, code);
            String secret = result.optString("authToken");
            if (!secret.isEmpty() && !secret.equals("null")) {
              JSONObject who = host.auth.user(secret);
              JSONArray users = host.auth.users(secret);
              JSONObject connected =
                  new JSONObject()
                      .put("token", secret)
                      .put("name", who.optString("title", who.optString("username", "Plex")))
                      .put("uuid", who.optString("uuid"))
                      .put("users", users);
              host.runOnUiThread(
                  () -> {
                    if (request != host.generation) return;
                    try {
                      host.store.account(connected);
                      host.account = connected;
                      host.prefs().edit().putBoolean("preview", false).apply();
                      host.history.clear();
                      host.profilePicker.openOwnProfile();
                    } catch (Exception e) {
                      hint.setText(
                          "Sign-in succeeded, but credentials could not be saved. Please try"
                              + " again.");
                    }
                  });
              return;
            }
            host.runOnUiThread(
                () -> {
                  if (request == host.generation)
                    host.handler.postDelayed(() -> poll(request, id, code, expires, hint), 2000);
                });
          } catch (Exception e) {
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  if (e instanceof PlexAccount.Failure
                      && Set.of(404, 410).contains(((PlexAccount.Failure) e).status)) {
                    hint.setText("This code has expired. Choose New code to try again.");
                    return;
                  }
                  hint.setText("Waiting for Plex. We'll retry shortly.");
                  host.handler.postDelayed(() -> poll(request, id, code, expires, hint), 4000);
                });
          }
        });
  }
}
