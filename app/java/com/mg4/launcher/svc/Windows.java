package com.mg4.launcher.svc;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.widget.Toast;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.ui.Prefs;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Vidros: fecho automático ao sair do carro e fecho manual (funções do WinClose, reescritas para o launcher).
 * <p>
 * Ligação ao carro (SWI69, EH32):
 * <ul>
 *   <li>READY e marcha: ICarStateService (hub com.saicmotor.caradapter, código 11) com chamadas binder diretas,
 *       como o {@link VehicleClient};</li>
 *   <li>vidros: CarVehicleSettingClient (código 8) das bibliotecas carapi da SAIC, carregadas da app da home original
 *       (com.saicmotor.launcher na SWI69): setVehicleWindowStatus(área, comando) e getVehicleWindowValue(área)
 *       (0 = fechado … 255 = aberto, só fiável em vidros com sensor);</li>
 *   <li>porta do condutor: callback ICarStateCallback registado no CarStateClient (transação 6, 0 = aberta;
 *       transação 3 = marcha).</li>
 * </ul>
 * Corre sempre, mesmo com o launcher em segundo plano (o processo da home não é terminado), num fio próprio.
 */
public final class Windows {
    private static final String HUB_PKG = "com.saicmotor.caradapter";
    private static final String HUB_ACTION = "com.saicmotor.caradapter.CarAdapterService";
    private static final String HUB = "com.saicmotor.carapi.ICarAdapterService";
    private static final int HUB_QUERY_CLIENT = 1, BIND_VS = 8, BIND_STATE = 11;
    private static final String STATE_DESC = "com.saicmotor.carapi.carstate.ICarStateService";
    private static final int ST_SERVICE_READY = 3, ST_GEAR = 5, ST_EPTRDY = 8;

    private static final String VS_CLIENT = "com.saicmotor.carapi.client.CarVehicleSettingClient";
    private static final String STATE_CLIENT = "com.saicmotor.carapi.client.CarStateClient";
    private static final String STATE_CB_DESC = "com.saicmotor.carapi.carstate.ICarStateCallback";
    private static final int CB_GEAR = 3, CB_DOOR = 6;
    /** Apps onde estão as bibliotecas carapi (a primeira é a da SWI69). */
    private static final String[] SAIC_PKGS = {"com.saicmotor.launcher", "com.saicmotor.hmi.launcher", "com.saicmotor.carcontrol",
            "com.saicmotor.settings", "com.saicmotor.hmi", "com.saicmotor.voiceservice", "com.saicmotor.telematics"};

    /** Comandos do vidro: parar, subir (enquanto se mantém), subida automática. */
    private static final int CMD_STOP = 0, CMD_UP = 1, CMD_AUTO_UP = 3;
    private static final long PULSE_MS = 5000, PULSE_STEP_MS = 120;
    /**
     * Ritmo do ciclo, conforme o que pode acontecer a seguir (antes eram 500 ms sempre, também com a função desligada):
     * contagem a decorrer 0,5 s (bip e vidro mexido à mão); armado em P à espera da porta 1 s; em READY 2 s (armar pela
     * velocidade, marcha); parado 5 s (só para ver o READY de uma viagem nova). Com o fecho automático desligado não se
     * lê o estado do carro: só se prepara o controlo dos vidros para o botão "Fechar agora" (15 s até ficar pronto,
     * depois 60 s). A porta chega por callback e acorda o ciclo logo.
     */
    private static final long LOOP_COUNTING_MS = 500, LOOP_ARMED_P_MS = 1000, LOOP_READY_MS = 2000, LOOP_IDLE_MS = 5000,
            LOOP_OFF_PENDING_MS = 15_000, LOOP_OFF_MS = 60_000;

    private static Windows instance;

