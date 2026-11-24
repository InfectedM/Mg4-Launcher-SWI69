package com.mg4.launcher.svc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Use as default home" and "Workshop mode".
 * <ul>
 *   <li>Switch off (permanent): default home = MG home, no start at power-on, no
 *       home reconfirmation. The launcher stays usable as a normal app.</li>
 *   <li>Workshop (1/3/7 days): saves the switch state, turns it off, suspends updates and popups.
 *       Ends at expiry (checked at power-on and periodically), if the user cancels it by opening the
 *       launcher, or if the switch is turned back on; at the end the switch returns to how it was.</li>
 * </ul>
 */
public final class HomeMode {
    private static final String K_ENABLED = "defaultHome", K_UNTIL = "workshopUntil", K_PREV = "workshopHomeBefore";

    private HomeMode() {}

    private static SharedPreferences sp(Context c) {
        return com.mg4.launcher.ui.Prefs.sp(c);
    }

    /** Switch value (off also during workshop mode). */
    public static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, true) && !workshop(c);
    }

    /** Start and home-reconfirmation logic active? */
    public static boolean active(Context c) {
        return enabled(c) && !HomeLock.uninstalling(c);   // 1.0.4: during uninstall the MG home is in charge
    }

    public static String reason(Context c) {
        if (HomeLock.uninstalling(c)) return "uninstall in progress";
        if (workshop(c)) return "workshop";
        return sp(c).getBoolean(K_ENABLED, true) ? "active" : "switch off";
    }

    /** Workshop mode active (expiry ends it by itself). */
    public static boolean workshop(Context c) {
        long until = sp(c).getLong(K_UNTIL, 0);
        if (until <= 0) return false;
        if (System.currentTimeMillis() >= until) {
            end(c, "expiry");
            return false;
        }
        return true;
    }

    public static long workshopUntil(Context c) {
        return sp(c).getLong(K_UNTIL, 0);
    }

    public static String workshopDate(Context c) {
        return new SimpleDateFormat("dd/MM", Locale.ITALY).format(new Date(workshopUntil(c)));
    }

    /** Expiry check (at power-on and periodically). */
    public static void checkExpiry(Context c) {
        workshop(c);
    }

    /** "Use as default home" switch. Turning it back on during workshop mode cancels it. */
    public static void setEnabled(Context c, boolean on) {
        if (on && sp(c).getLong(K_UNTIL, 0) > 0) {
            sp(c).edit().putBoolean(K_PREV, true).apply();
            end(c, "switch");
            return;
        }
        sp(c).edit().putBoolean(K_ENABLED, on).apply();
        apply(c, on);
        if (on) openOurs(c);
        else openMg(c);
    }

    /** Enables workshop mode for a few days. */
    public static void startWorkshop(Context c, int days) {
        boolean prev = sp(c).getBoolean(K_ENABLED, true);
        long until = System.currentTimeMillis() + days * 24L * 60 * 60 * 1000;
        sp(c).edit().putBoolean(K_PREV, prev).putBoolean(K_ENABLED, false).putLong(K_UNTIL, until).apply();
        Diag.put("workshop", "active until " + new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(new Date(until)));
        apply(c, false);
        openMg(c);
    }

    /** End of workshop mode: switch as it was, updates active again, launcher reopened if needed. */
    public static void end(Context c, String why) {
        SharedPreferences s = sp(c);
        if (s.getLong(K_UNTIL, 0) <= 0) return;
        boolean prev = s.getBoolean(K_PREV, true);
        s.edit().remove(K_UNTIL).remove(K_PREV).putBoolean(K_ENABLED, prev).apply();
        Diag.put("workshop", "inactive (reason: " + why + ")");
        apply(c, prev);
        if (prev) openOurs(c);
    }

    /** Effect of the switch on the default home. */
    private static void apply(Context c, boolean on) {
        new Thread(() -> {
            if (on) {
                HomeLock.apply(c, "switch on");
            } else {
                HomeLock.release(c, workshop(c) ? "workshop" : "switch off");
                HomeDefault.setMg(c);
            }
            diag(c);
        }, "mg4-home").start();
    }

    public static void diag(Context c) {
        String v = active(c) ? "ours" : "MG (" + (workshop(c) ? "workshop" : "switch off") + ")";
        Diag.put("home.setting", v);
    }

    private static void openMg(Context c) {
        HomeLock.release(c, "MG home opened");
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(HomeDefault.MG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) {
            Diag.error("home MG", e.toString());
        }
    }

    /** 0.4.2: launcher opened by the launcher itself (start/replacement of the MG home), not by the user's Home key. */
    public static final String EXTRA_AUTO = "mg4_aperto_da_launcher";

    public static void openOurs(Context c) {
        RetroReturn.noteBroughtBy("start/home replacement (HomeMode.openOurs)");
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(HomeDefault.ours(c))
                    .putExtra(EXTRA_AUTO, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Throwable e) {
            Diag.error("startup launcher", e.toString());
        }
    }
}
