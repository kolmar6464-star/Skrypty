package pl.zdalnypulpit.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.res.ColorStateList;

/**
 * Launcher for the RustDesk public ID network.
 * No custom authentication service, no open port, no Tailscale dependency.
 * RustDesk itself authenticates the connection and remembers approved sessions.
 */
public final class MainActivity extends Activity {
    private EditText idInput;
    private TextView hint;
    private SharedPreferences prefs;

    private int dp(float value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("zp11_id_v2", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(246, 248, 253));

        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(dp(22), dp(28), dp(22), dp(36));
        layout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(layout);
        setContentView(scroll);

        TextView header = text("Zdalny Pulpit 11", 27, true);
        header.setTextColor(Color.rgb(27, 46, 73));
        layout.addView(header);
        TextView subtitle = text("Połączenie z komputerem tylko przez ID RustDesk", 14, false);
        layout.addView(subtitle);

        space(layout, 22);
        TextView idLabel = text("ID KOMPUTERA", 13, true);
        layout.addView(idLabel);

        idInput = new EditText(this);
        idInput.setSingleLine(true);
        idInput.setTextSize(22);
        idInput.setSelectAllOnFocus(true);
        idInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                             android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        idInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(90)});
        idInput.setHint("np. 123456789");
        idInput.setText(prefs.getString("id", ""));
        idInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        idInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) { connect(); return true; }
            return false;
        });
        layout.addView(idInput, new LinearLayout.LayoutParams(-1, dp(66)));

        Button connect = button("POŁĄCZ Z KOMPUTEREM", true);
        connect.setOnClickListener(v -> connect());
        layout.addView(connect);

        hint = text("ID zapisze się automatycznie. Po pierwszej autoryzacji RustDesk może zapamiętać hasło na tym telefonie.", 13, false);
        layout.addView(hint);

        space(layout, 18);
        TextView sec = text("FUNKCJE DODATKOWE", 14, true);
        layout.addView(sec);

        Button screen = button("Screenshot pulpitu → Galeria", false);
        screen.setOnClickListener(v -> dialog("Zrzut ekranu",
            "Po połączeniu przez RustDesk wyświetl pulpit na całym ekranie telefonu. Naciśnij jednocześnie przyciski Zasilanie + Ciszej. Android zapisze zrzut widocznego ekranu w Galerii.\n\nJest to zrzut ekranu telefonu, a nie bezpośredni obraz w rozdzielczości komputera."));
        layout.addView(screen);

        Button power = button("Wyłącz / uruchom ponownie komputer", false);
        power.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Zarządzanie zasilaniem Windows")
            .setMessage("Połącz się z pulpitem przez RustDesk, otwórz menu Start → Zasilanie, a następnie wybierz Zamknij lub Uruchom ponownie.\n\nSam numer ID nie daje oddzielnej aplikacji prawa do wysyłania komend zasilania bez uwierzytelnionej sesji.")
            .setPositiveButton("Otwórz pulpit", (d, w) -> connect())
            .setNegativeButton("Zamknij", null).show());
        layout.addView(power);

        Button wake = button("Jak włączyć wyłączony komputer?", false);
        wake.setOnClickListener(v -> dialog("Wake-on-LAN",
            "Włączenie całkowicie wyłączonego komputera przez internet wymaga obsługi Wake-on-LAN w BIOS/UEFI i karcie Ethernet oraz urządzenia w sieci domowej (router lub przekaźnik), które nada pakiet budzący. Sam numer ID RustDesk nie pozwala włączyć wyłączonego PC."));
        layout.addView(wake);

        Button reverse = button("Steruj telefonem z komputera", false);
        reverse.setOnClickListener(v -> dialog("Sterowanie Androidem z Windows",
            "Na telefonie otwórz RustDesk → Udostępnij ekran, uruchom usługę i przyznaj uprawnienia do przechwytywania ekranu i sterowania. Na Windows połącz się z ID telefonu. System Android może wymagać ponownego zatwierdzania przechwytywania."));
        layout.addView(reverse);

        space(layout, 18);
        TextView foot = text("Wymagany oficjalny RustDesk na telefonie. ID nie jest hasłem — pierwsze połączenie wymaga autoryzacji. Program nie wyłącza mechanizmów bezpieczeństwa.", 12, false);
        layout.addView(foot);
    }

    private void connect() {
        String id = idInput.getText().toString().trim();
        if (!id.matches("[A-Za-z0-9._@-]{5,80}")) {
            idInput.setError("Wpisz poprawne ID RustDesk (5–80 znaków)");
            return;
        }
        prefs.edit().putString("id", id).apply();
        // RustDesk's documented Android 1.5.0 URI route (flutter/lib/common.dart).
        Uri uri = Uri.parse("rustdesk://connection/new/").buildUpon().appendPath(id).build();
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setPackage("com.carriez.flutter_hbb");
        try {
            startActivity(intent);
            hint.setText("Zapisano ID: " + id + ". Połączenie jest obsługiwane przez RustDesk.");
        } catch (ActivityNotFoundException ex) {
            new AlertDialog.Builder(this)
                .setTitle("Brakuje RustDesk")
                .setMessage("Do obsługi pulpitu zainstaluj oficjalny RustDesk na telefonie. Następnie wróć tutaj i naciśnij Połącz.")
                .setPositiveButton("Strona RustDesk", (d, i) -> {
                    Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rustdesk/rustdesk/releases"));
                    startActivity(browser);
                })
                .setNegativeButton("Anuluj", null).show();
        } catch (Exception ex) {
            dialog("Nie udało się uruchomić RustDesk", ex.getMessage() == null ? "Sprawdź instalację RustDesk." : ex.getMessage());
        }
    }

    private TextView text(String value, int pt, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(pt);
        t.setTextColor(Color.rgb(55, 71, 95));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(7), 0, dp(8));
        return t;
    }
    private Button button(String value, boolean primary) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(primary ? 16 : 13);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : Color.rgb(24, 51, 87));
        b.setBackgroundTintList(ColorStateList.valueOf(primary ? Color.rgb(24, 104, 204) : Color.rgb(220, 232, 249)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(primary ? 56 : 52));
        p.setMargins(0, dp(7), 0, dp(5));
        b.setLayoutParams(p);
        return b;
    }
    private void space(LinearLayout root, int height) {
        View v = new View(this);
        root.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
    }
    private void dialog(String title, String body) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK", null).show();
    }
}