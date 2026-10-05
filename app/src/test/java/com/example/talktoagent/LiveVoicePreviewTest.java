package com.example.talktoagent;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Approved production utterance seam; speculative hypotheses never enter delivery. */
public class LiveVoicePreviewTest {
    @Test public void missingSamplesExpireAndNewTrendStartsEmpty() {
        long[] now = {0};
        VoiceUtterance voice = new VoiceUtterance(s -> {}, s -> {}, s -> s, () -> now[0], e -> {});
        long session = voice.start(true); voice.microphoneStarted(session);
        assertFalse(voice.hasVolume());
        voice.volume(session, .8f); now[0] = 500;
        assertFalse(voice.hasVolume()); assertEquals(0f, voice.volumeTrend()[31], 0f);
        voice.volume(session, .2f);
        assertEquals(0f, voice.volumeTrend()[30], 0f);
        assertEquals(.2f, voice.volumeTrend()[31], 0f);
    }
    @Test public void volumeUsesOnlyCurrentCaptureAndStopsAtEveryExit() {
        VoiceUtterance voice = new VoiceUtterance(s -> {}, s -> {}, s -> s, () -> 0L, e -> {});
        long old = voice.start(true);
        assertFalse(voice.volume(old, .5f)); // preparing is not capture
        voice.microphoneStarted(old);
        assertTrue(voice.volume(old, 0f));
        assertEquals(0f, voice.volumeTrend()[31], 0f);
        voice.volume(old, .25f); voice.volume(old, .75f);
        assertEquals(.25f, voice.volumeTrend()[30], 0f);
        assertEquals(.75f, voice.volumeTrend()[31], 0f);
        assertFalse(voice.volume(old, Float.NaN));
        assertFalse(voice.volume(old, -1f));
        voice.finish();
        assertFalse(voice.volume(old, 1f));
        assertFalse(voice.hasVolume());
        assertEquals(0f, voice.volumeTrend()[31], 0f);
        voice.leave();
        long current = voice.start(true); voice.microphoneStarted(current);
        voice.interim(current, "新段");
        assertFalse(voice.volume(old, 1f));
        assertFalse(voice.interim(old, "舊段"));
        assertEquals("新段", voice.preview());
        voice.cancel();
        assertEquals("", voice.preview());
        assertFalse(voice.volume(current, 1f));
    }
    @Test public void previewNeverFillsAnInvalidFinalAndFailureOrLeaveClearsIt() {
        List<String> sent = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, s -> s, () -> 0L, e -> {});
        long session = voice.start(true); voice.microphoneStarted(session);
        voice.interim(session, "這不是定稿"); voice.finish(); voice.finalText(session, " ");
        assertTrue(sent.isEmpty()); assertEquals("", voice.preview());
        session = voice.start(true); voice.microphoneStarted(session);
        assertEquals("", voice.preview());
        voice.interim(session, "暫定"); voice.volume(session, 1f); voice.fail(session, "失敗");
        assertEquals("", voice.preview()); assertFalse(voice.hasVolume());
        session = voice.start(true); voice.microphoneStarted(session);
        voice.interim(session, "暫定"); voice.volume(session, 1f); voice.leave();
        voice.interim(session, "晚回呼"); voice.finalText(session, "晚定稿");
        assertEquals("", voice.preview()); assertFalse(voice.hasVolume()); assertTrue(sent.isEmpty());
    }
    @Test public void hypothesesReplaceAndFreezeWhileOnlyFinalIsSubmittedOnce() {
        List<String> sent = new ArrayList<>();
        VoiceUtterance voice = new VoiceUtterance(sent::add, s -> {}, s -> s, () -> 0L, e -> {});
        long session = voice.start(true);
        voice.microphoneStarted(session);
        voice.interim(session, "下週三");
        voice.interim(session, "下週四下午");
        assertEquals("下週四下午", voice.preview());
        assertTrue(sent.isEmpty());
        voice.finish();
        voice.interim(session, "停止後的暫定文字");
        assertEquals("下週四下午", voice.preview());
        voice.finalText(session, "確定定稿");
        voice.finalText(session, "重複定稿");
        assertEquals(java.util.Collections.singletonList("確定定稿"), sent);
        assertEquals("", voice.preview());
    }
}
