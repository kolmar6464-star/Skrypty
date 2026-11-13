package pl.folderlan.android;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent foreground SMB transfer. Neither gallery nor Activity owns a running job. */
public final class TransferService extends Service {
    static final String START="pl.folderlan.android.UPLOAD";
    static final String CANCEL="pl.folderlan.android.UPLOAD_CANCEL";
    static final String STATUS="pl.folderlan.android.UPLOAD_STATUS";
    static final String PREF="folderlan_transfer";
    private static final String CHANNEL="folderlan_transfer_v1";
    private static final int NOTIFICATION=44139;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean(false);
    private final Object guard=new Object();
    private volatile boolean running;
    private volatile LanShare currentUpload;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;

    static final class Item {
        final Uri uri;
        final String name;
        final boolean video;
        final boolean camera;
        final String timeLabel;
        Item(Uri uri,String name,boolean video,boolean camera,String timeLabel) {
            this.uri=uri;this.name=name;this.video=video;this.camera=camera;this.timeLabel=timeLabel;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("uri",uri.toString()).put("name",name)
                .put("video",video).put("camera",camera).put("time",timeLabel);
        }
        static Item from(JSONObject obj) {
            return new Item(Uri.parse(obj.optString("uri","")),
                obj.optString("name","zdjecie"),obj.optBoolean("video"),
                obj.optBoolean("camera"),obj.optString("time",""));
        }
    }

