package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Automatic restart of wireless CarPlay / Android Auto dongles that sometimes do not start until unplugged and
 * plugged back (e.g. "AutoKit-TBox Plus", "SmartBox").
 * <ul>
 *   <li>acts only with a recognized dongle: USB vendor of the dongles (Carlinkit 1314) or the USB device
 *   whose serial number already gave a session with a dongle name (the launcher learns it by itself). An iPhone or
 *   an Android phone connected by cable and music sticks are never touched;</li>
 *   <li>only if N seconds after power-on (20 by default) no CarPlay/AA session has started;</li>
 *   <li>at most 2 attempts per power-on: reset of the dongle's USB port (like unplugging and replugging it), then
 *   restart of the CarPlay/AA service (Allgo);</li>
 *   <li>0.9.8 (like the SWI68): no more "automatic" detection of any Apple/Android: wireless dongles show up on USB
 *   as an iPhone (05ac:12a8) or in Android accessory mode (18d1:2d00), just like phones on a cable, which were being
 *   reset. Now only these count as dongles: dongle vendor (Carlinkit), dongle USB name (e.g. "smartlinkBox",
 *   "OKBox") or a serial number that already gave a session with a dongle name. Apple, Android accessory mode
 *   and devices presenting themselves as a phone (name, class) are excluded in all other cases, and so are serial
 *   numbers that gave a session with a phone name. With an excluded phone connected nothing on USB is
 *   touched. The reason for each exclusion goes into the diagnostics (dongle.escluso);</li>
 *   <li>never during a call.</li>
 * </ul>
 * Every step goes into the diagnostics (dongle.* keys).
 */
public final class DongleWatch {
    private static final String PREFS = "dongle";
    /** USB vendors of dongles (not of phones): Carlinkit / "Magic Communication Tec.". */
    private static final int[] DONGLE_VIDS = {0x1314};
    private static final int APPLE_VID = 0x05AC, GOOGLE_AOAP_VID = 0x18D1;
    /** Names the dongles present to CarPlay/AA with (lowercase, no spaces). */
    private static final String[] DONGLE_NAMES = {"autokit", "carlinkit", "cpc200", "tbox", "smartbox", "ottocast",
            "u2w", "u2aw", "aawireless", "ma1", "carsifi", "cplay2air", "magicbox", "aibox", "wireless", "dongle", "adapter",
            "carplaybox", "playbox", "bmbox", "okbox", "smartlink", "carlink", "cpbox", "aabox", "linkbox", "aiplay",
            "vehiconn", "play2video", "carbox"};
    /** Phone names (lowercase, no spaces): USB or session with these names = phone, never reset. */
    private static final String[] PHONE_NAMES = {"iphone", "ipad", "pixel", "redmi", "xiaomi", "poco", "galaxy", "samsung",
            "oneplus", "huawei", "honor", "oppo", "vivo", "realme", "motorola", "moto", "nokia", "xperia", "sony", "nothing",
            "phone", "android"};
    private static final String[] ALLGO_PACKAGES = {"com.allgo.carplay.service", "com.allgo.app.androidauto", "com.allgo.rui"};

    private static DongleWatch instance;

    public static synchronized DongleWatch get(Context c, MediaClient media) {
        if (instance == null) instance = new DongleWatch(c.getApplicationContext(), media);
        return instance;
    }

    /** Instance already created by the launcher (null before the services start). */
    public static synchronized DongleWatch peek() {
        return instance;
    }

    private final Context ctx;
    private final MediaClient media;
    private final SharedPreferences sp;
    private final Handler bg;
    private boolean started;

    private DongleWatch(Context c, MediaClient media) {
        ctx = c;
        this.media = media;
        sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HandlerThread th = new HandlerThread("mg4-dongle");
        th.start();
        bg = new Handler(th.getLooper());
    }

    // ---- settings --------------------------------------------------------------------------

    public boolean enabled() {
        return sp.getBoolean("attivo", true);
    }

    public void setEnabled(boolean on) {
        sp.edit().putBoolean("attivo", on).apply();
        Diag.put("dongle.auto", on ? "on" : "off");
    }

    /** Seconds after power-on before acting. */
    public int delaySeconds() {
        return sp.getInt("secondi", 20);
    }

    public void setDelaySeconds(int s) {
        sp.edit().putInt("secondi", s).apply();
    }

