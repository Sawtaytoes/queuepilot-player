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
      long resume,
      String seasonKey,
      String showKey,
      JSONArray markers,
      String showTitle,
      int season,
      String seasonTitle,
      String showThumb,
      String summary,
      JSONObject facts) {
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
          .put("resume", resume)
          .put("seasonKey", seasonKey)
          .put("showKey", showKey)
          .put("markers", markers)
          .put("showTitle", showTitle)
          .put("season", season)
          .put("seasonTitle", seasonTitle)
          .put("showThumb", showThumb)
          .put("summary", summary)
          .put("facts", facts);
    }

    /** The large line: the show for an episode, otherwise this item's title. */
    String primary() {
      return EpisodeLabel.primary(type, title, showTitle);
    }

    /** "S1 · E9 · The signal" for an episode; empty for anything else. */
    String secondary() {
      return EpisodeLabel.secondary(type, season, episode, title);
    }

    String spoken() {
      return EpisodeLabel.spoken(type, title, showTitle, season, episode);
    }

    /** Poster-shaped episode cards use the show poster, like the approved preview. */
    String posterThumb() {
      return "episode".equals(type) && !showThumb.isEmpty() ? showThumb : thumb;
    }

    /** Intro and credits markers, when the metadata was requested with includeMarkers=1. */
    java.util.List<MarkerSkip.Marker> skipMarkers() {
      java.util.List<MarkerSkip.Marker> list = new java.util.ArrayList<>();
      for (int i = 0; i < markers.length(); i++) {
        JSONObject m = markers.optJSONObject(i);
        if (m != null)
          list.add(
              new MarkerSkip.Marker(
                  m.optString("type"),
                  m.optLong("start"),
                  m.optLong("end"),
                  m.optBoolean("isFinal")));
      }
      return list;
    }

    /** The same title with a completed cast; inherited credits are looked up only once. */
    Entry withCast(JSONArray completeRoles) {
      return new Entry(
          key,
          title,
          type,
          thumb,
          children,
          part,
          cast,
          episode,
          completeRoles,
          duration,
          resume,
          "",
          "",
          markers,
          showTitle,
          season,
          seasonTitle,
          showThumb,
          summary,
          facts);
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
          j.optLong("resume"),
          j.optString("seasonKey"),
          j.optString("showKey"),
          j.optJSONArray("markers") == null ? new JSONArray() : j.optJSONArray("markers"),
          // Catalogs cached before 0.5.2 lack these; the episode then keeps its own title.
          j.optString("showTitle"),
          j.optInt("season", EpisodeLabel.UNKNOWN),
          j.optString("seasonTitle"),
          j.optString("showThumb"),
          j.optString("summary"),
          j.optJSONObject("facts") == null ? new JSONObject() : j.optJSONObject("facts"));
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

  /** A Plex {@code PUT} with no body, such as saving a part's chosen streams. */
  static void put(String address, String token) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
    connection.setConnectTimeout(8000);
    connection.setReadTimeout(15000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod("PUT");
    if (!token.isBlank()) connection.setRequestProperty("X-Plex-Token", token);
    connection.setRequestProperty("Accept", "application/xml");
    try {
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300)
        throw new IllegalStateException("Server returned HTTP " + status);
    } finally {
      connection.disconnect();
    }
  }

  static JSONArray list(byte[] xml) throws Exception {
    return list(xml, 2);
  }

  /**
   * Search results: the items of each {@code <Hub>}, each carrying its hub's type in
   * {@code hub} ("movie", "show", "episode", …).
   */
  static JSONArray hubs(byte[] xml) throws Exception {
    return list(xml, 3);
  }

  private static XmlPullParser parser(byte[] xml) throws Exception {
    XmlPullParser parser = Xml.newPullParser();
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
    parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
    return parser;
  }

  /** The listing's {@code totalSize} (sent for a paged request), else -1. */
  static int totalSize(byte[] xml) throws Exception {
    XmlPullParser parser = parser(xml);
    for (int event = parser.getEventType();
        event != XmlPullParser.END_DOCUMENT;
        event = parser.next())
      if (event == XmlPullParser.START_TAG) {
        try {
          return Integer.parseInt(attribute(parser, "totalSize"));
        } catch (NumberFormatException e) {
          return -1;
        }
      }
    return -1;
  }

  /** A section's first-character index ({@code /library/sections/<key>/firstCharacter}). */
  static java.util.List<LibraryBrowse.Letter> letters(byte[] xml) throws Exception {
    XmlPullParser parser = parser(xml);
    java.util.List<LibraryBrowse.Letter> letters = new java.util.ArrayList<>();
    for (int event = parser.getEventType();
        event != XmlPullParser.END_DOCUMENT;
        event = parser.next())
      if (event == XmlPullParser.START_TAG
          && parser.getName().equals("Directory")
          && parser.getDepth() == 2) {
        int size;
        try {
          size = Integer.parseInt(attribute(parser, "size"));
        } catch (NumberFormatException e) {
          size = 0;
        }
        String key = attribute(parser, "key"), title = attribute(parser, "title");
        if (!key.isEmpty()) letters.add(new LibraryBrowse.Letter(key, title.isEmpty() ? key : title, size));
      }
    return letters;
  }

  private static JSONArray list(byte[] xml, int depth) throws Exception {
    XmlPullParser parser = parser(xml);
    JSONArray result = new JSONArray();
    JSONObject current = null;
    int itemDepth = -1, partDepth = -1;
    boolean isFirstPart = false;
    String hub = "";
    StringBuilder cast = new StringBuilder();
    for (int event = parser.getEventType();
        event != XmlPullParser.END_DOCUMENT;
        event = parser.next()) {
      if (event == XmlPullParser.START_TAG) {
        String name = parser.getName();
        if (name.equals("Hub") && parser.getDepth() == depth - 1) hub = attribute(parser, "type");
        if ((name.equals("Directory") || name.equals("Video")) && parser.getDepth() == depth) {
          String key = attribute(parser, "ratingKey");
          String directoryKey = attribute(parser, "key");
          String type = attribute(parser, "type");
          String libraryType = type;
          if (key.isEmpty()) {
            key = directoryKey;
            if (!directoryKey.startsWith("/")) type = "library";
          }
          // A folder of a library's folder view: /library/sections/<key>/folder?parent=<id>.
          boolean isFolder =
              name.equals("Directory") && type.isEmpty() && LibraryOptions.isFolderPath(directoryKey);
          if (isFolder) type = "folder";
          if (type.isEmpty()) type = "library";
          String children =
              type.equals("library")
                  ? "/library/sections/" + directoryKey + "/all"
                  : directoryKey.startsWith("/")
                      ? directoryKey
                      : "/library/metadata/" + key + "/children";
          if (name.equals("Directory") && !type.equals("library"))
            children = isFolder ? directoryKey : "/library/metadata/" + key + "/children";
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
                  .put("resume", attribute(parser, "viewOffset"))
                  .put("seasonKey", attribute(parser, "parentRatingKey"))
                  .put("showKey", attribute(parser, "grandparentRatingKey"))
                  .put("markers", new JSONArray())
                  .put("showTitle", attribute(parser, "grandparentTitle"))
                  .put("season", seasonNumber(attribute(parser, "parentIndex")))
                  .put("seasonTitle", attribute(parser, "parentTitle"))
                  .put("showThumb", attribute(parser, "grandparentThumb"))
                  .put("summary", attribute(parser, "summary"))
                  .put("facts", facts(parser))
                  .put("hub", hub);
          itemDepth = parser.getDepth();
          cast.setLength(0);
        } else if (current != null && name.equals("Marker"))
          current
              .getJSONArray("markers")
              .put(
                  new JSONObject()
                      .put("type", attribute(parser, "type"))
                      .put("start", number(attribute(parser, "startTimeOffset")))
                      .put("end", number(attribute(parser, "endTimeOffset")))
                      .put("isFinal", "1".equals(attribute(parser, "final"))));
        else if (current != null && name.equals("Part") && current.optString("part").isEmpty()) {
          isFirstPart = true;
          partDepth = parser.getDepth();
          current.put("part", attribute(parser, "key"));
          // indexes="sd" means a BIF of seek thumbnails at /library/parts/<id>/indexes/sd.
          current
              .getJSONObject("facts")
              .put("partId", attribute(parser, "id"))
              .put("indexes", attribute(parser, "indexes"))
              .put("partContainer", attribute(parser, "container"))
              .put("streams", new JSONArray());
        } else if (current != null
            && isFirstPart
            && name.equals("Stream")
            && parser.getDepth() == partDepth + 1)
          current.getJSONObject("facts").getJSONArray("streams").put(stream(parser).json());
        else if (current != null
            && name.equals("Chapter")
            && parser.getDepth() == itemDepth + 1) {
          JSONObject facts = current.getJSONObject("facts");
          if (!facts.has("chapters")) facts.put("chapters", new JSONArray());
          facts
              .getJSONArray("chapters")
              .put(
                  new Chapters.Chapter(
                          attribute(parser, "tag"),
                          (int) number(attribute(parser, "index")),
                          number(attribute(parser, "startTimeOffset")),
                          number(attribute(parser, "endTimeOffset")),
                          attribute(parser, "thumb"))
                      .json());
        } else if (current != null
            && java.util.Set.of("Genre", "Country").contains(name)
            && parser.getDepth() == itemDepth + 1)
          append(
              current.getJSONObject("facts"),
              name.equals("Genre") ? "genres" : "countries",
              parser);
        else if (current != null
            && name.equals("Collection")
            && parser.getDepth() == itemDepth + 1) {
          // The collections a title belongs to, by name and tag id, for Go to collection.
          JSONObject facts = current.getJSONObject("facts");
          JSONArray collections = facts.optJSONArray("collections");
          if (collections == null) facts.put("collections", collections = new JSONArray());
          collections.put(
              new JSONObject()
                  .put("tag", attribute(parser, "tag"))
                  .put("id", attribute(parser, "id")));
        } else if (current != null && name.equals("Media") && parser.getDepth() == itemDepth + 1) {
          JSONObject facts = current.getJSONObject("facts");
          if (!facts.has("videoResolution"))
            facts
                .put("videoResolution", attribute(parser, "videoResolution"))
                .put("audioChannels", attribute(parser, "audioChannels"))
                .put("container", attribute(parser, "container"))
                .put("videoCodec", attribute(parser, "videoCodec"))
                .put("audioCodec", attribute(parser, "audioCodec"))
                .put("bitrate", attribute(parser, "bitrate"));
        }
        else if (current != null
            && java.util.Set.of("Role", "Director", "Writer", "Producer").contains(name)) {
          current
              .getJSONArray("roles")
              .put(
                  new JSONObject()
                      .put("name", attribute(parser, "tag"))
                      .put("kind", name)
                      .put("role", name.equals("Role") ? attribute(parser, "role") : name)
                      .put("thumb", attribute(parser, "thumb")));
          if (cast.length() > 0) cast.append('\n');
          cast.append(attribute(parser, "tag"));
          String role = attribute(parser, "role");
          if (!role.isEmpty()) cast.append(" — ").append(role);
        }
      } else if (event == XmlPullParser.END_TAG
          && isFirstPart
          && parser.getName().equals("Part")
          && parser.getDepth() == partDepth) {
        isFirstPart = false;
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

  /** One {@code <Stream>} of a part: streamType 1 video, 2 audio, 3 subtitle. */
  private static PlexTracks.Stream stream(XmlPullParser parser) {
    double frameRate;
    try {
      frameRate = Double.parseDouble(attribute(parser, "frameRate"));
    } catch (NumberFormatException e) {
      frameRate = 0;
    }
    String title = attribute(parser, "title");
    if (title.isEmpty()) title = attribute(parser, "displayTitle");
    return new PlexTracks.Stream(
        number(attribute(parser, "id")),
        (int) number(attribute(parser, "streamType")),
        (int) number(attribute(parser, "index")),
        attribute(parser, "languageCode").isEmpty()
            ? attribute(parser, "languageTag")
            : attribute(parser, "languageCode"),
        title,
        attribute(parser, "codec"),
        "1".equals(attribute(parser, "selected")),
        "1".equals(attribute(parser, "default")),
        "1".equals(attribute(parser, "forced")),
        attribute(parser, "key"),
        frameRate);
  }

  private static int seasonNumber(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return EpisodeLabel.UNKNOWN;
    }
  }

  private static long number(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Title facts a detail screen shows; every value is optional and kept as Plex sent it. */
  private static final String[] FACTS = {
    "year",
    "contentRating",
    "studio",
    "rating",
    "audienceRating",
    "originallyAvailableAt",
    "leafCount",
    "viewedLeafCount",
    "childCount",
    "viewCount",
    "tagline",
    // An extra's kind (/library/metadata/<key>/extras), and the library an item belongs to.
    "extraType",
    "subtype",
    "librarySectionID",
    // What All Movies and Shows sorts by, beside the facts above.
    "titleSort",
    "addedAt",
    "lastViewedAt",
  };

  private static JSONObject facts(XmlPullParser parser) throws Exception {
    JSONObject facts = new JSONObject().put("genres", new JSONArray());
    for (String name : FACTS) {
      String value = attribute(parser, name);
      if (!value.isEmpty()) facts.put(name, value);
    }
    return facts;
  }

  private static void append(JSONObject facts, String key, XmlPullParser parser)
      throws Exception {
    JSONArray list = facts.optJSONArray(key);
    if (list == null) facts.put(key, list = new JSONArray());
    list.put(attribute(parser, "tag"));
  }

  private static String attribute(XmlPullParser parser, String key) {
    String value = parser.getAttributeValue(null, key);
    return value == null ? "" : value;
  }
}
