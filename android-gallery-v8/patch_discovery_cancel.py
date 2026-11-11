from pathlib import Path
p = Path("app/src/main/java/pl/folderlan/android/LanShare.java")
s = p.read_text(encoding="utf-8")
def sub(old, new):
    global s
    if old not in s: raise RuntimeError("LanShare missing anchor: "+old[:100])
    s = s.replace(old, new, 1)
sub("import java.net.InetAddress;\n", """import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import android.content.SharedPreferences;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collection;
""")
sub("    private Host host;\n", """    private Host host;
    private volatile Thread activeUploadThread;
    private volatile String incompleteUploadPath;
    private volatile boolean abortRequested;
""")
start = s.index("    Host discover(Context ctx) throws Exception {")
end = s.index("    String hostLabel() {", start)
s = s[:start] + """
    private Host verifiedReply(DatagramPacket packet, String nonce) throws Exception {
        if (packet.getLength() > 256 || packet.getPort() != PORT) return null;
        String reply = new String(packet.getData(), packet.getOffset(),
            packet.getLength(), StandardCharsets.US_ASCII);
        String[] parts = reply.split("\\\\|", -1);
        if (parts.length != 7 || !"FL1A".equals(parts[0])
                || !KIT.equals(parts[1]) || !nonce.equals(parts[2])
                || !parts[3].matches("[0-9a-f]{32}")
                || !packet.getAddress().getHostAddress().equals(parts[5])) return null;
        String signed = String.join("|", parts[0], parts[1], parts[2],
                                  parts[3], parts[4], parts[5]);
        if (!MessageDigest.isEqual(sign(signed).getBytes(StandardCharsets.US_ASCII),
                parts[6].getBytes(StandardCharsets.US_ASCII))) return null;
        String hostName = new String(Base64.decode(parts[4], Base64.DEFAULT),
                                     StandardCharsets.UTF_8);
        if (!hostName.matches("[\\\\p{L}\\\\p{N}_.-]{1,15}")) return null;
        return new Host(parts[3], hostName, parts[5]);
    }

    private static int asInt(byte[] b) {
        return ((b[0] & 255) << 24) | ((b[1] & 255) << 16)
             | ((b[2] & 255) << 8) | (b[3] & 255);
    }
    private static String formatIp(int ip) {
        return ((ip >>> 24) & 255)+"."+((ip >>> 16)&255)+"."
              +((ip >>> 8)&255)+"."+(ip&255);
    }
    private Set<String> subnetTargets(Context ctx) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> enumerated = NetworkInterface.getNetworkInterfaces();
            while (enumerated != null && enumerated.hasMoreElements()) {
                NetworkInterface device = enumerated.nextElement();
                String interfaceName = device.getName().toLowerCase(Locale.ROOT);
                if (!(interfaceName.startsWith("wlan") || interfaceName.startsWith("eth")
                        || interfaceName.startsWith("ap") || interfaceName.startsWith("swlan"))) continue;
                if (!device.isUp() || device.isLoopback()) continue;
                for (InterfaceAddress address : device.getInterfaceAddresses()) {
                    if (!(address.getAddress() instanceof Inet4Address)) continue;
                    int ip = asInt(address.getAddress().getAddress());
                    int prefix = address.getNetworkPrefixLength();
                    if (prefix < 8 || prefix > 30) continue;
                    int bits = Math.max(24, prefix);
                    int mask = -1 << (32 - bits);
                    int network = ip & mask;
                    // Never scan outside the directly connected /24 (or smaller) Wi-Fi subnet.
                    int hostCount = 1 << (32 - bits);
                    InetAddress broad = address.getBroadcast();
                    if (broad != null) all.add(broad.getHostAddress());
                    for (int last = 1; last < hostCount - 1; last++) {
                        int target = network + last;
                        if (target != ip) all.add(formatIp(target));
                    }
                }
            }
        } catch (Exception ignored) { }
        try {
            WifiManager manager = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = manager == null ? null : manager.getDhcpInfo();
            if (dhcp != null) {
                int gateway = Integer.reverseBytes(dhcp.gateway);
                if (gateway != 0) all.add(formatIp(gateway));
            }
        } catch (Exception ignored) {}
        return all;
    }

    private Host collectReplies(DatagramSocket socket, String nonce,
                                long durationMs) throws Exception {
        long deadline = System.currentTimeMillis() + durationMs;
        Host found = null;
        byte[] received = new byte[512];
        while (System.currentTimeMillis() < deadline) {
            try {
                DatagramPacket packet = new DatagramPacket(received, received.length);
                socket.receive(packet);
                Host candidate = verifiedReply(packet, nonce);
                if (candidate == null) continue;
                if (found != null && !found.id.equals(candidate.id))
                    throw new Exception("Wykryto kilka komputerów głównych. Wybierz właściwy komputer.");
                found = candidate;
                // Valid signature and host identity are stronger than port probing.
                return found;
            } catch (java.net.SocketTimeoutException ignored) {}
        }
        return found;
    }

    Host discover(Context ctx) throws Exception {
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        StringBuilder nonce = new StringBuilder(32);
        for (byte b : nonceBytes) nonce.append(String.format(Locale.ROOT,"%02x",b&255));
        String q = "FL1Q|" + KIT + "|" + nonce;
        byte[] request = (q + "|" + sign(q)).getBytes(StandardCharsets.US_ASCII);
        LinkedHashSet<String> broadcasts = new LinkedHashSet<>();
        broadcasts.add(broadcastIP(ctx));
        broadcasts.add("255.255.255.255");
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(200);
            for (int i=0;i<3;i++) {
                for (String address : broadcasts) {
                    try {
                        InetAddress ip=InetAddress.getByName(address);
                        socket.send(new DatagramPacket(request,request.length,ip,PORT));
                    } catch (Exception ignored) {}
                }
                Host host=collectReplies(socket,nonce.toString(),450);
                if (host != null) return host;
            }
            // Android broadcast delivery may be filtered by router/AP isolation.
            // Probe up to one on-link /24 directly with the same signed discovery query.
            Set<String> targets=subnetTargets(ctx);
            int sent=0;
            for (String target : targets) {
                if ("255.255.255.255".equals(target)) continue;
                try {
                    InetAddress address=InetAddress.getByName(target);
                    socket.send(new DatagramPacket(request,request.length,address,PORT));
                } catch (Exception ignored) {}
                if (++sent % 45 == 0) {
                    Host host=collectReplies(socket,nonce.toString(),150);
                    if (host != null) return host;
                }
            }
            Host host=collectReplies(socket,nonce.toString(),1250);
            if (host != null) return host;
        }
        throw new Exception("Nie wykryto serwera. Telefon i komputer muszą być w tej samej sieci "
            +"(bez izolacji Wi-Fi). Na komputerze sprawdź zadanie FolderLAN i zaporę UDP 34792.");
    }

    private Host lastVerifiedHost(Context ctx) {
        SharedPreferences prefs=ctx.getSharedPreferences("folderlan_last_pc", Context.MODE_PRIVATE);
        String id=prefs.getString("id","");
        String name=prefs.getString("name","");
        String ip=prefs.getString("ip","");
        if (!id.matches("[0-9a-f]{32}")
                || !name.matches("[\\\\p{L}\\\\p{N}_.-]{1,15}")
                || !ip.matches("(?:[0-9]{1,3}\\\\.){3}[0-9]{1,3}")) return null;
        try {
            InetAddress parsed=InetAddress.getByName(ip);
            if (!(parsed instanceof Inet4Address)) return null;
            for (byte part : parsed.getAddress()) if (true && (part & 255)>255) return null;
        } catch (Exception e) { return null; }
        return new Host(id,name,ip);
    }

    void connect(Context ctx) throws Exception {
        Exception previous=null;
        Host recent=lastVerifiedHost(ctx);
        if (recent != null) {
            try {
                connectKnown(recent);
                return;
            } catch (Exception e) {
                previous=e;
            }
        }
        try {
            Host discovered=discover(ctx);
            connectKnown(discovered);
            ctx.getSharedPreferences("folderlan_last_pc",Context.MODE_PRIVATE).edit()
                .putString("ip",discovered.ip).putString("name",discovered.name)
                .putString("id",discovered.id).apply();
        } catch (Exception e) {
            if (previous != null)
                throw new Exception(e.getMessage()+" Ostatni komputer również nie odpowiada: "
                    +previous.getClass().getSimpleName(),e);
            throw e;
        }
    }

    private void connectKnown(Host target) throws Exception {
        if (abortRequested) throw new InterruptedIOException("Transfer anulowany");
        this.host=target;
        SmbConfig config=SmbConfig.builder().withEncryptData(true).build();
        this.client=new SMBClient(config);
        try {
            this.connection=client.connect(target.ip);
            if (abortRequested) throw new InterruptedIOException("Transfer anulowany");
            String password="Aa1!"+sign("smb-password|"+target.id);
            this.session=connection.authenticate(
                new AuthenticationContext(ACCOUNT,password.toCharArray(),target.name));
            if (abortRequested) throw new InterruptedIOException("Transfer anulowany");
            this.share=(DiskShare)session.connectShare(SHARE);
            this.share.list("");
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    /** Dedicated SMB connection: aborting an upload must not close the browser connection. */
    LanShare separateUploadConnection() throws Exception {
        if (host == null) throw new IllegalStateException("Najpierw połącz się z komputerem");
        LanShare uploader = new LanShare(
            Base64.encodeToString(key,Base64.NO_WRAP));
        uploader.host=host;
        return uploader;
    }
    void connectUpload() throws Exception { connectKnown(host); }
    String incompleteUploadPath() { return incompleteUploadPath; }
    void cleanupIncompleteUpload(String path) {
        if (path == null || path.isEmpty() || share == null) return;
        try { share.rm(path); } catch (Exception ignored) {}
    }
    /** Close the transfer connection in a worker to abort blocking SMB writes. */
    void abortActiveUpload() {
        abortRequested = true;
        Thread t = activeUploadThread;
        if (t != null) t.interrupt();
        final Connection activeConnection=connection;
        final SMBClient activeClient=client;
        Thread disruptor=new Thread(() -> {
            try { if (activeConnection != null) activeConnection.close(); }
            catch (Exception ignored) {}
            try { if (activeClient != null) activeClient.close(); }
            catch (Exception ignored) {}
        }, "folderlan-cancel-smb");
        disruptor.setDaemon(true);
        disruptor.start();
    }
""" + s[end:]
# Hook into upload method from v4 patch
sub("        boolean completed = false;\n        boolean created = false;\n",
"""        boolean completed = false;
        boolean created = false;
        activeUploadThread=Thread.currentThread();
""")
sub("                created = true;\n                try (OutputStream out = file.getOutputStream()) {",
"""                created = true;
                incompleteUploadPath=path;
                try (OutputStream out = file.getOutputStream()) {""")
