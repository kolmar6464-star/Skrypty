package pl.folderlan.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int SAVE_FILE = 502;
    private static final int READ_MEDIA_PERMISSION = 707;
    private static final int DELETE_MEDIA = 709;
    private static final int WRITE_MEDIA_PERMISSION = 710;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopGalleryUpload = new AtomicBoolean(false);
    private final LinkedHashMap<String, LanShare.Entry> selectedRemote = new LinkedHashMap<>();
    private final ArrayList<Uri> pendingDeletion = new ArrayList<>();
    private final ArrayList<Uri> currentDeletionGroup = new ArrayList<>();
    private final ArrayList<RemoteMedia> pendingDownloads = new ArrayList<>();
    private LanShare remote;
    private GalleryPanel gallery;
    private FrameLayout root;
    private View galleryView;
    private LinearLayout items;
    private TextView status, pathLabel, selectionCount;
    private Button selectAllMediaButton, clearSelectionButton, copyToGalleryButton;
    private String currentPath = "", pendingDownloadPath;
    private volatile boolean busy;
    private boolean deletedAnything;

    private static final int NAVY = Color.rgb(29, 51, 79);
    private static final int BLUE = Color.rgb(27, 105, 201);
    private static final int TEAL = Color.rgb(30, 120, 112);
    private static final int MUTED = Color.rgb(96, 111, 132);
    private static final int BG = Color.rgb(244, 247, 251);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        connect();
    }
    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }
    private GradientDrawable background(int fill, int border) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(12));
        if (border != 0) bg.setStroke(dp(1), border);
        return bg;
    }
    private TextView label(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(NAVY);
        t.setTextSize(sp);
        t.setPadding(dp(3), dp(6), dp(3), dp(6));
        return t;
    }
    private Button action(String title, int color, Runnable callback) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        b.setOnClickListener(v -> callback.run());
        return b;
    }
    private void buildUi() {
        root = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(14), dp(19), dp(14), dp(24));
        main.setBackgroundColor(BG);

        TextView title = label("Folder Udostępniony", 26);
        title.setTypeface(null, Typeface.BOLD);
        main.addView(title);
        main.addView(label("Pliki Windows • Wi-Fi • szyfrowany SMB3", 13));

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.addView(action("Połącz", BLUE, this::connect), new LinearLayout.LayoutParams(0, dp(55), 1));
        firstRow.addView(action("Synchronizuj foldery", TEAL, this::syncWindowsFolders), new LinearLayout.LayoutParams(0, dp(55), 2));
        main.addView(firstRow);
        main.addView(action("Galeria telefonu — kopiuj do Windows", BLUE, this::pickGallery),
                new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        secondRow.addView(action("Skrót na pulpicie", MUTED, this::addShortcut), new LinearLayout.LayoutParams(0, dp(53), 1));
        secondRow.addView(action("Rozłącz", MUTED, this::disconnect), new LinearLayout.LayoutParams(0, dp(53), 1));
        main.addView(secondRow);

        status = label("Łączenie z komputerem…", 13);
        status.setPadding(dp(12), dp(13), dp(12), dp(13));
        status.setBackground(background(Color.rgb(232, 241, 251), 0));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1,-2);
        statusLp.topMargin = dp(12);
        main.addView(status, statusLp);

        TextView heading = label("PLIKI I FOLDERY NA KOMPUTERZE", 12);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextColor(MUTED);
        main.addView(heading);

        pathLabel = label("Folder: /", 17);
        pathLabel.setTypeface(null, Typeface.BOLD);
        main.addView(pathLabel);
        main.addView(action("Wróć do folderu nadrzędnego", MUTED, this::goUp),
                new LinearLayout.LayoutParams(-1, dp(48)));

        TextView selectionHeading = label("Zaznacz pliki, które chcesz skopiować do galerii", 12);
        main.addView(selectionHeading);
        LinearLayout selectionActions = new LinearLayout(this);
        selectAllMediaButton = action("Zaznacz media", BLUE, this::selectAllRemoteMedia);
        clearSelectionButton = action("Odznacz", MUTED, () -> {
            selectedRemote.clear();
            updateRemoteSelectionUi();
            refresh();
        });
        selectionActions.addView(selectAllMediaButton, new LinearLayout.LayoutParams(0,dp(53),2));
        selectionActions.addView(clearSelectionButton, new LinearLayout.LayoutParams(0,dp(53),1));
        main.addView(selectionActions);

        selectionCount = label("Zaznaczono: 0", 13);
        main.addView(selectionCount);
        copyToGalleryButton = action("Kopiuj zaznaczone do galerii telefonu", TEAL, this::copyRemoteToPhoneGallery);
        main.addView(copyToGalleryButton, new LinearLayout.LayoutParams(-1,dp(56)));

        items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, dp(7), 0, 0);
        main.addView(items);

        scroll.addView(main);
        root.addView(scroll, new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        updateRemoteSelectionUi();
    }
    private void updateRemoteSelectionUi() {
        if (selectionCount == null) return;
        selectionCount.setText("Zaznaczono do galerii telefonu: " + selectedRemote.size());
        clearSelectionButton.setEnabled(!selectedRemote.isEmpty() && !busy);
        copyToGalleryButton.setEnabled(!selectedRemote.isEmpty() && !busy);
    }
    private String readPairingAsset() throws Exception {
        try (InputStream in = getAssets().open("pairing_key.txt")) {
            byte[] bytes = new byte[100];
            int n = in.read(bytes);
            if (n <= 0) throw new Exception("Brak danych połączenia");
            String key = new String(bytes, 0, n, StandardCharsets.US_ASCII).trim();
            if (key.length() != 44) throw new Exception("Nieprawidłowe dane połączenia");
            return key;
        }
    }
    private void setStatus(String message) {
        runOnUiThread(() -> { if (status != null) status.setText(message); });
    }
    interface Work { void run() throws Exception; }
    private void work(String message, Work job) {
        if (busy) { toast("Inna operacja już trwa."); return; }
        busy = true;
        updateRemoteSelectionUi();
        setStatus(message);
        executor.execute(() -> {
            try { job.run(); }
            catch (Exception e) { setStatus("Błąd: " + e.getMessage()); }
            finally {
                runOnUiThread(() -> { busy = false; updateRemoteSelectionUi(); });
            }
        });
    }
    private void connect() {
        work("Wyszukiwanie folderu w sieci Wi-Fi…", () -> {
            LanShare next = new LanShare(readPairingAsset());
            try { next.connect(getApplicationContext()); }
            catch (Exception e) { next.close(); throw e; }
            if (remote != null) remote.close();
            remote = next;
            currentPath = "";
            selectedRemote.clear();
            setStatus("Połączono z: " + next.hostLabel());
            refreshInternal();
        });
    }
    private void refresh() {
        work("Odczyt plików na komputerze…", this::refreshInternal);
    }
    private void refreshInternal() {
        LanShare linked = remote;
        if (linked == null) { setStatus("Najpierw połącz się z komputerem."); return; }
        final String location = currentPath;
        final List<LanShare.Entry> listing = linked.list(location);
        runOnUiThread(() -> {
            if (!location.equals(currentPath)) return;
            pathLabel.setText("Folder: /" + location.replace('\\','/'));
            items.removeAllViews();
            if (listing.isEmpty()) {
                TextView empty = label("Ten folder jest pusty.",14);
                empty.setPadding(dp(12),dp(28),dp(12),dp(28));
                items.addView(empty);
            }
            for (LanShare.Entry entry : listing) {
                final String path = location.isEmpty() ? entry.name : location + "\\" + entry.name;
                items.addView(fileCard(entry,path));
            }
            updateRemoteSelectionUi();
        });
    }
    private View fileCard(LanShare.Entry entry, String path) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(8), dp(7), dp(8));
        row.setBackground(background(Color.WHITE, Color.rgb(223, 231, 240)));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1,dp(82));
        rowLp.bottomMargin = dp(7);
        row.setLayoutParams(rowLp);

        TextView image = label(entry.directory ? "📁" : (isMediaName(entry.name) ? "🖼" : "📄"),24);
        image.setGravity(Gravity.CENTER);
        row.addView(image,new LinearLayout.LayoutParams(dp(41),-1));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(entry.name,15);
        name.setTypeface(null,Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(name);
        String type = entry.directory ? "Folder • dotknij, aby otworzyć"
            : String.format(Locale.forLanguageTag("pl-PL"), "Plik • %.1f MB",Math.max(0,entry.size)/1048576.0);
        TextView description = label(type,12);
        description.setTextColor(MUTED);
        titles.addView(description);
        row.addView(titles,new LinearLayout.LayoutParams(0,-2,1));

        if (entry.directory) {
            row.setOnClickListener(v -> {
                if (busy) return;
                currentPath = path;
                selectedRemote.clear();
                refresh();
            });
        } else if (isMediaName(entry.name)) {
            CheckBox check = new CheckBox(this);
            check.setContentDescription("Zaznacz " + entry.name + " do galerii telefonu");
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
            check.setChecked(selectedRemote.containsKey(path));
            check.setOnCheckedChangeListener((buttonView, checked) -> {
                if (checked) selectedRemote.put(path,entry);
                else selectedRemote.remove(path);
                updateRemoteSelectionUi();
            });
            row.addView(check,new LinearLayout.LayoutParams(dp(49),dp(55)));
            row.setOnClickListener(v -> { if (!busy) check.setChecked(!check.isChecked()); });
        } else {
            Button save = action("Pobierz", MUTED, () -> saveFile(path,entry.name));
            row.addView(save,new LinearLayout.LayoutParams(dp(89),dp(50)));
        }
        return row;
    }
    private static boolean isVideoName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".m4v")
            || n.endsWith(".3gp") || n.endsWith(".webm") || n.endsWith(".mkv")
            || n.endsWith(".avi") || n.endsWith(".mpeg") || n.endsWith(".mpg");
    }
    private static boolean isMediaName(String name) {
        String n=name.toLowerCase(Locale.ROOT);
        return isVideoName(n) || n.endsWith(".jpg") || n.endsWith(".jpeg")
            || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".webp")
            || n.endsWith(".heic") || n.endsWith(".heif") || n.endsWith(".bmp")
            || n.endsWith(".dng") || n.endsWith(".tif") || n.endsWith(".tiff")
            || n.endsWith(".avif");
    }
    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".heif")) return "image/heif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".dng")) return "image/x-adobe-dng";
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return "image/tiff";
        if (n.endsWith(".avif")) return "image/avif";
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        return "video/mpeg";
    }
    private void selectAllRemoteMedia() {
        if (busy || remote == null) return;
        work("Zaznaczanie zdjęć i filmów…", () -> {
            final String location = currentPath;
            final List<LanShare.Entry> listing = remote.list(location);
            runOnUiThread(() -> {
                if (!location.equals(currentPath)) return;
                selectedRemote.clear();
                for (LanShare.Entry e : listing) {
                    if (!e.directory && isMediaName(e.name)) {
                        String path = location.isEmpty() ? e.name : location + "\\" + e.name;
                        selectedRemote.put(path,e);
                    }
                }
                // Repaint existing checkboxes; don't start a nested worker.
                List<LanShare.Entry> copy = listing;
                items.removeAllViews();
                for (LanShare.Entry entry : copy) {
                    String path = location.isEmpty() ? entry.name : location + "\\" + entry.name;
                    items.addView(fileCard(entry,path));
                }
                updateRemoteSelectionUi();
            });
        });
    }
    private void goUp() {
        if (busy) return;
        int n = currentPath.lastIndexOf('\\');
        currentPath = n < 0 ? "" : currentPath.substring(0,n);
        selectedRemote.clear();
        refresh();
    }
    private void syncWindowsFolders() {
        work("Zlecanie synchronizacji komputerów Windows…", () -> {
            if (remote == null) throw new Exception("Brak połączenia. Wybierz Połącz.");
            String token = remote.requestWindowsSynchronization();
            setStatus("Wysłano żądanie synchronizacji. Komputery Windows uzupełnią brakujące pliki; "
                + "telefon nie tworzy lokalnej kopii.");
            refreshInternal();
        });
    }
    private void pickGallery() {
        if (gallery != null) return;
        if (Build.VERSION.SDK_INT >= 33) {
            boolean images = checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED;
            boolean videos = checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                == PackageManager.PERMISSION_GRANTED;
            boolean partial = Build.VERSION.SDK_INT >=34 &&
                checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED;
            if (!images && !videos && !partial) {
                if (Build.VERSION.SDK_INT >=34)
                    requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},READ_MEDIA_PERMISSION);
                else
                    requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO},READ_MEDIA_PERMISSION);
                return;
            }
        } else if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},READ_MEDIA_PERMISSION);
            return;
        }
        showGallery();
    }
    private void showGallery() {
        if (gallery != null) return;
        gallery = new GalleryPanel(this,new GalleryPanel.Handler(){
            @Override public void onClose() { closeGallery(); }
            @Override public void onCopy(List<GalleryPanel.Item> entries) { uploadToWindows(entries); }
            @Override public void onCancel() { stopGalleryUpload.set(true); }
            @Override public void onDelete(List<GalleryPanel.Item> entries) { confirmPhoneDelete(entries); }
        });
        galleryView = gallery.create();
        root.addView(galleryView,new FrameLayout.LayoutParams(-1,-1));
    }
    private void closeGallery() {
        if (gallery == null) return;
        if (gallery.isCopying()) { toast("Trwa kopiowanie — wybierz Przerwij kopiowanie."); return; }
        root.removeView(galleryView);
        gallery.close();
        gallery = null;
        galleryView = null;
    }
    @Override public void onBackPressed() {
        if (gallery != null) closeGallery();
        else super.onBackPressed();
    }
    private void uploadToWindows(List<GalleryPanel.Item> batch) {
        if (busy) {
            if (gallery != null) gallery.transferError("Inna operacja trwa.");
            return;
        }
        stopGalleryUpload.set(false);
        busy=true;
        executor.execute(() -> {
            final ArrayList<Uri> successes = new ArrayList<>();
            int failures=0;
            String firstError="";
            String fatal=null;
            boolean stopped=false;
            try {
                if (remote == null) {
                    LanShare next = new LanShare(readPairingAsset());
                    try { next.connect(getApplicationContext()); }
                    catch (Exception e) { next.close(); throw e; }
                    remote = next;
                    setStatus("Połączono: "+next.hostLabel());
                }
                for (int i=0; i<batch.size(); i++) {
                    if (stopGalleryUpload.get()) { stopped=true; break; }
                    final GalleryPanel.Item item=batch.get(i);
                    final int completed=i;
                    runOnUiThread(() -> {
                        if (gallery != null) gallery.transferProgress(completed,batch.size(),item.name);
                    });
                    try (InputStream in=getContentResolver().openInputStream(item.uri)) {
                        if (in==null) throw new Exception("Nie można odczytać pliku");
                        // Stream source bytes directly to Windows. Never create a second phone copy.
                        remote.uploadGallery(in,item.name,stopGalleryUpload);
                        successes.add(item.uri);
                    } catch (InterruptedIOException e) {
                        stopped=true;
                        break;
                    } catch (Exception e) {
                        failures++;
                        if (firstError.isEmpty()) firstError=item.name+": "+e.getMessage();
                    }
                }
                if (stopGalleryUpload.get()) stopped=true;
                setStatus((stopped?"Przerwano. ":"") + "Zapisano "+successes.size()+" plików w "
                    +remote.galleryFolderForToday()+". Błędy: "+failures);
            } catch (Exception e) {
                fatal=e.getMessage()==null ? e.toString():e.getMessage();
                setStatus("Błąd: "+fatal);
            } finally {
                final int errs=failures;
                final String error=firstError, crash=fatal;
                final boolean cancelled=stopped;
                runOnUiThread(() -> {
                    busy=false;
                    updateRemoteSelectionUi();
                    if (gallery==null) return;
                    if (crash!=null) gallery.transferError(crash);
                    else gallery.transferFinished(successes,errs,error,cancelled);
                });
            }
        });
    }
    private void confirmPhoneDelete(List<GalleryPanel.Item> entries) {
        if (entries.isEmpty()) return;
        if (busy) { toast("Najpierw zakończ kopiowanie."); return; }
        new AlertDialog.Builder(this)
            .setTitle("Usunąć zdjęcia z telefonu?")
            .setMessage("Wybrane pliki ("+entries.size()+") zostaną usunięte z galerii Androida. "
                +"Nie będą usuwane z folderu na komputerze. Android może poprosić o dodatkowe zatwierdzenie.")
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Usuń z telefonu",(d,w) -> {
                pendingDeletion.clear();
                for (GalleryPanel.Item e:entries) pendingDeletion.add(e.uri);
                deletedAnything=false;
                deleteNextBatch();
            }).show();
    }
    private void deleteNextBatch() {
        if (pendingDeletion.isEmpty()) {
            if (gallery != null && deletedAnything) gallery.refreshedAfterDeletion();
            setStatus(deletedAnything ? "Zaktualizowano galerię po usunięciu zdjęć." : "Nie usunięto żadnego pliku.");
            return;
        }
        currentDeletionGroup.clear();
        int limit = Math.min(100, pendingDeletion.size());
        for(int i=0;i<limit;i++) currentDeletionGroup.add(pendingDeletion.get(i));
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent intent = MediaStore.createDeleteRequest(getContentResolver(),currentDeletionGroup);
                startIntentSenderForResult(intent.getIntentSender(),DELETE_MEDIA,null,0,0,0);
            } catch (Exception e) {
                pendingDeletion.clear();
                setStatus("Nie udało się uruchomić systemowego potwierdzenia: "+e.getMessage());
            }
        } else {
            try {
                for(Uri uri:currentDeletionGroup) {
                    getContentResolver().delete(uri,null,null);
                    pendingDeletion.remove(uri);
                }
                deletedAnything=true;
                deleteNextBatch();
            } catch (android.app.RecoverableSecurityException re) {
                if (Build.VERSION.SDK_INT >=29) {
                    try {
                        startIntentSenderForResult(re.getUserAction().getActionIntent().getIntentSender(),
                            DELETE_MEDIA,null,0,0,0);
                    } catch(Exception e) {pendingDeletion.clear();toast("Brak pozwolenia na usuwanie.");}
                } else { pendingDeletion.clear(); toast("Nie udało się usunąć zdjęć."); }
            } catch (Exception e) {
                pendingDeletion.clear(); toast("Błąd usuwania: "+e.getMessage());
            }
        }
    }
    private static final class RemoteMedia {
        final String path,name;
        RemoteMedia(String path,String name) {this.path=path;this.name=name;}
    }
    private void copyRemoteToPhoneGallery() {
        if (remote==null) { toast("Najpierw połącz się z komputerem."); return; }
        if (selectedRemote.isEmpty()) { toast("Zaznacz pliki na liście."); return; }
        ArrayList<RemoteMedia> list = new ArrayList<>();
        for(Map.Entry<String,LanShare.Entry> entry:selectedRemote.entrySet()) {
            if (isMediaName(entry.getValue().name))
                list.add(new RemoteMedia(entry.getKey(),entry.getValue().name));
        }
        if (list.isEmpty()) {toast("Zaznacz zdjęcia lub filmy.");return;}
        if (Build.VERSION.SDK_INT <29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    !=PackageManager.PERMISSION_GRANTED) {
            pendingDownloads.clear(); pendingDownloads.addAll(list);
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},WRITE_MEDIA_PERMISSION);
            return;
        }
        startDownloadBatch(list);
    }
    private void startDownloadBatch(List<RemoteMedia> list) {
        if (busy) {toast("Inna operacja już trwa.");return;}
        new AlertDialog.Builder(this)
          .setTitle("Kopiuj do galerii telefonu")
          .setMessage("Zapisać "+list.size()+" zaznaczonych plików w Galerii Androida? "
              +"Dopiero ta operacja zajmie miejsce w pamięci telefonu.")
          .setNegativeButton("Anuluj",null)
          .setPositiveButton("Kopiuj",(dialog,which) -> {
              work("Kopiowanie do galerii Androida…",() -> {
                  int done=0, failed=0;
                  String firstError="";
                  for(RemoteMedia medium:list) {
                      try {
                          saveRemoteMedia(medium);
                          done++;
                      } catch(Exception e) {
                          failed++;
                          if(firstError.isEmpty()) firstError=medium.name+": "+e.getMessage();
                      }
                      setStatus("Kopiuj do galerii: "+done+" zapisano, "+failed+" błędów, z "+list.size());
                  }
                  final int ok=done, err=failed;
                  final String error=firstError;
                  runOnUiThread(() -> new AlertDialog.Builder(this)
                      .setTitle("Kopiowanie do galerii ukończone")
                      .setMessage("Zapisano: "+ok+"\nBłędy: "+err+
                          (error.isEmpty()?"":"\nPierwszy błąd: "+error))
                      .setPositiveButton("OK",null).show());
                  if (ok>0) {
                      runOnUiThread(() -> {selectedRemote.clear();refresh();});
                  }
              });
          }).show();
    }
    private void saveRemoteMedia(RemoteMedia medium) throws Exception {
        boolean video = isVideoName(medium.name);
        Uri collection = video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,medium.name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime(medium.name));
        if (Build.VERSION.SDK_INT >=29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (video?Environment.DIRECTORY_MOVIES:Environment.DIRECTORY_PICTURES)+"/Folder Udostepniony");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(
                    video?Environment.DIRECTORY_MOVIES:Environment.DIRECTORY_PICTURES),"Folder Udostepniony");
            if (!folder.exists() && !folder.mkdirs()) throw new Exception("Brak uprawnień do utworzenia galerii");
            values.put(MediaStore.MediaColumns.DATA,new File(folder,medium.name).getAbsolutePath());
        }
        Uri newEntry = getContentResolver().insert(collection,values);
        if (newEntry==null) throw new Exception("Android odmówił utworzenia pliku w galerii");
        boolean finished=false;
        try {
            try (OutputStream out=getContentResolver().openOutputStream(newEntry,"w")) {
                if(out==null) throw new Exception("Brak możliwości zapisania do galerii");
                // Direct stream from SMB to MediaStore, no second app-local temp file.
                remote.download(medium.path,out);
            }
            if (Build.VERSION.SDK_INT >=29) {
                ContentValues visible=new ContentValues();
                visible.put(MediaStore.MediaColumns.IS_PENDING,0);
                if (getContentResolver().update(newEntry,visible,null,null)<1)
                    throw new Exception("Nie można zatwierdzić pliku w galerii");
            }
            finished=true;
        } finally {
            if(!finished) {
                try { getContentResolver().delete(newEntry,null,null); } catch(Exception ignored) {}
            }
        }
    }
    private void saveFile(String path,String name) {
        pendingDownloadPath=path;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,name);
        startActivityForResult(intent,SAVE_FILE);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        boolean allowed=false;
        for(int g:grants) if(g==PackageManager.PERMISSION_GRANTED) allowed=true;
        if(!allowed) {toast("Nie przyznano wymaganego dostępu.");return;}
        if(request==READ_MEDIA_PERMISSION) showGallery();
        if(request==WRITE_MEDIA_PERMISSION) {
            ArrayList<RemoteMedia> copy=new ArrayList<>(pendingDownloads);
            pendingDownloads.clear();
            startDownloadBatch(copy);
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==DELETE_MEDIA) {
            if(result==RESULT_OK) {
                if(Build.VERSION.SDK_INT>=30) {
                    pendingDeletion.removeAll(currentDeletionGroup);
                    deletedAnything=true;
                    deleteNextBatch();
                } else {
                    // On Android 10, retry the delete with the freshly granted permission.
                    try {
                        for(Uri uri:new ArrayList<>(currentDeletionGroup)) {
                            getContentResolver().delete(uri,null,null);
                            pendingDeletion.remove(uri);
                        }
                        deletedAnything=true;
                        deleteNextBatch();
                    } catch(Exception e) {
                        pendingDeletion.clear();
                        toast("Nie udało się usunąć wybranych plików.");
                    }
                }
            } else {
                pendingDeletion.clear();
                setStatus("Usuwanie anulowane przez użytkownika.");
                if(deletedAnything && gallery!=null) gallery.refreshedAfterDeletion();
            }
            return;
        }
        if(request==SAVE_FILE && result==RESULT_OK && data!=null
            && data.getData()!=null && pendingDownloadPath!=null) {
            final String path=pendingDownloadPath;
            final Uri destination=data.getData();
            pendingDownloadPath=null;
            work("Pobieranie pliku…",() -> {
                try (OutputStream out=getContentResolver().openOutputStream(destination,"w")) {
                    if(out==null) throw new Exception("Brak dostępu do pliku docelowego");
                    remote.download(path,out);
                }
                setStatus("Pobrano plik do wskazanej lokalizacji na telefonie.");
            });
        }
    }
    private void disconnect() {
        if(busy){toast("Zakończ bieżącą operację.");return;}
        new AlertDialog.Builder(this).setTitle("Rozłączyć folder?")
          .setMessage("Zamyka połączenie z komputerem; nie usuwa plików.")
          .setNegativeButton("Anuluj",null)
          .setPositiveButton("Rozłącz",(d,w)->{
              if(remote!=null) remote.close();
              remote=null;selectedRemote.clear();currentPath="";
              items.removeAllViews();updateRemoteSelectionUi();
              setStatus("Rozłączono. Wybierz Połącz.");
          }).show();
    }
    private void addShortcut() {
        if(Build.VERSION.SDK_INT <26){toast("Wymagany Android 8+.");return;}
        ShortcutManager manager=getSystemService(ShortcutManager.class);
        if(manager==null||!manager.isRequestPinShortcutSupported()){
            toast("Program uruchamiający nie obsługuje skrótów.");return;
        }
        Intent launch=new Intent(this,MainActivity.class).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ShortcutInfo shortcut=new ShortcutInfo.Builder(this,"folderlan-home")
            .setShortLabel("Folder LAN")
            .setLongLabel("Folder Udostępniony Windows")
            .setIcon(Icon.createWithResource(this,android.R.drawable.ic_menu_upload))
            .setIntent(launch).build();
        manager.requestPinShortcut(shortcut,null);
    }
    private void toast(String message) {
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }
    @Override protected void onDestroy() {
        stopGalleryUpload.set(true);
        if(gallery!=null) gallery.close();
        executor.shutdown();
        super.onDestroy();
    }
}
