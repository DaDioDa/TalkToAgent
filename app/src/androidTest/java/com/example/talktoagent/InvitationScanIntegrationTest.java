package com.example.talktoagent;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

/** Controls only the external camera widget. Real decode callback, Activity result,
 * MainActivity selection/pinning and Wi-Fi transport run; no optical-camera claim. */
@RunWith(AndroidJUnit4.class)
public class InvitationScanIntegrationTest {
    private static final String ERROR = "不是合法 TalkToAgent 邀請；請取消並重新掃描電腦上的有效 QR";
    private volatile CameraInput camera;
    private File metadata;
    private byte[] originalMetadata;
    private String invitation;
    private AuthorizationRepository.Endpoint existing;

    private static final class CameraInput extends DecoratedBarcodeView {
        BarcodeCallback callback;
        CameraInput(Context context) { super(context); }
        @Override public void decodeSingle(BarcodeCallback callback) { this.callback = callback; }
        @Override public void resume() { }
        @Override public void pause() { }
        void decode(String text) {
            assertNotNull("Production must register the decoder callback", callback);
            callback.barcodeResult(new BarcodeResult(new Result(text, null, null, BarcodeFormat.QR_CODE), null));
        }
    }
    @Before public void setup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        metadata = new File(context.getNoBackupFilesDir(), "authorization-v1.bin");
        originalMetadata = metadata.exists() ? Files.readAllBytes(metadata.toPath()) : null;
        if (metadata.exists()) assertTrue(metadata.delete());
        try (java.io.Reader reader = new java.io.InputStreamReader(InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open("authorization-vectors.json"), java.nio.charset.StandardCharsets.UTF_8)) {
            invitation = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("valid")
                    .get(0).getAsJsonObject().get("uri").getAsString();
        }
        Invitation parsed = Invitation.parse(invitation);
        existing = new AuthorizationRepository.Endpoint("wifi", "wifi:127.0.0.1:12345", parsed.fingerprint);
        new AuthorizationStore(context).activate(existing, 0, "wifi");
        InvitationScanActivity.scannerFactory = contextForCamera -> camera = new CameraInput(contextForCamera);
    }
    @After public void cleanup() throws Exception {
        InvitationScanActivity.scannerFactory = DecoratedBarcodeView::new;
        if (originalMetadata == null) Files.deleteIfExists(metadata.toPath());
        else Files.write(metadata.toPath(), originalMetadata);
    }
    private void decode(String text) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> camera.decode(text));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void authorizationUnchanged() throws Exception {
        AuthorizationStore store = new AuthorizationStore(InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertNull(store.pending());
        assertEquals(1, store.active().size());
        assertEquals(existing.target, store.active().get(0).target);
        assertEquals(existing.fingerprint, store.active().get(0).fingerprint);
    }
    @Test public void validCallbackReturnsExactPayloadOnceEvenWithAnotherQueuedDecode() throws Exception {
        try (ActivityScenario<InvitationScanActivity> scenario = ActivityScenario.launchActivityForResult(InvitationScanActivity.class)) {
            // Both events are queued in the same foreground turn, before lifecycle pause.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                camera.decode(invitation); camera.decode(invitation.replaceAll("p=[^&]+", "p=12346"));
            });
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
            assertEquals(invitation, scenario.getResult().getResultData().getStringExtra(InvitationScanActivity.RESULT));
            authorizationUnchanged(); // Scanning is not authorization.
        }
    }
    @Test public void invalidCallbackShowsErrorAndCancelNeverReturnsInvitation() throws Exception {
        try (ActivityScenario<InvitationScanActivity> scenario = ActivityScenario.launchActivityForResult(InvitationScanActivity.class)) {
            decode("https://example.invalid/not-an-invitation");
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
            onView(withText(ERROR)).perform(scrollTo()).check(matches(isDisplayed()));
            authorizationUnchanged();
            onView(withContentDescription("取消掃描並返回裝置連線")).perform(click());
            assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
            assertNull(scenario.getResult().getResultData());
        }
    }
    @Test public void decodeAfterLeavingForegroundCannotDeliverAResult() throws Exception {
        try (ActivityScenario<InvitationScanActivity> scenario = ActivityScenario.launchActivityForResult(InvitationScanActivity.class)) {
            scenario.moveToState(Lifecycle.State.CREATED);
            decode(invitation);
            assertEquals(Lifecycle.State.CREATED, scenario.getState());
            scenario.moveToState(Lifecycle.State.RESUMED);
            onView(withContentDescription("取消掃描並返回裝置連線")).perform(click());
            decode(invitation); // A queued camera event after finish is also ignored.
            assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
            assertNull(scenario.getResult().getResultData());
            authorizationUnchanged();
        }
    }
    private void openScanner() throws Exception {
        camera = null;
        onView(withText("裝置連線")).perform(click());
        // Wait for the asynchronous real Keystore/metadata snapshot through its visible label.
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        do {
            try { onView(withText(org.hamcrest.Matchers.containsString("已授權電腦："))).perform(scrollTo()).check(matches(isDisplayed())); break; }
            catch (androidx.test.espresso.NoMatchingViewException error) {
                if (android.os.SystemClock.uptimeMillis() >= deadline) throw error;
                android.os.SystemClock.sleep(50);
            }
        } while (true);
        onView(withText("掃描 QR 邀請")).perform(scrollTo(), click());
        onView(withText("掃描連線邀請")).perform(scrollTo()).check(matches(isDisplayed()));
        assertNotNull(camera);
    }
    @Test public void mainReceivesOnlyValidResultAndConnectsOnceToControlledLoopback() throws Exception {
        // TCP/HTTP boundary suffices to prove actual transport construction and selection;
        // no receiver authorization, fake success or user-machine connection is introduced.
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // Start the bounded result wait after decode, not before UI navigation/scrolling.
            java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<String> request = worker.submit(() -> {
                    try (java.net.Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        return new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII)).readLine();
                    }
                });
                openScanner();
                decode("invalid");
                onView(withText(ERROR)).perform(scrollTo()).check(matches(isDisplayed()));
                assertFalse("Invalid input must not initiate a transport", request.isDone());
                authorizationUnchanged();
                // ZXing decodeSingle consumes one decode; follow the error instruction to cancel
                // and explicitly start a fresh scan, rather than inventing an automatic retry.
                onView(withContentDescription("取消掃描並返回裝置連線")).perform(click());
                openScanner();
                String local = invitation.replaceAll("h=[^&]+", "h=127.0.0.1").replaceAll("p=[^&]+", "p=" + server.getLocalPort());
                decode(local);
                assertEquals("GET /ws HTTP/1.1", request.get(8, TimeUnit.SECONDS));
                onView(withText("連線管理")).perform(scrollTo()).check(matches(isDisplayed()));
                AuthorizationStore store = new AuthorizationStore(InstrumentationRegistry.getInstrumentation().getTargetContext());
                assertEquals("wifi:127.0.0.1:" + server.getLocalPort(), store.pending().target);
                assertEquals(existing.fingerprint, store.pending().fingerprint);
                assertEquals(existing.target, store.active().get(0).target);
                // Reject a late duplicate at the camera boundary, and prove no automatic retry.
                decode(local);
                server.setSoTimeout(750);
                assertThrows(java.net.SocketTimeoutException.class, server::accept);
                onView(withText("電腦未連線 · 不代表輸入就緒")).perform(scrollTo()).check(matches(isDisplayed()));
            } finally { worker.shutdownNow(); }
        }
    }
    @Test public void mainRejectsDifferentFingerprintWithoutChangingExistingAuthorization() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openScanner();
            String differentPin = invitation.replaceAll("r=[^&]+", "r=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            decode(differentPin); // Scanner accepts valid syntax; MainActivity must enforce the pin.
            onView(withText("連線管理")).perform(scrollTo());
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            do {
                try { onView(isAssignableFrom(android.widget.ScrollView.class)).check(matches(hasDescendant(
                        org.hamcrest.Matchers.allOf(withText("QR 無效、不同授權電腦或儲存不可讀；既有授權未變更"), isDisplayed())))); break; }
                catch (AssertionError error) {
                    if (android.os.SystemClock.uptimeMillis() >= deadline) throw error;
                    android.os.SystemClock.sleep(50);
                }
            } while (true);
            authorizationUnchanged();
        }
    }
}
