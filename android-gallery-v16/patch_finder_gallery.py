from pathlib import Path

# Preserve the existing v15 gallery grid, upload/cancel, selection and delete
# implementation; relocate only transfer controls and update user-visible text.
gallery=Path("app/src/main/java/pl/folderlan/android/GalleryPanel.java")
s=gallery.read_text(encoding="utf-8")
def replace(old,new):
    global s
    if s.count(old)!=1:
        raise RuntimeError("Gallery v16 missing/ambiguous anchor %d: %s" % (s.count(old),old[:95]))
    s=s.replace(old,new,1)

start=s.index('        Button extraActions = button("Opcje", v -> {')
end=s.index('        heading.addView(extraActions, new LinearLayout.LayoutParams(dp(78), dp(52)));',start)
end+=len('        heading.addView(extraActions, new LinearLayout.LayoutParams(dp(78), dp(52)));\n')
s=s[:start]+s[end:]

# Move the existing COPY SELECTED action from below the gallery to above it
# without changing its handler or checkbox semantics.
start=s.index('        copy = button("Kopiuj do folderu (0)", v -> {')
end=s.index('        body.addView(copy, new LinearLayout.LayoutParams(-1, dp(58)));',start)
end+=len('        body.addView(copy, new LinearLayout.LayoutParams(-1, dp(58)));\n')
copy_block=s[start:end]
copy_block=copy_block.replace('Kopiuj do folderu (0)','Kopiuj do folderu komputera (0)')
copy_block=copy_block.replace('        body.addView(copy, new LinearLayout.LayoutParams(-1, dp(58)));\n','')
s=s[:start]+s[end:]

replace('        body.addView(actions);\n',
'''        body.addView(actions);

'''+copy_block+'''
        LinearLayout transferRow = new LinearLayout(activity);
        transferRow.setOrientation(LinearLayout.HORIZONTAL);
        transferRow.addView(copy, new LinearLayout.LayoutParams(0, dp(54), 1));

        move = button("Wytnij i wklej do folderu komputera", v -> {
            if (copying || selected.isEmpty()) return;
            ArrayList<Item> chosen = new ArrayList<>();
            for (Item item : photos) if (selected.contains(item.uri)) chosen.add(item);
            handler.onMove(chosen);
        });
        move.setTextColor(Color.WHITE);
        move.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(144, 62, 190)));
        transferRow.addView(move, new LinearLayout.LayoutParams(0, dp(54), 1));
        body.addView(transferRow);

        copyAll = button("Kopiuj wszystkie do folderu komputera", v -> {
            if (copying || photos.isEmpty()) return;
            // Copy everything without changing the selection of checkmarked files.
            setCopying(true);
            handler.onCopy(new ArrayList<>(photos));
        });
        copyAll.setTextColor(Color.WHITE);
        copyAll.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
        body.addView(copyAll, new LinearLayout.LayoutParams(-1, dp(48)));
''')

replace('    private Button selectAll, clear, copy, cancel, delete, copyAll, move;',
        '    private Button selectAll, clear, copy, cancel, delete, copyAll, move;')
# Above no-op: retain fields defined by v15 patch.
replace('        copy.setText("Kopiuj do folderu (" + selected.size() + ")");',
        '        copy.setText("Kopiuj do folderu komputera (" + selected.size() + ")");')
replace('        copy.setEnabled(false);\n        loadGallery();',
'''        copy.setEnabled(false);
        move.setEnabled(false);
        copyAll.setEnabled(false);
        loadGallery();''')
replace('''        copy.setText("Kopiuj do folderu komputera (" + selected.size() + ")");
    }''',
'''        copy.setText("Kopiuj do folderu komputera (" + selected.size() + ")");
        move.setEnabled(!copying && !selected.isEmpty());
        copyAll.setEnabled(!copying && !photos.isEmpty());
        copyAll.setText("Kopiuj wszystkie do folderu komputera (" + photos.size() + ")");
    }''')
assert "selected.removeAll(successes);" not in s, "Regression: checkmarks must remain selected"
assert '"Opcje"' not in s, "Extra actions must be visible"
gallery.write_text(s,encoding="utf-8")

# Delete confirmation must remain, but omit the redundant *first* move prompt.
main=Path("app/src/main/java/pl/folderlan/android/MainActivity.java")
m=main.read_text(encoding="utf-8")
start=m.index('    private void confirmMovePhotos(List<GalleryPanel.Item> batch) {')
end=m.index('    private void uploadToWindows(List<GalleryPanel.Item> batch) {',start)
m=m[:start]+'''    private void confirmMovePhotos(List<GalleryPanel.Item> batch) {
        if (batch==null || batch.isEmpty() || busy) return;
        // "Wytnij" starts transfer immediately. Removal still needs confirmation
        // and is offered only after copies finish and pass successful SMB writes.
        if (gallery!=null) gallery.setCopying(true);
        uploadToWindows(batch,true);
    }
'''+m[end:]
assert "confirmPhoneDelete(copiedForMove);" in m
main.write_text(m,encoding="utf-8")

