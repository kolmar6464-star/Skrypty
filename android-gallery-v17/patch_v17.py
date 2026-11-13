from pathlib import Path
import hashlib

root=Path("app/src/main/java/pl/folderlan/android")
gpath=root/"GalleryPanel.java"
mpath=root/"MainActivity.java"
lpath=root/"LanShare.java"
manifest=Path("app/src/main/AndroidManifest.xml")

g=gpath.read_text(encoding="utf-8")
m=mpath.read_text(encoding="utf-8")
l=lpath.read_text(encoding="utf-8")

def replace(s,old,new,tag):
    n=s.count(old)
    if n!=1:raise RuntimeError("v17 %s anchor count %d: %s"%(tag,n,old[:160]))
    return s.replace(old,new,1)

# 1. Display sequential gallery numbers next to checkboxes.
g=replace(g,"        void onDelete(List<Item> entries);\n",
"""        void onDelete(List<Item> entries);
        void onSendToPhone(List<Item> entries);
""","gallery handler")
g=replace(g,"    private Button selectAll, clear, copy, cancel, delete;",
"    private Button selectAll, clear, copy, cancel, delete, sendPhone;","send button field")
g=replace(g,"    private boolean stopping;",
"""    private boolean stopping;
    private boolean galleryLoaded;""","persist gallery loading state")
g=replace(g,"                    tick.setTextSize(23);",
"                    tick.setTextSize(15);","image index typography")
g=replace(g,"FrameLayout.LayoutParams tickParams = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.RIGHT | Gravity.TOP);",
"FrameLayout.LayoutParams tickParams = new FrameLayout.LayoutParams(dp(70), dp(34), Gravity.RIGHT | Gravity.TOP);",
"index width")
g=replace(g,'holder.tick.setText(marked ? "☑" : "□");',
'holder.tick.setText((position+1)+" "+(marked ? "☑" : "□"));',"number with tick")

g=replace(g,"        body.addView(delete, new LinearLayout.LayoutParams(-1, dp(54)));\n",
"""        body.addView(delete, new LinearLayout.LayoutParams(-1, dp(54)));

        sendPhone = button("Wyślij zaznaczone do innego telefonu", v -> {
            if (copying || selected.isEmpty()) return;
            ArrayList<Item> chosen = new ArrayList<>();
            for (Item item : photos) if (selected.contains(item.uri) && !item.video)
                chosen.add(item);
            if (!chosen.isEmpty()) handler.onSendToPhone(chosen);
        });
        sendPhone.setTextColor(Color.WHITE);
        sendPhone.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(98, 77, 174)));
        body.addView(sendPhone,new LinearLayout.LayoutParams(-1,dp(50)));
""","send selected phone visible")
g=replace(g,
"""        copy.setText("Kopiuj do folderu udostępnionego (" + selected.size() + ")");
    }
    /** Reload""",
"""        copy.setText("Kopiuj do folderu udostępnionego (" + selected.size() + ")");
        if (sendPhone!=null)sendPhone.setEnabled(!copying && !selected.isEmpty());
        // Persist checks even if the activity is minimized or recreated.
        org.json.JSONArray saved=new org.json.JSONArray();
        for (Uri uri:selected)saved.put(uri.toString());
        if(galleryLoaded)
            activity.getSharedPreferences("folderlan_gallery_checks",android.content.Context.MODE_PRIVATE)
                .edit().putString("selected",saved.toString()).apply();
    }
    /** Reload""","persist ticks")
g=replace(g,
"""                photos.clear();
                photos.addAll(found);
                adapter.notifyDataSetChanged();""",
"""                photos.clear();
                photos.addAll(found);
                galleryLoaded=true;
                // Restore photo checkmarks after Android killed/recreated MainActivity.
                try {
                    org.json.JSONArray saved=new org.json.JSONArray(
                        activity.getSharedPreferences("folderlan_gallery_checks",
                            android.content.Context.MODE_PRIVATE).getString("selected","[]"));
                    selected.clear();
                    java.util.HashSet<String> known=new java.util.HashSet<>();
                    for(Item entry:photos)known.add(entry.uri.toString());
                    for(int i=0;i<saved.length();i++)
                        if(known.contains(saved.getString(i)))selected.add(Uri.parse(saved.getString(i)));
                }catch(Exception ignored){}
                adapter.notifyDataSetChanged();""","restore ticks")
