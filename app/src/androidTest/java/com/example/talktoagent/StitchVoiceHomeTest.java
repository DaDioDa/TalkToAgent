package com.example.talktoagent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

/** Contract tests at the approved NativeCScreen State/Event boundary. */
@RunWith(AndroidJUnit4.class)
public class StitchVoiceHomeTest {
    @Test public void deadlineRefreshDoesNotInterruptAnInFlightFinishTap() {
        List<NativeCScreen.Event> events = new ArrayList<>();
        NativeCScreen[] screen = new NativeCScreen[1];
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                screen[0] = new NativeCScreen(a, events::add); a.setContentView(screen[0]);
                screen[0].render(new NativeCScreen.State(NativeCScreen.Phase.RECORDING, false,
                        "測試電腦", 45, "", Collections.emptyList()));
            });
            onView(withContentDescription("結束並送出")).check(matches(isDisplayed()));
            scenario.onActivity(a -> {
                android.view.View main = screen[0].findViewWithTag(NativeCScreen.Action.FINISH);
                int[] position = new int[2], origin = new int[2];
                main.getLocationOnScreen(position); screen[0].getLocationOnScreen(origin);
                float x = position[0] - origin[0] + main.getWidth() / 2f;
                float y = position[1] - origin[1] + main.getHeight() / 2f;
                long time = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(time, time + 20, android.view.MotionEvent.ACTION_UP, x, y, 0);
                try {
                    screen[0].dispatchTouchEvent(down);
                    screen[0].render(new NativeCScreen.State(NativeCScreen.Phase.RECORDING, false,
                            "測試電腦", 44, "", Collections.emptyList()));
                    screen[0].dispatchTouchEvent(up);
                } finally { down.recycle(); up.recycle(); }
            });
            // ACTION_UP posts View.performClick; let Espresso drain the UI queue.
            onView(withContentDescription("結束並送出")).check(matches(isDisplayed()));
            assertEquals(1, events.size());
            assertEquals(NativeCScreen.Action.FINISH, events.get(0).action);
        }
    }
    @Test public void longFinalCanBeOpenedAndCopiedInFullWithLargeFontWithoutSending() throws Exception {
        List<NativeCScreen.Event> events = new ArrayList<>();
        String finalText = String.join("", Collections.nCopies(80, "長篇轉錄定稿，保留全文與標點。"));
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                android.content.res.Configuration config = new android.content.res.Configuration(a.getResources().getConfiguration());
                config.fontScale = 2f;
                NativeCScreen screen = new NativeCScreen(a.createConfigurationContext(config), events::add);
                a.setContentView(screen);
                screen.render(new NativeCScreen.State(NativeCScreen.Phase.CANCELLED, true,
                        "測試電腦", 0, finalText, Collections.emptyList()));
            });
            onView(withText("最近定稿")).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());
            onView(withText(finalText)).check(matches(isDisplayed()));
            onView(withText("複製定稿")).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());
            assertEquals(2, events.size());
            assertEquals(NativeCScreen.Action.LATEST, events.get(0).action);
            assertEquals(NativeCScreen.Action.COPY, events.get(1).action);
            assertEquals(finalText, events.get(1).text);
            NativeCScreenTest.saveScreenshot("stitch-long-final-font2-copy");
        }
    }
    @Test public void homeShowsLatestSummaryAndCopyNeverSends() {
        List<NativeCScreen.Event> events = new ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, events::add);
                a.setContentView(screen);
                screen.render(new NativeCScreen.State(NativeCScreen.Phase.READY, true,
                        "測試電腦", 45, "最近的真實定稿", Collections.emptyList()));
            });
            onView(withText("最近的真實定稿")).check(matches(isDisplayed()));
            onView(withText("裝置連線")).check(matches(isDisplayed()));
            onView(withText("未收音 · 靜止")).check(matches(isDisplayed()));
            onView(withText("最近定稿")).perform(click());
            onView(withText("複製定稿")).perform(click());
            assertEquals(NativeCScreen.Action.LATEST, events.get(0).action);
            assertEquals(NativeCScreen.Action.COPY, events.get(1).action);
            assertEquals("最近的真實定稿", events.get(1).text);
            assertEquals(2, events.size());
        }
    }
}
