package com.mg4.launcher.apps;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import com.mg4.launcher.R;
import com.mg4.launcher.svc.Diag;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * App list: the car's apps (known SAIC components, with drawn icons) and those
 * installed by the user (read from the system, refreshed on every install/uninstall).
 */
public final class AppCatalog {

    public static final class App {
        public final String label;
        public final ComponentName component;   // null = use launchIntent
        public final Intent launchIntent;
        public final int iconRes;               // 0 = icon redrawn from the original
        public final boolean highlight;
        public final String packageName;

        App(String label, ComponentName cn, Intent li, int iconRes, boolean highlight, String pkg) {
            this.label = label;
            this.component = cn;
            this.launchIntent = li;
            this.iconRes = iconRes;
            this.highlight = highlight;
            this.packageName = pkg;
        }

        public Intent intent() {
            Intent i;
            Intent mg4 = component != null ? climateOverride(component.getPackageName()) : null;
            if (mg4 != null) {
                i = mg4;
            } else if (component != null) {
                i = new Intent(Intent.ACTION_MAIN).setComponent(component);
            } else {
                i = new Intent(launchIntent);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return i;
        }
    }

    /** SWI69 car apps (EH32 Color Radio), in the MG launcher's order; missing ones are not shown. */
    private static final String[][] CAR_APPS = {
            {"@app_vehicle", MgApps.VEHICLE_PKG, MgApps.VEHICLE_CLS, "car"},
            {"@climate", MgApps.HVAC_PKG, MgApps.HVAC_CLS, "fan"},
            {"@app_energy", MgApps.ENERGY_PKG, MgApps.ENERGY_CLS, "bolt"},
            {"@app_phone", MgApps.PHONE_PKG, MgApps.PHONE_CLS, "phone"},
            {"@src_radio", MgApps.RADIO_PKG, MgApps.RADIO_CLS, "radio"},
            {"@app_music", MgApps.MEDIA_PKG, MgApps.MEDIA_CLS, "music"},
            {"@app_files", MgApps.FILES_PKG, MgApps.FILES_CLS, "video"},
            {"@app_settings", MgApps.SETTINGS_PKG, MgApps.SETTINGS_CLS, "gear"},
            {"@app_sos", MgApps.SOS_PKG, MgApps.SOS_CLS, "sos"},
            // Android shortcuts (only if the activity really exists on the car)
            {"@app_android_settings", "@android_settings", "", "android_settings"},
            {"@app_android_files", "@android_files", "", "folder"},
    };

    /** Possible file managers, in the order they are searched. */
    private static final String[] FILE_PACKAGES = {"com.android.documentsui", "com.mediatek.filemanager", "com.android.fileexplorer"};

    /** Possible navigators (from the original: Telenav, iGo, SAIC). */
    private static final String[] NAV_PACKAGES = {"com.telenav.app.arp", "com.nng.igo.primong", "com.saicmotor.navigation"};

    /** System packages not to show among the installed apps. */
    private static final String[] HIDDEN_PREFIX = {"com.saicmotor.", "com.saic.", "com.allgo.", "com.android.", "android",
            "com.yfve.", "com.mediatek.", "com.mtk.", "com.google.android.car", "com.telenav.", "com.nng."};

    private final Context ctx;
    private final PackageManager pm;

    public AppCatalog(Context c) {
        ctx = c.getApplicationContext();
        pm = ctx.getPackageManager();
        spm = pm;
    }

    public static int iconFor(Context c, String name) {
        return c.getResources().getIdentifier("ic_" + name, "drawable", c.getPackageName());
    }

    /** Label: "@key" = translated text (res/values-xx/strings.xml), otherwise a fixed name (EV Route, Spotify...). */
    private String label(String l) {
        if (!l.startsWith("@")) return l;
        int id = ctx.getResources().getIdentifier(l.substring(1), "string", ctx.getPackageName());
        return id == 0 ? l.substring(1) : com.mg4.launcher.L.s(id);
    }

    /** Language changed: labels are re-read on the next call to carApps(). */
    public void refreshLabels() {
    }

    public List<App> carApps(String navPackageHint) {
        List<App> out = new ArrayList<>();
        for (String[] row : CAR_APPS) {
            String[] a = {label(row[0]), row[1], row[2], row[3]};
            int icon = iconFor(ctx, a[3]);
            if ("@nav".equals(a[1])) {
                Intent nav = navIntent(navPackageHint);
                if (nav != null) {
                    String pkg = nav.getComponent() != null ? nav.getComponent().getPackageName() : String.valueOf(nav.getPackage());
                    out.add(new App(a[0], null, nav, icon, false, pkg));
                }
                continue;
            }
            if ("@android_settings".equals(a[1]) || "@android_files".equals(a[1])) {
                Intent si = "@android_settings".equals(a[1]) ? androidSettingsIntent() : androidFilesIntent();
                if (si != null) out.add(new App(a[0], null, si, icon, false, targetPackage(si)));
                continue;
            }
            // 0.4.3: the ComponentName is created only with a valid package and class (before: crash "class name is null")
            if (empty(a[1])) continue;
            ComponentName cn = empty(a[2]) ? null : new ComponentName(a[1], a[2]);
            if (cn != null && exists(cn)) {
                out.add(new App(a[0], cn, null, icon, false, a[1]));
            } else {
                Intent li = pm.getLaunchIntentForPackage(a[1]);
                if (li != null) {
                    out.add(new App(a[0], null, li, icon, false, a[1]));
                }
            }
        }
        return out;
    }

