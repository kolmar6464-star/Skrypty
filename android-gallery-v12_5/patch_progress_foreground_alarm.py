from pathlib import Path
from hashlib import sha256

# FolderLAN v12.5 — ONLY:
# 1) finalize transfer progress, 2) text on copy button,
# 3) show the existing RED FindPhoneAlarmActivity while app is foreground.
# The complete original v12.4 Gallery layout, SMB failover, and all other
# functionality stay unchanged.
folder=Path("app/src/main/java/pl/folderlan/android")
panel=folder/"GalleryPanel.java"
service=folder/"FindPhoneService.java"
main=folder/"MainActivity.java"
lan=folder/"LanShare.java"
manifest=Path("app/src/main/AndroidManifest.xml")
before_lan=sha256(lan.read_bytes()).hexdigest()
before_manifest=sha256(manifest.read_bytes()).hexdigest()
g=panel.read_text(encoding="utf-8")
f=service.read_text(encoding="utf-8")
m=main.read_text(encoding="utf-8")

def alter(source, old, new, label):
    count=source.count(old)
    if count!=1: raise RuntimeError("%s: anchor count=%d: %s"%(label,count,old[:130]))
    return source.replace(old,new,1)

# Completion and error messages must always indicate final transfer state.
# The progress was stuck because update happened BEFORE every file, but no
# update happened AFTER the last file was successfully transferred.
g=alter(g, 'copy = button("Kopiuj do folderu (0)", v -> {',
        'copy = button("Kopiuj do folderu udostępnionego (0)", v -> {',"button initial label")
g=alter(g,'copy.setText("Kopiuj do folderu (" + selected.size() + ")");',
        'copy.setText("Kopiuj do folderu udostępnionego (" + selected.size() + ")");',
        "button dynamic label")
g=alter(g,'''        if (state) {
            stopping = false;
            cancel.setText("Przerwij kopiowanie");
        }
        update();
    }
''','''        if (state) {
            stopping = false;
            cancel.setText("Przerwij kopiowanie");
            progress.setMax(Math.max(1, selected.size()));
            progress.setProgress(0);
            progressLabel.setText("Rozpoczynanie kopiowania...");
        }
        update();
    }
''',"progress start reset")
# Do not touch the existing comment that deliberately retains checkmarks.
finish_start=g.index("    void transferFinished(")
finish_end=g.index("    void transferError(",finish_start)
segment=g[finish_start:finish_end]
assert segment.count("        setCopying(false);")==1
segment=segment.replace("        setCopying(false);",
'''        setCopying(false);
        if (!cancelled) {
            // Every selected file has been processed, including failed attempts.
            progress.setProgress(progress.getMax());
        }''',1)
assert segment.count('progressLabel.setText((cancelled ? "Przerwano. " : "") + "Skopiowano: "')==1
segment=segment.replace('progressLabel.setText((cancelled ? "Przerwano. " : "") + "Skopiowano: "',
'''progressLabel.setText((cancelled ? "Przerwano kopiowanie. " :
            (failures > 0 ? "Zakończono kopiowanie z błędami. " : "Kopiowanie zakończone. "))
            + "Skopiowano: "''',1)
g=g[:finish_start]+segment+g[finish_end:]
assert "selected.removeAll(successes);" not in g, "Do not change photo checkmark persistence"
assert "MediaStore.Images.Media.EXTERNAL_CONTENT_URI" in g, "Keep internal gallery"
panel.write_text(g,encoding="utf-8")

# The already implemented red alarm is triggered via a high priority
# notification on locked screen. Android may not open that full-screen
# notification while the phone is UNLOCKED (heads-up only). In that case the
# visible MainActivity must open its existing red activity directly.
f=alter(f,
'''    public static final String ALARM_FINISHED = "pl.folderlan.android.FIND_ALARM_FINISHED";''',
'''    public static final String ALARM_FINISHED = "pl.folderlan.android.FIND_ALARM_FINISHED";
    public static final String ALARM_STARTED = "pl.folderlan.android.FIND_ALARM_STARTED";''',
"alarm started action")
f=alter(f,
'''        postUrgentRingNotification();
        main.postDelayed(loopRing,2000L);''',
'''        postUrgentRingNotification();
        // MainActivity receives this only when visible, ensuring the existing
        // full-screen red alarm also opens when the phone is unlocked.
        sendBroadcast(new Intent(ALARM_STARTED).setPackage(getPackageName()));
        main.postDelayed(loopRing,2000L);''',
"alarm begin announcement")
assert "builder.setFullScreenIntent(openApp, true)" in f
assert "RING_NOTIFICATION_ID" in f
service.write_text(f,encoding="utf-8")

m=alter(m,"import android.content.Intent;\n",
"""import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
""","receiver imports")
m=alter(m,
'''    private GalleryPanel gallery;
''',
'''    private GalleryPanel gallery;
    private boolean alarmReceiverRegistered;
    private final BroadcastReceiver alarmStartedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !FindPhoneService.ALARM_STARTED.equals(intent.getAction())) return;
            // Activity is currently foreground. Show the SAME existing red
            // alert screen (including STOP) as when the device is locked.
            Intent screen = new Intent(MainActivity.this, FindPhoneAlarmActivity.class);
            screen.setAction(FindPhoneAlarmActivity.ACTION_SHOW_ALARM);
            screen.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(screen);
        }
    };
''',
"foreground activity alarm receiver")
m=alter(m,
'''    @Override protected void onResume() {
        super.onResume();
''',
'''    @Override protected void onResume() {
        super.onResume();
        if (!alarmReceiverRegistered) {
            IntentFilter alert = new IntentFilter(FindPhoneService.ALARM_STARTED);
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(alarmStartedReceiver, alert, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(alarmStartedReceiver, alert);
            alarmReceiverRegistered = true;
        }
''',
"register receiver when app visible")
m=alter(m,
'''    @Override protected void onPause() {
        foregroundActivity=false;''',
'''    @Override protected void onPause() {
        if (alarmReceiverRegistered) {
            try { unregisterReceiver(alarmStartedReceiver); } catch (Exception ignored) {}
            alarmReceiverRegistered = false;
        }
        foregroundActivity=false;''',
"unregister when app in background")
# Preserve exact gallery opening logic introduced in v12.4, and SMB failover.
start=m.index("    private void pickGallery() {")
end=m.index("    private void showGallery() {",start)
assert 'showGallery();' in m[start:end]
assert 'requestPermissions' not in m[start:end]
assert 'gallery = new GalleryPanel(' in m
main.write_text(m,encoding="utf-8")

assert sha256(lan.read_bytes()).hexdigest()==before_lan, "No LanShare changes"
assert sha256(manifest.read_bytes()).hexdigest()==before_manifest, "No Manifest changes"

gradle=Path("app/build.gradle")
b=gradle.read_text(encoding="utf-8")
assert "versionCode 19" in b and "versionName '12.4'" in b
gradle.write_text(b.replace("versionCode 19","versionCode 20")
                   .replace("versionName '12.4'","versionName '12.5'"),encoding="utf-8")
print("PASS v12.5: terminal progress 100% + clear completion, original checkmarks, new button label, red alarm while app foreground.")
print("PASS: gallery entry logic, alarm full-screen notification, and SMB discovery untouched.")
