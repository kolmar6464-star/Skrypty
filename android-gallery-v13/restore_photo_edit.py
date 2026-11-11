from pathlib import Path

gallery = Path("app/src/main/java/pl/folderlan/android/GalleryPanel.java")
s = gallery.read_text(encoding="utf-8")
def patch(old, new):
    global s
    if s.count(old) != 1:
        raise RuntimeError("Nie znaleziono jednego wystąpienia wzorca: " + old[:100]
                           + " (count=" + str(s.count(old)) + ")")
    s = s.replace(old, new, 1)

# Retain original v9 gallery browsing and selection; only restore a direct
# image editing action, without replacing gallery with Android file picker.
patch("        void onDelete(List<Item> entries);\n",
      "        void onDelete(List<Item> entries);\n        void onEdit(Item image);\n")
patch("    private Button selectAll, clear, copy, cancel, delete;\n",
      "    private Button selectAll, clear, copy, cancel, delete, edit;\n")
patch("""        body.addView(delete, new LinearLayout.LayoutParams(-1, dp(54)));

        selectAll.setEnabled(false);""",
"""        body.addView(delete, new LinearLayout.LayoutParams(-1, dp(54)));
        edit = button("Edytuj wybrane zdjęcie", v -> {
            if (copying || selected.size() != 1) return;
            for (Item item : photos) {
                if (!item.video && selected.contains(item.uri)) {
                    handler.onEdit(item);
                    return;
                }
            }
        });
        body.addView(edit, new LinearLayout.LayoutParams(-1, dp(52)));

        selectAll.setEnabled(false);""")
patch("""        copy.setText("Kopiuj do folderu (" + selected.size() + ")");
    }""",
"""        copy.setText("Kopiuj do folderu (" + selected.size() + ")");
        if (edit != null) {
            boolean canEdit = false;
            if (selected.size() == 1 && !copying) {
                for (Item item : photos) {
                    if (!item.video && selected.contains(item.uri)) { canEdit = true; break; }
                }
            }
            edit.setEnabled(canEdit);
        }
    }""")
patch("""    void refreshedAfterDeletion() {""",
"""    void refreshAfterPhotoEdit() {
        if (closed) return;
        loadGallery();
    }
    void refreshedAfterDeletion() {""")
# checkboxes must stay checked after copying; no reversion to v9's deselection!
assert "selected.removeAll(successes);" not in s, "v10+ fix lost: selected checkmarks"
gallery.write_text(s, encoding="utf-8")

main = Path("app/src/main/java/pl/folderlan/android/MainActivity.java")
m = main.read_text(encoding="utf-8")
def mainpatch(old, new):
    global m
    if m.count(old) != 1:
        raise RuntimeError("MainActivity anchor missing or ambiguous: " + old[:100] +
                           " count=" + str(m.count(old)))
    m = m.replace(old, new, 1)

mainpatch("    private static final int WRITE_MEDIA_PERMISSION = 710;\n",
          "    private static final int WRITE_MEDIA_PERMISSION = 710;\n    private static final int EDIT_GALLERY_PHOTO = 711;\n")
mainpatch("""            @Override public void onDelete(List<GalleryPanel.Item> entries) { confirmPhoneDelete(entries); }
""",
"""            @Override public void onDelete(List<GalleryPanel.Item> entries) { confirmPhoneDelete(entries); }
            @Override public void onEdit(GalleryPanel.Item image) { openPhotoEditor(image); }
""")
mainpatch("""    private void closeGallery() {""",
"""    private void openPhotoEditor(GalleryPanel.Item item) {
        if (item.video) {
            toast("Edycja dotyczy zdjęć. Filmy można kopiować i usuwać.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_EDIT);
        intent.setDataAndType(item.uri, "image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(intent, "Edytuj zdjęcie"), EDIT_GALLERY_PHOTO);
        } catch (android.content.ActivityNotFoundException noEditor) {
            new AlertDialog.Builder(this)
                .setTitle("Nie znaleziono edytora zdjęć")
                .setMessage("Telefon nie udostępnia aplikacji obsługującej edycję zdjęć. "
                    + "Możesz zainstalować lub włączyć systemowy edytor galerii.")
                .setPositiveButton("OK", null).show();
        } catch (SecurityException unavailable) {
            toast("Android nie przyznał dostępu do edycji tego zdjęcia.");
        }
    }
    private void closeGallery() {""")
mainpatch("""        if(request==DELETE_MEDIA) {""",
"""        if(request==EDIT_GALLERY_PHOTO) {
            // Keep selected checkboxes; simply reload media metadata/thumbnails.
            if (gallery!=null) gallery.refreshAfterPhotoEdit();
            return;
        }
        if(request==DELETE_MEDIA) {""")

# Restore v9's original gallery entry point, unchanged browser/gallery workflow.
assert 'Galeria telefonu — kopiuj do Windows' in m
assert "handler.onDelete" in s and "handler.onCopy" in s
main.write_text(m, encoding="utf-8")

gradle = Path("app/build.gradle")
g = gradle.read_text(encoding="utf-8")
if "versionCode 12" not in g or "versionName '12.0'" not in g:
    raise RuntimeError("Oczekiwano wersji 12.0 przed aktualizacją")
g = g.replace("versionCode 12", "versionCode 13")
g = g.replace("versionName '12.0'", "versionName '13.0'")
gradle.write_text(g, encoding="utf-8")
print("v13: restored v9 gallery workflow, editor for single photo, selection preserved after copy")
