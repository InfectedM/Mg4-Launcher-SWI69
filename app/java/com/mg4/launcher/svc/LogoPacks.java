package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Radio logo packs per country (logos/&lt;CC&gt;.zip + logos/index.json of the repository).
 * <ul>
 *   <li>downloaded as soon as there is network for the chosen countries (automatic = system country), SHA-256 checked,
 *   extracted into the app storage and kept forever: then they work without network;</li>
 *   <li>updates are checked at most once a week, or with "Aggiorna";</li>
 *   <li>from USB stick: MG4Launcher/logos/&lt;CC&gt;.zip (or the older MG4Launcher/loghi/) installs without internet
 *   (after confirmation).</li>
 * </ul>
 * Each pack holds one file per station (normalized name, .webp or .png) and alias.txt (broadcast name = file).
 */
public final class LogoPacks {
    /** Address of the packs: the other launcher versions change only this line. */
    // fork: logo packs from the InfectedM repository (same files and hashes as the original)
    public static final String BASE_URL = "https://raw.githubusercontent.com/InfectedM/Mg4-Launcher-SWI69/main/logos/";
    public static final String AUTO = "auto";
    private static final long WEEK_MS = 7L * 24 * 3600 * 1000;
    private static final long RETRY_MS = 30 * 60_000L;
    private static final String PREFS = PrefsMigration.LOGOS;

    /** Launcher language -> country, when the system does not report the region. */
    private static final Map<String, String> LANG_COUNTRY = new HashMap<>();

    static {
        String[][] m = {{"it", "IT"}, {"en", "GB"}, {"es", "ES"}, {"de", "DE"}, {"pt", "PT"}, {"fr", "FR"}, {"tr", "TR"}, {"pl", "PL"}};
        for (String[] x : m) LANG_COUNTRY.put(x[0], x[1]);
    }

    /** Available countries (same list as the packs; the names come from the system in the launcher language). */
    public static final String[] COUNTRIES = {"AD", "AL", "AT", "AX", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE", "DK",
            "EE", "ES", "FI", "FO", "FR", "GB", "GI", "GR", "HR", "HU", "IE", "IM", "IS", "IT", "LI", "LT", "LU", "LV", "MC",
            "MD", "ME", "MK", "MT", "NL", "NO", "PL", "PT", "RO", "RS", "RU", "SE", "SI", "SK", "SM", "TR", "UA", "VA", "XK",
            // 0.9.6: Australia and South America
            "AU", "AR", "BO", "BR", "CL", "CO", "EC", "GY", "PY", "PE", "SR", "UY", "VE"};

    public interface Listener {
        void changed();
    }

    /** Installed pack. */
    public static final class Pack {
        public String cc;
        public int version;
        public long size;
        public int logos;
        public String source = "";
        final Map<String, File> files = new HashMap<>();
        final Map<String, String> alias = new HashMap<>();
    }

    private static volatile LogoPacks instance;