sub("            completed = true;\n        } finally {\n            // Interrupted files",
"""            completed = true;
            incompleteUploadPath=null;
        } finally {
            activeUploadThread=null;
            // Interrupted files""")
# No incomplete cleanup if connection broken; parent will clean using its separate connection.
p.write_text(s, encoding="utf-8")

p=Path("app/src/main/java/pl/folderlan/android/MainActivity.java")
s=p.read_text(encoding="utf-8")
def sub2(old,new):
    global s
    if old not in s: raise RuntimeError("MainActivity missing anchor: "+old[:100])
    s=s.replace(old,new,1)
sub2("    private LanShare remote;\n",
"""    private LanShare remote;
    private volatile LanShare activeUploadSession;
""")
sub2("            @Override public void onCancel() { stopGalleryUpload.set(true); }",
"""            @Override public void onCancel() {
                stopGalleryUpload.set(true);
                LanShare current=activeUploadSession;
                if (current!=null) current.abortActiveUpload();
            }""")
sub2("        executor.execute(() -> {\n            final ArrayList<Uri> successes = new ArrayList<>();",
"""        executor.execute(() -> {
            LanShare uploader=null;
            final ArrayList<Uri> successes = new ArrayList<>();""")
sub2("""                for (int i=0; i<batch.size(); i++) {
                    if (stopGalleryUpload.get()) { stopped=true; break; }""",
"""                if (stopGalleryUpload.get()) throw new InterruptedIOException("Przerwano kopiowanie");
                uploader=remote.separateUploadConnection();
                activeUploadSession=uploader;
                uploader.connectUpload();
                for (int i=0; i<batch.size(); i++) {
                    if (stopGalleryUpload.get()) { stopped=true; break; }""")
