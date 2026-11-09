package pl.folderlan.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.CharBuffer;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, user-triggered discovery on the active Wi-Fi network. No credentials leave the phone. */
public final class LanDiscovery {
    public static final int PORT = 47292;
    public static final int LEGACY_TCP_PORT = 47291;
    public static final String PROTOCOL = "folderlan-android-v1";
    public static final String DEFAULT_SHARE = "FolderUdostepnionyLAN";
    private static final long SCAN_MILLIS = 4500L;
    private final Context context;

    public LanDiscovery(Context context) { this.context = context.getApplicationContext(); }

    public static final class Host {
        public final String name;
        public final String address;
        public final String source;
        public final String share;
        public Host(String name, String address, String source) { this(name, address, source, DEFAULT_SHARE); }
        public Host(String name, String address, String source, String share) {
            this.name = name; this.address = address; this.source = source; this.share = share;
        }
        @Override public String toString() { return name + " — " + address + " (" + source + ")"; }
    }
    public interface Listener {
        default void onHost(Host host) { }
        default void onStatus(String status) { }
    }
    public List<Host> discover() throws IOException { return discover(new Listener() { }); }

    /** Select Wi-Fi even when Android keeps cellular as its Internet/default network.
     * A local-only Wi-Fi LAN does not need NET_CAPABILITY_VALIDATED or Internet access. */
    public static Network findWifiNetwork(Context context) {
        ConnectivityManager cm=(ConnectivityManager)context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return null;
        Network active=cm.getActiveNetwork();
        if(isUsableWifi(cm,active))return active;
        for(Network candidate:cm.getAllNetworks())if(isUsableWifi(cm,candidate))return candidate;
        return null;
    }
    private static boolean isUsableWifi(ConnectivityManager cm,Network network) {
        if(network==null)return false;
        NetworkCapabilities capabilities=cm.getNetworkCapabilities(network);
        if(capabilities==null||!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return false;
        LinkProperties properties=cm.getLinkProperties(network);
        if(properties==null)return false;
        for(LinkAddress link:properties.getLinkAddresses())
            if(link.getAddress() instanceof Inet4Address && isLocalAddress(link.getAddress().getAddress()))return true;
        return false;
    }

    /** Call on a background thread. Callbacks are on that thread or Android's NSD thread. */
    public List<Host> discover(Listener listener) throws IOException {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = findWifiNetwork(context);
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            throw new IOException("Połącz telefon z tą samą siecią Wi-Fi co komputer. Internet mobilny nie jest obsługiwany.");
        LinkProperties properties = cm.getLinkProperties(network);
        List<LinkAddress> links = new ArrayList<>();
        if (properties != null) for (LinkAddress link : properties.getLinkAddresses())
            if (link.getAddress() instanceof Inet4Address && isLocalAddress(link.getAddress().getAddress())) links.add(link);
        if (links.isEmpty()) throw new IOException("Wi-Fi nie ma lokalnego adresu IPv4. Wpisz nazwę komputera lub jego lokalne IP ręcznie.");
        final Map<String, Host> found = new LinkedHashMap<>();
        WifiManager.MulticastLock lock = null;
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        NsdManager.DiscoveryListener nsdListener = null;
        final boolean[] active = {true};
        final Object resultLock = new Object();
        ExecutorService scanners = Executors.newFixedThreadPool(24);
        List<Future<?>> scans = new ArrayList<>();
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                try { lock = wifi.createMulticastLock("FolderLAN-discovery"); lock.setReferenceCounted(false); lock.acquire(); }
                catch (RuntimeException ignored) { listener.onStatus("Wykrywanie rozgłoszeniowe ograniczone; można wpisać IP ręcznie."); }
            }
            if (nsd != null) {
                nsdListener = new NsdManager.DiscoveryListener() {
                    @Override public void onDiscoveryStarted(String type) { }
                    @Override public void onServiceFound(NsdServiceInfo service) {
                        if (!service.getServiceType().contains("_smb._tcp")) return;
                        try {
                            nsd.resolveService(service, new NsdManager.ResolveListener() {
                                @Override public void onResolveFailed(NsdServiceInfo info, int code) { }
                                @Override public void onServiceResolved(NsdServiceInfo info) {
                                    InetAddress address = info.getHost();
                                    if (address == null || info.getPort() != 445 || !onLinks(address, links)) return;
                                    synchronized (resultLock) {
                                        if (active[0]) add(found, new Host(cleanName(info.getServiceName()), address.getHostAddress(), "SMB / mDNS"), listener);
                                    }
                                }
                            });
                        } catch (RuntimeException ignored) { /* A manual address remains available. */ }
                    }
                    @Override public void onServiceLost(NsdServiceInfo service) { }
                    @Override public void onDiscoveryStopped(String type) { }
                    @Override public void onStartDiscoveryFailed(String type, int code) { }
                    @Override public void onStopDiscoveryFailed(String type, int code) { }
                };
                try { nsd.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, nsdListener); }
                catch (RuntimeException ignored) { nsdListener = null; }
            }
            listener.onStatus("Szukam komputerów: Folder LAN TCP47291, NetBIOS i SMB/mDNS…");
            // The original Windows script probes only its local /24. Keep the same bounded scope.
            List<String> candidates = scanCandidates(links);
            for (String candidate : candidates) scans.add(scanners.submit(() -> {
                try {
                    Host host = queryLegacy(network, candidate, null);
                    if (host != null) synchronized (resultLock) { if (active[0]) add(found, host, listener); }
                } catch (IOException ignored) { }
            }));
            try (DatagramSocket custom = new DatagramSocket(); DatagramSocket netbios = new DatagramSocket()) {
                network.bindSocket(custom); network.bindSocket(netbios);
                custom.setBroadcast(true); netbios.setBroadcast(true);
                custom.setSoTimeout(100); netbios.setSoTimeout(100);
                int transaction = new SecureRandom().nextInt(65536);
                byte[] customQuery = ("{\"protocol\":\"" + PROTOCOL + "\",\"type\":\"discover\"}").getBytes(StandardCharsets.UTF_8);
                byte[] nbQuery = netbiosQuery(transaction);
                for (LinkAddress link : links) {
                    InetAddress broadcast = broadcast(link.getAddress(), link.getPrefixLength());
                    if (broadcast != null) {
                        send(custom, customQuery, broadcast, PORT);
                        send(netbios, nbQuery, broadcast, 137);
                    }
                }
                long deadline = System.currentTimeMillis() + SCAN_MILLIS;
                while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                    receive(custom, (packet) -> {
                        if (!onLinks(packet.getAddress(), links)) return;
                        Host host = parseResponse(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8), packet.getAddress().getHostAddress());
                        if (host != null) synchronized (resultLock) { add(found, host, listener); }
                    });
                    receive(netbios, (packet) -> {
                        if (!onLinks(packet.getAddress(), links)) return;
                        String name = parseNetbiosName(Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()), transaction);
                        if (name != null) synchronized (resultLock) { add(found, new Host(name, packet.getAddress().getHostAddress(), "SMB / NetBIOS"), listener); }
                    });
                }
            }
        } finally {
            synchronized (resultLock) { active[0] = false; }
            for (Future<?> scan : scans) scan.cancel(true);
            scanners.shutdownNow();
            if (nsd != null && nsdListener != null) try { nsd.stopServiceDiscovery(nsdListener); } catch (RuntimeException ignored) { }
            if (lock != null && lock.isHeld()) lock.release();
        }
        synchronized (resultLock) { return new ArrayList<>(found.values()); }
    }


    /** Recheck the original Windows HMAC challenge after the user enters the SMB password.
     * Never put the password into an advertisement, source file, or log. */
    public Host verifyLegacyHost(String address, char[] password) throws IOException {
        if (password == null || password.length == 0) throw new IOException("Wpisz hasło konta SMB do jednorazowego parowania.");
        if (!isLiteralLocalIpv4(address)) throw new IOException("Potwierdzenie komputera wymaga lokalnego IPv4.");
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network=findWifiNetwork(context);
        NetworkCapabilities capabilities=network==null?null:cm.getNetworkCapabilities(network);
        if(capabilities==null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) throw new IOException("Połącz telefon z lokalnym Wi-Fi.");
        LinkProperties properties=cm.getLinkProperties(network);
        if(properties==null || !onLinks(InetAddress.getByName(address),properties.getLinkAddresses())) throw new IOException("Komputer nie znajduje się w lokalnej podsieci Wi-Fi.");
        Host host=queryLegacy(network,address,password);
        if(host==null) throw new IOException("Nie potwierdzono serwera Folder LAN. Sprawdź adres, hasło i działanie oryginalnego skryptu Windows.");
        return host;
    }
    private static List<String> scanCandidates(List<LinkAddress> links) {
        List<String> candidates=new ArrayList<>();
        for(LinkAddress link:links) {
            byte[] self=link.getAddress().getAddress();
            if(self.length!=4)continue;
            // Never expand beyond one /24 per Wi-Fi IPv4 address; also respect narrower prefixes.
            for(int last=1;last<=254;last++) {
                byte[] value=self.clone();value[3]=(byte)last;
                if(Arrays.equals(value,self)||!sameSubnet(self,value,link.getPrefixLength()))continue;
                String candidate=(value[0]&255)+"."+(value[1]&255)+"."+(value[2]&255)+"."+(value[3]&255);
                if(!candidates.contains(candidate))candidates.add(candidate);
            }
            if(candidates.size()>=508)break;
        }
        return candidates;
    }
    private static Host queryLegacy(Network network,String address,char[] password) throws IOException {
        String nonce=hex(randomBytes(16));
        try(Socket socket=network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(address,LEGACY_TCP_PORT),220);
            socket.setSoTimeout(600);
            OutputStream output=socket.getOutputStream();
            output.write(("FOLDERLAN1|"+nonce+"\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();
            InputStream input=socket.getInputStream();
            byte[] buffer=new byte[256];int count=0;
            while(count<buffer.length) {int ch=input.read();if(ch<0||ch=='\n')break;if(ch!='\r')buffer[count++]=(byte)ch;}
            if(count==buffer.length)return null;
            String line=new String(buffer,0,count,StandardCharsets.US_ASCII);
            return parseLegacyResponse(line,address,nonce,password);
        }
    }
    static Host parseLegacyResponse(String line,String address,String nonce,char[] password) throws IOException {
        if(!isLiteralLocalIpv4(address)||line==null||!nonce.matches("[0-9a-f]{32}")||!line.matches("FOLDERLAN1\\|[a-zA-Z0-9-]{1,63}\\|[0-9a-f]{64}"))return null;
        String[] fields=line.split("\\|");
        if(password!=null) {
            String expected=legacyProof(nonce,fields[1],password);
            if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),fields[2].getBytes(StandardCharsets.US_ASCII)))throw new IOException("Nieprawidłowe hasło lub komputer nie należy do sparowanej grupy Folder LAN.");
        }
        return new Host(fields[1],address,password==null?"Folder LAN / TCP47291 (do parowania)":"Folder LAN / TCP47291 (potwierdzony)");
    }
    static String legacyProof(String nonce,String name,char[] password) throws IOException {
        ByteBuffer encoded=StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] key=new byte[encoded.remaining()];encoded.get(key);
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            String tag=hex(mac.doFinal("FOLDERLAN-GRUPA-2026".getBytes(StandardCharsets.UTF_8))).substring(0,20);
            return hex(mac.doFinal((nonce+"|"+name+"|"+tag).getBytes(StandardCharsets.UTF_8)));
        }catch(Exception error){throw new IOException("Nie można potwierdzić komputera Folder LAN.",error);}
        finally{Arrays.fill(key,(byte)0);if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);}
    }
    private static byte[] randomBytes(int length){byte[]bytes=new byte[length];new SecureRandom().nextBytes(bytes);return bytes;}
    private static String hex(byte[] value){StringBuilder result=new StringBuilder(value.length*2);for(byte b:value)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}

    private interface PacketReader { void read(DatagramPacket packet); }
    private static void receive(DatagramSocket socket, PacketReader reader) throws IOException {
        byte[] buffer = new byte[1500];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try { socket.receive(packet); reader.read(packet); } catch (SocketTimeoutException ignored) { }
    }
    private static void send(DatagramSocket socket, byte[] data, InetAddress host, int port) {
        try { socket.send(new DatagramPacket(data, data.length, host, port)); } catch (IOException ignored) { }
    }
    private static void add(Map<String, Host> found, Host host, Listener listener) {
        Host previous = found.get(host.address);
        if (previous == null || host.source.contains("TCP47291")) { found.put(host.address, host); listener.onHost(host); }
    }
    static Host parseResponse(String text, String sender) {
        try {
            if (text == null || text.length() > 1400 || !isLiteralLocalIpv4(sender)) return null;
            JSONObject object = new JSONObject(text);
            if (!PROTOCOL.equals(object.optString("protocol")) || !"host".equals(object.optString("type"))) return null;
            if (!sender.equals(object.optString("host"))) return null;
            String share = object.optString("share");
            if (!share.matches("[A-Za-z0-9 _.$-]{1,80}") || share.equals(".") || share.equals("..")) return null;
            String name = object.optString("name");
            if (name.length() == 0 || name.length() > 80 || !name.matches("[^\\x00-\\x1f\\x7f]{1,80}")) return null;
            return new Host(name, sender, "Folder LAN", share);
        } catch (Exception ignored) { return null; }
    }
    private static String cleanName(String name) { return name == null ? "Komputer SMB" : name.replaceAll("[\\p{Cntrl}]", "").substring(0, Math.min(name.replaceAll("[\\p{Cntrl}]", "").length(), 80)); }
    static boolean isLiteralLocalIpv4(String text) {
        if (text == null || !text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = text.split("\\."); byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) { int value = Integer.parseInt(parts[i]); if (value > 255) return false; bytes[i] = (byte)value; }
        return isLocalAddress(bytes);
    }
    static boolean isLocalAddress(byte[] bytes) {
        if (bytes.length != 4) return false;
        int a = bytes[0] & 255, b = bytes[1] & 255;
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254);
    }
    private static boolean onLinks(InetAddress address, List<LinkAddress> links) {
        byte[] value = address.getAddress(); if (!isLocalAddress(value)) return false;
        for (LinkAddress link : links) if (sameSubnet(value, link.getAddress().getAddress(), link.getPrefixLength())) return true;
        return false;
    }
    static boolean sameSubnet(byte[] a, byte[] b, int prefix) {
        if (a.length != 4 || b.length != 4 || prefix < 0 || prefix > 32) return false;
        for (int i = 0; i < 4; i++) { int bits = Math.min(8, Math.max(0, prefix - i * 8)); int mask = bits == 0 ? 0 : (255 << (8 - bits)) & 255; if ((a[i] & mask) != (b[i] & mask)) return false; }
        return true;
    }
    private static InetAddress broadcast(InetAddress host, int prefix) {
        if (prefix < 1 || prefix > 30) return null;
        byte[] bytes = host.getAddress().clone();
        for (int i = 0; i < 4; i++) { int bits = Math.min(8, Math.max(0, prefix - i * 8)); int mask = bits == 0 ? 0 : (255 << (8 - bits)) & 255; bytes[i] = (byte)((bytes[i] & mask) | (~mask & 255)); }
        try { return InetAddress.getByAddress(bytes); } catch (Exception ignored) { return null; }
    }
    static byte[] netbiosQuery(int transaction) {
        byte[] query = new byte[50]; query[0] = (byte)(transaction >>> 8); query[1] = (byte)transaction;
        query[3] = 0x10; query[5] = 1; query[12] = 32;
        byte[] name = new byte[16]; name[0] = '*';
        for (int i = 0; i < 16; i++) { query[13 + i*2] = (byte)('A' + ((name[i] & 255) >>> 4)); query[14 + i*2] = (byte)('A' + (name[i] & 15)); }
        query[47] = 0x21; query[49] = 1; return query;
    }
    private static int unsigned16(byte[] bytes, int offset) { return ((bytes[offset]&255)<<8)|(bytes[offset+1]&255); }
    private static int skipDnsName(byte[] bytes, int offset) {
        int labels = 0;
        while (offset < bytes.length && labels++ < 128) {
            int count = bytes[offset++] & 255; if (count == 0) return offset;
            if ((count & 0xc0) == 0xc0) return offset < bytes.length ? offset + 1 : -1;
            if ((count & 0xc0) != 0 || count > 63 || offset + count > bytes.length) return -1;
            offset += count;
        }
        return -1;
    }
    static String parseNetbiosName(byte[] bytes, int transaction) {
        if (bytes.length < 12 || unsigned16(bytes,0) != transaction || (bytes[2]&128) == 0 || (bytes[3]&15) != 0) return null;
        int qd = unsigned16(bytes,4), answers = unsigned16(bytes,6), offset = 12;
        if (qd > 8 || answers > 8) return null;
        for (int i=0; i<qd; i++) { offset = skipDnsName(bytes,offset); if (offset < 0 || offset+4 > bytes.length) return null; offset += 4; }
        for (int answer=0; answer<answers; answer++) {
            offset=skipDnsName(bytes,offset); if(offset<0 || offset+10>bytes.length) return null;
            int type=unsigned16(bytes,offset), length=unsigned16(bytes,offset+8); offset+=10;
            if (offset+length>bytes.length) return null;
            if (type==0x21 && length>0) {
                int count=bytes[offset]&255; if(1+count*18>length) return null;
                String workstation=null;
                for(int i=0;i<count;i++) {
                    int start=offset+1+i*18; int suffix=bytes[start+15]&255; int flags=unsigned16(bytes,start+16);
                    String name=new String(bytes,start,15,StandardCharsets.US_ASCII).trim();
                    if((flags&0x8000)!=0 || !name.matches("[A-Za-z0-9_-]{1,15}")) continue;
                    if(suffix==0x20) return name;
                    if(suffix==0x00) workstation=name;
                }
                if(workstation!=null) return workstation;
            }
            offset+=length;
        }
        return null;
    }
}
