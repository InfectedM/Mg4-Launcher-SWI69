package com.mg4.launcher.climate;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

/**
 * Automatic test of the climate commands (started by hand from the climate settings, only with the car stopped).
 * <p>
 * It sends the commands one at a time like the original climate app and checks that the state read changes as
 * expected. At the end it puts everything back as it was (temperature, fan, direction, buttons pressed twice, climate
 * off if it was off). It stops at once if the car moves, switches off, the screen is left or a command fails.
 * Every step goes into the climate events, which are part of the launcher log ("Save log").
 * <p>
 * The same run is the "check" of an unknown version of the original climate app: if it passes, the replacement is
 * allowed for that version. It also measures the car's real temperature range (LO / HI) and top fan speed.
 */
public final class AutoTest {
    public interface Progress {
        void step(String text);
    }

    private final HvacClient h;
    private final Progress progress;
    private final android.content.Context ctx;
    private final String kind;
    private int ok, bad, step;
    private final List<String> fails = new ArrayList<>();
    /** Parts already done (resume after an interruption) and starting values to restore at the end. */
    private final java.util.Set<String> done = new java.util.LinkedHashSet<>();
    private int t0 = -1, f0 = -1, m0 = -1, l0 = -1, auto0 = -1, power0 = -1;
    private boolean poweredOn;

    private AutoTest(android.content.Context c, HvacClient h, Progress p, String kind) {
        ctx = c;
        this.h = h;
        progress = p;
        this.kind = kind;
    }

    /** Interruption message for a moving car (recognised by the screen). */
    static final String MOVING = "car moving";
    /** Result text with no failures (recognised by the screen). */
    static final String NO_FAILS = ", 0 failed";

    private static volatile boolean running;

    /** Test or check in progress. */
    static boolean running() {
        return running;
    }

    /** Starts the test on its own thread; done receives the summary. kind: "test" or "verify" (for resuming). */
    public static void start(android.content.Context c, HvacClient h, Progress p, String kind,
                             java.util.function.Consumer<String> done) {
        new Thread(() -> {
            AutoTest t = new AutoTest(c, h, p, kind);
            String r;
            h.testing = true;
            running = true;
            try {
                t.load();
                r = t.run();
                ClimateStore.setTestProgress(c, kind, "");   // finished: nothing to resume
            } catch (Abort a) {
                r = "interrupted: " + a.getMessage() + " (" + t.ok + " ok, " + t.bad + " failed)";
                ClimateStore.event(c, "TEST " + r);
            } catch (Throwable e) {
                r = "error: " + e + " (" + t.ok + " ok, " + t.bad + " failed)";
                ClimateStore.event(c, "TEST " + r);
            } finally {
                h.testing = false;
                running = false;
            }
            done.accept(r);
        }, "mg4-climate-test").start();
    }

    private static final class Abort extends RuntimeException {
        Abort(String m) {
            super(m);
        }
    }

    private void log(String s) {
        ClimateStore.event(ctx, "TEST " + s);
    }

    private HvacClient.State safe() {
        if (!h.running()) throw new Abort("screen closed");
        HvacClient.State s = h.readNow();
        if (!s.connected) throw new Abort("climate service not connected " + s.error);
        if (!(s.ignition == 2 || s.ignition == 3)) throw new Abort("car not on (" + s.ignition + ")");
        if (!Float.isNaN(s.speed) && s.speed >= 1f) throw new Abort(MOVING + " (" + s.speed + " km/h)");
        return s;
    }

    private void send(String what, int code, Integer arg) {
        safe();
        step++;
        progress.step("step " + step + " · " + what);
        try {
            h.send(code, arg);
        } catch (Exception e) {
            log("✗ " + what + ": command error " + e);
            throw new Abort("command refused (" + what + ")");
        }
    }

    /** Waits (max 5 s) for the value to meet the condition; returns the last value read. */
    private int waitFor(ToIntFunction<HvacClient.State> f, IntPredicate cond) {
        long end = SystemClock.elapsedRealtime() + 5000;
        int v;
        do {
            SystemClock.sleep(400);
            v = f.applyAsInt(safe());
            if (cond.test(v)) return v;
        } while (SystemClock.elapsedRealtime() < end);
        return v;
    }

    private void check(String what, boolean good, String detail) {
        if (good) ok++;
        else {
            bad++;
            fails.add(what);
        }
        log((good ? "✓ " : "✗ ") + what + " → " + detail);
    }

