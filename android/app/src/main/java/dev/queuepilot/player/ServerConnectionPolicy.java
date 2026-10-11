package dev.queuepilot.player;

import java.net.URI;

/** Never send account credentials to a server, follow a redirect, or select a relay. */
final class ServerConnectionPolicy {
  static boolean allowed(String address, boolean local, boolean relay) {
    if (relay) return false;
    try {
      URI uri = URI.create(PlexCatalog.base(address));
      if (uri.getScheme().equals("https")) return true;
      if (!local) return false;
      String host = uri.getHost();
      if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
      if (host.contains(":"))
        return host.toLowerCase(java.util.Locale.ROOT)
            .matches("(?:f[cd][0-9a-f]{2}|fe[89ab][0-9a-f]):[0-9a-f:]+");
      String[] parts = host.split("\\.");
      if (parts.length != 4) return false;
      int[] octets = new int[4];
      for (int i = 0; i < 4; i++) {
        if (!parts[i].matches("[0-9]{1,3}")) return false;
        octets[i] = Integer.parseInt(parts[i]);
        if (octets[i] > 255) return false;
      }
      return octets[0] == 10
          || (octets[0] == 192 && octets[1] == 168)
          || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31);
    } catch (Exception ignored) {
      return false;
    }
  }
}
