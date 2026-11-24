package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Default home (ACTION_MAIN + CATEGORY_HOME): ours or the original MG one. replacePreferredActivity
 * requires SET_PREFERRED_APPLICATIONS, granted by the platform signature. A firmware or system app update
 * can reset it: that is why it is reconfirmed at every launcher start.
 */
public final class HomeDefault {
    public static final ComponentName MG = new ComponentName(com.mg4.launcher.apps.MgApps.HOME_PKG, com.mg4.launcher.apps.MgApps.HOME_CLS);

    private HomeDefault() {}

    /** Android setup wizard: on some cars it stays as the home, but it is not the MG home. */
    public static final String PROVISION = "com.android.provision";

    public static boolean isProvision(String pkg) {
        return PROVISION.equals(pkg);
    }

    /** Setup wizard state, for the log: "user_setup_complete=0, device_provisioned=1". */
    public static String provisionState(Context c) {
        String usc, dp;
        try {
            usc = String.valueOf(android.provider.Settings.Secure.getInt(c.getContentResolver(), "user_setup_complete", -1));
        } catch (Throwable e) {
            usc = "?";
        }
        try {
            dp = String.valueOf(android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.DEVICE_PROVISIONED, -1));
        } catch (Throwable e) {
            dp = "?";
        }
        return "user_setup_complete=" + usc + ", device_provisioned=" + dp;
    }

    /** If the resolved home is the Android setup wizard, writes it to the log (once per value). */
    private static String noteProvision(Context c, String pkg) {
        if (!isProvision(pkg)) return "";
        String st = provisionState(c);
        Diag.put("startup.homeFirstSetup", "home resolves to " + PROVISION + " (Android first setup, not the MG home) · " + st);
        return " (Android first setup, not the MG home · " + st + ")";
    }

    public static ComponentName ours(Context c) {
        return new ComponentName(c.getPackageName(), "com.mg4.launcher.MainActivity");
    }

    /** Reconfirms our home (only if active, i.e. switch on and no workshop mode). */
    public static void ensure(Context c) {
        if (!HomeMode.active(c)) {
            Diag.put("startup.defaultHome", "not reconfirmed (" + HomeMode.reason(c) + ")");
            return;
        }
        Diag.put("startup.defaultHome", set(c, ours(c)));
    }

    /** Makes the original MG home the default. */
    public static void setMg(Context c) {
        Diag.put("startup.defaultHome", "MG: " + set(c, MG));
    }

    /** Sets the default home; returns "ok" (already so), "restored" or "error: …". */
    private static String set(Context c, ComponentName target) {
        try {
            PackageManager pm = c.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo cur = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            String curPkg = cur != null && cur.activityInfo != null ? cur.activityInfo.packageName : "";
            if (target.getPackageName().equals(curPkg)) return "ok";
            noteProvision(c, curPkg);
            List<ResolveInfo> all = pm.queryIntentActivities(home, 0);
            List<ComponentName> set = new ArrayList<>();
            int bestMatch = 0;
            for (ResolveInfo ri : all) {
                set.add(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                bestMatch = Math.max(bestMatch, ri.match);
            }
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            int match = bestMatch != 0 ? bestMatch : IntentFilter.MATCH_CATEGORY_EMPTY;
            // hidden API in Android 9: called via reflection
            PackageManager.class.getMethod("replacePreferredActivity", IntentFilter.class, int.class, ComponentName[].class, ComponentName.class)
                    .invoke(pm, f, match, set.toArray(new ComponentName[0]), target);
            ResolveInfo now = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            boolean ok = now != null && now.activityInfo != null && target.getPackageName().equals(now.activityInfo.packageName);
            String nowPkg = now != null && now.activityInfo != null ? now.activityInfo.packageName : "none";
            return ok ? "restored (was " + (curPkg.isEmpty() ? "none" : curPkg) + noteProvision(c, curPkg) + ")"
                    : isProvision(nowPkg) ? "not changed: in front is " + nowPkg + noteProvision(c, nowPkg)
                    : "error: still " + nowPkg;
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            return "error: " + t;
        }
    }
}
