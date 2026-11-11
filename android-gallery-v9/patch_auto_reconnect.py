from pathlib import Path
p=Path("app/src/main/java/pl/folderlan/android/LanShare.java")
s=p.read_text(encoding="utf-8")
def substitute(old, new):
    global s
    if old not in s: raise RuntimeError("LanShare v9 patch: missing anchor "+old[:90])
    s=s.replace(old,new,1)
substitute("""    void connect(Context ctx) throws Exception {
        Exception previous=null;""",
"""    boolean isSameServer(Host latest) {
        Host previous=this.host;
        return previous!=null && latest!=null
            && previous.id.equals(latest.id)
            && previous.name.equalsIgnoreCase(latest.name)
            && previous.ip.equals(latest.ip);
    }

    /** Must only use Host results authenticated with signed UDP discovery. */
    void connectVerified(Context ctx, Host verified) throws Exception {
        if (verified==null) throw new IllegalArgumentException("Brak tożsamości serwera");
        connectKnown(verified);
        rememberVerified(ctx,verified);
    }

    private void rememberVerified(Context ctx, Host current) {
        ctx.getSharedPreferences("folderlan_last_pc",Context.MODE_PRIVATE).edit()
            .putString("ip",current.ip).putString("name",current.name)
            .putString("id",current.id).apply();
    }

    void forgetOutdatedHost(Context ctx) {
        ctx.getSharedPreferences("folderlan_last_pc",Context.MODE_PRIVATE)
            .edit().clear().apply();
    }

    void connect(Context ctx) throws Exception {
        Exception previous=null;""")
substitute("""            connectKnown(discovered);
            ctx.getSharedPreferences("folderlan_last_pc",Context.MODE_PRIVATE).edit()
                .putString("ip",discovered.ip).putString("name",discovered.name)
                .putString("id",discovered.id).apply();""",
"""            connectKnown(discovered);
            rememberVerified(ctx,discovered);""")
substitute("""            } catch (Exception e) {
                previous=e;
            }
        }
        try {
            Host discovered=discover(ctx);""",
"""            } catch (Exception e) {
                previous=e;
                // A reinstall may replace the original SMB host ID and credentials.
                // Purge stale identity and re-discover using the existing pairing key.
                forgetOutdatedHost(ctx);
            }
        }
        try {
            Host discovered=discover(ctx);""")
p.write_text(s,encoding="utf-8")

p=Path("app/src/main/java/pl/folderlan/android/MainActivity.java")
s=p.read_text(encoding="utf-8")
def substitute2(old,new):
    global s
    if old not in s: raise RuntimeError("MainActivity v9 patch: missing anchor "+old[:100])
    s=s.replace(old,new,1)
substitute2("    private LanShare remote;\n",
"""    private volatile LanShare remote;
    private volatile boolean autoReconnection=true;
    private volatile boolean foregroundActivity;
    private final android.os.Handler hostMonitor=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hostMonitorTask=new Runnable() {
        @Override public void run() {
            if (!foregroundActivity) return;
            if (autoReconnection && !busy && gallery==null) {
                busy=true;
                updateRemoteSelectionUi();
                executor.execute(() -> {
                    try { monitorCurrentHost(); }
                    finally {
                        runOnUiThread(() -> {
                            busy=false;
                            updateRemoteSelectionUi();
                        });
                    }
                });
            }
            hostMonitor.postDelayed(this,30000L);
        }
    };
""")
substitute2("""    private void connect() {
        work("Wyszukiwanie folderu w sieci Wi-Fi…", () -> {""",
"""    private void connect() {
        autoReconnection=true;
        work("Wyszukiwanie folderu w sieci Wi-Fi…", () -> {""")
