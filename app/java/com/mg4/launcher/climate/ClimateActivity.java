package com.mg4.launcher.climate;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.ui.Prefs;

/**
 * Built-in climate screen (from MG4 Clima): shows the real climate state and the buttons control the car like the
 * original app. It opens from the launcher's climate button and, through {@link ClimateGuard}, instead of the original
 * climate app. Its settings (gear) cover the screen, how the climate opens, the original app, the check of a new
 * climate app version, the automatic test and the diagnostics.
 */
public final class ClimateActivity extends Activity {
    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    private static final long BYPASS_MS = 2 * 60_000L;
    private static final String SETTINGS = "settings", DIAG = "diagnostics", KEYS = "keys";
    /** Moment (wall time, ms) the replaced original climate app appeared (to measure the flash). */
    static final String EXTRA_ORIGINAL_AT = "com.mg4.launcher.climate.ORIGINAL_AT";
    private long originalAt;

    private ClimateView view;
    private TextView banner;
    private boolean testRunning;
    private HvacClient client;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);   // no animation: it covers the original app at once
        L.init(this, new Prefs(this).language());
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
        view = new ClimateView(this);
        view.setRightHand(ClimateStore.rightHand(this));
        view.setAnimated(ClimateStore.animatedAir(this));
        view.setDashStyle(ClimateStore.dashStyle(this));
        view.setDefrostTiles(ClimateStore.defrostTiles(this));
        view.setShowLabels(ClimateStore.showLabels(this));
        view.setListener(this::onTap);
        root = new FrameLayout(this);
        root.addView(view);
        banner = new TextView(this);
        banner.setTextSize(22);
        bannerColors();
        banner.setPadding(40, 18, 40, 18);
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        swipe = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(android.view.MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                return onSwipe(e1, e2, vy);
            }
        });
        client = new HvacClient(this);
        cmd = new Commands(this, client, this::toast, this::openOriginal);
        joy = new Joystick(this, new Joystick.Listener() {
            @Override
            public void onKey(int code, boolean down, boolean lp, boolean consumed) {
                h.post(() -> {
                    armAutoClose();   // every joystick key restarts the countdown
                    onWheelKey(code, down, lp, consumed);
                });
            }

            @Override
            public void onSystemKey(int sysCode, boolean down) {
                h.post(() -> onSysKey(sysCode, down));
            }
        });
        joy.onDecided = () -> {   // exclusive keys not possible → the commands stay (through the broadcast), with a notice
            if (ClimateStore.joystick(this) != ClimateStore.JOY_OFF) toastLong(L.s(R.string.cl_a_joy_shared));
        };
        ClimateGuard.ensureEnabled(this);
        if (ClimateGuard.originalInstalled(this) && !ClimateGuard.originalKnown(this)) {
            toastLong(L.f(R.string.cl_a_original_unverified, ClimateGuard.originalVersion(this)));
        }
        ClimateStore.event(this, "screen opened");
        fromGuard(getIntent());
        opened(getIntent());
    }

    /** Where it was opened from (floating button over the projection, or the app that was in front): it goes back there on closing. */
    private void opened(Intent i) {
        String z = i == null ? null : i.getStringExtra(ReturnTo.EXTRA_FROM);
        if (z != null) ReturnTo.onOpen(z, "floating button");
        else ReturnTo.onOpen(ClimateGuard.lastOther, originalAt > 0 ? "instead of the original climate" : "opening");
    }

    // ---- automatic closing after N seconds without touches or joystick keys -------------------------------------------
    private final Runnable autoCloseR = () -> {
        if (!resumed) return;
        if (testRunning) {   // it does not close during the test or the check
            armAutoClose();
            return;
        }
        close("automatic closing after " + ClimateStore.autoClose(this) + " s without touches");
    };

    private void armAutoClose() {
        h.removeCallbacks(autoCloseR);
        int sec = ClimateStore.autoClose(this);
        if (sec > 0 && resumed) h.postDelayed(autoCloseR, sec * 1000L);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        overridePendingTransition(0, 0);
        fromGuard(i);
        opened(i);
    }

    /** Opened instead of the original climate app: remember when the original appeared. */
    private void fromGuard(Intent i) {
        long at = i == null ? 0 : i.getLongExtra(EXTRA_ORIGINAL_AT, 0);
        if (at > 0) originalAt = at;
    }

    /** Ours is really on the screen: the log says how long the original was visible. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // position and size of the screen (for the ready window) and away with the ready window
            int[] loc = new int[2];
            View d = getWindow().getDecorView();
            d.getLocationOnScreen(loc);
            ClimateStore.setScreenRect(this, loc[0], loc[1], d.getWidth(), d.getHeight());
            ClimateGuard.screenReady();
        }
        if (!hasFocus || originalAt <= 0) return;
        long now = System.currentTimeMillis();
        ClimateStore.event(this, "climate screen open at " + ClimateStore.ms(now) + " · original shown at " + ClimateStore.ms(originalAt)
                + " (+" + Math.max(0, now - originalAt) + " ms)");
        originalAt = 0;
    }

    @Override
    protected void onStart() {
        super.onStart();
        client.start(this::onState);
        h.post(clock);
    }

    @Override
    protected void onStop() {
        resumed = false;   // also in case onPause was not enough
        client.stop();
        h.removeCallbacks(clock);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.postDelayed(this::maybeAskVerify, 2500);
        if (L.init(this, new Prefs(this).language())) view.invalidate();   // language changed
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
        if (ClimateStore.closeShade(this)) shadeBlock(true);
        edges();
        view.setRightHand(ClimateStore.rightHand(this));   // the driving side can change in the launcher settings
        applyTheme();
        resumed = true;
        open(true, "onResume");
        armAutoClose();
        filterCodes.clear();
        joy.register();   // steering wheel keys only with the screen open and in front
        // exclusive keys (accessibility filter): the filter stays on in the service; the screen in front gets
        // (and has consumed) the keys
        ClimateGuard.keyListener(this::onFilterKey);
        ClimateGuard.refreshFilter(this);   // checks that the filter flag is really on
    }

    /**
     * The screen is in front (the key filter consumes only in this case): true only between onResume and
     * onPause/onStop; never saved, so it cannot stay on. "Climate screen open" also needs the accessibility service to
     * confirm it ({@link ClimateGuard#climateFront()}).
     */
    static volatile boolean resumed;
    /** Reason of the next closing (automatic closing, return…), for the climate.open line. */
    private static volatile String closing;
    private static volatile String openState = "no (never opened since the car was switched on)";

    /** climate.open line = yes/no with the reason (logged on every change). */
    private void open(boolean on, String why) {
        String c = closing;
        String line = (on ? "yes" : "no") + " (" + why + (!on && c != null ? " · " + c : "") + ")";
        if (!on) closing = null;
        if (line.equals(openState)) return;
        openState = line;
        ClimateStore.event(this, "climate.open = " + line);
    }

    /** The climate screen is in front (for the launcher: the same package, but not the home). */
    public static boolean inFront() {
        return resumed;
    }

    static String openText() {
        return openState;
    }

    /** Strips on the edges for the edge swipe, only while the climate screen is in front. */
    private void edges() {
        int m = ClimateStore.swipeMode(this);
        ClimateGuard.edges(this, m == ClimateStore.SWIPE_OFF ? 0 : m == ClimateStore.SWIPE_UP ? 1 : m == ClimateStore.SWIPE_DOWN ? 2 : 3);
        // if the accessibility service has not connected after a few seconds, it is said once
        if (m != ClimateStore.SWIPE_OFF && !a11yWarned) h.postDelayed(() -> {
            if (a11yWarned || ClimateGuard.connected() || isFinishing()) return;
            a11yWarned = true;
            ClimateStore.event(this, "notice: accessibility service not connected (shown to the user)");
            toastLong(L.s(R.string.cl_a_a11y_off));
        }, 6000);
    }

    private static boolean a11yWarned;

    /** The time on the stylised instrument cluster, and the day/night theme as the launcher decides it. */
    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            if (view.isDayTheme() != (ClimateStore.dashStyle(ClimateActivity.this) != ClimateStore.STYLE_PHOTO
                    && com.mg4.launcher.ui.Theme.wouldBeDay(ClimateActivity.this, new Prefs(ClimateActivity.this)))) applyTheme();
            view.invalidate();
            h.postDelayed(this, 20_000);
        }
    };

    private void onTap(String id) {
        if ("close".equals(id)) {
            close("close button");
            return;
        }
        if ("outside".equals(id)) {
            close("tap outside the panel");
            return;
        }
        if (testRunning) {
            toast(L.s(R.string.cl_a_test_running));
            return;
        }
        if ("settings".equals(id)) {
            settings();
            return;
        }
        command(id);
    }

    // ---------------------------------------------------------------------------------- commands

    private HvacClient.State last = new HvacClient.State();
    /** Command logic in {@link Commands}, the same as the overlay panel (Android Auto/CarPlay). */
    private Commands cmd;

    private void onState(HvacClient.State s) {
        last = s;
        ClimateGuard.lastState = s;   // for the ready window
        cmd.onState(s);
        view.setState(s);
    }

    private void command(String id) {
        cmd.command(id);
    }

    private void send(String what, int code, Integer arg) {
        cmd.send(what, code, arg);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private long lastOriginalAt;

    /** Original climate app (opened on purpose: the replacement pauses for 2 minutes). */
    private void openOriginal() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOriginalAt < 3000) return;   // no bouncing between the climate screen and the original
        lastOriginalAt = now;
        ClimateStore.bypass(this, BYPASS_MS);
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(ClimateGuard.HVAC_PKG, ClimateGuard.HVAC_MAIN))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        } catch (Throwable e) {
            toast(L.s(R.string.cl_a_original_missing));
        }
    }

    // ---------------------------------------------------------------------------------- settings

    private Sheet sheet;
    private FrameLayout root;
    private String currentSheet = "";

    private boolean settingsShowing() {
        return sheet != null && sheet.showing() && SETTINGS.equals(currentSheet);
    }

    private Sheet sheet() {
        if (sheet == null) sheet = new Sheet(this, root, view);
        return sheet;
    }

    private void settings() {
        Sheet s = sheet().open(L.s(R.string.cl_s_title), null);
        currentSheet = SETTINGS;

        // ---- screen
        s.label(L.s(R.string.cl_s_screen));
        LinearLayout c0 = s.card();
        int ds = ClimateStore.dashStyle(this);
        s.row(c0, L.s(R.string.cl_s_dash), L.s(R.string.cl_s_dash_sub),
                s.choices(new String[]{L.s(R.string.cl_s_dash_drawn), L.s(R.string.cl_s_dash_photo)}, ds == ClimateStore.STYLE_PHOTO ? 1 : 0, i -> {
                    int st = i == 1 ? ClimateStore.STYLE_PHOTO : ClimateStore.STYLE_DRAWN;
                    ClimateStore.setDashStyle(this, st);
                    view.setDashStyle(st);
                    ClimateStore.event(this, "dashboard " + (i == 1 ? "realistic" : "drawn"));
                    applyTheme();
                    settings();
                }), false);
        s.row(c0, L.s(R.string.cl_s_anim), L.s(R.string.cl_s_anim_sub),
                s.toggle(ClimateStore.animatedAir(this), v -> {
                    ClimateStore.setAnimatedAir(this, v);
                    view.setAnimated(v);
                    ClimateStore.event(this, "animated air " + (v ? "on" : "off"));
                }), false);
        s.row(c0, L.s(R.string.cl_s_labels), L.s(R.string.cl_s_labels_sub),
                s.toggle(ClimateStore.showLabels(this), v -> {
                    ClimateStore.setShowLabels(this, v);
                    view.setShowLabels(v);
                    ClimateStore.event(this, "button labels " + (v ? "shown" : "hidden"));
                }), false);
        s.row(c0, L.s(R.string.cl_s_defrost_tiles), L.s(R.string.cl_s_defrost_tiles_sub),
                s.toggle(ClimateStore.defrostTiles(this), v -> {
                    ClimateStore.setDefrostTiles(this, v);
                    view.setDefrostTiles(v);
                    ClimateStore.event(this, "windscreen/rear window buttons " + (v ? "shown" : "hidden"));
                }), true);

        // ---- automatic climate: the settings of the original climate app, read from and written to the car
        // (if the car does not provide them the section is not shown; each item only if there is a value)
        HvacClient.State st = last;
        boolean any = st.autoLevel >= 0 || st.econLink >= 0 || st.defrostLink >= 0;
        if (any) {
            s.label(L.s(R.string.cl_s_auto_section));
            LinearLayout ca = s.card();
            if (st.autoLevel >= 0) {
                int sel = st.autoLevel == HvacClient.AUTO_LOW ? 0 : st.autoLevel == HvacClient.AUTO_MEDIUM ? 1 : 2;
                s.row(ca, L.s(R.string.cl_s_auto_level), L.s(R.string.cl_s_auto_level_sub),
                        s.choices(new String[]{L.s(R.string.cl_v_level_low), L.s(R.string.cl_v_level_medium), L.s(R.string.cl_v_level_high)}, sel, i -> {
                            int v = i == 0 ? HvacClient.AUTO_LOW : i == 1 ? HvacClient.AUTO_MEDIUM : HvacClient.AUTO_HIGH;
                            setting("AUTO intensity", HvacClient.SET_AUTO_LEVEL, v);
                        }), st.econLink < 0 && st.defrostLink < 0);
            }
            if (st.econLink >= 0) {
                s.row(ca, L.s(R.string.cl_s_econ_link), L.s(R.string.cl_s_econ_link_sub),
                        s.toggle(st.econLink == 1, v -> setting("follow ECO", HvacClient.SET_ECON_LINK, v ? 1 : 2)), st.defrostLink < 0);
            }
            if (st.defrostLink >= 0) {
                s.row(ca, L.s(R.string.cl_s_defrost_link), L.s(R.string.cl_s_defrost_link_sub),
                        s.toggle(st.defrostLink == 0, v -> setting("automatic rear defrost", HvacClient.SET_DEFROST_LINK, v ? 0 : 1)), true);
            }
        }

        // ---- opening of the climate
        s.label(L.s(R.string.cl_s_opening));
        LinearLayout c1 = s.card();
        boolean known = ClimateGuard.originalKnown(this);
        s.row(c1, L.s(R.string.cl_s_replace),
                known ? L.s(R.string.cl_s_replace_sub) : L.f(R.string.cl_s_replace_unverified, ClimateGuard.originalVersion(this)),
                s.toggle(ClimateStore.replace(this), v -> {
                    ClimateStore.setReplace(this, v);
                    ClimateStore.event(this, "replacement " + (v ? "on" : "off"));
                }), false);
        // Off / Automatic (both directions) / Upwards / Downwards
        final int[] swModes = {ClimateStore.SWIPE_OFF, ClimateStore.SWIPE_AUTO, ClimateStore.SWIPE_UP, ClimateStore.SWIPE_DOWN};
        int swSel = 1;
        for (int i = 0; i < swModes.length; i++) if (swModes[i] == ClimateStore.swipeMode(this)) swSel = i;
        s.row(c1, L.s(R.string.cl_s_swipe), L.s(R.string.cl_s_swipe_sub),
                s.choices(new String[]{L.s(R.string.cl_s_swipe_off), L.s(R.string.cl_s_swipe_auto), L.s(R.string.cl_s_swipe_bottom),
                        L.s(R.string.cl_s_swipe_top)}, swSel, i -> {
                    ClimateStore.setSwipeMode(this, swModes[i]);
                    ClimateStore.event(this, "swipe to close: " + ClimateStore.swipeName(swModes[i]));
                    edges();
                    settings();
                }), false);
        // automatic closing without touches: Never / 10 / 20 / 30 / 60 s
        int acSel = 0;
        for (int i = 0; i < ClimateStore.AUTO_CLOSE.length; i++) if (ClimateStore.AUTO_CLOSE[i] == ClimateStore.autoClose(this)) acSel = i;
        String[] acNames = new String[ClimateStore.AUTO_CLOSE.length];
        for (int i = 0; i < acNames.length; i++) acNames[i] = i == 0 ? L.s(R.string.cl_s_never) : ClimateStore.AUTO_CLOSE[i] + " s";
        s.row(c1, L.s(R.string.cl_s_autoclose), L.s(R.string.cl_s_autoclose_sub), s.choices(acNames, acSel, i -> {
            ClimateStore.setAutoClose(this, ClimateStore.AUTO_CLOSE[i]);
            ClimateStore.event(this, "automatic closing: " + (i == 0 ? "never" : ClimateStore.AUTO_CLOSE[i] + " s without touches"));
            armAutoClose();
            settings();
        }), false);
        // commands from the steering wheel joystick (only with the screen open): off / on; explanation if not exclusive
        int js = ClimateStore.joystick(this), ex = ClimateStore.joyExclusive(this);
        s.row(c1, L.s(R.string.cl_s_joy), ex == 0 ? L.s(R.string.cl_s_joy_shared) : ex == 1 ? L.s(R.string.cl_s_joy_excl) : L.s(R.string.cl_s_joy_sub),
                s.choices(new String[]{L.s(R.string.cl_s_swipe_off), L.s(R.string.cl_s_joy_on)}, js == ClimateStore.JOY_OFF ? 0 : 1, i -> {
                    ClimateStore.setJoystick(this, i == 0 ? ClimateStore.JOY_OFF : ClimateStore.JOY_RIGHT);
                    ClimateGuard.refreshFilter(this);   // key filter on only with the commands on
                    ClimateStore.event(this, "joystick commands: " + (i == 0 ? "off" : "on (" + Joystick.mapText(this, ClimateStore.JOY_RIGHT) + ")"));
                    settings();
                }), false);
        // another app with the key filter (e.g. MG4 Control) can take the same keys
        String others = ClimateGuard.conflictApps(this);
        if (!others.isEmpty()) {
            ClimateStore.event(this, "joystick: other apps that can take the keys: " + others);
            s.row(c1, L.s(R.string.cl_s_joy_conflict), L.f(R.string.cl_s_joy_conflict_sub, others.replaceAll(" \\([a-z0-9_.]+\\)", "")), null, false);
        }
        s.row(c1, L.s(R.string.cl_s_quick), L.s(R.string.cl_s_quick_sub),
                s.toggle(ClimateStore.quickCover(this), v -> {
                    ClimateStore.setQuickCover(this, v);
                    ClimateStore.event(this, "ready window " + (v ? "on" : "off"));
                }), false);
        // climate during Android Auto and CarPlay (off by default)
        s.row(c1, L.s(R.string.cl_s_proj), L.s(R.string.cl_s_proj_sub),
                s.toggle(ClimateStore.projectionZone(this), v -> {
                    ClimateStore.setProjectionZone(this, v);
                    ProjectionZone.refresh(this);
                    ClimateStore.event(this, "climate.projection: option " + (v ? "on" : "off"));
                }), false);
        s.row(c1, L.s(R.string.cl_s_shade), L.s(R.string.cl_s_shade_sub),
                s.toggle(ClimateStore.closeShade(this), v -> {
                    ClimateStore.setCloseShade(this, v);
                    ClimateStore.event(this, "shade closing " + (v ? "on" : "off"));
                    shadeBlock(v);
                }), true);

        // ---- tools
        s.label(L.s(R.string.cl_s_tools));
        LinearLayout c5 = s.card();
        s.row(c5, L.s(R.string.cl_s_orig_app), L.s(R.string.cl_s_orig_app_sub),
                s.button(L.s(R.string.cl_b_open), v -> openOriginal()), false);
        if (ClimateGuard.originalInstalled(this) && !known)
            s.row(c5, L.s(R.string.cl_s_verify), L.s(R.string.cl_s_verify_sub), s.button(L.s(AutoTest.resumable(this, "verify")
                    ? R.string.cl_b_resume : R.string.cl_b_start), v -> confirmVerify()), false);
        long oUntil = ClimateStore.bypassUntil(this);
        if (System.currentTimeMillis() < oUntil) {
            s.row(c5, L.s(R.string.cl_s_workshop), L.f(R.string.cl_s_workshop_on,
                    new java.text.SimpleDateFormat("dd/MM", java.util.Locale.ITALY).format(new java.util.Date(oUntil))),
                    s.button(L.s(R.string.cl_b_cancel), v -> {
                        ClimateStore.bypass(this, 0);
                        ClimateStore.event(this, "workshop mode cancelled");
                        settings();
                    }), false);
        } else {
            s.row(c5, L.s(R.string.cl_s_workshop), L.s(R.string.cl_s_workshop_sub),
                    s.choices(new String[]{L.s(R.string.cl_s_workshop_1), L.s(R.string.cl_s_workshop_3), L.s(R.string.cl_s_workshop_7)}, -1,
                            i -> confirmWorkshop(new int[]{1, 3, 7}[i])), false);
        }
        s.row(c5, L.s(R.string.cl_s_keys), L.s(R.string.cl_s_keys_sub), s.button(L.s(R.string.cl_b_open), v -> keyTest()), false);
        s.row(c5, L.s(R.string.cl_s_test), L.s(R.string.cl_s_test_sub), s.button(L.s(AutoTest.resumable(this, "test")
                ? R.string.cl_b_resume : R.string.cl_b_start), v -> confirmTest()), false);
        s.row(c5, L.s(R.string.cl_s_diag), L.s(R.string.cl_s_diag_sub), s.button(L.s(R.string.cl_b_show), v -> diagnostics()), true);
    }

    /** Setting of the original climate app: sent to the car, the page is redrawn as soon as the car answers. */
    private void setting(String what, int code, int value) {
        if (!last.connected) {
            toast(L.s(R.string.cl_a_not_connected));
            return;
        }
        send(what, code, value);
        h.postDelayed(() -> {
            if (settingsShowing()) settings();
        }, 1200);
    }

    private void confirmTest() {
        sheet().confirm(L.s(R.string.cl_s_test), L.s(R.string.cl_c_test_msg),
                L.s(R.string.cl_b_cancel), L.s(R.string.cl_b_start), () -> {
                    sheet.close();
                    runTest();
                });
    }

    /** Workshop mode: the replacement stays paused for N days, then comes back by itself. */
    private void confirmWorkshop(int days) {
        sheet().confirm(L.s(R.string.cl_s_workshop), L.f(R.string.cl_c_workshop_msg, days),
                L.s(R.string.cl_b_cancel), L.s(R.string.cl_b_activate), () -> {
                    sheet.close();
                    ClimateStore.bypass(this, days * 24L * 3600_000L);
                    ClimateStore.event(this, "workshop mode for " + days + " days");
                    toast(L.f(R.string.cl_s_workshop_on, new java.text.SimpleDateFormat("dd/MM", java.util.Locale.ITALY)
                            .format(new java.util.Date(ClimateStore.bypassUntil(this)))));
                });
    }

    /** Check of a new version of the original climate app: same commands as the automatic test. */
    private void confirmVerify() {
        sheet().confirm(L.s(R.string.cl_s_verify), L.s(R.string.cl_c_verify_msg),
                L.s(R.string.cl_b_cancel), L.s(R.string.cl_b_start), () -> {
                    sheet.close();
                    runVerify();
                });
    }

    private void showResult(String text) {
        testRunning = false;
        banner.setText(text);
        banner.setOnClickListener(v -> banner.setVisibility(View.GONE));
        h.postDelayed(() -> banner.setVisibility(View.GONE), 60_000);
    }

    private void runVerify() {
        final String ver = ClimateGuard.originalVersion(this);
        testRunning = true;
        banner.setText(L.s(R.string.cl_s_verify));
        banner.setVisibility(View.VISIBLE);
        ClimateStore.event(this, "check of climate app " + ver + (AutoTest.resumable(this, "verify") ? " resumed" : " started"));
        AutoTest.start(this, client,
                x -> h.post(() -> banner.setText(L.s(R.string.cl_s_verify) + " · " + x)), "verify",
                r -> h.post(() -> {
                    boolean ok = !r.startsWith("interrupted") && !r.startsWith("error") && r.contains(AutoTest.NO_FAILS);
                    boolean moving = r.contains(AutoTest.MOVING);
                    if (ok) ClimateStore.addVerified(this, ver);
                    if (ok) ClimateStore.setPendingVerify(this, "");
                    // failed (not interrupted): not offered again by itself for this pair of versions
                    if (!ok && !moving && !r.startsWith("interrupted"))
                        ClimateStore.setVerifyFailed(this, ClimateStore.appVersion(this), ver);
                    ClimateStore.event(this, "check " + ver + (ok ? ": passed" : moving ? ": interrupted (car moving, it can be resumed)"
                            : ": failed") + " · " + r);
                    showResult(ok ? L.f(R.string.cl_a_verify_ok, ver) : moving ? L.s(R.string.cl_a_test_moving)
                            : L.f(R.string.cl_a_verify_fail, ver));
                }));
    }

    /** On opening: if an unknown version is waiting, it offers the check (climate off, car stopped only). */
    private void maybeAskVerify() {
        if (!ClimateGuard.originalInstalled(this) || ClimateGuard.originalKnown(this)) return;
        String ver = ClimateGuard.originalVersion(this);
        // offered once per version
        if (ClimateStore.verifyFailed(this, ClimateStore.appVersion(this), ver)) return;   // already failed: by hand only
        boolean pending = ver.equals(ClimateStore.pendingVerify(this));
        if (!pending && ver.equals(ClimateStore.askedVerify(this))) return;
        if (System.currentTimeMillis() < ClimateStore.bypassUntil(this)) return;
        if (testRunning || (sheet != null && sheet.showing())) return;
        // without the speed (NaN) it asks anyway, the confirmation says to stay still; the test stops by itself
        if (!last.connected || last.on() || (!Float.isNaN(last.speed) && last.speed > 0f)) return;
        ClimateStore.setAskedVerify(this, ver);
        ClimateStore.event(this, "check of " + ver + " offered on opening");
        confirmVerify();
    }

    private void runTest() {
        testRunning = true;
        banner.setText(L.s(R.string.cl_a_test_start));
        banner.setVisibility(View.VISIBLE);
        ClimateStore.event(this, "automatic test " + (AutoTest.resumable(this, "test") ? "resumed" : "started"));
        AutoTest.start(this, client,
                x -> h.post(() -> banner.setText(L.s(R.string.cl_s_test) + " · " + x)), "test",
                r -> h.post(() -> showResult(r.contains(AutoTest.MOVING) ? L.s(R.string.cl_a_test_moving) : L.f(R.string.cl_a_test_done, r))));
    }

    // ---------------------------------------------------------------------------------- steering wheel joystick

    private Joystick joy;
    private boolean consumeLogged;
    private final java.util.ArrayDeque<String> keysSeen = new java.util.ArrayDeque<>();
    /** Pairing in progress from the Key test page: joystick (1/2) and step (index in Joystick.ACTIONS). */
    private int assignSide, assignStep = -1;

    private void onWheelKey(int code, boolean down, boolean lp, boolean consumed) {
        if (!consumeLogged && down) {
            consumeLogged = true;
            ClimateStore.event(this, "joystick: steering wheel keys " + (consumed ? "consumed by the climate screen (the other apps do not get them)"
                    : ClimateGuard.keyFilterOn() ? "received through the broadcast while the filter is on (exclusivity is measured)"
                    : "also received by the other apps (the system does not allow blocking them)"));
        }
        if (ClimateGuard.keyFilterOn() && down && !lp) joy.sawCast(code, h);
        handleKey(code, down, lp, "broadcast");
    }

    /**
     * Key seen by the accessibility filter (main thread, must return at once). Only the paired joystick keys are
     * consumed, and only with the screen in front; all the others stay with the system.
     */
    private boolean onFilterKey(int keyCode, int scanCode, boolean down, int repeat) {
        boolean keyTest = sheet != null && sheet.showing() && KEYS.equals(currentSheet);
        if (down) {
            boolean mapped = ClimateStore.joystick(this) != ClimateStore.JOY_OFF && (Joystick.actionOf(this, ClimateStore.JOY_RIGHT, keyCode) != null
                    || Joystick.actionOf(this, ClimateStore.JOY_LEFT, keyCode) != null);
            if (!mapped && !(keyTest && assignStep >= 0 && keyCode >= 280)) return false;
            joy.sawFilter(keyCode, h);
        }
        h.post(() -> {
            if (keyTest) ClimateStore.event(this, "filter: keycode " + keyCode + " scancode " + scanCode + (down ? " pressed" : " released"));
            handleKey(keyCode, down, false, "filter");
        });
        return true;
    }

    /** Same key from the filter and the broadcast: used once only (the first that arrives). */
    private int lastKeyCode = -1;
    private boolean lastKeyDown;
    private long lastKeyAt;

    /** Codes seen by the filter since the screen came to the front: for these only the filter is used. */
    private final java.util.Set<Integer> filterCodes = new java.util.HashSet<>();

    private void handleKey(int code, boolean down, boolean lp, String from) {
        long now = android.os.SystemClock.uptimeMillis();
        // same key from the filter and the broadcast: if the filter sees it only the filter is used (no double commands)
        if ("filter".equals(from)) filterCodes.add(code);
        else if (filterCodes.contains(code) && ClimateGuard.keyFilterOn()) return;
        // SWI69, joystick in the car's climate mode: codes 311–315, the car handles them
        if (code >= 311 && code <= 315 && !(sheet != null && sheet.showing())) {
            if (down && !carModeLogged) {
                carModeLogged = true;
                ClimateStore.event(this, "joystick in the car's climate mode (code " + code + "): commands left to the car");
            }
            return;
        }
        if (!lp) {
            lastKeyCode = code;
            lastKeyDown = down;
            lastKeyAt = now;
        }
        // Key test page: every key is shown and logged, no commands
        if (sheet != null && sheet.showing() && KEYS.equals(currentSheet)) {
            String line = code + (down ? " pressed" : " released") + (lp ? " (long)" : "") + ("filter".equals(from) ? " · filter" : "");
            ClimateStore.event(this, "steering wheel key: code " + line);
            keysSeen.addFirst(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date()) + "  " + line);
            while (keysSeen.size() > 12) keysSeen.removeLast();
            if (down && !lp && assignStep >= 0) {
                String a = Joystick.ACTIONS[assignStep];
                ClimateStore.setJoyCode(this, assignSide, a, code);
                assignStep++;
                if (assignStep >= Joystick.ACTIONS.length) {
                    assignStep = -1;
                    assignDone();
                }
            }
            keyTest();
            return;
        }
        if (sheet != null && sheet.showing()) return;   // settings open: the keys do not control the climate
        if (ClimateStore.joystick(this) == ClimateStore.JOY_OFF) return;
        String action = Joystick.actionOf(this, ClimateStore.JOY_RIGHT, code);
        if (action == null) action = Joystick.actionOf(this, ClimateStore.JOY_LEFT, code);
        if (action == null) return;
        if (!down) {
            stopRepeat();
            return;
        }
        if (lp && repeatAction != null) return;   // already repeating with our timer
        // wait a moment for the key forwarded by the SystemUI: if the joystick is in the car's climate mode (★ key) the
        // car already controls the climate, the screen sends nothing (no double commands)
        final String act = action;
        final long pressAt = now;
        // taken by the filter → the SystemUI did not see it, nothing to wait for: command at once
        if ("filter".equals(from)) {
            joyAction(code, act, false);
            if (!"ok".equals(act)) startRepeat(code, act);
            return;
        }
        h.postDelayed(() -> {
            if (sysAt >= pressAt - 100 && sysMode == 2) {
                if (!carModeLogged) {
                    carModeLogged = true;
                    ClimateStore.event(this, "joystick in the car's climate mode (★ key): commands left to the car, the screen only shows the state");
                }
                view.flash(flashId(act));
                return;
            }
            joyAction(code, act, false);
            // repeat only if the key is still pressed (a short tap has already been released)
            if (!"ok".equals(act) && lastKeyCode == code && lastKeyDown) startRepeat(code, act);
        }, SYS_WAIT_MS);
    }

    private static final long SYS_WAIT_MS = 150;
    /** Last key forwarded by the SystemUI and the joystick mode it shows (see Joystick.sysMode). */
    private long sysAt;
    private int sysMode = -1, sysModeLogged = -2;
    private boolean carModeLogged;

    private void onSysKey(int sysCode, boolean down) {
        int m = Joystick.sysMode(sysCode);
        if (m < 0) return;
        if (down) {
            sysAt = android.os.SystemClock.uptimeMillis();
            sysMode = m;
            if (m == 2) joy.carClimateMode = true;   // the music is not involved: no exclusivity measurement
            if (m == 0 && ClimateGuard.keyFilterOn()) joy.sawSystemMusic();
        }
        if (m != sysModeLogged && ClimateStore.joystick(this) != ClimateStore.JOY_OFF) {
            sysModeLogged = m;
            ClimateStore.event(this, "joystick.carMode = " + Joystick.sysModeName(m) + " (the SystemUI forwards code " + sysCode + ")");
        }
    }

    private static String flashId(String action) {
        switch (action) {
            case "up": return "temp+";
            case "down": return "temp-";
            case "left": return "fan-";
            case "right": return "fan+";
            default: return "power";
        }
    }

    /**
     * End of the pairing. The left joystick is saved only if it sends codes different from the right one, otherwise
     * "the left joystick is not available on this car" (in the logs it sends the same codes as the right one).
     */
    private void assignDone() {
        if (assignSide == ClimateStore.JOY_LEFT) {
            boolean same = true;
            for (String a : Joystick.ACTIONS) {
                int k = ClimateStore.joyCode(this, ClimateStore.JOY_LEFT, a);
                if (Joystick.actionOf(this, ClimateStore.JOY_RIGHT, k) == null) same = false;
            }
            if (same) {
                ClimateStore.clearJoy(this, ClimateStore.JOY_LEFT);
                ClimateStore.event(this, "left joystick: same codes as the right one → not available on this car");
                toastLong(L.s(R.string.cl_k_left_none));
                return;
            }
        }
        ClimateStore.event(this, (assignSide == ClimateStore.JOY_RIGHT ? "right" : "left") + " joystick paired: "
                + Joystick.mapText(this, assignSide));
    }

    /** Runs the action: OK turns on (in AUTO) or off, up/down temperature, left/right fan. */
    private void joyAction(int code, String action, boolean repeat) {
        HvacClient.State s = last;
        String id, what, result = "sent";
        switch (action) {
            case "ok":
                id = s.on() ? "power" : "auto";
                what = s.on() ? "climate off" : "climate on (AUTO)";
                break;
            case "up":
                id = "temp+";
                what = "temperature +";
                if (s.temp >= s.setMax()) result = s.noHi ? "already at the maximum (HI hidden)" : "already at the maximum (HI)";
                break;
            case "down":
                id = "temp-";
                what = "temperature -";
                if (s.temp <= s.setMin()) result = s.noLo ? "already at the minimum (LO hidden)" : "already at the minimum (LO)";
                break;
            case "left":
                id = "fan-";
                what = !s.on() ? "fan - (climate off)" : s.fan >= 1 && s.fan <= 11 && s.fan <= Math.max(1, s.fanMin)
                        ? "fan - at the minimum → climate off" : "fan -";
                if (!s.on()) result = "climate off";
                else if (repeat && s.fan >= 1 && s.fan <= Math.max(1, s.fanMin)) result = "already at the minimum";   // holding it does not turn off
                break;
            default:
                id = "fan+";
                what = s.on() ? "fan +" : "fan + → climate on";
                if (s.on() && s.fan >= 1 && s.fan <= 11 && s.fan >= Math.min(11, s.fanMax)) result = "already at the maximum";
                if (!s.on() && repeat) result = "already on";
                break;
        }
        if (!s.connected) result = "climate service not connected";
        else if (!s.usable()) result = "car not on";
        if (!repeat || !"sent".equals(result)) ClimateStore.event(this, "joystick: code " + code + " → " + what + " → " + result);
        view.flash("auto".equals(id) ? "power" : id);
        if ("sent".equals(result)) command(id);
        else if (repeat) stopRepeat();
    }

    private String repeatAction;
    private int repeatCode, repeatCount;
    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (repeatAction == null) return;
            if (++repeatCount > 30) {   // at most 30 repeats (in case the release does not arrive)
                stopRepeat();
                return;
            }
            joyAction(repeatCode, repeatAction, true);
            h.postDelayed(this, 300);
        }
    };

    /** Holding up/down/left/right repeats the command (after half a second, every 0.3 s). */
    private void startRepeat(int code, String action) {
        stopRepeat();
        repeatAction = action;
        repeatCode = code;
        repeatCount = 0;
        h.postDelayed(repeater, 500);
    }

    private void stopRepeat() {
        repeatAction = null;
        h.removeCallbacks(repeater);
    }

    /** Steering wheel Key test page: shows the codes pressed and lets you pair the joystick. */
    private void keyTest() {
        Sheet s = sheet().open(L.s(R.string.cl_s_keys), L.s(R.string.cl_s_keys_hint));
        currentSheet = KEYS;
        LinearLayout c = s.card();
        String prompt = assignStep >= 0 ? L.f(R.string.cl_k_press, L.s(assignSide == ClimateStore.JOY_RIGHT ? R.string.cl_s_joy_right : R.string.cl_s_joy_left),
                L.s(actionName(Joystick.ACTIONS[assignStep]))) : L.s(R.string.cl_k_idle);
        s.row(c, prompt, keysSeen.isEmpty() ? L.s(R.string.cl_k_none) : String.join("\n", keysSeen), null, true);
        LinearLayout c2 = s.card();
        s.row(c2, L.s(R.string.cl_s_joy_right), Joystick.mapText(this, ClimateStore.JOY_RIGHT)
                        + (ClimateStore.joyFactory(this) ? " · " + L.s(R.string.cl_k_factory) : ""),
                s.button(L.s(R.string.cl_b_assign), v -> {
                    ClimateStore.clearJoy(this, ClimateStore.JOY_RIGHT);
                    assignSide = ClimateStore.JOY_RIGHT;
                    assignStep = 0;
                    ClimateStore.event(this, "right joystick pairing started");
                    keyTest();
                }), false);
        // the left one is not a choice: it is tried whether it sends codes of its own (usually not)
        boolean leftSaved = ClimateStore.joyCode(this, ClimateStore.JOY_LEFT, "up") >= 0;
        s.row(c2, L.s(R.string.cl_s_joy_left), leftSaved ? Joystick.mapText(this, ClimateStore.JOY_LEFT) : L.s(R.string.cl_k_left_try),
                s.button(L.s(R.string.cl_b_try), v -> {
                    ClimateStore.clearJoy(this, ClimateStore.JOY_LEFT);
                    assignSide = ClimateStore.JOY_LEFT;
                    assignStep = 0;
                    ClimateStore.event(this, "left joystick try started");
                    keyTest();
                }), true);
    }

    private static int actionName(String a) {
        switch (a) {
            case "up": return R.string.cl_k_up;
            case "down": return R.string.cl_k_down;
            case "left": return R.string.cl_k_left;
            case "right": return R.string.cl_k_right;
            default: return R.string.cl_k_ok;
        }
    }

    private void diagnostics() {
        Sheet s = sheet().open(L.s(R.string.cl_s_diag), null);
        currentSheet = DIAG;
        s.body(s.card(), ClimateStore.text(this));
    }

    // ---------------------------------------------------------------------------------- notification shade

    /**
     * Blocks the system notification shade (StatusBarManager.disable, STATUS_BAR permission of the system signature)
     * while the climate screen is in front: the system would open it every time the climate turns on/off.
     * The block is tied to this app: if it closes or crashes the system removes it by itself.
     */
    private void shadeBlock(boolean on) {
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("disable", int.class).invoke(sb, on ? 0x00010000 : 0);   // DISABLE_EXPAND
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            ClimateStore.event(this, "notification shade block failed: " + c);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        open(false, "onPause");
        h.removeCallbacks(autoCloseR);
        ClimateGuard.keyListener(null);   // the keys go back to the other apps at once (a key already pressed ends with us)
        shadeBlock(false);
        ClimateGuard.edges(this, 0);
        joy.unregister();
        stopRepeat();
        super.onPause();
    }

    /** The manifest declares uiMode in configChanges: the activity is not recreated, so the view gets the new theme. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyTheme();
    }

    /** Redraws with the current theme (day/night as the launcher). */
    private void applyTheme() {
        if (view == null) return;
        view.theme();
        bannerColors();
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(view.BG));
    }

    private void bannerColors() {
        if (banner == null || view == null) return;
        banner.setTextColor(view.TEXT);
        banner.setBackgroundColor((view.PANEL & 0xFFFFFF) | 0xE0000000);
    }

    private android.view.GestureDetector swipe;
    private boolean swiped;
    private long lastMissAt;

    /**
     * Closing with a swipe inside the panel (as MG4 Swipe): GestureDetector.onFling, at least 60 px vertically and
     * speed 100, wherever it starts. It watches the touch without consuming it, so the buttons stay the same; only the
     * recognised swipe consumes the finger lift (no tap on a button). Not with the settings open.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) armAutoClose();
        int mode = ClimateStore.swipeMode(this);
        if (mode != ClimateStore.SWIPE_OFF && swipe != null && (sheet == null || !sheet.showing())) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                swiped = false;
                downY = e.getRawY();
                downX = e.getRawX();
            }
            swipe.onTouchEvent(e);
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                if (swiped) {
                    close(swipeHow);
                    return true;
                }
                // long vertical gesture but not valid (too slow or crooked): in the log, at most every 3 s
                float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                long now = android.os.SystemClock.elapsedRealtime();
                if (Math.abs(dy) >= 60 && Math.abs(dy) > Math.abs(dx) && now - lastMissAt > 3000) {
                    lastMissAt = now;
                    ClimateStore.event(this, "closing: swipe " + (dy < 0 ? "upwards" : "downwards") + " in the panel ("
                            + Math.round(Math.abs(dy)) + " px) not recognised (mode " + ClimateStore.swipeName(mode) + ")");
                }
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private float downX, downY;
    private String swipeHow = "";

    private boolean onSwipe(android.view.MotionEvent e1, android.view.MotionEvent e2, float vy) {
        if (e1 == null) return false;
        float dy = e2.getRawY() - e1.getRawY(), dx = e2.getRawX() - e1.getRawX();
        int mode = ClimateStore.swipeMode(this);
        boolean up = dy < 0;
        boolean dirOk = mode == ClimateStore.SWIPE_AUTO || (mode == ClimateStore.SWIPE_UP && up) || (mode == ClimateStore.SWIPE_DOWN && !up);
        // 60 px: short taps are 3–13 px, so they are not confused
        boolean ok = Math.abs(dy) > 60 && Math.abs(vy) > 100 && Math.abs(dy) > Math.abs(dx) * 1.5f && dirOk;
        String g = "swipe " + (up ? "upwards" : "downwards") + " in the panel (" + Math.round(Math.abs(dy))
                + " px, speed " + Math.round(Math.abs(vy)) + ")";
        if (!ok) {
            // short taps (below 30 px) are not swipes: they do not go to the log
            if (Math.abs(dy) >= 30) {
                lastMissAt = android.os.SystemClock.elapsedRealtime();
                ClimateStore.event(this, "closing: " + g + " not recognised (mode " + ClimateStore.swipeName(mode) + ")");
            }
            return false;
        }
        swiped = true;
        swipeHow = g;
        return true;
    }

    /** Closing goes back to the home (the original climate app is below and should not be seen again). */
    @Override
    public void onBackPressed() {
        if (sheet != null && sheet.showing()) {
            assignStep = -1;   // joystick pairing left half-way: it starts again from "Pair"
            sheet.close();
            return;
        }
        boolean edge = android.os.SystemClock.elapsedRealtime() - ClimateGuard.edgeBackAt < 1500;
        ClimateGuard.edgeBackAt = 0;
        close(edge ? "edge swipe" : "back button");
    }

    /** Every closing goes to the log with the way it happened. */
    private void close(String how) {
        if (sheet != null && sheet.showing()) sheet.close();
        ClimateStore.event(this, "closing: " + how + " → closed");
        closing = how.startsWith("automatic closing") ? "automatic closing"
                : ReturnTo.fromProjection() ? "back to Android Auto/CarPlay" : how;
        ReturnTo.back(this);   // back to Android Auto / CarPlay if it was opened from there, otherwise home
        finish();
    }
}
