package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vehicle properties read from the Android Automotive CarService (android.car), via reflection because
 * android.car is not part of the public SDK. Used for tyre pressure (TPMS) and inside temperature,
 * which the SAIC vehicle service does not expose. Property IDs from firmware SWI69 R45 (YFVehicleProperty, same as SWI68).
 */
public final class CarProps {
    // tyre pressure (INT32, global area): FL, FR, RL, RR
    public static final int TIRE_FL = 557847891, TIRE_FR = 557847892, TIRE_RL = 557847893, TIRE_RR = 557847894;
    // cabin temperature (FLOAT, seat/zone area): current and the climate's "ambient"
    public static final int HVAC_TEMPERATURE_CURRENT = 358614274, HVAC_AMBIENT_TEMPERATURE = 358622506;
    /** Outside temperature validity (0 = valid, 1 = invalid), as used by the original SystemUI. */
    public static final int HVAC_TEMPERATURE_OUTCARV = 356525352;

    private final Context ctx;
    private Object car, propertyManager;
    private volatile boolean connected;
    private final Map<Integer, Integer> areas = new HashMap<>();
    /** All areas of each property (for the inside temperature probe). */
    private final Map<Integer, int[]> allAreas = new HashMap<>();
    private boolean areasRead;
    private String lastError = "";
    /** Properties the car rejects (IllegalArgumentException): never read again. */
    private final java.util.Set<Integer> unsupported = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Properties denied by a permission the car does not grant (SecurityException): never read again. */
    private final java.util.Set<Integer> denied = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Vehicle speed (FLOAT, m/s) and the permission that protects it. */
    public static final int PERF_VEHICLE_SPEED = 291504647;
    public static final String PERM_CAR_SPEED = "android.car.permission.CAR_SPEED";

    public CarProps(Context c) {
        ctx = c.getApplicationContext();
    }