g=replace(g,"    void transferProgress(int done, int total, String current) {",
"""    void restoreTransfer(int done,int total,int success,int errors,String message,boolean active) {
        if(closed || progress==null)return;
        copying=active;
        stopping=false;
        progress.setMax(Math.max(1,total));
        progress.setProgress(Math.max(0,Math.min(done,total)));
        progressLabel.setText(message==null?"":message);
        update();
    }
    void transferProgress(int done, int total, String current) {""","restore service progress")
gpath.write_text(g,encoding="utf-8")

# 2. Use a service to own photo upload, independent of this Activity instance.
m=replace(m,"    private boolean alarmReceiverRegistered;\n",
"""    private boolean alarmReceiverRegistered;
    private boolean transferReceiverRegistered;
    private boolean summaryDialogVisible;
    private final BroadcastReceiver transferReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            showTransferState();
        }
    };
""","transfer UI receiver")
m=replace(m,'            @Override public void onCopy(List<GalleryPanel.Item> entries) { uploadToWindows(entries); }',
"""            @Override public void onCopy(List<GalleryPanel.Item> entries) {
                TransferService.send(getApplicationContext(),entries);
                showTransferState();
            }
            @Override public void onSendToPhone(List<GalleryPanel.Item> entries) {
                sendToPhone(entries);
            }""","transfer service start")
m=replace(m,
"""                stopGalleryUpload.set(true);
                LanShare current=activeUploadSession;
                if (current!=null) current.abortActiveUpload();
                setStatus("Przerywanie bieżącego transferu SMB…");""",
"""                TransferService.cancel(getApplicationContext());
                setStatus("Przerywanie kopiowania…");""","cancel background service")
m=replace(m,"        root.addView(galleryView,new FrameLayout.LayoutParams(-1,-1));\n    }\n    private void closeGallery() {",
"""        root.addView(galleryView,new FrameLayout.LayoutParams(-1,-1));
        showTransferState();
    }
    private void closeGallery() {""","restore after open gallery")
m=replace(m,'        if (gallery.isCopying()) { toast("Trwa kopiowanie — wybierz Przerwij kopiowanie."); return; }\n',
'''        // TransferService continues and persists progress after the user leaves the gallery.
''',"allow exit while uploading")
m=replace(m,'        main.addView(action("Galeria telefonu — kopiuj do Windows", BLUE, this::pickGallery),\n                new LinearLayout.LayoutParams(-1, dp(56)));\n',
"""        main.addView(action("Galeria telefonu — kopiuj do Windows", BLUE, this::pickGallery),
                new LinearLayout.LayoutParams(-1, dp(56)));

        CheckBox camera= new CheckBox(this);
        camera.setText("✓ Zdjęcie z kamerki do folderu (automatycznie)");
        camera.setChecked(getSharedPreferences(CameraWatcher.PREF,MODE_PRIVATE)
            .getBoolean("enabled",false));
        camera.setOnCheckedChangeListener((view,enabled)->{
            try {
                CameraWatcher.setEnabled(getApplicationContext(),enabled);
                toast(enabled?"Automatyczne zdjęcia z aparatu: WŁĄCZONE":
                    "Automatyczne zdjęcia z aparatu: WYŁĄCZONE");
            }catch(Exception e){toast("Nie można uruchomić monitorowania: "+e.getMessage());}
        });
        main.addView(camera,new LinearLayout.LayoutParams(-1,dp(51)));

        CheckBox phoneExchange=new CheckBox(this);
        phoneExchange.setText("✓ Wymiana zdjęć między telefonami (odbieranie)");
        phoneExchange.setChecked(getSharedPreferences(PhonePeerService.PREF,MODE_PRIVATE)
            .getBoolean("enabled",false));
        phoneExchange.setOnCheckedChangeListener((view,enabled)->{
            try {
                PhonePeerService.setEnabled(getApplicationContext(),enabled);
                toast(enabled?"Ten telefon jest dostępny do wymiany zdjęć.":
                    "Odbieranie zdjęć z innych telefonów wyłączone.");
            }catch(Exception e){toast("Nie można uruchomić wymiany: "+e.getMessage());}
        });
        main.addView(phoneExchange,new LinearLayout.LayoutParams(-1,dp(51)));
""","camera and phone opts")
m=replace(m,
'''    @Override protected void onResume() {
        super.onResume();
''',
'''    @Override protected void onResume() {
        super.onResume();
        if(!transferReceiverRegistered) {
            IntentFilter progress=new IntentFilter(TransferService.STATUS);
            if(Build.VERSION.SDK_INT>=33)
                registerReceiver(transferReceiver,progress,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(transferReceiver,progress);
            transferReceiverRegistered=true;
        }
        showTransferState();
        // Re-register explicitly opted-in services after app process recreation.
        try {
            if(getSharedPreferences(CameraWatcher.PREF,MODE_PRIVATE).getBoolean("enabled",false))
                CameraWatcher.setEnabled(this,true);
            if(getSharedPreferences(PhonePeerService.PREF,MODE_PRIVATE).getBoolean("enabled",false))
                PhonePeerService.setEnabled(this,true);
        }catch(Exception ignored){}
''',"watch transfer state while visible")
m=replace(m,'''        foregroundActivity=true;
        if (Build.VERSION.SDK_INT >= 34) {''',
'''        foregroundActivity=true;
        showTransferState();
        if (Build.VERSION.SDK_INT >= 34) {''',"transfer completion summary after resume")
