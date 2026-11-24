package com.mg4.launcher.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Start at power-on (LOCKED_BOOT_COMPLETED / BOOT_COMPLETED). Checks the workshop-mode expiry and logs the
 * timings. If the accessibility service is active, it replaces the MG home itself (earlier and without
 * periodic checks); otherwise, as a fallback, the foreground app is checked every 400 ms.
 */
public final class BootWatcher extends BroadcastReceiver {
    public static final String MG_HOME = com.mg4.launcher.apps.MgApps.HOME_PKG;
    private static final long PERIOD_MS = 400, MAX_MS = 55_000;   // a background receiver is limited to 60 s

    private static volatile boolean running;

    /** To be called when the user deliberately opens the MG home. */
    public static void userOpenedOriginal(Context c) {
        StartGuard.userOpenedOriginal(c);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean locked = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        if (!locked && !Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        Context c = context.getApplicationContext();
        if (Firmware.wrong(c)) return;   // SWI69 build installed on another firmware: touch nothing
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date());
        Diag.put("startup.boot", "received at " + time + " (" + (locked ? "locked" : "normal") + ")"
                + " · " + (SystemClock.elapsedRealtime() / 1000) + " s since power-on");
        StartGuard.markBoot();
        CrashGuard.install(c);
        BootAccessibility.ensureEnabled(c);
        // 0.3.4: settings are not readable before unlock; the work is done on BOOT_COMPLETED
        if (!StartGuard.unlocked(c)) {
            Diag.put("startup.homeResumed", "waiting for unlock");
            return;
        }
        HomeMode.checkExpiry(c);
        HomeMode.diag(c);
        if (!HomeMode.active(c)) {
            Diag.put("startup.homeResumed", "no (" + HomeMode.reason(c) + ")");
            return;
        }
        if (StartGuard.a11yConnected) return;   // the accessibility service takes care of it
        if (running) return;
        running = true;
        StartGuard.openWindow(c, "power-on (fallback)");
        PendingResult pending = goAsync();
        HandlerThread thread = new HandlerThread("mg4-boot");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        long start = SystemClock.elapsedRealtime();
        h.post(new Runnable() {
            @Override
            public void run() {
                if (!StartGuard.a11yConnected) StartGuard.onForeground(c, StartGuard.topPackage(c));
                if (StartGuard.a11yConnected || !StartGuard.windowOpen() || SystemClock.elapsedRealtime() - start >= MAX_MS) {
                    running = false;
                    thread.quitSafely();
                    try {
                        pending.finish();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                h.postDelayed(this, PERIOD_MS);
            }
        });
    }
}
