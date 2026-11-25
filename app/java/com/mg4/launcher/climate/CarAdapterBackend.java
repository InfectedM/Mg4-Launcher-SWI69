package com.mg4.launcher.climate;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.mg4.launcher.svc.Rpc;

/**
 * SWI69: "caradapter" service (com.saicmotor.caradapter, ICarHvacService), the same as the original climate app
 * (com.saicmotor.hvac, A9_Hvac) and the launcher's climate bar (already verified in the cars).
 * Power, A/C, AUTO, ECO, recirculation and defrosts are "toggle" (no arguments); temperature, fan and air direction
 * have a value. Heated seats and steering wheel are not there (they are hidden).
 */
final class CarAdapterBackend {
    private static final String TAG = "MG4Climate";
    private static final String PKG = "com.saicmotor.caradapter";
    private static final String ACTION = "com.saicmotor.caradapter.CarAdapterService";
    private static final String HUB = "com.saicmotor.carapi.ICarAdapterService";
    private static final int HUB_QUERY_CLIENT = 1, BIND_HVAC = 7;

    private static final String AC = "com.saicmotor.carapi.hvac.ICarHvacService";
    private static final int GET_POWER = 6, GET_AC = 8, GET_AUTO = 10, GET_ECO = 14, GET_LOOP = 16, GET_DIRECTION = 18,
            GET_FAN = 20, GET_DRV_TEMP = 22, GET_OUT_TEMP = 25, GET_FRONT_DEFROST = 28, GET_REAR_DEFROST = 30;
    private static final int SET_DRV_TEMP = 21, SET_DIRECTION = 17, SET_FAN = 19;
    /** Settings of the original SWI69 climate app (ICarHvacService, verified on several SWI69 builds). */
    private static final int SET_AUTO_LEVEL = 39, GET_AUTO_LEVEL = 40, SET_ECON_LINK = 68, GET_ECON_LINK = 69;

    private static int code(int cmd) {
        switch (cmd) {
            case HvacClient.SET_POWER_PRESS: return 5;
            case HvacClient.SET_AC_PRESS: return 7;
            case HvacClient.SET_AUTO_PRESS: return 9;
            case HvacClient.SET_ECON_PRESS: return 13;
            case HvacClient.SET_LOOP_PRESS: return 15;
            case HvacClient.SET_BLOWER_MODE: return SET_DIRECTION;
            case HvacClient.SET_AIR_VOLUME: return SET_FAN;
            case HvacClient.SET_DRV_TEMP:
            case HvacClient.SET_DRV_TEMP_RAW: return SET_DRV_TEMP;
            case HvacClient.FRONT_DEFROST_OPEN:
            case HvacClient.FRONT_DEFROST_CLOSE: return 27;
            case HvacClient.REAR_DEFROST_OPEN:
            case HvacClient.REAR_DEFROST_CLOSE: return 29;
            default: return -1;   // seats and wheel: not present
        }
    }

    /**
     * MG4 EU/UK: 16..30 °C, the car uses 15.5 = LO and 30.5 = HI. Here: 15 = LO, 31 = HI (fallback).
     * The service does not give the limits: the calibration measures them (some SWI69 cars use 17 = LO and 33 = HI,
     * and on SWI69 R33 15.5 comes back as 31). See AutoTest.calibrateLow/High.
     */
    private static final int TEMP_MIN = 15, TEMP_MAX = 31;
    private static final float CAR_LO = 15.5f, CAR_HI = 30.5f;

    /** Range in use: LO and HI as they are sent and as the car reads them back; tMin/tMax = LO/HI on the screen. */
    private static volatile float loSend = CAR_LO, loRead = CAR_LO, hiSend = CAR_HI, hiRead = CAR_HI;
    private static volatile int tMin = TEMP_MIN, tMax = TEMP_MAX, fanMaxCal = -1;
    private static volatile boolean noLo, noHi;
    private static volatile String source = "fallback";

    static int tempFromCar(float f) {
        if (Float.isNaN(f) || f <= 0) return -1;
        if (f <= loRead + 0.01f) return tMin;
        if (f >= hiRead - 0.01f) return tMax;
        return Math.max(tMin + 1, Math.min(tMax - 1, Math.round(f)));
    }