    /** Command with an exact value (temperature, fan, direction). */
    private void setValue(String what, int code, int value, ToIntFunction<HvacClient.State> f) {
        send(what + " = " + value, code, value);
        int v = waitFor(f, x -> x == value);
        check(what + " = " + value, v == value, "read " + v);
        SystemClock.sleep(600);
    }

    /** On/off button: pressed twice, it must change and then go back. Always restored. */
    private void toggle(String what, int code, ToIntFunction<HvacClient.State> f) {
        int v0 = f.applyAsInt(safe());
        try {
            send(what + " (pressed)", code, 1);
            int v1 = waitFor(f, x -> x != v0);
            check(what + " pressed", v1 != v0, v0 + " → " + v1);
            SystemClock.sleep(READBACK_MS);
            send(what + " (again)", code, 1);
            int v2 = waitFor(f, x -> x == v0);
            check(what + " pressed again", v2 == v0, v1 + " → " + v2);
            SystemClock.sleep(600);
        } finally {
            restore(what, f, v0, code);
        }
    }

    /** The car reads seats, wheel and recirculation back with about 2 s of delay. */
    static final int READBACK_MS = 2000;

    /**
     * Multi-level button (recirculation): pressed until it is back where it was, the sequence is logged.
     * After every press it waits for the value read back to settle (READBACK_MS) before pressing again;
     * at the end, also if the test is interrupted, the starting value is put back ({@link #restore}).
     */
    private void cycle(String what, int code, ToIntFunction<HvacClient.State> f, int maxPress) {
        int v0 = f.applyAsInt(safe()), v = v0;
        try {
            StringBuilder seq = new StringBuilder().append(v0);
            boolean changed = false;
            for (int i = 0; i < maxPress; i++) {
                send(what + " (press " + (i + 1) + ")", code, 1);
                final int before = v;
                waitFor(f, x -> x != before);
                SystemClock.sleep(READBACK_MS);          // settled value, not the passing one
                v = f.applyAsInt(safe());
                seq.append(" → ").append(v);
                if (v != before) changed = true;
                else break;   // it does not change: no point insisting
                if (v == v0) break;
                SystemClock.sleep(600);
            }
            check(what + " levels", changed && v == v0, seq + (v == v0 ? "" : " (did not go back to " + v0 + ")"));
        } finally {
            restore(what, f, v0, code);
        }
    }

