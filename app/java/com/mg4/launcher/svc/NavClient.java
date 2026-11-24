package com.mg4.launcher.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * MG navigator via com.saicmotor.adapterservice (GeneralService): guidance status,
 * current road, remaining distance/time and Home / Work / Open navigator commands.
 */
public final class NavClient {
    private static final String PKG = "com.saicmotor.adapterservice";
    private static final String CLS = "com.saicmotor.adapterservice.services.GeneralService";
    private static final String GS = "com.saicmotor.adapterservice.IGeneralService";
    private static final int GET_MAP_PKG = 9, HAS_HOME = 12, HAS_OFFICE = 13, IS_NAVIGATING = 18, START_NAV = 20,
            GO_HOME = 21, GO_OFFICE = 22, DIST_UNIT = 28, ROAD = 29, GUIDE_STATUS = 31, GUIDE_INFOS = 32, REMAIN_TIME = 33, REMAIN_DIST = 34;

    public static final class State {
        public boolean connected;
        public boolean navigating;
        public String road = "";
        public int remainMeters = -1;
        public int remainSeconds = -1;
        public boolean hasHome, hasOffice;
        public String mapPackage = "";
        public int turnMeters = -1;       // distance to the next maneuver
        public String turnText = "";      // road name / direction after the maneuver
        public int turnIcon = -1;
    }

    /** Navigator broadcasts (TomTom / navimaps), the same ones the MG service listens to. */
    private static final String[] STATUS_ACTIONS = {"com.tomtom.navigation.GUIDE_STATUS", "com.saicmotor.navimaps_auto.GUIDE_STATUS"};
    private static final String[] INFO_ACTIONS = {"com.tomtom.navigation.GUIDE_INFO", "com.saicmotor.navimaps_auto.GUIDE_INFO"};

    public interface Listener {
        void onNavState(State s);
    }

    private final ServiceLink link;
    private final HandlerThread thread = new HandlerThread("mg4-nav");
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile State last = new State();
    private boolean running;
    private final Context ctx;
    private boolean receiverOn;
    private volatile int bcStatus = -1;          // last guidance status received via broadcast
    private volatile int bcTurnMeters = -1, bcTurnIcon = -1;
    private volatile String bcTurnText = "";
    private volatile long bcTime;
    private int lastRemain = Integer.MIN_VALUE, lastRemainSec;
    private long remainChanged = -1_000_000;

