package com.mg4.launcher.climate;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * Car speed from the CarService (PERF_VEHICLE_SPEED, m/s) for the automatic test: it only runs with the car stopped.
 * Everything through reflection (optional android.car library): if it cannot be read, the speed stays "unavailable".
 */
final class Speed {
    private static final int PERF_VEHICLE_SPEED = 291504647;
    private final Context ctx;
    private Object car, pm;
    private volatile boolean connected;

    Speed(Context c) {
        ctx = c;
    }

    synchronized void connect() {
        if (car != null) return;
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
                    pm = null;
                }
            };
            car = carCls.getMethod("createCar", Context.class, ServiceConnection.class).invoke(null, ctx, conn);
            carCls.getMethod("connect").invoke(car);
        } catch (Throwable ignored) {
        }
    }

    /** km/h, or NaN if it cannot be read. */
    float kmh() {
        try {
            if (pm == null && car != null && connected) pm = car.getClass().getMethod("getCarManager", String.class).invoke(car, "property");
            if (pm == null) return Float.NaN;
            Object v = pm.getClass().getMethod("getFloatProperty", int.class, int.class).invoke(pm, PERF_VEHICLE_SPEED, 0);
            return v instanceof Float ? Math.abs((Float) v) * 3.6f : Float.NaN;
        } catch (Throwable e) {
            return Float.NaN;
        }
    }
}
