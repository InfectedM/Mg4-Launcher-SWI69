package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import com.mg4.launcher.svc.ChargeFrames;
import com.mg4.launcher.svc.Diag;

import java.io.File;

/**
 * 1.0.4 (point F): charging animation over the drawn car.
 * <p>
 * Four phases: plug-in (once) → "charging" loop (repeated) → finished (once, the last frame stays
 * still) → unplug (when the cable is removed, then the still image returns). If the cable is already in when the
 * screen turns on, it starts from the loop (or the finished phase). Never in READY or with the car moving: back to the
 * still image at once. Photo → clip and clip → photo with a 0.3 s fade.
 * <p>
 * Playback (1.0.4 fix, in-car test of 15/11): no more MediaPlayer on TextureView, which on the car
 * stayed stuck on the first frame with a black box. The clips are converted only once into JPEG
 * frames ({@link ChargeFrames}); a thread decodes them at 30 per second into three alternating bitmaps and this
 * plain view draws them. The final frame of a phase equals the first of the next one, so the transition is invisible.
 * <p>
 * Position: the clip (1312×400) is twice the car box in the charging layout (about 656×200); the wheel
 * centers of the clip (x 358 and 973, y 288) align with those of the photo drawn by CarView.
 */
public final class ChargeAnimView extends View {
    public static final int IDLE = 0, PLUG_IN = 1, LOOP = 2, DONE = 3, UNPLUG = 4;
    private static final String[] NAMES = {"idle", "plugin", "loop", "done", "unplug"};
    private static final float CLIP_W = 1312, CLIP_H = 400, CLIP_WHEEL_X1 = 358, CLIP_WHEEL_X2 = 973, CLIP_WHEEL_Y = 288;
    private static final long FRAME_MS = 1000 / 30;

    /** Where to take the clips from: pack of the chosen colour or test folder (USB stick). */
    public interface Source {
        File clip(boolean day, String phase);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Drawing thread with its three bitmaps: a new one is created at every release (no shared bitmaps). */
    private static final class Player {
        final HandlerThread thread = new HandlerThread("mg4-animation-draw", android.os.Process.THREAD_PRIORITY_DISPLAY);
        final Handler h;
        final Bitmap[] buf = new Bitmap[3];

        Player() {
            thread.start();
            h = new Handler(thread.getLooper());
        }
    }

    private Player player;
    private final Rect dst = new Rect();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private volatile Bitmap front;
    /** Grows at every new sequence: the thread stops drawing the old ones. */
    private volatile int gen;

    private int state = IDLE;
    private boolean day = true;
    private boolean fresh = true;            // first evaluation after the screen turns on
    private volatile boolean finishAfterLoop;
    private Source source;
    private String reason = "";
    private Runnable afterTest;
    private boolean lastPlugged, lastDone, failedThisPlug;
    private boolean preparing;

    public ChargeAnimView(Context c) {
        super(c);
        setClickable(false);
        setVisibility(GONE);
        setAlpha(0f);
    }

    public int state() {
        return state;
    }

    public static String name(int st) {
        return NAMES[st];
    }

    /** The screen turned back on (or the home is new): if the cable is already in, start from the loop. */
    public void markFresh() {
        fresh = true;
    }

    // ---- geometry: same photo and same wheel centers as CarView -------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) {
            float s = CarView.photoScale(w, h);
            float[] wc = CarView.wheelCenter(w, h);   // front wheel center of the photo, in view px
            float k = s * CarView.wheelDistance() / (CLIP_WHEEL_X2 - CLIP_WHEEL_X1);
            int cw = Math.round(CLIP_W * k), ch = Math.round(CLIP_H * k);
            int cx = Math.round(wc[0] - CLIP_WHEEL_X1 * k), cy = Math.round(wc[1] - CLIP_WHEEL_Y * k);
            dst.set(cx, cy, cx + cw, cy + ch);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        Bitmap b = front;
        if (b != null && !b.isRecycled()) c.drawBitmap(b, null, dst, paint);
    }

    // ---- phase logic ---------------------------------------------------------------------------

