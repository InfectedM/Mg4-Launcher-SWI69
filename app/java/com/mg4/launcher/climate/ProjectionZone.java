package com.mg4.launcher.climate;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Climate during Android Auto and CarPlay (option, off by default). During projection the MG SystemUI does not open
 * the climate from the top bar. With the option on, while Android Auto (com.allgo.app.androidauto) or CarPlay
 * (com.allgo.carplay.service) is in front, an invisible zone in the middle of the top bar opens the climate screen.
 * The zone is as wide as the middle block of the bar, as tall as the bar, draws nothing and disappears as soon as the
 * projection is no longer in front. System window above the bar: 2017, then 2032, then 2010 (see {@link #show}).
 */
final class ProjectionZone {
    static final String[] PROJECTION = {"com.allgo.app.androidauto", "com.allgo.carplay.service"};
    private static final int TYPE_SUB_PANEL = 2017;   // WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL (hidden)
    private static final int ZONE_W = 360;

    private static View zone;
    private static String type = "-", lastPkg = "-";
    private static int opens;
    private static ClimateGuard service;

    private ProjectionZone() {
    }

    static void attach(ClimateGuard g) {
        service = g;
    }

    static boolean isProjection(String pkg) {
        for (String p : PROJECTION) if (p.equals(pkg)) return true;
        return false;
    }

    /** From the service on every change of the app in front (main thread). */
    static void onForeground(ClimateGuard g, String pkg) {
        service = g;
        if (pkg == null) return;
        lastPkg = pkg;
        boolean want = ClimateStore.projectionZone(g) && isProjection(pkg);
        if (want && zone == null) show(g);
        else if (!want && zone != null) hide(g, "in front " + pkg);
    }

    /** Option changed from the settings. */
    static void refresh(Context c) {
        ClimateGuard g = service;
        if (g == null) return;
        if (!ClimateStore.projectionZone(c)) hide(g, "option off");
        else if (isProjection(lastPkg) && zone == null) show(g);
    }

    private static int barHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = id > 0 ? c.getResources().getDimensionPixelSize(id) : 0;
        return h >= 30 && h <= 160 ? h : 64;
    }

    /**
     * Windows in order 2017 (STATUS_BAR_SUB_PANEL, with STATUS_BAR_SERVICE) → 2032 (ACCESSIBILITY_OVERLAY, from the
     * accessibility service) → 2010 (SYSTEM_ERROR, with INTERNAL_SYSTEM_WINDOW). A new View on every attempt: a View
     * refused with 2017 would stay "already added" and 2032 would throw IllegalStateException.
     */
    private static final int TYPE_SYSTEM_ERROR = 2010;

    private static void show(ClimateGuard g) {
        // WindowManager of the accessibility service: it has the token needed for 2032
        WindowManager wm = (WindowManager) g.getSystemService(Context.WINDOW_SERVICE);
        StringBuilder refused = new StringBuilder();
        for (int t : new int[]{TYPE_SUB_PANEL, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, TYPE_SYSTEM_ERROR}) {
            View v = new View(g);
            v.setBackgroundColor(0);   // invisible
            v.setOnClickListener(x -> open(g));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ZONE_W, barHeight(g), t,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.setTitle("MG4 climate projection zone");
            try {
                wm.addView(v, lp);
                zone = v;
                type = typeName(t);
                ClimateStore.event(g, "climate.projection: zone active over " + lastPkg + " · window " + type
                        + " · " + ZONE_W + "×" + lp.height + " px" + (refused.length() > 0 ? " (refused: " + refused + ")" : ""));
                return;
            } catch (Throwable e) {
                refused.append(refused.length() > 0 ? ", " : "").append(t);
                ClimateStore.event(g, "climate.projection: window " + typeName(t) + " refused (" + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "") + ")");
                try {
                    wm.removeViewImmediate(v);   // in case it was left half-added
                } catch (Throwable ignored) {
                }
            }
        }
        type = "none (refused " + refused + ")";
    }

    private static String typeName(int t) {
        return t == TYPE_SUB_PANEL ? "STATUS_BAR_SUB_PANEL (2017)"
                : t == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY ? "ACCESSIBILITY_OVERLAY (2032)"
                : t == TYPE_SYSTEM_ERROR ? "SYSTEM_ERROR (2010)" : String.valueOf(t);
    }

    static void hide(ClimateGuard g, String why) {
        View v = zone;
        zone = null;
        if (v == null) return;
        try {
            ((WindowManager) g.getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (Throwable ignored) {
        }
        ClimateStore.event(g, "climate.projection: zone removed (" + why + ")");
    }

    private static void open(ClimateGuard g) {
        opens++;
        g.openFromBar("zone over the bar");   // start, check after 1 s, panel if AllGo takes the screen back
    }

    /** climate.projection line: zone active yes/no, window type, openings. */
    static String stateText(Context c) {
        return "option " + (ClimateStore.projectionZone(c) ? "on" : "off") + " · zone active " + (zone != null ? "yes" : "no")
                + " · window " + type + " · openings " + opens + " · in front " + lastPkg;
    }
}
