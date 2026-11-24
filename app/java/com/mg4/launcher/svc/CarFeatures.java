package com.mg4.launcher.svc;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;

/**
 * Car configuration needed by the home layout: driving side and the lights indicator drawn by the system
 * in the top left. Several sources are tried, because not all of them exist on every firmware:
 * <ul>
 *   <li>system setting {@code wheel_position} (1 left, 2 right);</li>
 *   <li>CarService property INFO_DRIVER_SEAT (driver's seat: 1 left, 4 right);</li>
 *   <li>configuration provider of the original apps ({@code com.saicmotor.caradapter.featureprovider}).</li>
 * </ul>
 * All values read end up in the diagnostics ({@code auto.config.*}).
 */
public final class CarFeatures {
    private static final String FEATURE_URI = "content://com.saicmotor.caradapter.featureprovider/feature/key/";
    /** VehicleProperty.INFO_DRIVER_SEAT (Android 9). */
    private static final int INFO_DRIVER_SEAT = 356516106;
    private static final int SEAT_ROW_1_LEFT = 1, SEAT_ROW_1_RIGHT = 4;

    private static final Map<String, String> cache = new HashMap<>();
    private static Boolean rhd, indicator;

    private CarFeatures() {}

    /** Right-hand drive according to the car (false if it cannot be determined). */
    public static synchronized boolean isRightHandDrive(Context c) {
        if (rhd != null) return rhd;
        int wheel = -1;
        try {
            wheel = Settings.System.getInt(c.getContentResolver(), "wheel_position", -1);
        } catch (Throwable ignored) {
        }
        int seat = Integer.MIN_VALUE;
        boolean carReady = false;
        try {
            CarProps p = VehicleClient.carProps();
            carReady = p != null && p.ready();
            if (carReady) seat = p.getIntQuiet(INFO_DRIVER_SEAT);
        } catch (Throwable ignored) {
        }
        boolean r = wheel == 2 || seat == SEAT_ROW_1_RIGHT;
        boolean known = wheel == 1 || wheel == 2 || seat == SEAT_ROW_1_LEFT || seat == SEAT_ROW_1_RIGHT;
        Diag.put("car.config.driveSide", (r ? "right" : known ? "left" : "unknown (left)")
                + " · wheel_position " + wheel + " · driver seat " + (seat == Integer.MIN_VALUE ? "?" : String.valueOf(seat)));
        // if the CarService was not ready yet, retry on the next home start
        if (known || carReady) rhd = r;
        return r;
    }

    private static long lastTry;

