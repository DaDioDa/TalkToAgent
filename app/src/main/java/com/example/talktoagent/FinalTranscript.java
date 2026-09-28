package com.example.talktoagent;

/** Collects finalized segments of one press, then delivers them once after the stream settles. */
final class FinalTranscript {
    static final long QUIET_PERIOD_MS = 1000;

    private final StringBuilder segments = new StringBuilder();
    private long releasedAt = -1;
    private long streamEndedAt = -1;
    private long lastFinalAt = -1;
    private boolean delivered;

    void release(long now) {
        releasedAt = now;
    }

    void streamEnded(long now) {
        streamEndedAt = now;
    }

    boolean addFinal(String text, long now) {
        if (delivered || text == null || text.isEmpty()) return false;
        segments.append(text);
        lastFinalAt = now;
        return true;
    }

    String takeIfSettled(long now) {
        if (delivered || releasedAt < 0 || streamEndedAt < 0 || lastFinalAt < 0
                || now - Math.max(streamEndedAt, lastFinalAt) < QUIET_PERIOD_MS) {
            return null;
        }
        delivered = true;
        return segments.toString();
    }
}