    private String sysDiag;

    /** Android Settings (com.android.settings), if the activity exists. */
    private Intent androidSettingsIntent() {
        Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS).setPackage("com.android.settings");
        if (pm.resolveActivity(i, 0) == null) i = new Intent(android.provider.Settings.ACTION_SETTINGS);
        boolean ok = pm.resolveActivity(i, 0) != null;
        sysDiag = "settings " + (ok ? "ok" : "missing");
        return ok ? i : null;
    }

    /** The car's file manager: DocumentsUI, MediaTek, File Explorer, or whatever opens documents. */
    private Intent androidFilesIntent() {
        Intent found = null;
        for (String p : FILE_PACKAGES) {
            Intent li = pm.getLaunchIntentForPackage(p);
            if (li != null) {
                found = li;
                break;
            }
            if ("com.android.documentsui".equals(p)) {
                ComponentName fa = new ComponentName(p, "com.android.documentsui.files.FilesActivity");
                if (exists(fa)) {
                    found = new Intent(Intent.ACTION_MAIN).setComponent(fa);
                    break;
                }
            }
        }
        if (found == null) {
            Intent od = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            Intent gc = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            if (pm.resolveActivity(od, 0) != null) found = od;
            else if (pm.resolveActivity(gc, 0) != null) found = gc;
        }
        String head = sysDiag == null ? "settings ?" : sysDiag;
        Diag.put("app.system", head + ", file " + (found != null ? targetPackage(found) : "missing"));
        return found;
    }

    private String targetPackage(Intent i) {
        if (i.getComponent() != null) return i.getComponent().getPackageName();
        if (i.getPackage() != null) return i.getPackage();
        android.content.pm.ResolveInfo ri = pm.resolveActivity(i, 0);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "?";
    }

