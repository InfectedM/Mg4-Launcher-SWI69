package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.mg4.launcher.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.4.3 (point 10, from SWI68 0.4.3 adapted to SWI69 R45): Function check, like MG4 Clima's AutoTest and MG4 Voice's check.
 * <p>
 * Everything is READ ONLY: it looks at the connected services and the values already read, no command is sent to the car
 * (no climate, radio, play, calls). Each test gives ✓ (works), ✗ (does not work) or — (not present on this car,
 * or skipped). In the log: selfcheck.&lt;name&gt; = ✓/✗/— detail, plus selfcheck.summary.
 * <p>
 * After every update the check starts by itself, silently, 60 s after startup: log only, without the steering wheel
 * keys test (which asks to press a key).
 */
public final class SelfCheck {
    public static final int OK = 1, FAIL = 0, ABSENT = -1, SKIPPED = -2;

    public static final class Item {
        public final String key;
        public final int label;
        public int status = SKIPPED;
        /** Detail in Italian for the log. */
        public String detail = "";
        /** Word-free detail (numbers, °C, ports) shown on screen next to the translated sentence. */
        public String shown = "";

        Item(String key, int label) {
            this.key = key;
            this.label = label;
        }

        public String mark() {
            return status == OK ? "✓" : status == FAIL ? "✗" : "—";
        }
    }

    public interface Progress {
        /** A row changed (on the main thread). */
        void onItem(Item it);

        /** Keys test: seconds remaining (on the main thread); 0 = finished. */
        void onKeysCountdown(int seconds);

        /** 1.0.4: lights test: seconds remaining (on the main thread); 0 = finished. */
        default void onLightsCountdown(int seconds) {
        }

        void onDone(List<Item> items, int ok, int fail, int absent);
    }

    private static VehicleClient vehicle;
    private static MediaClient media;
    private static volatile boolean running;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SelfCheck() {}

    /** Called by MainActivity: the already connected clients (the check does not open new ones). */
    public static void bind(VehicleClient v, MediaClient m) {
        vehicle = v;
        media = m;
    }

    public static boolean running() {
        return running;
    }

