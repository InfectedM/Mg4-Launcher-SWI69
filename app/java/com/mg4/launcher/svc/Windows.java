package com.mg4.launcher.svc;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.widget.Toast;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.ui.Prefs;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Windows: automatic closing when leaving the car and manual closing (WinClose features, rewritten for the launcher).
 * <p>
 * Connection to the car (SWI69, EH32):
 * <ul>
 *   <li>READY and gear: ICarStateService (hub com.saicmotor.caradapter, code 11) with direct binder calls,
 *       like {@link VehicleClient};</li>
 *   <li>windows: CarVehicleSettingClient (code 8) from SAIC's carapi libraries, loaded from the original home app
 *       (com.saicmotor.launcher on the SWI69): setVehicleWindowStatus(area, command) and getVehicleWindowValue(area)
 *       (0 = closed … 255 = open, only reliable on windows with a sensor);</li>
 *   <li>driver's door: ICarStateCallback callback registered on CarStateClient (transaction 6, 0 = open;
 *       transaction 3 = gear).</li>
 * </ul>
 * Always runs, even with the launcher in the background (the home process is not killed), on its own thread.
 */
public final class Windows {
    private static final String HUB_PKG = "com.saicmotor.caradapter";
    private static final String HUB_ACTION = "com.saicmotor.caradapter.CarAdapterService";
    private static final String HUB = "com.saicmotor.carapi.ICarAdapterService";
    private static final int HUB_QUERY_CLIENT = 1, BIND_VS = 8, BIND_STATE = 11;
    private static final String STATE_DESC = "com.saicmotor.carapi.carstate.ICarStateService";
    private static final int ST_SERVICE_READY = 3, ST_GEAR = 5, ST_EPTRDY = 8;

    private static final String VS_CLIENT = "com.saicmotor.carapi.client.CarVehicleSettingClient";
    private static final String STATE_CLIENT = "com.saicmotor.carapi.client.CarStateClient";
    private static final String STATE_CB_DESC = "com.saicmotor.carapi.carstate.ICarStateCallback";
    private static final int CB_GEAR = 3, CB_DOOR = 6;
    /** Apps that contain the carapi libraries (the first is the SWI69 one). */
    private static final String[] SAIC_PKGS = {"com.saicmotor.launcher", "com.saicmotor.hmi.launcher", "com.saicmotor.carcontrol",
            "com.saicmotor.settings", "com.saicmotor.hmi", "com.saicmotor.voiceservice", "com.saicmotor.telematics"};

    /** Window commands: stop, up (while held), auto up. */
    private static final int CMD_STOP = 0, CMD_UP = 1, CMD_AUTO_UP = 3;
    private static final long PULSE_MS = 5000, PULSE_STEP_MS = 120;
    /**
     * Loop pace, depending on what can happen next (it used to be 500 ms always, even with the feature off):
     * countdown running 0.5 s (beep and window moved by hand); armed in P waiting for the door 1 s; in READY 2 s (arming by
     * speed, gear); idle 5 s (only to catch the READY of a new trip). With auto-close off the car state is not
     * read: only the window control is prepared for the "Close now" button (15 s until ready,
     * then 60 s). The door arrives via callback and wakes the loop immediately.
     */
    private static final long LOOP_COUNTING_MS = 500, LOOP_ARMED_P_MS = 1000, LOOP_READY_MS = 2000, LOOP_IDLE_MS = 5000,
            LOOP_OFF_PENDING_MS = 15_000, LOOP_OFF_MS = 60_000;

    private static Windows instance;

