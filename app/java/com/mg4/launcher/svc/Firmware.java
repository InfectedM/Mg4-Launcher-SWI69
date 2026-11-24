package com.mg4.launcher.svc;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Detects the car firmware. This launcher version is for the SWI69 (Color Radio): on the SWI68 the services
 * and the stock apps are different (the com.saicmotor.hmi.* packages exist and the configuration provider
 * com.saicmotor.caradapter.featureprovider is missing), so there the launcher must not attempt any connection.
 */
public final class Firmware {
    public static final String SWI68_URL = "https://github.com/ernestomicco2026-cell/Mg4-Launcher-SWI68";

    /** Stock SWI68 apps: if even one of them is present, it is a SWI68. */
    private static final String[] SWI68_APPS = {
            "com.saicmotor.hmi.launcher", "com.saicmotor.hmi.systemsettings", "com.saicmotor.hmi.radio",
            "com.saicmotor.hmi.hvac", "com.saicmotor.hmi.btcall", "com.saicmotor.hmi.aroundview",
            "com.saicmotor.hmi.vehiclesettings"};

    /** Result: SWI69 (correct), SWI68 (wrong version), OTHER (neither, e.g. a phone). */
    public static final int SWI69 = 0, SWI68 = 1, OTHER = 2;
    private static Integer kind;

    private Firmware() {}

    /** True if this version does not fit the device: no connection is attempted. */
    public static boolean wrong(Context c) {
        return kind(c) != SWI69;
    }

    /**
     * Phone or emulator instead of the infotainment: returns the reason, or null if it is a car (even with the
     * firmware of the other version). On these devices the launcher does not send logs.
     */
    public static String notACar(Context c) {
        String fp = lower(android.os.Build.FINGERPRINT), hw = lower(android.os.Build.HARDWARE), prod = lower(android.os.Build.PRODUCT),
                model = lower(android.os.Build.MODEL), brand = lower(android.os.Build.BRAND), man = lower(android.os.Build.MANUFACTURER);
        String dev = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        if (fp.startsWith("generic") || fp.contains("emulator") || fp.contains("sdk_gphone") || hw.contains("goldfish")
                || hw.contains("ranchu") || prod.contains("sdk") || prod.contains("emulator") || model.contains("android sdk built for")
                || model.startsWith("generic") || model.contains("emulator"))
            return "emulator (" + dev + ")";
        if (kind(c) == OTHER) return "firmware not recognized, no MG apps (" + dev + ")";
        for (String b : new String[]{"google", "oneplus", "samsung", "xiaomi", "redmi", "huawei", "oppo", "motorola", "sony", "nothing"})
            if (brand.equals(b) || man.equals(b)) return "phone (" + dev + ")";
        return null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    public static synchronized int kind(Context c) {
        if (kind != null) return kind;
        PackageManager pm = c.getPackageManager();
        boolean provider = CarFeatures.hasFeatureProvider(c);
        int found = 0;
        StringBuilder sb = new StringBuilder();
        for (String p : SWI68_APPS) {
            try {
                pm.getPackageInfo(p, 0);
                found++;
                sb.append(sb.length() == 0 ? "" : ", ").append(p.substring(p.lastIndexOf('.') + 1));
            } catch (Throwable ignored) {
            }
        }
        boolean mgHome;
        try {
            pm.getPackageInfo(com.mg4.launcher.apps.MgApps.HOME_PKG, 0);
            mgHome = true;
        } catch (Throwable e) {
            mgHome = false;
        }
        kind = found > 0 || (!provider && !mgHome) ? (found > 0 ? SWI68 : OTHER) : SWI69;
        Diag.put("firmware", (kind == SWI68 ? "SWI68 (wrong version)" : kind == OTHER ? "not recognized (no MG SWI69 apps)" : "SWI69")
                + " · config provider " + (provider ? "yes" : "no") + " · MG SWI69 home " + (mgHome ? "yes" : "no")
                + (found > 0 ? " · SWI68 apps found: " + sb : ""));
        return kind;
    }
}
