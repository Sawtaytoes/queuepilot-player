package dev.queuepilot.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class LibraryOptionsTest {
  @Test
  public void optionsAreKeptPerLibraryProfileAndServer() {
    assertEquals("library-view:ada:srv:4", LibraryOptions.viewKey("ada", "srv", "4"));
    assertEquals("library-all:grace:srv:4", LibraryOptions.includeKey("grace", "srv", "4"));
    assertNotEquals(
        LibraryOptions.viewKey("ada", "srv", "4"), LibraryOptions.viewKey("grace", "srv", "4"));
  }

  @Test
  public void anUnknownViewIsTitles() {
    assertEquals(LibraryOptions.TITLES, LibraryOptions.view(""));
    assertEquals(LibraryOptions.TITLES, LibraryOptions.view("grid"));
    assertEquals(LibraryOptions.FOLDERS, LibraryOptions.view("folders"));
    assertTrue(LibraryOptions.isFolders("folders"));
    assertFalse(LibraryOptions.isFolders(""));
  }

  @Test
  public void aFolderLibraryIsLeftOutOfAllUntilIncludedByHand() {
    assertTrue(LibraryOptions.isIncluded("", ""));
    assertTrue(LibraryOptions.isIncluded("", "titles"));
    assertFalse(LibraryOptions.isIncluded("", "folders"));
    assertTrue(LibraryOptions.isIncluded("1", "folders"));
    assertFalse(LibraryOptions.isIncluded("0", "titles"));
  }

  @Test
  public void aChoiceEqualToTheDefaultIsNotStored() {
    assertEquals("", LibraryOptions.storedInclude(true, false));
    assertEquals("", LibraryOptions.storedInclude(false, true));
    assertEquals("1", LibraryOptions.storedInclude(true, true));
    assertEquals("0", LibraryOptions.storedInclude(false, false));
    // Included by hand while in Folders, then switched to Titles: still included.
    assertTrue(LibraryOptions.isIncluded(LibraryOptions.storedInclude(true, true), "titles"));
  }

  @Test
  public void folderPathsAreRecognizedAtAnyDepth() {
    assertEquals("/library/sections/4/folder", LibraryOptions.folderRoot("4"));
    assertEquals("", LibraryOptions.folderRoot("x"));
    assertTrue(LibraryOptions.isFolderPath("/library/sections/4/folder"));
    assertTrue(LibraryOptions.isFolderPath("/library/sections/4/folder?parent=77"));
    assertFalse(LibraryOptions.isFolderPath("/library/sections/4/all"));
    assertEquals("4", LibraryOptions.folderSection("/library/sections/4/folder?parent=77"));
    assertEquals("", LibraryOptions.folderSection("/library/metadata/4/children"));
  }

  @Test
  public void panelRowsSayTheCurrentChoice() {
    assertEquals("View as · Folders", LibraryOptions.viewLabel("folders"));
    assertEquals("View as · Titles", LibraryOptions.viewLabel(""));
    assertEquals("In All Movies and Shows · Included", LibraryOptions.includeLabel(true));
    assertEquals("In All Movies and Shows · Not included", LibraryOptions.includeLabel(false));
  }
}
