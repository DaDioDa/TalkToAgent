package com.example.talktoagent;

import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class LiveVoiceScreenTest {
    static void capture(String name) throws Exception {
        // Espresso idle does not guarantee SurfaceFlinger has presented the latest scrolled frame.
        java.util.concurrent.CountDownLatch frames = new java.util.concurrent.CountDownLatch(1);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                android.view.Choreographer.getInstance().postFrameCallback(t ->
                        android.view.Choreographer.getInstance().postFrameCallback(next -> frames.countDown())));
        assertTrue(frames.await(3, java.util.concurrent.TimeUnit.SECONDS));
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(500, 5000);
        NativeCScreenTest.saveScreenshot(name);
    }
    @Test public void liveUpdatesPreserveScrollAndInFlightFinishAndCancelTaps() {
        java.util.List<NativeCScreen.Event> events = new java.util.ArrayList<>();
        NativeCScreen[] screen = new NativeCScreen[1];
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                screen[0] = new NativeCScreen(a, events::add); a.setContentView(screen[0]);
                screen[0].render(state(NativeCScreen.Phase.RECORDING));
            });
            onView(withContentDescription("結束並送出")).check(matches(isDisplayed()));
            for (NativeCScreen.Action action : new NativeCScreen.Action[]{NativeCScreen.Action.FINISH, NativeCScreen.Action.CANCEL}) {
                scenario.onActivity(a -> {
                    android.widget.ScrollView scroll = (android.widget.ScrollView) screen[0].getChildAt(1);
                    scroll.scrollTo(0, 20); int before = scroll.getScrollY();
                    View control = screen[0].findViewWithTag(action);
                    int[] position = new int[2], origin = new int[2];
                    control.getLocationOnScreen(position); screen[0].getLocationOnScreen(origin);
                    float x = position[0] - origin[0] + control.getWidth() / 2f;
                    float y = position[1] - origin[1] + control.getHeight() / 2f;
                    long time = android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(time, time, 0, x, y, 0);
                    android.view.MotionEvent up = android.view.MotionEvent.obtain(time, time + 30, 1, x, y, 0);
                    try {
                        screen[0].dispatchTouchEvent(down);
                        float[] trend = new float[32]; trend[31] = .8f;
                        for (int i = 0; i < 100; i++) screen[0].updateLive("高頻修訂 " + i, trend, true);
                        assertEquals(before, scroll.getScrollY());
                        screen[0].dispatchTouchEvent(up);
                    } finally { down.recycle(); up.recycle(); }
                });
                onView(withContentDescription("結束並送出")).check(matches(isDisplayed()));
            }
            assertEquals(2, events.size());
            assertEquals(NativeCScreen.Action.FINISH, events.get(0).action);
            assertEquals(NativeCScreen.Action.CANCEL, events.get(1).action);
        }
    }
    @Test public void longPreviewAt320dpAndFont2KeepsFinishAndCancelVisible() throws Exception {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                android.content.res.Configuration config = new android.content.res.Configuration(a.getResources().getConfiguration());
                config.fontScale = 2f;
                NativeCScreen screen = new NativeCScreen(a.createConfigurationContext(config), e -> {});
                android.widget.FrameLayout frame = new android.widget.FrameLayout(a); a.setContentView(frame);
                float density = a.getResources().getDisplayMetrics().density;
                frame.addView(screen, new android.widget.FrameLayout.LayoutParams(Math.round(320 * density), Math.round(480 * density)));
                screen.render(state(NativeCScreen.Phase.RECORDING));
                screen.updateLive(String.join("", Collections.nCopies(100, "服務長篇辨識預覽，仍未送出。")), null, false);
            });
            onView(withContentDescription("結束並送出")).check(matches(isDisplayed()));
            onView(withText("取消")).check(matches(isDisplayed()));
            capture("ticket02-compact-font2-preview");
        }
    }
    @Test public void allNonCapturePhasesAndMissingVolumeStayStill() {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, e -> {}); a.setContentView(screen);
                float[] trend = new float[32]; java.util.Arrays.fill(trend, 1f);
                for (NativeCScreen.Phase phase : NativeCScreen.Phase.values()) {
                    screen.render(state(phase)); screen.updateLive("片段", trend, true);
                    android.widget.LinearLayout wave = screen.findViewWithTag("voice-waveform");
                    for (int i = 0; i < 32; i++) assertEquals(phase == NativeCScreen.Phase.RECORDING ? 1f : .125f, wave.getChildAt(i).getScaleY(), 0f);
                }
                screen.render(state(NativeCScreen.Phase.RECORDING)); screen.updateLive("", trend, false);
                android.widget.LinearLayout wave = screen.findViewWithTag("voice-waveform");
                assertEquals(.125f, wave.getChildAt(31).getScaleY(), 0f);
                screen.updateLive("", new float[32], true); // valid silence is distinct from missing samples
                assertEquals(.125f, wave.getChildAt(31).getScaleY(), 0f);
            });
        }
    }
    private static NativeCScreen.State state(NativeCScreen.Phase phase) {
        return new NativeCScreen.State(phase, false, "測試電腦", 40, "先前定稿", Collections.emptyList());
    }
    @Test public void previewAndVolumeUpdateOnlyRetainedRegionsAndSendingIsNotUnsent() throws Exception {
        NativeCScreen[] screen = new NativeCScreen[1];
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> { screen[0] = new NativeCScreen(a, e -> {}); a.setContentView(screen[0]); screen[0].render(state(NativeCScreen.Phase.RECORDING)); });
            onView(withText("等待辨識文字")).perform(androidx.test.espresso.action.ViewActions.scrollTo()).check(matches(isDisplayed()));
            capture("ticket02-no-fragment");
            scenario.onActivity(a -> {
                View mic = screen[0].findViewWithTag(NativeCScreen.Action.FINISH);
                View words = screen[0].findViewWithTag("voice-transcript");
                View wave = screen[0].findViewWithTag("voice-waveform");
                float[] trend = new float[32]; trend[30] = .25f; trend[31] = 1f;
                for (int i = 0; i < 50; i++) screen[0].updateLive("修訂後的服務預覽", trend, true);
                assertSame(mic, screen[0].findViewWithTag(NativeCScreen.Action.FINISH));
                assertSame(words, screen[0].findViewWithTag("voice-transcript"));
                assertSame(wave, screen[0].findViewWithTag("voice-waveform"));
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, words.getAccessibilityLiveRegion());
                assertEquals("修訂後的服務預覽", ((TextView) words).getText().toString());
            });
            onView(withText("辨識預覽 · 尚未送出")).perform(androidx.test.espresso.action.ViewActions.scrollTo()).check(matches(isDisplayed()));
            scenario.onActivity(a -> { android.widget.ScrollView scroll = (android.widget.ScrollView) screen[0].getChildAt(1); scroll.scrollTo(0, scroll.getChildAt(0).getHeight()); assertTrue(scroll.getScrollY() > 0); });
            onView(withTagValue(org.hamcrest.Matchers.is("voice-waveform"))).check(matches(isCompletelyDisplayed()));
            capture("ticket02-preview-waveform");
            scenario.onActivity(a -> screen[0].render(state(NativeCScreen.Phase.FINALIZING)));
            onView(withText("辨識預覽 · 未定稿、尚未送出")).perform(androidx.test.espresso.action.ViewActions.scrollTo()).check(matches(isDisplayed()));
            scenario.onActivity(a -> { android.widget.ScrollView scroll = (android.widget.ScrollView) screen[0].getChildAt(1); scroll.scrollTo(0, scroll.getChildAt(0).getHeight()); });
            onView(withTagValue(org.hamcrest.Matchers.is("voice-waveform"))).check(matches(isDisplayed()));
            capture("ticket02-waiting");
            scenario.onActivity(a -> screen[0].render(state(NativeCScreen.Phase.SENDING)));
            onView(withText("轉錄定稿 · 傳送中")).perform(androidx.test.espresso.action.ViewActions.scrollTo()).check(matches(isDisplayed()));
            scenario.onActivity(a -> screen[0].render(state(NativeCScreen.Phase.CANCELLED)));
            onView(withText("先前定稿")).perform(androidx.test.espresso.action.ViewActions.scrollTo()).check(matches(isDisplayed()));
            capture("ticket02-cancelled");
            scenario.onActivity(a -> screen[0].render(new NativeCScreen.State(NativeCScreen.Phase.COMPLETED, true,
                    "測試電腦", 0, "本次有效轉錄定稿", Collections.emptyList())));
            onView(withText("本次有效轉錄定稿")).perform(androidx.test.espresso.action.ViewActions.scrollTo()).check(matches(isDisplayed()));
            capture("ticket02-final-result");
        }
    }
}
