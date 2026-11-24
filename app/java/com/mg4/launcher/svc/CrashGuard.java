package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Safe-start lifesaver (like MG4Dash): 3 launcher crashes in 3 minutes → the MG home screen is
 * re-enabled immediately, so the car screen is never left without a home. Safe start stays suspended until
 * the user opens the launcher by hand again (or turns the option back on).
 * The data lives in "device protected" storage, readable even before unlock.
 */
public final class CrashGuard {
    private static final int LIMIT = 3;
    private static final long WINDOW_MS = 3 * 60_000L;
    private static volatile boolean installed;

    private CrashGuard() {}

    private static SharedPreferences sp(Context c) {
        PrefsMigration.ensure(c);
        Context d = c.getApplicationContext();
        try {
            d = d.createDeviceProtectedStorageContext();
        } catch (Throwable ignored) {
        }
        return d.getSharedPreferences(PrefsMigration.CRASH, Context.MODE_PRIVATE);
    }

    /** Safe start suspended after too many crashes? */
    public static boolean tripped(Context c) {
        try {
            return sp(c).getBoolean("tripped", false);
        } catch (Throwable e) {
            return false;
        }
    }

    /** The user opened the launcher by hand: safe start can be retried. */
    public static void reset(Context c) {
        try {
            SharedPreferences s = sp(c);
            if (s.getBoolean("tripped", false) || !s.getString("crash", "").isEmpty()) {
                s.edit().putBoolean("tripped", false).putString("crash", "").apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /** To be called at process start (activity, accessibility service, receiver). */
    public static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                SharedPreferences s = sp(app);
                long now = System.currentTimeMillis(), up = android.os.SystemClock.elapsedRealtime();
                StringBuilder keep = new StringBuilder();
                int n = 1;
                for (String x : s.getString("crash", "").split(",")) {
                    try {
                        long v = Long.parseLong(x);
                        if (up - v < WINDOW_MS && v <= up) {
                            keep.append(v).append(',');
                            n++;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                keep.append(up);
                SharedPreferences.Editor ed = s.edit().putString("crash", keep.toString());
                if (n >= LIMIT) {
                    ed.putBoolean("tripped", true).putLong("trippedAt", now);
                    ed.commit();
                    HomeLock.release(app, "safeguard: " + n + " crashes in 3 minutes");
                } else {
                    ed.commit();
                }
            } catch (Throwable ignored) {
                // never hide the original crash
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
