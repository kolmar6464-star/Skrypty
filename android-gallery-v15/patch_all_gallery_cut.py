from pathlib import Path

g = Path("app/src/main/java/pl/folderlan/android/GalleryPanel.java")
s = g.read_text(encoding="utf-8")
def edit(old, new):
    global s
    n = s.count(old)
    if n != 1: raise RuntimeError("GalleryPanel anchor count %d: %s" % (n, old[:100]))
    s = s.replace(old, new, 1)

# Existing v9 in-app gallery, existing select/copy/delete/cancel controls retained.
edit("        void onDelete(List<Item> entries);\n",
     "        void onDelete(List<Item> entries);\n        void onMove(List<Item> entries);\n")
edit("    private Button selectAll, clear, copy, cancel, delete;",
     "    private Button selectAll, clear, copy, cancel, delete, copyAll, move;")

# Add buttons to existing panel; do not replace it with an external picker.
edit("        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));\n",
"""        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button extraActions = button("Opcje", v -> {
            String[] opts = {
                "Kopiuj WSZYSTKO do folderu (" + photos.size() + ")",
                "Wytnij zaznaczone i wklej (" + selected.size() + ")"
            };
            new android.app.AlertDialog.Builder(activity)
                .setTitle("Operacje na zdjęciach i filmach")
                .setItems(opts, (dlg, which) -> {
                    if (copying) return;
                    if (which == 0) {
                        if (photos.isEmpty()) return;
                        // Copy all visible files without modifying existing checks.
                        setCopying(true);
                        handler.onCopy(new ArrayList<>(photos));
                    } else {
                        if (selected.isEmpty()) return;
                        ArrayList<Item> chosen = new ArrayList<>();
                        for (Item item : photos) if (selected.contains(item.uri)) chosen.add(item);
                        handler.onMove(chosen);
                    }
                })
                .setNegativeButton("Zamknij", null)
                .show();
        });
        heading.addView(extraActions, new LinearLayout.LayoutParams(dp(78), dp(52)));
""")

# Guarantee that the checkbox remains selected after transferFinished.
assert "selected.removeAll(successes);" not in s, "Regression: copy should not uncheck selected items."
assert "loadGallery();" in s and "MediaStore.Images.Media.EXTERNAL_CONTENT_URI" in s
g.write_text(s, encoding="utf-8")

m = Path("app/src/main/java/pl/folderlan/android/MainActivity.java")
a = m.read_text(encoding="utf-8")
def activity(old, new):
    global a
    n = a.count(old)
    if n != 1: raise RuntimeError("MainActivity anchor count %d: %s" % (n, old[:100]))
    a = a.replace(old, new, 1)

activity("""            @Override public void onDelete(List<GalleryPanel.Item> entries) { confirmPhoneDelete(entries); }
""",
"""            @Override public void onDelete(List<GalleryPanel.Item> entries) { confirmPhoneDelete(entries); }
            @Override public void onMove(List<GalleryPanel.Item> entries) { confirmMovePhotos(entries); }
""")

# Do not accept Android 14's 'selected photos only' as full gallery access.
activity("""            if (!images && !videos && !partial) {
                if (Build.VERSION.SDK_INT >=34)""",
"""            if (!images && partial) {
                askFullGalleryPermission();
                return;
            }
            if (!images) {
                if (Build.VERSION.SDK_INT >=34)""")
activity("""    private void showGallery() {""",
"""    private void askFullGalleryPermission() {
        new AlertDialog.Builder(this)
            .setTitle("Pokaż wszystkie zdjęcia")
            .setMessage("Android przyznał dostęp tylko do wybranych zdjęć. Aby aplikacja "
                +"pokazała całą galerię, w uprawnieniach aplikacji Zdjęcia i filmy "
                +"wybierz: Zezwalaj na dostęp do wszystkich zdjęć.")
            .setPositiveButton("Otwórz ustawienia", (d,w) -> {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:"+getPackageName())));
            })
            .setNegativeButton("Zamknij",null).show();
    }
    private void showGallery() {""")

