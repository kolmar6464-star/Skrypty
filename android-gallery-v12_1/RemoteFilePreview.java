package pl.folderlan.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only preview of a remote SMB file. Never modifies the share or phone gallery. */
final class RemoteFilePreview {
    private static final long MAX_IMAGE = 48L * 1024 * 1024;
    private static final long MAX_VIDEO = 150L * 1024 * 1024;
    private static final long MAX_PDF = 48L * 1024 * 1024;
    private static final long MAX_TEXT = 2L * 1024 * 1024;

    private static int dp(Activity a, int n) {
        return (int)(a.getResources().getDisplayMetrics().density * n + .5f);
    }
    private static boolean extension(String n, String... ext) {
        for (String x : ext) if (n.endsWith(x)) return true;
        return false;
    }
    private static String category(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (extension(n,".jpg",".jpeg",".png",".webp",".gif",".bmp",
                ".heic",".heif",".avif",".tif",".tiff",".dng")) return "image";
        if (extension(n,".mp4",".m4v",".mov",".3gp",".webm",".mkv",
                ".avi",".mpeg",".mpg")) return "video";
        if (extension(n,".pdf")) return "pdf";
        if (extension(n,".txt",".log",".ps1",".bat",".cmd",".md",".csv",
                ".json",".xml",".ini",".cfg",".conf",".yaml",".yml",
                ".html",".htm",".css",".js",".py",".sql",".properties")) return "text";
        return "other";
    }
    private static long maxBytes(String category) {
        switch (category) {
            case "image": return MAX_IMAGE;
            case "video": return MAX_VIDEO;
            case "pdf": return MAX_PDF;
            case "text": return MAX_TEXT;
            default: return 0;
        }
    }

    static void show(Activity activity, LanShare remote, String path,
                     String name, long bytes, Runnable download) {
        String type = category(name);
        long cap = maxBytes(type);
        if (remote == null) {
            notice(activity,name,"Brak połączenia z komputerem.",download);
            return;
        }
        if (cap == 0) {
            notice(activity,name,"Podgląd tego typu pliku nie jest dostępny w aplikacji. "
                +"Możesz pobrać go i otworzyć w odpowiednim programie.",download);
            return;
        }
        if (bytes > cap) {
            notice(activity,name,"Plik jest za duży do bezpiecznego podglądu w telefonie. "
                +"Możesz go pobrać bezpośrednio.",download);
            return;
        }
        final AtomicBoolean canceled = new AtomicBoolean(false);
        AlertDialog loading = new AlertDialog.Builder(activity)
            .setTitle("Podgląd: " + name)
            .setMessage("Odczyt pliku z folderu udostępnionego…")
            .setNegativeButton("Anuluj", (d,w) -> canceled.set(true))
            .create();
        loading.setOnCancelListener(d -> canceled.set(true));
        loading.show();
        Thread thread = new Thread(() -> {
            File temp = null;
            try {
                String suffix = name.lastIndexOf('.')<0 ? ".tmp"
                    : name.substring(name.lastIndexOf('.')).replaceAll("[^a-zA-Z0-9.]", "");
                if (suffix.length() < 2 || suffix.length() > 12) suffix=".tmp";
                temp=File.createTempFile("folderlan_view_",suffix,activity.getCacheDir());
                try (FileOutputStream out=new FileOutputStream(temp)) {
                    remote.download(path,out);
                }
                if (temp.length() > cap) throw new Exception("Przekroczono limit podglądu.");
                if ("image".equals(type)) {
                    Bitmap bitmap=decodeSampled(temp,activity);
                    if (bitmap==null) throw new Exception("Telefon nie potrafi odczytać tego formatu zdjęcia.");
                    final File ready=temp;
                    temp=null;
                    activity.runOnUiThread(() -> {
                        loading.dismiss();
                        if (canceled.get() || activity.isFinishing()) {
                            bitmap.recycle(); ready.delete(); return;
                        }
                        ImageView view=new ImageView(activity);
                        view.setImageBitmap(bitmap);
                        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        view.setBackgroundColor(Color.BLACK);
                        view.setMinimumHeight(dp(activity,350));
                        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(name)
                            .setView(view).setPositiveButton("Zamknij",null).create();
                        dialog.setOnDismissListener(d -> { bitmap.recycle(); ready.delete(); });
                        dialog.show();
                    });
                } else if ("pdf".equals(type)) {
                    final File ready=temp;
                    Bitmap bitmap=renderFirstPage(ready,activity);
                    temp=null;
                    activity.runOnUiThread(() -> {
                        loading.dismiss();
                        if (canceled.get() || activity.isFinishing()) {
                            bitmap.recycle(); ready.delete(); return;
                        }
                        ImageView view=new ImageView(activity);
                        view.setImageBitmap(bitmap);
                        view.setAdjustViewBounds(true);
                        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        AlertDialog dialog=new AlertDialog.Builder(activity)
                            .setTitle(name+" — strona 1").setView(view)
                            .setPositiveButton("Zamknij",null).create();
                        dialog.setOnDismissListener(d -> { bitmap.recycle(); ready.delete(); });
                        dialog.show();
                    });
                } else if ("text".equals(type)) {
                    String content = readText(temp,150_000);
                    temp.delete(); temp=null;
                    activity.runOnUiThread(() -> {
                        loading.dismiss();
                        if (canceled.get() || activity.isFinishing()) return;
                        TextView text=new TextView(activity);
                        text.setText(content); text.setTextColor(Color.BLACK);
                        text.setTypeface(Typeface.MONOSPACE);
                        text.setTextSize(13); text.setTextIsSelectable(true);
                        text.setPadding(dp(activity,14),dp(activity,12),dp(activity,14),dp(activity,14));
                        ScrollView scroll=new ScrollView(activity);
                        scroll.addView(text);
                        AlertDialog dialog=new AlertDialog.Builder(activity)
                            .setTitle(name).setView(scroll)
                            .setPositiveButton("Zamknij",null).create();
                        dialog.show();
                    });
                } else if ("video".equals(type)) {
                    final File ready=temp;
                    temp=null;
                    activity.runOnUiThread(() -> {
                        loading.dismiss();
                        if (canceled.get() || activity.isFinishing()) {ready.delete(); return;}
                        VideoView video=new VideoView(activity);
                        video.setMinimumHeight(dp(activity,260));
                        video.setVideoURI(Uri.fromFile(ready));
                        video.setOnPreparedListener(mp -> {
                            mp.setLooping(false);
                            video.start();
                        });
                        video.setOnErrorListener((mp,what,extra) -> {
                            new AlertDialog.Builder(activity)
                                .setMessage("Ten format filmu nie jest obsługiwany przez odtwarzacz telefonu.")
                                .setPositiveButton("OK",null).show();
                            return true;
                        });
                        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(name)
                            .setView(video).setPositiveButton("Zamknij",null).create();
                        dialog.setOnDismissListener(d -> { video.stopPlayback(); ready.delete(); });
                        dialog.show();
                    });
                }
            } catch (Exception error) {
                final String message=error.getMessage()==null ?
                    "Nie udało się odczytać pliku.":error.getMessage();
                activity.runOnUiThread(() -> {
                    loading.dismiss();
                    if (!canceled.get() && !activity.isFinishing()) notice(activity,name,message,download);
                });
            } finally {
                if (temp!=null) temp.delete();
            }
        },"folderlan-remote-preview");
        thread.setDaemon(true);
        thread.start();
    }

