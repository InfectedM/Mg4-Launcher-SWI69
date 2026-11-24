package com.mg4.launcher.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * 0.4.3 (point 5): steering wheel keys. On the SWI69 R45 they arrive as broadcast com.saic.keyevent.hardkey.report (the
 * stock SystemUI listens to it, HardKeyManager) with the extras android.intent.extra.hardkey.keycode (Android KeyEvent
 * codes: 87 next, 88 previous, 24/25 volume…), android.intent.extra.hardkey.down and android.intent.extra.hardkey.longpress.
 * Listened to here: for the media.tastiVolante log, for the Function check test and for USB 2 (next/previous
 * go to the USB 2 session only if the system has not already done it, see MediaClient.onWheelSkip).
 */
public final class WheelKeys {
    public static final String ACTION = "com.saic.keyevent.hardkey.report";
    private static final String EX_CODE = "android.intent.extra.hardkey.keycode", EX_DOWN = "android.intent.extra.hardkey.down",
            EX_LONG = "android.intent.extra.hardkey.longpress";
    public static final int NEXT = 87, PREV = 88;

    public interface Listener {
        void onKey(int code, boolean down, boolean longPress);
    }

    private static boolean registered;
    private static volatile Listener probe, media;
    private static int count;
    private static final java.util.TreeMap<Integer, Integer> perCode = new java.util.TreeMap<>();

    private WheelKeys() {}

    public static synchronized void start(Context c) {
        if (registered) return;
        try {
            c.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    int code = i.getIntExtra(EX_CODE, -1);
                    boolean down = i.getBooleanExtra(EX_DOWN, false), lp = i.getBooleanExtra(EX_LONG, false);
                    note(code, down, lp);
                    Listener l = probe;
                    if (l != null) l.onKey(code, down, lp);
                    Listener m = media;
                    if (m != null) m.onKey(code, down, lp);
                }
            }, new IntentFilter(ACTION));
            registered = true;
            Diag.put("media.wheelKeys", "listening (" + ACTION + ") · no key seen so far");
        } catch (Throwable e) {
            Diag.error("media.wheelKeys", e.toString());
        }
    }

    /** Function check test: receives the keys until set back to null. */
    public static void setProbe(Listener l) {
        probe = l;
    }

    /** MediaClient: next/previous for USB 2. */
    static void setMedia(Listener l) {
        media = l;
    }

    public static boolean isSkip(int code) {
        return code == NEXT || code == PREV;
    }

    public static String name(int code) {
        switch (code) {
            case 24: return "volume +";
            case 25: return "volume −";
            case 164: return "mute";
            case 85: return "play/pause";
            case PREV: return "previous";
            case NEXT: return "next";
            case 3: return "home";
            case 5: return "call";
            case 6: return "hang up";
            case 220: case 221: return "brightness";
            default: return "code " + code;
        }
    }

    private static synchronized void note(int code, boolean down, boolean lp) {
        count++;
        Integer n = perCode.get(code);
        perCode.put(code, n == null ? 1 : n + 1);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, Integer> e : perCode.entrySet()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(" ").append(name(e.getKey())).append(" ×").append(e.getValue());
        }
        Diag.put("media.wheelKeys", "last code " + code + " (" + name(code) + ") down " + down + " long " + lp
                + " · source " + MediaClient.currentSourceName() + " · seen " + count + " events: " + sb);
    }
}