    /**
     * Driving side as soon as it becomes known (to rebuild the home if the CarService was not ready at startup);
     * null until known. Retries at most every 5 seconds.
     */
    public static synchronized Boolean knownRhd(Context c) {
        if (rhd != null) return rhd;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTry < 5000) return null;
        lastTry = now;
        isRightHandDrive(c);
        return rhd;
    }

    /** Does the system bar always draw the lights indicator in the top left? */
    public static synchronized boolean hasSystemLightIndicator(Context c) {
        if (indicator != null) return indicator;
        String rear = feature(c, "systemui_state_rearfog__");
        String rearNew = feature(c, "systemui_state_rearfognew__");
        String front = feature(c, "systemui_state_frontfog__");
        String frontNew = feature(c, "systemui_state_frontfognew__");
        String hbRear = feature(c, "hiboard_RearFog");
        String hbFront = feature(c, "hiboard_Frontfog");
        boolean on = "1".equals(rear) || "1".equals(rearNew);
        Diag.put("car.config.lightIndicator", (on ? "yes" : "no") + " · rearfog " + rear + "/" + rearNew + " · frontfog " + front + "/"
                + frontNew + " · hiboard " + hbRear + "/" + hbFront);
        indicator = on;
        return on;
    }

    // ---- 0.4.2 (point 2): SystemUI fog light button ------------------------------------------------------------
    // Verified in SystemUI R45 (StatusBarView + CarLampController.addBallView): after the first draw of the status bar
    // SystemUI hides the iv_car_lamp icon and puts a separate window (FloatBallView) of type 2021
    // (TYPE_BOOT_PROGRESS) in its place, 56 px wide (+61 px with front fog lights), at x = 20 px from the left edge.
    //  a) status bar / immersive mode flags only hide the bar (type 2000), not that window;
    //     another app cannot hide or remove SystemUI windows: it cannot be done cleanly.
    //  b) a type 2017/2010 window (like MG4 Control) on Android 9 sits BELOW type 2021 (layer 19/26 vs 32):
    //     it does not cover it. Covering it with another 2021 window depends on the order of addition (SystemUI recreates
    //     it and comes back on top) and would stay above other apps if the launcher closes badly: not safe.
    //  c) applied solution: the home leaves the corner free, measured from the real window when possible.

    private static final String PREF_INSET = "spiaLarghezza";

    /** Width (dp) to leave free in the top left: saved measurement or estimate from the configuration. */
    public static int systemLightInset(Context c) {
        int saved = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getInt(PREF_INSET, -1);
        if (saved > 0) return saved;
        boolean front = "1".equals(feature(c, "systemui_state_frontfog__"));
        return front ? 150 : 88;   // 20 margin + 56 icon (+ 5 + 56 front) + breathing room
    }

    private static long probedAt;

    /**
     * Measures the indicator window (dumpsys window, DUMP permission from the platform signature) and writes luci.pulsanteSistema.
     * true if the width to leave free changed (the home must be rebuilt). Must be called from a background thread.
     */
    public static synchronized boolean probeSystemLight(Context c) {
        long t = android.os.SystemClock.elapsedRealtime();
        if (probedAt > 0 && t - probedAt < 120_000L) return false;
        probedAt = t;
        String dump = dumpWindows(c);
        StringBuilder sb = new StringBuilder("solution: corner left free (the SystemUI window cannot be safely hidden or covered)");
        int need = -1;
        if (dump == null) {
            sb.append(" · window not measurable (dump unavailable): estimate ").append(systemLightInset(c)).append(" px");
        } else {
            boolean found = false;
            String[] blocks = dump.split("\n  Window #");
            for (String b : blocks) {
                if (!b.contains("com.android.systemui") || !(b.contains("ty=BOOT_PROGRESS") || b.contains("ty=2021"))) continue;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("mFrame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matcher(b);
                if (!m.find()) m = java.util.regex.Pattern.compile("frame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matcher(b);
                boolean vis = b.contains("mViewVisibility=0x0") && !b.contains("mHasSurface=false");
                if (m.find(0)) {
                    int l = Integer.parseInt(m.group(1)), tp = Integer.parseInt(m.group(2)), r = Integer.parseInt(m.group(3)), bt = Integer.parseInt(m.group(4));
                    found = true;
                    sb.append(" · indicator window (SystemUI type 2021) ").append(vis ? "VISIBLE" : "not visible").append(" [")
                            .append(l).append(',').append(tp).append("][").append(r).append(',').append(bt).append(']');
                    if (vis && l < 400 && tp < 200) need = Math.max(need, r + 12);
                }
            }
            if (!found) sb.append(" · no SystemUI indicator window");
            sb.append(dump.contains("StatusBar") ? " · status bar present" : "");
        }
        boolean changed = false;
        if (need > 0) {
            int dp = Math.round(need / Math.max(0.5f, c.getResources().getDisplayMetrics().density));
            dp = Math.max(64, Math.min(260, dp));
            android.content.SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
            if (sp.getInt(PREF_INSET, -1) != dp) {
                sp.edit().putInt(PREF_INSET, dp).apply();
                changed = true;
            }
        }
        sb.append(" · free space ").append(systemLightInset(c)).append(" dp");
        Diag.put("lights.systemButton", sb.toString());
        return changed;
    }

    /** dumpsys window windows, read with the window service binder (read only). null if not available. */
    private static String dumpWindows(Context c) {
        java.io.File f = new java.io.File(c.getCacheDir(), "finestre.txt");
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            android.os.IBinder b = (android.os.IBinder) sm.getMethod("getService", String.class).invoke(null, "window");
            if (b == null) return null;
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                b.dump(os.getFD(), new String[]{"windows"});
            }
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(all, "UTF-8");
            if (s.contains("Permission Denial")) return null;
            return s;
        } catch (Throwable e) {
            return null;
        } finally {
            f.delete();
        }
    }

    private static Boolean providerExists;

    /**
     * Does the SAIC configuration provider exist? Not on the SWI68: it is checked only once with
     * resolveContentProvider and then no longer queried (avoids errors at every start).
     */
    public static synchronized boolean hasFeatureProvider(Context c) {
        if (providerExists == null) {
            boolean ok = false;
            try {
                ok = c.getPackageManager().resolveContentProvider("com.saicmotor.caradapter.featureprovider", 0) != null;
            } catch (Throwable ignored) {
            }
            providerExists = ok;
            Diag.put("car.config.vendor", ok ? "present" : "absent (not queried)");
        }
        return providerExists;
    }

    /** Value of a configuration provider key, "-" if it does not exist. */
    private static String feature(Context c, String key) {
        if (cache.containsKey(key)) return cache.get(key);
        if (!hasFeatureProvider(c)) return "-";
        String v = "-";
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(Uri.parse(FEATURE_URI + key), null, null, null, null);
            if (cur != null && cur.moveToFirst() && cur.getColumnCount() > 0) {
                String s = cur.getString(0);
                if (s != null) v = s.trim();
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        cache.put(key, v);
        return v;
    }
}
