package com.example.talktoagent;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import java.util.ArrayList;
import java.util.List;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
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
    private static final int CHANNEL_WIFI = 0;
    private static final int CHANNEL_BLUETOOTH = 1;
    private Spinner channelInput;
    private int selectedChannel;
    private Spinner deviceInput;
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private BluetoothConnectionService bluetooth;
    private boolean bluetoothPending;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            bluetooth = ((BluetoothConnectionService.LocalBinder) binder).service();
            bluetooth.observe((ready, pending, status) -> {
                if (!bluetoothSelected()) return;
                receiverAuthenticated = ready;
                bluetoothPending = pending;
                receiverStatus.setText(status);
                sendButton.setEnabled(ready && !pending);
                refreshReadiness();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bluetooth = null;
            receiverAuthenticated = false;
            refreshReadiness();
        }
    };
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
    private TextView readinessDot;
    private TextView readinessLabel;
    private TextView recentText;
    private Button copyRecentButton;
    private String knownFailure;
    private GeminiKeyStore keyStore;
    private GeminiLiveTranscriber recognition;
    private int voiceGeneration;
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

        LinearLayout readinessRow = new LinearLayout(this);
        readinessRow.setOrientation(LinearLayout.HORIZONTAL);
        readinessDot = new TextView(this);
        readinessDot.setText("●  ");
        readinessDot.setTextSize(22);
        readinessRow.addView(readinessDot);
        readinessLabel = new TextView(this);
        readinessLabel.setTextSize(16);
        readinessLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        readinessRow.addView(readinessLabel);
        content.addView(readinessRow, fieldLayout());

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

        channelInput = new Spinner(this);
        channelInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Wi-Fi（可信任家庭區網）", "藍牙（已系統配對）"}));
        content.addView(channelInput, fieldLayout());
        selectedChannel = savedInstanceState == null ? CHANNEL_WIFI
                : savedInstanceState.getInt("channel", CHANNEL_WIFI);
        channelInput.setSelection(selectedChannel);
        deviceInput = new Spinner(this);
        content.addView(deviceInput, fieldLayout());
        Button listDevices = new Button(this);
        listDevices.setText("列出已配對藍牙電腦");
        content.addView(listDevices, fieldLayout());
        listDevices.setOnClickListener(v -> listPairedDevices());
        Button disconnectButton = new Button(this);
        disconnectButton.setText("手動中斷藍牙");
        content.addView(disconnectButton, fieldLayout());
        disconnectButton.setOnClickListener(v -> disconnectBluetooth());
        channelInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == selectedChannel) return;
                selectedChannel = position;
                cancelVoice();
                transportGeneration++;
                if (transport != null) { transport.close(); transport = null; }
                disconnectBluetooth();
                receiverAuthenticated = false;
                bluetoothPending = false;
                sendButton.setEnabled(false);
                refreshReadiness();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
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
                knownFailure = null;
                voiceStatus.setText(keyStore.read().isEmpty() ? "金鑰已清除" : "金鑰已儲存於手機");
            } catch (Exception failure) {
                knownFailure = "無法安全儲存金鑰";
                voiceStatus.setText("無法安全儲存金鑰；語音輸入不可用");
            }
            refreshReadiness();
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

        TextView recentLabel = new TextView(this);
        recentLabel.setText("最近一次口述定稿（僅在目前畫面暫留）");
        content.addView(recentLabel, fieldLayout());
        recentText = new TextView(this);
        recentText.setText("尚無定稿");
        recentText.setTextIsSelectable(true);
        recentText.setSaveEnabled(false);
        content.addView(recentText, fieldLayout());
        copyRecentButton = new Button(this);
        copyRecentButton.setText("手動複製最近定稿");
        copyRecentButton.setEnabled(false);
        copyRecentButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("口述定稿", recentText.getText()));
            voiceStatus.setText("已手動複製最近定稿");
        });
        content.addView(copyRecentButton, fieldLayout());

        sendButton = new Button(this);
        sendButton.setText("傳送並貼上一次");
        sendButton.setEnabled(false);
        content.addView(sendButton, fieldLayout());

        authenticateButton.setOnClickListener(view -> authenticateReceiver());
        sendButton.setOnClickListener(view -> sendFinalText());
        refreshReadiness();
    }

    private void refreshReadiness() {
        if (readinessDot == null || readinessLabel == null) return;
        InputReadiness.Display display = InputReadiness.evaluate(receiverAuthenticated,
                !keyStore.read().isEmpty(),
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                utterance.active(), knownFailure);
        readinessDot.setTextColor(display.ready() ? Color.rgb(46, 125, 50)
                : display.kind == InputReadiness.Kind.FAILED ? Color.rgb(176, 0, 32)
                : display.kind == InputReadiness.Kind.BUSY ? Color.rgb(153, 85, 0)
                : Color.DKGRAY);
        readinessLabel.setText(display.label());
        readinessDot.setContentDescription(display.label());
    }

    private void disconnectBluetooth() {
        // A started service may exist before the binding callback (or after unbind).
        startService(new Intent(this, BluetoothConnectionService.class).setAction(BluetoothConnectionService.DISCONNECT));
    }

    private boolean bluetoothSelected() {
        return channelInput != null && channelInput.getSelectedItemPosition() == CHANNEL_BLUETOOTH;
    }

    private void listPairedDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
            receiverStatus.setText("需允許附近裝置權限，授權後再列出已配對裝置");
            return;
        }
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        pairedDevices.clear();
        if (adapter != null && adapter.isEnabled()) pairedDevices.addAll(adapter.getBondedDevices());
        List<String> names = new ArrayList<>();
        for (BluetoothDevice device : pairedDevices) names.add(device.getName() + " (" + device.getAddress() + ")");
        deviceInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        if (names.isEmpty()) receiverStatus.setText("找不到已配對裝置；請先在系統設定配對並開啟藍牙");
    }

    private void authenticateBluetooth() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
            receiverStatus.setText("需允許附近裝置權限，授權後再連線");
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
            receiverStatus.setText("需允許通知以顯示背景連線及中斷操作，授權後再連線");
            return;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = BluetoothConnectionService.ensureChannel(notifications);
        if (!notifications.areNotificationsEnabled() || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            receiverStatus.setText("藍牙背景連線需要通知；請在系統設定開啟本應用通知及藍牙輸入連線通知類別後重試");
            return;
        }
        int selected = deviceInput.getSelectedItemPosition();
        String code = pairingCodeInput.getText().toString();
        if (selected < 0 || selected >= pairedDevices.size() || code.isEmpty() || bluetooth == null) {
            receiverStatus.setText("請先選擇已配對電腦、輸入 Receiver 配對碼；服務未就緒請稍後重試");
            return;
        }
        transportGeneration++;
        if (transport != null) { transport.close(); transport = null; }
        receiverAuthenticated = false;
        sendButton.setEnabled(false);
        try {
            startForegroundService(new Intent(this, BluetoothConnectionService.class));
            bluetooth.connect(pairedDevices.get(selected), code);
        } catch (RuntimeException failure) {
            receiverStatus.setText("無法啟動背景服務；請檢查藍牙及通知權限");
        }
    }

    private void authenticateReceiver() {
        if (bluetoothSelected()) { authenticateBluetooth(); return; }
        disconnectBluetooth();
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
        knownFailure = null;
        sendButton.setEnabled(false);
        refreshReadiness();

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
                    knownFailure = null;
                    refreshReadiness();
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
                    refreshReadiness();
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
                    knownFailure = message;
                    utterance.fail(message);
                    refreshReadiness();
                    if (recognition != null) { recognition.close(); recognition = null; }
                });
            }
        };
        transport = new WebSocketFinalTextTransport(listener);
        transport.authenticate(ipAddress, port, pairingCode);
    }

    private void sendFinalText() {
        if (utterance.active()) return;
        if (bluetoothSelected()) {
            if (bluetooth == null || !bluetooth.send(finalTextInput.getText().toString()))
                receiverStatus.setText("藍牙未接受本次文字；未傳送，請確認連線與貼上結果");
            return;
        }
        if (!receiverAuthenticated || transport == null) {
            sendButton.setEnabled(false);
            return;
        }
        String finalText = finalTextInput.getText().toString();
        receiverAuthenticated = false;
        sendButton.setEnabled(false);
        refreshReadiness();
        receiverStatus.setText(
                "已送出一次，等待 Receiver 確認；不會自動重送。"
        );
        transport.sendFinalText(finalText);
    }

    private void pressToTalk() {
        if (utterance.active()) return;
        if (bluetoothSelected() && bluetoothPending) {
            voiceStatus.setText("正在等待藍牙貼上結果；本次未收音或傳送");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceStatus.setText("需要麥克風權限；本次未收音，授權後請重新按住說話");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            refreshReadiness();
            return;
        }
        String key = keyStore.read();
        if (key.isEmpty()) {
            voiceStatus.setText("請先儲存 Gemini 金鑰，未收音或傳送");
            return;
        }
        if (!utterance.press(true, receiverAuthenticated)) { refreshReadiness(); return; }
        knownFailure = null;
        refreshReadiness();
        talkButton.setPressed(true);
        voiceStatus.setText("正在連接 Gemini，尚未開始收音");
        final int thisVoice = ++voiceGeneration;
        recognition = new GeminiLiveTranscriber(key, new GeminiLiveTranscriber.Listener() {
            @Override public void onStage(String stage) { if (thisVoice == voiceGeneration) voiceStatus.setText(stage); }
            @Override public void onInterim(String text) { if (thisVoice == voiceGeneration) utterance.interim(text); }
            @Override public void onFinal(String text) {
                if (thisVoice != voiceGeneration) return;
                utterance.finalText(text);
                if (receiverAuthenticated) knownFailure = voiceStatus.getText().toString();
                refreshReadiness();
                recognition = null;
            }
            @Override public void onError(String reason) {
                if (thisVoice != voiceGeneration) return;
                utterance.fail(reason);
                knownFailure = reason;
                refreshReadiness();
                recognition = null;
            }
        });
    }

    private void releaseToTalk() {
        talkButton.setPressed(false);
        if (utterance.release() && recognition != null) recognition.release();
        refreshReadiness();
    }

    private void cancelVoice() {
        voiceGeneration++;
        talkButton.setPressed(false);
        if (utterance.active()) {
            utterance.fail("操作已取消，未傳送");
            knownFailure = "操作已取消，未傳送";
        }
        if (recognition != null) { recognition.close(); recognition = null; }
        refreshReadiness();
    }

    private void deliverVoiceFinal(String text) {
        // Keep only the current on-screen final, even if delivery fails; never queue a retry.
        recentText.setText(text);
        copyRecentButton.setEnabled(true);
        if (!receiverAuthenticated || (bluetoothSelected() ? bluetooth == null : transport == null)) {
            knownFailure = "Receiver 已失去驗證，未傳送";
            voiceStatus.setText(knownFailure);
            refreshReadiness();
            return;
        }
        if (bluetoothSelected()) {
            if (bluetoothPending || !bluetooth.send(text)) {
                knownFailure = "藍牙未接受本次文字；未傳送，請檢查最近定稿";
                voiceStatus.setText(knownFailure);
                refreshReadiness();
            }
        } else {
            receiverAuthenticated = false;
            sendButton.setEnabled(false);
            refreshReadiness();
            transport.sendFinalText(text);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 2 || requestCode == 3) receiverStatus.setText("權限已更新；請重新選擇裝置並主動連線。拒絕時無法維持藍牙背景服務。");
        if (requestCode == 1) {
            voiceStatus.setText(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                    ? "已允許麥克風；請重新按住說話（本次沒有收音）"
                    : "麥克風權限遭拒，未收音或傳送；請在系統設定允許後重試");
            refreshReadiness();
        }
    }

    private void showAuthenticationFailure(String message) {
        receiverAuthenticated = false;
        knownFailure = message;
        authenticateButton.setEnabled(true);
        sendButton.setEnabled(false);
        receiverStatus.setText(message);
        refreshReadiness();
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

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("channel", selectedChannel);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, BluetoothConnectionService.class), serviceConnection, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (utterance.active()) cancelVoice();
        if (bluetooth != null) bluetooth.observe(null);
        unbindService(serviceConnection);
        bluetooth = null;
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshReadiness();
    }

    @Override protected void onPause() {
        super.onPause();
        if (utterance.active()) cancelVoice();
        refreshReadiness();
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
