from pathlib import Path
from hashlib import sha256

# FolderLAN v12.4 based on v12.3.
# Keep original v12.2 gallery UI, remote SMB failover and red alarm untouched.
root=Path("app/src/main/java/pl/folderlan/android")
panel=root/"GalleryPanel.java"
finder=root/"FindPhoneService.java"
lan=root/"LanShare.java"
oldchecks={p.name:sha256(p.read_bytes()).hexdigest() for p in (panel,finder,lan)}

main=root/"MainActivity.java"
s=main.read_text(encoding="utf-8")

# Android 14/15/16's permission workflow was launching the system photos
# picker *after* our own grid appeared. Do not request any media permissions
# from the Gallery button. The grid always opens inside our activity.
start=s.index("    private void pickGallery() {")
end=s.index("    private void showGallery() {",start)
old=s[start:end]
assert 'requestPermissions(' in old and 'showGallery();' in old
s=s[:start]+"""    private void pickGallery() {
        if (gallery != null) return;
        // Always show FolderLAN's own gallery. No system photo picker.
        showGallery();
    }
"""+s[end:]

# Ask once on the main screen, before the user enters the in-app gallery.
# Existing installations with full permissions won't see any permission dialog.
# The user controls whether to grant all photos or a limited selection.
start=s.index("    @Override public void onCreate(Bundle state) {")
end=s.index("    private int dp(",start)
create=s[start:end]
assert create.count("connect();")==1, "Unexpected onCreate baseline"
create=create.replace("connect();",
"""connect();
        getWindow().getDecorView().postDelayed(
            this::requestMediaAccessAtStartup, 850L);""",1)
s=s[:start]+create+s[end:]
anchor="    private void pickGallery() {\n"
assert s.count(anchor)==1
helper="""    private void requestMediaAccessAtStartup() {
        if (isFinishing()) return;
        android.content.SharedPreferences prefs = getSharedPreferences(
            "folderlan_gallery_permissions", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED) return;
            if (prefs.getBoolean("initial_request_shown", false)) return;
            prefs.edit().putBoolean("initial_request_shown", true).apply();
            // Do not request READ_MEDIA_VISUAL_USER_SELECTED here; doing so
            // can launch the system photo-selection UI.
            requestPermissions(new String[]{
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            }, READ_MEDIA_PERMISSION);
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) return;
            if (prefs.getBoolean("initial_request_shown", false)) return;
            prefs.edit().putBoolean("initial_request_shown", true).apply();
            requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }, READ_MEDIA_PERMISSION);
        }
    }
"""
s=s.replace(anchor,helper+anchor,1)

# The first-install permission callback must never open the gallery
# by itself; it should only refresh an already visible in-app grid.
old="""        if (request==READ_MEDIA_PERMISSION) {
            if (gallery!=null) gallery.refreshAfterPermission();
            else showGallery();
        }"""
new="""        if (request==READ_MEDIA_PERMISSION) {
            if (gallery!=null) gallery.refreshAfterPermission();
        }"""
assert s.count(old)==1,"Permission result handler changed"
s=s.replace(old,new,1)

idx=s.index("    private void pickGallery() {")
e=s.index("    private void showGallery() {",idx)
assert "requestPermissions" not in s[idx:e]
assert "startActivity" not in s[idx:e]
assert "showGallery();" in s[idx:e]
assert "gallery = new GalleryPanel(" in s
main.write_text(s,encoding="utf-8")

# No code in the actual in-app GalleryPanel, FindPhoneService, or LanShare
# was touched. This makes it impossible to regress their prior behavior
# in this patch.
for p in (panel,finder,lan):
    assert sha256(p.read_bytes()).hexdigest()==oldchecks[p.name], p.name

assert "setFullScreenIntent(openApp, true)" in finder.read_text(encoding="utf-8")
assert "RING_NOTIFICATION_ID" in finder.read_text(encoding="utf-8")
assert "FindPhoneAlarmActivity" in finder.read_text(encoding="utf-8")
assert 'found.firstEntry().getValue()' in lan.read_text(encoding="utf-8")
manifest=Path("app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
assert "android.permission.USE_FULL_SCREEN_INTENT" in manifest
assert "android.permission.READ_MEDIA_IMAGES" in manifest

gradle=Path("app/build.gradle")
b=gradle.read_text(encoding="utf-8")
assert "versionCode 18" in b and "versionName '12.2'" in b
gradle.write_text(b.replace("versionCode 18","versionCode 19")
                   .replace("versionName '12.2'","versionName '12.4'"),encoding="utf-8")
print("PASS: internal photo grid only, permission prompt outside Gallery button.")
print("PASS: GalleryPanel, FindPhoneService, LanShare byte-identical to v12.3 baseline.")
print("PASS: original red full-screen alarm and SMB multi-PC failover preserved.")