# Existing confirmation-on-delete handles scoped storage (Android 11+) correctly.
activity("""    private void uploadToWindows(List<GalleryPanel.Item> batch) {
        if (busy) {""",
"""    private void confirmMovePhotos(List<GalleryPanel.Item> batch) {
        if (batch==null || batch.isEmpty() || busy) return;
        new AlertDialog.Builder(this)
            .setTitle("Wytnij zdjęcia i wklej do folderu?")
            .setMessage("Najpierw skopiuję "+batch.size()+" plików do folderu na komputerze. "
                +"Dopiero po udanym przesłaniu zapytam o usunięcie oryginałów "
                +"z telefonu. Pliki, których nie uda się skopiować, pozostaną na telefonie. "
                +"Android może wymagać osobnego zatwierdzenia usuwania.")
            .setNegativeButton("Anuluj",null)
            .setPositiveButton("Rozpocznij przenoszenie",(d,w)->{
                if(gallery!=null) gallery.setCopying(true);
                uploadToWindows(batch,true);
            }).show();
    }
    private void uploadToWindows(List<GalleryPanel.Item> batch) {
        uploadToWindows(batch,false);
    }
    private void uploadToWindows(List<GalleryPanel.Item> batch, boolean cutAfterCopy) {
        if (busy) {""")

activity("""            final ArrayList<Uri> successes = new ArrayList<>();
            int failures=0;""",
"""            final ArrayList<Uri> successes = new ArrayList<>();
            final ArrayList<GalleryPanel.Item> copiedForMove = new ArrayList<>();
            int failures=0;""")
activity("""                        successes.add(item.uri);
                    } catch (InterruptedIOException e) {""",
"""                        successes.add(item.uri);
                        if (cutAfterCopy) copiedForMove.add(item);
                    } catch (InterruptedIOException e) {""")
activity("""                    else gallery.transferFinished(successes,errs,error,cancelled);
                });
            }
        });
    }
    private void confirmPhoneDelete(List<GalleryPanel.Item> entries) {""",
"""                    else {
                        gallery.transferFinished(successes,errs,error,cancelled);
                        // Delete only completed uploads; require a second explicit
                        // confirmation + Android system consent, never on cancel/error.
                        if (cutAfterCopy && !cancelled && !copiedForMove.isEmpty()) {
                            confirmPhoneDelete(copiedForMove);
                        }
                    }
                });
            }
        });
    }
    private void confirmPhoneDelete(List<GalleryPanel.Item> entries) {""")

# Handle denied/partial Android permission without claiming that all photos loaded.
activity("""        if(request==READ_MEDIA_PERMISSION) showGallery();""",
"""        if(request==READ_MEDIA_PERMISSION) {
            if (Build.VERSION.SDK_INT>=33 &&
                checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                askFullGalleryPermission();
            } else showGallery();
        }""")

# Preserve current Android v9 auto-recovery and finder functionality.
assert "onMove" in a and "requestWindowsSynchronization" in a
assert "askFullGalleryPermission" in a and "selectedRemote" in a
m.write_text(a, encoding="utf-8")

b = Path("app/build.gradle")
cfg = b.read_text(encoding="utf-8")
assert ("versionCode 10" in cfg and "versionName '9.1'" in cfg) or ("versionCode 12" in cfg and "versionName '12.0'" in cfg), "Expected v9.1 or v12 baseline"
cfg = cfg.replace("versionCode 10","versionCode 15").replace("versionName '9.1'","versionName '15.0'").replace("versionCode 12","versionCode 15").replace("versionName '12.0'","versionName '15.0'")
b.write_text(cfg,encoding="utf-8")
print("v15: original in-app media grid, full images permission, all-copy, checked cut, optional delete; no deselection after copy")
