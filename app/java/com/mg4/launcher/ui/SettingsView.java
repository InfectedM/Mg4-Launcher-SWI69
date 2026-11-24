package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.svc.CarFeatures;
import com.mg4.launcher.svc.Diag;
import com.mg4.launcher.svc.Updater;

/**
 * Full-screen settings: section menu on the left (on the right with right-hand drive), section content
 * on the other side, version and update status always visible at the bottom of the menu.
 * Switches and buttons follow "Dimensione comandi" (Ui.K).
 */
public final class SettingsView extends FrameLayout {

    public interface Host {
        void closeSettings();

        void settingsChanged(boolean rebuild);

        void openOriginalHome();

        Updater updater();


        /** The language changed: rebuild the whole interface. */
        void languageChanged();

        /** List of installed apps (excluding us), to pick "another app" for the 360 key. */
        java.util.List<com.mg4.launcher.apps.AppCatalog.App> allLaunchableApps();

        /** 1.0.4: installed apps that handle navigation (for "App di navigazione"). */
        java.util.List<com.mg4.launcher.apps.AppCatalog.App> navCandidateApps();

        /** 1.0.4: test of the charging animation on the home. */
        void testChargeAnimation();
    }

    // 0.4.0 (point 11): settings reorganized as in the SWI68 - same 7 sections, order and texts
    // Windows (fork): new section right after Car
    public static final int SEC_CAR = 0, SEC_WINDOWS = 1, SEC_HOME = 2, SEC_BAR = 3, SEC_LOGOS = 4, SEC_LOOK = 5, SEC_LAUNCH = 6, SEC_SYSTEM = 7;

    /** Section to show at the next opening (e.g. System from the updates popup). */
    public static void openAt(int s) {
        section = s;
    }
    private static final int[] SEC_ICON = {R.drawable.ic_car, R.drawable.ic_window, R.drawable.ic_home, R.drawable.ic_buttons, R.drawable.ic_radio, R.drawable.ic_palette, R.drawable.ic_play, R.drawable.ic_gear};
    private static final int[] SEC_NAME = {R.string.sec_car, R.string.sec_windows, R.string.sec_home, R.string.sec_bar, R.string.sec_logos, R.string.sec_look, R.string.sec_launch, R.string.sec_system};
    private static final int[] SEC_SUB = {R.string.sec_car_sub, R.string.sec_windows_sub, R.string.sec_home_sub, R.string.sec_bar_sub, R.string.sec_logos_sub, R.string.sec_look_sub, R.string.sec_launch_sub, R.string.sec_system_sub};
    /** Space at the top left for the system lights indicator (as in the home). */
    private final int SYSTEM_LIGHT_INSET;
    private static final long QUIET_CHECK_MS = 30 * 60_000L;

    /** Open section: stays the same when the screen is rebuilt (theme, language, size…). */
    private static int section = SEC_CAR;
    /** Last result of the update check, shared between openings. */
    private static Updater.Release lastRelease;
    private static long lastQuietCheck;
    private static String lastStatus;

    private final Theme t;
    private final Prefs prefs;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean rhd, sysIndicator;

    private final LinearLayout menuItems;
    private final FrameLayout footer;
    private final LinearLayout content;
    private boolean diagOpen;

    // views updated continuously
    private TextView diagText, logSaveSub, updSub;
    private View logSaveBtn, updCheckBtn, updInstallBtn;

