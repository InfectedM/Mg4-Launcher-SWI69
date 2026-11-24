package com.mg4.launcher.svc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.mg4.launcher.L;
import com.mg4.launcher.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates from the GitHub repository: reads the latest release, downloads the attached APK and installs it
 * with PackageInstaller (the launcher is signed with the platform key, so no confirmation is needed).
 */
public final class Updater {
    private static final String ACTION_RESULT = "com.mg4.launcher.INSTALL_RESULT";

    public static final class Release {
        public String tag = "";
        public String name = "";
        public String notes = "";
        public String apkUrl;       // API URL of the asset
        public String apkName = "";
        public long apkSize;
        public boolean newer;
        boolean raw;                // repository file (contents API) instead of a release asset
        String repo = "", path = "";  // for the fallback download from raw.githubusercontent.com
    }

    public interface Listener {
        /** Status message to show (on the main thread). */
        void onUpdateStatus(String text, Release r, boolean busy);
    }

    private final Context ctx;
    private final HandlerThread thread = new HandlerThread("mg4-update");
    private final Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile boolean busy;

    public Updater(Context c) {
        ctx = c.getApplicationContext();
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isBusy() {
        return busy;
    }

    public String currentVersion() {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void status(String s, Release r, boolean b) {
        busy = b;
        Diag.put("updates", s);
        main.post(() -> {
            if (listener != null) listener.onUpdateStatus(s, r, b);
        });
    }

    /** Checks the repository's latest release. */
    public void check(String repo, String token) {
        if (busy) return;
        status(L.s(R.string.upd_checking), null, true);
        bg.post(() -> {
            try {
                Release r = latest(repo, token);
                if (r.apkUrl == null) {
                    status(L.f(R.string.upd_no_apk, r.tag), r, false);
                } else if (r.newer) {
                    status(L.f(R.string.upd_available, r.tag), r, false);
                } else {
                    status(L.f(R.string.upd_latest, currentVersion(), r.tag), r, false);
                }
            } catch (HttpError e) {
                status(e.getMessage(), null, false);
            } catch (Exception e) {
                status(L.f(R.string.upd_no_connection, e.getClass().getSimpleName()), null, false);
                if (!Offline.failed("updates", e)) Diag.error("updates", e.toString());
            }
        });
    }

    public interface Found {
        void found(Release r);
    }

    /**
     * Silent background check (for the popup): does not touch the status shown in the settings and
     * calls {@code cb} on the main thread only if there is a newer version with an APK.
     */
    public void checkQuiet(String repo, String token, Found cb) {
        if (busy) return;
        if (!Offline.canTry()) {
            Diag.put("updates.auto", "offline: postponed");
            return;
        }
        bg.post(() -> {
            try {
                Release r = latest(repo, token);
                Offline.ok();
                Diag.put("updates.auto", "latest " + r.tag + (r.newer ? " (new)" : ""));
                if (r.newer && r.apkUrl != null) main.post(() -> cb.found(r));
            } catch (Exception e) {
                if (Offline.failed("updates", e)) Diag.put("updates.auto", "offline: postponed");
                else Diag.put("updates.auto", "failed: " + e.getClass().getSimpleName() + " (" + Diag.netError(e) + ")");
            }
        });
    }

    /** Silent check for the settings: always returns the latest release found (even if it is not newer). */
    public void checkSilent(String repo, String token, Found cb) {
        if (busy) return;
        if (!Offline.canTry()) {
            Diag.put("updates.check", "offline: postponed");
            return;
        }
        bg.post(() -> {
            try {
                Release r = latest(repo, token);
                Offline.ok();
                Diag.put("updates.check", "latest " + r.tag + (r.newer ? " (new)" : ""));
                main.post(() -> cb.found(r));
            } catch (Exception e) {
                if (Offline.failed("updates", e)) Diag.put("updates.check", "offline: postponed");
                else Diag.put("updates.check", "failed: " + e.getClass().getSimpleName() + " (" + Diag.netError(e) + ")");
            }
        });
    }

    /**
     * Downloads the release APK and installs it. The APK stays in memory and goes straight into the install
     * session: no temporary file to save (that was the step that failed on the car).
     */
    public void install(Release r, String token) {
        if (busy || r == null || r.apkUrl == null) return;
        if (HomeMode.workshop(ctx)) return;   // updates suspended during workshop mode
        status(L.f(R.string.upd_downloading, r.apkName), r, true);
        bg.post(() -> {
            byte[] apk;
            try {
                apk = downloadWithFallback(r, token);
            } catch (HttpError e) {
                status(e.getMessage(), r, false);
                Diag.error("updates.download", e.getMessage());
                return;
            } catch (Exception e) {
                status(L.f(R.string.upd_download_failed, describe(e)), r, false);
                Diag.error("updates.download", Diag.trace(e));
                return;
            }
            if (!isApk(apk)) {
                status(L.f(R.string.upd_invalid_apk, apk.length), r, false);
                Diag.error("updates.download", "invalid file, " + apk.length + " bytes");
                return;
            }
            String version = checkPackage(apk);
            if (version == null) {
                status(L.s(R.string.upd_wrong_package), r, false);
                return;
            }
            status(L.f(R.string.upd_installing, version), r, true);
            try {
                installApk(apk);
            } catch (Exception e) {
                status(L.f(R.string.upd_install_failed, describe(e)), r, false);
                Diag.error("updates.install", Diag.trace(e));
            }
        });
    }

    private static boolean isApk(byte[] b) {
        return b != null && b.length >= 1024 && b[0] == 'P' && b[1] == 'K';
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : " – " + m);
    }

    /**
     * Repository files: first raw.githubusercontent.com (no request limit, as in SWI69 0.2.x),
     * then the GitHub API. Release assets: API only.
     */
    private byte[] downloadWithFallback(Release r, String token) throws Exception {
        if (r.raw && !r.repo.isEmpty()) {
            try {
                byte[] b = download("https://raw.githubusercontent.com/" + r.repo + "/main/" + r.path, r, "", "application/octet-stream");
                if (isApk(b)) return b;
            } catch (Exception e) {
                Diag.error("updates.download", "raw.githubusercontent.com: " + describe(e) + " → trying the GitHub API");
            }
        }
        Exception first;
        try {
            byte[] b = download(r.apkUrl, r, token, r.raw ? "application/vnd.github.raw" : "application/octet-stream");
            if (!r.raw || isApk(b)) return b;
            first = new IOException("not an APK (" + b.length + " bytes)");
        } catch (Exception e) {
            first = e;
        }
        if (!r.raw || r.repo.isEmpty()) throw first;
        Diag.error("updates.download", "GitHub API: " + describe(first) + " → trying raw.githubusercontent.com");
        return download("https://raw.githubusercontent.com/" + r.repo + "/main/" + r.path, r, "", "application/octet-stream");
    }

    /**
     * Checks the package name and version of the downloaded APK. A file is needed to read it: if it cannot
     * be written the check is skipped (the session only accepts this package anyway).
     */
    private String checkPackage(byte[] apk) {
        File[] dirs = {ctx.getCacheDir(), ctx.getFilesDir(), ctx.getExternalCacheDir()};
        for (File dir : dirs) {
            if (dir == null) continue;
            File f = new File(dir, "update-check.apk");
            try {
                if (!dir.exists()) dir.mkdirs();
                try (OutputStream os = new FileOutputStream(f)) {
                    os.write(apk);
                }
                PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                f.delete();
                if (pi == null) return null;
                if (!ctx.getPackageName().equals(pi.packageName)) return null;
                return pi.versionName;
            } catch (Exception e) {
                Diag.error("updates.check", dir + ": " + describe(e));
                f.delete();
            }
        }
        return L.s(R.string.upd_new_version);
    }

    // ------------------------------------------------------------------------------------------

    private static final class HttpError extends IOException {
        HttpError(String m) {
            super(m);
        }
    }

    /**
     * First the repository's releases/ folder (latest.json + APK, uploaded with a normal push),
     * then the GitHub Releases.
     */
    private Release latest(String repo, String token) throws Exception {
        Release fromRepo = null;
        HttpError err = null;
        try {
            fromRepo = latestFromRepo(repo, token);
        } catch (HttpError e) {
            err = e;
        }
        Release fromReleases = null;
        try {
            fromReleases = latestRelease(repo, token);
        } catch (HttpError e) {
            if (err == null || fromRepo != null) err = e;
        }
        if (fromRepo != null && (fromReleases == null || fromReleases.apkUrl == null
                || compare(fromRepo.tag, fromReleases.tag) >= 0)) return fromRepo;
        if (fromReleases != null) return fromReleases;
        throw err != null ? err : new HttpError(L.f(R.string.upd_not_found, repo));
    }

    private Release latestFromRepo(String repo, String token) throws Exception {
        String base = "https://api.github.com/repos/" + repo + "/contents/";
        JSONObject o = null;
        // 1) raw.githubusercontent.com: no request limit (the GitHub API without a token allows 60 per hour)
        try {
            HttpURLConnection rc = open("https://raw.githubusercontent.com/" + repo + "/main/releases/latest.json", "", "*/*");
            if (rc.getResponseCode() == 200) o = new JSONObject(readAll(rc.getInputStream()));
            rc.disconnect();
        } catch (Exception e) {
            Diag.putQuiet("updates.raw", describe(e));
        }
        // 2) GitHub API
        if (o == null) {
            HttpURLConnection c = open(base + "releases/latest.json", token, "application/vnd.github.raw");
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                throw httpError(code, token, repo);
            }
            o = new JSONObject(readAll(c.getInputStream()));
            c.disconnect();
        }
        Release r = new Release();
        r.tag = o.optString("version", "");
        r.name = "MG4 Launcher " + r.tag;
        r.notes = o.optString("notes", "");
        String apk = o.optString("apk", "");
        if (!apk.isEmpty()) {
            r.apkUrl = base + apk;
            r.apkName = apk.substring(apk.lastIndexOf('/') + 1);
            r.apkSize = o.optLong("size");
            r.raw = true;
            r.repo = repo;
            r.path = apk;
        }
        r.newer = compare(r.tag, currentVersion()) > 0;
        return r;
    }