    public static synchronized Windows get(Context c) {
        if (instance == null) instance = new Windows(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Prefs prefs;
    private final ServiceLink hub;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowLogic logic;
    private Handler bg;
    private boolean started;

    // carapi libraries (loaded once)
    private ClassLoader saicCl;
    private String saicPkg;
    private long saicRetryAt;
    private boolean saicScanned;
    private volatile Object vsClient;
    private volatile Method setWin, getWin;
    /**
     * 0.4.8: on some SWI69 firmwares CarVehicleSettingClient no longer has setVehicleWindowStatus(int,int)
     * (NoSuchMethodException in the car). There the windows are driven as in MG4Control (SliDeeN): one float property
     * per window in the CarService (VEHICLE_DRIVERWINDOW…), same values 0 stop, 1 up, 3 auto up.
     */
    private static final int[] WIN_PROPS = {0x11603801, 0x11603802, 0x11603803, 0x11603804};
    private static final int AREA_SAIC = 0x1000000;
    private volatile boolean propMode;
    private volatile int propArea = AREA_SAIC;
    private volatile boolean carapiNoMethod;
    private boolean stateCbRegistered;
    private long stateCbRetryAt;

    private volatile boolean closing;
    private volatile String lastEvent = "";
    private float[] baseline;
    private ToneGenerator tone;
    private long nextBeep;

    private Windows(Context c) {
        ctx = c;
        prefs = new Prefs(c);
        hub = new ServiceLink(c, "windows", ServiceLink.byAction(HUB_PKG, HUB_ACTION));
        hub.setOnConnected(() -> {
            if (bg != null) {
                bg.post(this::forgetClients);
                wake();
            }
        });
        logic = new WindowLogic(new WindowLogic.Events() {
            @Override
            public void countdown(int seconds) {
                baseline = positions();
                nextBeep = 0;
                toast(L.f(R.string.win_toast_countdown, seconds));
            }

            @Override
            public void cancelled(String reason) {
                baseline = null;
                stopBeep();
                toast(L.s(R.string.win_toast_cancel));
            }

            @Override
            public void close() {
                baseline = null;
                stopBeep();
                closeAll("automatic");
            }

            @Override
            public void log(String msg) {
                lastEvent = msg;
                Diag.put("windows.event", msg);
            }
        }, this::config, this::allSurelyClosed);
    }

    /** Starts watching the car (once per process). */
    public synchronized void start() {
        if (started) return;
        started = true;
        HandlerThread th = new HandlerThread("mg4-windows");
        th.start();
        bg = new Handler(th.getLooper());
        hub.start();
        bg.post(loop);
    }

    private WindowLogic.Config config() {
        WindowLogic.Config c = new WindowLogic.Config();
        c.enabled = prefs.winAuto();
        c.speedKmh = prefs.winSpeed();
        c.armMinutes = prefs.winMinutes();
        c.delaySec = prefs.winDelay();
        c.onDoorOpen = prefs.winOnDoorOpen();
        return c;
    }

    // =========================================================================================
    // State for the settings
    // =========================================================================================

    /** Window control is available (carapi libraries loaded and car service connected). */
    public boolean available() {
        if (propMode) return propsReady();
        return vsClient != null && setWin != null && hub.isConnected();
    }

    private static boolean propsReady() {
        CarProps p = VehicleClient.carProps();
        return p != null && p.ready();
    }

    /** Status line for the Windows section. */
    public String statusText() {
        if (!available()) {
            // the car answered but neither path works: not "waiting", but lack of support
            if (carapiNoMethod && !propsReady()) return L.s(R.string.win_status_off);
            if (carapiNoMethod) return L.s(R.string.win_status_unsupported);
            return L.s(R.string.win_status_off);
        }
        if (closing) return L.s(R.string.win_closing);
        if (logic.counting()) return L.f(R.string.win_status_counting, logic.secondsLeft(SystemClock.uptimeMillis()));
        if (!prefs.winAuto()) return L.s(R.string.win_status_ok);
        return logic.armed() ? L.s(R.string.win_status_armed)
                : L.f(R.string.win_status_not_armed, prefs.winSpeed(), prefs.winMinutes());
    }

    /** Closes now all windows not set to "Off". False if there is no window control. */
    public boolean closeNow() {
        if (bg == null || !available()) return false;
        bg.post(() -> {
            logic.cancel("manual close");
            closeAll("manual");
        });
        return true;
    }

    // =========================================================================================
    // Loop
    // =========================================================================================

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (Throwable e) {
                Diag.error("windows", e.toString());
            }
            bg.postDelayed(this, nextDelayMs());
        }
    };

    private long nextDelayMs() {
        if (!prefs.winAuto()) return available() ? LOOP_OFF_MS : LOOP_OFF_PENDING_MS;
        if (logic.counting()) return LOOP_COUNTING_MS;
        if (logic.armed() && logic.gear() == WindowLogic.GEAR_P) return LOOP_ARMED_P_MS;
        if (logic.ready() == 1) return LOOP_READY_MS;
        return LOOP_IDLE_MS;
    }

    /** Runs the loop now (door, car connection, option changed in settings) instead of waiting for the interval. */
    public void wake() {
        Handler h = bg;
        if (h == null) return;
        h.post(() -> {
            h.removeCallbacks(loop);
            h.post(loop);
        });
    }

