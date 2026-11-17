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
   * Plex's own players send; {@code streamId} 0 turns subtitles off. Call off the main thread.
   */
  static void save(String origin, String token, String partId, int plexType, long streamId) {
    if (partId.isEmpty() || !partId.matches("[0-9]+") || streamId < 0) return;
    try {
      PlexCatalog.put(
          PlexCatalog.url(
              origin, "/library/parts/" + partId + "?" + PlexTracks.saveQuery(plexType, streamId)),
          token);
    } catch (Exception failed) {
      android.util.Log.w("QueuePilotTracks", "The stream choice was not saved: " + failed);
    }
  }

  double frameRate() {
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