    static float tempToCar(int v) {
        if (v <= tMin) return loSend;
        if (v >= tMax) return hiSend;
        return v;
    }

    /** Values tried by the calibration: LO going down, HI going up; it stops at the first one the car refuses. */
    static final float[] LO_TRY = {17f, 16f, 15.5f, 15f}, HI_TRY = {30.5f, 31f, 32f, 33f};

    /** Reads from the settings the range measured for this version of the original climate app. */
    static void loadLimits(Context c) {
        String ver = ClimateGuard.originalVersion(c);
        float[] r = ClimateStore.tempRange(c, ver);
        if (r != null) {
            noLo = Float.isNaN(r[0]);
            noHi = Float.isNaN(r[2]);
            loSend = noLo ? CAR_LO : r[0];
            loRead = noLo ? CAR_LO : r[1];
            hiSend = noHi ? CAR_HI : r[2];
            hiRead = noHi ? CAR_HI : r[3];
            fanMaxCal = (int) r[4];
            source = "calibration";
        } else {
            noLo = noHi = false;
            loSend = loRead = CAR_LO;
            hiSend = hiRead = CAR_HI;
            fanMaxCal = -1;
            source = "fallback";
        }
        tMin = noLo ? TEMP_MIN : (int) Math.floor(loRead + 0.001f);
        tMax = noHi ? TEMP_MAX : (int) Math.ceil(hiRead - 0.001f);
        if (tMax - tMin < 6) {   // absurd values: fallback
            tMin = TEMP_MIN;
            tMax = TEMP_MAX;
            loSend = loRead = CAR_LO;
            hiSend = hiRead = CAR_HI;
            source = "fallback (invalid measurement)";
        }
    }

    static String limitsText() {
        return "LO " + (noLo ? "hidden" : String.valueOf(loSend)) + " · HI " + (noHi ? "hidden" : String.valueOf(hiSend));
    }

    /** climate.range line: numeric minimum and maximum, LO, HI (sent → read back) and where they come from. */
    static String rangeText() {
        return "minimum " + (tMin + 1) + " · maximum " + (tMax - 1)
                + " · LO " + (noLo ? "hidden" : tMin + " (" + loSend + " → " + loRead + ")")
                + " · HI " + (noHi ? "hidden" : tMax + " (" + hiSend + " → " + hiRead + ")")
                + (fanMaxCal > 0 ? " · fan 1–" + fanMaxCal : "")
                + " · source " + source + " (the service does not give the limits)";
    }

    private final Context ctx;
    private final Speed speed;
    private volatile IBinder hub, hvac;
    private boolean bound;
    private Runnable onConnected;
    private int settingsFails;
    private boolean settingsBroken;

