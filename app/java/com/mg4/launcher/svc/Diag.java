package com.mg4.launcher.svc;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostics: latest values read from the services, recent errors and events.
 * Visible from the launcher settings, on logcat (tag MG4L), and can be saved to a log file.
 */
public final class Diag {
    private static final String TAG = "MG4L";
    private static final Map<String, String> VALUES = new TreeMap<>();
    private static final ArrayDeque<String> ERRORS = new ArrayDeque<>();
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();

    private Diag() {}

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date());
    }

    public static synchronized void put(String key, Object value) {
        String v = String.valueOf(value);
        String old = VALUES.put(key, v);
        if (!v.equals(old)) {
            Log.i(TAG, key + " = " + v);
            EVENTS.addLast(now() + "  " + key + " = " + v);
            while (EVENTS.size() > 400) EVENTS.removeFirst();
        }
    }

    /** 0.4.3: latest value of an entry ("—" if missing). */
    public static synchronized String value(String key) {
        String v = VALUES.get(key);
        return v == null ? "—" : v;
    }

    /** Like put, but without filling the events (values that change constantly, e.g. current). */
    public static synchronized void putQuiet(String key, Object value) {
        VALUES.put(key, String.valueOf(value));
    }

    public static synchronized void error(String where, String msg) {
        String line = now() + "  " + where + ": " + msg;
        Log.w(TAG, line);
        EVENTS.addLast(line);
        while (EVENTS.size() > 400) EVENTS.removeFirst();
        if (!ERRORS.isEmpty() && ERRORS.peekLast().endsWith(where + ": " + msg)) return;
        ERRORS.addLast(line);
        while (ERRORS.size() > 40) ERRORS.removeFirst();
    }

    /**
     * Network error for the log: message with the innermost cause (e.g. "Unacceptable certificate … ← reason") and, for
     * certificate errors, the car clock if it is behind (with the wrong date no certificate is valid).
     */
    public static String netError(Throwable e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        Throwable c = e.getCause();
        for (int i = 0; c != null && i < 4; i++, c = c.getCause()) {
            String m = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
            if (sb.indexOf(m) < 0) sb.append(" ← ").append(m);
        }
        boolean cert = false;
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof javax.net.ssl.SSLException || t instanceof java.security.cert.CertificateException) cert = true;
        if (cert && System.currentTimeMillis() < 1735689600000L)   // before 2025
            sb.append(" · car clock at ").append(new SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(new Date()));
        return sb.toString();
    }

    /** Exception with the first stack lines, for the log. */
    public static String trace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < 12; i++) sb.append(i == 0 ? "" : "\n      ").append(lines[i].trim());
        return sb.toString();
    }

    /** Saves the diagnostics to Android/data/com.mg4.launcher/files/diagnostics.txt (retrieve with adb pull). */
    public static void save(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            String text = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(new Date()) + "\n\n" + dump();
            try (FileOutputStream o = new FileOutputStream(new File(dir, "diagnostics.txt"))) {
                o.write(text.getBytes("UTF-8"));
            }
            // 0.4.11: the file used to be called diagnostica.txt; the stale copy is removed
            File old = new File(dir, "diagnostica.txt");
            //noinspection ResultOfMethodCallIgnored
            if (old.exists()) old.delete();
        } catch (Exception e) {
            Log.w(TAG, "saving diagnostics", e);
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : VALUES.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        if (!ERRORS.isEmpty()) {
            sb.append("\nRECENT ERRORS\n");
            for (String s : ERRORS) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    private static synchronized String events() {
        StringBuilder sb = new StringBuilder();
        for (String s : EVENTS) sb.append(s).append('\n');
        return sb.toString();
    }

    /** Launcher logcat lines (an app can always read its own). */
    private static String logcat() {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-t", "1500",
                    "--pid=" + android.os.Process.myPid()});
            int skipped = 0, dropped = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.contains("MG4L")) {
                        sb.append(l).append('\n');
                    } else if (l.contains("Skipped ") && l.contains(" frames")) {
                        if (skipped++ == 0) sb.append(l).append('\n'); else dropped++;   // only the first
                    } else if (noise(l)) {
                        dropped++;
                    } else {
                        sb.append(l).append('\n');
                    }
                }
            }
            p.waitFor();
            if (dropped > 0) sb.append("(").append(dropped).append(" repetitive system lines omitted)\n");
        } catch (Exception e) {
            sb.append("logcat unavailable: ").append(e).append('\n');
        }
        return sb.toString();
    }

    /** System lines that do not help understand launcher problems (repeated by the hundreds). */
    private static final String[] NOISE = {
            "disconnect failed", "ioctl failed", "jdwp agent", "chatty", "gralloc", "Background concurrent copying",
            "Background young concurrent copying", "Explicit concurrent copying"};

    static boolean noise(String l) {
        for (String n : NOISE) if (l.contains(n)) return true;
        return false;
    }

    /** Recent crashes of all apps (logcat crash buffer, needs READ_LOGS granted by the platform signature). */
    private static String crashes() {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-b", "crash", "-v", "time", "-t", "300"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            p.waitFor();
        } catch (Exception e) {
            sb.append("unavailable: ").append(e).append('\n');
        }
        return sb.length() == 0 ? "none\n" : sb.toString();
    }

    /**
     * 1.0.4 (point C3): logcat lines with tag "RadioFix" (the radio service patch writes AF frequency changes,
     * signal, choices there). Unique lines (ignoring the leading time for comparison), at most the last 300.
     * If the patch is not installed: "radiofix = absent". Needs READ_LOGS (platform signature).
     */
    static String radioFix(Context c) {
        String ver = null;
        // 0.4.4: on the SWI69 the radio service is com.saicmotor.radio; the SWI68 package is checked too
        for (String pk : new String[]{"com.saicmotor.radio", "com.saicmotor.service.radio"}) {
            try {
                String v = c.getPackageManager().getPackageInfo(pk, 0).versionName;
                if (ver == null || v != null && v.toLowerCase(Locale.ROOT).contains("radiofix")) ver = v;
            } catch (Exception ignored) {
            }
        }
        boolean installed = ver != null && ver.toLowerCase(Locale.ROOT).contains("radiofix");
        StringBuilder sb = new StringBuilder();
        if (!installed) {
            put("radiofix", "absent (radio service " + (ver == null ? "not found" : ver) + ")");
            return "radiofix = absent\n";
        }
        java.util.LinkedHashMap<String, String> uniq = new java.util.LinkedHashMap<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-s", "RadioFix:*"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("-----") || l.trim().isEmpty()) continue;
                    // key without date and time: identical lines are kept only once (the last one)
                    int i = l.indexOf(' ', l.indexOf(' ') + 1);
                    String key = i > 0 ? l.substring(i + 1) : l;
                    uniq.remove(key);
                    uniq.put(key, l);
                }
            }
            p.waitFor();
        } catch (Exception e) {
            sb.append("logcat unavailable: ").append(e).append('\n');
        }
        List<String> lines = new ArrayList<>(uniq.values());
        int from = Math.max(0, lines.size() - 300);
        for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        put("radiofix", "present (" + ver + ") · " + lines.size() + " unique lines" + (from > 0 ? ", kept the last 300" : ""));
        return sb.length() == 0 ? "no RadioFix lines in logcat\n" : sb.toString();
    }

    /**
     * Fork 0.4.10: system lines from Android Auto / CarPlay (AllGo), USB and dead processes, to understand projection
     * drops (the launcher only saw its own). Current pids of com.allgo.* apps + keywords; last 800 lines.
     */
    static String projectionLog(Context c) {
        java.util.Set<String> pids = new java.util.HashSet<>();
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo pi : ps)
                if (pi.processName != null && pi.processName.startsWith("com.allgo")) pids.add(String.valueOf(pi.pid));
        } catch (Exception ignored) {
        }
        String[] keys = {"allgo", "AllGo", "Allgo", "AAP", "Aap", "AndroidAuto", "androidauto", "CarPlay", "carplay",
                "Projection", "projection", "UsbDeviceManager", "UsbHostManager", "usb_", "USB disconnect", "accessory",
                "has died", "Process ", "lowmemorykiller", "am_kill", "am_proc_died", "am_crash", "am_anr", "ANR in",
                "Killing ", "FATAL", "audio_hw", "AudioFocus", "AudioPolicy"};
        java.util.ArrayDeque<String> last = new java.util.ArrayDeque<>();
        int total = 0;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-b", "main,system,events", "-v", "time", "-t", "20000"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.contains("MG4L") || noise(l)) continue;
                    boolean hit = false;
                    int a = l.indexOf('('), b = l.indexOf(')');
                    if (a > 0 && b > a) hit = pids.contains(l.substring(a + 1, b).trim());
                    if (!hit) for (String k : keys) if (l.contains(k)) { hit = true; break; }
                    if (!hit) continue;
                    total++;
                    last.addLast(l);
                    if (last.size() > 800) last.removeFirst();
                }
            }
            p.waitFor();
        } catch (Exception e) {
            return "logcat unavailable: " + e + "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("pid AllGo: ").append(pids.isEmpty() ? "none" : pids.toString()).append(" · lines ").append(total)
                .append(total > 800 ? " (last 800)" : "").append('\n');
        for (String l : last) sb.append(l).append('\n');
        return sb.toString();
    }

    private static String climate(Context c) {
        try {
            return com.mg4.launcher.climate.ClimateStore.text(c);
        } catch (Throwable t) {
            return "unavailable: " + t;
        }
    }

    /** Full log text: version, current values, recent events, launcher logcat (same format as the SWI69). */
    public static String buildLog(Context c) {
        StringBuilder sb = new StringBuilder();
        String version = "?";
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) {
        }
        sb.append("MG4 Launcher ").append(version).append('\n');
        sb.append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(new Date())).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · ").append(Build.DISPLAY).append("\n\n");
        sb.append("===== CURRENT VALUES =====\n").append(dump()).append('\n');
        sb.append("===== RECENT EVENTS =====\n").append(events()).append('\n');
        sb.append("===== RECENT CRASHES (all apps) =====\n").append(crashes()).append('\n');
        sb.append("===== ANDROID AUTO / CARPLAY / USB (system) =====\n").append(projectionLog(c)).append('\n');
        sb.append("===== CLIMATE SCREEN (built-in MG4 Clima) =====\n").append(climate(c)).append('\n');
        sb.append("===== RadioFix =====\n").append(radioFix(c)).append('\n');
        sb.append("===== LAUNCHER LOGCAT =====\n").append(logcat());
        return sb.toString();
    }

    /** Log save result: written paths and text to show. */
    public static final class Saved {
        public final List<String> paths = new ArrayList<>();
        public boolean onUsb;
    }

    /**
     * Saves a full log (values, errors, events, launcher logcat). Writes it to the app folder
     * on every available storage — including the USB stick, if inserted — and, if possible, to Download.
     * Must be called off the main thread.
     */
    public static Saved saveLog(Context c) {
        Saved out = new Saved();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ITALY).format(new Date());
        String name = "mg4-launcher-log-" + stamp + ".txt";
        String text = buildLog(c);
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Exception e) {
            data = text.getBytes();
        }

        // fork: first into folders VISIBLE in the car's file manager and on a computer:
        //   USB stick → <root>/MG4Launcher/logs/   ·   car storage → Download/MG4Launcher/
        // (the app's Android/data/... is only a fallback: many file managers do not show it)
        grantWriteStorage(c);
        List<File> visible = new ArrayList<>();
        File[] vols = new File("/storage").listFiles();
        if (vols != null) for (File v : vols) {
            String n = v.getName();
            if (n.equals("emulated") || n.equals("self") || !v.isDirectory() || !v.canRead()) continue;
            visible.add(new File(v, "MG4Launcher/logs"));   // USB stick
        }
        try {
            visible.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MG4Launcher"));
        } catch (Exception ignored) {
        }
        List<String> scan = new ArrayList<>();
        for (File dir : visible) {
            try {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(data);
                }
                out.paths.add(f.getAbsolutePath());
                scan.add(f.getAbsolutePath());
                if (!dir.getAbsolutePath().startsWith(Environment.getExternalStorageDirectory().getAbsolutePath())) out.onUsb = true;
            } catch (Exception e) {
                Log.w(TAG, "log not saved in " + dir + ": " + e);
            }
        }
        if (!scan.isEmpty()) {
            // so the file shows up right away in file managers / on a computer via MTP
            try {
                android.media.MediaScannerConnection.scanFile(c, scan.toArray(new String[0]), null, null);
            } catch (Throwable ignored) {
            }
        }

        List<File> dirs = new ArrayList<>();
        try {
            File[] ext = c.getExternalFilesDirs(null);
            if (ext != null) for (File f : ext) if (f != null) dirs.add(f);
        } catch (Exception ignored) {
        }
        dirs.add(c.getFilesDir());
        File primary = null;
        try {
            primary = c.getExternalFilesDir(null);
        } catch (Exception ignored) {
        }
        for (File dir : dirs) {
            try {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(data);
                }
                out.paths.add(f.getAbsolutePath());
                boolean removable = false;
                try {
                    removable = Environment.isExternalStorageRemovable(dir);
                } catch (Exception ignored) {
                }
                if (removable || (primary != null && !dir.equals(primary) && dir.getAbsolutePath().startsWith("/storage/")
                        && !dir.getAbsolutePath().startsWith("/storage/emulated"))) out.onUsb = true;
            } catch (Exception e) {
                Log.w(TAG, "log not saved in " + dir + ": " + e);
            }
        }
        put("log.saved", out.paths.isEmpty() ? "no writable folder" : out.paths.get(0));
        return out;
    }

    /** Writing to shared storage: the launcher grants itself the permission (platform signature, GRANT_RUNTIME_PERMISSIONS). */
    private static void grantWriteStorage(Context c) {
        String perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
        if (c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, c.getPackageName(), perm, android.os.Process.myUserHandle());
            put("log.writePermission", "granted now");
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            put("log.writePermission", "not granted (" + t.getClass().getSimpleName() + ")");
        }
    }
}
