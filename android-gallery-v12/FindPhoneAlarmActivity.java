package pl.folderlan.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen alarm UI. Displayed over the lock screen only when Android allows
 * a full-screen alarm notification. Otherwise the same STOP action is in the
 * notification. No overlay/system-alert-window permission is requested.
 */
public final class FindPhoneAlarmActivity extends Activity {
    public static final String ACTION_SHOW_ALARM = "pl.folderlan.android.SHOW_FIND_ALARM";

    private GradientDrawable rounded(int color, float dpRadius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dpRadius * getResources().getDisplayMetrics().density);
        return bg;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);

        LinearLayout content = new LinearLayout(this);
        content.setBackgroundColor(Color.rgb(17, 23, 34));
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(30), dp(24), dp(28));
        content.setGravity(Gravity.CENTER);

        TextView symbol = new TextView(this);
        symbol.setText("☎");
        symbol.setTextColor(Color.WHITE);
        symbol.setTextSize(66);
        symbol.setGravity(Gravity.CENTER);
        content.addView(symbol, new LinearLayout.LayoutParams(-1, dp(100)));

        TextView title = new TextView(this);
        title.setText("ZNAJDŹ TELEFON");
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(-1, dp(70)));

        TextView sub = new TextView(this);
        sub.setText("Komputer uruchomił alarm w telefonie.\nAby go wyciszyć, naciśnij czerwony przycisk.");
        sub.setTextSize(16);
        sub.setTextColor(Color.rgb(223, 231, 240));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(dp(4), dp(12), dp(4), dp(32));
        content.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        Button stop = new Button(this);
        stop.setText("WYŁĄCZ DŹWIĘK");
        stop.setAllCaps(false);
        stop.setTextColor(Color.WHITE);
        stop.setTextSize(21);
        stop.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        stop.setBackground(rounded(Color.rgb(214, 34, 50), 16));
        stop.setContentDescription("Wyłącz dźwięk szukania telefonu");
        stop.setOnClickListener(v -> stopAlarm());
        content.addView(stop, new LinearLayout.LayoutParams(-1, dp(100)));

        TextView info = new TextView(this);
        info.setText("Alarm wyłączy się automatycznie po 45 sekundach.");
        info.setTextSize(13);
        info.setTextColor(Color.rgb(195, 206, 219));
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(24), 0, 0);
        content.addView(info);

        setContentView(content);
    }

    private int dp(int px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }

    private void stopAlarm() {
        Intent stop = new Intent(this, FindPhoneService.class);
        stop.setAction(FindPhoneService.STOP_RING);
        try { startService(stop); } catch (Exception ignored) {}
        finish();
    }

    @Override public void onBackPressed() {
        stopAlarm();
    }
}
