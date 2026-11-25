package com.mg4.launcher.climate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

/**
 * Steering wheel keys. The SAIC system sends them to everyone with the broadcast "com.saic.keyevent.hardkey.report"
 * with the key code, pressed/released and long press (same format MG4 Voice and the radio service receive).
 * The climate screen registers only while it is open and in front (onResume → onPause): outside the screen the keys
 * stay with the music and the other functions. If the broadcast is "ordered" the key is consumed (abortBroadcast,
 * top priority), otherwise it also reaches the other apps: this is written to the log.
 */
final class Joystick {
    static final String ACTION = "com.saic.keyevent.hardkey.report";
    static final String X_CODE = "android.intent.extra.hardkey.keycode";
    static final String X_DOWN = "android.intent.extra.hardkey.down";
    static final String X_LONG = "android.intent.extra.hardkey.longpress";

    /** Joystick actions, in pairing order. */
    static final String[] ACTIONS = {"up", "down", "left", "right", "ok"};

    interface Listener {
        void onKey(int code, boolean down, boolean longPress, boolean consumed);

        /**
         * Key as the MG SystemUI forwards it to the other apps (com.android.systemui.ACTION_HARD_KEY_EVENT).
         * 1–5 = music and volume (volume +/-, mute, previous/next track); 6–10 = second function; 11–15 = joystick in
         * the car's climate mode (★ key set to Climate: the car controls the climate, the music does not change).
         */
        default void onSystemKey(int sysCode, boolean down) {
        }
    }

    /** Broadcast of the MG SystemUI with the key already converted (codes 1–16). */
    static final String SYS_ACTION = "com.android.systemui.ACTION_HARD_KEY_EVENT";

    /** Joystick mode according to the SystemUI: 0 music/volume, 1 second function, 2 car climate, -1 not seen. */
    static int sysMode(int sysCode) {
        if (sysCode >= 1 && sysCode <= 5) return 0;
        if (sysCode >= 6 && sysCode <= 10) return 1;
        if (sysCode >= 11 && sysCode <= 15) return 2;
        return -1;
    }

    static String sysModeName(int m) {
        return m == 0 ? "music and volume" : m == 1 ? "second function" : m == 2 ? "car climate (★ key)" : "not seen";
    }

    private BroadcastReceiver sysReceiver;

    private final Context ctx;
    private final Listener listener;
    private BroadcastReceiver receiver;

    Joystick(Context c, Listener l) {
        ctx = c;
        listener = l;
    }

