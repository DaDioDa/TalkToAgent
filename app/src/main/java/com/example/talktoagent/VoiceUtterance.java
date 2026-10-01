package com.example.talktoagent;

import java.util.function.Consumer;
import java.util.function.Function;

/** UI-thread serialized voice transaction shared by the production host and deterministic tests.
 * Session tokens reject stale recognition/results; only validated finalized text is dispatched.
 * The three-argument constructor and press/release entry retain legacy delivery regression callers. */
final class VoiceUtterance {
    enum Phase { IDLE, PREPARING, RECORDING, WAITING, SENDING, COMPLETED, CANCELLED, FAILED, UNKNOWN, DONE }
    enum Effect { CAPTURE, FINISH, WARNING, CLOSE, FINALIZATION_DEADLINE }
    private java.util.function.LongSupplier clock;
    private Consumer<Effect> effects;
    private long generation, deadline, preparationDeadline, finalizationDeadline;
    private boolean warned, manualOperation;

    VoiceUtterance(Consumer<String> delivery, Consumer<String> status,
                   Function<String, String> finalizeText, java.util.function.LongSupplier clock,
                   Consumer<Effect> effects) {
        this(delivery, status, finalizeText);
        this.clock = clock; this.effects = effects;
    }

    long start(boolean ready) {
        if (!ready || busy()) return -1;
        generation++;
        deadline = clock.getAsLong() + 45000;
        preparationDeadline = clock.getAsLong() + 10000;
        warned = false;
        manualOperation = false;
        phase = Phase.PREPARING;
        status.accept("準備中，尚未收音");
        return generation;
    }

    boolean submitManual(boolean ready, String text) {
        if (!ready || busy() || !ManualTextProtocol.isValidFinalText(text)) return false;
        generation++;
        manualOperation = true;
        phase = Phase.WAITING;
        finalText(generation, text);
        return true;
    }
    /** Restore only non-content result metadata, never a live operation or a retry. */
    void restoreResult(Phase previous) {
        if (phase != Phase.IDLE) return;
        switch (previous) {
            case SENDING: case UNKNOWN: phase = Phase.UNKNOWN; break;
            case PREPARING: case RECORDING: case WAITING: phase = Phase.CANCELLED; break;
            case COMPLETED: case CANCELLED: case FAILED: phase = previous; break;
            default: break;
        }
    }
    boolean manualOperation() { return manualOperation; }
    long session() { return generation; }
    Phase phase() { return phase; }
    boolean busy() { return active() || phase == Phase.SENDING; }
    int remainingSeconds() { return (int) Math.max(0, (deadline - clock.getAsLong() + 999) / 1000); }
    void microphoneStarted(long session) {
        if (session != generation || phase != Phase.PREPARING) return;
        if (clock.getAsLong() >= preparationDeadline) {
            fail(session, "Gemini 初始化逾時，未收音或傳送"); return;
        }
        phase = Phase.RECORDING;
        effects.accept(Effect.CAPTURE);
        status.accept("收音中，可以說話");
        tick();
    }
    void tick() {
        long now = clock.getAsLong();
        if (phase == Phase.PREPARING && now >= preparationDeadline) {
            fail(generation, "Gemini 初始化逾時，未收音或傳送"); return;
        }
        if (phase == Phase.WAITING && now >= finalizationDeadline) {
            effects.accept(Effect.FINALIZATION_DEADLINE);
            // Give the real transcript accumulator its existing last quiet-window check.
            if (phase == Phase.WAITING) fail(generation, "等待轉錄定稿逾時，未傳送");
            return;
        }
        if (phase != Phase.PREPARING && phase != Phase.RECORDING) return;
        if (remainingSeconds() <= 5 && !warned) { warned = true; effects.accept(Effect.WARNING); }
        if (remainingSeconds() == 0) {
            if (phase == Phase.RECORDING) finish();
            else fail(generation, "準備逾時，未傳送");
        }
    }
    boolean finish() {
        if (!release()) return false;
        effects.accept(Effect.FINISH);
        return true;
    }
    void cancel() {
        if (!active()) return;
        generation++;
        phase = Phase.CANCELLED;
        effects.accept(Effect.CLOSE);
        status.accept("已取消，未傳送");
    }
    void leave() {
        if (phase == Phase.SENDING) { phase = Phase.UNKNOWN; status.accept("輸入結果不明，不自動重送"); }
        else cancel();
    }
    void finalText(long session, String text) { if (session == generation) finalText(text); }
    void fail(long session, String reason) { if (session == generation) fail(reason); }
    /** Only for a local refusal before transport submission, never for a lost acknowledgement. */
    void notSubmitted(long session, String reason) {
        if (session != generation || phase != Phase.SENDING) return;
        phase = Phase.FAILED;
        status.accept(reason);
    }
    void outcome(long session, boolean completed) {
        if (session != generation || (phase != Phase.SENDING && phase != Phase.UNKNOWN)) return;
        phase = completed ? Phase.COMPLETED : Phase.UNKNOWN;
        status.accept(completed ? "貼上操作完成，不保證目標程式接受文字" : "輸入結果不明，不自動重送");
    }
    private Phase phase = Phase.IDLE;
    private final Consumer<String> delivery;
    private final Consumer<String> status;
    private final Function<String, String> finalizeText;

    VoiceUtterance(Consumer<String> delivery, Consumer<String> status,
                   Function<String, String> finalizeText) {
        this.delivery = delivery;
        this.status = status;
        this.finalizeText = finalizeText;
    }

    boolean press(boolean permission, boolean receiverReady) {
        if (phase == Phase.RECORDING || phase == Phase.WAITING) return false;
        if (!permission) { status.accept("麥克風權限遭拒，未收音或傳送"); return false; }
        if (!receiverReady) { status.accept("Receiver 尚未驗證，未收音或傳送"); return false; }
        phase = Phase.RECORDING;
        status.accept("錄音中／辨識中");
        return true;
    }

    boolean release() {
        if (phase != Phase.RECORDING) return false;
        phase = Phase.WAITING;
        if (clock != null) finalizationDeadline = clock.getAsLong() + 8000;
        status.accept("等待轉錄定稿");
        return true;
    }

    void interim(String text) {
        if (phase == Phase.RECORDING && text != null) status.accept("辨識中（暫定）：" + text);
    }

    void finalText(String text) {
        if (phase != Phase.WAITING) return;
        phase = effects == null ? Phase.DONE : Phase.FAILED;
        if (!ManualTextProtocol.isValidFinalText(text)) {
            status.accept("定稿為空或超限，未傳送");
            return;
        }
        final String converted;
        try {
            converted = finalizeText.apply(text);
        } catch (RuntimeException failure) {
            status.accept("繁體轉換失敗，未傳送");
            return;
        }
        if (!ManualTextProtocol.isValidFinalText(converted)) {
            status.accept("轉換後定稿為空或超限，未傳送");
            return;
        }
        if (effects != null) phase = Phase.SENDING;
        status.accept("定稿已送出一次，等待 Receiver 確認");
        delivery.accept(converted);
    }

    void timeout() { fail("等待轉錄定稿逾時，未傳送"); }

    void fail(String reason) {
        if (active()) {
            phase = effects == null ? Phase.DONE : Phase.FAILED;
            if (effects != null) effects.accept(Effect.CLOSE);
            status.accept(reason);
        }
    }

    boolean active() { return phase == Phase.PREPARING || phase == Phase.RECORDING || phase == Phase.WAITING; }
}
