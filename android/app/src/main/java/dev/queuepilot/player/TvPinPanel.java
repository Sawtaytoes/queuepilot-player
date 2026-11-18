package dev.queuepilot.player;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import org.json.JSONObject;

/**
 * The TV PIN: asked before a locked profile plays, with wrong tries counted and a wait after
 * five.
 */
@androidx.media3.common.util.UnstableApi
final class TvPinPanel {
  private final MainActivity host;

  TvPinPanel(MainActivity host) {
    this.host = host;
  }

  // The TV PIN: a queue marked "Locked on TV" in QueuePilot opens only after the household PIN.
  // The server enforces it (423 without a valid unlock); the TV only decides when to ask.

  /**
   * When {@code error} is QueuePilot's 423 for a locked queue, asks for the TV PIN and then runs
   * {@code retry}. Returns false for every other error, which the caller reports as before.
   */
  boolean askPinIfLocked(Exception error, Runnable retry) {
    if (!(error instanceof NativeQueueApi.Failure failure) || !failure.isLocked()) return false;
    NativeQueueApi.unlockToken = "";
    askTvPin(retry);
    return true;
  }

  /**
   * The number pad for the household TV PIN, 4 to 8 digits, driven with the D-pad or the remote's
   * number keys. A correct PIN keeps locked queues open until the app leaves the foreground.
   */
  void askTvPin(Runnable unlocked) {
    String origin = host.prefs().getString("queue-origin", "");
    if (host.profile == null || origin.isEmpty()) {
      host.message("Locked queue", "Connect Plex and your QueuePilot server to enter the TV PIN.");
      return;
    }
    LinearLayout form = host.ui.column();
    form.setPadding(host.dp(24), host.dp(8), host.dp(24), host.dp(16));
    StringBuilder pin = new StringBuilder();
    TextView dots = host.ui.title("○  ○  ○  ○", 30);
    dots.setGravity(Gravity.CENTER);
    dots.setTag("tv-pin-dots");
    form.addView(dots);
    host.ui.gap(form, 10);
    TextView feedback = host.ui.text("", 14, TvUi.MUTED);
    feedback.setGravity(Gravity.CENTER);
    feedback.setTag("tv-pin-message");
    feedback.setVisibility(View.GONE);
    form.addView(feedback, new LinearLayout.LayoutParams(-1, -2));
    host.ui.gap(form, 12);
    TvPanel dialog =
        host.panel()
            .setTitle("Enter the TV PIN")
            .setMessage("This queue is locked on the TV. Enter the QueuePilot TV PIN to open it.")
            .setView(form)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel", null)
            .create();
    boolean[] isBusy = {false};
    Runnable update =
        () -> {
          StringBuilder display = new StringBuilder();
          for (int i = 0; i < Math.max(4, pin.length()); i++)
            display.append(i < pin.length() ? "●  " : "○  ");
          dots.setText(display.toString().trim());
          dialog.getButton(TvPanel.BUTTON_POSITIVE).setEnabled(pin.length() >= 4 && !isBusy[0]);
        };
    java.util.function.Consumer<String> say =
        text -> {
          feedback.setText(text);
          feedback.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        };
    Runnable submit =
        () -> {
          if (pin.length() < 4 || isBusy[0]) return;
          String value = pin.toString();
          pin.setLength(0);
          isBusy[0] = true;
          update.run();
          say.accept("Checking the PIN…");
          host.network.execute(
              () -> {
                try {
                  JSONObject result =
                      NativeQueueApi.request(
                          origin,
                          "/api/native/unlock",
                          new JSONObject().put("pin", value),
                          "X-Plex-Profile-Token",
                          host.profileProof());
                  NativeQueueApi.unlockToken = result.optString("unlockToken");
                  host.runOnUiThread(
                      () -> {
                        dialog.dismiss();
                        unlocked.run();
                      });
                } catch (Exception error) {
                  host.runOnUiThread(
                      () -> {
                        isBusy[0] = false;
                        update.run();
                        if (!(error instanceof NativeQueueApi.Failure failure)) {
                          say.accept("QueuePilot couldn't check the PIN. " + error.getMessage());
                          return;
                        }
                        if (failure.status == 409) {
                          // No PIN is set any more, so nothing is locked: just try again.
                          dialog.dismiss();
                          unlocked.run();
                        } else if (failure.status == 429) {
                          int seconds = Math.max(1, failure.body.optInt("retryAfterSeconds", 60));
                          say.accept(
                              "Too many wrong PINs. Wait " + seconds + " seconds, then try again.");
                        } else if (failure.status == 403
                            && failure.body.has("attemptsLeft")) {
                          int left = failure.body.optInt("attemptsLeft");
                          say.accept(
                              "That PIN is not correct. "
                                  + (left == 1 ? "1 try left." : left + " tries left."));
                        } else say.accept(failure.getMessage());
                      });
                }
              });
        };
    GridLayout keypad = new GridLayout(host);
    keypad.setColumnCount(3);
    keypad.setTag("tv-pin-pad");
    LinearLayout.LayoutParams padLayout = new LinearLayout.LayoutParams(-2, -2);
    padLayout.gravity = Gravity.CENTER_HORIZONTAL;
    form.addView(keypad, padLayout);
    for (String key : List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "Delete", "0", "Clear")) {
      Runnable press =
          () -> {
            if (key.equals("Delete")) {
              if (pin.length() > 0) pin.deleteCharAt(pin.length() - 1);
            } else if (key.equals("Clear")) pin.setLength(0);
            else if (pin.length() < 8) pin.append(key);
            if (!isBusy[0]) say.accept("");
            update.run();
          };
      TextView button =
          key.equals("Delete")
              ? host.ui.button(key, R.drawable.lucide_delete, press, false)
              : host.ui.button(key, press, false);
      button.setContentDescription(key.equals("Delete") ? "Delete digit" : key);
      GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
      lp.width = host.dp(110);
      lp.height = host.dp(48);
      lp.setMargins(host.dp(4), host.dp(4), host.dp(4), host.dp(4));
      keypad.addView(button, lp);
    }
    dialog.setOnShowListener(
        v -> {
          dialog.setPanelSize(host.dp(460), -2);
          dialog.getButton(TvPanel.BUTTON_POSITIVE).setEnabled(false);
          dialog.getButton(TvPanel.BUTTON_POSITIVE).setOnClickListener(view -> submit.run());
          keypad.getChildAt(0).requestFocus();
        });
    dialog.setOnKeyListener(
        (d, keyCode, event) -> {
          if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            if (event.getAction() == KeyEvent.ACTION_UP && pin.length() < 8 && !isBusy[0]) {
              pin.append(keyCode - KeyEvent.KEYCODE_0);
              say.accept("");
              update.run();
            }
            return true;
          }
          return false;
        });
    dialog.show();
  }
}
