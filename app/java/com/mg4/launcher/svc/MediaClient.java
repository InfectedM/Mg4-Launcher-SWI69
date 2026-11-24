package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.mg4.launcher.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Car audio sources, SWI69 version (EH32 Color Radio), like the original SWI69 home:
 *  - USB and online music: MediaBrowserService of the com.saicmotor.media app
 *  - Bluetooth: A2dpMediaBrowserService of com.android.bluetooth
 *  - FM/DAB radio: RadioMBService (session: title, text, logo, play/pause, search) +
 *    IRadioManager of com.saicmotor.radio (frequency, direct tuning, DAB stations)
 *  - CarPlay / Android Auto (allgo): com.allgo.rui + MediaBrowser for the metadata, as on the SWI68
 * Radio favorites are kept by the launcher (the Radio app saves them in a private database).
 */
public final class MediaClient {
    // --- launcher sources ------------------------------------------------------------------
    public static final int SRC_RADIO = 1, SRC_USB = 2, SRC_ONLINE = 3, SRC_BT = 4, SRC_CARPLAY = 5, SRC_AAUTO = 6;
    /** 0.4.3 (point 5): second USB port (type C), Usb2MediaBrowserService of the Media app. */
    public static final int SRC_USB2 = 7;

    private static volatile int lastActiveSrc;

    /** Source playing right now (for the steering-wheel button log). */
    public static String currentSourceName() {
        return lastActiveSrc == 0 ? "none" : sourceName(lastActiveSrc);
    }

    public static String sourceName(Context c, int s) {
        return sourceName(s);
    }

    public static String sourceName(int s) {
        switch (s) {
            case SRC_RADIO: return "RADIO";
            case SRC_USB: return "USB MUSIC";
            case SRC_USB2: return "USB 2 MUSIC";
            case SRC_ONLINE: return "ONLINE MUSIC";
            case SRC_BT: return "BLUETOOTH";
            case SRC_CARPLAY: return "CARPLAY";
            case SRC_AAUTO: return "ANDROID AUTO";
            default: return "MEDIA";
        }
    }

    // --- MediaBrowserService (SWI69) ----------------------------------------------------------
    private static final String MEDIA_PKG = "com.saicmotor.media";
    private static final String RADIO_PKG = "com.saicmotor.radio";

    // --- radio: IRadioManager (com.android.car.radio.service) ----------------------------------
    private static final String RADIO_ACTION = "com.saicmotor.IRadioService";
    private static final String RAD = "com.android.car.radio.service.IRadioManager";
    private static final int RAD_TUNE = 1, RAD_SCAN = 8, RAD_ADD_CB = 14, RAD_CURRENT = 16, RAD_ADD_DAB_CB = 37,
            RAD_DAB_TUNE = 41, RAD_DAB_QUERY_LIST = 46;
    private static final String RAD_CB = "com.android.car.radio.service.IRadioCallback";
    private static final String DAB_CB = "com.android.car.radio.service.IDabCallback";
    private static final String ACTION_NEXT_BAND = "__ACTION_NEXT_SOURCE__";
    private static final String ACTION_PREV_BAND = "__ACTION_PREV_SOURCE__";
    /** SWI69 Radio app bands (order of FeatureUtil.getRadioBands: DAB, FM, AM). */
    public static final int BAND_AM = 0, BAND_FM = 1, BAND_DAB = 4;

    // --- CarPlay / Android Auto ---------------------------------------------------------------
    private static final String RUI = "com.allgo.rui.IRemoteUIService";
    private static final int RUI_LAUNCH_APP = 8, RUI_ACTIVE_DEVICE = 15, RUI_GET_STATUS = 17, RUI_MEDIA_KEY = 18;
    public static final int DEV_CARPLAY = 1, DEV_AAUTO = 2;

    /** Radio station (FM / DAB favorites). DAB needs all the fields of RadioDabStation. */
    public static final class Station {
        public int band, khz, eid, index, pty, type, channel;
        public long sid;
        public String name = "", ensemble = "";
        public String key = "";
        public Bitmap logo;

        /** Short name to show: RDS/DAB name or frequency. */
        public String label() {
            if (!name.isEmpty()) return name;
            if (band == BAND_AM) return khz + " kHz";
            return String.format(com.mg4.launcher.L.locale(), "%.1f", khz / 1000f);
        }

        /** Second line in the list: frequency (FM) or multiplex (DAB). */
        public String detail() {
            if (band == BAND_DAB) return ensemble.isEmpty() ? "DAB+" : "DAB+ · " + ensemble;
            if (band == BAND_AM) return khz + " kHz";
            return String.format(com.mg4.launcher.L.locale(), "%.1f MHz", khz / 1000f);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("band", band).put("khz", khz).put("sid", sid).put("eid", eid)
                    .put("index", index).put("pty", pty).put("type", type).put("channel", channel)
                    .put("name", name).put("ensemble", ensemble);
        }

        static Station fromJson(JSONObject o) {
            Station s = new Station();
            s.band = o.optInt("band", BAND_FM);
            s.khz = o.optInt("khz");
            s.sid = o.optLong("sid");
            s.eid = o.optInt("eid");
            s.index = o.optInt("index");
            s.pty = o.optInt("pty");
            s.type = o.optInt("type");
            s.channel = o.optInt("channel");
            s.name = o.optString("name", "");
            s.ensemble = o.optString("ensemble", "");
            return s;
        }
    }

    /** Media state shown by the launcher. */
    public static final class State {
        public int source;                 // active or chosen source
        public boolean playing;
        public String title = "";
        public String subtitle = "";
        public long positionMs = -1, durationMs = -1;
        public int radioBand = BAND_FM;
        public String radioFreq = "";      // e.g. "101,5"
        public String radioUnit = "MHz";
        public String stationKey = "";     // station on air (to highlight it among the favorites)
        public boolean stationIsFavorite;
        public Bitmap logo;                // logo of the station on air, if available
        public Bitmap art;                 // 0.4.2: cover art from the session (Bluetooth/USB/online), if the phone sends it
        public List<Station> favorites = new ArrayList<>();
        public List<Station> stations = new ArrayList<>();   // list for the band on air (FM or DAB)
        public boolean scanning;                              // station search in progress
        public int radioKhz;                                  // FM frequency on air (to highlight it)
        public int deviceType;             // 0 none, 1 CarPlay, 2 Android Auto
        public String deviceName = "";
        public final List<Integer> sources = new ArrayList<>();
        // navigation directions from CarPlay/AA
        public boolean tbtActive;
        public String tbtDistance = "", tbtRoad = "";
    }

    public interface Listener {
        void onMediaState(State s);
    }

    /** A MediaBrowserService with its controller and automatic reconnection. */
    private final class Browser {
        final String name;
        final ComponentName cn;
        volatile MediaBrowser browser;
        volatile MediaController controller;
        final MediaController.Callback callback;
        /**
         * Fork: cached metadata, updated by the service itself (onMetadataChanged). Before, the 1 s poll requested it over
         * binder every second and each reply brought a new DAB cover/logo (~400 KB). With the same object, the cover is
         * the same Bitmap and the screen does not re-upload the texture. Safety net: re-read after 30 s.
         */
        volatile MediaMetadata meta;
        volatile long metaAt;
        final MediaController.Callback metaCb = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata md) {
                meta = md;
                metaAt = SystemClock.uptimeMillis();
            }

