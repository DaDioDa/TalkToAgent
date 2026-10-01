package com.example.talktoagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import java.util.Collections;

/** Debug-only snapshot selector. No transitions, voice state machine or real operations. */
public final class NativeCPreviewActivity extends Activity {
    private NativeCScreen screen;
    private NativeCScreen.Phase phase = NativeCScreen.Phase.READY;
    private boolean ready = true;
    private int seconds = 45;
    private int reason;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            phase = NativeCScreen.Phase.valueOf(savedInstanceState.getString("phase", "READY"));
            ready = savedInstanceState.getBoolean("ready", true);
            seconds = savedInstanceState.getInt("seconds", 45);
            reason = savedInstanceState.getInt("reason", 0);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });
        TextView notice = new TextView(this);
        notice.setText("開發預覽 · 模擬資料／事件，未連接真實功能");
        notice.setMaxLines(2);
        root.addView(notice);
        LinearLayout controls = new LinearLayout(this);
        root.addView(controls);
        addControl(controls, "切換模擬狀態", () -> new AlertDialog.Builder(this)
                .setTitle("直接選擇呈現階段（不是語音流程）")
                .setItems(Arrays.stream(NativeCScreen.Phase.values()).map(Enum::name).toArray(String[]::new),
                        (dialog, index) -> { phase = NativeCScreen.Phase.values()[index]; render(); }).show());
        addControl(controls, "切換模擬就緒／障礙", () -> { ready = !ready; render(); });
        LinearLayout details = new LinearLayout(this);
        root.addView(details);
        addControl(details, "選擇障礙原因", () -> new AlertDialog.Builder(this).setItems(
                new String[]{"金鑰缺失／無效", "麥克風未授權", "Receiver 未驗證／斷線", "多項障礙（長文字）"},
                (dialog, index) -> { reason = index; render(); }).show());
        addControl(details, "期限 45／5 秒", () -> { seconds = seconds == 45 ? 5 : 45; render(); });
        screen = new NativeCScreen(this, event -> notice.setText("模擬事件（無實際操作）：" + event.action
                + (event.action == NativeCScreen.Action.SAVE_KEY ? " · 金鑰內容不顯示"
                        : event.text.isEmpty() ? "" : " · " + event.text)));
        root.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        render();
    }
    private void addControl(LinearLayout row, String label, Runnable click) {
        Button button = new Button(this);
        button.setText(label); button.setAllCaps(false); button.setTextSize(12);
        button.setOnClickListener(v -> click.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void render() {
        NativeCScreen.Blocker key = new NativeCScreen.Blocker("金鑰缺失／無效", NativeCScreen.Action.KEY);
        NativeCScreen.Blocker permission = new NativeCScreen.Blocker("麥克風未授權", NativeCScreen.Action.PERMISSION);
        NativeCScreen.Blocker connection = new NativeCScreen.Blocker("Receiver 未驗證／斷線，請檢查電腦與既有應用授權；不自動重送上一段", NativeCScreen.Action.CONNECTION);
        screen.render(new NativeCScreen.State(phase, ready,
                reason == 2 || reason == 3 ? "示範電腦 · 未連線" : "示範電腦 · 已連線", seconds, "示範轉錄定稿（非真實使用者資料）",
                ready ? Collections.emptyList() : reason == 3 ? Arrays.asList(key, permission, connection)
                        : Collections.singletonList(reason == 0 ? key : reason == 1 ? permission : connection)));
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("phase", phase.name()); out.putBoolean("ready", ready);
        out.putInt("seconds", seconds); out.putInt("reason", reason);
    }
}
