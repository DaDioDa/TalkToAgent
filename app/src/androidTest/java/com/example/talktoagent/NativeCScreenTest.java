package com.example.talktoagent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class NativeCScreenTest {
    @Test public void synchronousHostRenderWinsOverConnectionNavigation() {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen[] screen = new NativeCScreen[1];
                screen[0] = new NativeCScreen(a, event -> {
                    if (event.action == NativeCScreen.Action.CONNECTION)
                        screen[0].render(new NativeCScreen.State(NativeCScreen.Phase.PREPARING, true,
                                "示範電腦", 5, "", java.util.Collections.emptyList()));
                });
                a.setContentView(screen[0]);
                screen[0].render(new NativeCScreen.State(NativeCScreen.Phase.READY, true,
                        "示範電腦", 45, "", java.util.Collections.emptyList()));
            });
            onView(withText("連線管理")).perform(click());
            onView(withText("準備中 · 尚未收音")).check(matches(isDisplayed()));
            onView(withText("開始輸入")).check(matches(org.hamcrest.Matchers.not(isEnabled())));
            onView(withText("掃描 QR 邀請")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        }
    }
    @Test public void fullHomeCancelRemainsAboveCurrentSystemNavigationInsets() {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, e -> {}); a.setContentView(screen);
                screen.render(new NativeCScreen.State(NativeCScreen.Phase.RECORDING, true, "示範電腦", 5, "", java.util.Collections.emptyList()));
            });
            onView(withText("取消")).check(matches(isDisplayed())).check((view, error) -> {
                if (error != null) throw error;
                android.view.View decor = view.getRootView();
                android.graphics.Insets safe = decor.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                int[] position = new int[2], origin = new int[2];
                view.getLocationOnScreen(position); decor.getLocationOnScreen(origin);
                org.junit.Assert.assertTrue("Cancel above the current navigation safe area", position[1] + view.getHeight() <= origin[1] + decor.getHeight() - safe.bottom);
                org.junit.Assert.assertTrue("Cancel has a 48dp minimum hit height", view.getHeight() >= 48 * view.getResources().getDisplayMetrics().density);
                assertEquals("Full available width", decor.getWidth() - safe.left - safe.right, view.getWidth());
            });
        }
    }
    @Test public void keyAndPermissionRepairsOfferIntentOnlyControls() {
        List<NativeCScreen.Event> events = new ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, events::add); a.setContentView(screen);
                screen.render(new NativeCScreen.State(NativeCScreen.Phase.UNAVAILABLE, false, "已連線", 45, "",
                        java.util.Collections.singletonList(new NativeCScreen.Blocker("金鑰缺失", NativeCScreen.Action.KEY))));
            });
            onView(withText("金鑰缺失 · 修復")).perform(click());
            onView(withHint("新金鑰（不顯示已存金鑰）")).perform(androidx.test.espresso.action.ViewActions.replaceText("simulated-key-only"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
            onView(withText("儲存金鑰")).perform(click());
            assertEquals(NativeCScreen.Action.SAVE_KEY, events.get(1).action);
            assertEquals("simulated-key-only", events.get(1).text);
            onView(withText("清除金鑰")).perform(click());
            assertEquals(NativeCScreen.Action.CLEAR_KEY, events.get(2).action);
            onView(withText("返回主畫面")).perform(click());
            onView(withText("設定")).perform(click());
            onView(withText("麥克風權限")).perform(click());
            onView(withText("請求麥克風權限")).perform(click());
            assertEquals(NativeCScreen.Action.REQUEST_PERMISSION, events.get(events.size() - 1).action);
        }
    }
    @Test public void allTenPhasesRespectReadinessAndLockSecondaryEntries() {
        NativeCScreen.Phase[] phases = NativeCScreen.Phase.values();
        boolean[] busy = {false, false, true, true, true, true, false, false, false, false};
        boolean[] cancel = {false, false, true, true, false, false, false, false, false, false};
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            for (int i = 0; i < phases.length; i++) {
                for (boolean ready : new boolean[]{true, false}) {
                    final NativeCScreen.Phase phase = phases[i];
                    scenario.onActivity(a -> {
                        NativeCScreen screen = new NativeCScreen(a, e -> {});
                        a.setContentView(screen);
                        screen.render(new NativeCScreen.State(phase, ready, "電腦已連線", 5, "不公開定稿",
                                ready ? java.util.Collections.emptyList() : java.util.Collections.singletonList(
                                        new NativeCScreen.Blocker("金鑰無效", NativeCScreen.Action.KEY))));
                    });
                    boolean recording = i == 3;
                    boolean start = recording || (!busy[i] && ready && i != 0);
                    onView(withText(recording ? "結束並送出" : "開始輸入")).check(matches(start ? isEnabled() : org.hamcrest.Matchers.not(isEnabled())));
                    onView(withText("取消")).check(matches(cancel[i] ? isEnabled() : org.hamcrest.Matchers.not(isEnabled())));
                    for (String entry : new String[]{"連線管理", "設定", "最近定稿"})
                        onView(withText(entry)).check(matches(busy[i] ? org.hamcrest.Matchers.not(isEnabled()) : isEnabled()));
                    onView(withText("不公開定稿")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
                    if (i == 2 || i == 3) {
                        onView(withText("剩餘期限 5 秒（包含準備時間）")).check(matches(isDisplayed()));
                        onView(withText("即將自動結束並等待定稿")).check(matches(isDisplayed()));
                    }
                }
            }
        }
    }

    @Test public void compactHomeLongReasonsDoNotMoveControlsAndCancelEdgesOnlyEmitCancel() {
        List<NativeCScreen.Action> events = new ArrayList<>();
        android.graphics.Rect[] baseline = new android.graphics.Rect[2];
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            for (boolean longText : new boolean[]{false, true}) {
                scenario.onActivity(a -> {
                    NativeCScreen screen = new NativeCScreen(a, e -> events.add(e.action));
                    android.widget.FrameLayout root = new android.widget.FrameLayout(a);
                    root.addView(screen, new android.widget.FrameLayout.LayoutParams(-1,
                            Math.round(400 * a.getResources().getDisplayMetrics().density)));
                    a.setContentView(root);
                    screen.render(new NativeCScreen.State(longText ? NativeCScreen.Phase.RECORDING : NativeCScreen.Phase.PREPARING,
                            false, longText ? "示範電腦 · " + String.join("", java.util.Collections.nCopies(30, "長連線資訊")) : "電腦", 5, "",
                            java.util.Arrays.asList(
                                    new NativeCScreen.Blocker(String.join("", java.util.Collections.nCopies(30, "尚未授權，請修復連線")), NativeCScreen.Action.CONNECTION),
                                    new NativeCScreen.Blocker("金鑰缺失", NativeCScreen.Action.KEY))));
                });
                onView(withText(longText ? "收音中 · 可以說話" : "準備中 · 尚未收音")).check(matches(isCompletelyDisplayed()));
                onView(withText("剩餘期限 5 秒（包含準備時間）")).check(matches(isCompletelyDisplayed()));
                onView(withText("即將自動結束並等待定稿")).check(matches(isCompletelyDisplayed()));
                int index = 0;
                for (String label : new String[]{longText ? "結束並送出" : "開始輸入", "取消"}) {
                    final int slot = index++;
                    onView(withText(label)).check(matches(isDisplayed())).check((view, error) -> {
                        if (error != null) throw error;
                        org.junit.Assert.assertTrue("Control retains minimum 48dp hit height", view.getHeight() >= 48 * view.getResources().getDisplayMetrics().density);
                        int[] location = new int[2]; view.getLocationOnScreen(location);
                        android.graphics.Rect rect = new android.graphics.Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
                        if (baseline[slot] == null) baseline[slot] = rect;
                        else assertEquals("Long status must not move the controls", baseline[slot], rect);
                    });
                }
            }
            for (float x : new float[]{0.05f, 0.5f, 0.95f}) {
                onView(withText("取消")).perform(new androidx.test.espresso.action.GeneralClickAction(
                        androidx.test.espresso.action.Tap.SINGLE, view -> {
                            int[] location = new int[2]; view.getLocationOnScreen(location);
                            return new float[]{location[0] + view.getWidth() * x, location[1] + view.getHeight() / 2f};
                        }, androidx.test.espresso.action.Press.FINGER, 0, 0));
            }
            assertEquals(java.util.Arrays.asList(NativeCScreen.Action.CANCEL, NativeCScreen.Action.CANCEL, NativeCScreen.Action.CANCEL), events);
        }
    }

    @Test public void repairsAndConnectionControlsEmitExplicitIntents() {
        List<NativeCScreen.Action> events = new ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, e -> events.add(e.action)); a.setContentView(screen);
                screen.render(new NativeCScreen.State(NativeCScreen.Phase.UNKNOWN, false, "示範電腦 · 未驗證", 0, "",
                        java.util.Collections.singletonList(new NativeCScreen.Blocker("連線未驗證", NativeCScreen.Action.CONNECTION))));
            });
            onView(withText("連線未驗證 · 修復")).perform(click());
            for (String label : new String[]{"掃描 QR 邀請", "恢復既有應用授權", "通道：藍牙", "通道：Wi-Fi", "主動中斷連線"})
                onView(withText(label)).perform(click());
            assertEquals(java.util.Arrays.asList(NativeCScreen.Action.CONNECTION, NativeCScreen.Action.SCAN_QR,
                    NativeCScreen.Action.RECOVER_AUTHORIZATION, NativeCScreen.Action.BLUETOOTH, NativeCScreen.Action.WIFI,
                    NativeCScreen.Action.DISCONNECT), events);
        }
    }
    @Test public void previewAllowsDirectPhaseAndIndependentReadinessSelection() {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            onView(withText("切換模擬狀態")).perform(click());
            onView(withText("UNKNOWN")).perform(click());
            onView(withText("切換模擬就緒／障礙")).perform(click());
            onView(withText("輸入尚未就緒")).check(matches(isDisplayed()));
            onView(withText("輸入結果不明 · 請檢查電腦，不自動重送")).check(matches(isDisplayed()));
            onView(withText("開始輸入")).check(matches(org.hamcrest.Matchers.not(isEnabled())));
        }
    }
    @Test public void latestAndDiagnosticsNavigateAndEmitPayloadWithoutDeviceOperations() {
        List<NativeCScreen.Event> events = new ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, events::add);
                a.setContentView(screen);
                screen.render(new NativeCScreen.State(NativeCScreen.Phase.UNKNOWN, true,
                        "示範電腦 · 已連線", 4, "最新秘密", java.util.Collections.emptyList()));
            });
            onView(withText("最近定稿")).perform(click());
            onView(withText("最新秘密")).check(matches(isDisplayed()));
            onView(withText("複製定稿")).perform(click());
            assertEquals(NativeCScreen.Action.COPY, events.get(1).action);
            assertEquals("最新秘密", events.get(1).text);
            onView(withText("返回主畫面")).perform(click());
            onView(withText("設定")).perform(click());
            onView(withText("診斷／手動測試")).perform(click());
            onView(withHint("手動測試文字")).perform(androidx.test.espresso.action.ViewActions.replaceText("測試，不傳送"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
            onView(withText("送出手動測試")).perform(click());
            assertEquals(NativeCScreen.Action.MANUAL_SEND, events.get(events.size() - 1).action);
            assertEquals("測試，不傳送", events.get(events.size() - 1).text);
        }
    }
    @Test public void readyStartEmitsOnlyAnActionWithoutChangingPresentation() {
        List<NativeCScreen.Action> events = new ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, e -> events.add(e.action));
                a.setContentView(screen);
                screen.render(new NativeCScreen.State(NativeCScreen.Phase.READY, true,
                        "示範電腦 · 已連線", 45, "秘密定稿", java.util.Collections.emptyList()));
            });
            onView(withText("開始輸入")).perform(click());
            assertEquals(java.util.Collections.singletonList(NativeCScreen.Action.START), events);
            onView(withText("開始輸入")).check(matches(isEnabled()));
            onView(withText("取消")).check(matches(org.hamcrest.Matchers.not(isEnabled())));
            onView(withText("秘密定稿")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        }
    }
}
