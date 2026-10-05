package com.example.talktoagent;

import android.content.Context;
import androidx.appcompat.view.ContextThemeWrapper;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
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
        public final boolean manualReady;
        public final String operationStatus;
        public final String computer;
        public final boolean connected;
        public final String channel;
        public final int remainingSeconds;
        public final String latestFinalText;
        public final List<Blocker> blockers;
        public State(Phase phase, boolean ready, String computer, int remainingSeconds,
                     String latestFinalText, List<Blocker> blockers) {
            this(phase, ready, computer, remainingSeconds, latestFinalText, blockers, ready);
        }
        public State(Phase phase, boolean ready, String computer, int remainingSeconds,
                     String latestFinalText, List<Blocker> blockers, boolean manualReady) {
            this(phase, ready, computer, remainingSeconds, latestFinalText, blockers, manualReady, "");
        }
        public State(Phase phase, boolean ready, String computer, int remainingSeconds,
                     String latestFinalText, List<Blocker> blockers, boolean manualReady, String operationStatus) {
            this(phase, ready, computer, remainingSeconds, latestFinalText, blockers, manualReady,
                    operationStatus, manualReady, "");
        }
        public State(Phase phase, boolean ready, String computer, int remainingSeconds,
                     String latestFinalText, List<Blocker> blockers, boolean manualReady, String operationStatus,
                     boolean connected, String channel) {
            this.connected = connected;
            this.channel = Objects.requireNonNull(channel);
            this.operationStatus = Objects.requireNonNull(operationStatus);
            this.manualReady = manualReady;
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
    private Action panel;
    private TextView transcript;
    private TextView transcriptTitle;
    private TextView volumeStatus;
    private String livePreview = "";
    private LinearLayout waveform;
    /** UI-thread local update. No page replacement, layout animation or accessibility announcements. */
    public void updateLive(String preview, float[] trend, boolean available) {
        if (panel != null || transcript == null || waveform == null || state == null) return;
        boolean capturing = state.phase == Phase.RECORDING;
        boolean previewPhase = capturing || state.phase == Phase.FINALIZING;
        livePreview = previewPhase ? Objects.requireNonNull(preview) : "";
        String words = previewPhase && !livePreview.isEmpty() ? livePreview
                : isBusy(state.phase) ? waitingText(state.phase)
                : state.latestFinalText.isEmpty() ? "尚無定稿" : state.latestFinalText;
        if (!transcript.getText().toString().equals(words)) transcript.setText(words);
        String heading = transcriptHeading(state.phase);
        if (!transcriptTitle.getText().toString().equals(heading)) transcriptTitle.setText(heading);
        int textColor = previewPhase ? NativeCStyle.MUTED : NativeCStyle.TEXT;
        if (transcript.getCurrentTextColor() != textColor) transcript.setTextColor(textColor);
        boolean measured = capturing && available && trend != null && trend.length == 32;
        String volumeLabel = capturing ? measured ? "真實音量趨勢" : "等待音量 · 靜止" : "未收音 · 靜止";
        if (!volumeStatus.getText().toString().equals(volumeLabel)) volumeStatus.setText(volumeLabel);
        for (int i = 0; i < waveform.getChildCount(); i++) {
            float level = measured && Float.isFinite(trend[i]) ? Math.max(0f, Math.min(1f, trend[i])) : 0f;
            View bar = waveform.getChildAt(i);
            // Fixed bounds: amplitudes invalidate only the bars, never relayout the dock or scroll.
            bar.setScaleY(.125f + .875f * (float) Math.sqrt(level));
            bar.setAlpha(measured ? .45f + .55f * level : .35f);
        }
    }
    private String transcriptHeading(Phase phase) {
        switch (phase) {
            case PREPARING: return "本次語音 · 準備中、尚未送出";
            case RECORDING: return "辨識預覽 · 尚未送出";
            case FINALIZING: return "辨識預覽 · 未定稿、尚未送出";
            case SENDING: return "轉錄定稿 · 傳送中";
            default: return "最近一段轉錄定稿";
        }
    }
    private android.widget.FrameLayout micHalo;
    private MaterialButton micButton;
    private TextView actionHint;
    private TextView brandSubtitle;
    private Button cancelButton;
    private TextView remainingDeadline;
    private TextView deadlineWarning;
    private TextView panelPhase, panelFeedback, panelComputer, panelChannel, panelConnection;
    private LinearLayout panelRepairs;
    /** Refresh feedback in place: never replace a focused key or diagnostic draft. */
    public void update(State state) {
        this.state = Objects.requireNonNull(state);
        if (isBusy(state.phase)) { render(state); return; }
        if (panel == null) return;
        panelPhase.setText(panel == Action.LATEST ? "僅查看本機最近一段定稿" : phaseText(state.phase));
        panelFeedback.setText(panel == Action.LATEST ? "" : state.operationStatus);
        panelFeedback.setVisibility(state.operationStatus.isEmpty() || panel == Action.LATEST ? GONE : VISIBLE);
        panelRepairs.removeAllViews();
        for (Blocker blocker : state.blockers) {
            if (blocker.repair == panel) text(panelRepairs, blocker.reason);
            else button(panelRepairs, blocker.reason + " · 修復", blocker.repair, true);
        }
        if (panelComputer != null) panelComputer.setText(state.computer);
        if (panelChannel != null) panelChannel.setText("目前通道：" + (state.channel.isEmpty() ? "尚未選擇" : state.channel));
        if (panelConnection != null) panelConnection.setText(state.connected
                ? "電腦已連線 · 不代表輸入就緒" : "電腦未連線 · 不代表輸入就緒");
        View send = findViewWithTag(Action.MANUAL_SEND);
        if (send != null) send.setEnabled(state.manualReady);
    }
    public NativeCScreen(Context context, Actions actions) {
        super(new ContextThemeWrapper(context, R.style.Theme_TalkToAgent_NativeC));
        this.actions = Objects.requireNonNull(actions);
        setOrientation(VERTICAL);
        setFocusableInTouchMode(true);
        setBackgroundColor(NativeCStyle.SURFACE);
        setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
    }
    public void render(State state) {
        Objects.requireNonNull(state);
        if (panel == null && remainingDeadline != null && sameTimerPresentation(state)) {
            this.state = state;
            updateDeadline();
            return;
        }
        remainingDeadline = null;
        deadlineWarning = null;
        panel = null;
        this.state = Objects.requireNonNull(state);
        if (state.phase != Phase.RECORDING && state.phase != Phase.FINALIZING) livePreview = "";
        removeAllViews();
        boolean busy = isBusy(state.phase);
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(16), 0);
        android.widget.ImageView logo = new android.widget.ImageView(getContext());
        logo.setImageResource(R.drawable.native_c_mic);
        logo.setImageTintList(android.content.res.ColorStateList.valueOf(NativeCStyle.GREEN));
        logo.setBackground(NativeCStyle.rounded(getContext(), NativeCStyle.LOWEST, 20));
        logo.setPadding(dp(4), dp(4), dp(4), dp(4));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams logoBounds = new LayoutParams(dp(28), dp(28));
        logoBounds.rightMargin = dp(8);
        header.addView(logo, logoBounds);
        LinearLayout brand = new LinearLayout(getContext());
        brand.setOrientation(VERTICAL);
        TextView title = homeLine(brand, "TalkToAgent", 20);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFCDFFDE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        brandSubtitle = homeLine(brand, "VOICE INPUT", 10);
        brandSubtitle.setTypeface(android.graphics.Typeface.MONOSPACE);
        header.addView(brand, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView connected = homeLine(header, state.connected ? "● 已連線" : "未連線", 12);
        connected.setTextColor(state.connected ? NativeCStyle.GREEN : NativeCStyle.MUTED);
        connected.setContentDescription("電腦連線：" + (state.connected ? "已連線" : "未連線"));
        Button settings = button(header, "設定", Action.SETTINGS, !busy);
        settings.setContentDescription("設定");
        settings.setText("");
        ((MaterialButton) settings).setIconResource(R.drawable.native_c_settings);
        ((MaterialButton) settings).setIconSize(dp(24));
        ((MaterialButton) settings).setIconPadding(0);
        ((MaterialButton) settings).setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        ((MaterialButton) settings).setIconTint(android.content.res.ColorStateList.valueOf(NativeCStyle.MUTED));
        settings.setPadding(0, 0, 0, 0);
        settings.setLayoutParams(new LayoutParams(dp(48), LayoutParams.MATCH_PARENT));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, dp(64)));

        // Only information scrolls. Primary, cancel and tabs stay above the safe area.
        ScrollView status = new ScrollView(getContext());
        LinearLayout info = new LinearLayout(getContext());
        info.setOrientation(VERTICAL);
        info.setPadding(dp(16), 0, dp(16), dp(8));
        status.addView(info);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout system = addCard(info, NativeCStyle.LOW);
        TextView readiness = homeLine(system, busy ? "語音處理中" : state.ready ? "輸入就緒" : "輸入尚未就緒", 12);
        readiness.setTextColor(state.ready ? NativeCStyle.GREEN : NativeCStyle.MUTED);
        readiness.setBackground(NativeCStyle.rounded(getContext(), state.ready ? 0x2600F5A0 : NativeCStyle.HIGH, 20));
        readiness.setPadding(dp(12), dp(4), dp(12), dp(4));
        TextView computer = homeLine(system, state.computer, 20);
        computer.setMaxLines(2);
        computer.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (state.phase != Phase.READY && state.phase != Phase.UNAVAILABLE)
            homeLine(system, phaseText(state.phase), 14);
        if (!state.channel.isEmpty()) homeLine(system, "連線通道：" + state.channel, 12);
        boolean timed = state.phase == Phase.PREPARING || state.phase == Phase.RECORDING;
        if (timed) {
            remainingDeadline = homeLine(system, "", 12);
            deadlineWarning = homeLine(system, "即將自動結束並等待定稿", 12);
            updateDeadline();
        }
        if (!state.operationStatus.isEmpty()) homeLine(system, state.operationStatus, 14);
        for (Blocker blocker : state.blockers)
            button(system, blocker.reason + " · 修復", blocker.repair, !busy);

        LinearLayout words = addCard(info, NativeCStyle.CARD);
        LinearLayout wordHeading = new LinearLayout(getContext());
        wordHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        wordHeading.setMinimumHeight(dp(48));
        words.addView(wordHeading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        TextView wordTitle = homeLine(wordHeading, transcriptHeading(state.phase), 12);
        transcriptTitle = wordTitle;
        wordTitle.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        Button latest = button(wordHeading, "最近定稿", Action.LATEST, !busy);
        latest.setTextSize(12);
        latest.setLayoutParams(new LayoutParams(dp(96), dp(48)));
        LinearLayout inset = NativeCStyle.card(getContext(), NativeCStyle.LOWEST);
        words.addView(inset, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        transcript = homeLine(inset, busy ? waitingText(state.phase)
                : state.latestFinalText.isEmpty() ? "尚無定稿" : state.latestFinalText, 14);
        transcript.setTag("voice-transcript");
        transcript.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        transcript.setMinLines(state.phase == Phase.RECORDING || state.phase == Phase.FINALIZING ? 3 : 1);
        transcript.setMaxLines(3);
        transcript.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout monitor = addCard(info, NativeCStyle.LOWEST);
        LinearLayout monitorHeading = new LinearLayout(getContext());
        monitor.addView(monitorHeading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        TextView volumeTitle = homeLine(monitorHeading, "輸入音量", 10);
        volumeTitle.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        volumeStatus = homeLine(monitorHeading, "未收音 · 靜止", 10);
        volumeStatus.setTextColor(NativeCStyle.MUTED);
        volumeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        waveform = new LinearLayout(getContext());
        waveform.setTag("voice-waveform");
        waveform.setGravity(android.view.Gravity.CENTER);
        waveform.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        monitor.addView(waveform, new LayoutParams(LayoutParams.MATCH_PARENT, dp(32)));
        for (int i = 0; i < 32; i++) {
            View bar = new View(getContext());
            bar.setBackground(NativeCStyle.rounded(getContext(), i % 4 == 0 ? NativeCStyle.CYAN : NativeCStyle.GREEN, 3));
            bar.setAlpha(.35f);
            LayoutParams bounds = new LayoutParams(dp(3), dp(32));
            bounds.setMargins(dp(1), 0, dp(1), 0);
            waveform.addView(bar, bounds);
        }

        LinearLayout dock = new LinearLayout(getContext());
        dock.setOrientation(VERTICAL);
        dock.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.FrameLayout halo = new android.widget.FrameLayout(getContext());
        micHalo = halo;
        android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable();
        glow.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(dp(72));
        glow.setColors(new int[]{0x6600F5A0, 0x3300F5A0, 0x0000F5A0});
        halo.setBackground(glow);
        dock.addView(halo, new LayoutParams(dp(144), dp(144)));
        LinearLayout micHost = new LinearLayout(getContext());
        halo.addView(micHost, new android.widget.FrameLayout.LayoutParams(dp(96), dp(96), android.view.Gravity.CENTER));
        boolean recording = state.phase == Phase.RECORDING;
        MaterialButton main = (MaterialButton) button(micHost, recording ? "結束並送出" : "開始輸入",
                recording ? Action.FINISH : Action.START,
                recording || (!busy && state.ready && state.phase != Phase.UNAVAILABLE));
        micButton = main;
        main.setContentDescription(recording ? "結束並送出" : "開始語音輸入");
        main.setText(""); // Icon-only control; the TalkBack name and visible hint carry the operation.
        main.setIconResource(recording ? R.drawable.native_c_stop : R.drawable.native_c_mic);
        main.setIconSize(dp(38));
        main.setIconPadding(0);
        main.setPadding(0, 0, 0, 0);
        main.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        main.setIconTint(android.content.res.ColorStateList.valueOf(NativeCStyle.INK));
        android.graphics.drawable.GradientDrawable core = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BL_TR,
                new int[]{NativeCStyle.GREEN, 0xFF50FFAF, NativeCStyle.CYAN});
        core.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        core.setStroke(dp(8), NativeCStyle.HIGH);
        main.setBackgroundTintList(null);
        main.setBackground(core);
        main.setAlpha(main.isEnabled() ? 1f : .45f);
        main.setLayoutParams(new LayoutParams(dp(96), dp(96)));
        TextView hint = homeLine(dock, mainHint(false), 18);
        actionHint = hint;
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setMaxLines(2);
        hint.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        hint.setTextColor(NativeCStyle.GREEN);
        Button cancel = button(dock, "取消", Action.CANCEL,
                state.phase == Phase.PREPARING || recording);
        cancelButton = cancel;
        cancel.setContentDescription("取消語音輸入");
        cancel.setLayoutParams(new LayoutParams(dp(160), dp(48)));
        addView(dock, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LinearLayout tabs = new LinearLayout(getContext());
        tabs.setBackgroundColor(NativeCStyle.LOW);
        Button home = button(tabs, "語音輸入", Action.HOME, !busy);
        Button devices = button(tabs, "裝置連線", Action.CONNECTION, !busy);
        styleTab(home, R.drawable.native_c_mic, true, !busy);
        styleTab(devices, R.drawable.native_c_devices, false, !busy);
        for (Button tab : new Button[]{home, devices}) tab.setLayoutParams(new LayoutParams(0, dp(64), 1));
        addView(tabs, new LayoutParams(LayoutParams.MATCH_PARENT, dp(64)));
        updateLive(livePreview, null, false);
        requestFocus();
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
        this.panel = panel;
        transcript = null;
        waveform = null;
        removeAllViews();
        panelComputer = panelChannel = panelConnection = null;
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(content);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        TextView heading = text(content, panel == Action.CONNECTION ? "連線管理" : panel == Action.SETTINGS ? "設定"
                : panel == Action.LATEST ? "最近定稿" : panel == Action.KEY ? "Gemini 金鑰管理"
                : panel == Action.PERMISSION ? "麥克風權限管理" : "診斷／手動測試");
        heading.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        heading.setTextColor(NativeCStyle.GREEN);
        heading.setAccessibilityHeading(true);
        LinearLayout feedback = addCard(content, NativeCStyle.LOW);
        homeLine(feedback, "目前狀態", 12).setTextColor(NativeCStyle.MUTED);
        panelPhase = text(feedback, "");
        panelFeedback = text(feedback, "");
        panelFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panelRepairs = new LinearLayout(getContext());
        panelRepairs.setOrientation(VERTICAL);
        feedback.addView(panelRepairs);
        LinearLayout details = addCard(content, NativeCStyle.CARD);
        switch (panel) {
            case CONNECTION:
                panelComputer = text(details, state.computer);
                panelConnection = text(details, "");
                panelChannel = text(details, "");
                panelChannel.setTextColor(NativeCStyle.CYAN);
                text(details, "連線邀請是短效單次 QR；掃描成功不等於已取得應用授權，也不取代系統藍牙配對。");
                button(details, "掃描 QR 邀請", Action.SCAN_QR, true);
                button(details, "恢復既有應用授權", Action.RECOVER_AUTHORIZATION, true);
                LinearLayout channels = addCard(content, NativeCStyle.LOWEST);
                text(channels, "明確選擇通道 · 不自動切換或補送");
                button(channels, "通道：藍牙", Action.BLUETOOTH, true);
                button(channels, "通道：Wi-Fi", Action.WIFI, true);
                button(channels, "主動中斷連線", Action.DISCONNECT, true);
                break;
            case SETTINGS:
                text(details, "語音輸入的必要條件與低頻工具");
                button(details, "Gemini 金鑰", Action.KEY, true);
                button(details, "麥克風權限", Action.PERMISSION, true);
                button(details, "診斷／手動測試", Action.DIAGNOSTICS, true);
                break;
            case LATEST:
                text(details, "最近一段轉錄定稿（無持久歷史）");
                text(details, "查看與複製不會再次傳送；定稿不代表目標程式已接受文字。");
                text(details, state.latestFinalText.isEmpty() ? "尚無定稿" : state.latestFinalText);
                Button copy = button(details, "複製定稿", Action.COPY, !state.latestFinalText.isEmpty());
                copy.setOnClickListener(v -> actions.accept(new Event(Action.COPY, state.latestFinalText)));
                break;
            case DIAGNOSTICS:
                panelComputer = text(details, state.computer);
                text(details, "手動測試只送出一次；連線未就緒或操作進行中不送出。結果不會成為語音定稿，也不自動重送。");
                TextInputEditText input = input(details, "手動測試文字");
                input.setMinLines(3);
                input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                input.setSaveEnabled(false);
                Button send = button(details, "送出手動測試", Action.MANUAL_SEND, state.manualReady);
                send.setOnClickListener(v -> actions.accept(new Event(Action.MANUAL_SEND, input.getText().toString())));
                break;
            case KEY:
                text(details, "金鑰只由主程式安全儲存，已存金鑰不回填。輸入新的金鑰可取代原值；清除後需重新設定。修復後仍需主動開始。");
                TextInputEditText key = input(details, "新金鑰（不顯示已存金鑰）");
                key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                key.setSaveEnabled(false);
                Button save = button(details, "儲存金鑰", Action.SAVE_KEY, true);
                save.setOnClickListener(v -> {
                    String payload = key.getText().toString();
                    key.setText("");
                    actions.accept(new Event(Action.SAVE_KEY, payload));
                });
                Button clear = button(details, "清除金鑰", Action.CLEAR_KEY, true);
                clear.setOnClickListener(v -> { key.setText(""); actions.accept(new Event(Action.CLEAR_KEY, "")); });
                break;
            case PERMISSION:
                text(details, "麥克風只在主動開始後收音。權限請求使用 Android 系統介面；若已永久拒絕，請在應用設定允許麥克風。允許或返回 App 後不自動收音。");
                button(details, "請求麥克風權限", Action.REQUEST_PERMISSION, true);
                break;
            default: break;
        }
        update(state);
        LinearLayout navigation = new LinearLayout(getContext());
        navigation.setOrientation(VERTICAL);
        navigation.setBackgroundColor(NativeCStyle.LOW);
        navigation.setPadding(dp(16), dp(4), dp(16), dp(4));
        Button back = button(navigation, panel == Action.CONNECTION ? "語音輸入" : "返回主畫面", Action.HOME, true);
        if (panel == Action.CONNECTION) {
            navigation.setOrientation(HORIZONTAL);
            back.setContentDescription("語音輸入，返回主畫面");
            Button current = button(navigation, "裝置連線", Action.CONNECTION, true);
            styleTab(back, R.drawable.native_c_mic, false, true);
            styleTab(current, R.drawable.native_c_devices, true, true);
            for (Button tab : new Button[]{back, current}) tab.setLayoutParams(new LayoutParams(0, dp(64), 1));
        }
        addView(navigation, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }
    private void styleTab(Button tab, int icon, boolean selected, boolean enabled) {
        int color = selected ? NativeCStyle.GREEN : NativeCStyle.MUTED;
        tab.setSelected(selected);
        tab.setEnabled(enabled);
        tab.setTextColor(color);
        tab.setTextSize(10);
        MaterialButton material = (MaterialButton) tab;
        material.setStrokeWidth(0);
        material.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        material.setIconResource(icon);
        material.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        material.setIconSize(dp(22));
        material.setIconPadding(dp(2));
        material.setIconTint(android.content.res.ColorStateList.valueOf(color));
    }
    private LinearLayout addCard(LinearLayout parent, int color) {
        LinearLayout card = NativeCStyle.card(getContext(), color);
        LayoutParams bounds = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bounds.bottomMargin = dp(16);
        parent.addView(card, bounds);
        return card;
    }
    private TextView homeLine(LinearLayout parent, String value, int size) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(NativeCStyle.TEXT);
        view.setPadding(0, dp(3), 0, dp(3));
        parent.addView(view, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return view;
    }
    /** A clock tick must not replace the controls underneath an in-flight tap. */
    private boolean sameTimerPresentation(State next) {
        if (state == null || state.phase != next.phase || state.ready != next.ready
                || state.manualReady != next.manualReady || state.connected != next.connected
                || !state.computer.equals(next.computer) || !state.channel.equals(next.channel)
                || !state.latestFinalText.equals(next.latestFinalText)
                || !state.operationStatus.equals(next.operationStatus) || state.blockers.size() != next.blockers.size()) return false;
        for (int i = 0; i < state.blockers.size(); i++) {
            Blocker previous = state.blockers.get(i), current = next.blockers.get(i);
            if (previous.repair != current.repair || !previous.reason.equals(current.reason)) return false;
        }
        return true;
    }
    private void updateDeadline() {
        remainingDeadline.setText("剩餘期限 " + state.remainingSeconds + " 秒（包含準備時間）");
        deadlineWarning.setVisibility(state.remainingSeconds <= 5 ? View.VISIBLE : View.GONE);
    }
    private static boolean isBusy(Phase phase) {
        return phase == Phase.PREPARING || phase == Phase.RECORDING || phase == Phase.FINALIZING || phase == Phase.SENDING;
    }
    private String mainHint(boolean compact) {
        if (state.phase == Phase.RECORDING) return compact ? "點按以結束" : "按一下結束並送出";
        if (isBusy(state.phase)) return "請稍候";
        if (!state.ready || state.phase == Phase.UNAVAILABLE) return compact ? "請先修復條件" : "請先修復必要條件";
        return compact ? "點按以開始" : "按一下開始說話";
    }
    private String waitingText(Phase phase) {
        switch (phase) {
            case PREPARING: return "麥克風準備中，請稍候";
            case RECORDING: return "等待辨識文字";
            case FINALIZING: return "正在等待有效的轉錄定稿";
            case SENDING: return "正在傳送轉錄定稿";
            default: return "尚無辨識文字";
        }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (panel == null && micHalo != null) {
            boolean compact = View.MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom() < dp(620)
                    || getResources().getConfiguration().fontScale >= 1.5f;
            int haloSize = dp(compact ? 72 : 144);
            micHalo.getLayoutParams().width = haloSize;
            micHalo.getLayoutParams().height = haloSize;
            int micSize = dp(compact ? 64 : 96);
            android.widget.FrameLayout.LayoutParams host = (android.widget.FrameLayout.LayoutParams) ((View) micButton.getParent()).getLayoutParams();
            host.width = micSize; host.height = micSize;
            micButton.getLayoutParams().width = micSize; micButton.getLayoutParams().height = micSize;
            micButton.setIconSize(dp(compact ? 28 : 38));
            brandSubtitle.setVisibility(compact ? View.GONE : View.VISIBLE);
            actionHint.setTextSize(compact ? 14 : 18);
            actionHint.setText(mainHint(compact));
            cancelButton.getLayoutParams().height = dp(getResources().getConfiguration().fontScale >= 1.5f ? 64 : 48);
        }
        int contentWidth = Math.min(dp(560), Math.max(0, View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight()));
        for (int i = 0; i < getChildCount(); i++) {
            LayoutParams bounds = (LayoutParams) getChildAt(i).getLayoutParams();
            bounds.width = contentWidth;
            bounds.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)); view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view);
        return view;
    }
    private TextInputEditText input(LinearLayout parent, String hint) {
        TextInputLayout field = (TextInputLayout) android.view.LayoutInflater.from(getContext())
                .inflate(R.layout.native_c_input, parent, false);
        TextInputEditText edit = (TextInputEditText) field.getEditText();
        // Keep the hint on the edit view for both accessibility and the public test seam.
        edit.setHint(hint);
        parent.addView(field, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return edit;
    }
    private Button button(LinearLayout parent, String label, Action action, boolean enabled) {
        int layout = action == Action.START || action == Action.FINISH ? R.layout.native_c_main_button
                : action == Action.CANCEL || action == Action.HOME || action == Action.CLEAR_KEY || action == Action.DISCONNECT
                ? R.layout.native_c_outlined_button
                : action == Action.SAVE_KEY || action == Action.MANUAL_SEND || action == Action.REQUEST_PERMISSION
                || action == Action.COPY || action == Action.SCAN_QR ? R.layout.native_c_filled_button
                : R.layout.native_c_text_button;
        // A widget style is not a theme overlay: inflate explicit styles so default
        // materialButtonStyle cannot override the variant and its state-list colors.
        MaterialButton view = (MaterialButton) android.view.LayoutInflater.from(getContext()).inflate(layout, parent, false);
        view.setText(label);
        view.setTag(action);
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