    /**
     * Car state (from the main thread, at every vehicle read).
     *
     * @param stopWhy reason why the animation must not run ("" = it may run): off, pack missing, READY...
     */
    public void update(Source src, boolean dayTheme, boolean plugged, boolean done, String stopWhy) {
        if (afterTest != null) return;   // during the test the car signals are ignored
        boolean themeChanged = dayTheme != day;
        day = dayTheme;
        source = src;
        lastPlugged = plugged;
        lastDone = done;
        if (!stopWhy.isEmpty()) {
            reason = stopWhy;
            // with the cable already in, on restart go straight to the loop; plug-in only for a new insertion
            fresh = plugged;
            if (!plugged) failedThisPlug = false;
            if (state != IDLE) toPhoto();
            else writeLog();
            return;
        }
        // after an error the animation stays still until the cable is removed (no repeated attempts)
        if (!plugged) failedThisPlug = false;
        if (failedThisPlug) {
            if (state != IDLE) toPhoto();
            writeLog();
            return;
        }
        if (preparing) return;   // frames being prepared: decide when they are ready
        reason = "";
        boolean wasFresh = fresh;
        fresh = false;
        switch (state) {
            case IDLE:
                if (!plugged) break;
                if (wasFresh) play(done ? DONE : LOOP);
                else play(PLUG_IN);
                break;
            case PLUG_IN:
                break;   // decided at the end of plug-in
            case LOOP:
                if (!plugged) play(UNPLUG);
                else if (done) finishAfterLoop = true;   // "finished" starts at the end of the loop round (same frame)
                else if (themeChanged) play(LOOP);
                break;
            case DONE:
                if (!plugged) play(UNPLUG);
                else if (!done) play(LOOP);
                else if (themeChanged) play(DONE);
                break;
            case UNPLUG:
                break;   // at the end the photo returns
        }
    }

    /** Screen off or launcher in background: back to the photo and the frames are freed. */
    public void pause() {
        afterTest = null;
        fresh = true;
        if (state != IDLE) {
            state = IDLE;
            finishAfterLoop = false;
            writeLog();
        }
        release();
    }

    /** End of a phase (from the drawing thread, posted to the main one). */
    private void onPhaseEnd(int phase) {
        if (afterTest != null) {
            testNext(phase);
            return;
        }
        switch (phase) {
            case PLUG_IN:
                if (!lastPlugged) play(UNPLUG);
                else play(lastDone ? DONE : LOOP);
                break;
            case LOOP:   // only with the loop no longer repeated: the "finished" phase comes
                finishAfterLoop = false;
                if (!lastPlugged) play(UNPLUG);
                else play(DONE);
                break;
            case DONE:
                break;   // the last frame stays still
            case UNPLUG:
                toPhoto();
                break;
        }
    }

    // ---- test from the settings ----------------------------------------------------------------

    private int testLoops;

    /** Test: plug-in → loop (3 rounds) → finished → unplug, then the photo again. */
    public void playTest(Source src, boolean dayTheme, Runnable done) {
        release();
        source = src;
        day = dayTheme;
        afterTest = done;
        testLoops = 0;
        failedThisPlug = false;
        play(PLUG_IN);
    }

    private void testNext(int phase) {
        switch (phase) {
            case PLUG_IN:
                play(LOOP);
                break;
            case LOOP:
                play(DONE);   // after the 3 loop rounds
                break;
            case DONE:
                main.postDelayed(() -> {
                    if (afterTest != null) play(UNPLUG);
                }, 2000);
                break;
            case UNPLUG:
                Runnable r = afterTest;
                afterTest = null;
                toPhoto();
                if (r != null) r.run();
                break;
        }
    }

    // ---- playback ------------------------------------------------------------------------------

    /** Playback errors since start (for the Function check). */
    public static int errors;

    private Player ensurePlayer() {
        if (player == null) player = new Player();
        return player;
    }

