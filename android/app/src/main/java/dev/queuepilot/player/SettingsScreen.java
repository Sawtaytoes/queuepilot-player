package dev.queuepilot.player;

import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings: the Plex account and QueuePilot server cards, Display size with its Auto measure,
 * Rewind on resume, and the installed version.
 */
@androidx.media3.common.util.UnstableApi
final class SettingsScreen {
  private final MainActivity host;

  SettingsScreen(MainActivity host) {
    this.host = host;
  }

  void queueSetup() {
    LinearLayout form = host.ui.column();
    form.setPadding(host.dp(24), host.dp(8), host.dp(24), host.dp(8));
    EditText input = new EditText(host);
    input.setSingleLine();
    input.setHint("https://queues.example.com");
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    input.setText(host.prefs().getString("queue-origin", ""));
    form.addView(input, new LinearLayout.LayoutParams(-1, host.dp(54)));
    TvPanel d =
        host.panel()
            .setTitle("Connect QueuePilot")
            .setMessage("Enter the address you use to open QueuePilot.")
            .setView(form)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create();
    d.setOnShowListener(
        v -> {
          d.setPanelSize(host.dp(470), -2);
          d.getButton(TvPanel.BUTTON_POSITIVE)
              .setOnClickListener(
                  b -> {
                    try {
                      String origin = PlexCatalog.base(input.getText().toString());
                      host.prefs().edit().putString("queue-origin", origin).apply();
                      d.dismiss();
                      host.navigate(new Screen("queues"));
                    } catch (Exception e) {
                      input.setError("Enter a complete http:// or https:// address");
                    }
                  });
        });
    d.show();
  }

  void settings() {
    host.shell("Settings");
    host.heading("QueuePilot TV", "Settings");
    host.status.setText("Playback preview · " + versionName());
    LinearLayout row = host.horizontal();
    row.addView(
        host.ui.card(
            "Plex account",
            R.drawable.lucide_circle_user_round,
            host.account.optString("name", "Connect Plex"),
            "Sign in on your phone using a TV code.",
            "Sign in with Plex",
            () -> host.navigate(new Screen("signin")),
            270));
    row.addView(
        host.ui.card(
            "QueuePilot",
            R.drawable.lucide_list_video,
            "Your queue server",
            "Connect the server that manages your watching queues.",
            "Connect QueuePilot",
            this::queueSetup,
            270));
    row.addView(
        host.ui.card(
            "Plex Home",
            R.drawable.lucide_users,
            "Who's watching?",
            "Choose a profile and its library access.",
            "Switch profile",
            () -> host.navigate(new Screen("profiles")),
            270));
    host.ui.gap(host.content, 22);
    LinearLayout preferences = host.ui.row();
    TextView rewindButton =
        host.ui.button(rewindLabel(), R.drawable.lucide_rotate_ccw, this::rewindSetting, false);
    rewindButton.setTag("rewind-setting");
    preferences.addView(rewindButton);
    TextView sizeButton =
        host.ui.button(
            "Display size · " + DisplaySize.label(host.prefs()),
            R.drawable.lucide_scaling,
            this::displaySizeSetting,
            false);
    LinearLayout.LayoutParams sizeGap = new LinearLayout.LayoutParams(-2, -2);
    sizeGap.leftMargin = host.dp(12);
    preferences.addView(sizeButton, sizeGap);
    TextView playbackButton =
        host.ui.button("Playback", R.drawable.lucide_circle_play, () -> host.playbackMenus.playbackSettings().show(), false);
    playbackButton.setTag("playback-settings");
    LinearLayout.LayoutParams playbackGap = new LinearLayout.LayoutParams(-2, -2);
    playbackGap.leftMargin = host.dp(12);
    preferences.addView(playbackButton, playbackGap);
    host.content.addView(preferences);
    host.focusFirst(row.getChildAt(0));
  }

  /**
   * Display size: Auto, computed from the screen and the viewing distance entered above it, or a
   * manual size from 50% to 125%.
   */
  private void displaySizeSetting() {
    float inches = host.prefs().getFloat(DisplaySize.SCREEN_KEY, DisplaySize.DEFAULT_SCREEN_INCHES);
    float feet = host.prefs().getFloat(DisplaySize.DISTANCE_KEY, DisplaySize.DEFAULT_DISTANCE_FEET);
    int stored =
        DisplaySize.valid(host.prefs().getInt(DisplaySize.PREFERENCE_KEY, DisplaySize.DEFAULT_PERCENT));
    String[] choices = new String[DisplaySize.CHOICES.length + 1];
    choices[0] = DisplaySize.label(DisplaySize.AUTO, inches, feet);
    for (int i = 0; i < DisplaySize.CHOICES.length; i++)
      choices[i + 1] = DisplaySize.label(DisplaySize.CHOICES[i], inches, feet);
    TvPanel[] holder = new TvPanel[1];
    LinearLayout measures = host.ui.column();
    TextView screen =
        host.ui.button(
            "Screen · " + DisplaySize.screenLabel(inches),
            R.drawable.lucide_tv,
            () -> {
              holder[0].dismiss();
              displayMeasureSetting(true);
            },
            false);
    screen.setContentDescription("Screen size, " + DisplaySize.screenLabel(inches));
    screen.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    measures.addView(screen, new LinearLayout.LayoutParams(-1, -2));
    host.ui.gap(measures, 8);
    TextView distance =
        host.ui.button(
            "Distance · " + DisplaySize.distanceLabel(feet),
            R.drawable.lucide_ruler,
            () -> {
              holder[0].dismiss();
              displayMeasureSetting(false);
            },
            false);
    distance.setContentDescription("Viewing distance, " + DisplaySize.distanceLabel(feet));
    distance.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    measures.addView(distance, new LinearLayout.LayoutParams(-1, -2));
    host.ui.gap(measures, 12);
    holder[0] =
        host.panel()
            .setTitle("Display size")
            .setMessage(
                "Make everything smaller to fit more on screen, or larger to read it from"
                    + " farther away. Auto sets the size from your screen and how far away you"
                    + " sit.")
            .setView(measures)
            .setSelected(
                stored == DisplaySize.AUTO
                    ? 0
                    : 1 + java.util.Arrays.binarySearch(DisplaySize.CHOICES, stored))
            .setItems(
                choices,
                (d, i) -> {
                  host.prefs()
                      .edit()
                      .putInt(
                          DisplaySize.PREFERENCE_KEY,
                          i == 0 ? DisplaySize.AUTO : DisplaySize.CHOICES[i - 1])
                      .apply();
                  applyDisplaySize(false);
                })
            .setNegativeButton("Cancel", null)
            .create();
    holder[0].show();
    holder[0].setPanelSize(host.dp(330), -2);
  }

