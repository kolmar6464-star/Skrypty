package pl.folderlan.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.SocketFactory;
import org.junit.Test;
import static org.junit.Assert.*;

/** Verify a caller-selected Android Network socket factory is preserved without touching the network. */
public class SmbSocketRoutingTest {
    private static final class CaptureFactory extends SocketFactory {
        int created;
        int connectTimeout;
        InetSocketAddress destination;
        boolean closed;
        @Override public Socket createSocket() {
            created++;
            return new Socket() {
                @Override public void connect(SocketAddress address, int timeout) throws IOException {
                    destination = (InetSocketAddress) address;
                    connectTimeout = timeout;
                    throw new IOException("Test transport deliberately stopped before sending data");
                }
                @Override public synchronized void close() { closed = true; }
            };
        }
        @Override public Socket createSocket(String host, int port) { throw new AssertionError("Expected bound unconnected socket"); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { throw new AssertionError("Unexpected socket factory overload"); }
        @Override public Socket createSocket(InetAddress host, int port) { throw new AssertionError("Unexpected socket factory overload"); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { throw new AssertionError("Unexpected socket factory overload"); }
    }

    @Test public void callerSelectedNetworkSocketIsUsedWithPinnedLanAddressAndTimeout() throws Exception {
        CaptureFactory factory = new CaptureFactory();
        try (SmbRepository repository = new SmbRepository(factory)) {
            assertThrows(IOException.class, () -> repository.connect(new SmbRepository.Credentials("192.168.22.7", "share", "fixture-user", "", "fixture-value")));
            assertEquals(1, factory.created);
            assertNotNull(factory.destination);
            assertEquals("192.168.22.7", factory.destination.getAddress().getHostAddress());
            assertEquals(445, factory.destination.getPort());
            assertEquals(8000, factory.connectTimeout);
            assertTrue(factory.closed);
        }
    }

    @Test public void publicDestinationRejectedBeforeCreatingCallerNetworkSocket() throws Exception {
        CaptureFactory factory = new CaptureFactory();
        try (SmbRepository repository = new SmbRepository(factory)) {
            assertThrows(IOException.class, () -> repository.connect(new SmbRepository.Credentials("8.8.8.8", "share", "fixture-user", "", "fixture-value")));
            assertEquals(0, factory.created);
        }
    }
}
