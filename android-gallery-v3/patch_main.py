from pathlib import Path

src = Path('app/src/main/java/pl/folderlan/android/MainActivity.java')
s = src.read_text(encoding='utf-8')
def substitute(old, new):
    global s
    if old not in s:
        raise RuntimeError('Missing patch anchor: ' + old[:80])
    s = s.replace(old, new, 1)

substitute('    private static final int PICK_GALLERY = 501;\n',
           '    private static final int MEDIA_PERMISSION_REQUEST = 707;\n')
substitute('import android.widget.Button;\n',
           'import android.widget.Button;\nimport android.widget.FrameLayout;\nimport android.view.View;\n')
substitute('    private LinearLayout items;\n',
           '    private LinearLayout items;\n    private FrameLayout root;\n    private GalleryPanel gallery;\n    private View galleryView;\n')
substitute('        scroll.addView(main); setContentView(scroll);',
           '        scroll.addView(main);\n        root = new FrameLayout(this);\n        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));\n        setContentView(root);')

start = s.index('    private void pickGallery() {')
end = s.index('    private void saveFile(', start)

gallery_methods = """
    private void pickGallery() {
        if (gallery != null) return;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            boolean canReadImages = checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean canReadVideos = checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean selectedOnly = android.os.Build.VERSION.SDK_INT >= 34
                    && checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                       == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!canReadImages && !canReadVideos && !selectedOnly) {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    requestPermissions(new String[]{
                        android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    }, MEDIA_PERMISSION_REQUEST);
                } else {
                    requestPermissions(new String[]{
                        android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO
                    }, MEDIA_PERMISSION_REQUEST);
                }
                return;
            }
        } else if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    MEDIA_PERMISSION_REQUEST);
            return;
        }
        showGallery();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MEDIA_PERMISSION_REQUEST) return;
        boolean granted = false;
        for (int result : grantResults) {
            if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) granted = true;
        }
        if (granted) showGallery();
        else toast("Do wyświetlenia galerii potrzebny jest dostęp do zdjęć i filmów.");
    }
    private void showGallery() {
        if (gallery != null) return;
        gallery = new GalleryPanel(this, new GalleryPanel.Handler() {
            @Override public void onClose() { closeGallery(); }
            @Override public void onCopy(List<GalleryPanel.Item> entries) { uploadGallerySelections(entries); }
        });
        galleryView = gallery.create();
        root.addView(galleryView, new FrameLayout.LayoutParams(-1, -1));
    }
    private void closeGallery() {
        if (gallery == null) return;
        if (gallery.isCopying()) {
            toast("Trwa kopiowanie. Po zakończeniu można wrócić.");
            return;
        }
        root.removeView(galleryView);
        gallery.close();
        gallery = null;
        galleryView = null;
    }
    @Override public void onBackPressed() {
        if (gallery != null) closeGallery();
        else super.onBackPressed();
    }
    private void uploadGallerySelections(List<GalleryPanel.Item> batch) {
        if (batch.isEmpty()) {
            if (gallery != null) gallery.transferError("Nie zaznaczono zdjęć.");
            return;
        }
        if (busy) {
            if (gallery != null) gallery.transferError("Inna operacja trwa. Spróbuj ponownie.");
            return;
        }
        busy = true;
        executor.execute(() -> {
            List<Uri> successful = new ArrayList<>();
            int errors = 0;
            String firstError = "";
            String fatalError = null;
            try {
                if (remote == null) {
                    setStatus("Łączenie z komputerem przed kopiowaniem…");
                    LanShare next = new LanShare(readPairingAsset());
                    try { next.connect(getApplicationContext()); }
                    catch (Exception e) { next.close(); throw e; }
                    remote = next;
                    currentPath = "";
                    setStatus("Połączono: " + next.hostLabel());
                }
                for (int index = 0; index < batch.size(); index++) {
                    GalleryPanel.Item item = batch.get(index);
                    int completed = index;
                    runOnUiThread(() -> {
                        if (gallery != null) gallery.transferProgress(completed, batch.size(), item.name);
                    });
                    try (InputStream in = getContentResolver().openInputStream(item.uri)) {
                        if (in == null) throw new java.io.IOException("Brak dostępu do pliku na telefonie");
                        remote.uploadGallery(in, item.name);
                        successful.add(item.uri);
                    } catch (Exception e) {
                        errors++;
                        if (firstError.isEmpty()) firstError = item.name + ": " + e.getMessage();
                    }
                }
                int count = successful.size();
                int unsuccessful = errors;
                setStatus("Kopiowanie galerii: " + count + " zapisanych, błędy: " + unsuccessful
                        + ". Folder Z_Telefonu.");
                if (count > 0) {
                    try { refreshInternal(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                fatalError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                setStatus("Błąd kopiowania: " + fatalError);
            } finally {
                int finalErrors = errors;
                String finalFirstError = firstError;
                String finalFatalError = fatalError;
                runOnUiThread(() -> {
                    busy = false;
                    if (gallery == null) return;
                    if (finalFatalError != null) gallery.transferError(finalFatalError);
                    else gallery.transferFinished(successful, finalErrors, finalFirstError);
                });
            }
        });
    }
"""
s = s[:start] + gallery_methods + '\n' + s[end:]

start = s.index('    @Override protected void onActivityResult(')
end = s.index('    private void forgetPair()', start)
s = s[:start] + """
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null) return;
        if (code == SAVE_FILE && data.getData() != null && pendingDownloadPath != null) {
            Uri target = data.getData();
            String filePath = pendingDownloadPath;
            work("Pobieranie pliku…", () -> {
                try (OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                    if (out == null) throw new Exception("Nie można zapisać pliku.");
                    remote.download(filePath, out);
                }
                setStatus("Plik zapisany w pamięci telefonu.");
            });
        }
    }
""" + s[end:]
substitute('        executor.shutdown();\n',
           '        if (gallery != null) gallery.close();\n        executor.shutdown();\n')
src.write_text(s, encoding='utf-8')

manifest = Path('app/src/main/AndroidManifest.xml')
xml = manifest.read_text(encoding='utf-8')
tag = '    <uses-feature android:name="android.hardware.wifi" android:required="false" />'
if tag not in xml:
    raise RuntimeError('Missing manifest anchor')
xml = xml.replace(tag, '''    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
''' + tag, 1)
manifest.write_text(xml, encoding='utf-8')

gradle = Path('app/build.gradle')
g = gradle.read_text(encoding='utf-8')
if "versionCode 2" not in g or "versionName '2.0'" not in g:
    raise RuntimeError('Version 2 not present')
gradle.write_text(g.replace("versionCode 2", "versionCode 3")
                   .replace("versionName '2.0'", "versionName '3.0'"), encoding='utf-8')
print('Patched Android v3: in-app photo gallery, media permissions, progress and retry.')
