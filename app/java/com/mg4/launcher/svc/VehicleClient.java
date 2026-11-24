package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

/**
 * Vehicle, SWI69 version (EH32 Color Radio). Same interface as the SWI68 version, but with the SWI69 services:
 * <ul>
 *   <li>com.saicmotor.caradapter: "hub" (ICarAdapterService.queryClient(code)) that returns the binders for
 *       charging, climate, lights, configuration, cluster and vehicle settings, like the original home (carapi);</li>
 *   <li>"bms" battery manager of the CarService (CarBms): percentage, power and charging commands, like the
 *       Charging management page of the Energy app (com.saicmotor.saicev, EH32 EU/UK boards);</li>
 *   <li>CarService properties (CarProps): tyre pressure and inside temperature.</li>
 * </ul>
 */
public final class VehicleClient {
    private static final String PKG = "com.saicmotor.caradapter";
    private static final String ACTION = "com.saicmotor.caradapter.CarAdapterService";

    private static final String HUB = "com.saicmotor.carapi.ICarAdapterService";
    private static final int HUB_QUERY_CLIENT = 1;
    private static final int BIND_EV = 4, BIND_HVAC = 7, BIND_CONFIG = 2, BIND_LAMP = 13, BIND_IPK = 5, BIND_VS = 8;

    private static final String EV = "com.saicmotor.carapi.ev.ICarEvService";
    private static final int EV_PERCENT = 3;          // int  getChargePercentChange
    private static final int EV_STATUS = 4;           // int  getChargeStatus (same codes as SWI68)
    private static final int EV_REMAIN_TIME = 6;      // int  getChargingRemainTime (minutes)
    private static final int EV_DISCHG_TIME = 7;      // int  getDisChargingRemainTime
    private static final int EV_UNITS = 11;           // int  getDistanceUnits (0 = km, 1 = miles)
    private static final int EV_RANGE = 12;           // int  getCurrentRange
    private static final int EV_CHG_CTRL = 20;        // int  getChargeCtrlStatus
    private static final int EV_DISCHG_CTRL = 24;     // int  getDisChargeCtrl
    public static final int CTRL_START = 1, CTRL_STOP = 2, CTRL_PREPARING = 3;
    /** AC charging currents allowed by the car (codes 1..4, CarBMSManager.ALTNG_CHRG_CRNT_*). */
    public static final String[] CURRENT_LABELS = {"6 A", "8 A", "16 A", "Max"};

    private static final String AC = "com.saicmotor.carapi.hvac.ICarHvacService";
    private static final int AC_SWITCH_POWER = 5;     // void switchHvacPowerStatus()
    private static final int AC_GET_POWER = 6;        // bool getHvacPowerStatus
    private static final int AC_SWITCH_AUTO = 9;      // void switchAutoStatus()
    private static final int AC_GET_AUTO = 10;        // bool getAutoStatus
    private static final int AC_SET_FAN = 19;         // void setFanSpeed(int)
    private static final int AC_GET_FAN = 20;         // int  getFanSpeed
    private static final int AC_SET_DRV_TEMP = 21;    // void setDriverTemperature(float)
    private static final int AC_GET_DRV_TEMP = 22;    // float getDriverTemperature
    private static final int AC_GET_OUT_TEMP = 25;    // float getOutSideTemperature
    private static final int AC_SWITCH_AC = 7, AC_GET_AC = 8;                 // switchACStatus / getACStatus
    private static final int AC_SWITCH_DEFROST = 27, AC_GET_DEFROST = 28;     // windscreen
    // recirculation, like the climate bar of the original home: "toggle" without arguments; 0 inside, 1 outside, 2 auto
    private static final int AC_SWITCH_LOOP = 15, AC_GET_LOOP = 16;
    /** 0.4.2 (point 9): like the original EH32 climate app: seats switchSeatHeatStatus(1 left / 2 right), getSeatHeatStatus(side);
     *  steering wheel setSteeringWheelHeatStatus(1) which toggles, getSteeringWheelHeatStatus (3 = on). */
    private static final int AC_SWITCH_SEAT_HEAT = 31, AC_GET_SEAT_HEAT = 32, AC_SET_WHEEL_HEAT = 70, AC_GET_WHEEL_HEAT = 71;

    // fog lights, like the original car screen: set(1) = "pressed" (toggles), get 1 = on
    private static final String LAMP = "com.saicmotor.carapi.lamp.ICarLampService";
    private static final int LAMP_SET_REAR_FOG = 30, LAMP_GET_REAR_FOG = 31, LAMP_SET_FRONT_FOG = 40, LAMP_GET_FRONT_FOG = 41;
    /** 0.4.2: dipped beam (0/1), position lights (3 = on), light switch (1 = dipped beam by hand), like the original home. */
    private static final int LAMP_GET_DIPD_BEAM = 42, LAMP_GET_SIDE_LIGHT = 43, LAMP_GET_SWITCH_POS = 44;
    private static final String CONFIG = "com.saicmotor.carapi.config.ICarConfigService";
    private static final int CONFIG_FRONT_FOG = 60;   // getFrontFrogConfigData: 0 = front fog lights present
    // driving assistance, like the original Vehicle settings app of SWI69 R45 (ICarConfigService)
    private static final int CONFIG_LKA = 20;         // getLkaConfigData: 0 = LKA present, 1 absent
    private static final int CONFIG_ONE_PEDAL = 43;
    /** getRvcAvmConfigData, like the original SWI69 R45 home: 0 = no camera; 1, 2, 4 = EVS; 3 = AVM; 5 = cluster AVM. */
    private static final int CONFIG_RVC_AVM = 11;
    /** 0.4.2 (point 9): configuration used by the original EH32 climate app (HvacService/HvacMainFragment t11_eh32). */
    private static final int CONFIG_CAR_MODEL = 8, CONFIG_SEAT_HEAT = 23, CONFIG_WHEEL_HEAT = 52;
    /** Models for which the original climate app shows the heated steering wheel (AppCache.hasSteeringWheelHeatEH32CarModeConfig). */
    private static final int[] WHEEL_HEAT_MODELS = {69, 130, 131, 166, 173, 177, 132, 198, 200};   // getOnePedalConfig: 0 = One Pedal present (H17A), 1 absent
    private static final String VS = "com.saicmotor.carapi.vs.ICarVehicleSettingService";
    // LKA (getLasMode/setLasMode): 0 no request, 1 off, 2 LDW, 3 LDP, 4 LKA, 5 ELK
    private static final int VS_SET_LAS = 30, VS_GET_LAS = 31;
    // One Pedal: setSignalPedalState / getSignalPedalState (1 on, 0 off) / getSignalPedalEnable (inhibit:
    // 1 charge limit, 2 braking system, 3 conditions, 0 none)
    private static final int VS_SET_OPD = 162, VS_GET_OPD = 163, VS_GET_OPD_INHIBIT = 164;
    // keys of the "expected" state (different from the light codes: 31 is also the rear fog light)
    private static final int K_LKA = 80031, K_OPD = 80163;
    // driving assistant (ACC/TJA) and brake pedal (0.3.8 / 0.2.5): same car properties as SWI68
    private static final int VS_SET_ACC_TJA = 152, VS_GET_ACC_TJA = 153, VS_SET_PEDAL = 156, VS_GET_PEDAL = 157;
    private static final int K_ACC_TJA = 80153, K_PEDAL = 80157;
    private static final String IPK = "com.saicmotor.carapi.ipk.ICarIpkService";
    private static final int IPK_TYRE_UNIT = 29;      // 0 bar, 1 kPa, 2 psi (user's choice on the cluster)
    private static final int IPK_DAY_NIGHT = 23;      // 0.4.2: getClusterDayNightMode (only diagnostics tema.candidati)

    /** Fan like the launcher bar: 0 = climate off, 1..11, 15 = automatic. */
    public static final int FAN_OFF = 0, FAN_AUTO = 15;
    private static final int FAN_MIN = 1, FAN_MAX = 11;
    /** How long the value set by the user prevails over the readings (which arrive late). */
    private static final long HOLD_MS = 4000;

    /**
     * Temperature in the bar: MG4 EU/UK on SWI69 goes from 16 to 30 °C, with LO = 15.5 and HI = 30.5
     * (CarModel.isTempRange16to30CarModel of the original home). In the launcher: 15 = "LO", 31 = "HI".
     */
    public static final int TEMP_MIN = 15;   // "LO"
    public static final int TEMP_MAX = 31;   // "HI"
    private static final float CAR_LO = 15.5f, CAR_HI = 30.5f;

