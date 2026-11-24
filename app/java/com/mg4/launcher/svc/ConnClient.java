package com.mg4.launcher.svc;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

/**
 * Connection status for the top-right indicators: Bluetooth, Wi-Fi and the T-Box mobile network (4G).
 * 4G (only where the engmode service exists, i.e. not on the SWI69) is read like the original status bar: engmode service → "system_hardware" → getMobileNetworkInfoBean.
 */
public final class ConnClient {
    private static final String ENG_PKG = "com.saicmotor.service.engmode";
    private static final String ENG_ACTION = "com.saicmotor.service.engmode.EngineeringModeService";
    private static final String ENG = "com.saicmotor.sdk.engmode.IEngineeringMode";
    private static final int ENG_GET_SERVICE = 1;
    private static final String HW = "com.saicmotor.sdk.engmode.ISystemHardwareManager";
    private static final int HW_GET_MOBILE = 3;
    /** Thresholds of the original bar (SignalController): 0..4 bars. */
    private static final int[] SIGNAL_STEPS = {0, 3, 5, 8, 12};

    public static final class State {
        public boolean btOn, btConnected;
        public boolean wifiConnected;
        public int wifiLevel;          // 0..3
        public int mobileType = -1;    // 0 no network, 2 = 2G, 3 = 3G, 4 = 4G; -1 unknown
        public int mobileLevel;        // 0..4
    }

    public interface Listener {
        void onConn(State s);
    }

    private final Context ctx;
    private final ServiceLink eng;
    private final HandlerThread thread = new HandlerThread("mg4-conn");
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile State last = new State();
    private volatile IBinder hw;
    private boolean running;

    public ConnClient(Context c) {
        ctx = c.getApplicationContext();
        eng = new ServiceLink(ctx, "engmode", ServiceLink.byAction(ENG_PKG, ENG_ACTION));
        eng.setOnConnected(() -> hw = null);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public State last() {
        return last;
    }

    public void start() {
        if (bg == null) {
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        // the SWI69 lacks the SWI68's engmode service: without it, no 4G (and no repeated attempts)
        boolean hasEng;
        try {
            ctx.getPackageManager().getPackageInfo(ENG_PKG, 0);
            hasEng = true;
        } catch (Throwable e) {
            hasEng = false;
        }
        if (hasEng) eng.start();
        else Diag.put("conn.4g", "not available on this firmware");
        resume();
    }

    public void resume() {
        if (bg == null || running) return;
        running = true;
        bg.post(loop);
    }

    public void pause() {
        running = false;
        if (bg != null) bg.removeCallbacks(loop);
    }

    public void stop() {
        pause();
        eng.stop();
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            poll();
            bg.postDelayed(this, 3000);
        }
    };

    private void poll() {
        State s = new State();
        // Bluetooth: on and connected to a phone
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null) {
                s.btOn = bt.isEnabled();
                if (s.btOn) {
                    Object st = BluetoothAdapter.class.getMethod("getConnectionState").invoke(bt);
                    s.btConnected = st instanceof Integer && (Integer) st == BluetoothAdapter.STATE_CONNECTED;
                }
            }
        } catch (Throwable e) {
            Diag.error("conn.bluetooth", e.toString());
        }
        // Wi-Fi: connected to a network and strength 0..3
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI) : null;
            s.wifiConnected = ni != null && ni.isConnected();
            if (s.wifiConnected) {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
                s.wifiLevel = wi != null ? WifiManager.calculateSignalLevel(wi.getRssi(), 4) : 3;
            }
        } catch (Throwable e) {
            Diag.error("conn.wifi", e.toString());
        }
        // T-Box mobile network
        try {
            IBinder e = eng.binder();
            if (e != null) {
                if (hw == null || !hw.isBinderAlive()) {
                    hw = Rpc.callBinder(e, ENG, ENG_GET_SERVICE, p -> p.writeString("system_hardware"));
                }
                IBinder h = hw;
                if (h != null) {
                    int[] m = Rpc.call(h, HW, HW_GET_MOBILE, Rpc.NONE, p -> Rpc.readNullable(p, ConnClient::readMobile));
                    if (m != null) {
                        s.mobileType = m[0];
                        s.mobileLevel = m[1];
                    }
                }
            }
        } catch (Throwable e) {
            hw = null;
            Diag.error("conn.4g", e.toString());
        }
        Diag.putQuiet("conn.bt/wifi/network", (s.btConnected ? "BT connected" : s.btOn ? "BT on" : "BT off")
                + " · " + (s.wifiConnected ? "Wi-Fi " + s.wifiLevel + "/3" : "Wi-Fi no")
                + " · " + (s.mobileType < 0 ? "network ?" : s.mobileType == 0 ? "no network" : s.mobileType + "G " + s.mobileLevel + "/4"));
        last = s;
        main.post(() -> {
            if (listener != null) listener.onConn(s);
        });
    }

    /** MobileNetworkInfoBean: operator, standard, strength (string), area, IMSI, roaming, T-Box state. */
    private static int[] readMobile(Parcel p) {
        p.readString();                       // operator
        int standard = p.readInt();
        String strength = p.readString();
        p.readInt();                          // area code
        p.readString();                       // IMSI (unused)
        p.readInt();                          // roaming
        int tbox = p.readInt();
        if (tbox <= 0 || strength == null) return new int[]{0, 0};
        int value;
        try {
            value = Integer.parseInt(strength.trim());
        } catch (NumberFormatException ex) {
            return new int[]{0, 0};
        }
        int level = 0;
        if (value != 99) for (int i = SIGNAL_STEPS.length - 1; i >= 0; i--) if (value >= SIGNAL_STEPS[i]) { level = i; break; }
        int type;
        switch (standard) {
            case 1:
            case 3: type = 2; break;
            case 2:
            case 4:
            case 5: type = 3; break;
            case 6: type = 4; break;
            default: type = 0;
        }
        if (value == 99) type = 0;
        return new int[]{type, level};
    }
}
