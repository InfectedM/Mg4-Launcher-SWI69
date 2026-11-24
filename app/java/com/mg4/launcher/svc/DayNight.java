package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 1.0.2: automatic day/night theme, the same for all versions, with a chain of sources.
 * 1.0.4: the system (uiMode) is no longer a source: the launcher decides the theme and writes it to the system (syncSystem).
 * <ol>
 *   <li>the headlights (low beam or position lights, car property): on with the sun low = night;</li>
 *   <li>the time: sunrise and sunset computed offline from the last known position (if missing, time zone and Italy).</li>
 * </ol>
 * A change is applied only if it stays the same for 60 s (the theme does not flicker in a tunnel). The manual Day/Night
 * choice in the settings always takes precedence (Theme checks it).
 */
public final class DayNight {
    public interface Listener {
        void onThemeChange(boolean day);
    }

    private static final long CONFIRM_MS = 60_000, TICK_MS = 20_000, CANDIDATES_EVERY_MS = 10 * 60_000L;
    private static final long RELIABLE_WINDOW_MS = 72L * 60 * 60 * 1000;
    private static final String K_SYS_NIGHT_AT = "themeSysNightAt", K_LAT = "themeLastLat", K_LON = "themeLastLon";
    /** Central Italy: reference if there is no position at all. */
    private static final double ITALY_LAT = 42.5, ITALY_LON = 12.5;

    private static DayNight instance;

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler h = new Handler(Looper.getMainLooper());
    /** Readings (CarService, position) are done on this thread, never on the main thread. */
    private final Handler work;
    private Listener listener;
    private Boolean applied;            // automatic theme applied (true = day)
    private Boolean pending;
    private long pendingSince;
    private String lastChange = "—", source = "—", detail = "";
    private int lastUiNight = -1;
    private long candidatesAt;

    private DayNight(Context c) {
        ctx = c.getApplicationContext();
        sp = com.mg4.launcher.ui.Prefs.sp(ctx);
        android.os.HandlerThread t = new android.os.HandlerThread("mg4-theme");
        t.start();
        work = new Handler(t.getLooper());
    }

    public static synchronized DayNight get(Context c) {
        if (instance == null) instance = new DayNight(c);
        return instance;
    }

    public void start(Listener l) {
        listener = l;
        work.removeCallbacks(tick);
        work.post(tick);
        new Thread(this::ensureLocationPermission, "mg4-location").start();
    }