sub2("                        remote.uploadGallery(in,item.name,stopGalleryUpload);",
     "                        uploader.uploadGallery(in,item.name,stopGalleryUpload);")
sub2("""                    } catch (InterruptedIOException e) {
                        stopped=true;
                        break;
                    } catch (Exception e) {
                        failures++;""",
"""                    } catch (InterruptedIOException e) {
                        String incomplete=uploader.incompleteUploadPath();
                        remote.cleanupIncompleteUpload(incomplete);
                        stopped=true;
                        break;
                    } catch (Exception e) {
                        String incomplete=uploader.incompleteUploadPath();
                        remote.cleanupIncompleteUpload(incomplete);
                        if (stopGalleryUpload.get()) { stopped=true; break; }
                        failures++;""")
sub2("""            } catch (Exception e) {
                fatal=e.getMessage()==null ? e.toString():e.getMessage();
                setStatus("Błąd: "+fatal);
            } finally {
                final int errs=failures;""",
"""            } catch (Exception e) {
                if (stopGalleryUpload.get()) {
                    stopped=true;
                    if (uploader!=null && remote!=null)
                        remote.cleanupIncompleteUpload(uploader.incompleteUploadPath());
                } else {
                    fatal=e.getMessage()==null ? e.toString():e.getMessage();
                    setStatus("Błąd: "+fatal);
                }
            } finally {
                if (uploader!=null) {
                    final String incomplete=uploader.incompleteUploadPath();
                    uploader.close();
                    if (remote!=null && incomplete!=null)
                        remote.cleanupIncompleteUpload(incomplete);
                }
                activeUploadSession=null;
                final int errs=failures;""")
# When user taps cancel, status immediately changes to show cancel signal.
sub2("""                if (current!=null) current.abortActiveUpload();
            }""",
"""                if (current!=null) current.abortActiveUpload();
                setStatus("Przerywanie bieżącego transferu SMB…");
            }""")
p.write_text(s,encoding="utf-8")
p=Path("app/build.gradle")
s=p.read_text(encoding="utf-8")
if "versionCode 7" not in s: raise RuntimeError("Expected v7")
p.write_text(s.replace("versionCode 7","versionCode 8")
              .replace("versionName '7.0'","versionName '8.0'"),encoding="utf-8")
print("Patched: signed broadcast + on-link unicast discovery, last verified PC, dedicated interruptible SMB upload session, v8")
