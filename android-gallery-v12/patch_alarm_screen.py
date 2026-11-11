from pathlib import Path

service=Path("app/src/main/java/pl/folderlan/android/FindPhoneService.java")
s=service.read_text(encoding="utf-8")
def change(old,new):
    global s
    if s.count(old)!=1:
        raise RuntimeError("Finder v12 anchor mismatch: "+old[:120] +"; count="+str(s.count(old)))
    s=s.replace(old,new,1)

change("import android.media.AudioManager;\n", """import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.wifi.WifiManager;
""")
change('    private static final String CH_ALERT = "folderlan_find_alert";',
       '    private static final String CH_ALERT = "folderlan_find_alert_v12";')
change('    private final AtomicBoolean listening = new AtomicBoolean(false);\n',
"""    private final AtomicBoolean listening = new AtomicBoolean(false);
    public static final String ALARM_FINISHED = "pl.folderlan.android.FIND_ALARM_FINISHED";
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock alarmWakeLock;
    private ToneGenerator fallbackTone;
""")
change('''            try {
                if (ringtone != null && !ringtone.isPlaying()) ringtone.play();
            } catch (Exception ignored) {}
            main.postDelayed(this, 2500L);''',
'''            try {
                if (ringtone != null && !ringtone.isPlaying()) ringtone.play();
                else if (ringtone == null && fallbackTone != null)
                    fallbackTone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500);
            } catch (Exception ignored) {}
            main.postDelayed(this, 2500L);''')
change('''        manager.createNotificationChannel(alarming);''',
'''        alarming.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alarming.enableVibration(true);
        manager.createNotificationChannel(alarming);''')
change('''        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openApp = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);''',
'''        Intent open = new Intent(this,
                alerting ? FindPhoneAlarmActivity.class : MainActivity.class);
        if (alerting) open.setAction(FindPhoneAlarmActivity.ACTION_SHOW_ALARM);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openApp = PendingIntent.getActivity(this, alerting ? 61 : 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);''')
change('''        builder.setContentIntent(openApp);
        builder.setOngoing(true);''',
'''        builder.setContentIntent(openApp);
        builder.setOngoing(true);
        if (alerting) {
            builder.setCategory(Notification.CATEGORY_ALARM);
            builder.setPriority(Notification.PRIORITY_MAX);
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
            builder.setColor(android.graphics.Color.RED);
            // Android only opens over a locked screen when its full-screen
            // notification permission is granted and the OEM allows it.
            builder.setFullScreenIntent(openApp, true);
        }''')
change('''        if (!listening.compareAndSet(false,true)) return;
        receiver = new Thread(() -> {''',
'''        if (!listening.compareAndSet(false,true)) return;
        acquireWifiLocks();
        receiver = new Thread(() -> {''')
change('''            } finally {
                listening.set(false);
                saveActive(false);
                socket = null;
            }
        }, "folderlan-find-phone-udp");''',
'''            } finally {
                listening.set(false);
                saveActive(false);
                socket = null;
                releaseWifiLocks();
            }
        }, "folderlan-find-phone-udp");''')

# network socket may receive broadcasts only with multicast lock on certain devices
change('''    private String mac(String data) throws Exception {''',
'''    private void acquireWifiLocks() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager == null) return;
            multicastLock = manager.createMulticastLock("FolderLAN-PhoneFinder");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                                              "FolderLAN-FindPhone-WiFi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {
            // Notification still displays listening state; network delivery
            // depends on the Android device and local router.
        }
    }
    private void releaseWifiLocks() {
        try { if (multicastLock!=null && multicastLock.isHeld()) multicastLock.release(); }
        catch (Exception ignored) {}
        try { if (wifiLock!=null && wifiLock.isHeld()) wifiLock.release(); }
        catch (Exception ignored) {}
        multicastLock=null;
        wifiLock=null;
    }

    private String mac(String data) throws Exception {''')
