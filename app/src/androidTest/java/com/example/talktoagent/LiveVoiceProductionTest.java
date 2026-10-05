package com.example.talktoagent;

import android.Manifest;
import android.widget.LinearLayout;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

/** The external recognizer is controlled; actual MainActivity handlers, coordinator and Views run. */
@RunWith(AndroidJUnit4.class)
public class LiveVoiceProductionTest {
    @Before public void grantMicrophone() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission("com.example.talktoagent", Manifest.permission.RECORD_AUDIO);
    }
    private static final class Capture implements VoiceRecognition {
        GeminiLiveTranscriber.Listener listener;
        boolean released, closed;
        Capture(GeminiLiveTranscriber.Listener listener) { this.listener = listener; }
        public void release() { released = true; }
        public void close() { closed = true; }
        public void finalizeAtDeadline() { }
    }
    private static void externalField(MainActivity activity, String name, Object value) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true); field.set(activity, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void ready(MainActivity activity, List<Capture> captures, List<String> sent) {
        try { new GeminiKeyStore(activity).save("instrumentation-only-not-a-real-key"); }
        catch (Exception failure) { throw new AssertionError(failure); }
        activity.recognitionFactory = (key, listener) -> { Capture capture = new Capture(listener); captures.add(capture); return capture; };
        externalField(activity, "receiverAuthenticated", true);
        externalField(activity, "knownFailure", null);
        externalField(activity, "transport", new FinalTextTransport() {
            public void authenticate(ConnectionCoordinator coordinator) { }
            public void sendFinalText(String text) { sent.add(text); }
            public void close() { }
        });
        activity.findViewById(android.R.id.content).findViewWithTag(NativeCScreen.Action.HOME).performClick();
    }
    @Test public void realHandlersShowRevisionsFreezeOnFinishAndSendOnlyOneValidatedFinal() throws Exception {
        List<Capture> captures = new ArrayList<>(); List<String> sent = new ArrayList<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> ready(a, captures, sent));
            onView(withContentDescription("開始語音輸入")).perform(click());
            scenario.onActivity(a -> captures.get(0).listener.onCaptureStarted());
            onView(withText("等待辨識文字")).check(matches(isDisplayed()));
            scenario.onActivity(a -> {
                Capture c = captures.get(0);
                c.listener.onInterim("請安排週三"); c.listener.onInterim("請安排週四下午");
                c.listener.onVolume(.1f); c.listener.onVolume(.8f);
                LinearLayout wave = a.findViewById(android.R.id.content).findViewWithTag("voice-waveform");
                assertEquals(32, wave.getChildCount());
                assertTrue(wave.getChildAt(31).getScaleY() > wave.getChildAt(30).getScaleY());
                assertTrue(wave.getChildAt(30).getScaleY() > wave.getChildAt(0).getScaleY());
            });
            onView(withText("請安排週四下午")).check(matches(isDisplayed()));
            LiveVoiceScreenTest.capture("ticket02-production-preview");
            assertTrue(sent.isEmpty());
            onView(withContentDescription("結束並送出")).perform(click());
            assertTrue(captures.get(0).released);
            scenario.onActivity(a -> { captures.get(0).listener.onInterim("停止後不改寫"); captures.get(0).listener.onVolume(1f); });
            onView(withText("請安排週四下午")).check(matches(isDisplayed()));
            scenario.onActivity(a -> {
                LinearLayout wave = a.findViewById(android.R.id.content).findViewWithTag("voice-waveform");
                for (int i = 0; i < 32; i++) assertEquals(.125f, wave.getChildAt(i).getScaleY(), 0f);
                captures.get(0).listener.onFinal("安排週四下午。"); captures.get(0).listener.onFinal("重複");
            });
            assertEquals(java.util.Collections.singletonList("安排週四下午。"), sent);
            onView(withText("轉錄定稿 · 傳送中")).check(matches(isDisplayed()));
            LiveVoiceScreenTest.capture("ticket02-production-final");
            scenario.onActivity(a -> { try { new GeminiKeyStore(a).save(""); } catch (Exception e) { throw new AssertionError(e); } });
        }
    }
    @Test public void cancelNewSessionFailureAndLeaveRejectLateCallbacksIncludingFinals() {
        List<Capture> captures = new ArrayList<>(); List<String> sent = new ArrayList<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> ready(a, captures, sent));
            onView(withContentDescription("開始語音輸入")).perform(click());
            scenario.onActivity(a -> { captures.get(0).listener.onCaptureStarted(); captures.get(0).listener.onInterim("第一段"); });
            onView(withText("取消")).perform(click());
            assertTrue(captures.get(0).closed);
            onView(withContentDescription("開始語音輸入")).perform(click());
            scenario.onActivity(a -> {
                Capture old = captures.get(0), current = captures.get(1);
                current.listener.onCaptureStarted(); current.listener.onInterim("新段");
                old.listener.onCaptureStarted(); old.listener.onStage("舊階段"); old.listener.onInterim("舊段");
                old.listener.onVolume(1f); old.listener.onFinal("不能送"); old.listener.onError("舊錯誤");
            });
            onView(withText("新段")).check(matches(isDisplayed()));
            onView(withContentDescription("結束並送出")).perform(click());
            scenario.onActivity(a -> captures.get(1).listener.onFinal(" "));
            onView(withText("尚無定稿")).check(matches(isDisplayed()));
            assertTrue(sent.isEmpty());
            scenario.onActivity(a -> ready(a, captures, sent));
            onView(withContentDescription("開始語音輸入")).perform(click());
            scenario.onActivity(a -> {
                Capture failed = captures.get(2);
                failed.listener.onCaptureStarted(); failed.listener.onInterim("失敗段"); failed.listener.onVolume(.8f);
                failed.listener.onError("測試辨識失敗"); failed.listener.onInterim("失敗後晚回呼"); failed.listener.onVolume(1f);
            });
            onView(withText("尚無定稿")).check(matches(isDisplayed()));
            assertTrue(captures.get(2).closed); assertTrue(sent.isEmpty());
            scenario.onActivity(a -> ready(a, captures, sent));
            onView(withContentDescription("開始語音輸入")).perform(click());
            scenario.onActivity(a -> { captures.get(3).listener.onCaptureStarted(); captures.get(3).listener.onInterim("離開段"); captures.get(3).listener.onVolume(.8f); });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(a -> { captures.get(3).listener.onInterim("離開後晚回呼"); captures.get(3).listener.onFinal("不能送"); captures.get(3).listener.onVolume(1f); });
            scenario.moveToState(Lifecycle.State.RESUMED);
            onView(withText("尚無定稿")).check(matches(isDisplayed()));
            assertTrue(sent.isEmpty());
            scenario.onActivity(a -> { try { new GeminiKeyStore(a).save(""); } catch (Exception e) { throw new AssertionError(e); } });
        }
    }
}