    public void stop() {
        work.removeCallbacksAndMessages(null);
        listener = null;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            evaluate(false);
            work.postDelayed(this, TICK_MS);
        }
    };

    /** The system changed uiMode: re-evaluate right away and again after the confirmation time. */
    public void onConfigurationChanged() {
        work.post(() -> evaluate(false));
        work.postDelayed(() -> evaluate(false), CONFIRM_MS + 1000);
    }

    /** Automatic theme to apply now (on the first call it is computed right away, without waiting). */
    public synchronized boolean isDay() {
        if (applied == null) evaluate(true);
        return applied == null || applied;
    }

    /** Evaluates the sources; with immediate = true applies right away (startup), otherwise after 60 s of confirmation. */
    public synchronized void evaluate(boolean immediate) {
        boolean target;
        try {
            target = compute();
        } catch (Throwable e) {
            Diag.error("theme.state", e.toString());
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (applied == null || immediate && applied != target) {
            applyNow(target, applied == null ? "startup" : "immediate");
            return;
        }
        if (applied == target) {
            pending = null;
        } else if (pending == null || pending != target) {
            pending = target;
            pendingSince = now;
        } else if (now - pendingSince >= CONFIRM_MS) {
            pending = null;
            applyNow(target, "confirmed after 60 s");
            Listener l = listener;
            // the home is rebuilt on the main thread
            if (l != null) h.post(() -> l.onThemeChange(target));
        }
        writeDiag();
        if (now - candidatesAt > CANDIDATES_EVERY_MS || candidatesAt == 0) {
            candidatesAt = now;
            new Thread(this::candidates, "mg4-theme-candidates").start();   // slow readings off the main thread
        }
    }

    // ---- 1.0.3: readings for the Function check -------------------------------------------------

    private volatile double lastSunElevation = Double.NaN;

    /** Source used right now (log text). */
    public String source() {
        return source;
    }

    /** Automatic theme applied: true day, false night, null not decided yet. */
    public Boolean applied() {
        return applied;
    }

    public double sunElevation() {
        return lastSunElevation;
    }

    public boolean uiStuck() {
        return sp.getBoolean(K_UI_STUCK, false);
    }

    private void applyNow(boolean day, String how) {
        applied = day;
        lastChange = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY).format(new Date()) + " (" + how + ")";
        writeDiag();
        syncSystem("launcher decision (" + how + ")");
    }

    // ---- 0.4.4 (SWI69): the theme is written to the system the way the SAIC EH32 apps do it ----------

    /** System setting of the SAIC EH32 apps (SWI69/SWI131/SWI132): 0 = dark (night), 1 = light (day). */
    public static final String SKIN_KEY = "SKIN_THEME_CONFIG";
    /** Broadcast that makes the SAIC launcher, SystemUI, Clima and the other SAIC apps reload the theme. */
    public static final String SKIN_ACTION = "com.saicmotor.changeSkin";

    private volatile String sysTxt = "not written yet";

    /**
     * Writes the effective theme to the system (manual Day/Night choice, otherwise the automatic one).
     * On SWI69 R45 the system-wide theme does not go through UiModeManager: the CarAdapterService service
     * (AutoSkinObserver) and Settings (GeneralModel) write Settings.System SKIN_THEME_CONFIG (0 dark, 1 light)
     * and send the com.saicmotor.changeSkin broadcast; the SAIC launcher, SystemUI and the other apps read it back.
     * The same is done here. It is written only when the value differs: on launcher decisions, at startup and when
     * the manual choice changes; never on every tick.
     */
    public void syncSystem(String why) {
        work.post(() -> {
            String mode = sp.getString("theme", "auto");
            Boolean day = com.mg4.launcher.ui.Prefs.THEME_DAY.equals(mode) ? Boolean.TRUE : com.mg4.launcher.ui.Prefs.THEME_NIGHT.equals(mode) ? Boolean.FALSE : applied;
            if (day == null) return;
            String at = new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date());
            int want = day ? 1 : 0;
            android.content.ContentResolver cr = ctx.getContentResolver();
            String auto = "";
            try {
                auto = " · SAIC automatic theme (screen_skin_mode) " + android.provider.Settings.System.getInt(cr, "screen_skin_mode", -1);
            } catch (Throwable ignored) {
            }
            try {
                int now = android.provider.Settings.System.getInt(cr, SKIN_KEY, -1);
                if (now == want) {
                    sysTxt = "already " + (day ? "day" : "night") + " (" + SKIN_KEY + " " + now + ") · checked at " + at + " · " + why + auto;
                } else {
                    boolean ok = android.provider.Settings.System.putInt(cr, SKIN_KEY, want);
                    ctx.sendBroadcast(new android.content.Intent(SKIN_ACTION));
                    int after = android.provider.Settings.System.getInt(cr, SKIN_KEY, -1);
                    sysTxt = "written " + (day ? "day" : "night") + " (" + SKIN_KEY + " " + now + " → " + want + ", broadcast " + SKIN_ACTION
                            + ") · at " + at + " · result " + (ok && after == want ? "ok" : "error (system says " + after + ")")
                            + " · " + why + auto;
                }
            } catch (Throwable e) {
                sysTxt = "written " + (day ? "day" : "night") + " · at " + at + " · result error " + e.getClass().getSimpleName()
                        + " · " + why + auto;
            }
            Diag.put("theme.system", sysTxt);
            // read back 3 s later: if the SAIC service rewrote it, it shows in the log (no repeated rewriting)
            final int wanted = want;
            work.postDelayed(() -> {
                int v = -1;
                try {
                    v = android.provider.Settings.System.getInt(ctx.getContentResolver(), SKIN_KEY, -1);
                } catch (Throwable ignored) {
                }
                appliedOk = v == wanted;
                Diag.put("theme.system", sysTxt + " · after 3 s: " + (v == 1 ? "day" : v == 0 ? "night" : "unreadable")
                        + (appliedOk ? "" : " (DIFFERENT from what was written)"));
            }, 3000);
        });
    }

    /** Theme currently written in the system: true day, false night, null unreadable (Function check). */
    public Boolean systemDay() {
        try {
            int v = android.provider.Settings.System.getInt(ctx.getContentResolver(), SKIN_KEY, -1);
            return v == 1 ? Boolean.TRUE : v == 0 ? Boolean.FALSE : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private volatile boolean appliedOk = true;

    /** True if the effective theme of the configuration matches the one written (last check). */
    public boolean systemApplied() {
        return appliedOk;
    }

    /** For the Function check: [wanted theme (true day), system theme read back], null if not decided or unreadable. */
    public Boolean[] systemCheck() {
        String mode = sp.getString("theme", "auto");
        Boolean day = com.mg4.launcher.ui.Prefs.THEME_DAY.equals(mode) ? Boolean.TRUE : com.mg4.launcher.ui.Prefs.THEME_NIGHT.equals(mode) ? Boolean.FALSE : applied;
        // 0.4.4 (SWI69): the system theme is SKIN_THEME_CONFIG (0 dark, 1 light)
        return new Boolean[]{day, systemDay()};
    }

    public String systemTxt() {
        return sysTxt;
    }

    // ------------------------------------------------------------------------------------------

    private String uiTxt = "?", mgrTxt = "?", nightPropTxt = "?", lightsTxt = "?", sunTxt = "?";

    private boolean compute() {
        // a) system (log only: since 0.4.4 the launcher decides the theme and writes it to SKIN_THEME_CONFIG)
        int ui = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int uiNight = ui == Configuration.UI_MODE_NIGHT_YES ? 1 : ui == Configuration.UI_MODE_NIGHT_NO ? 0 : -1;
        uiTxt = uiNight == 1 ? "night" : uiNight == 0 ? "day" : "undefined";
        int mgr = -1;
        try {
            android.app.UiModeManager um = (android.app.UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
            int m = um.getNightMode();
            mgr = m == android.app.UiModeManager.MODE_NIGHT_YES ? 1 : m == android.app.UiModeManager.MODE_NIGHT_NO ? 0 : -1;
            mgrTxt = mgr == 1 ? "night" : mgr == 0 ? "day" : m == android.app.UiModeManager.MODE_NIGHT_AUTO ? "auto" : String.valueOf(m);
        } catch (Throwable e) {
            mgrTxt = "?";
        }
        int nightProp = Integer.MIN_VALUE;
        CarProps p = VehicleClient.carProps();
        if (p != null && p.ready()) nightProp = p.getBoolQuiet(CarProps.NIGHT_MODE);
        nightPropTxt = nightProp == 1 ? "night" : nightProp == 0 ? "day" : "absent";
        boolean sysNight = uiNight == 1 || mgr == 1 || nightProp == 1;
        long wall = System.currentTimeMillis();
        trackUiChanges(uiNight, wall);
        long prevSeen = sp.getLong(K_SYS_NIGHT_AT, 0);
        boolean changed = lastUiNight >= 0 && uiNight >= 0 && uiNight != lastUiNight;
        // written only when needed (at most once an hour), not on every tick
        if ((sysNight || changed) && wall - prevSeen > 60 * 60_000L) sp.edit().putLong(K_SYS_NIGHT_AT, wall).apply();
        if (uiNight >= 0) lastUiNight = uiNight;
        long seen = sp.getLong(K_SYS_NIGHT_AT, 0);
        boolean reliable = seen > 0 && wall - seen < RELIABLE_WINDOW_MS && wall >= seen;

        // c) sun (always computed, also needed for the headlights and the diagnostics)
        double[] loc = location();
        Sun sun = sun(loc[0], loc[1], wall);
        lastSunElevation = sun.elevation;
        sunTxt = "sunrise " + sun.rise + " · sunset " + sun.set + " · sun at " + Math.round(sun.elevation) + "° (" + (loc[2] == 1 ? "location"
                : loc[2] == 2 ? "last saved location" : "time zone/Italy") + ")";

        // b) headlights
        int lights = VehicleClient.lights();
        lightsTxt = lights == 1 ? "on" : lights == 0 ? "off" : "unreadable";

        // 1.0.4 (point A): the system uiMode is NO longer a source. It used to be changed by the SAIC launcher/settings
        // service; with our launcher nobody changed it and it stayed on "night". Now we decide it (headlights →
        // sunrise/sunset) and WRITE it to the system (syncSystem). uiMode, UiModeManager and NIGHT_MODE stay in the log.
        uiStuckTxt = "";
        boolean sunNight = sun.elevation < -0.833;
        if (lights >= 0) {
            // with the sun high, lights on means daytime running lights or a tunnel: stays day
            boolean night = sunNight || (lights == 1 && sun.elevation < 6);
            source = "headlights (" + lightsTxt + ") + sun";
            return !night;
        }
        source = "time (sunrise/sunset)";
        return !sunNight;
    }

    // ---- uiMode stuck (1.0.3) -------------------------------------------------------------------

    /** Power-on time without uiMode changes beyond which, if it contradicts the sun, uiMode is considered stuck. */
    private static final long UI_STUCK_ON_MS = 12L * 60 * 60 * 1000;
    private static final String K_UI_LAST = "themeUiLast", K_UI_CHANGED_AT = "themeUiChangedAt", K_UI_ON_MS = "themeUiOnMs",
            K_UI_STUCK = "themeUiStuck", K_UI_STUCK_WHY = "themeUiStuckWhy";
    private long onTickAt, onPending, onSavedAt;
    private String uiStuckTxt = "";

    /**
     * Counts power-on time since uiMode last changed (day↔night), also across ignition cycles.
     * Time with the car off does not count: between two ticks at most 3 times the check interval is added.
     */
    private void trackUiChanges(int uiNight, long wall) {
        long now = SystemClock.elapsedRealtime();
        if (onTickAt > 0) onPending += Math.max(0, Math.min(now - onTickAt, 3 * TICK_MS));
        onTickAt = now;
        if (uiNight < 0) return;
        int last = sp.getInt(K_UI_LAST, -1);
        SharedPreferences.Editor e = null;
        if (last != uiNight) {
            e = sp.edit().putInt(K_UI_LAST, uiNight);
            if (last >= 0) {
                // real change: reset the count and uiMode is reliable again
                e.putLong(K_UI_CHANGED_AT, wall).putLong(K_UI_ON_MS, 0).putBoolean(K_UI_STUCK, false).remove(K_UI_STUCK_WHY);
                onPending = 0;
                onSavedAt = now;
            }
        }
        if (onPending > 0 && (now - onSavedAt > 5 * 60_000L || e != null)) {
            if (e == null) e = sp.edit();
            e.putLong(K_UI_ON_MS, sp.getLong(K_UI_ON_MS, 0) + onPending);
            onPending = 0;
            onSavedAt = now;
        }
        if (e != null) e.apply();
    }

    private void markStuck(String why) {
        sp.edit().putBoolean(K_UI_STUCK, true).putString(K_UI_STUCK_WHY, why).apply();
    }

    private String uiHistoryTxt() {
        long at = sp.getLong(K_UI_CHANGED_AT, 0);
        long onH = (sp.getLong(K_UI_ON_MS, 0) + onPending) / 3_600_000L;
        return "last uiMode change " + (at == 0 ? "never seen" : new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(new Date(at)))
                + " (" + onH + " h powered on since)";
    }

    private void writeDiag() {
        String mode = sp.getString("theme", "auto");
        String manual = com.mg4.launcher.ui.Prefs.THEME_DAY.equals(mode) ? "manual choice Day (takes precedence) · automatic: " : com.mg4.launcher.ui.Prefs.THEME_NIGHT.equals(mode)
                ? "manual choice Night (takes precedence) · automatic: " : "";
        Diag.put("theme.state", manual + "source " + source + (uiStuckTxt.isEmpty() ? "" : " · " + uiStuckTxt) + " · " + uiHistoryTxt()
                + " · uiMode " + uiTxt + " · UiModeManager " + mgrTxt + " · NIGHT_MODE " + nightPropTxt
                + " · headlights " + lightsTxt + " · " + sunTxt + " · applied " + (applied == null ? "—" : applied ? "day" : "night")
                + (pending != null ? " (confirming: " + (pending ? "day" : "night") + ")" : "") + " · last change " + lastChange);
    }

    // ---- position ----------------------------------------------------------------------------

    private void ensureLocationPermission() {
        for (String perm : new String[]{"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"}) {
            if (ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) continue;
            try {
                android.content.pm.PackageManager pm = ctx.getPackageManager();
                pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                        .invoke(pm, ctx.getPackageName(), perm, android.os.Process.myUserHandle());
            } catch (Throwable ignored) {
            }
        }
    }

    /** {lat, lon, type}: type 1 = current position, 2 = last saved, 0 = time zone/Italy. */
    private double[] location() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            if (lm != null) {
                for (String prov : lm.getProviders(false)) {
                    try {
                        android.location.Location l = lm.getLastKnownLocation(prov);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (best != null && (best.getLatitude() != 0 || best.getLongitude() != 0)) {
                sp.edit().putFloat(K_LAT, (float) best.getLatitude()).putFloat(K_LON, (float) best.getLongitude()).apply();
                return new double[]{best.getLatitude(), best.getLongitude(), 1};
            }
        } catch (Throwable ignored) {
        }
        if (sp.contains(K_LAT)) return new double[]{sp.getFloat(K_LAT, 0), sp.getFloat(K_LON, 0), 2};
        TimeZone tz = TimeZone.getDefault();
        String id = tz.getID();
        if (id == null || id.equals("Europe/Rome") || id.equals("GMT") || id.equals("UTC")) return new double[]{ITALY_LAT, ITALY_LON, 0};
        // longitude from the time zone (15° per hour), latitude of Italy
        double lon = tz.getRawOffset() / 3_600_000.0 * 15.0;
        return new double[]{ITALY_LAT, lon, 0};
    }

    // ---- sun (simplified NOAA formulas, accurate to a few minutes) ----------------------------

    static final class Sun {
        double elevation;
        String rise = "—", set = "—";
    }

    static Sun sun(double lat, double lon, long wallMs) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(wallMs);
        int doy = u.get(Calendar.DAY_OF_YEAR);
        double hour = u.get(Calendar.HOUR_OF_DAY) + u.get(Calendar.MINUTE) / 60.0 + u.get(Calendar.SECOND) / 3600.0;
        double g = 2 * Math.PI / 365.0 * (doy - 1 + (hour - 12) / 24.0);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double tst = hour * 60 + eq + 4 * lon;
        double ha = Math.toRadians(tst / 4.0 - 180);
        double la = Math.toRadians(lat);
        double cosZ = Math.sin(la) * Math.sin(decl) + Math.cos(la) * Math.cos(decl) * Math.cos(ha);
        Sun s = new Sun();
        s.elevation = 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZ))));
        double c = Math.cos(Math.toRadians(90.833)) / (Math.cos(la) * Math.cos(decl)) - Math.tan(la) * Math.tan(decl);
        if (c >= -1 && c <= 1) {
            double ha0 = Math.toDegrees(Math.acos(c));
            s.rise = localTime(u, 720 - 4 * (lon + ha0) - eq);
            s.set = localTime(u, 720 - 4 * (lon - ha0) - eq);
        }
        return s;
    }

    private static String localTime(Calendar utcDay, double minutesUtc) {
        Calendar c = (Calendar) utcDay.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long ms = c.getTimeInMillis() + Math.round(minutesUtc * 60_000);
        return new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date(ms));
    }

    // ---- tema.candidati: all day/night signals that can be read ----------------------------------

    private float lastLux = Float.NaN;
    private boolean luxAsked;

    private void candidates() {
        StringBuilder sb = new StringBuilder();
        CarProps p = VehicleClient.carProps();
        if (p != null && p.ready()) {
            sb.append("NIGHT_MODE ").append(val(p.getBoolQuiet(CarProps.NIGHT_MODE)));
            sb.append(" · HEADLIGHTS_STATE ").append(val(p.getIntSafe(CarProps.HEADLIGHTS_STATE)));
            sb.append(" · HEADLIGHTS_SWITCH ").append(val(p.getIntSafe(CarProps.HEADLIGHTS_SWITCH)));
            sb.append(" · DISPLAY_BRIGHTNESS ").append(val(p.getIntSafe(CarProps.DISPLAY_BRIGHTNESS)));
        } else {
            sb.append("CarService not ready");
        }
        sb.append(" · headlights (service) ").append(VehicleClient.lightsDescription());
        // light sensor
        try {
            android.hardware.SensorManager sm = (android.hardware.SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            android.hardware.Sensor light = sm == null ? null : sm.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT);
            if (light == null) sb.append(" · light sensor absent");
            else {
                sb.append(" · light sensor ").append(Float.isNaN(lastLux) ? "reading" : Math.round(lastLux) + " lux");
                if (!luxAsked) {
                    luxAsked = true;
                    sm.registerListener(new android.hardware.SensorEventListener() {
                        @Override
                        public void onSensorChanged(android.hardware.SensorEvent e) {
                            lastLux = e.values[0];
                            sm.unregisterListener(this);   // one reading is enough for the diagnostics
                            luxAsked = false;
                        }

                        @Override
                        public void onAccuracyChanged(android.hardware.Sensor s, int a) {
                        }
                    }, light, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        } catch (Throwable e) {
            sb.append(" · light sensor error");
        }
        // system settings related to day/night or brightness
        sb.append(" · settings ").append(settingsMatching());
        try {
            sb.append(" · ui_night_mode ").append(android.provider.Settings.Secure.getString(ctx.getContentResolver(), "ui_night_mode"));
        } catch (Throwable ignored) {
        }
        sb.append(" · broadcast: configuration change (uiMode) listened to by the activity");
        Diag.put("theme.candidates", sb.toString());
    }

    private static String val(int v) {
        return v == Integer.MIN_VALUE ? "absent" : String.valueOf(v);
    }

    private String settingsMatching() {
        StringBuilder sb = new StringBuilder();
        for (String table : new String[]{"system", "secure", "global"}) {
            android.database.Cursor c = null;
            try {
                c = ctx.getContentResolver().query(android.net.Uri.parse("content://settings/" + table), new String[]{"name", "value"}, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String n = c.getString(0);
                    if (n == null) continue;
                    String l = n.toLowerCase(Locale.ROOT);
                    if (l.contains("night") || l.contains("day_") || l.contains("dark") || l.contains("brightness") || l.contains("daynight")
                            || l.contains("theme") || l.contains("lamp") || l.contains("headlight")) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(table).append('/').append(n).append('=').append(c.getString(1));
                        if (sb.length() > 900) return sb.append("…").toString();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) c.close();
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
