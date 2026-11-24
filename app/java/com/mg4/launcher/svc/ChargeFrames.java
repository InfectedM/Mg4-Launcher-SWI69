package com.mg4.launcher.svc;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 1.0.4: frames of the charging clips, extracted only once from the MP4s and saved as JPEGs next to the clips
 * (folder "fotogrammi/&lt;clip name&gt;/0001.jpg…", plus the "pronto" file with the number of frames).
 * <p>
 * Why: in the car, playback with MediaPlayer on a TextureView stayed stuck on the first frame (black screen,
 * "aggancio" phase stuck, log of 15/11). With the frames ready in advance the animation is drawn by a normal
 * view, with no video decoder on screen: no black surfaces, and errors show up in the log.
 * <p>
 * Extraction uses MediaMetadataRetriever (the same one as the gallery thumbnails) in chunks of 8 frames, on a
 * worker thread. About 290 frames per theme, ~18–20 MB in total for one color. Log "ricarica.fotogrammi".
 * <p>
 * Fork: frames are saved scaled down to {@link #SCALE} (1312×400 → 902×275). In the car the clip is drawn at most at
 * ~898×274 (720 dp panel, density 160), so the look does not change; during playback the 3 ARGB buffers go from
 * ~6 MB to ~2.8 MB and JPEG decoding at 30 fps is halved. New folder "fotogrammi2": the old one (full-size frames,
 * 25–30 MB) is deleted automatically on the first preparation.
 */
public final class ChargeFrames {
    private static final String DONE = "pronto", DIR = "fotogrammi2", OLD_DIR = "fotogrammi";
    /** 1312×400 → 902×275: exact ratio (the height also stays an integer). */
    private static final float SCALE = 0.6875f;
    private static final int CHUNK = 8, JPEG_Q = 88;

    private ChargeFrames() {}

    /** Frames folder of a clip. */
    public static File dir(File clip) {
        String n = clip.getName();
        int dot = n.lastIndexOf('.');
        return new File(new File(clip.getParentFile(), DIR), dot > 0 ? n.substring(0, dot) : n);
    }

    /** Ready frames of the clip (in order), or null if not extracted yet. */
    public static File[] frames(File clip) {
        if (clip == null) return null;
        File d = dir(clip);
        File done = new File(d, DONE);
        if (!done.isFile()) return null;
        int n;
        try {
            n = Integer.parseInt(new String(read(done)).trim());
        } catch (Exception e) {
            return null;
        }
        if (n <= 0) return null;
        File[] out = new File[n];
        for (int i = 0; i < n; i++) {
            out[i] = new File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", i + 1));
            if (!out[i].isFile()) return null;
        }
        return out;
    }

    /** true if all clips in the list have their frames ready. */
    public static boolean ready(File... clips) {
        for (File c : clips) if (c != null && frames(c) == null) return false;
        return true;
    }

    private static final Object LOCK = new Object();

    /**
     * Extracts the frames of the clips that do not have them yet. From a worker thread (blocking). Returns null if
     * everything went fine, otherwise the reason.
     */
    public static String build(File... clips) {
        synchronized (LOCK) {
            long t0 = android.os.SystemClock.elapsedRealtime();
            int total = 0, made = 0;
            for (File c : clips) {
                if (c == null) continue;
                File old = new File(c.getParentFile(), OLD_DIR);   // fork: old full-size frames
                if (old.isDirectory()) ChargePack.deleteTree(old);
                if (frames(c) != null) continue;
                String err = buildOne(c);
                if (err != null) {
                    Diag.put("charge.frames", "error " + c.getName() + ": " + err);
                    return err;
                }
                made++;
                File[] f = frames(c);
                total += f == null ? 0 : f.length;
            }
            if (made > 0) Diag.put("charge.frames", "ready: " + made + " clip, " + total + " frames in "
                    + (android.os.SystemClock.elapsedRealtime() - t0) / 1000 + " s · " + lastNote);
            return null;
        }
    }

    private static String buildOne(File clip) {
        File d = dir(clip);
        ChargePack.deleteTree(d);
        if (!d.mkdirs() && !d.isDirectory()) return "folder not created";
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(clip.getAbsolutePath());
            int n = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            if (n <= 0) {
                long ms = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                n = (int) Math.round(ms * 30 / 1000.0);
            }
            if (n <= 0) return "unknown frame count";
            int written = 0, missing = 0;
            String chunkErr = null;
            boolean chunks = true;   // getFramesAtIndex: if it fails once, it is not retried
            File prev = null;
            for (int i = 0; i < n; i += CHUNK) {
                int cnt = Math.min(CHUNK, n - i);
                List<Bitmap> list = null;
                if (chunks) {
                    try {
                        list = r.getFramesAtIndex(i, cnt);
                    } catch (Throwable e) {
                        chunkErr = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
                    }
                    if (list == null || list.size() != cnt) {
                        chunks = false;
                        if (list != null) for (Bitmap b : list) if (b != null) b.recycle();
                        list = null;
                    }
                }
                for (int j = 0; j < cnt; j++) {
                    int idx = i + j;
                    Bitmap b = list != null ? list.get(j) : null;
                    if (b == null) {   // one frame at a time: by index, then by time (in the middle of the frame)
                        try {
                            b = r.getFrameAtIndex(idx);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (b == null) {
                        try {
                            b = r.getFrameAtTime(idx * 1_000_000L / 30 + 16_000, MediaMetadataRetriever.OPTION_CLOSEST);
                        } catch (Throwable ignored) {
                        }
                    }
                    written++;
                    File out = new File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", written));
                    if (b == null) {
                        // unreadable frame (happens near the end of the clips): repeat the previous one
                        missing++;
                        if (prev == null || !copy(prev, out)) return "frame " + written + " not read";
                        continue;
                    }
                    int tw = Math.round(b.getWidth() * SCALE), th = Math.round(b.getHeight() * SCALE);
                    Bitmap sb = tw > 0 && th > 0 && tw < b.getWidth() ? Bitmap.createScaledBitmap(b, tw, th, true) : b;
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        if (!sb.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, fo)) return "frame " + written + " not saved";
                    } finally {
                        if (sb != b) sb.recycle();
                        b.recycle();
                    }
                    prev = out;
                }
            }
            if (missing * 10 > n) return missing + " frames of " + n + " not read";
            lastNote = (chunks ? "in chunks" : "one at a time" + (chunkErr != null ? " (chunks: " + chunkErr + ")" : ""))
                    + (missing > 0 ? ", " + missing + " repeated" : "");
            if (written <= 0) return "no frames";
            try (FileOutputStream fo = new FileOutputStream(new File(d, DONE))) {
                fo.write(String.valueOf(written).getBytes());
            }
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String lastNote = "";

    private static boolean copy(File from, File to) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int parse(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] read(File f) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(64, f.length())];
            int n = in.read(b);
            return n <= 0 ? new byte[0] : java.util.Arrays.copyOf(b, n);
        }
    }
}
