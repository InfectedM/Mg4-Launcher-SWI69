package com.mg4.launcher.climate;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;

import com.mg4.launcher.svc.Diag;

/**
 * The separate MG4 Clima app (com.mg4.clima) is no longer needed: its screen is built into the launcher. If it is still
 * installed it would replace the original climate app at the same time as the launcher, so it is uninstalled silently
 * (DELETE_PACKAGES, platform signature). Its settings are not carried over: the verified climate app versions and the
 * measured temperature range are measured again by the check.
 */
public final class OldClimateApp {
    public static final String PKG = "com.mg4.clima";
    private static final String ACTION = "com.mg4.launcher.climate.OLD_APP_REMOVED";
    private static volatile boolean tried;

    private OldClimateApp() {
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Uninstalls MG4 Clima if it is installed (once per launcher process). Any thread. */
    public static void removeIfInstalled(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (tried || !installed(app)) {
            if (!tried) Diag.put("climate.oldApp", "not installed");
            return;
        }
        tried = true;
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        app.unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                    int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    String line = st == PackageInstaller.STATUS_SUCCESS ? "MG4 Clima uninstalled (built into the launcher)"
                            : "MG4 Clima not uninstalled (" + (msg == null ? "code " + st : msg) + ")";
                    Diag.put("climate.oldApp", line);
                    ClimateStore.event(app, line);
                }
            }, new IntentFilter(ACTION));
            PendingIntent pi = PendingIntent.getBroadcast(app, 0, new Intent(ACTION).setPackage(app.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            app.getPackageManager().getPackageInstaller().uninstall(PKG, pi.getIntentSender());
            Diag.put("climate.oldApp", "MG4 Clima installed: uninstalling");
        } catch (Throwable e) {
            Diag.put("climate.oldApp", "MG4 Clima installed, uninstall failed: " + e);
        }
    }
}