    public static synchronized Windows get(Context c) {
        if (instance == null) instance = new Windows(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Prefs prefs;
    private final ServiceLink hub;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowLogic logic;
    private Handler bg;
    private boolean started;

    // bibliotecas carapi (carregadas uma vez)
    private ClassLoader saicCl;
    private String saicPkg;
    private long saicRetryAt;
    private boolean saicScanned;
    private volatile Object vsClient;
    private volatile Method setWin, getWin;
    private boolean stateCbRegistered;
    private long stateCbRetryAt;

    private volatile boolean closing;
    private volatile String lastEvent = "";
    private float[] baseline;
    private ToneGenerator tone;
    private long nextBeep;

    private Windows(Context c) {
        ctx = c;
        prefs = new Prefs(c);
        hub = new ServiceLink(c, "vidros", ServiceLink.byAction(HUB_PKG, HUB_ACTION));
        hub.setOnConnected(() -> {
            if (bg != null) {
                bg.post(this::forgetClients);
                wake();
            }
        });
        logic = new WindowLogic(new WindowLogic.Events() {
            @Override
            public void countdown(int seconds) {
                baseline = positions();
                nextBeep = 0;
                toast(L.f(R.string.win_toast_countdown, seconds));
            }

            @Override
            public void cancelled(String reason) {
                baseline = null;
                stopBeep();
                toast(L.s(R.string.win_toast_cancel));
            }

            @Override
            public void close() {
                baseline = null;
                stopBeep();
                closeAll("automático");
            }

            @Override
            public void log(String msg) {
                lastEvent = msg;
                Diag.put("vidros.evento", msg);
            }
        }, this::config, this::allSurelyClosed);
    }

    /** Começa a vigiar o carro (uma vez por processo). */
    public synchronized void start() {
        if (started) return;
        started = true;
        HandlerThread th = new HandlerThread("mg4-vidros");
        th.start();
        bg = new Handler(th.getLooper());
        hub.start();
        bg.post(loop);
    }

    private WindowLogic.Config config() {
        WindowLogic.Config c = new WindowLogic.Config();
        c.enabled = prefs.winAuto();
        c.speedKmh = prefs.winSpeed();
        c.armMinutes = prefs.winMinutes();
        c.delaySec = prefs.winDelay();
        c.onDoorOpen = prefs.winOnDoorOpen();
        return c;
    }

    // =========================================================================================
    // Estado para as definições
    // =========================================================================================

    /** Há controlo dos vidros (bibliotecas carapi carregadas e serviço do carro ligado). */
    public boolean available() {
        return vsClient != null && setWin != null && hub.isConnected();
    }

    /** Linha de estado para a secção Vidros. */
    public String statusText() {
        if (!available()) return L.s(R.string.win_status_off);
        if (closing) return L.s(R.string.win_closing);
        if (logic.counting()) return L.f(R.string.win_status_counting, logic.secondsLeft(SystemClock.uptimeMillis()));
        if (!prefs.winAuto()) return L.s(R.string.win_status_ok);
        return logic.armed() ? L.s(R.string.win_status_armed)
                : L.f(R.string.win_status_not_armed, prefs.winSpeed(), prefs.winMinutes());
    }

    /** Fecha agora todos os vidros que não estão em "Desligado". Falso se não há controlo dos vidros. */
    public boolean closeNow() {
        if (bg == null || !available()) return false;
        bg.post(() -> {
            logic.cancel("fecho manual");
            closeAll("manual");
        });
        return true;
    }

    // =========================================================================================
    // Ciclo
    // =========================================================================================

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (Throwable e) {
                Diag.error("vidros", e.toString());
            }
            bg.postDelayed(this, nextDelayMs());
        }
    };

    private long nextDelayMs() {
        if (!prefs.winAuto()) return available() ? LOOP_OFF_MS : LOOP_OFF_PENDING_MS;
        if (logic.counting()) return LOOP_COUNTING_MS;
        if (logic.armed() && logic.gear() == WindowLogic.GEAR_P) return LOOP_ARMED_P_MS;
        if (logic.ready() == 1) return LOOP_READY_MS;
        return LOOP_IDLE_MS;
    }

    /** Corre o ciclo já (porta, ligação ao carro, opção mudada nas definições) em vez de esperar pelo intervalo. */
    public void wake() {
        Handler h = bg;
        if (h == null) return;
        h.post(() -> {
            h.removeCallbacks(loop);
            h.post(loop);
        });
    }

    private void step() {
        long now = SystemClock.uptimeMillis();
        IBinder h = hub.binder();
        if (h == null) return;
        if (!prefs.winAuto()) {
            // fecho automático desligado: nenhuma leitura do carro, só o controlo para o botão "Fechar agora"
            if (logic.counting()) logic.cancel("fecho automático desligado");
            ensureClients(h, null, now);
            Diag.putQuiet("vidros.estado", (available() ? "controlo ok (" + saicPkg + ")" : "sem controlo") + " · fecho automático desligado");
            return;
        }
        IBinder st = query(h, BIND_STATE);
        if (st != null) {
            try {
                Boolean ok = Rpc.call(st, STATE_DESC, ST_SERVICE_READY, Rpc.NONE, p -> p.readInt() != 0);
                if (ok != null && ok) {
                    logic.onReady(Rpc.callInt(st, STATE_DESC, ST_EPTRDY), now);
                    int g = Rpc.callInt(st, STATE_DESC, ST_GEAR);
                    if (g >= 0 && g <= 11) logic.onGear(g, now);
                }
            } catch (Exception e) {
                Diag.error("vidros.estado", e.toString());
            }
        }
        ensureClients(h, st, now);
        logic.tick(VehicleClient.speedKmh(), now);
        if (logic.counting()) {
            beep(now);
            if (windowMoved()) logic.cancel("vidro mexido à mão");
        }
        Diag.putQuiet("vidros.estado", (available() ? "controlo ok (" + saicPkg + ")" : "sem controlo")
                + " · callback porta " + (stateCbRegistered ? "sim" : "não")
                + " · READY " + logic.ready() + " · marcha " + logic.gear() + " · porta " + logic.door()
                + " · armado " + (logic.armed() ? "sim" : "não") + (logic.counting() ? " · contagem" : ""));
    }

