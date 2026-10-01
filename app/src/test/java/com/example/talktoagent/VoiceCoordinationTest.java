package com.example.talktoagent;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.function.Function;

public class VoiceCoordinationTest {
    @Test public void manualDiagnosticSubmissionDoesNotPretendToCapture() {
        List<String> sent = new ArrayList<>();
        List<VoiceUtterance.Effect> effects = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, Function.identity(), () -> 0, effects::add);
        assertTrue(voice.submitManual(true, "benign diagnostic"));
        assertFalse(voice.submitManual(true, "duplicate"));
        assertEquals(List.of("benign diagnostic"), sent);
        assertTrue(effects.isEmpty());
    }

    @Test public void initializationAndFinalizationDeadlinesNeverSendLateFinals() {
        long[] now = {0};
        List<String> sent = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, Function.identity(), () -> now[0], e -> {});
        long first = voice.start(true);
        now[0] = 10000; voice.tick();
        assertEquals(VoiceUtterance.Phase.FAILED, voice.phase());
        voice.microphoneStarted(first); voice.finalText(first, "late");
        long next = voice.start(true); voice.microphoneStarted(next); voice.finish();
        now[0] = 18000; voice.tick(); voice.finalText(next, "late final");
        assertEquals(VoiceUtterance.Phase.FAILED, voice.phase());
        assertTrue(sent.isEmpty());
    }

    @Test public void cancelAndOldCallbacksCannotReviveOrOverwriteNewSession() {
        List<String> sent = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, Function.identity(), () -> 0, e -> {});
        long old = voice.start(true); voice.cancel();
        voice.microphoneStarted(old); voice.finalText(old, "discarded");
        assertEquals(VoiceUtterance.Phase.CANCELLED, voice.phase());
        long fresh = voice.start(true);
        voice.microphoneStarted(old); voice.fail(old, "old failure");
        assertEquals(VoiceUtterance.Phase.PREPARING, voice.phase());
        voice.microphoneStarted(fresh); voice.finish();
        voice.finalText(old, "old"); voice.finalText(fresh, "new");
        assertEquals(List.of("new"), sent);
    }

    @Test public void leaveOrLockBeforeDispatchDiscardsEveryUnsentPhase() {
        for (int stage = 0; stage < 3; stage++) {
            List<String> sent = new ArrayList<>();
            VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, Function.identity(), () -> 0, e -> {});
            long session = voice.start(true);
            if (stage > 0) voice.microphoneStarted(session);
            if (stage > 1) voice.finish();
            voice.leave(); voice.microphoneStarted(session); voice.finalText(session, "late");
            assertEquals(VoiceUtterance.Phase.CANCELLED, voice.phase());
            assertTrue(sent.isEmpty());
        }
    }

    @Test public void submittedWorkCannotBeCancelledAndUnknownSurvivesReadinessUntilExplicitStart() {
        List<String> sent = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, Function.identity(), () -> 0, e -> {});
        long session = voice.start(true); voice.microphoneStarted(session); voice.finish(); voice.finalText(session, "one");
        voice.cancel(); assertEquals(VoiceUtterance.Phase.SENDING, voice.phase());
        assertEquals(-1, voice.start(true));
        voice.leave(); assertEquals(VoiceUtterance.Phase.UNKNOWN, voice.phase());
        assertEquals(-1, voice.start(false));
        assertEquals(VoiceUtterance.Phase.UNKNOWN, voice.phase());
        voice.outcome(session, true); voice.leave();
        assertEquals(VoiceUtterance.Phase.COMPLETED, voice.phase());
        long fresh = voice.start(true);
        voice.outcome(session, false);
        assertEquals(VoiceUtterance.Phase.PREPARING, voice.phase());
        assertNotEquals(session, fresh);
        assertEquals(List.of("one"), sent);
    }

    @Test public void manualFinishBeforeDeadlineAndRepeatedClicksHaveOneSubmission() {
        long[] now = {0}; List<String> sent = new ArrayList<>();
        List<VoiceUtterance.Effect> effects = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, Function.identity(), () -> now[0], effects::add);
        long session = voice.start(true); voice.microphoneStarted(session);
        assertEquals(-1, voice.start(true));
        now[0] = 45000; assertTrue(voice.finish()); voice.tick(); assertFalse(voice.finish());
        voice.interim("partial"); voice.finalText(session, "final"); voice.finalText(session, "duplicate");
        assertEquals(List.of("final"), sent);
        assertEquals(1, Collections.frequency(effects, VoiceUtterance.Effect.CAPTURE));
        assertEquals(1, Collections.frequency(effects, VoiceUtterance.Effect.FINISH));
    }

    @Test public void invalidEmptyOversizedAndConversionFailureNeverSubmit() {
        for (String text : List.of("", "  ", "x".repeat(4097))) {
            List<String> sent = new ArrayList<>();
            VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, Function.identity(), () -> 0, e -> {});
            long session = voice.start(true); voice.microphoneStarted(session); voice.finish(); voice.finalText(session, text);
            assertEquals(VoiceUtterance.Phase.FAILED, voice.phase()); assertTrue(sent.isEmpty());
        }
        List<String> sent = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, t -> { throw new IllegalArgumentException(); }, () -> 0, e -> {});
        long session = voice.start(true); voice.microphoneStarted(session); voice.finish(); voice.finalText(session, "test");
        assertEquals(VoiceUtterance.Phase.FAILED, voice.phase()); assertTrue(sent.isEmpty());
    }

    @Test public void recreationRestoresOnlyResultNeverRecordingOrSubmission() {
        List<String> sent = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, Function.identity(), () -> 0, e -> {});
        voice.restoreResult(VoiceUtterance.Phase.SENDING);
        assertEquals(VoiceUtterance.Phase.UNKNOWN, voice.phase());
        assertFalse(voice.busy());
        assertEquals(-1, voice.start(false));
        assertEquals(VoiceUtterance.Phase.UNKNOWN, voice.phase());
        assertTrue(sent.isEmpty());
    }

    @Test public void localTransportRefusalIsFailureNotCancelledOrSubmitted() {
        VoiceUtterance[] holder = new VoiceUtterance[1];
        holder[0] = new VoiceUtterance(t -> holder[0].notSubmitted(holder[0].session(), "Receiver 未就緒，未傳送"),
                s -> {}, Function.identity(), () -> 0, e -> {});
        VoiceUtterance voice = holder[0];
        long session = voice.start(true); voice.microphoneStarted(session); voice.finish(); voice.finalText(session, "benign");
        voice.leave();
        assertEquals(VoiceUtterance.Phase.FAILED, voice.phase());
    }

    @Test public void lateMicrophoneStartCannotBeatInitializationDeadlineBetweenTicks() {
        long[] now = {0}; List<VoiceUtterance.Effect> effects = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(t -> fail("must not send"), s -> {}, Function.identity(), () -> now[0], effects::add);
        long session = voice.start(true);
        now[0] = 10001; voice.microphoneStarted(session);
        assertEquals(VoiceUtterance.Phase.FAILED, voice.phase());
        assertFalse(effects.contains(VoiceUtterance.Effect.CAPTURE));
    }

    @Test public void deadlineIncludesPreparationAndFinishesOnce() {
        long[] now = {100};
        List<String> sent = new ArrayList<>();
        List<VoiceUtterance.Effect> effects = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, Function.identity(), () -> now[0], effects::add);
        long session = voice.start(true);
        assertEquals(VoiceUtterance.Phase.PREPARING, voice.phase());
        now[0] = 5100;
        voice.microphoneStarted(session);
        assertEquals(40, voice.remainingSeconds());
        now[0] = 40100;
        voice.tick(); voice.tick();
        assertEquals(1, Collections.frequency(effects, VoiceUtterance.Effect.WARNING));
        now[0] = 45100;
        voice.tick(); voice.finish();
        assertEquals(1, Collections.frequency(effects, VoiceUtterance.Effect.FINISH));
        voice.finalText(session, "benign test"); voice.finalText(session, "duplicate");
        assertEquals(List.of("benign test"), sent);
        assertEquals(VoiceUtterance.Phase.SENDING, voice.phase());
    }
}