    public static synchronized LogoPacks get(Context c) {
        if (instance == null) instance = new LogoPacks(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final File root;
    private final SharedPreferences sp;
    private final Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile Map<String, Pack> packs = new HashMap<>();
    private volatile String status = "";
    private volatile boolean busy;
    private long nextTry;
    /** Zips found on USB stick and not yet offered (path + size + date). */
    private final Set<String> offered = new LinkedHashSet<>();

    private LogoPacks(Context c) {
        ctx = c;
        root = new File(c.getFilesDir(), "logo-packs");
        // 0.4.11: the folder used to be called "pacchetti-loghi"; moved once so the installed packs are kept
        File old = new File(c.getFilesDir(), "pacchetti-loghi");
        //noinspection ResultOfMethodCallIgnored
        if (old.isDirectory() && !root.exists()) old.renameTo(root);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        sp = PrefsMigration.open(c, PREFS);
        HandlerThread th = new HandlerThread("mg4-logo-packs");
        th.start();
        bg = new Handler(th.getLooper());
        bg.post(this::loadAll);
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void notifyChanged() {
        main.post(() -> {
            List<Listener> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Listener l : copy) l.changed();
        });
    }

    // ---- country choice --------------------------------------------------------------------

    /** "auto" or comma-separated codes (e.g. "IT,FR"). */
    public String choice() {
        return sp.getString("countries", AUTO);
    }

    public void setChoice(String v) {
        sp.edit().putString("countries", v == null || v.isEmpty() ? AUTO : v).apply();
        Diag.put("logos.countries", v);
        refresh(true);
    }

    // ---- automatic country -----------------------------------------------------------------
    //
    // The MG firmware uses language regions that do not match the country (Italian cars "it-CH", French "fr-BE"),
    // so the order is: system time zone → launcher language → region of the system language.
    // DAB stations (service identifier: RDS country in the top 4 bits, ECC if present) confirm the choice,
    // or correct it when the time zone does not point to a pack country.

    /** System time zone -> country (all pack countries; Kosovo uses Europe/Belgrade). */
    private static final Map<String, String> TZ_COUNTRY = new HashMap<>();

    static {
        String[][] tz = {
                {"AD", "Europe/Andorra"}, {"AL", "Europe/Tirane"}, {"AT", "Europe/Vienna"}, {"AX", "Europe/Mariehamn"},
                {"BA", "Europe/Sarajevo"}, {"BE", "Europe/Brussels"}, {"BG", "Europe/Sofia"}, {"BY", "Europe/Minsk"},
                {"CH", "Europe/Zurich"}, {"CY", "Asia/Nicosia", "Asia/Famagusta", "Europe/Nicosia"},
                {"CZ", "Europe/Prague"}, {"DE", "Europe/Berlin", "Europe/Busingen"}, {"DK", "Europe/Copenhagen"},
                {"EE", "Europe/Tallinn"}, {"ES", "Europe/Madrid", "Africa/Ceuta", "Atlantic/Canary"},
                {"FI", "Europe/Helsinki"}, {"FO", "Atlantic/Faroe", "Atlantic/Faeroe"}, {"FR", "Europe/Paris"},
                {"GB", "Europe/London", "Europe/Belfast", "GB", "GB-Eire"}, {"GI", "Europe/Gibraltar"},
                {"GR", "Europe/Athens"}, {"HR", "Europe/Zagreb"}, {"HU", "Europe/Budapest"},
                {"IE", "Europe/Dublin", "Eire"}, {"IM", "Europe/Isle_of_Man"}, {"IS", "Atlantic/Reykjavik", "Iceland"},
                {"IT", "Europe/Rome"}, {"LI", "Europe/Vaduz"}, {"LT", "Europe/Vilnius"}, {"LU", "Europe/Luxembourg"},
                {"LV", "Europe/Riga"}, {"MC", "Europe/Monaco"}, {"MD", "Europe/Chisinau", "Europe/Tiraspol"},
                {"ME", "Europe/Podgorica"}, {"MK", "Europe/Skopje"}, {"MT", "Europe/Malta"},
                {"NL", "Europe/Amsterdam"}, {"NO", "Europe/Oslo", "Arctic/Longyearbyen", "Atlantic/Jan_Mayen"},
                {"PL", "Europe/Warsaw", "Poland"}, {"PT", "Europe/Lisbon", "Atlantic/Madeira", "Atlantic/Azores", "Portugal"},
                {"RO", "Europe/Bucharest"}, {"RS", "Europe/Belgrade"},
                {"RU", "Europe/Moscow", "W-SU", "Europe/Kaliningrad", "Europe/Samara", "Europe/Volgograd", "Europe/Saratov",
                        "Europe/Ulyanovsk", "Europe/Astrakhan", "Europe/Kirov", "Asia/Yekaterinburg", "Asia/Omsk",
                        "Asia/Novosibirsk", "Asia/Krasnoyarsk", "Asia/Irkutsk", "Asia/Vladivostok"},
                {"SE", "Europe/Stockholm"}, {"SI", "Europe/Ljubljana"}, {"SK", "Europe/Bratislava"},
                {"SM", "Europe/San_Marino"}, {"TR", "Europe/Istanbul", "Asia/Istanbul", "Turkey"},
                {"UA", "Europe/Kiev", "Europe/Kyiv", "Europe/Uzhgorod", "Europe/Zaporozhye"}, {"VA", "Europe/Vatican"},
                // 0.9.6: Australia and South America
                {"AU", "Australia/Sydney", "Australia/Melbourne", "Australia/Brisbane", "Australia/Adelaide", "Australia/Perth",
                        "Australia/Hobart", "Australia/Darwin", "Australia/Canberra", "Australia/ACT", "Australia/NSW",
                        "Australia/Victoria", "Australia/Queensland", "Australia/West", "Australia/South", "Australia/North",
                        "Australia/Tasmania", "Australia/Lord_Howe", "Australia/Broken_Hill", "Australia/Lindeman", "Australia/Eucla"},
                {"AR", "America/Argentina/Buenos_Aires", "America/Buenos_Aires", "America/Argentina/Cordoba", "America/Cordoba",
                        "America/Argentina/Mendoza", "America/Mendoza", "America/Argentina/Salta", "America/Argentina/Jujuy",
                        "America/Argentina/Tucuman", "America/Argentina/Catamarca", "America/Argentina/La_Rioja",
                        "America/Argentina/San_Juan", "America/Argentina/San_Luis", "America/Argentina/Rio_Gallegos",
                        "America/Argentina/Ushuaia"},
                {"BO", "America/La_Paz"},
                {"BR", "America/Sao_Paulo", "Brazil/East", "America/Rio_Branco", "America/Manaus", "America/Belem",
                        "America/Fortaleza", "America/Recife", "America/Bahia", "America/Cuiaba", "America/Campo_Grande",
                        "America/Porto_Velho", "America/Boa_Vista", "America/Araguaina", "America/Maceio", "America/Santarem",
                        "America/Noronha", "America/Eirunepe"},
                {"CL", "America/Santiago", "Chile/Continental", "America/Punta_Arenas", "Pacific/Easter"},
                {"CO", "America/Bogota"}, {"EC", "America/Guayaquil", "Pacific/Galapagos"}, {"GY", "America/Guyana"},
                {"PY", "America/Asuncion"}, {"PE", "America/Lima"}, {"SR", "America/Paramaribo"},
                {"UY", "America/Montevideo"}, {"VE", "America/Caracas"}};
        for (String[] r : tz) for (int i = 1; i < r.length; i++) TZ_COUNTRY.put(r[i], r[0]);
    }

    /** RDS/DAB country: key "ECC:digit" (e.g. "E0:5" = Italy), for the pack countries. */
    private static final Map<String, String> RDS_COUNTRY = new HashMap<>();

    static {
        String[] m = {
                "E0:1=DE", "E0:3=AD", "E0:5=IT", "E0:6=BE", "E0:7=RU", "E0:9=AL", "E0:A=AT", "E0:B=HU", "E0:C=MT", "E0:D=DE",
                "E1:1=GR", "E1:2=CY", "E1:3=SM", "E1:4=CH", "E1:6=FI", "E1:7=LU", "E1:8=BG", "E1:9=DK", "E1:A=GI", "E1:C=GB",
                "E1:E=RO", "E1:F=FR",
                "E2:2=CZ", "E2:3=PL", "E2:4=VA", "E2:5=SK", "E2:9=LI", "E2:A=IS", "E2:B=MC", "E2:C=LT", "E2:D=RS", "E2:E=ES",
                "E2:F=NO",
                "E3:1=ME", "E3:2=IE", "E3:3=TR", "E3:4=MK", "E3:8=NL", "E3:9=LV", "E3:C=HR", "E3:E=SE", "E3:F=BY",
                "E4:1=MD", "E4:2=EE", "E4:6=UA", "E4:7=XK", "E4:8=PT", "E4:9=SI", "E4:F=BA"};
        for (String s : m) RDS_COUNTRY.put(s.substring(0, 4), s.substring(5));
    }

    /** RDS country digit of a country (territories use that of the country their stations belong to). */
    static char rdsDigit(String cc) {
        if ("AX".equals(cc)) cc = "FI";
        else if ("FO".equals(cc)) cc = "DK";
        else if ("IM".equals(cc)) cc = "GB";
        for (Map.Entry<String, String> e : RDS_COUNTRY.entrySet()) if (e.getValue().equals(cc)) return e.getKey().charAt(3);
        return 0;
    }

    /** Pack countries with that RDS digit. */
    static List<String> rdsCandidates(char digit) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : RDS_COUNTRY.entrySet())
            if (e.getKey().charAt(3) == digit && !out.contains(e.getValue())) out.add(e.getValue());
        java.util.Collections.sort(out);
        return out;
    }

