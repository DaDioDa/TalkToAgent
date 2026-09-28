package com.example.talktoagent;

import static org.junit.Assert.*;
import org.junit.Test;

public class InputReadinessTest {
    @Test public void onlyAuthenticatedKeyAndMicrophoneTogetherCanShowGreen() {
        assertFalse(InputReadiness.evaluate(false, false, false, false, null).ready());
        assertFalse(InputReadiness.evaluate(true, false, true, false, null).ready());
        assertFalse(InputReadiness.evaluate(true, true, false, false, null).ready());
        InputReadiness.Display ready = InputReadiness.evaluate(true, true, true, false, null);
        assertTrue(ready.ready());
        assertTrue(ready.label().contains("輸入就緒"));
        assertTrue(ready.label().contains("按下時"));
    }

    @Test public void knownFailureAndActiveSpeechCannotClaimReady() {
        InputReadiness.Display failed = InputReadiness.evaluate(true, true, true, false,
                "Gemini 連線失敗，未傳送");
        assertFalse(failed.ready());
        assertTrue(failed.label().contains("Gemini 連線失敗"));
        assertFalse(InputReadiness.evaluate(true, true, true, true, null).ready());
        assertTrue(InputReadiness.evaluate(true, true, true, false, null).ready());
    }
}
