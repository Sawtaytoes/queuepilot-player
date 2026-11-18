package dev.queuepilot.player;

import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.List;
import org.json.JSONObject;

/**
 * The player screen: ExoPlayer with its view, styled subtitles, and media session, started for
 * one item and released when playback ends or the app stops.
 */
@androidx.media3.common.util.UnstableApi
final class PlayerScreen {
  private final MainActivity host;

  PlayerScreen(MainActivity host) {
    this.host = host;
  }

  private AssPlayback assPlayback;

  private androidx.media3.session.MediaSession mediaSession;

  void startPlayer(String url, String secret, String title, String cast) {
    host.reset();
    if (host.current == null || !host.current.kind().equals("player")) host.history.push(host.current);
    host.current = new Screen("player");
    host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    FrameLayout root = new FrameLayout(host);
    root.setBackgroundColor(TvTheme.VIDEO_BACKDROP);
    host.playerView = new PlayerView(host);
    root.addView(host.playerView, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout overlay = host.ui.column();
    overlay.setPadding(host.dp(26), host.dp(18), host.dp(26), host.dp(16));
    overlay.setBackgroundColor(TvTheme.PLAYER_BAR);
    TextView playingTitle = host.ui.title(host.playingEntry == null ? title : host.playingEntry.primary(), 23);
    playingTitle.setTag("playing-title");
    overlay.addView(playingTitle);
    String playingLine = host.playingEntry == null ? "" : MainActivity.episodeLine(host.playingEntry, "");
    if (!playingLine.isEmpty()) {
      TextView episodeText = host.ui.text(playingLine, 16, TvUi.WHITE);
      episodeText.setTag("playing-episode");
      overlay.addView(episodeText);
    }
    host.status = host.ui.text("Opening video…", 13, TvUi.MUTED);
    overlay.addView(host.status);
    host.ui.gap(overlay, 10);
    LinearLayout actions = host.ui.row();
    overlay.addView(actions);
    host.ui.action(actions, "Cast", R.drawable.lucide_users, () -> host.castPanel.showCast(host.playingEntry), false);
    if (!host.playbackMenus.playingChapters().isEmpty()) {
      host.ui.action(
          actions,
          "Chapters",
          R.drawable.lucide_bookmark,
          () -> host.playbackMenus.chapterOptions(actions.findViewWithTag("player-chapters")),
          false);
      actions.getChildAt(actions.getChildCount() - 1).setTag("player-chapters");
    }
    if (host.nativePlan != null)
      host.ui.action(
          actions,
          "Next queue item",
          R.drawable.lucide_skip_forward,
          () -> {
            if (host.advancing) return;
            host.playbackReports.reportPlayback("skip");
            host.advancing = true;
            host.nativeIndex++;
            host.nativeQueuePlayback.loadNativeItem();
          },
          false);
    if (host.nativePlan != null && host.nativeQueue != null) {
      JSONObject playingQueue = host.nativeQueue;
      host.ui.action(
          actions,
          "Full queue",
          R.drawable.lucide_list_ordered,
          () -> host.nativeQueuePlayback.leaveForFullQueue(playingQueue),
          false);
    }
    host.ui.action(actions, "Back to title", R.drawable.lucide_arrow_left, host::back, false);
    root.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    host.playingMarkers = host.playingEntry == null ? List.of() : host.playingEntry.skipMarkers();
    host.playerControls.skipMarker = null;
    host.playerControls.isSkipDismissed = false;
    host.playerControls.isControllerVisible = false;
    host.playerControls.skipButton = host.ui.button("Skip Intro", R.drawable.lucide_chevrons_right, host.playerControls::skipMarker, true);
    host.playerControls.skipButton.setTag("skip-marker");
    host.playerControls.skipButton.setVisibility(View.GONE);
    FrameLayout.LayoutParams skipLayout =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
    skipLayout.setMargins(0, 0, host.dp(40), host.dp(130));
    root.addView(host.playerControls.skipButton, skipLayout);
    host.setContentView(root);
    androidx.media3.datasource.DataSource.Factory dataSourceFactory =
        PlexDataSource.factory(host.playingOrigin.isEmpty() ? host.server() : host.playingOrigin, secret);
    AssPlayback ass = new AssPlayback(host, dataSourceFactory, android.net.Uri.parse(url));
    assPlayback = ass;
    host.playerView.getOverlayFrameLayout().addView(ass.overlay, new FrameLayout.LayoutParams(-1, -1));
    PlaybackStats playbackStats = PlaybackStatsOverlay.create(host); // null unless turned on
    host.player =
        PlaybackStatsTaps.tap(
                playbackStats, new ExoPlayer.Builder(host, ass.renderers(host)), host)
            .setSeekBackIncrementMs(SkipIntervals.backMs(PlaybackSettings.back(host.prefs())))
            .setSeekForwardIncrementMs(
                SkipIntervals.forwardMs(
                    host.playingEntry == null ? -1 : host.playingEntry.duration(),
                    PlaybackSettings.forwardShort(host.prefs()),
                    PlaybackSettings.forwardLong(host.prefs())))
            .setLoadControl(
                new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build())
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(
                    dataSourceFactory, PlaybackStatsTaps.tap(playbackStats, ass.extractors())))
            .build();
    mediaSession = new androidx.media3.session.MediaSession.Builder(host, host.player).build();
    host.playerView.setPlayer(host.player);
    PlaybackStatsOverlay.show(playbackStats, host.player, root, host.playingEntry);
    host.playerView.setShowSubtitleButton(true);
    host.playbackMenus.applyCaptionStyle();
    host.playbackMenus.installPlaybackMenus();
    host.playerControls.roundPlayerControls(host.playerView);
    host.playerControls.scalePlayerControls(host.playerView);
    host.playerControls.installSeekPreview(root);
    host.rewind.reset();
    host.playerView.setControllerShowTimeoutMs(3500);
    host.playerView.setControllerVisibilityListener(
        (androidx.media3.ui.PlayerView.ControllerVisibilityListener)
            visibility -> {
              overlay.setVisibility(visibility);
              host.playerControls.isControllerVisible = visibility == View.VISIBLE;
              host.playerControls.updateSkip();
            });
    host.player.addListener(
        new Player.Listener() {
          @Override
          public void onTracksChanged(androidx.media3.common.Tracks tracks) {
            if (host.player == null) return;
            if (host.playbackTracks != null) host.playbackTracks.apply(host.player, tracks);
            host.playbackMenus.matchFrameRate();
            // A file can open and still have no video or audio this TV decodes.
            boolean hasVideo = tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO),
                hasAudio = tracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO);
            DirectPlayFailure.Reason undecodable =
                DirectPlayFailure.fromTracks(
                    hasVideo && !tracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_VIDEO),
                    hasAudio && !tracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_AUDIO),
                    host.conversionPlayback.playingFact("videoCodec"),
                    host.conversionPlayback.playingFact("audioCodec"));
            if (undecodable != null) host.conversionPlayback.cannotPlayDirectly(undecodable);
          }

          @Override
          public void onVideoSizeChanged(VideoSize size) {
            ass.videoSize(Math.round(size.width * size.pixelWidthHeightRatio), size.height);
            host.status.setText("Decoded video: " + size.width + " × " + size.height);
          }

          @Override
          public void onPlayWhenReadyChanged(boolean ready, int reason) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
              if (!ready) {
                host.rewind.pause();
                host.playbackReports.reportPlayback("paused");
              } else {
                long currentPosition = host.player.getCurrentPosition(),
                    resumePosition = host.rewind.resume(currentPosition, host.sectionFloor, host.rewindSeconds());
                if (resumePosition != currentPosition) host.player.seekTo(resumePosition);
                if (host.player.getPlaybackState() == Player.STATE_READY) host.playbackReports.reportPlayback("playing");
              }
            }
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY)
              host.playbackReports.reportPlayback(host.player.getPlayWhenReady() ? "playing" : "paused");
            if (state == Player.STATE_ENDED) {
              host.playbackReports.reportPlayback("ended");
              if (host.nativePlan != null && !host.advancing) {
                host.advancing = true;
                host.nativeIndex++;
                host.nativeQueuePlayback.loadNativeItem();
              } else if (host.nativePlan == null && !host.advancing) host.handler.post(() -> host.titleScreen.returnToTitle());
            }
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            host.playbackReports.reportPlayback("error");
            overlay.setVisibility(View.VISIBLE);
            host.conversionPlayback.cannotPlayDirectly(
                DirectPlayFailure.fromError(
                    error.errorCode,
                    host.conversionPlayback.playingFact("container"),
                    host.conversionPlayback.playingFact("videoCodec"),
                    host.conversionPlayback.playingFact("audioCodec")));
          }
        });
    host.player.setMediaItem(
        new MediaItem.Builder()
            .setUri(url)
            .setMediaId(host.playingEntry == null ? "video" : host.playingEntry.key())
            .setMediaMetadata(host.playbackLauncher.nowPlayingMetadata(title))
            .setMimeType(
                ServerConversion.isConversion(url)
                    ? androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    : null)
            .setSubtitleConfigurations(
                host.playbackTracks == null
                    ? List.of()
                    : host.playbackTracks.externalSubtitles(
                        host.playingOrigin.isEmpty() ? host.server() : host.playingOrigin))
            .build());
    if (host.playbackTracks != null) host.playbackTracks.prepare(host.player);
    host.player.prepare();
    host.player.play();
    host.playerView.requestFocus();
    final ExoPlayer observedPlayer = host.player;
    host.handler.postDelayed(
        new Runnable() {
          private long reportedAt = 0;

          public void run() {
            if (host.player == null || host.player != observedPlayer) return;
            if (host.nativePlan != null
                && host.sectionEnd >= 0
                && host.player.getCurrentPosition() >= host.sectionEnd
                && !host.advancing) {
              host.playbackReports.reportPlayback("section-end");
              host.advancing = true;
              host.nativeIndex++;
              host.nativeQueuePlayback.loadNativeItem();
              return;
            }
            host.playerControls.updateSkip();
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - reportedAt >= 10000) {
              host.playbackReports.reportPlayback(host.player.isPlaying() ? "playing" : "paused");
              if (host.conversionPlayback.conversion != null && !host.player.isPlaying()) host.conversionPlayback.conversion.ping(host.progressNetwork);
              reportedAt = now;
            }
            host.handler.postDelayed(this, 250);
          }
        },
        250);
  }

  long playingDuration() {
    long duration = host.player == null ? -1 : host.player.getDuration();
    if (duration > 0) return duration;
    return host.playingEntry == null ? -1 : host.playingEntry.duration();
  }

  void releasePlayer() {
    host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    DisplayModeSwitch.clear(host.getWindow());
    host.conversionPlayback.isDirectPlayHandled = false;
    host.conversionPlayback.endConversion();
    host.playerControls.skipButton = null;
    host.playerControls.seekPreview = null;
    host.playerControls.seekIndex = null;
    if (host.player != null) {
      if (!host.advancing) host.playbackReports.reportPlayback("stopped");
      if (mediaSession != null) {
        mediaSession.release();
        mediaSession = null;
      }
      host.player.release();
      host.player = null;
    }
    if (assPlayback != null) {
      assPlayback.close();
      assPlayback = null;
    }
    host.playerView = null;
  }
}
