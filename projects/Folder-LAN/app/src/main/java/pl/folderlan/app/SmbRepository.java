package pl.folderlan.app;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.auth.NtlmAuthenticator;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;

/** Blocking SMB operations: run all methods on the application's I/O executor, never the UI thread. */
public final class SmbRepository implements Closeable {
    public static final class Credentials {
        public final String host;
        public final String share;
        public final String username;
        public final String domain;
        public final String password;

        public Credentials(String host, String share, String username, String domain, String password) {
            this.host = HostPolicy.cleanHost(host);
            SharePath.validateName(share);
            if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
                throw new IllegalArgumentException("Podaj konto i hasło SMB z jednorazowego parowania.");
            }
            this.share = share;
            this.username = username.trim();
            this.domain = domain == null ? "" : domain.trim();
            this.password = password;
        }

        // Never include passwords (or tokens) in toString/logging.
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    public static final class Entry {
        public final String name;
        public final boolean isDirectory;
        public final long size;
        public Entry(String name, boolean isDirectory, long size) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.size = size;
        }
    }

    private final SocketFactory baseSocketFactory;

    /** Standard JVM/socket routing, useful for standalone protocol tests. */
    public SmbRepository() {
        this(SocketFactory.getDefault());
    }

    /** Android callers should pass the selected Wi-Fi Network.getSocketFactory(). */
    public SmbRepository(SocketFactory baseSocketFactory) {
        if (baseSocketFactory == null) throw new IllegalArgumentException("Nie wybrano sieci lokalnej.");
        this.baseSocketFactory = baseSocketFactory;
    }

    private SMBClient client;
    private Connection connection;
    private Session session;
    private DiskShare share;

    public synchronized boolean isConnected() {
        return share != null && share.isConnected();
    }

    public synchronized void connect(Credentials credentials) throws IOException {
        close();
        InetAddress pinnedAddress = HostPolicy.resolveLocal(credentials.host);
        SmbConfig config = SmbConfig.builder()
                .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
                .withSigningRequired(true)
                .withSigningEnabled(true)
                .withEncryptData(true)
                .withDfsEnabled(false)
                .withDirectoryLeasingEnabled(false)
                .withAuthenticators(new NtlmAuthenticator.Factory())
                .withSocketFactory(new LanSocketFactory(baseSocketFactory))
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build();
        char[] password = credentials.password.toCharArray();
        AuthenticationContext authentication = new AuthenticationContext(credentials.username, password, credentials.domain);
        try {
            client = new SMBClient(config);
            // Connect to the validated literal address to avoid a second hostname resolution (DNS rebinding).
            connection = client.connect(pinnedAddress.getHostAddress(), 445);
            session = connection.authenticate(authentication);
            if (session.isGuest() || session.isAnonymous()) {
                throw new IOException("Komputer zwrócił dostęp gościa. Aplikacja wymaga uwierzytelnionego konta SMB.");
            }
            Share connectedShare = session.connectShare(credentials.share);
            if (!(connectedShare instanceof DiskShare)) {
                connectedShare.close();
                throw new IOException("Wybrany udział nie jest folderem dyskowym SMB.");
            }
            share = (DiskShare) connectedShare;
        } catch (Exception error) {
            try { close(); } catch (IOException ignored) { }
            throw translate(error);
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(authentication.getPassword(), '\0');
        }
    }

    public synchronized List<Entry> list(String relativePath) throws IOException {
        try {
            requireConnection();
            String path = SharePath.normalize(relativePath);
            verifyNoReparsePoints(path);
            List<Entry> result = new ArrayList<>();
            for (FileIdBothDirectoryInformation info : share.list(path)) {
                String name = info.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;
                // Do not follow Windows junctions/symlinks through the selected shared folder.
                if ((info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) != 0) continue;
                try { SharePath.validateName(name); } catch (IllegalArgumentException ignored) { continue; }
                boolean directory = (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                result.add(new Entry(name, directory, directory ? 0 : info.getEndOfFile()));
            }
            Collections.sort(result, Comparator.comparing((Entry entry) -> !entry.isDirectory)
                    .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (Exception error) { throw translate(error); }
    }

    public synchronized void download(String path, OutputStream destination) throws IOException {
        try {
            requireConnection();
            String safePath = SharePath.requireChild(path);
            verifyNoReparsePoints(safePath);
            try (com.hierynomus.smbj.share.File file = share.openFile(safePath,
                    EnumSet.of(AccessMask.GENERIC_READ), EnumSet.noneOf(FileAttributes.class),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                 InputStream input = file.getInputStream()) {
                copy(input, destination);
                destination.flush();
            }
        } catch (Exception error) { throw translate(error); }
        // The caller owns the Android SAF stream and closes it.
    }

    public synchronized void upload(String path, InputStream source) throws IOException {
        String temporary = null;
        boolean committed = false;
        try {
            requireConnection();
            String safePath = SharePath.requireChild(path);
            String parent = SharePath.parent(safePath);
            verifyNoReparsePoints(parent);
            if (share.fileExists(safePath) || share.folderExists(safePath)) {
                throw new IOException("Plik lub folder o tej nazwie już istnieje. Zmień nazwę przed wysłaniem.");
            }
            temporary = SharePath.child(parent, ".folderlan-upload-" + UUID.randomUUID() + ".tmp");
            try (com.hierynomus.smbj.share.File file = share.openFile(temporary,
                    EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.noneOf(SMB2ShareAccess.class), SMB2CreateDisposition.FILE_CREATE,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                 OutputStream output = file.getOutputStream()) {
                copy(source, output);
                output.flush();
            }
            // Rename only once a complete upload has arrived; never overwrite an existing file.
            renameInternal(temporary, safePath);
            committed = true;
        } catch (Exception error) { throw translate(error); }
        finally {
            if (!committed && temporary != null && share != null) {
                try { share.rm(temporary); } catch (Exception ignored) { }
            }
        }
    }

    public synchronized void mkdir(String path) throws IOException {
        try {
            requireConnection();
            String safePath = SharePath.requireChild(path);
            verifyNoReparsePoints(SharePath.parent(safePath));
            share.mkdir(safePath);
        } catch (Exception error) { throw translate(error); }
    }

    public synchronized void rename(String oldPath, String newPath) throws IOException {
        try {
            requireConnection();
            String source = SharePath.requireChild(oldPath);
            String destination = SharePath.requireChild(newPath);
            verifyNoReparsePoints(source);
            verifyNoReparsePoints(SharePath.parent(destination));
            renameInternal(source, destination);
        } catch (Exception error) { throw translate(error); }
    }

    public synchronized void delete(String path, boolean directory) throws IOException {
        try {
            requireConnection();
            String safePath = SharePath.requireChild(path);
            verifyNoReparsePoints(safePath);
            if (directory) share.rmdir(safePath, false); // Empty folders only; never recurse through other data.
            else share.rm(safePath);
        } catch (Exception error) { throw translate(error); }
    }

    private void renameInternal(String oldPath, String newPath) throws IOException {
        try (DiskEntry entry = share.open(oldPath, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.noneOf(FileAttributes.class), EnumSet.allOf(SMB2ShareAccess.class),
                SMB2CreateDisposition.FILE_OPEN, EnumSet.noneOf(SMB2CreateOptions.class))) {
            entry.rename(newPath, false);
        }
    }

    private void verifyNoReparsePoints(String path) throws IOException {
        if (path.isEmpty()) return;
        String current = "";
        for (String component : path.split("\\\\")) {
            current = SharePath.child(current, component);
            long attributes = share.getFileInformation(current).getBasicInformation().getFileAttributes();
            if ((attributes & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) != 0) {
                throw new IOException("Ze względów bezpieczeństwa aplikacja nie otwiera dowiązań ani punktów połączenia Windows.");
            }
        }
    }

    private void requireConnection() throws IOException {
        if (!isConnected()) throw new IOException("Brak połączenia. Połącz ponownie z komputerem w tej samej sieci Wi-Fi.");
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Przerwano przesyłanie pliku.");
            if (count > 0) output.write(buffer, 0, count);
        }
    }

    private static IOException translate(Exception error) {
        if (error instanceof SMBApiException) {
            long status = ((SMBApiException) error).getStatusCode();
            if (status == 0xc000006dL || status == 0xc000006aL || status == 0xc0000064L || status == 0xc0000234L) {
                return new IOException("Błędne poświadczenia SMB lub zablokowane konto. Wykonaj ponowne parowanie.");
            }
            if (status == 0xc0000022L) return new IOException("Komputer odmówił dostępu. Sprawdź uprawnienia udziału i konta FolderLAN_SVC.");
            if (status == 0xc00000ccL) return new IOException("Nie znaleziono udziału SMB. Sprawdź nazwę FolderUdostepnionyLAN.");
            if (status == 0xc0000035L) return new IOException("Plik lub folder o tej nazwie już istnieje.");
            if (status == 0xc0000101L) return new IOException("Folder nie jest pusty. Najpierw usuń jego zawartość.");
            if (status == 0xc0000034L || status == 0xc000003aL) return new IOException("Plik lub folder już nie istnieje. Odśwież listę.");
            return new IOException("Operacja SMB nie powiodła się (kod 0x" + Long.toHexString(status) + "). Sprawdź połączenie i uprawnienia.");
        }
        if (error instanceof SocketTimeoutException) return new IOException("Komputer nie odpowiada. Sprawdź Wi-Fi, zasilanie komputera i usługę SMB 445 w sieci lokalnej.");
        if (error instanceof IOException) return (IOException) error;
        if (error instanceof IllegalArgumentException) return new IOException(error.getMessage());
        return new IOException("Nie udało się połączyć z SMB. Komputer musi obsługiwać szyfrowane SMB 3 i poprawne konto lokalne.");
    }

    @Override public synchronized void close() throws IOException {
        IOException failure = null;
        if (share != null) { try { share.close(); } catch (IOException error) { failure = error; } finally { share = null; } }
        if (session != null) { try { session.close(); } catch (IOException error) { if (failure == null) failure = error; } finally { session = null; } }
        if (connection != null) { try { connection.close(true); } catch (IOException error) { if (failure == null) failure = error; } finally { connection = null; } }
        if (client != null) { client.close(); client = null; }
        if (failure != null) throw new IOException("Połączenie SMB zostało przerwane podczas zamykania.");
    }

    /** Explicit TCP connect timeout and LAN policy are applied even inside the SMB transport. */
    private static final class LanSocketFactory extends SocketFactory {
        private final SocketFactory base;
        private LanSocketFactory(SocketFactory base) { this.base = base; }
        private Socket open(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            if (port != 445 || !HostPolicy.isLocalAddress(address)) throw new IOException("Połączenie SMB jest dozwolone wyłącznie w sieci LAN na porcie 445.");
            Socket socket = base.createSocket();
            try {
                if (local != null) socket.bind(new InetSocketAddress(local, localPort));
                socket.connect(new InetSocketAddress(address, port), 8000);
                return socket;
            } catch (IOException error) { try { socket.close(); } catch (IOException ignored) { } throw error; }
        }
        @Override public Socket createSocket() throws IOException { return base.createSocket(); }
        @Override public Socket createSocket(String host, int port) throws IOException { return open(HostPolicy.resolveLocal(host), port, null, 0); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return open(HostPolicy.resolveLocal(host), port, local, localPort); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return open(host, port, null, 0); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return open(host, port, local, localPort); }
    }
}