            @Override
            public void onSessionDestroyed() {
                meta = null;
                metaAt = 0;
            }
        };

        /** Current metadata (only on the poll thread: the Bundle cannot be unparceled on two threads). */
        MediaMetadata meta() {
            MediaController c = controller;
            if (c == null) return null;
            long now = SystemClock.uptimeMillis();
            if (metaAt == 0 || now - metaAt > 30_000) {
                meta = c.getMetadata();
                metaAt = now;
            }
            return meta;
        }

        private void dropController() {
            MediaController old = controller;
            controller = null;
            meta = null;
            metaAt = 0;
            if (old != null) {
                try {
                    old.unregisterCallback(metaCb);
                } catch (Exception ignored) {
                }
            }
        }

        Browser(String name, String pkg, String cls, MediaController.Callback cb) {
            this.name = name;
            this.cn = new ComponentName(pkg, cls);
            this.callback = cb;
        }

        void connect() {
            main.post(() -> {
                if (browser != null) return;
                browser = new MediaBrowser(ctx, cn, new MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        try {
                            MediaController c = new MediaController(ctx, browser.getSessionToken());
                            dropController();
                            // metaCb BEFORE the callback that runs the poll: callbacks arrive in registration order, on the same
                            // handler, so the poll already finds the new metadata
                            c.registerCallback(metaCb, main);
                            c.registerCallback(callback != null ? callback : refresh, main);
                            meta = null;
                            metaAt = 0;
                            controller = c;
                            Diag.put("media." + name, "connected");
                            post(MediaClient.this::poll);
                        } catch (Exception e) {
                            Diag.error("media." + name, e.toString());
                        }
                    }

                    @Override
                    public void onConnectionSuspended() {
                        dropController();
                        Diag.put("media." + name, "suspended");
                        retry(5000);
                    }

                    @Override
                    public void onConnectionFailed() {
                        dropController();
                        Diag.put("media." + name, "unavailable");
                        retry(15000);
                    }
                }, null);
                try {
                    browser.connect();
                } catch (Exception e) {
                    Diag.error("media." + name, e.toString());
                    retry(15000);
                }
            });
        }

        void retry(long ms) {
            main.post(() -> {
                MediaBrowser b = browser;
                browser = null;
                if (b != null) {
                    try {
                        b.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                if (started) main.postDelayed(this::connect, ms);
            });
        }

        void disconnect() {
            main.post(() -> {
                MediaBrowser b = browser;
                browser = null;
                dropController();
                if (b != null) {
                    try {
                        b.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        boolean ready() {
            return controller != null;
        }

        boolean isPlaying() {
            MediaController c = controller;
            PlaybackState ps = c == null ? null : c.getPlaybackState();
            return ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        }

        /** Session state for the logs: "playing", "paused", "stopped"… */
        String stateName() {
            MediaController c = controller;
            PlaybackState ps = c == null ? null : c.getPlaybackState();
            if (c == null) return "session not connected";
            if (ps == null) return "no state";
            switch (ps.getState()) {
                case PlaybackState.STATE_PLAYING: return "playing";
                case PlaybackState.STATE_PAUSED: return "paused";
                case PlaybackState.STATE_STOPPED: return "stopped";
                case PlaybackState.STATE_BUFFERING: return "buffering";
                case PlaybackState.STATE_NONE: return "none";
                default: return "state " + ps.getState();
            }
        }

        boolean isPaused() {
            MediaController c = controller;
            PlaybackState ps = c == null ? null : c.getPlaybackState();
            return ps != null && ps.getState() == PlaybackState.STATE_PAUSED;
        }

        MediaController.TransportControls controls() {
            MediaController c = controller;
            return c == null ? null : c.getTransportControls();
        }
    }

    private final Context ctx;
    private final ServiceLink radio, rui;
    private final Browser usb, usb2, online, bt, radioMbs, remote;
    private final HandlerThread thread = new HandlerThread("mg4-media");
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences sp;
    private Listener listener;
    private volatile State last = new State();
    private volatile int chosen;               // source chosen by the user
    private volatile Bundle tbt;
    private final StationLogos logos;
    private volatile List<Station> favorites = new ArrayList<>();
    // radio: data from the IRadioManager callbacks
    private volatile Station fmCurrent, dabCurrent;
    private volatile int bandFromCallback = -1;
    private volatile List<Station> dabList = new ArrayList<>();
    private volatile List<Station> fmList = new ArrayList<>();
    private volatile boolean scanning;
    private volatile long scanUntil;
    private volatile String dabText = "";
    private boolean started, running;

    public MediaClient(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        radio = new ServiceLink(ctx, "radio", ServiceLink.byAction(RADIO_PKG, RADIO_ACTION));
        radio.setOnConnected(() -> post(this::registerRadioCallbacks));
        rui = new ServiceLink(ctx, "carplay", ServiceLink.byComponent("com.allgo.rui", "com.allgo.rui.RemoteUIService", null));
        usb = new Browser("usb", MEDIA_PKG, "com.saicmotor.media.mbs.Usb1MediaBrowserService", null);
        usb2 = new Browser("usb2", MEDIA_PKG, "com.saicmotor.media.mbs.Usb2MediaBrowserService", null);
        online = new Browser("online", MEDIA_PKG, "com.saicmotor.media.mbs.OnlineMediaBrowserService", null);
        bt = new Browser("bt", "com.android.bluetooth", "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService", null);
        radioMbs = new Browser("radio.session", RADIO_PKG, "com.saicmotor.radio.service.RadioMBService", null);
        remote = new Browser("carplay.media", "com.allgo.remoteui.mediabrowserservice",
                "com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserService", remoteCallback);
        logos = new StationLogos(ctx);
        logos.setOnUpdate(this::refreshLogosSoon);
        loadFavorites();
        fmList = loadList("radio_fm_elenco");
        dabList = loadList("radio_dab_elenco");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Call in progress on CarPlay/Android Auto (Allgo state: com.allgo.extra.CallStatus other than 0). */
    public boolean remoteCallActive() {
        IBinder r = rui.binder();
        if (r == null) return false;
        try {
            android.os.Bundle b = Rpc.call(r, RUI, RUI_GET_STATUS, Rpc.NONE,
                    p -> Rpc.readNullable(p, q -> android.os.Bundle.CREATOR.createFromParcel(q)));
            return b != null && b.getInt("com.allgo.extra.CallStatus", 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    public State last() {
        return last;
    }

    public void start() {
        if (started) return;
        started = true;
        running = true;
        if (bg == null) {
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        radio.start();
        rui.start();
        usb.connect();
        usb2.connect();
        online.connect();
        bt.connect();
        radioMbs.connect();
        remote.connect();
        bg.post(loop);
        WheelKeys.start(ctx);
        WheelKeys.setMedia(wheel);
    }

    /** Pauses the reads (launcher not visible) without disconnecting from the services. */
    public void pause() {
        running = false;
        if (bg != null) bg.removeCallbacks(loop);
    }

    public void resume() {
        if (bg == null || running) return;
        running = true;
        bg.post(loop);
    }

    public void stop() {
        running = false;
        started = false;
        if (bg != null) bg.removeCallbacks(loop);
        radio.stop();
        rui.stop();
        usb.disconnect();
        usb2.disconnect();
        online.disconnect();
        bt.disconnect();
        radioMbs.disconnect();
        remote.disconnect();
    }

    private void post(Runnable r) {
        if (bg != null) bg.post(r);
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            poll();
            bg.postDelayed(this, 1000);
        }
    };

    /** Generic callback: every state/metadata change updates the UI immediately. */
    private final MediaController.Callback refresh = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            post(MediaClient.this::poll);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            post(MediaClient.this::poll);
        }
    };

    // ------------------------------------------------------------------------------------------
    // State reading
    // ------------------------------------------------------------------------------------------

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String join(String a, String b) {
        a = nz(a);
        b = nz(b);
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " · " + b;
    }

    private Browser browserFor(int src) {
        switch (src) {
            case SRC_USB: return usb;
            case SRC_USB2: return usb2;
            case SRC_ONLINE: return online;
            case SRC_BT: return bt;
            case SRC_RADIO: return radioMbs;
            case SRC_CARPLAY:
            case SRC_AAUTO: return remote;
            default: return null;
        }
    }

    /** Fallback: CarPlay / Android Auto from the active media session of the AllGo apps (0 if none). */
    private int projectionFromSessions() {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(null)) {
                String p = mc.getPackageName();
                if ("com.allgo.app.androidauto".equals(p)) return DEV_AAUTO;
                if ("com.allgo.carplay.service".equals(p)) return DEV_CARPLAY;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private void poll() {
        State s = new State();

        // CarPlay / Android Auto connected?
        // 1.0.4 (point E5): main source is the AllGo service (getActiveRemoteDevice, code 15: null = no
        // session; type 1 CarPlay, 2 Android Auto). If it does not answer, the media sessions of the AllGo apps.
        IBinder r = rui.binder();
        String projSrc = "AllGo (code 15)";
        boolean ruiOk = false;
        if (r != null) {
            try {
                int[] dev = Rpc.call(r, RUI, RUI_ACTIVE_DEVICE, Rpc.NONE, p -> Rpc.readNullable(p, q -> {
                    int session = q.readInt();
                    String name = q.readString();
                    q.readString();
                    q.readString();
                    int type = q.readInt();
                    s.deviceName = nz(name);
                    return new int[]{type, session};
                }));
                s.deviceType = dev == null ? 0 : dev[0];
                ruiOk = true;
                // no phone name in the log (personal data)
                Diag.put("carplay.device", dev == null ? "none" : ("type " + dev[0] + ", session " + dev[1]));
            } catch (Exception e) {
                Diag.error("carplay", e.toString());
            }
        }
        if (!ruiOk) {
            projSrc = "AllGo media sessions (AllGo service not responding)";
            s.deviceType = projectionFromSessions();
        }
        Diag.putQuiet("projection", "source " + projSrc + " · " + (s.deviceType == DEV_AAUTO ? "AA" : s.deviceType == DEV_CARPLAY ? "CarPlay" : "none"));

        // available sources
        s.sources.add(SRC_RADIO);
        s.sources.add(SRC_USB);
        // 0.4.3 (point 5): USB 2 only if there is music on the second port (state written by the Media app) or if it is playing/paused
        boolean usb2Shown = usb2Available() || usb2.isPlaying() || usb2.isPaused();
        if (usb2Shown) s.sources.add(SRC_USB2);
        usbBoth = usb2Shown;
        s.sources.add(SRC_BT);
        if (online.ready() && onlineAvailable()) s.sources.add(SRC_ONLINE);
        onlinePossible = s.sources.contains(SRC_ONLINE);
        if (s.deviceType == DEV_CARPLAY) s.sources.add(SRC_CARPLAY);
        if (s.deviceType == DEV_AAUTO) s.sources.add(SRC_AAUTO);

        // source playing (the one whose session is PLAYING)
        int active = 0;
        if (radioMbs.isPlaying()) active = SRC_RADIO;
        else if (usb.isPlaying()) active = SRC_USB;
        else if (usb2.isPlaying()) active = SRC_USB2;
        else if (bt.isPlaying()) active = SRC_BT;
        else if (online.isPlaying()) active = SRC_ONLINE;
        else if (remote.isPlaying() && s.deviceType != 0) active = s.deviceType == DEV_AAUTO ? SRC_AAUTO : SRC_CARPLAY;
        Diag.put("media.active", sourceName(ctx, active));
        lastActiveSrc = active;
        String u2 = "USB 1 " + usb.stateName() + " · USB 2 " + usb2.stateName() + " (music on the port: " + (usb2Available() ? "yes" : "no") + ")";
        if (!u2.equals(usbLog)) {
            usbLog = u2;
            Diag.put("media.usb2", u2);
        }
        rememberSource(active, s.deviceType);
        if (s.deviceType != 0) Diag.put("carplay.playback", remote.stateName());
        // when the phone (CarPlay / Android Auto) starts playing it becomes the active source: the buttons control it
        if ((active == SRC_CARPLAY || active == SRC_AAUTO) && prevActive != active && chosen != active) {
            chosen = active;
            Diag.put("carplay.source", sourceName(ctx, active) + " playing: becomes the active source");
        }
        checkHijack(active);

        // by default the playing source is shown, otherwise the radio
        int shown = chosen != 0 ? chosen : (active != 0 ? active : SRC_RADIO);
        if (!s.sources.contains(shown)) shown = active != 0 && s.sources.contains(active) ? active : SRC_RADIO;
        s.source = shown;

        try {
            if (shown == SRC_RADIO) fillRadio(s);
            else fillSession(s, shown);
        } catch (Exception e) {
            Diag.error("media." + sourceName(ctx, shown), e.toString());
        }

        Bundle t = tbt;
        if (t != null && s.deviceType != 0) {
            s.tbtActive = t.getBoolean("IS_TBT_ACTIVE", false);
            s.tbtDistance = (nz(String.valueOf(t.get("DIST_TO_NEXT_JUNCTION"))) + " "
                    + nz(String.valueOf(t.get("DIST_TO_NEXT_JUNCTION_UNIT")))).replace("null", "").trim();
            s.tbtRoad = nz(t.getString("ROAD_AFTER_JUNCTION"));
        }

        last = s;
        main.post(() -> {
            if (listener != null) listener.onMediaState(s);
        });
    }

    /** USB, Bluetooth, online, CarPlay/AA: everything from the media session. */
    private void fillSession(State s, int src) {
        Browser b = browserFor(src);
        MediaController c = b == null ? null : b.controller;
        if (c != null) {
            MediaMetadata m = b.meta();   // fork: cached (see Browser.meta)
            PlaybackState ps = c.getPlaybackState();
            if (m != null) {
                s.title = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
                s.subtitle = join(m.getString(MediaMetadata.METADATA_KEY_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM));
                s.durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                s.art = art;
            }
            if (src == SRC_BT) {
                // 0.4.2 (point 11e): what the phone sends via AVRCP
                btMetaSource = m == null ? "no data from the phone" : "Bluetooth A2DP session (AVRCP)";
                Diag.putQuiet("media.bt.info", "source " + btMetaSource + " · title " + (s.title.isEmpty() ? "no" : "yes") + " · artist "
                        + (m != null && !nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST)).isEmpty() ? "yes" : "no")
                        + " · cover " + (s.art != null ? "yes" : "no"));
            }
            if (ps != null) {
                s.playing = ps.getState() == PlaybackState.STATE_PLAYING;
                long pos = ps.getPosition();
                if (s.playing && ps.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                }
                s.positionMs = pos >= 0 ? Math.min(pos, s.durationMs > 0 ? s.durationMs : pos) : -1;
                Diag.put("media." + b.name + ".state", ps.getState());
            }
        } else if (b != null) {
            Diag.put("media." + b.name + ".state", "not connected");
        }
        switch (src) {
            case SRC_USB:
            case SRC_USB2:
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(R.string.no_track);
                if (s.subtitle.isEmpty()) s.subtitle = com.mg4.launcher.L.s(R.string.plug_usb);
                break;
            case SRC_BT:
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(R.string.bt_music);
                if (s.subtitle.isEmpty()) s.subtitle = com.mg4.launcher.L.s(R.string.bt_start);
                break;
            case SRC_ONLINE:
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(R.string.online_music);
                break;
            case SRC_CARPLAY:
            case SRC_AAUTO:
                if (s.title.isEmpty()) s.title = src == SRC_CARPLAY ? "CarPlay" : "Android Auto";
                if (s.subtitle.isEmpty()) s.subtitle = s.deviceName.isEmpty() ? com.mg4.launcher.L.s(R.string.from_phone) : s.deviceName;
                break;
            default:
                break;
        }
    }

    /** Radio: RadioMBService session (name, text, DAB logo, state) + frequency from IRadioManager. */
    private void fillRadio(State s) {
        MediaController c = radioMbs.controller;
        MediaMetadata m = c == null ? null : radioMbs.meta();   // fork: cached
        PlaybackState ps = c == null ? null : c.getPlaybackState();
        String author = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_AUTHOR));
        String title = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        Bitmap art = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        s.playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;

        // current FM/AM station (frequency) from the radio service
        IBinder b = radio.binder();
        if (b != null) {
            try {
                Station cur = Rpc.call(b, RAD, RAD_CURRENT, Rpc.NONE, p -> Rpc.readNullable(p, MediaClient::readRadioStation));
                if (cur != null) fmCurrent = cur;
            } catch (Exception e) {
                if (retryable(e)) Diag.putQuiet("radio.current.state", "not ready yet (" + e.getClass().getSimpleName() + "): retrying next round");
                else Diag.error("radio.current", e.toString());
            }
        }

        // the radio session sets "DAB" as the author for DAB; for FM/AM no author
        int band;
        if ("DAB".equalsIgnoreCase(author)) band = BAND_DAB;
        else if (m != null) band = fmCurrent != null && fmCurrent.band == BAND_AM ? BAND_AM : BAND_FM;
        else band = bandFromCallback >= 0 ? bandFromCallback : BAND_FM;
        s.radioBand = band;

        if (band == BAND_DAB) {
            Station d = currentDab(title);
            String name = !title.isEmpty() ? title : d != null ? d.name : "";
            s.title = name;
            s.radioFreq = "DAB";
            s.radioUnit = d != null ? d.ensemble : "";
            s.subtitle = !artist.isEmpty() ? artist : nz(dabText);
            s.stationKey = StationLogos.key(BAND_DAB, d != null ? d.sid : 0, 0, name);
            if (art != null) logos.put(s.stationKey, art);
            s.logo = art != null ? art : logos.get(s.stationKey, name);
        } else {
            Station f = fmCurrent;
            int khz = f != null ? f.khz : 0;
            String name = !title.isEmpty() ? title : f != null ? f.name : "";
            if (band == BAND_AM) {
                s.radioFreq = khz > 0 ? String.valueOf(khz) : "";
                s.radioUnit = "kHz";
            } else {
                s.radioFreq = khz > 0 ? String.format(com.mg4.launcher.L.locale(), "%.1f", khz / 1000f) : "";
                s.radioUnit = "MHz";
            }
            // for FM the session uses the RDS name as the title; if it is just the frequency it is not a name
            s.title = name.matches("[0-9.,\\s]*(MHz|kHz)?") ? "" : name;
            s.subtitle = artist;
            s.stationKey = StationLogos.key(band, 0, khz, s.title);
            s.logo = logos.get(s.stationKey, s.title);
            s.radioKhz = khz;
            if (band == BAND_FM && khz > 0) rememberFm(khz, s.title);
        }
        // list of the stations of the band on air. 0.4.3 (point 6): if it is empty the band's favorites are shown
        List<Station> list = band == BAND_DAB ? dabList : fmList;
        String listSrc = band == BAND_DAB ? sp.getString("radio_dab_fonte", "DAB stations listened to")
                : sp.getString("radio_fm_fonte", "stations listened to");
        if (list.isEmpty()) {
            List<Station> favBand = new ArrayList<>();
            for (Station st : favorites) if ((st.band == BAND_DAB) == (band == BAND_DAB)) favBand.add(st);
            if (!favBand.isEmpty()) {
                list = favBand;
                listSrc = "favorites (service list empty)";
            }
        }
        String el = "source " + listSrc + " · " + (band == BAND_DAB ? "DAB " : "FM ") + list.size() + " stations";
        if (!el.equals(radioListLog)) {
            radioListLog = el;
            Diag.put("radio.list", el + " · updated: " + radioListWhy);
        }
        for (Station st : list) {
            if (st.key.isEmpty()) st.key = StationLogos.key(st.band, st.sid, st.khz, st.name);
            if (st.logo == null) st.logo = logos.getThumb(st.key, st.name);   // fork: thumbnail (list at 40–60 px)
        }
        s.stations = list;
        if (scanning && SystemClock.uptimeMillis() > scanUntil) scanning = false;
        s.scanning = scanning;
        Diag.put("radio.current", "band " + band + ", " + s.radioFreq + " " + s.radioUnit + ", " + s.title
                + (ps != null ? " (state " + ps.getState() + ")" : ""));

        List<Station> fav = favorites;
        for (Station st : fav) {
            if (st.logo == null) st.logo = logos.getThumb(st.key, st.name);   // fork: thumbnail
            if (st.key.equals(s.stationKey)) s.stationIsFavorite = true;
        }
        s.favorites = fav;
        if (s.title.isEmpty()) s.title = s.radioFreq.isEmpty() ? com.mg4.launcher.L.s(R.string.src_radio) : s.radioFreq + " " + s.radioUnit;
    }

    /** DAB station on air: from the callback, or looked up by name in the DAB list. */
    private Station currentDab(String name) {
        Station d = dabCurrent;
        if (d != null && (name.isEmpty() || d.name.equalsIgnoreCase(name))) return d;
        for (Station x : dabList) if (x.name.equalsIgnoreCase(name)) return x;
        return d;
    }

    /** RadioStation: channel (kHz), subchannel, band, RadioRds (Parcelable or null). */
    private static Station readRadioStation(Parcel p) {
        Station st = new Station();
        st.khz = p.readInt();
        p.readInt();                               // subchannel
        st.band = p.readInt();
        String cls = p.readString();               // writeParcelable: class name, null if absent
        if (cls != null) {
            st.name = nz(p.readString());          // programService (RDS name)
            p.readString();                        // artist
            p.readString();                        // title
        }
        return st;
    }

    /** RadioDabStation (writeToParcel order). */
    private static Station readDabStation(Parcel p) {
        Station st = new Station();
        st.band = BAND_DAB;
        st.pty = p.readInt();
        st.name = nz(p.readString());
        st.sid = p.readLong();
        st.ensemble = nz(p.readString());
        st.eid = p.readInt();
        st.index = p.readInt();
        st.type = p.readInt();
        st.channel = p.readInt();
        return st;
    }

    private static void writeDabStation(Parcel p, Station st) {
        p.writeInt(1);                             // not null
        p.writeInt(st.pty);
        p.writeString(st.name);
        p.writeLong(st.sid);
        p.writeString(st.ensemble);
        p.writeInt(st.eid);
        p.writeInt(st.index);
        p.writeInt(st.type);
        p.writeInt(st.channel);
    }

    // ---- callbacks from the radio service (station, band, DAB) ------------------------------

    private final Binder radioCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(RAD_CB);
                return true;
            }
            try {
                data.enforceInterface(RAD_CB);
                if (code == 1) {                   // onRadioStationChanged(RadioStation)
                    if (data.readInt() != 0) fmCurrent = readRadioStation(data);
                } else if (code == 4) {            // onScanStarted(int band)
                    scanning = true;
                    scanUntil = SystemClock.uptimeMillis() + 120_000;
                    Diag.put("radio.search", "started, band " + data.readInt());
                } else if (code == 5) {            // onScanFinished(int band, List<RadioStation>)
                    int band = data.readInt();
                    int n = data.readInt();
                    List<Station> found = new ArrayList<>();
                    for (int i = 0; i < n && i < 200; i++) if (data.readInt() != 0) found.add(readRadioStation(data));
                    scanning = false;
                    Diag.put("radio.search", "finished, band " + band + ": " + found.size() + " stations");
                    if (!found.isEmpty() && band != BAND_DAB) {
                        mergeFm(found, true);
                        sp.edit().putString("radio_fm_fonte", "radio service (search)").apply();
                    }
                    radioListWhy = "radio service: search finished";
                    if (band != BAND_DAB) LogoPacks.noteStations(BAND_FM, found);
                } else if (code == 3) {            // onRadioBandChanged(int)
                    bandFromCallback = data.readInt();
                    Diag.put("radio.band", bandFromCallback);
                }
            } catch (Exception e) {
                Diag.error("radio.callback", e.toString());
            }
            if (reply != null) reply.writeNoException();
            if (code == 1 || code == 3 || code == 4 || code == 5) post(MediaClient.this::poll);   // not for signal, RDS…
            return true;
        }
    };

    private final Binder dabCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DAB_CB);
                return true;
            }
            try {
                data.enforceInterface(DAB_CB);
                if (code == 2) {                   // notifyDabStationListInfoChanged(List<RadioDabStation>)
                    int n = data.readInt();
                    List<Station> out = new ArrayList<>();
                    for (int i = 0; i < n && i < 300; i++) if (data.readInt() != 0) out.add(readDabStation(data));
                    if (!out.isEmpty()) {
                        java.util.Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
                        dabList = out;
                        saveList("radio_dab_elenco", out);
                        sp.edit().putString("radio_dab_fonte", "radio service (DAB list)").apply();
                    }
                    Diag.put("radio.dab.list", out.size() + " stations");
                    radioListWhy = "radio service: DAB list";
                    // RDS country from the DAB stations (confirms or corrects the automatic logo country)
                    LogoPacks.noteStations(BAND_DAB, out);
                } else if (code == 3) {            // notifyDabMainInfoChanged(RadioDabStation)
                    if (data.readInt() != 0) {
                        dabCurrent = readDabStation(data);
                        bandFromCallback = BAND_DAB;
                        rememberDab(dabCurrent);
                    }
                } else if (code == 4) {            // notifyDabDLSChanged(char[])
                    char[] t = data.createCharArray();
                    dabText = t == null ? "" : new String(t).trim();
                }
            } catch (Exception e) {
                Diag.error("radio.dab.callback", e.toString());
            }
            if (reply != null) reply.writeNoException();
            if (code == 2 || code == 3) post(MediaClient.this::poll);
            return true;
        }
    };

    private void registerRadioCallbacks() {
        IBinder b = radio.binder();
        if (b == null) return;
        try {
            Rpc.call(b, RAD, RAD_ADD_CB, p -> p.writeStrongBinder(radioCallback));
            Diag.put("radio.callback", "registered");
        } catch (Exception e) {
            Diag.error("radio.callback.register", e.toString());
        }
        try {
            Rpc.call(b, RAD, RAD_ADD_DAB_CB, p -> p.writeStrongBinder(dabCallback));
            Rpc.call(b, RAD, RAD_DAB_QUERY_LIST, Rpc.NONE);
        } catch (Exception e) {
            Diag.error("radio.dab.register", e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------------------------------

    /** Switches to the previous/next source and starts it. */
    public void cycleSource(int dir) {
        State s = last;
        List<Integer> list = s.sources;
        if (list.isEmpty()) return;
        int i = list.indexOf(s.source);
        int next = list.get(((i < 0 ? 0 : i) + dir + list.size()) % list.size());
        selectSource(next);
    }

    /** Shows the source and starts it (play on its session, as the original home does). */
    // --- online music: works only if the car has the feature enabled (like the original Media app) ---
    private static final String FEATURE_URI = "content://com.saicmotor.caradapter.featureprovider/feature/key/";
    private static final String FEATURE_ONLINE = "media_Onlinemusic___";
    private volatile String onlineFeature;      // configuration value, null = not read yet / not readable
    private volatile boolean onlineFeatureRead;
    private volatile boolean onlineFailed;      // tapped but did not start: hidden until the launcher restarts

    /** "0" = feature absent on the car: the service exists but plays nothing, so the button is not shown. */
    /** 0.4.4: on the SWI69 Online music is usually absent: the option is shown only if the source has appeared. */
    public static volatile boolean onlinePossible;

    private boolean onlineAvailable() {
        if (!onlineFeatureRead) {
            onlineFeatureRead = true;
            try (android.database.Cursor q = ctx.getContentResolver().query(
                    android.net.Uri.parse(FEATURE_URI + FEATURE_ONLINE), null, null, null, null)) {
                if (q != null && q.moveToNext()) onlineFeature = q.getString(0);
            } catch (Throwable e) {
                Diag.error("media.online.function", e.toString());
            }
            Diag.put("media.online.function", onlineFeature == null ? "unreadable" : onlineFeature + (("0".equals(onlineFeature)) ? " (absent)" : ""));
        }
        if (onlineFailed) return false;
        return !"0".equals(onlineFeature);
    }

    public void selectSource(int src) {
        if (src != SRC_BT && btWaitSince != 0) post(() -> {
            btWaitGen++;
            btWaitDone();
            Diag.put("media.bt.wait", "interrupted: the user chose " + sourceName(src));
        });
        chosen = src;
        if (src == SRC_RADIO) markRadioByUser();
        post(() -> {
            try {
                if (src == SRC_CARPLAY || src == SRC_AAUTO) {
                    remotePlay(true);
                } else {
                    Browser b = browserFor(src);
                    MediaController.TransportControls tc = b == null ? null : b.controls();
                    if (src == SRC_BT) {
                        // Bluetooth: like the original SWI69 R45 home, the source change is requested from the Media app, which
                        // takes audio focus, starts A2DP audio and presses play. The launcher does not take focus on its own
                        // (taking it away from the Media app made it pause: play/pause over and over).
                        startBt("user choice");
                    } else if (src == SRC_RADIO) {
                        // 0.4.2 (point 11b): the radio really starts (source, audio focus, last station)
                        startRadio("user choice");
                    } else if (tc != null) {
                        tc.play();
                    } else {
                        Diag.put("media.start", sourceName(ctx, src) + ": session not connected");
                    }
                    if (src == SRC_ONLINE) {
                        // if it has not started after a few seconds, the feature is missing: back to the radio and hide it
                        bg.postDelayed(() -> {
                            if (!online.isPlaying()) {
                                onlineFailed = true;
                                Diag.put("media.online", "not starting: hidden");
                                if (chosen == SRC_ONLINE) chosen = SRC_RADIO;
                                poll();
                            }
                        }, 6000);
                    }
                }
            } catch (Exception e) {
                Diag.error("media.start " + sourceName(ctx, src), e.toString());
            }
            poll();
        });
    }

    // --- Bluetooth: car audio source, like the original Media app (com.saicmotor.media) -------------------

    /**
     * Switches the source to Bluetooth music like the original SWI69 R45 home (ModeManager.sendCommand):
     * startService to com.saicmotor.media/.service.MediaService with extra "command" = CommandParcelable
     * (event 32769 = change source, 32773 = change and play; type 2 = Bluetooth music).
     */
    private boolean btSwitchCommand(int event) {
        try {
            Intent i = new Intent().setComponent(new android.content.ComponentName("com.saicmotor.media",
                    "com.saicmotor.media.service.MediaService")).setPackage("com.saicmotor.media");
            i.putExtra("command", new com.saicmotor.common.CommandParcelable(event,
                    com.saicmotor.common.CommandParcelable.TYPE_BT_MUSIC, "com.saicmotor.launcher"));
            boolean ok = ctx.startService(i) != null;
            Diag.put("media.bt.source", "command to the Media app (event " + event + "): " + (ok ? "sent" : "service not found"));
            return ok;
        } catch (Exception e) {
            Diag.put("media.bt.source", "command to the Media app failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    // ---- 0.4.2 (point 11c): reliable Bluetooth start ----------------------------------------------------------
    // Why it sometimes did not start: the Media app (MediaService.k) starts Bluetooth only if it gets audio focus right away
    // and already has its own A2DP session controller; just started (cold process) or with the radio holding
    // focus, command 32769 does nothing. Now: command, then after 1.5 s "change and play" (32773), after another 2 s
    // direct play on the A2DP session, and only at the end the Bluetooth page of the Media app.
    private int btAttempt;

    private void startBt(String why) {
        btAttempt++;
        final int attempt = btAttempt;
        boolean sent = btSwitchCommand(com.saicmotor.common.CommandParcelable.SWITCH_SOURCE);
        Diag.put("media.bt.result", "start (" + why + "): command 32769 " + (sent ? "sent" : "not sent"));
        bg.postDelayed(() -> {
            if (attempt != btAttempt || bt.isPlaying()) {
                if (bt.isPlaying()) Diag.put("media.bt.result", "started on attempt 1 (" + why + ")");
                return;
            }
            boolean s2 = btSwitchCommand(com.saicmotor.common.CommandParcelable.SWITCH_SOURCE_PLAY);
            Diag.put("media.bt.result", "not started: attempt 2 (32773) " + (s2 ? "sent" : "not sent"));
            bg.postDelayed(() -> {
                if (attempt != btAttempt) return;
                if (bt.isPlaying()) {
                    Diag.put("media.bt.result", "started on attempt 2 (" + why + ")");
                    return;
                }
                MediaController.TransportControls tc = bt.controls();
                if (tc != null) tc.play();
                Diag.put("media.bt.result", "not started: attempt 3 (play on the A2DP session" + (tc == null ? ": not connected" : "") + ")");
                checkBtStarted(attempt, sent || s2 || tc != null);
            }, 2000);
        }, 1500);
    }

    private void btCommandResult(String what, boolean wantPlaying) {
        bg.postDelayed(() -> Diag.put("media.bt.commands", what + ": sent · " + (bt.isPlaying() == wantPlaying ? "succeeded"
                : "no effect after 2 s (" + bt.stateName() + ")")), 2000);
    }

    /**
     * Final outcome (after the three attempts): if Bluetooth has not started, fall back to the original Media app (Bluetooth page),
     * which starts the music as when the source is chosen there.
     */
    private void checkBtStarted(int attempt, boolean sent) {
        bg.postDelayed(() -> {
            if (attempt != btAttempt) return;
            boolean playing = bt.isPlaying();
            if (playing) {
                Diag.put("media.bt.result", "started on attempt 3");
                return;
            }
            if (resuming) {
                Diag.put("media.bt.result", "not started after 3 attempts at power-on: switching to radio");
                startRadio("Bluetooth did not start at power-on");
                return;
            }
            Diag.put("media.bt.result", "not started after 3 attempts" + (sent ? "" : " (session not connected)") + ": opening the Media app's Bluetooth page");
            try {
                Intent i = new Intent(Intent.ACTION_MAIN)
                        .setComponent(new android.content.ComponentName(com.mg4.launcher.apps.MgApps.MEDIA_PKG, com.mg4.launcher.apps.MgApps.MEDIA_CLS))
                        .putExtra("type", 2)   // Media app MainActivity: type 2 = Bluetooth page
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e) {
                Diag.error("media.bt.app", e.toString());
            }
            poll();
        }, 2000);
    }

    // ---- 0.4.2 (points 4 and 11b): radio really started, with new attempts --------------------------------------

    /** Radio errors that clear by themselves shortly (service just started). */
    static boolean retryable(Throwable e) {
        String m = String.valueOf(e) + " " + (e.getCause() == null ? "" : e.getCause().toString());
        return m.contains("NOT_INITIALIZED") || m.contains("not connected") || e instanceof android.os.DeadObjectException;
    }

    private static final long[] RETRY_MS = {1000, 2000, 4000};

    /** Runs a radio command; if it answers "not initialized"/"not connected" it retries after 1, 2, 4 s. */
    private void radioRetry(String what, int attempt, RadioOp op) {
        try {
            op.run();
            if (attempt > 0) Diag.put("radio.attempts", what + ": succeeded on attempt " + (attempt + 1));
        } catch (Exception e) {
            if (retryable(e) && attempt < RETRY_MS.length) {
                Diag.put("radio.attempts", what + ": " + e.getClass().getSimpleName() + " (" + e.getMessage() + ") → retrying in "
                        + (RETRY_MS[attempt] / 1000) + " s");
                bg.postDelayed(() -> radioRetry(what, attempt + 1, op), RETRY_MS[attempt]);
            } else {
                Diag.error("radio." + what, e.toString() + (attempt > 0 ? " (after " + (attempt + 1) + " attempts)" : ""));
            }
        }
        poll();
    }

    private interface RadioOp {
        void run() throws Exception;
    }

    private int radioAttempt;

    /**
     * Starts the radio session like the original home (ModeManager.launchSourceLastMode): "change source and
     * play" command (32773) to the Radio app's RadioService, which reopens the saved band, takes audio focus and retunes
     * the last station. If it is not playing after 2.5 s: play on the session, then new attempts (1, 2, 4 s).
     */
    /**
     * 0.4.4 (from SWI68 1.0.4, point C1): at power-on the tuner may not be ready yet. First it
     * waits for the radio service to be connected and its session ready (up to 20 s, checking every second), then it
     * starts with new checks for about 49 s (1+2+3+5+8+10+10+10). At power-on the Radio app is never opened.
     * If the user picks another source meanwhile, the resume stops.
     */
    private static final long[] RESUME_WAIT_MS = {1000, 2000, 3000, 5000, 8000, 10_000, 10_000, 10_000};
    private static final long RADIO_READY_MAX_MS = 20_000;
    private long radioStartAt, radioReadyWaited;
    private int radioTries;

    private void startRadio(String why) {
        markRadioByUser();
        chosen = SRC_RADIO;
        radioAttempt++;
        final int attempt = radioAttempt;
        radioStartAt = SystemClock.uptimeMillis();
        radioTries = 1;
        boolean power = why.contains("power-on");
        if (power) {
            waitRadioReady(why, attempt);
            return;
        }
        boolean sent = radioSourceCommand();
        Diag.put("media.radio.start", why + ": command 32773 to the radio service " + (sent ? "sent" : "NOT sent"));
        radioStartCheck(why, attempt, 0, RETRY_MS);
    }

    private void waitRadioReady(String why, int attempt) {
        if (attempt != radioAttempt || chosen != SRC_RADIO) return;
        long waited = SystemClock.uptimeMillis() - radioStartAt;
        boolean ready = radio.binder() != null && radioMbs.ready();
        if (bg == null) return;
        if (ready || waited >= RADIO_READY_MAX_MS) {
            radioReadyWaited = waited;
            Diag.put("radio.ready", (ready ? "ready" : "not ready yet, trying anyway") + " after " + Math.round(waited / 1000.0)
                    + " s (service " + (radio.binder() != null ? "connected" : "not connected") + ", session " + radioMbs.stateName() + ")");
            boolean sent = radioSourceCommand();
            Diag.put("media.radio.start", why + ": command 32773 to the radio service " + (sent ? "sent" : "NOT sent"));
            radioStartCheck(why, attempt, 0, RESUME_WAIT_MS);
            return;
        }
        bg.postDelayed(() -> waitRadioReady(why, attempt), 1000);
    }

    private void radioStartCheck(String why, int attempt, int n, long[] waits) {
        bg.postDelayed(() -> {
            if (attempt != radioAttempt) return;
            boolean power = waits == RESUME_WAIT_MS;
            if (power && chosen != SRC_RADIO) {
                Diag.put("radio.attempts", "RADIO start: cancelled, the user chose another source · attempts " + radioTries);
                return;
            }
            long total = SystemClock.uptimeMillis() - radioStartAt;
            if (radioMbs.isPlaying()) {
                Diag.put("media.radio.start", why + ": radio on air" + (n > 0 ? " (attempt " + (n + 1) + ")" : ""));
                if (power) Diag.put("radio.attempts", "RADIO start: succeeded · service wait " + Math.round(radioReadyWaited / 1000.0)
                        + " s · attempts " + radioTries + " · total " + Math.round(total / 1000.0) + " s");
                poll();
                return;
            }
            MediaController.TransportControls tc = radioMbs.controls();
            if (tc != null) tc.play();
            else radioMbs.connect();
            radioTries++;
            if (n >= waits.length) {
                Diag.put("media.radio.start", why + ": the radio did not start after " + (n + 1) + " attempts (" + radioMbs.stateName() + ")");
                if (power) Diag.put("radio.attempts", "RADIO start: failed · service wait " + Math.round(radioReadyWaited / 1000.0)
                        + " s · attempts " + radioTries + " · total " + Math.round(total / 1000.0) + " s");
                poll();
                return;
            }
            if (n == 1 || power && n % 3 == 2) radioSourceCommand();   // every now and then the source command is repeated too
            Diag.put("media.radio.start", why + ": not on air yet (" + radioMbs.stateName() + ") → play and new check in "
                    + (waits[n] / 1000) + " s");
            radioStartCheck(why, attempt, n + 1, waits);
        }, n == 0 ? 2500 : waits[n - 1]);
    }

    private boolean radioSourceCommand() {
        try {
            Intent i = new Intent().setComponent(new ComponentName(RADIO_PKG, "com.saicmotor.radio.service.RadioService")).setPackage(RADIO_PKG);
            i.putExtra("command", new com.saicmotor.common.CommandParcelable(com.saicmotor.common.CommandParcelable.SWITCH_SOURCE_PLAY,
                    0, "com.saicmotor.launcher"));
            return ctx.startService(i) != null;
        } catch (Exception e) {
            Diag.put("media.radio.start", "command to the radio service failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    // ---- 0.4.2 (point 11a): at power-on the last source actually used is resumed -----------------------------------

    private volatile boolean resuming;
    private int savedSrcLogged = -1;

    private void rememberSource(int active, int deviceType) {
        if (active == 0) return;
        int saved = sp.getInt("media.ultimaSorgente", 0);
        if (saved != active) sp.edit().putInt("media.ultimaSorgente", active).apply();
        if (savedSrcLogged != active) {
            savedSrcLogged = active;
            Diag.putQuiet("media.savedSource", sourceName(active));
        }
    }

    /**
     * To call when the launcher starts and when the car wakes up: after a few seconds, if nothing is already playing
     * (the system has not resumed a source by itself), resumes the last one used; if unavailable, the radio.
     */
    public void resumeLastSource(String why) {
        if (bg == null) return;
        bg.postDelayed(() -> {
            int saved = sp.getInt("media.ultimaSorgente", SRC_RADIO);
            State st = last;
            boolean anyPlaying = radioMbs.isPlaying() || bt.isPlaying() || usb.isPlaying() || usb2.isPlaying() || online.isPlaying()
                    || (remote.isPlaying() && st.deviceType != 0);
            String startInfo = "saved " + sourceName(saved) + " · " + why;
            if (anyPlaying) {
                Diag.put("media.start", startInfo + " · not needed: already playing " + sourceName(prevActive) + " · attempts 0");
                return;
            }
            resuming = true;
            bg.postDelayed(() -> resuming = false, 20_000);
            int target = saved;
            String note = "";
            if (target == SRC_BT) {
                // 0.4.3 (point 4): at power-on the phone reconnects after a few seconds: wait for A2DP/AVRCP
                // (Bluetooth notice + check every second, at most 30 s), then press play
                Diag.put("media.start", startInfo + " · resuming BLUETOOTH: waiting for the phone to connect");
                chosen = SRC_BT;
                btWaitConnected(startInfo);
                return;
            } else if ((target == SRC_CARPLAY || target == SRC_AAUTO) && st.deviceType == 0) {
                target = SRC_RADIO;
                note = " (CarPlay/Android Auto not connected)";
            } else if (target == SRC_ONLINE && !(online.ready() && onlineAvailable())) {
                target = SRC_RADIO;
                note = " (online music unavailable)";
            }
            Diag.put("media.start", startInfo + " · resuming " + sourceName(target) + note);
            chosen = target;
            try {
                switch (target) {
                    case SRC_BT:
                        startBt("power-on");
                        break;
                    case SRC_USB: {
                        Intent i = new Intent().setComponent(new ComponentName(MEDIA_PKG, "com.saicmotor.media.service.MediaService")).setPackage(MEDIA_PKG);
                        i.putExtra("command", new com.saicmotor.common.CommandParcelable(com.saicmotor.common.CommandParcelable.SWITCH_SOURCE_PLAY, 0, "com.saicmotor.launcher"));
                        boolean ok = ctx.startService(i) != null;
                        bg.postDelayed(() -> {
                            if (!usb.isPlaying()) {
                                MediaController.TransportControls tc = usb.controls();
                                if (tc != null) tc.play();
                            }
                            Diag.put("media.start", startInfo + " · resuming USB · " + (usb.isPlaying() ? "succeeded" : "waiting (" + usb.stateName() + ")"));
                        }, 2500);
                        if (!ok) Diag.put("media.start", startInfo + " · USB: Media service not found");
                        break;
                    }
                    case SRC_ONLINE: {
                        MediaController.TransportControls tc = online.controls();
                        if (tc != null) tc.play();
                        break;
                    }
                    case SRC_USB2: {
                        MediaController.TransportControls tc = usb2.controls();
                        if (tc != null) tc.play();
                        Diag.put("media.start", startInfo + " · resuming USB 2 · " + (tc == null ? "session not connected" : "play sent"));
                        break;
                    }
                    case SRC_CARPLAY:
                    case SRC_AAUTO:
                        remotePlay(true);
                        break;
                    default:
                        startRadio("power-on");
                        break;
                }
            } catch (Exception e) {
                Diag.error("media.start", e.toString());
            }
            poll();
        }, 6000);
    }

    // ---- 0.4.3 (point 4): Bluetooth at power-on, wait for the connection before play ---------------------------
    private static final long BT_WAIT_MAX_MS = 30_000, BT_WAIT_STEP_MS = 1000, BT_AVRCP_GRACE_MS = 3000;
    /** Car-side Bluetooth profiles (hidden BluetoothProfile constants): A2DP sink 11, AVRCP controller 12. */
    private static final int PROFILE_A2DP_SINK = 11, PROFILE_AVRCP_CTRL = 12;
    private int btWaitGen;
    private volatile long btWaitSince;
    private long btWaitA2dpAt;
    private String btWaitWhy = "";
    private android.content.BroadcastReceiver btWaitReceiver;

    private void btWaitConnected(String why) {
        btWaitGen++;
        btWaitSince = SystemClock.uptimeMillis();
        btWaitA2dpAt = -1;
        btWaitWhy = why;
        final int gen = btWaitGen;
        try {
            if (btWaitReceiver == null) {
                btWaitReceiver = new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        post(() -> btWaitStep(btWaitGen, "notice " + i.getAction().replaceAll(".*\\.(a2dp-sink|avrcp-controller).*", "$1")));
                    }
                };
                android.content.IntentFilter f = new android.content.IntentFilter("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
                f.addAction("android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED");
                ctx.registerReceiver(btWaitReceiver, f);
            }
        } catch (Throwable e) {
            btWaitReceiver = null;
        }
        btWaitStep(gen, "check");
    }

    private void btWaitStep(int gen, String trigger) {
        if (gen != btWaitGen || btWaitSince == 0) return;
        long now = SystemClock.uptimeMillis();
        int a2dp = profileState(PROFILE_A2DP_SINK), avrcp = profileState(PROFILE_AVRCP_CTRL);
        boolean a2dpOk = a2dp == 2;
        if (a2dpOk && btWaitA2dpAt < 0) btWaitA2dpAt = now;
        boolean avrcpOk = avrcp == 2 || avrcp < 0 || btWaitA2dpAt >= 0 && now - btWaitA2dpAt >= BT_AVRCP_GRACE_MS;
        long waited = now - btWaitSince;
        String states = "A2DP " + profileTxt(a2dp) + " · AVRCP " + profileTxt(avrcp);
        if (a2dpOk && avrcpOk) {
            btWaitDone();
            Diag.put("media.bt.wait", "waited " + Math.round(waited / 1000.0) + " s · connected (" + trigger + ") · " + states);
            resuming = true;
            bg.postDelayed(() -> resuming = false, 20_000);
            startBt("power-on, after " + Math.round(waited / 1000.0) + " s of waiting");
            return;
        }
        if (waited >= BT_WAIT_MAX_MS) {
            btWaitDone();
            Diag.put("media.bt.wait", "waited " + (BT_WAIT_MAX_MS / 1000) + " s · phone not connected: switching to radio · " + states);
            Diag.put("media.start", btWaitWhy + " · resuming RADIO (Bluetooth phone not connected after 30 s)");
            chosen = SRC_RADIO;
            startRadio("Bluetooth not connected at power-on");
            return;
        }
        bg.removeCallbacks(btWaitTick);
        bg.postDelayed(btWaitTick, BT_WAIT_STEP_MS);
    }

    private final Runnable btWaitTick = () -> btWaitStep(btWaitGen, "check");

    private void btWaitDone() {
        btWaitSince = 0;
        bg.removeCallbacks(btWaitTick);
        if (btWaitReceiver != null) {
            try {
                ctx.unregisterReceiver(btWaitReceiver);
            } catch (Throwable ignored) {
            }
            btWaitReceiver = null;
        }
    }

    /** State of a Bluetooth profile: 2 connected, 0 disconnected, -1 not readable. */
    private static int profileState(int profile) {
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return -1;
            if (!ad.isEnabled()) return 0;
            return ad.getProfileConnectionState(profile);
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String profileTxt(int st) {
        switch (st) {
            case 2: return "connected";
            case 1: return "connecting";
            case 0: return "disconnected";
            case 3: return "disconnecting";
            default: return "unreadable";
        }
    }

    // ---- 0.4.3 (point 5): second USB port and steering-wheel buttons -------------------------------------------

    /** true when both ports are connected (the labels become "USB 1" and "USB 2"). */
    public static volatile boolean usbBoth;
    private String usbLog = "";

    /** The Media app writes "usb2AvailableStatus" = 1 in Settings.System when there is music on the second port. */
    private boolean usb2Available() {
        try {
            return android.provider.Settings.System.getInt(ctx.getContentResolver(), "usb2AvailableStatus", 0) == 1;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Steering-wheel next/previous with USB 2 playing (even when started from the original Media app): the system sends them
     * to the playing session; if the track has not changed after 0.9 s, the launcher sends them to the USB 2 session.
     * This way it never skips twice.
     */
    private final WheelKeys.Listener wheel = (code, down, lp) -> {
        if (down || lp || !WheelKeys.isSkip(code)) return;
        Handler h = bg;
        if (h == null || !MediaClient.this.usb2.isPlaying()) return;
        boolean fwd = code == WheelKeys.NEXT;
        String before = trackKey(MediaClient.this.usb2);
        h.postDelayed(() -> {
            String what = fwd ? "forward" : "back";
            if (!MediaClient.this.usb2.isPlaying()) return;
            if (!before.equals(trackKey(MediaClient.this.usb2))) {
                Diag.put("media.wheelKeys.usb2", what + ": already handled by the system");
                return;
            }
            MediaController.TransportControls tc = MediaClient.this.usb2.controls();
            if (tc == null) return;
            if (fwd) tc.skipToNext();
            else tc.skipToPrevious();
            Diag.put("media.wheelKeys.usb2", what + ": sent to the USB 2 session");
        }, 900);
    };

    private static String trackKey(Browser b) {
        MediaController c = b.controller;
        MediaMetadata m = c == null ? null : c.getMetadata();
        PlaybackState ps = c == null ? null : c.getPlaybackState();
        return (m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE)) + "|" + m.getLong(MediaMetadata.METADATA_KEY_DURATION))
                + "|" + (ps == null ? -1 : ps.getActiveQueueItemId());
    }

    // ---- 0.4.3 (point 10): reads for the Feature check (read only, no commands) ----------------------------

    public boolean radioLinked() {
        return radio.binder() != null;
    }

    public boolean mediaLinked() {
        return usb.ready();
    }

    /** Where the Bluetooth title and artist come from (last read). */
    public static volatile String btMetaSource = "";

    public boolean btLinked() {
        return bt.ready();
    }

    /** null = CarPlay/Android Auto (Allgo) not installed on this car. */
    public Boolean projectionLinked() {
        try {
            ctx.getPackageManager().getPackageInfo("com.allgo.rui", 0);
        } catch (Exception e) {
            return null;
        }
        return rui.binder() != null;
    }

    /** Bluetooth audio phone state: 2 = connected (A2DP), 0 = no, -1 if unreadable. */
    public int btState() {
        int a = profileState(PROFILE_A2DP_SINK);
        return a == 2 ? 2 : a < 0 ? -1 : 0;
    }

    /** USB ports with the session connected and the state (for the Feature check). */
    public List<String> usbPortsSeen() {
        List<String> out = new ArrayList<>();
        out.add("USB 1 " + (usb.ready() ? usb.stateName() : "session not connected"));
        out.add("USB 2 " + (usb2.ready() ? usb2.stateName() : "session not connected") + (usb2Available() ? " · music present" : ""));
        return out;
    }

    public boolean usb2Linked() {
        return usb2.ready();
    }

    /** Phone connected via Bluetooth audio (A2DP sink, profile 11)? */
    private boolean btConnected() {
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return false;
            return a.getProfileConnectionState(11) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                    || bt.ready() && bt.controller != null && bt.controller.getMetadata() != null;
        } catch (Throwable e) {
            return bt.ready();
        }
    }

    /**
     * Changes band (FM / DAB) like the Radio app: the session's "next/previous source" action,
     * which resumes the last station listened to on that band. Band order: DAB, FM, AM.
     */
    // --- automatic resume: after a notification the system sometimes puts the radio back in place of the music ---
    private static final long HIJACK_WINDOW_MS = 2500, RESUME_DELAY_MS = 2500, RADIO_USER_MS = 6000;
    private int prevActive;                    // source playing on the previous round
    private int lastMedia;                     // last music source playing (BT, USB, Online)
    private long lastMediaAt;                  // when it was still playing
    private volatile long radioUserAt;         // last radio command given by the user from the launcher
    private boolean resumePending;
    private final java.util.ArrayDeque<Long> resumes = new java.util.ArrayDeque<>();

    private Browser musicBrowser(int src) {
        return src == SRC_BT ? bt : src == SRC_USB ? usb : src == SRC_USB2 ? usb2 : src == SRC_ONLINE ? online : null;
    }

    /** The user chose or controlled the radio from the launcher: in that case the radio must never be "corrected". */
    private void markRadioByUser() {
        radioUserAt = android.os.SystemClock.uptimeMillis();
    }

    /** Called on every poll() round, on the worker thread. */
    private void checkHijack(int active) {
        long now = android.os.SystemClock.uptimeMillis();
        if (active == SRC_BT || active == SRC_USB || active == SRC_USB2 || active == SRC_ONLINE) {
            lastMedia = active;          // also applies if the music was started from the system app
            lastMediaAt = now;
        }
        boolean radioJustStarted = active == SRC_RADIO && prevActive != SRC_RADIO;
        prevActive = active;
        if (!radioJustStarted || resumePending || lastMedia == 0) return;
        Browser b = musicBrowser(lastMedia);
        if (b == null) return;
        // the music was playing a moment ago, now it is paused and the radio started without a command from the launcher
        if (now - lastMediaAt > HIJACK_WINDOW_MS) return;
        if (now - radioUserAt < RADIO_USER_MS) return;
        if (chosen == SRC_RADIO && now - radioUserAt < 60_000) return;
        final int src = lastMedia;
        resumePending = true;
        bg.postDelayed(() -> resumeIfStillHijacked(src), RESUME_DELAY_MS);
    }

    private void resumeIfStillHijacked(int src) {
        resumePending = false;
        Browser b = musicBrowser(src);
        String what = sourceName(ctx, src) + " after switch to RADIO";
        if (b == null || !radioMbs.isPlaying() || !b.isPaused()) return;   // resolved by itself or changed by the user
        if (android.os.SystemClock.uptimeMillis() - radioUserAt < RADIO_USER_MS) return;
        long now = android.os.SystemClock.uptimeMillis();
        while (!resumes.isEmpty() && now - resumes.peekFirst() > 60_000) resumes.pollFirst();
        if (resumes.size() >= 2) {
            Diag.put("media.autoResume", what + " (skipped: already 2 resumes in the last minute)");
            return;
        }
        resumes.addLast(now);
        MediaController.TransportControls tc = b.controls();
        if (tc == null) {
            Diag.put("media.autoResume", what + " (failed: session not connected)");
            return;
        }
        // Bluetooth: command to the Media app as in selectSource(); other sources: play on the session
        if (src != SRC_BT || !btSwitchCommand(com.saicmotor.common.CommandParcelable.SWITCH_SOURCE_PLAY)) tc.play();
        chosen = src;
        Diag.put("media.autoResume", what + " (sent)");
        bg.postDelayed(() -> {
            Diag.put("media.autoResume", what + (b.isPlaying() ? " (succeeded)" : " (failed)"));
            poll();
        }, 3000);
    }

    public void switchBand(int band) {
        chosen = SRC_RADIO;
        markRadioByUser();
        post(() -> radioRetry("band", 0, () -> {
            {
                int cur = last.radioBand;
                MediaController.TransportControls tc = radioMbs.controls();
                if (tc == null) throw new RemoteException("radio session unavailable");
                if (cur == band) {
                    tc.play();
                } else {
                    String action;
                    if (band == BAND_FM) action = cur == BAND_DAB ? ACTION_NEXT_BAND : ACTION_PREV_BAND;
                    else action = cur == BAND_FM ? ACTION_PREV_BAND : ACTION_NEXT_BAND;
                    tc.sendCustomAction(action, null);
                    bandFromCallback = band;
                }
                Diag.put("radio.band.chosen", cur + " → " + band);
            }
        }));
    }

    /** Tunes a favorite station and switches to the radio. */
    public void tune(Station st) {
        chosen = SRC_RADIO;
        markRadioByUser();
        post(() -> radioRetry("tune", 0, () -> {
            {
                IBinder b = radio.binder();
                if (b == null) throw new RemoteException("radio service not connected");
                if (st.band == BAND_DAB) {
                    Station d = st;
                    if (d.sid == 0) for (Station x : dabList) if (x.name.equalsIgnoreCase(st.name)) { d = x; break; }
                    Station dd = d;
                    Rpc.call(b, RAD, RAD_DAB_TUNE, p -> writeDabStation(p, dd));
                    bandFromCallback = BAND_DAB;
                } else {
                    Rpc.call(b, RAD, RAD_TUNE, p -> {
                        p.writeInt(1);             // not null
                        p.writeInt(st.khz);
                        p.writeInt(0);
                        p.writeInt(st.band);
                        p.writeString(null);       // RadioRds absent
                    });
                    bandFromCallback = st.band;
                }
                Diag.put("radio.tune", st.label());
            }
        }));
    }

    // ---- station list ------------------------------------------------------------------------

    private List<Station> loadList(String key) {
        List<Station> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                Station st = Station.fromJson(a.getJSONObject(i));
                st.key = StationLogos.key(st.band, st.sid, st.khz, st.name);
                out.add(st);
            }
        } catch (Exception e) {
            Diag.error("radio.list", e.toString());
        }
        return out;
    }

    private void saveList(String key, List<Station> list) {
        JSONArray a = new JSONArray();
        try {
            for (Station st : list) a.put(st.toJson());
        } catch (Exception e) {
            Diag.error("radio.list.save", e.toString());
        }
        sp.edit().putString(key, a.toString()).apply();
    }

    /** Merges FM stations into the list (same frequency = same station); replace = result of a full search. */
    private void mergeFm(List<Station> found, boolean replace) {
        java.util.Map<Integer, Station> byKhz = new java.util.TreeMap<>();
        if (!replace) for (Station st : fmList) byKhz.put(st.khz, st);
        for (Station st : found) {
            if (st.khz <= 0 || st.band == BAND_DAB) continue;
            Station old = byKhz.get(st.khz);
            if (old != null && st.name.isEmpty()) st.name = old.name;
            if (replace && old == null) {
                for (Station prev : fmList) if (prev.khz == st.khz && st.name.isEmpty()) st.name = prev.name;
            }
            st.band = BAND_FM;
            st.key = StationLogos.key(BAND_FM, 0, st.khz, st.name);
            byKhz.put(st.khz, st);
        }
        List<Station> out = new ArrayList<>(byKhz.values());
        fmList = out;
        saveList("radio_fm_elenco", out);
        Diag.put("radio.fm.list", out.size() + " stations");
    }

    /** Every FM station listened to with its RDS name enters the list (even without a search). */
    private void rememberFm(int khz, String name) {
        for (Station st : fmList) {
            if (st.khz == khz) {
                if (st.name.isEmpty() && !name.isEmpty()) {
                    st.name = name;
                    st.key = StationLogos.key(BAND_FM, 0, khz, name);
                    saveList("radio_fm_elenco", fmList);
                }
                return;
            }
        }
        if (name.isEmpty()) return;
        Station st = new Station();
        st.band = BAND_FM;
        st.khz = khz;
        st.name = name;
        List<Station> one = new ArrayList<>();
        one.add(st);
        mergeFm(one, false);
    }

    /**
     * 0.4.4 (EH32: SWI69, SWI131, SWI132): the R45 radio service never sends the DAB list to external clients
     * (DabTunerCallback.onDabStationListInfoChanged only saves it in its RadioStorage, without
     * notifyDabStationListInfoChanged) and the pre-scanned FM list stays in the Radio app's private database. So
     * the launcher builds the list by itself: every tuned DAB station (notifyDabMainInfoChanged) enters
     * the list and is remembered, as already happens for FM (stations with an RDS name + search results).
     */
    private void rememberDab(Station d) {
        if (d == null || d.name.trim().isEmpty() || d.sid == 0) return;
        List<Station> cur = dabList;
        for (Station st : cur) if (st.sid == d.sid && st.name.equalsIgnoreCase(d.name)) return;
        List<Station> out = new ArrayList<>(cur);
        out.add(d);
        java.util.Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
        if (out.size() > 300) return;
        dabList = out;
        saveList("radio_dab_elenco", out);
        if (!sp.contains("radio_dab_fonte")) sp.edit().putString("radio_dab_fonte", "DAB stations listened to").apply();
        radioListWhy = "new DAB station listened to";
    }

    private volatile String radioListWhy = "startup", radioListLog = "";

    /**
     * 0.4.3 (point 6): re-reads the station list (return to the launcher, e.g. from the Radio app). On the SWI69 R45
     * the radio service gives the full DAB list (queryDabStationList → callback) and the FM list only with the
     * search-finished callback (even when the Radio app runs the search): the pre-scanned FM list stays inside the Radio app.
     */
    public void refreshRadioLists(String why) {
        radioListWhy = why;
        Handler h = bg;
        if (h == null) return;
        h.removeCallbacks(radioRefresh);
        h.postDelayed(radioRefresh, 500);
    }

    private final Runnable radioRefresh = () -> {
        IBinder b = MediaClient.this.radio.binder();
        if (b != null) {
            try {
                Rpc.call(b, RAD, RAD_DAB_QUERY_LIST, Rpc.NONE);
            } catch (Exception e) {
                Diag.error("radio.list", e.getClass().getSimpleName());
            }
        }
        radioListLog = "";
        if (running) poll();
    };

    /** FM: full station search (Radio app scan). DAB: refreshes the list from the service. */
    public void searchStations() {
        markRadioByUser();
        int band = last.radioBand;
        post(() -> radioRetry("search", 0, () -> {
            try {
                IBinder b = radio.binder();
                if (b == null) throw new RemoteException("radio service not connected");
                if (band == BAND_DAB) {
                    Rpc.call(b, RAD, RAD_DAB_QUERY_LIST, Rpc.NONE);
                } else {
                    scanning = true;
                    scanUntil = SystemClock.uptimeMillis() + 120_000;
                    Rpc.call(b, RAD, RAD_SCAN, Rpc.NONE);
                }
                Diag.put("radio.search", "requested, band " + band);
            } catch (Exception e) {
                scanning = false;
                throw e;
            }
        }));
    }

    /** Adds or removes a list station from the favorites. @return true if it is now among the favorites */
    public boolean toggleFavorite(Station st) {
        List<Station> list = new ArrayList<>(favorites);
        for (int i = 0; i < list.size(); i++) {
            Station f = list.get(i);
            boolean same = st.band == BAND_DAB ? f.band == BAND_DAB && (f.sid == st.sid || f.key.equals(st.key))
                    : f.band != BAND_DAB && f.khz == st.khz;
            if (same) {
                list.remove(i);
                saveFavorites(list);
                post(this::poll);
                return false;
            }
        }
        Station c = new Station();
        c.band = st.band;
        c.khz = st.khz;
        c.sid = st.sid;
        c.eid = st.eid;
        c.index = st.index;
        c.pty = st.pty;
        c.type = st.type;
        c.channel = st.channel;
        c.name = st.name;
        c.ensemble = st.ensemble;
        c.key = st.key;
        c.logo = st.logo;
        list.add(c);
        saveFavorites(list);
        post(this::poll);
        return true;
    }

    /** true if the list station is already among the favorites. */
    public boolean isFavorite(Station st) {
        for (Station f : favorites) {
            if (st.band == BAND_DAB ? f.band == BAND_DAB && (f.sid == st.sid || f.key.equals(st.key))
                    : f.band != BAND_DAB && f.khz == st.khz) return true;
        }
        return false;
    }

    // ---- launcher radio favorites ----------------------------------------------------------

    private void loadFavorites() {
        List<Station> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("radio_preferiti", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Station st = Station.fromJson(a.getJSONObject(i));
                st.key = StationLogos.key(st.band, st.sid, st.khz, st.name);
                out.add(st);
            }
        } catch (Exception e) {
            Diag.error("radio.favourites", e.toString());
        }
        favorites = out;
    }

    private void saveFavorites(List<Station> list) {
        JSONArray a = new JSONArray();
        try {
            for (Station st : list) a.put(st.toJson());
        } catch (Exception e) {
            Diag.error("radio.favourites.save", e.toString());
        }
        sp.edit().putString("radio_preferiti", a.toString()).apply();
        favorites = list;
        Diag.put("radio.favourites", list.size() + " stations");
    }

    /**
     * Adds the station on air to the favorites, or removes it if already there.
     * @return true if it is now among the favorites, false if it was removed, null if there is no valid station
     */
    public Boolean toggleCurrentFavorite() {
        State s = last;
        if (s.source != SRC_RADIO || s.stationKey.isEmpty()) return null;
        List<Station> list = new ArrayList<>(favorites);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key.equals(s.stationKey)) {
                list.remove(i);
                saveFavorites(list);
                post(this::poll);
                return false;
            }
        }
        Station st;
        if (s.radioBand == BAND_DAB) {
            Station d = currentDab(s.title);
            if (d == null || d.sid == 0) return null;
            st = new Station();
            st.band = BAND_DAB;
            st.pty = d.pty;
            st.name = d.name.isEmpty() ? s.title : d.name;
            st.sid = d.sid;
            st.ensemble = d.ensemble;
            st.eid = d.eid;
            st.index = d.index;
            st.type = d.type;
            st.channel = d.channel;
        } else {
            Station f = fmCurrent;
            if (f == null || f.khz <= 0) return null;
            st = new Station();
            st.band = f.band;
            st.khz = f.khz;
            st.name = s.title.equals(s.radioFreq + " " + s.radioUnit) ? "" : s.title;
        }
        st.key = s.stationKey;
        st.logo = null;   // fork: the following poll loads the thumbnail (the full logo / DAB art is not kept around)
        list.add(st);
        saveFavorites(list);
        post(this::poll);
        return true;
    }

    public void removeFavorite(Station st) {
        List<Station> list = new ArrayList<>(favorites);
        list.remove(st);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).key.equals(st.key)) list.remove(i);
        saveFavorites(list);
        post(this::poll);
    }

    private void refreshLogosSoon() {
        post(this::poll);
    }

    // ---- play / pause / skip -------------------------------------------------------------------

    public void playPause() {
        if (last.source == SRC_RADIO) markRadioByUser();
        post(() -> {
            State s = last;
            try {
                if (s.source == SRC_CARPLAY || s.source == SRC_AAUTO) {
                    remotePlay(!remote.isPlaying());
                } else {
                    Browser b = browserFor(s.source);
                    MediaController.TransportControls tc = b == null ? null : b.controls();
                    if (s.source == SRC_RADIO && !s.playing) {
                        // 0.4.2 (point 11b): Play on the radio = same real start as the source (not just play)
                        startRadio("Play button");
                    } else if (s.source == SRC_BT && !s.playing) {
                        // Bluetooth resume like the original home: audio focus and A2DP are handled by the Media app
                        startBt("Play button");
                        btCommandResult("play", true);
                    } else if (tc != null) {
                        if (s.playing) tc.pause();
                        else tc.play();
                        if (s.source == SRC_BT) btCommandResult(s.playing ? "pause" : "play", !s.playing);
                    } else if (s.source == SRC_BT) {
                        Diag.put("media.bt.commands", (s.playing ? "pause" : "play") + ": Bluetooth session not connected");
                    }
                }
            } catch (Exception e) {
                Diag.error("media.play", e.toString());
            }
            poll();
        });
    }

    public void skip(boolean forward) {
        if (last.source == SRC_RADIO) markRadioByUser();
        post(() -> {
            State s = last;
            try {
                if (s.source == SRC_CARPLAY || s.source == SRC_AAUTO) {
                    // CarPlay / Android Auto: always the RemoteUI service keys, like the original home
                    if (!remoteKey(forward ? "forward" : "back", forward ? 7 : 8, forward ? 11 : 12)) {
                        MediaController.TransportControls tc = remote.controls();
                        if (tc != null) {
                            if (forward) tc.skipToNext();
                            else tc.skipToPrevious();
                            Diag.put("carplay.button", (forward ? "forward" : "back") + " · RemoteUI not connected: MediaController");
                        }
                    }
                } else {
                    Browser b = browserFor(s.source);
                    MediaController.TransportControls tc = b == null ? null : b.controls();
                    if (tc != null) {
                        String before = s.title;
                        if (forward) tc.skipToNext();
                        else tc.skipToPrevious();
                        if (s.source == SRC_BT) {
                            String what = forward ? "forward" : "back";
                            bg.postDelayed(() -> Diag.put("media.bt.commands", what + ": sent · "
                                    + (last.title.equals(before) ? "title unchanged after 2 s (the phone may not send the data)" : "succeeded")), 2000);
                        }
                    } else if (s.source == SRC_BT) {
                        Diag.put("media.bt.commands", (forward ? "forward" : "back") + ": Bluetooth session not connected");
                    }
                }
            } catch (Exception e) {
                Diag.error("media.skip", e.toString());
            }
            poll();
        });
    }

    /**
     * Play or pause for CarPlay / Android Auto. Like the original home (CPAAModel.mediaControlCPAA) the RemoteUI
     * service key is always sent: 20 pressed / 21 released, a single key that toggles. So it is pressed only if
     * the current session state differs from the desired one (otherwise choosing the CarPlay source the music
     * would pause). Android's MediaController is used only if the RemoteUI service is not connected: many
     * wireless adapters (e.g. AutoKit) ignore it.
     */
    private void remotePlay(boolean play) throws RemoteException {
        boolean playingNow = remote.isPlaying();
        if (playingNow == play) {
            Diag.put("carplay.button", (play ? "play" : "pause") + " · already " + remote.stateName() + ": no key");
            return;
        }
        if (remoteKey(play ? "play" : "pause", 20, 21)) return;
        MediaController.TransportControls tc = remote.controls();
        if (tc != null) {
            if (play) tc.play();
            else tc.pause();
            Diag.put("carplay.button", (play ? "play" : "pause") + " · RemoteUI not connected: MediaController");
        } else {
            Diag.put("carplay.button", (play ? "play" : "pause") + " · neither RemoteUI nor session connected");
        }
    }

    /** RemoteUI service media key (sendMediaPlayControlKey, transaction 18): pressed then released. */
    private boolean remoteKey(String what, int down, int up) {
        IBinder r = rui.binder();
        if (r == null) return false;
        try {
            Integer a = Rpc.call(r, RUI, RUI_MEDIA_KEY, p -> p.writeInt(down), Parcel::readInt);
            Integer b = Rpc.call(r, RUI, RUI_MEDIA_KEY, p -> p.writeInt(up), Parcel::readInt);
            Diag.put("carplay.button", what + " · sendMediaPlayControlKey " + down + "/" + up + " → " + a + "/" + b);
            return true;
        } catch (Exception e) {
            Diag.error("carplay.button", what + ": " + e);
            return false;
        }
    }

    /** Opens CarPlay or Android Auto full screen. */
    public boolean openProjection() {
        int type = last.deviceType;
        Intent i = new Intent(Intent.ACTION_MAIN);
        if (type == DEV_AAUTO) {
            i.setComponent(new ComponentName("com.allgo.app.androidauto", "com.allgo.app.androidauto.ProjectionActivity"));
        } else {
            i.setComponent(new ComponentName("com.allgo.carplay.service", "com.allgo.carplay.service.CarPlayActivity"));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Diag.error("carplay.open", e.toString());
            return false;
        }
    }

    /** Opens the CarPlay/AA navigation app (launchApp 2, device type). */
    public void openRemoteNavigation() {
        int type = last.deviceType;
        post(() -> {
            try {
                Rpc.call(rui.binder(), RUI, RUI_LAUNCH_APP, p -> {
                    p.writeInt(2);
                    p.writeInt(type);
                }, Parcel::readInt);
            } catch (Exception e) {
                Diag.error("carplay.navigation", e.toString());
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // CarPlay / Android Auto: navigation directions from the remote session
    // ------------------------------------------------------------------------------------------

    private final MediaController.Callback remoteCallback = new MediaController.Callback() {
        @Override
        public void onSessionEvent(String event, Bundle extras) {
            if ("ACTION_CARPLAY_NAVI_METADATA".equals(event) && extras != null) {
                tbt = extras;
                Diag.put("carplay.navigation", extras.toString());
            }
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            if (extras != null && extras.containsKey("IS_TBT_ACTIVE")) tbt = extras;
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            post(MediaClient.this::poll);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            post(MediaClient.this::poll);
        }

        @Override
        public void onSessionDestroyed() {
            remote.retry(5000);
        }
    };
}