    private void step() {
        long now = SystemClock.uptimeMillis();
        IBinder h = hub.binder();
        if (h == null) return;
        if (!prefs.winAuto()) {
            // auto-close off: no car reads, only the control for the "Close now" button
            if (logic.counting()) logic.cancel("auto-close off");
            ensureClients(h, null, now);
            Diag.putQuiet("windows.state", (available() ? "control ok (" + saicPkg + ")" : "no control") + " · auto-close off");
            return;
        }
        IBinder st = query(h, BIND_STATE);
        if (st != null) {
            try {
                Boolean ok = Rpc.call(st, STATE_DESC, ST_SERVICE_READY, Rpc.NONE, p -> p.readInt() != 0);
                if (ok != null && ok) {
                    logic.onReady(Rpc.callInt(st, STATE_DESC, ST_EPTRDY), now);
                    int g = Rpc.callInt(st, STATE_DESC, ST_GEAR);
                    if (g >= 0 && g <= 11) logic.onGear(g, now);
                }
            } catch (Exception e) {
                Diag.error("windows.state", e.toString());
            }
        }
        ensureClients(h, st, now);
        logic.tick(VehicleClient.speedKmh(), now);
        if (logic.counting()) {
            beep(now);
            if (windowMoved()) logic.cancel("window moved by hand");
        }
        Diag.putQuiet("windows.state", (available() ? "control ok (" + saicPkg + ")" : "no control")
                + " · door callback " + (stateCbRegistered ? "yes" : "no")
                + " · READY " + logic.ready() + " · gear " + logic.gear() + " · door " + logic.door()
                + " · armed " + (logic.armed() ? "yes" : "no") + (logic.counting() ? " · countdown" : ""));
    }

    private IBinder query(IBinder h, int code) {
        try {
            return Rpc.callBinder(h, HUB, HUB_QUERY_CLIENT, p -> p.writeInt(code));
        } catch (Exception e) {
            return null;
        }
    }

    private void forgetClients() {
        propMode = false;
        vsClient = null;
        setWin = null;
        getWin = null;
        stateCbRegistered = false;
        saicRetryAt = 0;
        stateCbRetryAt = 0;
    }

    // =========================================================================================
    // SAIC carapi libraries
    // =========================================================================================

    private void ensureClients(IBinder h, IBinder st, long now) {
        if (carapiNoMethod && !propMode) tryPropMode();
        if (saicCl == null) {
            if (now < saicRetryAt) return;
            saicRetryAt = now + 30_000;
            loadSaic();
            if (saicCl == null) return;
        }
        if (vsClient == null && !propMode && !carapiNoMethod) {
            IBinder vs = query(h, BIND_VS);
            if (vs != null) {
                try {
                    Class<?> cls = saicCl.loadClass(VS_CLIENT);
                    Object client = cls.getConstructor(IBinder.class).newInstance(vs);
                    setWin = cls.getMethod("setVehicleWindowStatus", int.class, int.class);
                    try {
                        getWin = cls.getMethod("getVehicleWindowValue", int.class);
                    } catch (NoSuchMethodException e) {
                        getWin = null;
                    }
                    vsClient = client;
                    StringBuilder pos = new StringBuilder();
                    for (int a = 0; a < 4; a++) pos.append(a == 0 ? "" : " ").append(position(a));
                    Diag.put("windows.control", "ready (" + saicPkg + ") · positions " + pos);
                } catch (NoSuchMethodException e) {
                    // firmware without the old function: switch to the CarService (only once, without repeating the error every 2 s)
                    carapiNoMethod = true;
                    Diag.put("windows.carapi.methods", windowMethods());
                    Diag.error("windows.control", "carapi without " + e.getMessage() + " → CarService properties");
                    tryPropMode();
                } catch (Throwable e) {
                    Diag.error("windows.control", e.toString());
                }
            }
        }
        if (!stateCbRegistered && st != null && now >= stateCbRetryAt) {
            stateCbRetryAt = now + 10_000;
            registerStateCallback(st);
        }
    }

    /** Enables CarService mode if the service is already ready (otherwise retries on the next loop). */
    private void tryPropMode() {
        if (propMode || !propsReady()) return;
        CarProps p = VehicleClient.carProps();
        boolean listed = p.has(WIN_PROPS[0]);
        propMode = true;
        StringBuilder pos = new StringBuilder();
        for (int a = 0; a < 4; a++) pos.append(a == 0 ? "" : " ").append(position(a));
        Diag.put("windows.control", "ready (CarService, like MG4Control) · property "
                + (listed ? "in the car's list" : "not in the car's list") + " · positions " + pos);
    }

