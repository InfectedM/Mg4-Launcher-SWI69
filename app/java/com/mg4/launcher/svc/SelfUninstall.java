package com.mg4.launcher.svc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Uninstalling the launcher from the car, without a computer (0.8.6-beta).
 * First it restores the original MG home and removes the launcher's accessibility service, then asks Android to
 * uninstall the package (PackageInstaller.uninstall: DELETE_PACKAGES, granted by the platform signature).
 * If that is not possible, the system confirmation opens (ACTION_DELETE).
 * <p>
 * 1.0.4 (issue #29): first of all the "uninstall in progress" marker (HomeLock no longer disables the MG home), then the
 * MG home re-enabled, set as default and verified (3 attempts); only then the uninstall request. If the MG home
 * does not come back on, the launcher is NOT removed. If the uninstall fails or is cancelled, the marker is removed.
 */
public final class SelfUninstall {
    private static final String ACTION_RESULT = "com.mg4.launcher.UNINSTALL_RESULT";

    public interface Result {
        void onFailed(String why);
    }

    private SelfUninstall() {
    }

    public static void run(Context c, Result result) {
        Context app = c.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        // 0) issue #29: "uninstall in progress" marker on disk BEFORE anything else. From here HomeLock no longer disables
        //    the MG home, not even if the launcher comes back to the front while the system confirmation is on screen.
        HomeLock.beginUninstall(app);
        Diag.put("uninstall", "requested · marking uninstall in progress");
        new Thread(() -> {
            // 1) MG home re-enabled, set back as default and VERIFIED (up to 3 attempts)
            boolean ok = false;
            String home = "";
            for (int i = 1; i <= 3 && !ok; i++) {
                HomeLock.release(app, "uninstall, attempt " + i);
                try {
                    HomeDefault.setMg(app);
                } catch (Throwable e) {
                    Diag.error("uninstall.home", e.toString());
                }
                ok = !HomeLock.mgDisabled(app);
                home = defaultHome(app);
                if (!ok) SystemClock.sleep(700);
            }
            if (!ok) {
                // the MG home does not come back on: do NOT uninstall, otherwise the car would be left without a home
                HomeLock.endUninstall(app);
                Diag.put("uninstall", "stopped: MG home still disabled after 3 attempts · launcher not removed");
                HomeLock.apply(app, "uninstall stopped");
                main.post(() -> {
                    if (result != null) result.onFailed("MG home not re-enabled");
                });
                return;
            }
            Diag.put("uninstall", "mark · MG home re-enabled and verified · default: " + home);
            main.post(() -> uninstall(app, result));
        }, "mg4-uninstall").start();
    }

    /** Package of the current default home, for the log. */
    private static String defaultHome(Context c) {
        try {
            android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return r == null || r.activityInfo == null ? "none" : r.activityInfo.packageName;
        } catch (Throwable e) {
            return "?";
        }
    }

    /** Uninstall failed or cancelled: remove the marker and restore everything as before. */
    private static void undo(Context app, String why, Result result) {
        HomeLock.endUninstall(app);
        Diag.put("uninstall", "result: not uninstalled (" + why + ") · mark removed, launcher as before");
        new Thread(() -> HomeLock.apply(app, "uninstall cancelled"), "mg4-homelock").start();
        BootAccessibility.ensureEnabled(app);
        if (result != null) result.onFailed(why);
    }

    private static void uninstall(Context app, Result result) {
        // 2) launcher's accessibility service removed from the list (the others stay)
        try {
            String me = new ComponentName(app, BootAccessibility.class).flattenToString();
            String cur = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (!TextUtils.isEmpty(cur)) {
                StringBuilder keep = new StringBuilder();
                for (String s : cur.split(":")) {
                    if (s.isEmpty() || s.equalsIgnoreCase(me)) continue;
                    if (keep.length() > 0) keep.append(':');
                    keep.append(s);
                }
                Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, keep.toString());
            }
        } catch (Throwable e) {
            Diag.error("uninstall.accessibility", e.toString());
        }
        // 3) uninstall
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Diag.put("uninstall", "result " + st + (msg == null ? "" : " " + msg));
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                ctx.startActivity(confirm);
                                return;   // the result arrives after the confirmation (or cancellation)
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    try {
                        ctx.getApplicationContext().unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                    if (st != PackageInstaller.STATUS_SUCCESS) undo(app, msg == null ? "code " + st : msg, result);
                }
            }, new IntentFilter(ACTION_RESULT));
            PendingIntent pi = PendingIntent.getBroadcast(app, 0,
                    new Intent(ACTION_RESULT).setPackage(app.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT);
            app.getPackageManager().getPackageInstaller().uninstall(app.getPackageName(), pi.getIntentSender());
            Diag.put("uninstall", "sent to PackageInstaller");
        } catch (Throwable e) {
            Diag.error("uninstall", e.toString());
            try {
                // system confirmation: no result comes back, the marker expires by itself after 10 minutes
                Intent del = new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + app.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(del);
            } catch (Throwable e2) {
                undo(app, e2.getClass().getSimpleName(), result);
            }
        }
    }
}
