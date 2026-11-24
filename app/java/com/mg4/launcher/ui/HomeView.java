package com.mg4.launcher.ui;

import android.content.Context;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.svc.Diag;
import com.mg4.launcher.svc.CarFeatures;
import com.mg4.launcher.svc.MediaClient;
import com.mg4.launcher.svc.NavClient;
import com.mg4.launcher.svc.VehicleClient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tesla-style home (MG4, SWI69 Color Radio): car + energy on the left, Map/Media/CarPlay panel on the right, bar at the bottom. */
public final class HomeView extends LinearLayout {

    /** Actions the home asks the activity to perform. */
    public interface Host {
        void openApps();

        void openSettings();

        void launch(String pkg, String cls);

        /** Car energy management on a tab: "ChargeManagementFragment" or "DischargeManagementFragment" (V2L). */
        void openEnergy(String module);

        void launchNavigator();

        void launchNavigator(String which);

        java.util.List<com.mg4.launcher.apps.AppCatalog.App> otherNavApps();

        /** False if the original app does not exist on this firmware (the button is disabled). */
        boolean canLaunch(String pkg, String cls);

        /** Full-screen container for the confirmations. */
        FrameLayout overlay();

        /** Cameras (360° / rear camera) like the original SWI69 home. */
        boolean openCamera();

        boolean canOpenCamera();
    }

    private static final int TAB_MAP = 0, TAB_MEDIA = 1, TAB_PHONE = 2;

    private final Theme t;
    private final Prefs prefs;
    private final Host host;
    private final VehicleClient vehicle;
    private final MediaClient media;
    private final NavClient nav;

    // car panel
    private TextView clockTv, dateTv;
    private FrameLayout acBtn, autoBtn;
    private FrameLayout frontFogBtn, rearFogBtn, recircBtn;
    private TextView rangeTv, rangeUnitTv, socTv, statusTv;
    private ImageView statusIcon;
    // charging buttons (option)
    private LinearLayout chargeRow;
    private View limitBtn, currentBtn, startBtn, unlockBtn, schedBtn;
    private TextView schedVal;
    private TextView limitVal, currentVal, startLabel, startVal, unlockVal;
    private VehicleClient.State lastVehicle = new VehicleClient.State();
    private Bars.Progress battery;
    private CarView car;
    private ChargeAnimView chargeAnim;

    // right panel
    private FrameLayout right;
    private View mapPanel, mediaPanel, phonePanel;
    private TextView tabMap, tabMedia, tabPhone;
    /**
     * 1.0.4 (new layout): base sizes (dp, they grow with "Dimensione comandi").
     * SRC_H source buttons (+15% compared to the previous 48), TABS_H Map/Media row (6 + 48 + 6),
     * FAV_H favorites row, BAND_H FM/DAB box (6 + 60 + 6).
     */
    private static final int SRC_H = 55, TABS_H = 60, FAV_H = 108, BAND_H = 72;
    /** 1.0.4: station list on the right, width (× control size) and row height (tp, + 2 dp of spacing). */
    private static final int STATION_W = 320, STATION_ROW = 62;
    private View tabsBox, favBottom;

    /** Distance of Map/Media from the bottom of the box: centered on the favorites row (which sits 20 from the bottom). */
    private int tabsBottom() {
        return dp(20) + Math.max(0, (tp(FAV_H) - tp(TABS_H)) / 2);
    }
    private LinearLayout connRow;
    private ImageView connBt, connWifi;
    private TextView connNet;
    private Bars.Signal connBars;
    private int tab = TAB_MEDIA;
    private boolean userPickedTab;
    private boolean wasNavigating;

    // map
    private LinearLayout navIdle, navCard, navEta;
    private TextView navCardTitle, navCardSub, navEtaArrive, navEtaMin, navEtaKm;
    private View navHomeBtn, navOfficeBtn;
    private LinearLayout navWith, navWithBox, navPlaces;
    private TextView navDefaultTv, navSearchTv;
    private ImageView navSearchIcon;
    private FrameLayout navGo;
    private boolean navWithPhone;

    // media
    private LinearLayout srcRow, stationPanel, stationList, stationEmpty, stationHead;
    private android.widget.ScrollView stationScroll;
    private TextView stationTitle, stationCount, stationSearch;
    private String stationSignature = "";
    private int stationBand = -1;
    private String srcSignature = "";
    private int pendingSource, pendingBand;
    private long pendingSourceUntil, pendingBandUntil;
    private TextView mTitle, mSub, mMeta, mPos, mDur, artFreq, artUnit, artBand;
    private ImageView artIcon, artLogo;
    /** Fork: bitmap currently shown on the large cover and in the mini-player (only set again when it changes). */
    private android.graphics.Bitmap artShown, miniShown;
    private LinearLayout artRadio, progressRow, bandRow, favRow;
    private View favScroll;
    private String favSignature = "";
    private Bars.Progress mProgress;
    private ImageView playIcon, miniPlayIcon;
    private TextView[] bandChips;
    // 1.0.2 (issue #25): FM/DAB band as a Radio sub-option, right below the sources row
    private LinearLayout bandBox, mediaCol;
    private LinearLayout mediaBody;
    /** Sources without label (icon only) when there is no room next to the top-right buttons. */
    private boolean srcCompact;
    /** 1.0.4: 0 normal spacing, 1 tight, 2 very tight, 3 icons only (last resort, never seen so far). */
    private int srcFit;
    private View topRightBox;
    /** 1.0.2 (issue #23): top-right button: 0 CarPlay/Android Auto, 1 chosen app, 2 none. */
    private int topMode;
    private String topPkg;

    // phone
    private TextView phoneTitle, phoneSub, phoneNavDist, phoneNavRoad;
    private LinearLayout phoneNav;

    // bar
    private TextView tempTv, miniTitle, miniSub;
    private ImageView miniArt;

    private int acTemp = -1;
    private int fanLevel = -1, fanMin = 1, fanMax = 11;
    private long localTempUntil, localFanUntil;
    private TextView fanTv, outTv, inTv;
    private VehicleClient.State lastInsideState;
    private LinearLayout tpmsRow, tpmsFront, tpmsRear;
    private FrameLayout seatLeftBtn, seatRightBtn, wheelBtn;
    // 0.8.2: Climate / Comfort, LKA and OPD groups
    private boolean grouped;
    private FrameLayout climateGroup, comfortGroup;
    private LinearLayout climateDots, comfortDots;
    /** Drive group (0.9.5): LKA, One Pedal, ACC/TJA assistant and brake pedal in a single button. */
    private FrameLayout driveGroup, projBtn;
    private LinearLayout driveDots;
    private int recircIcon;
    private View barView;
    private GroupPopup popup;
    private ImageView outIcon;

    /** Space left free in the top left when SystemUI draws the lights indicator (0.4.2: measured, point 2). */
    private final int SYSTEM_LIGHT_INSET;
    /** Home mirrored (right-hand drive) and system lights indicator present. */
    private final boolean rhd, sysIndicator;
    /** Large bottom bar (option): fixed sizes in px, independent of "Dimensione comandi". */
    private final boolean big;

    public HomeView(Context c, Theme t, Prefs prefs, Host host, VehicleClient vehicle, MediaClient media, NavClient nav) {
        super(c);
        this.t = t;
        this.prefs = prefs;
        this.host = host;
        this.vehicle = vehicle;
        this.media = media;
        this.nav = nav;
        setOrientation(VERTICAL);
        setBackgroundColor(t.bg);
        int pad = dp(20);
        setPadding(pad, pad, pad, pad);

        // right-hand drive: from the setting, or as the car says
        boolean carRhd = CarFeatures.isRightHandDrive(c);
        String side = prefs.driveSide();
        rhd = Prefs.DRIVE_RIGHT.equals(side) || (Prefs.DRIVE_AUTO.equals(side) && carRhd);
        sysIndicator = CarFeatures.hasSystemLightIndicator(c) || carRhd;
        SYSTEM_LIGHT_INSET = CarFeatures.systemLightInset(c);
        big = prefs.bigBar();

        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.FILL_VERTICAL);
        addView(top, Ui.lpWeight(LayoutParams.MATCH_PARENT, 0, 1f));
        View carPanel = buildCarPanel(c);
        right = buildRightPanel(c);
        if (!rhd) {
            top.addView(carPanel, new LayoutParams(dp(720), LayoutParams.MATCH_PARENT));
            top.addView(right, Ui.margins(Ui.lpWeight(0, LayoutParams.MATCH_PARENT, 1f), dp(16), 0, 0, 0));
        } else {
            // car and climate on the driver's side
            top.addView(right, Ui.lpWeight(0, LayoutParams.MATCH_PARENT, 1f));
            top.addView(carPanel, Ui.margins(new LayoutParams(dp(720), LayoutParams.MATCH_PARENT), dp(16), 0, 0, 0));
        }
        addView(buildBottomBar(c), Ui.margins(new LayoutParams(LayoutParams.MATCH_PARENT, big ? dp(BIG_BAR) : tp(100)), 0, dp(14), 0, 0));

        applyCarLook();
        selectTab(TAB_MEDIA, false);
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    /** Size of a touchable control (grows with the control size). */
    private int tp(float v) {
        return Ui.tp(getContext(), v);
    }

    /** Text of a touchable control. */
    private static float ts(float sp) {
        return Ui.ts(sp);
    }

    /** True if the home is mirrored for right-hand drive. */
    public boolean isRightHand() {
        return rhd;
    }

    public void refreshNav() {
        buildNavWith(getContext(), navWithPhone);
    }

    public void applyCarLook() {
        car.setLook(Prefs.CAR_COLOR_VALUES[prefs.carColorIndex()], prefs.blackRoof(), prefs.wheelIndex(), t);
    }

    // =========================================================================================
    // Car panel
    // =========================================================================================

    private View buildCarPanel(Context c) {
        LinearLayout p = Ui.column(c);
        p.setBackground(Ui.pressable(c, t.panel, 24, t));
        p.setPadding(dp(32), dp(28), dp(32), dp(24));
        p.setClickable(true);
        p.setOnClickListener(v -> host.openEnergy("ChargeManagementFragment"));
        p.setOnLongClickListener(v -> {
            host.openSettings();
            return true;
        });

        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.BOTTOM);
        rangeTv = Ui.text(c, "—", 76, t.text, Ui.LIGHT);
        rangeTv.setLetterSpacing(-0.03f);
        head.addView(rangeTv);
        rangeUnitTv = Ui.text(c, prefs.socBig() ? "%" : "km", 26, t.sub, Ui.REGULAR);
        head.addView(rangeUnitTv, Ui.margins(Ui.lp(-2, -2), dp(14), 0, 0, dp(8)));
        head.addView(Ui.spacer(c));
        // date (option) between range and time, aligned to the baseline of the numbers
        dateTv = Ui.oneLine(Ui.text(c, "", 26, t.sub, Ui.REGULAR));
        dateTv.setVisibility(prefs.showDate() ? VISIBLE : GONE);
        head.addView(dateTv, Ui.margins(Ui.lp(-2, -2), dp(12), 0, dp(22), dp(8)));
        // clock, as large as the range
        clockTv = Ui.text(c, "", 76, t.text, Ui.LIGHT);
        clockTv.setLetterSpacing(-0.03f);
        head.addView(clockTv);
        // system lights indicator in the top left: the range starts further right
        if (sysIndicator && !rhd) head.setPadding(dp(SYSTEM_LIGHT_INSET), 0, 0, 0);
        p.addView(head);

        LinearLayout batRow = Ui.row(c);
        batRow.setGravity(Gravity.CENTER_VERTICAL);
        battery = new Bars.Progress(c);
        battery.setColors(t.track, t.battery);
        batRow.addView(battery, Ui.lpWeight(0, dp(8), 1));
        socTv = Ui.text(c, "—", 32, t.text, Ui.MEDIUM);   // 0.3.3: larger (was 24)
        batRow.addView(socTv, Ui.margins(Ui.lp(-2, -2), dp(16), 0, 0, 0));
        p.addView(batRow, Ui.margins(new LayoutParams(-1, -2), 0, dp(10), 0, 0));
        updateClock();

        LinearLayout status = Ui.row(c);
        // fixed height: at rest the row stays empty without moving the car
        status.setMinimumHeight(dp(34));
        // 0.4.2 (point 7a): compact outside temperature on the left (number and unit only), below the optional inside one
        outIcon = Ui.icon(c, R.drawable.ic_thermo, t.text, 26);
        outIcon.setVisibility(GONE);
        status.addView(outIcon);
        LinearLayout temps = Ui.column(c);
        temps.setGravity(Gravity.START);
        outTv = Ui.text(c, "", 26, t.text, Ui.REGULAR);
        temps.addView(outTv);
        inTv = Ui.text(c, "", 22, t.sub, Ui.REGULAR);
        inTv.setVisibility(showInside() ? VISIBLE : GONE);
        temps.addView(inTv);
        status.addView(temps, Ui.margins(Ui.lp(-2, -2), dp(6), 0, dp(22), 0));
        // charging status (only while charging or discharging) to the right of the temperature
        statusIcon = Ui.icon(c, R.drawable.ic_bolt, t.battery, 22);
        status.addView(statusIcon);
        statusTv = Ui.oneLine(Ui.text(c, L.s(R.string.connecting_vehicle), 20, t.sub, Ui.REGULAR));
        status.addView(statusTv, Ui.margins(Ui.lpWeight(0, -2, 1), dp(10), 0, 0, 0));
        status.setGravity(Gravity.TOP);
        p.addView(status, Ui.margins(new LayoutParams(-1, -2), 0, dp(14), 0, 0));

        car = new CarView(c);
        // 1.0.4 (point F): the charging animation sits above the car, in the same box
        FrameLayout carFrame = new FrameLayout(c);
        carFrame.addView(car, new FrameLayout.LayoutParams(-1, -1));
        chargeAnim = new ChargeAnimView(c);
        carFrame.addView(chargeAnim, new FrameLayout.LayoutParams(-1, -1));
        carFrame.setClipChildren(true);
        p.addView(carFrame, Ui.margins(Ui.lpWeight(-1, 0, 1), 0, dp(4), 0, dp(4)));

        // tire pressure below the car's wheels (front on the left, rear on the right)
        tpmsRow = Ui.row(c);
        tpmsRow.addView(new View(c), Ui.lpWeight(0, 1, 0.09f));
        tpmsFront = tpmsBlock(c, L.s(R.string.tpms_front));   // 0.4.2 (point 8a): title for accessibility only
        tpmsRow.addView(tpmsFront, Ui.lpWeight(0, -2, 0.32f));
        tpmsRow.addView(new View(c), Ui.lpWeight(0, 1, 0.20f));
        tpmsRear = tpmsBlock(c, L.s(R.string.tpms_rear));
        tpmsRow.addView(tpmsRear, Ui.lpWeight(0, -2, 0.32f));
        tpmsRow.addView(new View(c), Ui.lpWeight(0, 1, 0.07f));
        // the pressure space is always reserved (even before the data arrives): the car does not change size
        tpmsRow.setVisibility(prefs.showTpms() ? INVISIBLE : GONE);
        p.addView(tpmsRow, Ui.margins(new LayoutParams(-1, -2), 0, 0, 0, dp(10)));

