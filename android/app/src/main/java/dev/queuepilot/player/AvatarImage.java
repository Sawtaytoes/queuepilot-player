package dev.queuepilot.player;

import java.net.HttpURLConnection;
import java.net.URI;

/** Image redirects are credential-free and restricted to Plex's image hosts. */
final class AvatarImage {
  static boolean redirectAllowed(String target) {
    try {
      URI u = URI.create(target);
      String host = u.getHost();
      return "https".equals(u.getScheme())
          && u.getUserInfo() == null
          && (u.getPort() == -1 || u.getPort() == 443)
          && host != null
          && (host.equals("plex.tv")
              || host.endsWith(".plex.tv")
              || host.equals("secure.gravatar.com")
              || host.equals("www.gravatar.com"));
    } catch (Exception e) {
      return false;
    }
  }

  static byte[] get(String address) throws Exception {
    String current = address;
    for (int attempt = 0; attempt < 4; attempt++) {
      URI imageUri = URI.create(current);
      if (imageUri.getHost() != null && imageUri.getHost().endsWith("gravatar.com")) {
        current = current.replaceAll("([?&])s=[0-9]+", "$1s=512");
        if (!current.matches(".*[?&]s=512(?:&.*)?"))
          current += (current.contains("?") ? "&" : "?") + "s=512";
      }
      HttpURLConnection c = (HttpURLConnection) URI.create(current).toURL().openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(8000);
      c.setReadTimeout(10000);
      try {
        int status = c.getResponseCode();
        if (status >= 300 && status < 400) {
          String next = URI.create(current).resolve(c.getHeaderField("Location")).toString();
          if (!redirectAllowed(next)) throw new IllegalStateException("Image redirect refused");
          current = next;
          continue;
        }
        if (status != 200 || c.getContentType() == null || !c.getContentType().startsWith("image/"))
          throw new IllegalStateException("Image is unavailable");
        try (var input = c.getInputStream();
            var output = new java.io.ByteArrayOutputStream()) {
          byte[] buffer = new byte[8192];
          int n;
          while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > 4 * 1024 * 1024)
              throw new IllegalStateException("Image too large");
            output.write(buffer, 0, n);
          }
          return output.toByteArray();
        }
      } finally {
        c.disconnect();
      }
    }
    throw new IllegalStateException("Too many image redirects");
  }
}
