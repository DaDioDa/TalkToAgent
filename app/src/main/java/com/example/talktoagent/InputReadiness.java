package com.example.talktoagent;

/** Idle readiness is a local prerequisite check, not a live Gemini connection or a paste guarantee. */
final class InputReadiness {
    enum Kind { READY, UNAVAILABLE, FAILED, BUSY }

    static final class Display {
        final Kind kind;
        final String label;
        Display(Kind kind, String label) { this.kind = kind; this.label = label; }
        boolean ready() { return kind == Kind.READY; }
        String label() { return label; }
    }

    static Display evaluate(boolean receiverAuthenticated, boolean hasGeminiKey,
                            boolean microphoneAllowed, boolean voiceActive, String knownFailure) {
        if (knownFailure != null && !knownFailure.isEmpty())
            return new Display(Kind.FAILED, "輸入未就緒：" + knownFailure);
        if (voiceActive)
            return new Display(Kind.BUSY, "輸入中：等待本次辨識或傳送完成");
        if (!hasGeminiKey)
            return new Display(Kind.UNAVAILABLE, "輸入未就緒：請儲存 Gemini 金鑰");
        if (!microphoneAllowed)
            return new Display(Kind.UNAVAILABLE, "輸入未就緒：需要麥克風權限");
        if (!receiverAuthenticated)
            return new Display(Kind.UNAVAILABLE, "輸入未就緒：Receiver 尚未驗證");
        return new Display(Kind.READY, "輸入就緒：按下時才連接 Gemini；請先在 Windows 放好游標");
    }
}
