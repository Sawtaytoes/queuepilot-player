package dev.queuepilot.player;

import android.util.Xml;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

final class PlexCatalog {
  record Entry(
      String key,
      String title,
      String type,
      String thumb,
      String children,
      String part,
      String cast,
      int episode,
      JSONArray roles,
      long duration,
      long resume) {
    JSONObject json() throws Exception {
      return new JSONObject()
          .put("key", key)
          .put("title", title)
          .put("type", type)
          .put("thumb", thumb)
          .put("children", children)
          .put("part", part)
          .put("cast", cast)
          .put("episode", episode)
          .put("roles", roles)
          .put("duration", duration)
          .put("resume", resume);
    }

    static Entry fromJson(JSONObject j) {
      return new Entry(
          j.optString("key"),
          j.optString("title"),
          j.optString("type"),
          j.optString("thumb"),
          j.optString("children"),
          j.optString("part"),
          j.optString("cast"),
          j.optInt("episode"),
          j.optJSONArray("roles") == null ? new JSONArray() : j.optJSONArray("roles"),
          j.optLong("duration"),
          j.optLong("resume"));
    }
  }

  static String base(String input) {
    URI uri = URI.create(input.trim());
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/")))
      throw new IllegalArgumentException("Use a server origin such as https://media.example.com");
    return input.trim().replaceAll("/+$", "");
  }

  static String url(String server, String path) {
    if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\"))
      throw new IllegalArgumentException("Invalid server path");
    URI uri = URI.create(base(server)).resolve(path);
    if (!uri.getAuthority().equals(URI.create(server).getAuthority()))
      throw new IllegalArgumentException("Cross-server path refused");
    return uri.toString();
  }

  static byte[] get(String address, String token, int limit) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
    connection.setConnectTimeout(8000);
    connection.setReadTimeout(15000);
    connection.setInstanceFollowRedirects(false);
    if (!token.isBlank()) connection.setRequestProperty("X-Plex-Token", token);
    connection.setRequestProperty("Accept", "application/xml");
    try {
      int status = connection.getResponseCode();
      if (status != 200) throw new IllegalStateException("Server returned HTTP " + status);
      try (var in = connection.getInputStream();
          var out = new java.io.ByteArrayOutputStream()) {
        byte[] buffer = new byte[16384];
        int count;
        while ((count = in.read(buffer)) != -1) {
          if (out.size() + count > limit)
            throw new IllegalStateException("Response exceeds prototype limit");
          out.write(buffer, 0, count);
        }
        return out.toByteArray();
      }
    } finally {
      connection.disconnect();
    }
  }

  static JSONArray list(byte[] xml) throws Exception {
    XmlPullParser parser = Xml.newPullParser();
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
    parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
    JSONArray result = new JSONArray();
    JSONObject current = null;
    int itemDepth = -1;
    StringBuilder cast = new StringBuilder();
    for (int event = parser.getEventType();
        event != XmlPullParser.END_DOCUMENT;
        event = parser.next()) {
      if (event == XmlPullParser.START_TAG) {
        String name = parser.getName();
        if ((name.equals("Directory") || name.equals("Video")) && parser.getDepth() == 2) {
          String key = attribute(parser, "ratingKey");
          String directoryKey = attribute(parser, "key");
          String type = attribute(parser, "type");
          String libraryType = type;
          if (key.isEmpty()) {
            key = directoryKey;
            if (!directoryKey.startsWith("/")) type = "library";
          }
          if (type.isEmpty()) type = "library";
          String children =
              type.equals("library")
                  ? "/library/sections/" + directoryKey + "/all"
                  : directoryKey.startsWith("/")
                      ? directoryKey
                      : "/library/metadata/" + key + "/children";
          if (name.equals("Directory") && !type.equals("library"))
            children = "/library/metadata/" + key + "/children";
          current =
              new JSONObject()
                  .put("key", key)
                  .put("title", attribute(parser, "title"))
                  .put("type", type)
                  .put("libraryType", libraryType)
                  .put("thumb", attribute(parser, "thumb"))
                  .put("children", children)
                  .put("part", "")
                  .put("episode", attribute(parser, "index"))
                  .put("roles", new JSONArray())
                  .put("duration", attribute(parser, "duration"))
                  .put("resume", attribute(parser, "viewOffset"));
          itemDepth = parser.getDepth();
          cast.setLength(0);
        } else if (current != null && name.equals("Part") && current.optString("part").isEmpty())
          current.put("part", attribute(parser, "key"));
        else if (current != null
            && java.util.Set.of("Role", "Director", "Writer", "Producer").contains(name)) {
          current
              .getJSONArray("roles")
              .put(
                  new JSONObject()
                      .put("name", attribute(parser, "tag"))
                      .put("role", name.equals("Role") ? attribute(parser, "role") : name)
                      .put("thumb", attribute(parser, "thumb")));
          if (cast.length() > 0) cast.append('\n');
          cast.append(attribute(parser, "tag"));
          String role = attribute(parser, "role");
          if (!role.isEmpty()) cast.append(" — ").append(role);
        }
      } else if (event == XmlPullParser.END_TAG
          && current != null
          && parser.getDepth() == itemDepth) {
        current.put("cast", cast.toString());
        result.put(current);
        current = null;
      }
    }
    return result;
  }

  private static String attribute(XmlPullParser parser, String key) {
    String value = parser.getAttributeValue(null, key);
    return value == null ? "" : value;
  }
}
