package com.mg4.launcher.ui;   // change the package to the launcher's one

import com.mg4.launcher.R;

/**
 * MG4 Clima 1.4.0 · climate state → icon (same values read by MG4 Clima and by the launcher, SAIC and caradapter).
 * The icons are in res/drawable (white): the color comes from res/values(-night)/clima_colori.xml.
 */
public final class ClimateIcon {
    private ClimateIcon() {
    }

    /** Recirculation: 0 inside, 1 outside air, 2 automatic. Inside and automatic = on. */
    public static int recircIcon(int loop) {
        switch (loop) {
            case 0: return R.drawable.climate_recirc_inside;
            case 2: return R.drawable.climate_recirc_auto;
            default: return R.drawable.climate_air_fresh;
        }
    }

    public static boolean recircOnIcon(int loop) {
        return loop == 0 || loop == 2;
    }

    /** Air direction 0..6 → which buttons are lit: {windscreen, face, feet}. */
    public static boolean[] directions(int blowerMode) {
        switch (blowerMode) {
            case 0: return new boolean[]{false, true, false};
            case 1: return new boolean[]{false, true, true};
            case 2: return new boolean[]{false, false, true};
            case 3: return new boolean[]{true, false, true};
            case 4: return new boolean[]{true, false, false};
            case 5: return new boolean[]{true, true, true};
            case 6: return new boolean[]{true, true, false};
            default: return new boolean[]{false, false, false};
        }
    }

    /** Button state: 0 off, 1 on, 2 unavailable (climate not usable or value not provided, -1). */
    public static int state(boolean usable, int value, int onValue) {
        if (!usable || value < 0) return 2;
        return value == onValue ? 1 : 0;
    }
}
