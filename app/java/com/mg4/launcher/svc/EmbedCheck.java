package com.mg4.launcher.svc;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * Check only, for the windowed map (PIP), no feature: writes to the diagnostics whether the screens of the
 * navigator and of CarPlay/Android Auto can be embedded (resizable, allowEmbedded) on this firmware.
 */
public final class EmbedCheck {
    private static final String[] PACKAGES = {"com.telenav.app.arp", "com.allgo.carplay.service", "com.allgo.app.androidauto", "com.allgo.rui"};
    /** ActivityInfo.FLAG_ALLOW_EMBEDDED (hidden). */
    private static final int FLAG_ALLOW_EMBEDDED = 0x80000000;

    private EmbedCheck() {}

    public static void log(Context c) {
        new Thread(() -> {
            PackageManager pm = c.getPackageManager();
            Diag.put("pip.secondaryDisplays", pm.hasSystemFeature("android.software.activities_on_secondary_displays") ? "yes" : "no");
            Diag.put("pip.embedPermission", pm.checkPermission("android.permission.ACTIVITY_EMBEDDING", c.getPackageName())
                    == PackageManager.PERMISSION_GRANTED ? "granted" : "not granted");
            for (String p : PACKAGES) {
                StringBuilder sb = new StringBuilder();
                try {
                    PackageInfo pi = pm.getPackageInfo(p, PackageManager.GET_ACTIVITIES);
                    if (pi.activities != null) for (ActivityInfo a : pi.activities) {
                        if (!a.exported && !p.equals("com.telenav.app.arp")) continue;
                        int resize = -1;
                        try {
                            resize = ActivityInfo.class.getField("resizeMode").getInt(a);
                        } catch (Throwable ignored) {
                        }
                        sb.append(sb.length() == 0 ? "" : " | ").append(a.name.substring(a.name.lastIndexOf('.') + 1))
                                .append(" resize=").append(resize)
                                .append((a.flags & FLAG_ALLOW_EMBEDDED) != 0 ? " embedded" : "")
                                .append(a.exported ? "" : " private");
                        if (sb.length() > 700) break;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    sb.append("not installed");
                } catch (Throwable e) {
                    sb.append(e.getClass().getSimpleName());
                }
                Diag.put("pip." + p, sb.length() == 0 ? "no exported activity" : sb.toString());
            }
        }, "mg4-pip-check").start();
    }
}
