package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One-time migration of the preference keys (and of a few stored values) from the old Italian/Portuguese names
 * to the English ones. Every preference file is opened through {@link #open(Context, String)} (or through
 * {@link com.mg4.launcher.ui.Prefs#sp(Context)} for the main file), so nothing can read a key before the migration.
 *
 * <p>Rules: each old key is copied to the new key with its exact type, only if the new key is not already there,
 * and then removed. Each file gets the int marker {@link #MARKER} = {@link #SCHEMA} in the same commit; once
 * the marker is there the file is never scanned again. The file names themselves are unchanged.
 *
 * <p>This is the only place where the old key names may appear.
 */
public final class PrefsMigration {
    private static final String TAG = "MG4L.prefs";

    /** Preference file names (unchanged on purpose: invisible to the user). */
    public static final String MAIN = "mg4launcher", LOGOS = "loghi_pacchetti", DONGLE = "dongle", CRASH = "mg4salvavita";

    public static final String MARKER = "prefsSchema";
    public static final int SCHEMA = 2;

    /** Main file "mg4launcher": old key → new key (null = dropped). */
    private static final String[][] MAIN_KEYS = {
            // migrations of 0.3.0 (SWI69 0.2.x names), formerly in Prefs.migrateFrom02x()
            {"lingua", "language"},
            {"mostraTempInterna", "showInsideTemp"},
            {"mostraTpms", "showTpms"},
            {"socDecimali", "socDecimals"},
            {"migrato030", null},
            // Prefs
            {"cerchi", "wheels"},
            {"vidros.auto", "winAuto"},
            {"vidros.velocidade", "winSpeed"},
            {"vidros.minutos", "winMinutes"},
            {"vidros.atraso", "winDelay"},
            {"vidros.portaAbre", "winOnDoorOpen"},
            {"vidros.bip", "winBeep"},
            {"vidros.bipVolume", "winBeepVolume"},
            {"dimensioneComandi", "uiScale"},
            {"dimensioneApp", "appsScale"},
            {"posizioneGuida", "driveSide"},
            {"tempInternaAssente", "insideTempMissing"},
            {"tempInternaModo", "insideTempMode"},
            {"mostraData", "showDate"},
            {"unitaPressione", "tyreUnit"},
            {"pulsantiRicarica", "chargeButtons"},
            {"retronebbia", "rearFog"},
            {"barraGrande", "bigBar"},
            {"pulsantiRaggruppati", "groupedButtons"},
            {"mostraAssistenza", "showAssist"},
            {"premiHomeSecondi", "autoHomeSeconds"},
            {"novitaVersione", "newsShownFor"},
            {"unitaDistanza", "distUnit"},
            {"tasto360", "cam360"},
            {"sorgentiNascoste", "hiddenSources"},
            {"animazioneRicarica", "chargeAnim"},
            {"riquadroBasso", "tileMode"},
            {"riquadroContatti", "tileContacts"},
            {"riquadroApp", "tileApps"},
            {"limiteRicaricaModo", "chargeLimitMode"},
            {"riscaldamentoModo", "heatMode"},
            {"retroRitorno", "retroReturn"},
            {"pulsanteAltoDestra", "topButton"},
            {"pulsanteRapido", null},   // 0.4.2–0.4.3 top button: folded into "topButton" below
            // MainActivity (remote notices)
            {"avvisi.visti", "noticesSeen"},
            {"avvisi.confermati", "noticesConfirmed"},
            // HomeLock, HomeMode, StoreSetup, CarFeatures, SelfCheck, StartGuard
            {"avvioSicuro", "safeStart"},
            {"disinstallazioneInCorso", "uninstallInProgress"},
            {"homePredefinita", "defaultHome"},
            {"officinaFinoA", "workshopUntil"},
            {"officinaHomePrima", "workshopHomeBefore"},
            {"storeGestito", "storeManaged"},
            {"spiaLarghezza", "indicatorInset"},
            {"controlloFattoPer", "checkDoneFor"},
            {"homeOriginaleFinoA", "originalHomeUntil"},
            {"homeOriginaleAvvio", "originalHomeBoot"},
            // DayNight
            {"temaNotteSistemaAlle", "themeSysNightAt"},
            {"temaUltimaLat", "themeLastLat"},
            {"temaUltimaLon", "themeLastLon"},
            {"temaUiUltimo", "themeUiLast"},
            {"temaUiCambiatoAlle", "themeUiChangedAt"},
            {"temaUiAccesoMs", "themeUiOnMs"},
            {"temaUiBloccato", "themeUiStuck"},
            {"temaUiBloccatoPerche", "themeUiStuckWhy"},
            // MediaClient
            {"media.ultimaSorgente", "mediaLastSource"},
            {"radio_fm_elenco", "radioFmList"},
            {"radio_dab_elenco", "radioDabList"},
            {"radio_fm_fonte", "radioFmSource"},
            {"radio_dab_fonte", "radioDabSource"},
            {"radio_preferiti", "radioFavorites"},
    };
    /** Main file: dynamic keys, old prefix → new prefix (the rest of the key is kept). */
    private static final String[][] MAIN_PREFIXES = {
            {"vidros.modo", "winMode"},                          // + window area 0..3
            {"aggiornamento.tentativi.", "updateAttempts."},     // + version tag (Updater)
            {"animControllo.", "animCheck."},                    // + color slug [+ ".tentativo"] (ChargePack)
    };
    /** Main file: old suffix → new suffix, applied after the prefix (ChargePack "<prefix><slug>.tentativo"). */
    private static final String[][] MAIN_SUFFIXES = {
            {".tentativo", ".attempt"},
    };
    /** Main file: stored values compared in code, per (new) key: old value → new value. */
    private static final String[][][] MAIN_VALUES = {
            {{"theme"}, {"giorno", "day"}, {"notte", "night"}},
            {{"topButton"}, {"proiezione", "projection"}, {"nessuno", "none"}},
            {{"tileMode"}, {"telefono", "phone"}},
            {{"cam360"}, {"nascosto", "hidden"}},
    };

    /** LogoPacks "loghi_pacchetti" (the "sha_", "src_", "ver_", "usb_" prefixes are already English and stay). */
    private static final String[][] LOGOS_KEYS = {
            {"paesi", "countries"},
            {"rds_dato", "rdsData"},
            {"rds_servizi", "rdsServices"},
            {"controllo", "lastCheck"},
            {"migrazione083", "migrated083"},
    };
    /** LogoPacks: pack source "chiavetta" (USB stick) → "usb", for every "src_<country>" key. */
    private static final String[][] LOGOS_SRC_VALUES = {{"chiavetta", "usb"}};

    /** DongleWatch "dongle" ("boot" stays). */
    private static final String[][] DONGLE_KEYS = {
            {"attivo", "enabled"},
            {"secondi", "seconds"},
            {"tentativi", "attempts"},
            {"seriali", "serials"},
            {"telefoni", "phones"},
    };

    /** CrashGuard "mg4salvavita", in device-protected storage ("crash" stays). */
    private static final String[][] CRASH_KEYS = {
            {"scattato", "tripped"},
            {"scattatoAlle", "trippedAt"},
    };

    private static boolean mainDone, logosDone, dongleDone, crashDone;
    private static volatile boolean allDone;

    private PrefsMigration() {}

    /** Opens a preference file of the launcher, migrated first. */
    public static SharedPreferences open(Context c, String file) {
        ensure(c);
        return app(c).getSharedPreferences(file, Context.MODE_PRIVATE);
    }

    /**
     * Migrates every preference file once per process (no-op when each file already carries the marker).
     * A file that cannot be opened yet (e.g. credential storage still locked) is retried on the next call.
     */
    public static void ensure(Context c) {
        if (allDone || c == null) return;
        synchronized (PrefsMigration.class) {
            if (allDone) return;
            Context a = app(c);
            if (!mainDone) mainDone = run(a, MAIN, false);
            if (!logosDone) logosDone = run(a, LOGOS, false);
            if (!dongleDone) dongleDone = run(a, DONGLE, false);
            if (!crashDone) crashDone = run(a, CRASH, true);
            allDone = mainDone && logosDone && dongleDone && crashDone;
        }
    }

    private static Context app(Context c) {
        Context a = c.getApplicationContext();
        return a != null ? a : c;
    }

    private static boolean run(Context a, String file, boolean deviceProtected) {
        try {
            Context ctx = a;
            if (deviceProtected) {
                try {
                    ctx = a.createDeviceProtectedStorageContext();
                } catch (Throwable ignored) {
                    ctx = a;
                }
            }
            SharedPreferences sp = ctx.getSharedPreferences(file, Context.MODE_PRIVATE);
            int moved = migrate(sp, file);
            if (moved >= 0) Log.i(TAG, file + ": migrated to schema " + SCHEMA + " (" + moved + " keys)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, file + ": migration not possible now: " + t);
            return false;
        }
    }

    /** Returns the number of keys moved, or -1 if the file was already migrated. */
    private static int migrate(SharedPreferences sp, String file) {
        if (sp.getInt(MARKER, 0) >= SCHEMA) return -1;
        Map<String, ?> all = sp.getAll();
        String[][] keys;
        String[][] prefixes = {}, suffixes = {};
        switch (file) {
            case MAIN:
                keys = MAIN_KEYS;
                prefixes = MAIN_PREFIXES;
                suffixes = MAIN_SUFFIXES;
                break;
            case LOGOS:
                keys = LOGOS_KEYS;
                break;
            case DONGLE:
                keys = DONGLE_KEYS;
                break;
            default:
                keys = CRASH_KEYS;
                break;
        }
        Map<String, String> exact = new HashMap<>();
        Set<String> dropped = new HashSet<>();
        for (String[] k : keys) {
            if (k[1] == null) dropped.add(k[0]);
            else exact.put(k[0], k[1]);
        }

        SharedPreferences.Editor e = sp.edit();
        // final value of every key this pass touches (absent = untouched); used for the "new key absent" test
        Map<String, Object> out = new HashMap<>();
        int moved = 0;

        if (MAIN.equals(file)) {
            // 0.4.4: with no saved top button, an "app:" choice of the old 0.4.2–0.4.3 quick button was carried over
            Object quick = all.get("pulsanteRapido");
            if (quick instanceof String && !all.containsKey("pulsanteAltoDestra") && !all.containsKey("topButton")) {
                String q = (String) quick;
                out.put("topButton", q.startsWith("app:") ? q : "projection");
            }
        }

        for (Map.Entry<String, ?> en : all.entrySet()) {
            String old = en.getKey();
            if (old == null || MARKER.equals(old)) continue;
            if (dropped.contains(old)) {
                e.remove(old);
                moved++;
                continue;
            }
            String nk = exact.get(old);
            if (nk == null) nk = renamePrefix(old, prefixes, suffixes);
            if (nk == null || nk.equals(old)) continue;
            if (!all.containsKey(nk) && !out.containsKey(nk)) out.put(nk, en.getValue());
            e.remove(old);
            moved++;
        }

        // value translation, both on moved values and on keys whose name did not change (e.g. "theme")
        if (MAIN.equals(file)) {
            for (String[][] vm : MAIN_VALUES) mapValue(vm[0][0], vm, 1, all, out);
        } else if (LOGOS.equals(file)) {
            Set<String> srcKeys = new HashSet<>();
            for (String k : all.keySet()) if (k != null && k.startsWith("src_")) srcKeys.add(k);
            for (String k : srcKeys) mapValue(k, LOGOS_SRC_VALUES, 0, all, out);
        }

        for (Map.Entry<String, Object> en : out.entrySet()) put(e, en.getKey(), en.getValue());
        e.putInt(MARKER, SCHEMA);
        if (!e.commit()) throw new IllegalStateException("commit failed");
        return moved;
    }

    /** New name of a dynamic key, or null if it matches no prefix. */
    private static String renamePrefix(String old, String[][] prefixes, String[][] suffixes) {
        for (String[] p : prefixes) {
            if (!old.startsWith(p[0]) || old.length() == p[0].length()) continue;
            String rest = old.substring(p[0].length());
            for (String[] s : suffixes) {
                if (rest.endsWith(s[0])) {
                    rest = rest.substring(0, rest.length() - s[0].length()) + s[1];
                    break;
                }
            }
            return p[1] + rest;
        }
        return null;
    }

    /** Translates the stored value of key (moved in this pass or already there) with pairs map[from..]. */
    private static void mapValue(String key, String[][] map, int from, Map<String, ?> all, Map<String, Object> out) {
        Object v = out.containsKey(key) ? out.get(key) : all.get(key);
        if (!(v instanceof String)) return;
        for (int i = from; i < map.length; i++) {
            if (map[i][0].equals(v)) {
                out.put(key, map[i][1]);
                return;
            }
        }
    }

    /** Writes v with its exact type. */
    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else if (v instanceof String) e.putString(k, (String) v);
        else if (v instanceof Set) e.putStringSet(k, new HashSet<>((Set<String>) v));
    }
}
