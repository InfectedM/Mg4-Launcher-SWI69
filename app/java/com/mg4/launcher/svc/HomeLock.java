package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Safe start (0.9.6 / 0.3.9 / 0.2.6): the MG home has a priority that installed apps cannot beat and the
 * firmware opens it by name at power-on. Like MG4Dash, ONLY its main screen is disabled (the rest
 * of the MG app, with its services, stays active): so Android itself opens our launcher at power-on and
 * with the Home key, without chasing the MG home.
 * <p>
 * The MG screen comes back on by itself: "default home" switch off, workshop, deliberate opening of the MG
 * home, "Disinstalla", 3 launcher crashes in 3 minutes ({@link CrashGuard}) and "Avvio sicuro" option off.
 * Platform signature: the CHANGE_COMPONENT_ENABLED_STATE permission is granted by the signature.
 */
public final class HomeLock {
    public static final String K_ON = "safeStart";
    /** 1.0.4 (issue #29): uninstall in progress (start time). While it holds, the MG home is never disabled. */
    private static final String K_UNINSTALL = "uninstallInProgress";
    /** After 10 minutes without outcome (e.g. system confirmation left halfway) the mark expires by itself. */
    private static final long UNINSTALL_MAX_MS = 10 * 60_000L;

    private HomeLock() {}

    private static SharedPreferences sp(Context c) {
        return com.mg4.launcher.ui.Prefs.sp(c);
    }

    /** "Avvio sicuro" option (on by default). */
    public static boolean option(Context c) {
        return sp(c).getBoolean(K_ON, true);
    }

    public static void setOption(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ON, on).apply();
        new Thread(() -> apply(c, on ? "option on" : "option off"), "mg4-homelock").start();
    }

    /** Uninstall in progress? With the mark active the MG home stays on and ours is not re-confirmed. */
    public static boolean uninstalling(Context c) {
        SharedPreferences s = sp(c);
        long at = s.getLong(K_UNINSTALL, 0);
        if (at <= 0) return false;
        long age = System.currentTimeMillis() - at;
        if (age >= 0 && age < UNINSTALL_MAX_MS) return true;
        s.edit().remove(K_UNINSTALL).apply();
        Diag.put("uninstall", "marker expired after 10 minutes with no outcome: launcher back to normal");
        return false;
    }

    /** "Uninstall in progress" mark, written to disk right away (commit) before touching the home. */
    public static void beginUninstall(Context c) {
        sp(c).edit().putLong(K_UNINSTALL, System.currentTimeMillis()).commit();
    }

    /** Clears the mark (uninstall cancelled or failed). */
    public static void endUninstall(Context c) {
        sp(c).edit().remove(K_UNINSTALL).commit();
    }

    /** MG main screen disabled right now? */
    public static boolean mgDisabled(Context c) {
        try {
            return c.getPackageManager().getComponentEnabledSetting(HomeDefault.MG)
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Correct state for the current settings: MG home disabled if launcher active + option on + no
     * lockout from the safeguard; otherwise re-enabled. Then re-confirms the default home. From a worker thread.
     */
    public static synchronized void apply(Context c, String why) {
        Context app = c.getApplicationContext();
        if (uninstalling(app)) {   // issue #29: never disable the MG home while uninstalling (on-screen confirmation)
            release(app, "uninstall in progress · " + why);
            Diag.put("home.safeStart", "not activated: uninstall in progress (" + why + ")");
            return;
        }
        boolean want = HomeMode.active(app) && option(app) && !CrashGuard.tripped(app);
        if (want) lock(app, why);
        else release(app, why);
        if (HomeMode.active(app)) HomeDefault.ensure(app);
    }

    private static void lock(Context c, String why) {
        if (uninstalling(c)) return;
        if (mgDisabled(c)) {
            Diag.put("home.safeStart", "active (MG home disabled)");
            return;
        }
        try {
            c.getPackageManager().setComponentEnabledSetting(HomeDefault.MG,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            Diag.put("home.safeStart", "activated: MG home disabled (" + why + ")");
        } catch (Throwable e) {
            Diag.put("home.safeStart", "not possible: " + e);
        }
    }

    /** Re-enables the MG main screen (always safe to call). */
    public static synchronized void release(Context c, String why) {
        Context app = c.getApplicationContext();
        if (!mgDisabled(app)) {
            Diag.putQuiet("home.safeStart", "off (" + why + ")");
            return;
        }
        try {
            app.getPackageManager().setComponentEnabledSetting(HomeDefault.MG,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
            Diag.put("home.safeStart", "MG home re-enabled (" + why + ")");
        } catch (Throwable e) {
            Diag.put("home.safeStart", "re-enabling failed: " + e);
        }
    }

    /** Other installed launchers (not ours, not MG, not Android system): they may compete for the home. */
    public static List<String> otherLaunchers(Context c) {
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(c.getPackageName()) || p.startsWith("com.saicmotor") || p.startsWith("com.android")
                        || p.equals("android") || out.contains(p)) continue;
                CharSequence label = ri.loadLabel(pm);
                out.add(label == null ? p : label + " (" + p + ")");
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** true if pkg is another home (MG or another launcher): at power-on it is replaced like the MG home. */
    public static boolean isOtherHome(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName()) || HomeDefault.isProvision(pkg)) return false;   // Android setup wizard: not the MG home
        if (HomeDefault.MG.getPackageName().equals(pkg)) return true;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
            List<ResolveInfo> l = c.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS);
            return !l.isEmpty() && !pkg.startsWith("com.android");
        } catch (Throwable e) {
            return false;
        }
    }

    /** Does the component exist on this car? (if not, nothing to disable) */
    public static boolean mgExists(Context c) {
        try {
            c.getPackageManager().getActivityInfo(HomeDefault.MG, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static ComponentName mg() {
        return HomeDefault.MG;
    }
}
