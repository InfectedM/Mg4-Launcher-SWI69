package com.mg4.launcher.svc;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;

/**
 * Shared power-on start logic, used by the accessibility service (window change events) and,
 * if the service is not active, by the fallback check every 400 ms (BootWatcher).
 * <p>
 * Start window: from power-on (or screen wake-up) until 5 s after our launcher is in the
 * foreground. Since 0.3.4: if the MG home has not appeared yet, the window stays open until the firmware
 * brings it to front at least once (at most 3 minutes), because on these cars it often arrives after our launcher. Inside the window, if the MG home appears, our launcher is reopened (at
 * most 3 times). It does not intervene if the MG home was opened on purpose, if another app is in the foreground
 * (rear camera, CarPlay, Android Auto, call, alerts) or if the switch is off / workshop mode is on.
 */
public final class StartGuard {
    private static final long WINDOW_MS = 60_000, OURS_STABLE_MS = 5000, ORIGINAL_GRACE_MS = 10 * 60_000L;
    /** Maximum wait for the MG home, if it has not appeared yet (0.3.4, measured on this version's logs). */
    private static final long WAIT_MG_MS = 180_000L, AFTER_MG_MS = 30_000;
    private static final int MAX_RESTARTS = 3;

    // times from SystemClock.elapsedRealtime() (ms since power-on), -1 = not yet
    private static long tProcess = -1, tA11y = -1, tBoot = -1, tMg = -1, tOurs = -1;
    private static long windowStart = -1, oursSince = -1, deadline = -1;
    private static boolean mgInWindow;
    private static int restarts;
    private static String windowWhy = "";
    static volatile boolean a11yConnected;

    private StartGuard() {}

    /**
     * User unlocked? Before unlocking the launcher settings (credential storage) are not readable and
     * reading them crashes the process (0.3.4: every start job waits for the unlock).
     */
    public static boolean unlocked(Context c) {
        try {
            android.os.UserManager um = (android.os.UserManager) c.getSystemService(Context.USER_SERVICE);
            return um == null || um.isUserUnlocked();
        } catch (Throwable e) {
            return true;
        }
    }

    /** Call when the user opens the MG home on purpose: for a while the start logic does not intervene. */
    public static void userOpenedOriginal(Context c) {
        // 0.9.6: time since power-on + power-on number (the car clock at start can be hours behind and
        // the permission stayed valid at the next power-on too)
        sp(c).edit().putLong("originalHomeUntil", SystemClock.elapsedRealtime() + ORIGINAL_GRACE_MS)
                .putInt("originalHomeBoot", bootCount(c)).apply();
    }

