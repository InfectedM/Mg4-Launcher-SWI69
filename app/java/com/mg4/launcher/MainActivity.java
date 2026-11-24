package com.mg4.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mg4.launcher.apps.AppCatalog;
import com.mg4.launcher.apps.IconStyler;
import com.mg4.launcher.svc.Diag;
import com.mg4.launcher.svc.MediaClient;
import com.mg4.launcher.svc.NavClient;
import com.mg4.launcher.svc.Updater;
import com.mg4.launcher.svc.VehicleClient;
import com.mg4.launcher.ui.AppsView;
import com.mg4.launcher.ui.HomeView;
import com.mg4.launcher.ui.Pager;
import com.mg4.launcher.ui.Prefs;
import com.mg4.launcher.ui.SettingsView;
import com.mg4.launcher.ui.Theme;
import com.mg4.launcher.ui.Ui;

/** Launcher home: builds the interface and connects the car services. */
public final class MainActivity extends Activity implements HomeView.Host, AppsView.Host, SettingsView.Host {

    /** Full screen: hides the status bar and MG dock (the SAIC SystemUI follows the standard flags). */
    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

    private Prefs prefs;
    private AppCatalog catalog;
    private VehicleClient vehicle;
    private MediaClient media;
    private NavClient nav;
    private Updater updater;

    private FrameLayout root;
    private Theme theme;
    private HomeView home;
    private AppsView apps;
    private Pager pager;
    private com.mg4.launcher.svc.ConnClient conn;
    private SettingsView settings;
    private int lastUiMode;

    /** SWI69 build on another firmware (e.g. SWI68): only the warning screen, no connection to the services. */
    private boolean wrongFirmware;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        L.init(this, prefs.language());
        // full screen from the first draw: no window resizing at start
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
        if (com.mg4.launcher.svc.Firmware.wrong(this)) {
            wrongFirmware = true;
            root = new FrameLayout(this);
            setContentView(root);
            showWrongFirmware();
            root.postDelayed(saveDiag, 15000);
            return;
        }
        catalog = new AppCatalog(this);
        vehicle = new VehicleClient(this);
        conn = new com.mg4.launcher.svc.ConnClient(this);
        conn.setListener(st -> {
            if (home != null) home.onConn(st);
        });
        media = new MediaClient(this);
        nav = new NavClient(this);
        updater = new Updater(this);
        // 0.4.3: Feature check (read only) and automatic check 60 s after the start of a new version
        com.mg4.launcher.svc.SelfCheck.bind(vehicle, media);
        com.mg4.launcher.svc.SelfCheck.scheduleAfterUpdate(this, updater.currentVersion());
        // 1.0.4 (point F): charging animation pack for the chosen color (in the background, if enabled)
        android.os.Handler later = new android.os.Handler(android.os.Looper.getMainLooper());
        later.postDelayed(() -> {
            if (!isDestroyed() && prefs.chargeAnim())
                new Thread(() -> com.mg4.launcher.svc.ChargePack.ensure(this, Prefs.CAR_COLORS[prefs.carColorIndex()], false), "mg4-anim-start").start();
        }, 45_000);
        // 1.0.4 (point D): MG4 Store bundled in the launcher, installed only once if missing or older (30 s after start)
        later.postDelayed(() -> {
            if (!isDestroyed()) new Thread(() -> com.mg4.launcher.svc.StoreSetup.onStart(this), "mg4-store-start").start();
        }, 30_000);
        lastUiMode = getResources().getConfiguration().uiMode;

