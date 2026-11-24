package com.mg4.launcher.svc;

/**
 * Windows: auto-close logic when leaving the car, with no Android dependencies (testable outside the car).
 * <p>
 * Behavior (the same as WinClose, rewritten for the launcher):
 * <ul>
 *   <li><b>Arm</b>: on each trip (READY 0 → 1) the closing is armed when the speed exceeds the limit or when the
 *       car has been in READY for longer than the minimum time;</li>
 *   <li><b>Trigger</b>: with the car in P and armed, when the driver's door opens (or closes, depending on the choice)
 *       the countdown starts; at the end of the countdown the windows close;</li>
 *   <li><b>Cancel</b>: the door opens again, the car leaves P, goes back to READY or a window moves during the
 *       countdown. After a trigger there is a 60 s pause (reopening the door does not trigger again right away).</li>
 * </ul>
 * Time comes from outside ({@code now}, in ms) and everything runs on the same thread (the windows service one).
 */
public final class WindowLogic {

    /** Gear from the SAIC state service (ICarStateService / ICarStateCallback): 1 = P. */
    public static final int GEAR_P = 1;
    /** Driver's door sensor (ICarStateCallback): 0 = open, other values = closing / closed. */
    public static final int DOOR_OPEN = 0;
    public static final long COOLDOWN_MS = 60_000L;

    public interface Events {
        /** Countdown started (seconds until closing). */
        void countdown(int seconds);

        /** Countdown cancelled before closing. */
        void cancelled(String reason);

        /** End of countdown: close the windows now. */
        void close();

        /** Line for the logs (diagnostics). */
        void log(String msg);
    }

    /** Configuration read from settings on every event (can change without restarting). */
    public static final class Config {
        public boolean enabled;
        public int speedKmh = 20;
        public int armMinutes = 5;
        public int delaySec = 5;
        /** true = the countdown starts when the door opens; false = when it closes after having opened in P. */
        public boolean onDoorOpen = true;
    }

    public interface ConfigSource {
        Config get();
    }

    /** Tells whether all windows are surely closed (reliable reading); if so, no countdown and no beep. */
    public interface ClosedCheck {
        boolean allSurelyClosed();
    }

    private final Events ev;
    private final ConfigSource cfg;
    private final ClosedCheck closedCheck;

    private int ready = -1;          // -1 unknown, 0 no, 1 READY
    private long readySince;
    private int gear = -1;
    private int door = -1;
    private boolean armed;
    private boolean doorOpenedInP;
    private long lastTrigger = Long.MIN_VALUE / 2;
    private long deadline = -1;      // >= 0 while counting down

    public WindowLogic(Events ev, ConfigSource cfg, ClosedCheck closedCheck) {
        this.ev = ev;
        this.cfg = cfg;
        this.closedCheck = closedCheck;
    }

    public boolean armed() {
        return armed;
    }

    public boolean counting() {
        return deadline >= 0;
    }

    /** Seconds left in the countdown (0 if not counting). */
    public int secondsLeft(long now) {
        if (deadline < 0) return 0;
        return (int) Math.max(0, (deadline - now + 999) / 1000);
    }

    public int gear() {
        return gear;
    }

    public int door() {
        return door;
    }

    public int ready() {
        return ready;
    }

    /** READY read from the car (1 / 0; other values ignored). */
    public void onReady(int r, long now) {
        if (r != 0 && r != 1) return;
        if (r == ready) return;
        int prev = ready;
        ready = r;
        if (r == 1) {
            // new trip: arming is needed again
            if (prev == 0) ev.log("READY: new trip");
            armed = false;
            doorOpenedInP = false;
            readySince = now;
            cancel("car in READY");
        } else if (gear != GEAR_P) {
            // switched off outside P: unexpected state, nothing is kept
            armed = false;
            doorOpenedInP = false;
        }
        // switched off in P: stays armed (in an EV you switch off before opening the door)
    }

    /** Gear read from the car. */
    public void onGear(int g, long now) {
        if (g < 0 || g == gear) return;
        gear = g;
        if (g != GEAR_P) {
            doorOpenedInP = false;
            cancel("left P");
        } else if (cfg.get().onDoorOpen && door == DOOR_OPEN) {
            maybeTrigger("P with door already open", now);
        }
    }

    /** Driver's door sensor. */
    public void onDoor(int v, long now) {
        if (v == door) return;
        door = v;
        Config c = cfg.get();
        if (v == DOOR_OPEN) {
            cancel("door open");
            if (gear == GEAR_P) doorOpenedInP = true;
            if (c.onDoorOpen) maybeTrigger("door open", now);
        } else {
            // the door sends several values while closing (ajar, latch): do not cancel here
            if (!c.onDoorOpen && doorOpenedInP && gear == GEAR_P) {
                doorOpenedInP = false;
                maybeTrigger("door closed", now);
            }
        }
    }

    /** Called frequently (≈ 2 times per second): arming by speed / time and end of countdown. */
    public void tick(float speedKmh, long now) {
        Config c = cfg.get();
        if (!armed && ready == 1) {
            if (!Float.isNaN(speedKmh) && speedKmh >= c.speedKmh) {
                armed = true;
                ev.log("armed: " + Math.round(speedKmh) + " km/h ≥ " + c.speedKmh);
            } else if (now - readySince >= c.armMinutes * 60_000L) {
                armed = true;
                ev.log("armed: " + c.armMinutes + " min in READY");
            }
        }
        if (deadline >= 0 && now >= deadline) {
            deadline = -1;
            ev.log("countdown finished: close");
            ev.close();
        }
    }

    /** Cancels the countdown (window moved by hand, button, etc.). */
    public void cancel(String reason) {
        if (deadline < 0) return;
        deadline = -1;
        ev.log("cancelled: " + reason);
        ev.cancelled(reason);
    }

    private void maybeTrigger(String why, long now) {
        Config c = cfg.get();
        if (!c.enabled) {
            ev.log(why + ": ignored (auto-close off)");
            return;
        }
        if (gear != GEAR_P) return;
        if (!armed) {
            ev.log(why + ": ignored (not armed)");
            return;
        }
        if (now - lastTrigger < COOLDOWN_MS) {
            ev.log(why + ": ignored (60 s pause)");
            return;
        }
        if (deadline >= 0) return;
        if (closedCheck != null && closedCheck.allSurelyClosed()) {
            ev.log(why + ": windows already closed, nothing to do");
            armed = false;
            return;
        }
        lastTrigger = now;
        armed = false;
        deadline = now + Math.max(0, c.delaySec) * 1000L;
        ev.log(why + ": countdown of " + c.delaySec + " s");
        ev.countdown(c.delaySec);
        if (c.delaySec <= 0) tick(Float.NaN, now);
    }
}
