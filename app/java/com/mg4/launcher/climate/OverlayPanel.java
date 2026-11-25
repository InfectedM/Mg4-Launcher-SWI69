package com.mg4.launcher.climate;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Toast;

import com.mg4.launcher.L;
import com.mg4.launcher.R;

/**
 * The climate screen as an overlay panel, only with Android Auto or CarPlay in front, when the real screen does not
 * stay in front after starting (AllGo takes the screen back). Same view and same commands as the screen
 * ({@link ClimateView}, {@link Commands}), in a window of the accessibility service: 2017 (STATUS_BAR_SUB_PANEL) and,
 * if refused, 2032 (ACCESSIBILITY_OVERLAY). It closes with ✕, by tapping outside, with the automatic closing or when
 * the projection is no longer in front; Android Auto/CarPlay stays below, so the return is already there.
 */
final class OverlayPanel {
    private static final int TYPE_SUB_PANEL = 2017;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static ClimateView view;
    private static HvacClient client;
    private static String type = "-";
    private static int shown;
    private static final Runnable autoClose = () -> hide("automatic closing after " + ClimateStore.autoClose(ctx()) + " s without touches");
    private static ClimateGuard guard;

    private OverlayPanel() {
    }

    private static Context ctx() {
        return guard;
    }

    static boolean showing() {
        return view != null;
    }

    /** Shows the panel over the projection. Main thread. true = shown. */
    static boolean show(ClimateGuard g, String under) {
        if (view != null) return true;
        guard = g;
        WindowManager wm = (WindowManager) g.getSystemService(Context.WINDOW_SERVICE);
        StringBuilder refused = new StringBuilder();
        for (int t : new int[]{TYPE_SUB_PANEL, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY}) {
            ClimateView v = build(g);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, t,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("MG4 climate panel");
            try {
                wm.addView(v, lp);
                view = v;
                type = t == TYPE_SUB_PANEL ? "STATUS_BAR_SUB_PANEL (2017)" : "ACCESSIBILITY_OVERLAY (2032)";
                shown++;
                startClient(g, v);
                armAutoClose();
                ClimateStore.event(g, "climate.panel: shown over " + under + " · window " + type
                        + (refused.length() > 0 ? " (refused: " + refused + ")" : ""));
                return true;
            } catch (Throwable e) {
                refused.append(refused.length() > 0 ? ", " : "").append(t);
                ClimateStore.event(g, "climate.panel: window " + t + " refused (" + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "") + ")");
                try {
                    wm.removeViewImmediate(v);
                } catch (Throwable ignored) {
                }
            }
        }
        type = "none (refused " + refused + ")";
        return false;
    }

    private static ClimateView build(ClimateGuard g) {
        ClimateView v = new ClimateView(g);
        v.setRightHand(ClimateStore.rightHand(g));
        v.setAnimated(ClimateStore.animatedAir(g));
        v.setDashStyle(ClimateStore.dashStyle(g));
        v.setDefrostTiles(ClimateStore.defrostTiles(g));
        v.setShowLabels(ClimateStore.showLabels(g));
        v.theme();
        v.setBackgroundColor(v.BG);
        HvacClient.State s = ClimateGuard.lastState;
        if (s != null) v.setState(s);
        v.setOnTouchListener((x, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) armAutoClose();
            return false;
        });
        return v;
    }

    private static void startClient(ClimateGuard g, ClimateView v) {
        client = new HvacClient(g);
        Commands cmd = new Commands(g, client, s -> Toast.makeText(g, s, Toast.LENGTH_SHORT).show(), null);
        v.setListener(id -> {
            if ("close".equals(id)) hide("close button");
            else if ("outside".equals(id)) hide("tap outside the panel");
            else if ("settings".equals(id)) Toast.makeText(g, L.s(R.string.cl_p_settings_later), Toast.LENGTH_SHORT).show();
            else cmd.command(id);
        });
        client.start(s -> {
            ClimateGuard.lastState = s;
            cmd.onState(s);
            if (view != null) view.setState(s);
        });
    }

    private static void armAutoClose() {
        main.removeCallbacks(autoClose);
        Context c = ctx();
        int sec = c == null ? 0 : ClimateStore.autoClose(c);
        if (sec > 0 && view != null) main.postDelayed(autoClose, sec * 1000L);
    }

    /** Removes the panel (main thread). */
    static void hide(String why) {
        ClimateView v = view;
        view = null;
        main.removeCallbacks(autoClose);
        if (v == null) return;
        HvacClient c = client;
        client = null;
        if (c != null) c.stop();
        ClimateGuard g = guard;
        try {
            ((WindowManager) g.getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (Throwable ignored) {
        }
        ClimateStore.event(g, "climate.panel: removed (" + why + ")");
    }

    /** From the service on every change of the app in front: without the projection in front the panel is not needed. */
    static void onForeground(String pkg) {
        if (view != null && pkg != null && !ProjectionZone.isProjection(pkg)) hide("in front " + pkg);
    }

    static String stateText() {
        return (view != null ? "shown" : "not shown") + " · window " + type + " · times " + shown;
    }
}
