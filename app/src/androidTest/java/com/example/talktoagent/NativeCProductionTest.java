package com.example.talktoagent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class NativeCProductionTest {
    @Test public void diagnosticsAndLatestAreHiddenAndLeavingDoesNotStartVoice() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withHint("手動測試文字")).check(doesNotExist());
            onView(withText("設定")).perform(click());
            onView(withText("診斷／手動測試")).perform(click());
            onView(withHint("手動測試文字")).check(matches(isDisplayed()));
            onView(withText("返回主畫面")).perform(click());
            onView(withText("最近定稿")).perform(click());
            onView(withText("尚無定稿")).check(matches(isDisplayed()));
            onView(withText("複製定稿")).check(matches(isNotEnabled()));
            onView(withText("返回主畫面")).perform(click());
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            onView(withText("取消")).check(matches(isNotEnabled()));
            scenario.onActivity(activity -> assertFalse(activity.getWindow().getDecorView().getKeepScreenOn()));
        }
    }

    @Test public void channelSelectionAndDisconnectUseProductionHandlersWithoutStartingCapture() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("連線管理")).perform(click());
            onView(withText("通道：藍牙")).perform(click());
            onView(withText("通道：Wi-Fi")).perform(click());
            onView(withText("主動中斷連線")).perform(click());
            onView(withText("返回主畫面")).perform(click());
            onView(withText("取消")).check(matches(isNotEnabled()));
            onView(withText("開始輸入")).check(matches(isNotEnabled()));
        }
    }
}
