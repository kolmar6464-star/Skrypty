from pathlib import Path

# Two changes ONLY to the known-good v12 code:
# (1) automatic persistent find-phone listener;
# (2) read-only file/photo preview in the remote Windows share.

activity = Path("app/src/main/java/pl/folderlan/android/MainActivity.java")
src = activity.read_text(encoding="utf-8")
def main(old,new):
    global src
    n=src.count(old)
    if n!=1: raise RuntimeError("v12.1 MainActivity missing anchor %d: %s"%(n,old[:120]))
    src=src.replace(old,new,1)

# Add separate explicit preview controls without changing tap-to-select, folders or saving.
main("""            row.addView(check,new LinearLayout.LayoutParams(dp(49),dp(55)));
            row.setOnClickListener(v -> { if (!busy) check.setChecked(!check.isChecked()); });""",
"""            Button preview=action("Podgląd",MUTED, () -> {
                if (!busy) RemoteFilePreview.show(this,remote,path,entry.name,entry.size,
                                                    () -> saveFile(path,entry.name));
            });
            preview.setTextSize(11);
            row.addView(preview,new LinearLayout.LayoutParams(dp(82),dp(49)));
            row.addView(check,new LinearLayout.LayoutParams(dp(49),dp(55)));
            row.setOnClickListener(v -> { if (!busy) check.setChecked(!check.isChecked()); });""")
main("""            Button save = action("Pobierz", MUTED, () -> saveFile(path,entry.name));
            row.addView(save,new LinearLayout.LayoutParams(dp(89),dp(50)));""",
"""            Button preview=action("Podgląd",MUTED, () -> {
                if (!busy) RemoteFilePreview.show(this,remote,path,entry.name,entry.size,
                                                    () -> saveFile(path,entry.name));
            });
            preview.setTextSize(11);
            row.addView(preview,new LinearLayout.LayoutParams(dp(77),dp(48)));
            Button save = action("Pobierz", MUTED, () -> saveFile(path,entry.name));
            save.setTextSize(11);
            row.addView(save,new LinearLayout.LayoutParams(dp(77),dp(48)));""")

# v12 had to be enabled manually. Start once automatically and on every foreground
# app resume if the listener has not started. Honor explicit "Wyłącz".
main("""        foregroundActivity=true;
        if (getSharedPreferences("finder",MODE_PRIVATE)
                .getBoolean("monitor_requested",false)
                && !FindPhoneService.isActive(this)
                && (Build.VERSION.SDK_INT<33 || checkSelfPermission(
                      android.Manifest.permission.POST_NOTIFICATIONS)
                          ==PackageManager.PERMISSION_GRANTED)) {
            startFinderService(FindPhoneService.START);
        }
        hostMonitor.removeCallbacks(hostMonitorTask);""",
"""        foregroundActivity=true;
        android.content.SharedPreferences finderPrefs =
            getSharedPreferences("finder",MODE_PRIVATE);
        if (finderPrefs.getBoolean("monitor_requested",true)
                && !FindPhoneService.isActive(this)) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED &&
                !finderPrefs.getBoolean("notify_permission_asked",false)) {
                finderPrefs.edit().putBoolean("notify_permission_asked",true).apply();
                requestPermissions(new String[]{
                    android.Manifest.permission.POST_NOTIFICATIONS},NOTIFY_PERMISSION);
            } else {
                startFinderService(FindPhoneService.START);
            }
        }
        hostMonitor.removeCallbacks(hostMonitorTask);""")
activity.write_text(src,encoding="utf-8")

service = Path("app/src/main/java/pl/folderlan/android/FindPhoneService.java")
finder=service.read_text(encoding="utf-8")
def find(old,new):
    global finder
    n=finder.count(old)
    if n!=1: raise RuntimeError("v12.1 FindPhoneService missing anchor %d: %s"%(n,old[:110]))
    finder=finder.replace(old,new,1)

find("""    private PowerManager.WakeLock alarmWakeLock;
""",
"""    private PowerManager.WakeLock alarmWakeLock;
    private PowerManager.WakeLock listenerWakeLock;
""")
find("""        acquireWifiLocks();
        receiver = new Thread(() -> {""",
"""        acquireWifiLocks();
        acquireListenerWakeLock();
        receiver = new Thread(() -> {""")
find("""                releaseWifiLocks();
            }
        }, "folderlan-find-phone-udp");""",
"""                releaseWifiLocks();
                releaseListenerWakeLock();
            }
        }, "folderlan-find-phone-udp");""")
find("""    private void acquireWifiLocks() {""",
"""    private void acquireListenerWakeLock() {
        try {
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(pm==null) return;
            listenerWakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                          "FolderLAN:AlwaysOnListener");
            listenerWakeLock.setReferenceCounted(false);
            listenerWakeLock.acquire();
        } catch (Exception ignored) {}
    }
    private void releaseListenerWakeLock() {
        try {
            if(listenerWakeLock!=null && listenerWakeLock.isHeld())
                listenerWakeLock.release();
        } catch (Exception ignored) {}
        listenerWakeLock=null;
    }
    private void acquireWifiLocks() {""")
find("""        releaseWifiLocks();
        stopRinging();""",
"""        releaseWifiLocks();
        releaseListenerWakeLock();
        stopRinging();""")
service.write_text(finder,encoding="utf-8")

# Boot receiver does not change the existing v12 alarm screen, notes or UI.
manifest=Path("app/src/main/AndroidManifest.xml")
xml=manifest.read_text(encoding="utf-8")
if xml.count('<uses-permission android:name="android.permission.WAKE_LOCK" />')!=1:
    raise RuntimeError("WAKE_LOCK absent or duplicated")
xml=xml.replace('<uses-permission android:name="android.permission.WAKE_LOCK" />',
'''<uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />''',1)
if xml.count('<service android:name=".FindPhoneService"')!=1:
    raise RuntimeError("Finder service Manifest missing")
xml=xml.replace('<service android:name=".FindPhoneService"',
'''<receiver android:name=".FindPhoneBootReceiver"
                  android:enabled="true"
                  android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
        <service android:name=".FindPhoneService"''',1)
manifest.write_text(xml,encoding="utf-8")

gradle=Path("app/build.gradle")
g=gradle.read_text(encoding="utf-8")
assert "versionCode 12" in g and "versionName '12.0'" in g
gradle.write_text(g.replace("versionCode 12","versionCode 17")
                   .replace("versionName '12.0'","versionName '12.1'"),
                   encoding="utf-8")
print("v12.1: ONLY auto-start/always-on v12 finder and read-only Windows photo/file preview")