    /** Starts a phase. If the frames are not ready yet it prepares them (only once) and then starts. */
    private void play(int phase) {
        state = phase;
        writeLog();
        Source src = source;
        File clip = src == null ? null : src.clip(day, NAMES[phase]);
        if (clip == null) {
            fail("clip " + NAMES[phase] + " missing");
            return;
        }
        File[] frames = ChargeFrames.frames(clip);
        if (frames == null) {
            // first time with this pack: prepare the frames of all the theme's phases, then retry
            preparing = true;
            reason = "preparing frames";
            writeLog();
            final boolean d = day;
            final int g = ++gen;
            ensurePlayer().h.post(() -> {
                File[] all = new File[NAMES.length - 1];
                for (int i = 1; i < NAMES.length; i++) all[i - 1] = src.clip(d, NAMES[i]);
                String err = ChargeFrames.build(all);
                main.post(() -> {
                    preparing = false;
                    reason = "";
                    if (g != gen) return;   // everything changed meanwhile
                    if (err != null) fail("frames not prepared: " + err);
                    else if (state == phase) play(phase);
                });
            });
            return;
        }
        boolean fromPhoto = getVisibility() != VISIBLE || getAlpha() < 1f;
        setVisibility(VISIBLE);
        final Player p = ensurePlayer();
        final int g = ++gen;
        final long t0 = SystemClock.uptimeMillis();
        p.h.post(new Runnable() {
            int i = 0, slot = 0;
            boolean shown;

            @Override
            public void run() {
                if (g != gen) return;
                int n = frames.length;
                Bitmap b = decode(p, frames[i], slot);
                if (b == null) {
                    main.post(() -> {
                        if (g == gen) fail("frame " + (i + 1) + " of " + NAMES[phase] + " not readable");
                    });
                    return;
                }
                slot = (slot + 1) % p.buf.length;
                main.post(() -> {
                    if (g != gen) return;
                    front = b;
                    invalidate();
                    if (!shown && fromPhoto) {
                        animate().cancel();
                        animate().alpha(1f).setDuration(300).start();
                    } else if (!shown) setAlpha(1f);
                    shown = true;
                });
                i++;
                boolean looping = phase == LOOP && (afterTest != null ? testLoops < 2 : !finishAfterLoop);
                if (i >= n) {
                    if (looping) {
                        if (afterTest != null) testLoops++;
                        i = 1;   // the last frame of the loop equals the first: restart from the second
                    } else {
                        main.post(() -> {
                            if (g == gen) onPhaseEnd(phase);
                        });
                        return;
                    }
                }
                long next = t0 + (long) (++ticks) * FRAME_MS;
                p.h.postAtTime(this, Math.max(next, SystemClock.uptimeMillis() + 1));
            }

            private long ticks;
        });
    }

    /** Decodes a frame into the slot's bitmap (reused: no garbage at every frame). */
    private static Bitmap decode(Player p, File f, int slot) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap reuse = p.buf[slot];
            if (reuse != null && !reuse.isRecycled()) o.inBitmap = reuse;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b != null) p.buf[slot] = b;
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    private void fail(String why) {
        reason = why;
        errors++;
        Diag.put("charge.animation", "error: " + why);
        if (afterTest == null) failedThisPlug = true;
        Runnable r = afterTest;
        afterTest = null;
        toPhoto();
        if (r != null) r.run();
    }

    /** Back to the still image (0.3 s fade) and frees the frames. */
    private void toPhoto() {
        state = IDLE;
        finishAfterLoop = false;
        gen++;
        writeLog();
        if (getVisibility() != VISIBLE) {
            release();
            return;
        }
        final int g = gen;
        animate().cancel();
        animate().alpha(0f).setDuration(300).withEndAction(() -> {
            if (g == gen && state == IDLE) release();
        }).start();
    }

    /** Frees everything (clip not visible). */
    public void release() {
        gen++;
        animate().cancel();
        setAlpha(0f);
        setVisibility(GONE);
        front = null;
        Player p = player;
        player = null;
        if (p != null) {
            // the bitmaps are freed on their thread, after any frame in progress; the thread then exits
            p.h.post(() -> {
                for (int i = 0; i < p.buf.length; i++) {
                    if (p.buf[i] != null) p.buf[i].recycle();
                    p.buf[i] = null;
                }
                p.thread.quitSafely();
            });
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        afterTest = null;
        state = IDLE;
        release();
    }

    private String lastLog = "";

    private void writeLog() {
        String txt = "phase " + NAMES[state] + (reason.isEmpty() ? "" : " · " + reason) + (afterTest != null ? " · test" : "");
        if (!txt.equals(lastLog)) {
            lastLog = txt;
            phaseTxt = txt;
        }
    }

    /** Phase text for the log (HomeView composes it with the rest). */
    public static volatile String phaseTxt = "phase ferma";
}