  /** Applies at once: every later view, panel, and grid reads the new size. */
  private void applyDisplaySize(boolean isReopened) {
    host.ui = new TvUi(host);
    host.getWindow()
        .getDecorView()
        .post(
            () -> {
              host.render(host.current);
              if (isReopened) displaySizeSetting();
            });
  }

  /** Enters the screen's diagonal in inches, or the viewing distance in feet, for Auto. */
  private void displayMeasureSetting(boolean isScreen) {
    String key = isScreen ? DisplaySize.SCREEN_KEY : DisplaySize.DISTANCE_KEY;
    float minimum =
        isScreen ? DisplaySize.MINIMUM_SCREEN_INCHES : DisplaySize.MINIMUM_DISTANCE_FEET;
    float maximum =
        isScreen ? DisplaySize.MAXIMUM_SCREEN_INCHES : DisplaySize.MAXIMUM_DISTANCE_FEET;
    float value =
        isScreen
            ? DisplaySize.screenInches(host.prefs().getFloat(key, DisplaySize.DEFAULT_SCREEN_INCHES))
            : DisplaySize.distanceFeet(host.prefs().getFloat(key, DisplaySize.DEFAULT_DISTANCE_FEET));
    String unit = isScreen ? "inches" : "feet";
    LinearLayout form = host.ui.column();
    form.setPadding(host.dp(4), host.dp(4), host.dp(4), host.dp(8));
    EditText input = new EditText(host);
    input.setSingleLine();
    input.setTag("display-measure");
    input.setInputType(
        InputType.TYPE_CLASS_NUMBER | (isScreen ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL));
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    input.setText(DisplaySize.number(value));
    input.setSelectAllOnFocus(true);
    input.setTypeface(TvTheme.regular(host));
    input.setTextColor(TvUi.WHITE);
    input.setContentDescription(isScreen ? "Screen size in inches" : "Viewing distance in feet");
    form.addView(input, new LinearLayout.LayoutParams(-1, host.dp(54)));
    TvPanel d =
        host.panel()
            .setTitle(isScreen ? "Screen size" : "Viewing distance")
            .setMessage(
                isScreen
                    ? "The screen's diagonal, in inches."
                    : "How far you sit from the screen, in feet.")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
    boolean[] isSaved = {false};
    Runnable save =
        () -> {
          if (isSaved[0]) return;
          float entered;
          try {
            entered = Float.parseFloat(input.getText().toString().trim());
          } catch (NumberFormatException e) {
            entered = Float.NaN;
          }
          if (!(entered >= minimum && entered <= maximum)) {
            input.setError(
                "Enter "
                    + DisplaySize.number(minimum)
                    + " to "
                    + DisplaySize.number(maximum)
                    + " "
                    + unit);
            return;
          }
          isSaved[0] = true;
          host.prefs().edit().putFloat(key, entered).apply();
          d.dismiss();
          applyDisplaySize(true);
        };
    input.setOnEditorActionListener(
        (v, action, event) -> {
          save.run();
          return true;
        });
    d.setOnShowListener(
        v -> {
          d.setPanelSize(host.dp(360), -2);
          d.getButton(TvPanel.BUTTON_POSITIVE).setOnClickListener(b -> save.run());
          input.requestFocus();
        });
    d.show();
  }

  /** The installed version, so Settings never shows a stale hand-written number. */
  private String versionName() {
    try {
      return host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionName;
    } catch (Exception e) {
      return "";
    }
  }

  private String rewindLabel() {
    return "Rewind on resume · " + RewindOnResume.label(host.rewindSeconds());
  }

  private void rewindSetting() {
    String[] choices = {"Off", "1 second", "3 seconds", "5 seconds", "10 seconds"};
    int[] values = {0, 1, 3, 5, 10};
    TvPanel dialog =
        host.panel()
            .setTitle("Rewind on resume")
            .setMessage("Replay a little when you continue after pausing.")
            .setSelected(java.util.Arrays.binarySearch(values, host.rewindSeconds()))
            .setItems(
                choices,
                (d, i) -> {
                  host.prefs().edit().putInt(RewindOnResume.PREFERENCE_KEY, values[i]).apply();
                  TextView button = host.getWindow().getDecorView().findViewWithTag("rewind-setting");
                  if (button != null) button.setText(rewindLabel());
                })
            .setNegativeButton("Cancel", null)
            .create();
    dialog.show();
    dialog.setPanelSize(host.dp(330), -2);
  }
}