    /** Reading for the restore: also works with the screen closed or the car moving (null = car not readable). */
    private HvacClient.State readForRestore() {
        try {
            HvacClient.State s = h.readNow();
            if (!s.connected || !(s.ignition == 2 || s.ignition == 3)) return null;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean moving(HvacClient.State s) {
        return !Float.isNaN(s.speed) && s.speed >= 1f;
    }

    /**
     * Puts the starting value v0 back with presses (never with the car moving), waiting for the settled value.
     * Up to 3 attempts; if it fails it is a serious error in the log.
     */
    private void restore(String what, ToIntFunction<HvacClient.State> f, int v0, int pressCode) {
        if (v0 < 0) return;
        HvacClient.State s = readForRestore();
        if (s == null) {
            log("✗✗ SERIOUS ERROR: " + what + " NOT restored to " + v0 + " (car off or service not connected)");
            return;
        }
        int v = f.applyAsInt(s);
        if (v == v0) return;   // already as it was
        String how = "";
        boolean stopMoving = false;
        for (int attempt = 1; attempt <= 3 && v != v0 && !stopMoving; attempt++) {
            try {
                how = "press";
                for (int k = 0; k < 3 && v != v0; k++) {
                    HvacClient.State m = readForRestore();
                    if (m == null) break;
                    if (moving(m)) {
                        stopMoving = true;
                        break;
                    }
                    h.send(pressCode, 1);
                    SystemClock.sleep(READBACK_MS);
                    HvacClient.State r = readForRestore();
                    if (r == null) break;
                    v = f.applyAsInt(r);
                }
            } catch (Throwable e) {
                how = "error " + e.getClass().getSimpleName();
            }
            if (v != v0 && !stopMoving) SystemClock.sleep(800);
        }
        if (v == v0) log("✓ " + what + " restored to " + v0 + " (" + how + ")");
        else {
            log("✗✗ SERIOUS ERROR: " + what + " NOT restored to " + v0 + " (read " + (v == Integer.MIN_VALUE ? "—" : v)
                    + (stopMoving ? ", car moving: no presses" : "") + (how.isEmpty() ? "" : ", " + how) + ")");
            bad++;
            fails.add("restore " + what);
        }
    }

    // ---- failed tries not repeated ------------------------------------------------------------------------------------
    // Recirculation: if a try fails, for the same launcher version and original climate app version it is not done again
    // (no presses on every check); the saved result is used.

    private boolean tryPart(String part) {
        String saved = ClimateStore.partFailed(ctx, ClimateStore.appVersion(ctx), version(), part);
        if (saved.isEmpty()) return true;
        bad++;
        fails.add(part);
        log("✗ " + part + " → not tried again: already failed with launcher " + ClimateStore.appVersion(ctx) + " and climate app "
                + version() + " (saved result: " + saved + ")");
        return false;
    }

    private void partDone(String part, int failsBefore) {
        if (fails.size() > failsBefore)
            ClimateStore.setPartFailed(ctx, ClimateStore.appVersion(ctx), version(), part,
                    String.join(", ", fails.subList(failsBefore, fails.size())));
    }

    // ---- resume from where it stopped ---------------------------------------------------------------------------------
    // After every part that succeeded, the parts done, the counts and the starting values are saved (for the same
    // climate app version). If the test is interrupted (e.g. the car moves) the next time it starts from the first part
    // not done.

    private String version() {
        return ClimateGuard.originalVersion(ctx);
    }

    private void load() {
        String raw = ClimateStore.testProgress(ctx, kind);
        if (raw.isEmpty()) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(raw);
            if (!version().equals(o.optString("version"))) return;
            org.json.JSONArray d = o.getJSONArray("done");
            for (int i = 0; i < d.length(); i++) done.add(d.getString(i));
            ok = o.optInt("ok");
            bad = o.optInt("bad");
            org.json.JSONArray f = o.optJSONArray("failed");
            if (f != null) for (int i = 0; i < f.length(); i++) fails.add(f.getString(i));
            t0 = o.optInt("t0", -1);
            f0 = o.optInt("f0", -1);
            m0 = o.optInt("m0", -1);
            l0 = o.optInt("l0", -1);
            auto0 = o.optInt("auto0", -1);
            power0 = o.optInt("power0", -1);
            log("RESUME · already done: " + String.join(", ", done) + " (" + ok + " ok, " + bad + " failed)");
        } catch (Throwable e) {
            done.clear();
        }
    }

    private void save() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("version", version());
            o.put("done", new org.json.JSONArray(done));
            o.put("ok", ok).put("bad", bad).put("failed", new org.json.JSONArray(fails));
            o.put("t0", t0).put("f0", f0).put("m0", m0).put("l0", l0).put("auto0", auto0).put("power0", power0);
            ClimateStore.setTestProgress(ctx, kind, o.toString());
        } catch (Throwable ignored) {
        }
    }

    /** There are parts of this kind of test already done, for the current climate app version. */
    static boolean resumable(android.content.Context c, String kind) {
        String raw = ClimateStore.testProgress(c, kind);
        if (raw.isEmpty()) return false;
        try {
            return ClimateGuard.originalVersion(c).equals(new org.json.JSONObject(raw).optString("version"));
        } catch (Throwable e) {
            return false;
        }
    }

    /** true if the part is to be done; otherwise it is skipped (already done before the interruption). */
    private boolean todo(String part) {
        if (!done.contains(part)) return true;
        log("· " + part + ": already done, skipped");
        return false;
    }

    private void did(String part) {
        done.add(part);
        save();
    }

    // ---- real temperature range of the car ----------------------------------------------------------------------------
    // LO = the lowest value the car accepts, HI = the highest. It tries going down (LO) and up (HI) and stops at the first
    // value the car refuses: a "LO" that comes back as HI (SWI69 R33: 15.5 → 31) is never good. The service does not give
    // the limits: LO among 17, 16, 15.5 and 15, HI among 30.5, 31, 32 and 33. The result is saved per climate app version
    // (ClimateStore.setTempRange) and used by the backend.
    private float calLoSend = Float.NaN, calLoRead = Float.NaN, calHiSend = Float.NaN, calHiRead = Float.NaN;
    private int calFanMax = -1;

    private float waitRaw(java.util.function.DoublePredicate cond) {
        long end = SystemClock.elapsedRealtime() + 5000;
        float v;
        do {
            SystemClock.sleep(400);
            v = safe().tempRaw;
            if (cond.test(v)) return v;
        } while (SystemClock.elapsedRealtime() < end);
        return v;
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < 0.05f;
    }

    /** LO: from the highest to the lowest of the possible values; the last one the car reads back the same is kept. */
    private void calibrateLow() {
        float[] tries = CarAdapterBackend.LO_TRY;
        StringBuilder tried = new StringBuilder();
        float okSend = Float.NaN, okRead = Float.NaN;
        boolean minProved = false;   // the car "clamped" the lowest value to the accepted one: it is its minimum
        boolean all = true;
        for (float t : tries) {
            send("temperature LO try " + t, HvacClient.SET_DRV_TEMP_RAW, Math.round(t * 10));
            final float tt = t;
            float r = waitRaw(x -> same((float) x, tt) || (tt < 16 && x <= tt + 0.01 && x >= 10));
            boolean good = same(r, t) || (t < 16 && r <= t + 0.01f && r >= 10);
            tried.append(tried.length() > 0 ? ", " : "").append(t).append(" → ").append(r);
            log("· try LO = " + t + " → read back " + r + (good ? " ✓" : r >= 29 ? " (read as HI) ✗" : " ✗"));
            if (!good) {
                all = false;
                minProved = !Float.isNaN(okRead) && same(r, okRead);
                break;
            }
            okSend = t;
            okRead = r;
            SystemClock.sleep(500);
        }
        // real LO: a value below 16 (15.5 / 15, the LO of EU cars), or the last accepted if the car stopped there. If the
        // next value "jumped" elsewhere (15.5 → 31) LO stays hidden, numeric minimum.
        if (!Float.isNaN(okSend) && (okRead < 16 || minProved || all)) {
            calLoSend = okSend;
            calLoRead = okRead;
        }
        if (!Float.isNaN(calLoSend)) {
            check("temperature LO", true, "car LO " + calLoSend + " (read back " + calLoRead + ") · tried: " + tried);
        } else {
            log("· temperature LO HIDDEN: no value read back as LO (tried: " + tried
                    + ") · temperature down to the numeric minimum, the replacement stays possible");
        }
        SystemClock.sleep(600);
    }

    /**
     * HI: it goes up one degree at a time to the numeric maximum the car accepts, then tries the HI values. If the car
     * stops earlier (variants 16–28) that value is HI.
     */
    private void calibrateHigh() {
        int top = 30;
        int cur = Math.round(safe().tempRaw);
        int last = cur;
        boolean clamped = true;   // the car stopped on the last accepted value (it did not "jump" elsewhere)
        while (cur < top) {
            int next = cur + 1;
            send("temperature " + next + " (one degree at a time)", HvacClient.SET_DRV_TEMP, next);
            final int n = next;
            float r = waitRaw(x -> same((float) x, n));
            if (!same(r, n)) {
                clamped = same(r, last);
                log("· temperature " + n + " (one degree at a time) → read " + r
                        + (clamped ? ": the car stops at " + last : ": unexpected value"));
                break;
            }
            last = cur = n;
            SystemClock.sleep(300);
        }
        StringBuilder tried = new StringBuilder();
        if (last >= 30) {   // SWI69 HI: 30.5 / 31 / 32 / 33, it stops at the first whole value refused
            float okSend = Float.NaN, okRead = Float.NaN;
            boolean maxProved = false, stopped = false;
            for (float t : CarAdapterBackend.HI_TRY) {
                send("temperature HI try " + t, HvacClient.SET_DRV_TEMP_RAW, Math.round(t * 10));
                final float tt = t;
                float r = waitRaw(x -> same((float) x, tt) || (tt < 31 && x >= tt - 0.01 && x <= 34));
                boolean good = same(r, t) || (t < 31 && r >= t - 0.01f && r <= 34);
                tried.append(tried.length() > 0 ? ", " : "").append(t).append(" → ").append(r);
                log("· try HI = " + t + " → read back " + r + (good ? " ✓" : " ✗"));
                if (good) {
                    okSend = t;
                    okRead = r;
                } else if (t >= 31) {   // 30.5 may fail and 31 work (SWI69 R33)
                    maxProved = !Float.isNaN(okRead) && same(r, okRead);
                    stopped = true;
                    break;
                }
                SystemClock.sleep(500);
            }
            // real HI: 30.5 (HI of EU cars), the last of the list (33), or the last accepted if the car stopped there.
            // If the next value "jumped" elsewhere, HI stays hidden.
            if (!Float.isNaN(okSend) && (okSend < 31 || maxProved || !stopped)) {
                calHiSend = okSend;
                calHiRead = okRead;
            }
        } else if (last >= 26 && clamped) {   // variant with a lower temperature (e.g. 16–28)
            calHiSend = calHiRead = last;
            tried.append("went up to ").append(last);
            log("· the car accepts no more than " + last + "°: smaller range (variant 16–28?)");
        }
        if (!Float.isNaN(calHiSend)) {
            check("temperature HI", true, "car HI " + calHiSend + " (read back " + calHiRead + ") · " + tried);
        } else {
            log("· temperature HI HIDDEN: no value read back as HI (tried: " + tried
                    + ") · temperature up to the numeric maximum, the replacement stays possible");
        }
        SystemClock.sleep(600);
    }

    /** Saves the measured range and makes the backend use it at once. */
    private void saveRange() {
        ClimateStore.setTempRange(ctx, version(), calLoSend, calLoRead, calHiSend, calHiRead, calFanMax);
        CarAdapterBackend.loadLimits(ctx);
        log("· climate.range = " + CarAdapterBackend.rangeText());
    }

    private String run() {
        HvacClient.State s0 = safe();
        if (s0.acAvailable != 1) throw new Abort("climate not available (" + s0.acAvailable + ")");
        log("START · climate app " + ClimateGuard.originalVersion(ctx) + "\n" + s0.dump());
        if (power0 < 0) power0 = s0.power == 1 ? 1 : 0;   // on resume: as it was the first time

        if (s0.power != 1) {
            send("climate on", HvacClient.SET_POWER_PRESS, 1);
            int p = waitFor(s -> s.power, x -> x == 1);
            if (!done.contains("power")) check("climate on", p == 1, "read " + p);
            if (p != 1) throw new Abort("the climate does not turn on: no other commands");
            SystemClock.sleep(1500);
        }
        if (!done.contains("power")) did("power");
        poweredOn = power0 == 0;   // it was off: at the end it is turned off again
        HvacClient.State s1 = safe();
        // starting values: on resume they stay those of the first time
        if (t0 < 0) t0 = s1.temp;
        if (f0 < 0) f0 = s1.fan;
        if (m0 < 0) m0 = s1.blowerMode;
        if (l0 < 0) l0 = s1.loop;
        if (auto0 < 0) auto0 = s1.auto;
        save();
        int lo = s1.tempMin, hi = s1.tempMax;
        int mid = t0 >= lo + 2 && t0 <= hi - 4 ? t0 : 22;

        // temperature: +2, LO, then back to the middle and up to HI one degree at a time
        if (todo("temperature")) {
            int t1 = mid + 2;
            setValue("temperature", HvacClient.SET_DRV_TEMP, t1, s -> s.temp);
            calibrateLow();    // real LO of the car
            setValue("temperature (after LO)", HvacClient.SET_DRV_TEMP, mid, s -> s.temp);
            calibrateHigh();   // real HI of the car (one degree at a time)
            saveRange();
            HvacClient.State s2 = safe();
            if (t0 >= s2.tempMin && t0 <= s2.tempMax) setValue("temperature (restore)", HvacClient.SET_DRV_TEMP, t0, s -> s.temp);
            else setValue("temperature (restore)", HvacClient.SET_DRV_TEMP, mid, s -> s.temp);
            did("temperature");
        }

        if (todo("fan")) {
            setValue("fan", HvacClient.SET_AIR_VOLUME, 3, s -> s.fan);
            setValue("fan", HvacClient.SET_AIR_VOLUME, 6, s -> s.fan);
            // fan up to 11 or up to 8 (variant)?
            send("fan = 11 (maximum)", HvacClient.SET_AIR_VOLUME, 11);
            int v = waitFor(s -> s.fan, x -> x == 11);
            if (v == 11) log("· top fan speed 11 ✓");
            else if (v >= 6 && v <= 10) {
                calFanMax = v;
                log("· top fan speed: the car stops at " + v + " (car with fan 1–" + v + ")");
            } else log("· fan 11 → read " + v + ": maximum not measured");
            float[] r0 = ClimateStore.tempRange(ctx, version());   // on resume the temperature is already measured
            if (Float.isNaN(calLoSend) && Float.isNaN(calHiSend) && r0 != null)
                ClimateStore.setTempRange(ctx, version(), r0[0], r0[1], r0[2], r0[3], calFanMax);
            else ClimateStore.setTempRange(ctx, version(), calLoSend, calLoRead, calHiSend, calHiRead, calFanMax);
            CarAdapterBackend.loadLimits(ctx);
            SystemClock.sleep(600);
            did("fan");
        }

        // air direction (without the windscreen combinations, which can turn the A/C on)
        if (todo("direction")) {
            setValue("direction face", HvacClient.SET_BLOWER_MODE, 0, s -> s.blowerMode);
            setValue("direction face+feet", HvacClient.SET_BLOWER_MODE, 1, s -> s.blowerMode);
            setValue("direction feet", HvacClient.SET_BLOWER_MODE, 2, s -> s.blowerMode);
            if (m0 >= 0 && m0 <= 6) setValue("direction (restore)", HvacClient.SET_BLOWER_MODE, m0, s -> s.blowerMode);
            did("direction");
        }

        // buttons
        if (todo("A/C")) {
            toggle("A/C", HvacClient.SET_AC_PRESS, s -> s.ac);
            did("A/C");
        }
        if (todo("ECO")) {
            toggle("ECO", HvacClient.SET_ECON_PRESS, s -> s.econ);
            did("ECO");
        }
        if (todo("recirculation")) {
            if (tryPart("recirculation")) {
                int fb = fails.size();
                cycle("recirculation", HvacClient.SET_LOOP_PRESS, s -> s.loop, 3);
                partDone("recirculation", fb);
            }
            did("recirculation");
        }
        // AUTO does not turn off when pressed again: it is left by changing the fan (restored at the end)
        if (todo("AUTO")) {
            send("AUTO (pressed)", HvacClient.SET_AUTO_PRESS, 1);
            int au = waitFor(s -> s.auto, x -> x == 1);
            check("AUTO pressed", au == 1, "read " + au);
            SystemClock.sleep(600);
            did("AUTO");
        }

        // fan restore (if it was not automatic)
        HvacClient.State s2 = safe();
        if (f0 >= 1 && f0 <= 11 && s2.fan != f0) setValue("fan (restore)", HvacClient.SET_AIR_VOLUME, f0, s -> s.fan);
        if (m0 >= 0 && m0 <= 6 && safe().blowerMode != m0)
            setValue("direction (restore after AUTO)", HvacClient.SET_BLOWER_MODE, m0, s -> s.blowerMode);
        if (auto0 == 1 && safe().auto != 1) {
            send("AUTO (restore)", HvacClient.SET_AUTO_PRESS, 1);
            waitFor(s -> s.auto, x -> x == 1);
        }
        // AUTO puts the recirculation in automatic (1 → 2) and it stays so after leaving AUTO: it is put back by
        // pressing the button until it returns to the starting value (at most 3 presses). Not counted in the result.
        if (auto0 != 1 && l0 >= 0 && safe().loop != l0) {
            int lv = safe().loop;
            StringBuilder seq = new StringBuilder().append(lv);
            for (int i = 0; i < 3 && lv != l0; i++) {
                SystemClock.sleep(600);
                send("recirculation (restore " + (i + 1) + ")", HvacClient.SET_LOOP_PRESS, 1);
                final int before = lv;
                lv = waitFor(s -> s.loop, x -> x != before);
                seq.append(" → ").append(lv);
                if (lv == before) break;
            }
            log((lv == l0 ? "✓" : "✗") + " recirculation (restore) " + seq + (lv == l0 ? "" : " (did not go back to " + l0 + ")"));
        }
        if (poweredOn) {
            SystemClock.sleep(800);
            send("climate off (it was off)", HvacClient.SET_POWER_PRESS, 1);
            int p = waitFor(s -> s.power, x -> x == 0);
            check("climate off", p != 1, "read " + p);
        }

        String diff = HvacClient.diff(s0, safe());
        String r = ok + " ok, " + bad + " failed" + (fails.isEmpty() ? "" : " (" + String.join(", ", fails) + ")")
                + (diff.isEmpty() ? " · everything put back as it was" : " · final differences: " + diff);
        log("END · " + r);
        return r;
    }
}