m=replace(m,
'''    @Override protected void onPause() {
        if (alarmReceiverRegistered) {''',
'''    @Override protected void onPause() {
        if(transferReceiverRegistered) {
            try{unregisterReceiver(transferReceiver);}catch(Exception ignored){}
            transferReceiverRegistered=false;
        }
        if (alarmReceiverRegistered) {''',"transfer unregister")

# New methods before old upload. Retain old implementation unused for backwards compatibility.
m=replace(m,
'''    private void uploadToWindows(List<GalleryPanel.Item> batch) {''',
'''    private void showTransferState() {
        android.content.SharedPreferences state=TransferService.state(this);
        boolean copying=state.getBoolean("active",false);
        int done=state.getInt("done",0),total=state.getInt("total",0);
        int success=state.getInt("success",0),errors=state.getInt("errors",0);
        String message=state.getString("message","Wybierz zdjęcia do skopiowania.");
        if(gallery!=null && total>0)
            gallery.restoreTransfer(done,total,success,errors,message,copying);
        if(state.getBoolean("completedPending",false) && !summaryDialogVisible
                && !isFinishing() && foregroundActivity) {
            summaryDialogVisible=true;
            new AlertDialog.Builder(this)
                .setTitle("Kopiowanie do folderu udostępnionego zakończone")
                .setMessage("Skopiowano zdjęć i plików: "+success+"\\nBłędy: "+errors)
                .setPositiveButton("OK",(d,w)-> {
                    state.edit().putBoolean("completedPending",false).apply();
                    summaryDialogVisible=false;
                })
                .setOnCancelListener(d->{
                    summaryDialogVisible=false;
                    state.edit().putBoolean("completedPending",false).apply();
                }).show();
        }
    }
    private void sendToPhone(List<GalleryPanel.Item> chosen) {
        executor.execute(()->{
            try{
                List<PhonePeerService.Peer> peers=PhonePeerService.discover(this);
                runOnUiThread(()->{
                    if(peers.isEmpty()) {
                        toast("Nie znaleziono drugiego telefonu z włączoną wymianą w tej sieci.");
                        return;
                    }
                    String[] names=new String[peers.size()];
                    for(int i=0;i<peers.size();i++)names[i]=peers.get(i).toString();
                    new AlertDialog.Builder(this).setTitle("Wyślij zdjęcia do telefonu")
                        .setItems(names,(dialog,index)->sendToPeer(chosen,peers.get(index)))
                        .setNegativeButton("Anuluj",null).show();
                });
            }catch(Exception e){runOnUiThread(()->toast(
                "Wyszukiwanie telefonów: "+e.getMessage()));}
        });
    }
    private void sendToPeer(List<GalleryPanel.Item> selected,PhonePeerService.Peer peer) {
        executor.execute(()->{
            int sent=0,failed=0;
            String firstError="";
            for(GalleryPanel.Item item:selected){
                if(item.video)continue;
                try {
                    PhonePeerService.send(this,item.uri,item.name,peer);
                    sent++;
                }catch(Exception e){
                    failed++;
                    if(firstError.isEmpty())firstError=e.getMessage();
                }
            }
            final int ok=sent,errors=failed;
            final String message=firstError;
            runOnUiThread(()->{
                if(isFinishing())return;
                new AlertDialog.Builder(this).setTitle("Wymiana zdjęć z telefonem")
                    .setMessage("Odbiorca: "+peer.name+"\\nWysłano: "+ok+
                        "\\nBłędy: "+errors+(message.isEmpty()?"":"\\n"+message))
                    .setPositiveButton("OK",null).show();
            });
        });
    }
    private void uploadToWindows(List<GalleryPanel.Item> batch) {''',
"service state and peer picker")

