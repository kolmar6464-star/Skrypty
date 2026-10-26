package pl.zdalnypulpit.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private SharedPreferences preferences;
    private EditText hostInput, keyInput;
    private TextView result;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        preferences = getSharedPreferences("zp11", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 30, 28, 36);
        root.setBackgroundColor(Color.rgb(242, 246, 253));
        scroll.addView(root);
        setContentView(scroll);
        TextView title = line("Zdalny Pulpit 11 PLUS", 24);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        root.addView(line("Panel Windows 11 przez prywatną sieć Tailscale. Pełny zdalny pulpit: RustDesk.", 14));

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setHint("Adres Tailscale PC, np. 100.101.102.103");
        hostInput.setText(preferences.getString("host", ""));
        root.addView(hostInput);

        keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setHint("64-znakowy kod parowania z Windows");
        keyInput.setText(preferences.getString("key", ""));
        root.addView(keyInput);

        action(root, "Zapisz parowanie i sprawdź połączenie", false,
            () -> { if (!validate()) return; preferences.edit().putString("host", hostInput.getText().toString().trim())
                .putString("key", keyInput.getText().toString().trim()).apply();
                request("/api/status", "GET", false); });

        result = line("Połącz Tailscale na telefonie i komputerze, a następnie wprowadź dane parowania.", 14);
        root.addView(result);

        action(root, "🖥 Otwórz pełny pulpit — RustDesk", false, this::openRustDesk);
        action(root, "📷 Zrób screenshot i zapisz w Galerii", false, () -> request("/api/screenshot", "GET", true));
        action(root, "↻ Restart komputera", false, () -> confirm("Uruchomić komputer ponownie?", "restart"));
        action(root, "⏻ Wyłącz komputer", true, () -> confirm("Na pewno wyłączyć komputer?", "shutdown"));
        action(root, "⏻ Włącz komputer — Wake-on-LAN", false,
            () -> info("Włączenie wyłączonego PC", "Funkcja wymaga urządzenia nadającego pakiet Wake-on-LAN w sieci domowej (router lub przekaźnik), skonfigurowanego BIOS/UEFI oraz karty Ethernet. Nie została jeszcze zaimplementowana w panelu."));
        action(root, "📱 Steruj telefonem z komputera", false,
            () -> new AlertDialog.Builder(this).setTitle("Android → Windows")
                .setMessage("W RustDesk na Androidzie uruchom udostępnianie ekranu. Potwierdź uprawnienia systemowe, potem z Windows połącz się z ID telefonu. System Android może wymagać ponownego zatwierdzenia nagrywania ekranu.")
                .setPositiveButton("Otwórz RustDesk", (d, b) -> openRustDesk())
                .setNegativeButton("Zamknij", null).show());
        root.addView(line("Dostęp wymaga Tailscale. Panel obsługuje wyłącznie adresy 100.64.0.0/10. Nie przekierowuj portu 39877 na routerze. Screenshoty: Obrazy/ZdalnyPulpit11.", 12));
    }

    private TextView line(String content, int size) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(35, 51, 70));
        t.setPadding(0, 10, 0, 12);
        return t;
    }
    private void action(LinearLayout root, String caption, boolean warning, Runnable onTap) {
        Button b = new Button(this);
        b.setText(caption);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(ColorStateList.valueOf(warning ? Color.rgb(153, 40, 57) : Color.rgb(35, 100, 183)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 8, 0, 5);
        b.setLayoutParams(p);
        b.setOnClickListener(v -> onTap.run());
        root.addView(b);
    }
    private void info(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private boolean validate() {
        String host = hostInput.getText().toString().trim();
        String key = keyInput.getText().toString().trim();
        String[] octets = host.split("\\.");
        if (octets.length != 4) { toast("Podaj poprawny adres Tailscale"); return false; }
        int[] nums = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                nums[i] = Integer.parseInt(octets[i]);
                if (nums[i] < 0 || nums[i] > 255) throw new Exception("octet");
            }
        } catch (Exception e) { toast("Błędny adres IP"); return false; }
        if (!(nums[0] == 100 && nums[1] >= 64 && nums[1] <= 127)) {
            toast("Dozwolone tylko adresy sieci Tailscale 100.64–127.x.x"); return false;
        }
        if (!key.matches("[a-fA-F0-9]{64}")) { toast("Kod parowania musi mieć 64 znaki szesnastkowe"); return false; }
        return true;
    }
    private void openRustDesk() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.carriez.flutter_hbb");
        if (intent != null) startActivity(intent);
        else toast("Zainstaluj oficjalny RustDesk na Androidzie.");
    }
    private void confirm(String question, String command) {
        new AlertDialog.Builder(this).setTitle("Potwierdzenie")
            .setMessage(question)
            .setPositiveButton("Tak", (d, n) -> request("/api/power/" + command, "POST", false))
            .setNegativeButton("Anuluj", null).show();
    }
    private void request(String path, String method, boolean screenshot) {
        if (!validate()) return;
        final String host = hostInput.getText().toString().trim();
        final String token = keyInput.getText().toString().trim();
        result.setText("Łączenie z komputerem…");
        executor.execute(() -> {
            HttpURLConnection c = null;
            try {
                URL url = new URL("http://" + host + ":39877" + path);
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(25000);
                c.setRequestMethod(method);
                c.setUseCaches(false);
                c.setRequestProperty("Authorization", "Bearer " + token);
                if ("POST".equals(method)) {
                    c.setDoOutput(true);
                    c.getOutputStream().close();
                }
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code + " — sprawdź parowanie/Tailscale");
                try (InputStream stream = c.getInputStream()) {
                    if (screenshot) {
                        ByteArrayOutputStream memory = new ByteArrayOutputStream();
                        byte[] buffer = new byte[32768];
                        int count;
                        while ((count = stream.read(buffer)) != -1) {
                            memory.write(buffer, 0, count);
                            if (memory.size() > 75_000_000) throw new Exception("Zbyt duży screenshot");
                        }
                        byte[] image = memory.toByteArray();
                        if (image.length < 8 || image[0] != (byte)137 || image[1] != 80 || image[2] != 78 || image[3] != 71)
                            throw new Exception("Odpowiedź nie jest obrazem PNG");
                        savePng(image);
                        report("Screenshot zapisany w Galerii → ZdalnyPulpit11.");
                    } else report("Połączenie działa. Polecenie zostało wysłane.");
                }
            } catch (Exception e) { report("Błąd: " + e.getMessage()); }
            finally { if (c != null) c.disconnect(); }
        });
    }
    private void savePng(byte[] data) throws Exception {
        String time = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        ContentValues meta = new ContentValues();
        meta.put(MediaStore.Images.Media.DISPLAY_NAME, "Pulpit_Windows_" + time + ".png");
        meta.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        meta.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ZdalnyPulpit11");
        meta.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, meta);
        if (uri == null) throw new Exception("Nie mogę otworzyć Galerii.");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("Nie można utworzyć pliku");
            out.write(data);
        } catch (Exception ex) { getContentResolver().delete(uri, null, null); throw ex; }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
    }
    private void report(String msg) { runOnUiThread(() -> { result.setText(msg); toast(msg); }); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}