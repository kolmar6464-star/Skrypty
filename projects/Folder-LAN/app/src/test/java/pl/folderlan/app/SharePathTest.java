package pl.folderlan.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SharePathTest {
    @Test public void supportsUnicodeAndNestedPaths() {
        assertEquals("Zdjęcia\\Rodzina\\żółć 😀.jpg", SharePath.normalize("Zdjęcia/Rodzina/żółć 😀.jpg"));
        assertEquals("Zdjęcia\\nowy folder", SharePath.child("Zdjęcia", "nowy folder"));
    }
    @Test public void rootCanBeListedButNeverMutated() {
        assertEquals("", SharePath.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> SharePath.requireChild(""));
    }
    @Test public void rejectsTraversalAndAbsolutePaths() {
        for (String value : new String[]{"..", "../secret", "folder/../../secret", "folder\\..\\secret", "./file", "/outside", "\\outside", "\\\\server\\share", "C:\\Windows", "folder//file"}) {
            assertThrows(value, IllegalArgumentException.class, () -> SharePath.normalize(value));
        }
    }
    @Test public void rejectsWindowsAliasesAndAlternateStreams() {
        for (String value : new String[]{"file:stream", "NUL", "con.txt", "COM1.log", "Lpt9", "folder.", "folder ", "bad\u0000name", "a?b"}) {
            assertThrows(value, IllegalArgumentException.class, () -> SharePath.normalize(value));
        }
    }
    @Test public void entryNamesCannotInjectPathSegments() {
        assertThrows(IllegalArgumentException.class, () -> SharePath.child("folder", "../outside"));
        assertThrows(IllegalArgumentException.class, () -> SharePath.child("folder", "evil\\path"));
    }
    @Test public void parentRemainsInsideShare() {
        assertEquals("folder", SharePath.parent("folder/file"));
        assertEquals("", SharePath.parent("folder"));
        assertEquals("", SharePath.parent(""));
    }
    @Test public void credentialsAreNeverGuestAndNeverPrinted() {
        assertThrows(IllegalArgumentException.class, () -> new SmbRepository.Credentials("192.168.1.2", "FolderUdostepnionyLAN", "", "", "fixture"));
        assertThrows(IllegalArgumentException.class, () -> new SmbRepository.Credentials("192.168.1.2", "FolderUdostepnionyLAN", "user", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SmbRepository.Credentials("192.168.1.2", "../share", "user", "", "fixture"));
        SmbRepository.Credentials credentials = new SmbRepository.Credentials("192.168.1.2", "FolderUdostepnionyLAN", "user", "", "fixture");
        assertEquals("Credentials[redacted]", credentials.toString());
    }
}
