package dev.queuepilot.player;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** QueuePilot JSON requests. Credentials stay on the explicitly configured origin. */
final class NativeQueueApi {
  static final class Failure extends Exception {
    final int status;

    Failure(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  static JSONObject request(
      String origin, String path, JSONObject body, String header, String secret) throws Exception {
    return request(origin, path, body, header, secret, "");
  }

  static JSONObject request(
      String origin, String path, JSONObject body, String header, String secret, String proof)
      throws Exception {
    if (!secret.isEmpty() && !ServerConnectionPolicy.allowed(origin, true, false))
      throw new IllegalArgumentException(
          "Playback authorization requires HTTPS or a private local IP address");
    HttpURLConnection c =
        (HttpURLConnection) URI.create(PlexCatalog.url(origin, path)).toURL().openConnection();
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(8000);
    c.setReadTimeout(path.endsWith("/events") ? 15000 : 120000);
    c.setRequestProperty("Accept", "application/json");
    if (!secret.isEmpty()) c.setRequestProperty(header, secret);
    if (!proof.isEmpty()) c.setRequestProperty("X-Plex-Profile-Token", proof);
    try {
      if (body != null) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (var out = c.getOutputStream()) {
          out.write(bytes);
        }
      }
      int status = c.getResponseCode();
      try (var in = status < 400 ? c.getInputStream() : c.getErrorStream();
          var out = new java.io.ByteArrayOutputStream()) {
        if (in == null) throw new IllegalStateException("QueuePilot is unavailable");
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
          if (out.size() + n > 8 * 1024 * 1024)
            throw new IllegalStateException("Response too large");
          out.write(buffer, 0, n);
        }
        JSONObject result = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        if (status >= 400)
          throw new Failure(status, result.optString("error", "QueuePilot is unavailable"));
        return result;
      }
    } finally {
      c.disconnect();
    }
  }
}
