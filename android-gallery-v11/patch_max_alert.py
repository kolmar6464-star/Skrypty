from pathlib import Path

p = Path("app/src/main/java/pl/folderlan/android/FindPhoneService.java")
s = p.read_text(encoding="utf-8")
def change(old, new):
    global s
    if s.count(old) != 1:
        raise RuntimeError("Expected exactly one FindPhoneService anchor: " + old[:100])
    s = s.replace(old, new, 1)

change("import android.media.AudioAttributes;\n",
       "import android.media.AudioAttributes;\nimport android.media.AudioManager;\n")
change("    private Vibrator vibrator;\n",
"""    private Vibrator vibrator;
    private AudioManager alertAudio;
    private int oldAlarmVolume = -1;
    private int oldRingerMode = -1;
    private boolean changedAlarmVolume;
    private boolean changedRingerMode;
    private NotificationManager dndManager;
    private int oldInterruptionFilter = -1;
    private boolean changedDnd;
""")
change("    private void startRing() {\n        stopRinging();\n        ringUntil = SystemClock.elapsedRealtime()+45000L;",
"""    /** Temporarily maximize ALARM audio when the owner requests Find Phone.
     *  Android's Do Not Disturb policy remains subject to an explicit user grant.
     *  All changed settings are restored after ringing or when service stops.
     */
    private void maximizeFindPhoneAudio() {
        alertAudio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (alertAudio == null) return;
        try {
            oldAlarmVolume = alertAudio.getStreamVolume(AudioManager.STREAM_ALARM);
            int maximum = alertAudio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (maximum > oldAlarmVolume) {
                alertAudio.setStreamVolume(AudioManager.STREAM_ALARM, maximum, 0);
                changedAlarmVolume = true;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Audio volume may be locked by device administration or parental controls.
        }
        try {
            oldRingerMode = alertAudio.getRingerMode();
            if (oldRingerMode != AudioManager.RINGER_MODE_NORMAL) {
                alertAudio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                changedRingerMode = true;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Android may require Notification Policy access to change silent mode.
        }
        try {
            dndManager = getSystemService(NotificationManager.class);
            if (dndManager != null && dndManager.isNotificationPolicyAccessGranted()) {
                oldInterruptionFilter = dndManager.getCurrentInterruptionFilter();
                if (oldInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                        && oldInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                    dndManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                    changedDnd = true;
                }
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // DND control requires permission explicitly granted in Android Settings.
        }
    }

    private void restoreFindPhoneAudio() {
        if (dndManager != null && changedDnd && oldInterruptionFilter > 0) {
            try {
                if (dndManager.isNotificationPolicyAccessGranted()
                        && dndManager.getCurrentInterruptionFilter()
                            == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    dndManager.setInterruptionFilter(oldInterruptionFilter);
                }
            } catch (SecurityException | IllegalArgumentException ignored) {}
        }
        changedDnd = false;
        oldInterruptionFilter = -1;
        dndManager = null;
        if (alertAudio != null) {
            if (changedAlarmVolume && oldAlarmVolume >= 0) {
                try { alertAudio.setStreamVolume(AudioManager.STREAM_ALARM,oldAlarmVolume,0); }
                catch (SecurityException | IllegalArgumentException ignored) {}
            }
            if (changedRingerMode && oldRingerMode >= 0) {
                try { alertAudio.setRingerMode(oldRingerMode); }
                catch (SecurityException | IllegalArgumentException ignored) {}
            }
        }
        changedAlarmVolume = false;
        changedRingerMode = false;
        oldAlarmVolume = -1;
        oldRingerMode = -1;
        alertAudio = null;
    }

    private void startRing() {
        stopRinging();
        ringUntil = SystemClock.elapsedRealtime()+45000L;
        maximizeFindPhoneAudio();""")
change("""        try {if(vibrator!=null) vibrator.cancel();} catch(Exception ignored) {}
        vibrator=null;
    }""",
"""        try {if(vibrator!=null) vibrator.cancel();} catch(Exception ignored) {}
        vibrator=null;
        restoreFindPhoneAudio();
    }""")
p.write_text(s, encoding="utf-8")

manifest = Path("app/src/main/AndroidManifest.xml")
xml = manifest.read_text(encoding="utf-8")
tag = '<uses-permission android:name="android.permission.VIBRATE" />'
if xml.count(tag) != 1: raise RuntimeError("Expected VIBRATE permission")
xml = xml.replace(tag,tag+'''
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />''',1)
manifest.write_text(xml, encoding="utf-8")

activity = Path("app/src/main/java/pl/folderlan/android/MainActivity.java")
main = activity.read_text(encoding="utf-8")
old = '"Nie można automatycznie ominąć trybu Nie przeszkadzać. "'
new = '"Tryb Nie przeszkadzać wymaga osobnej zgody Androida. Po przyznaniu dostępu aplikacja spróbuje go wyłączyć na czas alarmu i przywróci potem. "'
if main.count(old) != 1:
    raise RuntimeError("Missing FindPhone permissions description")
main = main.replace(old,new,1)
activity.write_text(main,encoding="utf-8")

gradle=Path("app/build.gradle")
build=gradle.read_text(encoding="utf-8")
if "versionCode 10" not in build or "versionName '10.0'" not in build:
    raise RuntimeError("Expected Android v10 before applying volume patch")
build=build.replace("versionCode 10","versionCode 11").replace("versionName '10.0'","versionName '11.0'")
gradle.write_text(build,encoding="utf-8")
print("v11: max alarm volume, restore audio settings, optional user-granted DND access")
