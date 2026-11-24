package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 0.4.2 (point 6): after reversing, return to the app that was in front (CarPlay, Android Auto also via box or dongle,
 * navigator or another app), instead of staying on the launcher.
 * <p>
 * Reversing is detected from the camera screen coming to the foreground (accessibility service) or from the
 * SAIC camera broadcasts; the end from the screen closing. If after closing the launcher is in front (or
 * the MG home the launcher would replace) the remembered app is reopened, as its button in the launcher does.
 * Not reopened if: setting off, reversing longer than 3 minutes, the user chose another app or pressed
 * Home, the phone/box is no longer connected.
 */
public final class RetroReturn {
    private RetroReturn() {}

    /** Who reopens the apps: the launcher (MainActivity), with the same method as its buttons. */
    public interface Opener {
        /** true if the app is still connected/openable (for CarPlay/Android Auto: device connected). */
        boolean available(String pkg);

        /** Reopens the app like its button in the launcher. */
        boolean reopen(String pkg);
    }

    private static final long MAX_REVERSE_MS = 3 * 60_000L, CHECK_AFTER_MS = 1200;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static volatile Opener opener;

    private static String current = "";        // app in the foreground now (including the launcher)
    private static String saved;               // app remembered at the start of reversing
    private static long startedAt = -1;
    private static boolean inReverse, userChoice, restorePending;
    private static String broughtBy = "";
    private static String startWhy = "";

    public static void setOpener(Opener o) {
        opener = o;
    }

    public static boolean isCamera(String pkg) {
        if (pkg == null || pkg.startsWith("com.mg4.")) return false;   // MG4 360 opened on purpose is not reversing
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        return p.equals("com.saicmotor.evs") || p.contains("aroundview") || p.contains(".avm") || p.contains("rvc") || p.contains("camera");
    }

    private static boolean isProjection(String pkg) {
        return pkg != null && (pkg.startsWith("com.allgo.carplay") || pkg.startsWith("com.allgo.app.androidauto") || pkg.startsWith("com.allgo."));
    }

    private static boolean ignorable(Context c, String pkg) {
        return pkg == null || "com.android.systemui".equals(pkg) || pkg.equals(c.getPackageName()) || BootWatcher.MG_HOME.equals(pkg)
                || pkg.startsWith("android") || pkg.equals("com.android.settings");
    }

    /**
     * 0.4.4 (issue #2, from SWI68 1.0.4): apps NEVER to remember or reopen. After reversing the "Grant Permissions"
     * request of another app appeared: the launcher restarted from scratch apps that were in front only briefly.
     * Excluded: other launchers (CATEGORY_HOME), system apps (except CarPlay/Android Auto), dashcams and apps with only
     * overlay windows (MG4 Control, clusterprojection), the MG home.
     */
    private static final String[] NEVER = {"com.drivehub.kamera", "com.saicmotor.hmi.clusterprojection", "com.tommasov.mg4swipenovalauncher"};
    private static java.util.Set<String> homeApps;