mpath.write_text(m,encoding="utf-8")

# 3. Numbers on the SMB host, with separate timestamped folder for camera.
start=l.index("    void uploadGallery(InputStream in, String fileName, AtomicBoolean cancelled) throws Exception {")
end=l.index("    private static final String RESTORE_CONTROL =",start)
existing=l[start:end]
assert "incompleteUploadPath=path;" in existing
assert 'String folder = galleryFolderForToday();' in existing
generic=existing.replace(
    "    void uploadGallery(InputStream in, String fileName, AtomicBoolean cancelled) throws Exception {",
    "    private void uploadInFolder(InputStream in, String folder, String fileName, AtomicBoolean cancelled) throws Exception {",
    1).replace("        String folder = galleryFolderForToday();\n","",1)
generic=generic.replace('byte[] chunk = new byte[65536];','byte[] chunk = new byte[262144];')
generic=generic.replace("                created = true;\n                created = true;","                created = true;")
generic=generic.replace('''            do { path = folder + "\\\\" + stem + "_" + n++ + ext; }
            while (share.fileExists(path));''',
'''            if(stem.matches("[0-9]{1,8}")) {
                int sequence=Integer.parseInt(stem);
                do { path=folder+"\\\\"+(++sequence)+ext; }
                while (share.fileExists(path));
            } else {
                do { path=folder+"\\\\"+stem+"_"+n++ + ext; }
                while (share.fileExists(path));
            }''')
wrapper='''    void uploadGallery(InputStream in, String fileName, AtomicBoolean cancelled) throws Exception {
        uploadInFolder(in,galleryFolderForToday(),fileName,cancelled);
    }
    void uploadCamera(InputStream in,String relative,AtomicBoolean cancelled) throws Exception {
        if(relative==null || !relative.startsWith("Zdjęcie z kamerki\\\\"))
            throw new IllegalArgumentException("Nieprawidłowy folder kamerki");
        int end=relative.lastIndexOf('\\\\');
        if(end < 19)throw new IllegalArgumentException("Brak daty folderu kamerki");
        String folder=relative.substring(0,end);
        String filename=relative.substring(end+1);
        if(filename.isEmpty())throw new IllegalArgumentException("Brak nazwy zdjęcia");
        mkdir("Zdjęcie z kamerki");
        mkdir(folder);
        uploadInFolder(in,folder,filename,cancelled);
    }
'''
l=l[:start]+wrapper+generic+l[end:]
lpath.write_text(l,encoding="utf-8")

# 4. Add only required opt-in Android foreground services and permissions.
xml=manifest.read_text(encoding="utf-8")
xml=replace(xml,
'''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />''',
'''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />''',"dataSync permission")
xml=replace(xml,
'''        <service android:name=".FindPhoneService"''',
'''        <service android:name=".TransferService"
                 android:exported="false"
                 android:foregroundServiceType="dataSync" />
        <service android:name=".CameraWatcher"
                 android:exported="false"
                 android:foregroundServiceType="connectedDevice" />
        <service android:name=".PhonePeerService"
                 android:exported="false"
                 android:foregroundServiceType="connectedDevice" />
        <service android:name=".FindPhoneService"''',"foreground service registrations")
manifest.write_text(xml,encoding="utf-8")

gradle=Path("app/build.gradle")
cfg=gradle.read_text(encoding="utf-8")
assert "versionCode 20" in cfg and "versionName '12.5'" in cfg
cfg=cfg.replace("versionCode 20","versionCode 21").replace("versionName '12.5'","versionName '17.0'")
gradle.write_text(cfg,encoding="utf-8")

assert "if (gallery != null) return;" in m and "showGallery();" in m
assert "selected.removeAll(successes);" not in g
assert "RING_NOTIFICATION_ID" in (root/"FindPhoneService.java").read_text(encoding="utf-8")
print("FolderLAN v17 — persistent transfer queue, camera opt-in, phone-to-phone, numbers, preview and red Find Phone unchanged.")
