package dev.queuepilot.player;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native queue-first TV presentation with Plex device-link onboarding. */
@androidx.media3.common.util.UnstableApi
public final class MainActivity extends androidx.activity.ComponentActivity {
  private final ExecutorService network = Executors.newFixedThreadPool(4);
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Deque<Screen> history = new ArrayDeque<>();
  private final Map<Screen, String> focusMemory = new HashMap<>();
  private final StoryOrder demo = StoryOrder.demo();
  private TvUi ui;
  private ProfileStore store;
  private JSONArray profiles = new JSONArray();
  private JSONObject account = new JSONObject(), profile;
  private String activeId = "demo-ada";
  private PlexAccount auth;
  private LinearLayout content;
  private TextView status;
  private ExoPlayer player;
  private PlayerView playerView;
  private androidx.media3.session.MediaSession mediaSession;
  private volatile int generation;
  private int mainWidth;
  private boolean fixture;
  private Screen current;
  private JSONObject nativePlan;
  private int nativeIndex;
  private long nativeSequence;
  private final ExecutorService progressNetwork = Executors.newSingleThreadExecutor();
  private PlexCatalog.Entry playingEntry;
  private String playingOrigin = "", playingToken = "";
  private long sectionFloor, sectionEnd = -1;
  private boolean advancing;
  private final Set<TvPanel> panels = new HashSet<>();
  private final RewindOnResume rewind = new RewindOnResume();
  private JSONObject pendingNativeQueue;
  private String pendingNativeProfile = "";
  private JSONArray queuePeople = new JSONArray();

  private record Screen(String kind, String path, String title, PlexCatalog.Entry entry, int page) {
    Screen(String kind, String path, String title, PlexCatalog.Entry entry) {
      this(kind, path, title, entry, 0);
    }

    Screen(String kind) {
      this(kind, "", "", null);
    }
  }

  private SharedPreferences prefs() {
    return getSharedPreferences("state", MODE_PRIVATE);
  }