change('''        maximizeFindPhoneAudio();
        try {
            Uri sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);''',
'''        maximizeFindPhoneAudio();
        try {
            PowerManager pm=(PowerManager) getSystemService(POWER_SERVICE);
            if(pm!=null) {
                alarmWakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                           "FolderLAN:FindPhoneAlarm");
                alarmWakeLock.setReferenceCounted(false);
                alarmWakeLock.acquire(58000L);
            }
        } catch (Exception ignored) {}
        try {
            Uri sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);''')
change('''                ringtone.play();
            }
        } catch(Exception ignored) {}''',
'''                ringtone.play();
            }
        } catch(Exception ignored) {}
        try {
            if (ringtone == null || !ringtone.isPlaying()) {
                fallbackTone=new ToneGenerator(AudioManager.STREAM_ALARM,100);
                fallbackTone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,1800);
            }
        } catch(Exception ignored) {}''')
# ensure last max volume after changing DND/ringer mode; don't recapture the old volume
change('''        } catch (SecurityException | IllegalArgumentException ignored) {
            // DND control requires permission explicitly granted in Android Settings.
        }
    }''',
'''        } catch (SecurityException | IllegalArgumentException ignored) {
            // DND control requires permission explicitly granted in Android Settings.
        }
        // Some OEMs remap stream volumes when ringer/DND mode changes.
        try {
            int max = alertAudio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (alertAudio.getStreamVolume(AudioManager.STREAM_ALARM) != max) {
                alertAudio.setStreamVolume(AudioManager.STREAM_ALARM,max,0);
                changedAlarmVolume=true;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {}
    }''')
change('''        try {if(vibrator!=null) vibrator.cancel();} catch(Exception ignored) {}
        vibrator=null;
        restoreFindPhoneAudio();''',
'''        try {if(vibrator!=null) vibrator.cancel();} catch(Exception ignored) {}
        vibrator=null;
        try {if(fallbackTone!=null) fallbackTone.release();} catch(Exception ignored) {}
        fallbackTone=null;
        try {if(alarmWakeLock!=null && alarmWakeLock.isHeld()) alarmWakeLock.release();}
        catch(Exception ignored) {}
        alarmWakeLock=null;
        restoreFindPhoneAudio();
        sendBroadcast(new Intent(ALARM_FINISHED).setPackage(getPackageName()));''')
change('''        if(socket!=null) socket.close();
        stopRinging();''',
'''        if(socket!=null) socket.close();
        releaseWifiLocks();
        stopRinging();''')
service.write_text(s,encoding="utf-8")

manifest=Path("app/src/main/AndroidManifest.xml")
xml=manifest.read_text(encoding="utf-8")
anchor='<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />'
assert xml.count(anchor)==1
xml=xml.replace(anchor,anchor+'''
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
''',1)
anchor='<service android:name=".FindPhoneService"'
assert xml.count(anchor)==1
xml=xml.replace(anchor,
'''<activity android:name=".FindPhoneAlarmActivity"
                  android:exported="false"
                  android:excludeFromRecents="true"
                  android:launchMode="singleTop"
                  android:showWhenLocked="true"
                  android:turnScreenOn="true" />
        <service android:name=".FindPhoneService"''',1)
manifest.write_text(xml,encoding="utf-8")

activity=Path("app/src/main/java/pl/folderlan/android/MainActivity.java")
m=activity.read_text(encoding="utf-8")
def amod(old,new):
    global m
    if m.count(old)!=1:
        raise RuntimeError("MainActivity v12 anchor mismatch: "+old[:120]+ " count:"+str(m.count(old)))
    m=m.replace(old,new,1)

# After user permits notification, re-enable finder and remember user choice
amod('''    private void enableFindPhone() {
        if (Build.VERSION.SDK_INT >= 33''',
'''    private void enableFindPhone() {
        getSharedPreferences("finder", MODE_PRIVATE).edit()
            .putBoolean("monitor_requested",true).apply();
        if (Build.VERSION.SDK_INT >= 33''')
