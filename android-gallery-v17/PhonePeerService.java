package pl.folderlan.android;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.*;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.MediaStore;
import android.content.ContentValues;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Opt-in, HMAC-discovered and AES-GCM-encrypted LAN phone exchange. */
public final class PhonePeerService extends Service {
    static final String ENABLE="pl.folderlan.android.PEER_ENABLE";
    static final String DISABLE="pl.folderlan.android.PEER_DISABLE";
    static final String PREF="folderlan_peers";
    private static final int UDP=34811,TCP=34812;
    private static final int CHUNK=65536;
    private static final long LIMIT=250L*1024*1024;
    private static final int MAGIC=0x464C5032;
    private static final String CHANNEL="folderlan_peer_v1";
    private static final int NOTIFY=44288;
    private volatile boolean enabled;
    private ServerSocket server;
    private DatagramSocket discovery;
    private ExecutorService workers=Executors.newFixedThreadPool(3);
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock cpu;

    static final class Peer {
        final String ip,name,id;
        Peer(String ip,String name,String id) { this.ip=ip;this.name=name;this.id=id; }
        @Override public String toString() {return name+" ("+ip+")";}
    }
    static void setEnabled(Context ctx,boolean active) {
        ctx.getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("enabled",active).commit();
        Intent i=new Intent(ctx,PhonePeerService.class).setAction(active?ENABLE:DISABLE);
        if(active && Build.VERSION.SDK_INT>=26)ctx.startForegroundService(i);
        else ctx.startService(i);
    }
    private static byte[] secret(Context ctx) throws Exception {
        byte[] buffer=new byte[128];
        try(InputStream in=ctx.getAssets().open("pairing_key.txt")) {
            int n=in.read(buffer);
            if(n<=0)throw new IOException("Brak klucza parowania.");
            byte[] raw=android.util.Base64.decode(
                new String(buffer,0,n,StandardCharsets.US_ASCII).trim(),
                android.util.Base64.DEFAULT);
            if(raw.length!=32)throw new IOException("Błędny klucz parowania.");
            return raw;
        }
    }
    private static byte[] hmac(byte[] key,byte[] source) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key,"HmacSHA256"));
        return mac.doFinal(source);
    }
    private static String sign(byte[] key,String text) throws Exception {
        return android.util.Base64.encodeToString(hmac(key,text.getBytes(StandardCharsets.UTF_8)),
            android.util.Base64.NO_WRAP);
    }
    private static byte[] getIv(byte[] seed,int chunk) {
        byte[] iv=seed.clone();
        iv[8]=(byte)(chunk>>>24);iv[9]=(byte)(chunk>>>16);
        iv[10]=(byte)(chunk>>>8);iv[11]=(byte)chunk;
        return iv;
    }
    private static Cipher aes(byte[] key,byte[] iv,int op,String aad) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(op,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return c;
    }
    private static String safeName(String name) {
        String normalized=name.replaceAll("[\\\\/:*?\\\"<>|\\x00-\\x1F]","_");
        if(normalized.isEmpty() || normalized.equals(".") || normalized.equals(".."))
            normalized="Zdjecie.jpg";
        if(normalized.length()>128)normalized=normalized.substring(0,128);
        return normalized;
    }
    private static String localId(Context ctx){
        android.content.SharedPreferences p=ctx.getSharedPreferences(PREF,MODE_PRIVATE);
        String id=p.getString("id","");
        if(!id.isEmpty())return id;
        id=UUID.randomUUID().toString().replace("-","");
        p.edit().putString("id",id).commit();
        return id;
    }
    static String localName(Context ctx) {
        return "Telefon "+Build.MANUFACTURER+" "+Build.MODEL+"-"+localId(ctx).substring(0,4);
    }
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL,"Wymiana zdjęć telefon–telefon",
                NotificationManager.IMPORTANCE_LOW));
    }
    private void foreground(){
        PendingIntent open=PendingIntent.getActivity(this,90,
            new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|
            PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("FolderLAN: wymiana zdjęć")
            .setContentText(localName(this)+" — odbieranie za zgodą")
            .setContentIntent(open).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)
            startForeground(NOTIFY,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFY,n);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        boolean allowed=getSharedPreferences(PREF,MODE_PRIVATE).getBoolean("enabled",false);
        if((intent!=null && DISABLE.equals(intent.getAction()))||!allowed) {
            enabled=false;stopSelf();return START_NOT_STICKY;
        }
        foreground();
        if(!enabled) {
            enabled=true;
            try {
                WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                if(wm!=null) {
                    multicastLock=wm.createMulticastLock("FolderLAN-PeerDiscovery");
                    multicastLock.setReferenceCounted(false);multicastLock.acquire();
                }
                PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
                cpu=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"FolderLAN:PhonePeer");
                cpu.setReferenceCounted(false);cpu.acquire();
            }catch(Exception ignored){}
            workers.execute(this::serveDiscovery);
            workers.execute(this::serveFiles);
        }
        return START_STICKY;
    }
    private void serveDiscovery(){
        try (DatagramSocket sock=new DatagramSocket(UDP)) {
            discovery=sock;
            sock.setSoTimeout(1200);
            byte[] key=secret(this);
            byte[] buffer=new byte[640];
            while(enabled){
                try {
                    DatagramPacket p=new DatagramPacket(buffer,buffer.length);
                    sock.receive(p);
                    String msg=new String(p.getData(),p.getOffset(),p.getLength(),
                        StandardCharsets.US_ASCII);
                    String[] parts=msg.split("\\|",-1);
                    if(parts.length!=4 || !"FLPT1".equals(parts[0]) ||
                        !LanShare.KIT.equals(parts[1]) ||
                        !parts[2].matches("[0-9a-f]{32}"))continue;
                    String expected=sign(key,parts[0]+"|"+parts[1]+"|"+parts[2]);
                    if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                        parts[3].getBytes(StandardCharsets.US_ASCII)))continue;
                    String name64=android.util.Base64.encodeToString(
                        localName(this).getBytes(StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP);
                    String unsigned="FLPTA|"+LanShare.KIT+"|"+parts[2]+"|"+
                        localId(this)+"|"+name64;
                    byte[] response=(unsigned+"|"+sign(key,unsigned))
                        .getBytes(StandardCharsets.US_ASCII);
                    sock.send(new DatagramPacket(response,response.length,p.getAddress(),p.getPort()));
                }catch(SocketTimeoutException ignored){}
                catch(Exception ignored){}
            }
        }catch(Exception ignored){}finally{discovery=null;}
    }
    private void serveFiles(){
        try(ServerSocket listener=new ServerSocket(TCP,12)){
            server=listener;
            listener.setSoTimeout(1500);
            while(enabled) {
                try {
                    Socket s=listener.accept();
                    workers.execute(()->receive(s));
                }catch(SocketTimeoutException ignored){}
                catch(Exception ignored){}
            }
        }catch(Exception ignored){}finally{server=null;}
    }
    private void receive(Socket socket){
        Uri record=null;
        try(Socket peer=socket){
            peer.setSoTimeout(60000);
            DataInputStream in=new DataInputStream(
                new BufferedInputStream(peer.getInputStream()));
            DataOutputStream ack=new DataOutputStream(peer.getOutputStream());
            byte[] key=secret(this);
            if(in.readInt()!=MAGIC)throw new IOException("Błędny nagłówek.");
            String filename=safeName(in.readUTF());
            long len=in.readLong();
            if(len<1||len>LIMIT)throw new IOException("Rozmiar pliku poza limitem.");
            byte[] iv=new byte[12];in.readFully(iv);
            byte[] proof=new byte[32];in.readFully(proof);
            String challenge="FLP2|"+filename+"|"+len+"|"+
                android.util.Base64.encodeToString(iv,android.util.Base64.NO_WRAP);
            if(!MessageDigest.isEqual(hmac(key,challenge.getBytes(StandardCharsets.UTF_8)),proof))
                throw new SecurityException("Nieprawidłowy podpis.");
            String mime=filename.toLowerCase(Locale.ROOT).endsWith(".png")?"image/png":
                (filename.toLowerCase(Locale.ROOT).endsWith(".heic")?"image/heic":"image/jpeg");
            Uri base=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/FolderLAN Telefon");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            record=getContentResolver().insert(base,values);
            if(record==null)throw new IOException("Nie można utworzyć zdjęcia w galerii.");
            try(OutputStream out=getContentResolver().openOutputStream(record,"w")) {
                if(out==null)throw new IOException("Nie można zapisać zdjęcia.");
                byte[] encrypted=new byte[CHUNK+16];
                long remaining=len;
                int chunk=0;
                while(remaining>0){
                    int chunkLen=(int)Math.min(CHUNK,remaining);
                    in.readFully(encrypted,0,chunkLen+16);
                    String aad="FLP2|"+filename+"|"+len+"|"+chunk;
                    byte[] clear=aes(key,getIv(iv,chunk),Cipher.DECRYPT_MODE,aad)
                        .doFinal(encrypted,0,chunkLen+16);
                    out.write(clear);
                    remaining-=chunkLen;chunk++;
                }
                out.flush();
            }
            ContentValues valid=new ContentValues();
            valid.put(MediaStore.MediaColumns.IS_PENDING,0);
            getContentResolver().update(record,valid,null,null);
            ack.writeByte(1);ack.flush();
            record=null;
        }catch(Exception ignored){}
        finally{
            if(record!=null)try{getContentResolver().delete(record,null,null);}
                catch(Exception ignored){}
        }
    }

    static List<Peer> discover(Context ctx) throws Exception {
        byte[] key=secret(ctx), nonce=new byte[16];
        new SecureRandom().nextBytes(nonce);
        String token=toHex(nonce);
        String q="FLPT1|"+LanShare.KIT+"|"+token;
        byte[] bytes=(q+"|"+sign(key,q)).getBytes(StandardCharsets.US_ASCII);
        Map<String,Peer> peers=new LinkedHashMap<>();
        try(DatagramSocket udp=new DatagramSocket()) {
            udp.setBroadcast(true);udp.setSoTimeout(250);
            List<String> targets=new ArrayList<>();
            targets.add("255.255.255.255");
            try {
                android.net.wifi.WifiManager manager=(android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(WIFI_SERVICE);
                android.net.DhcpInfo d=manager==null?null:manager.getDhcpInfo();
                if(d!=null){
                    int broadcast=(d.ipAddress&d.netmask)|(~d.netmask);
                    targets.add(String.format(Locale.ROOT,"%d.%d.%d.%d",
                        broadcast&255,broadcast>>>8&255,broadcast>>>16&255,broadcast>>>24&255));
                }
            }catch(Exception ignored){}
            for(String target:targets){
                try {InetAddress addr=InetAddress.getByName(target);
                    udp.send(new DatagramPacket(bytes,bytes.length,addr,UDP));
                }catch(Exception ignored){}
            }
            long end=System.currentTimeMillis()+2200;
            while(System.currentTimeMillis()<end){
                try{
                    DatagramPacket answer=new DatagramPacket(new byte[640],640);
                    udp.receive(answer);
                    String raw=new String(answer.getData(),answer.getOffset(),answer.getLength(),
                        StandardCharsets.US_ASCII);
                    String[] p=raw.split("\\|",-1);
                    if(p.length!=6||!"FLPTA".equals(p[0])||!LanShare.KIT.equals(p[1])||
                        !token.equals(p[2])||!p[3].matches("[0-9a-f]{32}"))continue;
                    String expected=sign(key,String.join("|",p[0],p[1],p[2],p[3],p[4]));
                    if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                        p[5].getBytes(StandardCharsets.US_ASCII)))continue;
                    if(localId(ctx).equals(p[3]))continue;
                    String name=new String(android.util.Base64.decode(p[4],android.util.Base64.DEFAULT),
                        StandardCharsets.UTF_8);
                    peers.put(p[3],new Peer(answer.getAddress().getHostAddress(),name,p[3]));
                }catch(SocketTimeoutException ignored){}
            }
        }
        return new ArrayList<>(peers.values());
    }
    private static String toHex(byte[] array){
        StringBuilder sb=new StringBuilder();
        for(byte b:array)sb.append(String.format(Locale.ROOT,"%02x",b&255));
        return sb.toString();
    }
    static void send(Context ctx,Uri uri,String name,Peer receiver) throws Exception {
        byte[] key=secret(ctx);
        name=safeName(name);
        long len=0;
        try(Cursor cursor=ctx.getContentResolver().query(uri,
            new String[]{MediaStore.MediaColumns.SIZE},null,null,null)) {
            if(cursor!=null && cursor.moveToFirst())len=cursor.getLong(0);
        }
        if(len<1||len>LIMIT)throw new IOException("Zdjęcie przekracza limit 250 MB.");
        byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
        String header="FLP2|"+name+"|"+len+"|"+
            android.util.Base64.encodeToString(iv,android.util.Base64.NO_WRAP);
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(receiver.ip,TCP),7000);
            socket.setSoTimeout(65000);
            DataOutputStream out=new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(MAGIC);out.writeUTF(name);out.writeLong(len);out.write(iv);
            out.write(hmac(key,header.getBytes(StandardCharsets.UTF_8)));
            long remaining=len;
            int chunk=0;
            byte[] source=new byte[CHUNK];
            try(InputStream in=ctx.getContentResolver().openInputStream(uri)){
                if(in==null)throw new IOException("Zdjęcie jest niedostępne.");
                while(remaining>0){
                    int needed=(int)Math.min(CHUNK,remaining);
                    int offset=0;
                    while(offset<needed){
                        int amount=in.read(source,offset,needed-offset);
                        if(amount<0)throw new EOFException("Zdjęcie zostało skrócone.");
                        offset+=amount;
                    }
                    String aad="FLP2|"+name+"|"+len+"|"+chunk;
                    byte[] encrypted=aes(key,getIv(iv,chunk),Cipher.ENCRYPT_MODE,aad)
                        .doFinal(source,0,needed);
                    out.write(encrypted);
                    remaining-=needed;chunk++;
                }
            }
            out.flush();
            if(new DataInputStream(socket.getInputStream()).readByte()!=1)
                throw new IOException("Drugi telefon nie potwierdził odbioru.");
        }
    }
    @Override public void onDestroy() {
        enabled=false;
        if(server!=null)try{server.close();}catch(Exception ignored){}
        if(discovery!=null)discovery.close();
        if(multicastLock!=null)try{if(multicastLock.isHeld())multicastLock.release();}
            catch(Exception ignored){}
        if(cpu!=null)try{if(cpu.isHeld())cpu.release();}catch(Exception ignored){}
        workers.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