  private int dp(int size) {
    return ui.dp(size);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    ui = new TvUi(this);
    store = new ProfileStore(this);
    progressNetwork.execute(this::flushNativeEvents);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                back();
              }
            });
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    boolean emulator =
        android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish");
    // Test routing is accepted only on the disposable emulator, never on a household device.
    fixture = emulator && getIntent().getBooleanExtra("fixture", false);
    String clientId = prefs().getString("plex-client-id", "");
    if (clientId.isEmpty()) {
      clientId = UUID.randomUUID().toString();
      prefs().edit().putString("plex-client-id", clientId).apply();
    }
    auth = new PlexAccount(clientId, fixture);
    try {
      profiles = store.read();
      account = store.account();
    } catch (Exception e) {
      Toast.makeText(this, "Please sign in again to restore access.", Toast.LENGTH_LONG).show();
    }
    setProfile(prefs().getString("active", "demo-ada"));
    if (profile != null || prefs().getBoolean("preview", false)) home();
    else if (!account.optString("token").isEmpty()) render(new Screen("profiles"));
    else render(new Screen("welcome"));
  }

  private void setProfile(String id) {
    profile = null;
    for (int i = 0; i < profiles.length(); i++) {
      JSONObject candidate = profiles.optJSONObject(i);
      if (candidate != null && candidate.optString("id").equals(id)) {
        profile = candidate;
        break;
      }
    }
    activeId = profile == null && !id.equals("demo-grace") ? "demo-ada" : id;
    prefs().edit().putString("active", activeId).apply();
  }

  private String profileName() {
    return profile == null
        ? (activeId.equals("demo-grace") ? "Grace" : "Ada")
        : profile.optString("name");
  }

  private String server() {
    return profile == null ? "" : profile.optString("server");
  }

  private String token() {
    return profile == null ? "" : profile.optString("token");
  }

  private Set<String> completed() {
    return new HashSet<>(prefs().getStringSet("done:" + activeId, Set.of()));
  }

  private void home() {
    history.clear();
    render(new Screen("home"));
  }

  private void navigate(Screen screen) {
    if (current != null) {
      View focused = getWindow().getDecorView().findFocus();
      if (focused != null) focusMemory.put(current, focusKey(focused));
      history.push(current);
    }
    render(screen);
  }

  private void back() {
    if (player != null) reportPlayback("stopped");
    advancing = true;
    nativePlan = null;
    pendingNativeQueue = null;
    pendingNativeProfile = "";

    if (!history.isEmpty()) render(history.pop());
    else if (current != null && !Set.of("home", "welcome").contains(current.kind())) home();
    else moveTaskToBack(true);
  }

  private void reset() {
    closePanels();
    generation++;
    handler.removeCallbacksAndMessages(null);
    releasePlayer();
  }

  private void render(Screen screen) {
    reset();
    current = screen;
    switch (screen.kind()) {
      case "welcome" -> welcome();
      case "signin" -> signIn();
      case "profiles" -> chooseProfile();
      case "settings" -> settings();
      case "library" -> load(screen.path(), screen.title());
      case "details" -> details(screen.entry());
      case "demo" -> demoSeason();
      case "queues" -> queues();
      default -> drawHome();
    }
  }

  private void shell(String selected) {
    LinearLayout root = ui.row();
    root.setBackgroundColor(TvUi.BG);
    LinearLayout rail = ui.column();
    rail.setPadding(dp(18), dp(28), dp(18), dp(22));
    rail.setBackgroundColor(0xff14171f);
    root.addView(rail, new LinearLayout.LayoutParams(dp(174), -1));
    rail.addView(ui.title("◉  QueuePilot", 20));
    ui.gap(rail, 36);
    nav(rail, "Home", selected, () -> home());
    nav(rail, "Watching", selected, () -> navigate(new Screen("queues")));
    nav(rail, "Series", selected, () -> browseType("show", "Series"));
    nav(rail, "Movies", selected, () -> browseType("movie", "Movies"));
    nav(rail, "Settings", selected, () -> navigate(new Screen("settings")));
    rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
    TextView profileButton =
        ui.button("●  " + profileName(), () -> navigate(new Screen("profiles")), false);
    if (profile != null) {
      ImageView face = avatar(44);
      String thumb = profile.optString("thumb");
      JSONArray users = account.optJSONArray("users");
      if (thumb.isEmpty() && users != null)
        for (int i = 0; i < users.length(); i++) {
          JSONObject user = users.optJSONObject(i);
          if (user != null && userId(user).equals(activeId)) thumb = user.optString("thumb");
        }
      loadProfileAvatar(face, thumb);
      rail.addView(face);
      ui.gap(rail, 8);
    }
    rail.addView(profileButton);
    ui.gap(rail, 12);
    rail.addView(ui.text(profile == null ? "Demo library" : "Plex library", 11, TvUi.MUTED));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    content = ui.column();
    content.setPadding(dp(36), dp(28), dp(32), dp(36));
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
    mainWidth =
        Math.max(
            540,
            Math.round(
                    getResources().getDisplayMetrics().widthPixels
                        / getResources().getDisplayMetrics().density)
                - 174
                - 68);
    setContentView(root);
  }

  private void nav(LinearLayout rail, String label, String selected, Runnable action) {
    TextView v = ui.button(label, action, false);
    v.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    v.setTextColor(TvUi.MUTED);
    v.setBackground(ui.shape(Color.TRANSPARENT, 0, 8));
    v.setOnFocusChangeListener(
        (view, focused) ->
            view.setBackground(ui.shape(Color.TRANSPARENT, focused ? TvUi.ACCENT : 0, 8)));
    if (label.equals(selected)) {
      v.setOnFocusChangeListener(
          (view, focused) ->
              view.setBackground(ui.shape(0xff293122, focused ? TvUi.ACCENT : 0, 8)));
      v.setTextColor(TvUi.ACCENT);
      v.setBackground(ui.shape(0xff293122, 0, 8));
    }
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
    lp.bottomMargin = dp(5);
    rail.addView(v, lp);
  }

  private void heading(String eyebrow, String title) {
    if (!eyebrow.isEmpty()) {
      content.addView(ui.text(eyebrow.toUpperCase(Locale.ROOT), 12, TvUi.ACCENT));
      ui.gap(content, 8);
    }
    content.addView(ui.title(title, 34));
    ui.gap(content, 8);
    status = ui.text("", 14, TvUi.MUTED);
    content.addView(status);
    ui.gap(content, 22);
  }

  private void section(String title) {
    ui.gap(content, 26);
    content.addView(ui.title(title, 23));
    ui.gap(content, 16);
  }

  private LinearLayout horizontal() {
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    LinearLayout row = ui.row();
    row.setPadding(dp(3), dp(3), dp(3), dp(3));
    scroll.addView(row);
    content.addView(scroll);
    return row;
  }

  private String focusKey(View view) {
    if (view.getContentDescription() != null && view.getContentDescription().length() > 0)
      return view.getContentDescription().toString();
    return view instanceof TextView ? ((TextView) view).getText().toString() : "";
  }

  private View rememberedFocus(View root, String key) {
    if (root.isFocusable() && key.equals(focusKey(root))) return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View found = rememberedFocus(((ViewGroup) root).getChildAt(i), key);
        if (found != null) return found;
      }
    return null;
  }

  private void focusFirst(View view) {
    view.post(
        () -> {
          if (!view.isAttachedToWindow()) return;
          String key = focusMemory.getOrDefault(current, "");
          View target = key.isEmpty() ? null : rememberedFocus(getWindow().getDecorView(), key);
          (target == null ? view : target).requestFocus();
        });
  }

  private void centered(String eyebrow, String title, String description) {
    FrameLayout frame = new FrameLayout(this);
    frame.setBackgroundColor(TvUi.BG);
    content = ui.column();
    content.setPadding(dp(38), dp(32), dp(38), dp(32));
    content.setBackground(ui.shape(0xff19202a, 0, 16));
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(630), -2, Gravity.CENTER);
    frame.addView(content, lp);
    setContentView(frame);
    heading(eyebrow, title);
    status.setText(description);
  }

  private void welcome() {
    centered(
        "QueuePilot TV",
        "Your people. Your queues.",
        "Connect your Plex library to start watching. Sign in on your phone with a short TV code.");
    LinearLayout actions = ui.row();
    content.addView(actions);
    ui.action(actions, "Sign in with Plex", () -> navigate(new Screen("signin")), true);
    ui.action(
        actions,
        "Explore the demo",
        () -> {
          prefs().edit().putBoolean("preview", true).apply();
          home();
        },
        false);
    focusFirst(actions.getChildAt(0));
  }

  private void drawHome() {
    shell("Home");
    heading(
        profile == null ? "DEMO · " + profileName() : "Welcome, " + profileName(),
        "What are we watching?");
    status.setText("Your people. Your queues. Your library.");
    LinearLayout cards = horizontal();
    int width = (mainWidth - 48) / 3;
    if (profile == null) {
      StoryOrder.Item next = demo.next(completed());
      cards.addView(
          ui.card(
              profileName() + " · Watching",
              "Ready when you are",
              next == null
                  ? "You have finished this demo sequence."
                  : "Next: Orbit Nine · " + next.label(),
              "View demo queue",
              () -> navigate(new Screen("demo")),
              width));
      cards.addView(
          ui.card(
              "Watching together",
              "Watch together",
              "Shared queues will use the QueuePilot engine's audience rules.",
              "Explore shared queues",
              () -> navigate(new Screen("queues")),
              width));
      cards.addView(
          ui.card(
              "Your library",
              "Make yourself at home",
              "Connect Plex with a code from your phone. Your library is discovered automatically.",
              "Sign in with Plex",
              () -> navigate(new Screen("signin")),
              width));
    } else {
      cards.addView(
          ui.card(
              profileName() + " · Watching",
              "Ready when you are",
              "Open your QueuePilot watching queues.",
              "View your queues",
              () -> navigate(new Screen("queues")),
              width));
      cards.addView(
          ui.card(
              "Watching together",
              "Watch together",
              "Choose an audience from your QueuePilot queues.",
              "Explore shared queues",
              () -> navigate(new Screen("queues")),
              width));
      cards.addView(
          ui.card(
              "Your library",
              "Browse something new",
              profile.optString("serverName", "Your Plex server"),
              "Browse library",
              () -> navigate(new Screen("library", "/library/sections", "Your library", null)),
              width));
    }
    ui.primaryCard(cards.getChildAt(0));
    focusFirst(cards.getChildAt(0));
    section("Continue your story");
    if (profile == null) {
      demoHero();
      section("Browse your library");
      demoPosters();
    } else {
      LinearLayout continuation = ui.column();
      content.addView(continuation);
      TextView loading = ui.text("Finding your next episode…", 16, TvUi.MUTED);
      continuation.addView(loading);
      fetch(
          "/library/onDeck",
          (entries, cached) -> {
            continuation.removeAllViews();
            if (entries.length() == 0)
              continuation.addView(
                  ui.text("Choose a title below to start watching.", 16, TvUi.MUTED));
            else
              continuation.addView(mediaHero(PlexCatalog.Entry.fromJson(entries.optJSONObject(0))));
          },
          () ->
              loading.setText(
                  "Continue watching is unavailable. You can still browse your libraries below."));
      section("Browse your library");
      LinearLayout libraries = horizontal();
      fetch(
          "/library/sections",
          (entries, cached) -> {
            for (int i = 0; i < entries.length(); i++) {
              PlexCatalog.Entry entry = PlexCatalog.Entry.fromJson(entries.optJSONObject(i));
              libraries.addView(
                  ui.card(
                      "Library",
                      entry.title(),
                      "Browse titles on your Plex server.",
                      "Open library",
                      () -> navigate(new Screen("library", entry.children(), entry.title(), null)),
                      Math.min(250, (mainWidth - 48) / 3)));
            }
          },
          () ->
              libraries.addView(
                  ui.text("Server unavailable. Try again from Series or Movies.", 15, TvUi.MUTED)));
    }
  }

  private void demoHero() {
    StoryOrder.Item next = demo.next(completed());
    FrameLayout hero = new FrameLayout(this);
    hero.setBackground(ui.shape(0xff102430, 0, 14));
    hero.setClipToOutline(true);
    FrameLayout.LayoutParams art = new FrameLayout.LayoutParams(dp(mainWidth / 2), -1, Gravity.END);
    hero.addView(ui.demoArt(0), art);
    LinearLayout labels = ui.column();
    labels.setPadding(dp(30), dp(26), dp(30), dp(24));
    labels.addView(ui.text("CONTINUE IN STORY ORDER · DEMO", 12, TvUi.ACCENT));
    ui.gap(labels, 12);
    labels.addView(ui.title("Orbit Nine", 34));
    ui.gap(labels, 12);
    labels.addView(
        ui.text(
            next == null ? "Demo sequence complete" : "Season 1 · " + next.label(),
            16,
            TvUi.MUTED));
    ui.gap(labels, 20);
    LinearLayout actions = ui.row();
    labels.addView(actions);
    ui.action(actions, "View season", () -> navigate(new Screen("demo")), true);
    hero.addView(labels, new FrameLayout.LayoutParams(dp(mainWidth * 2 / 3), -2));
    content.addView(hero, new LinearLayout.LayoutParams(-1, dp(235)));
  }

  private void demoPosters() {
    String[] titles = {
      "Orbit Nine", "The Last Beacon", "Glass City", "Wild Current", "After the Rain"
    };
    LinearLayout row = horizontal();
    for (int i = 0; i < titles.length; i++) {
      final int index = i;
      LinearLayout poster = ui.column();
      poster.setPadding(dp(3), dp(3), dp(3), dp(9));
      poster.addView(ui.demoArt(i), new LinearLayout.LayoutParams(-1, dp(240)));
      ui.gap(poster, 10);
      poster.addView(ui.title(titles[i], 16));
      ui.focusable(
          poster,
          () -> {
            if (index == 0) navigate(new Screen("demo"));
            else
              message(
                  titles[index],
                  "This is fictional demo artwork. Sign in with Plex to browse and play your"
                      + " library.");
          },
          TvUi.BG);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp((mainWidth - 76) / 5), -2);
      lp.rightMargin = dp(14);
      row.addView(poster, lp);
    }
  }

  private View mediaHero(PlexCatalog.Entry entry) {
    LinearLayout hero = ui.row();
    hero.setPadding(dp(24), dp(20), dp(24), dp(20));
    hero.setBackground(ui.shape(0xff102430, 0, 12));
    LinearLayout labels = ui.column();
    labels.addView(ui.text("CONTINUE WATCHING", 12, TvUi.ACCENT));
    ui.gap(labels, 12);
    labels.addView(ui.title(entry.title(), 29));
    ui.gap(labels, 14);
    if (entry.episode() > 0) labels.addView(ui.text("Episode " + entry.episode(), 16, TvUi.MUTED));
    ui.gap(labels, 14);
    labels.addView(
        ui.button("View title", () -> navigate(new Screen("details", "", "", entry)), true));
    hero.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    hero.addView(image, new LinearLayout.LayoutParams(dp(240), dp(185)));
    loadArt(image, entry.thumb(), 720);
    return hero;
  }

  private void signIn() {
    centered(
        "Connect Plex",
        "Sign in on your phone",
        "Visit plex.tv/link in your phone or computer's browser, then enter this code.");
    TextView code = ui.title("…", 56);
    code.setLetterSpacing(.16f);
    content.addView(code);
    ui.gap(content, 16);
    TextView hint = ui.text("Getting your sign-in code…", 15, TvUi.MUTED);
    content.addView(hint);
    ui.gap(content, 24);
    LinearLayout actions = ui.row();
    content.addView(actions);
    ui.action(actions, "New code", () -> render(new Screen("signin")), false);
    ui.action(actions, "Back", this::back, false);
    focusFirst(actions.getChildAt(1));
    int request = generation;
    network.execute(
        () -> {
          try {
            JSONObject pin = auth.createPin();
            long expires =
                SystemClock.elapsedRealtime() + Math.max(1, pin.optInt("expiresIn", 900)) * 1000L;
            long id = pin.getLong("id");
            String value = pin.getString("code");
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  code.setText(value);
                  poll(request, id, value, expires, hint);
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request == generation) {
                    code.setText("—");
                    hint.setText(
                        "Plex couldn't be reached. Check your connection and choose New code.");
                  }
                });
          }
        });
  }

  private void poll(int request, long id, String code, long expires, TextView hint) {
    if (request != generation) return;
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
    network.execute(
        () -> {
          try {
            JSONObject result = auth.pollPin(id, code);
            String secret = result.optString("authToken");
            if (!secret.isEmpty() && !secret.equals("null")) {
              JSONObject who = auth.user(secret);
              JSONArray users = auth.users(secret);
              JSONObject connected =
                  new JSONObject()
                      .put("token", secret)
                      .put("name", who.optString("title", who.optString("username", "Plex")))
                      .put("users", users);
              runOnUiThread(
                  () -> {
                    if (request != generation) return;
                    try {
                      store.account(connected);
                      account = connected;
                      prefs().edit().putBoolean("preview", false).apply();
                      history.clear();
                      render(new Screen("profiles"));
                    } catch (Exception e) {
                      hint.setText(
                          "Sign-in succeeded, but credentials could not be saved. Please try"
                              + " again.");
                    }
                  });
              return;
            }
            runOnUiThread(
                () -> {
                  if (request == generation)
                    handler.postDelayed(() -> poll(request, id, code, expires, hint), 2000);
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  if (e instanceof PlexAccount.Failure
                      && Set.of(404, 410).contains(((PlexAccount.Failure) e).status)) {
                    hint.setText("This code has expired. Choose New code to try again.");
                    return;
                  }
                  hint.setText("Waiting for Plex. We'll retry shortly.");
                  handler.postDelayed(() -> poll(request, id, code, expires, hint), 4000);
                });
          }
        });
  }

  private void chooseProfile() {
    shell("");
    heading("Plex Home", "Who's watching?");
    status.setText("Choose a profile to use its library access.");
    LinearLayout row = horizontal();
    JSONArray users = account.optJSONArray("users");
    if (users != null)
      for (int i = 0; i < users.length(); i++) {
        JSONObject user = users.optJSONObject(i);
        if (user == null) continue;
        String name = user.optString("title", user.optString("username", "Profile"));
        LinearLayout profileCard =
            ui.card(
                user.optBoolean("protected") ? "PIN protected" : "Plex Home",
                name,
                "",
                "Choose profile",
                () -> selectUser(user),
                220);
        ImageView face = avatar(70);
        loadProfileAvatar(face, user.optString("thumb"));
        profileCard.addView(face, 0);
        row.addView(profileCard);
      }
    else
      for (int i = 0; i < profiles.length(); i++) {
        JSONObject p = profiles.optJSONObject(i);
        row.addView(
            ui.card(
                "Saved profile",
                p.optString("name"),
                "Available on this device.",
                "Choose profile",
                () -> {
                  setProfile(p.optString("id"));
                  home();
                },
                220));
      }
    if (account.optString("token").isEmpty()) {
      section("Plex account");
      content.addView(ui.button("Sign in with Plex", () -> navigate(new Screen("signin")), true));
    }
    if (prefs().getBoolean("preview", false)) {
      section("Demo profiles");
      LinearLayout demos = horizontal();
      for (String name : List.of("Ada", "Grace"))
        demos.addView(
            ui.card(
                "Fictional profile",
                name,
                "Separate demo viewing progress.",
                "Choose demo profile",
                () -> {
                  setProfile("demo-" + name.toLowerCase(Locale.ROOT));
                  home();
                },
                220));
    }
    if (row.getChildCount() > 0) focusFirst(row.getChildAt(0));
  }

  private String userId(JSONObject user) {
    return user.optString("uuid", user.optString("id"));
  }

  private void selectUser(JSONObject user) {
    String id = userId(user);
    if (!user.optBoolean("protected"))
      for (int i = 0; i < profiles.length(); i++) {
        JSONObject p = profiles.optJSONObject(i);
        if (p != null && p.optString("id").equals(id)) {
          setProfile(id);
          home();
          playPendingNative();
          return;
        }
      }
    if (user.optBoolean("protected")) {
      LinearLayout form = ui.column();
      form.setPadding(dp(24), dp(8), dp(24), dp(16));
      StringBuilder pin = new StringBuilder();
      TextView dots = ui.title("○  ○  ○  ○", 30);
      dots.setGravity(Gravity.CENTER);
      form.addView(dots);
      ui.gap(form, 18);
      TvPanel dialog =
          panel()
              .setTitle("Unlock " + user.optString("title", "profile"))
              .setMessage("Enter this profile's four-digit Plex Home PIN.")
              .setView(form)
              .setPositiveButton("Continue", null)
              .setNegativeButton(
                  "Cancel",
                  (d, w) -> {
                    pendingNativeQueue = null;
                    pendingNativeProfile = "";
                  })
              .create();
      Runnable update =
          () -> {
            StringBuilder display = new StringBuilder();
            for (int i = 0; i < 4; i++) display.append(i < pin.length() ? "●  " : "○  ");
            dots.setText(display.toString().trim());
            dialog.getButton(TvPanel.BUTTON_POSITIVE).setEnabled(pin.length() == 4);
          };
      GridLayout keypad = new GridLayout(this);
      keypad.setColumnCount(3);
      LinearLayout.LayoutParams padLayout = new LinearLayout.LayoutParams(-2, -2);
      padLayout.gravity = Gravity.CENTER_HORIZONTAL;
      form.addView(keypad, padLayout);
      for (String key :
          List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "Delete", "0", "Clear")) {
        TextView button =
            ui.button(
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
        lp.width = dp(90);
        lp.height = dp(48);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        keypad.addView(button, lp);
      }
      dialog.setOnShowListener(
          v -> {
            dialog.setPanelSize(dp(440), -2);
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
    reset();
    shell("");
    heading("Plex Home", "Opening " + user.optString("title", "your profile"));
    status.setText("Finding your Plex servers…");
    int request = generation;
    network.execute(
        () -> {
          try {
            String userToken = auth.switchUser(account.getString("token"), userId(user), pin);
            JSONArray servers = auth.servers(userToken);
            for (int i = 0; i < servers.length(); i++)
              servers.getJSONObject(i).put("profileProof", userToken);
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  if (servers.length() == 0) {
                    status.setText("This profile has no Plex Media Servers available.");
                    content.addView(
                        ui.button(
                            "Choose another profile", () -> render(new Screen("profiles")), false));
                  } else if (servers.length() == 1) connectServer(user, servers.optJSONObject(0));
                  else {
                    status.setText("Choose a Plex server.");
                    LinearLayout row = horizontal();
                    for (int i = 0; i < servers.length(); i++) {
                      JSONObject s = servers.optJSONObject(i);
                      row.addView(
                          ui.card(
                              "Plex server",
                              s.optString("name"),
                              "",
                              "Connect",
                              () -> connectServer(user, s),
                              260));
                    }
                    focusFirst(row.getChildAt(0));
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request == generation) {
                    status.setText(
                        e instanceof PlexAccount.Failure
                                && Set.of(401, 403).contains(((PlexAccount.Failure) e).status)
                            ? "Plex couldn't unlock that profile. Check the PIN and try again."
                            : "Plex couldn't load this profile. Check your connection and try"
                                + " again.");
                    content.addView(
                        ui.button("Choose profile", () -> render(new Screen("profiles")), true));
                  }
                });
          }
        });
  }

  private void connectServer(JSONObject user, JSONObject selected) {
    reset();
    shell("");
    heading("Plex server", selected.optString("name", "Connecting"));
    status.setText("Connecting directly to your server…");
    int request = generation;
    network.execute(
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
              if (request != generation) return;
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
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  try {
                    JSONArray updated = new JSONArray();
                    for (int i = 0; i < profiles.length(); i++) {
                      JSONObject existing = profiles.optJSONObject(i);
                      if (!existing.optString("id").equals(p.optString("id")))
                        updated.put(existing);
                    }
                    updated.put(p);
                    store.write(updated);
                    profiles = updated;
                    setProfile(p.optString("id"));
                    prefs().edit().putBoolean("preview", false).apply();
                    home();
                    playPendingNative();
                  } catch (Exception e) {
                    status.setText("Credentials could not be saved. Please try again.");
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request == generation) {
                    status.setText(
                        "A direct connection to this server isn't available. Check that the server"
                            + " is running and reachable.");
                    content.addView(
                        ui.button("Try again", () -> connectServer(user, selected), true));
                  }
                });
          }
        });
  }

  private interface Listing {
    void ready(JSONArray entries, boolean cached);
  }

  private File cacheFile(String id, String origin, String path) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest((id + "|" + origin + "|" + path).getBytes(StandardCharsets.UTF_8));
    StringBuilder key = new StringBuilder();
    for (byte b : digest) key.append(String.format(Locale.ROOT, "%02x", b));
    return new File(getCacheDir(), key + ".json");
  }

  private void fetch(String path, Listing callback, Runnable failure) {
    int request = generation;
    String origin = server(), secret = token(), id = activeId;
    network.execute(
        () -> {
          JSONArray entries;
          boolean cached = false;
          try {
            entries =
                PlexCatalog.list(
                    PlexCatalog.get(PlexCatalog.url(origin, path), secret, 16 * 1024 * 1024));
            java.nio.file.Files.write(
                cacheFile(id, origin, path).toPath(),
                entries.toString().getBytes(StandardCharsets.UTF_8));
          } catch (Exception e) {
            try {
              entries =
                  new JSONArray(
                      new String(
                          java.nio.file.Files.readAllBytes(cacheFile(id, origin, path).toPath()),
                          StandardCharsets.UTF_8));
              cached = true;
            } catch (Exception missing) {
              runOnUiThread(
                  () -> {
                    if (request == generation) failure.run();
                  });
              return;
            }
          }
          JSONArray result = entries;
          boolean fromCache = cached;
          runOnUiThread(
              () -> {
                if (request == generation && id.equals(activeId)) callback.ready(result, fromCache);
              });
        });
  }

  private void browseType(String type, String title) {
    if (profile == null) {
      navigate(new Screen("demo"));
      return;
    }
    navigate(new Screen("library", "/library/sections", title, null));
  }

  private void load(String path, String title) {
    if (profile == null) {
      render(new Screen("welcome"));
      return;
    }
    shell(title.equals("Series") ? "Series" : title.equals("Movies") ? "Movies" : "");
    heading("Your library", title);
    status.setText("Loading your library…");
    int page = current.page();
    boolean paged = !path.equals("/library/sections");
    String requestPath =
        paged
            ? path
                + (path.contains("?") ? "&" : "?")
                + "X-Plex-Container-Start="
                + (page * 30)
                + "&X-Plex-Container-Size=30"
            : path;
    fetch(
        requestPath,
        (entries, cached) -> {
          if (path.equals("/library/sections") && Set.of("Series", "Movies").contains(title)) {
            JSONArray matching = new JSONArray();
            String type = title.equals("Series") ? "show" : "movie";
            for (int i = 0; i < entries.length(); i++)
              if (entries.optJSONObject(i).optString("libraryType").equals(type))
                matching.put(entries.optJSONObject(i));
            entries = matching;
          }
          status.setText(
              cached
                  ? "Saved listing · your server is currently unavailable"
                  : entries.length() + " titles");
          if (entries.length() == 0) {
            status.setText(
                page == 0
                    ? "No titles found in this library."
                    : "You have reached the end of this library.");
            if (page > 0)
              content.addView(
                  ui.button(
                      "Previous page",
                      () -> render(new Screen("library", path, title, null, page - 1)),
                      false));
            return;
          }
          LinearLayout row = null;
          View first = null;
          for (int i = 0; i < Math.min(30, entries.length()); i++) {
            if (i % 5 == 0) {
              row = ui.row();
              content.addView(row);
              ui.gap(content, 22);
            }
            PlexCatalog.Entry entry = PlexCatalog.Entry.fromJson(entries.optJSONObject(i));
            View card = poster(entry);
            row.addView(card);
            if (first == null) first = card;
          }
          LinearLayout pages = ui.row();
          content.addView(pages);
          if (page > 0)
            ui.action(
                pages,
                "Previous page",
                () -> render(new Screen("library", path, title, null, page - 1)),
                false);
          if (paged && entries.length() >= 30)
            ui.action(
                pages,
                "Next page",
                () -> render(new Screen("library", path, title, null, page + 1)),
                false);
          if (first != null) focusFirst(first);
        },
        () -> {
          status.setText("Your server is unavailable and this listing isn't saved yet.");
          content.addView(ui.button("Try again", () -> render(current), true));
        });
  }

  private View poster(PlexCatalog.Entry entry) {
    LinearLayout card = ui.column();
    card.setPadding(dp(3), dp(3), dp(3), dp(10));
    int width = (mainWidth - 76) / 5;
    if (entry.type().equals("library")) {
      TextView icon = ui.title("▤", 48);
      icon.setGravity(Gravity.CENTER);
      card.addView(icon, new LinearLayout.LayoutParams(-1, dp(150)));
    } else {
      ImageView image = new ImageView(this);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      image.setBackgroundColor(TvUi.PANEL);
      card.addView(image, new LinearLayout.LayoutParams(-1, dp(width * 3 / 2)));
      loadArt(image, entry.thumb(), 720);
    }
    ui.gap(card, 10);
    TextView label = ui.title(entry.title(), 16);
    label.setMaxLines(2);
    label.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(label);
    ui.gap(card, 5);
    card.addView(
        ui.text(
            entry.type().equals("episode") ? "Episode " + entry.episode() : entry.type(),
            12,
            TvUi.MUTED));
    ui.focusable(
        card,
        () -> {
          if (Set.of("movie", "episode").contains(entry.type()))
            navigate(new Screen("details", "", "", entry));
          else navigate(new Screen("library", entry.children(), entry.title(), null));
        },
        TvUi.BG);
    card.setContentDescription(entry.title());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), -2);
    lp.rightMargin = dp(14);
    card.setLayoutParams(lp);
    return card;
  }

  private Bitmap artwork(String origin, String secret, String thumb, int width) throws Exception {
    if (!thumb.startsWith("/") || thumb.startsWith("//"))
      throw new IllegalArgumentException("Invalid artwork path");
    String path =
        "/photo/:/transcode?width="
            + width
            + "&height="
            + (width * 3 / 2)
            + "&minSize=1&upscale=0&url="
            + PlexAccount.encode(thumb);
    byte[] bytes = PlexCatalog.get(PlexCatalog.url(origin, path), secret, 12 * 1024 * 1024);
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (bounds.outWidth <= 0
        || bounds.outHeight <= 0
        || bounds.outWidth > 4096
        || bounds.outHeight > 6144) throw new IllegalArgumentException("Invalid image");
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
  }

  private void loadArt(ImageView view, String thumb, int width) {
    if (thumb.isEmpty()) return;
    int request = generation;
    String origin = server(), secret = token();
    network.execute(
        () -> {
          try {
            Bitmap image = artwork(origin, secret, thumb, width);
            runOnUiThread(
                () -> {
                  if (request == generation) view.setImageBitmap(image);
                });
          } catch (Exception ignored) {
          }
        });
  }

  private void details(PlexCatalog.Entry entry) {
    shell("");
    heading(
        entry.type().equals("episode") ? "Episode " + entry.episode() : "Your library",
        entry.title());
    LinearLayout row = ui.row();
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    row.addView(image, new LinearLayout.LayoutParams(dp(210), dp(315)));
    loadArt(image, entry.thumb(), 1440);
    LinearLayout info = ui.column();
    info.setPadding(dp(28), dp(10), 0, 0);
    row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    info.addView(ui.text("Watch with " + profileName(), 18, TvUi.MUTED));
    ui.gap(info, 24);
    TextView play = ui.button("Play", () -> playEntry(entry), true);
    info.addView(play);
    ui.gap(info, 16);
    info.addView(ui.button("Artwork quality", () -> compareArt(entry), false));
    content.addView(row);
    focusFirst(play);
  }

  private void compareArt(PlexCatalog.Entry entry) {
    TvPanel dialog =
        panel()
            .setTitle("Artwork quality")
            .setMessage("Loading 240px and 1440px artwork…")
            .setPositiveButton("Close", null)
            .create();
    dialog.show();
    dialog.setPanelSize(dp(820), dp(550));
    int request = generation;
    String origin = server(), secret = token();
    network.execute(
        () -> {
          try {
            Bitmap low = artwork(origin, secret, entry.thumb(), 240),
                high = artwork(origin, secret, entry.thumb(), 1440);
            runOnUiThread(
                () -> {
                  if (request != generation || !dialog.isShowing()) return;
                  LinearLayout images = ui.row();
                  images.setPadding(dp(24), dp(16), dp(24), dp(16));
                  for (Bitmap bitmap : List.of(low, high)) {
                    LinearLayout col = ui.column();
                    col.addView(
                        ui.text(bitmap.getWidth() + " × " + bitmap.getHeight(), 16, TvUi.MUTED));
                    ImageView v = new ImageView(this);
                    v.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    v.setImageBitmap(bitmap);
                    col.addView(v, new LinearLayout.LayoutParams(-1, dp(330)));
                    images.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                  }
                  dialog.setMessage("");
                  dialog.setView(images);
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (dialog.isShowing()) dialog.setMessage("Artwork couldn't be loaded.");
                });
          }
        });
  }

  private void playEntry(PlexCatalog.Entry entry) {
    status.setText("Opening video…");
    int request = generation;
    String origin = server(), secret = token();
    network.execute(
        () -> {
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(origin, "/library/metadata/" + entry.key()),
                        secret,
                        16 * 1024 * 1024));
            PlexCatalog.Entry detail = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            if (detail.part().isEmpty()) throw new IllegalStateException();
            runOnUiThread(
                () -> {
                  if (request == generation) {
                    nativePlan = null;
                    playingEntry = detail;
                    playingOrigin = origin;
                    playingToken = secret;
                    sectionFloor = 0;
                    sectionEnd = -1;
                    advancing = false;
                    startPlayer(
                        PlexCatalog.url(origin, detail.part()),
                        secret,
                        detail.title(),
                        detail.cast());
                    player.seekTo(detail.resume());
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request == generation)
                    status.setText("This title couldn't be opened for direct playback.");
                });
          }
        });
  }

  private void startPlayer(String url, String secret, String title, String cast) {
    reset();
    if (current == null || !current.kind().equals("player")) history.push(current);
    current = new Screen("player");
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    playerView = new PlayerView(this);
    root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout overlay = ui.column();
    overlay.setPadding(dp(26), dp(18), dp(26), dp(16));
    overlay.setBackgroundColor(0xd0101218);
    overlay.addView(ui.title(title, 23));
    status = ui.text("Opening video…", 13, TvUi.MUTED);
    overlay.addView(status);
    ui.gap(overlay, 10);
    LinearLayout actions = ui.row();
    overlay.addView(actions);
    ui.action(actions, "Cast", () -> showCast(playingEntry), false);
    if (nativePlan != null)
      ui.action(
          actions,
          "Next queue item",
          () -> {
            if (advancing) return;
            reportPlayback("skip");
            advancing = true;
            nativeIndex++;
            loadNativeItem();
          },
          false);
    ui.action(actions, "Back to title", this::back, false);
    root.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    setContentView(root);
    player =
        new ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(
                    PlexDataSource.factory(
                        playingOrigin.isEmpty() ? server() : playingOrigin, secret)))
            .build();
    mediaSession = new androidx.media3.session.MediaSession.Builder(this, player).build();
    playerView.setPlayer(player);
    playerView.setShowSubtitleButton(true);
    installPlaybackMenus();
    roundPlayerControls(playerView);
    rewind.reset();
    playerView.setControllerShowTimeoutMs(3500);
    playerView.setControllerVisibilityListener(
        (androidx.media3.ui.PlayerView.ControllerVisibilityListener)
            visibility -> overlay.setVisibility(visibility));
    player.addListener(
        new Player.Listener() {
          @Override
          public void onVideoSizeChanged(VideoSize size) {
            status.setText("Decoded video: " + size.width + " × " + size.height);
          }

          @Override
          public void onPlayWhenReadyChanged(boolean ready, int reason) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
              if (!ready) {
                rewind.pause();
                reportPlayback("paused");
              } else {
                long currentPosition = player.getCurrentPosition(),
                    resumePosition =
                        rewind.resume(
                            currentPosition, sectionFloor, prefs().getInt("rewind-seconds", 3));
                if (resumePosition != currentPosition) player.seekTo(resumePosition);
                if (player.getPlaybackState() == Player.STATE_READY) reportPlayback("playing");
              }
            }
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY)
              reportPlayback(player.getPlayWhenReady() ? "playing" : "paused");
            if (state == Player.STATE_ENDED) {
              reportPlayback("ended");
              if (nativePlan != null && !advancing) {
                advancing = true;
                nativeIndex++;
                loadNativeItem();
              }
            }
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            reportPlayback("error");
            overlay.setVisibility(View.VISIBLE);
            status.setText(
                "This file couldn't play directly. Server conversion is not available in this"
                    + " preview.");
          }
        });
    player.setMediaItem(
        new MediaItem.Builder()
            .setUri(url)
            .setMediaId(playingEntry == null ? "video" : playingEntry.key())
            .setMediaMetadata(
                new androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
            .build());
    player.prepare();
    player.play();
    playerView.requestFocus();
    final ExoPlayer observedPlayer = player;
    handler.postDelayed(
        new Runnable() {
          private long reportedAt = 0;

          public void run() {
            if (player == null || player != observedPlayer) return;
            if (nativePlan != null
                && sectionEnd >= 0
                && player.getCurrentPosition() >= sectionEnd
                && !advancing) {
              reportPlayback("section-end");
              advancing = true;
              nativeIndex++;
              loadNativeItem();
              return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - reportedAt >= 10000) {
              reportPlayback(player.isPlaying() ? "playing" : "paused");
              reportedAt = now;
            }
            handler.postDelayed(this, 250);
          }
        },
        250);
  }

  private void demoSeason() {
    shell("Series");
    heading("Fictional demo · Season 1", "Orbit Nine");
    status.setText("Specials stay in viewing order. Episode numbers stay the same.");
    LinearLayout row = horizontal();
    Set<String> watched = completed();
    StoryOrder.Item next = demo.next(watched);
    for (StoryOrder.Item item : demo.items())
      row.addView(
          ui.card(
              watched.contains(item.positionKey())
                  ? "Watched"
                  : next != null && item.positionKey().equals(next.positionKey())
                      ? "Up next"
                      : "Season 1",
              item.label(),
              item.title(),
              "View demo episode",
              () -> {
                TvPanel d =
                    panel()
                        .setTitle(item.label() + " · " + item.title())
                        .setMessage(
                            "This fictional sequence demonstrates ordering and profile progress. It"
                                + " doesn't play library media.")
                        .setPositiveButton(
                            "Mark watched",
                            (dialog, which) -> {
                              Set<String> done = completed();
                              done.add(item.positionKey());
                              prefs().edit().putStringSet("done:" + activeId, done).apply();
                              render(new Screen("demo"));
                            })
                        .setNegativeButton("Back", null)
                        .create();
                d.show();
                d.setPanelSize(dp(540), -2);
              },
              (mainWidth - 48) / 3));
    focusFirst(row.getChildAt(0));
    ui.gap(content, 24);
    content.addView(
        ui.button(
            "Reset demo progress",
            () -> {
              prefs().edit().remove("done:" + activeId).apply();
              render(new Screen("demo"));
            },
            false));
  }

  private void queues() {
    shell("Watching");
    heading("QueuePilot", "Your watching queues");
    String origin = prefs().getString("queue-origin", "");
    if (origin.isEmpty()) {
      status.setText("Connect your QueuePilot server to see your queues here.");
      content.addView(ui.button("Connect QueuePilot", this::queueSetup, true));
      return;
    }
    Set<String> selected = new HashSet<>(prefs().getStringSet("queue-people", Set.of()));
    ui.action(content, "Choose people", this::chooseQueuePeople, false);
    status.setText(selected.isEmpty() ? "All Plex queues · loading…" : "Finding your Plex queues…");
    int request = generation;
    network.execute(
        () -> {
          try {
            String path =
                "/api/native/queues?people="
                    + PlexAccount.encode(String.join(",", new TreeSet<>(selected)));
            JSONObject response;
            try {
              response = NativeQueueApi.request(origin, path, null, "", "");
              java.nio.file.Files.write(
                  cacheFile("native-tv", origin, path).toPath(),
                  response.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception offline) {
              response =
                  new JSONObject(
                      new String(
                          java.nio.file.Files.readAllBytes(
                              cacheFile("native-tv", origin, path).toPath()),
                          StandardCharsets.UTF_8));
              response.put("cached", true);
            }
            JSONObject result = response;
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  queuePeople = result.optJSONArray("people");
                  if (queuePeople == null) queuePeople = new JSONArray();
                  JSONArray queues = result.optJSONArray("queues");
                  status.setText(
                      (result.optBoolean("cached")
                              ? "Saved queues · reconnect to start playback · "
                              : "")
                          + (selected.isEmpty() ? "All people" : selectedPeopleLabel(selected))
                          + " · "
                          + (queues == null ? 0 : queues.length())
                          + " Plex queues");
                  if (queues == null || queues.length() == 0) {
                    content.addView(
                        ui.text(
                            "No matching queues. Choose different people or clear the filter.",
                            17,
                            TvUi.MUTED));
                    return;
                  }
                  LinearLayout row = null;
                  for (int i = 0; i < queues.length(); i++) {
                    if (i % 3 == 0) {
                      row = ui.row();
                      content.addView(row);
                      ui.gap(content, 18);
                    }
                    JSONObject queue = queues.optJSONObject(i);
                    if (queue == null) continue;
                    LinearLayout card =
                        ui.card(
                            queue.optString("kind").equals("rules") ? "Rules" : "Picks",
                            queue.optString("title", "Movies & Shows"),
                            queueAudience(queue),
                            "Play queue",
                            () -> startNativeQueue(queue),
                            (mainWidth - 48) / 3);
                    LinearLayout faces = ui.row();
                    JSONArray people = queue.optJSONArray("people");
                    if (people != null)
                      for (int j = 0; j < Math.min(4, people.length()); j++) {
                        String id = people.optString(j);
                        ImageView face = avatar(36);
                        faces.addView(face);
                        loadQueueAvatar(face, id, origin);
                      }
                    card.addView(faces, 1);
                    row.addView(card);
                  }
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (request == generation)
                    status.setText("QueuePilot couldn't load the TV queues. " + error.getMessage());
                });
          }
        });
  }

  private String selectedPeopleLabel(Set<String> ids) {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < queuePeople.length(); i++) {
      JSONObject p = queuePeople.optJSONObject(i);
      if (p != null && ids.contains(p.optString("id"))) names.add(p.optString("displayName"));
    }
    return String.join(" + ", names);
  }

  private String queueAudience(JSONObject queue) {
    Set<String> ids = new HashSet<>();
    JSONArray list = queue.optJSONArray("people");
    if (list != null) for (int i = 0; i < list.length(); i++) ids.add(list.optString(i));
    String people = selectedPeopleLabel(ids);
    return people.isEmpty() ? "For everyone" : people;
  }

  private void chooseQueuePeople() {
    Set<String> selected = new HashSet<>(prefs().getStringSet("queue-people", Set.of()));
    LinearLayout rows = ui.column();
    rows.setPadding(dp(20), dp(12), dp(20), dp(12));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(rows);
    TvPanel dialog =
        panel()
            .setTitle("Who are these queues for?")
            .setMessage(
                "Select people to narrow the queues. This does not change the Plex playback"
                    + " profile.")
            .setView(scroll)
            .setPositiveButton(
                "Apply",
                (d, w) -> {
                  prefs().edit().putStringSet("queue-people", selected).apply();
                  render(new Screen("queues"));
                })
            .setNeutralButton(
                "All people",
                (d, w) -> {
                  prefs().edit().remove("queue-people").apply();
                  render(new Screen("queues"));
                })
            .setNegativeButton("Cancel", null)
            .create();
    for (int i = 0; i < queuePeople.length(); i++) {
      JSONObject person = queuePeople.optJSONObject(i);
      if (person == null) continue;
      String id = person.optString("id");
      LinearLayout row = ui.row();
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(10), dp(8), dp(10), dp(8));
      ImageView face = avatar(42);
      loadQueueAvatar(face, id, prefs().getString("queue-origin", ""));
      row.addView(face);
      TextView choice = ui.text(person.optString("displayName"), 16, TvUi.WHITE);
      choice.setPadding(dp(12), 0, dp(8), 0);
      row.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
      TextView check = ui.text(selected.contains(id) ? "✓" : "○", 20, TvUi.ACCENT);
      check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      row.addView(check);
      row.setContentDescription(person.optString("displayName"));
      row.setStateDescription(selected.contains(id) ? "Selected" : "Not selected");
      ui.focusable(
          row,
          () -> {
            if (!selected.add(id)) selected.remove(id);
            check.setText(selected.contains(id) ? "✓" : "○");
            row.setStateDescription(selected.contains(id) ? "Selected" : "Not selected");
          },
          TvUi.PANEL);
      rows.addView(row);
      ui.gap(rows, 6);
    }
    dialog.show();
    dialog.setPanelSize(dp(390), -2);
  }

  private void playPendingNative() {
    if (pendingNativeQueue != null && activeId.equals(pendingNativeProfile)) {
      JSONObject queue = pendingNativeQueue;
      pendingNativeQueue = null;
      pendingNativeProfile = "";
      startNativeQueue(queue);
    }
  }

  private void startNativeQueue(JSONObject queue) {
    String required = queue.optString("requiredProfile");
    JSONArray users = account.optJSONArray("users");
    if (!required.isEmpty() && users != null) {
      for (int i = 0; i < users.length(); i++) {
        JSONObject user = users.optJSONObject(i);
        if (user == null) continue;
        if (required.equals(user.optString("title"))
            || required.equals(user.optString("username"))) {
          if (!userId(user).equals(activeId)) {
            TvPanel dialog =
                panel()
                    .setTitle("Play as " + user.optString("title", required))
                    .setMessage("This queue saves watch history to that Plex profile.")
                    .setPositiveButton(
                        "Switch and play",
                        (d, w) -> {
                          pendingNativeQueue = queue;
                          pendingNativeProfile = userId(user);
                          selectUser(user);
                        })
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.show();
            dialog.setPanelSize(dp(460), -2);
            return;
          }
          break;
        }
      }
    }
    if (profile == null) {
      message("Connect Plex", "Sign in with Plex before starting a queue.");
      return;
    }
    String origin = prefs().getString("queue-origin", "");
    status.setText("Preparing your queue…");
    int request = generation;
    network.execute(
        () -> {
          try {
            String proof = profile.optString("profileProof");
            if (proof.isEmpty()) {
              JSONObject homeUser = null;
              JSONArray homeRoster = account.optJSONArray("users");
              if (homeRoster != null)
                for (int i = 0; i < homeRoster.length(); i++) {
                  JSONObject u = homeRoster.optJSONObject(i);
                  if (u != null && userId(u).equals(activeId)) homeUser = u;
                }
              if (homeUser == null
                  || (homeUser.optBoolean("protected") && !homeUser.optBoolean("admin")))
                throw new IllegalStateException(
                    "Choose your Plex profile again to authorize native playback.");
              proof =
                  homeUser.optBoolean("admin")
                      ? account.getString("token")
                      : auth.switchUser(account.getString("token"), activeId, "");
              profile.put("profileProof", proof);
              store.write(profiles);
            }
            progressNetwork.submit(this::flushNativeEvents).get();
            JSONObject plan =
                NativeQueueApi.request(
                    origin,
                    "/api/native/sessions",
                    new JSONObject().put("setId", queue.getString("id")),
                    "X-Plex-Profile-Token",
                    proof);
            plan.put("profileProof", proof);
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  nativePlan = plan;
                  nativeIndex = plan.optInt("cursor");
                  nativeSequence = plan.optLong("sequence");
                  loadNativeItem();
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (request == generation) {
                    status.setText("This queue couldn't start.");
                    message("Queue playback", error.getMessage());
                  }
                });
          }
        });
  }

  private void loadNativeItem() {
    advancing = true;
    handler.removeCallbacksAndMessages(null);
    releasePlayer();
    JSONArray items = nativePlan.optJSONArray("items");
    if (items == null || nativeIndex >= items.length()) {
      JSONObject previous = nativePlan;
      int continuationRequest = generation;
      String origin = prefs().getString("queue-origin", "");
      progressNetwork.execute(
          () -> {
            try {
              flushNativeEvents();
              JSONObject next =
                  NativeQueueApi.request(
                      origin,
                      "/api/native/sessions/" + previous.getString("id") + "/continue",
                      new JSONObject(),
                      "X-Native-Session-Key",
                      previous.getString("sessionKey"),
                      previous.optString("profileProof"));
              runOnUiThread(
                  () -> {
                    if (continuationRequest != generation || nativePlan != previous) return;
                    if (next.optBoolean("complete")) {
                      nativePlan = null;
                      render(new Screen("queues"));
                    } else {
                      try {
                        next.put("profileProof", previous.optString("profileProof"));
                      } catch (Exception ignored) {
                      }
                      nativePlan = next;
                      nativeIndex = 0;
                      nativeSequence = 0;
                      loadNativeItem();
                    }
                  });
            } catch (Exception error) {
              runOnUiThread(
                  () -> {
                    if (continuationRequest != generation || nativePlan != previous) return;
                    message("Queue finished", error.getMessage());
                    nativePlan = null;
                    render(new Screen("queues"));
                  });
            }
          });
      return;
    }
    JSONObject item = items.optJSONObject(nativeIndex);
    int request = generation;
    String defaultOrigin = server(), defaultToken = token();
    String serverId = item.optString("plexServer", nativePlan.optString("homeServerId"));
    JSONObject boundProfile = profile;
    network.execute(
        () -> {
          try {
            String origin = defaultOrigin, secret = defaultToken;
            if (!serverId.isEmpty() && !serverId.equals(boundProfile.optString("serverId"))) {
              JSONArray resources = auth.servers(boundProfile.getString("profileProof"));
              JSONObject resource = null;
              for (int i = 0; i < resources.length(); i++)
                if (resources.getJSONObject(i).optString("clientIdentifier").equals(serverId))
                  resource = resources.getJSONObject(i);
              if (resource == null)
                throw new IllegalStateException("This profile cannot access the queued server");
              origin = "";
              secret = resource.getString("accessToken");
              JSONArray connections = resource.getJSONArray("connections");
              for (int i = 0; i < connections.length(); i++) {
                JSONObject c = connections.getJSONObject(i);
                String uri = c.optString("uri");
                if (ServerConnectionPolicy.allowed(
                    uri, c.optBoolean("local"), c.optBoolean("relay"))) {
                  try {
                    PlexCatalog.get(
                        PlexCatalog.url(uri, "/library/sections"), secret, 8 * 1024 * 1024);
                    origin = PlexCatalog.base(uri);
                    break;
                  } catch (Exception ignored) {
                  }
                }
              }
              if (origin.isEmpty())
                throw new IllegalStateException("The queued server is unavailable");
            }
            PlexCatalog.Entry entry =
                PlexCatalog.Entry.fromJson(
                    PlexCatalog.list(
                            PlexCatalog.get(
                                PlexCatalog.url(
                                    origin, "/library/metadata/" + item.getString("ratingKey")),
                                secret,
                                16 * 1024 * 1024))
                        .getJSONObject(0));
            if (entry.part().isEmpty())
              throw new IllegalStateException("This item has no direct playback source");
            String mediaOrigin = origin, mediaToken = secret;
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  playingEntry = entry;
                  playingOrigin = mediaOrigin;
                  playingToken = mediaToken;
                  sectionFloor = item.optLong("sectionStartMs", 0);
                  sectionEnd =
                      item.has("sectionEndMs") && !item.isNull("sectionEndMs")
                          ? item.optLong("sectionEndMs")
                          : -1;
                  startPlayer(
                      PlexCatalog.url(mediaOrigin, entry.part()),
                      mediaToken,
                      entry.title(),
                      entry.cast());
                  advancing = false;
                  player.seekTo(item.optLong("resumeMs", 0));
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (request == generation) {
                    reportPlayback("error");
                    message("Queue playback", error.getMessage());
                  }
                });
          }
        });
  }

  private void queueSetup() {
    LinearLayout form = ui.column();
    form.setPadding(dp(24), dp(8), dp(24), dp(8));
    EditText input = new EditText(this);
    input.setSingleLine();
    input.setHint("https://queues.example.com");
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    input.setText(prefs().getString("queue-origin", ""));
    form.addView(input, new LinearLayout.LayoutParams(-1, dp(54)));
    TvPanel d =
        panel()
            .setTitle("Connect QueuePilot")
            .setMessage("Enter the address you use to open QueuePilot.")
            .setView(form)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create();
    d.setOnShowListener(
        v -> {
          d.setPanelSize(dp(470), -2);
          d.getButton(TvPanel.BUTTON_POSITIVE)
              .setOnClickListener(
                  b -> {
                    try {
                      String origin = PlexCatalog.base(input.getText().toString());
                      prefs().edit().putString("queue-origin", origin).apply();
                      d.dismiss();
                      navigate(new Screen("queues"));
                    } catch (Exception e) {
                      input.setError("Enter a complete http:// or https:// address");
                    }
                  });
        });
    d.show();
  }

  private void settings() {
    shell("Settings");
    heading("QueuePilot TV", "Settings");
    status.setText("Playback preview · 0.4.0");
    LinearLayout row = horizontal();
    row.addView(
        ui.card(
            "Plex account",
            account.optString("name", "Connect Plex"),
            "Sign in on your phone using a TV code.",
            "Sign in with Plex",
            () -> navigate(new Screen("signin")),
            270));
    row.addView(
        ui.card(
            "QueuePilot",
            "Your queue server",
            "Connect the server that manages your watching queues.",
            "Connect QueuePilot",
            this::queueSetup,
            270));
    row.addView(
        ui.card(
            "Plex Home",
            "Who's watching?",
            "Choose a profile and its library access.",
            "Switch profile",
            () -> navigate(new Screen("profiles")),
            270));
    ui.gap(content, 22);
    TextView rewindButton = ui.button(rewindLabel(), this::rewindSetting, false);
    rewindButton.setTag("rewind-setting");
    content.addView(rewindButton);
    focusFirst(row.getChildAt(0));
  }

  private String rewindLabel() {
    int seconds = prefs().getInt("rewind-seconds", 3);
    return "Rewind on resume · " + (seconds == 0 ? "Off" : seconds + " seconds");
  }

  private void rewindSetting() {
    String[] choices = {"Off", "1 second", "3 seconds", "5 seconds", "10 seconds"};
    int[] values = {0, 1, 3, 5, 10};
    TvPanel dialog =
        panel()
            .setTitle("Rewind on resume")
            .setMessage("Replay a little when you continue after pausing.")
            .setSelected(java.util.Arrays.binarySearch(values, prefs().getInt("rewind-seconds", 3)))
            .setItems(
                choices,
                (d, i) -> {
                  prefs().edit().putInt("rewind-seconds", values[i]).apply();
                  TextView button = getWindow().getDecorView().findViewWithTag("rewind-setting");
                  if (button != null) button.setText(rewindLabel());
                })
            .setNegativeButton("Cancel", null)
            .create();
    dialog.show();
    dialog.setPanelSize(dp(330), -2);
  }

  private ImageView avatar(int size) {
    ImageView face = new ImageView(this);
    face.setScaleType(ImageView.ScaleType.CENTER_CROP);
    face.setBackground(ui.shape(TvUi.PANEL, 0, size));
    face.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    face.setClipToOutline(true);
    face.setOutlineProvider(
        new ViewOutlineProvider() {
          @Override
          public void getOutline(View view, android.graphics.Outline outline) {
            outline.setOval(0, 0, view.getWidth(), view.getHeight());
          }
        });
    face.setImageResource(android.R.drawable.ic_menu_myplaces);
    return face;
  }

  private void loadPicture(ImageView face, String address, String secret) {
    if (address.isEmpty()) return;
    int request = generation;
    network.execute(
        () -> {
          try {
            byte[] bytes =
                secret.isEmpty()
                    ? AvatarImage.get(address)
                    : PlexCatalog.get(address, secret, 4 * 1024 * 1024);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0
                || bounds.outWidth > 4096
                || bounds.outHeight <= 0
                || bounds.outHeight > 6144) return;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 512)
              options.inSampleSize = options.inSampleSize == 0 ? 2 : options.inSampleSize * 2;
            Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            runOnUiThread(
                () -> {
                  if (request == generation) face.setImageBitmap(image);
                });
          } catch (Exception ignored) {
          }
        });
  }

  private void loadProfileAvatar(ImageView face, String thumb) {
    if (thumb.isEmpty()) return;
    if (fixture && thumb.startsWith("/")) {
      loadPicture(face, "http://10.0.2.2:9010" + thumb, "");
      return;
    }
    try {
      java.net.URI uri = java.net.URI.create(thumb);
      if (!"https".equals(uri.getScheme())) return;
      loadPicture(face, thumb, "");
    } catch (Exception ignored) {
    }
  }

  private void loadQueueAvatar(ImageView face, String id, String origin) {
    loadPicture(
        face,
        PlexCatalog.url(origin, "/api/native/people/" + PlexAccount.encode(id) + "/avatar"),
        "");
  }

  private void showCast(PlexCatalog.Entry entry) {
    if (entry == null || entry.roles().length() == 0) {
      message("Title cast", "No cast information is available for this title.");
      return;
    }
    ScrollView scroll = new ScrollView(this);
    LinearLayout list = ui.column();
    list.setPadding(dp(20), dp(12), dp(20), dp(20));
    scroll.addView(list);
    LinearLayout row = null;
    for (int i = 0; i < entry.roles().length(); i++) {
      if (i % 4 == 0) {
        row = ui.row();
        list.addView(row);
        ui.gap(list, 20);
      }
      JSONObject actor = entry.roles().optJSONObject(i);
      if (actor == null) continue;
      LinearLayout card = ui.column();
      ImageView face = avatar(100);
      card.addView(face);
      String thumb = actor.optString("thumb");
      if (thumb.startsWith("/")) {
        String origin = playingOrigin, secret = playingToken;
        int request = generation;
        network.execute(
            () -> {
              try {
                Bitmap image = artwork(origin, secret, thumb, 360);
                runOnUiThread(
                    () -> {
                      if (request == generation) face.setImageBitmap(image);
                    });
              } catch (Exception ignored) {
              }
            });
      } else if (thumb.startsWith("https://")) loadPicture(face, thumb, "");
      ui.gap(card, 10);
      card.addView(ui.title(actor.optString("name"), 15));
      ui.gap(card, 5);
      card.addView(ui.text(actor.optString("role"), 12, TvUi.MUTED));
      ui.focusable(
          card, () -> message(actor.optString("name"), actor.optString("role")), TvUi.PANEL);
      card.setContentDescription(actor.optString("name") + ". " + actor.optString("role"));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(128), -2);
      lp.setMargins(0, 0, dp(15), 0);
      row.addView(card, lp);
    }
    TvPanel dialog =
        panel()
            .setTitle("Cast · " + entry.title())
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create();
    dialog.show();
    dialog.setPanelSize(dp(Math.min(680, 84 + 143 * Math.min(4, entry.roles().length()))), -2);
  }

  private void roundPlayerControls(View view) {
    if (view.isClickable()
        && view.isFocusable()
        && !(view instanceof androidx.media3.ui.DefaultTimeBar)
        && !(view instanceof PlayerView)) {
      view.setForeground(null);
      android.graphics.drawable.StateListDrawable states =
          new android.graphics.drawable.StateListDrawable();
      states.addState(
          new int[] {android.R.attr.state_focused}, ui.shape(0x66202630, TvUi.ACCENT, 50));
      states.addState(new int[] {android.R.attr.state_pressed}, ui.shape(0x88202630, 0, 50));
      states.addState(new int[] {}, ui.shape(Color.TRANSPARENT, 0, 50));
      if (view.getId() == androidx.media3.ui.R.id.exo_rew_with_amount
          || view.getId() == androidx.media3.ui.R.id.exo_ffwd_with_amount) {
        android.graphics.drawable.Drawable icon = view.getBackground();
        view.setBackgroundTintList(null);
        view.setBackground(
            new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {states, icon}));
      } else view.setBackground(states);
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) roundPlayerControls(group.getChildAt(i));
    }
  }

  private void closePanels() {
    for (TvPanel panel : new ArrayList<>(panels)) panel.dismiss();
    panels.clear();
  }

  private TvPanel.Builder panel() {
    TvPanel.Builder builder = new TvPanel.Builder(this).setLifecycle(panels::add, panels::remove);
    if (playerView != null) {
      View anchor = getCurrentFocus();
      if (anchor == null || anchor == playerView)
        anchor = playerView.findViewById(androidx.media3.ui.R.id.exo_settings);
      return playbackPanel(builder, anchor);
    }
    return builder;
  }

  private TvPanel.Builder playbackPanel(TvPanel.Builder builder, View anchor) {
    PlayerView controls = playerView;
    return builder.setAnchor(
        anchor,
        () -> {
          controls.setControllerShowTimeoutMs(0);
          controls.showController();
        },
        () -> {
          if (playerView == controls && panels.isEmpty()) {
            controls.setControllerShowTimeoutMs(3500);
            controls.showController();
          }
        });
  }

  private TvPanel.Builder playbackPanel(View anchor) {
    return playbackPanel(
        new TvPanel.Builder(this).setLifecycle(panels::add, panels::remove), anchor);
  }

  private void installPlaybackMenus() {
    bindPlaybackMenu(androidx.media3.ui.R.id.exo_settings, this::playbackOptions);
    bindPlaybackMenu(androidx.media3.ui.R.id.exo_subtitle,
        anchor -> trackOptions(anchor, androidx.media3.common.C.TRACK_TYPE_TEXT));
    bindPlaybackMenu(androidx.media3.ui.R.id.exo_audio_track,
        anchor -> trackOptions(anchor, androidx.media3.common.C.TRACK_TYPE_AUDIO));
    bindPlaybackMenu(androidx.media3.ui.R.id.exo_playback_speed, this::speedOptions);
  }

  private void bindPlaybackMenu(int id, java.util.function.Consumer<View> open) {
    View control = playerView.findViewById(id);
    if (control == null) return;
    control.setOnClickListener(view -> {
      view.requestFocusFromTouch();
      open.accept(view);
    });
  }

  private void playbackOptions(View anchor) {
    if (player == null) return;
    playbackPanel(anchor)
        .setTitle("Playback")
        .setItems(
            new String[] {"Audio", "Subtitles", "Playback speed"},
            (d, index) -> {
              if (index == 2) speedOptions(anchor);
              else
                trackOptions(
                    anchor,
                    index == 0
                        ? androidx.media3.common.C.TRACK_TYPE_AUDIO
                        : androidx.media3.common.C.TRACK_TYPE_TEXT);
            })
        .setNegativeButton("Close", null)
        .create()
        .show();
  }

  private void speedOptions(View anchor) {
    if (player == null) return;
    ExoPlayer target = player;
    float[] speeds = {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
    String[] labels = {"0.5×", "0.75×", "Normal · 1×", "1.25×", "1.5×", "2×"};
    int selected = -1;
    for (int i = 0; i < speeds.length; i++)
      if (Math.abs(target.getPlaybackParameters().speed - speeds[i]) < .001f) selected = i;
    playbackPanel(anchor)
        .setTitle("Playback speed")
        .setSelected(selected)
        .setItems(
            labels,
            (d, i) -> {
              if (player == target) target.setPlaybackSpeed(speeds[i]);
            })
        .setNegativeButton("Back", (d, i) -> playbackOptions(anchor))
        .create()
        .show();
  }

  private void trackOptions(View anchor, int type) {
    if (player == null) return;
    ExoPlayer target = player;
    boolean text = type == androidx.media3.common.C.TRACK_TYPE_TEXT;
    List<String> labels = new ArrayList<>();
    List<androidx.media3.common.TrackSelectionOverride> overrides = new ArrayList<>();
    labels.add(text ? "Off" : "Automatic");
    overrides.add(null);
    int selected = 0;
    boolean disabled = target.getTrackSelectionParameters().disabledTrackTypes.contains(type);
    boolean automatic =
        !text
            && target.getTrackSelectionParameters().overrides.values().stream()
                .noneMatch(override -> override.getType() == type);
    androidx.media3.ui.DefaultTrackNameProvider names =
        new androidx.media3.ui.DefaultTrackNameProvider(getResources());
    for (androidx.media3.common.Tracks.Group group : target.getCurrentTracks().getGroups()) {
      if (group.getType() != type) continue;
      for (int i = 0; i < group.length; i++) {
        if (!group.isTrackSupported(i)) continue;
        androidx.media3.common.Format format = group.getTrackFormat(i);
        String name = names.getTrackName(format);
        if (format.label != null && !name.contains(format.label)) name += " · " + format.label;
        labels.add(name);
        overrides.add(
            new androidx.media3.common.TrackSelectionOverride(group.getMediaTrackGroup(), i));
        if (!disabled && !automatic && group.isTrackSelected(i)) selected = labels.size() - 1;
      }
    }
    // Containers do not always expose track titles; never show indistinguishable rows.
    Map<String, Integer> counts = new HashMap<>();
    for (String label : labels) counts.merge(label, 1, Integer::sum);
    for (int i = 1; i < labels.size(); i++) {
      String label = labels.get(i);
      if (counts.get(label) > 1) labels.set(i, label + " · Track " + i);
    }
    TvPanel.Builder builder =
        playbackPanel(anchor)
            .setTitle(text ? "Subtitles" : "Audio")
            .setSelected(selected)
            .setItems(
                labels.toArray(new String[0]),
                (d, i) -> {
                  if (player != target) return;
                  androidx.media3.common.TrackSelectionParameters.Builder parameters =
                      target
                          .getTrackSelectionParameters()
                          .buildUpon()
                          .clearOverridesOfType(type)
                          .setTrackTypeDisabled(type, text && i == 0);
                  if (i > 0) parameters.setOverrideForType(overrides.get(i));
                  target.setTrackSelectionParameters(parameters.build());
                })
            .setNegativeButton("Back", (d, i) -> playbackOptions(anchor));
    if (labels.size() == 1)
      builder.setMessage(
          text ? "No subtitle tracks in this video." : "No selectable audio tracks in this video.");
    builder.create().show();
  }

  private void reportPlayback(String state) {
    if (player == null || playingEntry == null) return;
    long position = Math.max(0, player.getCurrentPosition()),
        duration = Math.max(0, player.getDuration());
    String origin = playingOrigin, secret = playingToken, key = playingEntry.key();
    JSONObject plan = nativePlan;
    if (plan != null) {
      try {
        JSONObject event =
            new JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("sequence", ++nativeSequence)
                .put("index", nativeIndex)
                .put("state", state)
                .put("positionMs", position)
                .put("durationMs", duration);
        String queueOrigin = prefs().getString("queue-origin", "");
        JSONObject envelope =
            new JSONObject()
                .put("origin", queueOrigin)
                .put("sessionId", plan.getString("id"))
                .put("sessionKey", plan.getString("sessionKey"))
                .put("profileProof", plan.optString("profileProof"))
                .put("event", event);
        synchronized (store) {
          JSONArray pending = store.nativeEvents();
          if (pending.length() > 0) {
            JSONObject last = pending.optJSONObject(pending.length() - 1);
            JSONObject previous = last == null ? null : last.optJSONObject("event");
            if (previous != null
                && last.optString("sessionId").equals(envelope.optString("sessionId"))
                && previous.optInt("index") == nativeIndex
                && state.equals("playing")
                && previous.optString("state").equals("playing"))
              pending.remove(pending.length() - 1);
          }
          pending.put(envelope);
          store.nativeEvents(pending);
        }
        progressNetwork.execute(this::flushNativeEvents);
      } catch (Exception ignored) {
      }
    } else {
      String client = prefs().getString("plex-client-id", "");
      progressNetwork.execute(
          () -> {
            try {
              String path =
                  "/:/timeline?type=video&ratingKey="
                      + PlexAccount.encode(key)
                      + "&key="
                      + PlexAccount.encode("/library/metadata/" + key)
                      + "&state="
                      + PlexAccount.encode(
                          Set.of("ended", "error").contains(state) ? "stopped" : state)
                      + "&time="
                      + position
                      + "&duration="
                      + duration
                      + "&X-Plex-Client-Identifier="
                      + PlexAccount.encode(client)
                      + "&X-Plex-Product=QueuePilot%20TV&X-Plex-Platform=Android";
              PlexCatalog.get(PlexCatalog.url(origin, path), secret, 1024 * 1024);
            } catch (Exception ignored) {
            }
          });
    }
  }

  private void flushNativeEvents() {
    for (int attempt = 0; attempt < 4000; attempt++) {
      JSONObject first = null;
      try {
        synchronized (store) {
          JSONArray pending = store.nativeEvents();
          for (int i = 0; i < pending.length(); i++) {
            JSONObject candidate = pending.getJSONObject(i);
            if (!candidate.has("failed")) {
              first = candidate;
              break;
            }
          }
          if (first == null) return;
        }
        JSONObject event = first.getJSONObject("event");
        NativeQueueApi.request(
            first.getString("origin"),
            "/api/native/sessions/" + first.getString("sessionId") + "/events",
            event,
            "X-Native-Session-Key",
            first.getString("sessionKey"),
            first.optString("profileProof"));
        synchronized (store) {
          JSONArray pending = store.nativeEvents();
          JSONArray remaining = new JSONArray();
          for (int i = 0; i < pending.length(); i++) {
            JSONObject record = pending.getJSONObject(i);
            if (!record.getJSONObject("event").optString("id").equals(event.getString("id")))
              remaining.put(record);
          }
          store.nativeEvents(remaining);
        }
      } catch (Exception error) {
        if (first != null
            && error instanceof NativeQueueApi.Failure
            && Set.of(400, 403, 409).contains(((NativeQueueApi.Failure) error).status)) {
          try {
            synchronized (store) {
              JSONArray pending = store.nativeEvents();
              for (int i = 0; i < pending.length(); i++) {
                JSONObject record = pending.getJSONObject(i);
                if (record.optString("sessionId").equals(first.optString("sessionId")))
                  record.put("failed", error.getMessage());
              }
              store.nativeEvents(pending);
            }
          } catch (Exception ignored) {
          }
          runOnUiThread(
              () -> {
                if (player != null && status != null)
                  status.setText(
                      "Some saved history needs review. Its playback session is no longer valid.");
              });
          continue;
        }
        runOnUiThread(
            () -> {
              if (player != null && status != null)
                status.setText("Progress is saved locally and waiting to sync.");
            });
        return;
      }
    }
  }

  private void message(String title, String text) {
    TvPanel d = panel().setTitle(title).setMessage(text).setPositiveButton("Close", null).create();
    d.show();
    d.setPanelSize(dp(360), -2);
  }

  private void releasePlayer() {
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (player != null) {
      if (!advancing) reportPlayback("stopped");
      if (mediaSession != null) {
        mediaSession.release();
        mediaSession = null;
      }
      player.release();
      player = null;
    }
    playerView = null;
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (player != null) {
      reportPlayback("paused");
      rewind.pause();
      player.pause();
    }
  }

  @Override
  protected void onDestroy() {
    closePanels();
    generation++;
    handler.removeCallbacksAndMessages(null);
    releasePlayer();
    network.shutdownNow();
    progressNetwork.shutdown();
    super.onDestroy();
  }
}
