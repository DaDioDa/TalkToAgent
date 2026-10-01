package com.example.talktoagent;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Native presentation only. Hosts supply snapshots on the UI thread and consume intent events.
 * No action changes phase or performs a device operation. */
public final class NativeCScreen extends LinearLayout {
    public enum Phase { UNAVAILABLE, READY, PREPARING, RECORDING, FINALIZING, SENDING, COMPLETED, CANCELLED, FAILED, UNKNOWN }
    public enum Action { START, FINISH, CANCEL, CONNECTION, SETTINGS, LATEST, HOME, COPY,
        SCAN_QR, RECOVER_AUTHORIZATION, BLUETOOTH, WIFI, DISCONNECT, KEY, PERMISSION, DIAGNOSTICS, MANUAL_SEND,
        SAVE_KEY, CLEAR_KEY, REQUEST_PERMISSION }
    public static final class Event {
        public final Action action;
        public final String text;
        public Event(Action action, String text) { this.action = action; this.text = text; }
    }
    public interface Actions { void accept(Event event); }
    public static final class Blocker {
        public final String reason;
        public final Action repair;
        public Blocker(String reason, Action repair) {
            this.reason = Objects.requireNonNull(reason);
            if (repair != Action.KEY && repair != Action.PERMISSION && repair != Action.CONNECTION)
                throw new IllegalArgumentException("Repair must open key, permission or connection");
            this.repair = repair;
        }
    }
    public static final class State {
        public final Phase phase;
        public final boolean ready;
        public final String computer;
        public final int remainingSeconds;
        public final String latestFinalText;
        public final List<Blocker> blockers;
        public State(Phase phase, boolean ready, String computer, int remainingSeconds,
                     String latestFinalText, List<Blocker> blockers) {
            this.phase = Objects.requireNonNull(phase);
            this.ready = ready;
            this.computer = Objects.requireNonNull(computer);
            this.remainingSeconds = remainingSeconds;
            this.latestFinalText = Objects.requireNonNull(latestFinalText);
            this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
        }
    }
    private final Actions actions;
    private State state;
    public NativeCScreen(Context context, Actions actions) {
        super(context);
        this.actions = Objects.requireNonNull(actions);
        setOrientation(VERTICAL);
        setBackgroundColor(Color.WHITE);
        setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
    }
    public void render(State state) {
        this.state = Objects.requireNonNull(state);
        removeAllViews();
        LinearLayout info = new LinearLayout(getContext());
        info.setOrientation(VERTICAL);
        // A bounded, non-scrolling core keeps status visible even at 400dp height.
        // Reserve the same rows in every phase; only supplemental details live in panels.
        addView(info, new LayoutParams(LayoutParams.MATCH_PARENT, dp(196), 1));
        homeLine(info, state.computer, 18, 14);
        homeLine(info, state.ready ? "輸入就緒" : "輸入尚未就緒", 18, 14);
        boolean busy = state.phase == Phase.PREPARING || state.phase == Phase.RECORDING
                || state.phase == Phase.FINALIZING || state.phase == Phase.SENDING;
        homeLine(info, phaseText(state.phase), 24, 16);
        boolean timed = state.phase == Phase.PREPARING || state.phase == Phase.RECORDING;
        homeLine(info, timed ? "剩餘期限 " + state.remainingSeconds + " 秒（包含準備時間）" : "", 20, 14);
        homeLine(info, timed && state.remainingSeconds <= 5 ? "即將自動結束並等待定稿" : "", 20, 14);
        LinearLayout entries = new LinearLayout(getContext());
        info.addView(entries, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        for (Action entry : new Action[]{Action.CONNECTION, Action.SETTINGS, Action.LATEST}) {
            Button secondary = button(entries, entry == Action.CONNECTION ? "連線管理" : entry == Action.SETTINGS ? "設定" : "最近定稿", entry, !busy);
            secondary.setTextSize(14);
            secondary.setMaxLines(1);
            secondary.setLayoutParams(new LayoutParams(0, dp(48), 1));
        }
        if (state.blockers.isEmpty()) {
            info.addView(new View(getContext()), new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        } else {
            Blocker blocker = state.blockers.get(0);
            Button repair = button(info, blocker.reason + " · 修復", blocker.repair, !busy);
            repair.setSingleLine(true);
            repair.setEllipsize(android.text.TextUtils.TruncateAt.END);
            repair.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        }
        Button main = button(this, state.phase == Phase.RECORDING ? "結束並送出" : "開始輸入",
                state.phase == Phase.RECORDING ? Action.FINISH : Action.START,
                state.phase == Phase.RECORDING || (!busy && state.ready && state.phase != Phase.UNAVAILABLE));
        main.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(48), 1));
        View divider = new View(getContext());
        divider.setBackgroundColor(Color.DKGRAY);
        addView(divider, new LayoutParams(LayoutParams.MATCH_PARENT, dp(12)));
        Button cancel = button(this, "取消", Action.CANCEL,
                state.phase == Phase.PREPARING || state.phase == Phase.RECORDING);
        cancel.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(68)));
    }
    private String phaseText(Phase phase) {
        switch (phase) {
            case UNAVAILABLE: return "尚未就緒 · 請修復必要條件";
            case READY: return "等待開始";
            case PREPARING: return "準備中 · 尚未收音";
            case RECORDING: return "收音中 · 可以說話";
            case FINALIZING: return "等待轉錄定稿";
            case SENDING: return "傳送中 · 無法撤回貼上";
            case COMPLETED: return "貼上操作完成 · 不保證目標程式已接受文字";
            case CANCELLED: return "已取消 · 不傳送";
            case FAILED: return "辨識失敗 · 不自動重試";
            default: return "輸入結果不明 · 請檢查電腦，不自動重送";
        }
    }
    private void showPanel(Action panel) {
        removeAllViews();
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        scroll.addView(content);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        text(content, phaseText(state.phase));
        for (Blocker blocker : state.blockers)
            button(content, blocker.reason + " · 修復", blocker.repair, true);
        switch (panel) {
            case CONNECTION:
                text(content, "連線管理 · " + state.computer);
                text(content, "QR 邀請不等於應用授權；藍牙系統配對需另行完成。通道由使用者選擇，不自動切換或補送。");
                button(content, "掃描 QR 邀請", Action.SCAN_QR, true);
                button(content, "恢復既有應用授權", Action.RECOVER_AUTHORIZATION, true);
                button(content, "通道：藍牙", Action.BLUETOOTH, true);
                button(content, "通道：Wi-Fi", Action.WIFI, true);
                button(content, "主動中斷連線", Action.DISCONNECT, true);
                break;
            case SETTINGS:
                text(content, "設定");
                button(content, "Gemini 金鑰", Action.KEY, true);
                button(content, "麥克風權限", Action.PERMISSION, true);
                button(content, "診斷／手動測試", Action.DIAGNOSTICS, true);
                break;
            case LATEST:
                text(content, "最近一段轉錄定稿（無持久歷史）");
                text(content, state.latestFinalText.isEmpty() ? "尚無定稿" : state.latestFinalText);
                Button copy = button(content, "複製定稿", Action.COPY, !state.latestFinalText.isEmpty());
                copy.setOnClickListener(v -> actions.accept(new Event(Action.COPY, state.latestFinalText)));
                break;
            case DIAGNOSTICS:
                text(content, "診斷／手動測試 · " + state.computer);
                text(content, "手動送出與語音流程分開；由主程式處理操作。");
                android.widget.EditText input = new android.widget.EditText(getContext());
                input.setHint("手動測試文字");
                input.setMinHeight(dp(48));
                content.addView(input);
                Button send = button(content, "送出手動測試", Action.MANUAL_SEND, true);
                send.setOnClickListener(v -> actions.accept(new Event(Action.MANUAL_SEND, input.getText().toString())));
                break;
            case KEY:
                text(content, "Gemini 金鑰管理");
                text(content, "此畫面只輸出操作事件，不讀寫憑證。開發預覽請勿輸入真實金鑰。");
                android.widget.EditText key = new android.widget.EditText(getContext());
                key.setHint("新金鑰（不顯示已存金鑰）");
                key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                key.setSaveEnabled(false);
                key.setMinHeight(dp(48));
                content.addView(key);
                Button save = button(content, "儲存金鑰", Action.SAVE_KEY, true);
                save.setOnClickListener(v -> {
                    actions.accept(new Event(Action.SAVE_KEY, key.getText().toString()));
                    key.setText("");
                });
                button(content, "清除金鑰", Action.CLEAR_KEY, true);
                break;
            case PERMISSION:
                text(content, "麥克風權限管理");
                text(content, "由主程式處理權限請求；修復後仍需主動開始。");
                button(content, "請求麥克風權限", Action.REQUEST_PERMISSION, true);
                break;
            default: break;
        }
        button(this, "返回主畫面", Action.HOME, true);
    }
    private void homeLine(LinearLayout parent, String value, int height, int size) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextColor(Color.BLACK);
        view.setTextSize(size);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        view.setPadding(dp(12), 0, dp(12), 0);
        parent.addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, dp(height)));
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void text(LinearLayout parent, String value) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextColor(Color.BLACK); view.setTextSize(18);
        view.setPadding(dp(12), dp(4), dp(12), dp(4)); parent.addView(view);
    }
    private Button button(LinearLayout parent, String label, Action action, boolean enabled) {
        Button view = new Button(getContext());
        view.setText(label); view.setTextColor(Color.BLACK); view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.LTGRAY));
        view.setAllCaps(false); view.setMinHeight(dp(48)); view.setEnabled(enabled);
        view.setOnClickListener(v -> {
            if (action == Action.HOME) render(state);
            else if (action == Action.CONNECTION || action == Action.SETTINGS || action == Action.LATEST
                    || action == Action.DIAGNOSTICS || action == Action.KEY || action == Action.PERMISSION) showPanel(action);
            // Publish last: a synchronous host render must be the final presentation.
            actions.accept(new Event(action, ""));
        });
        parent.addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return view;
    }
}
