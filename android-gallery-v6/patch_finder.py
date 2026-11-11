from pathlib import Path
p=Path('app/src/main/java/pl/folderlan/android/MainActivity.java')
s=p.read_text(encoding='utf-8')
def replace(old,new):
    global s
    if old not in s: raise RuntimeError('MainActivity finder anchor missing '+old[:110])
    s=s.replace(old,new,1)
replace('    private static final int READ_MEDIA_PERMISSION = 707;\n',
        '    private static final int READ_MEDIA_PERMISSION = 707;\n    private static final int NOTIFY_PERMISSION = 713;\n')
replace('    private Button selectAllMediaButton, clearSelectionButton, copyToGalleryButton;\n',
        '    private Button selectAllMediaButton, clearSelectionButton, copyToGalleryButton, finderOnButton, finderOffButton;\n')
replace('        main.addView(secondRow);\n',
'''        main.addView(secondRow);

        TextView findHeader = label("ZNAJDŹ TELEFON Z KOMPUTERA", 12);
        findHeader.setTypeface(null, Typeface.BOLD);
        findHeader.setTextColor(MUTED);
        main.addView(findHeader);
        TextView findHint = label("Włącz nasłuch Wi-Fi, aby komputer mógł uruchomić dźwięk i wibracje w telefonie. Sygnał potrwa do 45 sekund.", 12);
        main.addView(findHint);
        LinearLayout findRow = new LinearLayout(this);
        findRow.setOrientation(LinearLayout.HORIZONTAL);
        finderOnButton = action("Włącz znajdowanie", TEAL, this::enableFindPhone);
        finderOffButton = action("Wyłącz", MUTED, this::disableFindPhone);
        findRow.addView(finderOnButton,new LinearLayout.LayoutParams(0,dp(54),2));
        findRow.addView(finderOffButton,new LinearLayout.LayoutParams(0,dp(54),1));
        main.addView(findRow);
        LinearLayout findExtras = new LinearLayout(this);
        findExtras.setOrientation(LinearLayout.HORIZONTAL);
        findExtras.addView(action("Test dźwięku", BLUE, this::testFindPhone),
                   new LinearLayout.LayoutParams(0,dp(51),1));
        findExtras.addView(action("Uprawnienia", MUTED, this::findPermissions),
                   new LinearLayout.LayoutParams(0,dp(51),1));
        main.addView(findExtras);
        finderOnButton.setContentDescription("Włącz odbieranie sygnału znajdowania z komputera");
        finderOffButton.setContentDescription("Wyłącz odbieranie sygnału znajdowania");
''')
replace('    private void disconnect() {\n',
'''    private void enableFindPhone() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_PERMISSION);
            return;
        }
        startFinderService(FindPhoneService.START);
        new AlertDialog.Builder(this)
          .setTitle("Znajdowanie telefonu włączone")
          .setMessage("Telefon nasłuchuje na lokalnym Wi-Fi. Komputer może uruchomić dzwonek i wibracje przez opcję Znajdź telefon. "
              +"Aplikacja powinna mieć widoczne powiadomienie pracy w tle. Jeśli bateria lub tryb Nie przeszkadzać blokuje dźwięk, sprawdź ustawienia Androida.")
          .setPositiveButton("OK",null).show();
    }
    private void disableFindPhone() {
        startFinderService(FindPhoneService.STOP);
        toast("Wyłączono znajdowanie telefonu.");
    }
    private void testFindPhone() {
        if (!FindPhoneService.isActive(this)) {
            toast("Najpierw wybierz Włącz znajdowanie.");
            return;
        }
        startFinderService(FindPhoneService.TEST);
        toast("Włączono sygnał testowy. Aby uciszyć, użyj powiadomienia alarmu.");
    }
    private void findPermissions() {
        new AlertDialog.Builder(this)
          .setTitle("Uprawnienia znajdowania telefonu")
          .setMessage("Wymagane: powiadomienia oraz działanie usługi podczas aktywnego nasłuchu. "
              +"Dźwięk jest odtwarzany przez głośnik telefonu zgodnie z jego ustawieniami. "
              +"Nie można automatycznie ominąć trybu Nie przeszkadzać. "
              +"W ustawieniach możesz zezwolić aplikacji na działanie bez ograniczeń baterii.")
          .setPositiveButton("Ustawienia aplikacji", (d,w) -> {
              android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()));
              startActivity(i);
          })
          .setNeutralButton("Ustawienia Nie przeszkadzać", (d,w) -> {
              startActivity(new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
          })
          .setNegativeButton("Zamknij",null).show();
    }
    private void startFinderService(String action) {
        android.content.Intent intent = new android.content.Intent(this, FindPhoneService.class).setAction(action);
        try {
            if(Build.VERSION.SDK_INT>=26 && (FindPhoneService.START.equals(action) || FindPhoneService.TEST.equals(action))) {
                startForegroundService(intent);
            } else startService(intent);
        } catch(Exception e) {
            toast("Nie można uruchomić alarmu: " + e.getMessage());
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (finderOnButton!=null && finderOffButton!=null) {
            boolean active=FindPhoneService.isActive(this);
            finderOnButton.setText(active ? "Nasłuch aktywny" : "Włącz znajdowanie");
            finderOffButton.setEnabled(active);
        }
    }
    private void disconnect() {
''')
replace('        if(request==READ_MEDIA_PERMISSION) showGallery();\n',
'''        if(request==READ_MEDIA_PERMISSION) showGallery();
        if(request==NOTIFY_PERMISSION) startFinderService(FindPhoneService.START);
''')
p.write_text(s,encoding='utf-8')

p=Path('app/src/main/AndroidManifest.xml')
m=p.read_text(encoding='utf-8')
needle='    <uses-permission android:name="android.permission.INTERNET" />'
if needle not in m: raise RuntimeError('Manifest missing permission')
m=m.replace(needle,needle+'''
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.VIBRATE" />
''',1)
needle='        <activity android:name=".MainActivity"'
if needle not in m: raise RuntimeError('Manifest missing MainActivity')
m=m.replace(needle,
'''        <service android:name=".FindPhoneService"
                 android:exported="false"
                 android:foregroundServiceType="connectedDevice" />
''' + needle,1)
p.write_text(m,encoding='utf-8')
p=Path('app/build.gradle')
g=p.read_text(encoding='utf-8')
if "versionCode 5" not in g:raise RuntimeError('Version 5 expected')
g=g.replace('versionCode 5','versionCode 6').replace("versionName '5.0'","versionName '6.0'")
p.write_text(g,encoding='utf-8')
print('Finder notification permissions, authenticated signal, app buttons, v6 metadata configured.')
