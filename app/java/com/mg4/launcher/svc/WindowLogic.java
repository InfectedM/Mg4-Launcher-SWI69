package com.mg4.launcher.svc;

/**
 * Vidros: lógica do fecho automático ao sair do carro, sem dependências do Android (testável fora do carro).
 * <p>
 * Comportamento (o mesmo do WinClose, reescrito para o launcher):
 * <ul>
 *   <li><b>Armar</b>: em cada viagem (READY 0 → 1) o fecho fica armado quando a velocidade passa o limite ou quando o
 *       carro está em READY há mais do que o tempo mínimo;</li>
 *   <li><b>Disparar</b>: com o carro em P e armado, quando a porta do condutor abre (ou fecha, conforme a escolha)
 *       começa a contagem; no fim da contagem fecham-se os vidros;</li>
 *   <li><b>Cancelar</b>: a porta volta a abrir, sai-se de P, o carro volta a READY ou um vidro mexe-se durante a
 *       contagem. Depois de um disparo há 60 s de pausa (reabrir a porta não volta a disparar logo).</li>
 * </ul>
 * O tempo vem de fora ({@code now}, em ms) e tudo corre no mesmo fio (o do serviço dos vidros).
 */
public final class WindowLogic {

    /** Marcha do serviço de estado SAIC (ICarStateService / ICarStateCallback): 1 = P. */
    public static final int GEAR_P = 1;
    /** Sensor da porta do condutor (ICarStateCallback): 0 = aberta, outros valores = a fechar / fechada. */
    public static final int DOOR_OPEN = 0;
    public static final long COOLDOWN_MS = 60_000L;

    public interface Events {
        /** Começou a contagem (segundos até fechar). */
        void countdown(int seconds);

        /** Contagem cancelada antes de fechar. */
        void cancelled(String reason);

        /** Fim da contagem: fechar os vidros agora. */
        void close();

        /** Linha para os registos (diagnóstico). */
        void log(String msg);
    }

    /** Configuração lida das definições a cada evento (pode mudar sem reiniciar). */
    public static final class Config {
        public boolean enabled;
        public int speedKmh = 20;
        public int armMinutes = 5;
        public int delaySec = 5;
        /** true = a contagem começa quando a porta abre; false = quando fecha depois de ter aberto em P. */
        public boolean onDoorOpen = true;
    }

    public interface ConfigSource {
        Config get();
    }

    /** Diz se todos os vidros estão de certeza fechados (leitura fiável); se sim, não há contagem nem bip. */
    public interface ClosedCheck {
        boolean allSurelyClosed();
    }

    private final Events ev;
    private final ConfigSource cfg;
    private final ClosedCheck closedCheck;

    private int ready = -1;          // -1 desconhecido, 0 não, 1 READY
    private long readySince;
    private int gear = -1;
    private int door = -1;
    private boolean armed;
    private boolean doorOpenedInP;
    private long lastTrigger = Long.MIN_VALUE / 2;
    private long deadline = -1;      // >= 0 enquanto há contagem

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

    /** Segundos que faltam na contagem (0 se não há contagem). */
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

    /** READY lido do carro (1 / 0; outros valores ignorados). */
    public void onReady(int r, long now) {
        if (r != 0 && r != 1) return;
        if (r == ready) return;
        int prev = ready;
        ready = r;
        if (r == 1) {
            // viagem nova: volta a ser preciso armar
            if (prev == 0) ev.log("READY: viagem nova");
            armed = false;
            doorOpenedInP = false;
            readySince = now;
            cancel("carro em READY");
        } else if (gear != GEAR_P) {
            // desligado fora de P: estado inesperado, não se mantém nada
            armed = false;
            doorOpenedInP = false;
        }
        // desligado em P: mantém-se armado (num elétrico desliga-se antes de abrir a porta)
    }

    /** Marcha lida do carro. */
    public void onGear(int g, long now) {
        if (g < 0 || g == gear) return;
        gear = g;
        if (g != GEAR_P) {
            doorOpenedInP = false;
            cancel("saiu de P");
        } else if (cfg.get().onDoorOpen && door == DOOR_OPEN) {
            maybeTrigger("P com a porta já aberta", now);
        }
    }

    /** Sensor da porta do condutor. */
    public void onDoor(int v, long now) {
        if (v == door) return;
        door = v;
        Config c = cfg.get();
        if (v == DOOR_OPEN) {
            cancel("porta aberta");
            if (gear == GEAR_P) doorOpenedInP = true;
            if (c.onDoorOpen) maybeTrigger("porta aberta", now);
        } else {
            // a porta manda vários valores ao fechar (encostada, trinco): não se cancela aqui
            if (!c.onDoorOpen && doorOpenedInP && gear == GEAR_P) {
                doorOpenedInP = false;
                maybeTrigger("porta fechada", now);
            }
        }
    }

    /** Chamado com frequência (≈ 2 vezes por segundo): armar pela velocidade / tempo e fim da contagem. */
    public void tick(float speedKmh, long now) {
        Config c = cfg.get();
        if (!armed && ready == 1) {
            if (!Float.isNaN(speedKmh) && speedKmh >= c.speedKmh) {
                armed = true;
                ev.log("armado: " + Math.round(speedKmh) + " km/h ≥ " + c.speedKmh);
            } else if (now - readySince >= c.armMinutes * 60_000L) {
                armed = true;
                ev.log("armado: " + c.armMinutes + " min em READY");
            }
        }
        if (deadline >= 0 && now >= deadline) {
            deadline = -1;
            ev.log("fim da contagem: fechar");
            ev.close();
        }
    }

    /** Cancela a contagem (vidro mexido à mão, botão, etc.). */
    public void cancel(String reason) {
        if (deadline < 0) return;
        deadline = -1;
        ev.log("cancelado: " + reason);
        ev.cancelled(reason);
    }

    private void maybeTrigger(String why, long now) {
        Config c = cfg.get();
        if (!c.enabled) {
            ev.log(why + ": ignorado (fecho automático desligado)");
            return;
        }
        if (gear != GEAR_P) return;
        if (!armed) {
            ev.log(why + ": ignorado (não armado)");
            return;
        }
        if (now - lastTrigger < COOLDOWN_MS) {
            ev.log(why + ": ignorado (pausa de 60 s)");
            return;
        }
        if (deadline >= 0) return;
        if (closedCheck != null && closedCheck.allSurelyClosed()) {
            ev.log(why + ": vidros já fechados, nada a fazer");
            armed = false;
            return;
        }
        lastTrigger = now;
        armed = false;
        deadline = now + Math.max(0, c.delaySec) * 1000L;
        ev.log(why + ": contagem de " + c.delaySec + " s");
        ev.countdown(c.delaySec);
        if (c.delaySec <= 0) tick(Float.NaN, now);
    }
}