    CarAdapterBackend(Context c) {
        ctx = c;
        loadLimits(c);
        speed = new Speed(c);
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            hub = service;
            hvac = null;
            Log.i(TAG, "caradapter connected");
            Runnable r = onConnected;
            if (r != null) r.run();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            hub = null;
            hvac = null;
        }
    };

    void bind(Runnable onConnected) {
        this.onConnected = onConnected;
        try {
            bound = ctx.bindService(new Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            bound = false;
            Log.e(TAG, "bind: " + e);
        }
        speed.connect();
    }

    void unbind() {
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (Throwable ignored) {
            }
        }
        bound = false;
        hub = null;
        hvac = null;
    }

    private IBinder ac() throws Exception {
        IBinder b = hvac;
        if (b != null && b.isBinderAlive()) return b;
        IBinder h = hub;
        if (h == null) return null;
        b = Rpc.callBinder(h, HUB, HUB_QUERY_CLIENT, p -> p.writeInt(BIND_HVAC));
        hvac = b;
        return b;
    }

    /** Empty state with the model's limits. */
    HvacClient.State empty() {
        HvacClient.State s = new HvacClient.State();
        s.caradapter = true;
        s.seatConfig = 2;
        s.fanMin = 1;
        s.fanMax = fanMaxCal >= 4 && fanMaxCal <= 11 ? fanMaxCal : 11;   // cars with fan 1–8
        s.tempMin = tMin;
        s.tempMax = tMax;
        s.noLo = noLo;
        s.noHi = noHi;
        s.hasWheel = false;
        s.hasSeats = false;
        return s;
    }

    /** 1 on, 0 off, -1 cannot be read. */
    int readPower() {
        try {
            IBinder a = ac();
            return a == null ? -1 : Rpc.callBool(a, AC, GET_POWER) ? 1 : 0;
        } catch (Throwable e) {
            hvac = null;
            return -1;
        }
    }

    void send(int cmd, Integer arg) throws Exception {
        if (cmd == HvacClient.SET_AUTO_LEVEL || cmd == HvacClient.SET_ECON_LINK) {
            IBinder a = ac();
            if (a == null) throw new IllegalStateException("climate service not connected");
            int val = arg == null ? 0 : arg;
            Rpc.call(a, AC, cmd == HvacClient.SET_AUTO_LEVEL ? SET_AUTO_LEVEL : SET_ECON_LINK, p -> p.writeInt(val));
            return;
        }
        int code = code(cmd);
        if (code < 0) throw new IllegalStateException("command not present on this car");
        IBinder a = ac();
        if (a == null) throw new IllegalStateException("climate service not connected");
        switch (code) {
            case SET_DRV_TEMP:
                if (cmd == HvacClient.SET_DRV_TEMP_RAW) {   // raw value (tenths of a degree), calibration only
                    final float raw = (arg == null ? 220 : arg) / 10f;
                    Rpc.call(a, AC, code, p -> p.writeFloat(raw));
                } else {
                    Rpc.call(a, AC, code, p -> p.writeFloat(tempToCar(arg == null ? 22 : arg)));
                }
                break;
            case SET_DIRECTION:
            case SET_FAN:
                Rpc.call(a, AC, code, p -> p.writeInt(arg == null ? 0 : arg));
                break;
            default:
                Rpc.call(a, AC, code, Rpc.NONE);   // "toggle"
        }
    }

    /** Reads the whole state (climate thread). */
    void read(HvacClient.State s) throws Exception {
        try {
            IBinder a = ac();
            if (a != null) {
                s.connected = true;
                s.acAvailable = 1;
                s.ignition = 2;   // the ignition is not read here: the climate only answers with the car on
                boolean power = Rpc.callBool(a, AC, GET_POWER);
                s.power = power ? 1 : 0;
                s.tempRaw = Rpc.callFloat(a, AC, GET_DRV_TEMP);
                s.temp = tempFromCar(s.tempRaw);
                s.fan = Rpc.callInt(a, AC, GET_FAN);
                s.auto = Rpc.callBool(a, AC, GET_AUTO) ? 1 : 0;
                if (s.auto == 1 && power && s.fan == 0) s.fan = HvacClient.FAN_AUTO;
                s.ac = Rpc.callBool(a, AC, GET_AC) ? 1 : 0;
                s.econ = Rpc.callBool(a, AC, GET_ECO) ? 1 : 0;
                s.blowerMode = Rpc.callInt(a, AC, GET_DIRECTION);
                s.loop = Rpc.callInt(a, AC, GET_LOOP);
                s.frontDefrost = Rpc.callBool(a, AC, GET_FRONT_DEFROST) ? 1 : 0;
                s.rearDefrost = Rpc.callBool(a, AC, GET_REAR_DEFROST) ? 1 : 0;
                float out = Rpc.callFloat(a, AC, GET_OUT_TEMP);
                s.outTemp = out < -100 || out > 100 ? Float.NaN : out;
                if (!settingsBroken) {
                    try {
                        int lv = Rpc.callInt(a, AC, GET_AUTO_LEVEL), ec = Rpc.callInt(a, AC, GET_ECON_LINK);
                        s.autoLevel = lv >= 0 && lv <= 2 ? lv : -1;
                        s.econLink = ec == 1 || ec == 2 ? ec : -1;
                    } catch (Throwable t) {
                        if (++settingsFails >= 5) settingsBroken = true;
                        ClimateStore.event(ctx, "original climate settings not readable: " + t);
                    }
                }
            }
        } catch (Exception e) {
            hvac = null;
            throw e;
        } finally {
            s.speed = speed.kmh();
        }
    }
}
