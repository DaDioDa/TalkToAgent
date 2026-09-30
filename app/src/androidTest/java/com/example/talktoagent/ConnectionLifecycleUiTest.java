package com.example.talktoagent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.startsWith;

/** Real Activity recreation/UI checks, not a substitute for SM-F7410 system bond evidence.
 * Uses no private helpers, QR Intent injection, saved secrets or network/hardware mocks.
 */
@RunWith(AndroidJUnit4.class)
public class ConnectionLifecycleUiTest {
    @Test public void coldActivityAndRecreationHaveExplicitSeparateRecoveryAndNeverReportConnected() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Receiver：尚未驗證")).check(matches(isAssignableFrom(android.widget.TextView.class)));
            onView(withText(startsWith("輸入未就緒："))).check(matches(isDisplayed()));
            // Controls may be below the viewport; presence, not visibility, is the contract here.
            onView(withText("恢復上次未完成授權")).check(matches(isAssignableFrom(android.widget.Button.class)));
            onView(withText("連線（恢復既有應用授權）")).check(matches(isAssignableFrom(android.widget.Button.class)));
            onView(withText("Windows Receiver IP")).check(doesNotExist());
            onView(withText("配對碼")).check(doesNotExist());
            onView(withText("列出已配對藍牙電腦")).check(doesNotExist());
            scenario.recreate();
            onView(withText("Receiver：尚未驗證")).check(matches(isAssignableFrom(android.widget.TextView.class)));
            onView(withText(startsWith("輸入未就緒："))).check(matches(isDisplayed()));
            onView(withText("恢復上次未完成授權")).check(matches(isAssignableFrom(android.widget.Button.class)));
        }
    }
}
