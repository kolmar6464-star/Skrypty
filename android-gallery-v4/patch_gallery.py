from pathlib import Path
p=Path('app/src/main/java/pl/folderlan/android/GalleryPanel.java')
s=p.read_text(encoding='utf-8')
def replace(old,new):
    global s
    if old not in s: raise RuntimeError('GalleryPanel missing anchor: '+old[:120])
    s=s.replace(old,new,1)
replace('        void onCopy(List<Item> entries);\n',
'''        void onCopy(List<Item> entries);
        void onCancel();
        void onDelete(List<Item> entries);
''')
replace('    private Button selectAll, clear, copy;\n',
        '    private Button selectAll, clear, copy, cancel, delete;\n')
replace('    private boolean copying;\n',
        '    private boolean copying;\n    private boolean stopping;\n')
replace('        body.addView(copy, new LinearLayout.LayoutParams(-1, dp(60)));\n',
'''        body.addView(copy, new LinearLayout.LayoutParams(-1, dp(58)));
        cancel = button("Przerwij kopiowanie", v -> {
            if (!copying || stopping) return;
            stopping = true;
            cancel.setEnabled(false);
            cancel.setText("Zatrzymywanie…");
            progressLabel.setText("Zatrzymywanie kopiowania po bieżącym fragmencie pliku…");
            handler.onCancel();
        });
        cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(240, 193, 91)));
        body.addView(cancel, new LinearLayout.LayoutParams(-1, dp(52)));
        delete = button("Usuń zaznaczone z telefonu (0)", v -> {
            if (copying || selected.isEmpty()) return;
            ArrayList<Item> chosen = new ArrayList<>();
            for (Item item : photos) if (selected.contains(item.uri)) chosen.add(item);
            handler.onDelete(chosen);
        });
        delete.setTextColor(Color.WHITE);
        delete.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(176, 47, 57)));
        body.addView(delete, new LinearLayout.LayoutParams(-1, dp(54)));
''')
replace('        copy.setEnabled(!copying && !selected.isEmpty());\n',
'''        copy.setEnabled(!copying && !selected.isEmpty());
        if (cancel != null) {
            cancel.setVisibility(copying ? View.VISIBLE : View.GONE);
            cancel.setEnabled(copying && !stopping);
        }
        if (delete != null) {
            delete.setEnabled(!copying && !selected.isEmpty());
            delete.setText("Usuń zaznaczone z telefonu (" + selected.size() + ")");
        }
''')
replace('    void setCopying(boolean state) {\n        copying = state;\n        update();\n    }\n',
'''    void setCopying(boolean state) {
        copying = state;
        if (state) {
            stopping = false;
            cancel.setText("Przerwij kopiowanie");
        }
        update();
    }
''')
replace('        progressLabel.setText("Kopiowanie " + done + " / " + total + ": " + current);\n',
'''        if (!stopping) progressLabel.setText("Kopiowanie " + done + " / " + total + ": " + current);
''')
replace('    void transferFinished(List<Uri> successes, int failures, String firstError) {\n',
'''    void transferFinished(List<Uri> successes, int failures, String firstError, boolean cancelled) {
''')
replace('        progressLabel.setText("Skopiowano: " + successes.size() + ", błędy: " + failures\n',
'''        progressLabel.setText((cancelled ? "Przerwano. " : "") + "Skopiowano: " + successes.size() + ", błędy: " + failures
''')
replace('    void transferError(String explanation) {\n',
'''    void refreshedAfterDeletion() {
        selected.clear();
        update();
        loadGallery();
    }
    void transferError(String explanation) {
''')
p.write_text(s,encoding='utf-8')
print('GalleryPanel v4: cancel and separate deletion UI; no auto-remove after copy')