    /** Snapshot of the vehicle state read from the services. */
    public static final class State {
        public boolean connected;
        /** 1.0.4: charging socket BMS_CHRG_PLUG_CNCTNIO (0 cable plugged in, 1 no, -1 not read). */
        public int plugProp = -1;
        /**
         * 0.4.5: true READY from SENSOR_EPTRDY (SAIC car state service, ICarStateService.getSensorEptrdyState):
         * 1 = READY, 0 = not in READY, -1 = unknown. Ignition (cluster on) is NOT READY.
         */
        public int ready = -1;
        public String readySource = "not read";
        /** 0.4.5: gear from the car state service (CarState: 1 P, 2 R, 3 N, 4-11 forward, 0 between two gears); -1 not read. */
        public int gear = -1;
        public float speedKmh = Float.NaN;
        public float soc = -1;            // %
        public int rangeKm = -1;
        public int chargingStatus = -1;   // SAIC codes (same as SWI68), see chargeText
        public int predictMinutes = -1;
        public float chargeKw = 0;
        public int chargeLimit = -1;      // % (converted from the SAIC code)
        public int chargeLimitCode = -1;  // 1..7
        public int currentLimit = -1;     // 1..4 (6 A, 8 A, 16 A, Max), 0 = unavailable
        public int chargeLock = -1;       // 0.4.0 (point 6): 1 locked, 2 unlocked, -1 not read
        public int chargeControl = -1;    // 1 charging, 2 stopped, 3 preparing
        public int dischargeControl = -1; // same for V2L
        public int dischargeLimit = -1;   // % below which V2L stops
        public int dischargeMinutes = -1;
        public boolean bmsAvailable;
        public int distUnit = -1;         // 0 km, 1 miles (from the cluster)
        public int tyreUnitCluster = -1;  // cluster pressure unit: 0 bar, 1 kPa, 2 psi
        public int dischargeStatus = -1;
        public int loopMode = -1;         // recirculation: 0 inside, 1 outside, 2 auto, -1 not supported
        public int acTemp = -1;           // 15 = LO, 16..30, 31 = HI
        public int hvacPower = -1;
        public int acOn = -1, autoOn = -1, defrostOn = -1;   // 1 on, 0 off, -1 unknown
        public int seatLeft = -1, seatRight = -1;          // 0.4.2: level 0..3, -1 = button hidden (car without it or Hide chosen)
        public int wheelHeat = -1;                         // 0.4.2: 1 on, 0 off, -1 hidden
        /** 0.4.2: lights from the car: dipped beam 0/1, position lights (3 = on), switch (1 = by hand); -1 not read. */
        public int lowBeam = -1, sideLight = -1, lightSwitch = -1;
        /** 0.4.2 (point 7d): charge limit visible (car supports the command or Show chosen). */
        public boolean chargeLimitShown;
        /** 0.4.2 (point 7d): scheduled charging: mode 1 timed, 2 off, 3 up to the limit; -1 = the car does not support it. */
        public int schedMode = -1, schedStartH = -1, schedStartM = -1, schedEndH = -1, schedEndM = -1;
        public float[] tireBar = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};   // FL, FR, RL, RR
        public float insideTemp = Float.NaN;
        public String insideSource = "";            // 0.4.3: where the inside temperature comes from ("" = no valid source)
        public int rearFog = -1, frontFog = -1;           // 1 on, 0 off, -1 unavailable
        public boolean hasFrontFog;
        /** Camera configuration (getRvcAvmConfigData); Integer.MIN_VALUE until known. */
        public int cameraConfig = Integer.MIN_VALUE;
        public int lka = -1;              // 1 on, 0 off, -1 unavailable
        public int lkaMode = -1;          // raw value from the car (see VS_GET_LAS)
        /** Driving assistant (car values): 1 ACC, 2 TJA, 4 off; -1 unavailable. */
        public int accTja = -1;
        /** Brake pedal sensitivity: 1 Comfort, 0 Medium, 2 Sport; -1 unavailable. */
        public int pedal = -1;
        public int opd = -1;              // One Pedal Drive: 1 on, 0 off, -1 unavailable
        /** Smart battery heating (fork): 1 on, 0 off, -1 unavailable. */
        public int batHeat = -1;
        public int opdInhibit;            // OPD inhibit reason (1 charging, 2 system, 3 conditions), 0 none
        public int fanLevel = -1;         // 0 off, 1..max, 15 auto
        public int fanMin = FAN_MIN, fanMax = FAN_MAX;
        public float outsideTemp = -10000f;   // °C
    }

    public interface Listener {
        void onVehicleState(State s);
    }

    private final Context ctx;
    private final ServiceLink hub;
    private final CarBms bms;
    private final Map<Integer, IBinder> subs = new HashMap<>();
    private final HandlerThread thread = new HandlerThread("mg4-vehicle");
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile State last = new State();
    private volatile int pendingTemp = -1, pendingFan = -1;
    private volatile int heldTemp = -1, heldFan = -1;
    private volatile long tempHoldUntil, fanHoldUntil;
    private boolean running;

    public VehicleClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        hub = new ServiceLink(ctx, "vehicle", ServiceLink.byAction(PKG, ACTION));
        bms = new CarBms(ctx);
        hub.setOnConnected(() -> {
            synchronized (subs) {
                subs.clear();
            }
            if (bg != null) bg.post(this::poll);
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public State last() {
        return last;
    }

    public void start() {
        if (running) return;
        running = true;
        if (bg == null) {
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        hub.start();
        bms.start();
        bg.post(loop);
    }

    public void stop() {
        pause();
        hub.stop();
    }

    /** Suspends the readings (launcher not visible) without disconnecting from the services. */
    public void pause() {
        running = false;
        if (bg != null) bg.removeCallbacks(loop);
    }

    public void resume() {
        if (bg == null || running) return;
        running = true;
        bg.post(loop);
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            poll();
            bg.postDelayed(this, 2000);
        }
    };

    private IBinder sub(int code) throws RemoteException {
        synchronized (subs) {
            IBinder b = subs.get(code);
            if (b != null && b.isBinderAlive()) return b;
        }
        IBinder h = hub.binder();
        if (h == null) return null;
        IBinder b = Rpc.callBinder(h, HUB, HUB_QUERY_CLIENT, p -> p.writeInt(code));
        if (b != null) {
            synchronized (subs) {
                subs.put(code, b);
            }
        }
        return b;
    }

    private void forget(int code) {
        synchronized (subs) {
            subs.remove(code);
        }
    }

    /** Charge limit: code 1 = 40% … 7 = 100% (CarBMSManager.CHRG_TRGT_SOC_*). */
    static int limitPercent(int code) {
        if (code >= 1 && code <= 7) return (code + 3) * 10;
        return -1;
    }

    /** Car temperature (°C, in half degrees) → bar value (15 = LO … 31 = HI). */
    static int tempFromCar(float f) {
        if (Float.isNaN(f) || f <= 0) return -1;
        if (f <= CAR_LO + 0.01f) return TEMP_MIN;
        if (f >= CAR_HI - 0.01f) return TEMP_MAX;
        return Math.max(TEMP_MIN + 1, Math.min(TEMP_MAX - 1, Math.round(f)));
    }

    /** Bar value → temperature to send to the car. */
    static float tempToCar(int v) {
        if (v <= TEMP_MIN) return CAR_LO;
        if (v >= TEMP_MAX) return CAR_HI;
        return v;
    }

    private static boolean ok(float v) {
        return !Float.isNaN(v);
    }

    private int distUnit = -1;

    private void poll() {
        State s = new State();
        s.connected = hub.isConnected();
        long now = android.os.SystemClock.uptimeMillis();
        if (s.connected) {
            try {
                IBinder e = sub(BIND_EV);
                if (e != null) {
                    s.soc = Rpc.callInt(e, EV, EV_PERCENT);
                    s.rangeKm = Rpc.callInt(e, EV, EV_RANGE);
                    s.chargingStatus = Rpc.callInt(e, EV, EV_STATUS);
                    s.predictMinutes = Rpc.callInt(e, EV, EV_REMAIN_TIME);
                    s.dischargeStatus = Rpc.callInt(e, EV, EV_DISCHG_CTRL);
                    if (isDischarging(s.chargingStatus)) s.dischargeMinutes = Rpc.callInt(e, EV, EV_DISCHG_TIME);
                    if (distUnit < 0) distUnit = Rpc.callInt(e, EV, EV_UNITS);
                    s.distUnit = distUnit;
                    Diag.put("vehicle.range", s.rangeKm + " (unit " + distUnit + ")");
                    Diag.put("vehicle.chargeState", s.chargingStatus);
                    Diag.put("vehicle.chargeMinutes", s.predictMinutes);
                    Diag.put("vehicle.chargeCommand(ev)", Rpc.callInt(e, EV, EV_CHG_CTRL));
                    Diag.put("vehicle.dischargeState(V2L)", s.dischargeStatus);
                }
            } catch (Exception ex) {
                Diag.error("charge", ex.toString());
                forget(BIND_EV);
            }
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null) {
                    sendPending(a);
                    boolean power = Rpc.callBool(a, AC, AC_GET_POWER);
                    boolean auto = Rpc.callBool(a, AC, AC_GET_AUTO);
                    float drv = Rpc.callFloat(a, AC, AC_GET_DRV_TEMP);
                    int readTemp = tempFromCar(drv);
                    s.acTemp = (now < tempHoldUntil && heldTemp > 0) ? heldTemp : readTemp;
                    int speed = Rpc.callInt(a, AC, AC_GET_FAN);
                    int readFan = !power ? FAN_OFF : auto ? FAN_AUTO : speed;
                    s.fanLevel = (now < fanHoldUntil && heldFan >= 0) ? heldFan : readFan;
                    s.hvacPower = power ? 1 : 0;
                    s.outsideTemp = Rpc.callFloat(a, AC, AC_GET_OUT_TEMP);
                    // 0.9.3: invalid value (firmware flag, like the original SystemUI) or -40 for "no data": not shown
                    float rawOut = s.outsideTemp;
                    int outV = outValid;
                    // 0.3.8/0.2.5: here "no data" is 0, same as a real 0 °C: 0 is shown only if the car marks it valid
                    boolean outBad = outV == 1 || rawOut <= -39.5f || (rawOut == 0f && outV != 0);
                    if (outBad) s.outsideTemp = -10000f;
                    else lastOutside = rawOut;
                    Diag.put("climate.tempOutside", rawOut + (outV == Integer.MIN_VALUE ? "" : " · validity " + (outV == 0 ? "ok" : outV == 1 ? "NOT valid" : String.valueOf(outV)))
                            + (outBad ? " → hidden" : ""));
                    Diag.put("climate.tempDriver", drv + (s.acTemp != readTemp ? " (requested " + s.acTemp + ")" : ""));
                    Diag.put("climate.fan", speed + (auto ? " auto" : "") + (s.fanLevel != readFan ? " (requested " + s.fanLevel + ")" : ""));
                    Diag.put("climate.power", power);
                    s.acOn = held(AC_GET_AC, Rpc.callBool(a, AC, AC_GET_AC) ? 1 : 0, now);
                    s.autoOn = held(AC_GET_AUTO, auto ? 1 : 0, now);
                    s.defrostOn = held(AC_GET_DEFROST, Rpc.callBool(a, AC, AC_GET_DEFROST) ? 1 : 0, now);
                    Diag.put("climate.ac/auto/defrost", s.acOn + " / " + s.autoOn + " / " + s.defrostOn);
                    if (loopSupported) {
                        try {
                            int loop = Rpc.callInt(a, AC, AC_GET_LOOP);
                            s.loopMode = held(AC_GET_LOOP, loop >= 0 && loop <= 2 ? loop : -1, now);
                            Diag.put("climate.recirc", loop + " (0 inside, 1 outside, 2 auto)");
                        } catch (Exception ex) {
                            loopSupported = false;   // no longer requested: the button stays hidden
                            Diag.put("climate.recirc", "not supported: " + ex.getClass().getSimpleName());
                        }
                    }
                }
            } catch (Exception ex) {
                Diag.error("climate", ex.toString());
                forget(BIND_HVAC);
            }
            readHeat(s, now);
            readCluster(s);
        }
        readLamps(s);
        readBms(s, now);
        readCarProps(s);
        readInside(s);
        readChargeSignals(s);
        last = s;
        lastShared = s;
        main.post(() -> {
            if (listener != null) listener.onVehicleState(s);
        });
    }

    private boolean loopSupported = true;
    private boolean limitSupported;

    /** Pressure unit chosen on the cluster (for "Automatic"). */
    private void readCluster(State s) {
        if (clusterUnit != Integer.MIN_VALUE && android.os.SystemClock.uptimeMillis() - clusterAt < 60_000) {
            s.tyreUnitCluster = clusterUnit;
            return;
        }
        try {
            IBinder ipk = sub(BIND_IPK);
            if (ipk != null) {
                clusterUnit = Rpc.callInt(ipk, IPK, IPK_TYRE_UNIT);
                clusterAt = android.os.SystemClock.uptimeMillis();
                try {
                    clusterDayNight = Rpc.callInt(ipk, IPK, IPK_DAY_NIGHT);
                } catch (Exception ignored) {
                }
                Diag.put("tpms.clusterUnit", clusterUnit + " (0 bar, 1 kPa, 2 psi)");
            }
        } catch (Exception e) {
            forget(BIND_IPK);
        }
        s.tyreUnitCluster = clusterUnit == Integer.MIN_VALUE ? -1 : clusterUnit;
    }

    private int clusterUnit = Integer.MIN_VALUE;
    private volatile int clusterDayNight = Integer.MIN_VALUE;
    private long clusterAt;

    /** Battery manager: percentage with decimals, power, limit and charging commands (like the Energy app). */
    private void readBms(State s, long now) {
        if (!bms.isReady()) {
            Diag.put("bms.readings", "unavailable");
            return;
        }
        s.bmsAvailable = true;
        float socDsp = bms.getFloat(CarBms.PACK_SOC_DSP);
        if (ok(socDsp) && socDsp >= 0 && socDsp <= 100) s.soc = socDsp;
        int code = bms.getInt(CarBms.CHRG_TRGT_SOC);
        if (code >= 1 && code <= 7) {
            s.chargeLimitCode = code;
            s.chargeLimit = limitPercent(code);
        }
        // 0.4.2 (point 7d): limit shown only if the car supports the command (valid reading), or by user choice
        if (code >= 1 && code <= 7) limitSupported = true;
        String lm = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getString("limiteRicaricaModo", "auto");
        s.chargeLimitShown = "show".equals(lm) || (!"hide".equals(lm) && limitSupported);
        Diag.putQuiet("charge.limit", "supported " + (limitSupported ? "yes" : "no") + " · value read " + code
                + (code >= 1 && code <= 7 ? " (" + limitPercent(code) + "%)" : "") + " · choice " + lm + " · shown " + (s.chargeLimitShown ? "yes" : "no"));
        readSchedule(s, now);
        int lim = held(CarBms.CHRG_TRGT_SOC, s.chargeLimitCode, now);
        if (lim >= 1 && lim != s.chargeLimitCode) {
            s.chargeLimitCode = lim;
            s.chargeLimit = limitPercent(lim);
        }
        int cur = bms.getInt(CarBms.ALTNG_CHRG_CRNT);
        s.currentLimit = held(CarBms.ALTNG_CHRG_CRNT, cur >= 0 && cur <= 4 ? cur : -1, now);
        int ctl = bms.getInt(CarBms.CHRG_CTRL);
        s.chargeControl = held(CarBms.CHRG_CTRL, ctl == Integer.MIN_VALUE ? -1 : ctl, now);
        int v2x = bms.getInt(CarBms.V2X);
        s.dischargeControl = held(CarBms.V2X, v2x == Integer.MIN_VALUE ? -1 : v2x, now);
        int lk = bms.getInt(CarBms.CHRG_LOCK);
        s.chargeLock = held(CarBms.CHRG_LOCK, lk == 1 || lk == 2 ? lk : -1, now);
        Diag.putQuiet("charge.portLock", s.chargeLock > 0 ? (s.chargeLock == 1 ? "locked" : "unlocked") : "not read");
        int dl = bms.getInt(CarBms.V2X_SP_SOC);
        s.dischargeLimit = dl >= 0 && dl <= 7 ? (dl + 1) * 10 : -1;
        float amps = bms.getFloat(CarBms.PACK_CRNT);
        float volts = bms.getFloat(CarBms.PACK_VOL);
        if (ok(amps) && ok(volts)) s.chargeKw = Math.abs(amps * volts) / 1000f;
        if (s.rangeKm <= 0) {
            int r = bms.getInt(CarBms.ESTD_ELEC_RNG);
            if (r > 0 && r < 2000) s.rangeKm = r;
        }
        Diag.put("bms.soc", socDsp);
        Diag.put("vehicle.chargeLimit", "code " + code + " = " + s.chargeLimit + "%");
        Diag.put("charge.commands", "current " + cur + " · start/stop " + ctl + " · V2L " + v2x + " · V2L limit " + dl
                + " · unit " + distUnit);
        Diag.putQuiet("vehicle.currentA/voltageV", amps + " / " + volts);
    }

    // ---- TPMS and outside temperature validity from the CarService (0.9.5: the inside temperature is not there) ----------------------------------------------

    private CarProps props;
    private static volatile CarProps shared;

    /** Shared CarService reader (null until the vehicle service has created it). */
    public static CarProps carProps() {
        return shared;
    }

    private long propsAt;
    private final float[] tireCache = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
    private volatile int outValid = Integer.MIN_VALUE;
    private volatile float lastOutside = Float.NaN;

    private void readCarProps(State s) {
        if (props == null) {
            props = new CarProps(ctx);
            props.connect();
            shared = props;
        }
        long now = android.os.SystemClock.uptimeMillis();
        readBatteryHeat(s, now);
        if (props.ready() && now - propsAt > 5000) {
            propsAt = now;
            int[] ids = {CarProps.TIRE_FL, CarProps.TIRE_FR, CarProps.TIRE_RL, CarProps.TIRE_RR};
            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int v = props.getInt(ids[i]);
                tireCache[i] = CarProps.toBar(v);
                raw.append(i == 0 ? "" : " / ").append(v == Integer.MIN_VALUE ? "?" : String.valueOf(v));
            }
            Diag.put("tyres (raw FL/FR/RL/RR)", raw);
            // 0.9.3: outside temperature, validity (0 valid, 1 invalid)
            outValid = props.getIntQuiet(CarProps.HVAC_TEMPERATURE_OUTCARV);
        }
        System.arraycopy(tireCache, 0, s.tireBar, 0, 4);
    }

    // ---- 0.4.2 (point 3): inside temperature, only with the option on; never the outside one in its place -----------

    private long insideAt;
    private float insideCache = Float.NaN;
    private String insideSrc = "";

    private void readInside(State s) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        long now = android.os.SystemClock.uptimeMillis();
        if (props == null || !props.ready()) {
            Diag.putQuiet("climate.tempInside", "CarService not ready → \"--\"");
            return;
        }
        // 0.4.3 (point 3): property missing on this car (list already read) → the "Automatic" option hides itself
        boolean missing = !props.has(CarProps.HVAC_TEMPERATURE_CURRENT);
        if (sp.getBoolean("tempInternaAssente", false) != missing) sp.edit().putBoolean("tempInternaAssente", missing).apply();
        String mode = sp.getString("tempInternaModo", null);
        if (mode == null) mode = sp.getBoolean("showInsideTemp", false) ? "auto" : "hide";
        if ("hide".equals(mode)) {
            Diag.putQuiet("climate.tempInside", "not read (Hide option)" + (missing ? " · property absent on this car" : ""));
            return;
        }
        if (now - insideAt > 5000) {
            insideAt = now;
            // only cabin source: HVAC_TEMPERATURE_CURRENT (all areas). HVAC_AMBIENT_TEMPERATURE is the OUTSIDE one: not used.
            float best = Float.NaN;
            String src = "none";
            StringBuilder all = new StringBuilder();
            for (int a : props.areasOf(CarProps.HVAC_TEMPERATURE_CURRENT)) {
                float v = props.getFloatQuiet(CarProps.HVAC_TEMPERATURE_CURRENT, a);
                all.append(all.length() == 0 ? "" : ", ").append(a).append('=').append(v);
                if (Float.isNaN(best) && valid(v)) {
                    best = v;
                    src = "HVAC_TEMPERATURE_CURRENT area " + a;
                }
            }
            float out = lastOutside;
            boolean same = !Float.isNaN(best) && !Float.isNaN(out) && Math.abs(best - out) < 0.05f;
            insideCache = best;
            insideSrc = Float.isNaN(best) ? "" : src;
            Diag.putQuiet("climate.tempInside", (Float.isNaN(best) ? "--" : String.valueOf(best)) + " · source " + src
                    + " · valid " + (Float.isNaN(best) ? "no" : "yes") + " · outside " + (Float.isNaN(out) ? "?" : String.valueOf(out))
                    + (same ? " (same as outside)" : "") + " · areas [" + all + "]");
        }
        s.insideTemp = insideCache;
        s.insideSource = insideSrc;
    }

    /**
     * Signals for the charging animation (SWI69 R45, EH32 family: also valid for SWI131/SWI132):
     * <ul>
     *   <li>socket: SWI68 BMS property (YFVehicleProperty BMS_CHRG_PLUG_CNCTNIO 557904904: 0 plugged in, 1 no);</li>
     *   <li>0.4.5: true READY from SENSOR_EPTRDY. SAIC car state service: CarAdapterService, code 11
     *       (BIND_CODE_CAR_STATE), interface com.saicmotor.carapi.carstate.ICarStateService, 8 = getSensorEptrdyState
     *       (CarState: 1 READY, 0 not READY), 3 = isServiceReady, 5 = getGearState. With the service not ready or an
     *       error, READY stays unknown. Ignition (2 = cluster on) is no longer used as READY;</li>
     *   <li>speed PERF_VEHICLE_SPEED.</li>
     * </ul>
     */
    private static final int BIND_STATE = 11, ST_SERVICE_READY = 3, ST_GEAR = 5, ST_EPTRDY = 8;
    private static final String STATE_DESC = "com.saicmotor.carapi.carstate.ICarStateService";
    private volatile int plugCache = -1;

    private void readChargeSignals(State s) {
        if (props != null && props.ready()) {
            String[] why = new String[1];
            Integer plug = props.readLight(CarProps.BMS_CHRG_PLUG_CNCTNIO, false, why);
            plugCache = plug == null ? -1 : plug;
            Diag.putQuiet("charge.port.signal", plug == null ? "not read (" + why[0] + ")" : (plug == 0 ? "cable plugged in" : "cable not plugged in") + " (" + plug + ")");
        }
        s.plugProp = plugCache;
        s.speedKmh = speedKmh();
        try {
            IBinder st = sub(BIND_STATE);
            if (st == null) {
                s.readySource = "car state service unavailable";
            } else {
                Boolean ok = Rpc.call(st, STATE_DESC, ST_SERVICE_READY, Rpc.NONE, p -> p.readInt() != 0);
                int r = Rpc.callInt(st, STATE_DESC, ST_EPTRDY);
                int g = Rpc.callInt(st, STATE_DESC, ST_GEAR);
                if (ok == null || !ok) s.readySource = "SENSOR_EPTRDY, service not ready";
                else if (r == 0 || r == 1) {
                    s.ready = r;
                    s.readySource = "SENSOR_EPTRDY";
                } else s.readySource = "SENSOR_EPTRDY value " + (r == Integer.MIN_VALUE ? "not read" : String.valueOf(r));
                if (ok != null && ok && g != Integer.MIN_VALUE && g >= 0 && g <= 11) s.gear = g;
            }
        } catch (Exception e) {
            s.readySource = "SENSOR_EPTRDY error " + e.getClass().getSimpleName();
        }
        Diag.putQuiet("car.ready", "ready " + s.ready + " (" + s.readySource + ") · gear " + gearName(s.gear));
    }

    /** Gear name from the car state service, for the logs. */
    public static String gearName(int g) {
        switch (g) {
            case -1: return "not read";
            case 0: return "between gears";
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            default: return "D (" + g + ")";
        }
    }

    /** Inside temperature readable? true / false, null until known (CarService not ready yet). */
    public static Boolean insideAvailable() {
        CarProps p = shared;
        if (p == null) return null;
        Boolean a = p.available(CarProps.HVAC_TEMPERATURE_CURRENT), b = p.available(CarProps.HVAC_AMBIENT_TEMPERATURE);
        if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) return true;
        if (Boolean.FALSE.equals(a) && Boolean.FALSE.equals(b)) return false;
        return null;
    }

    private static boolean valid(float t) {
        return !Float.isNaN(t) && t > -40 && t < 80 && t != 0f;
    }

    private int rvcAvmConfig = Integer.MIN_VALUE;

    /**
     * Cameras present according to the car configuration: true / false, null if not known yet
     * (in that case the 360° button stays as before). Same values as the original SWI69 home.
     */
    public static Boolean cameraPresent(int cfg) {
        if (cfg < 0) return null;
        return cfg >= 1 && cfg <= 5;
    }

    /** 0.4.4: immediate re-read of the lights (Feature check test, off the main thread). */
    public void refreshLightsNow() {
        Handler h = bg;
        if (h == null) return;
        final Object done = new Object();
        final boolean[] ok = {false};
        h.post(() -> {
            try {
                State s = new State();
                s.connected = last.connected;
                readLamps(s);
            } catch (Throwable ignored) {
            }
            synchronized (done) {
                ok[0] = true;
                done.notifyAll();
            }
        });
        synchronized (done) {   // the Feature check test waits for the reading (at most 1.5 s)
            long until = android.os.SystemClock.uptimeMillis() + 1500;
            while (!ok[0] && android.os.SystemClock.uptimeMillis() < until) {
                try {
                    done.wait(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void readLamps(State s) {
        if (!s.connected) return;
        try {
            if (rvcAvmConfig < 0) {
                IBinder cfg = sub(BIND_CONFIG);
                if (cfg != null) {
                    rvcAvmConfig = Rpc.callInt(cfg, CONFIG, CONFIG_RVC_AVM);
                    Diag.put("cameras.config", rvcAvmConfig + " (" + (rvcAvmConfig == 0 ? "no camera"
                            : rvcAvmConfig == 3 ? "360° AVM" : rvcAvmConfig == 5 ? "360° AVM quad"
                            : rvcAvmConfig >= 1 && rvcAvmConfig <= 5 ? "rear camera / 360° EVS" : rvcAvmConfig < 0 ? "not yet known" : "unknown: no camera") + ")");
                }
            }
        } catch (Exception e) {
            Diag.error("cameras.config", e.toString());
        }
        s.cameraConfig = rvcAvmConfig;
        try {
            if (frontFogConfig == Integer.MIN_VALUE) {
                IBinder cfg = sub(BIND_CONFIG);
                if (cfg != null) {
                    frontFogConfig = Rpc.callInt(cfg, CONFIG, CONFIG_FRONT_FOG);
                    Diag.put("lights.frontFogConfig", frontFogConfig);
                }
            }
        } catch (Exception e) {
            Diag.error("lights.config", e.toString());
            forget(BIND_CONFIG);
        }
        try {
            IBinder l = sub(BIND_LAMP);
            if (l != null) {
                long now = android.os.SystemClock.uptimeMillis();
                s.rearFog = held(LAMP_GET_REAR_FOG, Rpc.callInt(l, LAMP, LAMP_GET_REAR_FOG) == 1 ? 1 : 0, now);
                s.hasFrontFog = frontFogConfig == 0;
                if (s.hasFrontFog) s.frontFog = held(LAMP_GET_FRONT_FOG, Rpc.callInt(l, LAMP, LAMP_GET_FRONT_FOG) == 1 ? 1 : 0, now);
                Diag.put("lights.fog front/rear", s.frontFog + " / " + s.rearFog);
                // 0.4.2 (points 1 and 7b): lights, as the original home reads them (ModeManager.initCarLampData)
                if (lampStateOk) {
                    try {
                        s.lowBeam = Rpc.callInt(l, LAMP, LAMP_GET_DIPD_BEAM);
                        s.sideLight = Rpc.callInt(l, LAMP, LAMP_GET_SIDE_LIGHT);
                        s.lightSwitch = Rpc.callInt(l, LAMP, LAMP_GET_SWITCH_POS);
                    } catch (Exception ex) {
                        lampStateOk = false;   // service without these commands: switch to the vehicle properties
                        Diag.put("lights.headlights", "lights service without headlight state (" + ex.getClass().getSimpleName() + "): using CarService");
                    }
                }
            }
        } catch (Exception e) {
            Diag.error("lights", e.toString());
            forget(BIND_LAMP);
        }
        if (s.lowBeam < 0 && props != null && props.ready()) {
            // fallback: vehicle properties used by the original SystemUI (CarLampController)
            s.lowBeam = props.getBoolQuiet(DIPD_BEAM_LGHT);
            int side = props.getIntQuiet(VEH_SIDE_LGHT), sw = props.getIntQuiet(LIGHT_SWITCH_POS_STS);
            s.sideLight = side == Integer.MIN_VALUE ? -1 : side;
            s.lightSwitch = sw == Integer.MIN_VALUE ? -1 : sw;
        }
        // 0.4.3: light state for the automatic theme (DayNight, same as SWI68): 1 on, 0 off, -1 not read
        lightsState = s.lowBeam == 1 || s.sideLight == 3 || s.lightSwitch == 1 ? 1 : s.lowBeam == 0 || s.sideLight >= 0 ? 0 : -1;
        lightsDesc = "low beam " + s.lowBeam + " · side lights " + s.sideLight + " · switch " + s.lightSwitch
                + (lampStateOk ? " (lights service)" : " (CarService)");
        Diag.putQuiet("lights.headlights", "low beam " + s.lowBeam + " · side lights " + s.sideLight + " · switch " + s.lightSwitch
                + (lampStateOk ? " (lights service)" : " (CarService)"));
        try {
            readAssist(s, android.os.SystemClock.uptimeMillis());
        } catch (Exception e) {
            Diag.error("adas", e.toString());
            forget(BIND_VS);
        }
    }

    private int lkaConfig = Integer.MIN_VALUE, onePedalConfig = Integer.MIN_VALUE;
    private Boolean vsValid;

    /** Vehicle settings service, only if binding 8 answers with the right interface. */
    private IBinder vs() throws RemoteException {
        IBinder b = sub(BIND_VS);
        if (b == null) return null;
        if (vsValid == null) {
            String d = b.getInterfaceDescriptor();
            vsValid = VS.equals(d);
            Diag.put("adas.service", String.valueOf(d));
        }
        return vsValid ? b : null;
    }

    /** LKA and OPD: configuration read once, state on every loop. Without a valid configuration there is no button. */
    private void readAssist(State s, long now) throws Exception {
        if (lkaConfig == Integer.MIN_VALUE || onePedalConfig == Integer.MIN_VALUE) {
            IBinder cfg = sub(BIND_CONFIG);
            if (cfg == null) return;
            lkaConfig = Rpc.callInt(cfg, CONFIG, CONFIG_LKA);
            onePedalConfig = Rpc.callInt(cfg, CONFIG, CONFIG_ONE_PEDAL);
            Diag.put("adas.config", "LKA " + lkaConfig + ", One Pedal " + onePedalConfig);
        }
        IBinder v = vs();
        if (v == null) return;
        try {
            int at = Rpc.callInt(v, VS, VS_GET_ACC_TJA);
            s.accTja = held(K_ACC_TJA, at == 1 || at == 2 || at == 4 ? at : -1, now);
            int bp = Rpc.callInt(v, VS, VS_GET_PEDAL);
            s.pedal = held(K_PEDAL, bp >= 0 && bp <= 2 ? bp : -1, now);
            Diag.put("adas.driving", "ACC/TJA " + at + ", pedal " + bp);
        } catch (Exception e) {
            Diag.put("adas.driving", "unreadable: " + e);
        }
        if (lkaConfig == 0) {
            int mode = Rpc.callInt(v, VS, VS_GET_LAS);
            s.lkaMode = mode;
            int on = mode == 1 ? 0 : mode >= 2 && mode <= 5 ? 1 : -1;
            s.lka = held(K_LKA, on, now);
        }
        if (onePedalConfig == 0) {
            int o = Rpc.callInt(v, VS, VS_GET_OPD);
            s.opd = held(K_OPD, o == 1 ? 1 : o == 0 ? 0 : -1, now);
            s.opdInhibit = Rpc.callInt(v, VS, VS_GET_OPD_INHIBIT);
        }
        Diag.put("adas.state", "LKA " + s.lka + " (mode " + s.lkaMode + "), OPD " + s.opd + " (inhibit " + s.opdInhibit + ")");
    }

    /** LKA on/off: like the switch of the original SWI69 app, turns on with 2 (LDW) and off with 1. */
    public void toggleLka() {
        if (bg == null) return;
        int cur = predicted(K_LKA, last.lka);
        if (cur < 0) return;
        boolean turnOn = cur == 0;
        hold(K_LKA, turnOn ? 1 : 0);
        bg.post(() -> {
            try {
                IBinder v = vs();
                if (v == null) return;
                int value = turnOn ? 2 : 1;
                Rpc.call(v, VS, VS_SET_LAS, p -> p.writeInt(value));
                Diag.put("command.last", "assist.lka · " + (turnOn ? "on (mode " + value + ")" : "off"));
            } catch (Exception e) {
                Diag.error("adas.lka", e.toString());
                forget(BIND_VS);
            }
            poll();
        });
    }

    // ---- driving assistant and pedal (0.9.5 / 0.3.8 / 0.2.5) -------------------------------------

    /** Speed in km/h from the CarService (NaN if not readable). */
    public static float speedKmh() {
        CarProps p = shared;
        if (p == null) return Float.NaN;
        float v = p.getFloat(CarProps.PERF_VEHICLE_SPEED);   // m/s; NaN if the car does not grant CAR_SPEED (read only once)
        return Float.isNaN(v) ? Float.NaN : Math.abs(v) * 3.6f;
    }

    /** Changed only with the car stopped (not above 3 km/h): they are driving settings. */
    public static boolean stopped() {
        float v = speedKmh();
        return Float.isNaN(v) || v < 3f;
    }

    /** VehicleProperty GEAR_SELECTION / CURRENT_GEAR (VehicleGear: 2 reverse, 4 park). */
    public static final int GEAR_SELECTION = 289408000, CURRENT_GEAR = 289408001, GEAR_REVERSE = 2, GEAR_PARK = 4;

    /** Current gear, Integer.MIN_VALUE if not readable. */
    public static int gear() {
        CarProps p = shared;
        if (p == null || !p.ready()) return Integer.MIN_VALUE;
        int g = p.getIntQuiet(CURRENT_GEAR);
        if (g == Integer.MIN_VALUE) g = p.getIntQuiet(GEAR_SELECTION);
        return g;
    }

    /**
     * 0.4.2 (points 5 and 12): car surely stopped: speed read below 1 km/h or gear P. False if neither speed nor
     * gear can be read (in that case an update is never forced).
     */
    public static boolean surelyStopped() {
        float v = speedKmh();
        int g = gear();
        boolean ok = (!Float.isNaN(v) && v < 1f) || g == GEAR_PARK;
        Diag.putQuiet("car.stopForUpdate", "speed " + (Float.isNaN(v) ? "not read" : String.format(java.util.Locale.ROOT, "%.1f km/h", v))
                + " · gear " + (g == Integer.MIN_VALUE ? "not read" : String.valueOf(g)) + " → " + (ok ? "stopped" : "not safe"));
        return ok;
    }

    /** Driving assistant: Off → ACC → TJA → Off, like the car settings. */
    public boolean cycleAccTja() {
        if (bg == null || !stopped()) return false;
        int cur = predicted(K_ACC_TJA, last.accTja);
        if (cur < 0) return false;
        int next = cur == 4 ? 1 : cur == 1 ? 2 : 4;
        hold(K_ACC_TJA, next);
        bg.post(() -> {
            try {
                IBinder b = vs();
                if (b == null) return;
                Rpc.call(b, VS, VS_SET_ACC_TJA, p -> p.writeInt(next));
                Diag.put("command.last", "assist.accTja · " + cur + " → " + next);
            } catch (Exception e) {
                Diag.error("adas.accTja", e.toString());
                forget(BIND_VS);
            }
            poll();
        });
        return true;
    }

    /** Brake pedal: Comfort → Medium → Sport → Comfort. */
    public boolean cyclePedal() {
        if (bg == null || !stopped()) return false;
        int cur = predicted(K_PEDAL, last.pedal);
        if (cur < 0) return false;
        int next = cur == 1 ? 0 : cur == 0 ? 2 : 1;
        hold(K_PEDAL, next);
        bg.post(() -> {
            try {
                IBinder b = vs();
                if (b == null) return;
                Rpc.call(b, VS, VS_SET_PEDAL, p -> p.writeInt(next));
                Diag.put("command.last", "assist.pedal · " + cur + " → " + next);
            } catch (Exception e) {
                Diag.error("adas.pedal", e.toString());
                forget(BIND_VS);
            }
            poll();
        });
        return true;
    }

    /** One Pedal Drive on/off. If the car inhibits it (charging, conditions…) the read state stays as it was. */
    public void toggleOpd() {
        if (bg == null) return;
        int cur = predicted(K_OPD, last.opd);
        if (cur < 0) return;
        int next = cur == 1 ? 0 : 1;
        hold(K_OPD, next);
        bg.post(() -> {
            try {
                IBinder v = vs();
                if (v == null) return;
                Rpc.call(v, VS, VS_SET_OPD, p -> p.writeInt(next));
                Diag.put("command.last", "assist.opd · " + (next == 1 ? "on" : "off") + " · inhibit " + Rpc.callInt(v, VS, VS_GET_OPD_INHIBIT));
            } catch (Exception e) {
                Diag.error("adas.opd", e.toString());
                forget(BIND_VS);
            }
            poll();
        });
    }

    // ---- smart battery heating (fork) -----------------------------------------------

    /**
     * Smart battery heating (DRVNG_PTC_HEAT), the option hidden in the car's battery widget: heats the
     * battery while driving (better fast charging, less range) and does not turn itself off.
     * Global CarService property (area 0), like the original A9 app / MG4Control: read 0 = on, 1 = off;
     * write 1 = turn on, 2 = turn off. The car takes a few seconds to reflect the change.
     */
    private static final int BAT_HEAT = 0x2140f429, K_BAT_HEAT = 90429;
    private static final long BAT_HEAT_HOLD_MS = 15_000;

    private void readBatteryHeat(State s, long now) {
        if (props == null || !props.ready()) return;
        int raw = props.getIntAt(BAT_HEAT, 0);
        int on = raw == 0 ? 1 : raw == 1 ? 0 : -1;
        s.batHeat = held(K_BAT_HEAT, on, now);
        Diag.putQuiet("battery.heating", raw == Integer.MIN_VALUE ? "unreadable" : "value " + raw + " → " + (on == 1 ? "on" : on == 0 ? "off" : "?"));
    }

    /** Turns battery heating on / off. Returns the new requested state (1 / 0) or -1 if unavailable. */
    public int toggleBatteryHeat() {
        if (bg == null || props == null) return -1;
        int cur = predicted(K_BAT_HEAT, last.batHeat);
        if (cur < 0) return -1;
        int next = cur == 1 ? 0 : 1;
        synchronized (holds) {
            holds.put(K_BAT_HEAT, new long[]{next, android.os.SystemClock.uptimeMillis() + BAT_HEAT_HOLD_MS});
        }
        bg.post(() -> {
            boolean ok = props.setIntAt(BAT_HEAT, 0, next == 1 ? 1 : 2);
            Diag.put("command.last", "battery.heating · " + (next == 1 ? "turn on" : "turn off") + (ok ? "" : " · failed"));
            poll();
        });
        return next;
    }

    // ---- charging states ----------------------------------------------------------------------

    private int frontFogConfig = Integer.MIN_VALUE;
    private boolean lampStateOk = true;

    /** Light properties used by the original R45 SystemUI (CarLampController). */
    static final int DIPD_BEAM_LGHT = 555782944, VEH_SIDE_LGHT = 557880099, LIGHT_SWITCH_POS_STS = 557880119;
    private static volatile int lightsState = -1;
    private static volatile String lightsDesc = "not read";

    /** 0.4.3: lights for the automatic theme: 1 on, 0 off, -1 not readable. */
    public static int lights() {
        return lightsState;
    }

    public static String lightsDescription() {
        return lightsDesc;
    }

    /** Charging states (same codes as SWI68): charging / balancing / fast / wireless. */
    public static boolean isCharging(int st) {
        return st == 1 || st == 3 || st == 10 || st == 16 || st == 11;
    }

    /** V2L: discharging (13). */
    public static boolean isDischarging(int st) {
        return st == 13;
    }

    /**
     * Mode of the start/stop button, like the two tabs of the original Charging management:
     * 2 = V2L socket connected (11 connected, 13 discharging, 14 suspended, 15 finished), 1 = charging cable connected,
     * 0 = nothing connected.
     */
    /**
     * 1.0.4 (point F): charging cable plugged in, even before the charger starts. Socket signal
     * (BMS_CHRG_PLUG_CNCTNIO = 0) or a charging state with the cable attached (1-5, 7-10, 12; 11 only with power).
     * V2L excluded (13, 14, 15).
     */
    private static volatile State lastShared;

    /** 0.4.4: cable plugged in now (latest reading); false if unknown. */
    public static boolean cablePluggedNow() {
        State s = lastShared;
        return s != null && s.connected && cablePlugged(s);
    }

    /**
     * 0.4.5: cable plugged in. The socket saying "attached" is enough on its own; the socket saying "detached" does NOT win over a
     * charging state saying cable attached (1-5, 7-10, 12; 11 only with power). If they disagree it writes
     * "ricarica.presa = contrasto: presa <p> · stato <s>".
     */
    public static boolean cablePlugged(State s) {
        int st = s.chargingStatus;
        if (st == 13 || st == 14 || st == 15) return false;   // V2L: no charging cable
        boolean byState = st == 11 ? s.chargeKw > 0.3f : st >= 1 && st <= 5 || st >= 7 && st <= 10 || st == 12;
        boolean plugged = s.plugProp == 0 || byState;
        String txt;
        if (s.plugProp == 1 && byState || s.plugProp == 0 && !byState && st >= 0) {
            txt = "conflict: socket " + s.plugProp + " · state " + st + " → " + (plugged ? "cable plugged in" : "cable not plugged in");
        } else {
            txt = (plugged ? "cable plugged in" : "cable not plugged in") + " · socket " + s.plugProp + " · state " + st;
        }
        Diag.putQuiet("charge.port", txt);
        return plugged;
    }

    /** 1.0.4: charging finished (2) or stopped at the limit (7/8 with the battery at the chosen limit). */
    public static boolean chargeDone(State s) {
        int st = s.chargingStatus;
        if (st == 2) return true;
        return (st == 7 || st == 8) && s.chargeLimit > 0 && s.soc >= s.chargeLimit - 1;
    }

    public static int plugMode(State s) {
        int st = s.chargingStatus;
        if (st == 13 || st == 14 || st == 15) return 2;
        if (st == 11) return s.chargeKw > 0.3f ? 1 : 2;
        if (st == 1 || st == 2 || st == 3 || st == 4 || (st >= 6 && st <= 10)) return 1;
        return 0;
    }

    // ---- toggle buttons ----------------------------------------------------------------

    private final Map<Integer, long[]> holds = new HashMap<>();   // read code -> {value, expiry}

    /** After a tap, shows the new state right away for a few seconds, until the car confirms it. */
    private int held(int getCode, int read, long now) {
        synchronized (holds) {
            long[] h = holds.get(getCode);
            if (h == null) return read;
            if (now < h[1] && read != h[0]) return (int) h[0];
            holds.remove(getCode);
            return read;
        }
    }

    private int predicted(int getCode, int fallback) {
        synchronized (holds) {
            long[] h = holds.get(getCode);
            return h != null && android.os.SystemClock.uptimeMillis() < h[1] ? (int) h[0] : fallback;
        }
    }

    private void hold(int getCode, int value) {
        synchronized (holds) {
            holds.put(getCode, new long[]{value, android.os.SystemClock.uptimeMillis() + HOLD_MS});
        }
    }

    /** Toggles: the SWI69 service only has "toggle state" (no arguments) for the climate, set(1) for the lights. */
    private void press(int bind, String desc, int setCode, boolean withOne, int getCode, int current, String what) {
        if (bg == null) return;
        if (current == 0 || current == 1) hold(getCode, 1 - current);
        bg.post(() -> {
            try {
                IBinder b = sub(bind);
                if (b != null) {
                    if (withOne) Rpc.call(b, desc, setCode, p -> p.writeInt(1));
                    else Rpc.call(b, desc, setCode, Rpc.NONE);
                }
            } catch (Exception e) {
                Diag.error(what, e.toString());
                forget(bind);
            }
            poll();
        });
    }

    public void toggleAc() {
        press(BIND_HVAC, AC, AC_SWITCH_AC, false, AC_GET_AC, last.acOn, "climate.ac");
    }

    public void toggleAuto() {
        press(BIND_HVAC, AC, AC_SWITCH_AUTO, false, AC_GET_AUTO, last.autoOn, "climate.auto");
    }

    public void toggleFrontDefrost() {
        press(BIND_HVAC, AC, AC_SWITCH_DEFROST, false, AC_GET_DEFROST, last.defrostOn, "climate.defrost");
    }

    public void toggleRearFog() {
        press(BIND_LAMP, LAMP, LAMP_SET_REAR_FOG, true, LAMP_GET_REAR_FOG, last.rearFog, "lights.rearFog");
    }

    public void toggleFrontFog() {
        press(BIND_LAMP, LAMP, LAMP_SET_FRONT_FOG, true, LAMP_GET_FRONT_FOG, last.frontFog, "lights.frontFog");
    }

    // ---- 0.4.2 (point 9): heated seats and steering wheel, like the original EH32 climate app ------------------------

    private int seatCfg = Integer.MIN_VALUE, wheelCfg = Integer.MIN_VALUE, carModel = Integer.MIN_VALUE;
    private String heatCmd = "none";

    private String heatMode() {
        String v = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getString("riscaldamentoModo", "auto");
        return "show".equals(v) || "hide".equals(v) ? v : "auto";
    }

    private static boolean wheelModel(int m) {
        for (int x : WHEEL_HEAT_MODELS) if (x == m) return true;
        return false;
    }

    /**
     * Presence like the original climate app (car configuration, NOT whether the reads answer: the SWI69 answers
     * even on cars that do not have them). Seats: configuration 4 = 3-level seats (2 = absent; other values
     * are not certain and stay hidden in Automatic). Steering wheel: configuration 0 and model among the original app's ones.
     */
    private void readHeat(VehicleClient.State s, long now) {
        String mode = heatMode();
        try {
            if (seatCfg == Integer.MIN_VALUE || wheelCfg == Integer.MIN_VALUE || carModel == Integer.MIN_VALUE) {
                IBinder cfg = sub(BIND_CONFIG);
                if (cfg != null) {
                    if (seatCfg == Integer.MIN_VALUE) seatCfg = Rpc.callInt(cfg, CONFIG, CONFIG_SEAT_HEAT);
                    if (wheelCfg == Integer.MIN_VALUE) wheelCfg = Rpc.callInt(cfg, CONFIG, CONFIG_WHEEL_HEAT);
                    if (carModel == Integer.MIN_VALUE) carModel = Rpc.callInt(cfg, CONFIG, CONFIG_CAR_MODEL);
                }
            }
        } catch (Exception e) {
            forget(BIND_CONFIG);
        }
        boolean seatAuto = seatCfg == 4;
        boolean wheelAuto = wheelCfg == 0 && wheelModel(carModel);
        boolean seatOn = "show".equals(mode) || ("auto".equals(mode) && seatAuto);
        boolean wheelOn = "show".equals(mode) || ("auto".equals(mode) && wheelAuto);
        int rl = -1, rr = -1, rw = -1;
        if (seatOn || wheelOn || "auto".equals(mode)) {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null) {
                    rl = Rpc.call(a, AC, AC_GET_SEAT_HEAT, p -> p.writeInt(1), android.os.Parcel::readInt);
                    rr = Rpc.call(a, AC, AC_GET_SEAT_HEAT, p -> p.writeInt(2), android.os.Parcel::readInt);
                    rw = Rpc.callInt(a, AC, AC_GET_WHEEL_HEAT);
                }
            } catch (Exception e) {
                Diag.putQuiet("comfort.reading", "failed: " + e.getClass().getSimpleName());
            }
        }
        if (seatOn) {
            s.seatLeft = held(K_SEAT_L, rl >= 0 && rl <= 3 ? rl : 0, now);
            s.seatRight = held(K_SEAT_R, rr >= 0 && rr <= 3 ? rr : 0, now);
        }
        if (wheelOn) s.wheelHeat = held(AC_GET_WHEEL_HEAT, rw == 3 ? 1 : 0, now);
        Diag.putQuiet("comfort.heating", "presence: seats config " + q(seatCfg) + (seatAuto ? " (yes)" : " (no)")
                + ", wheel config " + q(wheelCfg) + " model " + q(carModel) + (wheelAuto ? " (yes)" : " (no)")
                + " · source car configuration (EH32 climate app) · choice " + mode
                + " · read seat L " + rl + " R " + rr + " wheel " + rw + " (3 = on)"
                + " · shown seats " + (seatOn ? "yes" : "no") + " wheel " + (wheelOn ? "yes" : "no")
                + " · last command " + heatCmd);
    }

    private static String q(int v) {
        return v == Integer.MIN_VALUE ? "?" : String.valueOf(v);
    }

    private static final int K_SEAT_L = 90031, K_SEAT_R = 90032;

    /** Left seat (car side 1): one tap = next level (0 → 1 → 2 → 3 → 0), like the original app. */
    public void pressSeatLeft() {
        seatCommand(1);
    }

    public void pressSeatRight() {
        seatCommand(2);
    }

    /** Comfort group: driver's or passenger's seat (with right-hand drive the driver is side 2). */
    public void stepSeat(boolean driver) {
        boolean rhd = CarFeatures.isRightHandDrive(ctx);
        seatCommand(driver == !rhd ? 1 : 2);
    }

    private void seatCommand(int side) {
        if (bg == null) return;
        int key = side == 1 ? K_SEAT_L : K_SEAT_R;
        int cur = predicted(key, side == 1 ? last.seatLeft : last.seatRight);
        if (cur < 0) return;
        int next = (cur + 1) % 4;
        hold(key, next);
        bg.post(() -> {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a == null) throw new RemoteException("climate service not connected");
                Rpc.call(a, AC, AC_SWITCH_SEAT_HEAT, p -> p.writeInt(side));
                heatCmd = "seat " + (side == 1 ? "L" : "R") + " " + cur + " → " + next + " sent";
            } catch (Exception e) {
                heatCmd = "seat " + (side == 1 ? "L" : "R") + " not sent: " + e.getClass().getSimpleName();
                forget(BIND_HVAC);
            }
            // re-read after ~1 s: if the car has not changed, the button returns to the real state
            bg.postDelayed(() -> verifyHeat(key, side, cur), 1100);
        });
    }

    public void toggleWheelHeat() {
        if (bg == null) return;
        int cur = predicted(AC_GET_WHEEL_HEAT, last.wheelHeat);
        if (cur < 0) return;
        hold(AC_GET_WHEEL_HEAT, 1 - cur);
        bg.post(() -> {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a == null) throw new RemoteException("climate service not connected");
                Rpc.call(a, AC, AC_SET_WHEEL_HEAT, p -> p.writeInt(1));
                heatCmd = "wheel " + (cur == 1 ? "turn off" : "turn on") + " sent";
            } catch (Exception e) {
                heatCmd = "wheel not sent: " + e.getClass().getSimpleName();
                forget(BIND_HVAC);
            }
            bg.postDelayed(() -> verifyHeat(AC_GET_WHEEL_HEAT, 0, cur), 1100);
        });
    }

    /** side 1/2 = seat, 0 = steering wheel; before = value before the command. */
    private void verifyHeat(int key, int side, int before) {
        int now = -1;
        try {
            IBinder a = sub(BIND_HVAC);
            if (a != null) {
                if (side == 0) now = Rpc.callInt(a, AC, AC_GET_WHEEL_HEAT) == 3 ? 1 : 0;
                else now = Rpc.call(a, AC, AC_GET_SEAT_HEAT, p -> p.writeInt(side), android.os.Parcel::readInt);
            }
        } catch (Exception ignored) {
        }
        boolean changed = now >= 0 && now != before;
        if (!changed) {
            synchronized (holds) {
                holds.remove(key);   // the button returns to the real state right away
            }
        }
        heatCmd += " · re-read " + now + (changed ? " (confirmed)" : " (NOT changed: back to the real state)");
        Diag.put("comfort.lastCommand", heatCmd);
        poll();
    }

    /**
     * Recirculation: the service only has "toggle" (moves to the next state, like the original home's button);
     * the expected state (inside ↔ outside) is shown right away, then the one read from the car.
     */
    public void toggleRecirc() {
        if (bg == null) return;
        int cur = predicted(AC_GET_LOOP, last.loopMode);
        if (cur < 0) return;
        hold(AC_GET_LOOP, cur == 0 ? 1 : cur == 1 ? 2 : 0);   // cycle Inside → Outside → Auto
        bg.post(() -> {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null) Rpc.call(a, AC, AC_SWITCH_LOOP, Rpc.NONE);
                Diag.put("climate.recircCommand", "toggle (was " + cur + ")");
            } catch (Exception e) {
                Diag.error("climate.recirc", e.toString());
                forget(BIND_HVAC);
            }
            poll();
        });
    }

    // ---- temperature and fan --------------------------------------------------------------------

    /** Sets the driver-side temperature (15 = LO … 31 = HI). The requested value stays visible for a few seconds. */
    public void setDriverTemp(int celsius) {
        int t = Math.max(TEMP_MIN, Math.min(TEMP_MAX, celsius));
        heldTemp = t;
        tempHoldUntil = android.os.SystemClock.uptimeMillis() + HOLD_MS;
        pendingTemp = t;
        sendSoon();
    }

    /** Fan level: 0 = turns the climate off, 1..11, 15 = automatic. */
    public void setFanLevel(int level) {
        int l = level == FAN_AUTO || level == FAN_OFF ? level : Math.max(FAN_MIN, Math.min(FAN_MAX, level));
        heldFan = l;
        fanHoldUntil = android.os.SystemClock.uptimeMillis() + HOLD_MS;
        // OFF: the car does not accept the fan at 0 (puts it back to 1 after a moment). Like the original app, the fan stays
        // at 1 or above and "OFF" means turning the climate off.
        if (l == FAN_OFF) {
            Diag.put("climate.fanOff", "fan OFF → turning climate off");
            setHvacPower(false);
            return;
        }
        pendingFan = l;
        sendSoon();
    }

    private void sendPending(IBinder a) throws RemoteException {
        int t = pendingTemp;
        if (t > 0) {
            pendingTemp = -1;
            float f = tempToCar(t);
            Rpc.call(a, AC, AC_SET_DRV_TEMP, p -> p.writeFloat(f));
        }
        int fan = pendingFan;
        if (fan >= 0) {
            pendingFan = -1;
            boolean power = Rpc.callBool(a, AC, AC_GET_POWER);
            if (fan == FAN_OFF) {
                if (power) Rpc.call(a, AC, AC_SWITCH_POWER, Rpc.NONE);
            } else if (fan == FAN_AUTO) {
                if (!Rpc.callBool(a, AC, AC_GET_AUTO)) Rpc.call(a, AC, AC_SWITCH_AUTO, Rpc.NONE);
            } else {
                if (!power) Rpc.call(a, AC, AC_SWITCH_POWER, Rpc.NONE);
                Rpc.call(a, AC, AC_SET_FAN, p -> p.writeInt(fan));
            }
        }
    }

    /** Sends the command right away, without rewriting the UI with an immediate reading. */
    private void sendSoon() {
        if (bg == null) return;
        bg.post(() -> {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null) sendPending(a);
            } catch (Exception e) {
                Diag.error("climate.command", e.toString());
                forget(BIND_HVAC);
            }
        });
    }

    /** Turns the climate on or off (the SWI69 service only has the "toggle state" command). */
    public void setHvacPower(boolean on) {
        if (bg == null) return;
        bg.post(() -> {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null && Rpc.callBool(a, AC, AC_GET_POWER) != on) {
                    Rpc.call(a, AC, AC_SWITCH_POWER, Rpc.NONE);
                }
            } catch (Exception e) {
                Diag.error("climate.power", e.toString());
            }
            poll();
        });
    }

    // ---- charging: battery manager commands, like the Energy app's Charging management --------

    /** Charge limit: code 1..7 (40..100 %). */
    public void setChargeLimitCode(int code) {
        if (code < 1 || code > 7) return;
        chargeCommand(CarBms.CHRG_TRGT_SOC, code, code, "charge.limit → " + limitPercent(code) + "%");
    }

    /** AC charging current: code 1..4 (6 A, 8 A, 16 A, Max). */
    public void setCurrentLimit(int code) {
        if (code < 1 || code > 4) return;
        chargeCommand(CarBms.ALTNG_CHRG_CRNT, code, code, "charge.current → " + CURRENT_LABELS[code - 1]);
    }

    /** Starts or stops charging (cable connected). */
    public void setCharging(boolean start) {
        chargeCommand(CarBms.CHRG_CTRL, CTRL_PREPARING, start ? CTRL_START : CTRL_STOP, "charge." + (start ? "start" : "stop"));
    }

    /** Starts or stops V2L discharging. */
    public void setDischarging(boolean start) {
        chargeCommand(CarBms.V2X, CTRL_PREPARING, start ? CTRL_START : CTRL_STOP, "v2l." + (start ? "start" : "stop"));
    }

    private void chargeCommand(int prop, int shown, int value, String what) {
        if (bg == null) return;
        hold(prop, shown);
        bg.post(() -> {
            boolean sent = bms.setInt(prop, value);
            Diag.put("charge.lastCommand", what + (sent ? " · sent" : " · not sent (battery manager not ready)"));
            poll();
        });
    }

    // ---- 0.4.2 (point 7d): scheduled charging, only if the car supports it -------------------------------------

    private void readSchedule(State s, long now) {
        int mode = bms.getInt(CarBms.RESER_CTRL);
        int sh = bms.getInt(CarBms.RESER_ST_HOUR), sm = bms.getInt(CarBms.RESER_ST_MIN);
        int eh = bms.getInt(CarBms.RESER_SP_HOUR), em = bms.getInt(CarBms.RESER_SP_MIN);
        boolean ok = mode >= 1 && mode <= 3 && sh >= 0 && sh <= 23 && sm >= 0 && sm <= 59 && eh >= 0 && eh <= 23 && em >= 0 && em <= 59;
        if (ok) {
            s.schedMode = held(CarBms.RESER_CTRL, mode, now);
            s.schedStartH = held(CarBms.RESER_ST_HOUR, sh, now);
            s.schedStartM = held(CarBms.RESER_ST_MIN, sm, now);
            s.schedEndH = held(CarBms.RESER_SP_HOUR, eh, now);
            s.schedEndM = held(CarBms.RESER_SP_MIN, em, now);
        }
        Diag.putQuiet("charge.scheduled", "supported " + (ok ? "yes" : "no") + " · mode " + mode + " (1 schedule, 2 off, 3 up to limit)"
                + " · start " + sh + ":" + sm + " · end " + eh + ":" + em);
    }

    /** Scheduled charging mode: 1 timed, 2 off, 3 up to the limit (like the Energy app). */
    public void setScheduleMode(int mode) {
        if (mode < 1 || mode > 3) return;
        chargeCommand(CarBms.RESER_CTRL, mode, mode, "charge.scheduled → mode " + mode);
    }

    /** Scheduled charging times (the EH32 Energy app sets hour and minutes together). */
    public void setScheduleTimes(int sh, int sm, int eh, int em) {
        if (bg == null) return;
        hold(CarBms.RESER_ST_HOUR, sh);
        hold(CarBms.RESER_ST_MIN, sm);
        hold(CarBms.RESER_SP_HOUR, eh);
        hold(CarBms.RESER_SP_MIN, em);
        bg.post(() -> {
            boolean ok = bms.setInt(CarBms.RESER_ST_HOUR, sh) & bms.setInt(CarBms.RESER_ST_MIN, sm)
                    & bms.setInt(CarBms.RESER_SP_HOUR, eh) & bms.setInt(CarBms.RESER_SP_MIN, em);
            Diag.put("charge.lastCommand", String.format(java.util.Locale.ROOT, "charge.scheduled → %02d:%02d - %02d:%02d", sh, sm, eh, em)
                    + (ok ? " · sent" : " · not sent (battery manager not ready)"));
            poll();
        });
    }

    /** 0.4.0 (point 6): unlocks the charging socket (same command as Charging management: socket lock = 2). */
    public void unlockChargingSocket() {
        if (bg == null) return;
        hold(CarBms.CHRG_LOCK, 2);
        bg.post(() -> {
            boolean sent = bms.setInt(CarBms.CHRG_LOCK, 2);
            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
            Diag.put("charge.unlockPort", (sent ? "sent at " : "NOT sent (battery manager not ready) at ") + time);
            poll();
        });
    }
}
