package com.mg4.launcher.svc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Radio station logos. Looked up in this order:
 * <ol>
 *   <li>user folder {@code MG4Launcher/logos/NAME.png} (internal storage or USB stick; the older
 *   {@code MG4Launcher/loghi} is still read): takes priority;</li>
 *   <li>downloaded country packs (LogoPacks, with the alternative RDS/DAB names): work without internet;</li>
 *   <li>logos already saved in the app's storage (broadcast by the DAB station or downloaded in the past);</li>
 *   <li>only with network: search by name on radio-browser.info, then saved permanently.</li>
 * </ol>
 * NAME is the normalized station name: lowercase, letters and digits only (e.g. "RTL 102.5" → rtl1025.png).
 * Without network the attempts stop (increasing pause up to 30 minutes) and logos not found are remembered for 3 days.
 */
public final class StationLogos {
    private static final long RETRY_MS = 3L * 24 * 3600 * 1000;   // retry after 3 days if not found
    private static final long USER_SCAN_MS = 60_000;               // the user folder is re-read every minute
    private static final long OFFLINE_MIN_MS = 2 * 60_000L, OFFLINE_MAX_MS = 30 * 60_000L;
    private static final String API = "https://de1.api.radio-browser.info/json/stations/search";
    public static final String USER_FOLDER = "MG4Launcher/logos";
    /** 0.4.11: USER_FOLDER first, then the folder name used up to 0.4.10 (custom logos already on USB sticks). */
    public static final String[] USER_FOLDERS = {USER_FOLDER, "MG4Launcher/loghi"};

    private final Context ctx;
    private final File dir;
    /**
     * Fork: two byte-limited caches (before: an unbounded HashMap with everything at 192×192, ~144 KB per logo, never
     * freed). "full" = 192 px, only for the station on air; "thumbs" = 96 px for the lists and favorites, which are
     * shown at 40–60 px. {@link #FULL} and {@link #THUMB} also hold the sizes used when decoding.
     */
    private static final int FULL = 192, THUMB = 96;
    private final android.util.LruCache<String, Bitmap> full = new android.util.LruCache<String, Bitmap>(1536 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final android.util.LruCache<String, Bitmap> thumbs = new android.util.LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final Map<String, Long> missUntil = new HashMap<>();   // keys without a logo: the disk is not rechecked for 30 s
    private final Set<String> pending = new HashSet<>();
    private final Set<String> savedThisSession = new HashSet<>();
    private final HandlerThread thread = new HandlerThread("mg4-logos");
    private final Handler bg;
    private volatile Runnable onUpdate;

    private final LogoPacks packs;
    /** Names of the stations seen, with and without a logo (for diagnostics: the RDS names to add to the aliases). */
    private final Set<String> seenWith = new java.util.TreeSet<>(), seenWithout = new java.util.TreeSet<>();
    private volatile Map<String, File> user = new HashMap<>();
    private long userScanAt;
    private long offlineUntil, offlineStep = OFFLINE_MIN_MS;

    public StationLogos(Context c) {
        ctx = c.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "logos");
        // 0.4.11: the folder used to be called "loghi"; moved once so the logos already saved are kept
        File old = new File(ctx.getFilesDir(), "loghi");
        //noinspection ResultOfMethodCallIgnored
        if (old.isDirectory() && !dir.exists()) old.renameTo(dir);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        thread.start();
        bg = new Handler(thread.getLooper());
        packs = LogoPacks.get(ctx);
        packs.addListener(() -> {
            synchronized (this) {
                full.evictAll();
                thumbs.evictAll();
                missUntil.clear();
            }
            Runnable r = onUpdate;
            if (r != null) r.run();
        });
        bg.post(this::grantStorage);
        bg.post(this::scanUser);
    }

    public void setOnUpdate(Runnable r) {
        onUpdate = r;
    }

    public static String key(int band, long sid, int khz, String name) {
        if (band == MediaClient.BAND_DAB && sid > 0) return "dab_" + Long.toHexString(sid);
        String n = norm(name);
        return n.isEmpty() ? "fm_" + khz : "fm_" + n;
    }

