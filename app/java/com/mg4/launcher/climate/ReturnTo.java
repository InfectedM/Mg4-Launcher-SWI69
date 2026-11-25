package com.mg4.launcher.climate;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import java.util.List;

/**
 * On closing, the climate screen goes back to where it was opened from.
 * <ul>
 *   <li>opened while Android Auto or CarPlay was in front (also from the floating button): if the AllGo service says
 *       the session is still active, the task that was there is brought back to the front (moveTaskToFront), without
 *       restarting the app;</li>
 *   <li>otherwise (from the home, no session, task not found): home.</li>
 * </ul>
 * Session: service com.allgo.rui/.RemoteUIService, interface com.allgo.rui.IRemoteUIService,
 * getActiveRemoteDevice() = transaction 15; the reply starts with 0 if no device is in a session.
 * Nothing is read about the device (no names in the logs).
 */
final class ReturnTo {
    static final String EXTRA_FROM = "com.mg4.launcher.climate.FROM";
    private static final ComponentName ALLGO = new ComponentName("com.allgo.rui", "com.allgo.rui.RemoteUIService");
    private static final String ALLGO_IFACE = "com.allgo.rui.IRemoteUIService";
    private static final int TX_ACTIVE_DEVICE = 15;

    /** App the current screen was opened from (package) and how. */
    private static volatile String from = "-", via = "-";
    private static volatile IBinder allgo;
    private static boolean binding;

    private ReturnTo() {
    }

    /** From the service: link to AllGo (once), so the answer is immediate on closing. */
    static synchronized void bind(Context c) {
        if (allgo != null || binding) return;
        try {
            c.getPackageManager().getServiceInfo(ALLGO, 0);
        } catch (Throwable e) {
            return;   // no AllGo: no projection
        }
        binding = true;
        try {
            c.getApplicationContext().bindService(new Intent().setComponent(ALLGO), new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    allgo = b;
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    allgo = null;
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            binding = false;
        }
    }

    /** When the screen opens: fromPkg = app that was in front before the climate screen. */
    static void onOpen(String fromPkg, String how) {
        from = fromPkg == null || fromPkg.isEmpty() ? "-" : fromPkg;
        via = how;
    }

    static boolean fromProjection() {
        return ProjectionZone.isProjection(from);
    }

    /** 1 = session active, 0 = no session, -1 = AllGo does not answer. */
    static int session() {
        IBinder b = allgo;
        if (b == null) return -1;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(ALLGO_IFACE);
            if (!b.transact(TX_ACTIVE_DEVICE, d, r, 0)) return -1;
            r.readException();
            return r.readInt() == 0 ? 0 : 1;
        } catch (Throwable e) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /**
     * Closing: if it was opened from Android Auto/CarPlay and the session is still there, brings that task back;
     * otherwise the home. Returns the text for the log.
     */
    static String back(Activity a) {
        String opened = from;
        String result = "home";
        if (fromProjection()) {
            int ses = session();
            if (ses == 0) result = "home (no AllGo session)";
            else {
                int task = taskOf(a, opened);
                if (task < 0) result = "home (task of " + opened + " not found)";
                else {
                    try {
                        ((ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE)).moveTaskToFront(task, 0);
                        result = opened + " (task " + task + (ses < 0 ? ", AllGo does not answer" : "") + ")";
                    } catch (Throwable e) {
                        result = "home (moveTaskToFront failed: " + e.getClass().getSimpleName() + ")";
                    }
                }
            }
        }
        if (result.startsWith("home")) {
            try {
                a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
        String line = "opened from " + opened + " (" + via + ") · back to " + result;
        ClimateStore.setReturnLine(a, line);
        from = "-";
        via = "-";
        return line;
    }

    @SuppressWarnings("deprecation")
    private static int taskOf(Context c, String pkg) {
        try {
            List<ActivityManager.RunningTaskInfo> tasks =
                    ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(30);
            for (ActivityManager.RunningTaskInfo t : tasks) {
                if ((t.baseActivity != null && pkg.equals(t.baseActivity.getPackageName()))
                        || (t.topActivity != null && pkg.equals(t.topActivity.getPackageName()))) return t.id;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
