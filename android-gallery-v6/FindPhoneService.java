package pl.folderlan.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.PowerManager;
import android.util.Base64;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Opt-in foreground LAN listener. Authenticated HMAC challenge over UDP.
 * Local network only; does not provide access over mobile Internet.
 */
public final class FindPhoneService extends Service {
    public static final String START = "pl.folderlan.android.FIND_START";
    public static final String STOP = "pl.folderlan.android.FIND_STOP";
    public static final String STOP_RING = "pl.folderlan.android.FIND_STOP_RING";
    public static final String TEST = "pl.folderlan.android.FIND_TEST";
    private static final String KIT = "5E4CA3EA7A";
    private static final int PORT = 34793;
    private static final String CH_MONITOR = "folderlan_find_monitor";
    private static final String CH_ALERT = "folderlan_find_alert";
    private static final int NOTIFICATION_ID = 34793;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private DatagramSocket socket;
    private Thread receiver;
    private byte[] secret;
    private Ringtone ringtone;
    private Vibrator vibrator;
    private long ringUntil;
    private volatile String lastNonce = "";
    private long lastTrigger;
    private final Runnable loopRing = new Runnable() {
        @Override public void run() {
            if (ringUntil == 0) return;
            if (SystemClock.elapsedRealtime() > ringUntil) {
                stopRinging();
                return;
            }
            try {
                if (ringtone != null && !ringtone.isPlaying()) ringtone.play();
            } catch (Exception ignored) {}
            main.postDelayed(this, 2500L);
        }
    };

    public static boolean isActive(Context context) {
        return context.getSharedPreferences("finder", MODE_PRIVATE)
                .getBoolean("monitor_enabled", false);
    }

    private void saveActive(boolean enabled) {
        getSharedPreferences("finder", MODE_PRIVATE).edit()
            .putBoolean("monitor_enabled", enabled).apply();
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel monitoring = new NotificationChannel(
                CH_MONITOR, "Znajdowanie telefonu w Wi-Fi", NotificationManager.IMPORTANCE_LOW);
        monitoring.setDescription("Stałe połączenie nasłuchujące polecenia z komputera.");
        manager.createNotificationChannel(monitoring);
        NotificationChannel alarming = new NotificationChannel(
                CH_ALERT, "Alarm znajdowania telefonu", NotificationManager.IMPORTANCE_HIGH);
        alarming.setDescription("Powiadomienie, gdy komputer żąda znalezienia telefonu.");
        manager.createNotificationChannel(alarming);
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, FindPhoneService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification(boolean alerting) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openApp = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, alerting ? CH_ALERT : CH_MONITOR)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_lock_silent_mode_off);
        builder.setContentTitle(alerting ? "Znajdź telefon — alarm" : "Znajdź telefon — nasłuch aktywny");
        builder.setContentText(alerting ? "Odtwarzam sygnał, dotknij Wycisz."
                : "Telefon oczekuje na polecenie z komputera w tej samej sieci Wi-Fi.");
        builder.setContentIntent(openApp);
        builder.setOngoing(true);
        builder.addAction(android.R.drawable.ic_media_pause,
                alerting ? "Wycisz" : "Wyłącz",
                serviceAction(alerting ? STOP_RING : STOP, alerting ? 2 : 3));
        return builder.build();
    }
    private void foreground(boolean alarm) {
        try {
            Notification n = notification(alarm);
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION_ID, n);
        } catch (Exception exception) {
            saveActive(false);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? START : intent.getAction();
        if (STOP.equals(action)) {
            stopRinging();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (STOP_RING.equals(action)) {
            stopRinging();
            foreground(false);
            return START_STICKY;
        }
        foreground(false);
        if (TEST.equals(action)) {
            startRing();
            return START_STICKY;
        }
        if (!listening.get()) launchListener();
        return START_STICKY;
    }

    private void launchListener() {
        try {
            byte[] data = new byte[128];
            try (java.io.InputStream input = getAssets().open("pairing_key.txt")) {
                int n = input.read(data);
                if (n <= 0) throw new java.io.IOException("Missing pairing key");
                secret = Base64.decode(new String(data,0,n,StandardCharsets.US_ASCII).trim(),Base64.DEFAULT);
                if (secret.length != 32) throw new java.io.IOException("Invalid key");
            }
        } catch (Exception e) {
            saveActive(false);
            stopSelf();
            return;
        }
        if (!listening.compareAndSet(false,true)) return;
        receiver = new Thread(() -> {
            try (DatagramSocket udp = new DatagramSocket(PORT)) {
                socket = udp;
                udp.setSoTimeout(1500);
                saveActive(true);
                byte[] buffer = new byte[320];
                while (listening.get()) {
                    try {
                        DatagramPacket received = new DatagramPacket(buffer, buffer.length);
                        udp.receive(received);
                        if (received.getLength() > 256 || received.getLength() < 70) continue;
                        String raw = new String(received.getData(),received.getOffset(),
                                received.getLength(),StandardCharsets.US_ASCII);
                        String[] fields = raw.split("\\|",-1);
                        if (fields.length != 5 || !"FLF1".equals(fields[0])
                                || !KIT.equals(fields[1]) || !fields[2].matches("[0-9]{10,11}")
                                || !fields[3].matches("[0-9a-f]{32}")) continue;
                        long issued = Long.parseLong(fields[2]);
                        long now = System.currentTimeMillis()/1000L;
                        if (Math.abs(now-issued)>90L || fields[3].equals(lastNonce)) continue;
                        String signed = String.join("|",fields[0],fields[1],fields[2],fields[3]);
                        String expected = mac(signed);
                        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                                fields[4].getBytes(StandardCharsets.US_ASCII))) continue;
                        if (SystemClock.elapsedRealtime()-lastTrigger<3000L) continue;
                        lastNonce=fields[3];
                        lastTrigger=SystemClock.elapsedRealtime();
                        main.post(this::startRing);
                        String ack = "FLFA|"+KIT+"|"+fields[3];
                        byte[] response=(ack+"|"+mac(ack)).getBytes(StandardCharsets.US_ASCII);
                        udp.send(new DatagramPacket(response,response.length,
                                received.getAddress(),received.getPort()));
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
            } finally {
                listening.set(false);
                saveActive(false);
                socket = null;
            }
        }, "folderlan-find-phone-udp");
        receiver.setDaemon(true);
        receiver.start();
    }

    private String mac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret,"HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.US_ASCII)),Base64.NO_WRAP);
    }

    private void startRing() {
        stopRinging();
        ringUntil = SystemClock.elapsedRealtime()+45000L;
        try {
            Uri sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound==null) sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ringtone=RingtoneManager.getRingtone(this,sound);
            if (ringtone!=null) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ringtone.play();
            }
        } catch(Exception ignored) {}
        try {
            vibrator=(Vibrator)getSystemService(VIBRATOR_SERVICE);
            if(vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0,300,350,400,350};
                if(Build.VERSION.SDK_INT>=26) vibrator.vibrate(VibrationEffect.createWaveform(pattern,0));
                else vibrator.vibrate(pattern,0);
            }
        } catch(Exception ignored) {}
        foreground(true);
        main.postDelayed(loopRing,2000L);
    }

    private void stopRinging() {
        ringUntil=0;
        main.removeCallbacks(loopRing);
        try {if(ringtone!=null) ringtone.stop();} catch(Exception ignored) {}
        ringtone=null;
        try {if(vibrator!=null) vibrator.cancel();} catch(Exception ignored) {}
        vibrator=null;
    }
    @Override public void onDestroy() {
        listening.set(false);
        saveActive(false);
        if(socket!=null) socket.close();
        stopRinging();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}