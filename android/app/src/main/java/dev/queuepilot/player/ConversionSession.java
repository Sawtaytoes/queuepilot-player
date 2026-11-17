package dev.queuepilot.player;

import android.app.Activity;
import android.util.Xml;
import java.io.ByteArrayInputStream;
import java.util.concurrent.Executor;
import org.xmlpull.v1.XmlPullParser;

/**
 * One server conversion: the server and the token of the profile that plays the item, so the
 * conversion, its keep-alive and its end all run as that profile. A queue item converts with the
 * queue profile's grant.
 */
final class ConversionSession {
  final String origin, token;
  final ServerConversion.Request request;

  ConversionSession(String origin, String token, ServerConversion.Request request) {
    this.origin = origin;
    this.token = token;
    this.request = request;
  }

  /** Asks the server whether it can convert the item. Call off the main thread. */
  ServerConversion.Decision decide() throws Exception {
    byte[] xml = PlexCatalog.get(PlexCatalog.url(origin, request.decisionPath()), token, 1 << 20);
    XmlPullParser parser = Xml.newPullParser();
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
    parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
    for (int event = parser.getEventType();
        event != XmlPullParser.END_DOCUMENT;
        event = parser.next())
      if (event == XmlPullParser.START_TAG)
        return new ServerConversion.Decision(
            ServerConversion.Decision.code(attribute(parser, "generalDecisionCode")),
            ServerConversion.Decision.code(attribute(parser, "transcodeDecisionCode")),
            ServerConversion.Decision.code(attribute(parser, "mdeDecisionCode")),
            attribute(parser, "generalDecisionText"));
    return new ServerConversion.Decision(-1, -1, -1, "");
  }

  String url() {
    return PlexCatalog.url(origin, request.startPath());
  }

  /** Keeps a paused conversion alive; Plex ends an idle one. */
  void ping(Executor network) {
    send(network, request.pingPath());
  }

  /** Ends the conversion on the server when the player leaves it. */
  void stop(Executor network) {
    send(network, request.stopPath());
  }

  private void send(Executor network, String path) {
    try {
      network.execute(
          () -> {
            try {
              PlexCatalog.get(PlexCatalog.url(origin, path), token, 1 << 16);
            } catch (Exception ignored) {
              // Best effort: Plex ends an abandoned conversion on its own.
            }
          });
    } catch (RuntimeException shutDown) {
      // The executor has stopped with the activity.
    }
  }

  /**
   * Asks what to do with a file that cannot play directly: convert it on the server now, or stop
   * so the file can be fixed. Back leaves the player where it is.
   */
  static void ask(
      Activity activity,
      TvPanel.Builder builder,
      DirectPlayFailure.Reason reason,
      Runnable convert,
      Runnable stop) {
    TvPanel panel =
        builder
            .setTitle("This file can't play directly")
            .setMessage(
                reason.text()
                    + " The server can convert it while you watch, or you can stop and fix the"
                    + " file so it plays directly next time.")
            .setPositiveButton("Convert on the server", (d, i) -> convert.run())
            .setNegativeButton("Stop so I can fix the file", (d, i) -> stop.run())
            .create();
    panel.show();
    panel.setPanelSize(new TvUi(activity).dp(460), -2);
  }

  private static String attribute(XmlPullParser parser, String name) {
    String value = parser.getAttributeValue(null, name);
    return value == null ? "" : value;
  }
}