    /**
     * CarService permission (e.g. CAR_SPEED): on the car it is a runtime permission, which the launcher grants itself
     * thanks to the platform signature (GRANT_RUNTIME_PERMISSIONS). Returns true if it ends up granted.
     */
    public boolean ensurePermission(String perm) {
        String name = perm.substring(perm.lastIndexOf('.') + 1);
        if (ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Diag.put("car.permission." + name, "granted");
            return true;
        }
        String why;
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, ctx.getPackageName(), perm, android.os.Process.myUserHandle());
            why = "";
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            why = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
        }
        boolean ok = ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        Diag.put("car.permission." + name, ok ? "granted now" : "not granted by the car" + (why.isEmpty() ? "" : " (" + why + ")"));
        return ok;
    }

    /** Connects to the CarService (must be called from a background thread). */
    public synchronized void connect() {
        if (car != null) return;
        // speed (driving assistant and pedal only with the car stopped): without permission it is not even attempted
        // 1.0.4: exterior lights (headlights) for the drawn car and the theme
        ensurePermission("android.car.permission.CAR_EXTERIOR_LIGHTS");
        if (!ensurePermission(PERM_CAR_SPEED)) {
            denied.add(PERF_VEHICLE_SPEED);
            Diag.put("car.speed", "not read: the car does not grant the CAR_SPEED permission (logged only once)");
        }
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    connected = true;
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    connected = false;
                    propertyManager = null;
                }
            };
            car = carCls.getMethod("createCar", Context.class, ServiceConnection.class).invoke(null, ctx, conn);
            carCls.getMethod("connect").invoke(car);
            Diag.put("car.carservice", "connecting…");
        } catch (Throwable e) {
            error("connection", e);
        }
    }

    public boolean ready() {
        return connected;
    }

    private Object pm() throws Exception {
        if (propertyManager == null && car != null && connected) {
            propertyManager = car.getClass().getMethod("getCarManager", String.class).invoke(car, "property");
            Diag.put("car.carservice", propertyManager != null ? "connected" : "no property manager");
        }
        return propertyManager;
    }

    /** Area to use for a property (0 for global ones, the first configured area for the others). */
    private int area(int prop) {
        if (!areasRead) {
            areasRead = true;
            try {
                Object p = pm();
                if (p != null) {
                    List<?> cfgs = (List<?>) p.getClass().getMethod("getPropertyList").invoke(p);
                    for (Object cfg : cfgs) {
                        int id = (Integer) cfg.getClass().getMethod("getPropertyId").invoke(cfg);
                        int[] ids = (int[]) cfg.getClass().getMethod("getAreaIds").invoke(cfg);
                        areas.put(id, ids != null && ids.length > 0 ? ids[0] : 0);
                        allAreas.put(id, ids != null && ids.length > 0 ? ids : new int[]{0});
                    }
                    Diag.put("car.property", cfgs.size() + " available; TPMS " + (areas.containsKey(TIRE_FL) ? "yes" : "no")
                            // 0.4.3: "ambient" is the outside temperature, it does not count as inside
                            + ", inside temp. " + (areas.containsKey(HVAC_TEMPERATURE_CURRENT) ? "yes"
                            : areas.containsKey(HVAC_AMBIENT_TEMPERATURE) ? "no (ambient only = outside)" : "no"));
                }
            } catch (Throwable e) {
                areasRead = false;
                error("property list", e);
            }
        }
        Integer a = areas.get(prop);
        return a == null ? 0 : a;
    }

    /** Integer, or Integer.MIN_VALUE if not readable. */
    public int getInt(int prop) {
        try {
            Object p = pm();
            if (p == null) return Integer.MIN_VALUE;
            Method m = p.getClass().getMethod("getIntProperty", int.class, int.class);
            return (Integer) m.invoke(p, prop, area(prop));
        } catch (Throwable e) {
            error("read " + prop, e);
            return Integer.MIN_VALUE;
        }
    }

    /** Integer at a fixed area (e.g. 0 for global properties), Integer.MIN_VALUE if not readable. No errors in the diagnostics. */
    public int getIntAt(int prop, int area) {
        try {
            Object p = pm();
            if (p == null) return Integer.MIN_VALUE;
            Method m = p.getClass().getMethod("getIntProperty", int.class, int.class);
            return (Integer) m.invoke(p, prop, area);
        } catch (Throwable e) {
            return Integer.MIN_VALUE;
        }
    }

    /** Float at a fixed area, NaN if not readable. No errors in the diagnostics. */
    public float getFloatAt(int prop, int area) {
        try {
            Object p = pm();
            if (p == null) return Float.NaN;
            Method m = p.getClass().getMethod("getFloatProperty", int.class, int.class);
            return (Float) m.invoke(p, prop, area);
        } catch (Throwable e) {
            return Float.NaN;
        }
    }

    /** Writes a float at a fixed area; throws the car's exception (the caller decides whether to try another area). */
    public void setFloatAt(int prop, int area, float value) throws Throwable {
        Object p = pm();
        if (p == null) throw new IllegalStateException("CarService not connected");
        Method m = p.getClass().getMethod("setFloatProperty", int.class, int.class, float.class);
        try {
            m.invoke(p, prop, area, value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    /** Writes an integer at a fixed area; false if it was not possible. */
    public boolean setIntAt(int prop, int area, int value) {
        try {
            Object p = pm();
            if (p == null) return false;
            Method m = p.getClass().getMethod("setIntProperty", int.class, int.class, int.class);
            m.invoke(p, prop, area, value);
            return true;
        } catch (Throwable e) {
            error("write " + prop, e);
            return false;
        }
    }

    /** Like getInt, but without writing errors to the diagnostics (properties that may not exist). */
    public int getIntQuiet(int prop) {
        try {
            Object p = pm();
            if (p == null) return Integer.MIN_VALUE;
            area(prop);
            if (!areas.containsKey(prop)) return Integer.MIN_VALUE;
            Method m = p.getClass().getMethod("getIntProperty", int.class, int.class);
            return (Integer) m.invoke(p, prop, area(prop));
        } catch (Throwable e) {
            return Integer.MIN_VALUE;
        }
    }

    /** 0.4.3 (from SWI68 0.4.3): NIGHT_MODE (BOOLEAN), the system says it is night. */
    public static final int NIGHT_MODE = 287310855;
    /** HEADLIGHTS_STATE (INT32): 0 off, 1 on, 2 daytime running lights. */
    public static final int HEADLIGHTS_STATE = 289410560;
    /** HEADLIGHTS_SWITCH (INT32): stalk position, 0 off, 1 on, 2 daytime, 3 automatic. */
    public static final int HEADLIGHTS_SWITCH = 289410576;
    /** DISPLAY_BRIGHTNESS (INT32). */
    public static final int DISPLAY_BRIGHTNESS = 289409539;

    /** True if the property is in the car's list (read once). */
    public synchronized boolean has(int prop) {
        try {
            area(prop);
        } catch (Throwable ignored) {
        }
        return areasRead && areas.containsKey(prop);
    }

    /** Integer without errors in the diagnostics; remembers the car's rejections. */
    public int getIntSafe(int prop) {
        if (unsupported.contains(prop) || denied.contains(prop)) return Integer.MIN_VALUE;
        try {
            Object p = pm();
            if (p == null) return Integer.MIN_VALUE;
            area(prop);
            if (!areas.containsKey(prop)) return Integer.MIN_VALUE;
            Method m = p.getClass().getMethod("getIntProperty", int.class, int.class);
            return (Integer) m.invoke(p, prop, area(prop));
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            if (c instanceof SecurityException) denied.add(prop);
            else if (c instanceof IllegalArgumentException) unsupported.add(prop);
            return Integer.MIN_VALUE;
        }
    }

    /** 0.4.2: boolean property: 1 true, 0 false, -1 not readable (without errors in the diagnostics). */
    public int getBoolQuiet(int prop) {
        if (unsupported.contains(prop) || denied.contains(prop)) return -1;
        try {
            Object p = pm();
            if (p == null) return -1;
            area(prop);
            if (!areas.containsKey(prop)) return -1;
            Method m = p.getClass().getMethod("getBooleanProperty", int.class, int.class);
            return (Boolean) m.invoke(p, prop, area(prop)) ? 1 : 0;
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            if (c instanceof SecurityException) denied.add(prop);
            else if (c instanceof IllegalArgumentException) unsupported.add(prop);
            return -1;
        }
    }

    /** 0.4.2: float without errors in the diagnostics (NaN if not readable or not in the car's list). */
    public float getFloatQuiet(int prop, int areaId) {
        if (unsupported.contains(prop) || denied.contains(prop)) return Float.NaN;
        try {
            Object p = pm();
            if (p == null) return Float.NaN;
            area(prop);
            if (!areas.containsKey(prop)) return Float.NaN;
            Method m = p.getClass().getMethod("getFloatProperty", int.class, int.class);
            return (Float) m.invoke(p, prop, areaId == Integer.MIN_VALUE ? area(prop) : areaId);
        } catch (Throwable e) {
            return Float.NaN;
        }
    }

    /** Configured areas of a property (empty if missing). */
    public int[] areasOf(int prop) {
        area(prop);
        int[] a = allAreas.get(prop);
        return a == null ? new int[0] : a;
    }

    /** Float, or NaN if not readable. After the car's first rejection the property is never read again. */
    public float getFloat(int prop) {
        if (unsupported.contains(prop) || denied.contains(prop)) return Float.NaN;
        try {
            Object p = pm();
            if (p == null) return Float.NaN;
            Method m = p.getClass().getMethod("getFloatProperty", int.class, int.class);
            return (Float) m.invoke(p, prop, area(prop));
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            if (c instanceof SecurityException) {
                // permission denied by the car (e.g. CAR_SPEED): a single log line, then the property is never read again
                denied.add(prop);
                Diag.put("car.permissionDenied." + Integer.toHexString(prop), "no longer read this session (" + c.getMessage() + ")");
                return Float.NaN;
            }
            if (c instanceof IllegalArgumentException) {
                unsupported.add(prop);
                Diag.put("car.propertyUnsupported." + Integer.toHexString(prop), "not available on this car (" + c.getClass().getSimpleName() + ")");
                return Float.NaN;
            }
            error("read " + prop, e);
            return Float.NaN;
        }
    }

    // ---- inside temperature probe (0.9.3) ------------------------------------------------
    // The car does not expose the cabin temperature through the standard properties: HVAC_TEMPERATURE_CURRENT is always 0 and
    // HVAC_AMBIENT_TEMPERATURE is the outside temperature. The iSmart app does show it, though, so it goes through somewhere.
    // The probe reads all the car's decimal (FLOAT type) properties, in all areas, and logs those with
    // temperature-like values and how much they vary: one that changes with the climate on and does not follow outside is the right one.

    private static final int TYPE_MASK = 0x00FF0000, TYPE_FLOAT = 0x00600000;
    /** Already known (climate settings, outside): not needed for the search. */
    private static final int[] PROBE_SKIP = {358622475, 358622476, 358614275, 358622481, 291505923};
    private final Map<String, float[]> probe = new java.util.TreeMap<>();

    /** One probe reading; returns the summary for the diagnostics ("" if the CarService is not ready). */
    public synchronized String probeInsideTemp(float outside) {
        area(0);
        if (!areasRead) return "";
        StringBuilder all = new StringBuilder();
        int n = 0;
        try {
            Object p = pm();
            if (p == null) return "";
            Method m = p.getClass().getMethod("getFloatProperty", int.class, int.class);
            for (Map.Entry<Integer, int[]> e : allAreas.entrySet()) {
                int id = e.getKey();
                if ((id & TYPE_MASK) != TYPE_FLOAT || unsupported.contains(id)) continue;
                boolean skip = false;
                for (int k : PROBE_SKIP) if (k == id) skip = true;
                if (skip) continue;
                for (int a : e.getValue()) {
                    float v;
                    try {
                        v = (Float) m.invoke(p, id, a);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (Float.isNaN(v) || v <= -30f || v >= 70f || v == 0f) continue;
                    String key = "0x" + Integer.toHexString(id) + "/" + a;
                    float[] r = probe.get(key);
                    if (r == null) probe.put(key, new float[]{v, v, v});
                    else {
                        r[0] = Math.min(r[0], v);
                        r[1] = Math.max(r[1], v);
                        r[2] = v;
                    }
                    n++;
                }
            }
        } catch (Throwable e) {
            return "error " + e;
        }
        // candidates: cabin-like values that are not the outside one
        for (Map.Entry<String, float[]> e : probe.entrySet()) {
            float[] r = e.getValue();
            boolean outsideLike = !Float.isNaN(outside) && Math.abs(r[2] - outside) < 0.6f;
            all.append(all.length() == 0 ? "" : " | ").append(e.getKey()).append('=').append(r[2])
                    .append(" (").append(r[0]).append('…').append(r[1]).append(')').append(outsideLike ? " =out" : "");
        }
        return n + " readings · " + (all.length() == 0 ? "no temperature-like value" : all);
    }

    /**
     * 1.0.4 (point B): SAIC lights (global properties, area 0), the same ones the SAIC settings service reads for the
     * theme and that MG4 Control uses (only the information, no code copied).
     * VEH_SIDE_LGHT (INT32): position lights, 3 = on (follows the automatic headlights).
     * DIPD_BEAM_LGHT (BOOLEAN): dipped beam on.
     * LIGHT_SWITCH_POS_STS (INT32): stalk position (probably not exposed on the SWI68).
     */
    public static final int VEH_SIDE_LGHT = 557880099, DIPD_BEAM_LGHT = 555782944, LIGHT_SWITCH_POS_STS = 557880119;
    /** 1.0.4: charging socket, BMS_CHRG_PLUG_CNCTNIO (global, INT32): 0 = cable plugged in, 1 = not plugged in. */
    public static final int BMS_CHRG_PLUG_CNCTNIO = 557904904;
    private Object lampManager;
    private boolean lampTried;
    private String lampWhy = "";

    /** "lamp" manager of the SAIC CarService (CarLampManager), as used by the original launcher. Null if missing. */
    private synchronized Object lamp() {
        if (lampManager == null && !lampTried && car != null && connected) {
            lampTried = true;
            try {
                lampManager = car.getClass().getMethod("getCarManager", String.class).invoke(car, "lamp");
                lampWhy = lampManager == null ? "no lamp manager" : "";
            } catch (Throwable e) {
                Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                lampWhy = c.getClass().getSimpleName();
            }
        }
        return lampManager;
    }

    /**
     * Reads a light: first from the "lamp" manager, then from the property manager (getProperty(Class, id, 0)).
     * Returns Integer (booleans as 1/0) or null if not readable; why[0] holds the source or the reason.
     */
    public Integer readLight(int prop, boolean bool, String[] why) {
        Class<?> cls = bool ? Boolean.class : Integer.class;
        String err = "";
        Object[] mgrs = new Object[2];
        mgrs[0] = lamp();
        try {
            mgrs[1] = pm();
        } catch (Throwable ignored) {
        }
        String[] names = {"lamp", "property"};
        for (int i = 0; i < 2; i++) {
            Object m = mgrs[i];
            if (m == null) continue;
            try {
                Object v = m.getClass().getMethod("getProperty", Class.class, int.class, int.class).invoke(m, cls, prop, 0);
                if (v != null) v = v.getClass().getMethod("getValue").invoke(v);
                if (v instanceof Boolean) {
                    why[0] = names[i];
                    return (Boolean) v ? 1 : 0;
                }
                if (v instanceof Integer) {
                    why[0] = names[i];
                    return (Integer) v;
                }
            } catch (Throwable e) {
                Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                err = names[i] + ": " + c.getClass().getSimpleName();
            }
        }
        why[0] = err.isEmpty() ? (lampWhy.isEmpty() ? "not read" : lampWhy) : err;
        return null;
    }

    /** Float value for each area of the property (area → value), empty if missing. No errors in the log. */
    public synchronized Map<Integer, Float> floatsAllAreas(int prop) {
        Map<Integer, Float> out = new java.util.LinkedHashMap<>();
        if (unsupported.contains(prop) || denied.contains(prop)) return out;
        try {
            Object p = pm();
            if (p == null || !has(prop)) return out;
            Method m = p.getClass().getMethod("getFloatProperty", int.class, int.class);
            int[] as = allAreas.get(prop);
            if (as == null) as = new int[]{0};
            for (int a : as) {
                try {
                    out.put(a, (Float) m.invoke(p, prop, a));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** False if the property is not in the car's list or was rejected; null if not known yet. */
    public Boolean available(int prop) {
        if (unsupported.contains(prop)) return false;
        if (!connected) return null;
        area(prop);
        if (!areasRead) return null;
        return areas.containsKey(prop);
    }

    private void error(String what, Throwable e) {
        Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        String msg = what + ": " + c;
        if (!msg.equals(lastError)) {
            lastError = msg;
            Diag.error("car.carservice", msg);
        }
    }

    /**
     * Pressure in bar from the raw value: the sensors give kPa (e.g. 250) or tenths/hundredths of bar.
     * Returns NaN for out-of-range values.
     */
    public static float toBar(int raw) {
        if (raw == Integer.MIN_VALUE || raw <= 0) return Float.NaN;
        if (raw >= 100 && raw <= 500) return raw / 100f;        // kPa
        if (raw >= 10 && raw < 100) return raw / 10f;           // tenths of bar
        if (raw > 500 && raw <= 5000) return raw / 1000f;       // millibar
        return Float.NaN;
    }
}
