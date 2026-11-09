package pl.folderlan.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Optional standalone integration runner. Credentials are read only from the invoking process environment. */
public final class SmbIntegrationSmoke {
    public static void main(String[] args) throws Exception {
        String host = System.getenv("FOLDERLAN_TEST_HOST");
        String user = System.getenv("FOLDERLAN_TEST_USER");
        String password = System.getenv("FOLDERLAN_TEST_PASSWORD");
        String share = System.getenv("FOLDERLAN_TEST_SHARE");
        if (host == null || user == null || password == null || share == null) {
            System.err.println("Nie wykonano integracji: brak prywatnego serwera SMB3 i poświadczeń w środowisku.");
            System.exit(2);
        }
        try (SmbRepository rejected = new SmbRepository()) {
            boolean badPasswordRejected = false;
            try { rejected.connect(new SmbRepository.Credentials(host, share, user, "", password + "-invalid")); }
            catch (java.io.IOException expectedError) {
                badPasswordRejected = expectedError.getMessage().contains("poświadczenia");
            }
            if (!badPasswordRejected) throw new AssertionError("Nie odrzucono błędnego hasła lub nie opisano go poprawnie");
            boolean publicHostRejected = false;
            try { rejected.connect(new SmbRepository.Credentials("8.8.8.8", share, user, "", password)); }
            catch (java.io.IOException expectedError) {
                publicHostRejected = expectedError.getMessage().contains("lokalny");
            }
            if (!publicHostRejected) throw new AssertionError("Nie odrzucono adresu poza LAN");
        }
        String directory = "FolderLAN-test-" + UUID.randomUUID();
        String original = SharePath.child(directory, "test.txt");
        String renamed = SharePath.child(directory, "zmieniony.txt");
        byte[] expected = "Folder LAN — próba przesyłania żółć".getBytes(StandardCharsets.UTF_8);
        try (SmbRepository repository = new SmbRepository()) {
            repository.connect(new SmbRepository.Credentials(host, share, user, "", password));
            try {
                repository.mkdir(directory);
                repository.upload(original, new ByteArrayInputStream(expected));
                if (repository.list(directory).size() != 1) throw new AssertionError("Niezgodna lista plików");
                ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
                repository.download(original, downloaded);
                if (!Arrays.equals(expected, downloaded.toByteArray())) throw new AssertionError("Niezgodna treść pliku");
                boolean collision = false;
                try { repository.upload(original, new ByteArrayInputStream(new byte[]{0})); } catch (java.io.IOException expectedError) { collision = true; }
                if (!collision) throw new AssertionError("Nadpisano istniejący plik");
                boolean nonempty = false;
                try { repository.delete(directory, true); } catch (java.io.IOException expectedError) { nonempty = true; }
                if (!nonempty) throw new AssertionError("Usunięto niepusty folder");
                repository.rename(original, renamed);
                repository.delete(renamed, false);
                repository.delete(directory, true);
                System.out.println("SMB3 integracja: połączenie, lista, mkdir, upload/download zgodne bajtowo, ochrona kolizji, rename/delete, odmowa błędnego hasła i publicznego adresu — OK.");
            } finally {
                try { repository.delete(original, false); } catch (Exception ignored) { }
                try { repository.delete(renamed, false); } catch (Exception ignored) { }
                try { repository.delete(directory, true); } catch (Exception ignored) { }
            }
        }
    }
}
