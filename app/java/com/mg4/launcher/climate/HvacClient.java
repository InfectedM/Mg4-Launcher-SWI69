package com.mg4.launcher.climate;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * Climate reading and commands of the built-in climate screen (from MG4 Clima). On the SWI69 the link to the car is
 * the caradapter service ({@link CarAdapterBackend}, ICarHvacService), the same as the original climate app.
 * The commands here are "logical" (power, temperature, …): the backend translates them into the service codes.
 */
public final class HvacClient {

    // logical commands ("PRESS" = like pressing the button of the original app)
    public static final int SET_POWER_PRESS = 1, SET_AUTO_PRESS = 2, SET_AC_PRESS = 3, SET_LOOP_PRESS = 4,
            SET_ECON_PRESS = 5, SET_BLOWER_MODE = 6, SET_AIR_VOLUME = 7, SET_DRV_SEAT_PRESS = 8,
            SET_PSG_SEAT_PRESS = 9, SET_DRV_TEMP = 10, SET_WHEEL_PRESS = 11,
            FRONT_DEFROST_OPEN = 12, FRONT_DEFROST_CLOSE = 13, REAR_DEFROST_OPEN = 14, REAR_DEFROST_CLOSE = 15,
            // settings of the original climate app: value as the original
            SET_AUTO_LEVEL = 16, SET_ECON_LINK = 17, SET_DEFROST_LINK = 18,
            // temperature as the car's raw value, in tenths of a degree (only the LO / HI calibration)
            SET_DRV_TEMP_RAW = 19,
            // direct restore commands of the check (SAIC firmwares only: not used on the SWI69)
            DRV_SEAT_OFF = 20, PSG_SEAT_OFF = 21, LOOP_INNER = 22, LOOP_OUTSIDE = 23;

    /** AUTO intensity in the car's values: 2 low, 1 medium, 0 high (as the original app). */
    public static final int AUTO_LOW = 2, AUTO_MEDIUM = 1, AUTO_HIGH = 0;

    /** Fan in automatic (as the original climate apps). */
    public static final int FAN_AUTO = 15;

    /** State read from the car (-1 = not read). */
    public static final class State {
        public boolean connected;
        /** caradapter service: the climate only answers with the car on, there is no ignition to read. */
        public boolean caradapter = true;
        public int acAvailable = -1, ignition = -1, seatConfig = -1;
        public int power = -1, temp = -1, fan = -1, fanMin = 1, fanMax = 12;
        public int auto = -1, ac = -1, econ = -1, blowerMode = -1, loop = -1;
        public int frontDefrost = -1, rearDefrost = -1, wheel = -1, seatDrv = -1, seatPsg = -1;
        /** Settings of the original app: AUTO intensity 0..2, follow ECO 1 yes / 2 no, automatic rear defrost 0 yes / 1 no. */
        public int autoLevel = -1, econLink = -1, defrostLink = -1;
        public float outTemp = Float.NaN, speed = Float.NaN;
        /** Temperature: lowest value = "LO", highest = "HI". */
        public int tempMin = 17, tempMax = 33;
        /** LO / HI cannot be set on this car (the calibration found no value): from the numeric minimum to the maximum. */
        public boolean noLo, noHi;
        /** Temperature as the car sends it (before rounding), NaN if not read. */
        public float tempRaw = Float.NaN;

        /** Lowest value that can be set (LO, or the numeric minimum if there is no LO). */
        public int setMin() {
            return noLo ? tempMin + 1 : tempMin;
        }

        /** Highest value that can be set (HI, or the numeric maximum if there is no HI). */
        public int setMax() {
            return noHi ? tempMax - 1 : tempMax;
        }

        public boolean hasWheel = true, hasSeats = true;
        public String model = "", error = "";

        /** The climate can be used. */
        public boolean usable() {
            if (caradapter) return connected && power >= 0;
            return connected && acAvailable == 1 && (ignition == 2 || ignition == 3);
        }

        public boolean on() {
            return usable() && power == 1;
        }

        /** Air directions from the original's 0..6 code: {windscreen, face, feet}. */
        public boolean[] directions() {
            switch (blowerMode) {
                case 0: return new boolean[]{false, true, false};
                case 1: return new boolean[]{false, true, true};
                case 2: return new boolean[]{false, false, true};
                case 3: return new boolean[]{true, false, true};
                case 4: return new boolean[]{true, false, false};
                case 5: return new boolean[]{true, true, true};
                case 6: return new boolean[]{true, true, false};
                default: return new boolean[]{false, false, false};
            }
        }

        /** Heated seats present. */
        public boolean hasSeatHeat() {
            return hasSeats && seatConfig != 2;
        }

        /** Value the car does not provide (-1 / NaN) written plainly in the log. */
        private static String nv(int v) {
            return v < 0 ? "not provided" : String.valueOf(v);
        }

        private static String nv(float v) {
            return Float.isNaN(v) ? "not provided" : String.valueOf(v);
        }

        public String dump() {
            return model + " · connected " + connected + " · caradapter service"
                    + "\npower " + power + " · temperature " + temp + (Float.isNaN(tempRaw) ? "" : " (raw " + tempRaw + ")")
                    + " (" + tempMin + "=LO" + (noLo ? " hidden" : "") + ", " + tempMax + "=HI" + (noHi ? " hidden" : "") + ")"
                    + " · fan " + fan + " (" + fanMin + "–" + fanMax + ")"
                    + "\nAUTO " + auto + " · A/C " + ac + " · ECO " + econ + " · direction " + blowerMode + " · recirculation " + loop
                    + "\nAUTO intensity " + nv(autoLevel) + " · follow ECO " + nv(econLink) + " · automatic rear defrost " + nv(defrostLink)
                    + "\nwindscreen " + frontDefrost + " · rear window " + rearDefrost
                    + (hasWheel ? " · wheel " + wheel : "")
                    + (hasSeats ? "\nseats " + seatDrv + " / " + seatPsg + " (configuration " + seatConfig + ")" : "")
                    + " · outside " + nv(outTemp) + " · speed " + nv(speed)
                    + (error.isEmpty() ? "" : "\nerror: " + error);
        }
    }