amod('''    private void disableFindPhone() {
        startFinderService(FindPhoneService.STOP);''',
'''    private void disableFindPhone() {
        getSharedPreferences("finder", MODE_PRIVATE).edit()
            .putBoolean("monitor_requested",false).apply();
        startFinderService(FindPhoneService.STOP);''')

# Enhance Android permissions chooser while preserving the two existing buttons
amod('''          .setPositiveButton("Ustawienia aplikacji", (d,w) -> {''',
'''          .setPositiveButton("Ustawienia aplikacji", (d,w) -> {''') if False else None
amod('''        main.addView(findExtras);
        finderOnButton.setContentDescription''',
'''        main.addView(findExtras);
        // Available on Android 14+; full-screen alarm requires user consent.
        if (Build.VERSION.SDK_INT >= 34) {
            main.addView(action("Czerwony ekran alarmu na blokadzie — uprawnienie",
                  BLUE, this::requestFullScreenAlarmAccess),
                  new LinearLayout.LayoutParams(-1,dp(50)));
        }
        finderOnButton.setContentDescription''')
amod('''    private void findPermissions() {''',
'''    private void requestFullScreenAlarmAccess() {
        if (Build.VERSION.SDK_INT < 34) {
            toast("Uprawnienie ekranu alarmowego działa automatycznie w tej wersji Androida.");
            return;
        }
        android.app.NotificationManager nm=getSystemService(android.app.NotificationManager.class);
        if (nm!=null && nm.canUseFullScreenIntent()) {
            toast("Zgoda na ekran alarmu została już przyznana.");
            return;
        }
        new AlertDialog.Builder(this)
          .setTitle("Czerwony przycisk na zablokowanym ekranie")
          .setMessage("Android może wymagać zezwolenia na pełnoekranowe alarmy. Włącz pozwolenie "
            +"dla Folder Udostępniony, aby podczas szukania telefonu pojawiał się czerwony przycisk. "
            +"Jeśli Android odmawia, przycisk Wycisz pozostaje w powiadomieniu.")
          .setNegativeButton("Anuluj",null)
          .setPositiveButton("Otwórz uprawnienie",(d,w) -> {
              try {
                  android.content.Intent i=new android.content.Intent(
                      android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                  i.setData(android.net.Uri.parse("package:"+getPackageName()));
                  startActivity(i);
              } catch(Exception e) {
                  startActivity(new android.content.Intent(
                      android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                      android.net.Uri.parse("package:"+getPackageName())));
              }
          }).show();
    }
    private void findPermissions() {''')
# OnResume, re-start service requested earlier in app settings (after process recreation).
amod('''        foregroundActivity=true;
        hostMonitor.removeCallbacks(hostMonitorTask);''',
'''        foregroundActivity=true;
        if (getSharedPreferences("finder",MODE_PRIVATE)
                .getBoolean("monitor_requested",false)
                && !FindPhoneService.isActive(this)
                && (Build.VERSION.SDK_INT<33 || checkSelfPermission(
                      android.Manifest.permission.POST_NOTIFICATIONS)
                          ==PackageManager.PERMISSION_GRANTED)) {
            startFinderService(FindPhoneService.START);
        }
        hostMonitor.removeCallbacks(hostMonitorTask);''')
activity.write_text(m,encoding="utf-8")

b=Path("app/build.gradle")
g=b.read_text(encoding="utf-8")
assert "versionCode 11" in g and "versionName '11.0'" in g
b.write_text(g.replace("versionCode 11","versionCode 12").replace("versionName '11.0'","versionName '12.0'"),encoding="utf-8")
print("v12 patch: original gallery retained, checkmarks kept; authenticated UDP listener with wifi locks; loud lock-screen alarm with RED STOP button, full-screen permission and vibration")
