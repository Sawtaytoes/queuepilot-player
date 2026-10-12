package dev.queuepilot.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class AvatarImageTest {
  @Test
  public void permitsPlexAndGravatarImageRedirects() {
    assertTrue(AvatarImage.redirectAllowed("https://assets.plex.tv/avatar.png"));
    assertTrue(AvatarImage.redirectAllowed("https://secure.gravatar.com/avatar/fixture"));
  }

  @Test
  public void refusesDowngradeCredentialsPrivateHostsAndLookalikes() {
    for (String u :
        new String[] {
          "http://assets.plex.tv/a",
          "https://user@assets.plex.tv/a",
          "https://127.0.0.1/a",
          "https://plex.tv.example.com/a",
          "https://assets.plex.tv:444/a"
        }) assertFalse(u, AvatarImage.redirectAllowed(u));
  }
}