    static boolean neverReopen(Context c, String pkg) {
        if (pkg == null || ignorable(c, pkg) || BootWatcher.MG_HOME.equals(pkg) || isCamera(pkg)) return true;
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String n : NEVER) if (p.equals(n)) return true;
        if (p.contains("dashcam") || p.contains("kamera") || p.contains("dvr") || p.contains("mg4control") || p.contains("mg4.control")) return true;
        if (homeApps == null) {
            java.util.Set<String> h = new java.util.HashSet<>();
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME);
                for (android.content.pm.ResolveInfo ri : c.getPackageManager().queryIntentActivities(i, 0)) h.add(ri.activityInfo.packageName);
            } catch (Exception ignored2) {
            }
            homeApps = h;
        }
        if (homeApps.contains(pkg)) return true;
        if (isProjection(pkg)) return false;
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return true;
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    /**
     * App with a real window (task) in front, the most recent non-excluded one: apps with only overlay windows have
     * no task and stay out on their own. Returns {package, task id} or null.
     */
    @SuppressWarnings("deprecation")
    private static Object[] topTask(Context c) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningTaskInfo ti : am.getRunningTasks(8)) {
                if (ti.topActivity == null) continue;
                String pkg = ti.topActivity.getPackageName();
                // like the SWI68: only systemui, keyboard and panels are skipped; launcher and MG home reach neverReopen
                if (isCamera(pkg) || topIgnored(pkg)) continue;
                if (neverReopen(c, pkg)) return null;   // the launcher, the MG home or an excluded app was in front
                return new Object[]{pkg, ti.id};
            }
        } catch (Exception ignored2) {
        }
        return null;
    }

    private static boolean topIgnored(String pkg) {
        return pkg.equals("com.android.systemui") || pkg.equals("android") || pkg.contains("inputmethod");
    }

    /** Id of a package's already open task, -1 if none. */
    @SuppressWarnings("deprecation")
    private static int taskOf(Context c, String pkg) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningTaskInfo ti : am.getRunningTasks(16)) {
                if (ti.topActivity != null && pkg.equals(ti.topActivity.getPackageName())) return ti.id;
                if (ti.baseActivity != null && pkg.equals(ti.baseActivity.getPackageName())) return ti.id;
            }
        } catch (Exception ignored2) {
        }
        return -1;
    }

    /** New app in the foreground (accessibility service). */
    public static synchronized void onForeground(Context c, String pkg) {
        if (pkg == null || "com.android.systemui".equals(pkg)) return;
        if (isCamera(pkg)) {
            if (!inReverse) start(c, "cameras in foreground (" + pkg + ")");
            return;
        }
        if (inReverse) end(c, "cameras closed, in front " + pkg);
        else if (restorePending && !ignorable(c, pkg) && !pkg.equals(saved)) {
            userChoice = true;   // between closing and the check the user opened another app
        }
        current = pkg;
    }

    /** SAIC camera broadcasts (com.saicmotor.360camera.show / close). */
    public static synchronized void onCameraBroadcast(Context c, boolean show) {
        if (show && !inReverse) start(c, "broadcast cameras opened");
        else if (!show && inReverse) end(c, "broadcast cameras closed");
    }

    private static void start(Context c, String why) {
        inReverse = true;
        startedAt = SystemClock.elapsedRealtime();
        userChoice = false;
        restorePending = false;
        broughtBy = "";
        startWhy = why;
        // 0.4.4: the app to reopen is the one with a real window in front (task), not the last window seen; never other
        // launchers, system apps (except CarPlay/Android Auto), dashcams or apps with only overlay windows
        Object[] top = topTask(c);
        String cand = current != null && !neverReopen(c, current) ? current : null;
        if (top != null && (cand == null || !isProjection(cand))) cand = (String) top[0];
        else if (top == null && cand != null && !isProjection(cand)) cand = null;
        saved = cand;
        Diag.put("reverse.return", "reversing started (" + why + ") · saved app " + (saved == null ? "none (the launcher was in front)" : saved));
    }

    private static void end(Context c, String why) {
        inReverse = false;
        long dur = SystemClock.elapsedRealtime() - startedAt;
        if (saved == null) {
            Diag.put("reverse.return", "reversing ended (" + why + ") · no app to reopen");
            return;
        }
        if (dur > MAX_REVERSE_MS) {
            Diag.put("reverse.return", "reversing ended · " + saved + " NOT reopened: reversing lasted " + (dur / 1000) + " s (more than 3 minutes)");
            saved = null;
            return;
        }
        restorePending = true;
        final Context app = c.getApplicationContext();
        h.postDelayed(() -> check(app, why, dur), CHECK_AFTER_MS);
    }

    /** Records who brought the launcher (or the MG home) to the foreground after the cameras. */
    public static synchronized void noteBroughtBy(String who) {
        if (inReverse || restorePending) broughtBy = who;
    }

    /** Home key pressed by the user: no automatic return. */
    public static synchronized void noteHomePressed() {
        if (inReverse || restorePending) {
            userChoice = true;
            broughtBy = "user's Home button";
        }
    }

    /**
     * Called by StartGuard when the MG home to replace appears: if there is an app to reopen after reversing,
     * that one is reopened instead of the launcher. true = handled here.
     */
    public static synchronized boolean interceptMgHome(Context c) {
        if (!restorePending && !inReverse) return false;
        broughtBy = "MG home opened by the system after the cameras";
        if (inReverse) return false;
        return restoreNow(c, "MG home replaced directly by the saved app");
    }

    private static synchronized void check(Context c, String why, long dur) {
        if (!restorePending) return;
        String top = StartGuard.topPackage(c);
        if (top != null && !top.equals(c.getPackageName()) && !BootWatcher.MG_HOME.equals(top)) {
            restorePending = false;
            Diag.put("reverse.return", "reversing ended (" + (dur / 1000) + " s) · saved app " + saved + " · in front "
                    + top + (top.equals(saved) ? ": already returned on its own" : ": NOT reopened (user's choice)"));
            return;
        }
        restoreNow(c, "launcher in foreground after the cameras" + (broughtBy.isEmpty() ? "" : " (brought back by: " + broughtBy + ")"));
    }

    private static boolean restoreNow(Context c, String context) {
        restorePending = false;
        String app = saved;
        saved = null;
        if (app == null) return false;
        String reason = null;
        boolean on = com.mg4.launcher.ui.Prefs.retroReturn(c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE));
        Opener o = opener;
        if (!on) reason = "setting off";
        else if (userChoice) reason = "the user chose another app or pressed Home";
        else if (o != null && !o.available(app)) reason = isProjection(app) ? "phone/box no longer connected" : "app no longer available";
        if (reason != null) {
            Diag.put("reverse.return", context + " · saved app " + app + " · NOT reopened: " + reason);
            return false;
        }
        // 0.4.4: the already open window is brought back to front (same state, no restart, no permission request).
        // If the task is gone, only CarPlay/Android Auto is started from scratch, like its button; other apps are not.
        int task = taskOf(c, app);
        boolean ok = false;
        String method;
        if (task >= 0) {
            try {
                ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).moveTaskToFront(task, 0);
                ok = true;
            } catch (Exception e) {
                ok = isProjection(app) && o != null && o.reopen(app);
            }
            method = "task brought to front";
        } else if (isProjection(app)) {
            ok = o != null && o.reopen(app);
            method = "started";
        } else {
            Diag.put("reverse.return", context + " · saved app " + app + " · NOT reopened: its window is no longer open");
            return false;
        }
        Diag.put("reverse.return", context + " · saved app " + app + " · " + (ok ? "reopened" : "reopen FAILED")
                + " · method: " + method);
        return ok;
    }
}
