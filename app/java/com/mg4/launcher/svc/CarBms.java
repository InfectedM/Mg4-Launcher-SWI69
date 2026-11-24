package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * SAIC battery properties read from the Android CarService ("bms" manager, like the SWI69 Energy app).
 * The android.car library is in the system classpath: it is used via reflection, without bundling it in the APK.
 * IDs taken from android.car.hardware.CarBMSManager of the SWI69 firmware.
 */
public final class CarBms {
    public static final int CHRG_TRGT_SOC = 557904908;      // int, code 1..7 (40%..100%), read and command
    public static final int ALTNG_CHRG_CRNT = 557904907;    // int, AC current 1..4 (6 A, 8 A, 16 A, Max), read and command
    public static final int CHRG_CTRL = 557904914;          // int, 1 start, 2 stop (like Charge management)
    public static final int V2X = 557904944;                // int, V2L discharge: 1 start, 2 stop
    public static final int V2X_SP_SOC = 557904943;         // int, V2L discharge limit (code)
    public static final int PACK_SOC_DSP = 560002052;       // float, displayed %
    public static final int PACK_VOL = 560002054;           // float, V
    public static final int PACK_CRNT = 560002055;          // float, A
    public static final int ESTD_ELEC_RNG = 557904918;      // int, km
    public static final int CHRG_LOCK = 557904915;          // int, plug lock: 1 locked, 2 unlocked (command: 2)
    public static final int CHRG_STS = 557904905;           // int, BMS charge state
    /** 0.4.2 (point 7d): scheduled charging, like the EH32 Charge management of the Energy app (CarBMSManager.ID_RESER_*):
     *  RESER_CTRL 1 = on (start and end time), 2 = off, 3 = on and continues up to the limit after the end time. */
    public static final int RESER_CTRL = 557904909, RESER_ST_HOUR = 557904910, RESER_ST_MIN = 557904911,
            RESER_SP_HOUR = 557904912, RESER_SP_MIN = 557904913;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Object car;
    private volatile Object bms;
    private volatile Method getProperty, setProperty;
    /** Properties the car returns empty or does not support: no longer re-read (no repeated errors). */
    private final java.util.Set<Integer> unsupported = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** CarService "hvac" manager (cabin temperature). */
    private volatile Object hvac;
    private volatile Method hvacGetFloat;
    /** CarService "property" manager (generic properties, e.g. tyre pressure). */
    private volatile Object prop;
    private volatile Method propGetFloat, propGetInt;
    private boolean started;

    public CarBms(Context c) {
        ctx = c.getApplicationContext();
    }

    public void start() {
        if (started) return;
        started = true;
        main.post(this::connect);
    }

    public boolean isReady() {
        return bms != null && getProperty != null;
    }