# Keep Android FindPhoneService in the foreground and with CPU awake when user
# explicitly enables the listener. Stops and restores all resources on shutdown.
svc=Path("app/src/main/java/pl/folderlan/android/FindPhoneService.java")
f=svc.read_text(encoding="utf-8")
def service(old,new):
    global f
    if f.count(old)!=1: raise RuntimeError("FindPhone v16 service anchor count %d: %s" % (f.count(old),old[:100]))
    f=f.replace(old,new,1)
service('    private PowerManager.WakeLock alarmWakeLock;\n',
'''    private PowerManager.WakeLock alarmWakeLock;
    private PowerManager.WakeLock listenerWakeLock;
''')
service('''        acquireWifiLocks();
        receiver = new Thread(() -> {''',
'''        acquireWifiLocks();
        acquireListenerWakeLock();
        receiver = new Thread(() -> {''')
service('''                releaseWifiLocks();
            }
        }, "folderlan-find-phone-udp");''',
'''                releaseWifiLocks();
                releaseListenerWakeLock();
            }
        }, "folderlan-find-phone-udp");''')
service('''    private void acquireWifiLocks() {''',
'''    private void acquireListenerWakeLock() {
        try {
            PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);
            if(manager==null) return;
            listenerWakeLock=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                               "FolderLAN:PhoneFinderListener");
            listenerWakeLock.setReferenceCounted(false);
            listenerWakeLock.acquire();
        } catch (Exception ignored) { }
    }
    private void releaseListenerWakeLock() {
        try {
            if (listenerWakeLock!=null && listenerWakeLock.isHeld())
                listenerWakeLock.release();
        } catch (Exception ignored) { }
        listenerWakeLock=null;
    }
    private void acquireWifiLocks() {''')
service('''        releaseWifiLocks();
        stopRinging();''',
'''        releaseWifiLocks();
        releaseListenerWakeLock();
        stopRinging();''')
svc.write_text(f,encoding="utf-8")

# Reduce unnecessary "OK" dialog after enabling listening, keep user controls.
m=main.read_text(encoding="utf-8")
start=m.index('        new AlertDialog.Builder(this)\n          .setTitle("Znajdowanie telefonu włączone")')
end=m.index('          .setPositiveButton("OK",null).show();',start)+len('          .setPositiveButton("OK",null).show();')
m=m[:start]+'        toast("Nasłuch Znajdź telefon jest włączony.");'+m[end:]
# An explicit battery exemption/settings button for uninterrupted WiFi listener.
anchor='        main.addView(findExtras);\n'
if m.count(anchor)!=1:raise RuntimeError("MainActivity finder extra button anchor mismatch")
m=m.replace(anchor,anchor+'''        main.addView(action("Zezwól na działanie w tle / bateria", BLUE,
            this::findPhoneBatterySettings),new LinearLayout.LayoutParams(-1,dp(46)));
''',1)
anchor='    private void findPermissions() {\n'
if m.count(anchor)!=1:raise RuntimeError("MainActivity findPermissions anchor mismatch")
m=m.replace(anchor,'''    private void findPhoneBatterySettings() {
        // Only the user can grant unrestricted background battery access.
        try {
            android.os.PowerManager pm=(android.os.PowerManager)getSystemService(POWER_SERVICE);
            if(pm!=null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent request=new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                request.setData(Uri.parse("package:"+getPackageName()));
                startActivity(request);
            } else {
                toast("Wyjątek od optymalizacji baterii jest już aktywny.");
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:"+getPackageName())));
            }
        }
    }
    private void findPermissions() {
''',1)
main.write_text(m,encoding="utf-8")

manifest=Path("app/src/main/AndroidManifest.xml")
xml=manifest.read_text(encoding="utf-8")
def man(old,new):
    global xml
    if xml.count(old)!=1:raise RuntimeError("AndroidManifest anchor mismatch "+old)
    xml=xml.replace(old,new,1)
man('<uses-permission android:name="android.permission.WAKE_LOCK" />',
'''<uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />''')
man('<service android:name=".FindPhoneService"',
'''<receiver android:name=".FindPhoneBootReceiver"
                  android:enabled="true"
                  android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
        <service android:name=".FindPhoneService"''')
manifest.write_text(xml,encoding="utf-8")

gradle=Path("app/build.gradle")
cfg=gradle.read_text(encoding="utf-8")
assert "versionCode 15" in cfg and "versionName '15.0'" in cfg
gradle.write_text(cfg.replace("versionCode 15","versionCode 16")
                     .replace("versionName '15.0'","versionName '16.0'"),encoding="utf-8")
print("FolderLAN v16: direct gallery actions, fewer confirmations, persistent find-phone WiFi/CPU listener, optional battery grant & boot receiver")