    /** List of tests, in the order they appear on screen. */
    public static List<Item> items() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("vehicle", R.string.chk_vehicle));
        l.add(new Item("climate", R.string.chk_climate));
        l.add(new Item("radio", R.string.chk_radio));
        l.add(new Item("media", R.string.chk_media));
        l.add(new Item("bms", R.string.chk_bms));
        l.add(new Item("carplayAA", R.string.chk_projection));
        l.add(new Item("tempOutside", R.string.chk_out_temp));
        l.add(new Item("tempInside", R.string.chk_in_temp));
        l.add(new Item("theme", R.string.chk_theme));
        l.add(new Item("themeSystem", R.string.chk_theme_system));
        l.add(new Item("lights", R.string.chk_lights));
        l.add(new Item("usb", R.string.chk_usb));
        l.add(new Item("bluetooth", R.string.chk_bt));
        l.add(new Item("stations", R.string.chk_stations));
        l.add(new Item("wheelKeys", R.string.chk_keys));
        l.add(new Item("climateButton", R.string.chk_sys_button));
        l.add(new Item("fogIndicator", R.string.chk_fog_window));
        l.add(new Item("seatsWheel", R.string.chk_comfort));
        l.add(new Item("update", R.string.chk_update));
        l.add(new Item("notices", R.string.chk_notices));
        l.add(new Item("chargeAnim", R.string.chk_charge_anim));
        l.add(new Item("ready", R.string.chk_ready));
        return l;
    }

    /**
     * Starts the check on a separate thread. interactive = true: with the steering wheel keys test (10 s).
     * Progress can be null (silent check after an update).
     */
    public static void run(Context ctx, boolean interactive, Progress p) {
        if (running) return;
        running = true;
        Context c = ctx.getApplicationContext();
        List<Item> items = items();
        new Thread(() -> {
            try {
                Diag.put("selfcheck.start", (interactive ? "from settings" : "automatic after update") + " · "
                        + new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date()));
                for (Item it : items) {
                    try {
                        check(c, it, interactive, p);
                    } catch (Throwable e) {
                        it.status = FAIL;
                        it.detail = "error " + e.getClass().getSimpleName();
                    }
                    Diag.put("selfcheck." + it.key, it.mark() + " " + it.detail);
                    if (p != null) main.post(() -> p.onItem(it));
                }
                int ok = 0, fail = 0, absent = 0;
                for (Item it : items) {
                    if (it.status == OK) ok++;
                    else if (it.status == FAIL) fail++;
                    else absent++;
                }
                Diag.put("selfcheck.summary", ok + " ok · " + fail + " errors · " + absent + " absent");
                int o = ok, f = fail, a = absent;
                if (p != null) main.post(() -> p.onDone(items, o, f, a));
            } finally {
                running = false;
            }
        }, "mg4-selfcheck").start();
    }

    private static void set(Item it, int status, String detail, String shown) {
        it.status = status;
        it.detail = detail;
        it.shown = shown == null ? "" : shown;
    }

    private static void check(Context c, Item it, boolean interactive, Progress p) throws InterruptedException {
        VehicleClient v = vehicle;
        MediaClient m = media;
        VehicleClient.State s = v == null ? null : v.last();
        switch (it.key) {
            case "vehicle": {
                CarProps cp = VehicleClient.carProps();
                boolean hub = s != null && s.connected;
                boolean props = cp != null && cp.ready();
                set(it, hub ? OK : FAIL, "vehicle service " + (hub ? "connected" : "not connected") + " · CarService "
                        + (props ? "ready" : "not ready"), "");
                break;
            }
            case "climate": {
                boolean read = s != null && (s.hvacPower >= 0 || s.acTemp > 0 || s.fanLevel >= 0);
                boolean app = installed(c, com.mg4.launcher.apps.MgApps.HVAC_PKG);
                if (read) set(it, OK, "state read (power " + s.hvacPower + ", temperature " + s.acTemp + ", fan " + s.fanLevel + ")",
                        s.acTemp > 0 ? s.acTemp + "°" : "");
                else set(it, FAIL, "climate state not read" + (app ? "" : " · Climate app absent"), "");
                break;
            }
            case "radio": {
                if (!installed(c, "com.saicmotor.radio")) {
                    set(it, ABSENT, "radio service not installed", "");
                    break;
                }
                boolean ok = m != null && m.radioLinked();
                set(it, ok ? OK : FAIL, "radio service " + (ok ? "connected" : "not connected") + " · callback " + Diag.value("radio.callback"), "");
                break;
            }
            case "media": {
                boolean ok = m != null && m.mediaLinked();
                set(it, ok ? OK : FAIL, "media service (USB session 1) " + (ok ? "connected" : "not connected"), "");
                break;
            }
            case "bms": {
                boolean ok = s != null && s.bmsAvailable && s.soc >= 0;
                set(it, ok ? OK : FAIL, ok ? "charge read " + Math.round(s.soc) + "%" : "battery charge not read",
                        ok ? Math.round(s.soc) + "%" : "");
                break;
            }
            case "carplayAA": {
                Boolean l = m == null ? Boolean.FALSE : m.projectionLinked();
                if (l == null) set(it, ABSENT, "Allgo (CarPlay/Android Auto) not installed", "");
                else set(it, l ? OK : FAIL, "Allgo service " + (l ? "connected" : "not connected")
                        + (m != null && m.last().deviceType != 0 ? " · phone connected" : " · no phone"), "");
                break;
            }
            case "tempOutside": {
                boolean ok = s != null && s.outsideTemp > -60 && s.outsideTemp < 70;
                set(it, ok ? OK : FAIL, ok ? "read " + s.outsideTemp + " °C" : "not read", ok ? Math.round(s.outsideTemp) + "°C" : "");
                break;
            }
            case "tempInside": {
                CarProps cp = VehicleClient.carProps();
                if (cp != null && cp.ready() && !cp.has(CarProps.HVAC_TEMPERATURE_CURRENT)) {
                    set(it, ABSENT, "property absent (the option hides itself)", "");
                    // the "Automatico" option hides itself, as the normal reading also does
                    com.mg4.launcher.ui.Prefs.sp(c).edit().putBoolean("insideTempMissing", true).apply();
                    break;
                }
                boolean ok = s != null && !Float.isNaN(s.insideTemp) && !s.insideSource.isEmpty();
                set(it, ok ? OK : FAIL, ok ? "read " + s.insideTemp + " °C from " + s.insideSource : "no valid value · " + Diag.value("climate.tempInside"),
                        ok ? Math.round(s.insideTemp) + "°C" : "");
                break;
            }
            case "theme": {
                DayNight dn = DayNight.get(c);
                Boolean day = dn.applied();
                double sun = dn.sunElevation();
                String mode = com.mg4.launcher.ui.Prefs.sp(c).getString("theme", "auto");
                String base = "source " + dn.source() + " · applied " + (day == null ? "—" : day ? "day" : "night")
                        + (Double.isNaN(sun) ? "" : " · sun at " + Math.round(sun) + "°") + (dn.uiStuck() ? " · uiMode stuck: ignored" : "");
                if (!"auto".equals(mode)) {
                    set(it, OK, "manual choice " + mode + " · " + base, "");
                } else if (day == null || Double.isNaN(sun)) {
                    set(it, FAIL, "theme not decided yet · " + base, "");
                } else {
                    boolean wrong = !day && sun > 6 || day && sun < -6;
                    set(it, wrong ? FAIL : OK, (wrong ? "inconsistent with the sun · " : "consistent with the sun · ") + base, "");
                }
                break;
            }
            case "themeSystem": {
                // 0.4.4: the theme written by the launcher into the system, read back from Settings.System SKIN_THEME_CONFIG
                Boolean[] r = DayNight.get(c).systemCheck();
                if (r[0] == null) set(it, FAIL, "theme not decided yet · " + DayNight.get(c).systemTxt(), "");
                else if (r[1] == null) set(it, FAIL, "SKIN_THEME_CONFIG unreadable · " + DayNight.get(c).systemTxt(), "");
                else set(it, r[0].equals(r[1]) && DayNight.get(c).systemApplied() ? OK : FAIL, "wanted " + (r[0] ? "day" : "night") + " · system " + (r[1] ? "day" : "night")
                        + (DayNight.get(c).systemApplied() ? "" : " · configuration differs")
                        + " · " + DayNight.get(c).systemTxt(), r[1] ? "☀" : "☾");
                break;
            }
            case "lights": {
                // 1.0.4 (point B): the interactive test asks to turn on the position lights within 10 s
                if (VehicleClient.lights() < 0) {
                    set(it, FAIL, "headlights unreadable · " + VehicleClient.lightsDescription(), "");
                    break;
                }
                if (!interactive) {
                    set(it, OK, "readable · " + VehicleClient.lightsDescription(), "");
                    break;
                }
                boolean seenOn = VehicleClient.lights() == 1;
                for (int sec = 10; sec > 0 && !seenOn; sec--) {
                    int left = sec;
                    if (p != null) main.post(() -> p.onLightsCountdown(left));
                    Thread.sleep(1000);
                    if (v != null) v.refreshLightsNow();
                    seenOn = VehicleClient.lights() == 1;
                }
                if (p != null) main.post(() -> p.onLightsCountdown(0));
                set(it, seenOn ? OK : FAIL, (seenOn ? "switch-on seen" : "no switch-on seen in 10 s") + " · "
                        + VehicleClient.lightsDescription(), "");
                break;
            }
            case "chargeAnim": {
                String color = com.mg4.launcher.ui.Prefs.CAR_COLORS[Math.max(0, Math.min(7,
                        com.mg4.launcher.ui.Prefs.sp(c).getInt("carColor", 5)))];
                boolean on = com.mg4.launcher.ui.Prefs.sp(c).getBoolean("chargeAnim", true);
                if (com.mg4.launcher.ui.ChargeAnimView.errors > 0) set(it, FAIL, "playback errors: " + com.mg4.launcher.ui.ChargeAnimView.errors
                        + " · " + ChargePack.state(), "");
                else if (ChargePack.complete(c, color)) set(it, OK, "pack " + color + " present" + (on ? "" : " (animation off)"), "");
                else if (ChargePack.failed()) set(it, FAIL, ChargePack.state(), "");
                else set(it, ABSENT, "not available yet · " + ChargePack.state(), "");
                break;
            }
            case "usb": {
                List<String> ports = m == null ? null : m.usbPortsSeen();
                if (ports == null) {
                    set(it, FAIL, "media service not responding", "");
                    break;
                }
                boolean u1 = m.mediaLinked(), u2 = m.usb2Linked();
                String txt = android.text.TextUtils.join(" · ", ports);
                if (!u1 && !u2) set(it, FAIL, "USB sessions not connected · " + txt, "");
                else set(it, OK, "USB 1 " + (u1 ? "yes" : "no") + " · USB 2 " + (u2 ? "yes" : "no") + " (separate sources) · " + txt,
                        "USB 1" + (u2 ? " · USB 2" : ""));
                break;
            }
            case "bluetooth": {
                if (m == null || !m.btLinked()) {
                    set(it, FAIL, "Bluetooth music service not connected", "");
                    break;
                }
                int st = m.btState();
                if (st != 2) set(it, ABSENT, "no phone connected via Bluetooth (state " + st + ")", "");
                else set(it, OK, "phone connected · titles from " + (MediaClient.btMetaSource.isEmpty() ? "— (open the Bluetooth source)"
                        : MediaClient.btMetaSource) + " · " + Diag.value("media.bt.wait"), "");
                break;
            }
            case "stations": {
                if (m == null || !m.radioLinked()) {
                    set(it, FAIL, "radio service not connected", "");
                    break;
                }
                // 1.0.4 (point C2): the list may arrive after the check starts: wait up to 30 s
                MediaClient.State ms = m.last();
                long until = android.os.SystemClock.uptimeMillis() + 30_000;
                while ((ms.stations == null || ms.stations.isEmpty()) && (ms.favorites == null || ms.favorites.isEmpty())
                        && android.os.SystemClock.uptimeMillis() < until) {
                    Thread.sleep(1000);
                    ms = m.last();
                }
                int n = ms.stations == null ? 0 : ms.stations.size();
                int f = ms.favorites == null ? 0 : ms.favorites.size();
                String listing = Diag.value("radio.list");
                if (n > 0 || f > 0) set(it, OK, n + " stations · " + f + " favorites · " + listing, String.valueOf(Math.max(n, f)));
                else set(it, FAIL, "no stations received (open the Radio and run a scan) · " + listing, "0");
                break;
            }
            case "wheelKeys": {
                if (!interactive) {
                    set(it, SKIPPED, "skipped (automatic check: a key press is needed)", "");
                    break;
                }
                final int[] seen = {-1};
                Object lock = new Object();
                WheelKeys.setProbe((code, down, lp) -> {
                    if (WheelKeys.isSkip(code)) synchronized (lock) {
                        seen[0] = code;
                        lock.notifyAll();
                    }
                });
                try {
                    for (int sec = 10; sec > 0 && seen[0] < 0; sec--) {
                        int left = sec;
                        if (p != null) main.post(() -> p.onKeysCountdown(left));
                        synchronized (lock) {
                            if (seen[0] < 0) lock.wait(1000);
                        }
                    }
                } finally {
                    WheelKeys.setProbe(null);
                    if (p != null) main.post(() -> p.onKeysCountdown(0));
                }
                if (seen[0] > 0) set(it, OK, "seen code " + seen[0] + " (" + WheelKeys.name(seen[0]) + ")", "#" + seen[0]);
                else set(it, FAIL, "no next/previous key seen in 10 s · " + Diag.value("media.wheelKeys"), "");
                break;
            }
            case "ready": {
                // 0.4.5: READY read from SENSOR_EPTRDY (read only); ignition does not count as READY
                if (s == null) {
                    set(it, FAIL, "vehicle service not connected", "");
                    break;
                }
                String txt = "ready " + s.ready + " (" + s.readySource + ") · gear " + VehicleClient.gearName(s.gear);
                if (s.ready == 1) set(it, OK, txt, "READY");
                else if (s.ready == 0) set(it, OK, txt, "—");
                else set(it, FAIL, txt + " · READY unknown: the animation is not blocked", "");
                break;
            }
            case "climateButton": {
                boolean app = installed(c, com.mg4.launcher.apps.MgApps.HVAC_PKG) || installed(c, "com.mg4.clima");
                if (app) set(it, OK, "opens " + (installed(c, "com.mg4.clima") ? "MG4 Clima or the Climate app" : "the original Climate app"), "");
                else set(it, ABSENT, "Climate app not installed", "");
                break;
            }
            case "fogIndicator": {
                // SystemUI window (type 2021) in the top left: the home leaves the corner free
                boolean on = CarFeatures.hasSystemLightIndicator(c);
                int inset = CarFeatures.systemLightInset(c);
                if (!on) set(it, ABSENT, "configuration: no system light indicator · " + Diag.value("car.config.lightIndicator"), "");
                else set(it, OK, "corner left free " + inset + " dp · " + Diag.value("lights.systemButton"), inset + " dp");
                break;
            }
            case "seatsWheel": {
                if (s == null) {
                    set(it, FAIL, "vehicle service not connected", "");
                    break;
                }
                String cfg = "EH32 configuration · left seat " + (s.seatLeft >= 0 ? "yes" : "no") + " · right seat " + (s.seatRight >= 0 ? "yes" : "no")
                        + " · steering wheel " + (s.wheelHeat >= 0 ? "yes" : "no");
                boolean any = s.seatLeft >= 0 || s.seatRight >= 0 || s.wheelHeat >= 0;
                set(it, any ? OK : ABSENT, cfg, "");
                break;
            }
            case "update":
            case "notices": {
                String[] r = remote();
                if ("update".equals(it.key)) {
                    if (r[0] != null) set(it, OK, "latest.json read: version " + r[0], r[0]);
                    else set(it, FAIL, "latest.json unreachable (no internet?)", "");
                } else {
                    if (r[1] != null) set(it, OK, "notices.json read: " + r[1] + " notices", r[1]);
                    else set(it, FAIL, "notices.json not read (no internet?)", "");
                }
                break;
            }
            default:
                set(it, SKIPPED, "", "");
        }
    }

    private static String[] remoteCache;
    private static long remoteAt;

    /** latest.json and notices.json read once per check (read only). */
    private static synchronized String[] remote() {
        long now = android.os.SystemClock.uptimeMillis();
        if (remoteCache == null || now - remoteAt > 30_000) {
            remoteCache = Updater.probeRemote();
            remoteAt = now;
        }
        return remoteCache;
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- automatic check after an update ------------------------------------------------------

    private static final String K_DONE_FOR = "checkDoneFor";

    /** If this version has not been checked yet, the silent check starts 60 s later. */
    public static void scheduleAfterUpdate(Context ctx, String version) {
        Context c = ctx.getApplicationContext();
        android.content.SharedPreferences sp = com.mg4.launcher.ui.Prefs.sp(c);
        if (version.equals(sp.getString(K_DONE_FOR, ""))) return;
        main.postDelayed(() -> {
            if (version.equals(sp.getString(K_DONE_FOR, "")) || running) return;
            sp.edit().putString(K_DONE_FOR, version).apply();
            run(c, false, null);
        }, 60_000);
    }
}
