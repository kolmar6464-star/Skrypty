package pl.folderlan.android;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Built-in local media gallery. No external file picker or extra applications. */
final class GalleryPanel {
    interface Handler {
        void onClose();
        void onCopy(List<Item> entries);
    }
    static final class Item {
        final Uri uri;
        final String name;
        final long date;
        final long mediaId;
        final boolean video;
        Item(Uri uri, String name, long date, long mediaId, boolean video) {
            this.uri = uri; this.name = name; this.date = date;
            this.mediaId = mediaId; this.video = video;
        }
    }
    private static final int DARK = Color.rgb(31, 42, 56);
    private static final int BLUE = Color.rgb(20, 111, 219);
    private final Activity activity;
    private final Handler handler;
    private final ArrayList<Item> photos = new ArrayList<>();
    private final Set<Uri> selected = new HashSet<>();
    private final ExecutorService thumbnails = Executors.newFixedThreadPool(3);
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Set<String> pending = java.util.Collections.synchronizedSet(new HashSet<>());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(20 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private FrameLayout screen;
    private GridView grid;
    private TextView count;
    private TextView info;
    private TextView progressLabel;
    private ProgressBar progress;
    private Button selectAll, clear, copy;
    private BaseAdapter adapter;
    private volatile boolean closed;
    private boolean copying;

    GalleryPanel(Activity activity, Handler handler) {
        this.activity = activity; this.handler = handler;
    }
    private int dp(int px) {
        return (int) (px * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
    private TextView text(String s, int sp) {
        TextView v = new TextView(activity);
        v.setText(s); v.setTextSize(sp); v.setTextColor(DARK);
        return v;
    }
    private Button button(String s, View.OnClickListener listener) {
        Button b = new Button(activity);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(13);
        b.setOnClickListener(listener);
        return b;
    }
    View create() {
        screen = new FrameLayout(activity);
        screen.setBackgroundColor(Color.rgb(247, 249, 253));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        screen.addView(body, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button("← Wróć", v -> handler.onClose());
        heading.addView(back, new LinearLayout.LayoutParams(dp(100), dp(52)));
        TextView title = text("Galeria zdjęć i filmów", 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(heading);

        info = text("Wczytywanie zdjęć i filmów z telefonu…", 13);
        body.addView(info);

        count = text("Zaznaczono 0", 15);
        count.setPadding(0, dp(8), 0, dp(6));
        body.addView(count);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        selectAll = button("Zaznacz wszystkie", v -> {
            selected.clear();
            for (Item item : photos) selected.add(item.uri);
            update();
            adapter.notifyDataSetChanged();
        });
        clear = button("Odznacz", v -> {
            selected.clear();
            update();
            adapter.notifyDataSetChanged();
        });
        actions.addView(selectAll, new LinearLayout.LayoutParams(0, dp(52), 2));
        actions.addView(clear, new LinearLayout.LayoutParams(0, dp(52), 1));
        body.addView(actions);

        grid = new GridView(activity);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(6));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(6), 0, dp(8));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return photos.size(); }
            @Override public Object getItem(int position) { return photos.get(position); }
            @Override public long getItemId(int position) { return photos.get(position).mediaId; }
            @Override public View getView(int position, View reusable, ViewGroup parent) {
                Holder holder;
                if (reusable == null) {
                    LinearLayout cell = new LinearLayout(activity);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    FrameLayout photo = new FrameLayout(activity);
                    ImageView image = new ImageView(activity);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setBackgroundColor(Color.rgb(222, 228, 234));
                    photo.addView(image, new FrameLayout.LayoutParams(-1, dp(112)));
                    TextView tick = new TextView(activity);
                    tick.setGravity(Gravity.CENTER);
                    tick.setTextSize(23);
                    FrameLayout.LayoutParams tickParams = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.RIGHT | Gravity.TOP);
                    photo.addView(tick, tickParams);
                    TextView filename = text("", 11);
                    filename.setSingleLine(true);
                    filename.setEllipsize(TextUtils.TruncateAt.END);
                    filename.setPadding(dp(3), dp(3), dp(3), dp(3));
                    cell.addView(photo);
                    cell.addView(filename);
                    holder = new Holder(image, tick, filename);
                    cell.setTag(holder);
                    reusable = cell;
                } else {
                    holder = (Holder) reusable.getTag();
                }
                Item item = photos.get(position);
                holder.filename.setText((item.video ? "▶ " : "") + item.name);
                boolean marked = selected.contains(item.uri);
                holder.tick.setText(marked ? "☑" : "□");
                holder.tick.setTextColor(marked ? BLUE : Color.WHITE);
                holder.tick.setShadowLayer(2f, 1f, 1f, Color.BLACK);
                holder.image.setTag(item.uri.toString());
                Bitmap cached = cache.get(item.uri.toString());
                if (cached != null) {
                    holder.image.setImageBitmap(cached);
                } else {
                    holder.image.setImageResource(item.video ? android.R.drawable.ic_media_play : android.R.drawable.ic_menu_gallery);
                    queueThumbnail(item, holder.image);
                }
                reusable.setBackgroundColor(marked ? Color.rgb(219, 235, 255) : Color.WHITE);
                return reusable;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (copying) return;
            Item item = photos.get(position);
            if (!selected.add(item.uri)) selected.remove(item.uri);
            update();
            adapter.notifyDataSetChanged();
        });
        body.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        progressLabel = text("Wybierz zdjęcia i naciśnij Kopiuj do folderu.", 13);
        progressLabel.setPadding(0, dp(3), 0, dp(6));
        body.addView(progressLabel);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        body.addView(progress, new LinearLayout.LayoutParams(-1, dp(7)));
        copy = button("Kopiuj do folderu (0)", v -> {
            if (selected.isEmpty() || copying) return;
            ArrayList<Item> chosen = new ArrayList<>();
            for (Item item : photos) if (selected.contains(item.uri)) chosen.add(item);
            setCopying(true);
            handler.onCopy(chosen);
        });
        copy.setTextColor(Color.WHITE);
        copy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
        body.addView(copy, new LinearLayout.LayoutParams(-1, dp(60)));

        selectAll.setEnabled(false);
        clear.setEnabled(false);
        copy.setEnabled(false);
        loadGallery();
        return screen;
    }
    private static final class Holder {
        final ImageView image;
        final TextView tick, filename;
        Holder(ImageView image, TextView tick, TextView filename) {
            this.image = image; this.tick = tick; this.filename = filename;
        }
    }
    private void update() {
        count.setText("Zaznaczono: " + selected.size() + " z " + photos.size());
        selectAll.setEnabled(!copying && !photos.isEmpty());
        clear.setEnabled(!copying && !selected.isEmpty());
        copy.setEnabled(!copying && !selected.isEmpty());
        copy.setText("Kopiuj do folderu (" + selected.size() + ")");
    }
    private void loadGallery() {
        loader.execute(() -> {
            ArrayList<Item> found = new ArrayList<>();
            String error = null;
            try {
                readStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, found);
                readStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, found);
                found.sort((a, b) -> Long.compare(b.date, a.date));
            } catch (Exception e) { error = e.getMessage(); }
            final String message = error;
            activity.runOnUiThread(() -> {
                if (closed) return;
                photos.clear();
                photos.addAll(found);
                adapter.notifyDataSetChanged();
                if (message == null) {
                    info.setText(photos.size() == 0
                            ? "Brak dostępnych zdjęć. Sprawdź uprawnienia do pełnej galerii w ustawieniach Androida."
                            : "Dotknij zdjęć, aby je zaznaczyć, albo wybierz Zaznacz wszystkie.");
                } else {
                    info.setText("Błąd odczytu galerii: " + message);
                }
                update();
            });
        });
    }
    private void readStore(Uri base, boolean video, List<Item> out) {
        ContentResolver resolver = activity.getContentResolver();
        String[] cols = {BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED};
        try (Cursor cursor = resolver.query(base, cols, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(BaseColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            while (!closed && cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                long date = cursor.getLong(dateCol);
                if (name == null || name.trim().isEmpty()) name = (video ? "Film_" : "Zdjecie_") + id;
                out.add(new Item(ContentUris.withAppendedId(base, id), name, date, id, video));
            }
        }
    }
    private void queueThumbnail(Item item, ImageView view) {
        final String key = item.uri.toString();
        if (!pending.add(key)) return;
        thumbnails.execute(() -> {
            Bitmap result = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    result = activity.getContentResolver().loadThumbnail(item.uri, new Size(220, 220), null);
                } else if (item.video) {
                    result = MediaStore.Video.Thumbnails.getThumbnail(
                        activity.getContentResolver(), item.mediaId, MediaStore.Video.Thumbnails.MINI_KIND, null);
                } else {
                    result = MediaStore.Images.Thumbnails.getThumbnail(
                        activity.getContentResolver(), item.mediaId, MediaStore.Images.Thumbnails.MINI_KIND, null);
                }
            } catch (Exception ignored) { }
            Bitmap thumbnail = result;
            pending.remove(key);
            if (thumbnail == null || closed) return;
            activity.runOnUiThread(() -> {
                if (closed) return;
                cache.put(key, thumbnail);
                if (key.equals(view.getTag())) view.setImageBitmap(thumbnail);
            });
        });
    }
    void setCopying(boolean state) {
        copying = state;
        update();
    }
    void transferProgress(int done, int total, String current) {
        if (closed) return;
        progress.setMax(Math.max(1, total));
        progress.setProgress(done);
        progressLabel.setText("Kopiowanie " + done + " / " + total + ": " + current);
    }
    void transferFinished(List<Uri> successes, int failures, String firstError) {
        if (closed) return;
        setCopying(false);
        selected.removeAll(successes);
        adapter.notifyDataSetChanged();
        update();
        progressLabel.setText("Skopiowano: " + successes.size() + ", błędy: " + failures
            + (firstError == null || firstError.isEmpty() ? "" : ". " + firstError)
            + (failures > 0 ? " — ponów Kopiuj dla pozostałych plików." : ""));
    }
    void transferError(String explanation) {
        if (closed) return;
        setCopying(false);
        progressLabel.setText("Kopiowanie nie powiodło się: " + explanation);
    }
    boolean isCopying() { return copying; }
    void close() {
        closed = true;
        loader.shutdownNow();
        thumbnails.shutdownNow();
        photos.clear(); selected.clear();
        cache.evictAll();
    }
}
