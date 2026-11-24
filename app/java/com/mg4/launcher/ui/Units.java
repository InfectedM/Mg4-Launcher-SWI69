package com.mg4.launcher.ui;

import com.mg4.launcher.L;
import com.mg4.launcher.svc.Diag;

/**
 * 1.0.0: distance units. The car always gives the range in km (charging service) and the navigator gives distances in
 * meters; if the instrument cluster is in miles they are converted here, instead of just changing the label.
 * Unit source in "Automatic": the cluster (charging service, 0 km / 1 miles), otherwise the navigator
 * (1 km, 2 miles).
 */
public final class Units {
    public static final double KM_TO_MI = 0.621371;
    /** MG navigator unit: 1 km, 2 miles, -1 not read. Written by NavClient. */
    public static volatile int navUnit = -1;
    private static String lastDiag = "";

    private Units() {}

    /** True if distances should be shown in miles (user's choice, or same as the cluster). */
    public static boolean miles(Prefs p, int cluster) {
        String pref = p.distUnit();
        boolean mi;
        String source;
        if (Prefs.DIST_KM.equals(pref)) {
            mi = false;
            source = "chosen km";
        } else if (Prefs.DIST_MI.equals(pref)) {
            mi = true;
            source = "chosen miles";
        } else if (cluster >= 0) {
            mi = cluster == 1;
            source = "cluster " + cluster;
        } else if (navUnit > 0) {
            mi = navUnit == 2;
            source = "navigator " + navUnit;
        } else {
            mi = false;
            source = "no source (km)";
        }
        String d = source + " · cluster " + cluster + " · navigator " + navUnit + " · shown " + (mi ? "mi" : "km");
        if (!d.equals(lastDiag)) {
            lastDiag = d;
            Diag.put("vehicle.distanceUnit", d);
        }
        return mi;
    }

    /** Range (km from the car) in the displayed unit, rounded. */
    public static int range(int km, boolean mi) {
        return mi ? (int) Math.round(km * KM_TO_MI) : km;
    }

    /** Navigator distance: "350 m" / "1,2 km" / "0,3 mi" / "12 mi". */
    public static String distance(int meters, boolean mi) {
        if (!mi) {
            if (meters < 1000) return meters + " m";
            return String.format(L.locale(), meters < 10000 ? "%.1f km" : "%.0f km", meters / 1000f);
        }
        double m = meters / 1000.0 * KM_TO_MI;
        if (m < 0.1) return Math.round(meters * 3.28084 / 10) * 10 + " ft";
        return String.format(L.locale(), m < 10 ? "%.1f mi" : "%.0f mi", m);
    }
}
