package com.mg4.launcher.climate;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.mg4.launcher.R;

/**
 * Climate during Android Auto and CarPlay (option, off by default). During projection the MG SystemUI does not open
 * the climate from the top bar. With the option on, while Android Auto (com.allgo.app.androidauto) or CarPlay
 * (com.allgo.carplay.service) is in front, a small round floating button opens the climate screen. It can be dragged
 * anywhere (the position is remembered), so it does not cover buttons of Waze or Maps, and disappears as soon as
 * the projection is no longer in front. System window above the bar: 2017, then 2032, then 2010 (see {@link #show}).
 */
final class ProjectionZone {
    static final String[] PROJECTION = {"com.allgo.app.androidauto", "com.allgo.carplay.service"};
    private static final int TYPE_SUB_PANEL = 2017;   // WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL (hidden)
    private static final int SIZE = 84, MARGIN = 16, DRAG_SLOP = 14;

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
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(SIZE, SIZE, t,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            int[] pos = position(g);
            lp.x = pos[0];
            lp.y = pos[1];
            lp.setTitle("MG4 climate projection button");
            View v = button(g, wm, lp);
            try {
                wm.addView(v, lp);
                zone = v;
                type = typeName(t);
                ClimateStore.event(g, "climate.projection: button shown over " + lastPkg + " · window " + type
                        + " · at " + lp.x + "," + lp.y + (refused.length() > 0 ? " (refused: " + refused + ")" : ""));
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

    private static int[] screen(Context c) {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    /** Saved position kept inside the screen; default: driver side, a little below the middle. */
    private static int[] position(ClimateGuard g) {
        int[] s = screen(g), p = ClimateStore.projectionButton(g);
        int x = p[0], y = p[1];
        if (x < 0 || y < 0) {
            x = ClimateStore.rightHand(g) ? s[0] - SIZE - MARGIN : MARGIN;
            y = s[1] / 2 + SIZE / 2;
        }
        return new int[]{clamp(x, 0, s[0] - SIZE), clamp(y, 0, s[1] - SIZE)};
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Round button with the fan icon: a tap opens the climate, a drag moves it (position saved on release). */
    private static View button(ClimateGuard g, WindowManager wm, WindowManager.LayoutParams lp) {
        ImageView v = new ImageView(g);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xE61C1F24);
        bg.setStroke(2, 0x66FFFFFF);
        v.setBackground(bg);
        v.setImageResource(R.drawable.climate_fan);
        v.setColorFilter(0xFFFFFFFF);
        int pad = SIZE / 4;
        v.setPadding(pad, pad, pad, pad);
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        dragging = false;
                        view.setAlpha(.7f);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > DRAG_SLOP) dragging = true;
                        if (dragging) {
                            int[] s = screen(g);
                            lp.x = clamp(startX + Math.round(dx), 0, s[0] - SIZE);
                            lp.y = clamp(startY + Math.round(dy), 0, s[1] - SIZE);
                            try {
                                wm.updateViewLayout(view, lp);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        view.setAlpha(1f);
                        if (dragging) {
                            ClimateStore.setProjectionButton(g, lp.x, lp.y);
                            ClimateStore.event(g, "climate.projection: button moved to " + lp.x + "," + lp.y);
                        } else open(g);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.setAlpha(1f);
                        return true;
                }
                return false;
            }
        });
        return v;
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
        ClimateStore.event(g, "climate.projection: button removed (" + why + ")");
    }

    private static void open(ClimateGuard g) {
        opens++;
        g.openFromBar("floating button");   // start, check after 1 s, panel if AllGo takes the screen back
    }

    /** climate.projection line: button shown yes/no, window type, openings. */
    static String stateText(Context c) {
        return "option " + (ClimateStore.projectionZone(c) ? "on" : "off") + " · button shown " + (zone != null ? "yes" : "no")
                + " · window " + type + " · openings " + opens + " · in front " + lastPkg;
    }
}
