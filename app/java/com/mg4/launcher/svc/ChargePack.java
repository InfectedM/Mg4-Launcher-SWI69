package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 1.0.4 (point F): charging animation packs. The clips are NOT in the APK: only the zip of the color chosen in the
 * settings is downloaded, from releases/animazioni.json of this repository (packs in the repository's animazioni/ folder).
 * <ul>
 *   <li>in the background, resuming if the download is interrupted (Range), with sha256 and size check;</li>
 *   <li>if the color changes, the new one is downloaded and the old one deleted;</li>
 *   <li>if animazioni.json or the pack is missing: no animation, no error on screen.</li>
 * </ul>
 * The 8 clips are extracted into files/animazione/&lt;color&gt;/ (carica-&lt;color&gt;-&lt;giorno|notte&gt;-&lt;phase&gt;.mp4).
 */
public final class ChargePack {
    public static final String[] PHASES = {"aggancio", "loop", "finita", "sgancio"};
    private static final String JSON_URL = Updater.OWN_RAW + "releases/animazioni.json";
    private static final long CHECK_EVERY_MS = 12 * 60 * 60_000L, RETRY_MISSING_MS = 15 * 60_000L;

    private static volatile String state = "not checked";
    private static volatile boolean busy;
    private static volatile boolean failed;

    private ChargePack() {}

    /** "Arctic White" → "arctic-white" (file and zip names). */
    public static String slug(String color) {
        return color.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    public static File root(Context c) {
        return new File(c.getFilesDir(), "animazione");
    }

    public static File dir(Context c, String color) {
        return new File(root(c), slug(color));
    }

    /** Clip of a phase, null if the color pack is not complete. */
    public static File clip(Context c, String color, boolean day, String phase) {
        File f = new File(dir(c, color), "carica-" + slug(color) + "-" + (day ? "giorno" : "notte") + "-" + phase + ".mp4");
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** True if all 8 clips of the color are present. */
    public static boolean complete(Context c, String color) {
        for (String ph : PHASES) {
            if (clip(c, color, true, ph) == null || clip(c, color, false, ph) == null) return false;
        }
        return true;
    }

    public static String state() {
        return state;
    }

    public static boolean failed() {
        return failed;
    }

    public static boolean busy() {
        return busy;
    }

    /**
     * Checks the pack of the chosen color and downloads it if needed (separate thread). force = true skips the wait
     * between one check and the next (color change).
     */
    public static synchronized void ensure(Context ctx, String color, boolean force) {
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        if (busy) return;
        busy = true;
        // everything (folders, check, download) off the main thread
        new Thread(() -> {
            try {
                removeOthers(c, color);
                // 0.4.4 (fix B): every 12 hours (or when forced) check whether the pack changed on GitHub
                String key = "animControllo." + slug(color);
                long last = sp.getLong(key, 0);
                boolean due = force || last <= 0 || System.currentTimeMillis() - last >= CHECK_EVERY_MS;
                if (complete(c, color)) {
                    // pack in use: not replaced while the cable is plugged in (the animation might be playing)
                    boolean plugged = VehicleClient.cablePluggedNow();
                    if (due && !plugged) sp.edit().putLong(key, System.currentTimeMillis()).apply();
                    if (!due || plugged || !changed(c, color)) {
                        state = "pack " + color + " present";
                        failed = false;
                        buildFrames(c, color);
                        generation++;
                        return;
                    }
                } else {
                    // pack missing: after a failed attempt, retry after 15 minutes, not after 12 hours
                    long lastTry = sp.getLong(key + ".tentativo", 0);
                    if (!force && lastTry > 0 && System.currentTimeMillis() - lastTry < RETRY_MISSING_MS) return;
                    sp.edit().putLong(key + ".tentativo", System.currentTimeMillis()).apply();
                }
                download(c, color);
                if (complete(c, color)) sp.edit().putLong(key, System.currentTimeMillis()).apply();
                if (complete(c, color)) buildFrames(c, color);
                generation++;
            } finally {
                busy = false;
            }
        }, "mg4-animation").start();
    }

    /** Frames of all clips of the color (only once: afterwards ChargeFrames finds them ready). */
    private static void buildFrames(Context c, String color) {
        java.util.List<File> all = new java.util.ArrayList<>();
        for (String ph : PHASES) {
            all.add(clip(c, color, true, ph));
            all.add(clip(c, color, false, ph));
        }
        ChargeFrames.build(all.toArray(new File[0]));
    }

    /** Frames of the clips in a test folder (USB stick). */
    public static String buildFramesIn(File dir, String slug) {
        java.util.List<File> all = new java.util.ArrayList<>();
        for (String ph : PHASES) {
            all.add(clipIn(dir, slug, true, ph));
            all.add(clipIn(dir, slug, false, ph));
        }
        return ChargeFrames.build(all.toArray(new File[0]));
    }

    /** Increases when the pack changes (downloaded, deleted): whoever caches complete() recomputes it. */
    public static volatile int generation;

    /** Only the chosen color is kept: folders and partial zips of the other colors are deleted. */
    private static void removeOthers(Context c, String color) {
        File[] list = root(c).listFiles();
        if (list == null) return;
        String keep = slug(color);
        for (File f : list) {
            String n = f.getName();
            if (n.equals(keep) || n.equals(keep + ".zip.part") || n.equals("prova")) continue;
            deleteTree(f);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** File with the sha256 fingerprint of the installed pack, in the color folder. */
    private static final String SHA_FILE = "sha256";

    /**
     * 0.4.4 (fix B): the color's entry in animazioni.json; null if the json cannot be read or the color is missing.
     * Call only from the worker thread (network).
     */
    private static JSONObject entry(String color) throws Exception {
        JSONObject j = new JSONObject(Updater.getText(JSON_URL));
        JSONObject colors = j.optJSONObject("colori");
        return colors == null ? null : colors.optJSONObject(color);
    }

    /**
     * 0.4.4 (fix B): true if the installed pack differs from the one in animazioni.json (must be downloaded again).
     * Unreadable json or entry without sha256 → false. Folder without the "sha256" file (packs downloaded by 0.4.4 and
     * earlier do not have it): the current one is written and false is returned, without downloading again.
     */
    private static boolean changed(Context c, String color) {
        String want;
        try {
            JSONObject e = entry(color);
            want = e == null ? "" : e.optString("sha256", "").toLowerCase(Locale.ROOT).trim();
        } catch (Exception ex) {
            return false;
        }
        if (want.isEmpty()) return false;
        File f = new File(dir(c, color), SHA_FILE);
        try {
            if (!f.isFile()) {
                java.nio.file.Files.write(f.toPath(), want.getBytes("UTF-8"));
                return false;
            }
            String have = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim().toLowerCase(Locale.ROOT);
            if (have.equals(want)) return false;
        } catch (Exception ex) {
            return false;
        }
        Diag.put("charge.pack", "pack " + color + " changed on GitHub: downloading again");
        return true;
    }

    private static void download(Context c, String color) {
        JSONObject entry;
        try {
            entry = entry(color);
        } catch (Exception e) {
            state = "pack " + color + " absent (animazioni.json unavailable)";
            Diag.put("charge.pack", state);
            return;
        }
        if (entry == null) {
            state = "pack " + color + " absent (not in animazioni.json)";
            Diag.put("charge.pack", state);
            return;
        }
        String url = entry.optString("url", ""), sha = entry.optString("sha256", "").toLowerCase(Locale.ROOT);
        long size = entry.optLong("size", -1);
        File root = root(c);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        File part = new File(root, slug(color) + ".zip.part");
        state = "pack " + color + " downloading";
        Diag.put("charge.pack", state);
        try {
            fetch(url, part, size);
            String got = sha256(part);
            if (!got.equals(sha) || size > 0 && part.length() != size) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();   // next time it is downloaded from scratch
                throw new java.io.IOException("sha256 or size mismatch");
            }
            File tmp = new File(root, slug(color) + ".tmp");
            deleteTree(tmp);
            int clips = extract(part, tmp, "carica-" + slug(color) + "-");
            // 0.4.4: the content is checked before touching the pack in use (8 clips: 4 phases × day/night)
            if (clips < PHASES.length * 2) {
                deleteTree(tmp);
                throw new java.io.IOException("incomplete pack (" + clips + " clip)");
            }
            File dst = dir(c, color);
            // safe swap: the old one becomes .old, the new one takes its place, then the old one is deleted
            File old = new File(root, slug(color) + ".old");
            deleteTree(old);
            boolean hadOld = dst.exists();
            if (hadOld && !dst.renameTo(old)) {
                deleteTree(tmp);
                throw new java.io.IOException("old folder not renamed");
            }
            if (!tmp.renameTo(dst)) {
                if (hadOld) old.renameTo(dst);   // put the previous pack back
                throw new java.io.IOException("folder not renamed");
            }
            deleteTree(old);
            // 0.4.4 (fix B): fingerprint of the pack just checked, to notice when it changes on GitHub
            try {
                java.nio.file.Files.write(new File(dst, SHA_FILE).toPath(), sha.getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            failed = !complete(c, color);
            state = failed ? "pack " + color + " incomplete" : "pack " + color + " present (just downloaded)";
        } catch (Exception e) {
            failed = !complete(c, color);   // 0.4.4: if the old pack is still there, the animation keeps using it
            state = "pack " + color + " · download error: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()) + " (resumes next time)";
        }
        Diag.put("charge.pack", state);
    }

    /** Resumable download: if part of the file is already there, only the rest is requested (Range). */
    private static void fetch(String url, File part, long size) throws Exception {
        long have = part.isFile() ? part.length() : 0;
        if (size > 0 && have > size) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            have = 0;
        }
        if (size > 0 && have == size) return;
        String u = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            con.setRequestProperty("User-Agent", "MG4Launcher");
            if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new java.io.IOException("redirect without location");
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            if (code == 416) {   // the partial file is already complete (or wrong): sha256 decides, otherwise start from scratch
                con.disconnect();
                return;
            }
            boolean append = code == 206;
            if (code != 200 && code != 206) {
                con.disconnect();
                throw new java.io.IOException("HTTP " + code);
            }
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                con.disconnect();
            }
            return;
        }
        throw new java.io.IOException("too many redirects");
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /** Extracts only the .mp4 clips with the expected prefix, without folders (no odd paths inside the zip). */
    static int extract(File zip, File to, String prefix) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        to.mkdirs();
        int n = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                String name = new File(e.getName()).getName();
                if (e.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".mp4")) continue;
                try (OutputStream out = new FileOutputStream(new File(to, name))) {
                    int r;
                    while ((r = zin.read(buf)) > 0) out.write(buf, 0, r);
                }
                n++;
            }
        }
        return n;
    }

    // ---- test from USB stick -----------------------------------------------------------------

    /**
     * Test from technical mode: looks on the USB stick for an animazione-&lt;color&gt;.zip (in the root
     * folder), extracts it into files/animazione/prova/ and returns {folder, color}. Null if there is none.
     */
    public static Object[] loadFromUsb(Context c) {
        java.util.List<File> roots = new java.util.ArrayList<>();
        File[] st = new File("/storage").listFiles();
        if (st != null) for (File f : st) roots.add(f);
        File[] mr = new File("/mnt/media_rw").listFiles();
        if (mr != null) for (File f : mr) roots.add(f);
        for (File r : roots) {
            File[] zips = r.listFiles((d, n) -> n.startsWith("animazione-") && n.endsWith(".zip"));
            if (zips == null || zips.length == 0) continue;
            File zip = zips[0];
            String sl = zip.getName().substring("animazione-".length(), zip.getName().length() - 4);
            File to = new File(root(c), "prova");
            deleteTree(to);
            try {
                int n = extract(zip, to, "carica-" + sl + "-");
                Diag.put("charge.test", "zip " + zip.getName() + " from USB stick (" + r.getName() + ") · " + n + " clip");
                if (n > 0) return new Object[]{to, sl};
            } catch (Exception e) {
                Diag.put("charge.test", "zip " + zip.getName() + " not read: " + e.getClass().getSimpleName());
            }
        }
        return null;
    }

    /** Clip of a phase in the test folder (color slug already prepared). */
    public static File clipIn(File dir, String slug, boolean day, String phase) {
        File f = new File(dir, "carica-" + slug + "-" + (day ? "giorno" : "notte") + "-" + phase + ".mp4");
        return f.isFile() && f.length() > 0 ? f : null;
    }
}