    /** Changes since the previous reading, e.g. "temperature 22→20, fan 0→3" ("" if nothing). */
    static String diff(State a, State b) {
        if (a == null || !a.connected || !b.connected) return "";
        StringBuilder sb = new StringBuilder();
        d(sb, "car ignition", a.ignition, b.ignition);
        d(sb, "climate on", a.power, b.power);
        d(sb, "temperature", a.temp, b.temp);
        d(sb, "fan", a.fan, b.fan);
        d(sb, "AUTO", a.auto, b.auto);
        d(sb, "A/C", a.ac, b.ac);
        d(sb, "ECO", a.econ, b.econ);
        d(sb, "direction", a.blowerMode, b.blowerMode);
        d(sb, "recirculation", a.loop, b.loop);
        d(sb, "windscreen", a.frontDefrost, b.frontDefrost);
        d(sb, "rear window", a.rearDefrost, b.rearDefrost);
        d(sb, "wheel", a.wheel, b.wheel);
        d(sb, "driver seat", a.seatDrv, b.seatDrv);
        d(sb, "passenger seat", a.seatPsg, b.seatPsg);
        d(sb, "AUTO intensity", a.autoLevel, b.autoLevel);
        d(sb, "follow ECO", a.econLink, b.econLink);
        d(sb, "automatic rear defrost", a.defrostLink, b.defrostLink);
        return sb.toString();
    }

    private static void d(StringBuilder sb, String name, int x, int y) {
        if (x == y) return;
        sb.append(sb.length() == 0 ? "" : ", ").append(name).append(' ').append(x).append('→').append(y);
    }

    public interface Listener {
        void onState(State s);
    }

    public interface Result {
        void done(String error);   // null = command sent
    }

    private final Context ctx;
    private final CarAdapterBackend backend;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private Listener listener;
    private boolean running;
    private State prev;
    /** During the automatic test the changes are not logged as "changed by hand". */
    volatile boolean testing;

    public HvacClient(Context c) {
        ctx = c.getApplicationContext();
        backend = new CarAdapterBackend(ctx);
    }

    private void begin(String threadName, boolean poll) {
        running = true;
        thread = new HandlerThread(threadName);
        thread.start();
        bg = new Handler(thread.getLooper());
        backend.bind(() -> {
            Handler b = bg;
            if (listener != null && b != null) b.post(this::read);
        });
        if (poll) bg.post(tick);
    }

    public void start(Listener l) {
        listener = l;
        if (running) return;
        begin("mg4-climate", true);
    }

    /** Service link only, without periodic readings (for ClimateGuard: it only reads the power state). */
    public void startLight() {
        if (running) return;
        begin("mg4-climate-light", false);
    }

    public void stop() {
        running = false;
        if (bg != null) bg.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        backend.unbind();
    }

    public boolean running() {
        return running;
    }

    /** Climate on (1), off (0), -1 if it cannot be read. */
    public int readPower() {
        return backend.readPower();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            read();
            if (bg != null) bg.postDelayed(this, 1000);
        }
    };

    /** Sends a command (climate thread or automatic test). arg null = without arguments. */
    public void send(int cmd, Integer arg) throws Exception {
        backend.send(cmd, arg);
    }

    private void read() {
        State s = readNow();
        String ch = diff(prev, s);
        if (!ch.isEmpty()) ClimateStore.event(ctx, (testing ? "  · read: " : "changed: ") + ch);
        // raw value the car reports after every temperature change (to find the real range from the logs)
        if (prev != null && !Float.isNaN(s.tempRaw)
                && (Float.isNaN(prev.tempRaw) || Math.abs(s.tempRaw - prev.tempRaw) > 0.01f))
            ClimateStore.event(ctx, "climate.tempRaw = " + s.tempRaw + " (before " + prev.tempRaw + ") · shown " + s.temp
                    + " · range in use " + CarAdapterBackend.rangeText());
        prev = s;
        final State st = s;
        ClimateStore.diag(st.dump());
        main.post(() -> {
            if (listener != null) listener.onState(st);
        });
    }

    /** Command from the screen: on the climate thread, then a quick re-read of the state. */
    public void command(String what, int code, Integer arg, Result r) {
        Handler b = bg;
        if (b == null) return;
        b.post(() -> {
            String err = null;
            try {
                send(code, arg);
                ClimateStore.event(ctx, "command: " + what + (arg == null ? "" : " (" + arg + ")"));
            } catch (Throwable e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                ClimateStore.event(ctx, "command failed: " + what + " · " + err);
            }
            b.postDelayed(this::read, 250);
            b.postDelayed(this::read, 700);
            final String fe = err;
            if (r != null) main.post(() -> r.done(fe));
        });
    }

    /** Reads the whole state right away (from any thread). */
    public State readNow() {
        State s = backend.empty();
        s.model = "SWI69";
        try {
            backend.read(s);
        } catch (Throwable e) {
            s.error = e.toString();
        }
        return s;
    }
}