    private static HttpError httpError(int code, String token, String repo) {
        if (code == 404) return new HttpError(L.f(R.string.upd_not_found, repo));
        if (code == 401) return new HttpError(L.s(R.string.upd_token_invalid));
        if (code == 403) return new HttpError(L.s(R.string.upd_refused));
        return new HttpError(L.f(R.string.upd_unexpected, code));
    }

    private Release latestRelease(String repo, String token) throws Exception {
        HttpURLConnection c = open("https://api.github.com/repos/" + repo + "/releases/latest", token, "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw httpError(code, token, repo);
        }
        JSONObject o = new JSONObject(readAll(c.getInputStream()));
        c.disconnect();

        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.name = o.optString("name", r.tag);
        r.notes = o.optString("body", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").toLowerCase().endsWith(".apk")) {
                    r.apkUrl = a.optString("url");
                    r.apkName = a.optString("name");
                    r.apkSize = a.optLong("size");
                    break;
                }
            }
        }
        r.newer = compare(r.tag, currentVersion()) > 0;
        return r;
    }

    private byte[] download(String url, Release r, String token, String accept) throws Exception {
        // the asset answers with a redirect to GitHub's storage: it is followed without the token
        HttpURLConnection c = open(url, token, accept);
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        for (int hops = 0; code / 100 == 3 && hops < 5; hops++) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            boolean api = loc != null && loc.startsWith("https://api.github.com/");
            c = open(loc, api ? token : "", api ? accept : "application/octet-stream");
            c.setInstanceFollowRedirects(false);
            code = c.getResponseCode();
        }
        if (code != 200) {
            c.disconnect();
            throw new HttpError(L.f(R.string.upd_download_failed, L.f(R.string.upd_unexpected, code)));
        }
        long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.apkSize;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(total > 0 ? (int) total : 512 * 1024);
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            long done = 0, lastShown = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                done += n;
                if (total > 0 && done - lastShown > total / 20) {
                    lastShown = done;
                    status(L.f(R.string.upd_download_pct, (int) Math.min(100, done * 100 / total)), r, true);
                }
            }
        } finally {
            c.disconnect();
        }
        Diag.put("updates.downloaded", bo.size() + " bytes");
        return bo.toByteArray();
    }

    private void installApk(byte[] apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        p.setSize(apk.length);
        // explicit internal storage: the MG firmware blocks non-system apps on the "automatic" internal storage
        p.setInstallLocation(PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        // like "adb install -r -f": INSTALL_REPLACE_EXISTING (0x2) + INSTALL_INTERNAL (0x10)
        try {
            java.lang.reflect.Field f = PackageInstaller.SessionParams.class.getField("installFlags");
            f.setInt(p, f.getInt(p) | 0x2 | 0x10);
        } catch (Throwable t) {
            Diag.error("updates.flag", t.toString());
        }
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream os = s.openWrite("base.apk", 0, apk.length)) {
                os.write(apk);
                s.fsync(os);
            }
            try {
                ctx.registerReceiver(result, new IntentFilter(ACTION_RESULT));
            } catch (Exception ignored) {
            }
            PendingIntent cb = PendingIntent.getBroadcast(ctx, id,
                    new Intent(ACTION_RESULT).setPackage(ctx.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(cb.getIntentSender());
        } catch (Exception e) {
            try {
                pi.abandonSession(id);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private final BroadcastReceiver result = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // without the system permission Android asks for confirmation: the standard screen is shown
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                status(L.s(R.string.upd_confirm), null, true);
                return;
            }
            try {
                ctx.unregisterReceiver(this);
            } catch (Exception ignored) {
            }
            if (st == PackageInstaller.STATUS_SUCCESS) {
                status(L.s(R.string.upd_installed), null, false);
            } else {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Diag.error("updates.install", "state " + st + ": " + msg);
                status(L.f(R.string.upd_install_failed, msg == null ? L.f(R.string.upd_error_code, st) : msg), null, false);
            }
        }
    };

    private static HttpURLConnection open(String url, String token, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "MG4Launcher");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "token " + token);
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** Compares versions like "v0.3.0" and "0.2.0" number by number. */
    static int compare(String a, String b) {
        int[] x = nums(a), y = nums(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int[] nums(String v) {
        String[] parts = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    // ---- 0.4.2 (point 12): automatic check, forced update and remote notices ------------------------------------
    // Only HTTPS addresses of the launcher's repository (raw.githubusercontent.com/<repo>/main/releases/...).

    // fork: updates come from the InfectedM repository (Ernesto's original version does not replace this one)
    public static final String OWN_REPO = "InfectedM/Mg4-Launcher-SWI69";
    private static final String RAW = "https://raw.githubusercontent.com/" + OWN_REPO + "/main/";
    /** 0.4.4: address of the files in our own repository (animazioni.json, animation packs). */
    public static final String OWN_RAW = RAW;
    private static final int MAX_FORCED_TRIES = 3;

    /** Outcome of the automatic check (on the main thread). */
    public interface Auto {
        /** New version available (for the unobtrusive notice with the Update button). */
        void onNewVersion(Release r);

        /** Remote notices to show. */
        void onNotices(java.util.List<Notice> list);
    }

    /** A notice from releases/avvisi.json. */
    public static final class Notice {
        public String id = "";
        public boolean important, updateButton;
        public org.json.JSONObject text;

        /** Text in the launcher's language, English as fallback. */
        public String text(String lang) {
            if (text == null) return "";
            String t = text.optString(lang, "");
            if (t.isEmpty()) t = text.optString("en", "");
            if (t.isEmpty() && text.length() > 0) t = text.optString(text.keys().next(), "");
            return t;
        }
    }

    private volatile String autoState = "never";
    /** For the notices.state diagnostics. */
    public volatile String noticeIds = "", noticeShown = "shown - · confirmed -";

    private void autoDiag(String s) {
        autoState = s;
        Diag.put("update.state", s);
    }

    /** Automatic check (2 minutes after power-on, then every 6 hours). Does not block the UI. */
    public void autoCheck(String lang, Auto cb) {
        if (!Offline.canTry()) {
            autoDiag("last check " + now() + " · offline: postponed");
            return;
        }
        bg.post(() -> {
            String when = now();
            org.json.JSONObject o;
            try {
                o = new org.json.JSONObject(fetch(RAW + "releases/latest.json"));
                Offline.ok();
            } catch (Exception e) {
                Offline.failed("updates", e);
                autoDiag("last check " + when + " · latest.json not read (" + e.getClass().getSimpleName() + ")");
                notices(lang, cb);
                return;
            }
            String ver = o.optString("version", ""), min = o.optString("minima", ""), apk = o.optString("apk", "");
            long size = o.optLong("size", 0);
            String cur = currentVersion();
            boolean newer = compare(ver, cur) > 0 && !apk.isEmpty();
            boolean forced = newer && !min.isEmpty() && compare(cur, min) < 0;
            String base = "last check " + when + " · installed " + cur + " · found " + ver + " · minimum " + (min.isEmpty() ? "-" : min);
            if (newer) {
                Release r = new Release();
                r.tag = ver;
                r.name = "MG4 Launcher " + ver;
                r.notes = o.optString("notes", "");
                r.apkUrl = "https://api.github.com/repos/" + OWN_REPO + "/contents/" + apk;
                r.apkName = apk.substring(apk.lastIndexOf('/') + 1);
                r.apkSize = size;
                r.raw = true;
                r.repo = OWN_REPO;
                r.path = apk;
                r.newer = true;
                if (forced) forced(r, base);
                else autoDiag(base + " · result: new version, notice shown");
                main.post(() -> cb.onNewVersion(r));
            } else {
                autoDiag(base + " · result: already up to date");
            }
            notices(lang, cb);
        });
    }

    /** Forced update attempts already made for a version. */
    private int forcedTries(String ver) {
        return ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getInt("aggiornamento.tentativi." + ver, 0);
    }

    private int forcedWaits;

    /**
     * Forced update ("minima" newer than the installed one): only with the car stopped, APK with the same key and the
     * stated size, at most 3 attempts per version.
     */
    private void forced(Release r, String base) {
        int tries = forcedTries(r.tag);
        if (tries >= MAX_FORCED_TRIES) {
            autoDiag(base + " · forced: STOPPED after " + tries + " failed attempts (notice only)");
            return;
        }
        if (busy || HomeMode.workshop(ctx)) {
            autoDiag(base + " · forced: postponed (" + (busy ? "update already in progress" : "workshop mode") + ")");
            return;
        }
        float v = VehicleClient.speedKmh();
        int g = VehicleClient.gear();
        if (Float.isNaN(v) && g == Integer.MIN_VALUE) {
            autoDiag(base + " · forced: NOT forced, speed and gear unreadable (notice only)");
            return;
        }
        if (!VehicleClient.surelyStopped()) {
            // moving: retry every minute (for at most an hour), never while driving
            if (forcedWaits++ < 60) {
                autoDiag(base + " · forced: waiting for the car to stop (speed " + (Float.isNaN(v) ? "?" : Math.round(v) + " km/h") + ", gear " + (g == Integer.MIN_VALUE ? "?" : g) + ")");
                bg.postDelayed(() -> forced(r, base), 60_000);
            } else {
                autoDiag(base + " · forced: car never stopped for an hour, retrying at the next check");
                forcedWaits = 0;
            }
            return;
        }
        forcedWaits = 0;
        if (!r.path.startsWith("releases/") || r.path.contains("..")) {
            autoDiag(base + " · forced: invalid APK path (" + r.path + ")");
            return;
        }
        ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).edit().putInt("aggiornamento.tentativi." + r.tag, tries + 1).apply();
        String t = "attempt " + (tries + 1) + "/" + MAX_FORCED_TRIES;
        busy = true;
        try {
            byte[] apk = download(RAW + r.path, r, "", "application/octet-stream");
            if (!isApk(apk)) throw new IOException("invalid file (" + apk.length + " bytes)");
            if (r.apkSize > 0 && apk.length != r.apkSize) throw new IOException("size " + apk.length + " instead of " + r.apkSize);
            String why = sameSignature(apk);
            if (why != null) throw new IOException(why);
            autoDiag(base + " · forced: " + t + " · verified (size and signature), installing");
            busy = false;
            status(L.f(R.string.upd_installing, r.tag), r, true);
            installApk(apk);
        } catch (Exception e) {
            busy = false;
            autoDiag(base + " · forced: " + t + " FAILED: " + describe(e));
        }
    }

    /** null if the APK has the same package and key as the installed launcher, otherwise the reason. */
    @SuppressWarnings("deprecation")
    private String sameSignature(byte[] apk) {
        File f = new File(ctx.getCacheDir(), "update-firma.apk");
        try {
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(apk);
            }
            PackageManager pm = ctx.getPackageManager();
            PackageInfo a = pm.getPackageArchiveInfo(f.getPath(), PackageManager.GET_SIGNATURES);
            PackageInfo me = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            if (a == null) return "APK unreadable";
            if (!ctx.getPackageName().equals(a.packageName)) return "different package (" + a.packageName + ")";
            if (a.signatures == null || a.signatures.length == 0 || me.signatures == null || me.signatures.length == 0) return "signature unreadable";
            return a.signatures[0].equals(me.signatures[0]) ? null : "signature differs from the installed launcher";
        } catch (Exception e) {
            return "signature check failed: " + e.getClass().getSimpleName();
        } finally {
            f.delete();
        }
    }

    /** releases/avvisi.json: notices for this version (empty or missing = none, with no visible errors). */
    private void notices(String lang, Auto cb) {
        java.util.List<Notice> out = new java.util.ArrayList<>();
        String cur = currentVersion();
        StringBuilder ids = new StringBuilder();
        try {
            org.json.JSONObject o = new org.json.JSONObject(fetch(RAW + "releases/avvisi.json"));
            JSONArray a = o.optJSONArray("avvisi");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject n = a.optJSONObject(i);
                if (n == null || n.optString("id", "").isEmpty()) continue;
                JSONArray vs = n.optJSONArray("versioni");
                boolean match = vs == null || vs.length() == 0;
                for (int k = 0; vs != null && k < vs.length(); k++) if (cur.equals(vs.optString(k))) match = true;
                ids.append(ids.length() == 0 ? "" : ",").append(n.optString("id")).append(match ? "" : "(other version)");
                if (!match) continue;
                Notice x = new Notice();
                x.id = n.optString("id");
                x.important = n.optBoolean("importante", false);
                x.updateButton = "aggiorna".equals(n.optString("pulsante", ""));
                x.text = n.optJSONObject("testo");
                if (!x.text(lang).isEmpty()) out.add(x);
            }
            noticeIds = ids.length() == 0 ? "none" : ids.toString();
        } catch (Exception e) {
            noticeIds = "file missing or unreadable (" + e.getClass().getSimpleName() + ")";
        }
        Diag.put("notices.state", "read " + noticeIds + " · " + noticeShown);
        if (!out.isEmpty()) main.post(() -> cb.onNotices(out));
    }

    /** 0.4.4: GET of a small text file from our own repository (animazioni.json). */
    public static String getText(String url) throws IOException {
        return fetch(url);
    }

    private static String fetch(String url) throws IOException {
        if (!url.startsWith(RAW)) throw new IOException("address not allowed");
        HttpURLConnection c = open(url, "", "*/*");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String now() {
        return new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALY).format(new java.util.Date());
    }

    /** true if the launcher has permission to install without confirmation (signed with the platform key). */
    public boolean canInstallSilently() {
        return ctx.checkSelfPermission("android.permission.INSTALL_PACKAGES") == PackageManager.PERMISSION_GRANTED;
    }

    /** 0.4.3 (Feature check): reads latest.json and avvisi.json without touching the update status. */
    public static String[] probeRemote() {
        String[] out = new String[2];
        try {
            out[0] = new JSONObject(fetch(RAW + "releases/latest.json")).optString("version", "?");
        } catch (Exception e) {
            out[0] = null;
        }
        try {
            JSONArray a = new JSONObject(fetch(RAW + "releases/avvisi.json")).optJSONArray("avvisi");
            out[1] = String.valueOf(a == null ? 0 : a.length());
        } catch (Exception e) {
            out[1] = null;
        }
        return out;
    }
}