    private IBinder query(IBinder h, int code) {
        try {
            return Rpc.callBinder(h, HUB, HUB_QUERY_CLIENT, p -> p.writeInt(code));
        } catch (Exception e) {
            return null;
        }
    }

    private void forgetClients() {
        vsClient = null;
        setWin = null;
        getWin = null;
        stateCbRegistered = false;
        saicRetryAt = 0;
        stateCbRetryAt = 0;
    }

    // =========================================================================================
    // Bibliotecas carapi da SAIC
    // =========================================================================================

    private void ensureClients(IBinder h, IBinder st, long now) {
        if (saicCl == null) {
            if (now < saicRetryAt) return;
            saicRetryAt = now + 30_000;
            loadSaic();
            if (saicCl == null) return;
        }
        if (vsClient == null) {
            IBinder vs = query(h, BIND_VS);
            if (vs != null) {
                try {
                    Class<?> cls = saicCl.loadClass(VS_CLIENT);
                    Object client = cls.getConstructor(IBinder.class).newInstance(vs);
                    setWin = cls.getMethod("setVehicleWindowStatus", int.class, int.class);
                    try {
                        getWin = cls.getMethod("getVehicleWindowValue", int.class);
                    } catch (NoSuchMethodException e) {
                        getWin = null;
                    }
                    vsClient = client;
                    StringBuilder pos = new StringBuilder();
                    for (int a = 0; a < 4; a++) pos.append(a == 0 ? "" : " ").append(position(a));
                    Diag.put("vidros.controlo", "pronto (" + saicPkg + ") · posições " + pos);
                } catch (Throwable e) {
                    Diag.error("vidros.controlo", e.toString());
                }
            }
        }
        if (!stateCbRegistered && st != null && now >= stateCbRetryAt) {
            stateCbRetryAt = now + 10_000;
            registerStateCallback(st);
        }
    }

