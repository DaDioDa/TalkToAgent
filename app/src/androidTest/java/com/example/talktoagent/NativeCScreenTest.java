package com.example.talktoagent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;

/** Native State/Event regression suite. Snapshots are test data, never production voice results. */
@RunWith(AndroidJUnit4.class)
public class NativeCScreenTest {
    private NativeCScreen.State snapshot(NativeCScreen.Phase phase, boolean ready, String latest) {
        return new NativeCScreen.State(phase, ready, "測試電腦 · 已連線", 5, latest,
                ready ? Collections.emptyList() : Collections.singletonList(
                        new NativeCScreen.Blocker("金鑰無效", NativeCScreen.Action.KEY)),
                ready, "", true, "Wi-Fi");
    }
    @Test public void synchronousHostRenderWinsOverNavigation() {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen[] screen = new NativeCScreen[1];
                screen[0] = new NativeCScreen(a, event -> screen[0].render(snapshot(NativeCScreen.Phase.PREPARING, true, "")));
                a.setContentView(screen[0]);
                screen[0].render(snapshot(NativeCScreen.Phase.READY, true, ""));
            });
            onView(withText("裝置連線")).perform(click());
            onView(withText("準備中 · 尚未收音")).check(matches(isDisplayed()));
            onView(withContentDescription("開始語音輸入")).check(matches(isNotEnabled()));
            onView(withText("掃描 QR 邀請")).check(doesNotExist());
        }
    }
    @Test public void allTenPhasesRespectReadinessAndLockSecondaryEntries() {
        boolean[] busy = {false, false, true, true, true, true, false, false, false, false};
        boolean[] cancel = {false, false, true, true, false, false, false, false, false, false};
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            for (int i = 0; i < NativeCScreen.Phase.values().length; i++) {
                for (boolean ready : new boolean[]{true, false}) {
                    NativeCScreen.Phase phase = NativeCScreen.Phase.values()[i];
                    scenario.onActivity(a -> {
                        NativeCScreen screen = new NativeCScreen(a, e -> {});
                        a.setContentView(screen); screen.render(snapshot(phase, ready, "先前的定稿"));
                    });
                    boolean start = i == 3 || (!busy[i] && ready && i != 0);
                    onView(withContentDescription(i == 3 ? "結束並送出" : "開始語音輸入"))
                            .check(matches(start ? isEnabled() : isNotEnabled()));
                    onView(withText("取消")).check(matches(cancel[i] ? isEnabled() : isNotEnabled()));
                    onView(withContentDescription("設定")).check(matches(busy[i] ? isNotEnabled() : isEnabled()));
                    for (String entry : new String[]{"裝置連線", "最近定稿"})
                        onView(withText(entry)).check(matches(busy[i] ? isNotEnabled() : isEnabled()));
                    if (busy[i]) onView(withText("先前的定稿")).check(doesNotExist());
                    else onView(withText("先前的定稿")).perform(scrollTo()).check(matches(isDisplayed()));
                    if (i == 2 || i == 3) {
                        onView(withText("剩餘期限 5 秒（包含準備時間）")).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
                        onView(withText("即將自動結束並等待定稿")).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
                    }
                }
            }
        }
    }
    @Test public void compactLargeFontLongReasonsKeepControlsVisibleAndCancelOnlyCancels() throws Exception {
        List<NativeCScreen.Action> events = new ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                android.content.res.Configuration config = new android.content.res.Configuration(a.getResources().getConfiguration());
                config.fontScale = 2f;
                NativeCScreen screen = new NativeCScreen(a.createConfigurationContext(config), e -> events.add(e.action));
                android.widget.FrameLayout root = new android.widget.FrameLayout(a);
                root.addView(screen, new android.widget.FrameLayout.LayoutParams(
                        NativeCStyle.dp(a, 320), NativeCStyle.dp(a, 480)));
                a.setContentView(root);
                screen.render(new NativeCScreen.State(NativeCScreen.Phase.RECORDING, false,
                        String.join("", Collections.nCopies(20, "長電腦名稱")), 5, "",
                        Collections.singletonList(new NativeCScreen.Blocker(
                                String.join("", Collections.nCopies(20, "尚未授權請修復連線")), NativeCScreen.Action.CONNECTION))));
            });
            for (String label : new String[]{"結束並送出", "取消"}) {
                onView(withContentDescription(label.equals("取消") ? "取消語音輸入" : label)).check(matches(isCompletelyDisplayed())).check((view, error) -> {
                    if (error != null) throw error;
                    assertTrue(view.getHeight() >= 48 * view.getResources().getDisplayMetrics().density);
                    assertNotNull(view.getContentDescription());
                });
            }
            onView(withText("收音中 · 可以說話")).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
            onView(withText("剩餘期限 5 秒（包含準備時間）")).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
            onView(withText("取消")).perform(click());
            assertEquals(Collections.singletonList(NativeCScreen.Action.CANCEL), events);
            saveScreenshot("stitch-compact-320dp-font2-long");
        }
    }
    @Test public void cancelCapsuleIsAboveSafeAreaAndSeparateFromCircularMain() {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, e -> {}); a.setContentView(screen);
                screen.render(snapshot(NativeCScreen.Phase.RECORDING, true, ""));
            });
            android.graphics.Rect main = new android.graphics.Rect();
            onView(withContentDescription("結束並送出")).check((view, error) -> {
                if (error != null) throw error;
                assertEquals("Circular trigger", view.getWidth(), view.getHeight());
                int[] p = new int[2]; view.getLocationOnScreen(p);
                main.set(p[0], p[1], p[0] + view.getWidth(), p[1] + view.getHeight());
            });
            onView(withText("取消")).check(matches(isCompletelyDisplayed())).check((view, error) -> {
                if (error != null) throw error;
                android.view.View decor = view.getRootView();
                android.graphics.Insets safe = decor.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.systemBars());
                int[] p = new int[2], origin = new int[2]; view.getLocationOnScreen(p); decor.getLocationOnScreen(origin);
                assertTrue(p[1] > main.bottom);
                assertTrue(p[1] + view.getHeight() <= origin[1] + decor.getHeight() - safe.bottom);
                assertTrue("Capsule is not full width", view.getWidth() < decor.getWidth());
            });
        }
    }
    @Test public void repairsKeyPermissionAndConnectionControlsEmitExplicitIntents() throws Exception {
        List<NativeCScreen.Event> events = new ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, events::add); a.setContentView(screen);
                screen.render(snapshot(NativeCScreen.Phase.UNAVAILABLE, false, ""));
            });
            onView(withText("金鑰無效 · 修復")).perform(click());
            onView(withHint("新金鑰（不顯示已存金鑰）")).perform(replaceText("test-key"), closeSoftKeyboard());
            onView(withText("儲存金鑰")).perform(click());
            assertEquals(NativeCScreen.Action.SAVE_KEY, events.get(1).action);
            assertEquals("test-key", events.get(1).text);
            onView(withHint("新金鑰（不顯示已存金鑰）")).check(matches(withText(""))).check((view, error) -> {
                if (error != null) throw error; assertFalse(view.isSaveEnabled());
            });
            onView(withText("清除金鑰")).perform(click());
            onView(withText("返回主畫面")).perform(click());
            onView(withContentDescription("設定")).perform(click());
            onView(withText("麥克風權限")).perform(click());
            onView(withText("請求麥克風權限")).perform(click());
            assertEquals(NativeCScreen.Action.REQUEST_PERMISSION, events.get(events.size() - 1).action);
            onView(withText("返回主畫面")).perform(click());
            onView(withText("裝置連線")).perform(click());
            for (String label : new String[]{"掃描 QR 邀請", "恢復既有應用授權", "通道：藍牙", "通道：Wi-Fi", "主動中斷連線"})
                onView(withText(label)).perform(scrollTo(), click());
            assertEquals(NativeCScreen.Action.DISCONNECT, events.get(events.size() - 1).action);
            saveScreenshot("stitch-connection");
        }
    }
    @Test public void readinessUpdatesDoNotClearKeyOrDiagnosticDrafts() throws Exception {
        NativeCScreen[] screen = new NativeCScreen[1];
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                screen[0] = new NativeCScreen(a, e -> {}); a.setContentView(screen[0]);
                screen[0].render(snapshot(NativeCScreen.Phase.READY, true, ""));
            });
            onView(withContentDescription("設定")).perform(click()); onView(withText("Gemini 金鑰")).perform(scrollTo(), click());
            onView(withHint("新金鑰（不顯示已存金鑰）")).perform(replaceText("draft"), closeSoftKeyboard());
            scenario.onActivity(a -> screen[0].update(snapshot(NativeCScreen.Phase.UNAVAILABLE, false, "")));
            onView(withHint("新金鑰（不顯示已存金鑰）")).check(matches(withText("draft")));
            onView(withText("返回主畫面")).perform(click()); onView(withContentDescription("設定")).perform(click());
            onView(withText("診斷／手動測試")).perform(scrollTo(), click());
            onView(withHint("手動測試文字")).perform(scrollTo(), replaceText("diagnostic draft"), closeSoftKeyboard());
            scenario.onActivity(a -> screen[0].update(snapshot(NativeCScreen.Phase.READY, true, "")));
            onView(withHint("手動測試文字")).check(matches(withText("diagnostic draft")));
            onView(withText("送出手動測試")).check(matches(isEnabled()));
            saveScreenshot("stitch-diagnostics-draft");
        }
    }
    @Test public void startIsIntentOnlyAndLatestAndDiagnosticsNeverResend() {
        List<NativeCScreen.Event> events = new ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, events::add); a.setContentView(screen);
                screen.render(snapshot(NativeCScreen.Phase.READY, true, "語音定稿"));
            });
            onView(withContentDescription("開始語音輸入")).perform(click());
            onView(withText("取消")).check(matches(isNotEnabled()));
            assertEquals(NativeCScreen.Action.START, events.get(0).action);
            onView(withText("最近定稿")).perform(scrollTo(), click());
            onView(withText("複製定稿")).perform(click());
            assertEquals("語音定稿", events.get(2).text);
            onView(withText("返回主畫面")).perform(click()); onView(withContentDescription("設定")).perform(click());
            onView(withText("診斷／手動測試")).perform(scrollTo(), click());
            onView(withHint("手動測試文字")).perform(scrollTo(), replaceText("manual draft"), closeSoftKeyboard());
            onView(withText("送出手動測試")).perform(click());
            assertEquals(NativeCScreen.Action.MANUAL_SEND, events.get(events.size() - 1).action);
            assertEquals("manual draft", events.get(events.size() - 1).text);
        }
    }
    @Test public void allPhaseSnapshotsUseFixedDarkPaletteAndPersistScreenshots() throws Exception {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            for (NativeCScreen.Phase phase : NativeCScreen.Phase.values()) {
                scenario.onActivity(a -> {
                    android.content.res.Configuration config = new android.content.res.Configuration(a.getResources().getConfiguration());
                    config.uiMode = (config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            | android.content.res.Configuration.UI_MODE_NIGHT_NO;
                    NativeCScreen screen = new NativeCScreen(a.createConfigurationContext(config), e -> {});
                    a.setContentView(screen); screen.render(snapshot(phase, phase != NativeCScreen.Phase.UNAVAILABLE, "測試用定稿摘要"));
                    assertEquals(NativeCStyle.SURFACE, com.google.android.material.color.MaterialColors.getColor(screen, com.google.android.material.R.attr.colorSurface));
                });
                saveScreenshot("stitch-" + phase.name().toLowerCase());
            }
        }
    }
    static void saveScreenshot(String name) throws Exception {
        android.app.Instrumentation i = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        i.waitForIdleSync();
        android.content.ContentResolver resolver = i.getTargetContext().getContentResolver();
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name + ".png");
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TalkToAgent-stitch");
        android.net.Uri destination = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(destination);
        android.graphics.Bitmap image = i.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (java.io.OutputStream output = resolver.openOutputStream(destination)) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
    }
}
