package com.mg4.launcher.climate;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Settings of the built-in climate screen (device-protected storage: readable before unlock, so nothing crashes at
 * start-up) and its diagnostics (state read from the car and events), shown in the climate settings and appended to
 * the launcher log ("Save log").
 */
public final class ClimateStore {
    private static final String TAG = "MG4Climate";

    private ClimateStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().createDeviceProtectedStorageContext()
                .getSharedPreferences("mg4climate", Context.MODE_PRIVATE);
    }

    /** Version of the launcher (the climate screen is part of it), for the results saved per version. */
    static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Software of the infotainment (e.g. "SWI69-…-R45"), for the results saved per software. */
    static String software() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class, String.class).invoke(null, "ro.build.mt2712.version", "");
            if (v != null && !v.isEmpty()) return v;
        } catch (Throwable ignored) {
        }
        return android.os.Build.DISPLAY;
    }

    /** Replace the original climate app when it opens (switch). Also decides where the launcher's climate button goes. */
    public static boolean replace(Context c) {
        return sp(c).getBoolean("replace", true);
    }

    public static void setReplace(Context c, boolean on) {
        sp(c).edit().putBoolean("replace", on).apply();
    }

    /** Close the notification shade the system opens when the climate turns on or off. */
    public static boolean closeShade(Context c) {
        return sp(c).getBoolean("closeShade", true);
    }

    public static void setCloseShade(Context c, boolean on) {
        sp(c).edit().putBoolean("closeShade", on).apply();
    }

    /** Dashboard style: 0 drawn, 1 realistic (photo). */
    public static final int STYLE_DRAWN = 0, STYLE_PHOTO = 1;

    public static int dashStyle(Context c) {
        return sp(c).getInt("dashStyle", STYLE_DRAWN);
    }

    public static void setDashStyle(Context c, int s) {
        sp(c).edit().putInt("dashStyle", s).apply();
    }

    /** Windscreen and rear window buttons in the bottom row (off by default: there are physical buttons). */
    public static boolean defrostTiles(Context c) {
        return sp(c).getBoolean("defrostTiles", false);
    }

    public static void setDefrostTiles(Context c, boolean on) {
        sp(c).edit().putBoolean("defrostTiles", on).apply();
    }

    /** Animated air on the climate screen (on by default). */
    public static boolean animatedAir(Context c) {
        return sp(c).getBoolean("animatedAir", true);
    }

    public static void setAnimatedAir(Context c, boolean on) {
        sp(c).edit().putBoolean("animatedAir", on).apply();
    }

    /** Right-hand drive (the screen is mirrored): the launcher's driving side setting, or as the car says. */
    public static boolean rightHand(Context c) {
        String side = new com.mg4.launcher.ui.Prefs(c).driveSide();
        return com.mg4.launcher.ui.Prefs.DRIVE_RIGHT.equals(side)
                || (com.mg4.launcher.ui.Prefs.DRIVE_AUTO.equals(side) && com.mg4.launcher.svc.CarFeatures.isRightHandDrive(c));
    }

    /**
     * Swipe to close: 0 off, 1 bottom to top, 2 top to bottom, 3 automatic (vertical swipe in both directions and from
     * both edges, the default).
     */
    public static final int SWIPE_OFF = 0, SWIPE_UP = 1, SWIPE_DOWN = 2, SWIPE_AUTO = 3;

    public static int swipeMode(Context c) {
        int m = sp(c).getInt("swipeMode", SWIPE_AUTO);
        return m >= SWIPE_OFF && m <= SWIPE_AUTO ? m : SWIPE_AUTO;
    }

    public static void setSwipeMode(Context c, int m) {
        sp(c).edit().putInt("swipeMode", m).apply();
    }

    static String swipeName(int m) {
        return m == SWIPE_OFF ? "off" : m == SWIPE_UP ? "upwards" : m == SWIPE_DOWN ? "downwards" : "automatic";
    }

    /** Version for which the check was already offered on opening (once per version). */
    public static String askedVerify(Context c) {
        return sp(c).getString("verifyAsked", "");
    }

    public static void setAskedVerify(Context c, String v) {
        sp(c).edit().putString("verifyAsked", v == null ? "" : v).apply();
    }

    /**
     * Failed part of the check (seats, wheel, recirculation), per launcher version and original climate app version:
     * it is not repeated, the saved result is used. "" = never failed.
     */
    public static String partFailed(Context c, String appVer, String origVer, String part) {
        return sp(c).getString("partFailed_" + appVer + "_" + origVer + "_" + part, "");
    }

    public static void setPartFailed(Context c, String appVer, String origVer, String part, String detail) {
        sp(c).edit().putString("partFailed_" + appVer + "_" + origVer + "_" + part, detail).apply();
    }

    /** Check already failed for this pair of versions: it is not offered again by itself on opening. */
    public static boolean verifyFailed(Context c, String appVer, String origVer) {
        return sp(c).getBoolean("verifyFailed_" + appVer + "_" + origVer, false);
    }

    public static void setVerifyFailed(Context c, String appVer, String origVer) {
        sp(c).edit().putBoolean("verifyFailed_" + appVer + "_" + origVer, true).apply();
    }

    /** Last return on closing (climate.return line). */
    private static volatile String returnLine = "no closing since the car was switched on";

    public static void setReturnLine(Context c, String line) {
        returnLine = line;
        event(c, "climate.return = " + line);
    }

    /** Automatic closing after N seconds without touches (0 = never). */
    public static final int[] AUTO_CLOSE = {0, 10, 20, 30, 60};

    public static int autoClose(Context c) {
        return sp(c).getInt("autoClose", 0);
    }

    public static void setAutoClose(Context c, int sec) {
        sp(c).edit().putInt("autoClose", sec).apply();
    }

    /** Parts of the interrupted test / check already done ("" = nothing to resume). */
    public static String testProgress(Context c, String kind) {
        return sp(c).getString("resume_" + kind, "");
    }

    public static void setTestProgress(Context c, String kind, String json) {
        sp(c).edit().putString("resume_" + kind, json == null ? "" : json).apply();
    }

    /**
     * Commands from the steering wheel joystick: 0 off, 1 on (right joystick). On by default, also where the keys
     * cannot be taken exclusively (the commands then go through the broadcast; the settings explain that volume and
     * track can change together with the climate).
     */
    public static final int JOY_OFF = 0, JOY_RIGHT = 1, JOY_LEFT = 2;

    public static int joystick(Context c) {
        return sp(c).getInt("joystick", JOY_RIGHT) == JOY_OFF ? JOY_OFF : JOY_RIGHT;
    }

    public static void setJoystick(Context c, int m) {
        sp(c).edit().putInt("joystick", m == JOY_OFF ? JOY_OFF : JOY_RIGHT).apply();
    }

    /** Factory codes of the right joystick (14 pairings in the logs, SWI68 and SWI69: all the same). */
    static final int[] FACTORY_RIGHT = {297, 298, 299, 300, 301};   // up, down, left, right, ok

    /** Key code paired with a joystick action (up, down, left, right, ok), -1 if not paired. */
    public static int joyCode(Context c, int side, String action) {
        int v = sp(c).getInt("joy" + side + "_" + action, -1);
        if (v >= 0 || side != JOY_RIGHT) return v;
        for (int i = 0; i < Joystick.ACTIONS.length; i++) if (Joystick.ACTIONS[i].equals(action)) return FACTORY_RIGHT[i];
        return -1;
    }

    /** The right joystick pairing is the factory one (nothing paired by hand). */
    public static boolean joyFactory(Context c) {
        for (String a : Joystick.ACTIONS) if (sp(c).contains("joy" + JOY_RIGHT + "_" + a)) return false;
        return true;
    }

    public static void setJoyCode(Context c, int side, String action, int code) {
        sp(c).edit().putInt("joy" + side + "_" + action, code).apply();
    }

    public static void clearJoy(Context c, int side) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String a : Joystick.ACTIONS) e.remove("joy" + side + "_" + a);
        e.apply();
    }

    /**
     * Exclusive steering wheel keys on this car software: -1 not tried yet, 1 works (with the filter on the other
     * apps no longer get the keys), 0 not possible (they still get them). Measured again on every software.
     */
    public static int joyExclusive(Context c) {
        return sp(c).getInt("joyExclusive_" + software(), -1);
    }

    public static void setJoyExclusive(Context c, int v, String detail) {
        sp(c).edit().putInt("joyExclusive_" + software(), v).putString("joyExclusiveDetail", detail).apply();
    }

    public static String joyExclusiveDetail(Context c) {
        return sp(c).getString("joyExclusiveDetail", "");
    }

    /** Labels under the button icons (on by default); off = bigger icons only. */
    public static boolean showLabels(Context c) {
        return sp(c).getBoolean("labels", true);
    }

    public static void setShowLabels(Context c, boolean on) {
        sp(c).edit().putBoolean("labels", on).apply();
    }

    /**
     * Temperature range measured by the calibration for this version of the original climate app:
     * {LO to send, LO as the car reads it back, HI to send, HI as read back, top fan speed (-1 = not measured)}.
     * NaN in LO/HI = no value works (hidden). null = never measured.
     */
    public static float[] tempRange(Context c, String version) {
        String v = sp(c).getString("range_" + version, null);
        if (v == null) return null;
        String[] p = v.split(";");
        if (p.length != 5) return null;
        float[] r = new float[5];
        try {
            for (int i = 0; i < 5; i++) r[i] = "nan".equals(p[i]) ? Float.NaN : Float.parseFloat(p[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return r;
    }

    public static void setTempRange(Context c, String version, float loSend, float loRead, float hiSend, float hiRead, int fanMax) {
        float[] r = {loSend, loRead, hiSend, hiRead, fanMax};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) b.append(';');
            b.append(Float.isNaN(r[i]) ? "nan" : String.valueOf(r[i]));
        }
        sp(c).edit().putString("range_" + version, b.toString()).apply();
    }

    /** Zone to open the climate from the top bar during Android Auto / CarPlay (off by default). */
    public static boolean projectionZone(Context c) {
        return sp(c).getBoolean("projectionZone", false);
    }

    public static void setProjectionZone(Context c, boolean on) {
        sp(c).edit().putBoolean("projectionZone", on).apply();
    }

    /** Position of the floating climate button during Android Auto / CarPlay (x, y in px; -1 = default). */
    public static int[] projectionButton(Context c) {
        return new int[]{sp(c).getInt("projectionButtonX", -1), sp(c).getInt("projectionButtonY", -1)};
    }

    public static void setProjectionButton(Context c, int x, int y) {
        sp(c).edit().putInt("projectionButtonX", x).putInt("projectionButtonY", y).apply();
    }

    /** Position and size of the screen on the display (for the ready window that covers the flash). */
    public static int[] screenRect(Context c) {
        String v = sp(c).getString("screenRect", "");
        String[] p = v.split(",");
        if (p.length != 4) return null;
        try {
            int[] r = new int[4];
            for (int i = 0; i < 4; i++) r[i] = Integer.parseInt(p[i]);
            return r[2] > 100 && r[3] > 100 ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setScreenRect(Context c, int x, int y, int w, int h) {
        String v = x + "," + y + "," + w + "," + h;
        if (!v.equals(sp(c).getString("screenRect", ""))) sp(c).edit().putString("screenRect", v).apply();
    }

    /** Ready window when opening instead of the original app (on by default). */
    public static boolean quickCover(Context c) {
        return sp(c).getBoolean("quickCover", true);
    }

    public static void setQuickCover(Context c, boolean on) {
        sp(c).edit().putBoolean("quickCover", on).apply();
    }

    /** Time with milliseconds, to measure the flash of the original app. */
    public static String ms(long wall) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY).format(new Date(wall));
    }

    /** Versions of the original climate app verified on this car. */
    public static boolean isVerified(Context c, String v) {
        return ("|" + sp(c).getString("verified", "") + "|").contains("|" + v + "|");
    }

    public static void addVerified(Context c, String v) {
        if (v == null || v.isEmpty() || isVerified(c, v)) return;
        String old = sp(c).getString("verified", "");
        sp(c).edit().putString("verified", old.isEmpty() ? v : old + "|" + v).apply();
    }

    /** Version seen but not verified yet ("" = none). */
    public static String pendingVerify(Context c) {
        return sp(c).getString("verifyPending", "");
    }

    public static void setPendingVerify(Context c, String v) {
        sp(c).edit().putString("verifyPending", v == null ? "" : v).apply();
    }

    /** Until when the replacement is paused (workshop mode, or the original app opened on purpose); 0 = never. */
    public static long bypassUntil(Context c) {
        return sp(c).getLong("bypassUntil", 0);
    }

    /** For a while the original climate app is not replaced. */
    public static void bypass(Context c, long ms) {
        sp(c).edit().putLong("bypassUntil", System.currentTimeMillis() + ms).apply();
    }

    public static boolean bypassed(Context c) {
        return System.currentTimeMillis() < sp(c).getLong("bypassUntil", 0);
    }

    // ---------------------------------------------------------------------- diagnostics

    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static String lastState = "";

    public static synchronized void event(Context c, String what) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date()) + "  " + what;
        Log.i(TAG, what);
        EVENTS.addLast(line);
        while (EVENTS.size() > 400) EVENTS.removeFirst();
    }

    /** State read from the car (kept for the diagnostics). */
    public static synchronized void diag(String state) {
        lastState = state;
    }

    /** Climate diagnostics: settings, state lines, last state read and events. */
    public static synchronized String text(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("climate screen (built-in) · launcher ").append(appVersion(c)).append(" · ")
                .append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(new Date())).append('\n');
        sb.append("software: ").append(software()).append('\n');
        sb.append("dashboard: ").append(dashStyle(c) == STYLE_PHOTO ? "realistic" : "drawn")
                .append(" · windscreen/rear window buttons: ").append(defrostTiles(c) ? "yes" : "no")
                .append(" · right-hand drive: ").append(rightHand(c) ? "yes" : "no").append('\n');
        sb.append("close the shade: ").append(closeShade(c) ? "yes" : "no")
                .append(" · swipe to close: ").append(swipeName(swipeMode(c))).append('\n');
        int js = joystick(c);
        sb.append("joystick commands: ").append(js == JOY_OFF ? "off" : "right joystick")
                .append(" (").append(Joystick.mapText(c, JOY_RIGHT)).append(joyFactory(c) ? ", factory" : ", paired by hand").append(')')
                .append(joyCode(c, JOY_LEFT, "up") >= 0 ? " · left: " + Joystick.mapText(c, JOY_LEFT) : "").append('\n');
        sb.append("joystick.exclusive = ").append(Joystick.exclusiveLine(c)).append('\n');
        sb.append("joystick.filterKeys = ").append(ClimateGuard.seenText()).append('\n');
        sb.append("climate.open = ").append(ClimateActivity.openText()).append(" · in front ").append(ClimateGuard.foreground())
                .append(" · panel ").append(OverlayPanel.stateText()).append('\n');
        sb.append("climate.return = ").append(returnLine).append(" · automatic closing ")
                .append(autoClose(c) == 0 ? "never" : autoClose(c) + " s").append('\n');
        sb.append("climate.projection = ").append(ProjectionZone.stateText(c)).append('\n');
        sb.append("button labels: ").append(showLabels(c) ? "yes" : "no").append(" · ready window: ")
                .append(quickCover(c) ? "yes" : "no").append('\n');
        sb.append("original climate app: ").append(ClimateGuard.originalLine(c)).append('\n');
        sb.append("temperature (calibration): ").append(CarAdapterBackend.limitsText()).append('\n');
        sb.append("climate.range = ").append(CarAdapterBackend.rangeText()).append('\n');
        sb.append("replacement: ").append(replace(c) ? "on" : "off")
                .append(bypassed(c) ? " (paused: original app opened on purpose or workshop mode)" : "")
                .append(" · accessibility: ").append(ClimateGuard.enabledState(c)).append('\n');
        sb.append("\n== STATE READ ==\n").append(lastState).append('\n');
        sb.append("\n== EVENTS ==\n");
        for (String e : EVENTS) sb.append(e).append('\n');
        return sb.toString();
    }
}
