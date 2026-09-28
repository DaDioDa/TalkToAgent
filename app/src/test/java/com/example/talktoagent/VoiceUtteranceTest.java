package com.example.talktoagent;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class VoiceUtteranceTest {
    private final List<String> sent = new ArrayList<>();
    private final List<String> states = new ArrayList<>();
    // The delivery seam encodes exactly the frame consumed by the existing authenticated Receiver.
    private final VoiceUtterance utterance = new VoiceUtterance(
            text -> sent.add(ManualTextProtocol.finalText(text)), states::add, Function.identity());

    @Test public void provisionalRevisionNeverPastesButFinalAfterReleaseDoesOnce() {
        assertTrue(utterance.press(true, true));
        utterance.interim("错误");
        utterance.interim("正確 English");
        assertTrue(sent.isEmpty());
        utterance.release();
        utterance.finalText("正確 English");
        utterance.finalText("duplicate");
        assertEquals(List.of("{\"type\":\"final_text\",\"text\":\"正確 English\"}"), sent);
        assertTrue(states.contains("等待轉錄定稿"));
    }

    @Test public void emptyAndTimeoutNeverSendInterim() {
        utterance.press(true, true);
        utterance.interim("暫定");
        utterance.release();
        utterance.finalText("  ");
        assertTrue(sent.isEmpty());
        assertEquals("定稿為空或超限，未傳送", states.get(states.size()-1));
        assertTrue(utterance.press(true, true));
        utterance.interim("暫定");
        utterance.release();
        utterance.timeout();
        utterance.finalText("遲到");
        assertTrue(sent.isEmpty());
    }

    @Test public void deniedPermissionAndNoReceiverCannotStartOrSend() {
        assertFalse(utterance.press(false, true));
        utterance.release();
        utterance.finalText("不應貼上");
        assertFalse(utterance.press(true, false));
        assertTrue(sent.isEmpty());
        assertTrue(states.get(0).contains("麥克風權限"));
    }

    @Test public void finalBeforeReleaseCannotPasteUntilAfterReleaseFinalization() {
        utterance.press(true, true);
        utterance.finalText("停頓時的定稿");
        assertTrue(sent.isEmpty());
        utterance.release();
        utterance.finalText("完整定稿");
        assertEquals(List.of("{\"type\":\"final_text\",\"text\":\"完整定稿\"}"), sent);
    }

    @Test public void conversionFailureOrOversizedResultNeverSends() {
        List<String> delivered = new ArrayList<>();
        List<String> updates = new ArrayList<>();
        VoiceUtterance failed = new VoiceUtterance(delivered::add, updates::add,
                text -> { throw new IllegalStateException("sensitive text"); });
        failed.press(true, true);
        failed.release();
        failed.finalText("測試");
        assertTrue(delivered.isEmpty());
        assertEquals("繁體轉換失敗，未傳送", updates.get(updates.size() - 1));

        VoiceUtterance oversized = new VoiceUtterance(delivered::add, updates::add,
                text -> "測".repeat(1500));
        oversized.press(true, true);
        oversized.release();
        oversized.finalText("测试");
        assertTrue(delivered.isEmpty());
        assertEquals("轉換後定稿為空或超限，未傳送", updates.get(updates.size() - 1));
    }

    @Test public void failureAndCancelledRecordingNeverSend() {
        utterance.press(true, true);
        utterance.finalText("先前停頓");
        utterance.release();
        utterance.fail("辨識失敗");
        utterance.finalText("遲到");
        assertTrue(sent.isEmpty());
    }
}
