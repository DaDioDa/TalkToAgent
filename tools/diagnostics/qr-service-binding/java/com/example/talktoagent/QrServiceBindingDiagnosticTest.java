package com.example.talktoagent;

import android.app.Activity;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.app.Instrumentation;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Diagnostic only: real Activity/result/worker/coordinator/UI, withheld binding delivery. */
@RunWith(AndroidJUnit4.class)
public class QrServiceBindingDiagnosticTest {
    private static final String FAILURE = "背景服務尚未就緒；請稍後按連線";
    private static Object field(MainActivity a, String name) {
        try { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(a); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void set(Object a, String name, Object value) {
        try { Field f = a.getClass().getDeclaredField(name); f.setAccessible(true); f.set(a, value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void invoke(MainActivity a, String name) {
        try { java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(a); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void request(MainActivity a) {
        ConnectionCoordinator c = new ConnectionCoordinator((AuthorizationStore) field(a, "authorizationStore"));
        c.selectInvitation(syntheticInvitation());
        set(a, "coordinator", c); set(a, "selectedChannel", 1);
        set(a, "bluetooth", null); set(a, "connectingAttempt", true);
        invoke(a, "connectSelected");
        assertNotNull("Real request must wait", field(a, "bindingCoordinator"));
    }
    private static boolean visibleText(View v, String text) {
        if (v.getVisibility() != View.VISIBLE) return false;
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(text)) return true;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
            if (visibleText(((ViewGroup) v).getChildAt(i), text)) return true;
        return false;
    }
    private static String diagnosticText(View v) {
        StringBuilder text = new StringBuilder();
        if (v instanceof TextView && v.getVisibility() == View.VISIBLE)
            text.append(((TextView) v).getText().toString()
                    .replaceAll("bt:[A-Fa-f0-9]{12}|[A-Za-z0-9_-]{32,}", "<REDACTED>")).append(" | ");
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
            text.append(diagnosticText(((ViewGroup) v).getChildAt(i)));
        return text.toString();
    }
    private static String syntheticInvitation() {
        SecureRandom random = new SecureRandom();
        byte[] id = new byte[16], secret = new byte[32], fingerprint = new byte[32];
        random.nextBytes(id); random.nextBytes(secret); random.nextBytes(fingerprint);
        // Never logged. No real receiver or user token; binding remains withheld.
        return "talktoagent://invite/1?c=bt&a=020000000001&i=" + Invitation.encode(id)
                + "&k=" + Invitation.encode(secret) + "&r=" + Invitation.encode(fingerprint);
    }
    @Test public void scannerResultBeforeBindingMustWaitInsteadOfRejectingInvitation() throws Exception {
        assertEquals("Isolation is mandatory", "com.example.talktoagent.qrdiagnosis",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(a -> ((ExecutorService) field(a, "authorizationWorker")).execute(loaded::countDown));
            assertTrue("Authorization worker setup timed out", loaded.await(5, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            AtomicReference<MainActivity> owner = new AtomicReference<>();
            AtomicReference<ServiceConnection> delivery = new AtomicReference<>();
            AtomicReference<IBinder> heldBinder = new AtomicReference<>();
            CountDownLatch bound = new CountDownLatch(1);
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                    DiagnosticScanReturnActivity.class.getName(), null, false);
            scenario.onActivity(a -> {
                owner.set(a);
                assertNotNull("Real coordinator must be loaded", field(a, "coordinator"));
                assertTrue("Device Bluetooth must already be enabled; harness does not change it",
                        a.getSystemService(BluetoothManager.class).getAdapter().isEnabled());
                // Android drives the same covering Activity / resultCode / requestCode lifecycle;
                // only QR camera acquisition is replaced, so no real QR can be accidentally read.
                a.startActivityForResult(new Intent(a, DiagnosticScanReturnActivity.class), 40);
            });
            Activity scanner = monitor.waitForActivityWithTimeout(5000);
            assertNotNull("Synthetic scanner must launch", scanner);
            instrumentation.removeMonitor(monitor);
            // Wait for the actual production onStop before replacing the next binding boundary.
            boolean[] stopped = new boolean[1];
            for (int i = 0; i < 100 && !stopped[0]; i++) {
                instrumentation.runOnMainSync(() -> stopped[0] = !(Boolean) field(owner.get(), "activityVisible"));
                if (!stopped[0]) Thread.sleep(20);
            }
            assertTrue("MainActivity must actually stop under scanner", stopped[0]);
            instrumentation.runOnMainSync(() -> {
                MainActivity a = owner.get();
                assertNull("Production onStop must unbind", field(a, "bluetooth"));
                try {
                    Field f = MainActivity.class.getDeclaredField("serviceConnection"); f.setAccessible(true);
                    delivery.set((ServiceConnection) f.get(a));
                    f.set(a, new ServiceConnection() {
                        @Override public void onServiceConnected(ComponentName name, IBinder binder) { heldBinder.set(binder); bound.countDown(); }
                        @Override public void onServiceDisconnected(ComponentName name) { /* withheld */ }
                    });
                } catch (Exception e) { throw new AssertionError(e); }
                scanner.setResult(Activity.RESULT_OK, new Intent().putExtra(InvitationScanActivity.RESULT, syntheticInvitation()));
                scanner.finish();
            });
            boolean[] returned = new boolean[1];
            for (int i = 0; i < 100 && !returned[0]; i++) {
                instrumentation.runOnMainSync(() -> returned[0] = (Boolean) field(owner.get(), "activityVisible"));
                if (!returned[0]) Thread.sleep(20);
            }
            assertTrue("MainActivity must restart after synthetic scan return", returned[0]);
            instrumentation.waitForIdleSync();
            CountDownLatch parsed = new CountDownLatch(1);
            scenario.onActivity(a -> {
                assertNull("Binding callback must remain withheld", field(a, "bluetooth"));
                ((ExecutorService) field(a, "authorizationWorker")).execute(parsed::countDown);
            });
            assertTrue("Scan worker timed out", parsed.await(5, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a -> {
                View root = a.getWindow().getDecorView();
                // The home screen only shows the first blocker (missing fixture Gemini key).
                // Connection details expose every blocker, matching the user's actual screen.
                View connection = root.findViewWithTag(NativeCScreen.Action.CONNECTION);
                assertNotNull("Connection entry must exist", connection);
                connection.performClick();
                assertFalse("Valid Bluetooth scan result was rejected before binding callback; visible symptom: " + FAILURE,
                        visibleText(root, FAILURE));
                assertTrue("User-requested invitation should remain visibly pending while binding is withheld; UI: " + diagnosticText(root),
                        visibleText(root, "驗證中"));
            });
            assertTrue("Isolated binder callback timed out", bound.await(5, TimeUnit.SECONDS));
            scenario.onActivity(a -> {
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                a.bluetoothDevicePreparation = adapter -> calls.incrementAndGet();
                ConnectionCoordinator scanned = (ConnectionCoordinator) field(a, "coordinator");
                BluetoothConnectionService service = ((BluetoothConnectionService.LocalBinder) heldBinder.get()).service();
                set(service, "flow", new ConnectionCoordinator((AuthorizationStore) field(a, "authorizationStore")));
                set(service, "running", true); // Old service flow must not replace the scanned invitation.
                ComponentName name = new ComponentName(a, BluetoothConnectionService.class);
                delivery.get().onServiceConnected(name, heldBinder.get());
                assertSame("Current QR must win over old service flow", scanned, field(a, "coordinator"));
                assertEquals("Delayed callback continues once at device boundary", 1, calls.get());
                delivery.get().onServiceConnected(name, heldBinder.get());
                assertEquals("Duplicate callback must not retry", 1, calls.get());
                set(service, "running", false);

                set(a, "connectingAttempt", true);
                invoke(a, "connectSelected");
                assertEquals("Already bound request continues immediately", 2, calls.get());

                request(a);
                invoke(a, "disconnectAll");
                delivery.get().onServiceConnected(name, heldBinder.get());
                assertEquals("Disconnect cancels stale continuation", 2, calls.get());

                request(a);
                View wifi = a.getWindow().getDecorView().findViewWithTag(NativeCScreen.Action.WIFI);
                assertNotNull("Channel switch control", wifi); wifi.performClick();
                delivery.get().onServiceConnected(name, heldBinder.get());
                assertEquals("Channel switch cancels stale continuation", 2, calls.get());

                request(a);
                set(a, "transportGeneration", (Integer) field(a, "transportGeneration") + 1);
                delivery.get().onServiceConnected(name, heldBinder.get());
                assertEquals("Superseded request cannot continue", 2, calls.get());

                request(a);
                ((Runnable) field(a, "bindingTimeout")).run();
                assertFalse("Timeout clears spinner", (Boolean) field(a, "connectingAttempt"));
                assertNull(field(a, "bindingCoordinator"));
                delivery.get().onServiceConnected(name, heldBinder.get());
                assertEquals("Timeout does not retry", 2, calls.get());

                request(a);
                delivery.get().onNullBinding(name);
                assertFalse("Failed binding clears spinner", (Boolean) field(a, "connectingAttempt"));
                delivery.get().onServiceConnected(name, heldBinder.get());
                assertEquals("Failed binding does not retry", 2, calls.get());
                request(a);
                invoke(a, "onStop");
                delivery.get().onServiceConnected(name, heldBinder.get());
                assertEquals("Leaving Activity cancels continuation", 2, calls.get());
                invoke(a, "onStart");
            });
        }
    }
}
