package pl.folderlan.app;

import java.io.IOException;
import java.net.InetAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostPolicyTest {
    private static boolean allowed(String address) throws Exception { return HostPolicy.isLocalAddress(InetAddress.getByName(address)); }
    @Test public void privateLanRangesAllowed() throws Exception {
        for (String address : new String[]{"10.0.0.1","172.16.0.1","172.31.255.254","192.168.1.2","169.254.10.2","fd12::1234","fe80::1234"}) {
            assertTrue(address, allowed(address));
        }
    }
    @Test public void publicInternetAndBoundaryRangesRejected() throws Exception {
        for (String address : new String[]{"8.8.8.8","1.1.1.1","172.15.255.255","172.32.0.1","192.169.0.1","2001:4860:4860::8888"}) {
            assertFalse(address, allowed(address));
        }
    }
    @Test public void loopbackMulticastAndUnspecifiedRejected() throws Exception {
        for (String address : new String[]{"127.0.0.1","0.0.0.0","224.0.0.1","::1","::","ff02::1"}) {
            assertFalse(address, allowed(address));
        }
    }
    @Test public void serverFieldDoesNotAcceptUrisCredentialsPortsOrPaths() {
        for (String host : new String[]{"smb://computer/share","user@computer","computer/share","computer\\share","computer:445","a b",""}) {
            assertThrows(host, IllegalArgumentException.class, () -> HostPolicy.cleanHost(host));
        }
        assertEquals("fd12::1", HostPolicy.cleanHost("[fd12::1]"));
        assertEquals("DESKTOP-ROG.local", HostPolicy.cleanHost(" DESKTOP-ROG.local "));
    }
    @Test public void dnsResultStillMustBeLocal() throws Exception {
        assertEquals("192.168.1.2", HostPolicy.resolveLocal("192.168.1.2").getHostAddress());
        assertThrows(IOException.class, () -> HostPolicy.resolveLocal("127.0.0.1"));
        assertThrows(IOException.class, () -> HostPolicy.resolveLocal("8.8.8.8"));
    }
}