    private void connect() {
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            Method create = carCls.getMethod("createCar", Context.class, ServiceConnection.class);
            car = create.invoke(null, ctx, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    try {
                        bms = car.getClass().getMethod("getCarManager", String.class).invoke(car, "bms");
                        if (bms != null) {
                            getProperty = bms.getClass().getMethod("getProperty", Class.class, int.class, int.class);
                            try {
                                setProperty = bms.getClass().getMethod("setProperty", Class.class, int.class, int.class, Object.class);
                            } catch (Throwable e) {
                                Diag.error("bms.commands", e.toString());
                            }
                        }
                        Diag.put("bms.state", bms != null ? "connected" : "bms manager missing");
                        try {
                            prop = car.getClass().getMethod("getCarManager", String.class).invoke(car, "property");
                            if (prop != null) {
                                propGetFloat = prop.getClass().getMethod("getFloatProperty", int.class, int.class);
                                propGetInt = prop.getClass().getMethod("getIntProperty", int.class, int.class);
                            }
                            Diag.put("property.state", prop != null ? "connected" : "property manager missing");
                        } catch (Throwable e) {
                            Diag.error("property.manager", e.toString());
                        }
                        try {
                            hvac = car.getClass().getMethod("getCarManager", String.class).invoke(car, "hvac");
                            if (hvac != null) hvacGetFloat = hvac.getClass().getMethod("getFloatProperty", int.class, int.class);
                            Diag.put("hvac.state", hvac != null ? "connected" : "hvac manager missing");
                        } catch (Throwable e) {
                            Diag.error("hvac.manager", e.toString());
                        }
                    } catch (Throwable e) {
                        Diag.error("bms.manager", e.toString());
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    bms = null;
                    Diag.put("bms.state", "disconnected");
                }
            });
            carCls.getMethod("connect").invoke(car);
            Diag.put("bms.state", "connecting…");
        } catch (Throwable e) {
            Diag.error("bms.car", e.toString());
            main.postDelayed(this::connect, 15000);
        }
    }

    /** Float property of the "hvac" manager (CarHvacManager) for a zone, or NaN if unreadable. */
    public float hvacFloat(int id, int area) {
        Object m = hvac;
        Method g = hvacGetFloat;
        if (m == null || g == null) return Float.NaN;
        try {
            Object v = g.invoke(m, id, area);
            return v instanceof Float ? (Float) v : Float.NaN;
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            Diag.put("hvac.0x" + Integer.toHexString(id) + "@" + area, "error " + c.getClass().getSimpleName());
            return Float.NaN;
        }
    }

    /** Generic CarService float property for an area, or NaN (the error goes to the logs). */
    public float propFloat(int id, int area) {
        Object v = propCall(propGetFloat, id, area);
        return v instanceof Float ? (Float) v : Float.NaN;
    }

    /** Generic CarService int property for an area, or Integer.MIN_VALUE. */
    public int propInt(int id, int area) {
        Object v = propCall(propGetInt, id, area);
        return v instanceof Integer ? (Integer) v : Integer.MIN_VALUE;
    }

    private Object propCall(Method g, int id, int area) {
        Object m = prop;
        if (m == null || g == null) return null;
        try {
            return g.invoke(m, id, area);
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            Diag.put("property.0x" + Integer.toHexString(id) + "@" + area, "error " + c.getClass().getSimpleName());
            return null;
        }
    }

    /** Float value of the property, or NaN if unreadable. */
    public float getFloat(int id) {
        Object v = get(Float.class, id);
        return v instanceof Float ? (Float) v : Float.NaN;
    }

    /** Int value of the property, or Integer.MIN_VALUE if unreadable. */
    public int getInt(int id) {
        Object v = get(Integer.class, id);
        return v instanceof Integer ? (Integer) v : Integer.MIN_VALUE;
    }

    private Object get(Class<?> type, int id) {
        Object m = bms;
        Method g = getProperty;
        if (m == null || g == null || unsupported.contains(id)) return null;
        try {
            Object v = g.invoke(m, type, id, 0);
            if (v == null) {
                // empty value: marked once and never re-read (previously it produced an error every 2 seconds)
                unsupported.add(id);
                Diag.put("bms.0x" + Integer.toHexString(id), "empty value: not available on this car");
            }
            return v;
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof NullPointerException || c instanceof IllegalArgumentException) {
                unsupported.add(id);
                Diag.put("bms.0x" + Integer.toHexString(id), "not available on this car (" + c.getClass().getSimpleName() + ")");
            } else {
                Diag.error("bms.0x" + Integer.toHexString(id), c.toString());
            }
            return null;
        }
    }

    /** Integer command to the battery manager (like the Charge management page). True if sent. */
    public boolean setInt(int id, int value) {
        Object m = bms;
        Method s = setProperty;
        if (m == null || s == null) return false;
        try {
            s.invoke(m, Integer.class, id, 0, value);
            return true;
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            Diag.error("bms.command.0x" + Integer.toHexString(id), c.toString());
            return false;
        }
    }

    /** True if the property is readable (not marked as empty or unsupported). */
    public boolean supported(int id) {
        return !unsupported.contains(id);
    }
}
