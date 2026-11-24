package com.mg4.launcher.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Launcher settings. */
public final class Prefs {
    public static final String[] CAR_COLORS = {
            "Arctic White", "Black Pearl", "Camden Grey", "Cosmic Silver",
            "Ealing Green", "Piccadilly Blue", "Dynamic Red", "Volcano Orange"};
    public static final int[] CAR_COLOR_VALUES = {
            0xFFE8E9E6, 0xFF202227, 0xFF5D6166, 0xFFA9ADB2,
            0xFF4C6A5A, 0xFF2E5597, 0xFFA5161E, 0xFFE1621F};
    /** Rims: names, images and size relative to the 17" of the photo. */
    public static final int[] WHEEL_NAMES = {com.mg4.launcher.R.string.wheel_16, com.mg4.launcher.R.string.wheel_17,
            com.mg4.launcher.R.string.wheel_18_lux, com.mg4.launcher.R.string.wheel_18_xpower};
    public static final int[] WHEEL_RES = {com.mg4.launcher.R.drawable.rim_black16, com.mg4.launcher.R.drawable.rim_aero17,
            com.mg4.launcher.R.drawable.rim_luxury18, com.mg4.launcher.R.drawable.rim_xpower18};
    public static final float[] WHEEL_SCALE = {0.95f, 1f, 1.06f, 1.06f};
    /** The rims of the car photo (stock): not redrawn. */
    public static final int WHEEL_PHOTO = 1;
    public static final String THEME_AUTO = "auto", THEME_NIGHT = "notte", THEME_DAY = "giorno";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        migrateFrom02x();
    }

    /** SWI69 0.2.x settings with the old names: copied once into the 0.3.0 names (shared with the SWI68). */
    private void migrateFrom02x() {
        if (sp.getBoolean("migrato030", false)) return;
        SharedPreferences.Editor e = sp.edit();
        if (sp.contains("lingua") && !sp.contains("language")) e.putString("language", sp.getString("lingua", "auto"));
        if (sp.contains("mostraTempInterna") && !sp.contains("showInsideTemp")) e.putBoolean("showInsideTemp", sp.getBoolean("mostraTempInterna", false));
        if (sp.contains("mostraTpms") && !sp.contains("showTpms")) e.putBoolean("showTpms", sp.getBoolean("mostraTpms", true));
        if (sp.contains("socDecimali") && !sp.contains("socDecimals")) e.putBoolean("socDecimals", sp.getBoolean("socDecimali", false));
        e.putBoolean("migrato030", true).apply();
    }

    public int carColorIndex() {
        return Math.max(0, Math.min(CAR_COLORS.length - 1, sp.getInt("carColor", 5)));
    }

    public void setCarColorIndex(int i) {
        sp.edit().putInt("carColor", i).apply();
    }

    public int wheelIndex() {
        return Math.max(0, Math.min(WHEEL_RES.length - 1, sp.getInt("cerchi", WHEEL_PHOTO)));
    }

    public void setWheelIndex(int i) {
        sp.edit().putInt("cerchi", i).apply();
    }

    // --- Windows: automatic closing when leaving the car ---
    /** Mode of each window: AUTO = car's automatic closing, PULSE = held "up" command, OFF = never. */
    public static final int WIN_AUTO = 0, WIN_PULSE = 1, WIN_OFF = 2;
    public static final int[] WIN_SPEEDS = {5, 10, 20, 30, 40};
    public static final int[] WIN_MINUTES = {1, 3, 5, 10, 15};
    public static final int[] WIN_DELAYS = {0, 3, 5, 10, 20, 30};
    public static final int[] WIN_VOLUMES = {25, 50, 75, 100};

    public boolean winAuto() {
        return sp.getBoolean("vidros.auto", false);
    }

    public void setWinAuto(boolean on) {
        sp.edit().putBoolean("vidros.auto", on).apply();
    }

    public int winSpeed() {
        return sp.getInt("vidros.velocidade", 20);
    }

    public void setWinSpeed(int kmh) {
        sp.edit().putInt("vidros.velocidade", kmh).apply();
    }

    public int winMinutes() {
        return sp.getInt("vidros.minutos", 5);
    }

    public void setWinMinutes(int m) {
        sp.edit().putInt("vidros.minutos", m).apply();
    }

    public int winDelay() {
        return sp.getInt("vidros.atraso", 5);
    }

    public void setWinDelay(int s) {
        sp.edit().putInt("vidros.atraso", s).apply();
    }

    /** true = the countdown starts when the door opens; false = when it closes. */
    public boolean winOnDoorOpen() {
        return sp.getBoolean("vidros.portaAbre", true);
    }

    public void setWinOnDoorOpen(boolean open) {
        sp.edit().putBoolean("vidros.portaAbre", open).apply();
    }

    public boolean winBeep() {
        return sp.getBoolean("vidros.bip", false);
    }

    public void setWinBeep(boolean on) {
        sp.edit().putBoolean("vidros.bip", on).apply();
    }

    public int winBeepVolume() {
        return sp.getInt("vidros.bipVolume", 50);
    }

    public void setWinBeepVolume(int v) {
        sp.edit().putInt("vidros.bipVolume", v).apply();
    }

    /** area: 0 front left, 1 front right, 2 rear left, 3 rear right. */
    public int winMode(int area) {
        int m = sp.getInt("vidros.modo" + area, WIN_PULSE);
        return m < WIN_AUTO || m > WIN_OFF ? WIN_PULSE : m;
    }

    public void setWinMode(int area, int mode) {
        sp.edit().putInt("vidros.modo" + area, mode).apply();
    }

    public boolean blackRoof() {
        return sp.getBoolean("blackRoof", true);
    }

    public void setBlackRoof(boolean b) {
        sp.edit().putBoolean("blackRoof", b).apply();
    }

    public String themeMode() {
        return sp.getString("theme", THEME_AUTO);
    }

    public void setThemeMode(String m) {
        sp.edit().putString("theme", m).apply();
    }

    // --- installed apps hidden from the grid ---

    public Set<String> hiddenApps() {
        return new HashSet<>(sp.getStringSet("hiddenApps", new HashSet<>()));
    }

    public void setAppHidden(String key, boolean hide) {
        Set<String> s = hiddenApps();
        if (hide) s.add(key);
        else s.remove(key);
        sp.edit().putStringSet("hiddenApps", s).apply();
    }

    // --- updates from GitHub ---
    public static final String DEFAULT_REPO = "InfectedM/Mg4-Launcher-SWI69";
    /** Previous repository names (renamed): switched to the new one automatically. */
    // fork: anyone coming from the original version with the repository saved moves to the fork's one
    private static final String[] OLD_REPOS = {"ernestomicco2026-cell/Mg4-Launcher-SW169", "ernestomicco2026-cell/Mg4-Launcher-SWI69"};

    public String updateRepo() {
        String r = sp.getString("ghRepo", DEFAULT_REPO);
        for (String old : OLD_REPOS) {
            if (old.equalsIgnoreCase(r)) {
                sp.edit().putString("ghRepo", DEFAULT_REPO).apply();
                return DEFAULT_REPO;
            }
        }
        return r;
    }

    public void setUpdateRepo(String r) {
        sp.edit().putString("ghRepo", r == null || r.trim().isEmpty() ? DEFAULT_REPO : r.trim()).apply();
    }

    /** Read-only GitHub token: needed because the repository is private. */
    public String githubToken() {
        return sp.getString("ghToken", "");
    }

    public void setGithubToken(String tok) {
        sp.edit().putString("ghToken", tok == null ? "" : tok.trim()).apply();
    }

    // --- default navigator: "auto" (phone if connected, otherwise MG), "mg", "phone" or package name ---
    public static final String NAV_AUTO = "auto", NAV_MG = "mg", NAV_PHONE = "phone";

    public String navApp() {
        // 0.4.4: the SWI69 has no MG navigator: default Automatic (also for an old "mg" value)
        String v = sp.getString("navApp", NAV_AUTO);
        return NAV_MG.equals(v) ? NAV_AUTO : v;
    }

    public void setNavApp(String v) {
        sp.edit().putString("navApp", v).apply();
    }

    // --- language: "auto" (system language) or code (it, en, es, de, pt, fr, tr) ---
    public String language() {
        return sp.getString("language", com.mg4.launcher.L.AUTO);
    }

    public void setLanguage(String code) {
        sp.edit().putString("language", code).apply();
    }

    // --- display ---
    public boolean socDecimals() {
        return sp.getBoolean("socDecimals", false);
    }

    public void setSocDecimals(boolean b) {
        sp.edit().putBoolean("socDecimals", b).apply();
    }

    /** 0.3.4: battery percentage large and range next to the bar (km and % swapped). */
    public boolean socBig() {
        return sp.getBoolean("socBig", false);
    }

    public void setSocBig(boolean b) {
        sp.edit().putBoolean("socBig", b).apply();
    }

    public boolean showTpms() {
        return sp.getBoolean("showTpms", true);
    }

    public void setShowTpms(boolean b) {
        sp.edit().putBoolean("showTpms", b).apply();
    }

    /** Discreet popup when a new version is on GitHub. */
    public boolean updatePopup() {
        return sp.getBoolean("updatePopup", true);
    }

    /** Last version for which "Più tardi" was pressed: for that one the popup does not reappear. */
    public String dismissedUpdate() {
        return sp.getString("dismissedUpdate", "");
    }

    public void setDismissedUpdate(String tag) {
        sp.edit().putString("dismissedUpdate", tag == null ? "" : tag).apply();
    }

    /** Control size: 0 Normal, 1 Large, 2 Very large. */
    public static final float[] UI_SCALES = {1f, 1.15f, 1.3f};

    public int uiScale() {
        return Math.max(0, Math.min(2, sp.getInt("dimensioneComandi", 0)));
    }

    public void setUiScale(int i) {
        sp.edit().putInt("dimensioneComandi", i).apply();
    }

    /** Size of the App screen (icons and text): 0 Normal, 1 Large, 2 Very large (1.x). */
    public static final float[] APPS_SCALES = {1f, 1.25f, 1.5f};

    public int appsScale() {
        return Math.max(0, Math.min(2, sp.getInt("dimensioneApp", 0)));
    }

    public void setAppsScale(int i) {
        sp.edit().putInt("dimensioneApp", i).apply();
    }

    public float appsScaleFactor() {
        return APPS_SCALES[appsScale()];
    }

    public float uiScaleFactor() {
        return UI_SCALES[uiScale()];
    }

    /** Driving position: "auto" (as the car), "left", "right". */
    public static final String DRIVE_AUTO = "auto", DRIVE_LEFT = "left", DRIVE_RIGHT = "right";

    public String driveSide() {
        String v = sp.getString("posizioneGuida", DRIVE_AUTO);
        return DRIVE_LEFT.equals(v) || DRIVE_RIGHT.equals(v) ? v : DRIVE_AUTO;
    }

    public void setDriveSide(String v) {
        sp.edit().putString("posizioneGuida", v).apply();
    }

    /** Inside temperature under the outside one (off by default: many cars do not provide it). */
    public boolean showInsideTemp() {
        return sp.getBoolean("showInsideTemp", false);
    }

    public void setShowInsideTemp(boolean b) {
        sp.edit().putBoolean("showInsideTemp", b).apply();
    }

    /** True if the car does not provide the inside temperature (the option is then hidden). */
    public boolean insideTempMissing() {
        return sp.getBoolean("tempInternaAssente", false);
    }

    /**
     * 0.4.3: inside temperature Automatic (only if the car provides it) / Show (even "--") / Hide.
     * Those who had turned on the 1.0.2 switch move to Automatic, those who had not touched it stay on Hide.
     */
    public String insideTempMode() {
        String v = sp.getString("tempInternaModo", null);
        if (SHOW_AUTO.equals(v) || SHOW_ON.equals(v) || SHOW_OFF.equals(v)) return v;
        return sp.getBoolean("showInsideTemp", false) ? SHOW_AUTO : SHOW_OFF;
    }

    public void setInsideTempMode(String v) {
        sp.edit().putString("tempInternaModo", v).apply();
    }

    // --- new in 0.8.0 ---

    /** Date between the range and the time (off by default). */
    public boolean showDate() {
        return sp.getBoolean("mostraData", false);
    }

    public void setShowDate(boolean b) {
        sp.edit().putBoolean("mostraData", b).apply();
    }

    /** Tyre pressure unit: "auto" (as the cluster), "bar", "kpa", "psi". */
    public static final String TYRE_AUTO = "auto", TYRE_BAR = "bar", TYRE_KPA = "kpa", TYRE_PSI = "psi";

    public String tyreUnit() {
        String v = sp.getString("unitaPressione", TYRE_AUTO);
        return TYRE_BAR.equals(v) || TYRE_KPA.equals(v) || TYRE_PSI.equals(v) ? v : TYRE_AUTO;
    }

    public void setTyreUnit(String v) {
        sp.edit().putString("unitaPressione", v).apply();
    }

    /** Charging buttons under the car (off by default: the space goes to the car). */
    public boolean chargeButtons() {
        return sp.getBoolean("pulsantiRicarica", false);
    }

    public void setChargeButtons(boolean b) {
        sp.edit().putBoolean("pulsantiRicarica", b).apply();
    }

    /** Rear fog button: "auto" (hidden if the system bar already has its indicator), "show", "hide". */
    public static final String FOG_AUTO = "auto", FOG_SHOW = "show", FOG_HIDE = "hide";

    public String rearFogMode() {
        String v = sp.getString("retronebbia", FOG_AUTO);
        return FOG_SHOW.equals(v) || FOG_HIDE.equals(v) ? v : FOG_AUTO;
    }

    public void setRearFogMode(String v) {
        sp.edit().putString("retronebbia", v).apply();
    }

    /** Rear fog button visible? systemIndicator = the system bar already shows the lights indicator. */
    public boolean showRearFog(boolean systemIndicator) {
        String m = rearFogMode();
        return FOG_SHOW.equals(m) || (FOG_AUTO.equals(m) && !systemIndicator);
    }

    /** Large bottom bar (150 px, buttons 120 px); without mini player. */
    public boolean bigBar() {
        return sp.getBoolean("barraGrande", false);
    }

    public void setBigBar(boolean b) {
        sp.edit().putBoolean("barraGrande", b).apply();
    }

    /** 0.8.2: A/C, AUTO and recirculation in "Clima", seats and wheel in "Comfort" (one button per group). */
    public boolean groupedButtons() {
        return sp.getBoolean("pulsantiRaggruppati", true);
    }

    public void setGroupedButtons(boolean b) {
        sp.edit().putBoolean("pulsantiRaggruppati", b).apply();
    }

    /** 0.8.2: LKA and OPD buttons in the bar (if the car has them). */
    public boolean showAssist() {
        return sp.getBoolean("mostraAssistenza", true);
    }

    public void setShowAssist(boolean b) {
        sp.edit().putBoolean("mostraAssistenza", b).apply();
    }

    /** Seconds after power-on to press Home automatically: 0 = off, 5/10/15/20/30. */
    public static final int[] AUTO_HOME_SECONDS = {0, 5, 10, 15, 20, 30};

    public int autoHomeSeconds() {
        return autoHomeSeconds(sp);
    }

    public static int autoHomeSeconds(SharedPreferences sp) {
        int v = sp.getInt("premiHomeSecondi", 0);
        for (int s : AUTO_HOME_SECONDS) if (s == v) return v;
        return 0;
    }

    public void setAutoHomeSeconds(int s) {
        sp.edit().putInt("premiHomeSecondi", s).apply();
    }

    /** Last version for which the "Novità" popup was shown. */
    public String newsShownFor() {
        return sp.getString("novitaVersione", "");
    }

    public void setNewsShownFor(String v) {
        sp.edit().putString("novitaVersione", v).apply();
    }

    // --- 0.4.0 ---

    /** Distance unit: "auto" (as the cluster), "km", "mi". */
    public static final String DIST_AUTO = "auto", DIST_KM = "km", DIST_MI = "mi";

    public String distUnit() {
        String v = sp.getString("unitaDistanza", DIST_AUTO);
        return DIST_KM.equals(v) || DIST_MI.equals(v) ? v : DIST_AUTO;
    }

    public void setDistUnit(String v) {
        sp.edit().putString("unitaDistanza", v).apply();
    }

    /** 360 key of the bar: "" default, "orig", "mg4", "app:<package>", "nascosto". */
    public static final String CAM_ORIGINAL = "orig", CAM_MG4 = "mg4", CAM_APP = "app:", CAM_HIDDEN = "nascosto";

    public String cam360() {
        return sp.getString("tasto360", "");
    }

    public void setCam360(String v) {
        sp.edit().putString("tasto360", v == null ? "" : v).apply();
    }

    // --- 0.4.2 ---

    /** Three-way choices: "auto" (as the car), "show" (always show), "hide" (always hide). */
    public static final String SHOW_AUTO = "auto", SHOW_ON = "show", SHOW_OFF = "hide";

    // --- 0.4.3 ---

    /**
     * 1.0.4 (issue #28): audio sources to show. The HIDDEN ones are saved (so new sources appear by
     * themselves); default: none hidden. CarPlay and Android Auto cannot be hidden.
     */
    public boolean sourceShown(int src) {
        // 0.4.4 (SWI69): the second USB port follows the "USB" choice
        if (src == com.mg4.launcher.svc.MediaClient.SRC_USB2) src = com.mg4.launcher.svc.MediaClient.SRC_USB;
        return !("," + sp.getString("sorgentiNascoste", "") + ",").contains("," + src + ",");
    }

    public void setSourceShown(int src, boolean shown) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String x : sp.getString("sorgentiNascoste", "").split(",")) if (!x.trim().isEmpty()) set.add(x.trim());
        if (shown) set.remove(String.valueOf(src));
        else set.add(String.valueOf(src));
        sp.edit().putString("sorgentiNascoste", android.text.TextUtils.join(",", set)).apply();
    }

    /** 1.0.4 (point F): charging animation (default: on). */
    public boolean chargeAnim() {
        return sp.getBoolean("animazioneRicarica", true);
    }

    public void setChargeAnim(boolean on) {
        sp.edit().putBoolean("animazioneRicarica", on).apply();
    }

    /** Order at the top of the right panel: false = sources then Map/Media (current, default), true = swapped. */

    /** Bottom panel next to the climate: "media" (as before, default), "telefono" (favourites), "app" (shortcuts). */
    public static final String TILE_MEDIA = "media", TILE_PHONE = "telefono", TILE_APPS = "app";
    public static final int TILE_MAX = 6;

    public String tileMode() {
        String v = sp.getString("riquadroBasso", TILE_MEDIA);
        return TILE_PHONE.equals(v) || TILE_APPS.equals(v) ? v : TILE_MEDIA;
    }

    public void setTileMode(String v) {
        sp.edit().putString("riquadroBasso", v).apply();
    }

    /** Phone favourites chosen by the user (name, number), at most 6. They stay only on the car, never in the logs. */
    public java.util.List<String[]> tileContacts() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String line : sp.getString("riquadroContatti", "").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) continue;
            out.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
            if (out.size() == TILE_MAX) break;
        }
        return out;
    }

    public void setTileContacts(java.util.List<String[]> list) {
        StringBuilder sb = new StringBuilder();
        for (String[] c : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(c[0].replace('\t', ' ').replace('\n', ' ')).append('\t').append(c[1]);
        }
        sp.edit().putString("riquadroContatti", sb.toString()).apply();
    }

    /** App shortcuts of the panel (packages), at most 6. */
    public java.util.List<String> tileApps() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : sp.getString("riquadroApp", "").split(",")) if (!p.trim().isEmpty() && out.size() < TILE_MAX) out.add(p.trim());
        return out;
    }

    public void setTileApps(java.util.List<String> pkgs) {
        sp.edit().putString("riquadroApp", android.text.TextUtils.join(",", pkgs)).apply();
    }

    private String tri(String key) {
        String v = sp.getString(key, SHOW_AUTO);
        return SHOW_ON.equals(v) || SHOW_OFF.equals(v) ? v : SHOW_AUTO;
    }

    /** Point 7d: charge limit (Automatic = only if the car supports the command). */
    public String chargeLimitMode() {
        return tri("limiteRicaricaModo");
    }

    public void setChargeLimitMode(String v) {
        sp.edit().putString("limiteRicaricaModo", v).apply();
    }

    /** Point 9: seat and wheel heating (Automatic = according to the car configuration). */
    public String heatMode() {
        return tri("riscaldamentoModo");
    }

    public void setHeatMode(String v) {
        sp.edit().putString("riscaldamentoModo", v).apply();
    }

    /** Point 6: after reversing, return to the previous app (CarPlay, Android Auto, navigator…). */
    public boolean retroReturn() {
        return sp.getBoolean("retroRitorno", true);
    }

    public void setRetroReturn(boolean b) {
        sp.edit().putBoolean("retroRitorno", b).apply();
    }

    public static boolean retroReturn(SharedPreferences sp) {
        return sp.getBoolean("retroRitorno", true);
    }

    /** Point 8b: top-right button: "proj" (CarPlay / Android Auto), "app:<package>", "none". */
    /**
     * Button next to Map/Media (from SWI68 1.0.4): none or an app. CarPlay/Android Auto are in the bottom
     * bar. 0.4.4: the "an app" choice of the old top-right button of 0.4.2–0.4.3 is carried over.
     */
    public static final String TOP_PROJECTION = "proiezione", TOP_APP = "app:", TOP_NONE = "nessuno";

    public String topButton() {
        String v = sp.getString("pulsanteAltoDestra", null);
        if (v == null) {
            String old = sp.getString("pulsanteRapido", "");
            v = old.startsWith(TOP_APP) ? old : TOP_PROJECTION;
        }
        return TOP_NONE.equals(v) || v.startsWith(TOP_APP) ? v : TOP_PROJECTION;
    }

    public void setTopButton(String v) {
        sp.edit().putString("pulsanteAltoDestra", v == null ? TOP_PROJECTION : v).apply();
    }

    public SharedPreferences raw() {
        return sp;
    }
}
