package com.mg4.launcher.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.mg4.launcher.svc.Diag;

/**
 * 1.0.0: what the bar's 360 button opens. Choices: original 360 (default), MG4 360 (only if installed),
 * another installed app, hidden. If the car has no 360 camera the default is "hidden".
 */
public final class Cam360 {
    public static final String ORIG_PKG = "com.saicmotor.evs";
    public static final String ORIG_CLS = null;

    public static final int ORIGINAL = 0, MG4 = 1, APP = 2, HIDDEN = 3;

    /** What to do: kind, package and activity to open (cls null = the package's main activity). */
    public static final class Target {
        public final int kind;
        public final String pkg, cls, label;

        Target(int kind, String pkg, String cls, String label) {
            this.kind = kind;
            this.pkg = pkg;
            this.cls = cls;
            this.label = label;
        }
    }

    private static String lastDiag = "";

    private Cam360() {}

    /** True if the original 360 camera app is present (the car has the camera). */
    public static boolean hasOriginal(Context c) {
        return installed(c, ORIG_PKG);
    }

    /** MG4 360 package if installed (com.mg4.* with "360" in the name or label), otherwise null. */
    public static String mg4Package(Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.startsWith("com.mg4.") || pkg.equals(c.getPackageName())) continue;
                String label = String.valueOf(ri.loadLabel(pm));
                if (pkg.contains("360") || label.contains("360")) return pkg;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean launchable(Context c, String pkg) {
        try {
            return c.getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Effective choice; if the chosen app is gone, fall back to the default (and record it in the diagnostics). */
    public static Target resolve(Context c, Prefs p) {
        String v = p.cam360();
        boolean orig = hasOriginal(c);
        Target t;
        String note = "";
        if (Prefs.CAM_HIDDEN.equals(v)) {
            t = new Target(HIDDEN, null, null, "");
        } else if (Prefs.CAM_MG4.equals(v)) {
            String pkg = mg4Package(c);
            if (pkg != null) t = new Target(MG4, pkg, null, label(c, pkg));
            else {
                t = def(orig);
                note = " (MG4 360 not installed: default)";
            }
        } else if (v.startsWith(Prefs.CAM_APP)) {
            String pkg = v.substring(Prefs.CAM_APP.length());
            // 0.4.3: if the chosen app is gone (or cannot be opened) the default button comes back, without crashing
            if (!pkg.isEmpty() && installed(c, pkg) && launchable(c, pkg)) t = new Target(APP, pkg, null, label(c, pkg));
            else {
                t = def(orig);
                note = " (" + (pkg.isEmpty() ? "empty app" : pkg) + " not installed or cannot be opened: default)";
            }
        } else if (Prefs.CAM_ORIGINAL.equals(v)) {
            t = orig ? new Target(ORIGINAL, ORIG_PKG, ORIG_CLS, "") : def(false);
            if (!orig) note = " (original 360 missing: hidden)";
        } else {
            t = def(orig);
        }
        String kind = t.kind == ORIGINAL ? "original 360" : t.kind == MG4 ? "MG4 360 " + t.pkg
                : t.kind == APP ? "app " + t.pkg : "hidden";
        String d = "choice " + (v.isEmpty() ? "default" : v) + " → " + kind + note + " · original camera "
                + (orig ? "present" : "missing") + " · MG4 360 " + (mg4Package(c) != null ? "installed" : "missing");
        if (!d.equals(lastDiag)) {
            lastDiag = d;
            Diag.put("bar.button360", d);
        }
        return t;
    }

    private static Target def(boolean orig) {
        return orig ? new Target(ORIGINAL, ORIG_PKG, ORIG_CLS, "") : new Target(HIDDEN, null, null, "");
    }
}
