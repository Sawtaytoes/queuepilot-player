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
  final ExecutorService network = Executors.newFixedThreadPool(4);
  final Handler handler = new Handler(Looper.getMainLooper());

  /** The logcat tag of the queue list's failures and live retries. */
  static final String QUEUE_LOG = "QueuePilotQueues";

  /**
   * The pending live retry of a screen that shows saved queues, or none. At boot, or when a TV
   * wakes, the app can open before Android has a default network; the first request then fails
   * and the screen keeps asking until QueuePilot answers (see {@link #loadLiveQueueLists}).
   */
  QueueLoader.LiveQueueRetry queueRetry;

  android.net.ConnectivityManager.NetworkCallback networkCallback;
  final Deque<Screen> history = new ArrayDeque<>();
  final Map<Screen, String> focusMemory = new HashMap<>();
  final StoryOrder demo = StoryOrder.demo();
  TvUi ui;
  ProfileStore store;
  JSONArray profiles = new JSONArray();
  JSONObject account = new JSONObject(), profile;
  String activeId = "demo-ada";
  PlexAccount auth;
  private String deviceName = DeviceName.FALLBACK;
  LinearLayout content;
  TextView status;
  ExoPlayer player;
  private AssPlayback assPlayback;
  private PlayerView playerView;
  private androidx.media3.session.MediaSession mediaSession;
  volatile int generation;
  int mainWidth;
  private boolean fixture;
  Screen current;
  JSONObject nativePlan;
  private int nativeIndex;
  private long nativeSequence;
  private final ExecutorService progressNetwork = Executors.newSingleThreadExecutor();

  /**
   * Adds playback events to the encrypted outbox, one at a time and in the order they happened, so
   * the main thread never encrypts or writes it. One for the process, never shut down: a recreated
   * activity's events still follow the ones its predecessor reported. Sending runs on {@link
   * #progressNetwork}, and {@link PendingEvents#drain} keeps one sender at a time.
   */
  private static final ExecutorService eventStore = Executors.newSingleThreadExecutor();

  private final java.util.concurrent.atomic.AtomicBoolean isFlushQueued =
      new java.util.concurrent.atomic.AtomicBoolean();

  /** One outbox for the process, so a recreated activity never holds a stale copy. */
  private static PendingEvents sharedPendingEvents;
  PlexCatalog.Entry playingEntry;
  boolean isCastLoading;
  private TextView skipButton;
  private List<MarkerSkip.Marker> playingMarkers = List.of();
  private MarkerSkip.Marker skipMarker;
  private long skipShownAt;
  private boolean isSkipDismissed;
  private boolean isControllerVisible;
  String playingOrigin = "", playingToken = "";
  /** The playing poster for the media session: JPEG bytes, and a tokenless LAN address. */
  private byte[] playingArtwork;

  private String playingArtworkUri = "";
  /** True while an extra plays: it reports nothing to Plex and never touches a queue. */
  boolean isPlayingExtra;
  /** The title screen whose extras take focus when it is drawn again after its title ended. */
  Screen extrasFocus;

  final TitleScreen titleScreen = new TitleScreen(this);
  final CastPanel castPanel = new CastPanel(this);
  final SettingsScreen settingsScreen = new SettingsScreen(this);

  /** What the screen parts in their own files ({@link TitleExtrasRow}, …) use of this activity. */
  final ScreenHost screenHost =
      new ScreenHost() {
        public android.app.Activity activity() { return MainActivity.this; }
        public TvUi ui() { return ui; }
        public int generation() { return generation; }
        public String server() { return MainActivity.this.server(); }
        public String token() { return MainActivity.this.token(); }
        public String profileName() { return MainActivity.this.profileName(); }
        public void background(Runnable work) { network.execute(work); }
        public void openLibrary(String path, String title) {
          navigate(new Screen("library", path, title, null));
        }
        public void message(String title, String text) { MainActivity.this.message(title, text); }
        public TvPanel.Builder panel() { return MainActivity.this.panel(); }
        public void loadArt(ImageView view, String thumb, int width) {
          MainActivity.this.loadArt(view, thumb, width);
        }
        public LinearLayout horizontalIn(LinearLayout parent) {
          return MainActivity.this.horizontalIn(parent);
        }
        public void status(String text) { status.setText(text); }
        public void fillDetailFacts(LinearLayout facts, PlexCatalog.Entry entry) {
          titleScreen.fillDetailFacts(facts, entry);
        }
        public void playExtra(PlexCatalog.Entry extra, View from) {
          MainActivity.this.playExtra(extra, from);
        }
        public View rememberedView() {
          String key = focusMemory.getOrDefault(current, "");
          return key.isEmpty() ? null : rememberedFocus(getWindow().getDecorView(), key);
        }
        public boolean takeExtrasFocus() {
          boolean isEnded = extrasFocus != null && extrasFocus == current;
          if (isEnded) extrasFocus = null;
          return isEnded;
        }
      };

  final TitleNavigation titleNavigation = new TitleNavigation(screenHost);
  private long sectionFloor, sectionEnd = -1;
  private boolean advancing;
  private final Set<TvPanel> panels = new HashSet<>();
  private final RewindOnResume rewind = new RewindOnResume();
  /** The playing part's Plex streams and the choice made from them, or null. */
  private PlaybackTracks playbackTracks;
  /** The server conversion the player is streaming, or null when it plays the file directly. */
  private ConversionSession conversion;
  /** True once the playing item has asked what to do about a file that can't play directly. */
  private boolean isDirectPlayHandled;
  /** Each profile's plex.tv language settings, read once and kept in memory. */
  private final Map<String, PlexTracks.Languages> accountLanguages =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** The catalog entry of the queue being played, saved with the profile's resume snapshot. */
  JSONObject nativeQueue;
  JSONArray queuePeople = new JSONArray();
  /** Every queue the catalog has listed, by id, so a full queue or history can start one. */
  final Map<String, JSONObject> knownQueues = new HashMap<>();
  /** "continue" or "only" when the playing session began at a chosen item, else empty. */
  private String nativeStartMode = "";
  /** The full queue's watched items are folded into one row until the viewer shows them. */
  boolean isWatchedShown;
  /** The item to focus when the full queue is drawn again after a change. */
  String fullQueueFocus = "";
  int historyDays = 30;

  SharedPreferences prefs() {
    return getSharedPreferences("state", MODE_PRIVATE);
  }

  int dp(int size) {
    return ui.dp(size);
  }

  private int displayPercent() {
    return DisplaySize.percent(prefs());
  }

  /** Columns for a grid that has {@code baseline} columns at 100%. */
  int columns(int baseline) {
    return DisplaySize.columns(baseline, displayPercent());
  }

  int itemWidth(int columns) {
    return DisplaySize.itemWidth(mainWidth, columns, 14);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    DebugChecks.install(this);
    ui = new TvUi(this);
    store = new ProfileStore(this);
    scheduleFlush();
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
    deviceName = DeviceName.read(this);
    auth = new PlexAccount(clientId, deviceName, fixture);
    NativeQueueApi.deviceId = clientId;
    try {
      profiles = store.read();
      account = store.account();
    } catch (Exception e) {
      Toast.makeText(this, "Please sign in again to restore access.", Toast.LENGTH_LONG).show();
    }
    queueLoader.watchDefaultNetwork();
    setProfile(prefs().getString("active", "demo-ada"));
    if (profile != null || prefs().getBoolean("preview", false)) home();
    else if (!account.optString("token").isEmpty()) profilePicker.openOwnProfile();
    else render(new Screen("welcome"));
  }

  final ProfilePicker profilePicker = new ProfilePicker(this);

  void setProfile(String id) {
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

  String profileName() {
    return profile == null
        ? (activeId.equals("demo-grace") ? "Grace" : "Ada")
        : profile.optString("name");
  }

  String server() {
    return profile == null ? "" : profile.optString("server");
  }

  String token() {
    return profile == null ? "" : profile.optString("token");
  }

  Set<String> completed() {
    return new HashSet<>(prefs().getStringSet("done:" + activeId, Set.of()));
  }

  void home() {
    history.clear();
    render(new Screen("home"));
  }

  void navigate(Screen screen) {
    extrasFocus = null;
    if (current != null) {
      View focused = getWindow().getDecorView().findFocus();
      if (focused != null) focusMemory.put(current, focusKey(focused));
      history.push(current);
    }
    render(screen);
  }

  void back() {
    if (player != null) reportPlayback("stopped");
    advancing = true;
    nativePlan = null;

    if (!history.isEmpty()) render(history.pop());
    else if (current != null && !Set.of("home", "welcome").contains(current.kind())) home();
    else moveTaskToBack(true);
  }

  void reset() {
    closePanels();
    generation++;
    handler.removeCallbacksAndMessages(null);
    queueRetry = null;
    releasePlayer();
  }

  void render(Screen screen) {
    reset();
    current = screen;
    switch (screen.kind()) {
      case "welcome" -> signInScreen.welcome();
      case "signin" -> signInScreen.signIn();
      case "profiles" -> profilePicker.chooseProfile();
      case "settings" -> settingsScreen.settings();
      case "library" -> load(screen.path(), screen.title());
      case "details" -> titleScreen.details(screen.entry());
      case "demo" -> demoCatalog.demoSeason();
      case "queues" -> watchingScreen.queues();
      case "fullqueue" -> fullQueueScreen.fullQueue(screen.path(), screen.title());
      case "history" -> historyScreen.history();
      case "search" -> searchScreen.search(screen);
      default -> homeScreen.drawHome();
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

  void shell(String selected) {
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
          if (user != null && profilePicker.userId(user).equals(activeId)) thumb = user.optString("thumb");
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

  void heading(String eyebrow, String title) {
    heading(eyebrow, title, 34);
  }

  TextView heading(String eyebrow, String title, int size) {
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

  void section(String title) {
    ui.gap(content, 26);
    content.addView(ui.title(title, 23));
    ui.gap(content, 16);
  }

  LinearLayout horizontal() {
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
    if (view.getTag() instanceof ScreenHost.FocusKey key) return key.value();
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

  void focusFirst(View view) {
    view.post(
        () -> {
          if (!view.isAttachedToWindow()) return;
          String key = focusMemory.getOrDefault(current, "");
          View target = key.isEmpty() ? null : rememberedFocus(getWindow().getDecorView(), key);
          (target == null ? view : target).requestFocus();
        });
  }

  void centered(String eyebrow, String title, String description) {
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

  final SignInScreen signInScreen = new SignInScreen(this);

  final HomeScreen homeScreen = new HomeScreen(this);

  final QueueLoader queueLoader = new QueueLoader(this);

  final QueueCards queueCards = new QueueCards(this);

  final DemoCatalog demoCatalog = new DemoCatalog(this);

  /** "S1 · E9 · The signal", or the pre-0.5.2 "Episode 9" for a cached entry without a show. */
  static String episodeLine(PlexCatalog.Entry entry, String fallback) {
    if (!entry.type().equals("episode")) return fallback;
    if (!entry.showTitle().isEmpty()) return entry.secondary();
    return entry.episode() > 0 ? "Episode " + entry.episode() : fallback;
  }

  interface Listing {
    void ready(JSONArray entries, boolean cached);
  }

  File cacheFile(String id, String origin, String path) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest((id + "|" + origin + "|" + path).getBytes(StandardCharsets.UTF_8));
    StringBuilder key = new StringBuilder();
    for (byte b : digest) key.append(String.format(Locale.ROOT, "%02x", b));
    return new File(getCacheDir(), key + ".json");
  }

  void fetch(String path, Listing callback, Runnable failure) {
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
  static final class Grid {
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

  Grid grid;

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
      titleScreen.loadParentHeader(parent.group(1), header);
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
  View poster(PlexCatalog.Entry entry, int index) {
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

  Bitmap artwork(String origin, String secret, String thumb, int width) throws Exception {
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

  void loadArt(ImageView view, String thumb, int width) {
    int request = generation;
    ArtworkLoader.load(view, server(), token(), thumb, width, () -> request == generation);
  }

  /**
   * Plays an extra and comes back to this screen. An extra reports nothing to Plex, so it is
   * never marked watched, never moves the title's resume point, and never touches a queue.
   */
  private void playExtra(PlexCatalog.Entry extra, View from) {
    if (current != null && from != null) focusMemory.put(current, focusKey(from));
    int request = generation;
    String origin = server(), secret = token();
    network.execute(
        () -> {
          try {
            PlexCatalog.Entry detail = extra;
            if (detail.part().isEmpty()) {
              JSONArray items =
                  PlexCatalog.list(
                      PlexCatalog.get(
                          PlexCatalog.url(origin, "/library/metadata/" + extra.key()),
                          secret,
                          4 * 1024 * 1024));
              detail = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            }
            String url = PlexCatalog.url(origin, detail.part());
            PlexCatalog.Entry playing = detail;
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  nativePlan = null;
                  playingEntry = playing;
                  playingArtwork = null;
                  playingArtworkUri = "";
                  playingOrigin = origin;
                  playingToken = secret;
                  sectionFloor = 0;
                  sectionEnd = -1;
                  advancing = false;
                  isPlayingExtra = true;
                  // An extra plays its file's own tracks: never the last title's choices or
                  // subtitle files, and a track change in it is not saved to Plex.
                  playbackTracks = null;
                  startPlayer(url, secret, playing.title(), "");
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request == generation)
                    message("Extras", "This extra couldn't be opened for direct playback.");
                });
          }
        });
  }

  LinearLayout horizontalIn(LinearLayout parent) {
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    LinearLayout row = ui.row();
    row.setPadding(dp(3), dp(3), dp(3), dp(3));
    scroll.addView(row);
    parent.addView(scroll);
    return row;
  }

  void playEntry(PlexCatalog.Entry entry) {
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
                            origin, "/library/metadata/" + entry.key() + "?includeMarkers=1&includeChapters=1"),
                        secret,
                        16 * 1024 * 1024));
            PlexCatalog.Entry detail = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            if (detail.part().isEmpty()) throw new IllegalStateException();
            // The chosen profile plays a single title, so its own language settings apply.
            PlaybackTracks tracks = PlaybackTracks.forEntry(detail, this::chosenLanguages);
            byte[] artwork = nowPlayingArtwork(origin, secret, detail);
            String artworkUri = isQueueHome ? queueThumbUri(detail) : "";
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  ResumePrompt.ask(
                      this,
                      panel(),
                      detail.primary(),
                      detail.resume(),
                      0,
                      detail.duration(),
                      rewindSeconds(),
                      start -> {
                        if (request != generation) return;
                        nativePlan = null;
                        isPlayingExtra = false;
                        playingEntry = detail;
                        playingArtwork = artwork;
                        playingArtworkUri = artworkUri;
                        playingOrigin = origin;
                        playingToken = secret;
                        playbackTracks = tracks;
                        sectionFloor = 0;
                        sectionEnd = -1;
                        advancing = false;
                        startPlayer(
                            PlexCatalog.url(origin, detail.part()),
                            secret,
                            detail.title(),
                            detail.cast());
                        player.seekTo(start);
                      });
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
    ui.action(actions, "Cast", R.drawable.lucide_users, () -> castPanel.showCast(playingEntry), false);
    if (!playingChapters().isEmpty()) {
      ui.action(
          actions,
          "Chapters",
          R.drawable.lucide_bookmark,
          () -> chapterOptions(actions.findViewWithTag("player-chapters")),
          false);
      actions.getChildAt(actions.getChildCount() - 1).setTag("player-chapters");
    }
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
    PlaybackStats playbackStats = PlaybackStatsOverlay.create(this); // null unless turned on
    player =
        PlaybackStatsTaps.tap(
                playbackStats, new ExoPlayer.Builder(this, ass.renderers(this)), this)
            .setSeekBackIncrementMs(SkipIntervals.backMs(PlaybackSettings.back(prefs())))
            .setSeekForwardIncrementMs(
                SkipIntervals.forwardMs(
                    playingEntry == null ? -1 : playingEntry.duration(),
                    PlaybackSettings.forwardShort(prefs()),
                    PlaybackSettings.forwardLong(prefs())))
            .setLoadControl(
                new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build())
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(
                    dataSourceFactory, PlaybackStatsTaps.tap(playbackStats, ass.extractors())))
            .build();
    mediaSession = new androidx.media3.session.MediaSession.Builder(this, player).build();
    playerView.setPlayer(player);
    PlaybackStatsOverlay.show(playbackStats, player, root, playingEntry);
    playerView.setShowSubtitleButton(true);
    applyCaptionStyle();
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
          public void onTracksChanged(androidx.media3.common.Tracks tracks) {
            if (player == null) return;
            if (playbackTracks != null) playbackTracks.apply(player, tracks);
            matchFrameRate();
            // A file can open and still have no video or audio this TV decodes.
            boolean hasVideo = tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO),
                hasAudio = tracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO);
            DirectPlayFailure.Reason undecodable =
                DirectPlayFailure.fromTracks(
                    hasVideo && !tracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_VIDEO),
                    hasAudio && !tracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_AUDIO),
                    playingFact("videoCodec"),
                    playingFact("audioCodec"));
            if (undecodable != null) cannotPlayDirectly(undecodable);
          }

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
              } else if (nativePlan == null && !advancing) handler.post(() -> titleScreen.returnToTitle());
            }
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            reportPlayback("error");
            overlay.setVisibility(View.VISIBLE);
            cannotPlayDirectly(
                DirectPlayFailure.fromError(
                    error.errorCode,
                    playingFact("container"),
                    playingFact("videoCodec"),
                    playingFact("audioCodec")));
          }
        });
    player.setMediaItem(
        new MediaItem.Builder()
            .setUri(url)
            .setMediaId(playingEntry == null ? "video" : playingEntry.key())
            .setMediaMetadata(nowPlayingMetadata(title))
            .setMimeType(
                ServerConversion.isConversion(url)
                    ? androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    : null)
            .setSubtitleConfigurations(
                playbackTracks == null
                    ? List.of()
                    : playbackTracks.externalSubtitles(
                        playingOrigin.isEmpty() ? server() : playingOrigin))
            .build());
    if (playbackTracks != null) playbackTracks.prepare(player);
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
              if (conversion != null && !player.isPlaying()) conversion.ping(progressNetwork);
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

  final WatchingScreen watchingScreen = new WatchingScreen(this);

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
  synchronized String profileProof() throws Exception {
    String proof = profile.optString("profileProof");
    if (!proof.isEmpty()) return proof;
    JSONObject homeUser = null;
    JSONArray homeRoster = account.optJSONArray("users");
    if (homeRoster != null)
      for (int i = 0; i < homeRoster.length(); i++) {
        JSONObject u = homeRoster.optJSONObject(i);
        if (u != null && profilePicker.userId(u).equals(activeId)) homeUser = u;
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

  void startNativeQueue(JSONObject queue) {
    startNativeQueue(queue, null);
  }

  /**
   * Starts a queue session. {@code start} is null for the engine's own next item, or a chosen
   * item {@code {entryKey, ratingKey, plexServer?, mode}}; earlier unwatched items stay unwatched.
   */
  void startNativeQueue(JSONObject queue, JSONObject start) {
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
                      prefs().edit().remove(queueLoader.snapshotKey()).apply();
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
    queueLoader.saveQueueSnapshot(item);
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
    // When Plex selects no streams, the queue profile's own plex.tv language settings decide. The
    // QueuePilot server sends them with the plan, since this TV holds no plex.tv token for another
    // profile. Without them, a queue that plays as the chosen profile uses the chosen profile's
    // settings; another profile's queue keeps Plex's selection, never the chosen profile's.
    JSONObject planLanguages = nativePlan.optJSONObject("languages");
    boolean isChosenProfile =
        grant == null || activeId.equals(nativePlan.optString("profileUuid"));
    java.util.concurrent.Callable<PlexTracks.Languages> queueLanguages =
        planLanguages != null
            ? () -> PlexTracks.Languages.fromUser(planLanguages)
            : isChosenProfile ? this::chosenLanguages : null;
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
                                        + "?includeMarkers=1&includeChapters=1"),
                                secret,
                                16 * 1024 * 1024))
                        .getJSONObject(0));
            if (entry.part().isEmpty())
              throw new IllegalStateException("This item has no direct playback source");
            String mediaOrigin = origin, mediaToken = secret;
            // Plex marks the queue profile's own choices, since the queue's grant asked. The
            // chosen profile's language settings are never applied to another profile's queue.
            PlaybackTracks tracks = PlaybackTracks.forEntry(entry, queueLanguages);
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
                  playbackTracks = tracks;
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

  final FullQueueScreen fullQueueScreen = new FullQueueScreen(this);

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

  final FullQueueItems fullQueueItems = new FullQueueItems(this);

  TextView panelButton(
      LinearLayout body, String label, int icon, boolean primary, Runnable action) {
    TextView button = ui.button(label, icon, action, primary);
    button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(8);
    body.addView(button, lp);
    return button;
  }

  void panelNote(LinearLayout body, String text) {
    TextView note = ui.text(text, 14, TvUi.MUTED);
    ui.withIcon(note, R.drawable.lucide_info);
    note.setPadding(0, dp(4), 0, dp(12));
    body.addView(note, new LinearLayout.LayoutParams(-1, -2));
  }

  final QueueItemPanel itemPanel = new QueueItemPanel(this);

  final HistoryScreen historyScreen = new HistoryScreen(this);

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

  int rewindSeconds() {
    return prefs().getInt(RewindOnResume.PREFERENCE_KEY, RewindOnResume.DEFAULT_SECONDS);
  }

  ImageView avatar(int size) {
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
  static Bitmap portrait(byte[] bytes) {
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

  void loadProfileAvatar(ImageView face, String thumb) {
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

  void loadQueueAvatar(ImageView face, String id, String origin) {
    loadPicture(
        face,
        PlexCatalog.url(origin, "/api/native/people/" + PlexAccount.encode(id) + "/avatar"),
        "");
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

  TvPanel.Builder panel() {
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
    List<String> items =
        new ArrayList<>(List.of("Audio", "Subtitles", "Playback speed", "Playback settings"));
    if (!playingChapters().isEmpty()) items.add(3, "Chapters");
    playbackPanel(anchor)
        .setTitle("Playback")
        .setItems(
            items.toArray(new String[0]),
            (d, index) -> {
              switch (items.get(index)) {
                case "Playback speed" -> speedOptions(anchor);
                case "Chapters" -> chapterOptions(anchor);
                case "Playback settings" -> playbackSettings().show();
                default ->
                    trackOptions(
                        anchor,
                        index == 0
                            ? androidx.media3.common.C.TRACK_TYPE_AUDIO
                            : androidx.media3.common.C.TRACK_TYPE_TEXT);
              }
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
    List<androidx.media3.common.Tracks.Group> rowGroups = new ArrayList<>();
    List<Integer> rowIndexes = new ArrayList<>();
    rowGroups.add(null);
    rowIndexes.add(-1);
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
        rowGroups.add(group);
        rowIndexes.add(i);
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
    int selectedRow = selected;
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
                  // Confirming the row that is already playing changes nothing to save.
                  if (i != selectedRow) saveTrackChoice(type, rowGroups.get(i), rowIndexes.get(i));
                })
            .setNegativeButton("Back", (d, i) -> playbackOptions(anchor));
    if (labels.size() == 1)
      builder.setMessage(
          text ? "No subtitle tracks in this video." : "No selectable audio tracks in this video.");
    builder.create().show();
  }

  // Playback features that live in their own classes; these are only their call sites.

  PlaybackSettings playbackSettings() {
    return new PlaybackSettings(this, prefs(), this::panel, this::matchFrameRate, () -> {});
  }

  private List<Chapters.Chapter> playingChapters() {
    return playingEntry == null
        ? List.of()
        : Chapters.fromJson(playingEntry.facts().optJSONArray("chapters"));
  }

  private void chapterOptions(View anchor) {
    List<Chapters.Chapter> chapters = playingChapters();
    if (player == null || chapters.isEmpty()) return;
    ExoPlayer target = player;
    String origin = playingOrigin, secret = playingToken;
    long floor = sectionFloor, end = sectionEnd;
    ChapterMenu.show(
        this,
        playbackPanel(anchor),
        chapters,
        target.getCurrentPosition(),
        network,
        thumb -> artwork(origin, secret, thumb, 240),
        start -> {
          if (player == target)
            target.seekTo(Chapters.seekTarget(start, floor, end));
        });
  }

  private void matchFrameRate() {
    if (player == null) return;
    DisplayModeSwitch.apply(
        getWindow(),
        PlaybackSettings.isFrameRateMatched(prefs()),
        playbackTracks == null ? 0 : playbackTracks.frameRate(),
        player.getCurrentTracks());
  }

  private String playingFact(String name) {
    return playingEntry == null ? "" : playingEntry.facts().optString(name);
  }

  /**
   * A file this TV can't play directly: ask, convert on the server, or stop, as Settings → Playback
   * says. Once per item; a failing conversion only says so.
   */
  private void cannotPlayDirectly(DirectPlayFailure.Reason reason) {
    if (player == null) return;
    if (conversion != null) {
      // The converted stream failed: end the server's session instead of keeping it alive.
      endConversion();
      status.setText("The server's conversion stopped.");
      return;
    }
    if (isDirectPlayHandled) return;
    isDirectPlayHandled = true;
    if (!reason.isConvertible() || playingEntry == null) {
      status.setText("This file couldn't be played. " + reason.text());
      return;
    }
    status.setText(reason.text());
    switch (ServerConversion.mode(prefs().getString(ServerConversion.PREFERENCE_KEY, null))) {
      case ServerConversion.CONVERT -> convertOnServer();
      case ServerConversion.STOP -> {
        back();
        message("This file can't play directly", reason.text() + " Settings says to stop.");
      }
      default ->
          ConversionSession.ask(
              this,
              panel(),
              reason,
              this::convertOnServer,
              () -> {
                back();
                message("Stopped", reason.text());
              });
    }
  }

  /**
   * Plays the item again through Plex's universal transcoder, from where it was, as the same
   * profile. Plex's own stream choice for the part decides the converted audio; a subtitle from
   * inside the file is burned into the picture.
   */
  private void convertOnServer() {
    PlexCatalog.Entry entry = playingEntry;
    if (player == null || entry == null) return;
    long position = Math.max(sectionFloor, player.getCurrentPosition());
    androidx.media3.common.Tracks current = player.getCurrentTracks();
    long burned =
        playbackTracks != null
            ? playbackTracks.burnedSubtitleId(current)
            : PlaybackTracks.isEmbeddedTextSelected(current) ? 0 : -1;
    String partId = entry.facts().optString("partId");
    ConversionSession session =
        new ConversionSession(
            playingOrigin,
            playingToken,
            new ServerConversion.Request(
                entry.key(),
                UUID.randomUUID().toString(),
                prefs().getString("plex-client-id", ""),
                deviceName,
                -1,
                burned >= 0));
    PlaybackTracks tracks =
        playbackTracks == null
            ? null
            : playbackTracks.forConversion(playbackTracks.shownSidecar(current));
    status.setText("Asking the server to convert this file…");
    int request = generation;
    network.execute(
        () -> {
          String refusal;
          try {
            // Plex burns the part's stored subtitle, so the one on screen is saved first.
            if (burned > 0
                && !PlaybackTracks.save(
                    session.origin, session.token, partId, PlexTracks.SUBTITLE, burned)) {
              refusal = "Plex didn't save the subtitle to burn into the picture.";
            } else {
              ServerConversion.Decision decision = session.decide();
              refusal = decision.isPlayable() ? null : decision.text();
            }
          } catch (Exception e) {
            refusal = e.getMessage() == null ? "" : e.getMessage();
          }
          String reason = refusal;
          runOnUiThread(
              () -> {
                if (request != generation || player == null) return;
                if (reason != null) {
                  status.setText("The server can't convert this file.");
                  message(
                      "The server can't convert this file",
                      reason.isEmpty() ? "Plex refused the conversion." : reason);
                  return;
                }
                boolean wasAdvancing = advancing;
                advancing = true; // Not a stop: the same item continues.
                playbackTracks = tracks;
                startPlayer(session.url(), session.token, entry.title(), entry.cast());
                advancing = wasAdvancing;
                // Set after startPlayer, whose release of the direct player ends no conversion.
                conversion = session;
                player.seekTo(position);
                status.setText("Converting on the server");
              });
        });
  }

  /** Ends the server's conversion session, if one is playing. */
  private void endConversion() {
    if (conversion == null) return;
    conversion.stop(progressNetwork);
    conversion = null;
  }

  /** Text subtitles follow Android's caption settings; ASS keeps its own styles. */
  private void applyCaptionStyle() {
    androidx.media3.ui.SubtitleView subtitles =
        playerView == null ? null : playerView.getSubtitleView();
    if (subtitles == null) return;
    subtitles.setUserDefaultStyle();
    subtitles.setUserDefaultTextSize();
  }

  /** The chosen profile's plex.tv language settings, or null. Call off the main thread. */
  private PlexTracks.Languages chosenLanguages() throws Exception {
    String proof = profileProof();
    PlexTracks.Languages known = accountLanguages.get(proof);
    if (known != null) return known;
    PlexTracks.Languages read = PlexTracks.Languages.fromUser(auth.user(proof));
    accountLanguages.put(proof, read);
    return read;
  }

  /** Saves a track the viewer chose to Plex, for the profile that is playing the item. */
  private void saveTrackChoice(int type, androidx.media3.common.Tracks.Group group, int index) {
    PlaybackTracks known = playbackTracks;
    if (player == null || known == null || !known.hasStreams() || playingEntry == null) return;
    boolean isText = type == androidx.media3.common.C.TRACK_TYPE_TEXT;
    if (group == null && !isText) return; // Automatic audio is not a choice to save.
    long streamId =
        group == null ? 0 : known.streamId(player.getCurrentTracks(), type, group, index);
    String origin = playingOrigin, secret = playingToken;
    String partId = playingEntry.facts().optString("partId");
    progressNetwork.execute(
        () ->
            PlaybackTracks.save(
                origin,
                secret,
                partId,
                isText ? PlexTracks.SUBTITLE : PlexTracks.AUDIO,
                streamId));
  }

  private void reportPlayback(String state) {
    reportPlayback(state, !PendingEvents.isProgressTick(state));
  }

  /**
   * Reports playback. A queue's events go to the encrypted outbox and are sent in order; {@code
   * isDurable} writes the outbox to disk before sending, for an event that may be the last before
   * the process ends.
   */
  private void reportPlayback(String state, boolean isDurable) {
    if (player == null || playingEntry == null) return;
    // An extra is never scrobbled and never moves a resume point; a queue never plays one.
    if (isPlayingExtra && nativePlan == null) return;
    long position = Math.max(0, player.getCurrentPosition()),
        duration = Math.max(0, playingDuration());
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
        eventStore.execute(
            () -> {
              try {
                pendingEvents().add(envelope, isDurable);
              } catch (Exception ignored) {
                // The outbox could not be read or written; the event cannot be kept.
              }
              scheduleFlush();
            });
      } catch (Exception ignored) {
        // Includes a report after onDestroy shut the executors down.
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
                      + "&X-Plex-Device-Name="
                      + PlexAccount.encode(deviceName)
                      + "&X-Plex-Product=QueuePilot%20TV&X-Plex-Platform=Android";
              PlexCatalog.get(PlexCatalog.url(origin, path), secret, 1024 * 1024);
            } catch (Exception ignored) {
            }
          });
    }
  }

  private PendingEvents pendingEvents() {
    synchronized (MainActivity.class) {
      if (sharedPendingEvents == null) {
        ProfileStore secrets = new ProfileStore(getApplicationContext());
        sharedPendingEvents =
            new PendingEvents(
                new PendingEvents.Storage() {
                  @Override
                  public String load() throws Exception {
                    return secrets.nativeEvents();
                  }

                  @Override
                  public void save(String json, boolean isDurable) throws Exception {
                    secrets.nativeEvents(json, isDurable);
                  }
                },
                System::currentTimeMillis);
      }
      return sharedPendingEvents;
    }
  }

  /** Sends the outbox soon. A send already waiting to start covers this request. */
  private void scheduleFlush() {
    if (!isFlushQueued.compareAndSet(false, true)) return;
    try {
      progressNetwork.execute(
          () -> {
            isFlushQueued.set(false);
            flushNativeEvents();
          });
    } catch (java.util.concurrent.RejectedExecutionException closed) {
      // onDestroy shut the sender down; the next start sends the outbox.
      isFlushQueued.set(false);
    }
  }

  /** Waits until every event reported so far is in the outbox. */
  private void awaitQueuedEvents() {
    try {
      eventStore.submit(() -> {}).get();
    } catch (Exception interrupted) {
      // The sender is stopping; the next start sends what is saved.
    }
  }

  /**
   * Waits on the main thread, at most {@code 1 s}, for the events just reported to reach the disk.
   * Only for leaving the screen: Android may end a stopped app's process at any time after.
   */
  private void awaitSavedEvents() {
    try {
      eventStore.submit(() -> {}).get(1, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception slow) {
      // The write continues in the background.
    }
  }

  /** Sends the outbox in order. Runs on {@link #progressNetwork}. */
  private void flushNativeEvents() {
    // A continuation or a new session must follow the events reported before it, such as the
    // "ended" that advances the server's cursor.
    awaitQueuedEvents();
    PendingEvents.Drained drained;
    try {
      drained =
          pendingEvents()
              .drain(
                  record ->
                      NativeQueueApi.request(
                          record.optString("origin"),
                          "/api/native/sessions/" + record.optString("sessionId") + "/events",
                          record.optJSONObject("event"),
                          "X-Native-Session-Key",
                          record.optString("sessionKey"),
                          record.optString("profileProof")),
                  error ->
                      error instanceof NativeQueueApi.Failure
                          ? ((NativeQueueApi.Failure) error).status
                          : 0);
    } catch (Exception unreadable) {
      return;
    }
    String message =
        drained.isWaiting
            ? "Progress is saved locally and waiting to sync."
            : drained.isRejected
                ? "Some saved history needs review. Its playback session is no longer valid."
                : null;
    if (message != null)
      runOnUiThread(
          () -> {
            if (player != null && status != null) status.setText(message);
          });
  }

  // Add to queue: a side panel listing every Picks queue. Each plays as its own profile.

  /**
   * Lists the queues a title can be added to and adds it to the chosen one. {@code isShow}
   * says the title is the show of the episode on screen, which is named in the panel.
   */
  void addToQueue(String ratingKey, String title, boolean isShow) {
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
    queueLoader.fetchQueues(
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
            String name = queue.optString("title", "Movies & Shows"), audience = queueCards.queueAudience(queue);
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

  final SearchScreen searchScreen = new SearchScreen(this);

  final androidx.activity.result.ActivityResultLauncher<android.content.Intent> voice =
      registerForActivityResult(
          new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getData() == null || searchScreen.searchField == null || !searchScreen.searchField.isAttachedToWindow())
              return;
            ArrayList<String> heard =
                result
                    .getData()
                    .getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (heard != null && !heard.isEmpty()) searchScreen.searchField.setText(heard.get(0));
          });

  void message(String title, String text) {
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
    DisplayModeSwitch.clear(getWindow());
    isDirectPlayHandled = false;
    endConversion();
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
  protected void onResume() {
    super.onResume();
    // The caption style may have been changed in Android's settings meanwhile.
    applyCaptionStyle();
    // Coming back to a screen that still shows saved queues asks QueuePilot again at once.
    queueLoader.retryQueuesNow("resumed");
  }

  @Override
  protected void onStop() {
    super.onStop();
    // An unlock lasts until the app leaves the foreground (decision 2026-10-23). A sitting that
    // already started keeps playing: its session key, not the unlock, authorizes it.
    NativeQueueApi.unlockToken = "";
    if (player != null) {
      // Durable, and waited for: Android may end a stopped app's process at any time after this.
      reportPlayback("paused", true);
      awaitSavedEvents();
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
    awaitSavedEvents();
    network.shutdownNow();
    // A sender still running finishes its request. The outbox executor belongs to the process.
    progressNetwork.shutdown();
    if (networkCallback != null) {
      try {
        getSystemService(android.net.ConnectivityManager.class)
            .unregisterNetworkCallback(networkCallback);
      } catch (RuntimeException ignored) {
        // Already unregistered.
      }
      networkCallback = null;
    }
    super.onDestroy();
  }
}