    private static int bootCount(Context c) {
        try {
            return android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** MG home opened on purpose recently, in this same power-on? */
    private static boolean originalGrace(Context c) {
        SharedPreferences s = sp(c);
        return s.getInt("originalHomeBoot", -2) == bootCount(c)
                && SystemClock.elapsedRealtime() < s.getLong("originalHomeUntil", 0);
    }

    // --- 0.9.6: other homes (another launcher, e.g. MG4Dash) treated like the MG home at power-on ---
    private static final java.util.Map<String, Boolean> homeCache = new java.util.HashMap<>();

    static boolean isHome(Context c, String pkg) {
        if (BootWatcher.MG_HOME.equals(pkg)) return true;
        if (pkg == null) return false;
        Boolean v = homeCache.get(pkg);
        if (v == null) {
            v = HomeLock.isOtherHome(c, pkg);
            homeCache.put(pkg, v);
            if (v) Diag.put("startup.otherHome", pkg + " (treated as the MG home)");
        }
        return v;
    }

    /** Apps not covered at power-on: rear camera, phone calls, CarPlay/Android Auto, navigator. */
    private static boolean keepInFront(String pkg) {
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String k : new String[]{"aroundview", "avm", "rvc", "camera", "btcall", "phone", "dialer", "incall", "carplay",
                "androidauto", "allgo", "telenav", "navi", "map", "waze"}) {
            if (p.contains(k)) return true;
        }
        return false;
    }

    /**
     * 0.9.6: if a few seconds after power-on the MG home has not appeared and no app to leave is in front
     * (e.g. the projection on the cluster), the launcher is opened anyway (once). Called by the accessibility service.
     */
    public static synchronized void lateCheck(Context c) {
        if (windowStart < 0 || !windowOpen() || mgInWindow || tOurs >= 0 && oursSince >= 0) return;
        if (!HomeMode.active(c) || originalGrace(c)) return;
        String top = topPackage(c);
        if (top != null && (c.getPackageName().equals(top) || keepInFront(top))) {
            Diag.put("startup.homeMissing", "MG home did not appear; in front " + top + ": left alone");
            return;
        }
        if (restarts < MAX_RESTARTS) {
            restarts++;
            HomeMode.openOurs(c);
            Diag.put("startup.homeResumed", "opened the launcher: MG home did not appear, in front " + (top == null ? "no app" : top));
        }
    }

    private static SharedPreferences sp(Context c) {
        return com.mg4.launcher.ui.Prefs.sp(c);
    }

    public static synchronized void markProcess() {
        if (tProcess < 0) tProcess = Process.getStartElapsedRealtime();
    }

    public static synchronized void markA11y() {
        markProcess();
        if (tA11y < 0) tA11y = SystemClock.elapsedRealtime();
        times();
    }

    public static synchronized void markBoot() {
        markProcess();
        if (tBoot < 0) tBoot = SystemClock.elapsedRealtime();
        times();
    }

    /** Opens the start window (power-on or screen wake-up). */
    public static synchronized void openWindow(Context c, String why) {
        markProcess();
        if (!HomeMode.active(c)) {
            Diag.put("startup.homeResumed", "no (" + HomeMode.reason(c) + ")");
            return;
        }
        if (windowOpen()) return;
        windowStart = SystemClock.elapsedRealtime();
        deadline = windowStart + WAIT_MG_MS;
        mgInWindow = false;
        oursSince = -1;
        restarts = 0;
        windowWhy = why;
        Diag.put("startup.homeResumed", "check in progress (" + why + ")");
    }

    public static synchronized boolean windowOpen() {
        return windowStart >= 0 && SystemClock.elapsedRealtime() < deadline;
    }

    /** true if in the current window the MG home has already appeared (or the window is closed). */
    public static synchronized boolean mgSeenInWindow() {
        return windowStart < 0 || mgInWindow;
    }

    /** New app in the foreground (periodic check: package only). */
    public static synchronized void onForeground(Context c, String pkg) {
        onForeground(c, pkg, null, false);
    }

    // --- SWI69: MG home opened by name from the Home key (or from an app remapping keys), outside the start ---
    private static final long LOOP_WINDOW_MS = 10_000, LOOP_PAUSE_MS = 2 * 60_000L, HOME_CHECK_MS = 20_000;
    private static final int LOOP_MAX = 3;
    private static final java.util.ArrayDeque<Long> mgSeen = new java.util.ArrayDeque<>();
    private static long pausedUntil = -1, homeCheckedAt = -1;
    private static boolean ourHomeDefault, mgHomeDefault;
    private static String prevPkg = "";

    /**
     * New app in the foreground. anytime = true (accessibility service): with the launcher as default home, the
     * main screen of the MG home is replaced every time it appears, not only at power-on.
     */
    public static synchronized void onForeground(Context c, String pkg, String cls, boolean anytime) {
        if (pkg == null) return;
        String prev = prevPkg;
        if (!pkg.equals(prevPkg)) prevPkg = pkg;
        if (anytime && !windowOpen() && BootWatcher.MG_HOME.equals(pkg) && isMgMain(pkg, cls)) {
            replaceAnytime(c, prev);
        }
        long now = SystemClock.elapsedRealtime();
        if (BootWatcher.MG_HOME.equals(pkg) && tMg < 0) {
            tMg = now;
            Diag.put("startup.foreground", pkg + " after " + (now / 1000) + " s");
            times();
        }
        if (c.getPackageName().equals(pkg) && tOurs < 0) {
            tOurs = now;
            times();
        }
        if (windowStart < 0) return;
        if (now >= deadline) {
            close(restarts > 0 ? "yes (timed out)" : mgInWindow ? "no (timed out)" : "no (MG home never appeared)");
            return;
        }
        if (!HomeMode.active(c)) {
            close("no (" + HomeMode.reason(c) + ")");
            return;
        }
        if (isHome(c, pkg)) {
            oursSince = -1;
            if (!mgInWindow) {
                mgInWindow = true;
                // from here the previous timings suffice: 60 s from the start, but at least 30 s to verify the reopening
                deadline = Math.max(windowStart + WINDOW_MS, now + AFTER_MG_MS);
            }
            if (originalGrace(c)) {
                close("no (MG home opened on purpose)");
                return;
            }
            if (restarts < MAX_RESTARTS) {
                restarts++;
                HomeMode.openOurs(c);
                Diag.put("startup.homeResumed", "reopened the launcher (attempt " + restarts + ", " + windowWhy + ")");
            }
        } else if (c.getPackageName().equals(pkg)) {
            if (oursSince < 0) oursSince = now;
            if (now - oursSince >= OURS_STABLE_MS) {
                if (mgInWindow) close(restarts > 0 ? "yes" : "no (already in foreground)");
                else Diag.put("startup.homeResumed", "launcher in front, waiting for the MG home (" + windowWhy + ")");
            }
        } else {
            oursSince = -1;   // another app (rear camera, CarPlay, call...): left alone
        }
    }

    /** Only the main screen of the MG home (not the guides or other pages opened by the system). */
    private static boolean isMgMain(String pkg, String cls) {
        return cls == null || !cls.startsWith(pkg) || com.mg4.launcher.apps.MgApps.HOME_CLS.equals(cls);
    }

    private static void replaceAnytime(Context c, String prev) {
        long now = SystemClock.elapsedRealtime();
        if (!HomeMode.active(c)) return;
        if (originalGrace(c)) {
            Diag.putQuiet("home.button", "MG home left alone (opened on purpose by the launcher)");
            return;
        }
        if (homeCheckedAt < 0 || now - homeCheckedAt > HOME_CHECK_MS) {
            homeCheckedAt = now;
            String home = resolvedHome(c);
            ourHomeDefault = c.getPackageName().equals(home);
            mgHomeDefault = com.mg4.launcher.apps.MgApps.HOME_PKG.equals(home);
        }
        // 0.3.8 / 0.2.5: in the firmware the MG home has priority 2 and Android always picks it, even if the launcher is
        // set as default (it happened on almost all cars). It is replaced anyway, as at power-on;
        // it is left alone only if the default home is yet another app (another launcher chosen by the user).
        if (!ourHomeDefault && !mgHomeDefault) {
            Diag.putQuiet("home.button", "MG home left alone (default home: another app)");
            return;
        }
        if (pausedUntil > now) return;
        mgSeen.addLast(now);
        while (!mgSeen.isEmpty() && now - mgSeen.peekFirst() > LOOP_WINDOW_MS) mgSeen.removeFirst();
        if (mgSeen.size() > LOOP_MAX) {
            pausedUntil = now + LOOP_PAUSE_MS;
            mgSeen.clear();
            Diag.put("home.button", "suspended for 2 minutes: the MG home reappeared more than " + LOOP_MAX + " times in 10 s");
            return;
        }
        String origin = prev.startsWith("com.saicmotor.") || prev.startsWith("com.saic.") ? "other (" + prev + ")" : "button";
        // 0.4.2 (point 6): MG home appeared when the cameras closed → reopen the previous app, not the launcher
        if (RetroReturn.interceptMgHome(c)) {
            Diag.put("home.button", "MG home after reversing: reopened the previous app");
            return;
        }
        HomeMode.openOurs(c);
        Diag.put("home.button", "MG home replaced (origin: " + origin + ")");
    }

    /** Package of the default home for ACTION_MAIN + CATEGORY_HOME (written in the logs as home.default). */
    public static String resolvedHome(Context c) {
        String pkg;
        try {
            android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(
                    new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            pkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "none";
        } catch (Throwable t) {
            pkg = "error " + t.getClass().getSimpleName();
        }
        Diag.putQuiet("home.default", pkg);
        return pkg;
    }

    /** Apps that can remap keys (e.g. MG4 Control), written in the logs as home.appTasti. */
    public static void reportKeyApps(Context c) {
        try {
            java.util.Set<String> found = new java.util.LinkedHashSet<>();
            String enabled = android.provider.Settings.Secure.getString(c.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                for (android.accessibilityservice.AccessibilityServiceInfo info : am.getInstalledAccessibilityServiceList()) {
                    if ((info.getCapabilities() & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) == 0) continue;
                    String p = info.getResolveInfo().serviceInfo.packageName;
                    if (p.equals(c.getPackageName())) continue;
                    found.add(p + (enabled != null && enabled.contains(p + "/") ? " (active)" : ""));
                }
            }
            android.content.pm.PackageManager pm = c.getPackageManager();
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(c.getPackageName())) continue;
                String label = String.valueOf(pm.getApplicationLabel(ai)).toLowerCase(java.util.Locale.ROOT);
                String p = ai.packageName.toLowerCase(java.util.Locale.ROOT);
                if (label.contains("mg4 control") || p.contains("mg4control") || label.contains("key mapper") || label.contains("button mapper")
                        || p.contains("keymapper") || p.contains("buttonmapper")) {
                    found.add(ai.packageName + " (" + pm.getApplicationLabel(ai) + ")");
                }
            }
            Diag.put("home.appButtons", found.isEmpty() ? "none found" : android.text.TextUtils.join(", ", found));
        } catch (Throwable t) {
            Diag.put("home.appButtons", "error " + t.getClass().getSimpleName());
        }
    }

    private static void close(String result) {
        Diag.put("startup.homeResumed", result + ", attempts " + restarts + " (" + windowWhy + ")");
        windowStart = -1;
        deadline = -1;
        oursSince = -1;
    }

    /** App in the foreground now (REAL_GET_TASKS, granted by the platform signature). */
    public static String topPackage(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) return null;
            return tasks.get(0).topActivity.getPackageName();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String s(long t) {
        return t < 0 ? "—" : String.format(java.util.Locale.ROOT, "%.1fs", t / 1000f);
    }

    private static void times() {
        Diag.put("startup.timings", "process " + s(tProcess) + " · accessibility " + s(tA11y) + " · boot " + s(tBoot)
                + " · MG home " + s(tMg) + " · our launcher " + s(tOurs));
    }
}
