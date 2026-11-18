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
  String deviceName = DeviceName.FALLBACK;
  LinearLayout content;
  TextView status;
  ExoPlayer player;
  PlayerView playerView;
  volatile int generation;
  int mainWidth;
  private boolean fixture;
  Screen current;
  JSONObject nativePlan;
  int nativeIndex;
  long nativeSequence;
  final ExecutorService progressNetwork = Executors.newSingleThreadExecutor();

  /**
   * Adds playback events to the encrypted outbox, one at a time and in the order they happened, so
   * the main thread never encrypts or writes it. One for the process, never shut down: a recreated
   * activity's events still follow the ones its predecessor reported. Sending runs on {@link
   * #progressNetwork}, and {@link PendingEvents#drain} keeps one sender at a time.
   */
  static final ExecutorService eventStore = Executors.newSingleThreadExecutor();

  PlexCatalog.Entry playingEntry;
  boolean isCastLoading;
  List<MarkerSkip.Marker> playingMarkers = List.of();
  String playingOrigin = "", playingToken = "";
  /** The playing poster for the media session: JPEG bytes, and a tokenless LAN address. */
  byte[] playingArtwork;

  String playingArtworkUri = "";
  /** True while an extra plays: it reports nothing to Plex and never touches a queue. */
  boolean isPlayingExtra;
  /** The title screen whose extras take focus when it is drawn again after its title ended. */
  Screen extrasFocus;

  final TitleScreen titleScreen = new TitleScreen(this);
  final CastPanel castPanel = new CastPanel(this);
  final SettingsScreen settingsScreen = new SettingsScreen(this);
  final PlayerScreen playerScreen = new PlayerScreen(this);
  final PlaybackLauncher playbackLauncher = new PlaybackLauncher(this);
  final NativeQueuePlayback nativeQueuePlayback = new NativeQueuePlayback(this);
  final TvPinPanel tvPinPanel = new TvPinPanel(this);
  final PlayerControls playerControls = new PlayerControls(this);
  final PlaybackMenus playbackMenus = new PlaybackMenus(this);
  final ConversionPlayback conversionPlayback = new ConversionPlayback(this);
  final PlaybackReports playbackReports = new PlaybackReports(this);

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
          playbackLauncher.playExtra(extra, from);
        }
        public View rememberedView() {
          String key = focusMemory.getOrDefault(current, "");
          return key.isEmpty() ? null : rememberedFocus(getWindow().getDecorView(), key);
        }
        public View poster(PlexCatalog.Entry entry) { return MainActivity.this.poster(entry, -1); }
        public int posterWidth() { return itemWidth(columns(5)); }
        public boolean takeExtrasFocus() {
          boolean isEnded = extrasFocus != null && extrasFocus == current;
          if (isEnded) extrasFocus = null;
          return isEnded;
        }
      };

  final TitleNavigation titleNavigation = new TitleNavigation(screenHost);
  /** All Movies and Shows: every included library's titles, read and merged off the main thread. */
  final AllLibrariesSource allLibraries = new AllLibrariesSource(System::currentTimeMillis);

  /** The open All Movies and Shows screen's read session ({@link AllLibrariesSource#open}). */
  private long allSession;

  /** Each library's kind ("movie", "show", …) by key, as the library list last gave it. */
  private final Map<String, String> sectionTypes = new HashMap<>();

  private LibraryOptionsPanel.Keys libraryKeys(String section) {
    String server = profile.optString("serverId");
    return new LibraryOptionsPanel.Keys(
        sortPreference(section),
        LibraryOptions.viewKey(activeId, server, section),
        LibraryOptions.includeKey(activeId, server, section));
  }

  /** Whether All Movies and Shows includes a library, for the profile on show. Any thread. */
  java.util.function.Predicate<String> includedLibraries() {
    SharedPreferences stored = prefs();
    String id = activeId, server = profile == null ? "" : profile.optString("serverId");
    return section ->
        LibraryOptions.isIncluded(
            stored.getString(LibraryOptions.includeKey(id, server, section), ""),
            stored.getString(LibraryOptions.viewKey(id, server, section), ""));
  }
  long sectionFloor, sectionEnd = -1;
  boolean advancing;
  final Set<TvPanel> panels = new HashSet<>();
  final RewindOnResume rewind = new RewindOnResume();
  /** The playing part's Plex streams and the choice made from them, or null. */
  PlaybackTracks playbackTracks;
  /** Each profile's plex.tv language settings, read once and kept in memory. */
  final Map<String, PlexTracks.Languages> accountLanguages =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** The catalog entry of the queue being played, saved with the profile's resume snapshot. */
  JSONObject nativeQueue;
  JSONArray queuePeople = new JSONArray();
  /** Every queue the catalog has listed, by id, so a full queue or history can start one. */
  final Map<String, JSONObject> knownQueues = new HashMap<>();
  /** "continue" or "only" when the playing session began at a chosen item, else empty. */
  String nativeStartMode = "";
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
    playbackReports.scheduleFlush();
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
      // Leaving through the rail says nothing about the screen's own place: it starts fresh.
      if (focused != null && !isInRail(focused)) focusMemory.put(current, focusKey(focused));
      else if (focused != null) focusMemory.remove(current);
      history.push(current);
    }
    render(screen);
  }

  void back() {
    if (player != null) playbackReports.reportPlayback("stopped");
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
    playerScreen.releasePlayer();
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

  String focusKey(View view) {
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
          // A library's Recently Added row may already hold the screen's first focus.
          if (RecentlyAddedRow.holds(getCurrentFocus())) return;
          String key = focusMemory.getOrDefault(current, "");
          View target = key.isEmpty() ? null : rememberedFocus(getWindow().getDecorView(), key);
          if (target != null && isInRail(target)) target = null;
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
    boolean isComplete, isLoadingLater, isLoadingEarlier, isCached, isFolders;
    LinearLayout rows;
    final Map<Integer, View> cards = new HashMap<>();
    List<LibraryBrowse.Letter> letters = List.of();
    final Map<String, TextView> letterViews = new HashMap<>();
    String shownLetter = "";
    /** The first item read, to notice a server that ignores paging and sends it again. */
    JSONObject first;
    /** The card the grid offers focus to first, before the viewer moves. */
    View firstFocus;
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
    boolean isAll = AllLibraries.isPath(path);
    if (isAll) allSession = allLibraries.open();
    String section = isAll ? AllLibraries.SEARCH_SECTION : LibraryBrowse.sectionKey(path);
    // A library viewed as Folders lists its folders from the top, with no sort or letter bar.
    boolean isFolders =
        !isAll
            && !section.isEmpty()
            && LibraryOptions.isFolders(prefs().getString(libraryKeys(section).view(), ""));
    if (!section.isEmpty() && !isFolders) {
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
    g.path = isFolders ? LibraryOptions.folderRoot(section) : path;
    g.sectionKey = section;
    g.columns = columns(5);
    g.pageSize = LibraryBrowse.pageSize(g.columns);
    if (!section.isEmpty()) {
      g.isFolders = isFolders;
      if (!isFolders) {
        g.sort = LibraryBrowse.sort(prefs().getString(sortPreference(section), ""));
        g.sortParam = g.sort.param();
      }
      libraryTools(g, title);
    }
    if (!section.isEmpty() && !isAll) {
      // Recently Added, on the library's first screen only, above the grid.
      LinearLayout recent = ui.column();
      content.addView(recent);
      boolean isFresh = focusMemory.getOrDefault(current, "").isEmpty();
      new RecentlyAddedRow(screenHost)
          .load(
              section,
              sectionTypes.getOrDefault(section, ""),
              recent,
              isFresh,
              () -> g.firstFocus);
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
            LibraryOptionsPanel.buttonLabel(g.isFolders, g.sort),
            g.isFolders ? R.drawable.lucide_folder : R.drawable.lucide_arrow_up_down,
            () -> sortPanel(g, title),
            false);
    sort.setContentDescription(g.isFolders ? "Options, Folders" : "Sort, " + g.sort.label());
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

  /** The library's sorts and options in a side panel, kept for this library and profile. */
  private void sortPanel(Grid g, String title) {
    String path = current.path();
    LibraryOptionsPanel.show(
        screenHost,
        prefs(),
        libraryKeys(g.sectionKey),
        title,
        AllLibraries.isPath(path),
        g.sort,
        // The same screen, in its new order or view, starting at its first title.
        () -> render(new Screen("library", path, title, null, 0)));
  }

  /** The library list behind Series or Movies: a few libraries, never paged. */
  private void loadSections(String path, String title) {
    fetch(
        path,
        (entries, cached) -> {
          for (int i = 0; i < entries.length(); i++)
            sectionTypes.put(
                entries.optJSONObject(i).optString("key"),
                entries.optJSONObject(i).optString("libraryType"));
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
          entries = withAllLibraries(entries);
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

  /** The library list with All Movies and Shows first. */
  private static JSONArray withAllLibraries(JSONArray entries) {
    JSONArray withAll = new JSONArray();
    try {
      withAll.put(
          new JSONObject()
              .put("key", AllLibraries.SEARCH_SECTION)
              .put("title", AllLibraries.TITLE)
              .put("type", "library")
              .put("children", AllLibraries.PATH));
    } catch (Exception impossible) {
      return entries;
    }
    for (int i = 0; i < entries.length(); i++) withAll.put(entries.opt(i));
    return withAll;
  }

  /** One page of a paged listing, with the listing's total; the last copy when offline. */
  private interface Page {
    void ready(JSONArray entries, int total, boolean cached);
  }

  private void fetchPage(String path, Page callback, Runnable failure) {
    int request = generation;
    long session = allSession;
    String origin = server(), secret = token(), id = activeId;
    java.util.function.Predicate<String> included = includedLibraries();
    network.execute(
        () -> {
          JSONObject page;
          boolean cached = false;
          try {
            if (AllLibraries.isPath(path)) {
              // Merged from every included library; there is no saved copy of it offline.
              page = allLibraries.page(origin, secret, id, path, session, included, () -> request == generation);
              JSONObject merged = page;
              runOnUiThread(
                  () -> {
                    if (request == generation && id.equals(activeId))
                      callback.ready(
                          merged.optJSONArray("entries"), merged.optInt("total", -1), false);
                  });
              return;
            }
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
          if (!isFirst && LibraryBrowse.isRepeat(g.first, entries)) {
            // The server ignored paging and sent the listing again: it is complete.
            g.isComplete = true;
            gridStatus(g);
            return;
          }
          if (isFirst) g.first = entries.optJSONObject(0);
          appendRows(g, entries, from, false);
          g.end = from + count;
          // More than a page means the server ignored paging and sent the whole listing.
          g.isComplete =
              count < g.pageSize || count > g.pageSize || (g.total >= 0 && g.end >= g.total);
          gridStatus(g);
          if (!isFirst) return;
          if (count == 0) {
            if (!cached) status.setText("No titles found in this library.");
            return;
          }
          View target = focusIndex >= 0 ? g.cards.get(focusIndex) : null;
          // A jump is chosen on the bar, so its title takes focus even after a touch.
          if (target != null) target.requestFocusFromTouch();
          else {
            g.firstFocus = g.cards.get(from);
            focusFirst(g.firstFocus);
          }
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
    String id = activeId;
    java.util.function.Predicate<String> included = includedLibraries();
    boolean isAll = AllLibraries.isPath(g.path);
    long session = allSession;
    network.execute(
        () -> {
          try {
            List<LibraryBrowse.Letter> letters =
                isAll
                    ? allLibraries.letters(origin, secret, id, session, included, () -> request == generation)
                    : PlexCatalog.letters(
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
    if (entry.type().equals("library") || entry.type().equals("folder")) {
      FrameLayout tile = new FrameLayout(this);
      tile.setBackgroundColor(TvUi.PANEL);
      ImageView icon =
          ui.icon(
              entry.type().equals("folder")
                  ? R.drawable.lucide_folder
                  : AllLibraries.isPath(entry.children())
                      ? R.drawable.lucide_layers
                      : R.drawable.lucide_library_big,
              TvUi.ACCENT,
              48);
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
    TextView chip = LibraryChip.view(this, ui, entry.facts());
    if (chip != null) {
      ui.gap(card, 6);
      card.addView(chip, new LinearLayout.LayoutParams(-2, -2));
    }
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
    card.setContentDescription(LibraryChip.spoken(entry.spoken(), entry.facts()));
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

  final WatchingScreen watchingScreen = new WatchingScreen(this);

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

  final FullQueueScreen fullQueueScreen = new FullQueueScreen(this);

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

  int seekPreviewFrame = -1;

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
      return playbackMenus.playbackPanel(builder, anchor);
    }
    return builder;
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
                  if (tvPinPanel.askPinIfLocked(error, () -> postAddToQueue(queue, ratingKey, title))) return;
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

  @Override
  protected void onResume() {
    super.onResume();
    // The caption style may have been changed in Android's settings meanwhile.
    playbackMenus.applyCaptionStyle();
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
      playbackReports.reportPlayback("paused", true);
      playbackReports.awaitSavedEvents();
      rewind.pause();
      player.pause();
    }
  }

  @Override
  protected void onDestroy() {
    closePanels();
    generation++;
    handler.removeCallbacksAndMessages(null);
    playerScreen.releasePlayer();
    playbackReports.awaitSavedEvents();
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