    /** Third-party navigators installed on the car (Waze, Google Maps, OsmAnd, ...). */
    public List<App> otherNavApps(String mgNavPackage) {
        Set<String> pkgs = new java.util.LinkedHashSet<>();
        Collections.addAll(pkgs, "com.waze", "com.google.android.apps.maps", "com.google.android.apps.maps.auto",
                "net.osmand", "net.osmand.plus", "com.sygic.aura", "com.here.app.maps", "com.tomtom.gplay.navapp",
                "com.mapfactor.navigator", "cz.seznam.mapy");
        try {
            Intent geo = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=roma"));
            for (ResolveInfo ri : pm.queryIntentActivities(geo, 0)) pkgs.add(ri.activityInfo.packageName);
        } catch (Exception ignored) {
        }
        List<App> out = new ArrayList<>();
        for (String p : pkgs) {
            if (p.equals(mgNavPackage) || p.startsWith("com.saicmotor") || p.startsWith("com.telenav")
                    || p.startsWith("com.nng") || p.startsWith("com.tomtom.navigation")) continue;
            Intent li = pm.getLaunchIntentForPackage(p);
            if (li == null) continue;
            String label = p;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
            } catch (Exception ignored) {
            }
            out.add(new App(label, null, li, 0, false, p));
        }
        return out;
    }

    /**
     * 1.0.4 (issue #27): installed apps that handle navigation: the known navigators (ABRP included), plus all those that
     * open "geo:" or "google.navigation:". MG navigation is excluded (it is the default choice).
     */
    public List<App> navCandidateApps(String mgNavPackage) {
        Set<String> seen = new java.util.HashSet<>();
        List<App> out = new ArrayList<>();
        for (App a : otherNavApps(mgNavPackage)) if (seen.add(a.packageName)) out.add(a);
        Set<String> extra = new java.util.LinkedHashSet<>();
        Collections.addAll(extra, "com.iternio.abrpapp", "com.google.android.apps.maps");
        for (String uri : new String[]{"google.navigation:q=roma", "geo:0,0?q=roma"}) {
            try {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)), 0)) {
                    extra.add(ri.activityInfo.packageName);
                }
            } catch (Exception ignored) {
            }
        }
        for (String p : extra) {
            if (seen.contains(p) || p.equals(mgNavPackage) || p.startsWith("com.saicmotor") || p.equals(ctx.getPackageName())) continue;
            Intent li = pm.getLaunchIntentForPackage(p);
            if (li == null) continue;
            String label = p;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
            } catch (Exception ignored) {
            }
            seen.add(p);
            out.add(new App(label, null, li, 0, false, p));
        }
        return out;
    }

    public Intent navIntent(String hint) {
        List<String> candidates = new ArrayList<>();
        if (hint != null && !hint.isEmpty()) candidates.add(hint);
        Collections.addAll(candidates, NAV_PACKAGES);
        for (String p : candidates) {
            Intent i = pm.getLaunchIntentForPackage(p);
            if (i != null) return i;
        }
        return null;
    }

    private boolean exists(ComponentName cn) {
        try {
            ActivityInfo ai = pm.getActivityInfo(cn, 0);
            return ai != null && ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** User-installed apps (DiPlay, Task Manager, ...), in alphabetical order. */
    public List<App> userApps() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden(pkg)) continue;
            String key = pkg + "/" + ri.activityInfo.name;
            if (!seen.add(key)) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            boolean mine = pkg.toLowerCase(Locale.ROOT).contains("diplay") || label.toLowerCase(Locale.ROOT).contains("diplay")
                    || label.toLowerCase(Locale.ROOT).contains("task manager") || pkg.toLowerCase(Locale.ROOT).contains("taskmanager");
            out.add(new App(label, new ComponentName(pkg, ri.activityInfo.name), null, 0, mine, pkg));
        }
        final Collator col = Collator.getInstance(Locale.ITALY);
        Collections.sort(out, (x, y) -> {
            return col.compare(x.label, y.label);
        });
        Diag.put("app.installed", out.size());
        return out;
    }

    private static boolean hidden(String pkg) {
        for (String p : HIDDEN_PREFIX) {
            if (pkg.startsWith(p)) return true;
        }
        return false;
    }

    public Drawable originalIcon(App a) {
        try {
            if (a.component != null) return pm.getActivityIcon(a.component);
            return pm.getApplicationIcon(a.packageName);
        } catch (Exception e) {
            return ctx.getDrawable(R.drawable.ic_app);
        }
    }

    public App findByPackageHint(String... needles) {
        for (App a : userApps()) {
            String p = a.packageName.toLowerCase(Locale.ROOT), l = a.label.toLowerCase(Locale.ROOT);
            for (String n : needles) {
                if (p.contains(n) || l.contains(n)) return a;
            }
        }
        return null;
    }

    public boolean launch(App a) {
        try {
            ctx.startActivity(a.intent());
            return true;
        } catch (Exception e) {
            Diag.error("startup " + a.label, e.toString());
            return false;
        }
    }

    private final java.util.Set<String> missingLogged = new java.util.HashSet<>();

    /**
     * Intent to open an original app: the exact activity if it exists, otherwise the package's main activity
     * (on some SWI68 firmwares HvacActivity and ChargeManagementActivity are missing). Null if that is missing too:
     * written only once to the diagnostics.
     */
    private static volatile PackageManager spm;

    /**
     * MG4 Clima installed: the climate opens directly there (without briefly showing the original app).
     * MG4 Clima forwards to the original if the replacement is turned off.
     */
    static Intent climateOverride(String pkg) {
        if (!MgApps.HVAC_PKG.equals(pkg) || spm == null) return null;
        Intent i = new Intent("com.mg4.clima.APRI").setPackage("com.mg4.clima").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            return spm.resolveActivity(i, 0) != null ? i : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public Intent resolveLaunch(String pkg, String cls) {
        spm = pm;
        // 0.4.3: without a package there is nothing to open (button disabled, no crash)
        if (empty(pkg)) return null;
        Intent mg4 = climateOverride(pkg);
        if (mg4 != null) return mg4;
        // 0.4.3: the ComponentName is created only if package and class are present (crash "class name is null" seen on the SWI133)
        boolean hasCls = !empty(cls);
        try {
            Intent exact = hasCls ? new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls)) : null;
            if (exact != null && pm.resolveActivity(exact, 0) != null) return exact;
        } catch (Exception ignored) {
        }
        Intent li = null;
        try {
            li = pm.getLaunchIntentForPackage(pkg);
        } catch (Exception ignored) {
        }
        String key = pkg + "/" + cls;
        if ((hasCls || li == null) && missingLogged.add(key)) {
            Diag.put("app.missing." + pkg.substring(pkg.lastIndexOf('.') + 1),
                    (hasCls ? cls.substring(cls.lastIndexOf('.') + 1) + " missing · " : "") + (li != null ? "opens the main app" : "app not installed: button disabled"));
        }
        return li;
    }

    public boolean canLaunch(String pkg, String cls) {
        try {
            return resolveLaunch(pkg, cls) != null;
        } catch (Exception e) {
            Diag.error("app.verify " + pkg, e.toString());
            return false;
        }
    }

    public boolean launchComponent(String pkg, String cls) {
        try {
            Intent i = resolveLaunch(pkg, cls);
            if (i == null) return false;
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            Diag.error("startup " + pkg, e.toString());
            return false;
        }
    }
}