    private static boolean known(String cc) {
        return cc != null && Arrays.asList(COUNTRIES).contains(cc);
    }

    /** Sources of the automatic country, for the choice and the diagnostics. */
    public static final class AutoInfo {
        public String tz = "", tzCountry, lang = "", langCountry, region = "", regionCountry;
        /** RDS: most frequent digit (0 = no data), exact country if ECC is present, number of services. */
        public char rdsDigit;
        public String rdsExact;
        public int rdsServices;
        public String country, reason, rdsNote = "";

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("timezone ").append(tz.isEmpty() ? "?" : tz).append(" → ").append(tzCountry == null ? "—" : tzCountry);
            sb.append(" · language ").append(lang.isEmpty() ? "?" : lang).append(" → ").append(langCountry == null ? "—" : langCountry);
            sb.append(" · region ").append(region.isEmpty() ? "?" : region).append(" → ").append(regionCountry == null ? "—" : regionCountry);
            sb.append(" · RDS ");
            if (rdsDigit == 0) sb.append("no data");
            else {
                sb.append(rdsDigit).append(" (").append(rdsExact != null ? rdsExact : String.join("/", rdsCandidates(rdsDigit)))
                        .append(", ").append(rdsServices).append(" DAB services)");
            }
            sb.append(" ⇒ ").append(country).append(" (").append(reason).append(rdsNote.isEmpty() ? "" : ", " + rdsNote).append(')');
            return sb.toString();
        }
    }

    /** RDS data collected from DAB stations (distinct identifiers -> digit/ECC). */
    private final Map<Long, String> rdsSeen = new HashMap<>();
    private volatile AutoInfo lastAuto;
    private volatile String lastAutoDiag = "";

    /** Automatic country (compatibility: without RDS data). */
    public static String autoCountry(String launcherLang) {
        LogoPacks p = instance;
        return p != null ? p.auto(launcherLang).country : resolve(launcherLang, (char) 0, null, 0).country;
    }

    private AutoInfo auto(String launcherLang) {
        char digit = 0;
        String exact = null;
        int services;
        synchronized (rdsSeen) {
            services = rdsSeen.size();
            if (services == 0) {
                // last reliable RDS data from previous power-ons
                String saved = sp.getString("rdsData", "");
                if (saved.length() >= 1) {
                    digit = saved.charAt(0);
                    exact = saved.length() >= 4 ? saved.substring(2) : null;
                    services = sp.getInt("rdsServices", 0);
                }
            } else {
                Map<Character, Integer> count = new HashMap<>();
                Map<Character, String> ex = new HashMap<>();
                for (String v : rdsSeen.values()) {
                    char d = v.charAt(0);
                    count.put(d, count.containsKey(d) ? count.get(d) + 1 : 1);
                    if (v.length() >= 4) ex.put(d, v.substring(2));
                }
                int best = 0;
                for (Map.Entry<Character, Integer> e : count.entrySet()) if (e.getValue() > best) {
                    best = e.getValue();
                    digit = e.getKey();
                }
                // reliable data: at least 3 services and 3 out of 4 with the same digit
                if (best < 3 || best * 4 < services * 3) digit = 0;
                exact = digit == 0 ? null : ex.get(digit);
                if (digit != 0) {
                    String v = digit + (exact == null ? "" : ":" + exact);
                    if (!v.equals(sp.getString("rdsData", "")) || sp.getInt("rdsServices", 0) != services)
                        sp.edit().putString("rdsData", v).putInt("rdsServices", services).apply();
                }
            }
        }
        AutoInfo a = resolve(launcherLang, digit, exact, services);
        lastAuto = a;
        String d = a.describe();
        if (!d.equals(lastAutoDiag)) {
            lastAutoDiag = d;
            Diag.put("logos.carCountry", d);
        }
        return a;
    }

    /** Choice (0.4.0): RDS/DAB → language and region agreeing → time zone → region alone (→ language → IT). */
    static AutoInfo resolve(String launcherLang, char digit, String exact, int services) {
        AutoInfo a = new AutoInfo();
        try {
            java.util.TimeZone z = java.util.TimeZone.getDefault();
            a.tz = z == null ? "" : z.getID();
        } catch (Throwable ignored) {
        }
        a.tzCountry = TZ_COUNTRY.get(a.tz);
        String lang = launcherLang == null ? "" : launcherLang;
        if (lang.isEmpty() || lang.equals("auto")) lang = Locale.getDefault().getLanguage();
        a.lang = lang;
        a.langCountry = LANG_COUNTRY.get(lang);
        try {
            Locale sys;
            try {
                sys = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
            } catch (Throwable e) {
                sys = Locale.getDefault();
            }
            a.region = sys.toLanguageTag();
            String c = sys.getCountry() == null ? "" : sys.getCountry().toUpperCase(Locale.ROOT);
            a.regionCountry = known(c) ? c : null;
        } catch (Throwable ignored) {
        }
        a.rdsDigit = digit;
        a.rdsExact = exact;
        a.rdsServices = services;

        // 0.4.0: the cars' time zone is unreliable (Italian or Spanish cars with Europe/London or Europe/Paris).
        // Order: 1) RDS/DAB  2) language and region agreeing (e.g. it + IT)  3) time zone  4) region alone.
        // (Language alone remains as the last fallback before the default.)
        // English (en-GB) is the firmware's starting language even outside the United Kingdom (Australia, Germany,
        // France in the logs): "en + GB" says nothing about the country and does not count as agreement.
        String agree = known(a.langCountry) && a.langCountry.equals(a.regionCountry) && !"en".equals(a.lang)
                ? a.langCountry : null;
        if (digit != 0) {
            List<String> cand = exact != null ? java.util.Collections.singletonList(exact) : rdsCandidates(digit);
            String pick = null;
            if (cand.size() == 1) pick = cand.get(0);
            else {
                // same RDS digit for several countries: the other source RDS confirms decides, in the same order
                for (String s : new String[]{agree, a.tzCountry, a.regionCountry, a.langCountry})
                    if (s != null && cand.contains(s)) { pick = s; break; }
            }
            if (known(pick)) {
                a.country = pick;
                a.reason = "RDS";
                a.rdsNote = exact != null ? "exact ECC" : cand.size() == 1 ? "RDS digit" : "RDS digit confirmed by another source";
                return a;
            }
            a.rdsNote = "ambiguous RDS (" + String.join("/", cand) + ")";
        }
        if (agree != null) {
            a.country = agree;
            a.reason = "language and region";
        } else if (known(a.tzCountry)) {
            a.country = a.tzCountry;
            a.reason = "timezone";
        } else if (a.regionCountry != null) {
            a.country = a.regionCountry;
            a.reason = "region";
        } else if (known(a.langCountry)) {
            a.country = a.langCountry;
            a.reason = "language";
        } else {
            a.country = "IT";
            a.reason = "default";
        }
        return a;
    }

    /**
     * DAB stations found by the radio: the RDS country is derived from the service identifier
     * (top 4 bits of the 16 bits; with 32 bits there is also the ECC). FM ones are written only to the diagnostics.
     */
    public static void noteStations(int band, List<MediaClient.Station> list) {
        LogoPacks p = instance;
        if (p == null || list == null || list.isEmpty()) return;
        if (band == MediaClient.BAND_FM) {
            StringBuilder sb = new StringBuilder();
            for (MediaClient.Station st : list) {
                if (st.sid <= 0) continue;
                if (sb.length() > 120) { sb.append('…'); break; }
                sb.append(sb.length() == 0 ? "" : " ").append(Long.toHexString(st.sid).toUpperCase(Locale.ROOT));
            }
            Diag.put("logos.rdsFM", sb.length() == 0 ? "no code" : sb.toString());
            return;
        }
        if (band != MediaClient.BAND_DAB) return;
        boolean changed = false;
        synchronized (p.rdsSeen) {
            for (MediaClient.Station st : list) {
                long sid = st.sid;
                if (sid <= 0 || p.rdsSeen.containsKey(sid)) continue;
                String v;
                if (sid > 0xFFFFL) {
                    // 32-bit service: ECC (8 bits) + country digit (4 bits) + number
                    int ecc = (int) ((sid >> 24) & 0xFF);
                    char d = Character.toUpperCase(Character.forDigit((int) ((sid >> 20) & 0xF), 16));
                    String cc = RDS_COUNTRY.get(String.format(Locale.ROOT, "%02X:%c", ecc, d));
                    v = d + (cc == null ? "" : ":" + cc);
                } else {
                    v = String.valueOf(Character.toUpperCase(Character.forDigit((int) ((sid >> 12) & 0xF), 16)));
                }
                if (v.charAt(0) == '0') continue;
                p.rdsSeen.put(sid, v);
                changed = true;
            }
        }
        if (changed) {
            String before = p.lastAuto == null ? "" : p.lastAuto.country;
            AutoInfo a = p.auto(com.mg4.launcher.L.locale().getLanguage());
            if (AUTO.equals(p.choice()) && !a.country.equals(before)) {
                Diag.put("logos.countries", AUTO + " → [" + a.country + "]");
                p.refresh(false);
            }
        }
    }

    public List<String> chosen() {
        String v = choice();
        List<String> out = new ArrayList<>();
        if (AUTO.equals(v)) {
            out.add(auto(com.mg4.launcher.L.locale().getLanguage()).country);
        } else {
            for (String s : v.split(",")) if (s.trim().length() == 2) out.add(s.trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    public static String countryName(String cc) {
        String n = new Locale("", cc).getDisplayCountry(com.mg4.launcher.L.locale());
        return n == null || n.isEmpty() || n.equals(cc) ? cc : n;
    }

    // ---- state -----------------------------------------------------------------------------

    public Map<String, Pack> installed() {
        return packs;
    }

    public String status() {
        return status;
    }

    public boolean isBusy() {
        return busy;
    }

    // ---- logo lookup -----------------------------------------------------------------------

    /** Logo file for the normalized name (also via alias), in the packs of the countries chosen first. */
    public File find(String norm) {
        if (norm == null || norm.isEmpty()) return null;
        Map<String, Pack> p = packs;
        List<String> order = new ArrayList<>(chosen());
        for (String cc : p.keySet()) if (!order.contains(cc)) order.add(cc);
        for (String cc : order) {
            Pack pk = p.get(cc);
            if (pk == null) continue;
            File f = pk.files.get(norm);
            if (f == null) {
                String a = pk.alias.get(norm);
                if (a != null) f = pk.files.get(a);
            }
            if (f == null && norm.startsWith("radio")) f = pk.files.get(norm.substring(5));
            if (f == null) f = pk.files.get("radio" + norm);
            if (f != null) return f;
        }
        // variants of a station without its own logo ("RTL 102.5 Traffic" → rtl1025): only in the chosen countries,
        // with a name of at least 7 characters and never between two digits ("radio10" does not match "radio101")
        for (String cc : chosen()) {
            Pack pk = p.get(cc);
            if (pk == null) continue;
            String best = null;
            for (String k : pk.files.keySet()) {
                if (k.length() < 7 || k.length() >= norm.length() || !norm.startsWith(k)) continue;
                if (Character.isDigit(k.charAt(k.length() - 1)) && Character.isDigit(norm.charAt(k.length()))) continue;
                if (best == null || k.length() > best.length()) best = k;
            }
            if (best != null) return pk.files.get(best);
        }
        return null;
    }

    // ---- loading ---------------------------------------------------------------------------

    private void loadAll() {
        Map<String, Pack> out = new HashMap<>();
        File[] dirs = root.listFiles();
        if (dirs != null) for (File d : dirs) {
            if (!d.isDirectory() || d.getName().length() != 2) continue;
            Pack p = load(d);
            if (p != null) out.put(p.cc, p);
        }
        packs = out;
        report();
        notifyChanged();
    }

    private Pack load(File d) {
        Pack p = new Pack();
        p.cc = d.getName();
        File[] fs = d.listFiles();
        if (fs == null) return null;
        long size = 0;
        for (File f : fs) {
            String n = f.getName();
            size += f.length();
            int dot = n.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.equals("webp") || ext.equals("png") || ext.equals("jpg")) p.files.put(n.substring(0, dot), f);
        }
        File al = new File(d, "alias.txt");
        if (al.exists()) {
            try (InputStream in = new FileInputStream(al)) {
                for (String line : new String(readAll(in), "UTF-8").split("\n")) {
                    line = line.trim();
                    int eq = line.indexOf('=');
                    if (line.isEmpty() || line.startsWith("#") || eq <= 0) continue;
                    p.alias.put(StationLogos.norm(line.substring(0, eq)), StationLogos.norm(line.substring(eq + 1)));
                }
            } catch (Exception ignored) {
            }
        }
        if (p.files.isEmpty()) return null;
        p.logos = p.files.size();
        p.size = size;
        p.version = sp.getInt("ver_" + p.cc, 0);
        p.source = sp.getString("src_" + p.cc, "");
        return p;
    }

    private void report() {
        StringBuilder sb = new StringBuilder();
        for (Pack p : packs.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.cc).append(" v").append(p.version).append(' ').append(p.logos).append(" logos ")
                    .append(String.format(Locale.ROOT, "%.1f MB", p.size / 1048576f))
                    .append(p.source.isEmpty() ? "" : " (" + p.source + ")");
        }
        Diag.put("logos.countries", choice() + " → " + chosen());
        AutoInfo a = auto(com.mg4.launcher.L.locale().getLanguage());
        if (!AUTO.equals(choice())) Diag.put("logos.carCountry", a.describe() + " · not used: countries chosen manually");
        Diag.put("logos.packs", sb.length() == 0 ? "none" : sb.toString());
    }

    // ---- download --------------------------------------------------------------------------

    /** Pack check: immediately if a chosen country is missing, otherwise at most once a week. */
    public void refresh(boolean force) {
        bg.post(() -> check(force));
    }

    /** Call every now and then (e.g. every minute): downloads when the network arrives. */
    public void tick() {
        bg.post(() -> {
            boolean missing = false;
            for (String cc : chosen()) if (!packs.containsKey(cc)) missing = true;
            long last = sp.getLong("lastCheck", 0);
            if ((missing || System.currentTimeMillis() - last > WEEK_MS) && System.currentTimeMillis() >= nextTry) check(false);
        });
    }

    private void check(boolean force) {
        if (busy) return;
        if (!online() || (!force && !Offline.canTry())) {
            setStatus("offline");
            return;
        }
        busy = true;
        notifyChanged();
        try {
            JSONObject idx = new JSONObject(new String(fetch(BASE_URL + "index.json", 1_000_000), "UTF-8"));
            JSONArray arr = idx.getJSONArray("countries");
            Map<String, JSONObject> byCc = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                byCc.put(o.getString("code"), o);
            }
            StringBuilder res = new StringBuilder();
            for (String cc : chosen()) {
                JSONObject o = byCc.get(cc);
                if (o == null) {
                    res.append(cc).append(": no pack; ");
                    continue;
                }
                Pack cur = packs.get(cc);
                String sha = o.optString("sha256", "");
                if (!force && cur != null && sha.equals(sp.getString("sha_" + cc, ""))) {
                    res.append(cc).append(": up to date; ");
                    continue;
                }
                if (cur != null && sha.equals(sp.getString("sha_" + cc, ""))) {
                    res.append(cc).append(": already at the latest version; ");
                    continue;
                }
                byte[] zip = fetch(BASE_URL + o.optString("file", cc + ".zip"), 40_000_000);
                String got = sha256(zip);
                if (!got.equalsIgnoreCase(sha)) {
                    res.append(cc).append(": hash mismatch, discarded; ");
                    Diag.error("logos.download", cc + " hash " + got + " instead of " + sha);
                    continue;
                }
                int n = install(cc, zip);
                sp.edit().putString("sha_" + cc, sha).putInt("ver_" + cc, o.optInt("version", 1)).putString("src_" + cc, "internet").apply();
                res.append(cc).append(": downloaded (").append(n).append(" logos); ");
            }
            sp.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
            Diag.put("logos.download", res.toString().trim());
            migrate083();
            setStatus("ok");
            nextTry = 0;
            Offline.ok();
        } catch (Exception e) {
            nextTry = System.currentTimeMillis() + RETRY_MS;
            if (Offline.failed("logo packs", e)) {
                // 0.4.0: without network a single line (rete.offline), not one error per attempt
                nextTry = Math.max(System.currentTimeMillis() + Offline.waitMs(), System.currentTimeMillis() + 60_000L);
                setStatus("offline");
            } else {
                Diag.error("logos.download", e.toString());
                setStatus("error");
            }
        } finally {
            busy = false;
            loadAll();
        }
    }

    /**
     * 0.8.3, only once: those on "Automatico" had the wrong country's pack (firmware region, e.g. CH
     * for Italian cars). As soon as the right one is present, the other automatically downloaded packs are removed.
     * Those who chose countries by hand are not touched.
     */
    private void migrate083() {
        if (sp.getBoolean("migrated083", false)) return;
        if (!AUTO.equals(choice())) {
            sp.edit().putBoolean("migrated083", true).apply();
            return;
        }
        List<String> ch = chosen();
        for (String cc : ch) if (!new File(root, cc).isDirectory()) return;   // the right one must be there first
        StringBuilder gone = new StringBuilder();
        for (String cc : new ArrayList<>(packs.keySet())) {
            if (ch.contains(cc) || !"internet".equals(sp.getString("src_" + cc, ""))) continue;
            deleteTree(new File(root, cc));
            sp.edit().remove("sha_" + cc).remove("ver_" + cc).remove("src_" + cc).apply();
            gone.append(gone.length() == 0 ? "" : ", ").append(cc);
        }
        sp.edit().putBoolean("migrated083", true).apply();
        Diag.put("logos.migration", gone.length() == 0 ? "nothing to change (" + ch + ")" : "removed " + gone + ", now " + ch);
    }

    private void setStatus(String s) {
        status = s;
        notifyChanged();
    }

    /** Extracts the zip into a new folder and then swaps it with the old one (never halfway). */
    private int install(String cc, byte[] zip) throws Exception {
        File tmp = new File(root, cc + ".tmp");
        deleteTree(tmp);
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        int n = 0;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = new File(e.getName()).getName();   // no paths inside the zip
                String low = name.toLowerCase(Locale.ROOT);
                boolean img = low.endsWith(".webp") || low.endsWith(".png") || low.endsWith(".jpg");
                if (!img && !low.equals("alias.txt")) continue;
                try (FileOutputStream o = new FileOutputStream(new File(tmp, name))) {
                    int r;
                    long total = 0;
                    while ((r = z.read(buf)) > 0) {
                        total += r;
                        if (total > 2_000_000) throw new IllegalStateException("file too large: " + name);
                        o.write(buf, 0, r);
                    }
                }
                if (img) n++;
            }
        }
        if (n == 0) {
            deleteTree(tmp);
            throw new IllegalStateException("no logo in the zip");
        }
        File dst = new File(root, cc);
        deleteTree(dst);
        if (!tmp.renameTo(dst)) throw new IllegalStateException("rename failed");
        return n;
    }

    public void delete(String cc) {
        bg.post(() -> {
            deleteTree(new File(root, cc));
            sp.edit().remove("sha_" + cc).remove("ver_" + cc).remove("src_" + cc).apply();
            Diag.put("logos.deleted", cc);
            loadAll();
        });
    }

    // ---- USB stick -------------------------------------------------------------------------

    /** Pack zips found on storage or USB stick (MG4Launcher/logos/CC.zip or MG4Launcher/loghi/CC.zip), not yet offered. */
    public List<File> usbPacksToOffer() {
        List<File> out = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        for (String uf : StationLogos.USER_FOLDERS) {
            dirs.add(new File(Environment.getExternalStorageDirectory(), uf));
            File[] vols = new File("/storage").listFiles();
            if (vols != null) for (File v : vols) {
                if (v.getName().equals("emulated") || v.getName().equals("self")) continue;
                dirs.add(new File(v, uf));
            }
        }
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName();
                if (!n.toLowerCase(Locale.ROOT).endsWith(".zip") || n.length() != 6) continue;
                String id = f.getPath() + ":" + f.length() + ":" + f.lastModified();
                if (sp.getBoolean("usb_" + id, false)) continue;
                synchronized (offered) {
                    if (!offered.add(id)) continue;
                }
                out.add(f);
            }
        }
        return out;
    }

    /** Installs (or rejects, install = false) the zip found on the USB stick; it will not be offered again. */
    public void installUsb(File f, boolean install) {
        String id = f.getPath() + ":" + f.length() + ":" + f.lastModified();
        sp.edit().putBoolean("usb_" + id, true).apply();
        if (!install) return;
        bg.post(() -> {
            String cc = f.getName().substring(0, 2).toUpperCase(Locale.ROOT);
            try (InputStream in = new FileInputStream(f)) {
                byte[] zip = readAll(in);
                int n = install(cc, zip);
                sp.edit().putString("sha_" + cc, sha256(zip)).putString("src_" + cc, "usb").apply();
                Diag.put("logos.usbStick", cc + ": installed (" + n + " logos) from " + f.getPath());
            } catch (Exception e) {
                Diag.error("logos.usbStick", cc + ": " + e);
            }
            loadAll();
        });
    }

    // ---- utilities -------------------------------------------------------------------------

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

    private static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static byte[] fetch(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "MG4Launcher/1.0");
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code + " " + url);
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
            }
        } finally {
            c.disconnect();
        }
    }
}