    /** Normalized name for the logo files: lowercase, letters and digits only. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    // ---- user folder -----------------------------------------------------------------------

    /**
     * Reading internal storage and USB sticks (MG4Launcher/logos folder): the permission is self-granted thanks to the
     * platform signature (GRANT_RUNTIME_PERMISSIONS). If that fails, the app's own folder remains, which does not need it.
     */
    private void grantStorage() {
        String perm = android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Diag.put("logos.storagePermission", "granted");
            return;
        }
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, ctx.getPackageName(), perm, android.os.Process.myUserHandle());
            Diag.put("logos.storagePermission", "granted now");
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Diag.put("logos.storagePermission", "not granted (" + c.getClass().getSimpleName() + "): app folder only");
        }
    }

    /**
     * MG4Launcher/logos folders (and the older MG4Launcher/loghi): internal storage, USB sticks and the app's folder
     * (the latter needs no permissions). The first folder that has a name wins.
     */
    private List<File> userDirs() {
        List<File> out = new ArrayList<>();
        File[] vols = new File("/storage").listFiles();
        File[] app = ctx.getExternalFilesDirs(null);
        for (String uf : USER_FOLDERS) {
            out.add(new File(Environment.getExternalStorageDirectory(), uf));
            if (vols != null) for (File v : vols) {
                if (v.getName().equals("emulated") || v.getName().equals("self")) continue;
                out.add(new File(v, uf));
            }
        }
        if (app != null) for (File a : app) if (a != null) {
            out.add(new File(a, "logos"));
            out.add(new File(a, "loghi"));
        }
        return out;
    }

    private void scanUser() {
        userScanAt = SystemClock.uptimeMillis();
        Map<String, File> found = new HashMap<>();
        StringBuilder where = new StringBuilder();
        for (File d : userDirs()) {
            File[] files = d.listFiles();
            if (files == null) continue;
            int n = 0;
            for (File f : files) {
                String name = f.getName();
                String low = name.toLowerCase(Locale.ROOT);
                if (!(low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp"))) continue;
                String k = norm(name.substring(0, name.lastIndexOf('.')));
                if (!k.isEmpty() && !found.containsKey(k)) {
                    found.put(k, f);
                    n++;
                }
            }
            if (n > 0) where.append(where.length() == 0 ? "" : ", ").append(d.getPath()).append(" (").append(n).append(')');
        }
        boolean changed = !found.keySet().equals(user.keySet());
        user = found;
        Diag.put("logos.user", found.isEmpty() ? "none (folder " + USER_FOLDER + ")" : found.size() + " in " + where);
        if (changed) {
            synchronized (this) {
                full.evictAll();
                thumbs.evictAll();
                missUntil.clear();
            }
            Runnable r = onUpdate;
            if (r != null) r.run();
        }
    }

    private Bitmap packLogo(String n, int target) {
        File f = packs.find(n);
        return f == null ? null : decode(f, target);
    }

    private void seen(String name, boolean has) {
        String nm = clean(name);
        if (nm.length() < 2 || nm.matches("[0-9.,\\s]+( FM| MHz)?")) return;
        boolean changed = has ? seenWith.add(nm) | seenWithout.remove(nm) : !seenWith.contains(nm) && seenWithout.add(nm);
        if (!changed) return;
        Diag.put("logos.stations", seenWith.size() + " with logo, " + seenWithout.size() + " without");
        StringBuilder sb = new StringBuilder();
        for (String s : seenWithout) {
            if (sb.length() > 600) {
                sb.append("…");
                break;
            }
            sb.append(sb.length() == 0 ? "" : " | ").append(s);
        }
        Diag.put("logos.noLogo", sb.length() == 0 ? "none" : sb.toString());
    }

    /**
     * Decodes already downscaled: reads the dimensions first and uses inSampleSize, so a large image (user folder)
     * is never decoded in full; then scales down to the requested size.
     */
    private static Bitmap decode(File f, int target) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample(o.outWidth, o.outHeight, target);
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            return b == null ? null : scaleOwned(b, target);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Largest power of 2 that still leaves the decoded longer side ≥ target. */
    private static int sample(int w, int h, int target) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= target) s *= 2;
        return s;
    }

    // ---- lookup ----------------------------------------------------------------------------

    /** Station logo (user folder, built-in pack, storage); if missing, starts the download when there is network. */
    public synchronized Bitmap get(String key, String name) {
        maybeScanUser();
        Bitmap b = full.get(key);
        if (b != null) return b;
        b = resolve(key, name, FULL);
        if (b != null) full.put(key, b);
        return b;
    }

    /**
     * Fork: thumbnail (96 px) for the lists and favorites. Comes from the thumbnail cache, from a downscale of the full logo
     * already in memory or, if there is none, from disk/pack decoded directly at 96 px (with the same logic as get()).
     */
    public synchronized Bitmap getThumb(String key, String name) {
        maybeScanUser();
        Bitmap b = thumbs.get(key);
        if (b != null) return b;
        Bitmap f = full.get(key);
        b = f != null ? scaleTo(f, THUMB) : resolve(key, name, THUMB);
        if (b != null) thumbs.put(key, b);
        return b;
    }

    /** The user folder is re-read every minute, even when the logos are already cached (as before). */
    private void maybeScanUser() {
        long now = SystemClock.uptimeMillis();
        if (now - userScanAt > USER_SCAN_MS) {
            userScanAt = now;
            bg.post(this::scanUser);
        }
    }

    /** Looks up the logo (user folder, packs, disk) at the requested size; if none, schedules the network search. */
    private Bitmap resolve(String key, String name, int target) {
        long now = SystemClock.uptimeMillis();
        Long miss = missUntil.get(key);
        if (miss != null && now < miss) return null;
        String n = norm(clean(name));
        String k = key.startsWith("fm_") ? key.substring(3) : n;
        Bitmap b = null;
        File uf = user.get(k);
        if (uf == null && !n.isEmpty()) uf = user.get(n);
        if (uf != null) b = decode(uf, target);
        if (b == null) b = packLogo(k, target);
        if (b == null && !n.isEmpty() && !n.equals(k)) b = packLogo(n, target);
        if (b == null) {
            File f = new File(dir, key + ".png");
            if (f.exists()) b = decode(f, target);   // fork: the saved PNGs (DAB art) are downscaled too
        }
        seen(name, b != null);
        if (b != null) return b;
        missUntil.put(key, now + 30_000);
        File none = new File(dir, key + ".none");
        boolean recentMiss = none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS;
        String nm = clean(name);
        if (!recentMiss && nm.length() >= 2 && !nm.matches("[0-9.,\\s]+( FM| MHz)?") && now >= offlineUntil && Offline.canTry() && online() && pending.add(key)) {
            bg.post(() -> lookup(key, nm));
        }
        return null;
    }

    /** Logo received from the DAB station: saved permanently (unless the user has put in their own). */
    public synchronized void put(String key, Bitmap b) {
        if (b == null || b.getWidth() < 8) return;
        // fork: a copy downscaled to 192 px is stored (DAB art arrives at ~320×240). NEVER recycle b: it is the
        // MediaMetadata bitmap and is still shown as the logo of the station on air.
        boolean need = full.get(key) == null;
        boolean save = savedThisSession.add(key);   // saved to disk once per launch
        missUntil.remove(key);
        if (!need && !save) return;
        Bitmap small = scaleTo(b, FULL);
        if (need) full.put(key, small);
        if (save) bg.post(() -> save(key, small));
    }

    private void save(String key, Bitmap b) {
        try (FileOutputStream o = new FileOutputStream(new File(dir, key + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, key + ".none").delete();
        } catch (Exception e) {
            Diag.error("logos.save", e.toString());
        }
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            @SuppressWarnings("deprecation")
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable e) {
            return true;
        }
    }

    private void lookup(String key, String name) {
        Bitmap found = null;
        try {
            // stations from the logo countries (picked from the list, typed by hand or automatic), most listened first.
            // 0.9.6: all chosen countries are tried, not just the first (e.g. "AU, NZ")
            JSONArray arr = new JSONArray();
            for (String country : L_countries(packs)) {
                String url = API + "?name=" + URLEncoder.encode(name, "UTF-8")
                        + (country.isEmpty() ? "" : "&countrycode=" + country)
                        + "&hidebroken=true&order=clickcount&reverse=true&limit=8";
                arr = new JSONArray(new String(fetch(url, 200_000), "UTF-8"));
                if (arr.length() > 0) break;
            }
            for (int i = 0; i < arr.length() && found == null; i++) {
                JSONObject o = arr.getJSONObject(i);
                String fav = o.optString("favicon", "");
                if (!fav.startsWith("http")) continue;
                try {
                    byte[] img = fetch(fav, 1_500_000);
                    // fork: dimensions first, then downscaled decoding (a favicon can be 2000 px or more)
                    BitmapFactory.Options bo = new BitmapFactory.Options();
                    bo.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(img, 0, img.length, bo);
                    if (bo.outWidth < 32 || bo.outHeight < 32 || (long) bo.outWidth * bo.outHeight > 16_000_000L) continue;
                    bo.inJustDecodeBounds = false;
                    bo.inSampleSize = sample(bo.outWidth, bo.outHeight, FULL);
                    Bitmap b = BitmapFactory.decodeByteArray(img, 0, img.length, bo);
                    if (b != null) found = scaleOwned(b, FULL);
                } catch (Exception | OutOfMemoryError ignored) {
                }
            }
        } catch (Exception e) {
            // no network (or server unreachable): increasing pause before retrying any logo
            synchronized (this) {
                pending.remove(key);
                offlineUntil = SystemClock.uptimeMillis() + offlineStep;
                // 0.4.0: the pause and the diagnostics line are the shared ones (rete.offline)
                if (!Offline.failed("station logos", e))
                    Diag.put("logos.network", "unreachable (" + e.getClass().getSimpleName() + "): pause " + offlineStep / 60_000 + " min");
                offlineStep = Math.min(OFFLINE_MAX_MS, offlineStep * 2);
            }
            return;
        }
        Offline.ok();
        synchronized (this) {
            offlineStep = OFFLINE_MIN_MS;
            pending.remove(key);
            if (found != null) {
                full.put(key, found);
                missUntil.remove(key);
                save(key, found);
            } else {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, key + ".none").createNewFile();
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, key + ".none").setLastModified(System.currentTimeMillis());
                } catch (Exception ignored) {
                }
            }
        }
        Diag.put("logos.downloaded." + key, found != null ? "found and saved" : "not found (retrying in 3 days)");
        Runnable r = onUpdate;
        if (found != null && r != null) r.run();
    }

    private static List<String> L_countries(LogoPacks packs) {
        List<String> out = new java.util.ArrayList<>();
        try {
            for (String cc : packs.chosen()) if (cc != null && cc.length() == 2 && !out.contains(cc)) out.add(cc);
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static String L_country(LogoPacks packs) {
        try {
            List<String> ch = packs.chosen();
            return ch.isEmpty() ? "" : ch.get(0);
        } catch (Throwable e) {
            return "";
        }
    }

    /** Scales down so the longer side is max (returns the bitmap itself if it already fits). Does not recycle the original. */
    private static Bitmap scaleTo(Bitmap b, int max) {
        if (b.getWidth() <= max && b.getHeight() <= max) return b;
        float k = max / (float) Math.max(b.getWidth(), b.getHeight());
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
    }

    /** Like scaleTo, but recycles the intermediate: only for bitmaps decoded in here. */
    private static Bitmap scaleOwned(Bitmap b, int max) {
        Bitmap r = scaleTo(b, max);
        if (r != b) b.recycle();
        return r;
    }

    private static byte[] fetch(String url, int maxBytes) throws Exception {
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "MG4Launcher/1.0");
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) break;
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new java.io.IOException("HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new IllegalStateException("too large");
                    bo.write(buf, 0, n);
                }
                return bo.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new java.io.IOException("too many redirects");
    }
}
