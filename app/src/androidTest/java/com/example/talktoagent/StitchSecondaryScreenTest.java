package com.example.talktoagent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

/** Exercise public snapshot/event contract with real native widgets. */
@RunWith(AndroidJUnit4.class)
public class StitchSecondaryScreenTest {
    private NativeCScreen.State snapshot(boolean connected, String status) {
        return new NativeCScreen.State(NativeCScreen.Phase.UNAVAILABLE, false, "測試電腦", 45, "",
                Collections.singletonList(new NativeCScreen.Blocker("需要麥克風權限", NativeCScreen.Action.PERMISSION)),
                connected, status, connected, "Wi-Fi");
    }
    @Test public void scannerUsesNativeStyleAndCancelReturnsWithoutInvitation() throws Exception {
        android.app.Instrumentation instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        try (android.os.ParcelFileDescriptor granted = instrumentation.getUiAutomation().executeShellCommand(
                "pm grant com.example.talktoagent android.permission.CAMERA")) {
            new java.io.FileInputStream(granted.getFileDescriptor()).read(new byte[1024]);
        }
        try (ActivityScenario<InvitationScanActivity> scenario = ActivityScenario.launchActivityForResult(InvitationScanActivity.class)) {
            onView(withText("掃描連線邀請")).check(matches(isDisplayed()));
            onView(withContentDescription("取消掃描並返回裝置連線")).check(matches(isCompletelyDisplayed()));
            scenario.onActivity(a -> {
                org.junit.Assert.assertTrue((a.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0);
                // Keep FLAG_SECURE. This test-only rendering explicitly excludes the camera view,
                // including TextureView pixels; never export any invitation or QR image.
                android.view.View root = a.findViewById(android.R.id.content);
                android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                android.view.View camera = findCamera(root);
                camera.setVisibility(android.view.View.INVISIBLE);
                root.draw(new android.graphics.Canvas(image));
                camera.setVisibility(android.view.View.VISIBLE);
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "ticket03-qr-native-ui-no-camera.png");
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TalkToAgent-stitch");
                android.net.Uri uri = a.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                try (java.io.OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                    org.junit.Assert.assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
                } catch (java.io.IOException error) { throw new AssertionError(error); }
                finally { image.recycle(); }
            });
            onView(withContentDescription("取消掃描並返回裝置連線")).perform(click());
            org.junit.Assert.assertEquals(android.app.Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
            org.junit.Assert.assertNull(scenario.getResult().getResultData());
        }
    }
    private android.view.View findCamera(android.view.View view) {
        if (view instanceof com.journeyapps.barcodescanner.DecoratedBarcodeView) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = findCamera(group.getChildAt(i)); if (found != null) return found;
            }
        }
        return null;
    }
    @Test public void productionKeySaveAndClearNeverRefillStoredCredentialOrStartRecording() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withContentDescription("設定")).perform(click());
            onView(withText("Gemini 金鑰")).perform(scrollTo(), click());
            onView(withHint("新金鑰（不顯示已存金鑰）")).perform(scrollTo(), replaceText("test-only-key-not-a-credential"), closeSoftKeyboard());
            onView(withText("儲存金鑰")).perform(scrollTo(), click());
            onView(withText("取消")).check(matches(isNotEnabled()));
            onView(withText("請設定 Gemini 金鑰 · 修復")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            onView(withContentDescription("設定")).perform(click());
            onView(withText("Gemini 金鑰")).perform(scrollTo(), click());
            onView(withHint("新金鑰（不顯示已存金鑰）")).perform(scrollTo()).check(matches(withText("")));
            onView(withText("清除金鑰")).perform(scrollTo(), click());
            onView(withText("請設定 Gemini 金鑰 · 修復")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("取消")).check(matches(isNotEnabled()));
        }
    }
    @Test public void scannerWithoutCameraPermissionCancelsWithoutReturningAnInvitation() throws Exception {
        // Revoke externally before instrumentation: revoking a target package kills its process,
        // including its test runner. The dedicated -e class invocation verifies this denied state.
        android.content.Context context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.junit.Assume.assumeTrue("Run separately after adb pm revoke CAMERA",
                context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED);
        try (ActivityScenario<InvitationScanActivity> scenario = ActivityScenario.launchActivityForResult(InvitationScanActivity.class)) {
            org.junit.Assert.assertEquals(android.app.Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
            org.junit.Assert.assertNull(scenario.getResult().getResultData());
        }
    }
    @Test public void tabsExposeSelectionAndSettingsHasAccessibleTouchTarget() {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, e -> {}); a.setContentView(screen); screen.render(snapshot(false, ""));
            });
            onView(withContentDescription("設定")).check(matches(isCompletelyDisplayed())).check((v, error) -> {
                if (error != null) throw error;
                org.junit.Assert.assertTrue(v.getWidth() >= NativeCStyle.dp(v.getContext(), 48));
                org.junit.Assert.assertTrue(v.getHeight() >= NativeCStyle.dp(v.getContext(), 48));
            });
            onView(withText("語音輸入")).check(matches(isSelected())).check(tabStyle(true));
            onView(withText("裝置連線")).check(matches(org.hamcrest.Matchers.not(isSelected()))).check((v, error) -> {
                if (error != null) throw error; org.junit.Assert.assertEquals(NativeCStyle.MUTED, ((android.widget.TextView) v).getCurrentTextColor());
            }).perform(click());
            onView(withText("裝置連線")).check(matches(isSelected())).check(tabStyle(true));
            onView(withContentDescription("語音輸入，返回主畫面"))
                    .check(matches(withText("語音輸入"))).check(matches(org.hamcrest.Matchers.not(isSelected())))
                    .check(tabStyle(false)).perform(click());
            onView(withText("語音輸入")).check(matches(isSelected()));
        }
    }
    private static androidx.test.espresso.ViewAssertion tabStyle(boolean selected) {
        return (view, error) -> {
            if (error != null) throw error;
            com.google.android.material.button.MaterialButton tab = (com.google.android.material.button.MaterialButton) view;
            int color = selected ? NativeCStyle.GREEN : NativeCStyle.MUTED;
            org.junit.Assert.assertEquals(NativeCStyle.dp(view.getContext(), 22), tab.getIconSize());
            org.junit.Assert.assertEquals(NativeCStyle.dp(view.getContext(), 2), tab.getIconPadding());
            org.junit.Assert.assertEquals(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_TOP, tab.getIconGravity());
            org.junit.Assert.assertEquals(color, tab.getCurrentTextColor());
            org.junit.Assert.assertEquals(color, tab.getIconTint().getDefaultColor());
            org.junit.Assert.assertEquals(0, tab.getStrokeWidth());
            org.junit.Assert.assertEquals(android.graphics.Color.TRANSPARENT, tab.getBackgroundTintList().getDefaultColor());
            org.junit.Assert.assertTrue(tab.isEnabled());
            org.junit.Assert.assertEquals(10f * view.getResources().getDisplayMetrics().scaledDensity, tab.getTextSize(), .1f);
        };
    }
    @Test public void actualKeyboardKeepsKeySubmitAndBackReachable() throws Exception {
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                NativeCScreen screen = new NativeCScreen(a, e -> {}); a.setContentView(screen); screen.render(snapshot(false, ""));
            });
            onView(withContentDescription("設定")).perform(click());
            onView(withText("Gemini 金鑰")).perform(scrollTo(), click());
            onView(withHint("新金鑰（不顯示已存金鑰）")).perform(scrollTo(), click());
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            // Wait for the real IME animation, not a fake inset snapshot.
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            boolean[] visible = {false};
            do {
                scenario.onActivity(a -> visible[0] = a.getWindow().getDecorView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()));
                if (!visible[0]) android.os.SystemClock.sleep(100);
            } while (!visible[0] && android.os.SystemClock.uptimeMillis() < deadline);
            org.junit.Assert.assertTrue("Real keyboard must be visible", visible[0]);
            // Insets become visible at animation start. Wait until final viewport geometry settles.
            int[] lastBottom = {-1}, stableSamples = {0};
            long settleDeadline = android.os.SystemClock.uptimeMillis() + 5000;
            do {
                scenario.onActivity(a -> {
                    int bottom = a.getWindow().getDecorView().getRootWindowInsets().getInsets(android.view.WindowInsets.Type.ime()).bottom;
                    stableSamples[0] = bottom == lastBottom[0] ? stableSamples[0] + 1 : 0;
                    lastBottom[0] = bottom;
                });
                android.os.SystemClock.sleep(100);
            } while (stableSamples[0] < 5 && android.os.SystemClock.uptimeMillis() < settleDeadline);
            org.junit.Assert.assertTrue("IME viewport settled", stableSamples[0] >= 5);
            // The IME has resized the viewport; bring the still-focused field back into view
            // before input, rather than typing during the click-triggered inset transition.
            onView(withHint("新金鑰（不顯示已存金鑰）")).perform(scrollTo())
                    .check(matches(hasFocus())).check(matches(isCompletelyDisplayed()))
                    .perform(replaceText("keyboard-test-only"))
                    .check(matches(withText("keyboard-test-only")));
            // Espresso scrollTo deliberately skips scrolling an already 90%-visible control.
            // A real swipe verifies the last 9% can also be reached without dismissing the IME.
            onView(isAssignableFrom(android.widget.ScrollView.class)).perform(swipeUp());
            onView(withText("儲存金鑰")).check(matches(isCompletelyDisplayed()));
            onView(withText("返回主畫面")).check(matches(isCompletelyDisplayed()));
            scenario.onActivity(a -> org.junit.Assert.assertTrue("Submit reachable while IME remains visible",
                    a.getWindow().getDecorView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime())));
            NativeCScreenTest.saveScreenshot("ticket03-key-real-keyboard");
            onView(withText("儲存金鑰")).perform(click());
            onView(withHint("新金鑰（不顯示已存金鑰）")).check(matches(withText("")));
            onView(withText("返回主畫面")).perform(click());
        }
    }
    @Test public void grantedPermissionProductionFeedbackAndResumeNeverStartsRecording() throws Exception {
        android.app.Instrumentation instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        try (android.os.ParcelFileDescriptor granted = instrumentation.getUiAutomation().executeShellCommand(
                "pm grant com.example.talktoagent android.permission.RECORD_AUDIO")) {
            new java.io.FileInputStream(granted.getFileDescriptor()).read(new byte[1024]);
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withContentDescription("設定")).perform(click());
            onView(withText("麥克風權限")).perform(scrollTo(), click());
            onView(withText("請求麥克風權限")).perform(scrollTo(), click());
            onView(withText("已允許麥克風；請返回並主動開始（目前沒有收音）")).perform(scrollTo()).check(matches(isDisplayed()));
            NativeCScreenTest.saveScreenshot("ticket03-permission-production-granted");
            onView(withText("返回主畫面")).perform(click());
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            onView(withText("取消")).check(matches(isNotEnabled()));
            scenario.onActivity(a -> org.junit.Assert.assertFalse(a.getWindow().getDecorView().getKeepScreenOn()));
        }
    }
    @Test public void keyAndPermissionFeedbackRefreshWithoutDiscardingDraftOrStartingVoice() throws Exception {
        NativeCScreen[] screen = new NativeCScreen[1];
        java.util.List<NativeCScreen.Event> events = new java.util.ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                screen[0] = new NativeCScreen(a, events::add); a.setContentView(screen[0]); screen[0].render(snapshot(false, ""));
            });
            onView(withContentDescription("設定")).perform(click());
            NativeCScreenTest.saveScreenshot("ticket03-settings");
            onView(withText("Gemini 金鑰")).perform(scrollTo(), click());
            onView(withHint("新金鑰（不顯示已存金鑰）")).perform(scrollTo(), replaceText("test-only-draft"), closeSoftKeyboard());
            scenario.onActivity(a -> screen[0].update(snapshot(false, "連線已中斷；草稿仍保留")));
            onView(withText("連線已中斷；草稿仍保留")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withHint("新金鑰（不顯示已存金鑰）")).perform(scrollTo()).check(matches(withText("test-only-draft"))).check((view, error) -> {
                if (error != null) throw error;
                org.junit.Assert.assertTrue(((android.widget.EditText) view).getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod);
                org.junit.Assert.assertFalse(view.isSaveEnabled());
            });
            NativeCScreenTest.saveScreenshot("ticket03-key-masked");
            onView(withText("清除金鑰")).perform(scrollTo(), click());
            onView(withHint("新金鑰（不顯示已存金鑰）")).check(matches(withText("")));
            org.junit.Assert.assertEquals("", events.get(events.size() - 1).text);
            onView(withText("返回主畫面")).perform(click());
            onView(withContentDescription("設定")).perform(click());
            onView(withText("麥克風權限")).perform(scrollTo(), click());
            onView(withText("請求麥克風權限")).perform(scrollTo(), click());
            scenario.onActivity(a -> screen[0].update(snapshot(false, "麥克風權限遭拒，沒有收音")));
            onView(withText("麥克風權限遭拒，沒有收音")).perform(scrollTo()).check(matches(isDisplayed()));
            NativeCScreenTest.saveScreenshot("ticket03-permission-denied");
            org.junit.Assert.assertEquals(NativeCScreen.Action.REQUEST_PERMISSION, events.get(events.size() - 1).action);
            org.junit.Assert.assertFalse(events.stream().anyMatch(e -> e.action == NativeCScreen.Action.START));
        }
    }
    @Test public void diagnosticsKeepDraftAndDisableSendThenReturnHomeWhenVoiceBecomesBusy() throws Exception {
        NativeCScreen[] screen = new NativeCScreen[1];
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                screen[0] = new NativeCScreen(a, e -> {}); a.setContentView(screen[0]); screen[0].render(snapshot(true, ""));
            });
            onView(withContentDescription("設定")).perform(click());
            onView(withText("診斷／手動測試")).perform(scrollTo(), click());
            onView(withHint("手動測試文字")).perform(scrollTo(), replaceText("a real manual draft"), closeSoftKeyboard());
            scenario.onActivity(a -> screen[0].update(snapshot(false, "拒絕連線 · 請修復授權")));
            onView(withText("拒絕連線 · 請修復授權")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withHint("手動測試文字")).perform(scrollTo()).check(matches(withText("a real manual draft")));
            onView(withText("送出手動測試")).perform(scrollTo()).check(matches(isNotEnabled()));
            NativeCScreenTest.saveScreenshot("ticket03-diagnostics-draft");
            scenario.onActivity(a -> screen[0].update(new NativeCScreen.State(NativeCScreen.Phase.PREPARING,
                    false, "測試電腦", 40, "", Collections.emptyList(), false)));
            onView(withText("裝置連線")).check(matches(isNotEnabled()));
            onView(withContentDescription("設定")).check(matches(isNotEnabled()));
        }
    }
    @Test public void compactFontTwoLongFinalIsReadableAndDoesNotAttachManualResult() throws Exception {
        String longText = String.join("\n", Collections.nCopies(50, "完整語音定稿，不是手動測試。"));
        java.util.List<NativeCScreen.Event> events = new java.util.ArrayList<>();
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                android.content.res.Configuration config = new android.content.res.Configuration(a.getResources().getConfiguration()); config.fontScale = 2f;
                NativeCScreen screen = new NativeCScreen(a.createConfigurationContext(config), events::add);
                android.widget.FrameLayout root = new android.widget.FrameLayout(a);
                root.addView(screen, new android.widget.FrameLayout.LayoutParams(NativeCStyle.dp(a, 320), NativeCStyle.dp(a, 480)));
                a.setContentView(root);
                screen.render(new NativeCScreen.State(NativeCScreen.Phase.COMPLETED, false, "測試電腦", 45,
                        longText, Collections.emptyList(), false, "手動測試的貼上操作完成", false, "Wi-Fi"));
            });
            onView(withText("最近定稿")).perform(scrollTo(), click());
            onView(withText("手動測試的貼上操作完成")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            onView(withText("複製定稿")).perform(scrollTo(), click());
            org.junit.Assert.assertEquals(longText, events.get(events.size() - 1).text);
            NativeCScreenTest.saveScreenshot("ticket03-latest-long-320dp-font2");
            onView(withText("返回主畫面")).check(matches(isCompletelyDisplayed())).perform(click());
            org.junit.Assert.assertFalse(events.stream().anyMatch(e -> e.action == NativeCScreen.Action.MANUAL_SEND || e.action == NativeCScreen.Action.START));
        }
    }
    @Test public void connectionShowsActualChannelAndRefreshesFeedbackWithoutClaimingReady() throws Exception {
        NativeCScreen[] screen = new NativeCScreen[1];
        try (ActivityScenario<NativeCPreviewActivity> scenario = ActivityScenario.launch(NativeCPreviewActivity.class)) {
            scenario.onActivity(a -> {
                screen[0] = new NativeCScreen(a, e -> {});
                a.setContentView(screen[0]); screen[0].render(snapshot(false, "授權失效，請重新掃描邀請"));
            });
            onView(withText("裝置連線")).perform(click());
            onView(withText("目前通道：Wi-Fi")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("電腦未連線 · 不代表輸入就緒")).perform(scrollTo()).check(matches(isDisplayed()));
            scenario.onActivity(a -> screen[0].update(snapshot(true, "已恢復應用授權，仍缺少麥克風權限")));
            onView(withText("電腦已連線 · 不代表輸入就緒")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("已恢復應用授權，仍缺少麥克風權限")).perform(scrollTo()).check(matches(isDisplayed()));
            NativeCScreenTest.saveScreenshot("ticket03-connection");
        }
    }
}
