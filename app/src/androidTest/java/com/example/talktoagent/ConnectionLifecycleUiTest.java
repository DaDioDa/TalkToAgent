package com.example.talktoagent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

/** Real production navigation/recreation; no credentials, QR injection or target paste. */
@RunWith(AndroidJUnit4.class)
public class ConnectionLifecycleUiTest {
    @Test public void coldActivityAndRecreationUseNativeEntryAndNeverAutomaticallyCapture() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("開始輸入")).check(matches(isDisplayed()));
            onView(withText("取消")).check(matches(isNotEnabled()));
            onView(withText("按住說話／放開結束")).check(doesNotExist());
            onView(withText("連線管理")).perform(click());
            onView(withText("恢復既有應用授權")).check(matches(isDisplayed()));
            onView(withText("掃描 QR 邀請")).check(matches(isDisplayed()));
            onView(withText("Windows Receiver IP")).check(doesNotExist());
            onView(withText("返回主畫面")).perform(click());
            scenario.recreate();
            onView(withText("開始輸入")).check(matches(isDisplayed()));
            onView(withText("取消")).check(matches(isNotEnabled()));
        }
    }
}
