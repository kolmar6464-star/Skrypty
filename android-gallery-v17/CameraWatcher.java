package pl.folderlan.android;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in camera-only MediaStore observer. No historical gallery bulk upload. */
public final class CameraWatcher extends Service {
    static final String ENABLE="pl.folderlan.android.CAMERA_ENABLE";
    static final String DISABLE="pl.folderlan.android.CAMERA_DISABLE";
    static final String PREF="folderlan_camera";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Handler handler;
    private ContentObserver observer;
    private volatile boolean scanning,enabled;
    private static final String CHANNEL="folderlan_camera_v1";
    private static final int NOTIFICATION=44218;
    private final Runnable check=new Runnable() {
        @Override public void run() {
            if(!enabled)return;
            scan();
            handler.postDelayed(this,15000);
        }
    };
    static void setEnabled(Context ctx,boolean enabled) {
        ctx.getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("enabled",enabled).commit();
        Intent intent=new Intent(ctx,CameraWatcher.class)
            .setAction(enabled?ENABLE:DISABLE);
        if(enabled && Build.VERSION.SDK_INT>=26)ctx.startForegroundService(intent);
        else ctx.startService(intent);
    }
    @Override public void onCreate(){
        super.onCreate();
        handler=new Handler(Looper.getMainLooper());
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL,"Automatyczne zdjęcia z aparatu",
                NotificationManager.IMPORTANCE_LOW));
    }
    private void notifyForeground() {
        PendingIntent open=PendingIntent.getActivity(this,442,
            new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|
            PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("FolderLAN — zdjęcia z kamerki")
            .setContentText("Nowe zdjęcia z aparatu trafią do folderu sieciowego")
            .setContentIntent(open).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)
            startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION,n);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        boolean pref=getSharedPreferences(PREF,MODE_PRIVATE).getBoolean("enabled",false);
        if((intent!=null && DISABLE.equals(intent.getAction()))||!pref) {
            enabled=false;stopSelf();return START_NOT_STICKY;
        }
        enabled=true;
        notifyForeground();
        if(observer==null) {
            observer=new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange,Uri uri) { scan(); }
                @Override public void onChange(boolean selfChange) { scan(); }
            };
            getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer);
            // Initialize watermark to newest photo when opt-in is enabled.
            if(getSharedPreferences(PREF,MODE_PRIVATE).getLong("lastId",-1)<0)
                worker.execute(this::initializeWatermark);
            else scan();
            handler.postDelayed(check,15000);
        }
        return START_STICKY;
    }
    private void initializeWatermark() {
        long max=0;
        try(Cursor c=getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            new String[]{MediaStore.MediaColumns._ID},null,null,
            MediaStore.MediaColumns._ID+" DESC")) {
            if(c!=null && c.moveToFirst())max=c.getLong(0);
        }catch(Exception ignored){}
        getSharedPreferences(PREF,MODE_PRIVATE).edit()
            .putLong("lastId",max).commit();
    }
    private void scan() {
        if(!enabled||scanning)return;
        scanning=true;
        worker.execute(() -> {
            try { scanNewCameraPhotos(); }
            finally { scanning=false; }
        });
    }
    private void scanNewCameraPhotos() {
        if(!enabled)return;
        android.content.SharedPreferences pref=getSharedPreferences(PREF,MODE_PRIVATE);
        long last=pref.getLong("lastId",-1);
        if(last<0){initializeWatermark();return;}
        long newest=last;
        String[] fields={MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.MediaColumns.IS_PENDING};
        // Only new device media, never images already existing before opt-in.
        String where=MediaStore.MediaColumns._ID+" > ?";
        try(Cursor c=getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,fields,where,
                new String[]{Long.toString(last)},
                MediaStore.MediaColumns._ID+" ASC")) {
            if(c==null)return;
            while(enabled && c.moveToNext()) {
                long id=c.getLong(0);
                if(id>newest)newest=id;
                if(c.getInt(5)!=0)continue;
                String folder=c.getString(3);
                if(folder==null)continue;
                String lower=folder.replace('\\','/').toLowerCase(Locale.ROOT);
                if(!(lower.startsWith("dcim/") && (lower.contains("camera") ||
                    lower.equals("dcim/"))))continue;
                String filename=c.getString(1);
                if(filename==null||filename.isEmpty())filename="zdjecie.jpg";
                long when=c.getLong(4);
                if(when<100000000000L)when=c.getLong(2)*1000L;
                Uri photo=android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id);
                try {
                    TransferService.sendCamera(this,photo,filename,when);
                }catch(Exception error) {
                    // Keep the event for the next scan if Android blocks service start.
                    newest=id-1;break;
                }
            }
        }catch(SecurityException ignored) {
            // User must allow full image/media access in Android Settings.
        }catch(Exception ignored){}
        if(newest>last)
            pref.edit().putLong("lastId",newest).commit();
    }
    @Override public void onDestroy(){
        enabled=false;
        handler.removeCallbacks(check);
        try{if(observer!=null)getContentResolver().unregisterContentObserver(observer);}
        catch(Exception ignored){}
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