    /** Methods with "Window" of this firmware's carapi client (for the diagnostics). */
    private String windowMethods() {
        try {
            StringBuilder b = new StringBuilder();
            for (Method m : saicCl.loadClass(VS_CLIENT).getMethods()) {
                if (!m.getName().toLowerCase(java.util.Locale.ROOT).contains("window")) continue;
                if (b.length() > 0) b.append("; ");
                b.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) b.append(i == 0 ? "" : ",").append(ps[i].getSimpleName());
                b.append(')');
                if (b.length() > 400) break;
            }
            return b.length() == 0 ? "none" : b.toString();
        } catch (Throwable e) {
            return e.toString();
        }
    }

    /**
     * Code is loaded only from SYSTEM apps (installed in the car image): a user-installed app with a
     * com.saicmotor.* name never runs inside the launcher (which has system permissions).
     */
    private boolean isSystemApp(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Looks for the carapi classes: first in the known apps, then (once) in the system com.saicmotor.* ones. */
    private void loadSaic() {
        Set<String> pkgs = new LinkedHashSet<>();
        for (String p : SAIC_PKGS) pkgs.add(p);
        if (!saicScanned) {
            saicScanned = true;
            try {
                for (android.content.pm.PackageInfo pi : ctx.getPackageManager().getInstalledPackages(0))
                    if (pi.packageName != null && pi.packageName.startsWith("com.saicmotor.")) pkgs.add(pi.packageName);
            } catch (Throwable ignored) {
            }
        }
        StringBuilder why = new StringBuilder();
        for (String p : pkgs) {
            if (!isSystemApp(p)) {
                if (why.length() < 300) why.append(p).append(": not a system app; ");
                continue;
            }
            try {
                // CONTEXT_IGNORE_SECURITY is required to load another app's code; only system code gets here
                Context pc = ctx.createPackageContext(p, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                ClassLoader cl = pc.getClassLoader();
                cl.loadClass(VS_CLIENT);
                saicCl = cl;
                saicPkg = p;
                Diag.put("windows.carapi", "found in " + p);
                return;
            } catch (Throwable e) {
                if (why.length() < 300) why.append(p).append(": ").append(e.getClass().getSimpleName()).append("; ");
            }
        }
        Diag.put("windows.carapi", "not found (" + why + ")");
    }

    /** Car state callback: driver's door (and gear), delivered on the windows thread. */
    private void registerStateCallback(IBinder st) {
        try {
            Class<?> cls = saicCl.loadClass(STATE_CLIENT);
            Object client = cls.getConstructor(IBinder.class).newInstance(st);
            Method reg = null;
            for (Method m : cls.getMethods()) {
                if ((m.getName().equals("registerListener") || m.getName().equals("registListener")) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].isInterface()) {
                    reg = m;
                    break;
                }
            }
            if (reg == null) {
                Diag.put("windows.door", "registerListener does not exist in CarStateClient");
                return;
            }
            Binder cb = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    try {
                        data.enforceInterface(STATE_CB_DESC);
                        if (code == CB_DOOR || code == CB_GEAR) {
                            int v = data.readInt();
                            bg.post(() -> {
                                long now = SystemClock.uptimeMillis();
                                if (code == CB_DOOR) logic.onDoor(v, now);
                                else logic.onGear(v, now);
                            });
                            wake();   // a countdown may have started: the loop switches to 0.5 s right away
                        }
                    } catch (Throwable ignored) {
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
            };
            Class<?> cbType = reg.getParameterTypes()[0];
            Object proxy = Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType}, (o, m, args) -> {
                if (m.getName().equals("asBinder")) return cb;
                Class<?> r = m.getReturnType();
                if (r == int.class) return 0;
                if (r == boolean.class) return false;
                if (r == float.class) return 0f;
                if (r == long.class) return 0L;
                if (r == double.class) return 0d;
                return null;
            });
            reg.invoke(client, proxy);
            stateCbRegistered = true;
            Diag.put("windows.door", "callback registered (" + reg.getName() + ")");
        } catch (Throwable e) {
            Diag.error("windows.door", e.toString());
        }
    }

    // =========================================================================================
    // Windows
    // =========================================================================================

    /** Position of a window (0 closed … 255 open), -1 if not readable. */
    private float position(int area) {
        if (propMode) {
            // only the driver's window has a sensor (0 closed … 100 open); the others give fixed 127.5 / 255
            CarProps p = VehicleClient.carProps();
            if (p == null) return -1f;
            float v = p.getFloatAt(WIN_PROPS[area], propArea);
            if (Float.isNaN(v) && propArea != 0) v = p.getFloatAt(WIN_PROPS[area], 0);
            return v >= 0f && v <= 100f ? v : -1f;
        }
        if (vsClient == null || getWin == null) return -1f;
        try {
            Object v = getWin.invoke(vsClient, area);
            return v instanceof Number ? ((Number) v).floatValue() : -1f;
        } catch (Throwable e) {
            return -1f;
        }
    }

    private float[] positions() {
        float[] p = new float[4];
        for (int a = 0; a < 4; a++) p[a] = prefs.winMode(a) == Prefs.WIN_AUTO ? position(a) : -1f;
        return p;
    }

    /** Only AUTO windows have a sensor: if all are AUTO and read as closed, there is nothing to do. */
    private boolean allSurelyClosed() {
        for (int a = 0; a < 4; a++) {
            if (prefs.winMode(a) != Prefs.WIN_AUTO) return false;
            float p = position(a);
            if (p < 0f || p > 0.5f) return false;
        }
        return true;
    }

    /** During the countdown: a window with a sensor that moves means someone is using the buttons. */
    private boolean windowMoved() {
        float[] b = baseline;
        if (b == null) return false;
        for (int a = 0; a < 4; a++) {
            if (b[a] < 0f) continue;
            float now = position(a);
            if (now >= 0f && Math.abs(now - b[a]) > 2f) return true;
        }
        return false;
    }

    private void cmd(int area, int c) {
        if (propMode) {
            CarProps p = VehicleClient.carProps();
            if (p == null) return;
            try {
                p.setFloatAt(WIN_PROPS[area], propArea, c);
            } catch (Throwable e) {
                if (propArea == 0) {
                    Diag.error("windows.command", "window " + area + " → " + c + ": " + e);
                    return;
                }
                // SAIC services area rejected: try area 0 (like MG4Control) and keep it if it works
                try {
                    p.setFloatAt(WIN_PROPS[area], 0, c);
                    propArea = 0;
                    Diag.put("windows.area", "0x1000000 refused (" + e + ") → area 0");
                } catch (Throwable e2) {
                    Diag.error("windows.command", "window " + area + " → " + c + ": " + e2);
                }
            }
            return;
        }
        try {
            setWin.invoke(vsClient, area, c);
        } catch (Throwable e) {
            Diag.error("windows.command", "window " + area + " → " + c + ": " + e);
        }
    }

    /** Closes the windows according to each one's mode: AUTO = auto up; PULSE = "up" held for 5 s; OFF = nothing. */
    private void closeAll(String why) {
        if (closing) return;
        if (!available()) {
            Diag.put("windows.autoClose", why + ": no window control");
            return;
        }
        closing = true;
        new Thread(() -> {
            StringBuilder s = new StringBuilder(why + ":");
            try {
                java.util.List<Integer> hold = new java.util.ArrayList<>();
                for (int a = 0; a < 4; a++) {
                    int m = prefs.winMode(a);
                    if (m == Prefs.WIN_AUTO) {
                        cmd(a, CMD_AUTO_UP);
                        s.append(" ").append(a).append("=auto");
                    } else if (m == Prefs.WIN_PULSE) {
                        hold.add(a);
                        s.append(" ").append(a).append("=pulse");
                    } else {
                        s.append(" ").append(a).append("=off");
                    }
                }
                if (!hold.isEmpty()) {
                    long end = SystemClock.uptimeMillis() + PULSE_MS;
                    while (SystemClock.uptimeMillis() < end) {
                        for (int a : hold) cmd(a, CMD_UP);
                        SystemClock.sleep(PULSE_STEP_MS);
                    }
                    for (int a : hold) cmd(a, CMD_STOP);
                }
                Diag.put("windows.autoClose", s.toString());
            } finally {
                closing = false;
            }
        }, "mg4-windows-close").start();
    }

    // =========================================================================================
    // Warning: beep and message
    // =========================================================================================

    private void beep(long now) {
        if (!prefs.winBeep() || now < nextBeep) return;
        nextBeep = now + 1000;
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, prefs.winBeepVolume());
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
        } catch (Throwable e) {
            Diag.error("windows.beep", e.toString());
        }
    }

    private void stopBeep() {
        if (tone != null) {
            try {
                tone.release();
            } catch (Throwable ignored) {
            }
            tone = null;
        }
    }

    private void toast(String text) {
        main.post(() -> {
            try {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }
}
