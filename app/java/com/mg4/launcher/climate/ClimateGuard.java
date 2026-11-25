package com.mg4.launcher.climate;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Replacement of the original climate app (com.saicmotor.hvac, MainActivity) by the built-in climate screen, and
 * closing of the notification shade the system opens when the climate turns on (from MG4 Clima).
 * <p>
 * It only uses window change events (no screen content). When the main screen of the climate app comes to the front,
 * the climate screen opens over it. Safeguards:
 * <ul>
 *   <li>only if the version of the climate app is one of those analysed, or verified on this car;</li>
 *   <li>only the main screen: popups and system windows are not touched;</li>
 *   <li>switch in the settings, and a pause when the original app is opened on purpose;</li>
 *   <li>anti-loop: more than 3 replacements in 30 s → 2-minute pause.</li>
 * </ul>
 * The original app is not modified: if the climate screen crashes, the original stays usable.
 * <p>
 * A separate accessibility service from {@link com.mg4.launcher.svc.BootAccessibility}: it also asks for the steering
 * wheel key filter, which must not depend on the launcher's own service.
 */
public final class ClimateGuard extends AccessibilityService {
    /** Original climate app of the SWI69 (R45; also SWI132 R43): package, main screen, analysed versions. */
    public static final String HVAC_PKG = "com.saicmotor.hvac", HVAC_MAIN = "com.saicmotor.hvac.ui.activity.MainActivity";
    private static final String[] KNOWN_VERSIONS = {"A9_Hvac_V1.0.1.120"};

    private static final long LOOP_WINDOW_MS = 30_000, LOOP_PAUSE_MS = 2 * 60_000L;
    private static final int LOOP_MAX = 3;
    /** Double events of the same opening (DUP_MS) and at most one replacement every GAP_MS. */
    private static final long DUP_MS = 1500, GAP_MS = 4000;
    private long lastReplaceAt = -60_000, dupLogged;
    private final ArrayDeque<Long> seen = new ArrayDeque<>();
    private long pausedUntil = -1;

