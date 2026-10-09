package dev.queuepilot.player;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.InputType;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
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

/** Diagnostic controls only; the final TV interface is reviewed separately. */
@androidx.media3.common.util.UnstableApi
public final class MainActivity extends androidx.activity.ComponentActivity {
  private final ExecutorService network = Executors.newFixedThreadPool(3);
  private ProfileStore store;
  private JSONArray profiles;
  private JSONObject profile;
  private String activeId = "demo-ada";
  private LinearLayout content;
  private TextView status;
  private ExoPlayer player;
  private PlayerView playerView;
  private int generation;
  private String currentPath = "/library/sections";
  private final StoryOrder demo = StoryOrder.demo();

  private SharedPreferences prefs() {
    return getSharedPreferences("state", MODE_PRIVATE);
  }

  private int dp(int size) {
    return Math.round(size * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                home();
              }
            });
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    store = new ProfileStore(this);
    try {
      profiles = store.read();
    } catch (Exception e) {
      profiles = new JSONArray();
      new AlertDialog.Builder(this)
          .setTitle("Profile store unavailable")
          .setMessage(
              "Stored credentials could not be decrypted. Add the profile again; no cloud lookup is"
                  + " required.")
          .setPositiveButton("OK", null)
          .show();
    }
    activeId = prefs().getString("active", "demo-ada");
    setProfile(activeId);
    home();
  }

  private void setProfile(String id) {
    profile = null;
    for (int i = 0; i < profiles.length(); i++) {
      JSONObject p = profiles.optJSONObject(i);
      if (p != null && id.equals(p.optString("id"))) {
        profile = p;
        break;
      }
    }
    if (profile == null && !id.equals("demo-grace")) id = "demo-ada";
    activeId = id;
    prefs().edit().putString("active", id).apply();
    generation++;
  }

  private String profileName() {
    return profile == null
        ? (activeId.equals("demo-grace") ? "Grace (demo)" : "Ada (demo)")
        : profile.optString("name");
  }

  private String server() {
    return profile == null ? "" : profile.optString("server");
  }

  private String token() {
    return profile == null ? "" : profile.optString("token");
  }

  private Set<String> completed(String id) {
    return new HashSet<>(prefs().getStringSet("done:" + id, Set.of()));
  }

  private void complete(String id, String position) {
    Set<String> done = completed(id);
    done.add(position);
    prefs().edit().putStringSet("done:" + id, done).apply();
  }

  private void base(String title) {
    generation++;
    releasePlayer();
    ScrollView scroll = new ScrollView(this);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(32), dp(24), dp(32), dp(24));
    content.setBackgroundColor(Color.rgb(16, 18, 24));
    scroll.addView(content);
    setContentView(scroll);
    text(title, 28, Color.WHITE);
    status = text("", 14, Color.rgb(170, 183, 198));
  }

  private TextView text(String value, int size, int color) {
    TextView text = new TextView(this);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(color);
    text.setPadding(0, dp(6), 0, dp(6));
    content.addView(text);
    return text;
  }

  private Button button(String label, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(16);
    button.setOnClickListener(v -> action.run());
    content.addView(
        button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
    return button;
  }

  private void home() {
    base("QueuePilot Lab · playback harness");
    String mode = "unavailable";
    if (getDisplay() != null) {
      var m = getDisplay().getMode();
      mode = m.getPhysicalWidth() + " × " + m.getPhysicalHeight();
    }
    var metrics = getResources().getDisplayMetrics();
    status.setText(
        "Profile: "
            + profileName()
            + " · Android UI: "
            + metrics.widthPixels
            + " × "
            + metrics.heightPixels
            + " · display mode: "
            + mode);
    text(
        "This APK tests playback and artwork. Choose the finished TV layout in the separate design"
            + " preview.",
        16,
        Color.LTGRAY);
    button("Switch enrolled profile", this::chooseProfile);
    button("Add Plex profile (server + its own token)", this::enroll);
    button("Browse Plex library", () -> load("/library/sections", "Plex libraries"));
    button("Open a video URL", this::openVideo);
    button("View QueuePilot queues (read-only)", this::queueEndpoint);
    text("Story-order experiment · fictional library", 22, Color.WHITE);
    StoryOrder.Item next = demo.next(completed(activeId));
    text(
        "Next: " + (next == null ? "Finished" : next.label() + " · " + next.title()),
        18,
        Color.rgb(207, 237, 170));
    for (StoryOrder.Item item : demo.items()) {
      boolean watched = completed(activeId).contains(item.positionKey());
      button(
          (watched ? "✓ " : "") + item.label() + " · " + item.title(),
          () ->
              new AlertDialog.Builder(this)
                  .setTitle(item.label() + " · " + item.title())
                  .setMessage(
                      "Demo only. Provider identity stays "
                          + item.mediaKey()
                          + ". Marking this position complete does not change Plex history.")
                  .setPositiveButton(
                      "Mark demo position finished",
                      (d, w) -> {
                        complete(activeId, item.positionKey());
                        home();
                      })
                  .setNegativeButton("Cancel", null)
                  .show());
    }
    button(
        "Reset this profile's demo progress",
        () -> {
          prefs().edit().remove("done:" + activeId).apply();
          home();
        });
  }

  private void chooseProfile() {
    List<String> ids = new ArrayList<>(List.of("demo-ada", "demo-grace")),
        names = new ArrayList<>(List.of("Ada (demo)", "Grace (demo)"));
    for (int i = 0; i < profiles.length(); i++) {
      JSONObject p = profiles.optJSONObject(i);
      ids.add(p.optString("id"));
      names.add(p.optString("name"));
    }
    new AlertDialog.Builder(this)
        .setTitle("Enrolled profiles · no internet lookup")
        .setItems(
            names.toArray(new String[0]),
            (d, w) -> {
              setProfile(ids.get(w));
              home();
            })
        .show();
  }

  private EditText field(LinearLayout form, String hint, boolean secret) {
    EditText input = new EditText(this);
    input.setHint(hint);
    input.setSingleLine();
    input.setTextColor(Color.WHITE);
    input.setInputType(
        secret
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT);
    input.setAutofillHints((String[]) null);
    form.addView(input);
    return input;
  }

  private void enroll() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(24), dp(8), dp(24), dp(8));
    EditText name = field(form, "Profile display name", false),
        endpoint = field(form, "Plex server origin (https://media.example.com)", false),
        credential = field(form, "This profile's Plex token", true);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Enroll an existing Plex token")
            .setView(form)
            .setMessage(
                "Use a token for this profile. An owner token grants owner access; typing a child's"
                    + " name does not create a restricted profile. HTTPS protects remote"
                    + " connections.")
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        String origin = PlexCatalog.base(endpoint.getText().toString()),
                            label = name.getText().toString().trim(),
                            secret = credential.getText().toString().trim();
                        if (label.isEmpty() || secret.isEmpty())
                          throw new IllegalArgumentException("Name and profile token are required");
                        JSONObject p =
                            new JSONObject()
                                .put("id", UUID.randomUUID().toString())
                                .put("name", label)
                                .put("server", origin)
                                .put("token", secret);
                        JSONArray updated = new JSONArray(profiles.toString());
                        updated.put(p);
                        store.write(updated);
                        profiles = updated;
                        setProfile(p.getString("id"));
                        dialog.dismiss();
                        home();
                      } catch (Exception e) {
                        credential.setError(
                            "Profile was not saved. Check the name, origin, token, and device"
                                + " storage.");
                      }
                    }));
    dialog.show();
  }

  private File cacheFile(String id, String origin, String path) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest((id + "|" + origin + "|" + path).getBytes(StandardCharsets.UTF_8));
    StringBuilder key = new StringBuilder();
    for (byte b : digest) key.append(String.format(Locale.ROOT, "%02x", b));
    return new File(getCacheDir(), key + ".json");
  }

  private void load(String path, String title) {
    if (profile == null) {
      new AlertDialog.Builder(this)
          .setTitle("Add a Plex profile first")
          .setMessage(
              "The two demo profiles have no server credentials. Enroll a profile once while you"
                  + " have its token; switching later uses only local storage.")
          .setPositiveButton("Add profile", (d, w) -> enroll())
          .setNegativeButton("Cancel", null)
          .show();
      return;
    }
    base(title);
    currentPath = path;
    status.setText("Loading from your Plex server…");
    button("Back to lab", this::home);
    int request = generation;
    String origin = server(), secret = token(), id = activeId;
    network.execute(
        () -> {
          JSONArray entries;
          String note;
          try {
            entries =
                PlexCatalog.list(
                    PlexCatalog.get(PlexCatalog.url(origin, path), secret, 16 * 1024 * 1024));
            java.nio.file.Files.write(
                cacheFile(id, origin, path).toPath(),
                entries.toString().getBytes(StandardCharsets.UTF_8));
            note = "Live server · " + entries.length() + " items";
          } catch (Exception e) {
            try {
              entries =
                  new JSONArray(
                      new String(
                          java.nio.file.Files.readAllBytes(cacheFile(id, origin, path).toPath()),
                          StandardCharsets.UTF_8));
              note = "Cached listing · server request failed · playback still requires the server";
            } catch (Exception missing) {
              runOnUiThread(
                  () -> {
                    if (request == generation)
                      status.setText(
                          "Server unavailable or access refused. No cached listing exists for this"
                              + " profile.");
                  });
              return;
            }
          }
          JSONArray items = entries;
          String info = note;
          runOnUiThread(
              () -> {
                if (request != generation || !activeId.equals(id)) return;
                status.setText(
                    info + (items.length() > 40 ? " · showing first 40 for this harness" : ""));
                for (int i = 0; i < Math.min(items.length(), 40); i++) {
                  PlexCatalog.Entry entry = PlexCatalog.Entry.fromJson(items.optJSONObject(i));
                  showEntry(entry, request, origin, secret);
                }
              });
        });
  }

  private void showEntry(PlexCatalog.Entry entry, int request, String origin, String secret) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, dp(8), 0, dp(8));
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    row.addView(image, new LinearLayout.LayoutParams(dp(112), dp(168)));
    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.setPadding(dp(18), 0, 0, 0);
    row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    TextView title = new TextView(this);
    title.setText(
        entry.title() + (entry.type().equals("episode") ? " · Episode " + entry.episode() : ""));
    title.setTextColor(Color.WHITE);
    title.setTextSize(20);
    labels.addView(title);
    Button open = new Button(this);
    open.setAllCaps(false);
    open.setText(
        entry.type().equals("movie") || entry.type().equals("episode") ? "Play directly" : "Open");
    open.setOnClickListener(
        v -> {
          if (entry.type().equals("movie") || entry.type().equals("episode")) playEntry(entry);
          else load(entry.children(), entry.title());
        });
    labels.addView(open);
    if (!entry.thumb().isEmpty()) {
      Button compare = new Button(this);
      compare.setText("Compare 240px / 1440px artwork");
      compare.setAllCaps(false);
      compare.setOnClickListener(v -> compareArt(entry));
      labels.addView(compare);
      network.execute(
          () -> {
            try {
              Bitmap bitmap = artwork(origin, secret, entry.thumb(), 720);
              runOnUiThread(
                  () -> {
                    if (request == generation) image.setImageBitmap(bitmap);
                  });
            } catch (Exception ignored) {
            }
          });
    }
    content.addView(row);
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
            + java.net.URLEncoder.encode(thumb, "UTF-8");
    byte[] bytes = PlexCatalog.get(PlexCatalog.url(origin, path), secret, 12 * 1024 * 1024);
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (bounds.outWidth <= 0
        || bounds.outHeight <= 0
        || bounds.outWidth > 4096
        || bounds.outHeight > 6144) throw new IllegalArgumentException("Invalid image dimensions");
    Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    if (image == null) throw new IllegalStateException("Image decode failed");
    return image;
  }

  private void compareArt(PlexCatalog.Entry entry) {
    base("Artwork comparison · " + entry.title());
    status.setText(
        "Loading two server derivatives. A 4K surface does not make low-resolution source artwork"
            + " sharper.");
    button("Back to listing", () -> load(currentPath, "Plex library"));
    int request = generation;
    String origin = server(), secret = token();
    network.execute(
        () -> {
          try {
            Bitmap low = artwork(origin, secret, entry.thumb(), 240),
                high = artwork(origin, secret, entry.thumb(), 1440);
            runOnUiThread(
                () -> {
                  if (request != generation) return;
                  status.setText(
                      "Received "
                          + low.getWidth()
                          + "×"
                          + low.getHeight()
                          + " and "
                          + high.getWidth()
                          + "×"
                          + high.getHeight()
                          + ". Comparison canvas requests 3840×2160; final output depends on the"
                          + " device compositor.");
                  SurfaceView surface = new SurfaceView(this);
                  surface.getHolder().setFixedSize(3840, 2160);
                  surface
                      .getHolder()
                      .addCallback(
                          new SurfaceHolder.Callback() {
                            public void surfaceCreated(SurfaceHolder holder) {
                              draw(holder);
                            }

                            public void surfaceChanged(
                                SurfaceHolder holder, int format, int width, int height) {
                              draw(holder);
                            }

                            public void surfaceDestroyed(SurfaceHolder holder) {}

                            private void draw(SurfaceHolder holder) {
                              Canvas canvas = holder.lockCanvas();
                              if (canvas == null) return;
                              try {
                                canvas.drawColor(Color.rgb(16, 18, 24));
                                Paint paint =
                                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                                paint.setColor(Color.WHITE);
                                paint.setTextSize(72);
                                canvas.drawText("240px derivative", 170, 120, paint);
                                canvas.drawText("1440px request", 2060, 120, paint);
                                canvas.drawBitmap(
                                    low, null, new RectF(300, 220, 1380, 1840), paint);
                                canvas.drawBitmap(
                                    high, null, new RectF(2180, 220, 3260, 1840), paint);
                                paint.setTextSize(48);
                                canvas.drawText(
                                    "Actual buffer: "
                                        + canvas.getWidth()
                                        + " × "
                                        + canvas.getHeight(),
                                    170,
                                    2070,
                                    paint);
                              } finally {
                                holder.unlockCanvasAndPost(canvas);
                              }
                            }
                          });
                  var metrics = getResources().getDisplayMetrics();
                  int availableWidth = Math.max(1, metrics.widthPixels - dp(64));
                  int availableHeight = Math.max(1, metrics.heightPixels - dp(220));
                  int surfaceHeight = Math.min(availableWidth * 9 / 16, availableHeight);
                  LinearLayout.LayoutParams surfaceLayout =
                      new LinearLayout.LayoutParams(surfaceHeight * 16 / 9, surfaceHeight);
                  surfaceLayout.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                  content.addView(surface, surfaceLayout);
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request == generation)
                    status.setText(
                        "Artwork comparison failed. The selected profile may not have access, or"
                            + " the server is unreachable.");
                });
          }
        });
  }

  private void playEntry(PlexCatalog.Entry entry) {
    base("Preparing direct playback…");
    int request = generation;
    String origin = server(), secret = token(), id = activeId;
    network.execute(
        () -> {
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(origin, "/library/metadata/" + entry.key()),
                        secret,
                        16 * 1024 * 1024));
            if (items.length() == 0) throw new IllegalStateException("No metadata");
            PlexCatalog.Entry detail = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            if (detail.part().isEmpty()) throw new IllegalStateException("No playable part");
            String stream = PlexCatalog.url(origin, detail.part());
            runOnUiThread(
                () -> {
                  if (request == generation && id.equals(activeId))
                    startPlayer(stream, secret, detail.title(), detail.cast());
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request == generation) {
                    status.setText(
                        "Cannot resolve a direct-play part. This prototype does not negotiate"
                            + " transcoding.");
                    button("Back to lab", this::home);
                  }
                });
          }
        });
  }

  private void openVideo() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    EditText input = field(form, "HTTPS video URL (no Plex token added)", false);
    new AlertDialog.Builder(this)
        .setTitle("Test a video URL")
        .setView(form)
        .setPositiveButton(
            "Play",
            (d, w) -> {
              try {
                java.net.URI u = java.net.URI.create(input.getText().toString().trim());
                if (!Set.of("http", "https").contains(u.getScheme())
                    || u.getHost() == null
                    || u.getUserInfo() != null) throw new IllegalArgumentException();
                startPlayer(u.toString(), "", "Video URL", "");
              } catch (Exception e) {
                home();
                status.setText("Invalid video URL");
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void startPlayer(String url, String secret, String title, String cast) {
    base(title);
    FrameLayout frame = new FrameLayout(this);
    playerView = new PlayerView(this);
    frame.addView(
        playerView,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    content.addView(
        frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)));
    androidx.media3.datasource.DataSource.Factory factory =
        secret.isEmpty()
            ? new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false)
            : PlexDataSource.factory(server(), secret);
    player =
        new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(factory))
            .build();
    playerView.setPlayer(player);
    playerView.setControllerShowTimeoutMs(3000);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onVideoSizeChanged(VideoSize size) {
            status.setText(
                "Decoded video: "
                    + size.width
                    + " × "
                    + size.height
                    + " · direct playback · no server conversion requested");
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            status.setText(
                "Playback failed: "
                    + error.getErrorCodeName()
                    + ". This prototype has no transcode fallback.");
          }
        });
    player.setMediaItem(MediaItem.fromUri(url));
    player.prepare();
    player.play();
    button(
        "Cast (leave video playing)",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Title cast")
                .setMessage(cast.isEmpty() ? "This source has no cast metadata." : cast)
                .setPositiveButton("Return to playback", null)
                .show());
    button("Back to lab", this::home);
    playerView.requestFocus();
  }

  private void queueEndpoint() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    EditText input = field(form, "QueuePilot origin (https://queues.example.com)", false);
    input.setText(prefs().getString("queue-origin", ""));
    new AlertDialog.Builder(this)
        .setTitle("Read QueuePilot queues")
        .setMessage(
            "This harness only reads the queue registry. Native queue launch and progress reporting"
                + " need a separate server contract; /api/play controls external devices.")
        .setView(form)
        .setPositiveButton(
            "Load",
            (d, w) -> {
              try {
                String origin = PlexCatalog.base(input.getText().toString());
                prefs().edit().putString("queue-origin", origin).apply();
                base("QueuePilot queues · read-only");
                button("Back to lab", this::home);
                int request = generation;
                network.execute(
                    () -> {
                      try {
                        JSONObject registry =
                            new JSONObject(
                                new String(
                                    PlexCatalog.get(origin + "/api/sets", "", 8 * 1024 * 1024),
                                    StandardCharsets.UTF_8));
                        JSONArray sets = registry.optJSONArray("sets");
                        runOnUiThread(
                            () -> {
                              if (request != generation) return;
                              status.setText("Read-only connection succeeded");
                              if (sets == null) {
                                text(
                                    "Registry connected; this server's response needs an adapter.",
                                    18,
                                    Color.LTGRAY);
                                return;
                              }
                              for (int i = 0; i < sets.length(); i++) {
                                JSONObject item = sets.optJSONObject(i);
                                if (item != null)
                                  text(
                                      item.optString("label", item.optString("id")),
                                      20,
                                      Color.WHITE);
                              }
                            });
                      } catch (Exception e) {
                        runOnUiThread(
                            () -> {
                              if (request == generation)
                                status.setText(
                                    "QueuePilot unavailable, or authentication is required. No"
                                        + " queue was started.");
                            });
                      }
                    });
              } catch (Exception e) {
                home();
                status.setText("Invalid QueuePilot origin");
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void releasePlayer() {
    if (playerView != null) {
      playerView.setPlayer(null);
      playerView = null;
    }
    if (player != null) {
      player.release();
      player = null;
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (player != null) player.pause();
  }

  @Override
  public void onDestroy() {
    generation++;
    releasePlayer();
    network.shutdownNow();
    super.onDestroy();
  }
}
