package dev.queuepilot.player;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts {@link PlexTracks}' choice into the Media3 player, and maps the viewer's later choice back
 * to the Plex stream it plays, so it can be saved for the profile.
 */
@androidx.media3.common.util.UnstableApi
final class PlaybackTracks {
  private final List<PlexTracks.Stream> streams;
  private final PlexTracks.Choice choice;
  private boolean isApplied;

  PlaybackTracks(List<PlexTracks.Stream> streams, PlexTracks.Choice choice) {
    this.streams = streams;
    this.choice = choice;
  }

  /**
   * The streams for {@code entry}. When Plex selected none, the account's language settings are
   * read through {@code account}, which may return null; only the profile that plays the item may
   * supply them. Call off the main thread.
   */
  static PlaybackTracks forEntry(
      PlexCatalog.Entry entry, java.util.concurrent.Callable<PlexTracks.Languages> account) {
    List<PlexTracks.Stream> streams =
        PlexTracks.streams(entry.facts().optJSONArray("streams"));
    PlexTracks.Choice choice = PlexTracks.choose(streams, PlexTracks.Languages.NONE);
    if (!choice.isFromPlex() && !streams.isEmpty() && account != null) {
      try {
        PlexTracks.Languages languages = account.call();
        if (languages != null) choice = PlexTracks.choose(streams, languages);
      } catch (Exception unavailable) {
        // The file's own defaults stay.
      }
    }
    return new PlaybackTracks(streams, choice);
  }

  /**
   * Saves the viewer's choice for this profile with {@code PUT /library/parts/<id>}, the request
   * Plex's own players send; {@code streamId} 0 turns subtitles off. Returns whether Plex accepted
   * it. Call off the main thread.
   */
  static boolean save(String origin, String token, String partId, int plexType, long streamId) {
    if (partId.isEmpty() || !partId.matches("[0-9]+") || streamId < 0) return false;
    try {
      PlexCatalog.put(
          PlexCatalog.url(
              origin, "/library/parts/" + partId + "?" + PlexTracks.saveQuery(plexType, streamId)),
          token);
      return true;
    } catch (Exception failed) {
      android.util.Log.w("QueuePilotTracks", "The stream choice was not saved: " + failed);
      return false;
    }
  }

  /**
   * The choice for a server conversion. The converted stream holds only the streams Plex chose, so
   * only a subtitle file beside the media is still picked here, {@code sidecar} (null is none); an
   * embedded subtitle is burned into the picture by the server.
   */
  PlaybackTracks forConversion(PlexTracks.Stream sidecar) {
    List<PlexTracks.Stream> external = new ArrayList<>();
    for (PlexTracks.Stream s : streams) if (s.isExternal()) external.add(s);
    PlexTracks.Stream subtitle = sidecar != null && sidecar.isExternal() ? sidecar : null;
    PlaybackTracks converted =
        new PlaybackTracks(external, new PlexTracks.Choice(null, subtitle, choice.isFromPlex()));
    converted.frameRate = frameRate();
    return converted;
  }

  /**
   * The Plex id of the subtitle from inside the file that a conversion burns: the player's
   * selection when the file opened, otherwise the planned choice, because a container the TV can't
   * open has no tracks. 0 is an embedded subtitle whose Plex stream is not known; -1 is none.
   */
  long burnedSubtitleId(Tracks tracks) {
    boolean hasText = false;
    for (Tracks.Group group : tracks.getGroups()) {
      if (group.getType() != C.TRACK_TYPE_TEXT) continue;
      hasText = true;
      for (int i = 0; i < group.length; i++) {
        if (!group.isTrackSelected(i)) continue;
        String id = group.getTrackFormat(i).id;
        if (id != null && id.contains(PlexTracks.EXTERNAL_PREFIX)) return -1;
        return Math.max(0, streamId(tracks, C.TRACK_TYPE_TEXT, group, i));
      }
    }
    return hasText ? -1 : plannedBurnedSubtitleId();
  }

  /**
   * The subtitle file beside the media that the viewer has on now, for a conversion to keep: the
   * player's selection when the file opened, otherwise the planned one. Null when subtitles are
   * Off or the subtitle is inside the file, which the server burns.
   */
  PlexTracks.Stream shownSidecar(Tracks tracks) {
    boolean hasText = false;
    for (Tracks.Group group : tracks.getGroups()) {
      if (group.getType() != C.TRACK_TYPE_TEXT) continue;
      hasText = true;
      for (int i = 0; i < group.length; i++) {
        if (!group.isTrackSelected(i)) continue;
        String id = group.getTrackFormat(i).id;
        if (id == null || !id.contains(PlexTracks.EXTERNAL_PREFIX)) return null;
        long streamId = streamId(tracks, C.TRACK_TYPE_TEXT, group, i);
        for (PlexTracks.Stream s : streams) if (s.id() == streamId && s.isExternal()) return s;
        return null;
      }
    }
    return hasText ? null : plannedSidecar();
  }

  /** The planned subtitle when it is a file beside the media, or null. */
  PlexTracks.Stream plannedSidecar() {
    PlexTracks.Stream subtitle = choice.subtitle();
    return subtitle != null && subtitle.isExternal() ? subtitle : null;
  }

  /** The planned subtitle's Plex id when it is inside the file, or -1. */
  long plannedBurnedSubtitleId() {
    PlexTracks.Stream subtitle = choice.subtitle();
    return subtitle != null && !subtitle.isExternal() ? subtitle.id() : -1;
  }

