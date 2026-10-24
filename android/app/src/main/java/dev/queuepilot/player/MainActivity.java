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
  private AssPlayback assPlayback;
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
  private boolean isCastLoading;
  private TextView skipButton;
  private List<MarkerSkip.Marker> playingMarkers = List.of();
  private MarkerSkip.Marker skipMarker;
  private long skipShownAt;
  private boolean isSkipDismissed;
  private boolean isControllerVisible;
  private String playingOrigin = "", playingToken = "";
  /** The playing poster for the media session: JPEG bytes, and a tokenless LAN address. */
  private byte[] playingArtwork;

  private String playingArtworkUri = "";
  private long sectionFloor, sectionEnd = -1;
  private boolean advancing;
  private final Set<TvPanel> panels = new HashSet<>();
  private final RewindOnResume rewind = new RewindOnResume();
  /** The catalog entry of the queue being played, saved with the profile's resume snapshot. */
  private JSONObject nativeQueue;
  private JSONArray queuePeople = new JSONArray();
  /** Every queue the catalog has listed, by id, so a full queue or history can start one. */
  private final Map<String, JSONObject> knownQueues = new HashMap<>();
  /** "continue" or "only" when the playing session began at a chosen item, else empty. */
  private String nativeStartMode = "";
  /** The full queue's watched items are folded into one row until the viewer shows them. */
  private boolean isWatchedShown;
  /** The item to focus when the full queue is drawn again after a change. */
  private String fullQueueFocus = "";
  private int historyDays = 30;

  /**
   * One screen in the Back history. {@code position} is the library item a paged grid is
   * restored around, so Back from a title deep in a library returns to that title.
   */
  private record Screen(
      String kind, String path, String title, PlexCatalog.Entry entry, int position) {
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

  private int displayPercent() {
    return DisplaySize.percent(prefs());
  }

  /** Columns for a grid that has {@code baseline} columns at 100%. */
  private int columns(int baseline) {
    return DisplaySize.columns(baseline, displayPercent());
  }

  private int itemWidth(int columns) {
    return DisplaySize.itemWidth(mainWidth, columns, 14);
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
                View focused = getWindow().getDecorView().findFocus();
                if (playerView == null
                    && isTopLevel()
                    && railSelected != null
                    && rail.isAttachedToWindow()
                    && focused != null
                    && !isInRail(focused)) {
                  railSelected.requestFocus();
                  return;
                }
                back();
              }
            });
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    getWindow()
        .getDecorView()
        .getViewTreeObserver()
        .addOnGlobalFocusChangeListener(this::railFocusMoved);
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
    NativeQueueApi.deviceId = clientId;
    try {
      profiles = store.read();
      account = store.account();
    } catch (Exception e) {
      Toast.makeText(this, "Please sign in again to restore access.", Toast.LENGTH_LONG).show();
    }
    setProfile(prefs().getString("active", "demo-ada"));
    if (profile != null || prefs().getBoolean("preview", false)) home();
    else if (!account.optString("token").isEmpty()) openOwnProfile();
    else render(new Screen("welcome"));
  }

  /**
   * After sign-in, opens the signed-in account's own Plex profile without a choice: queues play
   * as the profile each queue names, so nobody has to pick a profile to reach them. The picker
   * stays one press away on the rail avatar, for library browsing, search, and single titles,
   * which use the chosen profile. A PIN-protected own profile still shows the picker.
   */
  private void openOwnProfile() {
    JSONObject own = null;
    JSONArray users = account.optJSONArray("users");
    String uuid = account.optString("uuid");
    for (int i = 0; users != null && i < users.length(); i++) {
      JSONObject user = users.optJSONObject(i);
      if (user == null) continue;
      boolean isOwn = uuid.isEmpty() ? user.optBoolean("admin") : uuid.equals(user.optString("uuid"));
      if (isOwn) own = user;
    }
    if (own == null || own.optBoolean("protected")) render(new Screen("profiles"));
    else selectUser(own);
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
      case "fullqueue" -> fullQueue(screen.path(), screen.title());
      case "history" -> history();
      case "search" -> search(screen);
      default -> drawHome();
    }
  }

  /** The rail shows icons only while focus is in the content, and its labels when focused. */
  private static final int RAIL_COLLAPSED = 76, RAIL_EXPANDED = 186, RAIL_MILLIS = 120;

  private LinearLayout rail;
  private FrameLayout shellRoot;
  private ScrollView contentScroll;
  private View railSelected;
  private final List<View> railLabels = new ArrayList<>();
  private boolean isRailExpanded;
  private android.animation.ValueAnimator railAnimation;

  private void shell(String selected) {
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(TvUi.BG);
    shellRoot = root;
    ScrollView scroll = new ScrollView(this);
    contentScroll = scroll;
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    content = ui.column();
    content.setPadding(dp(36), dp(28), dp(32), dp(36));
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(-1, -1);
    scrollParams.leftMargin = dp(RAIL_COLLAPSED);
    root.addView(scroll, scrollParams);

    // The expanded rail is drawn over the content, so opening it never reflows a grid.
    rail = ui.column();
    railLabels.clear();
    railSelected = null;
    isRailExpanded = false;
    rail.setPadding(dp(12), dp(28), dp(12), dp(22));
    rail.setBackgroundColor(TvTheme.SURFACE_BASE);
    rail.setTag("rail");
    rail.setContentDescription("Navigation, collapsed");
    // Seven entries and the avatar are taller than a 1080p screen at density 320 and the largest
    // Display size, so the rail scrolls to the focused entry when it does not fit.
    ScrollView railScroll = new ScrollView(this);
    railScroll.setFillViewport(true);
    railScroll.setVerticalScrollBarEnabled(false);
    railScroll.addView(rail, new ScrollView.LayoutParams(-1, -1));
    root.addView(railScroll, new FrameLayout.LayoutParams(dp(RAIL_COLLAPSED), -1));
    LinearLayout brand = ui.row();
    brand.setGravity(Gravity.CENTER_VERTICAL);
    brand.setPadding(dp(15), 0, 0, 0);
    TextView mark = ui.title("◉", 20);
    brand.addView(mark);
    TextView name = ui.title("QueuePilot", 20);
    name.setPadding(dp(12), 0, 0, 0);
    name.setSingleLine(true);
    brand.addView(name);
    railLabels.add(name);
    rail.addView(brand);
    ui.gap(rail, 36);
    nav(rail, "Home", R.drawable.lucide_house, selected, () -> home());
    nav(
        rail,
        "Watching",
        R.drawable.lucide_list_video,
        selected,
        () -> navigate(new Screen("queues")));
    nav(
        rail,
        "History",
        R.drawable.lucide_history,
        selected,
        () -> navigate(new Screen("history")));
    nav(rail, "Series", R.drawable.lucide_tv, selected, () -> browseType("show", "Series"));
    nav(rail, "Movies", R.drawable.lucide_film, selected, () -> browseType("movie", "Movies"));
    nav(
        rail,
        "Search",
        R.drawable.lucide_search,
        selected,
        () -> navigate(new Screen("search")));
    View settingsEntry =
        nav(
            rail,
            "Settings",
            R.drawable.lucide_settings,
            selected,
            () -> navigate(new Screen("settings")));
    rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
    // The avatar alone switches profile; the name is spoken, and shown in the switcher.
    ImageView face = avatar(44);
    if (profile != null) {
      String thumb = profile.optString("thumb");
      JSONArray users = account.optJSONArray("users");
      if (thumb.isEmpty() && users != null)
        for (int i = 0; i < users.length(); i++) {
          JSONObject user = users.optJSONObject(i);
          if (user != null && userId(user).equals(activeId)) thumb = user.optString("thumb");
        }
      loadProfileAvatar(face, thumb);
    }
    FrameLayout profileButton = new FrameLayout(this);
    profileButton.setPadding(dp(4), dp(4), dp(4), dp(4));
    profileButton.addView(face);
    ui.focusable(profileButton, () -> navigate(new Screen("profiles")), TvUi.BG);
    profileButton.setBackground(ui.shape(0, 0, 40));
    profileButton.setOnFocusChangeListener(
        (v, focused) -> v.setBackground(ui.shape(0, focused ? TvUi.ACCENT : 0, 40)));
    profileButton.setContentDescription("Switch profile, " + profileName());
    rail.addView(profileButton, new LinearLayout.LayoutParams(-2, -2));
    // The gap above the avatar is tall, so a plain focus search from Settings would reach
    // the nearer content instead; Down and Up stay in the rail.
    settingsEntry.setId(View.generateViewId());
    profileButton.setId(View.generateViewId());
    settingsEntry.setNextFocusDownId(profileButton.getId());
    profileButton.setNextFocusUpId(settingsEntry.getId());
    for (View label : railLabels) label.setVisibility(View.GONE);
    // In Display-size-scaled dp, so a smaller size leaves room for more columns.
    mainWidth =
        Math.max(
            480,
            Math.round(
                    getResources().getDisplayMetrics().widthPixels
                        / (getResources().getDisplayMetrics().density * ui.scale))
                - RAIL_COLLAPSED
                - 68);
    setContentView(root);
  }

  private View nav(LinearLayout rail, String label, int icon, String selected, Runnable action) {
    boolean isSelected = label.equals(selected);
    LinearLayout entry = ui.row();
    entry.setGravity(Gravity.CENTER_VERTICAL);
    entry.setPadding(dp(15), 0, dp(12), 0);
    int color = isSelected ? TvTheme.CONTENT_PRIMARY : TvTheme.CONTENT_MUTED;
    ImageView glyph = ui.icon(icon, color, 22);
    entry.addView(glyph);
    TextView text = ui.text(label, 16, color);
    text.setTypeface(ui.medium());
    text.setPadding(dp(14), 0, 0, 0);
    text.setSingleLine(true);
    text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    entry.addView(text);
    railLabels.add(text);
    entry.setContentDescription(label);
    int fill = isSelected ? TvTheme.NEUTRAL_SURFACE : Color.TRANSPARENT;
    ui.focusable(entry, action, fill, 8);
    // Focus fills the entry with the accent and draws the icon and label in the background
    // color: the "filled" state, using only existing colors.
    entry.setOnFocusChangeListener(
        (view, focused) -> {
          view.setBackground(ui.shape(focused ? TvTheme.ACCENT_SOLID : fill, 0, 8));
          int ink = focused ? TvTheme.ACCENT_ON_SOLID : color;
          glyph.setImageTintList(android.content.res.ColorStateList.valueOf(ink));
          text.setTextColor(ink);
        });
    if (isSelected) {
      entry.setSelected(true);
      railSelected = entry;
    }
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
    lp.bottomMargin = dp(5);
    rail.addView(entry, lp);
    return entry;
  }

  private boolean isInRail(View view) {
    for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null)
      if (v == rail) return true;
    return false;
  }

  /** Expands the rail when focus moves onto it, and collapses it when focus enters content. */
  private void railFocusMoved(View from, View to) {
    if (rail == null || to == null || !rail.isAttachedToWindow()) return;
    boolean isRail = isInRail(to);
    if (isRail && from != null && !isInRail(from) && railSelected != null && to != railSelected) {
      View target = railSelected;
      target.post(target::requestFocus);
    }
    setRailExpanded(isRail);
  }

  private void setRailExpanded(boolean isExpanded) {
    if (isExpanded == isRailExpanded) return;
    isRailExpanded = isExpanded;
    if (railAnimation != null) railAnimation.cancel();
    View target = (View) rail.getParent();
    int from = target.getWidth(), to = dp(isExpanded ? RAIL_EXPANDED : RAIL_COLLAPSED);
    if (isExpanded) for (View label : railLabels) label.setVisibility(View.VISIBLE);
    // The open rail floats over the content: elevation.high, and the scrim on the content.
    target.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
    target.setElevation(isExpanded ? dp(TvTheme.RAIL_ELEVATION) : 0);
    if (contentScroll != null)
      contentScroll.setForeground(
          isExpanded ? new android.graphics.drawable.ColorDrawable(TvTheme.SCRIM) : null);
    railAnimation = android.animation.ValueAnimator.ofInt(from, to);
    railAnimation.setDuration(RAIL_MILLIS);
    railAnimation.addUpdateListener(
        a -> {
          target.getLayoutParams().width = (int) a.getAnimatedValue();
          target.requestLayout();
        });
    railAnimation.addListener(
        new android.animation.AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(android.animation.Animator animation) {
            if (!isRailExpanded) for (View label : railLabels) label.setVisibility(View.GONE);
          }
        });
    railAnimation.start();
    rail.setContentDescription(isExpanded ? "Navigation, expanded" : "Navigation, collapsed");
  }

  /** Top-level screens send Back from their content to the rail rather than leaving. */
  private boolean isTopLevel() {
    if (current == null) return false;
    return switch (current.kind()) {
      case "home", "queues", "history", "settings" -> true;
      case "library" -> "/library/sections".equals(current.path());
      case "search" -> current.path().isEmpty();
      default -> false;
    };
  }

  private void heading(String eyebrow, String title) {
    heading(eyebrow, title, 34);
  }

  private TextView heading(String eyebrow, String title, int size) {
    if (!eyebrow.isEmpty()) {
      content.addView(ui.text(eyebrow.toUpperCase(Locale.ROOT), 12, TvUi.ACCENT));
      ui.gap(content, 8);
    }
    TextView heading = ui.title(title, size);
    content.addView(heading);
    ui.gap(content, 8);
    status = ui.text("", 14, TvUi.MUTED);
    content.addView(status);
    ui.gap(content, 22);
    return heading;
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
    content.setBackground(ui.shape(TvTheme.SURFACE_RAISED, 0, 16));
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
              "Your library",
              "Browse something new",
              profile.optString("serverName", "Your media server"),
              "Browse library",
              () -> navigate(new Screen("library", "/library/sections", "Your library", null)),
              width));
      homeQueueCards(cards, width);
    }
    if (profile == null || prefs().getString("queue-origin", "").isEmpty()) {
      ui.primaryCard(cards.getChildAt(0));
      focusFirst(cards.getChildAt(0));
    }
    section("Continue your story");
    if (profile == null) {
      demoHero();
      section("Browse your library");
      demoPosters();
    } else {
      JSONObject snapshot = queueSnapshot();
      if (snapshot != null) {
        content.addView(queueHero(snapshot));
        ui.gap(content, 16);
      }
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
                      "Browse titles on " + profile.optString("serverName", "your server") + ".",
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

  /**
   * Queues come first on Home: every queue, grouped by the profile it plays as, each with its
   * audience as text. Without a QueuePilot connection a single card offers to connect one.
   */
  private void homeQueueCards(LinearLayout cards, int width) {
    String origin = prefs().getString("queue-origin", "");
    if (origin.isEmpty()) {
      cards.addView(
          ui.card(
              "QueuePilot",
              "Connect your queues",
              "Your QueuePilot watching queues will appear here.",
              "Connect QueuePilot",
              () -> navigate(new Screen("queues")),
              width),
          0);
      return;
    }
    int request = generation;
    // The rows are added where the placeholder sits, above Continue and the library rows.
    LinearLayout queueArea = ui.column();
    ViewGroup cardsParent = (ViewGroup) cards.getParent();
    ViewGroup outer = (ViewGroup) cardsParent.getParent();
    outer.addView(queueArea, outer.indexOfChild(cardsParent));
    TextView loading = ui.text("Finding your queues…", 15, TvUi.MUTED);
    queueArea.addView(loading);
    loadQueueLists(
        origin,
        (all, shown) -> {
          if (request != generation) return;
          queueArea.removeAllViews();
          View first = renderQueueGroups(queueArea, all, shown, origin, true);
          if (first == null) first = cards.getChildAt(0);
          focusFirst(first);
        },
        error -> {
          if (request != generation) return;
          loading.setText("QueuePilot couldn't be reached. Your library is still here.");
          ui.primaryCard(cards.getChildAt(0));
          focusFirst(cards.getChildAt(0));
        });
  }

  /**
   * Loads every queue, and the filtered list too when a people filter is active, so the screen
   * can say how many queues the filter hides.
   */
  private void loadQueueLists(
      String origin,
      java.util.function.BiConsumer<JSONObject, JSONObject> loaded,
      java.util.function.Consumer<Exception> failed) {
    Set<String> selected = new HashSet<>(prefs().getStringSet("queue-people", Set.of()));
    fetchQueues(
        origin,
        Set.of(),
        all -> {
          queuePeople = all.optJSONArray("people");
          if (queuePeople == null) queuePeople = new JSONArray();
          if (selected.isEmpty()) loaded.accept(all, all);
          else fetchQueues(origin, selected, shown -> loaded.accept(all, shown), failed);
        },
        failed);
  }

  /**
   * Draws the filter notice and every queue. Home uses scrolling rows; Watching uses a
   * grid. Returns the first queue card, or null when no queue is shown.
   */
  private View renderQueueGroups(
      LinearLayout area, JSONObject all, JSONObject shown, String origin, boolean isRow) {
    JSONArray allQueues = all.optJSONArray("queues"), shownQueues = shown.optJSONArray("queues");
    int total = allQueues == null ? 0 : allQueues.length();
    int count = shownQueues == null ? 0 : shownQueues.length();
    for (int i = 0; i < total; i++) {
      JSONObject queue = allQueues.optJSONObject(i);
      if (queue != null) knownQueues.put(queue.optString("id"), queue);
    }
    if (shown != all) {
      LinearLayout notice = ui.row();
      notice.setGravity(Gravity.CENTER_VERTICAL);
      TextView summary = ui.text(QueueList.filterSummary(count, total), 15, TvUi.WHITE);
      summary.setTag("queue-filter-summary");
      summary.setPadding(0, 0, dp(14), 0);
      notice.addView(summary);
      notice.addView(
          ui.button(
              "Clear",
              () -> {
                prefs().edit().remove("queue-people").apply();
                render(current);
              },
              false));
      area.addView(notice);
      ui.gap(area, 14);
    }
    if (all.optBoolean("cached")) {
      area.addView(ui.text("Saved queues · reconnect to start playback", 14, TvUi.MUTED));
      ui.gap(area, 10);
    }
    List<JSONObject> list = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      JSONObject queue = shownQueues.optJSONObject(i);
      if (queue != null) list.add(queue);
    }
    // EVERY QUEUE, IN THE SERVER'S ORDER, AS ONE GROUP. Nobody switches profile to use a queue:
    // the server plays each as the profile it names, which each card states as information.
    View first = null;
    int columns = columns(3), width = itemWidth(columns);
    LinearLayout row = null;
    for (int i = 0; i < list.size(); i++) {
      if (row == null || (!isRow && i % columns == 0)) {
        if (isRow) {
          HorizontalScrollView scroll = new HorizontalScrollView(this);
          scroll.setHorizontalScrollBarEnabled(false);
          scroll.setClipToPadding(false);
          row = ui.row();
          row.setPadding(dp(3), dp(3), dp(3), dp(3));
          scroll.addView(row);
          area.addView(scroll);
        } else {
          row = ui.row();
          area.addView(row);
        }
        ui.gap(area, 18);
      }
      LinearLayout card = queueCard(list.get(i), width, origin);
      row.addView(withFullQueue(card, list.get(i)));
      if (first == null) {
        first = card;
        ui.primaryCard(card);
      }
    }
    if (count == 0)
      area.addView(
          ui.text(
              total == 0
                  ? "No queues yet. Create one in QueuePilot."
                  : "No queues match these people. Choose Clear to show all " + total + ".",
              17,
              TvUi.MUTED));
    return first;
  }

  /** A queue card with its "Full queue" action beneath it, as one column in a row or grid. */
  private View withFullQueue(LinearLayout card, JSONObject queue) {
    LinearLayout column = ui.column();
    column.setLayoutParams(card.getLayoutParams());
    column.addView(card, new LinearLayout.LayoutParams(-1, -2));
    TextView full = fullQueueButton(queue);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.topMargin = dp(8);
    column.addView(full, lp);
    return column;
  }

  private TextView fullQueueButton(JSONObject queue) {
    TextView full =
        ui.button("Full queue", R.drawable.lucide_list_ordered, () -> openFullQueue(queue), false);
    // Titles repeat, so the spoken name carries the audience as the card does.
    full.setContentDescription(
        "Full queue. "
            + queue.optString("title", "Movies & Shows")
            + (queue.has("people") ? ". " + queueAudience(queue) : ""));
    return full;
  }

  private LinearLayout queueCard(JSONObject queue, int width, String origin) {
    String kind = queue.optString("kind").equals("rules") ? "Rules" : "Picks";
    boolean isLocked = queue.optBoolean("isLocked");
    LinearLayout card =
        ui.card(
            isLocked ? kind + " · Locked" : kind,
            isLocked
                ? R.drawable.lucide_lock
                : kind.equals("Rules") ? R.drawable.lucide_list_filter : R.drawable.lucide_sparkles,
            queue.optString("title", "Movies & Shows"),
            queueAudience(queue),
            "Play queue",
            () -> startNativeQueue(queue),
            width);
    if (isLocked) card.setTag("queue-locked");
    // Whom the queue plays as is information only: it never asks anybody to switch profile.
    String playsAs = QueueList.playsAs(queue.optString("playsAs"));
    if (!playsAs.isEmpty()) {
      TextView line = ui.text(playsAs, 13, TvUi.MUTED);
      line.setTag("queue-plays-as");
      line.setPadding(0, 0, 0, dp(10));
      View action = card.findViewWithTag("card-action");
      card.addView(line, card.indexOfChild(action));
    }
    LinearLayout faces = ui.row();
    JSONArray people = queue.optJSONArray("people");
    if (people != null)
      for (int j = 0; j < Math.min(4, people.length()); j++) {
        ImageView face = avatar(36);
        faces.addView(face);
        loadQueueAvatar(face, people.optString(j), origin);
      }
    card.addView(faces, 1);
    // Titles repeat, so the spoken name carries the audience and the profile too.
    card.setContentDescription(
        queue.optString("title", "Movies & Shows")
            + ". "
            + queueAudience(queue)
            + ". "
            + kind
            + (isLocked ? ". Locked" : "")
            + (playsAs.isEmpty() ? "" : ". " + playsAs)
            + ". Play queue");
    return card;
  }

  /** Reads the queue list, falling back to the last saved copy when QueuePilot is unreachable. */
  private void fetchQueues(
      String origin,
      Set<String> selected,
      java.util.function.Consumer<JSONObject> loaded,
      java.util.function.Consumer<Exception> failed) {
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
            runOnUiThread(() -> loaded.accept(result));
          } catch (Exception error) {
            runOnUiThread(() -> failed.accept(error));
          }
        });
  }

  private String snapshotKey() {
    return "queue-snapshot:" + activeId;
  }

  /** The last queue this profile played and the item it was on, or null. */
  private JSONObject queueSnapshot() {
    try {
      String saved = prefs().getString(snapshotKey(), "");
      if (saved.isEmpty()) return null;
      JSONObject snapshot = new JSONObject(saved);
      return snapshot.optJSONObject("queue") == null || snapshot.optJSONObject("item") == null
          ? null
          : snapshot;
    } catch (Exception e) {
      return null;
    }
  }

  private void saveQueueSnapshot(JSONObject item) {
    if (nativeQueue == null || item == null) return;
    try {
      JSONObject saved = new JSONObject();
      for (String key : new String[] {"ratingKey", "type", "title", "show", "season", "episode"})
        if (item.has(key)) saved.put(key, item.get(key));
      prefs()
          .edit()
          .putString(
              snapshotKey(),
              new JSONObject().put("queue", nativeQueue).put("item", saved).toString())
          .apply();
    } catch (Exception ignored) {
    }
  }

  /**
   * Continues the profile's last queue. It names the queue and the item it was on. Resuming starts
   * a QueuePilot session, so it needs the QueuePilot server; the card says so rather than imply an
   * offline resume.
   */
  private View queueHero(JSONObject snapshot) {
    JSONObject queue = snapshot.optJSONObject("queue"), item = snapshot.optJSONObject("item");
    String type = item.optString("type", "episode"), title = item.optString("title");
    String show = item.optString("show");
    int season = item.isNull("season") ? EpisodeLabel.UNKNOWN : item.optInt("season", -1);
    int episode = item.isNull("episode") ? 0 : item.optInt("episode", 0);
    LinearLayout hero = ui.column();
    hero.setPadding(dp(24), dp(20), dp(24), dp(20));
    hero.setBackground(ui.shape(TvTheme.ACCENT_SURFACE, 0, 12));
    hero.addView(ui.text(queue.optString("title", "QueuePilot queue"), 13, TvUi.ACCENT));
    ui.gap(hero, 12);
    hero.addView(ui.title(EpisodeLabel.primary(type, title, show), 29));
    String line = EpisodeLabel.secondary(type, season, episode, title);
    if (!line.isEmpty()) {
      ui.gap(hero, 10);
      hero.addView(ui.text(line, 16, TvUi.WHITE));
    }
    ui.gap(hero, 10);
    hero.addView(
        ui.text(
            "Where this queue left off. Resuming asks your QueuePilot server for the queue.",
            14,
            TvUi.MUTED));
    ui.gap(hero, 14);
    TextView resume =
        ui.button("Resume queue", R.drawable.lucide_play, () -> startNativeQueue(queue), true);
    resume.setContentDescription(
        "Resume queue. "
            + queue.optString("title")
            + ". "
            + EpisodeLabel.spoken(type, title, show, season, episode));
    LinearLayout actions = ui.row();
    actions.addView(resume);
    TextView full = fullQueueButton(queue);
    LinearLayout.LayoutParams fullGap = new LinearLayout.LayoutParams(-2, -2);
    fullGap.leftMargin = dp(12);
    actions.addView(full, fullGap);
    hero.addView(actions);
    return hero;
  }

  private void demoHero() {
    StoryOrder.Item next = demo.next(completed());
    FrameLayout hero = new FrameLayout(this);
    hero.setBackground(ui.shape(TvTheme.SURFACE_RAISED, 0, 14));
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
          TvUi.BG,
          0);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(dp(itemWidth(columns(5))), -2);
      lp.rightMargin = dp(14);
      row.addView(poster, lp);
    }
  }

  private View mediaHero(PlexCatalog.Entry entry) {
    LinearLayout hero = ui.row();
    hero.setPadding(dp(24), dp(20), dp(24), dp(20));
    hero.setBackground(ui.shape(TvTheme.SURFACE_RAISED, 0, 12));
    LinearLayout labels = ui.column();
    // The media server's own on-deck item: name that source, never imply it is a queue's next
    // item, and name the server rather than the provider.
    String source = profile == null ? "" : profile.optString("serverName");
    String eyebrow = source.isEmpty() ? "" : source.toUpperCase(Locale.ROOT) + " · ";
    labels.addView(ui.text(eyebrow + "CONTINUE WATCHING", 12, TvUi.ACCENT));
    ui.gap(labels, 12);
    labels.addView(ui.title(entry.primary(), 29));
    ui.gap(labels, 14);
    String line = episodeLine(entry, "");
    if (!line.isEmpty()) labels.addView(ui.text(line, 16, TvUi.MUTED));
    ui.gap(labels, 14);
    TextView view =
        ui.button(
            "View title",
            R.drawable.lucide_film,
            () -> navigate(new Screen("details", "", "", entry)),
            true);
    view.setContentDescription("View title. " + entry.spoken());
    labels.addView(view);
    hero.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    boolean isShowPoster = !entry.posterThumb().equals(entry.thumb());
    hero.addView(image, new LinearLayout.LayoutParams(dp(isShowPoster ? 124 : 240), dp(185)));
    loadArt(image, entry.posterThumb(), 720);
    return hero;
  }

  /** "S1 · E9 · The signal", or the pre-0.5.2 "Episode 9" for a cached entry without a show. */
  private static String episodeLine(PlexCatalog.Entry entry, String fallback) {
    if (!entry.type().equals("episode")) return fallback;
    if (!entry.showTitle().isEmpty()) return entry.secondary();
    return entry.episode() > 0 ? "Episode " + entry.episode() : fallback;
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
                      .put("uuid", who.optString("uuid"))
                      .put("users", users);
              runOnUiThread(
                  () -> {
                    if (request != generation) return;
                    try {
                      store.account(connected);
                      account = connected;
                      prefs().edit().putBoolean("preview", false).apply();
                      history.clear();
                      openOwnProfile();
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

  private static String profileBadge(JSONObject user) {
    if (user.optBoolean("protected")) return "🔒 PIN";
    return user.optBoolean("admin") ? "Owner" : "";
  }

  private void chooseProfile() {
    shell("");
    heading("", "Who's watching?");
    status.setText("Choose a profile to use its library access.");
    LinearLayout row = horizontal();
    JSONArray users = account.optJSONArray("users");
    if (users != null)
      for (int i = 0; i < users.length(); i++) {
        JSONObject user = users.optJSONObject(i);
        if (user == null) continue;
        String name = user.optString("title", user.optString("username", "Profile"));
        // Only what differs between profiles: a PIN lock, or the account owner.
        LinearLayout profileCard =
            ui.card(
                profileBadge(user),
                name,
                "",
                "Choose profile",
                () -> selectUser(user),
                220);
        String badge = profileBadge(user);
        profileCard.setContentDescription(
            name + (badge.isEmpty() ? "" : ". " + badge.replace("🔒 ", "")) + ". Choose profile");
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
                "",
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
              .setNegativeButton("Cancel", null)
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

  /** Width kept clear along the right edge of a section grid for the jump-by-letter bar. */
  private static final int LETTER_BAR = 44;

  /**
   * The library grid on show: one contiguous window [start, end) of a paged listing, starting
   * on a row boundary. Pages load ahead of focus in both directions; a letter jump replaces
   * the grid with a new window that starts at the letter.
   */
  private static final class Grid {
    String path = "", sortParam = "", sectionKey = "";
    LibraryBrowse.Sort sort;
    int columns, pageSize, start, end, total = -1;
    boolean isComplete, isLoadingLater, isLoadingEarlier, isCached;
    LinearLayout rows;
    final Map<Integer, View> cards = new HashMap<>();
    List<LibraryBrowse.Letter> letters = List.of();
    final Map<String, TextView> letterViews = new HashMap<>();
    String shownLetter = "";
  }

  private Grid grid;

  private String sortPreference(String section) {
    return "library-sort:" + activeId + ":" + profile.optString("serverId") + ":" + section;
  }

  private void load(String path, String title) {
    if (profile == null) {
      render(new Screen("welcome"));
      return;
    }
    shell(title.equals("Series") ? "Series" : title.equals("Movies") ? "Movies" : "");
    String section = LibraryBrowse.sectionKey(path);
    if (!section.isEmpty()) {
      // The letter bar sits along the right edge, so the grid leaves it room.
      mainWidth -= LETTER_BAR;
      content.setPadding(
          content.getPaddingLeft(),
          content.getPaddingTop(),
          dp(32 + LETTER_BAR),
          content.getPaddingBottom());
    }
    heading("Your library", title);
    status.setText("Loading your library…");
    if (path.equals("/library/sections")) {
      loadSections(path, title);
      return;
    }
    java.util.regex.Matcher parent =
        java.util.regex.Pattern.compile("/library/metadata/(\\d+)/children").matcher(path);
    if (parent.matches()) {
      // A show or season lists its children under its own summary, facts and cast.
      LinearLayout header = ui.column();
      content.addView(header);
      loadParentHeader(parent.group(1), header);
    }
    Grid g = new Grid();
    g.path = path;
    g.sectionKey = section;
    g.columns = columns(5);
    g.pageSize = LibraryBrowse.pageSize(g.columns);
    if (!section.isEmpty()) {
      g.sort = LibraryBrowse.sort(prefs().getString(sortPreference(section), ""));
      g.sortParam = g.sort.param();
      libraryTools(g, title);
    }
    g.rows = ui.column();
    content.addView(g.rows);
    g.start = g.end = LibraryBrowse.restoreStart(current.position(), g.columns);
    grid = g;
    loadLater(g, -1);
    if (g.sort == LibraryBrowse.DEFAULT_SORT) loadLetters(g);
  }

  /** Sort and Search for a library section, above its grid. */
  private void libraryTools(Grid g, String title) {
    LinearLayout tools = ui.row();
    TextView sort =
        ui.button(
            "Sort · " + g.sort.label(),
            R.drawable.lucide_arrow_up_down,
            () -> sortPanel(g, title),
            false);
    sort.setContentDescription("Sort, " + g.sort.label());
    tools.addView(sort);
    TextView find =
        ui.button(
            "Search",
            R.drawable.lucide_search,
            () -> navigate(new Screen("search", g.sectionKey, title, null)),
            false);
    find.setContentDescription("Search " + title);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.leftMargin = dp(12);
    tools.addView(find, lp);
    content.addView(tools);
    ui.gap(content, 22);
  }

  private static final int[] SORT_ICONS = {
    R.drawable.lucide_arrow_down_a_z,
    R.drawable.lucide_calendar_plus,
    R.drawable.lucide_calendar,
    R.drawable.lucide_star,
    R.drawable.lucide_history,
  };

  /** The section's sorts in a side panel; the choice is kept for this section and profile. */
  private void sortPanel(Grid g, String title) {
    String[] labels = new String[LibraryBrowse.SORTS.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = LibraryBrowse.SORTS.get(i).label();
    TvPanel dialog =
        panel()
            .setTitle("Sort " + title)
            .setSelected(LibraryBrowse.indexOf(g.sort))
            .setItemIcons(SORT_ICONS)
            .setItems(
                labels,
                (d, i) -> {
                  LibraryBrowse.Sort chosen = LibraryBrowse.SORTS.get(i);
                  prefs().edit().putString(sortPreference(g.sectionKey), chosen.id()).apply();
                  // The same screen in a new order, starting at its first title.
                  render(new Screen("library", g.path, title, null, 0));
                })
            .create();
    dialog.show();
    dialog.setPanelSize(dp(360), -2);
  }

  /** The library list behind Series or Movies: a few libraries, never paged. */
  private void loadSections(String path, String title) {
    fetch(
        path,
        (entries, cached) -> {
          if (Set.of("Series", "Movies").contains(title)) {
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
                  : entries.length() + (entries.length() == 1 ? " library" : " libraries"));
          if (entries.length() == 0) {
            status.setText("No libraries found on this server.");
            return;
          }
          LinearLayout row = null;
          View first = null;
          int columns = columns(5);
          for (int i = 0; i < entries.length(); i++) {
            if (i % columns == 0) {
              row = ui.row();
              row.setTag(i == 0 ? "poster-row-first" : null);
              content.addView(row);
              ui.gap(content, 22);
            }
            View card = poster(PlexCatalog.Entry.fromJson(entries.optJSONObject(i)), -1);
            row.addView(card);
            if (first == null) first = card;
          }
          if (first != null) focusFirst(first);
        },
        () -> {
          status.setText("Your server is unavailable and this listing isn't saved yet.");
          content.addView(ui.button("Try again", () -> render(current), true));
        });
  }

  /** One page of a paged listing, with the listing's total; the last copy when offline. */
  private interface Page {
    void ready(JSONArray entries, int total, boolean cached);
  }

  private void fetchPage(String path, Page callback, Runnable failure) {
    int request = generation;
    String origin = server(), secret = token(), id = activeId;
    network.execute(
        () -> {
          JSONObject page;
          boolean cached = false;
          try {
            byte[] xml = PlexCatalog.get(PlexCatalog.url(origin, path), secret, 16 * 1024 * 1024);
            page =
                new JSONObject()
                    .put("entries", PlexCatalog.list(xml))
                    .put("total", PlexCatalog.totalSize(xml));
            java.nio.file.Files.write(
                cacheFile(id, origin, path).toPath(),
                page.toString().getBytes(StandardCharsets.UTF_8));
          } catch (Exception e) {
            try {
              page =
                  new JSONObject(
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
          JSONObject result = page;
          boolean fromCache = cached;
          runOnUiThread(
              () -> {
                if (request == generation && id.equals(activeId))
                  callback.ready(
                      result.optJSONArray("entries") == null
                          ? new JSONArray()
                          : result.optJSONArray("entries"),
                      result.optInt("total", -1),
                      fromCache);
              });
        });
  }

  private void gridStatus(Grid g) {
    if (g.isCached) status.setText("Saved listing · your server is currently unavailable");
    else if (g.sort != null && g.total >= 0) status.setText(LibraryBrowse.countLine(g.total, g.sort));
    else {
      int count = g.total >= 0 ? g.total : g.end;
      status.setText(count + (count == 1 ? " title" : " titles"));
    }
  }

  /**
   * Loads the page after the window. {@code focusIndex} is the item to focus when this is the
   * window's first page (a letter jump), or -1 for the remembered or first item.
   */
  private void loadLater(Grid g, int focusIndex) {
    if (g.isLoadingLater || g.isComplete) return;
    g.isLoadingLater = true;
    int from = g.end;
    boolean isFirst = g.start == g.end;
    fetchPage(
        LibraryBrowse.pagePath(g.path, g.sortParam, from, g.pageSize),
        (entries, total, cached) -> {
          if (g != grid) return;
          g.isLoadingLater = false;
          if (total >= 0) g.total = total;
          g.isCached |= cached;
          int count = entries.length();
          if (count == 0 && isFirst && from > 0) {
            // The remembered place is past the end now; start again at the first title.
            g.start = g.end = 0;
            loadLater(g, -1);
            return;
          }
          appendRows(g, entries, from, false);
          g.end = from + count;
          g.isComplete = count < g.pageSize || (g.total >= 0 && g.end >= g.total);
          gridStatus(g);
          if (!isFirst) return;
          if (count == 0) {
            if (!cached) status.setText("No titles found in this library.");
            return;
          }
          View target = focusIndex >= 0 ? g.cards.get(focusIndex) : null;
          // A jump is chosen on the bar, so its title takes focus even after a touch.
          if (target != null) target.requestFocusFromTouch();
          else focusFirst(g.cards.get(from));
        },
        () -> {
          if (g != grid) return;
          g.isLoadingLater = false;
          if (!isFirst) return;
          status.setText("Your server is unavailable and this listing isn't saved yet.");
          content.addView(ui.button("Try again", () -> render(current), true));
        });
  }

  /** Loads the page before the window and keeps the focused poster where it was on screen. */
  private void loadEarlier(Grid g) {
    if (g.isLoadingEarlier || g.start <= 0) return;
    g.isLoadingEarlier = true;
    int from = LibraryBrowse.earlierStart(g.start, g.pageSize), size = g.start - from;
    fetchPage(
        LibraryBrowse.pagePath(g.path, g.sortParam, from, size),
        (entries, total, cached) -> {
          if (g != grid) return;
          g.isLoadingEarlier = false;
          if (entries.length() != size) return; // the listing changed; keep the window as is
          View anchor = g.rows.getChildCount() > 0 ? g.rows.getChildAt(0) : null;
          int before = anchor == null ? 0 : anchor.getTop();
          appendRows(g, entries, from, true);
          g.start = from;
          if (anchor != null)
            anchor
                .getViewTreeObserver()
                .addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                      @Override
                      public boolean onPreDraw() {
                        anchor.getViewTreeObserver().removeOnPreDrawListener(this);
                        contentScroll.scrollBy(0, anchor.getTop() - before);
                        return true;
                      }
                    });
        },
        () -> {
          if (g == grid) g.isLoadingEarlier = false;
        });
  }

  /** Adds whole rows of posters, after the window or, for {@code isBefore}, above it. */
  private void appendRows(Grid g, JSONArray entries, int from, boolean isBefore) {
    int at = 0;
    LinearLayout row = null;
    for (int i = 0; i < entries.length(); i++) {
      if (i % g.columns == 0) {
        row = ui.row();
        if (from + i == 0) row.setTag("poster-row-first");
        View gap = new View(this);
        if (isBefore) {
          g.rows.addView(row, at++);
          g.rows.addView(gap, at++, new LinearLayout.LayoutParams(1, dp(22)));
        } else {
          g.rows.addView(row);
          g.rows.addView(gap, new LinearLayout.LayoutParams(1, dp(22)));
        }
      }
      int index = from + i;
      View card = poster(PlexCatalog.Entry.fromJson(entries.optJSONObject(i)), index);
      View.OnFocusChangeListener outline = card.getOnFocusChangeListener();
      card.setOnFocusChangeListener(
          (v, focused) -> {
            outline.onFocusChange(v, focused);
            if (focused) gridFocused(g, index);
          });
      g.cards.put(index, card);
      row.addView(card);
    }
  }

  private void gridFocused(Grid g, int index) {
    if (g != grid) return;
    if (LibraryBrowse.needsLater(index, g.end, g.columns, g.isComplete)) loadLater(g, -1);
    if (LibraryBrowse.needsEarlier(index, g.start, g.columns)) loadEarlier(g);
    LibraryBrowse.Letter letter = LibraryBrowse.letterAt(g.letters, index);
    showLetter(g, letter == null ? "" : letter.key());
  }

  /** The jump-by-letter bar, from the section's first-character index, under title order. */
  private void loadLetters(Grid g) {
    int request = generation;
    String origin = server(), secret = token(), path = "/library/sections/" + g.sectionKey;
    network.execute(
        () -> {
          try {
            List<LibraryBrowse.Letter> letters =
                PlexCatalog.letters(
                    PlexCatalog.get(
                        PlexCatalog.url(origin, path + "/firstCharacter"),
                        secret,
                        1024 * 1024));
            runOnUiThread(
                () -> {
                  if (request == generation && g == grid && !letters.isEmpty())
                    drawLetterBar(g, letters);
                });
          } catch (Exception ignored) {
            // The grid still scrolls without the bar.
          }
        });
  }

  private void drawLetterBar(Grid g, List<LibraryBrowse.Letter> letters) {
    g.letters = letters;
    LinearLayout bar = ui.column();
    bar.setGravity(Gravity.CENTER_HORIZONTAL);
    bar.setTag("letter-bar");
    int height = getResources().getDisplayMetrics().heightPixels;
    // Every letter fits on the screen at any Display size: the cells shrink to fit.
    int cell = Math.max(dp(12), Math.min(dp(30), (height - dp(32)) / letters.size()));
    for (LibraryBrowse.Letter letter : letters) {
      TextView view = new TextView(this);
      view.setText(letter.title());
      view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cell * .56f);
      view.setTypeface(ui.medium());
      view.setTextColor(TvUi.MUTED);
      view.setGravity(Gravity.CENTER);
      view.setIncludeFontPadding(false);
      // The bar outlives a jump, so it always acts on the grid on show.
      ui.focusable(view, () -> jumpTo(letter), Color.TRANSPARENT, 6);
      view.setContentDescription("Jump to " + letter.title());
      g.letterViews.put(letter.key(), view);
      bar.addView(view, new LinearLayout.LayoutParams(dp(LETTER_BAR - 8), cell));
    }
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams(dp(LETTER_BAR - 4), -2, Gravity.END | Gravity.CENTER_VERTICAL);
    lp.rightMargin = dp(4);
    shellRoot.addView(bar, lp);
    View focused = getWindow().getDecorView().findFocus();
    for (Map.Entry<Integer, View> card : g.cards.entrySet())
      if (card.getValue() == focused) gridFocused(g, card.getKey());
  }

  /** Marks the letter of the focused title in the bar. */
  private void showLetter(Grid g, String key) {
    if (key.equals(g.shownLetter)) return;
    TextView old = g.letterViews.get(g.shownLetter), now = g.letterViews.get(key);
    if (old != null) old.setTextColor(TvUi.MUTED);
    if (now != null) now.setTextColor(TvUi.ACCENT);
    g.shownLetter = key;
  }

  /** Starts the grid at the first title of {@code letter} and focuses it. */
  private void jumpTo(LibraryBrowse.Letter letter) {
    Grid g = grid;
    if (g == null) return;
    int offset = LibraryBrowse.offsetOf(g.letters, letter.key());
    if (offset < 0) return;
    View cached = g.cards.get(offset);
    if (cached != null) {
      cached.requestFocusFromTouch();
      return;
    }
    Grid jumped = new Grid();
    jumped.path = g.path;
    jumped.sectionKey = g.sectionKey;
    jumped.sort = g.sort;
    jumped.sortParam = g.sortParam;
    jumped.columns = g.columns;
    jumped.pageSize = g.pageSize;
    jumped.total = g.total;
    jumped.letters = g.letters;
    jumped.letterViews.putAll(g.letterViews);
    jumped.start = jumped.end = LibraryBrowse.rowStart(offset, g.columns);
    jumped.rows = ui.column();
    int at = content.indexOfChild(g.rows);
    content.removeView(g.rows);
    content.addView(jumped.rows, at);
    showLetter(g, "");
    grid = jumped;
    status.setText("Jumping to " + letter.title() + "…");
    loadLater(jumped, offset);
  }

  /**
   * A poster card. {@code index} is its place in a paged grid, or -1; opening a grid poster
   * remembers it, so Back restores the grid around it.
   */
  private View poster(PlexCatalog.Entry entry, int index) {
    LinearLayout card = ui.column();
    card.setPadding(dp(3), dp(3), dp(3), dp(10));
    int width = itemWidth(columns(5));
    if (entry.type().equals("library")) {
      FrameLayout tile = new FrameLayout(this);
      tile.setBackgroundColor(TvUi.PANEL);
      ImageView icon = ui.icon(R.drawable.lucide_library_big, TvUi.ACCENT, 48);
      tile.addView(icon, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
      card.addView(tile, new LinearLayout.LayoutParams(-1, dp(150)));
    } else {
      ImageView image = new ImageView(this);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      image.setBackgroundColor(TvUi.PANEL);
      card.addView(image, new LinearLayout.LayoutParams(-1, dp(width * 3 / 2)));
      loadArt(image, entry.posterThumb(), 720);
    }
    ui.gap(card, 10);
    TextView label = ui.title(entry.primary(), 16);
    label.setMaxLines(2);
    label.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(label);
    ui.gap(card, 5);
    TextView detail = ui.text(episodeLine(entry, entry.type()), 12, TvUi.MUTED);
    detail.setMaxLines(2);
    detail.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(detail);
    ui.focusable(
        card,
        () -> {
          if (index >= 0 && current != null)
            current = new Screen(current.kind(), current.path(), current.title(), null, index);
          if (Set.of("movie", "episode").contains(entry.type()))
            navigate(new Screen("details", "", "", entry));
          else navigate(new Screen("library", entry.children(), entry.title(), null));
        },
        TvUi.BG,
        0);
    card.setContentDescription(entry.spoken());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), -2);
    lp.rightMargin = dp(14);
    card.setLayoutParams(lp);
    return card;
  }

  private Bitmap artwork(String origin, String secret, String thumb, int width) throws Exception {
    String path = TitleCast.transcodePath(thumb, width);
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
    // Smaller than a screen heading: a long title still fits two lines at 1080p, then ends in an
    // ellipsis instead of pushing Play below the fold.
    TextView title =
        heading(
            entry.type().equals("episode")
                ? entry.showTitle().isEmpty() ? "Episode " + entry.episode() : "Episode"
                : "Your library",
            entry.primary(),
            26);
    title.setTag("details-title");
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    if (!entry.showTitle().isEmpty() && entry.type().equals("episode")) {
      TextView line = ui.text(entry.secondary(), 17, TvUi.WHITE);
      line.setTag("details-episode");
      line.setMaxLines(2);
      line.setEllipsize(TextUtils.TruncateAt.END);
      content.addView(line);
      ui.gap(content, 14);
    }
    LinearLayout row = ui.row();
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    row.addView(image, new LinearLayout.LayoutParams(dp(180), dp(270)));
    loadArt(image, entry.thumb(), 1440);
    LinearLayout info = ui.column();
    info.setPadding(dp(28), dp(4), 0, 0);
    row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    info.addView(ui.text("Watch with " + profileName(), 15, TvUi.MUTED));
    ui.gap(info, 10);
    // Year, rating, length and studio; genres; scores; watch state; director and writer.
    LinearLayout facts = ui.column();
    info.addView(facts);
    fillDetailFacts(facts, entry);
    ui.gap(info, 14);
    TextView more = null;
    if (!entry.summary().isBlank()) {
      TextView summary = ui.text(entry.summary().trim(), 15, TvUi.WHITE);
      summary.setTag("details-summary");
      summary.setMaxLines(4);
      summary.setEllipsize(TextUtils.TruncateAt.END);
      summary.setLineSpacing(0, 1.12f);
      info.addView(summary);
      ui.gap(info, 18);
      more =
          ui.button(
              "More",
              R.drawable.lucide_text,
              () ->
                  panel()
                      .setTitle(entry.primary())
                      .setMessage(entry.summary().trim())
                      .setPositiveButton("Close", null)
                      .create()
                      .show(),
              false);
      more.setContentDescription("More. Read the full summary");
      // Offered only when the summary is actually cut off.
      TextView moreButton = more;
      moreButton.setVisibility(View.GONE);
      summary.addOnLayoutChangeListener(
          (v, l, t, r, b, ol, ot, or, ob) -> {
            android.text.Layout layout = summary.getLayout();
            boolean isClamped =
                layout != null
                    && layout.getLineCount() > 0
                    && layout.getEllipsisCount(layout.getLineCount() - 1) > 0;
            int visibility = isClamped ? View.VISIBLE : View.GONE;
            if (moreButton.getVisibility() != visibility)
              moreButton.post(() -> moreButton.setVisibility(visibility));
          });
    }
    LinearLayout actions = ui.row();
    TextView play = ui.button("Play", R.drawable.lucide_play, () -> playEntry(entry), true);
    actions.addView(play);
    // An episode is added as its show: a queue holds movies and shows.
    boolean isEpisode = entry.type().equals("episode");
    String addKey = isEpisode ? entry.showKey() : entry.key();
    if (!addKey.isEmpty()) {
      String addTitle = isEpisode ? entry.showTitle() : entry.title();
      TextView add =
          ui.button(
              "Add to queue",
              R.drawable.lucide_list_plus,
              () -> addToQueue(addKey, addTitle, isEpisode),
              false);
      add.setContentDescription(
          isEpisode ? "Add the show " + addTitle + " to a queue" : "Add to queue");
      LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
      gap.leftMargin = dp(12);
      actions.addView(add, gap);
    }
    if (more != null) {
      LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
      gap.leftMargin = dp(12);
      actions.addView(more, gap);
    }
    LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(-2, -2);
    art.leftMargin = dp(12);
    actions.addView(
        ui.button("Artwork quality", R.drawable.lucide_image, () -> compareArt(entry), false), art);
    // The actions scroll sideways rather than squeeze a label at a large Display size.
    HorizontalScrollView actionScroll = new HorizontalScrollView(this);
    actionScroll.setHorizontalScrollBarEnabled(false);
    actionScroll.setClipToPadding(false);
    actionScroll.addView(actions);
    info.addView(actionScroll);
    content.addView(row);
    LinearLayout castArea = ui.column();
    content.addView(castArea);
    fillCastRow(castArea, entry);
    focusFirst(play);
    loadFullDetails(entry, facts, castArea);
  }

  private void fillDetailFacts(LinearLayout facts, PlexCatalog.Entry entry) {
    facts.removeAllViews();
    String[][] lines = {
      {"details-meta", DetailFacts.meta(entry.type(), entry.facts(), entry.duration())},
      {"details-genres", DetailFacts.genres(entry.facts())},
      {"details-ratings", DetailFacts.ratings(entry.facts())},
      {
        "details-progress",
        DetailFacts.progress(entry.type(), entry.resume(), entry.duration(), entry.facts())
      },
      {"details-credits", DetailFacts.credits(entry.roles())},
    };
    for (String[] line : lines) {
      if (line[1].isEmpty()) continue;
      int color = line[0].equals("details-meta") ? TvUi.WHITE : TvUi.MUTED;
      TextView text = ui.text(line[1], 15, color);
      text.setTag(line[0]);
      text.setMaxLines(2);
      text.setEllipsize(TextUtils.TruncateAt.END);
      facts.addView(text);
      ui.gap(facts, 5);
    }
  }

  /** The title's actors as a row of faces, the way the Plex detail page shows them. */
  private void fillCastRow(LinearLayout area, PlexCatalog.Entry entry) {
    area.removeAllViews();
    List<JSONObject> actors = DetailFacts.actors(entry.roles());
    if (actors.isEmpty()) return;
    ui.gap(area, 26);
    area.addView(ui.title("Cast", 20));
    ui.gap(area, 12);
    LinearLayout row = horizontalIn(area);
    String origin = server(), secret = token();
    for (JSONObject actor : actors.subList(0, Math.min(12, actors.size()))) {
      LinearLayout card = ui.column();
      card.setGravity(Gravity.CENTER_HORIZONTAL);
      card.setPadding(dp(6), dp(8), dp(6), dp(8));
      ImageView face = avatar(84);
      card.addView(face);
      loadCastPhoto(face, actor.optString("thumb"), actor.optString("name"), origin, secret);
      ui.gap(card, 8);
      TextView name = ui.title(actor.optString("name"), 14);
      name.setGravity(Gravity.CENTER);
      name.setMaxLines(2);
      name.setEllipsize(TextUtils.TruncateAt.END);
      card.addView(name);
      TextView role = ui.text(actor.optString("role"), 12, TvUi.MUTED);
      role.setGravity(Gravity.CENTER);
      role.setMaxLines(2);
      role.setEllipsize(TextUtils.TruncateAt.END);
      card.addView(role);
      ui.focusable(
          card, () -> message(actor.optString("name"), actor.optString("role")), TvUi.BG);
      card.setContentDescription(actor.optString("name") + ". " + actor.optString("role"));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(124), -2);
      lp.rightMargin = dp(10);
      row.addView(card, lp);
    }
  }

  private LinearLayout horizontalIn(LinearLayout parent) {
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    LinearLayout row = ui.row();
    row.setPadding(dp(3), dp(3), dp(3), dp(3));
    scroll.addView(row);
    parent.addView(scroll);
    return row;
  }

  /**
   * A listing carries only part of a title's metadata, so the full record is read once the
   * screen is showing; an episode's cast is completed from its season and show.
   */
  private void loadFullDetails(
      PlexCatalog.Entry entry, LinearLayout facts, LinearLayout castArea) {
    if (profile == null || entry.key().isEmpty()) return;
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
            if (items.length() == 0) return;
            PlexCatalog.Entry listed = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            PlexCatalog.Entry full = withInheritedCast(listed, origin, secret);
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  fillDetailFacts(facts, full);
                  fillCastRow(castArea, full);
                });
          } catch (Exception ignored) {
            // The listing's facts stay; the screen is still usable without the full record.
          }
        });
  }

  private void loadParentHeader(String key, LinearLayout header) {
    int request = generation;
    String origin = server(), secret = token();
    network.execute(
        () -> {
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(origin, "/library/metadata/" + key),
                        secret,
                        16 * 1024 * 1024));
            if (items.length() == 0) return;
            PlexCatalog.Entry listed = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            PlexCatalog.Entry parent = withInheritedCast(listed, origin, secret);
            if (!Set.of("show", "season").contains(parent.type())) return;
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  LinearLayout facts = ui.column();
                  header.addView(facts);
                  fillDetailFacts(facts, parent);
                  if (!parent.summary().isBlank()) {
                    TextView summary = ui.text(parent.summary().trim(), 15, TvUi.WHITE);
                    summary.setTag("parent-summary");
                    summary.setMaxLines(3);
                    summary.setEllipsize(TextUtils.TruncateAt.END);
                    ui.gap(header, 6);
                    header.addView(summary);
                  }
                  if (parent.type().equals("show")) {
                    ui.gap(header, 14);
                    LinearLayout row = ui.row();
                    TextView add =
                        ui.button(
                            "Add to queue",
                            R.drawable.lucide_list_plus,
                            () -> addToQueue(parent.key(), parent.title(), false),
                            false);
                    row.addView(add);
                    header.addView(row);
                  }
                  LinearLayout cast = ui.column();
                  header.addView(cast);
                  fillCastRow(cast, parent);
                  ui.gap(header, 22);
                });
          } catch (Exception ignored) {
            // The listing still works without its parent's header.
          }
        });
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
    // A single title outside a queue plays as the profile the viewer chose: its token, its
    // restrictions, and its watch history. Only queues play as their own profile.
    String origin = server(), secret = token();
    boolean isQueueHome =
        profile != null
            && profile.optString("serverId").equals(prefs().getString("queue-home-server", "-"));
    network.execute(
        () -> {
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(
                            origin, "/library/metadata/" + entry.key() + "?includeMarkers=1"),
                        secret,
                        16 * 1024 * 1024));
            PlexCatalog.Entry detail = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            if (detail.part().isEmpty()) throw new IllegalStateException();
            byte[] artwork = nowPlayingArtwork(origin, secret, detail);
            String artworkUri = isQueueHome ? queueThumbUri(detail) : "";
            runOnUiThread(
                () -> {
                  if (request == generation) {
                    nativePlan = null;
                    playingEntry = detail;
                    playingArtwork = artwork;
                    playingArtworkUri = artworkUri;
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

  /**
   * What other devices show for the playing item. A universal remote (the Unfolded Circle Remote
   * 3 through its Android TV integration) reads the Shield's Cast media status, which Android
   * fills from this media session: the title line, and the artwork. Without artwork the remote
   * falls back to an app icon. So the one title line carries the show, number and episode title,
   * and the poster is given twice: as bytes, for the TV's own now-playing card, and as a
   * tokenless QueuePilot address, for a device on the LAN that can only fetch a URL. A Plex
   * address is never given: it would carry the profile's token to every listener.
   */
  private androidx.media3.common.MediaMetadata nowPlayingMetadata(String fallbackTitle) {
    androidx.media3.common.MediaMetadata.Builder metadata =
        new androidx.media3.common.MediaMetadata.Builder();
    PlexCatalog.Entry entry = playingEntry;
    if (entry == null) return metadata.setTitle(fallbackTitle).build();
    boolean isEpisode = "episode".equals(entry.type());
    String line =
        EpisodeLabel.nowPlaying(
            entry.type(), entry.title(), entry.showTitle(), entry.season(), entry.episode());
    metadata
        .setTitle(line)
        .setDisplayTitle(line)
        .setSubtitle(isEpisode ? entry.secondary() : null)
        .setMediaType(
            isEpisode
                ? androidx.media3.common.MediaMetadata.MEDIA_TYPE_TV_SHOW
                : androidx.media3.common.MediaMetadata.MEDIA_TYPE_MOVIE);
    if (isEpisode && !entry.showTitle().isEmpty()) metadata.setArtist(entry.showTitle());
    if (isEpisode && !entry.seasonTitle().isEmpty()) metadata.setAlbumTitle(entry.seasonTitle());
    if (playingArtwork != null)
      metadata.setArtworkData(
          playingArtwork, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER);
    if (!playingArtworkUri.isEmpty())
      metadata.setArtworkUri(android.net.Uri.parse(playingArtworkUri));
    return metadata.build();
  }

  /** The poster as JPEG bytes from the playing server, or null. Call off the main thread. */
  private static byte[] nowPlayingArtwork(String origin, String secret, PlexCatalog.Entry entry) {
    try {
      String thumb = entry.posterThumb();
      if (thumb.isEmpty()) return null;
      byte[] bytes =
          PlexCatalog.get(
              PlexCatalog.url(origin, TitleCast.transcodePath(thumb, 400)), secret, 2 * 1024 * 1024);
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
      return bounds.outWidth > 0 && bounds.outHeight > 0 ? bytes : null;
    } catch (Exception unavailable) {
      return null;
    }
  }

  /**
   * QueuePilot's tokenless poster address for the playing title, or empty. QueuePilot's poster
   * proxy reads its home Plex server, so this is used only for an item on that server. An
   * episode uses its show's poster, as the queue's own cards do.
   */
  private String queueThumbUri(PlexCatalog.Entry entry) {
    String origin = prefs().getString("queue-origin", "");
    String key =
        "episode".equals(entry.type()) && !entry.showKey().isEmpty() ? entry.showKey() : entry.key();
    if (origin.isEmpty() || key.isEmpty() || !key.matches("[0-9]+")) return "";
    return PlexCatalog.url(origin, "/api/thumb/" + key);
  }

  private void startPlayer(String url, String secret, String title, String cast) {
    reset();
    if (current == null || !current.kind().equals("player")) history.push(current);
    current = new Screen("player");
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(TvTheme.VIDEO_BACKDROP);
    playerView = new PlayerView(this);
    root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout overlay = ui.column();
    overlay.setPadding(dp(26), dp(18), dp(26), dp(16));
    overlay.setBackgroundColor(TvTheme.PLAYER_BAR);
    TextView playingTitle = ui.title(playingEntry == null ? title : playingEntry.primary(), 23);
    playingTitle.setTag("playing-title");
    overlay.addView(playingTitle);
    String playingLine = playingEntry == null ? "" : episodeLine(playingEntry, "");
    if (!playingLine.isEmpty()) {
      TextView episodeText = ui.text(playingLine, 16, TvUi.WHITE);
      episodeText.setTag("playing-episode");
      overlay.addView(episodeText);
    }
    status = ui.text("Opening video…", 13, TvUi.MUTED);
    overlay.addView(status);
    ui.gap(overlay, 10);
    LinearLayout actions = ui.row();
    overlay.addView(actions);
    ui.action(actions, "Cast", R.drawable.lucide_users, () -> showCast(playingEntry), false);
    if (nativePlan != null)
      ui.action(
          actions,
          "Next queue item",
          R.drawable.lucide_skip_forward,
          () -> {
            if (advancing) return;
            reportPlayback("skip");
            advancing = true;
            nativeIndex++;
            loadNativeItem();
          },
          false);
    if (nativePlan != null && nativeQueue != null) {
      JSONObject playingQueue = nativeQueue;
      ui.action(
          actions,
          "Full queue",
          R.drawable.lucide_list_ordered,
          () -> leaveForFullQueue(playingQueue),
          false);
    }
    ui.action(actions, "Back to title", R.drawable.lucide_arrow_left, this::back, false);
    root.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    playingMarkers = playingEntry == null ? List.of() : playingEntry.skipMarkers();
    skipMarker = null;
    isSkipDismissed = false;
    isControllerVisible = false;
    skipButton = ui.button("Skip Intro", R.drawable.lucide_chevrons_right, this::skipMarker, true);
    skipButton.setTag("skip-marker");
    skipButton.setVisibility(View.GONE);
    FrameLayout.LayoutParams skipLayout =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
    skipLayout.setMargins(0, 0, dp(40), dp(130));
    root.addView(skipButton, skipLayout);
    setContentView(root);
    androidx.media3.datasource.DataSource.Factory dataSourceFactory =
        PlexDataSource.factory(playingOrigin.isEmpty() ? server() : playingOrigin, secret);
    AssPlayback ass = new AssPlayback(this, dataSourceFactory, android.net.Uri.parse(url));
    assPlayback = ass;
    playerView.getOverlayFrameLayout().addView(ass.overlay, new FrameLayout.LayoutParams(-1, -1));
    player =
        new ExoPlayer.Builder(this, ass.renderers(this))
            .setLoadControl(
                new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build())
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(dataSourceFactory, ass.extractors()))
            .build();
    mediaSession = new androidx.media3.session.MediaSession.Builder(this, player).build();
    playerView.setPlayer(player);
    playerView.setShowSubtitleButton(true);
    installPlaybackMenus();
    roundPlayerControls(playerView);
    scalePlayerControls(playerView);
    installSeekPreview(root);
    rewind.reset();
    playerView.setControllerShowTimeoutMs(3500);
    playerView.setControllerVisibilityListener(
        (androidx.media3.ui.PlayerView.ControllerVisibilityListener)
            visibility -> {
              overlay.setVisibility(visibility);
              isControllerVisible = visibility == View.VISIBLE;
              updateSkip();
            });
    player.addListener(
        new Player.Listener() {
          @Override
          public void onVideoSizeChanged(VideoSize size) {
            ass.videoSize(Math.round(size.width * size.pixelWidthHeightRatio), size.height);
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
                    resumePosition = rewind.resume(currentPosition, sectionFloor, rewindSeconds());
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
            .setMediaMetadata(nowPlayingMetadata(title))
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
            updateSkip();
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

  private long playingDuration() {
    long duration = player == null ? -1 : player.getDuration();
    if (duration > 0) return duration;
    return playingEntry == null ? -1 : playingEntry.duration();
  }

  /**
   * Shows Skip Intro or Skip Credits while the position is inside a Plex marker, and pulls focus
   * to it once per marker. It hides after a few seconds unless the controls are showing, and when
   * playback leaves the marker. Nothing is skipped without the viewer pressing it.
   */
  private void updateSkip() {
    if (skipButton == null || player == null) return;
    MarkerSkip.Marker marker =
        MarkerSkip.active(playingMarkers, player.getCurrentPosition(), sectionFloor, sectionEnd);
    long now = SystemClock.elapsedRealtime();
    boolean isShown = skipButton.getVisibility() == View.VISIBLE;
    if (marker == null) {
      skipMarker = null;
      isSkipDismissed = false;
      if (isShown) hideSkip();
      return;
    }
    if (!marker.equals(skipMarker)) {
      skipMarker = marker;
      isSkipDismissed = false;
      showSkip(marker, true);
    } else if (isShown && !isControllerVisible && now - skipShownAt >= MarkerSkip.VISIBLE_MS) {
      isSkipDismissed = true;
      hideSkip();
    } else if (!isShown && isSkipDismissed && isControllerVisible) {
      showSkip(marker, false);
    }
  }

  private void showSkip(MarkerSkip.Marker marker, boolean isFocusPulled) {
    skipButton.setText(MarkerSkip.label(marker));
    skipButton.setContentDescription(MarkerSkip.label(marker));
    skipButton.setVisibility(View.VISIBLE);
    skipShownAt = SystemClock.elapsedRealtime();
    if (isFocusPulled) skipButton.requestFocus();
  }

  private void hideSkip() {
    boolean hadFocus = skipButton.hasFocus();
    skipButton.setVisibility(View.GONE);
    if (hadFocus && playerView != null) playerView.requestFocus();
  }

  private void skipMarker() {
    if (player == null || skipMarker == null) return;
    long target = MarkerSkip.target(skipMarker, playingDuration(), sectionFloor, sectionEnd);
    // Landing on the end of the item or section reuses the normal completion path: Plex gets a
    // finished timeline and a queue reports the item complete and moves on.
    hideSkip();
    skipMarker = null;
    player.seekTo(target);
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
                        .setTitle("Orbit Nine · " + item.label() + " · " + item.title())
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
    ui.action(content, "Choose people", R.drawable.lucide_users, this::chooseQueuePeople, false);
    status.setText(selected.isEmpty() ? "All queues · loading…" : "Finding your queues…");
    LinearLayout area = ui.column();
    ui.gap(content, 16);
    content.addView(area);
    int request = generation;
    loadQueueLists(
        origin,
        (all, shown) -> {
          if (request != generation) return;
          JSONArray queues = shown.optJSONArray("queues");
          status.setText(
              (selected.isEmpty() ? "All people" : selectedPeopleLabel(selected))
                  + " · "
                  + (queues == null ? 0 : queues.length())
                  + " queues");
          renderQueueGroups(area, all, shown, origin, false);
        },
        error -> {
          if (request == generation)
            status.setText("QueuePilot couldn't load the TV queues. " + error.getMessage());
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

  /** Who the queue is for, in the web app's text form: "Ada, Grace". */
  private String queueAudience(JSONObject queue) {
    // The server's own audience line, the same text the web app prints, when it sends one.
    String audience = queue.optString("audience");
    if (!audience.isEmpty()) return audience;
    List<String> names = new ArrayList<>();
    JSONArray list = queue.optJSONArray("people");
    if (list != null)
      for (int i = 0; i < list.length(); i++) {
        String id = list.optString(i), name = id;
        for (int j = 0; queuePeople != null && j < queuePeople.length(); j++) {
          JSONObject person = queuePeople.optJSONObject(j);
          if (person != null && id.equals(person.optString("id")))
            name = person.optString("displayName", id);
        }
        names.add(name);
      }
    return QueueList.audience(names);
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

  // The TV PIN: a queue marked "Locked on TV" in QueuePilot opens only after the household PIN.
  // The server enforces it (423 without a valid unlock); the TV only decides when to ask.

  /**
   * When {@code error} is QueuePilot's 423 for a locked queue, asks for the TV PIN and then runs
   * {@code retry}. Returns false for every other error, which the caller reports as before.
   */
  private boolean askPinIfLocked(Exception error, Runnable retry) {
    if (!(error instanceof NativeQueueApi.Failure failure) || !failure.isLocked()) return false;
    NativeQueueApi.unlockToken = "";
    askTvPin(retry);
    return true;
  }

  /**
   * The number pad for the household TV PIN, 4 to 8 digits, driven with the D-pad or the remote's
   * number keys. A correct PIN keeps locked queues open until the app leaves the foreground.
   */
  private void askTvPin(Runnable unlocked) {
    String origin = prefs().getString("queue-origin", "");
    if (profile == null || origin.isEmpty()) {
      message("Locked queue", "Connect Plex and your QueuePilot server to enter the TV PIN.");
      return;
    }
    LinearLayout form = ui.column();
    form.setPadding(dp(24), dp(8), dp(24), dp(16));
    StringBuilder pin = new StringBuilder();
    TextView dots = ui.title("○  ○  ○  ○", 30);
    dots.setGravity(Gravity.CENTER);
    dots.setTag("tv-pin-dots");
    form.addView(dots);
    ui.gap(form, 10);
    TextView feedback = ui.text("", 14, TvUi.MUTED);
    feedback.setGravity(Gravity.CENTER);
    feedback.setTag("tv-pin-message");
    feedback.setVisibility(View.GONE);
    form.addView(feedback, new LinearLayout.LayoutParams(-1, -2));
    ui.gap(form, 12);
    TvPanel dialog =
        panel()
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
          network.execute(
              () -> {
                try {
                  JSONObject result =
                      NativeQueueApi.request(
                          origin,
                          "/api/native/unlock",
                          new JSONObject().put("pin", value),
                          "X-Plex-Profile-Token",
                          profileProof());
                  NativeQueueApi.unlockToken = result.optString("unlockToken");
                  runOnUiThread(
                      () -> {
                        dialog.dismiss();
                        unlocked.run();
                      });
                } catch (Exception error) {
                  runOnUiThread(
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
    GridLayout keypad = new GridLayout(this);
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
              ? ui.button(key, R.drawable.lucide_delete, press, false)
              : ui.button(key, press, false);
      button.setContentDescription(key.equals("Delete") ? "Delete digit" : key);
      GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
      lp.width = dp(110);
      lp.height = dp(48);
      lp.setMargins(dp(4), dp(4), dp(4), dp(4));
      keypad.addView(button, lp);
    }
    dialog.setOnShowListener(
        v -> {
          dialog.setPanelSize(dp(460), -2);
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

  /**
   * The active profile's proof for QueuePilot's native routes: the account token for the Plex
   * Home owner, otherwise a profile token from an explicit switch. Call off the main thread;
   * it is synchronized so two screens loading at once do not both switch profile.
   */
  private synchronized String profileProof() throws Exception {
    String proof = profile.optString("profileProof");
    if (!proof.isEmpty()) return proof;
    JSONObject homeUser = null;
    JSONArray homeRoster = account.optJSONArray("users");
    if (homeRoster != null)
      for (int i = 0; i < homeRoster.length(); i++) {
        JSONObject u = homeRoster.optJSONObject(i);
        if (u != null && userId(u).equals(activeId)) homeUser = u;
      }
    if (homeUser == null || (homeUser.optBoolean("protected") && !homeUser.optBoolean("admin")))
      throw new IllegalStateException(
          "Choose your Plex profile again to authorize native playback.");
    proof =
        homeUser.optBoolean("admin")
            ? account.getString("token")
            : auth.switchUser(account.getString("token"), activeId, "");
    profile.put("profileProof", proof);
    store.write(profiles);
    return proof;
  }

  private void startNativeQueue(JSONObject queue) {
    startNativeQueue(queue, null);
  }

  /**
   * Starts a queue session. {@code start} is null for the engine's own next item, or a chosen
   * item {@code {entryKey, ratingKey, plexServer?, mode}}; earlier unwatched items stay unwatched.
   */
  private void startNativeQueue(JSONObject queue, JSONObject start) {
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
            String proof = profileProof();
            progressNetwork.submit(this::flushNativeEvents).get();
            JSONObject body = new JSONObject().put("setId", queue.getString("id"));
            if (start != null) body.put("start", start);
            JSONObject plan =
                NativeQueueApi.request(
                    origin, "/api/native/sessions", body, "X-Plex-Profile-Token", proof);
            plan.put("profileProof", proof);
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  nativePlan = plan;
                  nativeQueue = queue;
                  // Remembered so a library title on the same server can name its poster too.
                  if (!plan.optString("homeServerId").isEmpty())
                    prefs()
                        .edit()
                        .putString("queue-home-server", plan.optString("homeServerId"))
                        .apply();
                  nativeStartMode = start == null ? "" : start.optString("mode");
                  nativeIndex = plan.optInt("cursor");
                  nativeSequence = plan.optLong("sequence");
                  loadNativeItem();
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  if (askPinIfLocked(error, () -> startNativeQueue(queue, start))) return;
                  status.setText("This queue couldn't start.");
                  message("Queue playback", error.getMessage());
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
                      if (nativeStartMode.equals("only")) {
                        // One chosen item has finished, not the queue: return to where it was
                        // chosen, and keep the queue's place on Home.
                        if (!history.isEmpty()) render(history.pop());
                        else render(new Screen("queues"));
                        return;
                      }
                      // A finished queue has nothing to continue on Home.
                      prefs().edit().remove(snapshotKey()).apply();
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
    saveQueueSnapshot(item);
    int request = generation;
    String defaultOrigin = server(), defaultToken = token();
    // A JSON null reads as the text "null" through optString, so test for it first.
    String itemServer =
        item.isNull("plexServer") || item.optString("plexServer").isEmpty()
            ? nativePlan.optString("homeServerId")
            : item.optString("plexServer");
    JSONObject grant = queueGrant(nativePlan, itemServer);
    boolean isQueueHome = itemServer.equals(nativePlan.optString("homeServerId"));
    JSONObject boundProfile = profile;
    network.execute(
        () -> {
          try {
            String origin = defaultOrigin, secret = defaultToken;
            if (grant != null) {
              // THE QUEUE'S OWN PROFILE PLAYS IT. The session handed over that profile's grant
              // for this server, so the item streams as the queue's profile, not the profile
              // this TV has chosen for library browsing. The chosen profile's connection is
              // reused when it is the same server; the grant's verified address otherwise.
              secret = grant.getString("token");
              if (!itemServer.equals(boundProfile.optString("serverId"))) {
                origin = PlexCatalog.base(grant.getString("baseUrl"));
                if (!ServerConnectionPolicy.allowed(origin, true, false))
                  throw new IllegalStateException("The queued server is unavailable");
              }
            } else if (!itemServer.isEmpty()
                && !itemServer.equals(boundProfile.optString("serverId"))) {
              JSONArray resources = auth.servers(boundProfile.getString("profileProof"));
              JSONObject resource = null;
              for (int i = 0; i < resources.length(); i++)
                if (resources.getJSONObject(i).optString("clientIdentifier").equals(itemServer))
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
                                    origin,
                                    "/library/metadata/"
                                        + item.getString("ratingKey")
                                        + "?includeMarkers=1"),
                                secret,
                                16 * 1024 * 1024))
                        .getJSONObject(0));
            if (entry.part().isEmpty())
              throw new IllegalStateException("This item has no direct playback source");
            String mediaOrigin = origin, mediaToken = secret;
            byte[] artwork = nowPlayingArtwork(origin, secret, entry);
            String artworkUri = isQueueHome ? queueThumbUri(entry) : "";
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  playingEntry = entry;
                  playingArtwork = artwork;
                  playingArtworkUri = artworkUri;
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

  /**
   * The queue profile's playback grant for {@code serverId}, from the session QueuePilot started,
   * or null when the server sent none (an older server; the chosen profile's access is used).
   */
  private static JSONObject queueGrant(JSONObject plan, String serverId) {
    JSONArray grants = plan == null ? null : plan.optJSONArray("grants");
    for (int i = 0; grants != null && i < grants.length(); i++) {
      JSONObject grant = grants.optJSONObject(i);
      if (grant != null
          && serverId.equals(grant.optString("serverId"))
          && !grant.optString("token").isEmpty()) return grant;
    }
    return null;
  }

  // Full queue: the whole ordered queue from QueuePilot, in the layout the server chose for its
  // content (a list for episodes, an artwork grid for movies), with tonight's lineup marked.

  private void openFullQueue(JSONObject queue) {
    String id = queue.optString("id");
    knownQueues.put(id, queue);
    isWatchedShown = false;
    fullQueueFocus = "";
    navigate(new Screen("fullqueue", id, queue.optString("title", "Movies & Shows"), null));
  }

  /** Leaves playback for the playing queue's full list; Back returns to where playback began. */
  private void leaveForFullQueue(JSONObject queue) {
    if (player != null) reportPlayback("stopped");
    advancing = true;
    nativePlan = null;
    String id = queue.optString("id");
    knownQueues.put(id, queue);
    isWatchedShown = false;
    fullQueueFocus = playingEntry == null ? "" : playingEntry.key();
    Screen previous = history.peek();
    if (previous != null && previous.kind().equals("fullqueue") && previous.path().equals(id))
      render(history.pop());
    else render(new Screen("fullqueue", id, queue.optString("title", "Movies & Shows"), null));
  }

  /** A queue the catalog has not listed yet, known only by its id and title. */
  private JSONObject queueFor(String setId, String title) {
    JSONObject known = knownQueues.get(setId);
    if (known != null) return known;
    JSONObject queue = new JSONObject();
    try {
      queue.put("id", setId).put("title", title);
    } catch (Exception ignored) {
    }
    return queue;
  }

  /**
   * Explains why a QueuePilot screen cannot load, with the one action that fixes it. Returns false
   * when the screen can load.
   */
  private boolean needsQueueConnection(LinearLayout actions, String what) {
    if (profile != null && !prefs().getString("queue-origin", "").isEmpty()) return false;
    boolean isSignedOut = profile == null;
    status.setText(
        isSignedOut
            ? "Sign in to see " + what + "."
            : "Connect your QueuePilot server to see " + what + ".");
    Runnable fix = isSignedOut ? () -> navigate(new Screen("signin")) : this::queueSetup;
    ui.action(
        actions,
        isSignedOut ? "Sign in" : "Connect QueuePilot",
        isSignedOut ? R.drawable.lucide_circle_user_round : R.drawable.lucide_list_video,
        fix,
        true);
    focusFirst(actions.getChildAt(0));
    return true;
  }

  private void fullQueue(String setId, String title) {
    shell("Watching");
    JSONObject queue = queueFor(setId, title);
    String audience = queue.has("people") ? queueAudience(queue) : "";
    heading(audience.isEmpty() ? "Queue" : "Queue · " + audience, title);
    status.setText("Loading the whole queue…");
    LinearLayout actions = ui.row();
    content.addView(actions);
    LinearLayout notes = ui.column();
    content.addView(notes);
    LinearLayout area = ui.column();
    content.addView(area);
    if (needsQueueConnection(actions, "this queue")) return;
    // No profile gate: the server reads every queue as the profile the queue plays as. A queue
    // locked on the TV answers 423 until the household TV PIN is entered.
    String origin = prefs().getString("queue-origin", "");
    int request = generation;
    network.execute(
        () -> {
          try {
            String proof = profileProof();
            JSONObject full =
                NativeQueueApi.request(
                    origin,
                    "/api/native/queues/" + PlexAccount.encode(setId) + "/full",
                    null,
                    "X-Plex-Profile-Token",
                    proof);
            runOnUiThread(
                () -> {
                  if (request == generation) drawFullQueue(full, queue, actions, notes, area);
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  if (askPinIfLocked(error, () -> render(current))) {
                    status.setText("This queue is locked on the TV. Enter the TV PIN to open it.");
                    ui.action(
                        actions,
                        "Enter PIN",
                        R.drawable.lucide_lock,
                        () -> askTvPin(() -> render(current)),
                        true);
                    focusFirst(actions.getChildAt(0));
                    return;
                  }
                  status.setText("The full queue couldn't load. " + error.getMessage());
                  ui.action(
                      actions, "Try again", R.drawable.lucide_rotate_cw, () -> render(current), true);
                  focusFirst(actions.getChildAt(0));
                });
          }
        });
  }

  private void drawFullQueue(
      JSONObject full,
      JSONObject queue,
      LinearLayout actions,
      LinearLayout notes,
      LinearLayout area) {
    QueueDetail.Summary summary = QueueDetail.summarize(full);
    status.setText(QueueDetail.summaryLine(summary));
    JSONObject next = null;
    JSONArray entries = full.optJSONArray("entries");
    for (int e = 0; next == null && entries != null && e < entries.length(); e++) {
      JSONArray items = entries.optJSONObject(e).optJSONArray("items");
      for (int i = 0; next == null && items != null && i < items.length(); i++)
        if (items.optJSONObject(i).optBoolean("isNext")) next = items.optJSONObject(i);
    }
    String nextLabel =
        next == null
            ? ""
            : "episode".equals(next.optString("type"))
                ? EpisodeLabel.secondary(
                    "episode",
                    QueueDetail.season(next),
                    QueueDetail.episode(next),
                    QueueDetail.title(next))
                : QueueDetail.primary(next);
    TextView play =
        ui.button(
            nextLabel.isEmpty() ? "Play queue" : "Continue · " + nextLabel,
            R.drawable.lucide_play,
            () -> startNativeQueue(queue),
            true);
    play.setContentDescription(
        next == null ? "Play queue" : "Continue. " + QueueDetail.spoken(next));
    play.setTag("full-queue-play");
    actions.addView(play);
    boolean isGrid = "grid".equals(full.optString("layout"));
    if (!isGrid && summary.watched() > 0) {
      TextView[] fold = new TextView[1];
      fold[0] =
          ui.button(
              "",
              () -> {
                isWatchedShown = !isWatchedShown;
                foldLabel(fold[0], summary.watched());
                drawFullQueueItems(full, queue, area);
              },
              false);
      foldLabel(fold[0], summary.watched());
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.leftMargin = dp(12);
      actions.addView(fold[0], lp);
    }
    ui.gap(notes, 16);
    LinearLayout chips = ui.row();
    chip(chips, "Watched · " + summary.watched(), R.drawable.lucide_circle_check);
    chip(chips, "Tonight · " + summary.lineup(), R.drawable.lucide_play);
    chip(
        chips,
        "Later · " + Math.max(0, summary.items() - summary.watched() - summary.lineup()),
        R.drawable.lucide_clock);
    notes.addView(chips);
    if (!full.optBoolean("isWholeQueue", true))
      note(notes, "A Rules queue has no stored order, so this is its current lineup.");
    String startReason = QueueDetail.startUnavailable(full);
    if (startReason != null) note(notes, startReason);
    if (full.optBoolean("isTruncated"))
      note(notes, "Showing the first " + summary.items() + " items of a longer queue.");
    // The page opens at the viewer's place: the item chosen last, else the next item. Taking
    // focus from touch also scrolls it into view after a pointer tap.
    View target = drawFullQueueItems(full, queue, area);
    View focus = target == null ? play : target;
    focus.post(
        () -> {
          if (focus.isAttachedToWindow()) focus.requestFocusFromTouch();
        });
  }

  private void foldLabel(TextView fold, int watched) {
    fold.setText((isWatchedShown ? "Hide " : "Show ") + QueueDetail.count(watched, "watched item"));
    ui.withIcon(fold, isWatchedShown ? R.drawable.lucide_eye_off : R.drawable.lucide_eye);
    fold.setTag("watched-fold");
  }

  private void chip(LinearLayout row, String label, int icon) {
    TextView chip = ui.text(label, 13, TvUi.WHITE);
    chip.setPadding(dp(12), dp(7), dp(12), dp(7));
    chip.setBackground(ui.shape(TvUi.PANEL, 0, 16));
    ui.withIcon(chip, icon);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.rightMargin = dp(10);
    row.addView(chip, lp);
  }

  private void note(LinearLayout parent, String text) {
    ui.gap(parent, 12);
    TextView note = ui.text(text, 14, TvUi.MUTED);
    ui.withIcon(note, R.drawable.lucide_info);
    parent.addView(note);
  }

  /**
   * Draws every entry and its items. Returns the view to focus: the item chosen last, or the next
   * item. The lineup line follows the last lineup item, at {@code lineup.endPosition}.
   *
   * <p>THE GRID IS ONE WRAPPING GRID (decision 2026-10-23, item 5). Items flow across entries into
   * shared rows of posters, as many columns as the width and Display size allow. Only an entry
   * with more than one item gets a heading, and only a heading or the lineup line starts a new
   * row; the next item is marked by its own "Next" badge, not by a line that breaks the row. One
   * poster per row was the defect: every one-movie entry drew its own heading and its own row.
   * The list layout keeps one compact row per item, under every entry's heading.
   */
  private View drawFullQueueItems(JSONObject full, JSONObject queue, LinearLayout area) {
    area.removeAllViews();
    boolean isGrid = "grid".equals(full.optString("layout"));
    JSONArray entries = full.optJSONArray("entries");
    JSONObject lineup = full.optJSONObject("lineup");
    int endPosition =
        lineup == null || lineup.isNull("endPosition") ? -1 : lineup.optInt("endPosition", -1);
    long lineupMs = 0;
    for (int e = 0; entries != null && e < entries.length(); e++) {
      JSONArray items = entries.optJSONObject(e).optJSONArray("items");
      for (int i = 0; items != null && i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item.isNull("lineupIndex") || item.optString("state").equals("watched")) continue;
        long duration = Math.max(0, item.optLong("durationMs", 0));
        lineupMs += Math.max(0, duration - Math.max(0, item.optLong("offsetMs", 0)));
      }
    }
    int columns = columns(5), width = itemWidth(columns);
    boolean isDividerPlaced = endPosition < 0;
    View focus = null, nextView = null;
    int drawn = 0;
    LinearLayout row = null;
    int inRow = 0;
    for (int e = 0; entries != null && e < entries.length(); e++) {
      JSONObject entry = entries.optJSONObject(e);
      JSONArray items = entry.optJSONArray("items");
      List<JSONObject> visible = new ArrayList<>();
      for (int i = 0; items != null && i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (!isGrid && !isWatchedShown && item.optString("state").equals("watched")) continue;
        visible.add(item);
      }
      if (visible.isEmpty()) continue;
      boolean hasHeading = !isGrid || visible.size() > 1;
      if (hasHeading) {
        row = null;
        ui.gap(area, 22);
        LinearLayout heading = ui.row();
        heading.setGravity(Gravity.BOTTOM);
        TextView name = ui.title(QueueDetail.text(entry, "title"), 20);
        name.setTag("queue-entry");
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(
            ui.text(QueueDetail.entryNote(entry, e, entries.length()), 13, TvUi.MUTED));
        area.addView(heading);
        ui.gap(area, 12);
      }
      for (JSONObject item : visible) {
        int position = item.optInt("position");
        if (!isDividerPlaced && position > endPosition) {
          area.addView(lineupDivider(full, lineupMs));
          isDividerPlaced = true;
          row = null;
        }
        if (item.optBoolean("isNext") && !isGrid) {
          TextView tonight =
              ui.text("TONIGHT'S LINEUP · STARTS AT YOUR NEXT ITEM", 12, TvUi.ACCENT);
          ui.withIcon(tonight, R.drawable.lucide_play);
          if (row != null) ui.gap(area, 4);
          area.addView(tonight);
          ui.gap(area, 10);
          row = null;
        }
        Runnable open = () -> queueItemPanel(full, entry, item, queue);
        View view = isGrid ? queueTile(item, width, open) : queueRow(item, open);
        if (isGrid) {
          if (row == null || inRow == columns) {
            if (row == null && drawn == 0 && !hasHeading) ui.gap(area, 22);
            row = ui.row();
            row.setTag("queue-grid-row");
            area.addView(row);
            ui.gap(area, 18);
            inRow = 0;
          }
          row.addView(view);
          inRow++;
        } else {
          area.addView(view, new LinearLayout.LayoutParams(-1, -2));
          ui.gap(area, 8);
        }
        drawn++;
        if (item.optBoolean("isNext")) nextView = view;
        if (focus == null
            && !fullQueueFocus.isEmpty()
            && fullQueueFocus.equals(QueueDetail.text(item, "ratingKey"))) focus = view;
        if (!isDividerPlaced && position == endPosition) {
          area.addView(lineupDivider(full, lineupMs));
          isDividerPlaced = true;
          row = null;
        }
      }
      // A headed entry owns its rows, so whatever follows it starts below it.
      if (hasHeading) row = null;
    }
    if (!isDividerPlaced && drawn > 0) area.addView(lineupDivider(full, lineupMs));
    if (drawn == 0)
      area.addView(
          ui.text(
              entries == null || entries.length() == 0
                  ? "This queue has no items yet."
                  : "Every item in this queue is watched.",
              17,
              TvUi.MUTED));
    return focus != null ? focus : nextView;
  }

  private View lineupDivider(JSONObject full, long lineupMs) {
    LinearLayout box = ui.column();
    box.setPadding(dp(20), dp(16), dp(20), dp(16));
    box.setBackground(ui.shape(TvTheme.ACCENT_SURFACE, TvTheme.ACCENT_BORDER, 9));
    TextView title = ui.title("Tonight's lineup ends here", 17);
    title.setTextColor(TvUi.ACCENT);
    box.addView(title);
    ui.gap(box, 8);
    box.addView(ui.text(QueueDetail.lineupNote(full, lineupMs), 14, TvUi.MUTED));
    box.setTag("lineup-end");
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(10), dp(14), dp(18));
    box.setLayoutParams(lp);
    return box;
  }

  /** The item's own picture from its server; items on another server keep the plain tile. */
  private void loadQueueArt(ImageView view, JSONObject item, int width) {
    if (profile == null) return;
    String serverId = QueueDetail.text(item, "serverId");
    if (!serverId.isEmpty() && !serverId.equals(profile.optString("serverId"))) return;
    JSONObject art = item.optJSONObject("artwork");
    String thumb = "";
    for (String key : new String[] {"thumb", "parentThumb", "grandparentThumb"})
      if (thumb.isEmpty()) thumb = QueueDetail.text(art, key);
    if (!thumb.isEmpty()) loadArt(view, thumb, width);
  }

  private static String itemDescription(JSONObject item) {
    String section = QueueDetail.section(item);
    return (item.optInt("position") + 1)
        + ". "
        + QueueDetail.spoken(item)
        + ". "
        + QueueDetail.state(item)
        + (section == null || QueueDetail.state(item).equals(section) ? "" : ". " + section);
  }

  /** The state badge: words and an icon, so watched, in progress and next need no color. */
  private TextView queueBadge(JSONObject item) {
    String state = item.optString("state");
    boolean isNext = item.optBoolean("isNext"), isWatched = state.equals("watched");
    String label = QueueDetail.state(item);
    int icon = 0;
    if (isWatched) icon = R.drawable.lucide_circle_check;
    else if (isNext) icon = R.drawable.lucide_play;
    else if (state.equals("in_progress") || QueueDetail.section(item) != null)
      icon = R.drawable.lucide_clock;
    else if (item.optBoolean("isSpecial")) icon = R.drawable.lucide_sparkles;
    TextView badge =
        ui.text(
            label, 13, isNext ? TvTheme.ACCENT_ON_SOLID : isWatched ? TvUi.MUTED : TvUi.WHITE);
    badge.setPadding(dp(10), dp(6), dp(10), dp(6));
    badge.setBackground(ui.shape(isNext ? TvTheme.ACCENT_SOLID : TvTheme.NEUTRAL_SURFACE, 0, 7));
    badge.setSingleLine(true);
    if (icon != 0) ui.withIcon(badge, icon);
    badge.setTag("queue-state");
    return badge;
  }

  private View progressBar(float fraction) {
    LinearLayout track = ui.row();
    track.setBackground(ui.shape(TvTheme.BORDER_DEFAULT, 0, 2));
    View fill = new View(this);
    fill.setBackground(ui.shape(TvUi.ACCENT, 0, 2));
    track.addView(fill, new LinearLayout.LayoutParams(0, -1, fraction));
    track.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1 - fraction));
    track.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(4)));
    return track;
  }

  /** 1A: one row per item, with a small square-cornered picture. */
  private View queueRow(JSONObject item, Runnable open) {
    boolean isWatched = item.optString("state").equals("watched");
    boolean isNext = item.optBoolean("isNext");
    LinearLayout row = ui.row();
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(12), dp(10), dp(16), dp(10));
    TextView number = ui.text(String.valueOf(item.optInt("position") + 1), 14, TvUi.MUTED);
    number.setGravity(Gravity.CENTER);
    row.addView(number, new LinearLayout.LayoutParams(dp(40), -2));
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackgroundColor(TvTheme.SURFACE_SUNKEN);
    LinearLayout.LayoutParams imageLayout = new LinearLayout.LayoutParams(dp(112), dp(63));
    imageLayout.rightMargin = dp(16);
    row.addView(image, imageLayout);
    loadQueueArt(image, item, 240);
    LinearLayout labels = ui.column();
    TextView title = ui.title(QueueDetail.rowTitle(item), 17);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(title);
    ui.gap(labels, 6);
    TextView detail = ui.text(QueueDetail.detail(item), 13, TvUi.MUTED);
    detail.setSingleLine(true);
    detail.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(detail);
    float progress = QueueDetail.progress(item);
    if (progress >= 0) {
      ui.gap(labels, 8);
      View bar = progressBar(progress);
      ((LinearLayout.LayoutParams) bar.getLayoutParams()).width = dp(220);
      labels.addView(bar);
    }
    LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(0, -2, 1);
    labelLayout.rightMargin = dp(14);
    row.addView(labels, labelLayout);
    row.addView(queueBadge(item));
    if (isWatched) {
      image.setAlpha(.5f);
      labels.setAlpha(.6f);
    }
    ui.focusable(row, open, isNext ? TvTheme.ACCENT_SURFACE : TvUi.PANEL);
    row.setContentDescription(itemDescription(item));
    row.setTag("queue-item");
    return row;
  }

  /** 1B: a picture card per item; watched cards stay in place, dimmed. */
  private View queueTile(JSONObject item, int width, Runnable open) {
    boolean isWatched = item.optString("state").equals("watched");
    boolean isMovie = !"episode".equals(item.optString("type"));
    LinearLayout card = ui.column();
    card.setPadding(dp(3), dp(3), dp(3), dp(10));
    FrameLayout frame = new FrameLayout(this);
    frame.setBackgroundColor(TvUi.PANEL);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
    loadQueueArt(image, item, 720);
    TextView number = ui.text(String.valueOf(item.optInt("position") + 1), 12, TvUi.WHITE);
    number.setPadding(dp(7), dp(4), dp(7), dp(4));
    number.setBackground(ui.shape(TvTheme.ART_LABEL, 0, 5));
    FrameLayout.LayoutParams numberLayout =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    numberLayout.setMargins(dp(8), dp(8), 0, 0);
    frame.addView(number, numberLayout);
    card.addView(frame, new LinearLayout.LayoutParams(-1, dp(isMovie ? width * 3 / 2 : width * 9 / 16)));
    float progress = QueueDetail.progress(item);
    if (progress >= 0) {
      ui.gap(card, 6);
      card.addView(progressBar(progress));
    }
    ui.gap(card, 10);
    LinearLayout labels = ui.column();
    TextView title = ui.title(QueueDetail.rowTitle(item), 16);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(title);
    ui.gap(labels, 5);
    TextView detail = ui.text(QueueDetail.detail(item), 12, TvUi.MUTED);
    detail.setMaxLines(2);
    detail.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(detail);
    ui.gap(labels, 8);
    LinearLayout badgeRow = ui.row();
    badgeRow.addView(queueBadge(item));
    labels.addView(badgeRow);
    card.addView(labels);
    if (isWatched) {
      frame.setAlpha(.5f);
      labels.setAlpha(.6f);
    }
    ui.focusable(card, open, TvUi.BG, 0);
    card.setContentDescription(itemDescription(item));
    card.setTag("queue-item");
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), -2);
    lp.rightMargin = dp(14);
    card.setLayoutParams(lp);
    return card;
  }

  private TextView panelButton(
      LinearLayout body, String label, int icon, boolean primary, Runnable action) {
    TextView button = ui.button(label, icon, action, primary);
    button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(8);
    body.addView(button, lp);
    return button;
  }

  private void panelNote(LinearLayout body, String text) {
    TextView note = ui.text(text, 14, TvUi.MUTED);
    ui.withIcon(note, R.drawable.lucide_info);
    note.setPadding(0, dp(4), 0, dp(12));
    body.addView(note, new LinearLayout.LayoutParams(-1, -2));
  }

  /** The side panel for one full-queue item: start here, play only it, or mark it. */
  private void queueItemPanel(JSONObject full, JSONObject entry, JSONObject item, JSONObject queue) {
    LinearLayout body = ui.column();
    TvPanel[] holder = new TvPanel[1];
    String entryKey = QueueDetail.text(entry, "entryKey");
    String startReason = QueueDetail.startUnavailable(full);
    if (startReason == null && entryKey.isEmpty())
      startReason = "This entry can't start from a chosen item.";
    if (startReason == null) {
      panelButton(
          body,
          "Start here, then continue",
          R.drawable.lucide_list_start,
          true,
          () -> {
            holder[0].dismiss();
            startFrom(queue, entryKey, item, "continue");
          });
      panelButton(
          body,
          "Play only this item",
          R.drawable.lucide_circle_play,
          false,
          () -> {
            holder[0].dismiss();
            startFrom(queue, entryKey, item, "only");
          });
      if (!item.optString("state").equals("watched"))
        panelNote(body, "Earlier items you have not watched stay in the queue.");
    } else panelNote(body, startReason);
    String markReason = QueueDetail.markUnavailable(item);
    boolean isWatched = item.optString("state").equals("watched");
    if (markReason == null && !entryKey.isEmpty())
      panelButton(
          body,
          isWatched ? "Mark unwatched" : "Mark watched",
          isWatched ? R.drawable.lucide_undo_2 : R.drawable.lucide_circle_check,
          false,
          () -> {
            holder[0].dismiss();
            markWatched(queue, entryKey, item, !isWatched);
          });
    else
      panelNote(
          body, markReason == null ? "This item can't be marked from a chosen item." : markReason);
    panelButton(body, "Close", R.drawable.lucide_x, false, () -> holder[0].dismiss());
    String section = QueueDetail.section(item);
    String message = QueueDetail.detail(item) + "\n" + QueueDetail.state(item);
    if (section != null && !QueueDetail.state(item).equals(section)) message += " · " + section;
    holder[0] =
        panel().setTitle(QueueDetail.rowTitle(item)).setMessage(message).setView(body).create();
    holder[0].show();
    holder[0].setPanelSize(dp(440), -2);
  }

  private void startFrom(JSONObject queue, String entryKey, JSONObject item, String mode) {
    try {
      String ratingKey = QueueDetail.text(item, "ratingKey");
      JSONObject start =
          new JSONObject().put("entryKey", entryKey).put("ratingKey", ratingKey).put("mode", mode);
      String server = QueueDetail.text(item, "plexServer");
      if (!server.isEmpty()) start.put("plexServer", server);
      fullQueueFocus = ratingKey;
      startNativeQueue(queue, start);
    } catch (Exception error) {
      message("Queue playback", error.getMessage());
    }
  }

  /** Marks one item of a queue-owned entry, then draws the queue again from the server. */
  private void markWatched(JSONObject queue, String entryKey, JSONObject item, boolean isWatched) {
    String origin = prefs().getString("queue-origin", ""), setId = queue.optString("id");
    String ratingKey = QueueDetail.text(item, "ratingKey"), server = QueueDetail.text(item, "plexServer");
    fullQueueFocus = ratingKey;
    status.setText(isWatched ? "Marking watched…" : "Marking unwatched…");
    int request = generation;
    network.execute(
        () -> {
          try {
            JSONObject body =
                new JSONObject()
                    .put("entryKey", entryKey)
                    .put("ratingKey", ratingKey)
                    .put("isWatched", isWatched);
            if (!server.isEmpty()) body.put("plexServer", server);
            NativeQueueApi.request(
                origin,
                "/api/native/queues/" + PlexAccount.encode(setId) + "/watched",
                body,
                "X-Plex-Profile-Token",
                profileProof());
            runOnUiThread(
                () -> {
                  if (request == generation) render(current);
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  if (askPinIfLocked(
                      error, () -> markWatched(queue, entryKey, item, isWatched))) return;
                  status.setText("The change wasn't saved.");
                  message(isWatched ? "Mark watched" : "Mark unwatched", error.getMessage());
                });
          }
        });
  }

  // Watch history: queue, then sitting, then what was watched in it (design 2B).

  private void history() {
    shell("History");
    heading("Every queue · Watch history", "History");
    status.setText("Loading your history…");
    LinearLayout actions = ui.row();
    content.addView(actions);
    if (needsQueueConnection(actions, "your watch history")) return;
    TextView period =
        ui.button(
            QueueDetail.days(historyDays), R.drawable.lucide_calendar, this::historyPeriod, false);
    period.setContentDescription("Period, " + QueueDetail.days(historyDays));
    actions.addView(period);
    ui.gap(content, 16);
    TextView scope =
        ui.text(
            "Plays of every queue on this TV, and plays in other apps when a queue tracks them."
                + " Newest first.",
            14,
            TvUi.MUTED);
    ui.withIcon(scope, R.drawable.lucide_info);
    content.addView(scope);
    LinearLayout area = ui.column();
    content.addView(area);
    String origin = prefs().getString("queue-origin", "");
    int request = generation, days = historyDays;
    network.execute(
        () -> {
          try {
            JSONObject catalog = null;
            try {
              catalog = NativeQueueApi.request(origin, "/api/native/queues?people=", null, "", "");
            } catch (Exception ignored) {
              // The history still reads without the catalog; only the start actions need it.
            }
            JSONObject result =
                NativeQueueApi.request(
                    origin,
                    "/api/native/history?days=" + days,
                    null,
                    "X-Plex-Profile-Token",
                    profileProof());
            JSONObject listed = catalog;
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  JSONArray queues = listed == null ? null : listed.optJSONArray("queues");
                  for (int i = 0; queues != null && i < queues.length(); i++) {
                    JSONObject queue = queues.optJSONObject(i);
                    if (queue != null) knownQueues.put(queue.optString("id"), queue);
                  }
                  drawHistory(result, area, period, days);
                  int hidden = result.optInt("hiddenLockedQueues");
                  if (hidden > 0) {
                    TextView locked =
                        ui.button(
                            hidden == 1
                                ? "1 locked queue is not shown · Enter PIN"
                                : hidden + " locked queues are not shown · Enter PIN",
                            R.drawable.lucide_lock,
                            () -> askTvPin(() -> render(current)),
                            false);
                    locked.setTag("history-locked");
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.leftMargin = dp(12);
                    actions.addView(locked, lp);
                  }
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  status.setText("Your history couldn't load. " + error.getMessage());
                  ui.action(
                      actions, "Try again", R.drawable.lucide_rotate_cw, () -> render(current), true);
                  focusFirst(period);
                });
          }
        });
  }

  private void historyPeriod() {
    int[] choices = {7, 30, 90, 365};
    String[] labels = new String[choices.length];
    int selected = -1;
    for (int i = 0; i < choices.length; i++) {
      labels[i] = QueueDetail.days(choices[i]);
      if (choices[i] == historyDays) selected = i;
    }
    TvPanel dialog =
        panel()
            .setTitle("Show history for")
            .setSelected(selected)
            .setItems(
                labels,
                (d, i) -> {
                  historyDays = choices[i];
                  render(current);
                })
            .setNegativeButton("Cancel", null)
            .create();
    dialog.show();
    dialog.setPanelSize(dp(330), -2);
  }

  private void drawHistory(JSONObject result, LinearLayout area, View period, int days) {
    JSONArray queues = result.optJSONArray("queues");
    int queueCount = queues == null ? 0 : queues.length(), sittings = 0;
    for (int q = 0; q < queueCount; q++) {
      JSONArray list = queues.optJSONObject(q).optJSONArray("sittings");
      sittings += list == null ? 0 : list.length();
    }
    status.setText(
        queueCount == 0
            ? "Nothing watched in the " + QueueDetail.days(days).toLowerCase(Locale.US) + "."
            : QueueDetail.count(sittings, "sitting")
                + " in "
                + QueueDetail.count(queueCount, "queue")
                + " · "
                + QueueDetail.days(days));
    long now = System.currentTimeMillis();
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    int columns = columns(3), width = itemWidth(columns);
    View first = null;
    for (int q = 0; q < queueCount; q++) {
      JSONObject listed = queues.optJSONObject(q);
      String setId = listed.optString("setId"), title = listed.optString("title");
      JSONObject queue = queueFor(setId, title);
      ui.gap(area, 26);
      LinearLayout heading = ui.row();
      heading.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout names = ui.column();
      TextView name = ui.title(title, 23);
      name.setTag("history-queue");
      names.addView(name);
      ui.gap(names, 6);
      JSONArray list = listed.optJSONArray("sittings");
      int count = list == null ? 0 : list.length();
      names.addView(
          ui.text(
              QueueDetail.count(count, "sitting")
                  + " · last watched "
                  + QueueDetail.lastWatched(listed.optLong("lastWatchedAt"), now, zone),
              14,
              TvUi.MUTED));
      heading.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
      heading.addView(fullQueueButton(queue));
      area.addView(heading);
      ui.gap(area, 14);
      LinearLayout row = null;
      for (int s = 0; s < count; s++) {
        if (s % columns == 0) {
          row = ui.row();
          area.addView(row);
          ui.gap(area, 14);
        }
        View card = sittingCard(listed, list.optJSONObject(s), queue, width, now, zone);
        row.addView(card);
        if (first == null) first = card;
      }
    }
    if (queueCount == 0)
      area.addView(
          ui.text(
              "Queue plays will appear here, grouped by queue and by sitting.", 17, TvUi.MUTED));
    focusFirst(first == null ? period : first);
  }

  private View sittingCard(
      JSONObject listed,
      JSONObject sitting,
      JSONObject queue,
      int width,
      long now,
      java.time.ZoneId zone) {
    LinearLayout card = ui.column();
    card.setPadding(dp(20), dp(18), dp(20), dp(18));
    JSONArray items = sitting.optJSONArray("items");
    int count = items == null ? 0 : items.length();
    LinearLayout pictures = ui.row();
    Set<String> pictured = new HashSet<>();
    for (int i = 0; i < count && pictured.size() < 3; i++) {
      JSONObject item = items.optJSONObject(i);
      String ratingKey = QueueDetail.text(item, "ratingKey"), server = QueueDetail.text(item, "plexServer");
      boolean isHome = server.isEmpty() || (profile != null && server.equals(profile.optString("serverId")));
      if (!isHome || !ratingKey.matches("\\d+") || !pictured.add(ratingKey)) continue;
      ImageView image = new ImageView(this);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      image.setBackgroundColor(TvTheme.SURFACE_SUNKEN);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(63));
      lp.rightMargin = dp(6);
      pictures.addView(image, lp);
      loadArt(image, "/library/metadata/" + ratingKey + "/thumb", 240);
    }
    if (pictures.getChildCount() > 0) {
      card.addView(pictures);
      ui.gap(card, 14);
    }
    boolean isApproximate = QueueDetail.isApproximate(sitting);
    String span =
        QueueDetail.sittingSpan(
            sitting.optLong("startedAt"), sitting.optLong("endedAt"), isApproximate, now, zone);
    TextView eyebrow = ui.text(span.toUpperCase(Locale.US), 12, TvUi.ACCENT);
    eyebrow.setTag("sitting-span");
    card.addView(eyebrow);
    ui.gap(card, 10);
    TextView name = ui.title(listed.optString("title"), 21);
    name.setMaxLines(2);
    name.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(name);
    ui.gap(card, 10);
    List<String> spoken = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      JSONObject item = items.optJSONObject(i);
      String source = QueueDetail.source(item), label = QueueDetail.historyItem(item);
      TextView line = ui.text(label + (source.isEmpty() ? "" : " · " + source), 15, TvUi.WHITE);
      ui.withIcon(
          line,
          item.optString("state").equals("finished")
              ? R.drawable.lucide_circle_check
              : R.drawable.lucide_clock);
      line.setMaxLines(2);
      line.setEllipsize(TextUtils.TruncateAt.END);
      line.setTag("sitting-item");
      card.addView(line);
      ui.gap(card, 6);
      spoken.add(
          (item.optString("state").equals("finished") ? "Finished, " : "Partly watched, ")
              + label);
    }
    ui.gap(card, 6);
    String footer =
        QueueDetail.sittingFooter(sitting) + (isApproximate ? " · times are approximate" : "");
    card.addView(ui.text(footer, 13, TvUi.MUTED));
    ui.focusable(card, () -> sittingPanel(listed, sitting, queue, span), TvUi.PANEL, 12);
    card.setContentDescription(
        listed.optString("title") + ". " + span + ". " + String.join(". ", spoken) + ". " + footer);
    card.setTag("sitting");
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), -2);
    lp.rightMargin = dp(14);
    card.setLayoutParams(lp);
    return card;
  }

  /** Continue the queue, play one item from the sitting again, or open the full queue. */
  private void sittingPanel(JSONObject listed, JSONObject sitting, JSONObject queue, String span) {
    LinearLayout body = ui.column();
    TvPanel[] holder = new TvPanel[1];
    panelButton(
        body,
        "Continue the queue",
        R.drawable.lucide_play,
        true,
        () -> {
          holder[0].dismiss();
          startNativeQueue(queue);
        });
    JSONArray items = sitting.optJSONArray("items");
    if ("rules".equals(queue.optString("kind")))
      panelNote(
          body, "A Rules queue chooses its own order each time, so it always starts from the top.");
    else {
      Set<String> offered = new HashSet<>();
      for (int i = 0; items != null && i < items.length() && offered.size() < 6; i++) {
        JSONObject item = items.optJSONObject(i);
        String entryKey = QueueDetail.text(item, "entryKey");
        String key = QueueDetail.text(item, "plexServer") + ":" + QueueDetail.text(item, "ratingKey");
        if (entryKey.isEmpty() || !offered.add(key)) continue;
        String label = QueueDetail.secondary(item);
        if (label.isEmpty()) label = QueueDetail.primary(item);
        panelButton(
            body,
            "Play " + label,
            R.drawable.lucide_circle_play,
            false,
            () -> {
              holder[0].dismiss();
              startFrom(queue, entryKey, item, "only");
            });
      }
    }
    panelButton(
        body,
        "Open the full queue",
        R.drawable.lucide_list_ordered,
        false,
        () -> {
          holder[0].dismiss();
          openFullQueue(queue);
        });
    panelButton(body, "Close", R.drawable.lucide_x, false, () -> holder[0].dismiss());
    holder[0] = panel().setTitle(listed.optString("title")).setMessage(span).setView(body).create();
    holder[0].show();
    holder[0].setPanelSize(dp(460), -2);
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
    status.setText("Playback preview · " + versionName());
    LinearLayout row = horizontal();
    row.addView(
        ui.card(
            "Plex account",
            R.drawable.lucide_circle_user_round,
            account.optString("name", "Connect Plex"),
            "Sign in on your phone using a TV code.",
            "Sign in with Plex",
            () -> navigate(new Screen("signin")),
            270));
    row.addView(
        ui.card(
            "QueuePilot",
            R.drawable.lucide_list_video,
            "Your queue server",
            "Connect the server that manages your watching queues.",
            "Connect QueuePilot",
            this::queueSetup,
            270));
    row.addView(
        ui.card(
            "Plex Home",
            R.drawable.lucide_users,
            "Who's watching?",
            "Choose a profile and its library access.",
            "Switch profile",
            () -> navigate(new Screen("profiles")),
            270));
    ui.gap(content, 22);
    LinearLayout preferences = ui.row();
    TextView rewindButton =
        ui.button(rewindLabel(), R.drawable.lucide_rotate_ccw, this::rewindSetting, false);
    rewindButton.setTag("rewind-setting");
    preferences.addView(rewindButton);
    TextView sizeButton =
        ui.button(
            "Display size · " + DisplaySize.label(prefs()),
            R.drawable.lucide_scaling,
            this::displaySizeSetting,
            false);
    LinearLayout.LayoutParams sizeGap = new LinearLayout.LayoutParams(-2, -2);
    sizeGap.leftMargin = dp(12);
    preferences.addView(sizeButton, sizeGap);
    content.addView(preferences);
    focusFirst(row.getChildAt(0));
  }

  private LinearLayout seekPreview;
  private ImageView seekPreviewImage;
  private TextView seekPreviewTime;
  private BifIndex seekIndex;
  private boolean isSeekIndexRequested;
  private int seekPreviewFrame = -1;

  /**
   * Seek thumbnails: while the time bar is scrubbed, by touch or by D-pad, a rounded preview of
   * the frame at that time sits above the bar with the time beneath it. The BIF is fetched only
   * on the first scrub, and only the frame being shown is decoded.
   */
  private void installSeekPreview(FrameLayout root) {
    seekIndex = null;
    isSeekIndexRequested = false;
    seekPreviewFrame = -1;
    View bar = playerView.findViewById(androidx.media3.ui.R.id.exo_progress);
    if (!(bar instanceof androidx.media3.ui.TimeBar timeBar)) return;
    LinearLayout box = ui.column();
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    box.setVisibility(View.GONE);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackground(ui.shape(TvUi.PANEL, TvUi.WHITE, 12));
    image.setClipToOutline(true);
    box.addView(image, new LinearLayout.LayoutParams(dp(240), dp(135)));
    ui.gap(box, 8);
    TextView time = ui.text("", 16, TvUi.WHITE);
    time.setPadding(dp(10), dp(4), dp(10), dp(4));
    time.setBackground(ui.shape(TvUi.BG, 0, 8));
    box.addView(time);
    root.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
    seekPreview = box;
    seekPreviewImage = image;
    seekPreviewTime = time;
    timeBar.addListener(
        new androidx.media3.ui.TimeBar.OnScrubListener() {
          @Override
          public void onScrubStart(androidx.media3.ui.TimeBar t, long position) {
            requestSeekIndex();
            showSeekPreview(bar, position);
          }

          @Override
          public void onScrubMove(androidx.media3.ui.TimeBar t, long position) {
            showSeekPreview(bar, position);
          }

          @Override
          public void onScrubStop(androidx.media3.ui.TimeBar t, long position, boolean canceled) {
            box.setVisibility(View.GONE);
          }
        });
  }

  private void requestSeekIndex() {
    PlexCatalog.Entry entry = playingEntry;
    if (isSeekIndexRequested || entry == null) return;
    isSeekIndexRequested = true;
    String partId = entry.facts().optString("partId"), indexes = entry.facts().optString("indexes");
    if (partId.isEmpty() || !java.util.Arrays.asList(indexes.split(",")).contains("sd")) return;
    String origin = playingOrigin.isEmpty() ? server() : playingOrigin, secret = playingToken;
    int request = generation;
    network.execute(
        () -> {
          try {
            byte[] bytes =
                PlexCatalog.get(
                    PlexCatalog.url(origin, "/library/parts/" + partId + "/indexes/sd"),
                    secret,
                    BifIndex.MAX_BYTES);
            BifIndex index = BifIndex.parse(bytes);
            runOnUiThread(
                () -> {
                  if (request != generation || playingEntry != entry) return;
                  seekIndex = index;
                  seekPreviewFrame = -1;
                  if (seekPreview != null && seekPreview.getVisibility() == View.VISIBLE) {
                    View bar = playerView.findViewById(androidx.media3.ui.R.id.exo_progress);
                    Object position = seekPreview.getTag();
                    if (bar != null && position instanceof Long value) showSeekPreview(bar, value);
                  }
                });
          } catch (Exception e) {
            // No thumbnails: the time alone is still shown while scrubbing.
          }
        });
  }

  private void showSeekPreview(View bar, long requested) {
    if (seekPreview == null || player == null) return;
    long duration = Math.max(0, playingDuration());
    long floor = Math.max(0, sectionFloor);
    long ceiling = sectionEnd > 0 ? sectionEnd : duration;
    long position = Math.max(floor, Math.min(requested, Math.max(floor, ceiling)));
    seekPreview.setTag(position);
    String label = clockTime(position);
    seekPreviewTime.setText(label);
    String spoken = "Seek preview, " + label;
    if (seekIndex != null) {
      int frame = seekIndex.frameAt(position);
      if (frame != seekPreviewFrame) {
        Bitmap image =
            BitmapFactory.decodeByteArray(
                seekIndex.data(), seekIndex.frameOffset(frame), seekIndex.frameLength(frame));
        seekPreviewImage.setImageBitmap(image);
        seekPreviewFrame = frame;
      }
      spoken += ", frame " + (frame + 1) + " of " + seekIndex.frameCount();
    }
    seekPreview.setContentDescription(spoken);
    seekPreview.setVisibility(View.VISIBLE);
    View root = (View) seekPreview.getParent();
    seekPreview.measure(
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int width = seekPreview.getMeasuredWidth(), height = seekPreview.getMeasuredHeight();
    int[] barAt = new int[2], rootAt = new int[2];
    bar.getLocationInWindow(barAt);
    root.getLocationInWindow(rootAt);
    float fraction = duration <= 0 ? 0 : position / (float) duration;
    float center = barAt[0] - rootAt[0] + fraction * bar.getWidth();
    int margin = dp(24);
    // Clamped so the box never leaves the screen at either end of the bar.
    float x = Math.max(margin, Math.min(center - width / 2f, root.getWidth() - width - margin));
    float y = Math.max(margin, barAt[1] - rootAt[1] - height - dp(18));
    seekPreview.setTranslationX(x);
    seekPreview.setTranslationY(y);
  }

  private static String clockTime(long milliseconds) {
    long seconds = Math.max(0, milliseconds / 1000);
    long hours = seconds / 3600, minutes = seconds / 60 % 60, rest = seconds % 60;
    return hours > 0
        ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
        : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
  }

  /**
   * Media3 sizes its controller from its own resources, so the Display size is applied by
   * scaling each control group about the edge it is anchored to. The overlay text and the Skip
   * button are built with TvUi and already follow the size.
   */
  private void scalePlayerControls(PlayerView view) {
    if (ui.scale == 1f) return;
    int[][] groups = {
      {androidx.media3.ui.R.id.exo_center_controls, Gravity.CENTER},
      {androidx.media3.ui.R.id.exo_time, Gravity.START | Gravity.BOTTOM},
      {androidx.media3.ui.R.id.exo_basic_controls, Gravity.END | Gravity.BOTTOM},
      {androidx.media3.ui.R.id.exo_extra_controls, Gravity.END | Gravity.BOTTOM},
    };
    for (int[] group : groups) {
      View control = view.findViewById(group[0]);
      if (control == null) continue;
      control.addOnLayoutChangeListener(
          (v, l, t, r, b, ol, ot, or, ob) -> {
            boolean isStart = (group[1] & Gravity.START) == Gravity.START,
                isEnd = (group[1] & Gravity.END) == Gravity.END;
            v.setPivotX(isStart ? 0 : isEnd ? v.getWidth() : v.getWidth() / 2f);
            v.setPivotY(group[1] == Gravity.CENTER ? v.getHeight() / 2f : v.getHeight());
          });
      control.setScaleX(ui.scale);
      control.setScaleY(ui.scale);
    }
  }

  /**
   * Display size: Auto, computed from the screen and the viewing distance entered above it, or a
   * manual size from 50% to 125%.
   */
  private void displaySizeSetting() {
    float inches = prefs().getFloat(DisplaySize.SCREEN_KEY, DisplaySize.DEFAULT_SCREEN_INCHES);
    float feet = prefs().getFloat(DisplaySize.DISTANCE_KEY, DisplaySize.DEFAULT_DISTANCE_FEET);
    int stored =
        DisplaySize.valid(prefs().getInt(DisplaySize.PREFERENCE_KEY, DisplaySize.DEFAULT_PERCENT));
    String[] choices = new String[DisplaySize.CHOICES.length + 1];
    choices[0] = DisplaySize.label(DisplaySize.AUTO, inches, feet);
    for (int i = 0; i < DisplaySize.CHOICES.length; i++)
      choices[i + 1] = DisplaySize.label(DisplaySize.CHOICES[i], inches, feet);
    TvPanel[] holder = new TvPanel[1];
    LinearLayout measures = ui.column();
    TextView screen =
        ui.button(
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
    ui.gap(measures, 8);
    TextView distance =
        ui.button(
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
    ui.gap(measures, 12);
    holder[0] =
        panel()
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
                  prefs()
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
    holder[0].setPanelSize(dp(330), -2);
  }

  /** Applies at once: every later view, panel, and grid reads the new size. */
  private void applyDisplaySize(boolean isReopened) {
    ui = new TvUi(this);
    getWindow()
        .getDecorView()
        .post(
            () -> {
              render(current);
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
            ? DisplaySize.screenInches(prefs().getFloat(key, DisplaySize.DEFAULT_SCREEN_INCHES))
            : DisplaySize.distanceFeet(prefs().getFloat(key, DisplaySize.DEFAULT_DISTANCE_FEET));
    String unit = isScreen ? "inches" : "feet";
    LinearLayout form = ui.column();
    form.setPadding(dp(4), dp(4), dp(4), dp(8));
    EditText input = new EditText(this);
    input.setSingleLine();
    input.setTag("display-measure");
    input.setInputType(
        InputType.TYPE_CLASS_NUMBER | (isScreen ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL));
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    input.setText(DisplaySize.number(value));
    input.setSelectAllOnFocus(true);
    input.setTypeface(TvTheme.regular(this));
    input.setTextColor(TvUi.WHITE);
    input.setContentDescription(isScreen ? "Screen size in inches" : "Viewing distance in feet");
    form.addView(input, new LinearLayout.LayoutParams(-1, dp(54)));
    TvPanel d =
        panel()
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
          prefs().edit().putFloat(key, entered).apply();
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
          d.setPanelSize(dp(360), -2);
          d.getButton(TvPanel.BUTTON_POSITIVE).setOnClickListener(b -> save.run());
          input.requestFocus();
        });
    d.show();
  }

  /** The installed version, so Settings never shows a stale hand-written number. */
  private String versionName() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception e) {
      return "";
    }
  }

  private int rewindSeconds() {
    return prefs().getInt(RewindOnResume.PREFERENCE_KEY, RewindOnResume.DEFAULT_SECONDS);
  }

  private String rewindLabel() {
    return "Rewind on resume · " + RewindOnResume.label(rewindSeconds());
  }

  private void rewindSetting() {
    String[] choices = {"Off", "1 second", "3 seconds", "5 seconds", "10 seconds"};
    int[] values = {0, 1, 3, 5, 10};
    TvPanel dialog =
        panel()
            .setTitle("Rewind on resume")
            .setMessage("Replay a little when you continue after pausing.")
            .setSelected(java.util.Arrays.binarySearch(values, rewindSeconds()))
            .setItems(
                choices,
                (d, i) -> {
                  prefs().edit().putInt(RewindOnResume.PREFERENCE_KEY, values[i]).apply();
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
            Bitmap image =
                portrait(
                    secret.isEmpty()
                        ? AvatarImage.get(address)
                        : PlexCatalog.get(address, secret, 4 * 1024 * 1024));
            if (image == null) return;
            runOnUiThread(
                () -> {
                  if (request == generation) face.setImageBitmap(image);
                });
          } catch (Exception ignored) {
          }
        });
  }

  /** Decodes a bounded portrait, downsampled to at most 512 pixels wide. */
  private static Bitmap portrait(byte[] bytes) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (bounds.outWidth <= 0
        || bounds.outWidth > 4096
        || bounds.outHeight <= 0
        || bounds.outHeight > 6144) return null;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 1;
    while (bounds.outWidth / options.inSampleSize > 512) options.inSampleSize *= 2;
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
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
    if (entry == null
        || TitleCast.inheritedKeys(entry.type(), entry.seasonKey(), entry.showKey()).isEmpty()) {
      castDialog(entry);
      return;
    }
    if (isCastLoading) return;
    isCastLoading = true;
    String origin = playingOrigin, secret = playingToken;
    int request = generation;
    network.execute(
        () -> {
          PlexCatalog.Entry complete = withInheritedCast(entry, origin, secret);
          runOnUiThread(
              () -> {
                isCastLoading = false;
                if (request != generation) return;
                if (playingEntry == entry) playingEntry = complete;
                castDialog(complete);
              });
        });
  }

  /**
   * Adds the season's and show's credits after the episode's own. Uses only the server
   * connection that is playing the episode; a missing parent leaves the episode's credits.
   */
  private PlexCatalog.Entry withInheritedCast(
      PlexCatalog.Entry entry, String origin, String secret) {
    List<JSONObject> credits = new ArrayList<>();
    collectCredits(credits, entry.roles());
    for (String key : TitleCast.inheritedKeys(entry.type(), entry.seasonKey(), entry.showKey())) {
      try {
        JSONArray parents =
            PlexCatalog.list(
                PlexCatalog.get(
                    PlexCatalog.url(origin, "/library/metadata/" + key), secret, 16 * 1024 * 1024));
        if (parents.length() > 0)
          collectCredits(credits, parents.getJSONObject(0).optJSONArray("roles"));
      } catch (Exception ignored) {
      }
    }
    return entry.withCast(
        new JSONArray(
            TitleCast.distinct(
                credits,
                credit -> TitleCast.identity(credit.optString("kind"), credit.optString("name")))));
  }

  private static void collectCredits(List<JSONObject> credits, JSONArray roles) {
    if (roles == null) return;
    for (int i = 0; i < roles.length(); i++) {
      JSONObject credit = roles.optJSONObject(i);
      if (credit != null) credits.add(credit);
    }
  }

  private void loadCastPhoto(ImageView face, String thumb, String name) {
    loadCastPhoto(face, thumb, name, playingOrigin, playingToken);
  }

  private void loadCastPhoto(
      ImageView face, String thumb, String name, String origin, String secret) {
    boolean isExternal = TitleCast.isExternalPhoto(thumb);
    if (!isExternal && !(thumb.startsWith("/") && !thumb.startsWith("//"))) return;
    int request = generation;
    network.execute(
        () -> {
          Bitmap image = null;
          try {
            // The server resizes its own paths and proxies external portraits.
            image = artwork(origin, secret, thumb, 360);
          } catch (Exception ignored) {
          }
          if (image == null && isExternal) {
            try {
              // Credential-free fallback straight to the portrait host.
              image = portrait(AvatarImage.get(thumb));
            } catch (Exception ignored) {
            }
          }
          if (image == null) return;
          Bitmap loaded = image;
          runOnUiThread(
              () -> {
                if (request != generation) return;
                face.setImageBitmap(loaded);
                face.setContentDescription("Photo of " + name);
              });
        });
  }

  private void castDialog(PlexCatalog.Entry entry) {
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
      loadCastPhoto(face, actor.optString("thumb"), actor.optString("name"));
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
            .setTitle(
                "Cast · "
                    + entry.primary()
                    + (entry.showTitle().isEmpty() ? "" : " · " + entry.secondary()))
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
          new int[] {android.R.attr.state_focused}, ui.shape(TvTheme.CONTROL_FOCUSED, TvTheme.FOCUS_RING, 50));
      states.addState(
          new int[] {android.R.attr.state_pressed}, ui.shape(TvTheme.CONTROL_PRESSED, 0, 50));
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
    bindPlaybackMenu(
        androidx.media3.ui.R.id.exo_subtitle,
        anchor -> trackOptions(anchor, androidx.media3.common.C.TRACK_TYPE_TEXT));
    bindPlaybackMenu(
        androidx.media3.ui.R.id.exo_audio_track,
        anchor -> trackOptions(anchor, androidx.media3.common.C.TRACK_TYPE_AUDIO));
    bindPlaybackMenu(androidx.media3.ui.R.id.exo_playback_speed, this::speedOptions);
  }

  private void bindPlaybackMenu(int id, java.util.function.Consumer<View> open) {
    View control = playerView.findViewById(id);
    if (control == null) return;
    control.setOnClickListener(
        view -> {
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

  // Add to queue: a side panel listing every Picks queue. Each plays as its own profile.

  /**
   * Lists the queues a title can be added to and adds it to the chosen one. {@code isShow}
   * says the title is the show of the episode on screen, which is named in the panel.
   */
  private void addToQueue(String ratingKey, String title, boolean isShow) {
    String origin = prefs().getString("queue-origin", "");
    if (origin.isEmpty()) {
      message(
          "Add to queue",
          "Connect your QueuePilot server in Settings to add titles to your queues.");
      return;
    }
    LinearLayout body = ui.column();
    TvPanel[] holder = new TvPanel[1];
    holder[0] =
        panel()
            .setTitle("Add to a queue")
            .setMessage(isShow ? title + " · the whole show" : title)
            .setView(body)
            .create();
    panelNote(body, "Finding your queues…");
    holder[0].show();
    holder[0].setPanelSize(dp(460), -2);
    fetchQueues(
        origin,
        Set.of(),
        all -> {
          if (!holder[0].isShowing()) return;
          JSONArray people = all.optJSONArray("people");
          if (people != null) queuePeople = people;
          JSONArray listed = all.optJSONArray("queues");
          List<JSONObject> queues = new ArrayList<>();
          for (int i = 0; listed != null && i < listed.length(); i++)
            if (listed.optJSONObject(i) != null) queues.add(listed.optJSONObject(i));
          QueueList.AddTargets<JSONObject> targets =
              QueueList.addTargets(queues, q -> q.optString("kind"));
          body.removeAllViews();
          View first = null;
          for (JSONObject queue : targets.addable()) {
            String name = queue.optString("title", "Movies & Shows"), audience = queueAudience(queue);
            boolean isLocked = queue.optBoolean("isLocked");
            TextView choice =
                panelButton(
                    body,
                    name + " · " + audience,
                    isLocked ? R.drawable.lucide_lock : R.drawable.lucide_list_plus,
                    false,
                    () -> {
                      holder[0].dismiss();
                      postAddToQueue(queue, ratingKey, title);
                    });
            // Titles repeat, so the spoken name carries the audience as the queue cards do.
            choice.setContentDescription(
                "Add to " + name + ". " + audience + (isLocked ? ". Locked" : ""));
            if (first == null) first = choice;
          }
          if (targets.addable().isEmpty()) panelNote(body, "There is no Picks queue yet.");
          if (targets.rules() > 0)
            panelNote(
                body, "Rules queues choose their own titles, so they can't take a title you add.");
          if (all.optBoolean("cached"))
            panelNote(body, "Saved queues · QueuePilot is unavailable, so adding may fail.");
          TextView close =
              panelButton(body, "Close", R.drawable.lucide_x, false, () -> holder[0].dismiss());
          holder[0].setPanelSize(dp(460), -2);
          (first == null ? close : first).requestFocus();
        },
        error -> {
          if (!holder[0].isShowing()) return;
          body.removeAllViews();
          panelNote(body, "QueuePilot couldn't load your queues. " + error.getMessage());
          panelButton(body, "Close", R.drawable.lucide_x, false, () -> holder[0].dismiss()).requestFocus();
          holder[0].setPanelSize(dp(460), -2);
        });
  }

  private void postAddToQueue(JSONObject queue, String ratingKey, String title) {
    String origin = prefs().getString("queue-origin", ""), setId = queue.optString("id");
    String name = queue.optString("title", "Movies & Shows");
    String serverId = profile == null ? "" : profile.optString("serverId");
    if (status != null) status.setText("Adding to " + name + "…");
    int request = generation;
    network.execute(
        () -> {
          try {
            JSONObject body = new JSONObject().put("ratingKey", ratingKey);
            if (!serverId.isEmpty()) body.put("plexServer", serverId);
            JSONObject result =
                NativeQueueApi.request(
                    origin,
                    "/api/native/queues/" + PlexAccount.encode(setId) + "/items",
                    body,
                    "X-Plex-Profile-Token",
                    profileProof());
            boolean isAdded = result.optBoolean("added");
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  status.setText(isAdded ? "Added to " + name : "Already in " + name);
                  message(
                      isAdded ? "Added to queue" : "Already in this queue",
                      isAdded
                          ? title + " is now at the top of " + name + "."
                          : title + " is already in " + name + ".");
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  if (askPinIfLocked(error, () -> postAddToQueue(queue, ratingKey, title))) return;
                  status.setText("The title wasn't added.");
                  message("Add to queue", error.getMessage());
                });
          }
        });
  }

  // Search: Plex's /hubs/search, grouped into Movies, Shows, and Episodes.

  /** The last query per search screen ("" for the whole library, or a section key). */
  private final Map<String, String> searchQueries = new HashMap<>();

  private int searchSequence;
  private EditText searchField;
  private Runnable pendingSearch;

  private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> voice =
      registerForActivityResult(
          new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getData() == null || searchField == null || !searchField.isAttachedToWindow())
              return;
            ArrayList<String> heard =
                result
                    .getData()
                    .getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (heard != null && !heard.isEmpty()) searchField.setText(heard.get(0));
          });

  private boolean isVoiceAvailable() {
    return !getPackageManager()
        .queryIntentActivities(
            new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        .isEmpty();
  }

  private void search(Screen screen) {
    String section = screen.path(), sectionTitle = screen.title();
    shell("Search");
    heading(section.isEmpty() ? "Your library" : sectionTitle, "Search");
    if (profile == null) {
      status.setText("Sign in to search your library.");
      return;
    }
    status.setText(
        section.isEmpty() ? "Movies, shows, and episodes" : "Titles in " + sectionTitle);
    LinearLayout bar = ui.row();
    bar.setGravity(Gravity.CENTER_VERTICAL);
    EditText field = new EditText(this);
    searchField = field;
    field.setSingleLine(true);
    field.setInputType(InputType.TYPE_CLASS_TEXT);
    // The keyboard stays below the field, so the results above it stay in view.
    field.setImeOptions(
        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
    field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TvUi.typeSize(18) * ui.scale);
    field.setTextColor(TvUi.WHITE);
    field.setHintTextColor(TvUi.MUTED);
    field.setHint("Search titles");
    field.setContentDescription("Search titles");
    field.setPadding(dp(16), dp(12), dp(16), dp(12));
    ui.withIcon(field, R.drawable.lucide_search);
    field.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(TvUi.MUTED));
    field.setBackground(ui.shape(TvUi.PANEL, 0, 8));
    field.setOnFocusChangeListener(
        (v, focused) -> v.setBackground(ui.shape(TvUi.PANEL, focused ? TvUi.ACCENT : 0, 8)));
    bar.addView(field, new LinearLayout.LayoutParams(dp(560), -2));
    if (isVoiceAvailable()) {
      TextView mic =
          ui.button(
              "Voice",
              R.drawable.lucide_mic,
              () -> {
                try {
                  voice.launch(
                      new android.content.Intent(
                              android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                          .putExtra(
                              android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                              android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                          .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a title"));
                } catch (Exception unavailable) {
                  message("Search by voice", "Voice search isn't available on this device.");
                }
              },
              false);
      mic.setContentDescription("Search by voice");
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.leftMargin = dp(12);
      bar.addView(mic, lp);
    }
    content.addView(bar);
    ui.gap(content, 10);
    LinearLayout results = ui.column();
    content.addView(results);
    // Back from a result draws the same results again and focuses the result that was opened.
    boolean[] isRestoring = {
      focusMemory.containsKey(current) && !searchQueries.getOrDefault(section, "").isEmpty()
    };
    android.text.TextWatcher watcher =
        new android.text.TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence t, int a, int b, int c) {}

          @Override
          public void onTextChanged(CharSequence t, int a, int b, int c) {}

          @Override
          public void afterTextChanged(android.text.Editable text) {
            if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
            String query = text.toString();
            pendingSearch = () -> runSearch(section, query, results, isRestoring);
            handler.postDelayed(pendingSearch, 450);
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
          if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
          runSearch(section, field.getText().toString(), results, isRestoring);
          ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
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
    if (!isRestoring[0]) focusFirst(field);
  }

  private static final String[][] SEARCH_GROUPS = {
    {"movie", "Movies"}, {"show", "Shows"}, {"episode", "Episodes"},
  };

  private void runSearch(String section, String query, LinearLayout results, boolean[] isRestoring) {
    String trimmed = query.trim();
    searchQueries.put(section, trimmed);
    int sequence = ++searchSequence, request = generation;
    if (trimmed.isEmpty()) {
      results.removeAllViews();
      status.setText(section.isEmpty() ? "Movies, shows, and episodes" : "Titles in this library");
      return;
    }
    status.setText("Searching for “" + trimmed + "”…");
    String origin = server(), secret = token();
    String path =
        "/hubs/search?query="
            + PlexAccount.encode(trimmed)
            + "&limit=30"
            + (section.isEmpty() ? "" : "&sectionId=" + PlexAccount.encode(section));
    network.execute(
        () -> {
          try {
            JSONArray found =
                PlexCatalog.hubs(
                    PlexCatalog.get(PlexCatalog.url(origin, path), secret, 16 * 1024 * 1024));
            runOnUiThread(
                () -> {
                  if (request != generation || sequence != searchSequence) return;
                  View first = drawSearchResults(found, results);
                  int count = 0;
                  for (int i = 0; i < found.length(); i++)
                    for (String[] group : SEARCH_GROUPS)
                      if (group[0].equals(found.optJSONObject(i).optString("hub"))) count++;
                  status.setText(
                      count == 0
                          ? "No titles match “" + trimmed + "”."
                          : count + (count == 1 ? " result" : " results") + " for “" + trimmed + "”");
                  if (isRestoring[0] && first != null) focusFirst(first);
                  isRestoring[0] = false;
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request != generation || sequence != searchSequence) return;
                  results.removeAllViews();
                  status.setText("Search is unavailable while your server can't be reached.");
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
      ui.gap(results, 18);
      TextView heading = ui.title(group[1], 21);
      heading.setTag("search-group");
      results.addView(heading);
      ui.gap(results, 12);
      LinearLayout row = horizontalIn(results);
      for (PlexCatalog.Entry entry : entries) {
        View card = poster(entry, -1);
        row.addView(card);
        if (first == null) first = card;
      }
    }
    return first;
  }

  private void message(String title, String text) {
    TvPanel d = panel().setTitle(title).setMessage(text).setPositiveButton("Close", null).create();
    d.show();
    d.setPanelSize(dp(360), -2);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    // Keep foreground media keys with the visible player, including when Cast or
    // another overlay owns focus. Shield's legacy session fallback can supply
    // an empty controller package for injected media keys.
    if (playerView != null && playerView.dispatchMediaKeyEvent(event)) return true;
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (playerView != null && playerView.dispatchMediaKeyEvent(event)) return true;
    return super.onKeyUp(keyCode, event);
  }

  private void releasePlayer() {
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    skipButton = null;
    seekPreview = null;
    seekIndex = null;
    if (player != null) {
      if (!advancing) reportPlayback("stopped");
      if (mediaSession != null) {
        mediaSession.release();
        mediaSession = null;
      }
      player.release();
      player = null;
    }
    if (assPlayback != null) {
      assPlayback.close();
      assPlayback = null;
    }
    playerView = null;
  }

  @Override
  protected void onStop() {
    super.onStop();
    // An unlock lasts until the app leaves the foreground (decision 2026-10-23). A sitting that
    // already started keeps playing: its session key, not the unlock, authorizes it.
    NativeQueueApi.unlockToken = "";
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