    static void send(Context ctx,List<GalleryPanel.Item> photos) {
        JSONArray list=new JSONArray();
        for(GalleryPanel.Item item:photos) {
            try { list.put(new Item(item.uri,item.name,item.video,false,"").json()); }
            catch (Exception ignored) {}
        }
        sendQueue(ctx,list);
    }
    static void sendCamera(Context ctx,Uri uri,String filename,long timestamp) {
        JSONArray list=new JSONArray();
        String date=new SimpleDateFormat("EEEE - dd.MM.yyyy|HH-mm-ss",new Locale("pl","PL"))
            .format(new Date(timestamp>0?timestamp:System.currentTimeMillis()));
        try { list.put(new Item(uri,filename,false,true,date).json()); }
        catch (Exception ignored) {}
        sendQueue(ctx,list);
    }
    private static void sendQueue(Context ctx,JSONArray list) {
        if(list.length()==0)return;
        Intent action=new Intent(ctx,TransferService.class).setAction(START)
            .putExtra("photos",list.toString());
        if(Build.VERSION.SDK_INT>=26)ctx.startForegroundService(action);
        else ctx.startService(action);
    }
    static void cancel(Context ctx) {
        ctx.startService(new Intent(ctx,TransferService.class).setAction(CANCEL));
    }
    static SharedPreferences state(Context ctx) { return ctx.getSharedPreferences(PREF,MODE_PRIVATE); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
            "Kopiowanie FolderLAN",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(int done,int total,String label) {
        Intent open=new Intent(this,MainActivity.class).addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap=PendingIntent.getActivity(this,56,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,57,
            new Intent(this,TransferService.class).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(
                "FolderLAN: "+done+"/"+total+" zdjęć")
            .setContentText(label).setContentIntent(tap).setOngoing(true)
            .setOnlyAlertOnce(true).setProgress(Math.max(1,total),done,false);
        n.addAction(android.R.drawable.ic_menu_close_clear_cancel,"Przerwij",stop);
        return n.build();
    }
    private void foreground(int done,int total,String message) {
        try {
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFICATION,notification(done,total,message),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION,notification(done,total,message));
        } catch(Exception ignored) {}
    }
    private void updateNotification(int done,int total,String message) {
        try {getSystemService(NotificationManager.class).notify(
            NOTIFICATION,notification(done,total,message));}catch(Exception ignored){}
    }
    private void acquireLocks() {
        try {
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            cpu=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "FolderLAN:CopyPhotos");
            cpu.setReferenceCounted(false);cpu.acquire();
            WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wm!=null) {
                wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "FolderLAN:CopyWiFi");
                wifi.setReferenceCounted(false);wifi.acquire();
            }
        }catch(Exception ignored){}
    }
    private void releaseLocks() {
        try{if(cpu!=null&&cpu.isHeld())cpu.release();}catch(Exception ignored){}
        try{if(wifi!=null&&wifi.isHeld())wifi.release();}catch(Exception ignored){}
        cpu=null;wifi=null;
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?"":intent.getAction();
        SharedPreferences prefs=state(this);
        if(CANCEL.equals(action)) {
            cancelled.set(true);
            LanShare session=currentUpload;
            if(session!=null)session.abortActiveUpload();
            publish(prefs,"Przerywanie kopiowania…");
            return START_STICKY;
        }
        // Android requires foreground start immediately, before heavy IO.
        foreground(prefs.getInt("done",0),prefs.getInt("total",0),
            prefs.getString("message","Przygotowywanie kopiowania"));
        if(START.equals(action) && intent!=null) {
            String incoming=intent.getStringExtra("photos");
            if(incoming!=null)appendQueue(prefs,incoming);
        }
        if(prefs.getBoolean("active",false) && !running) {
            running=true;
            cancelled.set(false);
            executor.execute(() -> transferLoop(prefs));
        }else if(!prefs.getBoolean("active",false)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_STICKY;
    }
    private void appendQueue(SharedPreferences prefs,String incoming) {
        synchronized(guard) {
            try {
                JSONArray existing=new JSONArray(prefs.getString("queue","[]"));
                JSONArray added=new JSONArray(incoming);
                if(!prefs.getBoolean("active",false)) {
                    existing=new JSONArray();
                    prefs.edit().putInt("done",0).putInt("success",0).putInt("errors",0)
                        .putInt("next",0).putBoolean("completedPending",false)
                        .putString("error","").commit();
                }
                for(int i=0;i<added.length();i++)existing.put(added.getJSONObject(i));
                prefs.edit().putString("queue",existing.toString())
                    .putInt("total",existing.length()).putBoolean("active",true)
                    .putString("message","Rozpoczynanie kopiowania…").commit();
            } catch(Exception e) {
                prefs.edit().putBoolean("active",false)
                    .putString("message","Nieprawidłowa kolejka: "+e.getMessage()).commit();
            }
        }
        publish(prefs,prefs.getString("message",""));
    }

    private String key() throws Exception {
        byte[] buffer=new byte[128];
        try(InputStream input=getAssets().open("pairing_key.txt")) {
            int n=input.read(buffer);
            if(n<=0)throw new IOException("Brak danych parowania.");
            return new String(buffer,0,n,StandardCharsets.US_ASCII).trim();
        }
    }
    private String extension(Item item) {
        String n=item.name.toLowerCase(Locale.ROOT);
        int i=n.lastIndexOf('.');
        String ext=(i>=0 && i>n.length()-8)?n.substring(i):"";
        if(!ext.matches("\\.[a-z0-9]{2,5}"))ext=item.video?".mp4":".jpg";
        return ext;
    }
    private String numberedFilename(SharedPreferences prefs,Item item,int cursor) {
        String date=new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(new Date());
        String key="seq_"+date;
        // Numeric filename allocated monotonically, even if Windows target changes.
        int next=prefs.getInt(key,1);
        prefs.edit().putInt(key,next+1).commit();
        return next+extension(item);
    }

    private void transferLoop(SharedPreferences prefs) {
        acquireLocks();
        LanShare session=null;
        String pairing=null;
        try {
            pairing=key();
            while(prefs.getBoolean("active",false) && !cancelled.get()) {
                JSONArray queue;
                int next=prefs.getInt("next",0);
                try{queue=new JSONArray(prefs.getString("queue","[]"));}
                catch(Exception e){throw new IOException("Nie można odczytać kolejki.",e);}
                if(next>=queue.length())break;
                Item item=Item.from(queue.getJSONObject(next));
                String chosenName=prefs.getString("filename_"+next,"");
                if(chosenName.isEmpty()) {
                    chosenName=numberedFilename(prefs,item,next);
                    prefs.edit().putString("filename_"+next,chosenName).commit();
                }
                String file=chosenName;
                int success=prefs.getInt("success",0);
                int done=prefs.getInt("done",0);
                int attempts=0;
                while(!cancelled.get()) {
                    try {
                        if(session==null) {
                            session=new LanShare(pairing);
                            session.connect(getApplicationContext());
                        }
                        currentUpload=session;
                        String target=item.camera ? "Zdjęcie z kamerki\\"+
                            item.timeLabel.replace("|","\\")+"_"+file : file;
                        // Use an existing device-independent SMB connection.
                        try(InputStream input=getContentResolver().openInputStream(item.uri)) {
                            if(input==null)throw new IOException("Zdjęcie nie jest dostępne.");
                            if(item.camera)session.uploadCamera(input,target,cancelled);
                            else session.uploadGallery(input,target,cancelled);
                        }
                        success++;
                        done++;
                        prefs.edit().putInt("success",success).putInt("done",done)
                            .putInt("next",next+1).commit();
                        publish(prefs,"Skopiowano: "+success+" / "+queue.length());
                        break;
                    } catch(InterruptedIOException e) {
                        if(cancelled.get())break;
                        attempts++;
                        if(session!=null){session.close();session=null;}
                    } catch(Exception e) {
                        if(session!=null){session.close();session=null;}
                        attempts++;
                        prefs.edit().putString("error",e.getMessage()==null?"Błąd SMB":e.getMessage()).commit();
                    } finally {currentUpload=null;}
                    if(cancelled.get())break;
                    // Server vanished during transfer; re-discover another
                    // authenticated SMB host and retry current photo. Never skip it.
                    publish(prefs,"Oczekiwanie na inny komputer Wi-Fi… próba "+attempts);
                    Thread.sleep(Math.min(15000,1500+attempts*1250));
                }
            }
            if(cancelled.get()) {
                prefs.edit().putBoolean("active",false).putBoolean("cancelled",true)
                    .putString("message","Kopiowanie przerwane: "+
                        prefs.getInt("success",0)+" zdjęć").commit();
            }else {
                int total=prefs.getInt("total",0);
                int ok=prefs.getInt("success",0);
                prefs.edit().putBoolean("active",false).putBoolean("cancelled",false)
                    .putInt("done",total).putBoolean("completedPending",true)
                    .putString("message","Kopiowanie zakończone. Skopiowano: "+ok+
                        ", błędy: "+prefs.getInt("errors",0)).commit();
            }
            publish(prefs,prefs.getString("message",""));
        }catch(Exception fatal){
            prefs.edit().putBoolean("active",false)
                .putString("message","Błąd kopiowania: "+fatal.getMessage())
                .putBoolean("completedPending",true).commit();
            publish(prefs,prefs.getString("message",""));
        }finally{
            currentUpload=null;
            if(session!=null)session.close();
            releaseLocks();
            running=false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }
    private void publish(SharedPreferences prefs,String message) {
        prefs.edit().putString("message",message).apply();
        Intent event=new Intent(STATUS).setPackage(getPackageName());
        event.putExtra("message",message);
        sendBroadcast(event);
        updateNotification(prefs.getInt("done",0),prefs.getInt("total",0),message);
    }
    @Override public void onDestroy(){
        cancelled.set(true);
        if(currentUpload!=null)currentUpload.abortActiveUpload();
        releaseLocks();
        executor.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
