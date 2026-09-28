package com.example.talktoagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A controlled Gemini final at the voice-to-Receiver seam on the real Android ICU runtime. */
@RunWith(AndroidJUnit4.class)
public class TraditionalVoiceDeliveryTest {
    private final List<String> frames = new ArrayList<>();
    private final VoiceUtterance utterance = new VoiceUtterance(
            text -> frames.add(ManualTextProtocol.finalText(text)),
            status -> {}, TraditionalText::convert);

    @Test public void simplifiedFinalIsTraditionalBeforeTransportWhileLatinAndPunctuationSurvive() {
        assertTrue(utterance.press(true, true));
        utterance.interim("测试 AI 123，录音功能。");
        assertTrue(frames.isEmpty());
        assertTrue(utterance.release());
        utterance.finalText("测试 AI 123，录音功能。");
        utterance.finalText("late duplicate");
        assertEquals(List.of(ManualTextProtocol.finalText("測試 AI 123，錄音功能。")), frames);
    }

    @Test public void existingTraditionalAndWhitespaceAreUntouched() {
        assertTrue(utterance.press(true, true));
        assertTrue(utterance.release());
        utterance.finalText("繁體 English 123!\n");
        assertEquals(List.of(ManualTextProtocol.finalText("繁體 English 123!\n")), frames);
    }
}
