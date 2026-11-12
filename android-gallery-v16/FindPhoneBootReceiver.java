package pl.folderlan.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Re-enable the explicitly opted-in find-phone listener after Windows/Android reboot.
 * Device/vendor power restrictions and user permissions still apply.
 */
public final class FindPhoneBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a=intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) &&
            !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a) &&
            !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!context.getSharedPreferences("finder",Context.MODE_PRIVATE)
                    .getBoolean("monitor_requested",false)) return;
        try {
            Intent service = new Intent(context,FindPhoneService.class)
                    .setAction(FindPhoneService.START);
            if (Build.VERSION.SDK_INT>=26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception ignored) {
            // On restricted Android devices the user must re-enable background
            // execution in system settings; never crash the system broadcast.
        }
    }
}