    private static Bitmap decodeSampled(File file, Activity activity) throws Exception {
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if (options.outWidth<1 || options.outHeight<1) return null;
        int size=1;
        while (options.outWidth/size>2048 || options.outHeight/size>2048) size*=2;
        options.inJustDecodeBounds=false;
        options.inSampleSize=size;
        return BitmapFactory.decodeFile(file.getAbsolutePath(),options);
    }
    private static Bitmap renderFirstPage(File file,Activity activity) throws Exception {
        try (ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf=new PdfRenderer(fd)) {
            if (pdf.getPageCount() < 1) throw new Exception("PDF nie ma stron.");
            try (PdfRenderer.Page page=pdf.openPage(0)) {
                float scale=Math.min(2f,1600f/Math.max(page.getWidth(),page.getHeight()));
                int width=Math.max(1,Math.round(page.getWidth()*scale));
                int height=Math.max(1,Math.round(page.getHeight()*scale));
                Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        }
    }
    private static String readText(File file,int maxBytes) throws Exception {
        byte[] data=new byte[maxBytes];
        int n=0;
        try (InputStream in=new FileInputStream(file)) {
            while (n<maxBytes) {
                int amount=in.read(data,n,maxBytes-n);
                if (amount<0) break;
                n+=amount;
            }
        }
        return new String(data,0,n,StandardCharsets.UTF_8)
            +(file.length()>maxBytes?"\n\n[Podgląd skrócony]":"");
    }
    private static void notice(Activity activity,String name,String message,Runnable download) {
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle(name)
            .setMessage(message).setNegativeButton("Zamknij",null);
        if(download!=null)dialog.setPositiveButton("Pobierz", (d,w) -> download.run());
        dialog.show();
    }
}