# Add manual "rescan" is existing Połącz, no new button
substitute2("""        firstRow.addView(action("Połącz", BLUE, this::connect), new LinearLayout.LayoutParams(0, dp(55), 1));""",
"""        firstRow.addView(action("Szukaj / Połącz", BLUE, this::connect), new LinearLayout.LayoutParams(0, dp(55), 1));""")
substitute2("""    private void refresh() {
        work("Odczyt plików na komputerze…", this::refreshInternal);
    }""",
"""    /**
     * Watchdog runs while the app is visible, without storing a fixed Windows
     * hostname/IP. Signed replies give current host ID, SMB name and IP.
     * Reinstalling the host creates a new host ID; the pairing key stays the same.
     * Never copy files during a rescan and never erase the old local gallery.
     */
    private void monitorCurrentHost() {
        LanShare earlier=remote;
        try {
            if (earlier==null) {
                reconnectWithLiveHost(null);
                return;
            }
            LanShare.Host live=earlier.discover(getApplicationContext());
            if (!earlier.isSameServer(live)) {
                reconnectWithLiveHost(live);
                return;
            }
            // The discovery server is alive; also check its SMB share.
            try {
                earlier.list("");
            } catch (Exception e) {
                reconnectWithLiveHost(live);
            }
        } catch (Exception discoveryError) {
            if (earlier==null) {
                setStatus("Szukam komputera w Wi-Fi… Automatyczna ponowna próba za 30 sekund.");
                return;
            }
            try {
                // UDP could be blocked even though existing SMB session works.
                earlier.list("");
            } catch (Exception smbError) {
                // Clear dead SMB handle, retry from scratch at next check.
                if (remote==earlier) {
                    remote=null;
                    earlier.close();
                    runOnUiThread(() -> {
                        currentPath="";
                        selectedRemote.clear();
                        items.removeAllViews();
                        pathLabel.setText("Folder: /");
                        updateRemoteSelectionUi();
                    });
                }
                setStatus("Serwer niedostępny. Automatyczne wyszukiwanie co 30 sekund.");
            }
        }
    }

    private void reconnectWithLiveHost(LanShare.Host verified) throws Exception {
        LanShare replacement=new LanShare(readPairingAsset());
        try {
            if (verified==null) replacement.connect(getApplicationContext());
            else replacement.connectVerified(getApplicationContext(),verified);
        } catch (Exception failed) {
            replacement.close();
            throw failed;
        }
        LanShare earlier=remote;
        remote=replacement;
        currentPath="";
        selectedRemote.clear();
        if (earlier!=null) earlier.close();
        setStatus("Wykryto aktualny komputer: "+replacement.hostLabel()
              +". Zasoby folderu zostały odświeżone.");
        refreshInternal();
    }

    private void refresh() {
        work("Odczyt plików na komputerze…", this::refreshInternal);
    }""")
substitute2("""        final String location = currentPath;
        final List<LanShare.Entry> listing = linked.list(location);
        runOnUiThread(() -> {""",
"""        String activePath=currentPath;
        List<LanShare.Entry> activeListing;
        try {
            activeListing=linked.list(activePath);
        } catch (Exception staleConnection) {
            // On a replaced Windows installation the previous directories may
            // no longer exist. Reconnect and show the root of the new share.
            try {
                LanShare recent=new LanShare(readPairingAsset());
                try { recent.connect(getApplicationContext()); }
                catch (Exception e) { recent.close(); throw e; }
                remote=recent;
                if (linked!=recent) linked.close();
                currentPath="";
                selectedRemote.clear();
                activePath="";
                activeListing=recent.list("");
                setStatus("Po zmianie komputera przywrócono połączenie: "
                    +recent.hostLabel()+". Pokazuję aktualne zasoby.");
            } catch (Exception recheckFailed) {
                setStatus("Nie można otworzyć poprzedniego serwera. "
                    +"Ponawiam wykrywanie w Wi-Fi. "+recheckFailed.getMessage());
                return;
            }
        }
        final String location=activePath;
        final List<LanShare.Entry> listing=activeListing;
        runOnUiThread(() -> {""")
substitute2("""        new AlertDialog.Builder(this).setTitle("Rozłączyć folder?")""",
"""        new AlertDialog.Builder(this).setTitle("Rozłączyć folder?")""") if False else None
substitute2("""          .setPositiveButton("Rozłącz",(d,w)->{
              if(remote!=null) remote.close();""",
"""          .setPositiveButton("Rozłącz",(d,w)->{
              autoReconnection=false; // manual disconnect must remain disconnected
              if(remote!=null) remote.close();""")
substitute2("""    @Override protected void onResume() {
        super.onResume();""",
"""    @Override protected void onResume() {
        super.onResume();
        foregroundActivity=true;
        hostMonitor.removeCallbacks(hostMonitorTask);
        hostMonitor.postDelayed(hostMonitorTask,12000L);""")
substitute2("""    private void disconnect() {""",
"""    @Override protected void onPause() {
        foregroundActivity=false;
        hostMonitor.removeCallbacks(hostMonitorTask);
        super.onPause();
    }
    private void disconnect() {""")
substitute2("""    @Override protected void onDestroy() {
        stopGalleryUpload.set(true);""",
"""    @Override protected void onDestroy() {
        foregroundActivity=false;
        hostMonitor.removeCallbacks(hostMonitorTask);
        stopGalleryUpload.set(true);""")
p.write_text(s,encoding="utf-8")
p=Path("app/build.gradle")
g=p.read_text(encoding="utf-8")
if "versionCode 8" not in g: raise RuntimeError("Expected version 8")
g=g.replace("versionCode 8","versionCode 9").replace("versionName '8.0'","versionName '9.0'")
p.write_text(g,encoding="utf-8")
print("FolderLAN AUTO v9: host identity refresh, signed auto rediscovery, stale SMB recovery")
