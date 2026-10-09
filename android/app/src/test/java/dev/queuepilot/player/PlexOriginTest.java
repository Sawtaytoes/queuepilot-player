package dev.queuepilot.player;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlexOriginTest {
 @Test public void resourcePathsStayOnTheEnrolledOrigin() {
  assertEquals("https://media.example.com:32400/library/metadata/7",PlexCatalog.url("https://media.example.com:32400","/library/metadata/7"));
 }
 @Test public void originsRejectEmbeddedCredentialsAndUnexpectedPaths() {
  for(String origin : new String[]{"https://owner:secret@media.example.com","https://media.example.com/path","file:///tmp/media","https://media.example.com?token=secret"}) {
   try {PlexCatalog.base(origin);fail("Should refuse invalid origin");}catch(IllegalArgumentException expected){}
  }
 }
 @Test public void foreignArtworkAndMediaPathsAreRefused() {
  for(String path : new String[]{"//other.example.com/video","https://other.example.com/video","/\\other.example.com/video"}) {
   try {PlexCatalog.url("https://media.example.com",path);fail("Should refuse cross-server path");}catch(IllegalArgumentException expected){}
  }
 }
}
