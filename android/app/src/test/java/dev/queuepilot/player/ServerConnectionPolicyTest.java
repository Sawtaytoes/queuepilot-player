package dev.queuepilot.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class ServerConnectionPolicyTest {
  @Test
  public void directHttpsIsAllowedButRelaysAreNeverSelected() {
    assertTrue(ServerConnectionPolicy.allowed("https://media.example.com:32400", false, false));
    assertFalse(ServerConnectionPolicy.allowed("https://relay.example.com", false, true));
  }

  @Test
  public void plainHttpIsRestrictedToLocalPrivateAddresses() {
    assertTrue(ServerConnectionPolicy.allowed("http://10.0.2.2:9010", true, false));
    assertTrue(ServerConnectionPolicy.allowed("http://192.168.5.4:32400", true, false));
    assertTrue(ServerConnectionPolicy.allowed("http://172.16.0.8:32400", true, false));
    for (String host :
        new String[] {"example.com", "127.0.0.1", "169.254.169.254", "172.32.0.8", "10.999.1.1"})
      assertFalse(host, ServerConnectionPolicy.allowed("http://" + host + ":32400", true, false));
    assertFalse(ServerConnectionPolicy.allowed("http://192.168.5.4:32400", false, false));
  }

  @Test
  public void credentialsPathsAndAlternativeSchemesAreRefused() {
    for (String origin :
        new String[] {
          "https://token@media.example.com",
          "https://media.example.com/steal",
          "file:///etc/passwd",
          "https://media.example.com?token=secret"
        }) assertFalse(origin, ServerConnectionPolicy.allowed(origin, true, false));
  }
}
