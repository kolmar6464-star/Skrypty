package pl.folderlan.app;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class DiscoveryProtocolTest {
    private static String json(String host, String share) {
        return "{\"protocol\":\"folderlan-android-v1\",\"type\":\"host\",\"name\":\"KOMPUTER\",\"host\":\""+host+"\",\"share\":\""+share+"\"}";
    }
    @Test public void validLocalAdvertisementUsesPacketSource() {
        LanDiscovery.Host host=LanDiscovery.parseResponse(json("192.168.1.9","FolderUdostepnionyLAN"),"192.168.1.9");
        assertNotNull(host); assertEquals("192.168.1.9",host.address); assertEquals("FolderUdostepnionyLAN",host.share);
    }
    @Test public void publicOrRedirectedAdvertisementsAreRejected() {
        assertNull(LanDiscovery.parseResponse(json("8.8.8.8","share"),"8.8.8.8"));
        assertNull(LanDiscovery.parseResponse(json("192.168.1.10","share"),"192.168.1.9"));
        assertNull(LanDiscovery.parseResponse(json("192.168.1.9","../secret"),"192.168.1.9"));
    }
    @Test public void unknownOriginalProtocolIsNotGuessed() {
        assertNull(LanDiscovery.parseResponse("{\"protocol\":\"folderlan\",\"type\":\"host\"}","192.168.1.9"));
        assertNull(LanDiscovery.parseResponse("FolderUdostepnionyLAN|KOMPUTER","192.168.1.9"));
        assertNull(LanDiscovery.parseResponse("{", "192.168.1.9"));
    }
    @Test public void advertisementsWithControlCharactersAreRejected() {
        assertNull(LanDiscovery.parseResponse(json("192.168.1.9","share").replace("KOMPUTER","PC\\n\\nEVIL"),"192.168.1.9"));
    }
    @Test public void localIpv4RangeAndSubnetBoundariesAreChecked() {
        assertTrue(LanDiscovery.isLiteralLocalIpv4("10.0.0.1"));
        assertTrue(LanDiscovery.isLiteralLocalIpv4("172.16.0.1"));
        assertFalse(LanDiscovery.isLiteralLocalIpv4("172.32.0.1"));
        assertFalse(LanDiscovery.isLiteralLocalIpv4("192.168.0.999"));
        assertFalse(LanDiscovery.isLiteralLocalIpv4("localhost"));
        assertFalse(LanDiscovery.isLiteralLocalIpv4("127.0.0.1"));
        assertTrue(LanDiscovery.sameSubnet(new byte[]{(byte)192,(byte)168,1,9},new byte[]{(byte)192,(byte)168,1,1},24));
        assertFalse(LanDiscovery.sameSubnet(new byte[]{(byte)192,(byte)168,2,9},new byte[]{(byte)192,(byte)168,1,1},24));
    }
    @Test public void netbiosQueryHasProperWildcardNameAndStatusType() {
        byte[] query=LanDiscovery.netbiosQuery(0x1234);
        assertEquals(50,query.length);assertEquals(0x12,query[0]);assertEquals(0x34,query[1]);
        assertEquals('C',query[13]);assertEquals('K',query[14]);assertEquals(0x21,query[47]);
    }
    private static byte[] nbResponse(int transaction) {
        byte[] bytes=new byte[43];bytes[0]=(byte)(transaction>>8);bytes[1]=(byte)transaction;bytes[2]=(byte)0x84;bytes[7]=1;
        bytes[12]=0;bytes[14]=0x21;bytes[16]=1;bytes[22]=20; // root name, NBSTAT, IN, rdlength=20
        bytes[23]=1;byte[] name="PC-SMB         ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name,0,bytes,24,15);bytes[39]=0x20; return bytes;
    }
    @Test public void originalWindowsChallengeIsVerifiedWithoutEmbeddedSecrets() throws Exception {
        String nonce="00112233445566778899aabbccddeeff";
        char[] testPassword="synthetic-test-password-not-a-credential".toCharArray();
        String proof=LanDiscovery.legacyProof(nonce,"TEST-PC",testPassword);
        assertEquals(64,proof.length());
        LanDiscovery.Host candidate=LanDiscovery.parseLegacyResponse("FOLDERLAN1|TEST-PC|"+proof,"192.168.1.2",nonce,null);
        assertNotNull(candidate);assertTrue(candidate.source.contains("do parowania"));
        LanDiscovery.Host verified=LanDiscovery.parseLegacyResponse("FOLDERLAN1|TEST-PC|"+proof,"192.168.1.2",nonce,testPassword);
        assertNotNull(verified);assertTrue(verified.source.contains("potwierdzony"));
        try{LanDiscovery.parseLegacyResponse("FOLDERLAN1|TEST-PC|"+proof,"192.168.1.2",nonce,"wrong".toCharArray());fail("wrong password accepted");}catch(java.io.IOException expected){}
        assertNull(LanDiscovery.parseLegacyResponse("FOLDERLAN1|TEST-PC|"+proof,"8.8.8.8",nonce,testPassword));
        assertNull(LanDiscovery.parseLegacyResponse("FOLDERLAN1|TEST-PC|"+proof,"192.168.1.2","invalid",testPassword));
    }
    @Test public void originalWindowsProofMatchesIndependentKnownVector() throws Exception {
        assertEquals("1acc94270c8efa55333c6b7d63b18f743e10ba0a258d77d7d7a74ac8a39fd623",LanDiscovery.legacyProof("00112233445566778899aabbccddeeff","TEST-PC","synthetic-test-password-not-a-credential".toCharArray()));
    }
    @Test public void netbiosServerNameIsParsedWithBoundsAndTransactionChecks() {
        byte[] bytes=nbResponse(0x1234);
        assertEquals("PC-SMB",LanDiscovery.parseNetbiosName(bytes,0x1234));
        assertNull(LanDiscovery.parseNetbiosName(bytes,0x5678));
        assertNull(LanDiscovery.parseNetbiosName(new byte[3],0x1234));
        bytes[23]=100;assertNull(LanDiscovery.parseNetbiosName(bytes,0x1234));
    }
}
