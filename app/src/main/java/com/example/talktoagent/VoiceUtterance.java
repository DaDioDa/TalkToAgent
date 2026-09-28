package com.example.talktoagent;

import java.util.function.Consumer;

/** One press/release transaction; only a finalized event after release can leave this boundary. */
final class VoiceUtterance {
    private enum Phase { IDLE, RECORDING, WAITING, DONE }
    private Phase phase = Phase.IDLE;
    private final Consumer<String> delivery;
    private final Consumer<String> status;

    VoiceUtterance(Consumer<String> delivery, Consumer<String> status) {
        this.delivery = delivery;
        this.status = status;
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
        status.accept("等待轉錄定稿");
        return true;
    }

    void interim(String text) {
        if (phase == Phase.RECORDING && text != null) status.accept("辨識中（暫定）：" + text);
    }

    void finalText(String text) {
        if (phase != Phase.WAITING) return;
        phase = Phase.DONE;
        if (!ManualTextProtocol.isValidFinalText(text)) {
            status.accept("定稿為空或超限，未傳送");
            return;
        }
        status.accept("定稿已送出一次，等待 Receiver 確認");
        delivery.accept(text);
    }

    void timeout() { fail("等待轉錄定稿逾時，未傳送"); }

    void fail(String reason) {
        if (phase == Phase.RECORDING || phase == Phase.WAITING) {
            phase = Phase.DONE;
            status.accept(reason);
        }
    }

    boolean active() { return phase == Phase.RECORDING || phase == Phase.WAITING; }
}