    public NavClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        link = new ServiceLink(ctx, "navigator", ServiceLink.byComponent(PKG, CLS, null));
        link.setOnConnected(() -> {
            registerPush();
            pollSoon();
        });
    }

    // ---- service push notifications (like the original launcher: IGeneralNotificationListener) ----
    private static final String LISTENER = "com.saicmotor.adapterservice.IGeneralNotificationListener";
    private static final int REGISTER = 1, NAV_ACTIVE = 27;
    private volatile int pushStatus = -1, pushTurnIcon = -1, pushTurnMeters = -1, pushRemainM = -1, pushRemainS = -1;
    private volatile String pushTurnText = "", pushRoad = "";
    private volatile long pushTime;

    private final Binder push = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(LISTENER);
                return true;
            }
            try {
                data.enforceInterface(LISTENER);
                switch (code) {
                    case 1: // guideStatusChange(int)
                        pushStatus = data.readInt();
                        Diag.put("nav.push.guidanceState", pushStatus);
                        break;
                    case 2: // guideInfosChange(int icon, int distance, String direction)
                        pushTurnIcon = data.readInt();
                        pushTurnMeters = data.readInt();
                        String dir = data.readString();
                        pushTurnText = dir == null ? "" : dir.trim();
                        Diag.put("nav.push.maneuver", pushTurnMeters + " m · icon " + pushTurnIcon + " · " + pushTurnText);
                        break;
                    case 3: // roadInfoChange(String)
                        String road = data.readString();
                        pushRoad = road == null ? "" : road.trim();
                        Diag.put("nav.push.street", pushRoad);
                        break;
                    case 12: // remainingTimesChange(int)
                        pushRemainS = data.readInt();
                        Diag.put("nav.push.timeLeft", pushRemainS);
                        break;
                    case 13: // remainingDistanceChange(int)
                        pushRemainM = data.readInt();
                        Diag.put("nav.push.distanceLeft", pushRemainM);
                        break;
                    case 15: // navAppHasActivatedChange(boolean)
                        Diag.put("nav.push.activeApp", data.readInt() != 0);
                        break;
                    default:
                        Diag.put("nav.push.other", "code " + code);
                        break;
                }
                pushTime = SystemClock.uptimeMillis();
                if (reply != null) reply.writeNoException();
                main.post(NavClient.this::pollSoon);
            } catch (Exception e) {
                Diag.error("navigator.push", e.toString());
            }
            return true;
        }
    };

    private void registerPush() {
        if (bg == null) return;
        bg.post(() -> {
            try {
                Rpc.call(link.binder(), GS, REGISTER, p -> p.writeStrongBinder(push));
                Diag.put("nav.push", "registered");
            } catch (Exception e) {
                Diag.error("navigator.register", e.toString());
            }
        });
    }

    private final BroadcastReceiver guide = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            if (a.endsWith("GUIDE_STATUS")) {
                bcStatus = i.getIntExtra("STATUS", 0);
                Diag.put("nav.broadcastState", bcStatus);
            } else {
                bcTurnIcon = i.getIntExtra("turnIcon", -1);
                bcTurnMeters = i.getIntExtra("turnDist", -1);
                String d = i.getStringExtra("dirName");
                bcTurnText = d == null ? "" : d.trim();
                if (bcStatus < 0) bcStatus = 1;
            }
            bcTime = SystemClock.uptimeMillis();
            pollSoon();
        }
    };

    private void pollSoon() {
        if (bg == null || !running) return;
        bg.removeCallbacks(loop);
        bg.post(loop);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public State last() {
        return last;
    }

    public void start() {
        if (running) return;
        running = true;
        if (bg == null) {
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        link.start();
        if (!receiverOn) {
            IntentFilter f = new IntentFilter();
            for (String a : STATUS_ACTIONS) f.addAction(a);
            for (String a : INFO_ACTIONS) f.addAction(a);
            try {
                ctx.registerReceiver(guide, f);
                receiverOn = true;
            } catch (Exception e) {
                Diag.error("navigator.broadcast", e.toString());
            }
        }
        bg.post(loop);
    }

    public void stop() {
        pause();
        link.stop();
        if (receiverOn) {
            try {
                ctx.unregisterReceiver(guide);
            } catch (Exception ignored) {
            }
            receiverOn = false;
        }
    }

    /** Pauses the reads (launcher not visible) without disconnecting from the services. */
    public void pause() {
        running = false;
        if (bg != null) bg.removeCallbacks(loop);
    }

    public void resume() {
        if (bg == null || running) return;
        running = true;
        bg.post(loop);
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            poll();
            bg.postDelayed(this, 2000);
        }
    };

    private void poll() {
        State s = new State();
        IBinder b = link.binder();
        s.connected = b != null;
        int status = -1;
        if (b != null) {
            try {
                boolean mapNav = Rpc.callBool(b, GS, IS_NAVIGATING);
                status = Rpc.callInt(b, GS, GUIDE_STATUS);
                s.hasHome = Rpc.callBool(b, GS, HAS_HOME);
                s.hasOffice = Rpc.callBool(b, GS, HAS_OFFICE);
                String pkg = Rpc.callString(b, GS, GET_MAP_PKG);
                s.mapPackage = pkg == null ? "" : pkg;
                String road = Rpc.callString(b, GS, ROAD);
                s.road = road == null ? "" : road.trim();
                s.remainMeters = Rpc.callInt(b, GS, REMAIN_DIST);
                s.remainSeconds = Rpc.callInt(b, GS, REMAIN_TIME);
                List<?> infos = Rpc.call(b, GS, GUIDE_INFOS, Rpc.NONE, p -> p.readArrayList(NavClient.class.getClassLoader()));
                if (infos != null && infos.size() >= 3) {
                    if (infos.get(0) instanceof Integer) s.turnIcon = (Integer) infos.get(0);
                    if (infos.get(1) instanceof Integer) s.turnMeters = (Integer) infos.get(1);
                    if (infos.get(2) instanceof String) s.turnText = ((String) infos.get(2)).trim();
                }
                // the navigator does not always report isMapNavigating: any one of the guidance signals is enough
                long now = SystemClock.uptimeMillis();
                if (s.remainMeters != lastRemain || s.remainSeconds != lastRemainSec) {
                    if (lastRemain != Integer.MIN_VALUE && s.remainMeters > 0) remainChanged = now;
                    lastRemain = s.remainMeters;
                    lastRemainSec = s.remainSeconds;
                }
                boolean moving = s.remainMeters > 0 && now - remainChanged < 90_000;
                s.navigating = mapNav || status > 0 || moving;
                Diag.put("nav.signals", "isMapNavigating " + mapNav + " · guidanceState " + status
                        + " · rem. " + s.remainMeters + " m / " + s.remainSeconds + " s");
                Diag.put("nav.nextManeuver", s.turnMeters + " m · icon " + s.turnIcon + " · " + s.turnText);
                int unit = Rpc.callInt(b, GS, DIST_UNIT);
                com.mg4.launcher.ui.Units.navUnit = unit;
                Diag.put("nav.distanceUnit", unit);
                Diag.put("nav.street", s.road);
                Diag.put("nav.app", s.mapPackage);
                Diag.put("nav.activeApp", Rpc.callBool(b, GS, NAV_ACTIVE));
            } catch (Exception e) {
                Diag.error("navigator", e.toString());
            }
        }
        // service push notifications
        if (pushStatus > 0) s.navigating = true;
        else if (pushStatus == 0 && status <= 0) s.navigating = false;
        if (pushTurnMeters >= 0) {
            s.turnMeters = pushTurnMeters;
            s.turnIcon = pushTurnIcon;
            s.turnText = pushTurnText;
        }
        if (s.road.isEmpty() && !pushRoad.isEmpty()) s.road = pushRoad;
        if (s.remainMeters <= 0 && pushRemainM > 0) s.remainMeters = pushRemainM;
        if (s.remainSeconds <= 0 && pushRemainS > 0) s.remainSeconds = pushRemainS;
        // direct navigator broadcasts (faster than the service)
        int bc = bcStatus;
        if (bc == 0 && status <= 0) s.navigating = false;
        else if (bc > 0 && SystemClock.uptimeMillis() - bcTime < 120_000) s.navigating = true;
        if (s.turnMeters < 0 && bcTurnMeters >= 0) {
            s.turnMeters = bcTurnMeters;
            s.turnIcon = bcTurnIcon;
            s.turnText = bcTurnText;
        }
        if (!s.navigating) {
            s.turnMeters = -1;
            s.turnText = "";
        }
        last = s;
        main.post(() -> {
            if (listener != null) listener.onNavState(s);
        });
    }

    private void command(int code) {
        if (bg == null) return;
        bg.post(() -> {
            try {
                Rpc.call(link.binder(), GS, code, Rpc.NONE);
            } catch (Exception e) {
                Diag.error("navigator.command " + code, e.toString());
            }
        });
    }

    public boolean isConnected() {
        return link.isConnected();
    }

    public void openNavigator() {
        command(START_NAV);
    }

    public void goHome() {
        command(GO_HOME);
    }

    public void goOffice() {
        command(GO_OFFICE);
    }
}
