package pl.folderlan.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Start the already installed v12 FindPhoneService after reboot or app update. */
public final class FindPhoneBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action=intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!context.getSharedPreferences("finder",Context.MODE_PRIVATE)
                    .getBoolean("monitor_requested",true)) return;
        try {
            Intent service=new Intent(context,FindPhoneService.class);
            service.setAction(FindPhoneService.START);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception ignored) {
            // Some device vendors restrict service launch after reboot.
        }
    }
}