    public SettingsView(Context c, Theme t, Prefs prefs, Host host) {
        super(c);
        this.t = t;
        this.prefs = prefs;
        this.host = host;
        boolean carRhd = CarFeatures.isRightHandDrive(c);
        String side = prefs.driveSide();
        rhd = Prefs.DRIVE_RIGHT.equals(side) || (Prefs.DRIVE_AUTO.equals(side) && carRhd);
        sysIndicator = CarFeatures.hasSystemLightIndicator(c) || carRhd;
        SYSTEM_LIGHT_INSET = CarFeatures.systemLightInset(c);

        setBackgroundColor(t.bg);
        setClickable(true);

        LinearLayout card = Ui.row(c);
        card.setGravity(Gravity.NO_GRAVITY);
        card.setBackground(Ui.round(t.panel, dp(28)));
        card.setClipToOutline(true);
        LayoutParams clp = new LayoutParams(-1, -1);
        clp.setMargins(dp(20), dp(20), dp(20), dp(20));
        addView(card, clp);

        // --- section menu ---
        LinearLayout menu = Ui.column(c);
        final int menuBg = t.day ? 0xFFF6F7F9 : 0xFF111317;
        menu.setBackgroundColor(menuBg);
        menu.setPadding(dp(24), dp(30), dp(24), dp(24));
        TextView title = Ui.text(c, L.s(R.string.set_menu_title), 32, t.text, Ui.MEDIUM);
        title.setPadding(dp(12) + (sysIndicator && !rhd ? dp(SYSTEM_LIGHT_INSET) : 0), 0, dp(12), dp(26));
        menu.addView(title);
        // 0.4.0 (7 sections): the menu goes in a ScrollView, otherwise on screens/many sections System and the
        // updates box end up off screen (bug of SWI68 1.0.0, fixed in 1.0.1).
        // fork: with 8 sections the last ones are out of view; the edge fade shows that the list continues.
        // opaque getSolidColor = fade painted with a simple gradient (no saveLayer, cheap on the MT2712)
        ScrollView menuScroll = new ScrollView(c) {
            @Override
            public int getSolidColor() {
                return menuBg;
            }
        };
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        menuScroll.setVerticalFadingEdgeEnabled(true);
        menuScroll.setFadingEdgeLength(dp(40));
        menuItems = Ui.column(c);
        menuScroll.addView(menuItems, new FrameLayout.LayoutParams(-1, -2));
        menu.addView(menuScroll, Ui.lpWeight(-1, 0, 1));
        footer = new FrameLayout(c);
        menu.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        View sep = new View(c);
        sep.setBackgroundColor(t.track);

        // --- content ---
        content = Ui.column(c);
        int insetLeft = sysIndicator && rhd ? dp(SYSTEM_LIGHT_INSET) : 0;
        content.setPadding(dp(44) + insetLeft, dp(34), dp(44), dp(30));

        if (!rhd) {
            card.addView(menu, new LinearLayout.LayoutParams(dp(440), -1));
            card.addView(sep, new LinearLayout.LayoutParams(dp(1), -1));
            card.addView(content, Ui.lpWeight(0, -1, 1));
        } else {
            card.addView(content, Ui.lpWeight(0, -1, 1));
            card.addView(sep, new LinearLayout.LayoutParams(dp(1), -1));
            card.addView(menu, new LinearLayout.LayoutParams(dp(440), -1));
        }

        Updater up = host.updater();
        up.setListener((text, r, busy) -> {
            if (r != null) lastRelease = r;
            lastStatus = text;
            refreshUpdateViews(busy);
        });
        if (SystemClock.elapsedRealtime() - lastQuietCheck > QUIET_CHECK_MS || lastQuietCheck == 0) {
            lastQuietCheck = SystemClock.elapsedRealtime();
            up.checkSilent(prefs.updateRepo(), prefs.githubToken(), r -> {
                if (r != null) lastRelease = r;
                refreshUpdateViews(up.isBusy());
            });
        }

        buildMenu();
        buildFooter(up.isBusy());
        showSection(section);
        // fork: when opening on a section at the bottom (e.g. System from the update notice) the menu scrolls to it
        menuScroll.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int tp, int r, int b, int ol, int ot, int or, int ob) {
                View sel = menuItems.getChildAt(section);
                if (sel == null || sel.getHeight() == 0) return;
                menuScroll.removeOnLayoutChangeListener(this);
                int h = menuScroll.getHeight();
                if (sel.getBottom() > h) menuScroll.scrollTo(0, sel.getBottom() - h + dp(24));
            }
        });
    }

    // =========================================================================================
    // Menu
    // =========================================================================================

    private void buildMenu() {
        Context c = getContext();
        menuItems.removeAllViews();
        for (int i = 0; i < SEC_NAME.length; i++) {
            final int idx = i;
            boolean on = i == section;
            FrameLayout item = new FrameLayout(c);
            item.setBackground(Ui.pressable(c, on ? t.chip : 0x00000000, 20, t));
            item.setClickable(true);
            item.setOnClickListener(v -> {
                if (section == idx && !diagOpen) return;
                section = idx;
                diagOpen = false;
                curScroll = null;      // new section: start at the top
                keptSection = -1;
                buildMenu();
                showSection(idx);
            });
            if (on) {
                View bar = new View(c);
                bar.setBackground(Ui.round(t.text, dp(3)));
                item.addView(bar, new LayoutParams(dp(5), dp(44), Gravity.CENTER_VERTICAL | (rhd ? Gravity.RIGHT : Gravity.LEFT)));
            }
            LinearLayout r = Ui.row(c);
            r.setPadding(dp(20), 0, dp(20), 0);
            FrameLayout box = new FrameLayout(c);
            box.setBackground(Ui.round(on ? t.text : t.chip, dp(16)));
            box.addView(Ui.icon(c, SEC_ICON[i], on ? t.panel : t.text, 28), new LayoutParams(dp(28), dp(28), Gravity.CENTER));
            r.addView(box, Ui.lp(dp(52), dp(52)));
            LinearLayout tx = Ui.column(c);
            tx.addView(Ui.oneLine(Ui.text(c, L.s(SEC_NAME[i]), 23, t.text, on ? Ui.MEDIUM : Ui.REGULAR)));
            TextView sub = Ui.oneLine(Ui.text(c, L.s(SEC_SUB[i]), 15, t.sub, Ui.REGULAR));
            tx.addView(sub, Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(4), 0, 0));
            r.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(18), 0, 0, 0));
            item.addView(r, new LayoutParams(-1, -1));
            item.setContentDescription(L.s(SEC_NAME[i]));
            menuItems.addView(item, Ui.margins(new LinearLayout.LayoutParams(-1, dp(76)), 0, 0, 0, dp(8)));
        }
    }

    /** Box at the bottom of the menu: installed version, or new version with "Installa". */
    private void buildFooter(boolean busy) {
        Context c = getContext();
        footer.removeAllViews();
        Updater up = host.updater();
        Updater.Release r = lastRelease;
        boolean newer = r != null && r.newer && r.apkUrl != null;
        LinearLayout box = Ui.row(c);
        box.setPadding(dp(18), dp(14), dp(14), dp(14));
        box.setMinimumHeight(dp(70));
        if (newer) {
            box.setBackground(Ui.round(t.text, dp(20)));
            box.addView(Ui.icon(c, R.drawable.ic_download, t.panel, 28));
            LinearLayout tx = Ui.column(c);
            tx.addView(Ui.oneLine(Ui.text(c, L.f(R.string.upd_new_short, r.tag), 18, t.panel, Ui.MEDIUM)));
            String sub = busy && lastStatus != null ? lastStatus : L.f(R.string.upd_installed_ver, up.currentVersion());
            tx.addView(Ui.oneLine(Ui.text(c, sub, 14, t.panel, Ui.REGULAR)), Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(4), 0, 0));
            tx.getChildAt(1).setAlpha(0.75f);
            box.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(14), 0, dp(10), 0));
            TextView inst = Ui.text(c, L.s(R.string.btn_install), Ui.ts(18), t.text, Ui.MEDIUM);
            inst.setGravity(Gravity.CENTER);
            inst.setPadding(tp(20), 0, tp(20), 0);
            inst.setBackground(Ui.pressable(c, t.panel, 14 * Ui.K, t));
            inst.setClickable(!busy);
            inst.setAlpha(busy ? 0.5f : 1f);
            inst.setOnClickListener(v -> up.install(r, prefs.githubToken()));
            box.addView(inst, Ui.lp(-2, tp(52)));
        } else {
            box.setBackground(Ui.round(t.chip, dp(20)));
            boolean known = r != null;
            box.addView(Ui.icon(c, known ? R.drawable.ic_check : R.drawable.ic_info, known ? t.battery : t.sub, 26));
            LinearLayout tx = Ui.column(c);
            tx.addView(Ui.oneLine(Ui.text(c, "MG4 Launcher " + up.currentVersion(), 18, t.text, Ui.MEDIUM)));
            String sub = busy && lastStatus != null ? lastStatus : L.s(known ? R.string.upd_current : R.string.upd_unknown);
            tx.addView(Ui.oneLine(Ui.text(c, sub, 14, t.sub, Ui.REGULAR)), Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(4), 0, 0));
            box.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(14), 0, 0, 0));
        }
        footer.addView(box, new LayoutParams(-1, -2));
    }

    private void refreshUpdateViews(boolean busy) {
        buildFooter(busy);
        if (updSub != null) {
            Context c = getContext();
            updSub.setVisibility(VISIBLE);
            updSub.setText(lastStatus != null ? lastStatus
                    : lastRelease == null ? L.s(R.string.upd_unknown)
                    : lastRelease.newer ? L.f(R.string.upd_new, lastRelease.tag)
                    : L.s(R.string.upd_current));
            updCheckBtn.setEnabled(!busy);
            updCheckBtn.setAlpha(busy ? 0.5f : 1f);
            boolean canInstall = !busy && lastRelease != null && lastRelease.newer && lastRelease.apkUrl != null;
            updInstallBtn.setVisibility(canInstall ? VISIBLE : GONE);
        }
    }

    // =========================================================================================
    // Section content
    // =========================================================================================

    /**
     * 1.0.4 (issue #33): page position. Changing an option rebuilds the section: the scroll is put back
     * where it was. Static because some options (theme, size) recreate all the settings: in that case
     * it applies if the rebuild comes within a few seconds. Changing section from the menu always starts at the top.
     */
    private static int keptSection = -1, keptY;
    private static long keptAt;
    private ScrollView curScroll;
    private int curKey = -1;

    private void showSection(int s) {
        Context c = getContext();
        int key = s + (diagOpen ? 100 : 0);
        int keep = 0;
        if (curScroll != null && curKey == key) keep = curScroll.getScrollY();
        else if (curScroll == null && keptSection == key && SystemClock.uptimeMillis() - keptAt < 5000) keep = keptY;
        curScroll = null;
        curKey = key;
        content.removeAllViews();
        diagText = null;
        logSaveSub = null;
        updSub = null;

        // header: title + close
        LinearLayout head = Ui.row(c);
        if (diagOpen) {
            head.addView(Ui.iconButton(c, t, R.drawable.ic_chev_left, t.text, t.chip, 64, 30, 32, L.s(R.string.btn_back), v -> {
                diagOpen = false;
                showSection(section);
            }), Ui.margins(Ui.lp(tp(64), tp(64)), 0, 0, dp(20), 0));
        }
        head.addView(Ui.oneLine(Ui.text(c, L.s(diagOpen ? R.string.set_diag_title : SEC_NAME[s]), 34, t.text, Ui.MEDIUM)), Ui.lpWeight(0, -2, 1));
        head.addView(Ui.iconButton(c, t, R.drawable.ic_close, t.text, t.chip, 64, 30, 32, L.s(R.string.close), v -> host.closeSettings()));
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (diagOpen) {
            buildDiag(c);
            refreshTick.run();
            return;
        }

        // fork: fade on the content too (the choices were cut off sharply at the bottom of the card)
        ScrollView sv = new ScrollView(c) {
            @Override
            public int getSolidColor() {
                return t.panel;
            }
        };
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dp(32));
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        curScroll = sv;
        // 0.4.4 (fix A, issue #33): the position is no longer saved while scrolling (it held for 5 s and whoever stayed
        // still longer went back to the top): it is saved when the old settings close (onDetachedFromWindow)
        if (keep > 0) {
            final int y0 = keep;
            // after layout (before, the page has height 0 and the scroll would be reset), without animation
            sv.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int tp, int r, int b, int ol, int ot, int or, int ob) {
                    if (sv.getChildCount() == 0 || sv.getChildAt(0).getHeight() == 0) return;
                    sv.removeOnLayoutChangeListener(this);
                    sv.scrollTo(0, y0);
                }
            });
        }
        LinearLayout col = Ui.column(c);
        col.setPadding(0, dp(26), 0, 0);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        content.addView(sv, Ui.lpWeight(-1, 0, 1));

        switch (s) {
            case SEC_CAR: buildCar(c, col); break;
            case SEC_WINDOWS: buildWindows(c, col); break;
            case SEC_HOME: buildHome(c, col); break;
            case SEC_BAR: buildBar(c, col); break;
            case SEC_LOGOS: buildLogos(c, col); break;
            case SEC_LOOK: buildLook(c, col); break;
            case SEC_LAUNCH: buildLaunch(c, col); break;
            default: buildSystem(c, col); break;
        }
        refreshTick.run();
    }

    private void buildCar(Context c, LinearLayout col) {
        col.addView(label(c, L.s(R.string.set_car_color)));
        LinearLayout sw = Ui.row(c);
        int sel = prefs.carColorIndex();
        for (int i = 0; i < Prefs.CAR_COLORS.length; i++) {
            final int idx = i;
            sw.addView(swatch(c, Prefs.CAR_COLOR_VALUES[i], i == sel, Prefs.CAR_COLORS[i], v -> {
                prefs.setCarColorIndex(idx);
                // 1.0.4: with the new colour its pack is downloaded and the old one deleted
                if (prefs.chargeAnim()) com.mg4.launcher.svc.ChargePack.ensure(c, Prefs.CAR_COLORS[idx], true);
                host.settingsChanged(false);
                showSection(SEC_CAR);
            }), Ui.margins(Ui.lp(tp(72), tp(72)), 0, 0, dp(16), 0));
        }
        col.addView(sw, top(14));
        col.addView(Ui.text(c, Prefs.CAR_COLORS[sel], 18, t.sub, Ui.REGULAR), top(14));
        // 1.0.4 (point F): charging animation (pack of the chosen colour, downloaded separately)
        LinearLayout animCard = card(c);
        animCard.addView(row(c, L.s(R.string.set_charge_anim), L.s(R.string.set_charge_anim_sub),
                toggle(c, prefs.chargeAnim(), on -> {
                    prefs.setChargeAnim(on);
                    if (on) com.mg4.launcher.svc.ChargePack.ensure(c, Prefs.CAR_COLORS[prefs.carColorIndex()], true);
                    host.settingsChanged(false);
                }), true));
        col.addView(animCard, top(14));

        // rims (0.9.3): thumbnails like the colours, the photo's ones are "stock"
        col.addView(label(c, L.s(R.string.set_wheels).toUpperCase(java.util.Locale.getDefault())), top(30));
        LinearLayout wr = Ui.row(c);
        int wsel = prefs.wheelIndex();
        for (int i = 0; i < Prefs.WHEEL_RES.length; i++) {
            final int idx = i;
            wr.addView(wheelThumb(c, i, i == wsel, v -> {
                prefs.setWheelIndex(idx);
                host.settingsChanged(false);
                showSection(SEC_CAR);
            }), Ui.margins(Ui.lp(tp(92), tp(92)), 0, 0, dp(18), 0));
        }
        col.addView(wr, top(14));
        col.addView(Ui.text(c, L.s(Prefs.WHEEL_NAMES[wsel]), 18, t.sub, Ui.REGULAR), top(14));

        col.addView(label(c, L.s(R.string.set_bodywork)), top(30));
        LinearLayout card = card(c);
        card.addView(row(c, L.s(R.string.set_black_roof), L.s(R.string.set_black_roof_sub),
                toggle(c, prefs.blackRoof(), on -> {
                    prefs.setBlackRoof(on);
                    host.settingsChanged(false);
                    showSection(SEC_CAR);
                }), true));
        col.addView(card, topFull(14));

        // car preview with chosen colour and roof
        CarView car = new CarView(c);
        car.setLook(Prefs.CAR_COLOR_VALUES[sel], prefs.blackRoof(), prefs.wheelIndex(), t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(520), dp(230));
        lp.gravity = rhd ? Gravity.LEFT : Gravity.RIGHT;
        lp.topMargin = dp(10);
        col.addView(car, lp);

        // 0.4.0 (point 11): driving side, moved here from "Avvio e app"
        col.addView(label(c, L.s(R.string.set_drive_side)), top(30));
        boolean carRight2 = CarFeatures.isRightHandDrive(c);
        String[][] sides = {
                {Prefs.DRIVE_AUTO, L.s(R.string.drive_auto) + " · " + L.s(carRight2 ? R.string.drive_right : R.string.drive_left)},
                {Prefs.DRIVE_LEFT, L.s(R.string.drive_left)},
                {Prefs.DRIVE_RIGHT, L.s(R.string.drive_right)}};
        Flow fd = new Flow(c);
        for (String[] m : sides) {
            fd.addView(choice(c, m[1], m[0].equals(prefs.driveSide()), v -> {
                if (m[0].equals(prefs.driveSide())) return;
                prefs.setDriveSide(m[0]);
                host.settingsChanged(true);   // rebuilds the home (and the settings) in the new layout
            }));
        }
        col.addView(fd, topFull(14));
        col.addView(Ui.text(c, L.s(R.string.drive_hint), 17, t.sub, Ui.REGULAR), top(14));
    }

    /** Windows: automatic closing when leaving the car (WinClose functions) and manual closing. */
    private void buildWindows(Context c, LinearLayout col) {
        com.mg4.launcher.svc.Windows w = com.mg4.launcher.svc.Windows.get(c);
        LinearLayout main = card(c);
        main.addView(row(c, L.s(R.string.win_auto), L.s(R.string.win_auto_sub), toggle(c, prefs.winAuto(), on -> {
            prefs.setWinAuto(on);
            w.wake();
            showSection(SEC_WINDOWS);
        }), false));
        TextView status = Ui.text(c, w.statusText(), 17, t.sub, Ui.REGULAR);
        main.addView(row(c, L.s(R.string.win_status_title), "", status, false));
        main.addView(row(c, L.s(R.string.win_close_now), L.s(R.string.win_close_now_sub), button(c, L.s(R.string.win_close_btn), v -> {
            if (!w.closeNow()) android.widget.Toast.makeText(c, L.s(R.string.win_status_off), android.widget.Toast.LENGTH_LONG).show();
            status.setText(w.statusText());
        }), true));
        col.addView(main, topFull(0));
        // state updated while the section is open
        Runnable upd = new Runnable() {
            @Override
            public void run() {
                if (!status.isAttachedToWindow()) return;
                status.setText(w.statusText());
                status.postDelayed(this, 1000);
            }
        };
        status.postDelayed(upd, 1000);

        col.addView(label(c, L.s(R.string.win_arm_label)), top(30));
        col.addView(Ui.text(c, L.s(R.string.win_speed), 18, t.text, Ui.REGULAR), top(14));
        col.addView(numberChoices(c, Prefs.WIN_SPEEDS, prefs.winSpeed(), " km/h", v -> prefs.setWinSpeed(v)), topFull(10));
        col.addView(Ui.text(c, L.s(R.string.win_time), 18, t.text, Ui.REGULAR), top(20));
        col.addView(numberChoices(c, Prefs.WIN_MINUTES, prefs.winMinutes(), " min", v -> prefs.setWinMinutes(v)), topFull(10));

        col.addView(label(c, L.s(R.string.win_trigger_label)), top(30));
        col.addView(Ui.text(c, L.s(R.string.win_trigger), 18, t.text, Ui.REGULAR), top(14));
        Flow ft = new Flow(c);
        ft.addView(choice(c, L.s(R.string.win_trigger_open), prefs.winOnDoorOpen(), v -> {
            prefs.setWinOnDoorOpen(true);
            showSection(SEC_WINDOWS);
        }));
        ft.addView(choice(c, L.s(R.string.win_trigger_close), !prefs.winOnDoorOpen(), v -> {
            prefs.setWinOnDoorOpen(false);
            showSection(SEC_WINDOWS);
        }));
        col.addView(ft, topFull(10));
        col.addView(Ui.text(c, L.s(R.string.win_delay), 18, t.text, Ui.REGULAR), top(20));
        col.addView(numberChoices(c, Prefs.WIN_DELAYS, prefs.winDelay(), " s", v -> prefs.setWinDelay(v)), topFull(10));
        LinearLayout beep = card(c);
        beep.addView(row(c, L.s(R.string.win_beep), L.s(R.string.win_beep_sub), toggle(c, prefs.winBeep(), on -> {
            prefs.setWinBeep(on);
            showSection(SEC_WINDOWS);
        }), !prefs.winBeep()));
        if (prefs.winBeep()) {
            beep.addView(row(c, L.s(R.string.win_beep_volume), "", numberChoicesInCard(c, Prefs.WIN_VOLUMES, prefs.winBeepVolume(), " %",
                    v -> prefs.setWinBeepVolume(v)), true));
        }
        col.addView(beep, topFull(20));

        col.addView(label(c, L.s(R.string.win_modes_label)), top(30));
        int[] names = {R.string.win_fl, R.string.win_fr, R.string.win_rl, R.string.win_rr};
        String[] modes = {L.s(R.string.win_mode_auto), L.s(R.string.win_mode_pulse), L.s(R.string.win_mode_off)};
        LinearLayout mc = card(c);
        for (int a = 0; a < 4; a++) {
            final int area = a;
            mc.addView(row(c, L.s(names[a]), "", choices(c, modes, prefs.winMode(a), idx -> {
                prefs.setWinMode(area, idx);
                showSection(SEC_WINDOWS);
            }), a == 3));
        }
        col.addView(mc, topFull(14));
        col.addView(Ui.text(c, L.s(R.string.win_modes_hint), 17, t.sub, Ui.REGULAR), top(14));
        col.addView(Ui.text(c, L.s(R.string.win_cancel_hint), 17, t.sub, Ui.REGULAR), top(10));
    }

    private interface OnNumber {
        void chosen(int value);
    }

    /** Choice of a number from a list (speed, minutes, seconds): buttons like the Theme ones, outside the cards. */
    private View numberChoices(Context c, int[] values, int sel, String unit, OnNumber l) {
        Flow f = new Flow(c);
        for (int v : values) {
            f.addView(choice(c, v + unit, v == sel, x -> {
                if (v == sel) return;
                l.chosen(v);
                showSection(SEC_WINDOWS);
            }));
        }
        return f;
    }

    /** Same, inside a card (beep volume). */
    private LinearLayout numberChoicesInCard(Context c, int[] values, int sel, String unit, OnNumber l) {
        String[] names = new String[values.length];
        int idx = -1;
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i] + unit;
            if (values[i] == sel) idx = i;
        }
        return choices(c, names, idx, i -> {
            l.chosen(values[i]);
            showSection(SEC_WINDOWS);
        });
    }

    private void buildHome(Context c, LinearLayout col) {
        col.addView(label(c, L.s(R.string.set_home_card)));
        LinearLayout card = card(c);
        card.addView(row(c, L.s(R.string.set_show_date), L.s(R.string.set_show_date_sub),
                toggle(c, prefs.showDate(), on -> {
                    prefs.setShowDate(on);
                    host.settingsChanged(true);
                }), false));
        card.addView(row(c, L.s(R.string.set_soc_decimals), L.s(R.string.set_soc_decimals_sub),
                toggle(c, prefs.socDecimals(), on -> {
                    prefs.setSocDecimals(on);
                    host.settingsChanged(false);
                }), false));
        card.addView(row(c, L.s(R.string.set_soc_big), L.s(R.string.set_soc_big_sub),
                toggle(c, prefs.socBig(), on -> {
                    prefs.setSocBig(on);
                    host.settingsChanged(true);
                }), false));
        // 0.4.0 (point 2): distance unit (range and distances)
        String[] distVals = {Prefs.DIST_AUTO, Prefs.DIST_KM, Prefs.DIST_MI};
        String[] distNames = {L.s(R.string.unit_auto), "km", "mi"};
        int distSel = java.util.Arrays.asList(distVals).indexOf(prefs.distUnit());
        card.addView(row(c, L.s(R.string.set_dist_unit), L.s(R.string.set_dist_unit_sub),
                choices(c, distNames, distSel, i -> {
                    prefs.setDistUnit(distVals[i]);
                    host.settingsChanged(true);
                    showSection(SEC_HOME);
                }), false));
        card.addView(row(c, L.s(R.string.set_tpms_title), L.s(R.string.set_tpms_sub),
                toggle(c, prefs.showTpms(), on -> {
                    prefs.setShowTpms(on);
                    host.settingsChanged(true);
                    showSection(SEC_HOME);
                }), false));
        if (prefs.showTpms()) {
            String[] units = {Prefs.TYRE_AUTO, Prefs.TYRE_BAR, Prefs.TYRE_KPA, Prefs.TYRE_PSI};
            String[] names = {L.s(R.string.unit_auto), "bar", "kPa", "psi"};
            int sel = java.util.Arrays.asList(units).indexOf(prefs.tyreUnit());
            card.addView(row(c, L.s(R.string.set_tyre_unit), L.s(R.string.set_tyre_unit_sub),
                    choices(c, names, sel, i -> {
                        prefs.setTyreUnit(units[i]);
                        host.settingsChanged(false);
                        showSection(SEC_HOME);
                    }), false));
        }
        // 0.4.3 (point 3): inside temperature Automatic / Show / Hide; if the car does not provide it
        // the row is hidden (if it was on "Mostra" it stays, so one can always switch to Hide)
        if (!prefs.insideTempMissing() || Prefs.SHOW_ON.equals(prefs.insideTempMode())) {
            String[] inVals = {Prefs.SHOW_AUTO, Prefs.SHOW_ON, Prefs.SHOW_OFF};
            String[] inNames = {L.s(R.string.fog_auto), L.s(R.string.fog_show), L.s(R.string.fog_hide)};
            int inSel = java.util.Arrays.asList(inVals).indexOf(prefs.insideTempMode());
            card.addView(row(c, L.s(R.string.set_show_inside_temp), L.s(R.string.set_inside_sub),
                    choices(c, inNames, inSel, i -> {
                        prefs.setInsideTempMode(inVals[i]);
                        host.settingsChanged(true);
                        showSection(SEC_HOME);
                    }), false));
        }
        // 0.4.2 (point 7d): charge limit Automatic / Show / Hide
        String[] triVals = {Prefs.SHOW_AUTO, Prefs.SHOW_ON, Prefs.SHOW_OFF};
        String[] triNames = {L.s(R.string.fog_auto), L.s(R.string.fog_show), L.s(R.string.fog_hide)};
        card.addView(row(c, L.s(R.string.set_charge_limit), L.s(R.string.set_charge_limit_sub),
                choices(c, triNames, java.util.Arrays.asList(triVals).indexOf(prefs.chargeLimitMode()), i -> {
                    prefs.setChargeLimitMode(triVals[i]);
                    host.settingsChanged(false);
                    showSection(SEC_HOME);
                }), true));
        col.addView(card, topFull(14));

        // 1.0.0 (point 11): map/media/CarPlay/AA panel - the navigator opened from the home panel
        col.addView(label(c, L.s(R.string.set_nav_default)), top(30));
        card = card(c);
        // 0.4.4 (issue #27, from SWI68 1.0.4): navigation app. The SWI69 has no MG navigator: choose between
        // CarPlay/Android Auto, an app installed on the car or Automatic (phone if connected, otherwise the first navigator)
        String navPref = prefs.navApp();
        boolean navOther = !Prefs.NAV_AUTO.equals(navPref) && !Prefs.NAV_MG.equals(navPref) && !Prefs.NAV_PHONE.equals(navPref);
        String otherName = navOther ? com.mg4.launcher.ui.Cam360.label(c, navPref) : L.s(R.string.nav_other_app);
        String[] navNames = {L.s(R.string.nav_default_phone), otherName, L.s(R.string.unit_auto)};
        int navSel = navOther ? 1 : Prefs.NAV_PHONE.equals(navPref) ? 0 : 2;
        LinearLayout navRow = Ui.row(c);
        for (int i = 0; i < navNames.length; i++) {
            final int idx = i;
            TextView ch = choice(c, navNames[i], i == navSel, v -> {
                if (idx == 1) {   // always opens the app choice
                    pickNavApp(c);
                    return;
                }
                if (idx == navSel) return;
                prefs.setNavApp(idx == 0 ? Prefs.NAV_PHONE : Prefs.NAV_AUTO);
                host.settingsChanged(false);
                showSection(SEC_HOME);
            });
            ch.setPadding(tp(20), 0, tp(20), 0);
            if (i != navSel) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
            navRow.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        card.addView(row(c, L.s(R.string.set_nav_default), L.s(R.string.set_nav_default_sub), navRow, false));
        // 1.0.4 (issue #28): audio sources to show (at least one)
        // 0.4.4 (SWI69): "Online" only if the car really has the source (usually absent on the SWI69)
        boolean onl = com.mg4.launcher.svc.MediaClient.onlinePossible;
        int[] srcIds = onl ? new int[]{com.mg4.launcher.svc.MediaClient.SRC_RADIO, com.mg4.launcher.svc.MediaClient.SRC_USB,
                com.mg4.launcher.svc.MediaClient.SRC_BT, com.mg4.launcher.svc.MediaClient.SRC_ONLINE}
                : new int[]{com.mg4.launcher.svc.MediaClient.SRC_RADIO, com.mg4.launcher.svc.MediaClient.SRC_USB,
                com.mg4.launcher.svc.MediaClient.SRC_BT};
        String[] srcNames = onl ? new String[]{L.s(R.string.src_radio), "USB", "Bluetooth", L.s(R.string.src_online)}
                : new String[]{L.s(R.string.src_radio), "USB", "Bluetooth"};
        LinearLayout srcSel = Ui.row(c);
        for (int i = 0; i < srcIds.length; i++) {
            final int src = srcIds[i];
            boolean on = prefs.sourceShown(src);
            TextView ch = choice(c, srcNames[i], on, v -> {
                boolean now = prefs.sourceShown(src);
                if (now) {   // at least one source must remain
                    int left = 0;
                    for (int x : srcIds) if (prefs.sourceShown(x)) left++;
                    if (left <= 1) return;
                }
                prefs.setSourceShown(src, !now);
                com.mg4.launcher.svc.Diag.put("home.sources", sourcesTxt(srcIds, srcNames));
                host.settingsChanged(true);
                showSection(SEC_HOME);
            });
            ch.setPadding(tp(20), 0, tp(20), 0);
            if (!on) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
            srcSel.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        card.addView(row(c, L.s(R.string.set_sources), L.s(R.string.set_sources_sub), srcSel, false));
        // 1.0.2 (issue #23): top-right button of the panel: CarPlay/Android Auto (as before), an app, none
        String top = prefs.topButton();
        boolean topApp = top.startsWith(Prefs.TOP_APP);
        String topAppLabel = topApp ? Cam360.label(c, top.substring(Prefs.TOP_APP.length())) : L.s(R.string.top_app);
        // 1.0.4: the button is next to Map/Media, at the bottom; CarPlay/Android Auto are in the bottom-right bar
        String[] topNames = {L.s(R.string.top_none), topAppLabel};
        int topSel = topApp ? 1 : 0;
        LinearLayout topRow = Ui.row(c);
        for (int i = 0; i < topNames.length; i++) {
            final int idx = i;
            boolean on = i == topSel;
            TextView ch = choice(c, topNames[i], on, v -> {
                if (idx == 1) { pickTopApp(c); return; }   // always opens the list, even if already chosen
                if (idx == topSel) return;
                prefs.setTopButton(Prefs.TOP_NONE);
                host.settingsChanged(true);
                showSection(SEC_HOME);
            });
            ch.setPadding(tp(20), 0, tp(20), 0);
            if (!on) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
            topRow.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        card.addView(row(c, L.s(R.string.set_top_button), L.s(R.string.set_top_button_sub), topRow, true));
        col.addView(card, topFull(14));
    }

    /** List of installed apps for the top-right button. */
    private void pickTopApp(Context c) {
        java.util.List<com.mg4.launcher.apps.AppCatalog.App> apps = host.allLaunchableApps();
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).label;
        String cur = prefs.topButton();
        int sel = -1;
        if (cur.startsWith(Prefs.TOP_APP)) {
            String pkg = cur.substring(Prefs.TOP_APP.length());
            for (int i = 0; i < apps.size(); i++) if (apps.get(i).packageName.equals(pkg)) sel = i;
        }
        Confirm.pick(this, t, L.s(R.string.top_pick_app), "", names, sel, L.s(R.string.btn_cancel), i -> {
            prefs.setTopButton(Prefs.TOP_APP + apps.get(i).packageName);
            host.settingsChanged(true);
            showSection(SEC_HOME);
        });
    }

    private void buildBar(Context c, LinearLayout col) {
        col.addView(label(c, L.s(R.string.set_bar_card)));
        LinearLayout card = card(c);
        card.addView(row(c, L.s(R.string.set_big_bar), L.s(R.string.set_big_bar_sub),
                toggle(c, prefs.bigBar(), on -> {
                    prefs.setBigBar(on);
                    host.settingsChanged(true);
                }), false));
        // rear fog: Automatic (hidden if the system bar already has the indicator) / Show / Hide
        String[] fogModes = {Prefs.FOG_AUTO, Prefs.FOG_SHOW, Prefs.FOG_HIDE};
        String[] fogNames = {L.s(R.string.fog_auto), L.s(R.string.fog_show), L.s(R.string.fog_hide)};
        card.addView(row(c, L.s(R.string.set_rear_fog), L.s(R.string.set_rear_fog_sub),
                choices(c, fogNames, java.util.Arrays.asList(fogModes).indexOf(prefs.rearFogMode()), i -> {
                    prefs.setRearFogMode(fogModes[i]);
                    host.settingsChanged(true);
                    showSection(SEC_BAR);
                }), false));
        card.addView(row(c, L.s(R.string.set_grouped), L.s(R.string.set_grouped_sub),
                toggle(c, prefs.groupedButtons(), on -> {
                    prefs.setGroupedButtons(on);
                    host.settingsChanged(true);
                }), false));
        card.addView(row(c, L.s(R.string.set_assist), L.s(R.string.set_assist_sub),
                toggle(c, prefs.showAssist(), on -> {
                    prefs.setShowAssist(on);
                    host.settingsChanged(true);
                }), false));
        card.addView(row(c, L.s(R.string.set_charge_buttons), L.s(R.string.set_charge_buttons_sub),
                toggle(c, prefs.chargeButtons(), on -> {
                    prefs.setChargeButtons(on);
                    host.settingsChanged(true);
                }), false));
        // 0.4.3 (point 7): panel under the station, next to the climate: Media (as before) / Phone favourites / App shortcuts
        String tile = prefs.tileMode();
        String[] tileNames = {L.s(R.string.tile_media), L.s(R.string.tile_phone), L.s(R.string.tile_apps)};
        int tileSel = Prefs.TILE_PHONE.equals(tile) ? 1 : Prefs.TILE_APPS.equals(tile) ? 2 : 0;
        LinearLayout tileRow = Ui.row(c);
        for (int i = 0; i < tileNames.length; i++) {
            final int idx = i;
            boolean on = i == tileSel;
            TextView ch = choice(c, tileNames[i], on, v -> {
                if (idx == 1) { pickTileContacts(c); return; }   // always opens the choice, even if already selected
                if (idx == 2) { pickTileApps(c); return; }
                if (idx == tileSel) return;
                prefs.setTileMode(Prefs.TILE_MEDIA);
                host.settingsChanged(true);
                showSection(SEC_BAR);
            });
            ch.setPadding(tp(20), 0, tp(20), 0);
            if (!on) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
            tileRow.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        card.addView(row(c, L.s(R.string.set_tile), L.s(R.string.set_tile_sub), tileRow, false));
        // 0.4.0 (point 8): what the 360 key opens
        String cam = prefs.cam360();
        boolean camApp = cam.startsWith(Prefs.CAM_APP);
        String appLabel = camApp ? com.mg4.launcher.ui.Cam360.label(c, cam.substring(Prefs.CAM_APP.length())) : L.s(R.string.cam360_app);
        String[] camNames = {L.s(R.string.cam360_original), L.s(R.string.cam360_mg4), appLabel, L.s(R.string.cam360_hidden)};
        int camSel = Prefs.CAM_ORIGINAL.equals(cam) ? 0 : Prefs.CAM_MG4.equals(cam) ? 1 : camApp ? 2 : cam.isEmpty() ? 0 : 3;
        LinearLayout camRow = Ui.row(c);
        for (int i = 0; i < camNames.length; i++) {
            final int idx = i;
            boolean on = i == camSel;
            TextView ch = choice(c, camNames[i], on, v -> {
                if (idx == 2) { pickCam360App(c); return; }   // always opens the list, even if already selected
                if (idx == camSel) return;
                prefs.setCam360(idx == 0 ? Prefs.CAM_ORIGINAL : idx == 1 ? Prefs.CAM_MG4 : Prefs.CAM_HIDDEN);
                host.settingsChanged(false);
                showSection(SEC_BAR);
            });
            ch.setPadding(tp(20), 0, tp(20), 0);
            if (!on) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
            camRow.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        card.addView(row(c, L.s(R.string.set_cam360), L.s(R.string.set_cam360_sub), camRow, false));
        // 0.4.2 (point 9): seat and wheel heating Automatic / Show / Hide
        String[] triVals = {Prefs.SHOW_AUTO, Prefs.SHOW_ON, Prefs.SHOW_OFF};
        String[] triNames = {L.s(R.string.fog_auto), L.s(R.string.fog_show), L.s(R.string.fog_hide)};
        card.addView(row(c, L.s(R.string.set_heat), L.s(R.string.set_heat_sub),
                choices(c, triNames, java.util.Arrays.asList(triVals).indexOf(prefs.heatMode()), i -> {
                    prefs.setHeatMode(triVals[i]);
                    host.settingsChanged(false);
                    showSection(SEC_BAR);
                }), false));
        col.addView(card, topFull(14));
    }

    /** List of installed apps (excluding us) to pick "another app" for the 360 key. */
    /**
     * 0.4.3: phone favourites (at most 6) from the contacts of the connected Bluetooth phone. The contacts are read
     * off the main thread; favourites already chosen remain even if the phone is not connected now.
     */
    private void pickTileContacts(Context c) {
        com.mg4.launcher.svc.PhoneClient pc = com.mg4.launcher.svc.PhoneClient.get(c);
        pc.start(null);
        new Thread(() -> {
            java.util.List<com.mg4.launcher.svc.PhoneClient.Contact> book = pc.contacts();
            post(() -> {
                pc.stop(null);
                if (!isAttachedToWindow()) return;
                java.util.List<String[]> cur = prefs.tileContacts();
                // list: contacts + the already chosen favourites that are missing (phone not connected)
                java.util.List<String[]> all = new java.util.ArrayList<>();
                java.util.Set<String> keys = new java.util.HashSet<>();
                for (com.mg4.launcher.svc.PhoneClient.Contact k : book) if (keys.add(k.name + "|" + k.number)) all.add(new String[]{k.name, k.number});
                for (String[] f : cur) if (keys.add(f[0] + "|" + f[1])) all.add(0, f);
                if (all.isEmpty()) {
                    prefs.setTileMode(Prefs.TILE_PHONE);
                    host.settingsChanged(true);
                    showSection(SEC_BAR);
                    android.widget.Toast.makeText(c, L.s(R.string.tile_no_contacts), android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                String[] names = new String[all.size()];
                for (int i = 0; i < all.size(); i++) {
                    String num = all.get(i)[1];
                    // with several numbers for the same person the last 3 digits are shown
                    names[i] = all.get(i)[0] + (num.length() > 3 ? "  ·" + num.substring(num.length() - 3) : "");
                }
                java.util.List<Integer> preset = new java.util.ArrayList<>();
                for (String[] f : cur) for (int i = 0; i < all.size(); i++) if (all.get(i)[0].equals(f[0]) && all.get(i)[1].equals(f[1])) preset.add(i);
                Confirm.pickMany(this, t, L.s(R.string.tile_pick_contacts), L.s(R.string.tile_pick_contacts_sub), names, preset, Prefs.TILE_MAX,
                        L.s(R.string.btn_cancel), L.s(R.string.btn_save), chosen -> {
                            java.util.List<String[]> out = new java.util.ArrayList<>();
                            for (int i : chosen) out.add(all.get(i));
                            prefs.setTileContacts(out);
                            prefs.setTileMode(Prefs.TILE_PHONE);
                            com.mg4.launcher.svc.Diag.put("home.tile", "Phone favourites · " + out.size() + " favourites chosen");
                            host.settingsChanged(true);
                            showSection(SEC_BAR);
                        });
            });
        }, "mg4-contacts").start();
    }

    /** 0.4.3: app shortcuts for the panel (at most 6), in the order they are tapped. */
    private void pickTileApps(Context c) {
        java.util.List<com.mg4.launcher.apps.AppCatalog.App> apps = host.allLaunchableApps();
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).label;
        java.util.List<Integer> preset = new java.util.ArrayList<>();
        for (String pkg : prefs.tileApps()) for (int i = 0; i < apps.size(); i++) if (apps.get(i).packageName.equals(pkg)) { preset.add(i); break; }
        Confirm.pickMany(this, t, L.s(R.string.tile_pick_apps), L.s(R.string.tile_pick_apps_sub), names, preset, Prefs.TILE_MAX,
                L.s(R.string.btn_cancel), L.s(R.string.btn_save), chosen -> {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (int i : chosen) if (!out.contains(apps.get(i).packageName)) out.add(apps.get(i).packageName);
                    prefs.setTileApps(out);
                    prefs.setTileMode(Prefs.TILE_APPS);
                    host.settingsChanged(true);
                    showSection(SEC_BAR);
                });
    }

    private String sourcesTxt(int[] ids, String[] names) {
        StringBuilder sb = new StringBuilder("shown:");
        for (int i = 0; i < ids.length; i++) if (prefs.sourceShown(ids[i])) sb.append(' ').append(names[i]);
        return sb.toString();
    }

    /**
     * 1.0.4 (issue #27): choice of the navigation app among the installed apps that handle navigation
     * (Google Maps, Waze, ABRP, OsmAnd...): those that open a "geo:" or "google.navigation:" address plus the known ones.
     */
    private void pickNavApp(Context c) {
        java.util.List<com.mg4.launcher.apps.AppCatalog.App> apps = host.navCandidateApps();
        if (apps.isEmpty()) {
            android.widget.Toast.makeText(c, L.s(R.string.nav_no_apps), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[apps.size()];
        int sel = -1;
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i).label;
            if (apps.get(i).packageName.equals(prefs.navApp())) sel = i;
        }
        Confirm.pick(this, t, L.s(R.string.nav_other_app), L.s(R.string.nav_other_app_sub), names, sel, L.s(R.string.btn_cancel), i -> {
            prefs.setNavApp(apps.get(i).packageName);
            com.mg4.launcher.svc.Diag.put("nav.chosenApp", apps.get(i).packageName + " (from settings)");
            host.settingsChanged(false);
            showSection(SEC_HOME);
        });
    }

    /** List of installed apps (excluding us) to pick "another app" for the 360 key. */
    private void pickCam360App(Context c) {
        java.util.List<com.mg4.launcher.apps.AppCatalog.App> apps = host.allLaunchableApps();
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).label;
        String cur = prefs.cam360();
        int sel = -1;
        if (cur.startsWith(Prefs.CAM_APP)) {
            String pkg = cur.substring(Prefs.CAM_APP.length());
            for (int i = 0; i < apps.size(); i++) if (apps.get(i).packageName.equals(pkg)) sel = i;
        }
        Confirm.pick(this, t, L.s(R.string.cam360_pick_app), "", names, sel, L.s(R.string.btn_cancel), i -> {
            prefs.setCam360(Prefs.CAM_APP + apps.get(i).packageName);
            host.settingsChanged(false);
            showSection(SEC_BAR);
        });
    }

    // ---- radio logos -----------------------------------------------------------------------

    private final com.mg4.launcher.svc.LogoPacks.Listener logoListener = () -> {
        if (section == SEC_LOGOS && !diagOpen && isAttachedToWindow()) showSection(SEC_LOGOS);
    };

    private void buildLogos(Context c, LinearLayout col) {
        com.mg4.launcher.svc.LogoPacks lp = com.mg4.launcher.svc.LogoPacks.get(c);
        col.addView(label(c, L.s(R.string.set_logos_card)), top(30));
        LinearLayout card = card(c);
        java.util.List<String> chosen = lp.chosen();
        StringBuilder names = new StringBuilder();
        for (String cc : chosen) names.append(names.length() == 0 ? "" : ", ").append(com.mg4.launcher.svc.LogoPacks.countryName(cc));
        boolean auto = com.mg4.launcher.svc.LogoPacks.AUTO.equals(lp.choice());
        String sub = auto ? L.f(R.string.logos_auto_sub, names) : names.toString();
        card.addView(row(c, L.s(R.string.set_logos_country), sub, logoButtons(c, lp), false));

        // state of the packs of the chosen countries (and of those installed from USB stick)
        java.util.Map<String, com.mg4.launcher.svc.LogoPacks.Pack> inst = lp.installed();
        StringBuilder st = new StringBuilder();
        java.util.LinkedHashSet<String> show = new java.util.LinkedHashSet<>(chosen);
        show.addAll(inst.keySet());
        for (String cc : show) {
            com.mg4.launcher.svc.LogoPacks.Pack p = inst.get(cc);
            if (st.length() > 0) st.append('\n');
            st.append(com.mg4.launcher.svc.LogoPacks.countryName(cc)).append(": ");
            if (p == null) st.append(L.s(R.string.logos_not_downloaded));
            else st.append(L.f(R.string.logos_pack_state, p.version > 0 ? String.valueOf(p.version) : "–", p.logos,
                    String.format(L.locale(), "%.1f MB", p.size / 1048576f)));
        }
        String state = lp.status();
        if (lp.isBusy()) st.append('\n').append(L.s(R.string.logos_downloading));
        else if ("offline".equals(state)) st.append('\n').append(L.s(R.string.logos_offline));
        else if ("error".equals(state)) st.append('\n').append(L.s(R.string.logos_error));
        LinearLayout btns = Ui.row(c);
        TextView upd = button(c, L.s(R.string.btn_update), v -> lp.refresh(true));
        upd.setEnabled(!lp.isBusy());
        upd.setAlpha(lp.isBusy() ? 0.4f : 1f);
        btns.addView(upd);
        if (!inst.isEmpty()) {
            TextView del = button(c, L.s(R.string.btn_delete), v -> {
                String[] ccs = inst.keySet().toArray(new String[0]);
                if (ccs.length == 1) {
                    Confirm.show(this, t, L.s(R.string.logos_delete_title), L.f(R.string.logos_delete_msg,
                            com.mg4.launcher.svc.LogoPacks.countryName(ccs[0])), L.s(R.string.btn_cancel), L.s(R.string.btn_delete),
                            () -> lp.delete(ccs[0]));
                    return;
                }
                String[] nm = new String[ccs.length];
                for (int i = 0; i < ccs.length; i++) nm[i] = com.mg4.launcher.svc.LogoPacks.countryName(ccs[i]);
                Confirm.pick(this, t, L.s(R.string.logos_delete_title), L.s(R.string.logos_delete_pick), nm, -1,
                        L.s(R.string.btn_cancel), i -> lp.delete(ccs[i]));
            });
            btns.addView(del, Ui.margins(Ui.lp(-2, tp(60)), dp(12), 0, 0, 0));
        }
        card.addView(row(c, L.s(R.string.set_logos_state), st.toString(), btns, true));
        col.addView(card, topFull(14));
    }

    /** "Cambia" (country list) and, since 0.9.6, "Codice" to type any country by hand (e.g. US, NZ). */
    private LinearLayout logoButtons(Context c, com.mg4.launcher.svc.LogoPacks lp) {
        LinearLayout b = Ui.row(c);
        b.addView(button(c, L.s(R.string.btn_change), v -> pickLogoCountries(lp)));
        b.addView(button(c, L.s(R.string.btn_logo_code), v -> {
            String cur = com.mg4.launcher.svc.LogoPacks.AUTO.equals(lp.choice()) ? "" : lp.choice();
            Confirm.input(this, t, L.s(R.string.logos_code_title), L.s(R.string.logos_code_msg), cur, "AU, NZ",
                    L.s(R.string.btn_cancel), L.s(R.string.btn_ok), text -> {
                        StringBuilder v2 = new StringBuilder();
                        for (String p : text.toUpperCase(java.util.Locale.ROOT).split("[^A-Z]+")) {
                            if (p.length() == 2 && v2.indexOf(p) < 0) v2.append(v2.length() == 0 ? "" : ",").append(p);
                        }
                        lp.setChoice(v2.length() == 0 ? com.mg4.launcher.svc.LogoPacks.AUTO : v2.toString());
                        showSection(SEC_HOME);
                    });
        }), Ui.margins(Ui.lp(-2, tp(60)), dp(12), 0, 0, 0));
        return b;
    }

    private void pickLogoCountries(com.mg4.launcher.svc.LogoPacks lp) {
        String[] codes = com.mg4.launcher.svc.LogoPacks.COUNTRIES;
        String[] opts = new String[codes.length + 1];
        boolean[] on = new boolean[opts.length];
        opts[0] = L.s(R.string.unit_auto);
        boolean auto = com.mg4.launcher.svc.LogoPacks.AUTO.equals(lp.choice());
        on[0] = auto;
        java.util.List<String> chosen = lp.chosen();
        // alphabetical order by name in the launcher language
        Integer[] order = new Integer[codes.length];
        for (int i = 0; i < codes.length; i++) order[i] = i;
        java.text.Collator col = java.text.Collator.getInstance(L.locale());
        java.util.Arrays.sort(order, (a, b) -> col.compare(com.mg4.launcher.svc.LogoPacks.countryName(codes[a]),
                com.mg4.launcher.svc.LogoPacks.countryName(codes[b])));
        for (int i = 0; i < codes.length; i++) {
            String cc = codes[order[i]];
            opts[i + 1] = com.mg4.launcher.svc.LogoPacks.countryName(cc);
            on[i + 1] = !auto && chosen.contains(cc);
        }
        Confirm.multi(this, t, L.s(R.string.set_logos_country), L.s(R.string.logos_pick_msg), opts, on,
                L.s(R.string.btn_cancel), L.s(R.string.btn_ok), idx -> {
                    StringBuilder v = new StringBuilder();
                    for (int i : idx) v.append(v.length() == 0 ? "" : ",").append(codes[order[i - 1]]);
                    lp.setChoice(v.length() == 0 ? com.mg4.launcher.svc.LogoPacks.AUTO : v.toString());
                    showSection(SEC_HOME);
                });
    }

    private interface OnChoice {
        void chosen(int index);
    }

    /** Small choices in a card row (selected = inverted colours, the others on panel background). */
    private LinearLayout choices(Context c, String[] names, int sel, OnChoice l) {
        LinearLayout r = Ui.row(c);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView ch = choice(c, names[i], i == sel, v -> {
                if (idx != sel) l.chosen(idx);
            });
            ch.setPadding(tp(20), 0, tp(20), 0);
            if (i != sel) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
            r.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        return r;
    }

    private void buildLook(Context c, LinearLayout col) {
        col.addView(label(c, L.s(R.string.set_theme)));
        String mode = prefs.themeMode();
        String[][] modes = {{Prefs.THEME_AUTO, L.s(R.string.theme_auto)}, {Prefs.THEME_NIGHT, L.s(R.string.theme_night)}, {Prefs.THEME_DAY, L.s(R.string.theme_day)}};
        Flow f = new Flow(c);
        for (String[] m : modes) {
            f.addView(choice(c, m[1], m[0].equals(mode), v -> {
                if (m[0].equals(prefs.themeMode())) return;
                prefs.setThemeMode(m[0]);
                com.mg4.launcher.svc.DayNight.get(c).syncSystem("manual choice " + m[0]);
                host.settingsChanged(true);
            }));
        }
        col.addView(f, topFull(14));

        col.addView(label(c, L.s(R.string.set_apps_size)), top(28));
        int[] appNames = {R.string.ui_size_normal, R.string.ui_size_large, R.string.ui_size_xlarge};
        Flow fa = new Flow(c);
        for (int i = 0; i < appNames.length; i++) {
            final int idx = i;
            fa.addView(choice(c, L.s(appNames[i]), prefs.appsScale() == i, v -> {
                if (prefs.appsScale() == idx) return;
                prefs.setAppsScale(idx);
                host.settingsChanged(true);   // rebuilds the App screen with the new size
            }));
        }
        col.addView(fa, topFull(14));

        col.addView(label(c, L.s(R.string.set_ui_size)), top(28));
        int[] names = {R.string.ui_size_normal, R.string.ui_size_large, R.string.ui_size_xlarge};
        f = new Flow(c);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            f.addView(choice(c, L.s(names[i]), prefs.uiScale() == i, v -> {
                if (prefs.uiScale() == idx) return;
                prefs.setUiScale(idx);
                host.settingsChanged(true);   // rebuilds home and settings with the new size
            }));
        }
        col.addView(f, topFull(14));

        col.addView(label(c, L.s(R.string.set_language)), top(28));
        String cur = prefs.language();
        f = new Flow(c);
        f.addView(choice(c, L.s(R.string.lang_auto) + " · " + L.nameOf(L.resolve(L.AUTO)), L.AUTO.equals(cur), v -> setLang(L.AUTO)));
        for (int i = 0; i < L.CODES.length; i++) {
            final String code = L.CODES[i];
            f.addView(choice(c, L.NAMES[i], code.equals(cur), v -> setLang(code)));
        }
        col.addView(f, topFull(14));
    }

    private void setLang(String code) {
        if (code.equals(prefs.language())) return;
        prefs.setLanguage(code);
        host.languageChanged();   // reapplies the language and rebuilds the interface
    }

    private static int officinaDays = 3;

    /** 0.4.0 (point 11): "Avvio e app" - safe start, auto-press Home, other launchers, stock apps, dongle. */
    private void buildLaunch(Context c, LinearLayout col) {
        // default home and workshop mode
        Context app = c.getApplicationContext();
        boolean workshop = com.mg4.launcher.svc.HomeMode.workshop(app);
        col.addView(label(c, L.s(R.string.set_start_section)));
        LinearLayout hc = card(c);
        String homeSub = workshop ? L.f(R.string.workshop_until_sub, com.mg4.launcher.svc.HomeMode.workshopDate(app))
                : L.s(com.mg4.launcher.svc.HomeMode.enabled(app) ? R.string.home_default_sub : R.string.home_default_off_sub);
        hc.addView(row(c, L.s(R.string.home_default_title), homeSub, toggle(c, com.mg4.launcher.svc.HomeMode.enabled(app), on -> {
            if (on) {
                com.mg4.launcher.svc.HomeMode.setEnabled(app, true);
                showSection(SEC_LAUNCH);
            } else {
                Confirm.show(this, t, L.s(R.string.confirm_home_off_title), L.s(R.string.confirm_home_off_msg),
                        L.s(R.string.btn_cancel), L.s(R.string.btn_turn_off), () -> com.mg4.launcher.svc.HomeMode.setEnabled(app, false));
                showSection(SEC_LAUNCH);   // until confirmed the switch stays on
            }
        }), false));
        // 0.9.6: safe start (the MG home screen is disabled, as MG4Dash does) and other launchers warning
        if (com.mg4.launcher.svc.HomeLock.mgExists(app)) {
            String safeSub = com.mg4.launcher.svc.CrashGuard.tripped(app) ? L.s(R.string.set_safe_start_tripped)
                    : L.s(R.string.set_safe_start_sub);
            hc.addView(row(c, L.s(R.string.set_safe_start), safeSub, toggle(c, com.mg4.launcher.svc.HomeLock.option(app), on -> {
                if (on) com.mg4.launcher.svc.CrashGuard.reset(app);
                com.mg4.launcher.svc.HomeLock.setOption(app, on);
                postDelayed(() -> showSection(SEC_LAUNCH), 400);
            }), false));
        }
        java.util.List<String> others = com.mg4.launcher.svc.HomeLock.otherLaunchers(app);
        if (!others.isEmpty()) {
            TextView warn = Ui.text(c, "⚠", 26, t.warn, Ui.MEDIUM);
            hc.addView(row(c, L.s(R.string.set_other_launchers), L.f(R.string.set_other_launchers_sub, android.text.TextUtils.join(", ", others)),
                    warn, false));
        }
        LinearLayout oc = Ui.row(c);
        if (workshop) {
            oc.addView(button(c, L.s(R.string.btn_deactivate), v -> {
                com.mg4.launcher.svc.HomeMode.end(app, "cancelled");
                showSection(SEC_LAUNCH);
            }));
        } else {
            int[] days = {1, 3, 7};
            int[] names = {R.string.days_1, R.string.days_3, R.string.days_7};
            for (int i = 0; i < 3; i++) {
                final int d = days[i];
                TextView ch = choice(c, L.s(names[i]), officinaDays == d, v -> {
                    officinaDays = d;
                    showSection(SEC_LAUNCH);
                });
                // on the card (chip background) unselected choices have the panel background
                if (officinaDays != d) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
                oc.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), 0, 0, dp(10), 0));
            }
            TextView act = button(c, L.s(R.string.btn_activate), v -> {
                String until = new java.text.SimpleDateFormat("dd/MM", java.util.Locale.ITALY)
                        .format(new java.util.Date(System.currentTimeMillis() + officinaDays * 24L * 3600_000L));
                Confirm.show(this, t, L.s(R.string.confirm_workshop_title), L.f(R.string.confirm_workshop_msg, until),
                        L.s(R.string.btn_cancel), L.s(R.string.btn_activate), () -> com.mg4.launcher.svc.HomeMode.startWorkshop(app, officinaDays));
            });
            act.setTextColor(t.panel);
            act.setBackground(Ui.pressable(c, t.text, 16 * Ui.K, t));
            oc.addView(act, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), dp(6), 0, 0, 0));
        }
        hc.addView(row(c, L.s(R.string.workshop_title), workshop
                ? L.f(R.string.workshop_until_sub, com.mg4.launcher.svc.HomeMode.workshopDate(app)) : L.s(R.string.workshop_sub), oc, false));
        // auto-press Home after power-on (accessibility service)
        int[] secs = Prefs.AUTO_HOME_SECONDS;
        String[] secNames = new String[secs.length];
        int secSel = 0;
        for (int i = 0; i < secs.length; i++) {
            secNames[i] = secs[i] == 0 ? L.s(R.string.off) : secs[i] + " s";
            if (secs[i] == prefs.autoHomeSeconds()) secSel = i;
        }
        LinearLayout ah = Ui.column(c);
        ah.setPadding(dp(28), dp(18), dp(28), dp(20));
        ah.addView(Ui.text(c, L.s(R.string.set_auto_home), 24, t.text, Ui.REGULAR));
        ah.addView(Ui.text(c, L.s(R.string.set_auto_home_sub), 17, t.sub, Ui.REGULAR), Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(6), 0, 0));
        ah.addView(choices(c, secNames, secSel, i -> {
            prefs.setAutoHomeSeconds(secs[i]);
            showSection(SEC_LAUNCH);
        }), Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(14), 0, 0));
        hc.addView(ah);
        col.addView(hc, topFull(14));

        // 0.4.2 (point 6): return to the previous app after reversing (on by default)
        LinearLayout rc = card(c);
        rc.addView(row(c, L.s(R.string.set_retro), L.s(R.string.set_retro_sub),
                toggle(c, prefs.retroReturn(), on -> {
                    prefs.setRetroReturn(on);
                    showSection(SEC_LAUNCH);
                }), true));
        col.addView(rc, topFull(14));

        // CarPlay / Android Auto: restart of the wireless dongle that does not connect
        com.mg4.launcher.svc.DongleWatch dw = com.mg4.launcher.svc.DongleWatch.peek();
        if (dw != null) {
            col.addView(label(c, L.s(R.string.set_projection_card)), top(30));
            LinearLayout pc = card(c);
            pc.addView(row(c, L.s(R.string.set_dongle), L.s(R.string.set_dongle_sub),
                    toggle(c, dw.enabled(), on -> {
                        dw.setEnabled(on);
                        showSection(SEC_LAUNCH);
                    }), false));
            if (dw.enabled()) {
                int[] ds = {15, 20, 30, 45};
                String[] dn = new String[ds.length];
                int dsel = 1;
                for (int i = 0; i < ds.length; i++) {
                    dn[i] = ds[i] + " s";
                    if (ds[i] == dw.delaySeconds()) dsel = i;
                }
                pc.addView(row(c, L.s(R.string.set_dongle_delay), L.s(R.string.set_dongle_delay_sub), choices(c, dn, dsel, i -> {
                    dw.setDelaySeconds(ds[i]);
                    showSection(SEC_LAUNCH);
                }), false));
            }
            pc.addView(row(c, L.s(R.string.set_dongle_now), L.s(R.string.set_dongle_now_sub), button(c, L.s(R.string.btn_restart), v -> {
                if (dw.inCall()) {
                    Confirm.notice(this, t, L.s(R.string.set_dongle_now), "", new String[]{L.s(R.string.dongle_in_call)}, L.s(R.string.btn_ok), null);
                    return;
                }
                Confirm.show(this, t, L.s(R.string.set_dongle_now), L.s(R.string.dongle_confirm), L.s(R.string.btn_cancel),
                        L.s(R.string.btn_restart), () -> dw.restartNow(null));
            }), true));
            col.addView(pc, topFull(14));
        }

        // stock apps (open the MG home for checks/workshop)
        col.addView(label(c, L.s(R.string.set_original_label)), top(30));
        LinearLayout oac = card(c);
        oac.addView(row(c, L.s(R.string.set_open_original), L.s(R.string.set_original_sub),
                button(c, L.s(R.string.btn_open), v -> host.openOriginalHome()), true));
        col.addView(oac, topFull(14));
    }

    private void buildSystem(Context c, LinearLayout col) {
        Updater up = host.updater();
        col.addView(label(c, L.s(R.string.set_updates)));
        LinearLayout card = card(c);
        LinearLayout btns = Ui.row(c);
        updInstallBtn = button(c, L.s(R.string.btn_install), v -> {
            if (lastRelease != null) up.install(lastRelease, prefs.githubToken());
        });
        ((TextView) updInstallBtn).setTextColor(t.panel);
        updInstallBtn.setBackground(Ui.pressable(c, t.text, 16 * Ui.K, t));
        btns.addView(updInstallBtn, Ui.margins(Ui.lp(-2, tp(60)), 0, 0, dp(12), 0));
        updCheckBtn = button(c, L.s(R.string.btn_check), v -> up.check(prefs.updateRepo(), prefs.githubToken()));
        btns.addView(updCheckBtn, Ui.lp(-2, tp(60)));
        LinearLayout ur = row(c, "MG4 Launcher " + up.currentVersion(), "", btns, true);
        updSub = (TextView) ((ViewGroup) ur.getChildAt(0)).getChildAt(1);
        card.addView(ur);
        col.addView(card, topFull(14));

        col.addView(label(c, L.s(R.string.set_diag_section)), top(30));
        card = card(c);
        logSaveBtn = button(c, L.s(R.string.btn_save), v -> saveLog());
        LinearLayout sr = row(c, L.s(R.string.save_log), L.s(R.string.save_log_sub), logSaveBtn, false);
        logSaveSub = (TextView) ((ViewGroup) sr.getChildAt(0)).getChildAt(1);
        card.addView(sr);
        LinearLayout dr = row(c, L.s(R.string.set_diag_title), L.s(R.string.set_diag_sub),
                Ui.icon(c, R.drawable.ic_chev_right, t.sub, Math.round(30 * Ui.K)), true);
        dr.setBackground(Ui.pressable(c, 0x00000000, 0, t));
        dr.setClickable(true);
        dr.setOnClickListener(v -> {
            diagOpen = true;
            showSection(SEC_SYSTEM);
        });
        card.addView(dr);
        col.addView(card, topFull(14));

        // 1.0.4 (point D): MG4 Store. If the user removed it the launcher does not put it back: reinstall from here
        // fork: only if this APK bundles the Store (builds without the Store have nothing to install)
        if (com.mg4.launcher.svc.StoreSetup.installedCode(c) < 0 && com.mg4.launcher.svc.StoreSetup.bundledAvailable(c)) {
            card = card(c);
            View stBtn = button(c, L.s(R.string.store_install), v -> {
                android.widget.Toast.makeText(c, L.s(R.string.store_installing), android.widget.Toast.LENGTH_SHORT).show();
                com.mg4.launcher.svc.StoreSetup.install(c, true, (ok, msg) -> post(() -> {
                    com.mg4.launcher.svc.Diag.put("store", ok ? "installed now from settings" : "installation failed (" + msg + ")");
                    android.widget.Toast.makeText(c, L.s(ok ? R.string.store_installed : R.string.store_failed), android.widget.Toast.LENGTH_LONG).show();
                    if (isAttachedToWindow()) showSection(SEC_SYSTEM);
                }));
            });
            card.addView(row(c, L.s(R.string.store_install), L.s(R.string.store_install_sub), stBtn, true));
            col.addView(card, topFull(14));
        }

        // 1.0.4 (point F5): charging animation test (technical mode): downloaded pack or zip on USB stick
        card = card(c);
        View animBtn = button(c, L.s(R.string.anim_test), v -> host.testChargeAnimation());
        card.addView(row(c, L.s(R.string.anim_test), L.s(R.string.anim_test_sub), animBtn, true));
        col.addView(card, topFull(14));

        // 0.4.3: Function check, read-only
        card = card(c);
        View chkBtn = button(c, L.s(R.string.check_start), v -> CheckPanel.show(this, t));
        card.addView(row(c, L.s(R.string.check_title), L.s(R.string.check_sub), chkBtn, true));
        col.addView(card, topFull(14));

        // 0.3.3: uninstall from the car (restores the MG home and removes the launcher)
        card = card(c);
        View unBtn = button(c, L.s(R.string.btn_uninstall), v -> askUninstall());
        card.addView(row(c, L.s(R.string.uninstall_title), L.s(R.string.uninstall_sub), unBtn, false));
        col.addView(card, topFull(14));

        refreshUpdateViews(up.isBusy());
    }

    private void buildDiag(Context c) {
        ScrollView ds = new ScrollView(c);
        ds.setBackground(Ui.round(t.chip, dp(22)));
        diagText = Ui.text(c, "", 14, t.sub, Typeface.MONOSPACE);
        diagText.setPadding(dp(22), dp(18), dp(22), dp(18));
        diagText.setTextIsSelectable(true);
        diagText.setLineSpacing(0, 1.15f);
        ds.addView(diagText);
        content.addView(ds, Ui.margins(Ui.lpWeight(-1, 0, 1), 0, dp(24), 0, 0));
    }

    /** Confirmation and uninstall of the launcher; if it fails, it says so. */
    private void askUninstall() {
        Confirm.show(this, t, L.s(R.string.uninstall_title), L.s(R.string.uninstall_confirm),
                L.s(R.string.btn_cancel), L.s(R.string.btn_uninstall),
                () -> com.mg4.launcher.svc.SelfUninstall.run(getContext(), why -> post(() ->
                        Confirm.show(this, t, L.s(R.string.uninstall_title), L.f(R.string.uninstall_failed, why),
                                L.s(R.string.btn_cancel), "OK", () -> { }))));
    }

    /** Saves the full log (in background) and shows where it ended up. */
    private void saveLog() {
        logSaveBtn.setEnabled(false);
        logSaveBtn.setAlpha(0.5f);
        if (logSaveSub != null) logSaveSub.setText(L.s(R.string.log_saving));
        Context app = getContext().getApplicationContext();
        new Thread(() -> {
            Diag.Saved r = Diag.saveLog(app);
            main.post(() -> {
                if (logSaveBtn != null) {
                    logSaveBtn.setEnabled(true);
                    logSaveBtn.setAlpha(1f);
                }
                if (logSaveSub == null) return;
                if (r.paths.isEmpty()) {
                    logSaveSub.setText(L.s(R.string.log_failed));
                    return;
                }
                StringBuilder sb = new StringBuilder(L.s(r.onUsb ? R.string.log_saved_usb : R.string.log_saved)).append(' ');
                sb.append(r.paths.get(0));
                // fork: the first path is the visible folder (USB stick or Download); also shows the second, if visible
                if (r.paths.size() > 1 && !r.paths.get(1).contains("/Android/data/")) sb.append('\n').append(r.paths.get(1));
                if (!r.onUsb) sb.append('\n').append(L.s(R.string.log_usb_hint));
                logSaveSub.setSingleLine(false);
                logSaveSub.setText(sb.toString());
            });
        }, "mg4-log").start();
    }

    // =========================================================================================
    // UI elements
    // =========================================================================================

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    private int tp(float v) {
        return Ui.tp(getContext(), v);
    }

    private LinearLayout.LayoutParams top(int m) {
        return Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(m), 0, 0);
    }

    private LinearLayout.LayoutParams topFull(int m) {
        return Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, dp(m), 0, 0);
    }

    private TextView label(Context c, String s) {
        TextView tv = Ui.text(c, s.toUpperCase(L.locale()), 15, t.sub, Ui.MEDIUM);
        tv.setLetterSpacing(0.08f);
        return tv;
    }

    private LinearLayout card(Context c) {
        LinearLayout l = Ui.column(c);
        l.setBackground(Ui.round(t.chip, dp(22)));
        l.setClipToOutline(true);
        return l;
    }

    /** Large row: title + explanation on the left, control on the right; separator below unless it is the last. */
    /** Empty zero-width view, for info-only rows (without measures it took the whole row). */
    private static View spacer0(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 0));
        return v;
    }

    private LinearLayout row(Context c, String title, String sub, View right, boolean last) {
        LinearLayout r = Ui.row(c);
        r.setMinimumHeight(tp(96));
        r.setPadding(dp(28), dp(14), dp(28), dp(14));
        if (!last) {
            GradientDrawable line = new GradientDrawable();
            line.setColor(t.chip);
            r.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.ColorDrawable(t.track), line}) {{
                setLayerInset(1, 0, 0, 0, dp(1));
            }});
        }
        LinearLayout tx = Ui.column(c);
        tx.addView(Ui.text(c, title, 24, t.text, Ui.REGULAR));
        TextView st = Ui.text(c, sub, 17, t.sub, Ui.REGULAR);
        tx.addView(st, Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(6), 0, 0));
        if (sub.isEmpty()) st.setVisibility(GONE);
        r.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), 0, 0, dp(20), 0));
        r.addView(right);
        if (right instanceof Toggle) {
            // fork: the whole row toggles (before only the 76×44 switch). isEnabled() checked at the moment of the
            // tap (some switches are disabled after creation); callOnClick so the click sound does not play twice
            r.setClickable(true);
            r.setOnClickListener(v -> {
                if (right.isEnabled()) right.callOnClick();
            });
            r.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(t.day ? 0x22000000 : 0x33FFFFFF), null,
                    new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
            r.setContentDescription(title);
        }
        return r;
    }

    /** Secondary button of a row (panel background on the card). */
    private TextView button(Context c, String text, View.OnClickListener l) {
        TextView b = Ui.text(c, text, Ui.ts(20), t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(tp(26), 0, tp(26), 0);
        b.setMinHeight(tp(60));
        b.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
        b.setClickable(true);
        b.setOnClickListener(l);
        b.setLayoutParams(Ui.lp(-2, tp(60)));
        return b;
    }

    /** Button choice (theme, size, language, drive): selected = inverted colours. */
    private TextView choice(Context c, String text, boolean on, View.OnClickListener l) {
        TextView b = Ui.text(c, text, Ui.ts(20), on ? t.panel : t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(tp(26), 0, tp(26), 0);
        b.setSingleLine(true);
        b.setBackground(on ? Ui.round(t.text, tp(16)) : Ui.pressable(c, t.chip, 16 * Ui.K, t));
        b.setClickable(true);
        b.setOnClickListener(l);
        b.setLayoutParams(new ViewGroup.LayoutParams(-2, tp(60)));
        return b;
    }

    /** Rim thumbnail: rim with the tyre around it, border like the colours if it is the chosen one. */
    private View wheelThumb(Context c, int idx, boolean selected, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF1B1B1C);   // tyre
        g.setStroke(dp(selected ? 4 : 1), selected ? t.text : t.track);
        f.setBackground(g);
        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setImageResource(Prefs.WHEEL_RES[idx]);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = tp(13);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        ((FrameLayout.LayoutParams) iv.getLayoutParams()).setMargins(pad, pad, pad, pad);
        f.setContentDescription(L.s(Prefs.WHEEL_NAMES[idx]));
        f.setClickable(true);
        f.setOnClickListener(l);
        return f;
    }

    private View swatch(Context c, int color, boolean selected, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(selected ? 4 : 1), selected ? t.text : t.track);
        f.setBackground(g);
        f.setContentDescription(desc);
        f.setClickable(true);
        f.setOnClickListener(l);
        return f;
    }

    private interface OnToggle {
        void changed(boolean on);
    }

    private Toggle toggle(Context c, boolean on, OnToggle l) {
        Toggle tg = new Toggle(c, on, l);
        tg.setLayoutParams(Ui.lp(tp(76), tp(44)));
        return tg;
    }

    /**
     * Switch drawn with the theme colours (on = text colour, off = track).
     * Fork: off with a t.sub outline (before, the track on the card had ~1.1:1 and only the knob was visible) and the knob slides
     * in 150 ms; the action runs at the end of the animation (many rebuild the section), and taps during the animation are ignored.
     */
    private final class Toggle extends View {
        private boolean on;
        private float pos;   // 0 = off, 1 = on (knob position)
        private boolean busy;
        private android.animation.ValueAnimator anim;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Toggle(Context c, boolean on, OnToggle l) {
            super(c);
            this.on = on;
            this.pos = on ? 1f : 0f;
            setClickable(true);
            setOnClickListener(v -> {
                if (busy) return;
                busy = true;
                this.on = !this.on;
                boolean now = this.on;
                anim = android.animation.ValueAnimator.ofFloat(pos, now ? 1f : 0f);
                anim.setDuration(150);
                anim.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
                anim.addUpdateListener(a -> {
                    pos = (float) a.getAnimatedValue();
                    invalidate();
                });
                anim.start();
                // the action always runs (even if the view leaves the screen meanwhile): the preference is saved
                main.postDelayed(() -> {
                    busy = false;
                    l.changed(now);
                }, 160);
            });
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.4f);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), pad = h * 4f / 44f, d = h - 2 * pad;
            boolean lit = pos >= 0.5f;   // the colour changes halfway through the slide
            if (lit) {
                r.set(0, 0, w, h);
                p.setStyle(Paint.Style.FILL);
                p.setColor(t.text);
                cv.drawRoundRect(r, h / 2, h / 2, p);
            } else {
                float sw = dp(2);
                r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
                p.setStyle(Paint.Style.FILL);
                p.setColor(t.chip);
                cv.drawRoundRect(r, h / 2, h / 2, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                p.setColor(t.sub);
                cv.drawRoundRect(r, h / 2, h / 2, p);
                p.setStyle(Paint.Style.FILL);
            }
            p.setColor(lit ? t.panel : t.sub);
            float cx = pad + d / 2 + pos * (w - 2 * pad - d);
            cv.drawCircle(cx, h / 2, d / 2, p);
        }
    }

    /** Row of buttons that wraps when there is no room (languages, drive at large sizes). */
    private final class Flow extends ViewGroup {
        private final int gap = dp(12);

        Flow(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getSize(ws);
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(v.getLayoutParams().height, MeasureSpec.EXACTLY));
                if (x > 0 && x + v.getMeasuredWidth() > max) {
                    x = 0;
                    y += lineH + gap;
                    lineH = 0;
                }
                x += v.getMeasuredWidth() + gap;
                lineH = Math.max(lineH, v.getMeasuredHeight());
            }
            setMeasuredDimension(max, y + lineH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int tt, int rr, int b) {
            int max = rr - l, x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + gap;
                    lineH = 0;
                }
                int left = rhd ? max - x - w : x;   // with right-hand drive the choices start from the right
                v.layout(left, y, left + w, y + h);
                x += w + gap;
                lineH = Math.max(lineH, h);
            }
        }
    }

    // =========================================================================================
    // Continuous update (log status and diagnostics)
    // =========================================================================================

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (diagText == null) return;
            if (diagText != null) {
                String d = Diag.dump();
                diagText.setText(d.isEmpty() ? L.s(R.string.diag_waiting) : d);
            }
            if (isAttachedToWindow()) main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshTick.run();
        com.mg4.launcher.svc.LogoPacks.get(getContext()).addListener(logoListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        main.removeCallbacks(refreshTick);
        // 0.4.4 (fix A): the position is saved when these settings close, so if an option
        // recreates them all (theme, size, language, Home options) the new page restarts from there
        if (curScroll != null) {
            keptSection = curKey;
            keptY = curScroll.getScrollY();
            keptAt = SystemClock.uptimeMillis();
        }
        host.updater().setListener(null);
        com.mg4.launcher.svc.LogoPacks.get(getContext()).removeListener(logoListener);
    }
}