    // ---- start -----------------------------------------------------------------------------

    public synchronized void start() {
        if (started) return;
        started = true;
        bg.post(this::logUsb);
        long wait = delaySeconds() * 1000L - SystemClock.elapsedRealtime();
        bg.postDelayed(this::check, Math.max(3000, wait));
        bg.postDelayed(this::learn, 15_000);
    }

    private int bootCount() {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Attempts already made in this power-on (stays correct even if the launcher restarts). */
    private int attempts() {
        return sp.getInt("boot", -1) == bootCount() ? sp.getInt("tentativi", 0) : 0;
    }

    private void setAttempts(int n) {
        sp.edit().putInt("boot", bootCount()).putInt("tentativi", n).apply();
    }

    private boolean sessionActive() {
        return media.last().deviceType != 0;
    }

    private void check() {
        if (sessionActive()) {
            Diag.put("dongle.result", attempts() == 0 ? "session started on its own" : "session started after " + attempts() + " attempts");
            learn();
            return;
        }
        if (!enabled()) {
            Diag.put("dongle.result", "no session, automatic restart off");
            return;
        }
        int n = attempts();
        if (n >= 2) {
            Diag.put("dongle.result", "no session after 2 attempts: nothing more this power-on");
            return;
        }
        if (inCall()) {
            Diag.put("dongle.result", "call in progress: postponing");
            bg.postDelayed(this::check, 15_000);
            return;
        }
        UsbDevice d = findDongle();
        String excl = excluded();
        if (d == null && !excl.isEmpty()) {
            Diag.put("dongle.result", "no session and no dongle: only " + excl + " connected: not touching the device");
            return;
        }
        if (d == null) {
            Diag.put("dongle.result", "no session, but no recognized dongle connected: not intervening");
            return;
        }
        setAttempts(n + 1);
        if (n == 0) {
            boolean ok = resetUsb(d);
            Diag.put("dongle.attempt1", "USB port reset " + describe(d) + ": " + (ok ? "done" : "failed"));
            checkReappear("dongle.afterReset", d.getVendorId(), d.getProductId());
            if (!ok) {   // go straight to the second
                bg.postDelayed(this::check, 2000);
                return;
            }
        } else {
            Diag.put("dongle.attempt2", "CarPlay/AA service restart: " + restartAllgo());
        }
        bg.postDelayed(this::check, delaySeconds() * 1000L);
    }

    /** "Riavvia CarPlay/AA" button: dongle reset if recognized, then service restart. */
    public void restartNow(Runnable onDone) {
        bg.post(() -> {
            String res;
            if (inCall()) {
                res = "call in progress: not restarted";
            } else {
                UsbDevice d = findDongle();
                StringBuilder sb = new StringBuilder();
                String excl = excluded();
                if (d == null && !excl.isEmpty()) sb.append("only ").append(excl).append(" connected: USB not touched; ");
                if (d != null) sb.append("reset USB ").append(describe(d)).append(": ").append(resetUsb(d) ? "done" : "failed").append("; ");
                sb.append("service: ").append(restartAllgo());
                res = sb.toString();
            }
            Diag.put("dongle.manual", res);
            if (onDone != null) new Handler(android.os.Looper.getMainLooper()).post(onDone);
        });
    }

    public boolean inCall() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int m = am == null ? 0 : am.getMode();
            if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE) return true;
        } catch (Throwable ignored) {
        }
        return media.remoteCallActive();
    }

    // ---- detection -------------------------------------------------------------------------

    private static boolean dongleName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.isEmpty() || n.contains("iphone") || n.contains("ipad")) return false;
        for (String d : DONGLE_NAMES) if (n.contains(d)) return true;
        return false;
    }

    private Set<String> learned() {
        return new HashSet<>(sp.getStringSet("seriali", new HashSet<>()));
    }

    private Set<String> phones() {
        return new HashSet<>(sp.getStringSet("telefoni", new HashSet<>()));
    }

    /**
     * Active session: with a dongle name, the serial numbers of the connected Apple/AOAP/dongle USB devices are remembered;
     * with a phone name (e.g. "iPhone 16 Pro max", "REDMI Note…") that serial number becomes a phone, which is
     * never reset. The last session seen counts.
     */
    private void learn() {
        MediaClient.State m = media.last();
        if (m.deviceType == 0) return;
        boolean dongle = dongleName(m.deviceName);
        Set<String> s = learned(), ph = phones();
        boolean changed = false;
        for (UsbDevice d : devices()) {
            int vid = d.getVendorId();
            if (vid != APPLE_VID && vid != GOOGLE_AOAP_VID && !isDongleVid(vid) && !dongleName(product(d))) continue;
            String serial = serial(d);
            if (serial.isEmpty()) {
                Diag.putQuiet("dongle.serial", describe(d) + ": serial number not readable");
                continue;
            }
            if (dongle) changed |= s.add(serial) | ph.remove(serial);
            else changed |= ph.add(serial) | s.remove(serial);
        }
        if (changed) {
            sp.edit().putStringSet("seriali", s).putStringSet("telefoni", ph).apply();
            if (dongle) Diag.put("dongle.detected", m.deviceName + " (" + s.size() + " serial numbers stored)");
            // 1.0.4: no phone name in the log (personal data)
            else Diag.put("dongle.phoneDetected", "session with a phone: the USB device is a phone, it will never be reset");
        }
    }

    private static boolean isDongleVid(int vid) {
        for (int v : DONGLE_VIDS) if (v == vid) return true;
        return false;
    }

    /**
     * Only a real wireless dongle (0.9.8): dongle vendor, dongle USB name (e.g. "OKBox", "SmartBox",
     * "smartlinkBox", also with 18d1:2d00 or 2717:ff40) or serial number already seen in a session with a dongle name.
     * A serial number seen in a session with a phone name is never a dongle.
     */
    private UsbDevice findDongle() {
        Set<String> s = learned(), ph = phones();
        for (UsbDevice d : devices()) {
            if (systemDevice(d)) continue;
            String serial = serial(d);
            if (!serial.isEmpty() && ph.contains(serial)) continue;
            if (isDongleVid(d.getVendorId()) || dongleName(product(d))) return d;
            if (!serial.isEmpty() && s.contains(serial)) return d;
        }
        return null;
    }

    private static boolean phoneName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.isEmpty()) return false;
        for (String p : PHONE_NAMES) if (n.contains(p)) return true;
        return n.matches("sm[a-z]?\\d{3}[a-z0-9]{0,3}");   // Samsung models, e.g. "SM-S918B"
    }

    /** The device presents itself as a phone: name (product or manufacturer) or class (communications, photo/MTP). */
    private static String phoneLike(UsbDevice d) {
        String p = product(d), m = "";
        try {
            m = d.getManufacturerName();
        } catch (Throwable ignored) {
        }
        if (phoneName(p)) return "name \"" + p + "\"";
        if (phoneName(m)) return "manufacturer \"" + m + "\"";
        if (d.getDeviceClass() == 2) return "class 2 (communications)";
        try {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                int c = d.getInterface(i).getInterfaceClass();
                if (c == 6) return "interface class 6 (photo/MTP)";
                if (c == 2) return "interface class 2 (communications)";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Devices excluded from the restart, with the reason (written in dongle.escluso): Apple, Android accessory mode
     * (18d1:2d00–2d05), devices presenting themselves as a phone or already seen as a phone. "" if none.
     */
    private String excluded() {
        Set<String> s = learned(), ph = phones();
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : devices()) {
            if (systemDevice(d) || isDongleVid(d.getVendorId()) || dongleName(product(d))) continue;
            String serial = serial(d);
            if (!serial.isEmpty() && s.contains(serial) && !ph.contains(serial)) continue;   // dongle already recognized
            int vid = d.getVendorId(), pid = d.getProductId();
            String why = null;
            if (!serial.isEmpty() && ph.contains(serial)) why = "already seen in a session with a phone name";
            else if (vid == APPLE_VID) why = "Apple (05ac), not recognized as a dongle";
            else if (vid == GOOGLE_AOAP_VID && pid >= 0x2D00 && pid <= 0x2D05)
                why = "Android accessory mode (18d1:" + Integer.toHexString(pid) + "), not recognized as a dongle";
            else {
                String ph2 = phoneLike(d);
                if (ph2 != null) why = "presents itself as a phone (" + ph2 + ")";
            }
            if (why == null) continue;
            sb.append(sb.length() == 0 ? "" : "; ").append(describe(d)).append(" excluded: ").append(why);
        }
        String v = sb.toString();
        if (!v.isEmpty()) Diag.put("dongle.excluded", v);
        return v;
    }

    private static String product(UsbDevice d) {
        try {
            String p = d.getProductName();
            return p == null ? "" : p;
        } catch (Throwable e) {
            return "";
        }
    }

    /** Always-connected system devices (root hub, modem, STM32) or hubs: never dongles nor phones. */
    private static boolean systemDevice(UsbDevice d) {
        int v = d.getVendorId();
        return d.getDeviceClass() == 9 || v == 0x1D6B || v == 0x05C6 || v == 0x0483;
    }

    private Iterable<UsbDevice> devices() {
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um != null) return um.getDeviceList().values();
        } catch (Throwable e) {
            Diag.error("dongle.usb", e.toString());
        }
        return new java.util.ArrayList<>();
    }

    private String serial(UsbDevice d) {
        try {
            grant(d);
            String s = d.getSerialNumber();
            return s == null ? "" : s;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String describe(UsbDevice d) {
        String p = "";
        try {
            p = d.getProductName();
        } catch (Throwable ignored) {
        }
        return String.format(Locale.ROOT, "%04x:%04x %s", d.getVendorId(), d.getProductId(), p == null ? "" : p).trim();
    }

    private void logUsb() {
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : devices()) {
            String m = "";
            try {
                m = d.getManufacturerName();
            } catch (Throwable ignored) {
            }
            sb.append(sb.length() == 0 ? "" : " | ").append(describe(d)).append(m == null || m.isEmpty() ? "" : " (" + m + ")")
                    .append(" class ").append(d.getDeviceClass());
        }
        Diag.put("usb.devices", sb.length() == 0 ? "none" : sb.toString());
    }

    // ---- actions ---------------------------------------------------------------------------

    /** USB permission granted by the launcher itself (MANAGE_USB, system signature), without a confirmation dialog. */
    private void grant(UsbDevice d) {
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um == null || um.hasPermission(d)) return;
            UsbManager.class.getMethod("grantPermission", UsbDevice.class).invoke(um, d);
        } catch (Throwable ignored) {
        }
    }

    /** After a port reset or power cycle: records whether the dongle reappears on the bus (1.x). */
    private void checkReappear(String tag, int vid, int pid) {
        bg.postDelayed(() -> {
            try {
                UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
                boolean found = false, any = false;
                for (UsbDevice x : um.getDeviceList().values()) {
                    if (isDongleVid(x.getVendorId())) any = true;
                    if (vid != 0 && x.getVendorId() == vid && x.getProductId() == pid) found = true;
                }
                String outcome = vid != 0 ? (found ? "reappeared" : "did NOT reappear") : (any ? "a dongle is visible" : "no dongle visible");
                Diag.put(tag, outcome + " 8 s after the reset");
            } catch (Throwable e) {
                Diag.error(tag, e.toString());
            }
        }, 8000);
    }

    /** USB port reset (like unplugging and replugging), the same one the Allgo service uses. */
    private boolean resetUsb(UsbDevice d) {
        UsbDeviceConnection c = null;
        try {
            grant(d);
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            c = um.openDevice(d);
            if (c == null) {
                Diag.error("dongle.reset", "openDevice failed (permission?)");
                return false;
            }
            Object r = UsbDeviceConnection.class.getMethod("resetDevice").invoke(c);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Diag.error("dongle.reset", t.toString());
            return false;
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Stops the CarPlay/AA services and restarts RemoteUIService (the launcher then reconnects by itself). */
    private String restartAllgo() {
        StringBuilder sb = new StringBuilder();
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            java.lang.reflect.Method fs = android.app.ActivityManager.class.getMethod("forceStopPackage", String.class);
            for (String p : ALLGO_PACKAGES) {
                try {
                    ctx.getPackageManager().getPackageInfo(p, 0);
                } catch (Exception e) {
                    continue;
                }
                fs.invoke(am, p);
                sb.append(p.substring(p.lastIndexOf('.') + 1)).append(" stopped; ");
            }
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            return "stop failed: " + t;
        }
        SystemClock.sleep(1500);
        try {
            ctx.startService(new Intent().setComponent(new ComponentName("com.allgo.rui", "com.allgo.rui.RemoteUIService")));
            sb.append("RemoteUIService restarted");
        } catch (Throwable e) {
            sb.append("restart: ").append(e.getClass().getSimpleName()).append(" (restarts on reconnection)");
        }
        return sb.toString();
    }
}
