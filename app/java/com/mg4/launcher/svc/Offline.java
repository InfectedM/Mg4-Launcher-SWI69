package com.mg4.launcher.svc;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * 1.0.0: car without internet. Log upload, update check and logos share a single growing pause
 * (1, 2, 4, 8, 16, 30, 60 min) after a network error (DNS, connection, timeout, SSL), instead of each retrying on
 * its own. The diagnostics hold a single "rete.offline" line instead of one error per attempt.
 */
public final class Offline {
    private static final long MIN_MS = 60_000L, MAX_MS = 60 * 60_000L;

    private static long until, step = MIN_MS, since;
    private static int failures;
    private static String lastError = "";
    private static final LinkedHashSet<String> who = new LinkedHashSet<>();

    private Offline() {}

    /** True if it is a network error (not a server or data error). */
    public static boolean isNetError(Throwable e) {
        // a rejected certificate (e.g. car clock behind) is not "offline": it stays an error with its cause
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.security.cert.CertificateException || t instanceof java.security.cert.CertPathValidatorException)
                return false;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.SocketException || t instanceof javax.net.ssl.SSLException) return true;
        }
        return false;
    }

    /** True if going online can be attempted now (no pause in progress). */
    public static synchronized boolean canTry() {
        return System.currentTimeMillis() >= until;
    }

    /** Minutes left until the end of the pause (0 = no pause). */
    public static synchronized long waitMs() {
        return Math.max(0, until - System.currentTimeMillis());
    }

    /**
     * Call when a network operation fails. If it is a network error it extends the shared pause, updates the
     * diagnostics line and returns true (the caller writes no other errors); otherwise returns false.
     */
    public static synchronized boolean failed(String what, Throwable e) {
        if (!isNetError(e)) return false;
        long now = System.currentTimeMillis();
        if (failures == 0) since = now;
        failures++;
        who.add(what);
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        lastError = e.getClass().getSimpleName() + (root != e ? " ← " + root.getClass().getSimpleName() : "");
        if (now >= until) {
            until = now + step;
            step = Math.min(MAX_MS, step * 2);
        }
        write();
        return true;
    }

    /** Call when a network operation succeeds: end of the pause. */
    public static synchronized void ok() {
        if (failures == 0) return;
        Diag.put("network.offline", "no · back at " + hhmm(System.currentTimeMillis()) + " after " + failures
                + " failed attempts (since " + hhmm(since) + ")");
        failures = 0;
        until = 0;
        step = MIN_MS;
        who.clear();
    }

    private static void write() {
        Diag.put("network.offline", "yes since " + hhmm(since) + " · " + failures + " failed attempts (" + String.join(", ", who)
                + ") · last error " + lastError + " · next attempt at " + hhmm(until));
    }

    private static String hhmm(long t) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(t));
    }
}
