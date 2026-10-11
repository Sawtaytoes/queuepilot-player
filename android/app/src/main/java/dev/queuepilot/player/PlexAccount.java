package dev.queuepilot.player;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Plex device-link authorization. No password entry, token entry, or embedded browser. */
final class PlexAccount {
  private final String origin;
  private final String clientId;

  PlexAccount(String clientId, boolean fixture) {
    this.clientId = clientId;
    origin = fixture ? "http://10.0.2.2:9010" : "https://plex.tv";
  }

  static final class Failure extends Exception {
    final int status;

    Failure(int status) {
      super("Plex request failed (" + status + ")");
      this.status = status;
    }
  }

  private Object request(String method, String path, String token, String body) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) URI.create(origin + path).toURL().openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(8000);
    connection.setReadTimeout(12000);
    connection.setRequestMethod(method);
    connection.setRequestProperty("Accept", "application/json");
    connection.setRequestProperty("X-Plex-Client-Identifier", clientId);
    connection.setRequestProperty("X-Plex-Product", "QueuePilot TV");
    connection.setRequestProperty("X-Plex-Version", "0.2.0");
    connection.setRequestProperty("X-Plex-Platform", "Android");
    connection.setRequestProperty("X-Plex-Device", "Android TV");
    connection.setRequestProperty("X-Plex-Device-Name", "QueuePilot TV");
    if (!token.isEmpty()) connection.setRequestProperty("X-Plex-Token", token);
    try {
      if (method.equals("POST")) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (var out = connection.getOutputStream()) {
          out.write(bytes);
        }
      }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) throw new Failure(status);
      try (var input = connection.getInputStream();
          var output = new java.io.ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
          if (output.size() + n > 4 * 1024 * 1024)
            throw new IllegalStateException("Response too large");
          output.write(buffer, 0, n);
        }
        String text = output.toString(StandardCharsets.UTF_8.name());
        return text.trim().startsWith("[") ? new JSONArray(text) : new JSONObject(text);
      }
    } finally {
      connection.disconnect();
    }
  }

  JSONObject createPin() throws Exception {
    return (JSONObject) request("POST", "/api/v2/pins", "", "strong=false");
  }

  JSONObject pollPin(long id, String code) throws Exception {
    return (JSONObject) request("GET", "/api/v2/pins/" + id + "?code=" + encode(code), "", "");
  }

  JSONObject user(String token) throws Exception {
    return (JSONObject) request("GET", "/api/v2/user", token, "");
  }

  JSONArray users(String token) throws Exception {
    JSONObject home = (JSONObject) request("GET", "/api/v2/home/users", token, "");
    JSONArray users = home.optJSONArray("users");
    if (users == null || users.length() == 0)
      throw new IllegalStateException("No Home profiles returned");
    for (int i = 0; i < users.length(); i++) {
      JSONObject user = users.getJSONObject(i);
      Object protection = user.opt("protected");
      boolean unprotected =
          Boolean.FALSE.equals(protection)
              || "false".equals(protection)
              || Integer.valueOf(0).equals(protection)
              || "0".equals(protection);
      user.put("protected", !unprotected);
    }
    return users;
  }

  String switchUser(String token, String uuid, String pin) throws Exception {
    JSONObject result =
        (JSONObject)
            request(
                "POST",
                "/api/v2/home/users/" + encode(uuid) + "/switch",
                token,
                pin.isEmpty() ? "" : "pin=" + encode(pin));
    String switched = result.optString("authToken", result.optString("authenticationToken"));
    if (switched.isEmpty()) throw new IllegalStateException("Missing profile token");
    return switched;
  }

  JSONArray servers(String token) throws Exception {
    JSONArray resources =
        (JSONArray) request("GET", "/api/v2/resources?includeHttps=1&includeRelay=0", token, "");
    JSONArray servers = new JSONArray();
    for (int i = 0; i < resources.length(); i++) {
      JSONObject server = resources.optJSONObject(i);
      if (server != null
          && java.util.Arrays.asList(server.optString("provides").split(",")).contains("server")
          && !server.optString("accessToken").isEmpty()) servers.put(server);
    }
    return servers;
  }

  static String encode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
