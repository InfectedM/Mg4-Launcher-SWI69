package com.mg4.launcher.svc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * 1.0.4 (point D): MG4 Store inside the launcher (assets/MG4Store.apk, signed with the same platform key).
 * <ul>
 *   <li>At the first power-on with a launcher that contains it, the Store installs itself, silently, ONLY if missing
 *       or older than the bundled one. It happens only once: afterwards the Store updates itself and the launcher
 *       no longer touches it, not even if a new launcher version contains an older Store.</li>
 *   <li>If the user uninstalls it, the launcher does not put it back: there is "Installa MG4 Store" in the settings.</li>
 * </ul>
 * "store" log: installed vN / installed now / installation failed (reason) / removed by the user.
 */
public final class StoreSetup {
    public static final String PKG = "com.mg4.store", ASSET = "MG4Store.apk";
    private static final String K_DONE = "storeGestito", ACTION = "com.mg4.launcher.STORE_INSTALL.";

    public interface Done {
        void done(boolean ok, String message);
    }

    private StoreSetup() {}

    /** Installed Store version (versionCode), -1 if absent. */
    public static long installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PKG, 0).versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PKG, 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    /** {versionCode, versionName} of the Store bundled in the launcher, null if missing. */
    static Object[] bundled(Context c) {
        java.io.File tmp = new java.io.File(c.getCacheDir(), "store-check.apk");
        try {
            copyAsset(c, tmp);
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
            return pi == null ? null : new Object[]{(long) pi.versionCode, pi.versionName, tmp.length()};
        } catch (Exception e) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Fork: does this APK bundle the MG4 Store (assets/MG4Store.apk)? Builds without the Store do not show the install button. */
    public static boolean bundledAvailable(Context c) {
        try (InputStream in = c.getApplicationContext().getAssets().open(ASSET)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Launcher start: automatic installation only once, then only the log. Must be called off the main thread. */
    public static void onStart(Context ctx) {
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        long have = installedCode(c);
        if (sp.getBoolean(K_DONE, false)) {
            Diag.put("store", have >= 0 ? "installed v" + installedName(c) + " (updates itself)" : "removed by the user (reinstall from Settings → Install MG4 Store)");
            return;
        }
        Object[] b = bundled(c);
        if (b == null) {
            Diag.put("store", "no Store inside the launcher");
            return;
        }
        long code = (Long) b[0];
        if (have >= code) {
            sp.edit().putBoolean(K_DONE, true).apply();   // from here on the launcher no longer touches it by itself
            Diag.put("store", "installed v" + installedName(c) + " (same as or newer than the one in the launcher, v" + b[1] + ")");
            return;
        }
        String before = installedName(c);
        install(c, false, (ok, msg) -> {
            // the "done" mark only if it succeeded: if it fails, retry at the next power-on
            if (ok) sp.edit().putBoolean(K_DONE, true).apply();
            Diag.put("store", ok ? "installed now v" + b[1] + (before != null ? " (before v" + before + ")" : "")
                    : "installation failed (" + msg + ") · retrying at next power-on");
        });
    }

    /**
     * Installs the Store bundled in the launcher. byUser = true (button in the settings): if Android asks for a
     * confirmation it shows it; at power-on it does not.
     */
    public static void install(Context ctx, boolean byUser, Done done) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            java.io.File tmp = new java.io.File(app.getCacheDir(), ASSET);
            try {
                copyAsset(app, tmp);
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(PKG);
                p.setSize(tmp.length());
                // internal storage: the MG firmware blocks non-system apps on the "automatic" location
                p.setInstallLocation(PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
                try {   // INSTALL_REPLACE_EXISTING (0x2) + INSTALL_INTERNAL (0x10), like "adb install -r"
                    java.lang.reflect.Field f = PackageInstaller.SessionParams.class.getField("installFlags");
                    f.setInt(p, f.getInt(p) | 0x2 | 0x10);
                } catch (Throwable ignored) {
                }
                int id = pi.createSession(p);
                String action = ACTION + id;
                BroadcastReceiver rcv = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                            if (byUser && confirm != null) {
                                app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                                return;   // the result arrives after the confirmation
                            }
                            try {   // at power-on no on-screen confirmation: the session is closed
                                pi.abandonSession(id);
                            } catch (Exception ignored) {
                            }
                        }
                        try {
                            app.unregisterReceiver(this);
                        } catch (Exception ignored) {
                        }
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        String msg = st == PackageInstaller.STATUS_PENDING_USER_ACTION ? "Android asks for confirmation"
                                : i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        done.done(st == PackageInstaller.STATUS_SUCCESS, msg == null ? "code " + st : msg);
                    }
                };
                app.registerReceiver(rcv, new IntentFilter(action));
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new java.io.FileInputStream(tmp); OutputStream os = s.openWrite("base.apk", 0, tmp.length())) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        s.fsync(os);
                    }
                    PendingIntent pend = PendingIntent.getBroadcast(app, id, new Intent(action).setPackage(app.getPackageName()),
                            PendingIntent.FLAG_UPDATE_CURRENT);
                    s.commit(pend.getIntentSender());
                } catch (Exception e) {
                    try {
                        app.unregisterReceiver(rcv);
                    } catch (Exception ignored) {
                    }
                    try {
                        pi.abandonSession(id);
                    } catch (Exception ignored) {
                    }
                    throw e;
                }
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                done.done(false, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        }, "mg4-store").start();
    }

    private static void copyAsset(Context c, java.io.File to) throws java.io.IOException {
        try (InputStream in = c.getAssets().open(ASSET); OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
