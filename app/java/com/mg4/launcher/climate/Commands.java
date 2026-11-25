package com.mg4.launcher.climate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.mg4.launcher.L;
import com.mg4.launcher.R;

/**
 * Climate commands (taps on the screen) in one class, used by the screen {@link ClimateActivity} and by the overlay
 * panel {@link OverlayPanel} (Android Auto/CarPlay).
 */
final class Commands {
    interface Toaster {
        void toast(String s);
    }

    private final Context ctx;
    private final HvacClient client;
    private final Toaster toaster;
    private final Runnable other;
    private final Handler h = new Handler(Looper.getMainLooper());
    private HvacClient.State last = new HvacClient.State();

    Commands(Context ctx, HvacClient client, Toaster toaster, Runnable other) {
        this.ctx = ctx;
        this.client = client;
        this.toaster = toaster;
        this.other = other;
    }

    private void toast(String s) {
        toaster.toast(s);
    }

    private int pendTemp = -1, pendFan = -1, lastManualFan = 4;
    private long pendTempAt, pendFanAt;

    void onState(HvacClient.State s) {
        last = s;
        if (s.fan >= 1 && s.fan <= 11) lastManualFan = s.fan;
    }

    void command(String id) {
        HvacClient.State s = last;
        if (!s.connected) {
            toast(L.s(R.string.cl_a_not_connected));
            return;
        }
        if (!s.usable()) {
            toast(L.s(R.string.cl_a_switch_on));
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        switch (id) {
            case "power":
                press("power", HvacClient.SET_POWER_PRESS);
                break;
            case "temp-":
            case "temp+": {
                int base = now - pendTempAt < 1500 && pendTemp > 0 ? pendTemp : s.temp;
                if (base < s.tempMin || base > s.tempMax) base = 22;
                // without LO / HI that can be set (calibration) it stops at the numeric minimum / maximum
                int tt = id.equals("temp+") ? Math.min(s.setMax(), base + 1) : Math.max(s.setMin(), base - 1);
                if (tt == base) return;
                pendTemp = tt;
                pendTempAt = now;
                send("temperature", HvacClient.SET_DRV_TEMP, tt);
                break;
            }
            case "fan-":
            case "fan+": {
                int min = Math.max(1, s.fanMin), max = Math.min(11, s.fanMax);
                // as the original app: "fan +" with the climate off turns it back on with the power command (the car
                // goes back to how it was), "fan −" at the lowest speed turns the climate off
                if (!s.on()) {
                    if (id.equals("fan+")) press("power (fan +)", HvacClient.SET_POWER_PRESS);
                    break;
                }
                int base = now - pendFanAt < 1500 && pendFan > 0 ? pendFan
                        : s.fan >= min && s.fan <= max ? s.fan : lastManualFan;   // from AUTO it starts at the last level
                boolean fromAuto = s.fan == HvacClient.FAN_AUTO && now - pendFanAt >= 1500;
                if (!fromAuto && id.equals("fan-") && base <= min && now - pendFanAt >= 1500) {
                    press("power off (fan − at the minimum)", HvacClient.SET_POWER_PRESS);
                    break;
                }
                int f = fromAuto ? base : id.equals("fan+") ? Math.min(max, base + 1) : Math.max(min, base - 1);
                if (f == base && !fromAuto) return;
                pendFan = f;
                pendFanAt = now;
                send("fan", HvacClient.SET_AIR_VOLUME, f);
                break;
            }
            case "auto":
                // with AUTO already on the button changes the intensity, as in the original's settings:
                // low → medium → high → low (AUTO is left by touching the fan, as in the original app)
                if (s.on() && s.auto == 1 && s.autoLevel >= 0) {
                    int next = s.autoLevel == HvacClient.AUTO_LOW ? HvacClient.AUTO_MEDIUM
                            : s.autoLevel == HvacClient.AUTO_MEDIUM ? HvacClient.AUTO_HIGH : HvacClient.AUTO_LOW;
                    send("AUTO intensity", HvacClient.SET_AUTO_LEVEL, next);
                    toast("AUTO · " + ClimateView.autoLevelName(next));
                } else press("AUTO", HvacClient.SET_AUTO_PRESS);
                break;
            case "ac": press("A/C", HvacClient.SET_AC_PRESS); break;
            case "eco": press("ECO", HvacClient.SET_ECON_PRESS); break;
            case "recirc": press("recirculation", HvacClient.SET_LOOP_PRESS); break;
            case "wheel": press("wheel", HvacClient.SET_WHEEL_PRESS); break;
            case "seatDriver": press("driver seat", HvacClient.SET_DRV_SEAT_PRESS); break;
            case "seatPassenger": press("passenger seat", HvacClient.SET_PSG_SEAT_PRESS); break;
            case "glass":
            case "face":
            case "feet":
                direction(id, s);
                break;
            case "frontDefrost":
                checked("windscreen", s.frontDefrost == 1 ? HvacClient.FRONT_DEFROST_CLOSE : HvacClient.FRONT_DEFROST_OPEN,
                        st -> st.frontDefrost, s.frontDefrost);
                break;
            case "rearDefrost":
                checked("rear window", s.rearDefrost == 1 ? HvacClient.REAR_DEFROST_CLOSE : HvacClient.REAR_DEFROST_OPEN,
                        st -> st.rearDefrost, s.rearDefrost);
                break;
            default:
                ClimateStore.event(ctx, "tap on " + id + " → original climate");
                if (other != null) other.run();
        }
    }

    /** Air direction as the original app: one of the three turns on/off and the 0..6 code is computed. */
    private void direction(String id, HvacClient.State s) {
        boolean[] d = s.blowerMode >= 0 && s.blowerMode <= 6 ? s.directions() : new boolean[3];
        boolean def = d[0], face = d[1], foot = d[2];
        if (id.equals("glass")) def = !def;
        else if (id.equals("face")) face = !face;
        else foot = !foot;
        int mode;
        if (face && !def && !foot) mode = 0;
        else if (face && foot && !def) mode = 1;
        else if (foot && !def && !face) mode = 2;
        else if (def && foot && !face) mode = 3;
        else if (def && !face && !foot) mode = 4;
        else if (face && def && foot) mode = 5;
        else if (face && def) mode = 6;
        else return;   // at least one direction must stay (as the original)
        send("direction", HvacClient.SET_BLOWER_MODE, mode);
    }

    private void press(String what, int code) {
        send(what, code, 1);
    }

    void send(String what, int code, Integer arg) {
        client.command(what, code, arg, err -> {
            if (err != null) toast(L.f(R.string.cl_a_cmd_failed, what));
        });
    }

    /** Commands not tried by the test (windscreen, rear window): if nothing changes after 3 s it says so. */
    private void checked(String what, int code, java.util.function.ToIntFunction<HvacClient.State> f, int before) {
        send(what, code, null);
        h.postDelayed(() -> {
            if (f.applyAsInt(last) == before) {
                ClimateStore.event(ctx, what + ": no change after the command");
                toast(L.f(R.string.cl_a_use_button, L.s("windscreen".equals(what) ? R.string.cl_t_front : R.string.cl_t_rear)));
            }
        }, 3000);
    }
}