        // charging buttons (option): limit, AC current, start/stop; off = more room for the car
        if (prefs.chargeButtons()) {
            chargeRow = Ui.row(c);
            limitBtn = chargeButton(c, chargeRow, L.s(R.string.cb_limit), true, v -> pickLimit());
            limitVal = (TextView) ((ViewGroup) limitBtn).getChildAt(1);
            currentBtn = chargeButton(c, chargeRow, L.s(R.string.cb_current), true, v -> pickCurrent());
            currentVal = (TextView) ((ViewGroup) currentBtn).getChildAt(1);
            // 0.4.2 (point 7d): scheduled charging, only if the car supports it
            schedBtn = chargeButton(c, chargeRow, L.s(R.string.cb_sched), true, v -> pickSchedule());
            schedVal = (TextView) ((ViewGroup) schedBtn).getChildAt(1);
            schedBtn.setVisibility(GONE);
            startBtn = chargeButton(c, chargeRow, L.s(R.string.cb_charge), true, v -> startStop());
            startLabel = (TextView) ((ViewGroup) startBtn).getChildAt(0);
            startVal = (TextView) ((ViewGroup) startBtn).getChildAt(1);
            unlockBtn = chargeButton(c, chargeRow, L.s(R.string.cb_unlock), false, v -> unlockSocket());
            unlockVal = (TextView) ((ViewGroup) unlockBtn).getChildAt(1);
            p.addView(chargeRow, new LayoutParams(-1, -2));
            styleChargeButtons(new VehicleClient.State());
        }
        return p;
    }

    /** Charging button: small title + value; tap to change. */
    private View chargeButton(Context c, LinearLayout parent, String label, boolean gap, View.OnClickListener l) {
        LinearLayout ch = Ui.column(c);
        ch.setBackground(Ui.pressable(c, t.chip, 14, t));
        ch.setPadding(dp(16), dp(12), dp(16), dp(12));
        ch.setMinimumHeight(tp(72));
        ch.setGravity(Gravity.CENTER_VERTICAL);
        ch.addView(Ui.oneLine(Ui.text(c, label, 14, t.sub, Ui.REGULAR)));
        TextView v = Ui.oneLine(Ui.text(c, "—", ts(20), t.text, Ui.MEDIUM));
        ch.addView(v, Ui.margins(new LayoutParams(-2, -2), 0, dp(4), 0, 0));
        ch.setClickable(true);
        ch.setContentDescription(label);
        ch.setOnClickListener(l);
        parent.addView(ch, Ui.margins(Ui.lpWeight(0, -2, 1), 0, 0, gap ? dp(10) : 0, 0));
        return ch;
    }

    private void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setClickable(on);
        v.setAlpha(on ? 1f : 0.45f);
    }

    private static final String[] LIMIT_LABELS = {"40%", "50%", "60%", "70%", "80%", "90%", "100%"};

    private void styleChargeButtons(VehicleClient.State s) {
        if (chargeRow == null) return;
        boolean ok = s.connected;
        limitVal.setText(s.chargeLimitCode >= 1 ? LIMIT_LABELS[s.chargeLimitCode - 1] : "—");
        enable(limitBtn, ok && s.chargeLimitCode >= 1);
        currentVal.setText(s.currentLimit >= 1 ? VehicleClient.CURRENT_LABELS[s.currentLimit - 1] : "—");
        enable(currentBtn, ok && s.currentLimit >= 1);
        int mode = VehicleClient.plugMode(s);
        boolean v2l = mode == 2;
        int ctrl = v2l ? s.dischargeControl : s.chargeControl;
        startLabel.setText(L.s(v2l ? R.string.cb_v2l : R.string.cb_charge));
        boolean running = v2l ? ctrl == VehicleClient.CTRL_START || VehicleClient.isDischarging(s.chargingStatus)
                : ctrl == VehicleClient.CTRL_START;
        boolean preparing = ctrl == VehicleClient.CTRL_PREPARING;
        String val;
        if (mode == 0) val = L.s(R.string.cb_unplugged);
        else if (preparing) val = L.s(R.string.cb_preparing);
        else val = L.s(running ? R.string.cb_stop : R.string.cb_start);
        startVal.setText(val);
        // like the original: command possible only with the battery ready and the cable/socket connected
        boolean can = ok && mode != 0 && !preparing && s.bmsAvailable;
        enable(startBtn, can);
        startBtn.setBackground(Ui.pressable(getContext(), can && running ? t.text : t.chip, 14, t));
        startLabel.setTextColor(can && running ? t.panel : t.sub);
        startVal.setTextColor(can && running ? t.panel : t.text);
        // 0.4.0 (point 6): always visible and clickable; if the state is unknown it explains why
        if (schedBtn != null) {
            schedBtn.setVisibility(s.schedMode >= 1 ? VISIBLE : GONE);
            schedVal.setText(s.schedMode == 2 || s.schedMode < 1 ? L.s(R.string.sched_off)
                    : s.schedMode == 3 ? hm(s.schedStartH, s.schedStartM) + " → " + L.s(R.string.sched_to_limit)
                    : hm(s.schedStartH, s.schedStartM) + " – " + hm(s.schedEndH, s.schedEndM));
            enable(schedBtn, ok);
        }
        unlockVal.setText(s.chargeLock == 1 ? L.s(R.string.cb_unlock_locked) : s.chargeLock == 2 ? L.s(R.string.cb_unlock_unlocked) : "—");
    }

    /** 0.4.0 (point 6): unlocks the charging socket, with confirmation; explains why if the state is not known yet. */
    private void unlockSocket() {
        VehicleClient.State s = lastVehicle;
        if (s == null || !s.connected || s.chargeLock <= 0) {
            Diag.put("charge.unlockPort", "not sent: socket state not read");
            Confirm.notice(host.overlay(), t, L.s(R.string.cb_unlock_title), "", new String[]{L.s(R.string.cb_unlock_wait)}, L.s(R.string.btn_ok), null);
            return;
        }
        if (s.chargeLock == 2) {   // like the original: unlocking starts only if the socket is locked
            Diag.put("charge.unlockPort", "not sent: socket already unlocked");
            Confirm.notice(host.overlay(), t, L.s(R.string.cb_unlock_title), "", new String[]{L.s(R.string.cb_unlock_unlocked)}, L.s(R.string.btn_ok), null);
            return;
        }
        Confirm.show(host.overlay(), t, L.s(R.string.cb_unlock_title), L.s(R.string.cb_unlock_msg),
                L.s(R.string.btn_cancel), L.s(R.string.cb_unlock), () -> vehicle.unlockChargingSocket());
    }

    private static String hm(int h, int m) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", Math.max(0, h), Math.max(0, m));
    }

    /** 0.4.2 (point 7d): Off / from–to / up to the limit, and changing the times (like Charge management). */
    private void pickSchedule() {
        VehicleClient.State s = lastVehicle;
        if (s == null || s.schedMode < 1) return;
        String[] opts = {L.s(R.string.sched_off), hm(s.schedStartH, s.schedStartM) + " – " + hm(s.schedEndH, s.schedEndM),
                L.s(R.string.sched_to_limit), L.s(R.string.sched_time)};
        int sel = s.schedMode == 2 ? 0 : s.schedMode == 1 ? 1 : 2;
        Confirm.pick(host.overlay(), t, L.s(R.string.cb_sched_title), L.s(R.string.cb_sched_msg), opts, sel, L.s(R.string.btn_cancel), i -> {
            if (i == 3) pickScheduleTimes();
            else vehicle.setScheduleMode(i == 0 ? 2 : i == 1 ? 1 : 3);
        });
    }

    private void pickScheduleTimes() {
        VehicleClient.State s = lastVehicle;
        Confirm.time(host.overlay(), t, L.s(R.string.sched_start), s.schedStartH, s.schedStartM, L.s(R.string.btn_cancel), L.s(R.string.btn_ok),
                (sh, sm) -> Confirm.time(host.overlay(), t, L.s(R.string.sched_end), s.schedEndH, s.schedEndM, L.s(R.string.btn_cancel), L.s(R.string.btn_ok),
                        (eh, em) -> {
                            vehicle.setScheduleTimes(sh, sm, eh, em);
                            if (lastVehicle.schedMode == 2) vehicle.setScheduleMode(1);   // setting a time turns it on
                        }));
    }

    private void pickLimit() {
        VehicleClient.State s = lastVehicle;
        Confirm.pick(host.overlay(), t, L.s(R.string.cb_limit_title), L.s(R.string.cb_limit_msg), LIMIT_LABELS,
                s.chargeLimitCode - 1, L.s(R.string.btn_cancel), i -> vehicle.setChargeLimitCode(i + 1));
    }

    private void pickCurrent() {
        VehicleClient.State s = lastVehicle;
        Confirm.pick(host.overlay(), t, L.s(R.string.cb_current_title), L.s(R.string.cb_current_msg), VehicleClient.CURRENT_LABELS,
                s.currentLimit - 1, L.s(R.string.btn_cancel), i -> vehicle.setCurrentLimit(i + 1));
    }

    private void startStop() {
        VehicleClient.State s = lastVehicle;
        int mode = VehicleClient.plugMode(s);
        if (mode == 0) return;
        boolean v2l = mode == 2;
        int ctrl = v2l ? s.dischargeControl : s.chargeControl;
        boolean running = v2l ? ctrl == VehicleClient.CTRL_START || VehicleClient.isDischarging(s.chargingStatus)
                : ctrl == VehicleClient.CTRL_START;
        if (v2l && !running && s.dischargeLimit > 0 && s.soc >= 0 && s.dischargeLimit > s.soc) {
            // like the original: the discharge limit is above the current battery level, V2L would not start
            Confirm.show(host.overlay(), t, L.s(R.string.cb_v2l_title), L.f(R.string.cb_v2l_low, s.dischargeLimit + "%"),
                    L.s(R.string.btn_cancel), L.s(R.string.cb_open_energy), () -> host.openEnergy("DischargeManagementFragment"));
            return;
        }
        int title = v2l ? (running ? R.string.cb_v2l_stop_title : R.string.cb_v2l_start_title)
                : (running ? R.string.cb_charge_stop_title : R.string.cb_charge_start_title);
        int msg = v2l ? (running ? R.string.cb_v2l_stop_msg : R.string.cb_v2l_start_msg)
                : (running ? R.string.cb_charge_stop_msg : R.string.cb_charge_start_msg);
        Confirm.show(host.overlay(), t, L.s(title), L.s(msg), L.s(R.string.btn_cancel), L.s(running ? R.string.cb_stop : R.string.cb_start), () -> {
            if (v2l) vehicle.setDischarging(!running);
            else vehicle.setCharging(!running);
        });
    }

    /** 0.4.2 (point 3): inside temperature back as an option (Settings › Home); "--" if the car does not provide it. */
    /** 0.4.3: Show always; Automatic only if the car really provides it; Hide never. */
    private boolean showInside() {
        String m = prefs.insideTempMode();
        if (Prefs.SHOW_ON.equals(m)) return true;
        if (!Prefs.SHOW_AUTO.equals(m) || prefs.insideTempMissing()) return false;
        VehicleClient.State st = lastInsideState;
        return st != null && !Float.isNaN(st.insideTemp) && !st.insideSource.isEmpty();
    }

    private final long createdAt = System.currentTimeMillis();

    /** 360° button: hidden when the car configuration says there are no cameras (only for "360 originale"). */
    private View cameraBtn;
    private int camKind = Cam360.ORIGINAL;

    public void onVehicle(VehicleClient.State s) {
        if (cameraBtn != null) {
            int vis = camKind == Cam360.HIDDEN ? GONE
                    : camKind == Cam360.ORIGINAL && Boolean.FALSE.equals(VehicleClient.cameraPresent(s.cameraConfig)) ? GONE : VISIBLE;
            if (cameraBtn.getVisibility() != vis) cameraBtn.setVisibility(vis);
        }
        if (!s.connected) {
            boolean early = System.currentTimeMillis() - createdAt < 12000;
            statusTv.setText(L.s(early ? R.string.connecting_vehicle : R.string.vehicle_unavailable));
            statusIcon.setImageResource(R.drawable.ic_info);
            statusIcon.setColorFilter(t.sub);
            return;
        }
        lastVehicle = s;
        // 0.4.0: the range always arrives in km; in miles it is converted (before, only the label changed)
        boolean mi = Units.miles(prefs, s.distUnit);
        String km = s.rangeKm > 0 && s.rangeKm < 2000 ? String.valueOf(Units.range(s.rangeKm, mi)) : "—";
        String unit = mi ? "mi" : "km";
        boolean socOk = s.soc >= 0 && s.soc <= 100;
        String soc = prefs.socDecimals() ? String.format(L.locale(), "%.1f", s.soc) : String.valueOf(Math.round(s.soc));
        if (prefs.socBig()) {
            // 0.3.4: large percentage, range next to the bar
            if (socOk) rangeTv.setText(soc);
            rangeUnitTv.setText("%");
            socTv.setText("—".equals(km) ? "—" : km + " " + unit);
        } else {
            rangeTv.setText(km);
            rangeUnitTv.setText(unit);
            if (socOk) socTv.setText(soc + "%");
        }
        if (socOk) battery.setValue(s.soc / 100f);
        battery.setLimit(s.chargeLimitShown && s.chargeLimit > 0 && s.chargeLimit < 100 ? s.chargeLimit / 100f : -1);
        // status only while charging or discharging (type, power, remaining time); at rest the space stays empty
        String st = chargeText(s);
        statusTv.setText(st);
        statusIcon.setVisibility(st.isEmpty() ? INVISIBLE : VISIBLE);
        statusIcon.setImageResource(VehicleClient.isDischarging(s.chargingStatus) ? R.drawable.ic_plug : R.drawable.ic_bolt);
        statusIcon.setColorFilter(t.battery);
        styleChargeButtons(s);
        if (s.acTemp > 0 && SystemClock.uptimeMillis() > localTempUntil) {
            acTemp = s.acTemp;
            tempTv.setText(tempLabel(acTemp));
        }
        boolean outOk = s.outsideTemp > -60 && s.outsideTemp < 70;
        outIcon.setVisibility(outOk ? VISIBLE : GONE);
        // like the SWI68: number and unit only ("12°C"), without the "esterni" label
        outTv.setText(outOk ? Math.round(s.outsideTemp) + "°C" : "");
        outTv.setVisibility(outOk ? VISIBLE : GONE);
        // inside temperature (option): "--" if the car does not provide a valid value; never 0° and never the outside one
        lastInsideState = s;
        boolean inShow = showInside();
        boolean inOk = !Float.isNaN(s.insideTemp) && !s.insideSource.isEmpty();
        inTv.setVisibility(inShow ? VISIBLE : GONE);
        if (inShow) inTv.setText(L.f(R.string.inside_temp, inOk ? String.valueOf(Math.round(s.insideTemp)) : "--"));
        if (inShow && !outOk) outIcon.setVisibility(VISIBLE);
        showTpms(s.tireBar, s.tyreUnitCluster);
        // 0.4.2 (point 7b): headlights on, drawn on the car (if the state cannot be read the car stays as it is)
        boolean known = s.lowBeam >= 0 || s.sideLight >= 0;
        car.setLights(known && (s.lowBeam == 1 || s.sideLight == 3 || s.lightSwitch == 1));
        // 0.4.4 (from SWI68 1.0.4): charging animation on the drawn car
        updateChargeAnim(s);
        // 0.4.2 (point 7d): charge limit only if the car supports it (or by choice)
        if (limitBtn != null) limitBtn.setVisibility(s.chargeLimitShown ? VISIBLE : GONE);
        // 0.4.2 (point 9): heating buttons only if the car has them (or by choice)
        if (!grouped) {
            seatLeftBtn.setVisibility(s.seatLeft >= 0 ? VISIBLE : GONE);
            seatRightBtn.setVisibility(s.seatRight >= 0 ? VISIBLE : GONE);
            wheelBtn.setVisibility(s.wheelHeat >= 0 ? VISIBLE : GONE);
        }
        if (s.fanLevel >= 0 && SystemClock.uptimeMillis() > localFanUntil) {
            fanLevel = s.fanLevel;
            fanTv.setText(fanLabel(fanLevel));
        }
        fanMin = s.fanMin;
        fanMax = s.fanMax;
        styleClimate(acBtn, s.acOn < 0 ? 3 : s.acOn == 1 ? 1 : 0);
        styleClimate(autoBtn, s.autoOn < 0 ? 3 : s.autoOn == 1 ? 1 : 0);
        styleHeat(seatLeftBtn, s.seatLeft);
        styleHeat(seatRightBtn, s.seatRight);
        styleClimate(wheelBtn, s.wheelHeat < 0 ? 3 : s.wheelHeat == 1 ? 2 : 0);
        frontFogBtn.setVisibility(s.hasFrontFog ? VISIBLE : GONE);
        // recirculation: hidden if the car does not support it (reading -1); real state (inside/automatic = on)
        recircBtn.setVisibility(s.loopMode >= 0 ? VISIBLE : GONE);
        styleClimate(recircBtn, s.loopMode < 0 ? 3 : ClimateIcon.recircOnIcon(s.loopMode) ? 1 : 0);
        setRecircIcon(s.loopMode);
        styleToggle(frontFogBtn, s.frontFog, t.fogFront);
        styleToggle(rearFogBtn, s.rearFog, t.warn);
        if (grouped) styleGroups(s);
        styleDrive(s);
        if (popup != null) popup.refresh(s);
    }

    /** Status text: only while charging or discharging (type · power · remaining time), otherwise empty. */
    private String chargeText(VehicleClient.State s) {
        String kw = s.chargeKw > 0.3f ? String.format(L.locale(), " · %.1f kW", s.chargeKw) : "";
        switch (s.chargingStatus) {
            case 1: return L.s(R.string.charge_charging) + kw + remaining(s.predictMinutes);
            case 3: return L.s(R.string.charge_balancing) + kw + remaining(s.predictMinutes);
            case 10: return L.s(R.string.charge_fast) + kw + remaining(s.predictMinutes);
            case 11: return s.chargeKw > 0.3f ? L.s(R.string.charge_fast) + kw + remaining(s.predictMinutes) : "";
            case 16: return L.s(R.string.charge_wireless) + kw + remaining(s.predictMinutes);
            case 13: return L.s(R.string.v2l_active) + kw + remaining(s.dischargeMinutes);
            default: return "";
        }
    }

    /** " · 1 h 25 min" (remaining time), empty if not available. */
    private static String remaining(int minutes) {
        if (minutes <= 0 || minutes >= 24 * 60) return "";
        int h = minutes / 60, m = minutes % 60;
        return " · " + (h > 0 ? L.f(R.string.time_h_min, h, m) : L.f(R.string.time_min, m));
    }

    /** Updates the clock (called every minute by the activity). */
    public void updateClock() {
        if (clockTv == null) return;
        boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
        Date now = new Date();
        clockTv.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", L.locale()).format(now));
        if (dateTv != null && dateTv.getVisibility() == VISIBLE) dateTv.setText(shortDate(now));
    }

    /** "Ven 16 ott" in the chosen language (abbreviated day and month, without dots). */
    public static String shortDate(Date d) {
        Locale l = L.locale();
        String day = new SimpleDateFormat("EEE", l).format(d).replace(".", "");
        String mon = new SimpleDateFormat("LLL", l).format(d).replace(".", "");
        String num = new SimpleDateFormat("d", l).format(d);
        day = day.isEmpty() ? day : day.substring(0, 1).toUpperCase(l) + day.substring(1);
        return day + " " + num + " " + mon.toLowerCase(l);
    }

    /** Indicators in the top right: visible only if there is something to show. */
    public void onConn(com.mg4.launcher.svc.ConnClient.State s) {
        connBt.setVisibility(s.btOn ? VISIBLE : GONE);
        connBt.setAlpha(s.btConnected ? 1f : 0.4f);
        connWifi.setVisibility(s.wifiConnected ? VISIBLE : GONE);
        connWifi.setAlpha(0.45f + 0.55f * s.wifiLevel / 3f);
        boolean net = s.mobileType >= 0;
        connNet.setVisibility(net ? VISIBLE : GONE);
        connBars.setVisibility(net ? VISIBLE : GONE);
        connNet.setText(s.mobileType >= 2 ? s.mobileType + "G" : "—");
        connBars.setLevel(s.mobileType >= 2 ? s.mobileLevel : 0);
        // margin of the first visible element
        boolean first = true;
        for (View v : new View[]{connBt, connWifi, connNet}) {
            if (v.getVisibility() != VISIBLE) continue;
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            lp.leftMargin = first ? 0 : dp(12);
            v.setLayoutParams(lp);
            first = false;
        }
        connRow.setVisibility(s.btOn || s.wifiConnected || net ? VISIBLE : GONE);
    }

    // =========================================================================================
    // Right panel
    // =========================================================================================

    private FrameLayout buildRightPanel(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.round(t.panel, dp(24)));
        f.setClipToOutline(true);

        mapPanel = buildMapPanel(c);
        mediaPanel = buildMediaPanel(c);
        phonePanel = buildPhonePanel(c);
        f.addView(mapPanel, new FrameLayout.LayoutParams(-1, -1));
        f.addView(mediaPanel, new FrameLayout.LayoutParams(-1, -1));
        f.addView(phonePanel, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout tabs = Ui.row(c);
        tabs.setBackground(Ui.round(t.overlay, dp(16)));
        // fork: no vertical padding; each tab takes the full height (60 instead of 48) and the background is drawn with
        // 6 dp margin top and bottom (InsetDrawable in styleTab): the look stays the same and the touch area grows
        tabs.setPadding(dp(6), 0, dp(6), 0);
        tabMap = addTab(c, tabs, L.s(R.string.tab_map), TAB_MAP);
        tabMedia = addTab(c, tabs, L.s(R.string.tab_media), TAB_MEDIA);
        tabPhone = addTab(c, tabs, "CarPlay", TAB_PHONE);
        // 0.9.6: a single tap opens CarPlay / Android Auto (before, the first tap only showed the tab)
        // 1.0.4 (point E5): CarPlay / Android Auto are no longer a tab: they appear in the bottom bar on the right
        // with the original icon. The third tab remains only for the app chosen in the settings (issue #23).
        tabPhone.setVisibility(GONE);
        // 1.0.2 (issue #23): the top-right button is chosen in the settings (CarPlay/AA as before, an app, none)
        String top = prefs.topButton();
        topMode = Prefs.TOP_NONE.equals(top) ? 2 : top.startsWith(Prefs.TOP_APP) ? 1 : 2;
        if (topMode == 1) {
            topPkg = top.substring(Prefs.TOP_APP.length());
            String label = Cam360.label(c, topPkg);
            // 1.0.3: if the chosen app is gone (or does not open) the default CarPlay/Android Auto button comes back
            boolean installed = Cam360.installed(c, topPkg) && Cam360.launchable(c, topPkg);
            if (installed) {
                tabPhone.setText(label);
                tabPhone.setContentDescription(label);
                tabPhone.setOnClickListener(v -> openTopApp());
                tabPhone.setVisibility(VISIBLE);
            } else {
                topMode = 2;
                topPkg = null;
            }
        }
        com.mg4.launcher.svc.Diag.put("home.topRightButton", topMode == 1 ? "app " + topPkg : "none"
                + (top.startsWith(Prefs.TOP_APP) ? " (chosen app not installed)" : "") + " · CarPlay/Android Auto in the bottom bar");
        // Bluetooth / Wi-Fi / 4G indicators in the top right, as tall as the source buttons
        LinearLayout topRight = Ui.row(c);
        topRight.setGravity(Gravity.CENTER_VERTICAL);
        connRow = Ui.row(c);
        connRow.setGravity(Gravity.CENTER_VERTICAL);
        connRow.setPadding(dp(14), 0, dp(14), 0);
        connRow.setBackground(Ui.round(t.overlay, dp(16)));
        connBt = Ui.icon(c, R.drawable.ic_bluetooth, t.text, 22);
        connRow.addView(connBt, new LayoutParams(dp(22), dp(22)));
        connWifi = Ui.icon(c, R.drawable.ic_wifi, t.text, 24);
        connRow.addView(connWifi, Ui.margins(new LayoutParams(dp(24), dp(24)), dp(12), 0, 0, 0));
        connNet = Ui.text(c, "4G", 14, t.text, Ui.MEDIUM);
        connRow.addView(connNet, Ui.margins(new LayoutParams(-2, -2), dp(12), 0, 0, 0));
        connBars = new Bars.Signal(c);
        connBars.setColors(t.text, t.track);
        connRow.addView(connBars, Ui.margins(new LayoutParams(dp(22), dp(18)), dp(5), 0, 0, dp(2)));
        connRow.setVisibility(GONE);
        topRight.addView(connRow, new LayoutParams(-2, tp(SRC_H)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(20), dp(20), 0);
        f.addView(topRight, lp);
        // 1.0.4 (point E2): Map/Media moves down to the bottom left, on the favorites row and before them. It sits above
        // all boxes (Map and Media), always in the same spot: centered on the favorites row (FAV_H tall).
        // The top order from 1.0.3 is no longer needed (only the sources stay at the top) and the option was removed.
        int inset = rhd && sysIndicator ? dp(SYSTEM_LIGHT_INSET) : 0;
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tl.setMargins(dp(28) + inset, 0, 0, tabsBottom());
        f.addView(tabs, tl);
        tabsBox = tabs;
        // the favorites start right after Map/Media
        tabs.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            if (favBottom == null || r - l == or - ol) return;
            android.view.ViewGroup.MarginLayoutParams fl = (android.view.ViewGroup.MarginLayoutParams) favScroll.getLayoutParams();
            int want = inset + (r - l) + dp(16);
            if (fl.leftMargin != want) {
                fl.leftMargin = want;
                favScroll.setLayoutParams(fl);
            }
        });
        // Map: title, search and arrival stay above the Map/Media row
        int above = tabsBottom() + tp(TABS_H) + dp(12);
        navIdle.setPadding(navIdle.getPaddingLeft(), navIdle.getPaddingTop(), navIdle.getPaddingRight(), above);
        FrameLayout.LayoutParams el = (FrameLayout.LayoutParams) navEta.getLayoutParams();
        el.bottomMargin = above;
        navEta.setLayoutParams(el);
        topRightBox = topRight;
        topRight.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            if (l != ol || r != or) fitSources();
        });
        return f;
    }

    private void openTopApp() {
        if (topPkg == null) return;
        try {
            android.content.Intent i = getContext().getPackageManager().getLaunchIntentForPackage(topPkg);
            if (i != null) getContext().startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            com.mg4.launcher.svc.Diag.error("home.topRightButton", e.toString());
        }
    }

    private TextView addTab(Context c, LinearLayout parent, String label, int which) {
        TextView tv = Ui.text(c, label, ts(18), t.sub, Ui.MEDIUM);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(tp(22), 0, tp(22), 0);
        int h = tp(TABS_H - 12) + 2 * dp(6);
        tv.setMinHeight(h);
        tv.setClickable(true);
        tv.setOnClickListener(v -> selectTab(which, true));
        parent.addView(tv, Ui.margins(Ui.lp(-2, h), which == 0 ? 0 : dp(6), 0, 0, 0));
        return tv;
    }

    private void selectTab(int which, boolean byUser) {
        tab = which;
        if (byUser) userPickedTab = true;
        mapPanel.setVisibility(which == TAB_MAP ? VISIBLE : GONE);
        mediaPanel.setVisibility(which == TAB_MEDIA ? VISIBLE : GONE);
        phonePanel.setVisibility(which == TAB_PHONE ? VISIBLE : GONE);
        styleTab(tabMap, which == TAB_MAP);
        styleTab(tabMedia, which == TAB_MEDIA);
        styleTab(tabPhone, which == TAB_PHONE);
    }

    private void styleTab(TextView tv, boolean on) {
        android.graphics.drawable.Drawable bg = on ? Ui.round(t.text, dp(12)) : Ui.pressable(getContext(), 0, 12, t);
        tv.setBackground(new android.graphics.drawable.InsetDrawable(bg, 0, dp(6), 0, dp(6)));
        tv.setPadding(tp(22), 0, tp(22), 0);   // a background with margins replaces the padding: restore the one from addTab
        tv.setTextColor(on ? t.panel : t.sub);
    }

    // ---- map ---------------------------------------------------------------------------------

    private View buildMapPanel(Context c) {
        FrameLayout f = new FrameLayout(c);
        Bars.MapDecor decor = new Bars.MapDecor(c);
        decor.setColors(t.map, t.road, t.roadMinor);
        f.addView(decor, new FrameLayout.LayoutParams(-1, -1));

        // gradient veil from the left: the text stays readable over the decorative map
        View veil = new View(c);
        veil.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{withAlpha(t.panel, 0xF2), withAlpha(t.panel, 0xC8), withAlpha(t.panel, 0x00)}));
        f.addView(veil, new FrameLayout.LayoutParams(dp(820), -1));

        // no navigation: title, search bar, Home / Work, "Apri con"
        navIdle = Ui.column(c);
        navIdle.setGravity(Gravity.CENTER_VERTICAL);
        navIdle.setPadding(dp(48), dp(30), dp(40), dp(28));

        TextView q = Ui.text(c, L.s(R.string.where_to), 44, t.text, Ui.MEDIUM);
        q.setLetterSpacing(-0.01f);
        navIdle.addView(q);
        navDefaultTv = Ui.oneLine(Ui.text(c, "", 18, t.sub, Ui.REGULAR));
        navIdle.addView(navDefaultTv, Ui.margins(new LayoutParams(-2, -2), 0, dp(6), 0, 0));

        LinearLayout search = Ui.row(c);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(26), 0, dp(12), 0);
        search.setBackground(Ui.pressable(c, t.chip, 38, t));
        search.setClickable(true);
        // SWI69: no MG navigator, navigation goes through CarPlay / Android Auto: the bar opens the phone
        search.setContentDescription(L.s(R.string.nav_waiting));
        search.setOnClickListener(v -> host.launchNavigator());
        navSearchIcon = Ui.icon(c, R.drawable.ic_smartphone, t.sub, 28);
        search.addView(navSearchIcon, new LayoutParams(tp(28), tp(28)));
        navSearchTv = Ui.oneLine(Ui.text(c, L.s(R.string.nav_waiting), ts(22), t.sub, Ui.REGULAR));
        search.addView(navSearchTv, Ui.margins(Ui.lpWeight(0, -2, 1), dp(16), 0, dp(12), 0));
        FrameLayout go = new FrameLayout(c);
        go.setBackground(Ui.round(t.accent, tp(26)));
        go.addView(Ui.icon(c, R.drawable.ic_nav, t.onAccent, 26), new FrameLayout.LayoutParams(tp(26), tp(26), Gravity.CENTER));
        navGo = go;
        search.addView(go, new LayoutParams(tp(52), tp(52)));
        navIdle.addView(search, Ui.margins(new LayoutParams(dp(640), tp(76)), 0, dp(22), 0, 0));

        navPlaces = Ui.row(c);
        navHomeBtn = placeTile(c, R.drawable.ic_home, L.s(R.string.home_place), v -> nav.goHome());
        navOfficeBtn = placeTile(c, R.drawable.ic_work, L.s(R.string.work_place), v -> nav.goOffice());
        navPlaces.addView(navHomeBtn, Ui.margins(new LayoutParams(dp(312), tp(88)), 0, 0, dp(16), 0));
        navPlaces.addView(navOfficeBtn, new LayoutParams(dp(312), tp(88)));
        navIdle.addView(navPlaces, Ui.margins(new LayoutParams(-2, -2), 0, dp(16), 0, 0));

        // open with: MG / phone (CarPlay, Android Auto) / installed navigators (Waze, Google Maps...)
        navWithBox = Ui.column(c);
        LinearLayout wl = Ui.row(c);
        wl.setGravity(Gravity.BOTTOM);
        TextView wt = Ui.text(c, L.s(R.string.open_with).toUpperCase(L.locale()), 13, t.sub, Ui.MEDIUM);
        wt.setLetterSpacing(0.08f);
        wl.addView(wt);
        wl.addView(Ui.oneLine(Ui.text(c, "· " + L.s(R.string.open_with_hint), 13, t.sub, Ui.REGULAR)),
                Ui.margins(new LayoutParams(-2, -2), dp(8), 0, 0, 0));
        navWithBox.addView(wl);
        navWith = Ui.row(c);
        navWithBox.addView(navWith, Ui.margins(new LayoutParams(-2, -2), -dp(5), dp(8), 0, 0));
        navIdle.addView(navWithBox, Ui.margins(new LayoutParams(-2, -2), 0, dp(22), 0, 0));
        buildNavWith(c, false);
        f.addView(navIdle, new FrameLayout.LayoutParams(-1, -1));

        // navigation in progress: instruction in the top left + arrival at the bottom
        navCard = Ui.row(c);
        navCard.setBackground(Ui.round(t.overlay, dp(18)));
        navCard.setPadding(dp(22), dp(16), dp(26), dp(16));
        navCard.addView(Ui.icon(c, R.drawable.ic_turn, t.text, 44));
        LinearLayout txt = Ui.column(c);
        navCardTitle = Ui.oneLine(Ui.text(c, "", 30, t.text, Ui.MEDIUM));
        navCardSub = Ui.oneLine(Ui.text(c, "", 18, t.sub, Ui.REGULAR));
        txt.addView(navCardTitle);
        txt.addView(navCardSub, Ui.margins(new LayoutParams(-2, -2), 0, dp(4), 0, 0));
        navCard.addView(txt, Ui.margins(new LayoutParams(-2, -2), dp(18), 0, 0, 0));
        navCard.setClickable(true);
        navCard.setOnClickListener(v -> openNavigation());
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cl.setMargins(dp(20 + (rhd && sysIndicator ? SYSTEM_LIGHT_INSET : 0)), dp(20), dp(20), 0);
        f.addView(navCard, cl);

        navEta = Ui.row(c);
        navEta.setBackground(Ui.round(t.overlay, dp(16)));
        navEta.setPadding(dp(18), dp(12), dp(18), dp(12));
        navEtaArrive = Ui.text(c, "", 18, t.text, Ui.MEDIUM);
        navEtaMin = Ui.text(c, "", 18, t.text, Ui.MEDIUM);
        navEtaKm = Ui.text(c, "", 18, t.text, Ui.MEDIUM);
        navEta.addView(navEtaArrive);
        navEta.addView(navEtaMin, Ui.margins(new LayoutParams(-2, -2), dp(22), 0, 0, 0));
        navEta.addView(navEtaKm, Ui.margins(new LayoutParams(-2, -2), dp(22), 0, 0, 0));
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        el.setMargins(dp(20), 0, 0, dp(20));
        f.addView(navEta, el);
        navCard.setVisibility(GONE);
        navEta.setVisibility(GONE);

        // it is not a mini map but a shortcut: a label in the bottom right says so
        f.addView(shortcutHint(c, L.s(R.string.map_shortcut_hint)), hintLp());
        return f;
    }

    /** "Shortcut" label: icon + text on a veiled background. */
    private View shortcutHint(Context c, String text) {
        LinearLayout h = Ui.row(c);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackground(Ui.round(t.overlay, dp(14)));
        h.setPadding(dp(14), dp(8), dp(16), dp(8));
        h.addView(Ui.icon(c, R.drawable.ic_open, t.sub, 18), new LayoutParams(dp(18), dp(18)));
        h.addView(Ui.oneLine(Ui.text(c, text, 15, t.sub, Ui.REGULAR)), Ui.margins(new LayoutParams(-2, -2), dp(10), 0, 0, 0));
        return h;
    }

    private FrameLayout.LayoutParams hintLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(20), dp(20));
        return lp;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Home / Work box: icon in a circle + name + "Avvia percorso". */
    private LinearLayout placeTile(Context c, int icon, String label, View.OnClickListener l) {
        LinearLayout tile = Ui.row(c);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(16), 0, dp(18), 0);
        tile.setBackground(Ui.pressable(c, t.chip, 22, t));
        tile.setClickable(true);
        tile.setContentDescription(label);
        tile.setOnClickListener(l);
        FrameLayout ic = new FrameLayout(c);
        ic.setBackground(Ui.round(t.overlay, dp(28)));
        ic.addView(Ui.icon(c, icon, t.text, 26), new FrameLayout.LayoutParams(tp(26), tp(26), Gravity.CENTER));
        tile.addView(ic, new LayoutParams(tp(56), tp(56)));
        LinearLayout tx = Ui.column(c);
        tx.addView(Ui.oneLine(Ui.text(c, label, ts(21), t.text, Ui.MEDIUM)));
        tx.addView(Ui.oneLine(Ui.text(c, L.s(R.string.go_there), ts(15), t.sub, Ui.REGULAR)), Ui.margins(new LayoutParams(-2, -2), 0, dp(2), 0, 0));
        tile.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(16), 0, 0, 0));
        tile.addView(Ui.icon(c, R.drawable.ic_chev_right, t.sub, 22), new LayoutParams(dp(22), dp(22)));
        return tile;
    }

    /**
     * "Apri con" row and phone status. SWI69: no MG navigator, so no "MG" button; the phone
     * (CarPlay / Android Auto) when connected, plus the navigators installed in the car (Waze, Google Maps...).
     */
    private void buildNavWith(Context c, boolean phone) {
        navWithPhone = phone;
        navWith.removeAllViews();
        String pref = prefs.navApp();
        String phoneName = media.last().deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay";
        if (phone) addNavChip(c, phoneName, Prefs.NAV_PHONE, pref);
        String defName = phone ? phoneName : "";
        for (com.mg4.launcher.apps.AppCatalog.App a : host.otherNavApps()) {
            addNavChip(c, a.label, a.packageName, pref);
            if (a.packageName.equals(pref)) defName = a.label;
        }
        navDefaultTv.setText(phone ? L.f(R.string.nav_sub_phone, phoneName) : L.s(R.string.nav_sub_nophone));
        navSearchTv.setText(phone ? L.f(R.string.nav_open_on, defName.isEmpty() ? phoneName : defName) : L.s(R.string.nav_waiting));
        navSearchTv.setTextColor(phone ? t.text : t.sub);
        navSearchIcon.setColorFilter(phone ? t.text : t.sub);
        navGo.setAlpha(phone ? 1f : 0.35f);
        navWithBox.setVisibility(navWith.getChildCount() > 0 ? VISIBLE : GONE);
    }

    private void addNavChip(Context c, String label, String value, String pref) {
        boolean def = value.equals(pref) || (Prefs.NAV_AUTO.equals(pref) && value.equals(Prefs.NAV_PHONE));
        TextView chip = Ui.text(c, label, ts(16), def ? t.text : t.sub, Ui.MEDIUM);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(tp(18), 0, tp(18), 0);
        chip.setMinHeight(tp(44));
        chip.setBackground(Ui.pressable(c, def ? t.chip : t.overlay, 22, t));
        chip.setClickable(true);
        chip.setOnClickListener(v -> host.launchNavigator(value));
        chip.setOnLongClickListener(v -> {
            prefs.setNavApp(value);
            buildNavWith(getContext(), navWithPhone);
            return true;
        });
        navWith.addView(chip, Ui.margins(Ui.lp(-2, tp(44)), dp(5), 0, dp(5), 0));
        chip.setBackground(Ui.pressable(c, def ? t.text : t.chip, 22, t));
        chip.setTextColor(def ? t.panel : t.text);
    }

    private void openNavigation() {
        MediaClient.State m = media.last();
        if (m.deviceType != 0 && m.tbtActive) {
            media.openProjection();
            media.openRemoteNavigation();
        } else {
            host.launchNavigator();
        }
    }

    public void onNav(NavClient.State n) {
        MediaClient.State m = media.last();
        boolean remote = m.deviceType != 0 && m.tbtActive;
        boolean guiding = n.navigating || remote;
        navHomeBtn.setVisibility(n.connected && n.hasHome ? VISIBLE : GONE);
        navOfficeBtn.setVisibility(n.connected && n.hasOffice ? VISIBLE : GONE);
        navPlaces.setVisibility(n.connected && (n.hasHome || n.hasOffice) ? VISIBLE : GONE);
        navIdle.setVisibility(guiding ? GONE : VISIBLE);
        navCard.setVisibility(guiding ? VISIBLE : GONE);
        navEta.setVisibility(n.navigating && !remote && n.remainSeconds > 0 ? VISIBLE : GONE);
        if (remote) {
            navCardTitle.setText(m.tbtDistance.isEmpty() ? (m.deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay") : m.tbtDistance);
            navCardSub.setText(m.tbtRoad.isEmpty() ? L.s(R.string.nav_phone) : m.tbtRoad);
        } else if (n.navigating) {
            if (n.turnMeters >= 0) {
                navCardTitle.setText(km(n.turnMeters));
                String next = !n.turnText.isEmpty() ? n.turnText : n.road;
                navCardSub.setText(next.isEmpty() ? L.s(R.string.next_maneuver) : next);
            } else {
                navCardTitle.setText(n.road.isEmpty() ? L.s(R.string.navigating) : n.road);
                navCardSub.setText(n.remainMeters > 0 ? L.f(R.string.remaining, km(n.remainMeters)) : L.s(R.string.tap_open_nav));
            }
            if (n.remainSeconds > 0) {
                Date arrive = new Date(System.currentTimeMillis() + n.remainSeconds * 1000L);
                navEtaArrive.setText(L.f(R.string.arrival, new SimpleDateFormat("HH:mm", L.locale()).format(arrive)));
                navEtaMin.setText(Math.max(1, Math.round(n.remainSeconds / 60f)) + " min");
                navEtaKm.setText(n.remainMeters > 0 ? km(n.remainMeters) : "");
            }
        }
        // when navigation starts, show the map, unless the user chose another tab
        if (guiding && !wasNavigating && (!userPickedTab || tab != TAB_PHONE)) selectTab(TAB_MAP, false);
        wasNavigating = guiding;
    }

    private String km(int meters) {
        return Units.distance(meters, Units.miles(prefs, lastVehicle.distUnit));
    }

    // ---- media -------------------------------------------------------------------------------

    private View buildMediaPanel(Context c) {
        LinearLayout col = Ui.column(c);
        col.setPadding(dp(28), dp(20), dp(20), dp(20));
        mediaCol = col;

        // sources: one button each (Radio, USB, Bluetooth, Online, + CarPlay/Android Auto if connected)
        // 1.0.4 (point E4): larger buttons (+15%), each with its own background and a light border; no shared box
        srcRow = Ui.row(c);
        // right-hand drive with the lights indicator: the sources row starts further right (current space, SYSTEM_LIGHT_INSET)
        col.addView(srcRow, Ui.margins(new LayoutParams(-2, tp(SRC_H)), rhd && sysIndicator ? dp(SYSTEM_LIGHT_INSET) : 0, 0, 0, 0));
        srcRow.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            alignBand();
            if (r - l != or - ol) fitSources();
        });

        // source sub-options (currently FM/DAB of the Radio), below the Radio button and aligned to its left
        // edge. 1.0.4: removed the "L" line that linked the button to the bands. Visible only with the Radio active.
        bandBox = Ui.row(c);
        bandBox.setGravity(Gravity.CENTER_VERTICAL);
        // 1.0.4 (point E1): FM/DAB goes back below the Radio button (as in 1.0.2), with the size from 1.0.3
        bandBox.setVisibility(GONE);

        // body: on the left cover + controls + favorites, on the right (radio only) the list of stations found
        LinearLayout body = Ui.row(c);
        mediaBody = body;
        col.addView(body, Ui.margins(Ui.lpWeight(-1, 0, 1), 0, dp(10), 0, 0));
        LinearLayout left = Ui.column(c);
        body.addView(left, Ui.lpWeight(0, -1, 1));
        // 1.0.4: FM/DAB sits at the top of the left column, so the station list on the right starts at the same
        // height (right below the sources) and fills the space that used to stay empty
        left.addView(bandBox, Ui.margins(new LayoutParams(-2, tp(BAND_H) + dp(Ui.K > 1.2f ? 2 : 8)), 0, 0, 0, 0));

        LinearLayout main = Ui.row(c);
        main.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(main, Ui.margins(Ui.lpWeight(-1, 0, 1), dp(8), dp(Ui.K > 1.2f ? 0 : 4), 0, 0));

        FrameLayout art = new FrameLayout(c);
        art.setBackground(Ui.pressable(c, t.chip, 26, t));
        art.setClipToOutline(true);
        art.setClickable(true);
        art.setContentDescription(L.s(R.string.open_source_app));
        art.setOnClickListener(v -> openCurrentSourceApp());
        artIcon = Ui.icon(c, R.drawable.ic_music, t.sub, 84);
        art.addView(artIcon, new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER));
        artLogo = new ImageView(c);
        artLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artLogo.setPadding(dp(20), dp(20), dp(20), dp(20));
        art.addView(artLogo, new FrameLayout.LayoutParams(-1, -1));
        artRadio = Ui.column(c);
        artRadio.setGravity(Gravity.CENTER);
        artBand = Ui.text(c, "FM", 20, t.sub, Ui.MEDIUM);
        artBand.setLetterSpacing(0.12f);
        artBand.setGravity(Gravity.CENTER);
        artFreq = Ui.oneLine(Ui.text(c, "", 54, t.text, Ui.LIGHT));
        artFreq.setGravity(Gravity.CENTER);
        artUnit = Ui.oneLine(Ui.text(c, "MHz", 19, t.sub, Ui.REGULAR));
        artUnit.setGravity(Gravity.CENTER);
        artRadio.addView(artBand, new LayoutParams(-1, -2));
        artRadio.addView(artFreq, Ui.margins(new LayoutParams(-1, -2), 0, dp(2), 0, dp(2)));
        artRadio.addView(artUnit, new LayoutParams(-1, -2));
        artRadio.setPadding(dp(12), 0, dp(12), 0);
        art.addView(artRadio, new FrameLayout.LayoutParams(-1, -1));
        // with larger controls the cover shrinks a bit to leave room for the buttons
        int artSize = dp(Ui.K > 1.2f ? 160 : Math.round(208 - 110 * (Ui.K - 1)));   // 1.0.4: 160 in "Molto grande", fits without clipping
        main.addView(art, new LayoutParams(artSize, artSize));

        LinearLayout info = Ui.column(c);
        main.addView(info, Ui.margins(Ui.lpWeight(0, -2, 1), dp(28), 0, 0, 0));

        mMeta = Ui.oneLine(Ui.text(c, "", 17, t.sub, Ui.MEDIUM));
        mMeta.setLetterSpacing(0.06f);
        info.addView(mMeta, new LayoutParams(-1, -2));
        mTitle = Ui.oneLine(Ui.text(c, "", 40 * Math.min(Ui.K, 1.15f), t.text, Ui.MEDIUM));   // 1.0.4: grows with the size (up to "Grande": beyond that the play button would not fit)
        info.addView(mTitle, Ui.margins(new LayoutParams(-1, -2), 0, dp(4), 0, 0));
        mSub = Ui.oneLine(Ui.text(c, "", 21, t.sub, Ui.REGULAR));
        info.addView(mSub, Ui.margins(new LayoutParams(-1, -2), 0, dp(4), 0, 0));

        progressRow = Ui.column(c);
        mProgress = new Bars.Progress(c);
        mProgress.setColors(t.track, t.text);
        progressRow.addView(mProgress, new LayoutParams(-1, dp(6)));
        LinearLayout times = Ui.row(c);
        mPos = Ui.text(c, "", 16, t.sub, Ui.REGULAR);
        mDur = Ui.text(c, "", 16, t.sub, Ui.REGULAR);
        times.addView(mPos);
        times.addView(Ui.spacer(c));
        times.addView(mDur);
        progressRow.addView(times, Ui.margins(new LayoutParams(-1, -2), 0, dp(8), 0, 0));
        info.addView(progressRow, Ui.margins(new LayoutParams(-1, -2), 0, dp(16), 0, 0));

        // large controls (0.4.4: the FM/DAB band is back below the Radio button, as in SWI68 1.0.4)
        LinearLayout ctr = Ui.row(c);
        ctr.setGravity(Gravity.CENTER_VERTICAL);
        // 1.0.4: in "Molto grande" these keys stay as large as in "Grande": there is no vertical room for more
        // (before, the play key was clipped at the top and bottom)
        float cf = Math.min(Ui.K, 1.15f) / Ui.K;
        int prevSz = Math.round(64 * cf), playSz = Math.round(84 * cf);
        ctr.addView(Ui.iconButton(c, t, R.drawable.ic_prev, t.text, t.chip, prevSz, Math.round(28 * cf), 32 * cf, L.s(R.string.previous), v -> media.skip(false)));
        FrameLayout play = Ui.iconButton(c, t, R.drawable.ic_pause, t.panel, t.text, playSz, Math.round(36 * cf), 42 * cf, L.s(R.string.play_pause), v -> media.playPause());
        playIcon = (ImageView) play.getChildAt(0);
        int gap = dp(Ui.K > 1 ? 14 : 20);
        ctr.addView(play, Ui.margins(Ui.lp(tp(playSz), tp(playSz)), gap, 0, gap, 0));
        ctr.addView(Ui.iconButton(c, t, R.drawable.ic_next, t.text, t.chip, prevSz, Math.round(28 * cf), 32 * cf, L.s(R.string.next), v -> media.skip(true)));
        bandRow = Ui.row(c);
        bandRow.setBackground(Ui.round(t.chip, dp(18)));
        bandRow.setPadding(dp(6), dp(6), dp(6), dp(6));
        bandChips = new TextView[2];
        String[] bands = {"FM", "DAB"};
        int[] bandIds = {MediaClient.BAND_FM, MediaClient.BAND_DAB};
        for (int i = 0; i < 2; i++) {
            final int band = bandIds[i];
            TextView b = Ui.text(c, bands[i], ts(22), t.sub, Ui.MEDIUM);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(tp(80));
            b.setPadding(tp(18), 0, tp(18), 0);
            b.setClickable(true);
            b.setContentDescription(L.s(R.string.src_radio) + " " + bands[i]);
            b.setOnClickListener(v -> {
                pendingBand = band;
                pendingBandUntil = SystemClock.uptimeMillis() + 4000;
                styleBands(band);
                media.switchBand(band);
            });
            bandRow.addView(b, Ui.margins(Ui.lp(-2, tp(60)), i == 0 ? 0 : dp(4), 0, 0, 0));
            bandChips[i] = b;
        }
        bandBox.addView(bandRow, Ui.lp(-2, tp(BAND_H)));
        info.addView(ctr, Ui.margins(new LayoutParams(-1, -2), 0, dp(20), 0, 0));

        // radio favorites with logos (FM and DAB)
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(OVER_SCROLL_NEVER);
        favRow = Ui.row(c);
        favRow.setGravity(Gravity.CENTER_VERTICAL);
        favRow.setMinimumHeight(tp(FAV_H));   // 1.0.4: tiles (or the prompt when there are no favorites) centered on the row
        hs.addView(favRow);
        favScroll = hs;
        // 1.0.4 (point E2): bottom row FAV_H tall, always present: on the left there is Map/Media (on top, as an
        // overlay), then the radio favorites. With the other sources only Map/Media remains.
        FrameLayout bottom = new FrameLayout(c);
        bottom.addView(hs, new FrameLayout.LayoutParams(-1, -1));
        favBottom = bottom;
        left.addView(bottom, Ui.margins(new LayoutParams(-1, tp(FAV_H)), 0, dp(10), 0, 0));

        // list of stations found by the radio (current band)
        stationPanel = Ui.column(c);
        stationPanel.setBackground(Ui.round(t.overlay, dp(20)));
        stationPanel.setPadding(dp(8), dp(14), dp(8), dp(8));
        LinearLayout sh = Ui.row(c);
        sh.setGravity(Gravity.CENTER_VERTICAL);
        sh.setPadding(dp(12), 0, dp(10), dp(6));
        stationTitle = Ui.text(c, "", ts(15), t.sub, Ui.MEDIUM);
        stationTitle.setLetterSpacing(0.08f);
        sh.addView(stationTitle, Ui.lpWeight(0, -2, 1));
        stationCount = Ui.text(c, "", ts(15), t.sub, Ui.REGULAR);
        sh.addView(stationCount);
        // SWI69: the FM list is filled by the radio scan (DAB: read again from the service)
        stationSearch = Ui.text(c, L.s(R.string.radio_search), ts(14), t.text, Ui.MEDIUM);
        stationSearch.setPadding(tp(12), tp(6), tp(12), tp(6));
        stationSearch.setBackground(Ui.pressable(c, t.chip, 10, t));
        stationSearch.setClickable(true);
        stationSearch.setOnClickListener(v -> media.searchStations());
        sh.addView(stationSearch, Ui.margins(new LayoutParams(-2, -2), dp(10), 0, 0, 0));
        stationPanel.addView(sh, new LayoutParams(-1, -2));
        android.widget.ScrollView ss = new android.widget.ScrollView(c);
        ss.setVerticalScrollBarEnabled(false);
        ss.setOverScrollMode(OVER_SCROLL_NEVER);
        stationList = Ui.column(c);
        ss.addView(stationList, new FrameLayout.LayoutParams(-1, -2));
        stationScroll = ss;
        stationPanel.addView(ss, Ui.lpWeight(-1, 0, 1));
        stationHead = sh;
        // fork: the "Procurar" button measures ~60×28 px; the touch area extends (without changing the look) from the top of the panel to
        // the end of the header and 16 dp on each side. It is only replaced when the rectangle really changes (the text changes:
        // Procurar/Atualizar/A procurar…): the panel re-lays out ~1×/s and a new TouchDelegate in the middle of a touch
        // lost the UP (no click and the button stayed pressed). It is never replaced while the button is pressed.
        final android.graphics.Rect[] lastHit = {null};
        View.OnLayoutChangeListener searchHit = (v, l, tp, r, b, ol, ot, or, ob) -> {
            if (stationSearch.getWidth() == 0 || sh.getWidth() == 0 || stationSearch.isPressed()) return;
            android.graphics.Rect hit = new android.graphics.Rect(
                    Math.max(0, sh.getLeft() + stationSearch.getLeft() - dp(16)), 0,
                    Math.min(stationPanel.getWidth(), sh.getLeft() + stationSearch.getRight() + dp(16)), sh.getBottom());
            if (hit.equals(lastHit[0])) return;
            lastHit[0] = hit;
            stationPanel.setTouchDelegate(new android.view.TouchDelegate(hit, stationSearch));
        };
        stationSearch.addOnLayoutChangeListener(searchHit);
        stationPanel.addOnLayoutChangeListener(searchHit);
        // 1.0.4: whole rows only (before, in "Molto grande" the last one was cut in half)
        stationPanel.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            if (b - tp != ob - ot) fitStationRows();
        });
        stationEmpty = Ui.column(c);
        stationEmpty.setGravity(Gravity.CENTER);
        stationEmpty.setPadding(dp(18), 0, dp(18), 0);
        TextView et = Ui.text(c, L.s(R.string.stations_empty), 16, t.sub, Ui.REGULAR);
        et.setGravity(Gravity.CENTER);
        stationEmpty.addView(et);
        TextView eb = Ui.text(c, L.s(R.string.open_radio_app), ts(17), t.text, Ui.MEDIUM);
        eb.setGravity(Gravity.CENTER);
        eb.setPadding(tp(22), 0, tp(22), 0);
        eb.setBackground(Ui.pressable(c, t.chip, 14, t));
        eb.setClickable(true);
        eb.setOnClickListener(v -> host.launch(com.mg4.launcher.apps.MgApps.RADIO_PKG, com.mg4.launcher.apps.MgApps.RADIO_CLS));
        stationEmpty.addView(eb, Ui.margins(new LayoutParams(-2, tp(52)), 0, dp(16), 0, 0));
        stationPanel.addView(stationEmpty, Ui.lpWeight(-1, 0, 1));
        // 1.0.4: the column grows with the chosen size (same factor as the text), so the center does not stay empty
        body.addView(stationPanel, Ui.margins(new LayoutParams(dp(STATION_W * Ui.K), -1), dp(18), 0, 0, 0));
        return col;
    }

    private void styleBands(int band) {
        int sel = band == MediaClient.BAND_DAB ? 1 : band == MediaClient.BAND_FM ? 0 : -1;
        for (int i = 0; i < bandChips.length; i++) {
            bandChips[i].setBackground(i == sel ? Ui.round(t.text, dp(14)) : Ui.pressable(getContext(), 0, 14, t));
            bandChips[i].setTextColor(i == sel ? t.panel : t.sub);
        }
    }

    private static String sourceLabel(int src) {
        switch (src) {
            case MediaClient.SRC_RADIO: return L.s(R.string.src_radio);
            // 0.4.3 (point 5): with the second port in use the two sources are called "USB 1" and "USB 2"
            case MediaClient.SRC_USB: return MediaClient.usbBoth ? "USB 1" : "USB";
            case MediaClient.SRC_USB2: return "USB 2";
            case MediaClient.SRC_BT: return "Bluetooth";
            case MediaClient.SRC_ONLINE: return L.s(R.string.src_online);
            case MediaClient.SRC_CARPLAY: return "CarPlay";
            case MediaClient.SRC_AAUTO: return "Android Auto";
            default: return L.s(R.string.media_generic);
        }
    }

    /** Source buttons: recreated only if the list changes, then the highlight is updated. */
    private void showSources(MediaClient.State m) {
        Context c = getContext();
        // 1.0.4 (issue #28): only the sources chosen in the settings. CarPlay/Android Auto are not here: they have their
        // own original icon in the bottom bar.
        java.util.List<Integer> shownSrc = new java.util.ArrayList<>();
        for (int src : m.sources) if (!isProjection(src) && prefs.sourceShown(src)) shownSrc.add(src);
        if (shownSrc.isEmpty()) for (int src : m.sources) if (!isProjection(src)) shownSrc.add(src);
        StringBuilder sig = new StringBuilder();
        for (int src : shownSrc) sig.append(src).append(',');
        if (!sig.toString().equals(srcSignature)) {
            srcSignature = sig.toString();
            srcRow.removeAllViews();
            // always start with normal labels and spacing; fitSources() tightens the spacing if there is no room
            srcCompact = false;
            srcFit = 0;
            boolean compact = false;
            for (int i = 0; i < shownSrc.size(); i++) {
                final int src = shownSrc.get(i);
                TextView b = Ui.text(c, sourceLabel(src), ts(19.5f), t.sub, Ui.MEDIUM);
                b.setGravity(Gravity.CENTER_VERTICAL);
                b.setPadding(tp(compact ? 16 : 20), 0, tp(compact ? 18 : 24), 0);
                b.setCompoundDrawablePadding(dp(11));
                b.setClickable(true);
                b.setTag(src);
                b.setContentDescription(sourceLabel(src));
                b.setOnClickListener(v -> {
                    // with CarPlay connected the iPhone audio goes through CarPlay, not Bluetooth: explain that
                    if (src == MediaClient.SRC_BT && media.last().deviceType == MediaClient.DEV_CARPLAY) {
                        Confirm.notice(host.overlay(), t, L.s(R.string.carplay_bt_title), L.s(R.string.carplay_bt_msg),
                                new String[0], L.s(R.string.btn_ok), null);
                    }
                    pendingSource = src;
                    pendingSourceUntil = SystemClock.uptimeMillis() + 3000;
                    styleSources(src);
                    media.selectSource(src);
                });
                srcRow.addView(b, Ui.margins(Ui.lp(-2, -1), i == 0 ? 0 : dp(8), 0, 0, 0));
            }
        }
        int shown = SystemClock.uptimeMillis() < pendingSourceUntil ? pendingSource : m.source;
        styleSources(shown);
        srcRow.post(this::fitSources);
    }

    /**
     * 1.0.2 (issue #23): the sources keep their label if there is room before the top-right buttons (for
     * example with the top-right button set to "none"); otherwise, as before, icons only except the chosen one.
     */
    private void fitSources() {
        if (srcRow == null || topRightBox == null || srcRow.getWidth() == 0 || srcFit >= 3) return;
        int[] a = new int[2], b = new int[2];
        srcRow.getLocationOnScreen(a);
        topRightBox.getLocationOnScreen(b);
        boolean overlap = topRightBox.getVisibility() == VISIBLE && a[0] + srcRow.getWidth() > b[0] - dp(12)
                && mediaPanel.getVisibility() == VISIBLE;
        if (!overlap) return;
        // 1.0.4: labels stay; first the margins and spacing between buttons are tightened (two steps), icons only if
        // it really is not enough (with 4 sources and "Molto grande" it does not happen)
        srcFit++;
        srcCompact = srcFit >= 3;
        if (srcCompact) com.mg4.launcher.svc.Diag.put("home.sources", "icons only: not enough space even with narrow spacing");
        applySrcFit();
        styleSources(SystemClock.uptimeMillis() < pendingSourceUntil ? pendingSource : media.last().source);
    }

    private static boolean isProjection(int src) {
        return src == MediaClient.SRC_CARPLAY || src == MediaClient.SRC_AAUTO;
    }

    /** Inner margins and spacing between the source buttons according to srcFit. */
    private void applySrcFit() {
        int pl = srcFit == 0 ? 20 : srcFit == 1 ? 14 : 10, pr = srcFit == 0 ? 24 : srcFit == 1 ? 16 : 12;
        int gap = srcFit == 0 ? 8 : srcFit == 1 ? 5 : 3;
        for (int i = 0; i < srcRow.getChildCount(); i++) {
            View v = srcRow.getChildAt(i);
            v.setPadding(tp(pl), 0, tp(pr), 0);
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            lp.leftMargin = i == 0 ? 0 : dp(gap);
            v.setLayoutParams(lp);
        }
    }

    /** Aligns FM/DAB to the left edge of the Radio button. */
    private void alignBand() {
        if (bandBox == null || srcRow == null) return;
        bandBox.post(() -> {
            View radioBtn = null;
            for (int i = 0; i < srcRow.getChildCount(); i++) {
                View v = srcRow.getChildAt(i);
                if (Integer.valueOf(MediaClient.SRC_RADIO).equals(v.getTag())) radioBtn = v;
            }
            int x = radioBtn == null ? 0 : radioBtn.getLeft();
            int left = ((LayoutParams) srcRow.getLayoutParams()).leftMargin + x;
            LayoutParams lp = (LayoutParams) bandBox.getLayoutParams();
            if (lp.leftMargin != left) {
                lp.leftMargin = left;
                bandBox.setLayoutParams(lp);
            }
        });
    }

    private void styleSources(int selected) {
        for (int i = 0; i < srcRow.getChildCount(); i++) {
            TextView b = (TextView) srcRow.getChildAt(i);
            int src = (Integer) b.getTag();
            boolean on = src == selected;
            // 1.0.4: each button has its own background (chip) and a light border (track); the selected one stays filled as before
            b.setBackground(on ? Ui.round(t.text, dp(14)) : sourceBg());
            b.setTextColor(on ? t.panel : t.text);
            boolean compact = srcCompact;
            b.setText(compact && !on ? "" : sourceLabel(src));
            b.setCompoundDrawablePadding(compact && !on ? 0 : dp(srcFit == 0 ? 11 : 7));
            android.graphics.drawable.Drawable d = getContext().getDrawable(sourceIcon(src)).mutate();
            d.setTint(on ? t.panel : t.text);
            d.setBounds(0, 0, tp(25), tp(25));
            b.setCompoundDrawables(d, null, null, null);
        }
    }

    /** Background of an unselected source button: chip + 1.5 dp track-colored border, corners 14, touch effect. */
    private android.graphics.drawable.Drawable sourceBg() {
        android.graphics.drawable.GradientDrawable g = Ui.round(t.chip, dp(14));
        g.setStroke(Math.max(1, dp(1.5f)), t.track);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(t.day ? 0x22000000 : 0x33FFFFFF), g, null);
    }

    /** Favorite station tile: logo (or initials) + name. */
    private View stationTile(Context c, MediaClient.Station st, boolean current) {
        LinearLayout tile = Ui.column(c);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setClickable(true);
        tile.setContentDescription(st.label());
        tile.setBackground(Ui.pressable(c, 0, 16, t));
        tile.setOnClickListener(v -> media.tune(st));
        // SWI69: the launcher keeps the favorites; long press to remove it
        tile.setOnLongClickListener(v -> {
            media.removeFavorite(st);
            return true;
        });
        FrameLayout box = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(t.chip);
        g.setCornerRadius(dp(18));
        if (current) g.setStroke(dp(2), t.text);
        box.setBackground(g);
        box.setClipToOutline(true);
        if (st.logo != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(st.logo);
            iv.setPadding(dp(8), dp(8), dp(8), dp(8));
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView ini = Ui.text(c, initials(st), ts(18), t.text, Ui.MEDIUM);
            ini.setGravity(Gravity.CENTER);
            box.addView(ini, new FrameLayout.LayoutParams(-1, -1));
        }
        TextView band = Ui.text(c, st.band == MediaClient.BAND_DAB ? "DAB" : "FM", ts(10), t.sub, Ui.MEDIUM);
        band.setPadding(dp(5), dp(1), dp(5), dp(1));
        band.setBackground(Ui.round(t.panel, dp(6)));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        blp.setMargins(0, 0, dp(5), dp(5));
        box.addView(band, blp);
        // 1.0.4: the tiles grow with the chosen size, like everything else (before they stayed fixed)
        tile.addView(box, new LayoutParams(tp(60), tp(60)));
        // always name + frequency (or DAB+), even with the logo
        TextView name = Ui.oneLine(Ui.text(c, st.label(), ts(14), current ? t.text : t.sub, Ui.MEDIUM));
        name.setGravity(Gravity.CENTER);
        tile.addView(name, Ui.margins(new LayoutParams(tp(92), -2), 0, dp(4), 0, 0));
        String freq = st.band == MediaClient.BAND_DAB ? "DAB+" : String.format(L.locale(), "%.1f MHz", st.khz / 1000f);
        TextView fq = Ui.oneLine(Ui.text(c, freq, ts(12), t.sub, Ui.REGULAR));
        fq.setGravity(Gravity.CENTER);
        tile.addView(fq, new LayoutParams(tp(92), -2));
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        return tile;
    }

    private static String initials(MediaClient.Station st) {
        String n = st.name.trim();
        if (n.isEmpty()) return st.label();
        String[] w = n.split("\\s+");
        if (w.length == 1) return w[0].length() <= 5 ? w[0].toUpperCase(L.locale()) : w[0].substring(0, 4).toUpperCase(L.locale());
        return (w[0].substring(0, 1) + w[1].substring(0, 1)).toUpperCase(L.locale());
    }

    /** Station list row: logo (or initials) + name + frequency; tap to tune. */
    private View stationRow(Context c, MediaClient.Station st, boolean current) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(12), 0);
        row.setBackground(current ? Ui.round(t.chip, dp(14)) : Ui.pressable(c, 0, 14, t));
        row.setClickable(true);
        row.setContentDescription(st.label());
        row.setOnClickListener(v -> media.tune(st));
        // SWI69: long press to add or remove the station from the favorites
        row.setOnLongClickListener(v -> {
            media.toggleFavorite(st);
            return true;
        });
        FrameLayout box = new FrameLayout(c);
        box.setBackground(Ui.round(current ? t.overlay : t.chip, dp(10)));
        box.setClipToOutline(true);
        if (st.logo != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(st.logo);
            iv.setPadding(dp(4), dp(4), dp(4), dp(4));
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView ini = Ui.text(c, initials(st), ts(13), t.text, Ui.MEDIUM);
            ini.setGravity(Gravity.CENTER);
            box.addView(ini, new FrameLayout.LayoutParams(-1, -1));
        }
        row.addView(box, new LayoutParams(tp(48), tp(48)));
        LinearLayout tx = Ui.column(c);
        String sub = st.band == MediaClient.BAND_DAB ? "DAB+" : String.format(L.locale(), "FM %.1f MHz", st.khz / 1000f);
        // always logo (or initials) + name + frequency or DAB+
        tx.addView(Ui.oneLine(Ui.text(c, st.label(), ts(20), t.text, current ? Ui.MEDIUM : Ui.REGULAR)));
        tx.addView(Ui.oneLine(Ui.text(c, sub, ts(15.5f), t.sub, Ui.REGULAR)));
        row.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(12), 0, 0, 0));
        if (current) row.addView(Ui.icon(c, R.drawable.ic_wave, t.text, 20), new LayoutParams(tp(22), tp(22)));
        return row;
    }

    /** Station list height = whole number of rows (the leftover space stays empty at the bottom of the box). */
    private void fitStationRows() {
        if (stationScroll == null || stationHead == null) return;
        int inner = stationPanel.getHeight() - stationPanel.getPaddingTop() - stationPanel.getPaddingBottom() - stationHead.getHeight();
        int row = tp(STATION_ROW) + dp(2);
        if (inner <= 0 || row <= 0) return;
        int want = Math.max(1, inner / row) * row;
        LayoutParams lp = (LayoutParams) stationScroll.getLayoutParams();
        if (lp.height == want && lp.weight == 0) return;
        lp.height = want;
        lp.weight = 0;
        stationScroll.setLayoutParams(lp);
    }

    private void showStations(MediaClient.State m) {
        boolean dab = m.radioBand == MediaClient.BAND_DAB;
        stationTitle.setText(L.f(R.string.stations_title, dab ? "DAB" : "FM").toUpperCase(L.locale()));
        stationCount.setText(m.stations.isEmpty() ? "" : String.valueOf(m.stations.size()));
        stationSearch.setText(L.s(m.scanning ? R.string.radio_searching : dab ? R.string.radio_refresh : R.string.radio_search));
        stationSearch.setAlpha(m.scanning ? 0.5f : 1f);
        StringBuilder sig = new StringBuilder(m.stationKey).append('#').append(m.radioBand);
        for (MediaClient.Station st : m.stations) sig.append('|').append(st.key).append(st.logo != null ? '+' : '-');
        boolean empty = m.stations.isEmpty();
        stationScroll.setVisibility(empty ? GONE : VISIBLE);
        stationEmpty.setVisibility(empty ? VISIBLE : GONE);
        if (sig.toString().equals(stationSignature)) return;
        boolean firstForBand = m.radioBand != stationBand;
        stationBand = m.radioBand;
        stationSignature = sig.toString();
        stationList.removeAllViews();
        Context c = getContext();
        int currentIndex = -1;
        for (int i = 0; i < m.stations.size(); i++) {
            MediaClient.Station st = m.stations.get(i);
            boolean cur = st.key.equals(m.stationKey);
            if (cur) currentIndex = i;
            stationList.addView(stationRow(c, st, cur), Ui.margins(new LayoutParams(-1, tp(STATION_ROW)), 0, 0, 0, dp(2)));
        }
        // bring the station on air into view
        if (currentIndex >= 0) {
            final int idx = currentIndex;
            stationScroll.post(() -> {
                View v = stationList.getChildAt(idx);
                if (v == null) return;
                int y = v.getTop() - (stationScroll.getHeight() - v.getHeight()) / 2;
                if (firstForBand) stationScroll.scrollTo(0, Math.max(0, y));
                else if (v.getTop() < stationScroll.getScrollY() || v.getBottom() > stationScroll.getScrollY() + stationScroll.getHeight())
                    stationScroll.smoothScrollTo(0, Math.max(0, y));
            });
        }
    }

    private void showFavorites(MediaClient.State m) {
        StringBuilder sig = new StringBuilder(m.stationKey);
        for (MediaClient.Station st : m.favorites) sig.append('|').append(st.key).append(st.logo != null ? '+' : '-');
        if (sig.toString().equals(favSignature)) return;
        favSignature = sig.toString();
        favRow.removeAllViews();
        for (MediaClient.Station st : m.favorites) {
            favRow.addView(stationTile(getContext(), st, st.key.equals(m.stationKey)), Ui.margins(new LayoutParams(-2, -2), 0, 0, dp(8), 0));
        }
    }

    private void openCurrentSourceApp() {
        MediaClient.State m = media.last();
        switch (m.source) {
            case MediaClient.SRC_RADIO:
                host.launch(com.mg4.launcher.apps.MgApps.RADIO_PKG, com.mg4.launcher.apps.MgApps.RADIO_CLS);
                break;
            case MediaClient.SRC_ONLINE:
                host.launch(com.mg4.launcher.apps.MgApps.MEDIA_PKG, com.mg4.launcher.apps.MgApps.MEDIA_CLS);
                break;
            case MediaClient.SRC_CARPLAY:
            case MediaClient.SRC_AAUTO:
                media.openProjection();
                break;
            default:
                host.launch(com.mg4.launcher.apps.MgApps.MEDIA_PKG, com.mg4.launcher.apps.MgApps.MEDIA_CLS);
                break;
        }
    }

    private static int sourceIcon(int src) {
        switch (src) {
            case MediaClient.SRC_USB:
            case MediaClient.SRC_USB2: return R.drawable.ic_usb;
            case MediaClient.SRC_BT: return R.drawable.ic_bluetooth;
            case MediaClient.SRC_ONLINE: return R.drawable.ic_wave;
            case MediaClient.SRC_CARPLAY:
            case MediaClient.SRC_AAUTO: return R.drawable.ic_smartphone;
            default: return R.drawable.ic_radio;
        }
    }

    public void onMedia(MediaClient.State m) {
        boolean radio = m.source == MediaClient.SRC_RADIO;
        showSources(m);
        mTitle.setText(m.title);
        mSub.setText(m.subtitle);
        // 1.0.4: empty row, or radio with large controls (the band is already in the frequency box) = no space
        // taken from the controls (before, in "Molto grande" the play key ended up clipped)
        mSub.setVisibility(m.subtitle.isEmpty() || (m.source == MediaClient.SRC_RADIO && (big || Ui.K > 1.05f)) ? GONE : VISIBLE);
        boolean logo = radio && m.logo != null;
        // 0.4.2 (point 11d): cover art from the phone (Bluetooth/USB/online) if it arrives; otherwise a clean icon
        boolean cover = !radio && m.art != null;
        artLogo.setVisibility(logo || cover ? VISIBLE : GONE);
        if (logo) {
            artLogo.setPadding(dp(20), dp(20), dp(20), dp(20));
            artLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (m.logo != artShown) {   // fork: only when the image changes (avoids redrawing and re-uploading the texture every second)
                artLogo.setImageBitmap(m.logo);
                artShown = m.logo;
            }
        } else if (cover) {
            artLogo.setPadding(0, 0, 0, 0);
            artLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (m.art != artShown) {
                artLogo.setImageBitmap(m.art);
                artShown = m.art;
            }
        }
        artRadio.setVisibility(radio && !logo ? VISIBLE : GONE);
        artIcon.setVisibility(radio || cover ? GONE : VISIBLE);
        artIcon.setImageResource(sourceIcon(m.source));
        if (radio) {
            boolean dab = m.radioBand == MediaClient.BAND_DAB;
            String band = dab ? "DAB+" : m.radioBand == MediaClient.BAND_AM ? "AM" : "FM";
            artBand.setText(band);
            artFreq.setText(dab ? m.title : m.radioFreq);
            artFreq.setTextSize(dab ? 30 : 56);
            artUnit.setText(m.radioUnit);
            mMeta.setText(dab ? band + (m.radioUnit.isEmpty() ? "" : " · " + m.radioUnit)
                    : band + (m.radioFreq.isEmpty() ? "" : " · " + m.radioFreq + " " + m.radioUnit));
            styleBands(SystemClock.uptimeMillis() < pendingBandUntil ? pendingBand : m.radioBand);
            showFavorites(m);
        } else {
            mMeta.setText(sourceLabel(m.source).toUpperCase(L.locale()));
        }
        // 1.0.4: FM/DAB band below the Radio button, only for the Radio
        if ((bandBox.getVisibility() == VISIBLE) != radio) {
            bandBox.setVisibility(radio ? VISIBLE : GONE);
            ((LayoutParams) mediaBody.getLayoutParams()).topMargin = dp(radio ? 2 : 12);
            mediaBody.requestLayout();
            alignBand();
        }
        // with little vertical space (large controls or large bar) the "FM · 101,5 MHz" row makes room for the band
        mMeta.setVisibility(radio && (big || Ui.K > 1.05f) ? GONE : VISIBLE);
        stationPanel.setVisibility(radio ? VISIBLE : GONE);
        if (radio) showStations(m);
        favScroll.setVisibility(radio && !m.favorites.isEmpty() ? VISIBLE : GONE);
        boolean hasProgress = !radio && m.durationMs > 0;
        progressRow.setVisibility(hasProgress ? VISIBLE : GONE);
        if (hasProgress) {
            long pos = Math.max(0, m.positionMs);
            mProgress.setValue(m.positionMs >= 0 ? pos / (float) m.durationMs : 0);
            mPos.setText(m.positionMs >= 0 ? time(pos) : "");
            mDur.setText(time(m.durationMs));
        }
        playIcon.setImageResource(m.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        miniPlayIcon.setImageResource(m.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        miniTitle.setText(m.title);
        miniSub.setText(sourceLabel(m.source) + (m.subtitle.isEmpty() ? "" : " · " + m.subtitle));
        if (radio && m.logo != null || !radio && m.art != null) {
            android.graphics.Bitmap mb = radio ? m.logo : m.art;
            if (mb != miniShown) {   // fork: only when the image changes
                miniArt.clearColorFilter();
                miniArt.setImageBitmap(mb);
                miniShown = mb;
            }
        } else {
            miniArt.setColorFilter(t.sub, android.graphics.PorterDuff.Mode.SRC_IN);
            miniArt.setImageResource(sourceIcon(m.source));
            miniShown = null;
        }

        // CarPlay / Android Auto: the tab appears only with the phone connected
        boolean phone = m.deviceType != 0;
        if (phone != navWithPhone) buildNavWith(getContext(), phone);
        if (topMode == 0) tabPhone.setVisibility(phone ? VISIBLE : GONE);
        if (projBtn != null) {
            projBtn.setVisibility(phone ? VISIBLE : GONE);
            projBtn.setContentDescription(m.deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay");
            if (phone && m.deviceType != projIconType) setProjectionIcon(m.deviceType);
        }
        if (topMode == 0) tabPhone.setText(m.deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay");
        if ((!phone || topMode != 0) && tab == TAB_PHONE) selectTab(TAB_MEDIA, false);
        if (phone) {
            phoneTitle.setText(m.deviceName.isEmpty() ? L.s(R.string.phone_connected) : m.deviceName);
            phoneSub.setText(L.f(R.string.projection_ready, m.deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay"));
        }
        boolean tbt = phone && m.tbtActive;
        phoneNav.setVisibility(tbt ? VISIBLE : GONE);
        if (tbt) {
            phoneNavDist.setText(m.tbtDistance.isEmpty() ? L.s(R.string.next_maneuver) : m.tbtDistance);
            phoneNavRoad.setText(m.tbtRoad.isEmpty() ? L.s(R.string.nav_phone) : m.tbtRoad);
        }
    }

    // ---- 1.0.4 (point F): charging animation ----------------------------------------------------

    private String animLog = "", animColor = "";
    private int animGen = -1;
    private long animEnsureAt;
    private boolean animHave;

    /** Screen off or launcher in the background: animation stopped and decoders released. */
    public void pauseChargeAnim() {
        if (chargeAnim != null) chargeAnim.pause();
    }

    private void updateChargeAnim(VehicleClient.State s) {
        if (chargeAnim == null) return;
        String color = Prefs.CAR_COLORS[prefs.carColorIndex()];
        boolean plugged = VehicleClient.cablePlugged(s), done = VehicleClient.chargeDone(s);
        // 0.4.5: stops only with READY confirmed (SENSOR_EPTRDY = 1), while moving or with a gear other than P;
        // unknown READY does not block, and ignition (cluster on) does not count as READY
        boolean ready = s.ready == 1, moving = !Float.isNaN(s.speedKmh) && Math.abs(s.speedKmh) >= 1f;
        boolean notPark = s.gear >= 0 && s.gear != 1;
        // the file check is redone only when the pack or the color changes (no disk access on every reading)
        if (animGen != com.mg4.launcher.svc.ChargePack.generation || !color.equals(animColor)) {
            animGen = com.mg4.launcher.svc.ChargePack.generation;
            animColor = color;
            animHave = com.mg4.launcher.svc.ChargePack.complete(getContext(), color);
        }
        boolean have = animHave;
        String why = !prefs.chargeAnim() ? "off in settings" : ready ? "in READY: stopped" : moving ? "moving: stopped"
                : notPark ? "gear not in P: stopped" : !have ? "pack not available" : "";
        // at most once a minute (ensure then checks the 12-hour limit between two downloads)
        long nowMs = SystemClock.uptimeMillis();
        if (!have && plugged && prefs.chargeAnim() && nowMs - animEnsureAt > 60_000) {
            animEnsureAt = nowMs;
            com.mg4.launcher.svc.ChargePack.ensure(getContext(), color, false);
        }
        Context c = getContext();
        chargeAnim.update((day, phase) -> com.mg4.launcher.svc.ChargePack.clip(c, color, day, phase), t.day, plugged, done, why);
        String txt = (prefs.chargeAnim() ? "on" : "off") + " · pack " + color + " "
                + (have ? "present" : com.mg4.launcher.svc.ChargePack.busy() ? "downloading" : "absent") + " · "
                + ChargeAnimView.phaseTxt + " · cable " + (plugged ? "plugged in" : "not plugged in") + (done ? " · charge complete" : "")
                + " · charging state " + s.chargingStatus + " · socket " + s.plugProp + " · ready " + s.ready + " (" + s.readySource + ")"
                + " · gear " + VehicleClient.gearName(s.gear);
        if (!txt.equals(animLog)) {
            animLog = txt;
            com.mg4.launcher.svc.Diag.put("charge.animation", txt);
        }
    }

    /** Screen back on: if the cable is already plugged in, the animation starts from the loop. */
    public void onScreenOn() {
        if (chargeAnim != null) chargeAnim.markFresh();
    }

    /** Test from technical mode: installed pack, otherwise zip from the USB stick. */
    public void testChargeAnim(java.io.File dir, String slug, Runnable done) {
        if (chargeAnim == null) return;
        chargeAnim.playTest((day, phase) -> com.mg4.launcher.svc.ChargePack.clipIn(dir, slug, day, phase), t.day, done);
    }

    private int projIconType = -1;

    /**
     * 1.0.4 (point E5): in the bar, the ORIGINAL icon of the app installed in the car (Android Auto: com.allgo.app.androidauto,
     * CarPlay: com.allgo.carplay.service), read from the PackageManager. The APK contains no Google or Apple logo:
     * if the app has no icon, the launcher's generic icon is used.
     */
    private void setProjectionIcon(int deviceType) {
        projIconType = deviceType;
        ImageView iv = (ImageView) projBtn.getChildAt(0);
        String pkg = deviceType == MediaClient.DEV_AAUTO ? "com.allgo.app.androidauto" : "com.allgo.carplay.service";
        android.graphics.drawable.Drawable d = null;
        try {
            d = getContext().getPackageManager().getApplicationIcon(pkg);
        } catch (Exception ignored) {
        }
        if (d != null) {
            iv.clearColorFilter();
            iv.setImageDrawable(d);
        } else {
            iv.setImageResource(R.drawable.ic_projection);
            iv.setColorFilter(t.text, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        com.mg4.launcher.svc.Diag.put("home.projectionIcon", (deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay")
                + " · " + (d != null ? "original icon of " + pkg : "generic icon (" + pkg + " has no icon)"));
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(L.locale(), "%02d", s % 60);
    }

    // ---- CarPlay / Android Auto --------------------------------------------------------------

    private View buildPhonePanel(Context c) {
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(40), dp(40), dp(40), dp(40));
        col.addView(Ui.icon(c, R.drawable.ic_smartphone, t.sub, 84));
        phoneTitle = Ui.text(c, L.s(R.string.phone_connected), 30, t.text, Ui.MEDIUM);
        phoneTitle.setGravity(Gravity.CENTER);
        col.addView(phoneTitle, Ui.margins(new LayoutParams(-2, -2), 0, dp(18), 0, 0));
        phoneSub = Ui.text(c, "", 20, t.sub, Ui.REGULAR);
        phoneSub.setGravity(Gravity.CENTER);
        col.addView(phoneSub, Ui.margins(new LayoutParams(-2, -2), 0, dp(12), 0, 0));
        // 0.4.0 (point 7): much larger button ("Tocca per aprire") and the whole tab touchable, for driving
        TextView open = Ui.text(c, L.s(R.string.tap_to_open), ts(26), t.onAccent, Ui.MEDIUM);
        open.setGravity(Gravity.CENTER);
        open.setPadding(tp(56), 0, tp(56), 0);
        open.setMinWidth(dp(420));
        open.setBackground(Ui.pressable(c, t.accent, 46 * Ui.K, t));
        open.setClickable(true);
        open.setOnClickListener(v -> media.openProjection());
        col.addView(open, Ui.margins(new LayoutParams(-2, tp(92)), 0, dp(26), 0, 0));

        // CarPlay / Android Auto navigation in progress: next maneuver
        phoneNav = Ui.row(c);
        phoneNav.setGravity(Gravity.CENTER_VERTICAL);
        phoneNav.setBackground(Ui.pressable(c, t.chip, 18, t));
        phoneNav.setPadding(dp(22), dp(14), dp(26), dp(14));
        phoneNav.addView(Ui.icon(c, R.drawable.ic_turn, t.text, 40));
        LinearLayout nt = Ui.column(c);
        phoneNavDist = Ui.oneLine(Ui.text(c, "", 28, t.text, Ui.MEDIUM));
        phoneNavRoad = Ui.oneLine(Ui.text(c, "", 18, t.sub, Ui.REGULAR));
        nt.addView(phoneNavDist);
        nt.addView(phoneNavRoad, Ui.margins(new LayoutParams(-2, -2), 0, dp(4), 0, 0));
        phoneNav.addView(nt, Ui.margins(new LayoutParams(-2, -2), dp(16), 0, 0, 0));
        phoneNav.setClickable(true);
        phoneNav.setOnClickListener(v -> {
            media.openProjection();
            media.openRemoteNavigation();
        });
        phoneNav.setVisibility(GONE);
        col.addView(phoneNav, Ui.margins(new LayoutParams(-2, -2), 0, dp(22), 0, 0));

        FrameLayout f = new FrameLayout(c);
        f.addView(col, new FrameLayout.LayoutParams(-1, -1));
        f.addView(shortcutHint(c, L.s(R.string.phone_shortcut_hint)), hintLp());
        // the whole tab opens CarPlay / Android Auto (the navigation row keeps its own command)
        f.setClickable(true);
        f.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(t.day ? 0x22000000 : 0x33FFFFFF), null,
                Ui.round(0xFFFFFFFF, dp(24))));
        f.setContentDescription(L.s(R.string.tap_to_open));
        f.setOnClickListener(v -> media.openProjection());
        return f;
    }

    // =========================================================================================
    // Bottom bar
    // =========================================================================================

    // large bar sizes (px, density 1 in the car): bar 150, buttons 112, arrows 92, icons 46
    private static final int BIG_BAR = 150, BIG_BTN = 112, BIG_ARROW = 92, BIG_ICON = 46, BIG_SHORTCUT = 96;

    /** Size of a bar element: normal (grows with Dimensione comandi) or large (fixed). */
    private int bs(float normal, float bigPx) {
        return big ? dp(bigPx) : tp(normal);
    }

    private float bt(float normalSp, float bigSp) {
        return big ? bigSp : ts(normalSp);
    }

    /** Bar icon button (sizes are already final). */
    private FrameLayout barIcon(Context c, int res, int iconColor, int bg, int size, int icon, float radiusDp, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.pressable(c, bg, radiusDp, t));
        f.setContentDescription(desc);
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        f.addView(iv, new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));
        f.setLayoutParams(new LayoutParams(size, size));
        f.setClickable(true);
        f.setOnClickListener(l);
        return f;
    }

    private View buildBottomBar(Context c) {
        LinearLayout bar = Ui.row(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(t.panel, dp(24)));
        bar.setPadding(dp(14), 0, dp(14), 0);
        int btn = bs(76, BIG_BTN), arrow = bs(68, BIG_ARROW), arrowIcon = bs(30, BIG_ICON - 8), icon = bs(32, BIG_ICON);
        float rad = big ? 24 : 20 * Ui.K;

        // temperature: large buttons, easy to hit while driving
        LinearLayout tempBox = Ui.row(c);
        tempBox.setGravity(Gravity.CENTER_VERTICAL);
        tempBox.setBackground(Ui.round(t.chip, dp(big ? 24 : 20)));
        tempBox.addView(barIcon(c, R.drawable.ic_chev_left, t.text, 0, arrow, arrowIcon, rad, L.s(R.string.temp_down), v -> changeTemp(-1)));
        tempTv = Ui.text(c, "—", bt(40, 54), t.text, Ui.REGULAR);
        tempTv.setGravity(Gravity.CENTER);
        tempBox.addView(tempTv, new LayoutParams(bs(112, 136), -2));
        tempBox.addView(barIcon(c, R.drawable.ic_chev_right, t.text, 0, arrow, arrowIcon, rad, L.s(R.string.temp_up), v -> changeTemp(1)));
        bar.addView(tempBox, new LayoutParams(-2, btn));

        // fan: ‹ level ›, tap the icon = Climate app, long press = turn the climate on/off
        LinearLayout fanGroup = Ui.row(c);
        fanGroup.setGravity(Gravity.CENTER_VERTICAL);
        fanGroup.setBackground(Ui.round(t.chip, dp(big ? 24 : 20)));
        fanGroup.addView(barIcon(c, R.drawable.ic_chev_left, t.text, 0, arrow, arrowIcon, rad, L.s(R.string.fan_down), v -> changeFan(-1)));
        LinearLayout fanBox = Ui.row(c);
        fanBox.setGravity(Gravity.CENTER);
        ImageView fanIc = Ui.icon(c, R.drawable.ic_fan, t.accent, 30);
        fanBox.addView(fanIc, new LayoutParams(bs(30, 40), bs(30, 40)));
        fanTv = Ui.text(c, "—", bt(34, 46), t.text, Ui.REGULAR);
        fanTv.setGravity(Gravity.CENTER);
        fanBox.addView(fanTv, Ui.margins(new LayoutParams(-2, -2), dp(10), 0, 0, 0));
        fanBox.setMinimumWidth(bs(118, 150));
        fanBox.setClickable(true);
        fanBox.setContentDescription(L.s(R.string.climate));
        fanBox.setBackground(Ui.pressable(c, 0, 16, t));
        fanBox.setOnClickListener(v -> host.launch(com.mg4.launcher.apps.MgApps.HVAC_PKG, com.mg4.launcher.apps.MgApps.HVAC_CLS));
        fanBox.setOnLongClickListener(v -> {
            vehicle.setHvacPower(vehicle.last().hvacPower != 1);
            return true;
        });
        fanGroup.addView(fanBox, new LayoutParams(-2, arrow));
        fanGroup.addView(barIcon(c, R.drawable.ic_chev_right, t.text, 0, arrow, arrowIcon, rad, L.s(R.string.fan_up), v -> changeFan(1)));
        bar.addView(fanGroup, Ui.margins(new LayoutParams(-2, btn), dp(10), 0, 0, 0));

        // A/C, AUTO and recirculation (defrost has the physical key); heating: left seat, steering wheel, right seat
        acBtn = climateBtn(c, R.drawable.climate_ac, L.s(R.string.ac_desc), v -> vehicle.toggleAc());
        autoBtn = climateBtn(c, R.drawable.climate_auto, L.s(R.string.auto_desc), v -> vehicle.toggleAuto());
        recircBtn = climateBtn(c, R.drawable.climate_recirc_inside, L.s(R.string.recirc_desc), v -> vehicle.toggleRecirc());
        recircBtn.setVisibility(GONE);   // appears when the car says it supports it
        seatLeftBtn = heatButton(c, R.drawable.climate_seat_heat, false, L.s(R.string.seat_left), v -> vehicle.pressSeatLeft());
        wheelBtn = climateBtn(c, R.drawable.climate_wheel_heat, L.s(R.string.wheel_heat), v -> vehicle.toggleWheelHeat());
        seatRightBtn = heatButton(c, R.drawable.climate_seat_heat, true, L.s(R.string.seat_right), v -> vehicle.pressSeatRight());
        grouped = prefs.groupedButtons();
        if (grouped) {
            // one button per group: a tap opens the box above the bar, a long press runs the main command
            climateGroup = groupButton(c, true);
            bar.addView(climateGroup, Ui.margins(new LayoutParams(groupWidth(), btn), dp(10), 0, 0, 0));
            comfortGroup = groupButton(c, false);
            comfortGroup.setVisibility(GONE);   // appears when the car says it has at least one control
            bar.addView(comfortGroup, Ui.margins(new LayoutParams(groupWidth(), btn), dp(8), 0, 0, 0));
        } else {
            bar.addView(acBtn, Ui.margins(new LayoutParams(btn, btn), dp(10), 0, 0, 0));
            bar.addView(autoBtn, Ui.margins(new LayoutParams(btn, btn), dp(8), 0, 0, 0));
            bar.addView(recircBtn, Ui.margins(new LayoutParams(btn, btn), dp(8), 0, 0, 0));
            // in a single group, so with right-hand drive the left seat stays on the left
            LinearLayout heat = Ui.row(c);
            heat.addView(seatLeftBtn, new LayoutParams(btn, btn));
            heat.addView(wheelBtn, Ui.margins(new LayoutParams(btn, btn), dp(8), 0, 0, 0));
            heat.addView(seatRightBtn, Ui.margins(new LayoutParams(btn, btn), dp(8), 0, 0, 0));
            bar.addView(heat, Ui.margins(new LayoutParams(-2, -2), dp(8), 0, 0, 0));
        }
        // drive (0.9.5): LKA, OPD, assistant and pedal in one group; hidden if the car has none of the controls
        driveGroup = driveButton(c);
        driveGroup.setVisibility(GONE);
        bar.addView(driveGroup, Ui.margins(new LayoutParams(groupWidth(), btn), dp(8), 0, 0, 0));
        bar.addView(new View(c), new LayoutParams(dp(8), 1));

        // compact mini-player (the only stretchy element). With very large controls or the large bar it makes room for the
        // buttons: the music stays in the Media panel.
        // with the groups there is room even with the large bar
        boolean showMini = grouped ? Ui.K < 1.5f : !big && Ui.K < 1.25f;
        // fork: with the groups and normal size there is a lot of spare room for the mini-player: the middle buttons get wider
        // and further apart (fewer taps on the neighboring button). In the other sizes they are already larger and there is no slack.
        roomy = showMini && grouped && !big && Ui.K < 1.1f;
        scHalf = roomy ? dp(7) : 0;
        bar.addView(sectionDivider(c));

        // fog lights (on the MG4 they can only be turned on from the screen); the rear fog can be hidden if the car has the key
        int fog = bs(68, BIG_BTN);
        frontFogBtn = toggleIcon(c, R.drawable.ic_fog_front, L.s(R.string.fog_front), v -> vehicle.toggleFrontFog());
        frontFogBtn.setVisibility(GONE);
        bar.addView(frontFogBtn, Ui.margins(new LayoutParams(fog, fog), scHalf, 0, roomy ? scHalf : dp(8), 0));
        rearFogBtn = toggleIcon(c, R.drawable.ic_fog_rear, L.s(R.string.fog_rear), v -> vehicle.toggleRearFog());
        // rear fog: Automatic = hidden if the system bar already shows its indicator/key, or Show / Hide
        boolean rearFog = prefs.showRearFog(com.mg4.launcher.svc.CarFeatures.hasSystemLightIndicator(c));
        if (!rearFog) rearFogBtn.setVisibility(GONE);
        bar.addView(rearFogBtn, Ui.margins(new LayoutParams(fog, fog), scHalf, 0, scHalf, 0));

        bar.addView(sectionDivider(c));

        // shortcuts; with the large bar phone and 360° remain (Radio is in the Media panel, Settings among the apps)
        // 0.9.6: CarPlay / Android Auto in the bar, only with the phone connected
        int pj = bs(64, BIG_SHORTCUT);
        projBtn = barIcon(c, R.drawable.ic_projection, t.text, 0, pj, bs(44, BIG_ICON + 16), big ? 22 : 18 * Ui.K, "CarPlay / Android Auto",
                v -> media.openProjection());
        projBtn.setLayoutParams(Ui.margins(Ui.lp(scWidth(pj), pj), roomy ? scHalf : dp(6), 0, scHalf, 0));
        projBtn.setVisibility(GONE);
        bar.addView(projBtn);
        bar.addView(shortcut(c, R.drawable.ic_phone, L.s(R.string.app_phone), com.mg4.launcher.apps.MgApps.PHONE_PKG, com.mg4.launcher.apps.MgApps.PHONE_CLS));
        if (!big) bar.addView(shortcut(c, R.drawable.ic_radio, L.s(R.string.src_radio), com.mg4.launcher.apps.MgApps.RADIO_PKG, com.mg4.launcher.apps.MgApps.RADIO_CLS));
        // 0.4.0 (point 8): the 360 key opens the original 360 (default), MG4 360, another chosen app, or is hidden;
        // the original stays hidden if the car configuration says there are no cameras.
        Cam360.Target cam = Cam360.resolve(c, prefs);
        camKind = cam.kind;
        cameraBtn = shortcut(c, 0, cam.label.isEmpty() ? L.s(R.string.app_camera) : cam.label, cam.pkg, cam.cls);
        if (camKind == Cam360.HIDDEN
                || (camKind == Cam360.ORIGINAL && Boolean.FALSE.equals(VehicleClient.cameraPresent(vehicle.last().cameraConfig))))
            cameraBtn.setVisibility(GONE);
        bar.addView(cameraBtn);
        if (!big) bar.addView(shortcut(c, R.drawable.ic_gear, L.s(R.string.app_settings), com.mg4.launcher.apps.MgApps.SETTINGS_PKG, com.mg4.launcher.apps.MgApps.SETTINGS_CLS));

        // fork: the mini-player (or the favorites/shortcuts replacing it) moves to the end of the bar, on the right; fog and
        // shortcuts stay in the middle, closer to the driver (left-hand drive). With right-hand drive the bar is mirrored.
        if (showMini) bar.addView(sectionDivider(c));
        LinearLayout mini = Ui.row(c);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(6), 0, dp(4), 0);
        miniArt = Ui.icon(c, R.drawable.ic_radio, t.sub, 26);
        FrameLayout artBox = new FrameLayout(c);
        artBox.setBackground(Ui.round(t.chip, big ? dp(16) : tp(12)));
        artBox.addView(miniArt, new FrameLayout.LayoutParams(bs(26, 40), bs(26, 40), Gravity.CENTER));
        // 1.0.4: in "Molto grande" the icon box makes room for the text (before, "93.8…" was truncated)
        boolean tightMini = !big && Ui.K >= 1.25f;
        if (!tightMini) mini.addView(artBox, new LayoutParams(bs(48, 76), bs(48, 76)));
        LinearLayout mt = Ui.column(c);
        miniTitle = Ui.oneLine(Ui.text(c, "", bt(17, 22), t.text, Ui.MEDIUM));
        miniSub = Ui.oneLine(Ui.text(c, "", bt(14, 18), t.sub, Ui.REGULAR));
        mt.addView(miniTitle);
        mt.addView(miniSub, Ui.margins(new LayoutParams(-2, -2), 0, dp(2), 0, 0));
        mini.addView(mt, Ui.margins(Ui.lpWeight(0, -2, 1), dp(tightMini ? 4 : 12), 0, dp(4), 0));
        mini.setClickable(true);
        mini.setOnClickListener(v -> selectTab(TAB_MEDIA, true));
        FrameLayout mp = Ui.iconButton(c, t, R.drawable.ic_pause, t.text, 0, big ? 76 : 52, big ? 36 : 26, 26, L.s(R.string.play_pause), v -> media.playPause());
        miniPlayIcon = (ImageView) mp.getChildAt(0);
        // fork: play and next take the full bar height (before 52×52, and a tap beside them opened the Media tab instead
        // of pausing). Same width and icon: the look does not change.
        FrameLayout mn = Ui.iconButton(c, t, R.drawable.ic_next, t.text, 0, big ? 76 : 52, big ? 34 : 24, 26, L.s(R.string.next), v -> media.skip(true));
        // the press highlight keeps the previous circle: background with vertical margin down to the button's original size
        int barH = big ? dp(BIG_BAR) : tp(100);
        for (FrameLayout mb : new FrameLayout[]{mp, mn}) {
            int ins = Math.max(0, (barH - mb.getLayoutParams().height) / 2);
            mb.setBackground(new android.graphics.drawable.InsetDrawable(mb.getBackground(), 0, ins, 0, ins));
            mini.addView(mb, new LayoutParams(mb.getLayoutParams().width, -1));
        }
        // 0.4.3 (point 7): instead of the mini-player you can have the phone favorites or app shortcuts
        String tileMode = prefs.tileMode();
        if (showMini && !Prefs.TILE_MEDIA.equals(tileMode)) {
            tileBox = Ui.row(c);
            tileBox.setGravity(Gravity.CENTER_VERTICAL);
            tileBox.setPadding(dp(6), 0, dp(4), 0);
            tileIsPhone = Prefs.TILE_PHONE.equals(tileMode);
            fillTile();
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setFillViewport(true);
            hs.addView(tileBox, new FrameLayout.LayoutParams(-2, -1));
            bar.addView(hs, Ui.lpWeight(0, -1, 1));
            if (tileIsPhone) com.mg4.launcher.svc.PhoneClient.get(c).start(phoneListener);
        } else if (showMini) bar.addView(mini, Ui.lpWeight(0, -1, 1));
        else bar.addView(Ui.spacer(c));

        // apps open by swiping the home to the left: no dedicated button
        if (rhd) mirrorRow(bar);
        barView = bar;
        return bar;
    }

    /**
     * Right-hand drive: element order reversed (the temperature ends up at the far right, near the
     * driver), while inside each element everything stays left to right (‹ › arrows included).
     */
    private static void mirrorRow(LinearLayout row) {
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            v.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            if (v.getLayoutParams() instanceof MarginLayoutParams) {
                MarginLayoutParams lp = (MarginLayoutParams) v.getLayoutParams();
                lp.setMarginStart(lp.leftMargin);
                lp.setMarginEnd(lp.rightMargin);
                v.setLayoutParams(lp);
            }
        }
    }

    /** TPMS block: small title (Anteriori / Posteriori) + "2,5 · 2,5 bar" (left · right). */
    private LinearLayout tpmsBlock(Context c, String title) {
        LinearLayout b = Ui.column(c);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        // 0.4.2 (point 8a): no "Anteriori"/"Posteriori" (clear from the drawing): larger numbers
        b.setContentDescription(title);
        View tt = new View(c);
        tt.setVisibility(GONE);
        b.addView(tt);
        TextView v = Ui.oneLine(Ui.text(c, "— · — bar", 27, t.text, Ui.MEDIUM));
        v.setGravity(Gravity.CENTER);
        b.addView(v, new LayoutParams(-2, -2));
        return b;
    }

    private void showTpms(float[] bar, int clusterUnit) {
        boolean any = false;
        for (float f : bar) if (!Float.isNaN(f)) any = true;
        // space always reserved: without data the row is invisible but takes the same height
        tpmsRow.setVisibility(!prefs.showTpms() ? GONE : any ? VISIBLE : INVISIBLE);
        if (!any) return;
        String unit = tyreUnit(prefs.tyreUnit(), clusterUnit);
        setTpms(tpmsFront, bar[0], bar[1], unit);
        setTpms(tpmsRear, bar[2], bar[3], unit);
    }

    /** Effective unit: chosen in the settings, or "Automatica" = the one chosen on the cluster (0 bar, 1 kPa, 2 psi). */
    static String tyreUnit(String pref, int clusterUnit) {
        if (!Prefs.TYRE_AUTO.equals(pref)) return pref;
        return clusterUnit == 1 ? Prefs.TYRE_KPA : clusterUnit == 2 ? Prefs.TYRE_PSI : Prefs.TYRE_BAR;
    }

    /** Pressure in bar converted to the chosen unit (bar with one decimal, kPa and psi as integers). */
    static String tyreValue(float bar, String unit) {
        if (Float.isNaN(bar)) return "—";
        if (Prefs.TYRE_KPA.equals(unit)) return String.valueOf(Math.round(bar * 100f));
        if (Prefs.TYRE_PSI.equals(unit)) return String.valueOf(Math.round(bar * 14.5038f));
        return String.format(L.locale(), "%.1f", bar);
    }

    static String tyreUnitLabel(String unit) {
        return Prefs.TYRE_KPA.equals(unit) ? "kPa" : Prefs.TYRE_PSI.equals(unit) ? "psi" : "bar";
    }

    private void setTpms(LinearLayout block, float left, float right, String unit) {
        TextView v = (TextView) block.getChildAt(1);
        v.setText(tyreValue(left, unit) + " · " + tyreValue(right, unit) + " " + tyreUnitLabel(unit));
        // low pressure (below 2.0 bar): in amber
        boolean low = (!Float.isNaN(left) && left < 2.0f) || (!Float.isNaN(right) && right < 2.0f);
        v.setTextColor(low ? t.warn : t.text);
    }

    /** Heated seat button: icon (mirrored for the right side) + three tick marks for the level. */
    private FrameLayout heatButton(Context c, int icon, boolean mirror, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        f.setContentDescription(desc);
        f.setClickable(true);
        f.setOnClickListener(l);
        ImageView iv = Ui.icon(c, icon, t.text, 32);
        if (mirror) iv.setScaleX(-1f);
        f.addView(iv, new FrameLayout.LayoutParams(bs(32, BIG_ICON), bs(32, BIG_ICON), Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        ((FrameLayout.LayoutParams) iv.getLayoutParams()).topMargin = bs(14, 20);
        LinearLayout dots = Ui.row(c);
        for (int i = 0; i < 3; i++) {
            View d = new View(c);
            d.setBackground(Ui.round(t.track, dp(3)));
            dots.addView(d, Ui.margins(new LayoutParams(bs(12, 18), bs(5, 7)), i == 0 ? 0 : bs(4, 6), 0, 0, 0));
        }
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dl.bottomMargin = bs(14, 20);
        f.addView(dots, dl);
        styleHeat(f, -1);
        return f;
    }

    /** Level 0..3: orange ticks lit, orange icon if on; -1 = not available (dimmed). */
    private void styleHeat(FrameLayout f, int level) {
        Integer prev = (Integer) f.getTag();
        if (prev != null && prev == level) return;
        f.setTag(level);
        f.setAlpha(level < 0 ? 0.45f : 1f);
        f.setBackground(Ui.pressable(getContext(), t.chip, big ? 24 : 20 * Ui.K, t));
        int heat = t.heat;
        ((ImageView) f.getChildAt(0)).setColorFilter(level > 0 ? heat : t.text);
        LinearLayout dots = (LinearLayout) f.getChildAt(1);
        for (int i = 0; i < 3; i++) dots.getChildAt(i).setBackground(Ui.round(i < level ? heat : t.track, dp(3)));
    }

    /** Toggle button with text (A/C, AUTO): on = inverted colors. */
    private TextView toggleText(Context c, String label, String desc, View.OnClickListener l) {
        TextView b = Ui.text(c, label, label.length() > 3 ? bt(17, 24) : bt(20, 28), t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(desc);
        b.setClickable(true);
        b.setOnClickListener(l);
        styleToggle(b, -1);
        return b;
    }

    private FrameLayout toggleIcon(Context c, int icon, String desc, View.OnClickListener l) {
        FrameLayout f = barIcon(c, icon, t.text, t.chip, bs(76, BIG_BTN), bs(32, BIG_ICON), big ? 24 : 20 * Ui.K, desc, l);
        styleToggle(f, -1);
        return f;
    }

    /** state: 1 on, 0 off, -1 unknown/not available (dimmed). onColor = icon color when on. */
    private void styleToggle(View v, int state) {
        styleToggle(v, state, 0);
    }

    private void styleToggle(View v, int state, int onColor) {
        boolean on = state == 1;
        Integer prev = (Integer) v.getTag();
        if (prev != null && prev == state) return;
        v.setTag(state);
        v.setAlpha(state < 0 ? 0.45f : 1f);
        float rad = big ? 24 : 20 * Ui.K;
        if (onColor != 0) {
            // fog lights: icon colored like the cluster indicator (green front, amber rear)
            // fork: the rear fog when on also gets a light amber background, so it does not depend only on the icon color
            v.setBackground(Ui.pressable(getContext(), on && onColor == t.warn ? t.warnBg : t.chip, rad, t));
            ((ImageView) ((FrameLayout) v).getChildAt(0)).setColorFilter(on ? onColor : t.text);
            return;
        }
        v.setBackground(on ? Ui.round(t.text, dp(rad)) : Ui.pressable(getContext(), t.chip, rad, t));
        if (v instanceof TextView) ((TextView) v).setTextColor(on ? t.panel : t.text);
        else ((ImageView) ((FrameLayout) v).getChildAt(0)).setColorFilter(on ? t.panel : t.text);
    }

    // ---- MG4 Clima 1.4.0 (point 10): official icons and colors of the climate pack -------------

    /** Icon/text color per state: 0 off, 1 on (blue), 2 hot (heating, orange), 3 not available. */
    private int climateFg(int state) {
        switch (state) {
            case 1: return getContext().getColor(R.color.climate_on);
            case 2: return getContext().getColor(R.color.climate_warm);
            case 3: return getContext().getColor(R.color.climate_unavailable);
            default: return getContext().getColor(R.color.climate_off);
        }
    }

    /** Background color per state (see climateFg). */
    private int climateBg(int state) {
        switch (state) {
            case 1: return getContext().getColor(R.color.climate_on_bg);
            case 2: return getContext().getColor(R.color.climate_warm_bg);
            default: return getContext().getColor(R.color.climate_off_bg);
        }
    }

    /** Climate icon button (bar button): background and icon colored according to the state. */
    private FrameLayout climateBtn(Context c, int icon, String desc, View.OnClickListener l) {
        FrameLayout f = barIcon(c, icon, t.text, t.chip, bs(76, BIG_BTN), bs(32, BIG_ICON), big ? 24 : 20 * Ui.K, desc, l);
        styleClimate(f, 3);
        return f;
    }

    /** Colors a climate icon button according to the state (0 off, 1 on, 2 hot, 3 not available). */
    private void styleClimate(View v, int state) {
        Integer prev = (Integer) v.getTag();
        if (prev != null && prev == state) return;
        v.setTag(state);
        float rad = big ? 24 : 20 * Ui.K;
        v.setBackground(Ui.round(climateBg(state), dp(rad)));
        ((ImageView) ((FrameLayout) v).getChildAt(0)).setColorFilter(climateFg(state));
    }

    /**
     * Shortcut to an original app. If the exact activity is missing the main app opens; if that is missing too, the
     * button stays dimmed and does nothing. icon = 0: "360°" label instead of the icon.
     */
    /** fork: bar with slack (see buildBottomBar): wider and more spaced shortcuts, more visible dividers. */
    private boolean roomy;
    private int scHalf;

    private int scWidth(int size) {
        return roomy ? size + dp(16) : size;
    }

    /** Divider between bar sections: a bit darker than the track, so it is visible by day too. */
    private View sectionDivider(Context c) {
        View v = Ui.divider(c, (0x59 << 24) | (t.sub & 0xFFFFFF), big ? 72 : 48);
        if (roomy) ((MarginLayoutParams) v.getLayoutParams()).setMargins(dp(10), 0, dp(10), 0);
        return v;
    }

    private View shortcut(Context c, int icon, String desc, String pkg, String cls) {
        int size = bs(64, BIG_SHORTCUT);
        boolean camera = icon == 0;
        View.OnClickListener l = camera ? v -> host.openCamera() : v -> host.launch(pkg, cls);
        View f;
        if (icon == 0) {
            TextView tv = Ui.text(c, "360°", bt(25, 34), t.text, Ui.BOLD);   // 0.4.2 (point 8c): large, readable label
            tv.setGravity(Gravity.CENTER);
            tv.setLetterSpacing(-0.02f);
            tv.setBackground(Ui.pressable(c, 0, big ? 22 : 18 * Ui.K, t));
            tv.setContentDescription(desc);
            tv.setClickable(true);
            tv.setOnClickListener(l);
            f = tv;
        } else {
            f = barIcon(c, icon, t.text, 0, size, bs(30, BIG_ICON - 2), big ? 22 : 18 * Ui.K, desc, l);
        }
        f.setLayoutParams(Ui.margins(Ui.lp(scWidth(size), size), roomy ? scHalf : dp(6), 0, scHalf, 0));
        if (camera ? !host.canOpenCamera() : !host.canLaunch(pkg, cls)) {
            f.setAlpha(0.35f);
            f.setClickable(false);
            f.setOnClickListener(null);
        }
        return f;
    }

    // =========================================================================================
    // Climate / Comfort groups (0.8.2): look like the approved examples (esempio-barra, -clima-aperto, -comfort-aperto)
    // =========================================================================================

    private static final int CLIMATE = 0, COMFORT = 1, DRIVE = 2;

    private int groupWidth() {
        return bs(76, BIG_BTN);
    }

    /**
     * Group button, as large as AUTO: Climate = "A/C" + recirculation icon, Comfort = seat + steering wheel (orange when
     * on). Below, one dot for each control that is on (white for Climate, orange for Comfort).
     */
    private FrameLayout groupButton(Context c, boolean climate) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.pressable(c, t.chip, big ? 24 : 20 * Ui.K, t));
        f.setContentDescription(L.s(climate ? R.string.group_climate : R.string.group_comfort));
        f.setClickable(true);
        LinearLayout content = Ui.row(c);
        content.setGravity(Gravity.CENTER);
        int ic = bs(22, 32);
        if (climate) {
            content.addView(Ui.text(c, "A/C", bt(15, 21), t.text, Ui.MEDIUM));
            content.addView(Ui.icon(c, R.drawable.climate_recirc_inside, t.text, 22), Ui.margins(new LayoutParams(ic, ic), bs(5, 7), 0, 0, 0));
        } else {
            content.addView(Ui.icon(c, R.drawable.climate_seat_heat, t.text, 22), new LayoutParams(ic, ic));
            content.addView(Ui.icon(c, R.drawable.climate_wheel_heat, t.text, 22), Ui.margins(new LayoutParams(ic, ic), bs(10, 14), 0, 0, 0));
        }
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        cl.bottomMargin = bs(8, 12);
        f.addView(content, cl);
        LinearLayout dots = Ui.row(c);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View d = new View(c);
            d.setBackground(Ui.round(climate ? t.text : t.heat, dp(3)));
            d.setVisibility(GONE);
            dots.addView(d, Ui.margins(new LayoutParams(bs(6, 9), bs(6, 9)), i == 0 ? 0 : bs(4, 6), 0, 0, 0));
        }
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dl.bottomMargin = bs(9, 14);
        f.addView(dots, dl);
        if (climate) climateDots = dots;
        else comfortDots = dots;
        f.setOnClickListener(v -> openGroup(climate ? CLIMATE : COMFORT));
        f.setOnLongClickListener(v -> {
            if (climate) vehicle.toggleAc();
            else if (rhd) vehicle.pressSeatRight();   // long press: driver's seat
            else vehicle.pressSeatLeft();
            return true;
        });
        return f;
    }

    private static void showDots(LinearLayout dots, int on) {
        for (int i = 0; i < dots.getChildCount(); i++) dots.getChildAt(i).setVisibility(i < on ? VISIBLE : GONE);
    }

    private void styleGroups(VehicleClient.State s) {
        int recirc = s.loopMode < 0 ? -1 : s.loopMode == 0 ? 1 : 0;
        showDots(climateDots, (s.acOn == 1 ? 1 : 0) + (s.autoOn == 1 ? 1 : 0) + (recirc == 1 ? 1 : 0));
        climateGroup.setAlpha(s.acOn < 0 && s.autoOn < 0 ? 0.45f : 1f);
        boolean seatOn = s.seatLeft > 0 || s.seatRight > 0;
        showDots(comfortDots, (s.seatLeft > 0 ? 1 : 0) + (s.wheelHeat > 0 ? 1 : 0) + (s.seatRight > 0 ? 1 : 0));
        LinearLayout icons = (LinearLayout) comfortGroup.getChildAt(0);
        ((ImageView) icons.getChildAt(0)).setColorFilter(seatOn ? t.heat : t.text);
        ((ImageView) icons.getChildAt(1)).setColorFilter(s.wheelHeat > 0 ? t.heat : t.text);
        icons.getChildAt(0).setVisibility(s.seatLeft >= 0 || s.seatRight >= 0 ? VISIBLE : GONE);
        icons.getChildAt(1).setVisibility(s.wheelHeat >= 0 ? VISIBLE : GONE);
        boolean any = s.seatLeft >= 0 || s.seatRight >= 0 || s.wheelHeat >= 0;
        comfortGroup.setVisibility(any ? VISIBLE : GONE);
    }

    /** Recirculation icon like the original climate app: inside, outside air or automatic. */
    static int recircRes(int loopMode) {
        return ClimateIcon.recircIcon(loopMode);
    }

    private void setRecircIcon(int loopMode) {
        int res = recircRes(loopMode);
        if (res == recircIcon) return;
        recircIcon = res;
        ((ImageView) recircBtn.getChildAt(0)).setImageResource(res);
        if (climateGroup != null) {
            LinearLayout content = (LinearLayout) climateGroup.getChildAt(0);
            if (content.getChildCount() > 1) ((ImageView) content.getChildAt(1)).setImageResource(res);
        }
    }

    static String recircName(int loopMode) {
        return loopMode == 0 ? L.s(R.string.recirc_in) : loopMode == 1 ? L.s(R.string.recirc_out) : loopMode == 2 ? "Auto" : "—";
    }

    /** Drive group button: road icon with lanes (the steering wheel gets confused with heating), one dot for each aid that is on (LKA, OPD, ACC/TJA). */
    private FrameLayout driveButton(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.pressable(c, t.chip, big ? 24 : 20 * Ui.K, t));
        f.setContentDescription(L.s(R.string.group_drive));
        f.setClickable(true);
        int ic = bs(28, 40);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ic, ic, Gravity.CENTER);
        cl.bottomMargin = bs(8, 12);
        f.addView(Ui.icon(c, R.drawable.ic_lanes, t.text, 28), cl);
        driveDots = Ui.row(c);
        driveDots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 4; i++) {
            View d = new View(c);
            d.setBackground(Ui.round(t.text, dp(3)));
            d.setVisibility(GONE);
            driveDots.addView(d, Ui.margins(new LayoutParams(bs(6, 9), bs(6, 9)), i == 0 ? 0 : bs(4, 6), 0, 0, 0));
        }
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dl.bottomMargin = bs(9, 14);
        f.addView(driveDots, dl);
        f.setOnClickListener(v -> openGroup(DRIVE));
        return f;
    }

    private void styleDrive(VehicleClient.State s) {
        boolean any = s.lka >= 0 || s.opd >= 0 || s.accTja >= 0 || s.pedal >= 0 || s.batHeat >= 0;
        driveGroup.setVisibility(prefs.showAssist() && any ? VISIBLE : GONE);
        showDots(driveDots, (s.lka == 1 ? 1 : 0) + (s.opd == 1 ? 1 : 0) + (s.accTja == 1 || s.accTja == 2 ? 1 : 0) + (s.batHeat == 1 ? 1 : 0));
    }

    /** Pedal: Comfort 1 tick, Medium 2, Sport 3 (car values 1, 0, 2). */
    static int pedalNotches(int v) {
        return v == 1 ? 1 : v == 0 ? 2 : v == 2 ? 3 : 0;
    }

    static String pedalName(int v) {
        return v == 1 ? "Comfort" : v == 0 ? "Medium" : v == 2 ? "Sport" : "—";
    }

    static String accName(int v) {
        return v == 1 ? "ACC" : v == 2 ? "TJA" : v == 4 ? "OFF" : "—";
    }

    private void driveTap(boolean pedal) {
        if (!VehicleClient.stopped()) {
            android.widget.Toast.makeText(getContext(), L.s(R.string.drive_stopped), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (pedal) vehicle.cyclePedal();
        else vehicle.cycleAccTja();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (popup != null) popup.close();
        if (tileIsPhone) {
            tileIsPhone = false;
            com.mg4.launcher.svc.PhoneClient.get(getContext()).stop(phoneListener);
        }
    }

    // =========================================================================================
    // 0.4.3 (point 7): configurable bottom box — phone favorites or app shortcuts
    // =========================================================================================

    private LinearLayout tileBox;
    private boolean tileIsPhone;
    private Boolean tilePhoneShown;
    private final com.mg4.launcher.svc.PhoneClient.Listener phoneListener = () -> {
        boolean conn = com.mg4.launcher.svc.PhoneClient.get(getContext()).phoneConnected();
        if (tilePhoneShown == null || tilePhoneShown != conn) fillTile();
    };

    private void fillTile() {
        if (tileBox == null) return;
        Context c = getContext();
        tileBox.removeAllViews();
        int h = bs(56, 76);
        float rad = big ? 22 : 18 * Ui.K;
        if (tileIsPhone) {
            com.mg4.launcher.svc.PhoneClient pc = com.mg4.launcher.svc.PhoneClient.get(c);
            boolean conn = pc.phoneConnected();
            tilePhoneShown = conn;
            java.util.List<String[]> favs = prefs.tileContacts();
            com.mg4.launcher.svc.Diag.put("home.tile", "Phone favorites · " + favs.size() + " favorites · phone "
                    + (conn ? "connected" : "not connected"));
            if (!conn) {
                tileBox.addView(tileHint(c, R.drawable.ic_phone, L.s(R.string.tile_connect_phone), h, rad,
                        v -> host.launch(com.mg4.launcher.apps.MgApps.PHONE_PKG, com.mg4.launcher.apps.MgApps.PHONE_CLS)));
                return;
            }
            if (favs.isEmpty()) {
                tileBox.addView(tileHint(c, R.drawable.ic_phone, L.s(R.string.tile_hint_contacts), h, rad, v -> host.openSettings()));
                return;
            }
            for (int i = 0; i < favs.size(); i++) {
                String[] f = favs.get(i);
                LinearLayout chip = Ui.row(c);
                chip.setPadding(dp(6), 0, tp(16), 0);
                chip.setBackground(Ui.pressable(c, t.chip, rad, t));
                chip.setClickable(true);
                chip.setContentDescription(f[0]);
                TextView ini = Ui.text(c, initials(f[0]), bt(16, 22), t.text, Ui.MEDIUM);
                ini.setGravity(Gravity.CENTER);
                ini.setBackground(Ui.round(t.overlay, dp(100)));
                int ic = h - dp(12);
                chip.addView(ini, new LayoutParams(ic, ic));
                TextView nm = Ui.oneLine(Ui.text(c, firstName(f[0]), bt(16, 22), t.text, Ui.MEDIUM));
                nm.setMaxWidth(dp(big ? 180 : 140));
                chip.addView(nm, Ui.margins(new LayoutParams(-2, -2), dp(10), 0, 0, 0));
                chip.setOnClickListener(v -> pc.call(f[1], ok -> {
                    if (!ok) android.widget.Toast.makeText(getContext(), L.s(R.string.tile_call_failed), android.widget.Toast.LENGTH_SHORT).show();
                }));
                tileBox.addView(chip, Ui.margins(new LayoutParams(-2, h), i == 0 ? 0 : dp(8), 0, 0, 0));
            }
            return;
        }
        java.util.List<String> pkgs = prefs.tileApps();
        android.content.pm.PackageManager pm = c.getPackageManager();
        int shown = 0;
        for (String pkg : pkgs) {
            android.graphics.drawable.Drawable icon;
            String label;
            try {
                if (pm.getLaunchIntentForPackage(pkg) == null) continue;   // app removed or not launchable: skip it
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                icon = pm.getApplicationIcon(ai);
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Exception e) {
                continue;
            }
            FrameLayout b = new FrameLayout(c);
            b.setBackground(Ui.pressable(c, t.chip, rad, t));
            b.setClickable(true);
            b.setContentDescription(label);
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int is = h - dp(16);
            b.addView(iv, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
            b.setOnClickListener(v -> host.launch(pkg, null));
            tileBox.addView(b, Ui.margins(new LayoutParams(h, h), shown == 0 ? 0 : dp(8), 0, 0, 0));
            shown++;
        }
        com.mg4.launcher.svc.Diag.put("home.tile", "App shortcuts · " + shown + " of " + pkgs.size() + " openable");
        if (shown == 0) tileBox.addView(tileHint(c, R.drawable.ic_app, L.s(R.string.tile_hint_apps), h, rad, v -> host.openSettings()));
    }

    private View tileHint(Context c, int icon, String text, int h, float rad, View.OnClickListener l) {
        LinearLayout chip = Ui.row(c);
        chip.setPadding(tp(16), 0, tp(18), 0);
        chip.setBackground(Ui.pressable(c, t.chip, rad, t));
        chip.setClickable(true);
        chip.setOnClickListener(l);
        chip.addView(Ui.icon(c, icon, t.sub, bs(24, 32) / 1), new LayoutParams(bs(24, 32), bs(24, 32)));
        chip.addView(Ui.oneLine(Ui.text(c, text, bt(16, 20), t.sub, Ui.MEDIUM)), Ui.margins(new LayoutParams(-2, -2), dp(10), 0, 0, 0));
        chip.setLayoutParams(new LayoutParams(-2, h));
        return chip;
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            int cp = w.codePointAt(0);
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toUpperCase(cp));
            if (sb.length() >= 2) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private static String firstName(String name) {
        String n = name.trim();
        int sp = n.indexOf(' ');
        return sp > 0 && sp < n.length() - 1 ? n.substring(0, sp) : n;
    }

    /** 0.4.0: closes the Climate/Comfort/Drive box, if open (Home key). True if there was one. */
    public boolean closePopup() {
        if (popup == null) return false;
        popup.close();
        return true;
    }

    private void openGroup(int kind) {
        if (popup != null) popup.close();
        popup = new GroupPopup(kind);
        popup.show();
    }

    /** Screen dimmed except for the open group's button, which stays lit with its border (white or orange). */
    private final class Dimmer extends FrameLayout {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF hole = new android.graphics.RectF();
        final android.graphics.Path path = new android.graphics.Path();
        int ring;
        float radius;
        /** Fork: 0..1 during enter/exit (multiplies the dimming and the ring); closing blocks touches. */
        float fade = 1f;
        boolean closing;

        Dimmer(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            return closing || super.onInterceptTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            return closing || super.onTouchEvent(e);
        }

        private int faded(int color) {
            int a = Math.round(((color >>> 24) & 0xFF) * fade);
            return (a << 24) | (color & 0x00FFFFFF);
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas cv) {
            path.reset();
            path.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            path.addRect(0, 0, getWidth(), getHeight(), android.graphics.Path.Direction.CW);
            if (!hole.isEmpty()) path.addRoundRect(hole, radius, radius, android.graphics.Path.Direction.CW);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(faded(t.day ? 0x80000000 : 0x99000000));
            cv.drawPath(path, p);
            if (!hole.isEmpty()) {
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(faded(ring));
                android.graphics.RectF r = new android.graphics.RectF(hole);
                r.inset(-dp(2), -dp(2));
                cv.drawRoundRect(r, radius + dp(2), radius + dp(2), p);
            }
            super.dispatchDraw(cv);
        }
    }

    /**
     * Box above the bar with the large tiles, as in the examples: title on the left, "tocca fuori per chiudere" on the
     * right, no X, pointing at the button. Closes when tapping outside or by itself after 6 seconds without touches.
     */
    private final class GroupPopup {
        private static final long AUTO_CLOSE_MS = 6000;
        /** climate: white tiles when on (Climate and Drive); otherwise orange (Comfort). */
        final boolean climate, drive;
        final Dimmer catcher;
        final LinearLayout card, tiles;
        final View arrow;
        final Runnable closer = this::close;
        final java.util.List<FrameLayout> tileViews = new java.util.ArrayList<>();

        GroupPopup(int kind) {
            this.climate = kind != COMFORT;
            this.drive = kind == DRIVE;
            Context c = getContext();
            catcher = new Dimmer(c);
            catcher.ring = climate ? t.text : t.heat;
            catcher.radius = dp(big ? 24 : 20 * Ui.K);
            catcher.setClickable(true);
            catcher.setOnClickListener(v -> close());
            card = Ui.column(c);
            card.setClickable(true);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(t.panel);
            bg.setCornerRadius(dp(28));
            bg.setStroke(dp(1), t.track);
            card.setBackground(bg);
            card.setPadding(dp(32), dp(24), dp(32), dp(30));
            LinearLayout head = Ui.row(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = Ui.text(c, L.s(drive ? R.string.group_drive : climate ? R.string.group_climate : R.string.group_comfort).toUpperCase(L.locale()), 16, t.sub, Ui.MEDIUM);
            title.setLetterSpacing(0.12f);
            head.addView(title, Ui.lpWeight(0, -2, 1));
            head.addView(Ui.text(c, L.s(R.string.tap_outside_close), 15, t.sub, Ui.REGULAR), Ui.margins(new LayoutParams(-2, -2), dp(40), 0, 0, 0));
            card.addView(head, new LayoutParams(-1, -2));
            tiles = Ui.row(c);
            card.addView(tiles, Ui.margins(new LayoutParams(-2, -2), 0, dp(24), 0, 0));
            buildTiles(c);
            arrow = new View(c) {
                final android.graphics.Paint ap = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                final android.graphics.Path tri = new android.graphics.Path();

                @Override
                protected void onDraw(android.graphics.Canvas cv) {
                    tri.reset();
                    tri.moveTo(0, 0);
                    tri.lineTo(getWidth(), 0);
                    tri.lineTo(getWidth() / 2f, getHeight());
                    tri.close();
                    ap.setColor(t.panel);
                    cv.drawPath(tri, ap);
                }
            };
            catcher.addView(card, new FrameLayout.LayoutParams(-2, -2));
            catcher.addView(arrow, new FrameLayout.LayoutParams(dp(28), dp(14)));
            card.setVisibility(INVISIBLE);
            arrow.setVisibility(INVISIBLE);
            card.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> place());
            refresh(vehicle.last());
        }

        private void buildTiles(Context c) {
            VehicleClient.State s = vehicle.last();
            if (drive) {
                if (s.lka >= 0) addTile(c, "lka", "LKA", 0, L.s(R.string.tile_lka), v -> vehicle.toggleLka());
                if (s.opd >= 0) addTile(c, "opd", "OPD", 0, "One Pedal", v -> {
                    VehicleClient.State st = vehicle.last();
                    if (st.opdInhibit > 0 && st.opd == 0) {
                        android.widget.Toast.makeText(getContext(), L.s(R.string.opd_blocked), android.widget.Toast.LENGTH_LONG).show();
                    }
                    vehicle.toggleOpd();
                });
                if (s.accTja >= 0) addTile(c, "acc", accName(s.accTja), 0, L.s(R.string.tile_acc), v -> driveTap(false));
                if (s.pedal >= 0) addTile(c, "pedal", null, R.drawable.ic_pedal, L.s(R.string.tile_pedal), v -> driveTap(true));
                if (s.batHeat >= 0) addTile(c, "bheat", null, R.drawable.ic_battery_heat, L.s(R.string.tile_bat_heat), v -> {
                    if (vehicle.toggleBatteryHeat() == 1)
                        android.widget.Toast.makeText(getContext(), L.s(R.string.bat_heat_on_hint), android.widget.Toast.LENGTH_LONG).show();
                });
            } else if (climate) {
                addTile(c, "ac", "A/C", 0, L.s(R.string.ac_desc), v -> vehicle.toggleAc());
                addTile(c, "auto", "AUTO", 0, L.s(R.string.tile_auto), v -> vehicle.toggleAuto());
                if (s.loopMode >= 0) addTile(c, "recirc", null, recircRes(s.loopMode), L.s(R.string.tile_recirc), v -> vehicle.toggleRecirc());
            } else {
                // 0.9.6: tiles in the physical order of the seats (left, wheel, right). With right-hand drive the driver
                // is on the right seat: the labels change, not the commands (each tile controls the seat in front of it).
                // Same command as the single buttons: 3 → 2 → 1 → off (before, the box's "off" did not stick).
                if (s.seatLeft >= 0) addTile(c, "seatL", null, R.drawable.climate_seat_heat,
                        L.s(rhd ? R.string.tile_seat_passenger : R.string.tile_seat_driver), v -> vehicle.pressSeatLeft());
                if (s.wheelHeat >= 0) addTile(c, "wheel", null, R.drawable.climate_wheel_heat, L.s(R.string.tile_wheel), v -> vehicle.toggleWheelHeat());
                if (s.seatRight >= 0) addTile(c, "seatR", null, R.drawable.climate_seat_heat,
                        L.s(rhd ? R.string.tile_seat_driver : R.string.tile_seat_passenger), v -> vehicle.pressSeatRight());
            }
        }

        /** Tile: large label or icon, name, state below (Accesa / Spento / Livello 2) and, for Comfort, the tick marks. */
        private void addTile(Context c, String id, String text, int icon, String name, View.OnClickListener l) {
            int w = big ? dp(210) : tp(185), h = big ? dp(256) : tp(228);
            FrameLayout f = new FrameLayout(c);
            f.setContentDescription(name);
            f.setClickable(true);
            f.setOnClickListener(v -> {
                l.onClick(v);
                touched();
            });
            LinearLayout col = Ui.column(c);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            View main;
            int mh = big ? dp(66) : tp(58);
            if (text != null) {
                TextView tv = Ui.text(c, text, big ? 50 : ts(44), t.text, Ui.REGULAR);
                tv.setGravity(Gravity.CENTER);
                main = tv;
                col.addView(tv, new LayoutParams(-2, mh));
            } else {
                ImageView iv = Ui.icon(c, icon, t.text, 48);
                iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                main = iv;
                col.addView(iv, new LayoutParams(mh, mh));
            }
            TextView nm = Ui.oneLine(Ui.text(c, name, big ? 22 : ts(20), t.text, Ui.REGULAR));
            nm.setGravity(Gravity.CENTER);
            col.addView(nm, Ui.margins(new LayoutParams(w - dp(10), -2), 0, dp(10), 0, 0));
            TextView st = Ui.oneLine(Ui.text(c, "", big ? 18 : ts(16), t.sub, Ui.REGULAR));
            st.setGravity(Gravity.CENTER);
            col.addView(st, Ui.margins(new LayoutParams(w - dp(10), -2), 0, dp(4), 0, 0));
            LinearLayout notches = Ui.row(c);
            if (!climate || id.equals("pedal")) {
                int n = id.equals("wheel") ? 1 : 3;
                for (int i = 0; i < n; i++) {
                    View d = new View(c);
                    notches.addView(d, Ui.margins(new LayoutParams(big ? dp(34) : tp(30), big ? dp(7) : tp(6)), i == 0 ? 0 : dp(7), 0, 0, 0));
                }
            }
            col.addView(notches, Ui.margins(new LayoutParams(-2, -2), 0, dp(14), 0, 0));
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
            f.addView(col, flp);
            f.setTag(new Object[]{id, main, nm, st, notches});
            tiles.addView(f, Ui.margins(new LayoutParams(w, h), tileViews.isEmpty() ? 0 : dp(20), 0, 0, 0));
            tileViews.add(f);
        }

        void refresh(VehicleClient.State s) {
            for (FrameLayout v : tileViews) {
                Object[] tag = (Object[]) v.getTag();
                String id = (String) tag[0];
                int state, level = -1;
                switch (id) {
                    case "ac": state = s.acOn; break;
                    case "auto": state = s.autoOn; break;
                    case "recirc": state = s.loopMode < 0 ? -1 : s.loopMode == 0 ? 1 : 0; break;
                    case "lka": state = s.lka; break;
                    case "opd": state = s.opd; break;
                    case "acc": state = s.accTja < 0 ? -1 : s.accTja == 4 ? 0 : 1; break;
                    case "pedal": state = s.pedal < 0 ? -1 : 0; level = pedalNotches(s.pedal); break;
                    case "bheat": state = s.batHeat; break;
                    case "wheel": state = s.wheelHeat; level = s.wheelHeat > 0 ? 1 : 0; break;
                    default:
                        level = id.equals("seatL") ? s.seatLeft : s.seatRight;
                        state = level < 0 ? -1 : level > 0 ? 1 : 0;
                }
                boolean on = state == 1;
                v.setAlpha(state < 0 ? 0.45f : 1f);
                float rad = dp(big ? 24 : 20 * Ui.K);
                TextView nm = (TextView) tag[2], st = (TextView) tag[3];
                String stateText = state < 0 ? "—" : (id.equals("seatL") || id.equals("seatR")) && on ? L.f(R.string.tile_level, level)
                        : id.equals("ac") ? L.s(on ? R.string.tile_on_f : R.string.tile_off_f) : L.s(on ? R.string.tile_on_m : R.string.tile_off_m);
                if (state >= 0 && id.equals("recirc")) {
                    stateText = recircName(s.loopMode);
                    ((ImageView) tag[1]).setImageResource(recircRes(s.loopMode));
                }
                if (state >= 0 && id.equals("acc")) {
                    stateText = s.accTja == 4 ? L.s(R.string.tile_off_m) : accName(s.accTja);
                    ((TextView) tag[1]).setText(accName(s.accTja));
                }
                if (state >= 0 && id.equals("pedal")) stateText = pedalName(s.pedal);
                st.setText(stateText);
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(rad);
                if (climate) {
                    // on = white tile with dark text, like the A/C button
                    g.setColor(on ? t.text : t.chip);
                    int fg = on ? t.panel : t.text;
                    if (tag[1] instanceof TextView) ((TextView) tag[1]).setTextColor(fg);
                    else ((ImageView) tag[1]).setColorFilter(fg);
                    nm.setTextColor(fg);
                    st.setTextColor(on ? (t.panel & 0x00FFFFFF) | 0xB3000000 : t.sub);
                    LinearLayout notches = (LinearLayout) tag[4];
                    for (int i = 0; i < notches.getChildCount(); i++) {
                        notches.getChildAt(i).setBackground(Ui.round(i < level ? t.text : t.track, dp(3)));
                    }
                } else {
                    // on = warm background, orange border, orange icon and state, level ticks
                    g.setColor(on ? (t.day ? 0xFFFFF1EA : 0xFF2E241F) : t.chip);
                    if (on) g.setStroke(dp(2), t.heat);
                    ((ImageView) tag[1]).setColorFilter(on ? t.heat : t.text);
                    nm.setTextColor(t.text);
                    st.setTextColor(on ? (t.day ? t.text : t.heat) : t.sub);   // fork: readable text by day
                    LinearLayout notches = (LinearLayout) tag[4];
                    for (int i = 0; i < notches.getChildCount(); i++) {
                        notches.getChildAt(i).setBackground(Ui.round(i < level ? t.heat : t.track, dp(3)));
                    }
                }
                v.setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(t.day ? 0x22000000 : 0x33FFFFFF), g, null));
            }
        }

        /** Box centered above the group button (inside the screen), pointing at the button, hole in the dimming. */
        private void place() {
            View anchor = drive ? driveGroup : climate ? climateGroup : comfortGroup;
            if (anchor == null || barView == null || card.getWidth() == 0) return;
            int[] a = new int[2], root = new int[2], bar = new int[2];
            anchor.getLocationOnScreen(a);
            catcher.getLocationOnScreen(root);
            barView.getLocationOnScreen(bar);
            float ax = a[0] - root[0], ay = a[1] - root[1];
            catcher.hole.set(ax, ay, ax + anchor.getWidth(), ay + anchor.getHeight());
            float cx = ax + anchor.getWidth() / 2f;
            float x = Math.max(dp(20), Math.min(catcher.getWidth() - card.getWidth() - dp(20), cx - card.getWidth() / 2f));
            float y = bar[1] - root[1] - card.getHeight() - dp(20);
            card.setX(x);
            card.setY(y);
            arrow.setX(cx - arrow.getWidth() / 2f);
            arrow.setY(y + card.getHeight() - 1);
            card.setVisibility(VISIBLE);
            arrow.setVisibility(VISIBLE);
            catcher.invalidate();
        }

        void show() {
            host.overlay().addView(catcher, new FrameLayout.LayoutParams(-1, -1));
            fade(true, null);
            touched();
        }

        /**
         * Fork: enter 150 ms / exit 120 ms with alpha only. The card and arrow position comes from place() (setX/setY): the
         * translation is not animated, otherwise the card jumped to the top of the screen.
         */
        private void fade(boolean in, Runnable end) {
            float from = in ? 0f : catcher.fade;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(from, in ? 1f : 0f);
            a.setDuration(in ? 150 : 120);
            a.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
            card.setLayerType(LAYER_TYPE_HARDWARE, null);
            if (in) {
                catcher.fade = 0f;
                card.setAlpha(0f);
                arrow.setAlpha(0f);
            }
            a.addUpdateListener(x -> {
                float f = (float) x.getAnimatedValue();
                catcher.fade = f;
                card.setAlpha(f);
                arrow.setAlpha(f);
                catcher.invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator an) {
                    card.setLayerType(LAYER_TYPE_NONE, null);
                    if (end != null) end.run();
                }
            });
            a.start();
        }

        void touched() {
            catcher.removeCallbacks(closer);
            catcher.postDelayed(closer, AUTO_CLOSE_MS);
        }

        void close() {
            catcher.removeCallbacks(closer);
            if (popup == this) popup = null;   // right away: refresh() and openGroup() stop seeing it
            if (catcher.closing) return;
            catcher.closing = true;             // from here on no touch reaches the tiles (no repeated commands)
            ViewGroup p = (ViewGroup) catcher.getParent();
            if (p == null) return;
            fade(false, () -> {
                if (catcher.getParent() == p) p.removeView(catcher);
            });
        }
    }

    private static String tempLabel(int v) {
        if (v <= VehicleClient.TEMP_MIN) return "LO";
        if (v >= VehicleClient.TEMP_MAX) return "HI";
        return v + "°";
    }

    private void changeTemp(int delta) {
        if (acTemp <= 0) acTemp = vehicle.last().acTemp;
        if (acTemp <= 0) return;
        acTemp = Math.max(VehicleClient.TEMP_MIN, Math.min(VehicleClient.TEMP_MAX, acTemp + delta));
        localTempUntil = SystemClock.uptimeMillis() + 4000;
        tempTv.setText(tempLabel(acTemp));
        vehicle.setDriverTemp(acTemp);
    }

    private static String fanLabel(int v) {
        if (v == VehicleClient.FAN_AUTO) return "A";
        if (v <= 0) return "OFF";
        return String.valueOf(v);
    }

    /** Like the original: from off or automatic the first tap starts from the minimum/maximum. */
    private void changeFan(int delta) {
        if (fanLevel < 0) fanLevel = vehicle.last().fanLevel;
        int cur = fanLevel;
        int next;
        if (cur == VehicleClient.FAN_AUTO) next = delta > 0 ? fanMax : fanMax - 1;
        else if (cur <= 0) next = delta > 0 ? fanMin : 0;
        else next = cur + delta;
        if (next < fanMin) next = VehicleClient.FAN_OFF;
        if (next > fanMax) next = fanMax;
        if (next == cur) return;
        fanLevel = next;
        localFanUntil = SystemClock.uptimeMillis() + 4000;
        fanTv.setText(fanLabel(fanLevel));
        vehicle.setFanLevel(fanLevel);
    }
}