        root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener(this::applyInsets);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.requestApplyInsets();
        });
        setContentView(root);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(vis -> {
            // if the system shows the bars again (e.g. after a popup) hide them again
            if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) root.postDelayed(this::goFullscreen, 2500);
        });
        goFullscreen();
        build();
        // first start after an update: "What's new" popup (only once per version)
        root.postDelayed(this::showNewsIfUpdated, 1500);

        vehicle.setListener(s -> {
            if (home != null) home.onVehicle(s);
            // driving side read from the car only after the CarService starts: if it changes, the home is rebuilt
            if (home != null && Prefs.DRIVE_AUTO.equals(prefs.driveSide())) {
                Boolean known = com.mg4.launcher.svc.CarFeatures.knownRhd(this);
                if (known != null && known != home.isRightHand()) {
                    boolean wasSettings = settings != null;
                    build();
                    if (wasSettings) openSettings();
                }
            }
        });
        media.setListener(s -> {
            if (home != null) {
                home.onMedia(s);
                // SWI69: directions only come from CarPlay / Android Auto (phone data in the media state)
                home.onNav(nav.last());
            }
        });
        nav.setListener(s -> {
            if (home != null) home.onNav(s);
        });
        // 0.4.3: automatic theme same as SWI68 0.4.3 (chain of sources, locked uiMode ignored, 60 s confirmation)
        com.mg4.launcher.svc.DayNight.get(this).start(day -> {
            if (!Prefs.THEME_AUTO.equals(prefs.themeMode()) || theme == null || theme.day == day || isFinishing()) return;
            com.mg4.launcher.svc.Diag.put("theme.rebuilt", (day ? "day" : "night") + " (change confirmed)");
            IconStyler.clearCache();
            boolean wasSettings = settings != null;
            build();
            if (wasSettings) openSettings();
        });
        // 0.4.2 (point 6): after reversing, the previous app is reopened the way the launcher buttons open it
        com.mg4.launcher.svc.RetroReturn.setOpener(new com.mg4.launcher.svc.RetroReturn.Opener() {
            @Override
            public boolean available(String pkg) {
                if (pkg.startsWith("com.allgo.")) return media.last().deviceType != 0;
                return getPackageManager().getLaunchIntentForPackage(pkg) != null;
            }

            @Override
            public boolean reopen(String pkg) {
                if (pkg.startsWith("com.allgo.")) return media.openProjection();
                Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
                if (li == null) return false;
                try {
                    startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
        // connect to the services after the first draw: the home appears right away, already at its final size
        root.post(() -> {
            vehicle.start();
            conn.start();
            media.start();
            // SWI69: no MG navigator (navigation via CarPlay / Android Auto): the navigation service is not used
            com.mg4.launcher.svc.DongleWatch.get(this, media).start();
            // Windows (fork): automatic closing when leaving the car, always runs in the background
            com.mg4.launcher.svc.Windows.get(this).start();
            com.mg4.launcher.svc.EmbedCheck.log(this);
            // 0.4.2 (point 11a): at car power-on the last audio source actually used is resumed
            if (android.os.SystemClock.elapsedRealtime() < 5 * 60_000L) media.resumeLastSource("power-on");
        });
        // 0.4.2 (point 2): size of the SystemUI lights indicator window (if the space changes, the home is redrawn)
        root.postDelayed(probeLight, 12_000);
        // start at power-on: accessibility service, workshop expiry, default home reconfirmation
        com.mg4.launcher.svc.StartGuard.markProcess();
        com.mg4.launcher.svc.HomeMode.checkExpiry(getApplicationContext());
        com.mg4.launcher.svc.HomeMode.diag(getApplicationContext());
        new Thread(() -> {
            com.mg4.launcher.svc.BootAccessibility.ensureEnabled(getApplicationContext());
            com.mg4.launcher.svc.HomeLock.apply(getApplicationContext(), "launcher start");
            com.mg4.launcher.svc.StartGuard.resolvedHome(getApplicationContext());
            com.mg4.launcher.svc.StartGuard.reportKeyApps(getApplicationContext());
        }, "mg4-home").start();
        com.mg4.launcher.svc.CrashGuard.install(this);
        com.mg4.launcher.svc.BootAccessibility.watchList(this);
        // 0.9.6: 10 minutes without crashes → the lifesaver resets (safe start can resume at the next power-on)
        root.postDelayed(() -> com.mg4.launcher.svc.CrashGuard.reset(getApplicationContext()), 10 * 60_000L);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        // at car start, user apps appear only after the Android user is unlocked
        registerReceiver(packages, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // clock in the battery box: every minute and on time / timezone change
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockTick, tf);
        root.postDelayed(saveDiag, 15000);
        // 0.4.2 (point 12): first update and notice check 2 minutes after start (system stable), then every 6 hours
        root.postDelayed(autoCheck, 120_000);
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, sf);
        root.postDelayed(this::logoPacksTick, 20_000);
    }

    // ---- workshop mode: if the launcher is opened during workshop mode, ask whether to turn it off ----------

    private boolean workshopAsked;

    private void askWorkshop() {
        android.content.Context app = getApplicationContext();
        if (!com.mg4.launcher.svc.HomeMode.workshop(app)) {
            workshopAsked = false;
            return;
        }
        if (workshopAsked) return;
        workshopAsked = true;
        root.post(() -> com.mg4.launcher.ui.Confirm.show(root, theme, L.s(R.string.workshop_active_title),
                L.f(R.string.workshop_active_msg, com.mg4.launcher.svc.HomeMode.workshopDate(app)),
                L.s(R.string.btn_keep), L.s(R.string.btn_deactivate), () -> {
                    com.mg4.launcher.svc.HomeMode.end(app, "cancelled");
                    if (settings != null) {
                        closeSettings();
                        openSettings();
                    }
                }));
    }

    // ---- discreet popup for new updates ----------------------------------------------

    private static final long CHECK_EVERY = 6 * 60 * 60 * 1000L;   // every 6 hours
    private static final long POPUP_SHOW = 20_000;                  // then it hides by itself
    private View updPopup;

    private boolean firstAutoCheck = true;

    private final Runnable autoCheck = new Runnable() {
        @Override
        public void run() {
            if (!com.mg4.launcher.svc.HomeMode.workshop(getApplicationContext())) {
                final boolean first = firstAutoCheck;
                firstAutoCheck = false;
                updater.autoCheck(L.resolve(prefs.language()), new Updater.Auto() {
                    @Override
                    public void onNewVersion(Updater.Release r) {
                        showUpdatePopup(r);
                    }

                    @Override
                    public void onNotices(java.util.List<Updater.Notice> list) {
                        showNotices(list, first);
                    }
                });
            }
            root.postDelayed(this, CHECK_EVERY);
        }
    };

    // ---- 0.4.2 (point 12c): remote notices ----------------------------------------------------------------

    private final java.util.ArrayDeque<Updater.Notice> noticeQueue = new java.util.ArrayDeque<>();
    private boolean noticeOpen;
    private Updater.Release lastFound;

    private void showNotices(java.util.List<Updater.Notice> list, boolean startup) {
        android.content.SharedPreferences sp = prefs.raw();
        java.util.Set<String> seen = new java.util.HashSet<>(sp.getStringSet("noticesSeen", new java.util.HashSet<>()));
        java.util.Set<String> ok = sp.getStringSet("noticesConfirmed", new java.util.HashSet<>());
        for (Updater.Notice n : list) {
            boolean show = n.important ? !ok.contains(n.id) && (startup || !seen.contains(n.id)) : !seen.contains(n.id);
            if (show) noticeQueue.add(n);
        }
        nextNotice();
    }

    private void nextNotice() {
        if (noticeOpen || root == null || theme == null) return;
        Updater.Notice n = noticeQueue.poll();
        if (n == null) return;
        noticeOpen = true;
        android.content.SharedPreferences sp = prefs.raw();
        java.util.Set<String> seen = new java.util.HashSet<>(sp.getStringSet("noticesSeen", new java.util.HashSet<>()));
        seen.add(n.id);
        sp.edit().putStringSet("noticesSeen", seen).apply();
        Runnable confirm = () -> {
            java.util.Set<String> ok = new java.util.HashSet<>(prefs.raw().getStringSet("noticesConfirmed", new java.util.HashSet<>()));
            ok.add(n.id);
            prefs.raw().edit().putStringSet("noticesConfirmed", ok).apply();
            noticeDiag();
            noticeOpen = false;
            root.post(this::nextNotice);
        };
        String text = n.text(L.resolve(prefs.language()));
        if (n.updateButton) {
            com.mg4.launcher.ui.Confirm.show(root, theme, L.s(R.string.notice_title), text, L.s(R.string.btn_ok), L.s(R.string.upd_now), () -> {
                confirm.run();
                updateNow(lastFound);
            }, confirm);
        } else {
            com.mg4.launcher.ui.Confirm.notice(root, theme, L.s(R.string.notice_title), "", new String[]{text}, L.s(R.string.btn_ok), confirm);
        }
        noticeDiag();
    }

    private void noticeDiag() {
        android.content.SharedPreferences sp = prefs.raw();
        updater.noticeShown = "shown " + sp.getStringSet("noticesSeen", new java.util.HashSet<>())
                + " · confirmed " + sp.getStringSet("noticesConfirmed", new java.util.HashSet<>());
        Diag.put("notices.state", "read " + updater.noticeIds + " · " + updater.noticeShown);
    }

    /** Update button outside the settings: opens the System section and installs (or checks, if the version is not there yet). */
    private void updateNow(Updater.Release r) {
        SettingsView.openAt(SettingsView.SEC_SYSTEM);
        closeSettings();
        openSettings();
        if (r != null) updater.install(r, prefs.githubToken());
        else updater.check(prefs.updateRepo(), prefs.githubToken());
    }

    /** Screen off/wake: after more than 5 minutes off, the last source is resumed (point 11a). */
    private long screenOffAt = -1;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOffAt = android.os.SystemClock.elapsedRealtime();
                if (home != null) home.pauseChargeAnim();   // 0.4.4: charging animation stopped while the screen is off
            } else if (screenOffAt > 0 && android.os.SystemClock.elapsedRealtime() - screenOffAt > 5 * 60_000L) {
                screenOffAt = -1;
                if (home != null) home.onScreenOn();
                if (media != null) media.resumeLastSource("wake-up after power-off");
            }
        }
    };

    private final Runnable probeLight = () -> new Thread(() -> {
        if (com.mg4.launcher.svc.CarFeatures.probeSystemLight(getApplicationContext())) {
            root.post(() -> {
                if (isFinishing()) return;
                boolean wasSettings = settings != null;
                build();
                if (wasSettings) openSettings();
            });
        }
    }, "mg4-indicator").start();

    private final Runnable hidePopup = () -> hideUpdatePopup();

    private void showUpdatePopup(Updater.Release r) {
        if (r != null) lastFound = r;
        if (r == null || updPopup != null || !prefs.updatePopup()) return;
        if (r.tag.equals(prefs.dismissedUpdate()) || updater.isBusy()) return;
        Theme t = theme;
        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(24), dp(14), dp(14), dp(14));
        android.graphics.drawable.GradientDrawable bgd = Ui.round(t.panel, dp(22));
        bgd.setStroke(dp(1), t.track);
        card.setBackground(bgd);
        card.setElevation(dp(14));
        card.setClickable(true);   // touches on the popup do not reach the home below

        LinearLayout txt = Ui.column(this);
        txt.addView(Ui.text(this, L.s(R.string.upd_popup_title), 18, t.text, Ui.MEDIUM));
        txt.addView(Ui.text(this, L.f(R.string.upd_popup_text, r.tag), 15, t.sub, Ui.REGULAR));
        card.addView(txt, Ui.margins(Ui.lp(-2, -2), 0, 0, dp(28), 0));

        card.addView(popupButton(L.s(R.string.upd_later), t.chip, t.text, v -> {
            prefs.setDismissedUpdate(r.tag);
            hideUpdatePopup();
        }), Ui.margins(Ui.lp(-2, dp(48)), 0, 0, dp(10), 0));
        card.addView(popupButton(L.s(R.string.upd_now), t.accent, t.onAccent, v -> {
            hideUpdatePopup();
            updateNow(r);
        }), Ui.lp(-2, dp(48)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(20);
        root.addView(card, lp);
        updPopup = card;
        card.setAlpha(0f);
        card.setTranslationY(-dp(30));
        card.animate().alpha(1f).translationY(0).setDuration(280).start();
        root.removeCallbacks(hidePopup);
        root.postDelayed(hidePopup, POPUP_SHOW);
    }

    private void hideUpdatePopup() {
        root.removeCallbacks(hidePopup);
        View v = updPopup;
        updPopup = null;
        if (v == null) return;
        v.animate().alpha(0f).translationY(-dp(30)).setDuration(220).withEndAction(() -> root.removeView(v)).start();
    }

    private TextView popupButton(String text, int bg, int fg, View.OnClickListener l) {
        TextView b = Ui.text(this, text, 16, fg, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setBackground(Ui.pressable(this, bg, 14, theme));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ---- wrong firmware ------------------------------------------------------------------

    private void showWrongFirmware() {
        theme = Theme.resolve(this, prefs);
        Theme t = theme;
        root.removeAllViews();
        root.setBackgroundColor(t.bg);
        boolean swi68 = com.mg4.launcher.svc.Firmware.kind(this) == com.mg4.launcher.svc.Firmware.SWI68;
        LinearLayout card = Ui.column(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(t.panel, dp(28)));
        card.setPadding(dp(56), dp(44), dp(56), dp(40));
        card.addView(Ui.icon(this, R.drawable.ic_info, t.text, 56));
        TextView title = Ui.text(this, L.s(swi68 ? R.string.fw_swi68_title : R.string.fw_other_title), 36, t.text, Ui.MEDIUM);
        title.setGravity(Gravity.CENTER);
        card.addView(title, Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(20), 0, 0));
        TextView msg = Ui.text(this, L.s(swi68 ? R.string.fw_swi68_msg : R.string.fw_other_msg), 21, t.sub, Ui.REGULAR);
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(0, 1.2f);
        card.addView(msg, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, dp(16), 0, 0));
        TextView link = Ui.text(this, com.mg4.launcher.svc.Firmware.SWI68_URL.replace("https://", ""), 22, t.text, Ui.MEDIUM);
        link.setGravity(Gravity.CENTER);
        link.setPadding(dp(24), dp(14), dp(24), dp(14));
        link.setBackground(Ui.round(t.chip, dp(16)));
        link.setTextIsSelectable(true);
        card.addView(link, Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(26), 0, 0));
        TextView open = Ui.text(this, L.s(R.string.fw_open_original), 20, t.panel, Ui.MEDIUM);
        open.setGravity(Gravity.CENTER);
        open.setPadding(dp(30), 0, dp(30), 0);
        open.setBackground(Ui.pressable(this, t.text, 16, t));
        open.setClickable(true);
        open.setOnClickListener(v -> openAnyOtherHome());
        card.addView(open, Ui.margins(new LinearLayout.LayoutParams(-2, dp(64)), 0, dp(30), 0, 0));
        root.addView(card, new FrameLayout.LayoutParams(dp(1100), -2, Gravity.CENTER));
    }

    /** Opens the car's original home (any firmware): the first home that is not this launcher. */
    private void openAnyOtherHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)
                        || com.mg4.launcher.svc.HomeDefault.isProvision(ri.activityInfo.packageName)) continue;
                startActivity(new Intent(home).setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        } catch (Exception e) {
            Diag.error("home original", e.toString());
        }
    }

    // ---- "What's new" popup after an update -------------------------------------------------

    /** Only once per version (fixes included), in the chosen language; not after the first install. */
    private void showNewsIfUpdated() {
        String ver = updater.currentVersion();
        if (ver.equals(prefs.newsShownFor())) return;
        boolean updated = false;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            updated = pi.lastUpdateTime > pi.firstInstallTime + 1000;
        } catch (Exception ignored) {
        }
        prefs.setNewsShownFor(ver);
        if (!updated) return;
        showNews(ver);
    }

    /** What's new in each version (texts in all languages in strings.xml). */
    private static int[] newsFor(String ver) {
        switch (ver) {
            case "0.4.11":  // fork InfectedM
                return new int[]{R.string.news_v0411_1, R.string.news_v0411_2};
            case "0.4.10":  // fork InfectedM
                return new int[]{R.string.news_v0410_1, R.string.news_v0410_2};
            case "0.4.9":   // fork InfectedM
                return new int[]{R.string.news_v049_1, R.string.news_v049_2};
            case "0.4.8":   // fork InfectedM
                return new int[]{R.string.news_v048_1, R.string.news_v048_2};
            case "0.4.7":   // fork InfectedM
                return new int[]{R.string.news_v047_1, R.string.news_v047_2, R.string.news_v047_3, R.string.news_v047_4};
            case "0.4.6":   // fork InfectedM
                return new int[]{R.string.news_v046_1, R.string.news_v046_2, R.string.news_v046_3, R.string.news_v046_4};
            case "0.4.5":
                return new int[]{R.string.news_v045_1, R.string.news_v045_2};
            case "0.4.4":
                return new int[]{R.string.news_v104_1, R.string.news_v104_2, R.string.news_v104_3, R.string.news_v104_4, R.string.news_v104_5, R.string.news_v104_6};
            case "0.4.3":
                return new int[]{R.string.news_v043_1, R.string.news_v043_2, R.string.news_v043_3, R.string.news_v043_4, R.string.news_v043_5};
            case "0.4.2":
                return new int[]{R.string.news_v042_1, R.string.news_v042_2, R.string.news_v042_3, R.string.news_v042_4, R.string.news_v042_5};
            case "0.4.1":
                return new int[]{R.string.news_v041_1, R.string.news_v041_2};
            case "0.4.0":
                return new int[]{R.string.news_v040_1, R.string.news_v040_2, R.string.news_v040_3, R.string.news_v040_4};
            case "0.3.11":
                return new int[]{R.string.news_v098_1, R.string.news_v098_2, R.string.news_v098_3};
            case "0.3.10":
                return new int[]{R.string.news_v0310_1, R.string.news_v0310_2, R.string.news_v0310_3};
            case "0.3.9":
                return new int[]{R.string.newsh_1, R.string.newsh_2, R.string.newsh_3, R.string.newsh_5, R.string.newsh_6};
            case "0.3.8":
                return new int[]{R.string.newsg_7, R.string.newsg_1, R.string.newsg_2, R.string.newsg_3, R.string.newsg_4, R.string.newsg_5, R.string.newsg_6};
            case "0.3.7":
                return new int[]{R.string.newsf_1};
            case "0.3.6":
                return new int[]{R.string.newsv_1, R.string.newsv_2, R.string.newsv_3, R.string.newsv_4};
            default:
                return new int[]{R.string.news_1, R.string.news_2, R.string.news_3, R.string.news_4, R.string.news_5,
                        R.string.news_6, R.string.news_7};
        }
    }

    private void showNews(String ver) {
        int[] ids = newsFor(ver);
        String[] items = new String[ids.length];
        for (int i = 0; i < ids.length; i++) items[i] = L.s(ids[i]);
        com.mg4.launcher.ui.Confirm.notice(root, theme, L.f(R.string.news_title, ver), L.s(R.string.news_sub), items,
                L.s(R.string.btn_ok), null);
    }

    /** Every 15 s saves the diagnostics to a file: adb pull /sdcard/Android/data/com.mg4.launcher/files/diagnostics.txt */
    private final Runnable saveDiag = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> Diag.save(MainActivity.this), "mg4-diag").start();
            root.postDelayed(this, 15000);
        }
    };

    /**
     * Full screen: the content always fills all 1920×720 px. The system bars, if they reappear
     * for a moment (dragging from the edge), overlay without shifting the layout.
     */
    private WindowInsets applyInsets(View v, WindowInsets in) {
        v.setPadding(0, 0, 0, 0);
        Diag.put("window", v.getWidth() + "×" + v.getHeight() + " · system insets " + in.getSystemWindowInsetLeft() + ","
                + in.getSystemWindowInsetTop() + "," + in.getSystemWindowInsetRight() + "," + in.getSystemWindowInsetBottom());
        return in.consumeSystemWindowInsets();
    }

    private void goFullscreen() {
        View d = getWindow().getDecorView();
        if (d.getSystemUiVisibility() != IMMERSIVE) d.setSystemUiVisibility(IMMERSIVE);
    }

    /** 0.4.0: true while the launcher is in front (onResume..onPause), to know whether Home comes "from inside". */
    private boolean inFront;

    @Override
    protected void onPause() {
        inFront = false;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        if (wrongFirmware) {
            goFullscreen();
            return;
        }
        // safe start: back in the launcher after deliberately opening the MG home → its screen is disabled again
        new Thread(() -> {
            Context app = getApplicationContext();
            if (com.mg4.launcher.svc.HomeLock.option(app) && com.mg4.launcher.svc.HomeMode.active(app)
                    && !com.mg4.launcher.svc.HomeLock.mgDisabled(app)) com.mg4.launcher.svc.HomeLock.apply(app, "back in the launcher");
        }, "mg4-homelock").start();
        askWorkshop();
        goFullscreen();
        com.mg4.launcher.svc.RetroReturn.noteBroughtBy("launcher resumed (MainActivity.onResume)");
        // 0.4.3 (point 6): coming back from the Radio app the station list is re-read immediately
        if (media != null) media.refreshRadioLists("back in the launcher");
        // 0.4.3 (point 7): phone state re-read ("Phone favourites" box), even if the service notifications do not arrive
        com.mg4.launcher.svc.PhoneClient.get(this).refresh();
        // 0.4.0: if day/night changed while the launcher was behind and the notification did not arrive, rebuild here
        if (theme != null && root != null && com.mg4.launcher.ui.Theme.wouldBeDay(this, prefs) != theme.day) {
            com.mg4.launcher.svc.Diag.put("theme.resumed", "change caught up on return to the launcher");
            IconStyler.clearCache();
            build();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    private void build() {
        theme = Theme.resolve(this, prefs);
        Ui.K = prefs.uiScaleFactor();
        root.setBackgroundColor(theme.bg);
        root.removeAllViews();
        root.removeCallbacks(hidePopup);
        updPopup = null;
        int keepPage = pager != null ? pager.page() : 0;
        pager = new Pager(this);
        home = new HomeView(this, theme, prefs, this, vehicle, media, nav);
        apps = new AppsView(this, theme, catalog, prefs, this);
        pager.addView(home);
        pager.addView(apps);
        pager.setListener(p -> {
            if (p == 1) apps.reload();
        });
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        settings = null;
        pager.show(keepPage, false);
        home.onVehicle(vehicle.last());
        home.onConn(conn.last());
        home.onMedia(media.last());
        home.onNav(nav.last());
        getWindow().setStatusBarColor(theme.bg);
        root.requestApplyInsets();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (wrongFirmware) return;
        // the service connections stay active; only the periodic reads are resumed here
        vehicle.resume();
        conn.resume();
        if (home != null) home.updateClock();
        media.resume();
    }

    @Override
    protected void onStop() {
        if (home != null) home.pauseChargeAnim();   // 1.0.4: animation stopped with the launcher in the background
        super.onStop();
        if (wrongFirmware) return;
        vehicle.pause();
        conn.pause();
        media.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        root.removeCallbacks(saveDiag);
        if (wrongFirmware) return;
        root.removeCallbacks(autoCheck);
        root.removeCallbacks(hidePopup);
        root.removeCallbacks(probeLight);
        com.mg4.launcher.svc.DayNight.get(this).stop();
        com.mg4.launcher.svc.RetroReturn.setOpener(null);
        try {
            unregisterReceiver(screen);
        } catch (Exception ignored) {
        }
        vehicle.stop();
        conn.stop();
        try {
            unregisterReceiver(clockTick);
        } catch (Exception ignored) {
        }
        media.stop();
        try {
            unregisterReceiver(packages);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (wrongFirmware) return;
        // Home key: always goes back to the main screen
        // 0.4.0 (point 3): with the launcher already in front it also closes every open popup (Climate, Comfort, Drive,
        // confirmations, lists, notices)
        if (inFront) closePopups("Home key");
        // 0.4.2 (point 6): user's Home key (not the automatic opening): no return to the app after reversing
        if (intent == null || !intent.getBooleanExtra(com.mg4.launcher.svc.HomeMode.EXTRA_AUTO, false)) {
            com.mg4.launcher.svc.RetroReturn.noteHomePressed();
        }
        closeSettings();
        closeApps();
    }

    /** Removes from the screen everything that is not the home/apps or settings: group popups, confirmations, notices. */
    private void closePopups(String why) {
        int n = 0;
        if (home != null && home.closePopup()) n++;
        if (root != null) {
            for (int i = root.getChildCount() - 1; i >= 0; i--) {
                View v = root.getChildAt(i);
                if (v == pager || v == settings) continue;
                root.removeViewAt(i);
                n++;
            }
        }
        com.mg4.launcher.svc.Diag.put("home.popupsClosed", n + " (" + why + ")");
    }

    @Override
    public void onBackPressed() {
        if (wrongFirmware) return;
        if (settings != null) closeSettings();
        else if (pager != null && pager.page() != 0) closeApps();
        // otherwise the launcher stays where it is
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (wrongFirmware) {
            if (L.init(this, prefs.language())) showWrongFirmware();
            return;
        }
        // system language changed: with "Automatic" the launcher follows it right away
        if (L.init(this, prefs.language())) {
            catalog.refreshLabels();
            build();
            return;
        }
        if (cfg.uiMode != lastUiMode) {
            lastUiMode = cfg.uiMode;
            // the change is applied after 60 s of confirmation (DayNight calls the rebuild)
            com.mg4.launcher.svc.DayNight.get(this).onConfigurationChanged();
        }
    }

    private final BroadcastReceiver clockTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (home != null) home.updateClock();
            logoPacksTick();
        }
    };

    // ---- radio logo packs: download when the network arrives, zip on USB stick to install ----------

    private int logoTicks;

    private void logoPacksTick() {
        com.mg4.launcher.svc.LogoPacks lp = com.mg4.launcher.svc.LogoPacks.get(this);
        lp.tick();
        if (logoTicks++ % 2 != 0) return;   // the USB stick is checked every 2 minutes
        new Thread(() -> {
            java.util.List<java.io.File> zips = lp.usbPacksToOffer();
            for (java.io.File f : zips) root.post(() -> offerUsbPack(lp, f));
        }, "mg4-usb-logos").start();
    }

    private void offerUsbPack(com.mg4.launcher.svc.LogoPacks lp, java.io.File f) {
        if (isFinishing()) return;
        String cc = f.getName().substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        com.mg4.launcher.ui.Confirm.show(root, theme, L.s(R.string.logos_usb_title),
                L.f(R.string.logos_usb_msg, com.mg4.launcher.svc.LogoPacks.countryName(cc), f.getParent()),
                L.s(R.string.btn_cancel), L.s(R.string.btn_install), () -> lp.installUsb(f, true));
        lp.installUsb(f, false);   // offered only once, even if cancelled
    }

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            IconStyler.clearCache();
            if (apps != null) apps.reload();
        }
    };

    // --- HomeView.Host ------------------------------------------------------------------------

    @Override
    public void openApps() {
        if (pager != null) pager.show(1, true);
    }

    @Override
    public void openSettings() {
        if (settings == null) {
            settings = new SettingsView(this, theme, prefs, this);
            root.addView(settings, new FrameLayout.LayoutParams(-1, -1));
            if (updPopup != null) updPopup.bringToFront();
        }
    }

    @Override
    public void launch(String pkg, String cls) {
        catalog.launchComponent(pkg, cls);
    }

    /** SWI69 Energy app (charging, V2L discharge, consumption): no tabs openable from outside, the main one is opened. */
    @Override
    public void openEnergy(String module) {
        Intent i = catalog.resolveLaunch(com.mg4.launcher.apps.MgApps.ENERGY_PKG, com.mg4.launcher.apps.MgApps.ENERGY_CLS);
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            com.mg4.launcher.svc.Diag.error("energy " + module, e.toString());
        }
    }

    /**
     * Cameras (360° or rear camera), like the button of the original SWI69 home: EvsService with "state" = 1.
     * If the service is missing, the camera screen is opened.
     */
    @Override
    public boolean openCamera() {
        // like the original SWI69 R45 home: configuration 3 = AVM, 5 = cluster AVM, others = EVS
        int cfg = vehicle != null ? vehicle.last().cameraConfig : Integer.MIN_VALUE;
        if (cfg == 3 || cfg == 5) {
            String pkg = cfg == 3 ? "com.saicmotor.avm" : "com.saicmotor.hmi.aroundview";
            String cls = cfg == 3 ? "com.saicmotor.avm.service.SaicMotorAvmService" : "com.saicmotor.hmi.aroundview.AvmService";
            try {
                if (startService(new Intent("com.saicmotor.avm.START_ENTER").setComponent(new ComponentName(pkg, cls))) != null) {
                    Diag.put("cameras", "opened (" + pkg + ")");
                    return true;
                }
            } catch (Exception e) {
                Diag.error("cameras", e.toString());
            }
        }
        try {
            Intent i = new Intent().setComponent(new ComponentName("com.saicmotor.evs", "com.saicmotor.evs.service.EvsService")).putExtra("state", 1);
            if (startService(i) != null) {
                Diag.put("cameras", "opened (EvsService)");
                return true;
            }
        } catch (Exception e) {
            Diag.error("cameras", e.toString());
        }
        return catalog.launchComponent("com.saicmotor.evs", "com.saicmotor.evs.view.EvsActivity");
    }

    @Override
    public boolean canOpenCamera() {
        // car without cameras according to the configuration: no 360° (like the original home)
        if (vehicle != null && Boolean.FALSE.equals(VehicleClient.cameraPresent(vehicle.last().cameraConfig))) return false;
        try {
            getPackageManager().getPackageInfo("com.saicmotor.evs", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean canLaunch(String pkg, String cls) {
        return catalog.canLaunch(pkg, cls);
    }

    @Override
    public FrameLayout overlay() {
        return root;
    }

    @Override
    public void launchNavigator() {
        launchNavigator(prefs.navApp());
    }

    /** Opens the chosen navigator: MG, the phone's one (CarPlay/Android Auto) or an installed app. */
    @Override
    public void launchNavigator(String which) {
        boolean phone = media.last().deviceType != 0;
        if (Prefs.NAV_PHONE.equals(which) || (Prefs.NAV_AUTO.equals(which) && phone)) {
            if (phone) {
                media.openProjection();
                media.openRemoteNavigation();
                return;
            }
        } else if (!Prefs.NAV_AUTO.equals(which) && !Prefs.NAV_MG.equals(which)) {
            // 0.4.4 (issue #27): chosen navigation app; if it is gone, continue as "Automatic", without crashing
            Intent li = null;
            try {
                li = getPackageManager().getLaunchIntentForPackage(which);
            } catch (Exception ignored) {
            }
            if (li != null) {
                try {
                    startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    Diag.put("nav.chosenApp", which + " · opened");
                    return;
                } catch (Exception e) {
                    Diag.error("navigator.app", e.toString());
                }
            }
            Diag.put("nav.chosenApp", which + " · unavailable: CarPlay/Android Auto or the first installed navigator");
        }
        // no MG navigator on the SWI69: without a phone the first installed navigator is opened, if any
        if (phone) {
            media.openProjection();
            media.openRemoteNavigation();
            return;
        }
        java.util.List<AppCatalog.App> others = catalog.otherNavApps("");
        if (!others.isEmpty()) {
            try {
                startActivity(others.get(0).intent());
            } catch (Exception e) {
                Diag.error("navigator.app", e.toString());
            }
        }
    }

    @Override
    public java.util.List<AppCatalog.App> otherNavApps() {
        return catalog.otherNavApps(nav.last().mapPackage);
    }

    /**
     * 1.0.4 (point F5): charging animation test. Uses the chosen color's pack if present, otherwise a zip
     * animation-<color>.zip in the root folder of the USB stick. The settings close and the test
     * runs on the home's car: plug in → loop (3 rounds) → finished → unplug.
     */
    @Override
    public void testChargeAnimation() {
        String color = Prefs.CAR_COLORS[prefs.carColorIndex()];
        new Thread(() -> {
            java.io.File dir;
            String slug;
            if (com.mg4.launcher.svc.ChargePack.complete(this, color)) {
                dir = com.mg4.launcher.svc.ChargePack.dir(this, color);
                slug = com.mg4.launcher.svc.ChargePack.slug(color);
                Diag.put("charge.test", "package " + color + " installed");
            } else {
                Object[] usb = com.mg4.launcher.svc.ChargePack.loadFromUsb(this);
                if (usb == null) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this, L.s(R.string.anim_test_missing), android.widget.Toast.LENGTH_LONG).show());
                    Diag.put("charge.test", "no package: neither downloaded nor animation-<color>.zip on the USB stick");
                    return;
                }
                dir = (java.io.File) usb[0];
                slug = (String) usb[1];
            }
            // 1.0.4: the first time the frames are prepared from the clips (about 20–40 s), then the test starts right away
            java.io.File probe = com.mg4.launcher.svc.ChargePack.clipIn(dir, slug, true, "loop");
            if (probe != null && com.mg4.launcher.svc.ChargeFrames.frames(probe) == null) {
                runOnUiThread(() -> android.widget.Toast.makeText(this, L.s(R.string.anim_preparing), android.widget.Toast.LENGTH_LONG).show());
                String err = com.mg4.launcher.svc.ChargePack.buildFramesIn(dir, slug);
                if (err != null) {
                    Diag.put("charge.test", "frames not prepared: " + err);
                    runOnUiThread(() -> android.widget.Toast.makeText(this, L.f(R.string.anim_prepare_failed, err), android.widget.Toast.LENGTH_LONG).show());
                    return;
                }
            }
            runOnUiThread(() -> {
                if (settings != null) closeSettings();
                if (home != null) home.testChargeAnim(dir, slug, () -> Diag.put("charge.test", "finished · " + slug));
            });
        }, "mg4-test-animation").start();
    }

    @Override
    public java.util.List<AppCatalog.App> navCandidateApps() {
        return catalog.navCandidateApps(nav.last().mapPackage);
    }

    // --- AppsView.Host ------------------------------------------------------------------------

    @Override
    public FrameLayout overlayRoot() {
        return root;
    }

    @Override
    public void closeApps() {
        if (pager != null) pager.show(0, true);
    }

    @Override
    public String navPackageHint() {
        return nav.last().mapPackage;
    }

    // --- SettingsView.Host --------------------------------------------------------------------

    @Override
    public void closeSettings() {
        if (settings != null) {
            root.removeView(settings);
            settings = null;
        }
    }

    @Override
    public void settingsChanged(boolean rebuild) {
        if (rebuild) {
            IconStyler.clearCache();
            build();
            openSettings();
        } else if (home != null) {
            home.applyCarLook();
            home.refreshNav();
            home.onVehicle(vehicle.last());
        }
    }

    @Override
    public void languageChanged() {
        L.init(this, prefs.language());
        catalog.refreshLabels();
        IconStyler.clearCache();
        build();
        openSettings();
    }

    @Override
    public Updater updater() {
        return updater;
    }

    @Override
    public java.util.List<AppCatalog.App> allLaunchableApps() {
        return catalog.userApps();
    }

    @Override
    public void openOriginalHome() {
        try {
            com.mg4.launcher.svc.HomeLock.release(this, "MG home opened on purpose");
            // opened on purpose: for a while the power-on check must not bring back our launcher
            com.mg4.launcher.svc.BootWatcher.userOpenedOriginal(this);
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(com.mg4.launcher.apps.MgApps.HOME_PKG, com.mg4.launcher.apps.MgApps.HOME_CLS))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Diag.error("home original", e.toString());
        }
    }
}