  /** True when the player shows a subtitle from inside the file, which a conversion must burn. */
  static boolean isEmbeddedTextSelected(Tracks tracks) {
    for (Tracks.Group group : tracks.getGroups()) {
      if (group.getType() != C.TRACK_TYPE_TEXT) continue;
      for (int i = 0; i < group.length; i++) {
        String id = group.getTrackFormat(i).id;
        if (group.isTrackSelected(i) && (id == null || !id.contains(PlexTracks.EXTERNAL_PREFIX)))
          return true;
      }
    }
    return false;
  }

  private double frameRate = -1;

  double frameRate() {
    if (frameRate >= 0) return frameRate;
    return PlexTracks.frameRate(streams);
  }

  boolean hasStreams() {
    return !streams.isEmpty();
  }

  /** Plex's subtitle files beside the media, loaded with the video so they can be chosen. */
  List<MediaItem.SubtitleConfiguration> externalSubtitles(String origin) {
    List<MediaItem.SubtitleConfiguration> list = new ArrayList<>();
    for (PlexTracks.Stream s : PlexTracks.loadableExternal(streams)) {
      try {
        list.add(
            new MediaItem.SubtitleConfiguration.Builder(Uri.parse(PlexCatalog.url(origin, s.key())))
                .setId(PlexTracks.externalId(s))
                .setMimeType(PlexTracks.subtitleMime(s.codec()))
                .setLanguage(s.language().isEmpty() ? null : s.language())
                .setLabel(s.title().isEmpty() ? null : s.title())
                .setSelectionFlags(s.isForced() ? C.SELECTION_FLAG_FORCED : 0)
                .build());
      } catch (IllegalArgumentException refused) {
        // A key that is not a path on this server is not loaded.
      }
    }
    return list;
  }

  /**
   * Before the tracks are known: the chosen languages as preferences, so the first selection is
   * already close, and subtitles off when Plex chose none.
   */
  void prepare(ExoPlayer player) {
    if (!hasStreams()) return;
    TrackSelectionParameters.Builder parameters = player.getTrackSelectionParameters().buildUpon();
    if (choice.audio() != null && !choice.audio().language().isEmpty())
      parameters.setPreferredAudioLanguage(choice.audio().language());
    if (choice.subtitle() == null) parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
    else if (!choice.subtitle().language().isEmpty())
      parameters.setPreferredTextLanguage(choice.subtitle().language());
    player.setTrackSelectionParameters(parameters.build());
  }

  /**
   * Once the tracks are known, selects exactly the chosen streams. Runs once per item; a track that
   * cannot be found keeps the language preference from {@link #prepare}.
   */
  void apply(ExoPlayer player, Tracks tracks) {
    if (isApplied || !hasStreams() || tracks.getGroups().isEmpty()) return;
    isApplied = true;
    TrackSelectionParameters.Builder parameters = player.getTrackSelectionParameters().buildUpon();
    TrackSelectionOverride audio = override(tracks, C.TRACK_TYPE_AUDIO, choice.audio());
    if (audio != null) parameters.clearOverridesOfType(C.TRACK_TYPE_AUDIO).setOverrideForType(audio);
    if (choice.subtitle() == null) parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
    else {
      TrackSelectionOverride text = override(tracks, C.TRACK_TYPE_TEXT, choice.subtitle());
      parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
      if (text != null) parameters.clearOverridesOfType(C.TRACK_TYPE_TEXT).setOverrideForType(text);
    }
    player.setTrackSelectionParameters(parameters.build());
  }

  /** The Plex stream id the player's track plays, or -1 when it is not known. */
  long streamId(Tracks tracks, int type, Tracks.Group group, int trackIndex) {
    List<PlexTracks.Track> list = new ArrayList<>();
    int position = -1;
    for (Tracks.Group g : tracks.getGroups()) {
      if (g.getType() != type) continue;
      for (int i = 0; i < g.length; i++) {
        if (g == group && i == trackIndex) position = list.size();
        list.add(track(g.getTrackFormat(i)));
      }
    }
    PlexTracks.Stream stream =
        PlexTracks.streamFor(streams, plexType(type), list, position);
    return stream == null ? -1 : stream.id();
  }

  private TrackSelectionOverride override(Tracks tracks, int type, PlexTracks.Stream target) {
    if (target == null) return null;
    List<PlexTracks.Track> list = new ArrayList<>();
    List<Tracks.Group> groups = new ArrayList<>();
    List<Integer> indexes = new ArrayList<>();
    for (Tracks.Group g : tracks.getGroups()) {
      if (g.getType() != type) continue;
      for (int i = 0; i < g.length; i++) {
        list.add(track(g.getTrackFormat(i)));
        groups.add(g);
        indexes.add(i);
      }
    }
    int position = PlexTracks.trackFor(streams, target, list);
    if (position < 0 || !groups.get(position).isTrackSupported(indexes.get(position))) return null;
    return new TrackSelectionOverride(
        groups.get(position).getMediaTrackGroup(), indexes.get(position));
  }

  private static PlexTracks.Track track(Format format) {
    return new PlexTracks.Track(format.id == null ? "" : format.id, format.language);
  }

  private static int plexType(int media3Type) {
    return media3Type == C.TRACK_TYPE_AUDIO ? PlexTracks.AUDIO : PlexTracks.SUBTITLE;
  }
}