    void register() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Bundle b = i == null ? null : i.getExtras();
                if (b == null) return;
                int code = intOf(b.get(X_CODE));
                if (code < 0) return;
                boolean down = boolOf(b.get(X_DOWN), true);
                boolean lp = boolOf(b.get(X_LONG), false);
                boolean consumed = false;
                if (isOrderedBroadcast() && listener != null && wants(code)) {
                    try {
                        abortBroadcast();
                        consumed = true;
                    } catch (Throwable ignored) {
                    }
                }
                if (listener != null) listener.onKey(code, down, lp, consumed);
            }
        };
        // also the key as the SystemUI forwards it (to know whether it goes to the music or to the car's climate)
        sysReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null || listener == null) return;
                int k = i.getIntExtra("KEY_CODE", -1);
                if (k >= 0) listener.onSystemKey(k, i.getBooleanExtra("DOWN", false));
            }
        };
        try {
            ctx.registerReceiver(sysReceiver, new IntentFilter(SYS_ACTION));
        } catch (Throwable e) {
            sysReceiver = null;
        }
        IntentFilter f = new IntentFilter(ACTION);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        try {
            ctx.registerReceiver(receiver, f);
        } catch (Throwable e) {
            receiver = null;
            ClimateStore.event(ctx, "joystick: key reception failed " + e);
        }
    }

    void unregister() {
        if (sysReceiver != null) {
            try {
                ctx.unregisterReceiver(sysReceiver);
            } catch (Throwable ignored) {
            }
            sysReceiver = null;
        }
        if (receiver == null) return;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiver = null;
    }

    /** The key is one of those paired with the chosen joystick (only those are consumed). */
    private boolean wants(int code) {
        int side = ClimateStore.joystick(ctx);
        return side != ClimateStore.JOY_OFF && actionOf(ctx, side, code) != null;
    }

    /** Action paired with the code for that joystick, null if none. */
    static String actionOf(Context c, int side, int code) {
        for (String a : ACTIONS) if (ClimateStore.joyCode(c, side, a) == code) return a;
        return null;
    }

    static String mapText(Context c, int side) {
        StringBuilder sb = new StringBuilder();
        for (String a : ACTIONS) {
            if (sb.length() > 0) sb.append(", ");
            int k = ClimateStore.joyCode(c, side, a);
            sb.append(a).append('=').append(k < 0 ? "?" : String.valueOf(k));
        }
        return sb.toString();
    }

    // ---- exclusive keys -----------------------------------------------------------------------------------------
    // The broadcast cannot be blocked: the other apps (radio, music, volume) get it anyway. The only clean way is the
    // key filter of the accessibility service (ClimateGuard.onKeyEvent), active only with the screen in front: if the
    // car generates the broadcast AFTER the filter, consuming the key there means it reaches nobody else.
    // It is measured in the car: with the filter on, if the filter sees the key and the broadcast does not arrive →
    // exclusive; if the broadcast arrives anyway → not possible (the other apps get it). Saved per software.

    private static final long MATCH_MS = 450;
    private final long[] lastFilter = new long[512], lastCast = new long[512];
    private int filterOnly, both, castNoFilter;
    private boolean decided;

    /** The accessibility filter saw (and consumed) the key: shortly afterwards it checks whether the broadcast came. */
    void sawFilter(int code, android.os.Handler h) {
        if (code < 0 || code >= lastFilter.length) return;
        final long t = android.os.SystemClock.uptimeMillis();
        lastFilter[code] = t;
        if (lastCast[code] != 0 && t - lastCast[code] < MATCH_MS) {   // the broadcast had already arrived
            both++;
            check();
            return;
        }
        h.postDelayed(() -> {
            if (lastCast[code] != 0 && lastCast[code] >= t - MATCH_MS && lastCast[code] <= t + MATCH_MS) return;   // counted by sawCast
            filterOnly++;
            check();
        }, MATCH_MS + 50);
    }

    /** The key's broadcast arrived while the filter is on. */
    void sawCast(int code, android.os.Handler h) {
        if (code < 0 || code >= lastCast.length) return;
        final long t = android.os.SystemClock.uptimeMillis();
        lastCast[code] = t;
        if (lastFilter[code] != 0 && t - lastFilter[code] < MATCH_MS) {   // the filter had already seen it
            both++;
            check();
            return;
        }
        h.postDelayed(() -> {
            if (lastFilter[code] != 0 && lastFilter[code] >= t - MATCH_MS && lastFilter[code] <= t + MATCH_MS) return;   // counted by sawFilter
            castNoFilter++;
            check();
        }, MATCH_MS + 50);
    }

    /** Joystick in the car's climate mode (★ key): measuring exclusivity makes no sense. */
    volatile boolean carClimateMode;

    private void check() {
        if (decided || carClimateMode) return;
        String counts = "keys seen only by the filter " + filterOnly + ", also by the broadcast " + both
                + ", broadcast without the filter " + castNoFilter;
        if (filterOnly >= 3 && both == 0) {
            decided = true;
            ClimateStore.setJoyExclusive(ctx, 1, counts);
            ClimateStore.event(ctx, "joystick.exclusive = accessibility filter · works · the other apps do not get the keys (" + counts + ")");
        } else if (both >= 3 || (castNoFilter >= 5 && filterOnly == 0 && both == 0 && ClimateGuard.filterVerified())) {
            // "the filter does not receive" counts only if the filter is really on in the service (flag read back)
            decided = true;
            String why = both >= 3 ? "the broadcast arrives anyway: the other apps get the keys"
                    : "the filter does not receive the steering wheel keys";
            String others = ClimateGuard.conflictApps(ctx);
            ClimateStore.setJoyExclusive(ctx, 0, why + " (" + counts + ")" + (others.isEmpty() ? "" : " · other apps: " + others));
            ClimateStore.event(ctx, "joystick.exclusive = accessibility filter · not possible · " + why + " (" + counts + ")"
                    + " · the commands stay on through the broadcast");
            if (onDecided != null) onDecided.run();
        }
    }

    Runnable onDecided;

    /** The SystemUI forwarded the key to the music (1–5) while the filter is on: the other apps get it. */
    void sawSystemMusic() {
        both++;
        check();
    }

    /** Diagnostics line: method, result and whether the keys still reach the other apps. */
    static String exclusiveLine(Context c) {
        int v = ClimateStore.joyExclusive(c);
        String d = ClimateStore.joyExclusiveDetail(c);
        String others = ClimateGuard.conflictApps(c);
        return "accessibility key filter · " + (v < 0 ? "not tried yet" : v == 1 ? "works · to the other apps: no"
                : "not possible · to the other apps: yes · commands through the broadcast") + (d.isEmpty() || v < 0 ? "" : " (" + d + ")")
                + " · filter now " + (ClimateGuard.keyFilterOn() ? "on (consumes only with the climate screen in front)" : "off")
                + " · flag in the service " + (ClimateGuard.filterVerified() ? "yes" : "no")
                + (others.isEmpty() ? "" : " · other services that can take the keys: " + others
                + " → turn the joystick off in the other app to use it with the climate screen");
    }

    private static int intOf(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static boolean boolOf(Object v, boolean def) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v) || "1".equals(v);
        return def;
    }
}
