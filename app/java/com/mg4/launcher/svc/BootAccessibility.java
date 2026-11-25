package com.mg4.launcher.svc;

import android.os.SystemClock;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * The launcher's accessibility service, used ONLY to know which app comes to the foreground
 * (TYPE_WINDOW_STATE_CHANGED, no window content). Android binds it at system start, before
 * BOOT_COMPLETED: so the MG home opened by the firmware is replaced earlier.
 */
public final class BootAccessibility extends AccessibilityService {
    private static final long EXPIRY_CHECK_MS = 30 * 60_000L;
    private final Handler h = new Handler(Looper.getMainLooper());

    /** Enables the service permanently (WRITE_SECURE_SETTINGS, platform signature), without touching the others. */
    // --- 0.9.6: if another app rewrites the accessibility services list and removes us, we add ourselves back ---
    private static volatile boolean watching;
    private static final java.util.ArrayDeque<Long> readds = new java.util.ArrayDeque<>();

    public static synchronized void watchList(Context ctx) {
        if (watching) return;
        watching = true;
        Context c = ctx.getApplicationContext();
        try {
            c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false, new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            if (!StartGuard.unlocked(c) || !HomeMode.active(c)) return;
                            String me = new ComponentName(c, BootAccessibility.class).flattenToString();
                            String climate = new ComponentName(c, com.mg4.launcher.climate.ClimateGuard.class).flattenToString();
                            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                            if (cur != null && cur.toLowerCase().contains(me.toLowerCase())
                                    && cur.toLowerCase().contains(climate.toLowerCase())) return;
                            long now = android.os.SystemClock.elapsedRealtime();
                            while (!readds.isEmpty() && now - readds.peekFirst() > 10 * 60_000L) readds.removeFirst();
                            if (readds.size() >= 5) {
                                Diag.put("startup.accessibilityRemoved", "removed again by another app: stopping for 10 minutes");
                                return;
                            }
                            readds.addLast(now);
                            Diag.put("startup.accessibilityRemoved", "another app had removed it (list: " + cur + "): restored");
                            ensureEnabled(c);
                            com.mg4.launcher.climate.ClimateGuard.ensureEnabled(c);   // built-in climate screen
                        }
                    });
        } catch (Throwable e) {
            Diag.put("startup.accessibilityRemoved", "check unavailable: " + e);
        }
    }

    public static void ensureEnabled(Context c) {
        try {
            String me = new ComponentName(c, BootAccessibility.class).flattenToString();
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = false;
            if (!TextUtils.isEmpty(cur)) for (String s : cur.split(":")) if (s.equalsIgnoreCase(me)) listed = true;
            int on = Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (listed && on == 1) {
                Diag.put("startup.accessibility", StartGuard.a11yConnected ? "active" : "enabled, waiting for connection");
                return;
            }
            if (!listed) {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        TextUtils.isEmpty(cur) ? me : cur + ":" + me);
            }
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Diag.put("startup.accessibility", "enabled now");
        } catch (Throwable e) {
            Diag.put("startup.accessibility", "error: " + e);
        }
    }

    /** 0.4.2 (point 6): cameras opened/closed (same broadcasts the original home listens to). */
    private boolean cameraRegistered;
    private final BroadcastReceiver camera = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            RetroReturn.onCameraBroadcast(c, "com.saicmotor.360camera.show".equals(i.getAction()));
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            HomeMode.checkExpiry(c);
            StartGuard.openWindow(c, "screen wake-up");
            scheduleAutoHome("wake-up");
        }
    };

    // ---- "Press Home by itself after power-on" (option) ---------------------------------------

    private final Runnable autoHome = this::autoHomeNow;
    private String autoHomeWhy = "";
    /** Until when to retry if the MG home has not appeared yet (0.3.4). */
    private long autoHomeUntil;
    private static final long AUTO_HOME_RETRY_MS = 5000, AUTO_HOME_WAIT_MS = 180_000L;

    /** Only once after power-on or wake-up, after the seconds chosen in the settings. */
    private void scheduleAutoHome(String why) {
        h.removeCallbacks(autoHome);
        int sec = com.mg4.launcher.ui.Prefs.autoHomeSeconds(com.mg4.launcher.ui.Prefs.sp(this));
        if (sec <= 0) {
            Diag.put("startup.pressHome", "off");
            return;
        }
        autoHomeWhy = why;
        autoHomeUntil = android.os.SystemClock.elapsedRealtime() + AUTO_HOME_WAIT_MS;
        Diag.put("startup.pressHome", "in " + sec + " s (" + why + ")");
        h.postDelayed(autoHome, sec * 1000L);
    }

    /**
     * Presses Home only if the MG home is in the foreground: does nothing if the launcher is already in front or another app is
     * (rear camera, 360°, CarPlay/Android Auto, call...).
     */
    private void autoHomeNow() {
        String top = StartGuard.topPackage(this);
        String result;
        if (getPackageName().equals(top)) {
            // the MG home may arrive later: recheck until it appears
            if (!StartGuard.mgSeenInWindow() && android.os.SystemClock.elapsedRealtime() < autoHomeUntil) {
                Diag.put("startup.pressHome", "launcher in front, waiting for the MG home · " + autoHomeWhy);
                h.postDelayed(autoHome, AUTO_HOME_RETRY_MS);
                return;
            }
            result = "not needed (launcher already in front)";
        } else if (BootWatcher.MG_HOME.equals(top)) {
            boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
            result = ok ? "pressed Home (the MG home was there)" : "Home failed";
        } else {
            result = "no: in foreground " + (top == null ? "unknown" : top);
        }
        Diag.put("startup.pressHome", result + " · " + autoHomeWhy);
    }

    private final Runnable expiry = new Runnable() {
        @Override
        public void run() {
            HomeMode.checkExpiry(BootAccessibility.this);
            h.postDelayed(this, EXPIRY_CHECK_MS);
        }
    };

    /** 0.3.4: bound before unlock → start when USER_UNLOCKED arrives. */
    private boolean started, unlockRegistered;
    private final BroadcastReceiver unlock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Diag.put("startup.accessibility", "user unlocked: starting");
            start();
        }
    };

    @Override
    protected void onServiceConnected() {
        if (Firmware.wrong(this)) {
            Diag.put("startup.accessibility", "inactive (firmware not SWI69)");
            return;
        }
        StartGuard.markA11y();
        CrashGuard.install(this);
        watchList(this);
        if (!StartGuard.unlocked(this)) {
            Diag.put("startup.accessibility", "connected before unlock: waiting for unlock");
            try {
                registerReceiver(unlock, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
                unlockRegistered = true;
            } catch (Throwable ignored) {
            }
            // unlocked in the meantime?
            if (StartGuard.unlocked(this)) start();
            return;
        }
        start();
    }

    /** The actual start, only once, with the user unlocked. */
    private void start() {
        if (started) return;
        started = true;
        StartGuard.a11yConnected = true;
        Diag.put("startup.accessibility", "active");
        HomeMode.checkExpiry(this);
        StartGuard.openWindow(this, "power-on");
        // the MG home may already be in the foreground
        StartGuard.onForeground(this, StartGuard.topPackage(this));
        scheduleAutoHome("power-on");
        StartGuard.resolvedHome(this);
        new Thread(() -> StartGuard.reportKeyApps(getApplicationContext()), "mg4-keys").start();
        try {
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (Throwable ignored) {
        }
        try {
            IntentFilter cam = new IntentFilter("com.saicmotor.360camera.show");
            cam.addAction("com.saicmotor.360camera.close");
            registerReceiver(camera, cam);
            cameraRegistered = true;
        } catch (Throwable ignored) {
        }
        h.postDelayed(expiry, EXPIRY_CHECK_MS);
        // 0.9.6: safe start reconfirmed at every power-on; if the MG home does not appear, the launcher is opened anyway
        new Thread(() -> HomeLock.apply(this, "power-on"), "mg4-homelock").start();
        h.postDelayed(() -> StartGuard.lateCheck(this), 8000);
        h.postDelayed(() -> StartGuard.lateCheck(this), 20000);
    }

    // ---- 0.4.0 (point 1): touch Home key of the system bar -------------------------------
    // With safe start the MG home (com.saicmotor.launcher) is disabled: the system bar still tries to
    // resolve its activity to open it and, not finding it, does nothing (no implicit intent, no
    // broadcast to intercept). Solution: intercept the touch on the system bar Home icon
    // (com.android.systemui, id bt_home/iv_home) and press Home in its place with performGlobalAction, WITHOUT
    // re-enabling the MG home. Only the id of the touched view is read, not the rest of the window content.
    private static final java.util.Set<String> SYSTEM_HOME_IDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "bt_home", "iv_home"));

    private void onSystemHomeTouch() {
        String top = StartGuard.topPackage(this);
        // already in front: nothing to do (the built-in climate screen is the same package but not the home)
        if (getPackageName().equals(top) && !com.mg4.launcher.climate.ClimateActivity.inFront()) return;
        boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
        Diag.put("home.touchButton", (top == null ? "unknown" : top) + " · performGlobalAction(HOME) · "
                + (ok ? "succeeded" : "failed"));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        if (!started || !StartGuard.a11yConnected) return;   // wrong firmware or user not yet unlocked
        String pkg = e.getPackageName().toString();

        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (!"com.android.systemui".equals(pkg)) return;
            android.view.accessibility.AccessibilityNodeInfo src = e.getSource();
            if (src == null) return;
            try {
                String vid = src.getViewIdResourceName();   // e.g. "com.android.systemui:id/bt_home"
                if (vid != null) {
                    int sep = vid.indexOf(':');
                    String name = sep >= 0 && vid.length() > sep + 4 ? vid.substring(sep + 4) : vid;   // after ":id/"
                    if (SYSTEM_HOME_IDS.contains(name)) onSystemHomeTouch();
                }
            } finally {
                src.recycle();
            }
            return;
        }

        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        guardAndroidSettings(pkg);   // 0.4.4 (issue #29): Android settings opened → MG home re-enabled in advance
        // 0.4.2 (point 6): reverse gear (cameras) and app to reopen afterwards; before StartGuard, which replaces the MG home
        try {
            RetroReturn.onForeground(this, pkg);
        } catch (Throwable t) {
            Diag.error("reverse.return", t.toString());
        }
        StartGuard.onForeground(this, pkg, e.getClassName() == null ? null : e.getClassName().toString(), true);
        if (getPackageName().equals(pkg)) probeSystemButton();
        // our launcher must stay in the foreground for 5 s: recheck without waiting for another event
        if (getPackageName().equals(pkg) && StartGuard.windowOpen()) {
            h.postDelayed(() -> StartGuard.onForeground(this, StartGuard.topPackage(this)), 5200);
        }
    }

    private long lastProbe;

    // ---- 0.4.4 (issue #29, from SWI68 1.0.4): Android settings opened → MG home re-enabled in advance -----
    //
    // If the launcher is removed from the Android Apps menu it has no way to re-enable the MG home (the car would stay on
    // "Smartphone starting"). So, as soon as the Android settings or the uninstall confirmation come to the front,
    // the MG home screen is re-enabled right away: the default home stays ours, so
    // nothing changes for the driver. Back in the launcher, safe start disables it again (MainActivity.onResume).
    private static final java.util.Set<String> ANDROID_SETTINGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.settings", "com.android.packageinstaller", "com.google.android.packageinstaller"));
    private long settingsGuardAt;

    private void guardAndroidSettings(String pkg) {
        if (!ANDROID_SETTINGS.contains(pkg)) return;
        long now = SystemClock.uptimeMillis();
        if (now - settingsGuardAt < 3000) return;
        settingsGuardAt = now;
        new Thread(() -> {
            if (HomeLock.mgDisabled(this)) HomeLock.release(this, "Android settings opened (" + pkg + ")");
        }, "mg4-homelock").start();
    }

    /**
     * 0.4.0 (point 12, check ONLY): records in the diagnostics whether the SystemUI window (bar with the
     * system keys and the rear fog light) is present and visible while the launcher is in the foreground.
     * Hides or changes nothing.
     */
    private void probeSystemButton() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastProbe < 30_000L) return;
        lastProbe = now;
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            StringBuilder sb = new StringBuilder("launcher in foreground · windows: ").append(ws == null ? 0 : ws.size());
            int sys = 0;
            if (ws != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    try {
                        if (!"com.android.systemui".contentEquals(r.getPackageName() == null ? "" : r.getPackageName())) continue;
                        sys++;
                        android.graphics.Rect b = new android.graphics.Rect();
                        w.getBoundsInScreen(b);
                        boolean fog = !r.findAccessibilityNodeInfosByViewId("com.android.systemui:id/iv_car_lamp").isEmpty();   // mIvRearFog view of StatusBarView
                        sb.append(" | systemui type ").append(w.getType()).append(" layer ").append(w.getLayer())
                                .append(" ").append(b.toShortString()).append(fog ? " · contains the fog light button (iv_car_lamp)" : "");
                    } finally {
                        r.recycle();
                    }
                }
            }
            sb.append(sys == 0 ? " | no visible SystemUI window" : "");
            Diag.put("lights.systemBar", sb.toString());
        } catch (Throwable t) {
            Diag.error("lights.systemBar", t.toString());
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        StartGuard.a11yConnected = false;
        h.removeCallbacksAndMessages(null);
        if (started) {
            try {
                unregisterReceiver(screenOn);
            } catch (Throwable ignored) {
            }
        }
        if (cameraRegistered) {
            try {
                unregisterReceiver(camera);
            } catch (Throwable ignored) {
            }
        }
        if (unlockRegistered) {
            try {
                unregisterReceiver(unlock);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }
}
