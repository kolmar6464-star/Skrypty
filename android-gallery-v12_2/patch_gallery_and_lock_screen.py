from pathlib import Path

# FolderLAN v12.2. Modify only the in-app photo gallery entry point and
# the full-screen alarm notification path from v12.1.
gallery=Path("app/src/main/java/pl/folderlan/android/GalleryPanel.java")
g=gallery.read_text(encoding="utf-8")
anchor="    private void loadGallery() {\n"
if g.count(anchor)!=1:raise RuntimeError("v12.1 gallery unexpected")
g=g.replace(anchor,
"""    /** Reload the built-in grid after Android grants access to photos. */
    void refreshAfterPermission() {
        if (closed) return;
        loadGallery();
    }
    private void loadGallery() {
""",1)
assert "selected.removeAll(successes);" not in g, "Do not restore checkmark removal"
assert "MediaStore.Images.Media.EXTERNAL_CONTENT_URI" in g
gallery.write_text(g,encoding="utf-8")

activity=Path("app/src/main/java/pl/folderlan/android/MainActivity.java")
s=activity.read_text(encoding="utf-8")
def main(old,new):
    global s
    if s.count(old)!=1:raise RuntimeError("MainActivity anchor %d: %s"%(s.count(old),old[:100]))
    s=s.replace(old,new,1)

start=s.index("    private void pickGallery() {")
end=s.index("    private void showGallery() {",start)
original=s[start:end]
assert "requestPermissions" in original and "showGallery();" in original
# The built-in GalleryPanel is always shown first. A one-time Android permission
# dialog is the only system UI potentially needed on first installation.
replacement="""    private void pickGallery() {
        if (gallery != null) return;
        // Open OUR gallery immediately; never invoke an external photo picker.
        showGallery();
        if (Build.VERSION.SDK_INT >= 33) {
            boolean images = checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED;
            boolean partial = Build.VERSION.SDK_INT >= 34 &&
                checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED;
            if (!images && !partial) {
                if (Build.VERSION.SDK_INT >= 34)
                    requestPermissions(new String[]{
                        android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    },READ_MEDIA_PERMISSION);
                else
                    requestPermissions(new String[]{
                        android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO
                    },READ_MEDIA_PERMISSION);
            } else if (!images) {
                // Selected-photos permission: only the selected items are visible.
                // Do not launch the OS photo picker again when opening the gallery.
                toast("Aby zobaczyć wszystkie zdjęcia, ustaw pełny dostęp w uprawnieniach aplikacji.");
            }
        } else if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            },READ_MEDIA_PERMISSION);
        }
    }
"""
s=s[:start]+replacement+s[end:]
main('''        if(request==READ_MEDIA_PERMISSION) showGallery();''',
'''        if (request==READ_MEDIA_PERMISSION) {
            if (gallery!=null) gallery.refreshAfterPermission();
            else showGallery();
        }''')
# On Android 14+, explain a required user-granted full-screen alarm privilege
# once. The existing v12 permission button stays in its original place.
main('''        foregroundActivity=true;
        android.content.SharedPreferences finderPrefs =''',
'''        foregroundActivity=true;
        if (Build.VERSION.SDK_INT >= 34) {
            android.content.SharedPreferences pref=getSharedPreferences("finder",MODE_PRIVATE);
            if (pref.getBoolean("monitor_requested",true)
                    && !pref.getBoolean("fullscreen_help_shown",false)) {
                android.app.NotificationManager nm=getSystemService(android.app.NotificationManager.class);
                if (nm!=null && !nm.canUseFullScreenIntent()) {
                    pref.edit().putBoolean("fullscreen_help_shown",true).apply();
                    requestFullScreenAlarmAccess();
                }
            }
        }
        android.content.SharedPreferences finderPrefs =''')
assert 'gallery.create()' in s
assert 'new Intent(Intent.ACTION_GET_CONTENT)' not in s
activity.write_text(s,encoding="utf-8")

service=Path("app/src/main/java/pl/folderlan/android/FindPhoneService.java")
f=service.read_text(encoding="utf-8")
def find(old,new):
    global f
    if f.count(old)!=1:raise RuntimeError("FindPhoneService anchor %d: %s"%(f.count(old),old[:95]))
    f=f.replace(old,new,1)

find('    private static final int NOTIFICATION_ID = 34793;',
     '    private static final int NOTIFICATION_ID = 34793;\n    private static final int RING_NOTIFICATION_ID = 34794;')
# Android triggers Full Screen Intent only when a fresh high-priority
# notification is POSTED; replacing an existing low-priority foreground
# notification with the same ID can suppress the FSI. Keep the monitor
# notification unchanged at 34793 and use a separate alarm at 34794.
find('''    private void startRing() {
        stopRinging();''',
'''    private void postUrgentRingNotification() {
        try {
            NotificationManager manager=getSystemService(NotificationManager.class);
            if (manager != null)
                manager.notify(RING_NOTIFICATION_ID,notification(true));
        } catch (SecurityException | IllegalArgumentException ignored) {
            // If notifications are denied, only the vibration/audio might work.
        }
    }
    private void cancelUrgentRingNotification() {
        try {
            NotificationManager manager=getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(RING_NOTIFICATION_ID);
        } catch (Exception ignored) {}
    }
    private void startRing() {
        stopRinging();''')
find('''        foreground(true);
        main.postDelayed(loopRing,2000L);''',
'''        // Separate alarm notification triggers lock-screen full-screen UI
        // on devices where the owner has granted full-screen notification access.
        postUrgentRingNotification();
        main.postDelayed(loopRing,2000L);''')
find('''    private void stopRinging() {
        ringUntil=0;''',
'''    private void stopRinging() {
        ringUntil=0;
        cancelUrgentRingNotification();''')
# Existing v12: notification(true) is already ALARM-category/high importance
# with setFullScreenIntent(...) and the RED FindPhoneAlarmActivity.
assert 'builder.setFullScreenIntent(openApp, true);' in f
assert 'alerting ? FindPhoneAlarmActivity.class : MainActivity.class' in f
service.write_text(f,encoding="utf-8")

build=Path("app/build.gradle")
b=build.read_text(encoding="utf-8")
assert "versionCode 17" in b and "versionName '12.1'" in b
build.write_text(b.replace("versionCode 17","versionCode 18").replace("versionName '12.1'","versionName '12.2'"),encoding="utf-8")
print("PASS: v12.2 retains v12.1 UI, immediately opens internal GalleryPanel; independent high-urgency FSI alarm notification and one-time Android 14 permission guidance.")