    public static String originalVersion(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(HVAC_PKG, 0).versionName;
            return v == null || v.isEmpty() ? "unnamed" : v;
        } catch (Exception e) {
            return "absent";
        }
    }

    /** Full version for the logs: package, versionName and versionCode. */
    public static String originalDetails(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(HVAC_PKG, 0);
            @SuppressWarnings("deprecation") long code = pi.versionCode;
            return HVAC_PKG + " " + pi.versionName + " (code " + code + ")";
        } catch (Exception e) {
            return HVAC_PKG + " absent";
        }
    }

    /** Line for the diagnostics. */
    public static String originalLine(Context c) {
        return originalVersion(c) + (originalKnown(c) ? " (verified)" : " (not verified: no replacement)")
                + " · " + originalDetails(c);
    }

    public static boolean originalKnown(Context c) {
        String v = originalVersion(c);
        for (String k : KNOWN_VERSIONS) if (k.equals(v)) return true;
        return ClimateStore.isVerified(c, v);   // verified on this car
    }

    public static boolean originalInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(HVAC_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Enables the service permanently (WRITE_SECURE_SETTINGS, platform signature), without touching the others. */
    public static void ensureEnabled(Context c) {
        try {
            String me = new ComponentName(c, ClimateGuard.class).flattenToString();
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = false;
            if (!TextUtils.isEmpty(cur)) for (String s : cur.split(":")) if (s.equalsIgnoreCase(me)) listed = true;
            if (!listed) {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        TextUtils.isEmpty(cur) ? me : cur + ":" + me);
                ClimateStore.event(c, "accessibility enabled");
            }
            if (Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (Throwable e) {
            ClimateStore.event(c, "accessibility: error " + e);
        }
    }

    public static String enabledState(Context c) {
        try {
            String me = new ComponentName(c, ClimateGuard.class).flattenToString();
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return (cur != null && cur.toLowerCase().contains(me.toLowerCase()) ? "enabled" : "not enabled")
                    + (instance != null ? ", connected" : ", not connected");
        } catch (Throwable e) {
            return "?";
        }
    }

    // ---- notification shade: the system opens it when the climate turns on/off (also from the physical button) ------

    private HvacClient hvac;
    private android.os.Handler bg;
    private volatile int knownPower = -1;
    private volatile long powerChangedAt, sysUiAt;
    private final Set<String> seenSysUi = new HashSet<>();

    private final Runnable powerPoll = new Runnable() {
        @Override
        public void run() {
            try {
                android.os.UserManager um = (android.os.UserManager) getSystemService(USER_SERVICE);
                if (um == null || um.isUserUnlocked()) {
                    if (hvac == null) {
                        hvac = new HvacClient(ClimateGuard.this);
                        hvac.startLight();
                    } else {
                        checkPower();
                    }
                }
            } catch (Throwable t) {
                ClimateStore.event(ClimateGuard.this, "power check: " + t);
            }
            // every 300 ms, so the climate turning on is seen early and the shade closes at once
            bg.postDelayed(this, 300);
        }
    };

    /** Reads the power state; if the climate has just turned on/off, closes the shade the system opened. */
    private void checkPower() {
        HvacClient c = hvac;
        if (c == null) return;
        int p = c.readPower();
        long now = SystemClock.elapsedRealtime();
        if (p >= 0 && knownPower >= 0 && p != knownPower) {
            powerChangedAt = now;
            // the shade opened a moment before we noticed
            if (now - sysUiAt < 1500) collapse("climate " + (p == 1 ? "on" : "off"));
        }
        if (p >= 0) knownPower = p;
    }

    private static boolean isSysUi(CharSequence pkg) {
        return pkg != null && pkg.toString().toLowerCase(java.util.Locale.ROOT).contains("systemui");
    }

    /** App in front (from the last window change that is not the SystemUI nor one of our overlay windows). */
    private volatile String fgPkg = "";
    /** The window in front is the climate screen (not the launcher home, which is the same package). */
    private volatile boolean fgClimate;
    /** Last app in front other than the climate screen and the original climate app (SystemUI excluded). */
    static volatile String lastOther = "";

    /** "Climate screen open" only if the screen is really visible (between onResume and onPause) AND the service sees it in front. */
    boolean climateFront() {
        return ClimateActivity.resumed && fgClimate;
    }

    static boolean climateFrontNow() {
        ClimateGuard g = instance;
        return g != null && g.climateFront();
    }

    /** App in front as seen by the service (SystemUI and our overlay windows excluded). */
    static String foreground() {
        ClimateGuard g = instance;
        return g == null ? "-" : g.fgPkg + (g.fgClimate ? " (climate screen)" : "");
    }

    private final android.os.Handler mainH = new android.os.Handler(android.os.Looper.getMainLooper());
    private int barTaps;

    /**
     * Tap on the bar with the projection in front (zone). Starts the climate screen and after 1 s checks that it is
     * really in front. If AllGo took the screen back, it shows the overlay panel.
     */
    void openFromBar(String who) {
        final String under = fgPkg;
        final int n = ++barTaps;
        ClimateStore.event(this, "climate.bar #" + n + ": tap taken by " + who + " over " + under + " → starting the climate screen");
        try {
            startActivity(new Intent(this, ClimateActivity.class)
                    .putExtra(ReturnTo.EXTRA_FROM, under)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Throwable t) {
            ClimateStore.event(this, "climate.bar #" + n + ": start failed " + t.getClass().getSimpleName());
        }
        mainH.postDelayed(() -> {
            if (climateFront()) {
                ClimateStore.event(this, "climate.bar #" + n + ": climate screen in front after 1 s");
                return;
            }
            String now = fgPkg;
            if (ProjectionZone.isProjection(now)) {
                boolean ok = OverlayPanel.show(this, now);
                ClimateStore.event(this, "climate.bar #" + n + ": after 1 s " + now + " is still in front"
                        + (ok ? " → climate as an overlay panel" : " → panel refused"));
            } else {
                ClimateStore.event(this, "climate.bar #" + n + ": the climate screen is not in front (in front " + now + ")");
            }
        }, 1000);
    }

    /**
     * SystemUI window: the power state is read at once; if it has just changed, the climate opened the shade.
     * With the climate screen in front the shade is never needed (e.g. physical climate button): it is closed.
     */
    private void onSysUi(AccessibilityEvent e) {
        sysUiAt = SystemClock.elapsedRealtime();
        String what = e.getPackageName() + "/" + e.getClassName();
        if (seenSysUi.size() < 20 && seenSysUi.add(what)) ClimateStore.event(this, "system window: " + what);
        if (climateFront()) {   // only with the screen really in front
            collapse("climate screen in front");
            return;
        }
        if (bg == null || hvac == null) return;
        bg.post(() -> {
            int p = hvac.readPower();
            long now = SystemClock.elapsedRealtime();
            boolean changed = (p >= 0 && knownPower >= 0 && p != knownPower) || now - powerChangedAt < 2000;
            if (p >= 0 && knownPower >= 0 && p != knownPower) powerChangedAt = now;
            if (p >= 0) knownPower = p;
            if (changed) collapse("climate " + (p == 1 ? "on" : "off"));
        });
    }

    private long lastCollapse;

    /** Closes the notification shade (one series of closings at a time). */
    private void collapse(String why) {
        if (!ClimateStore.closeShade(this)) return;
        long t = SystemClock.elapsedRealtime();
        if (t - lastCollapse < 2500) return;
        lastCollapse = t;
        String r;
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("collapsePanels").invoke(sb);
            r = "closed";
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            r = "not closed: " + c;
        }
        ClimateStore.event(this, "notification shade " + r + " (" + why + ")");
    }

    @Override
    protected void onServiceConnected() {
        long up = SystemClock.elapsedRealtime();
        ClimateStore.event(this, "service connected · original climate " + originalLine(this)
                + " · " + (up / 1000) + " s since start-up");
        instance = this;
        // if the climate screen is already open (the service connects later), the edge strips are added at once
        if (wantedEdges != 0) edges(this, wantedEdges);
        // key filter on if the joystick commands are on (it consumes only with the screen in front)
        filterOn = false;
        applyFilter(ClimateStore.joystick(this) != ClimateStore.JOY_OFF);
        main.postDelayed(this::prepareCover, 25_000);
        ReturnTo.bind(this);   // Android Auto / CarPlay session (AllGo) for the return on closing
        ProjectionZone.attach(this);
        if (bg == null) {
            android.os.HandlerThread th = new android.os.HandlerThread("mg4-climate-guard");
            th.start();
            bg = new android.os.Handler(th.getLooper());
            bg.postDelayed(powerPoll, 20_000);
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        edgesOff();
        hideCover("service closed");
        ProjectionZone.hide(this, "service closed");
        filterOn = false;
        if (bg != null) bg.removeCallbacksAndMessages(null);
        if (hvac != null) hvac.stop();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (isSysUi(e.getPackageName())) {
            onSysUi(e);
            return;
        }
        CharSequence pk = e.getPackageName(), cls = e.getClassName();
        if (pk == null) return;
        // our windows that are not an activity (zone over the bar, ready window, overlay panel, dialogs) do not count
        // as "in front"; the climate screen and the launcher's own activities do (same package)
        if (getPackageName().contentEquals(pk)) {
            String c = cls == null ? "" : cls.toString();
            if (ClimateActivity.class.getName().equals(c)) fgClimate = true;
            else if (c.startsWith(getPackageName() + ".") && c.endsWith("Activity")) fgClimate = false;
            else return;
        } else {
            fgClimate = false;
        }
        fgPkg = pk.toString();
        OverlayPanel.onForeground(fgClimate ? "" : fgPkg);   // overlay panel only with the projection in front
        // the last app in front that is not the climate screen nor the original climate (to go back there on closing)
        if (!fgClimate && !HVAC_PKG.equals(fgPkg)) lastOther = fgPkg;
        ProjectionZone.onForeground(this, fgPkg);   // climate zone during Android Auto / CarPlay
        // another app (not the climate screen nor the original climate) in front → away with the ready window
        if (!fgClimate && !HVAC_PKG.equals(fgPkg)) {
            if (cover != null) hideCover("in front " + fgPkg);
        }
        if (!HVAC_PKG.contentEquals(pk)) return;
        if (cls == null || !HVAC_MAIN.contentEquals(cls)) return;   // only the main screen
        // exact moment the original window appeared (event time, in ms)
        long shownAt = System.currentTimeMillis() - Math.max(0, SystemClock.uptimeMillis() - e.getEventTime());
        String at = " · original shown at " + ClimateStore.ms(shownAt);
        if (!ClimateStore.replace(this)) {
            ClimateStore.event(this, "original climate app left (replacement off)" + at);
            return;
        }
        if (ClimateStore.bypassed(this)) {
            ClimateStore.event(this, "original climate app left (opened on purpose or workshop mode)" + at);
            return;
        }
        if (!originalKnown(this)) {
            ClimateStore.setPendingVerify(this, originalVersion(this));   // to be verified the next time the screen opens
            ClimateStore.event(this, "climate app " + originalVersion(this) + " not verified: no replacement" + at);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pausedUntil > now) {
            ClimateStore.event(this, "original climate app left (anti-loop pause)" + at);
            return;
        }
        // the same opening often arrives with two events in a row: one replacement only
        if (now - lastReplaceAt < DUP_MS) {
            if (dupLogged != lastReplaceAt) {
                dupLogged = lastReplaceAt;
                ClimateStore.event(this, "original climate app: double event ignored (" + (now - lastReplaceAt) + " ms after the replacement)");
            }
            return;
        }
        // if the original opens again right after the replacement it is left (no flipping between the two)
        if (now - lastReplaceAt < GAP_MS) {
            ClimateStore.event(this, "original climate app opened again " + (now - lastReplaceAt) + " ms after the replacement: left");
            return;
        }
        seen.addLast(now);
        while (!seen.isEmpty() && now - seen.peekFirst() > LOOP_WINDOW_MS) seen.removeFirst();
        if (seen.size() > LOOP_MAX) {
            pausedUntil = now + LOOP_PAUSE_MS;
            seen.clear();
            ClimateStore.event(this, "replacement paused for 2 minutes (the climate app opened again too many times)");
            return;
        }
        try {
            lastReplaceAt = now;
            ClimateStore.event(this, "original climate app shown at " + ClimateStore.ms(shownAt) + " (event received after "
                    + Math.max(0, System.currentTimeMillis() - shownAt) + " ms)");
            // first the ready window (already drawn), then the real screen opening below it
            showCover(shownAt);
            startActivity(new Intent(this, ClimateActivity.class)
                    .putExtra(ClimateActivity.EXTRA_ORIGINAL_AT, shownAt)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            ClimateStore.event(this, "original climate app replaced");
        } catch (Throwable t) {
            ClimateStore.event(this, "replacement failed: " + t);
        }
    }

    // ---- swipe from the edge to close the climate screen (as MG4 Swipe) ----------------------------------------------
    // At the top and bottom there are the system bars: a swipe that starts there never reaches the screen. While the
    // climate screen is in front the service adds two thin invisible strips on the edges (accessibility windows: no
    // overlay permission), recognises the gesture (at least 60 px and speed 100) and sends "back" with
    // performGlobalAction. As soon as the screen leaves, the strips are removed.

    private static volatile ClimateGuard instance;
    /** Last "back" sent by a strip (to tell in the log the edge swipe from the back button). */
    static volatile long edgeBackAt;
    private static boolean edgeWarned;
    /** Strips wanted by the screen (0 = none): added also if the service connects later. */
    private static volatile int wantedEdges;

    /** The accessibility service is connected (needed for the replacement, the shade and the edge swipe). */
    static boolean connected() {
        return instance != null;
    }

    // minimum distance 60 px: short taps stay below 15 px
    private static final int EDGE_PX = 12, SWIPE_MIN = 60, SWIPE_VEL = 100;
    private android.view.View edgeTop, edgeBottom;

    /** Turns on (mode 1 = bottom edge, 2 = top edge, 3 = both) or off (0) the strips. From the main thread. */
    static void edges(Context c, int mode) {
        wantedEdges = mode;
        ClimateGuard g = instance;
        if (g == null) {
            if (mode != 0 && !edgeWarned) {
                edgeWarned = true;
                ClimateStore.event(c, "edge closing unavailable: accessibility service not connected");
            }
            return;
        }
        g.edgesOff();
        if (mode == 0) return;
        try {
            if (mode == 1 || mode == 3) g.edgeBottom = g.edge(false);
            if (mode == 2 || mode == 3) g.edgeTop = g.edge(true);
        } catch (Throwable t) {
            ClimateStore.event(c, "edge closing: strips not added " + t);
            g.edgesOff();
        }
    }

    private android.view.View edge(final boolean top) {
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, EDGE_PX,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = (top ? android.view.Gravity.TOP : android.view.Gravity.BOTTOM) | android.view.Gravity.LEFT;
        lp.setTitle("MG4 climate " + (top ? "top" : "bottom") + " edge");
        android.view.View v = new android.view.View(this);
        final android.view.GestureDetector gd = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                        if (e1 == null) return false;
                        float dy = e2.getRawY() - e1.getRawY();
                        boolean ok = (top ? dy > SWIPE_MIN : dy < -SWIPE_MIN) && Math.abs(vy) > SWIPE_VEL;
                        String g = "swipe from the " + (top ? "top" : "bottom") + " edge (" + Math.round(Math.abs(dy)) + " px, speed "
                                + Math.round(Math.abs(vy)) + ")";
                        if (!ok) {
                            ClimateStore.event(ClimateGuard.this, "closing: " + g + " not recognised");
                            return false;
                        }
                        edgeBackAt = SystemClock.elapsedRealtime();
                        boolean done = performGlobalAction(GLOBAL_ACTION_BACK);
                        ClimateStore.event(ClimateGuard.this, "closing: " + g + " → back " + (done ? "sent" : "failed"));
                        if (!done) {
                            edgeBackAt = 0;
                            try {
                                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    }
                });
        v.setOnTouchListener((view, ev) -> {
            gd.onTouchEvent(ev);
            return true;
        });
        wm.addView(v, lp);
        return v;
    }

    private void edgesOff() {
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        for (android.view.View v : new android.view.View[]{edgeTop, edgeBottom}) {
            if (v == null) continue;
            try {
                wm.removeView(v);
            } catch (Throwable ignored) {
            }
        }
        edgeTop = edgeBottom = null;
    }

    // ---- exclusive steering wheel keys (accessibility key filter) -----------------------------------------------------
    // (as MG4 Control) the filter stays on while the joystick commands are on, and a key is consumed ONLY if the
    // climate screen is in front and the key is one of the paired ones. Consumed here, the "hardkey.report" broadcast
    // is not sent and the SystemUI does not change volume or track. Outside the screen every key passes. If the app
    // crashes the filter disappears with the service. A key taken on press is kept until it is released (even if the
    // screen closes meanwhile): the system never gets a release without its press. Every error lets the key through.

    interface KeyListener {
        /**
         * Press (down) or release (up) of a key seen by the filter. For the press: true = key to consume.
         * The release of a taken key always arrives (the return value does not matter). Main thread, at once.
         */
        boolean onFilterKey(int keyCode, int scanCode, boolean down, int repeat);
    }

    private static volatile KeyListener keyListener;
    private static volatile boolean filterOn;
    private static int unknownLogged;
    /** Keys taken on press, with the time: press → release stay with us. */
    private final java.util.Map<Integer, Long> taken = new java.util.HashMap<>();
    private static final long TAKEN_MAX_MS = 15_000;   // "stuck" key (release lost): let go after 15 s

    /** Climate state read now (background thread; null if the service is not connected). */
    static HvacClient.State readState() {
        ClimateGuard g = instance;
        if (g == null || g.hvac == null) return null;
        return g.hvac.readNow();
    }

    static boolean keyFilterOn() {
        return filterOn;
    }

    // ---- keys seen by the filter (diagnostics) and apps that can take the same keys -------------------------------
    private static final java.util.ArrayDeque<String> seenKeys = new java.util.ArrayDeque<>();
    private static int seenCount;

    /** Last keys seen by the filter with the screen open: keycode, scancode, source, device. */
    private static void seen(android.view.KeyEvent e) {
        try {
            seenCount++;
            android.view.InputDevice d = e.getDevice();
            String line = ClimateStore.ms(System.currentTimeMillis()) + " keycode " + e.getKeyCode() + " scancode " + e.getScanCode()
                    + " source 0x" + Integer.toHexString(e.getSource()) + " device " + e.getDeviceId()
                    + (d != null ? " \"" + d.getName() + "\"" : "");
            synchronized (seenKeys) {
                seenKeys.addFirst(line);
                while (seenKeys.size() > 12) seenKeys.removeLast();
            }
        } catch (Throwable ignored) {
        }
    }

    /** joystick.filterKeys line for the diagnostics. */
    static String seenText() {
        synchronized (seenKeys) {
            if (seenKeys.isEmpty()) return "no key seen with the screen open";
            return seenCount + " keys seen, last: " + String.join(" | ", seenKeys);
        }
    }

    /** Known apps that can take the steering wheel keys before us or together with us. */
    private static final String[] KNOWN_CONFLICTS = {"com.mg4.control", "com.mg4.control.offline", "com.shihab.diplay"};

    /**
     * Apps that can take the same keys: the known apps (with their service on) and the other accessibility services
     * with the key filter. Visible name and package; "" if none.
     */
    static String conflictApps(Context c) {
        java.util.LinkedHashSet<String> pk = new java.util.LinkedHashSet<>();
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager) c.getSystemService(ACCESSIBILITY_SERVICE);
            for (android.accessibilityservice.AccessibilityServiceInfo i : am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (i == null || i.getResolveInfo() == null || i.getResolveInfo().serviceInfo == null) continue;
                String p = i.getResolveInfo().serviceInfo.packageName;
                if (c.getPackageName().equals(p)) continue;
                boolean known = java.util.Arrays.asList(KNOWN_CONFLICTS).contains(p);
                boolean filters = (i.getCapabilities() & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0;
                if (known || filters) pk.add(p);
            }
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (String p : pk) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(appName(c, p)).append(" (").append(p).append(')');
        }
        return sb.toString();
    }

    static String appName(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** Who gets the keys: the screen in onResume, null in onPause. The filter is not turned off. */
    static void keyListener(KeyListener l) {
        keyListener = l;
    }

    /**
     * Turns the filter on or off according to the "Joystick commands" setting (at start, when it changes and every
     * time the screen opens). It checks the real flag of the service, not only our variable: if the system lost it
     * (service restart, update) it is set again and logged.
     */
    static void refreshFilter(Context c) {
        ClimateGuard g = instance;
        if (g != null) g.applyFilter(ClimateStore.joystick(c) != ClimateStore.JOY_OFF);
    }

    /** The FLAG_REQUEST_FILTER_KEY_EVENTS flag is really on in the connected service (read back from the system). */
    static boolean filterVerified() {
        ClimateGuard g = instance;
        if (g == null) return false;
        try {
            android.accessibilityservice.AccessibilityServiceInfo i = g.getServiceInfo();
            return i != null && (i.flags & android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyFilter(boolean on) {
        if (on == filterOn && on == filterVerified()) return;
        if (on == filterOn) ClimateStore.event(this, "joystick: key filter flag lost by the system, setting it again");
        try {
            android.accessibilityservice.AccessibilityServiceInfo i = getServiceInfo();
            if (i == null) return;
            if (on) i.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            else i.flags &= ~android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(i);
            filterOn = on;
            if (!on) taken.clear();
            boolean really = filterVerified();
            ClimateStore.event(this, "joystick: key filter " + (on ? "on (consumes only with the climate screen in front)" : "off")
                    + " · flag in the service: " + (really ? "yes" : "no") + otherFilters(this));
        } catch (Throwable t) {
            filterOn = false;
            ClimateStore.event(this, "joystick: key filter not turned " + (on ? "on " : "off ") + t);
        }
    }

    /**
     * Other active accessibility services that can filter the keys (e.g. MG4 Control): if they remap the same
     * joystick keys, whichever sees them first takes them. "" if there are none.
     */
    static String otherFilters(Context c) {
        String o = conflictApps(c);
        return o.isEmpty() ? "" : " · other services that can take the keys: " + o;
    }

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent e) {
        try {
            if (!filterOn || e == null) return false;
            int code = e.getKeyCode(), a = e.getAction();
            long now = SystemClock.uptimeMillis();
            // follow-up of a key taken on press: repeats and release stay with us
            Long since = taken.get(code);
            if (since != null && !(a == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0)) {
                if (now - since > TAKEN_MAX_MS) {
                    taken.remove(code);
                    return false;
                }
                if (a == android.view.KeyEvent.ACTION_UP) {
                    taken.remove(code);
                    KeyListener l = keyListener;
                    if (l != null) l.onFilterKey(code, e.getScanCode(), false, 0);
                }
                return true;
            }
            taken.remove(code);
            if (a != android.view.KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return false;
            // the press is taken only with the climate screen in front (the screen in onResume is enough: the app in
            // front read from the window events could be late)
            KeyListener l = keyListener;
            if (l == null || !ClimateActivity.resumed) return false;
            seen(e);
            boolean used = l.onFilterKey(code, e.getScanCode(), true, 0);
            if (used) taken.put(code, now);
            else if (unknownLogged < 15) {
                unknownLogged++;
                ClimateStore.event(this, "joystick: the filter sees keycode " + code + " scancode " + e.getScanCode()
                        + " (not paired: left to the system)");
            }
            return used;
        } catch (Throwable t) {
            return false;   // an error must never take a steering wheel key
        }
    }

    // ---- ready window against the flash of the original app ----------------------------------------------------------
    // The event arrives ~60 ms after the original appears, but the real screen takes 350–1100 ms to open. The service
    // keeps a ClimateView ready (same settings, last state read) and puts it on top at once, as an accessibility
    // window that takes no touches, in the same position as the screen. It is removed as soon as the real screen is
    // drawn, if another app comes to the front, or after 3 s at the latest. The original app is not touched.

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private ClimateView coverView;
    private android.view.View cover;
    private long coverAt, coverOriginalAt;
    /** Last state read by the screen (same process): the ready window starts from here. */
    static volatile HvacClient.State lastState;

    private void prepareCover() {
        if (coverView != null || !ClimateStore.quickCover(this)) return;
        try {
            com.mg4.launcher.L.init(this, new com.mg4.launcher.ui.Prefs(this).language());
            ClimateView v = new ClimateView(this);
            v.setAnimated(false);
            // an empty draw right away: loads images and fonts, so the first draw on opening is fast
            int[] r = ClimateStore.screenRect(this);
            int w = r != null ? r[2] : 1920, h = r != null ? r[3] : 720;
            v.setDashStyle(ClimateStore.dashStyle(this));
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
            v.layout(0, 0, w, h);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            v.draw(new android.graphics.Canvas(b));
            b.recycle();
            coverView = v;
        } catch (Throwable t) {
            ClimateStore.event(this, "ready window not prepared: " + t);
        }
    }

    private void showCover(long originalAt) {
        if (!ClimateStore.quickCover(this)) return;
        int[] r = ClimateStore.screenRect(this);
        if (r == null) {
            ClimateStore.event(this, "ready window: screen size not known yet (next time it will be)");
            return;
        }
        try {
            prepareCover();
            ClimateView v = coverView;
            if (v == null) return;
            hideCover(null);
            v.setRightHand(ClimateStore.rightHand(this));
            v.setDashStyle(ClimateStore.dashStyle(this));
            v.setDefrostTiles(ClimateStore.defrostTiles(this));
            v.setShowLabels(ClimateStore.showLabels(this));
            v.theme();
            HvacClient.State st = lastState;
            if (st != null) v.setState(st);
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(r[2], r[3],
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.OPAQUE);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            lp.x = r[0];
            lp.y = r[1];
            lp.windowAnimations = 0;
            lp.setTitle("MG4 climate ready window");
            wm.addView(v, lp);
            cover = v;
            coverAt = System.currentTimeMillis();
            coverOriginalAt = originalAt;
            ClimateStore.event(this, "ready window shown at " + ClimateStore.ms(coverAt) + " (+" + Math.max(0, coverAt - originalAt)
                    + " ms after the original appeared)");
            main.postDelayed(coverTimeout, 3000);
            // state updated as soon as it is read (in the background), so the ready window shows the current one
            if (bg != null && hvac != null) bg.post(() -> {
                try {
                    HvacClient.State s2 = hvac.readNow();
                    if (s2 != null && s2.connected) main.post(() -> {
                        if (cover != null) coverView.setState(s2);
                    });
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            cover = null;
            ClimateStore.event(this, "ready window not shown: " + t);
        }
    }

    private final Runnable coverTimeout = () -> hideCover("after 3 s");

    /** The real screen is on the display: the ready window goes (after one frame, so no gap is seen). */
    static void screenReady() {
        ClimateGuard g = instance;
        if (g == null || g.cover == null) return;
        g.main.postDelayed(() -> g.hideCover("real screen open"), 60);
    }

    private void hideCover(String why) {
        main.removeCallbacks(coverTimeout);
        android.view.View v = cover;
        cover = null;
        if (v == null) return;
        try {
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v);
        } catch (Throwable ignored) {
        }
        if (why != null) {
            long now = System.currentTimeMillis();
            ClimateStore.event(this, "ready window removed (" + why + ") after " + (now - coverAt) + " ms · original covered from +"
                    + Math.max(0, coverAt - coverOriginalAt) + " ms");
        }
    }

    @Override
    public void onInterrupt() {
    }
}
