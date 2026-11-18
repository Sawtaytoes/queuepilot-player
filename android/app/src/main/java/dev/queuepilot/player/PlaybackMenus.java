package dev.queuepilot.player;

import android.view.View;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's menus: playback options, speed, audio and subtitle tracks with choices saved
 * per profile, chapters, frame-rate matching, and caption style.
 */
@androidx.media3.common.util.UnstableApi
final class PlaybackMenus {
  private final MainActivity host;

  PlaybackMenus(MainActivity host) {
    this.host = host;
  }

  TvPanel.Builder playbackPanel(TvPanel.Builder builder, View anchor) {
    PlayerView controls = host.playerView;
    return builder.setAnchor(
        anchor,
        () -> {
          controls.setControllerShowTimeoutMs(0);
          controls.showController();
        },
        () -> {
          if (host.playerView == controls && host.panels.isEmpty()) {
            controls.setControllerShowTimeoutMs(3500);
            controls.showController();
          }
        });
  }

  private TvPanel.Builder playbackPanel(View anchor) {
    return playbackPanel(
        new TvPanel.Builder(host).setLifecycle(host.panels::add, host.panels::remove), anchor);
  }

  void installPlaybackMenus() {
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
    View control = host.playerView.findViewById(id);
    if (control == null) return;
    control.setOnClickListener(
        view -> {
          view.requestFocusFromTouch();
          open.accept(view);
        });
  }

  private void playbackOptions(View anchor) {
    if (host.player == null) return;
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
    if (host.player == null) return;
    ExoPlayer target = host.player;
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
              if (host.player == target) target.setPlaybackSpeed(speeds[i]);
            })
        .setNegativeButton("Back", (d, i) -> playbackOptions(anchor))
        .create()
        .show();
  }

  private void trackOptions(View anchor, int type) {
    if (host.player == null) return;
    ExoPlayer target = host.player;
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
        new androidx.media3.ui.DefaultTrackNameProvider(host.getResources());
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
                  if (host.player != target) return;
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
    return new PlaybackSettings(host, host.prefs(), host::panel, this::matchFrameRate, () -> {});
  }

  List<Chapters.Chapter> playingChapters() {
    return host.playingEntry == null
        ? List.of()
        : Chapters.fromJson(host.playingEntry.facts().optJSONArray("chapters"));
  }

  void chapterOptions(View anchor) {
    List<Chapters.Chapter> chapters = playingChapters();
    if (host.player == null || chapters.isEmpty()) return;
    ExoPlayer target = host.player;
    String origin = host.playingOrigin, secret = host.playingToken;
    long floor = host.sectionFloor, end = host.sectionEnd;
    ChapterMenu.show(
        host,
        playbackPanel(anchor),
        chapters,
        target.getCurrentPosition(),
        host.network,
        thumb -> host.artwork(origin, secret, thumb, 240),
        start -> {
          if (host.player == target)
            target.seekTo(Chapters.seekTarget(start, floor, end));
        });
  }

  void matchFrameRate() {
    if (host.player == null) return;
    DisplayModeSwitch.apply(
        host.getWindow(),
        PlaybackSettings.isFrameRateMatched(host.prefs()),
        host.playbackTracks == null ? 0 : host.playbackTracks.frameRate(),
        host.player.getCurrentTracks());
  }

  /** Text subtitles follow Android's caption settings; ASS keeps its own styles. */
  void applyCaptionStyle() {
    androidx.media3.ui.SubtitleView subtitles =
        host.playerView == null ? null : host.playerView.getSubtitleView();
    if (subtitles == null) return;
    subtitles.setUserDefaultStyle();
    subtitles.setUserDefaultTextSize();
  }

  /** The chosen profile's plex.tv language settings, or null. Call off the main thread. */
  PlexTracks.Languages chosenLanguages() throws Exception {
    String proof = host.profileProof();
    PlexTracks.Languages known = host.accountLanguages.get(proof);
    if (known != null) return known;
    PlexTracks.Languages read = PlexTracks.Languages.fromUser(host.auth.user(proof));
    host.accountLanguages.put(proof, read);
    return read;
  }

  /** Saves a track the viewer chose to Plex, for the profile that is playing the item. */
  private void saveTrackChoice(int type, androidx.media3.common.Tracks.Group group, int index) {
    PlaybackTracks known = host.playbackTracks;
    if (host.player == null || known == null || !known.hasStreams() || host.playingEntry == null) return;
    boolean isText = type == androidx.media3.common.C.TRACK_TYPE_TEXT;
    if (group == null && !isText) return; // Automatic audio is not a choice to save.
    long streamId =
        group == null ? 0 : known.streamId(host.player.getCurrentTracks(), type, group, index);
    String origin = host.playingOrigin, secret = host.playingToken;
    String partId = host.playingEntry.facts().optString("partId");
    host.progressNetwork.execute(
        () ->
            PlaybackTracks.save(
                origin,
                secret,
                partId,
                isText ? PlexTracks.SUBTITLE : PlexTracks.AUDIO,
                streamId));
  }
}
