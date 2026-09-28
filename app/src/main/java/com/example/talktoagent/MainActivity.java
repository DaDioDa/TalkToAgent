package com.example.talktoagent;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private EditText ipAddressInput;
    private EditText portInput;
    private EditText pairingCodeInput;
    private EditText finalTextInput;
    private TextView receiverStatus;
    private Button authenticateButton;
    private Button sendButton;
    private Button talkButton;
    private EditText geminiKeyInput;
    private TextView voiceStatus;
    private GeminiKeyStore keyStore;
    private GeminiLiveTranscriber recognition;
    private VoiceUtterance utterance;
    private FinalTextTransport transport;
    private boolean receiverAuthenticated;
    private int transportGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("TalkToAgent 按住說話");
        keyStore = new GeminiKeyStore(this);
        utterance = new VoiceUtterance(this::deliverVoiceFinal,
                message -> voiceStatus.setText(message), TraditionalText::convert);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        scrollView.addView(content);
        setContentView(scrollView);

        TextView heading = new TextView(this);
        heading.setText("按住說話 → Windows");
        heading.setTextSize(22);
        content.addView(heading, fieldLayout());

        TextView scopeNotice = new TextView(this);
        scopeNotice.setText(
                "Gemini 僅在按下時連線；先設定金鑰並驗證 Receiver。"
                        + " ws:// 不加密，只能在可信任的家庭區網使用。"
        );
        scopeNotice.setTextSize(14);
        content.addView(scopeNotice, fieldLayout());

        ipAddressInput = addField(
                content,
                "Windows Receiver IP",
                "例如 192.168.1.20",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                ""
        );
        portInput = addField(
                content,
                "連接埠",
                "8765",
                InputType.TYPE_CLASS_NUMBER,
                "8765"
        );
        pairingCodeInput = addField(
                content,
                "配對碼",
                "從 Windows Receiver 啟動畫面輸入",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                ""
        );
        pairingCodeInput.setSaveEnabled(false);
        pairingCodeInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);

        geminiKeyInput = addField(content, "Gemini API key（儲存在本機加密儲存區）",
                "輸入金鑰後按儲存；留空可清除", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
        geminiKeyInput.setSaveEnabled(false);
        geminiKeyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        Button saveKeyButton = new Button(this);
        saveKeyButton.setText("儲存／清除 Gemini 金鑰");
        content.addView(saveKeyButton, fieldLayout());
        saveKeyButton.setOnClickListener(v -> {
            if (utterance.active()) cancelVoice();
            try {
                keyStore.save(geminiKeyInput.getText().toString().trim());
                geminiKeyInput.setText("");
                voiceStatus.setText(keyStore.read().isEmpty() ? "金鑰已清除" : "金鑰已儲存於手機");
            } catch (Exception failure) {
                voiceStatus.setText("無法安全儲存金鑰；語音輸入不可用");
            }
        });

        authenticateButton = new Button(this);
        authenticateButton.setText("驗證 Windows Receiver");
        content.addView(authenticateButton, fieldLayout());

        receiverStatus = new TextView(this);
        receiverStatus.setText("Receiver：尚未驗證");
        receiverStatus.setTextSize(16);
        receiverStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(receiverStatus, fieldLayout());

        voiceStatus = new TextView(this);
        voiceStatus.setText("尚未收音。先設定金鑰並驗證 Receiver；首次按下需允許麥克風權限。");
        voiceStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(voiceStatus, fieldLayout());
        talkButton = new Button(this);
        talkButton.setText("按住說話／放開結束");
        content.addView(talkButton, fieldLayout());
        talkButton.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pressToTalk();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                releaseToTalk();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                cancelVoice();
                return true;
            }
            return true;
        });

        finalTextInput = addField(
                content,
                "要手動貼上的文字",
                "輸入一次定稿文字",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                "TalkToAgent 手動測試"
        );
        finalTextInput.setMinLines(3);
        finalTextInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        finalTextInput.setSaveEnabled(false);

        sendButton = new Button(this);
        sendButton.setText("傳送並貼上一次");
        sendButton.setEnabled(false);
        content.addView(sendButton, fieldLayout());

        authenticateButton.setOnClickListener(view -> authenticateReceiver());
        sendButton.setOnClickListener(view -> sendFinalText());
    }

    private void authenticateReceiver() {
        if (utterance.active()) {
            voiceStatus.setText("先放開並等待本次辨識結束，再重新驗證 Receiver");
            return;
        }
        final int attempt = ++transportGeneration;
        if (transport != null) {
            transport.close();
            transport = null;
        }
        receiverAuthenticated = false;
        sendButton.setEnabled(false);

        String ipAddress = ipAddressInput.getText().toString().trim();
        String pairingCode = pairingCodeInput.getText().toString();
        final int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException exception) {
            showAuthenticationFailure("連接埠無效。");
            return;
        }
        if (ipAddress.isEmpty() || pairingCode.isEmpty() || port < 1 || port > 65535) {
            showAuthenticationFailure("請輸入有效 IP、連接埠與配對碼。");
            return;
        }

        authenticateButton.setEnabled(false);
        receiverStatus.setText("正在驗證 Windows Receiver…");

        FinalTextTransport.Listener listener = new FinalTextTransport.Listener() {
            @Override
            public void onAuthenticated() {
                runOnUiThread(() -> {
                    if (isFinishing() || attempt != transportGeneration) {
                        return;
                    }
                    receiverAuthenticated = true;
                    authenticateButton.setEnabled(true);
                    authenticateButton.setText("重新驗證 Windows Receiver");
                    sendButton.setEnabled(true);
                    receiverStatus.setText(
                            "Windows Receiver 已驗證；請先在 Windows 目標欄位放置游標。"
                                    + (keyStore.read().isEmpty() ? " 尚需設定 Gemini 金鑰。" : " 可按住說話。")
                    );
                });
            }

            @Override
            public void onAuthenticationFailed() {
                runOnUiThread(() -> {
                    if (attempt == transportGeneration && !isFinishing()) {
                        showAuthenticationFailure(
                                "配對驗證失敗；請確認 IP、連接埠與配對碼。"
                        );
                    }
                });
            }

            @Override
            public void onPasteComplete() {
                runOnUiThread(() -> {
                    if (attempt != transportGeneration || isFinishing()) {
                        return;
                    }
                    receiverAuthenticated = false;
                    authenticateButton.setEnabled(true);
                    sendButton.setEnabled(false);
                    receiverStatus.setText(
                            "Receiver 已確認完成剪貼簿與貼上動作；手機無法確認目標欄位是否顯示文字。"
                                    + "此連線已結束，不會自動重送；重新驗證才能再次輸入。"
                    );
                });
            }

            @Override
            public void onFailure(String reason) {
                runOnUiThread(() -> {
                    if (attempt != transportGeneration || isFinishing()) {
                        return;
                    }
                    receiverAuthenticated = false;
                    authenticateButton.setEnabled(true);
                    sendButton.setEnabled(false);
                    String message;
                    if ("invalid_text".equals(reason)) {
                        message = "文字不可為空白或超過 4 KiB UTF-8；未自動重送。";
                    } else if ("receiver_paste_failed".equals(reason)) {
                        message = "Receiver 貼上動作失敗；未自動重送。";
                    } else if ("receiver_invalid_message".equals(reason)) {
                        message = "Receiver 拒絕了訊息；未自動重送。";
                    } else {
                        message = "連線失敗或結果不明；未自動重送。請先檢查 Windows 目標欄位。";
                    }
                    receiverStatus.setText(message);
                    utterance.fail(message);
                    if (recognition != null) { recognition.close(); recognition = null; }
                });
            }
        };
        transport = new WebSocketFinalTextTransport(listener);
        transport.authenticate(ipAddress, port, pairingCode);
    }

    private void sendFinalText() {
        if (utterance.active()) return;
        if (!receiverAuthenticated || transport == null) {
            sendButton.setEnabled(false);
            return;
        }
        String finalText = finalTextInput.getText().toString();
        receiverAuthenticated = false;
        sendButton.setEnabled(false);
        receiverStatus.setText(
                "已送出一次，等待 Receiver 確認；不會自動重送。"
        );
        transport.sendFinalText(finalText);
    }

    private void pressToTalk() {
        if (utterance.active()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceStatus.setText("需要麥克風權限；本次未收音，授權後請重新按住說話");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        String key = keyStore.read();
        if (key.isEmpty()) {
            voiceStatus.setText("請先儲存 Gemini 金鑰，未收音或傳送");
            return;
        }
        if (!utterance.press(true, receiverAuthenticated)) return;
        talkButton.setPressed(true);
        voiceStatus.setText("正在連接 Gemini，尚未開始收音");
        recognition = new GeminiLiveTranscriber(key, new GeminiLiveTranscriber.Listener() {
            @Override public void onStage(String stage) { voiceStatus.setText(stage); }
            @Override public void onInterim(String text) { utterance.interim(text); }
            @Override public void onFinal(String text) { utterance.finalText(text); recognition = null; }
            @Override public void onError(String reason) { utterance.fail(reason); recognition = null; }
        });
    }

    private void releaseToTalk() {
        talkButton.setPressed(false);
        if (utterance.release() && recognition != null) recognition.release();
    }

    private void cancelVoice() {
        talkButton.setPressed(false);
        utterance.fail("操作已取消，未傳送");
        if (recognition != null) { recognition.close(); recognition = null; }
    }

    private void deliverVoiceFinal(String text) {
        // The same one-shot authenticated socket used by the manual demonstration.
        if (!receiverAuthenticated || transport == null) {
            voiceStatus.setText("Receiver 已失去驗證，未傳送");
            return;
        }
        receiverAuthenticated = false;
        sendButton.setEnabled(false);
        finalTextInput.setText(text);
        transport.sendFinalText(text);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1) {
            voiceStatus.setText(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                    ? "已允許麥克風；請重新按住說話（本次沒有收音）"
                    : "麥克風權限遭拒，未收音或傳送；請在系統設定允許後重試");
        }
    }

    private void showAuthenticationFailure(String message) {
        receiverAuthenticated = false;
        authenticateButton.setEnabled(true);
        sendButton.setEnabled(false);
        receiverStatus.setText(message);
    }

    private EditText addField(
            LinearLayout parent,
            String label,
            String hint,
            int inputType,
            String initialValue
    ) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(15);
        parent.addView(labelView, fieldLayout());

        EditText editText = new EditText(this);
        editText.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        editText.setInputType(inputType);
        editText.setHint(hint);
        editText.setText(initialValue);
        parent.addView(editText, fieldLayout());
        return editText;
    }

    private LinearLayout.LayoutParams fieldLayout() {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        layout.bottomMargin = dp(12);
        return layout;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onPause() {
        super.onPause();
        if (utterance.active()) cancelVoice();
    }

    @Override
    protected void onDestroy() {
        cancelVoice();
        transportGeneration++;
        if (transport != null) {
            transport.close();
            transport = null;
        }
        super.onDestroy();
    }
}