    /**
     * Só se carrega código de apps de SISTEMA (instaladas na imagem do carro): uma app instalada pelo utilizador com um
     * nome com.saicmotor.* nunca corre dentro do launcher (que tem permissões de sistema).
     */
    private boolean isSystemApp(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Procura as classes carapi: primeiro nas apps conhecidas, depois (uma vez) nas com.saicmotor.* de sistema. */
    private void loadSaic() {
        Set<String> pkgs = new LinkedHashSet<>();
        for (String p : SAIC_PKGS) pkgs.add(p);
        if (!saicScanned) {
            saicScanned = true;
            try {
                for (android.content.pm.PackageInfo pi : ctx.getPackageManager().getInstalledPackages(0))
                    if (pi.packageName != null && pi.packageName.startsWith("com.saicmotor.")) pkgs.add(pi.packageName);
            } catch (Throwable ignored) {
            }
        }
        StringBuilder why = new StringBuilder();
        for (String p : pkgs) {
            if (!isSystemApp(p)) {
                if (why.length() < 300) why.append(p).append(": não é de sistema; ");
                continue;
            }
            try {
                // CONTEXT_IGNORE_SECURITY é obrigatório para carregar código de outra app; só chega aqui código de sistema
                Context pc = ctx.createPackageContext(p, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                ClassLoader cl = pc.getClassLoader();
                cl.loadClass(VS_CLIENT);
                saicCl = cl;
                saicPkg = p;
                Diag.put("vidros.carapi", "encontrada em " + p);
                return;
            } catch (Throwable e) {
                if (why.length() < 300) why.append(p).append(": ").append(e.getClass().getSimpleName()).append("; ");
            }
        }
        Diag.put("vidros.carapi", "não encontrada (" + why + ")");
    }

    /** Callback do estado do carro: porta do condutor (e marcha), entregues no fio dos vidros. */
    private void registerStateCallback(IBinder st) {
        try {
            Class<?> cls = saicCl.loadClass(STATE_CLIENT);
            Object client = cls.getConstructor(IBinder.class).newInstance(st);
            Method reg = null;
            for (Method m : cls.getMethods()) {
                if ((m.getName().equals("registerListener") || m.getName().equals("registListener")) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].isInterface()) {
                    reg = m;
                    break;
                }
            }
            if (reg == null) {
                Diag.put("vidros.porta", "registerListener não existe no CarStateClient");
                return;
            }
            Binder cb = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    try {
                        data.enforceInterface(STATE_CB_DESC);
                        if (code == CB_DOOR || code == CB_GEAR) {
                            int v = data.readInt();
                            bg.post(() -> {
                                long now = SystemClock.uptimeMillis();
                                if (code == CB_DOOR) logic.onDoor(v, now);
                                else logic.onGear(v, now);
                            });
                            wake();   // uma contagem pode ter começado: o ciclo passa logo a 0,5 s
                        }
                    } catch (Throwable ignored) {
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
            };
            Class<?> cbType = reg.getParameterTypes()[0];
            Object proxy = Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType}, (o, m, args) -> {
                if (m.getName().equals("asBinder")) return cb;
                Class<?> r = m.getReturnType();
                if (r == int.class) return 0;
                if (r == boolean.class) return false;
                if (r == float.class) return 0f;
                if (r == long.class) return 0L;
                if (r == double.class) return 0d;
                return null;
            });
            reg.invoke(client, proxy);
            stateCbRegistered = true;
            Diag.put("vidros.porta", "callback registado (" + reg.getName() + ")");
        } catch (Throwable e) {
            Diag.error("vidros.porta", e.toString());
        }
    }

    // =========================================================================================
    // Vidros
    // =========================================================================================

    /** Posição de um vidro (0 fechado … 255 aberto), -1 se não se lê. */
    private float position(int area) {
        if (vsClient == null || getWin == null) return -1f;
        try {
            Object v = getWin.invoke(vsClient, area);
            return v instanceof Number ? ((Number) v).floatValue() : -1f;
        } catch (Throwable e) {
            return -1f;
        }
    }

    private float[] positions() {
        float[] p = new float[4];
        for (int a = 0; a < 4; a++) p[a] = prefs.winMode(a) == Prefs.WIN_AUTO ? position(a) : -1f;
        return p;
    }

    /** Só vidros em AUTO têm sensor: se todos estão em AUTO e lidos fechados, não há nada a fazer. */
    private boolean allSurelyClosed() {
        for (int a = 0; a < 4; a++) {
            if (prefs.winMode(a) != Prefs.WIN_AUTO) return false;
            float p = position(a);
            if (p < 0f || p > 0.5f) return false;
        }
        return true;
    }

    /** Durante a contagem: um vidro com sensor que se mexe quer dizer que alguém está a usar os botões. */
    private boolean windowMoved() {
        float[] b = baseline;
        if (b == null) return false;
        for (int a = 0; a < 4; a++) {
            if (b[a] < 0f) continue;
            float now = position(a);
            if (now >= 0f && Math.abs(now - b[a]) > 2f) return true;
        }
        return false;
    }

    private void cmd(int area, int c) {
        try {
            setWin.invoke(vsClient, area, c);
        } catch (Throwable e) {
            Diag.error("vidros.comando", "vidro " + area + " → " + c + ": " + e);
        }
    }

    /** Fecha os vidros segundo o modo de cada um: AUTO = subida automática; PULSO = "subir" mantido 5 s; DESLIGADO = nada. */
    private void closeAll(String why) {
        if (closing) return;
        if (!available()) {
            Diag.put("vidros.fecho", why + ": sem controlo dos vidros");
            return;
        }
        closing = true;
        new Thread(() -> {
            StringBuilder s = new StringBuilder(why + ":");
            try {
                java.util.List<Integer> hold = new java.util.ArrayList<>();
                for (int a = 0; a < 4; a++) {
                    int m = prefs.winMode(a);
                    if (m == Prefs.WIN_AUTO) {
                        cmd(a, CMD_AUTO_UP);
                        s.append(" ").append(a).append("=auto");
                    } else if (m == Prefs.WIN_PULSE) {
                        hold.add(a);
                        s.append(" ").append(a).append("=pulso");
                    } else {
                        s.append(" ").append(a).append("=desligado");
                    }
                }
                if (!hold.isEmpty()) {
                    long end = SystemClock.uptimeMillis() + PULSE_MS;
                    while (SystemClock.uptimeMillis() < end) {
                        for (int a : hold) cmd(a, CMD_UP);
                        SystemClock.sleep(PULSE_STEP_MS);
                    }
                    for (int a : hold) cmd(a, CMD_STOP);
                }
                Diag.put("vidros.fecho", s.toString());
            } finally {
                closing = false;
            }
        }, "mg4-vidros-fecho").start();
    }

    // =========================================================================================
    // Aviso: bip e mensagem
    // =========================================================================================

    private void beep(long now) {
        if (!prefs.winBeep() || now < nextBeep) return;
        nextBeep = now + 1000;
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, prefs.winBeepVolume());
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
        } catch (Throwable e) {
            Diag.error("vidros.bip", e.toString());
        }
    }

    private void stopBeep() {
        if (tone != null) {
            try {
                tone.release();
            } catch (Throwable ignored) {
            }
            tone = null;
        }
    }

    private void toast(String text) {
        main.post(() -> {
            try {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }
}
