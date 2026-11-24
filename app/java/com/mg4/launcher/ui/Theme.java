package com.mg4.launcher.ui;

import android.content.Context;

/** Launcher colors (same values as the approved mockup). */
public final class Theme {
    public final boolean day;
    public final int bg, panel, text, sub, track, chip, battery, accent, onAccent, overlay, map, road, roadMinor, glow, shadow;
    /**
     * Fork: warning colors per theme. The old ones (amber FFB020, green 34C77B, orange FF7A3D) were picked for a dark
     * background and by day had 1.6–2.3:1 contrast (rear fog light on looked worse than off).
     * Day: warn 4.5:1 and fogFront 4.5:1 on the chip; heat = MG4 Clima day orange (clima_caldo), 3.2:1 (icons).
     * Night: the usual values.
     */
    public final int warn, warnBg, heat, fogFront;

    private Theme(boolean day) {
        this.day = day;
        if (day) {
            bg = 0xFFE6E8EB; panel = 0xFFFFFFFF; text = 0xFF111318; sub = 0xFF555C66; track = 0xFFE1E4E8;
            chip = 0xFFF1F3F5; battery = 0xFF1E9E5A; overlay = 0xEBFFFFFF; map = 0xFFEEF0F2; road = 0xFFFFFFFF;
            roadMinor = 0xFFE2E5E9; glow = 0xFFC9D3E0; shadow = 0x1A000000;
            warn = 0xFFB45309; warnBg = 0xFFFFF4DC; heat = 0xFFE1621F; fogFront = 0xFF15803D;
        } else {
            bg = 0xFF0A0B0D; panel = 0xFF15171B; text = 0xFFF2F3F5; sub = 0xFF9BA1A9; track = 0xFF262A30;
            chip = 0xFF1E2126; battery = 0xFF34C77B; overlay = 0xEB15171B; map = 0xFF1A1D22; road = 0xFF2A2E35;
            roadMinor = 0xFF22252B; glow = 0xFF1F2A3A; shadow = 0x8C000000;
            warn = 0xFFFFB020; warnBg = 0xFF2E2A1F; heat = 0xFFFF7A3D; fogFront = 0xFF34C77B;
        }
        // no accent color: buttons and icons use the same color as the text
        accent = text;
        onAccent = luminance(accent) > 0.6 ? 0xFF111318 : 0xFFFFFFFF;
    }

    /**
     * 0.4.2 (point 1): manual Day/Night choice, otherwise the automatic theme from {@link com.mg4.launcher.svc.DayNight}
     * (uiMode if it really changes, the car's NIGHT_MODE, headlights, sunrise/sunset; switch after 60 s stable).
     */
    public static Theme resolve(Context c, Prefs p) {
        return new Theme(wouldBeDay(c, p));
    }

    /** Theme that would apply right now (to re-check when returning to the launcher). */
    public static boolean wouldBeDay(Context c, Prefs p) {
        String mode = p.themeMode();
        if (Prefs.THEME_DAY.equals(mode)) return true;
        if (Prefs.THEME_NIGHT.equals(mode)) return false;
        return com.mg4.launcher.svc.DayNight.get(c).isDay();
    }

    private static double luminance(int c) {
        return (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF)) / 255.0;
    }
}
