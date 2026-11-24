package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.4.3 (point 7): Bluetooth phone for the "Phone favourites" in the box below the station.
 * <ul>
 *   <li>SWI69 (R45): there is no com.saicmotor.service.btcall service as on the SWI68. The original Phone app
 *       (com.saicmotor.btphone, BTCall_eh32_ll) exposes BTPhoneService with the action com.saicmotor.btphone.voice and
 *       the interface com.saicmotor.btphone.IVoiceBTPhoneService (the one used by the voice assistant):
 *       1 = call the number (0 = started, 128 empty number, 129 invalid number, 131 rejected).</li>
 *   <li>phone connected: car-side Bluetooth HFP profile (16) connected, as the Phone app does.</li>
 *   <li>contacts: Bluetooth (PBAP) downloads them into the Android contacts; the Phone app reads them from there
 *       (ContactsContract Phone), as done here.</li>
 * </ul>
 * The call is placed by the phone service: the launcher does not need CALL_PHONE. Never names or numbers in the logs.
 */
public final class PhoneClient {
    public interface Listener {
        void onPhoneChanged();
    }

    public static final class Contact {
        public final String name, number;

        public Contact(String name, String number) {
            this.name = name == null ? "" : name;
            this.number = number == null ? "" : number;
        }
    }

    private static final String PKG = "com.saicmotor.btphone", ACTION = "com.saicmotor.btphone.voice";
    private static final String DESC = "com.saicmotor.btphone.IVoiceBTPhoneService";
    private static final int PLACE_CALL = 1;
    /** BluetoothProfile.HEADSET_CLIENT (hidden): 16. */
    private static final int PROFILE_HFP_CLIENT = 16;
    private static final String HFP_CHANGED = "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED";

    private static PhoneClient instance;

    private final Context ctx;
    private final ServiceLink link;
    private final Handler work;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile int btState = -1, syncState = -1;
    private android.content.BroadcastReceiver receiver;
    private int users;

    private PhoneClient(Context c) {
        ctx = c.getApplicationContext();
        HandlerThread t = new HandlerThread("mg4-phone");
        t.start();
        work = new Handler(t.getLooper());
        link = new ServiceLink(ctx, "phone", ServiceLink.byAction(PKG, ACTION));
        link.setOnConnected(this::refresh);
    }

    public static synchronized PhoneClient get(Context c) {
        if (instance == null) instance = new PhoneClient(c);
        return instance;
    }

    /** Connects to the service (ref-counted: disconnects when the last user calls stop). */
    public synchronized void start(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
        if (users++ == 0) {
            link.start();
            try {
                receiver = new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, android.content.Intent i) {
                        refresh();
                    }
                };
                android.content.IntentFilter f = new android.content.IntentFilter(HFP_CHANGED);
                f.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
                ctx.registerReceiver(receiver, f);
            } catch (Throwable e) {
                receiver = null;
            }
            refresh();
        }
    }

    public synchronized void stop(Listener l) {
        if (l != null) listeners.remove(l);
        if (users > 0 && --users == 0) {
            if (receiver != null) {
                try {
                    ctx.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                }
                receiver = null;
            }
            link.stop();
        }
    }

    public boolean serviceConnected() {
        return link.binder() != null;
    }

    /** Phone connected via Bluetooth (HFP). */
    public boolean phoneConnected() {
        return btState == 2;
    }

    public int btState() {
        return btState;
    }

    /** Contacts state: 2 = there are contacts, 0 = empty, -1 = not read. */
    public int syncState() {
        return syncState;
    }

    /** Re-reads the phone state (on the phone thread). */
    public void refresh() {
        work.post(() -> {
            int bs;
            try {
                android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (ad == null) bs = -1;
                else if (!ad.isEnabled()) bs = 0;
                else bs = ad.getProfileConnectionState(PROFILE_HFP_CLIENT) == 2 ? 2 : 1;
            } catch (Throwable e) {
                bs = -1;
            }
            boolean changed = bs != btState;
            btState = bs;
            Diag.put("phone.state", (link.binder() == null ? "phone service not connected · " : "phone service connected · ")
                    + "Bluetooth " + btTxt(bs));
            if (changed) notifyListeners();
        });
    }

    private static String btTxt(int s) {
        switch (s) {
            case 0: return "off";
            case 1: return "on, no phone";
            case 2: return "phone connected";
            default: return "?";
        }
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener l : listeners) l.onPhoneChanged();
        });
    }

    /** Number cleaned as the Phone app does: no spaces, dashes, parentheses. */
    public static String clean(String n) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : n.toCharArray()) if (Character.isDigit(ch) || ch == '+' || ch == '*' || ch == '#') sb.append(ch);
        return sb.toString();
    }

    /** Calls the number through the phone service. Result in the log without the number. */
    public void call(String number, java.util.function.Consumer<Boolean> done) {
        String n = clean(number);
        work.post(() -> {
            boolean ok = false;
            String why;
            IBinder b = link.binder();
            if (n.isEmpty()) why = "empty number";
            else if (b == null) why = "phone service not connected";
            else {
                try {
                    Integer r = Rpc.call(b, DESC, PLACE_CALL, p -> p.writeString(n), Parcel::readInt);
                    ok = r != null && r == 0;
                    why = ok ? "started" : "refused by the service (code " + r + ": phone not connected or invalid number)";
                } catch (Exception e) {
                    why = "error " + e.getClass().getSimpleName();
                }
            }
            Diag.put("phone.call", why + " · " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date()));
            if (done != null) {
                boolean res = ok;
                main.post(() -> done.accept(res));
            }
        });
    }

    /**
     * Contacts of the connected phone (name + number, no duplicates), in alphabetical order. Empty if the phone is not
     * connected or the contacts are not synced. To be called off the main thread.
     */
    public List<Contact> contacts() {
        List<Contact> out = new ArrayList<>();
        ensureContactsPermission(ctx);
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{"display_name", "data1"}, null, null, "display_name COLLATE NOCASE ASC");
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (c != null && c.moveToNext() && out.size() < 2000) {
                String name = c.getString(0), num = clean(c.getString(1));
                if (num.isEmpty() || name == null || name.isEmpty() || "本机号码".equals(name)) continue;
                if (!seen.add(name + "|" + num)) continue;
                out.add(new Contact(name, num));
            }
            syncState = out.isEmpty() ? 0 : 2;
            Diag.put("phone.contacts", out.size() + " numbers read");
        } catch (Throwable e) {
            Diag.error("phone.contacts", e.getClass().getSimpleName());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** The launcher is a system app: it grants itself the contacts permission (like location). */
    static void ensureContactsPermission(Context c) {
        String perm = "android.permission.READ_CONTACTS";
        if (c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, c.getPackageName(), perm, android.os.Process.myUserHandle());
        } catch (Throwable ignored) {
        }
    }
}
