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
    private ConnectionCoordinator.Targets targetsSnapshot;
    private Button restorePendingButton;
    private long snapshotGeneration;
    private long bondDeadline;
    private boolean storageUnavailable;
    /** Memory only. No Activity, executor, callback, Intent, Bundle or QR serialization. */
    private static final class BondRetention {
        final ConnectionCoordinator coordinator;
        final AuthorizationStore store;
        final BluetoothDevice device;
        final long deadline;
        BondRetention(ConnectionCoordinator coordinator, AuthorizationStore store, BluetoothDevice device, long deadline) {
            this.coordinator = coordinator; this.store = store; this.device = device; this.deadline = deadline;
        }
    }
    private AuthorizationStore authorizationStore;
    private ConnectionCoordinator coordinator;
    private ConnectionCoordinator.Preparation ownedPreparation;
    private final java.util.concurrent.ExecutorService authorizationWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Button scanButton;
    private TextView targetLabel;
    private boolean connectingAttempt, waitingBond, activityVisible;
    private int bondGeneration;
    private BluetoothDevice bondingDevice;
    private Runnable permissionContinuation;
    private boolean bondReceiverRegistered;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.content.BroadcastReceiver bondReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (!waitingBond || device == null || !device.equals(bondingDevice)) return;
            reconcileBond();
        }
    };
    private BluetoothConnectionService bluetooth;
    private boolean bluetoothPending;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            bluetooth = ((BluetoothConnectionService.LocalBinder) binder).service();
            if (bluetooth.coordinator() != null) coordinator = bluetooth.coordinator();
            bluetooth.observe((ready, pending, status) -> {
                if (!bluetoothSelected()) return;
                receiverAuthenticated = ready;
                if (ready) knownFailure = null;
                if (!ready && utterance.active()) cancelVoice();
                bluetoothPending = pending;
                if (authorizationStore != null) reloadTargets();
                receiverStatus.setText(status);
                sendButton.setEnabled(ready && !pending);
                refreshReadiness();
            });
            if (waitingBond && activityVisible) reconcileBond();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bluetooth = null;
            receiverAuthenticated = false;
            refreshReadiness();
        }
    };
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
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof BondRetention) {
            BondRetention bond = (BondRetention) retained;
            coordinator = bond.coordinator; authorizationStore = bond.store;
            bondingDevice = bond.device; bondDeadline = bond.deadline;
            waitingBond = coordinator.waitingForSystemBond(); connectingAttempt = waitingBond;
        }
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
                new String[]{"Wi-Fi（已授權目標）", "藍牙（已授權目標）"}));
        content.addView(channelInput, fieldLayout());
        selectedChannel = savedInstanceState == null ? CHANNEL_WIFI
                : savedInstanceState.getInt("channel", CHANNEL_WIFI);
        channelInput.setSelection(selectedChannel);
        targetLabel = new TextView(this);
        targetLabel.setText("尚未取得應用授權；請掃描電腦的短效邀請");
        content.addView(targetLabel, fieldLayout());
        scanButton = new Button(this); scanButton.setText("掃描連線邀請 QR");
        content.addView(scanButton, fieldLayout());
        scanButton.setOnClickListener(v -> scanInvitation());
        Button disconnectButton = new Button(this);
        disconnectButton.setText("手動中斷連線／取消配對等待");
        content.addView(disconnectButton, fieldLayout());
        disconnectButton.setOnClickListener(v -> disconnectAll());
        channelInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == selectedChannel) return;
                disconnectAll(); selectedChannel = position; updateTargetLabel();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
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
        authenticateButton.setText("連線（恢復既有應用授權）");
        content.addView(authenticateButton, fieldLayout());
        restorePendingButton = new Button(this);
        restorePendingButton.setText("恢復上次未完成授權"); restorePendingButton.setEnabled(false);
        restorePendingButton.setOnClickListener(v -> authenticateReceiver(true));
        content.addView(restorePendingButton, fieldLayout());

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
        if (savedInstanceState != null && savedInstanceState.getBoolean("bondWasWaiting") && !(retained instanceof BondRetention))
            receiverStatus.setText("配對邀請因程序結束已失效；已停止，請取得新邀請重掃（不會冷啟動自動連線）");
        reloadTargets();
    }

    private void refreshReadiness() {
        if (readinessDot == null || readinessLabel == null) return;
        if (coordinator != null) coordinator.setUtteranceActive(utterance.active());
        if (restorePendingButton != null) restorePendingButton.setEnabled(targetsSnapshot != null
                && targetsSnapshot.pending(bluetoothSelected() ? "bt" : "wifi") != null
                && coordinator != null && coordinator.canScan() && !connectingAttempt && !waitingBond
                && (bluetooth == null || !bluetooth.busy()));
        if (scanButton != null) scanButton.setEnabled(authorizationStore != null && coordinator != null && coordinator.canScan()
                && !connectingAttempt && !waitingBond && !bluetoothPending && (bluetooth == null || !bluetooth.busy()));
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

    private void scanInvitation() {
        if (coordinator == null || !coordinator.canScan() || connectingAttempt || waitingBond || bluetoothPending
                || (bluetooth != null && bluetooth.busy())) return;
        ensurePermission(Manifest.permission.CAMERA, "掃描電腦短效 QR 需要相機；不會儲存 QR 圖片", () ->
                startActivityForResult(new Intent(this, InvitationScanActivity.class), 40));
    }
    private void updateTargetLabel() {
        if (targetLabel == null) return;
        String channel = bluetoothSelected() ? "bt" : "wifi";
        AuthorizationRepository.Endpoint endpoint = null;
        if (targetsSnapshot != null) for (AuthorizationRepository.Endpoint value : targetsSnapshot.active)
            if (channel.equals(value.channel)) endpoint = value;
        AuthorizationRepository.Endpoint pending = targetsSnapshot == null ? null : targetsSnapshot.pending(channel);
        String label = endpoint == null ? "此通道尚無已授權電腦" : "已授權電腦：" + endpoint.target
                + "（" + endpoint.fingerprint.substring(0, 12) + "…）";
        if (pending != null) label += "\n上次未完成授權（尚非 active）：" + pending.target + "；可明確按恢復";
        targetLabel.setText(label); refreshReadiness();
    }
    private void reloadTargets() {
        if (storageUnavailable || authorizationWorker.isShutdown() || isDestroyed()) return;
        long request = ++snapshotGeneration;
        int attempt = transportGeneration;
        AuthorizationStore existing = authorizationStore;
        Context application = getApplicationContext();
        authorizationWorker.execute(() -> {
            try {
                AuthorizationStore store = existing == null ? new AuthorizationStore(application) : existing;
                ConnectionCoordinator loader = new ConnectionCoordinator(store);
                ConnectionCoordinator.Targets targets = loader.loadTargets();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || request != snapshotGeneration || attempt != transportGeneration) return;
                    authorizationStore = store;
                    if (coordinator == null) coordinator = loader;
                    targetsSnapshot = targets; updateTargetLabel();
                });
            } catch (Exception failure) { runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || request != snapshotGeneration || attempt != transportGeneration) return;
                storageUnavailable = true; authorizationStore = null; targetsSnapshot = null;
                disconnectAll(); showAuthenticationFailure("授權儲存不可讀；連線已停止，不會重建授權");
            }); }
        });
    }
    private void disconnectAll() {
        cancelVoice(); stopBondWait(); connectingAttempt = false; permissionContinuation = null;
        transportGeneration++; snapshotGeneration++;
        if (transport != null) { transport.close(); transport = null; }
        if (bluetooth != null) bluetooth.disconnect(); else disconnectBluetooth();
        if (coordinator != null) coordinator.disconnected();
        receiverAuthenticated = false; bluetoothPending = false; sendButton.setEnabled(false);
        authenticateButton.setEnabled(true);
        receiverStatus.setText(coordinator != null && coordinator.outcomeUnknown()
                ? "輸入結果不明；先檢查電腦，未自動補送" : "已手動中斷；不會自動重連");
        reloadTargets(); refreshReadiness();
    }
    private void ensurePermission(String permission, String rationale, Runnable continuation) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { continuation.run(); return; }
        receiverAuthenticated = false; refreshReadiness(); permissionContinuation = continuation;
        Runnable request = () -> requestPermissions(new String[]{permission}, 20);
        if (shouldShowRequestPermissionRationale(permission)) new android.app.AlertDialog.Builder(this)
                .setMessage(rationale).setPositiveButton("允許", (dialog, which) -> request.run())
                .setNegativeButton("取消", (dialog, which) -> { permissionContinuation = null; connectingAttempt = false; refreshReadiness(); }).show();
        else request.run();
    }
    private void showSettings(String message) {
        new android.app.AlertDialog.Builder(this).setMessage(message)
                .setPositiveButton("開啟設定", (dialog, which) -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton("取消", null).show();
    }
    private void authenticateBluetooth() {
        ensurePermission(Manifest.permission.BLUETOOTH_CONNECT, "需要附近裝置權限以連接 QR 指定電腦及完成系統配對", () ->
            ensurePermission(Manifest.permission.POST_NOTIFICATIONS, "需要通知以保留藍牙背景連線及手動中斷操作", this::prepareBluetooth));
    }
    private void prepareBluetooth() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = BluetoothConnectionService.ensureChannel(notifications);
        if (!notifications.areNotificationsEnabled() || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            connectingAttempt = false; showSettings("請允許通知及藍牙輸入連線通知類別，再按連線"); refreshReadiness(); return;
        }
        try {
            BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
            if (adapter == null || !adapter.isEnabled()) { connectingAttempt = false; showSettings("請在系統設定開啟藍牙，再按連線"); refreshReadiness(); return; }
            if (bluetooth == null) { showAuthenticationFailure("背景服務尚未就緒；請稍後按連線"); return; }
            BluetoothDevice device = adapter.getRemoteDevice(coordinator.selected().bluetoothAddress());
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) { startBluetoothSession(device); return; }
            coordinator.waitForSystemBond(); bondingDevice = device; waitingBond = true;
            bondDeadline = android.os.SystemClock.elapsedRealtime() + 120000;
            attachBondReceiver();
            receiverStatus.setText("等待指定電腦的系統藍牙配對確認；尚未取得應用授權"); refreshReadiness();
            if (device.getBondState() != BluetoothDevice.BOND_BONDING && !device.createBond()) { bondCancelled(); return; }
        } catch (SecurityException failure) { bondCancelled(); showSettings("附近裝置權限已撤銷；請修復後重掃"); }
        catch (RuntimeException failure) { bondCancelled(); }
    }
    private void startBluetoothSession(BluetoothDevice device) {
        try {
            connectingAttempt = false;
            startForegroundService(new Intent(this, BluetoothConnectionService.class));
            bluetooth.connect(device, coordinator); refreshReadiness();
        } catch (RuntimeException failure) { showAuthenticationFailure("無法啟動背景連線；請檢查藍牙及通知權限"); }
    }
    private void attachBondReceiver() {
        if (!waitingBond || bondingDevice == null || bondReceiverRegistered) return;
        registerReceiver(bondReceiver, new android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED);
        bondReceiverRegistered = true; // Protected system Bluetooth broadcast; actual state is queried below.
        int attempt = ++bondGeneration;
        long remaining = Math.max(0, bondDeadline - android.os.SystemClock.elapsedRealtime());
        main.postDelayed(() -> { if (waitingBond && attempt == bondGeneration && !isDestroyed()) bondCancelled(); }, remaining);
    }
    private void reconcileBond() {
        if (!waitingBond || bondingDevice == null || coordinator == null || !activityVisible || isDestroyed()) return;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            bondCancelled(); showSettings("配對等待所需權限已撤銷；請修復後重掃"); return;
        }
        try {
            int actual = bondingDevice.getBondState();
            if (actual == BluetoothDevice.BOND_BONDED && bluetooth == null) return; // Reconcile again after binding.
            ConnectionCoordinator.BondState state = actual == BluetoothDevice.BOND_BONDED ? ConnectionCoordinator.BondState.BONDED
                    : actual == BluetoothDevice.BOND_BONDING ? ConnectionCoordinator.BondState.BONDING : ConnectionCoordinator.BondState.NONE;
            ConnectionCoordinator.BondProgress progress = coordinator.observeSystemBond(coordinator.selected().target, state);
            if (progress == ConnectionCoordinator.BondProgress.CONNECT) {
                BluetoothDevice device = bondingDevice; stopBondWait();
                NotificationManager notifications = getSystemService(NotificationManager.class);
                if (!notifications.areNotificationsEnabled() || BluetoothConnectionService.ensureChannel(notifications).getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    coordinator.disconnected(); connectingAttempt = false; showSettings("通知不可用；已停止續接，請修復後重掃"); refreshReadiness(); return;
                }
                startBluetoothSession(device);
            } else if (progress == ConnectionCoordinator.BondProgress.CANCELLED) bondCancelled();
            else { receiverStatus.setText("等待指定電腦系統配對；邀請僅保留於記憶體"); refreshReadiness(); }
        } catch (SecurityException failure) { bondCancelled(); showSettings("附近裝置權限已撤銷；請修復後重掃"); }
    }
    private void stopBondWait() {
        waitingBond = false; bondingDevice = null; bondGeneration++;
        if (bondReceiverRegistered) { unregisterReceiver(bondReceiver); bondReceiverRegistered = false; }
    }
    private void bondCancelled() {
        stopBondWait(); connectingAttempt = false;
        receiverStatus.setText("系統配對已取消或未完成；已停止嘗試，請取得新邀請重掃");
        if (coordinator != null) coordinator.disconnected();
        refreshReadiness();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 40 || resultCode != RESULT_OK || data == null) return;
        if (coordinator == null || authorizationStore == null || !coordinator.canScan() || connectingAttempt || waitingBond) {
            receiverStatus.setText("授權儲存尚未就緒或連線忙碌；請稍後重掃，既有授權未變更"); return;
        }
        String uri = data.getStringExtra(InvitationScanActivity.RESULT);
        connectingAttempt = true; int scanAttempt = ++transportGeneration; refreshReadiness();
        authorizationWorker.execute(() -> {
            try {
                Invitation invitation = Invitation.parse(uri);
                List<AuthorizationRepository.Endpoint> pinned = authorizationStore.active();
                AuthorizationRepository.Endpoint pending = authorizationStore.pending();
                if (pending != null) pinned.add(pending);
                for (AuthorizationRepository.Endpoint endpoint : pinned)
                    if (!endpoint.fingerprint.equals(invitation.fingerprint)) throw new SecurityException();
                runOnUiThread(() -> {
                    if (isDestroyed() || scanAttempt != transportGeneration || !connectingAttempt) return;
                    try {
                        coordinator.selectInvitation(uri);
                        int channel = "bt".equals(coordinator.selected().channel) ? CHANNEL_BLUETOOTH : CHANNEL_WIFI;
                        selectedChannel = channel; channelInput.setSelection(channel);
                        targetLabel.setText("邀請指定電腦：" + coordinator.selected().target + "；尚未授權"); connectSelected();
                    } catch (Exception failure) { showAuthenticationFailure("QR 無效或連線忙碌；既有授權未變更"); }
                });
            } catch (Exception failure) { runOnUiThread(() -> {
                if (!isDestroyed() && scanAttempt == transportGeneration) showAuthenticationFailure("QR 無效、不同授權電腦或儲存不可讀；既有授權未變更");
            }); }
        });
    }
    private void connectSelected() {
        connectingAttempt = true; refreshReadiness();
        if (bluetoothSelected()) authenticateBluetooth(); else authenticateWifi();
    }

    private void authenticateReceiver() { authenticateReceiver(false); }
    private void authenticateReceiver(boolean pendingOnly) {
        if (coordinator == null || connectingAttempt || waitingBond || !coordinator.canScan()
                || (bluetooth != null && bluetooth.busy())) return;
        String channel = bluetoothSelected() ? "bt" : "wifi";
        AuthorizationRepository.Endpoint endpoint = targetsSnapshot == null ? null
                : pendingOnly ? targetsSnapshot.pending(channel) : targetsSnapshot.normal(channel);
        if (endpoint == null) { showAuthenticationFailure("此通道無可恢復授權；請掃描電腦的有效邀請"); return; }
        connectingAttempt = true; int attempt = ++transportGeneration; snapshotGeneration++; refreshReadiness();
        ownedPreparation = coordinator.prepareResume(endpoint, authorizationWorker, command -> main.post(command), new ConnectionCoordinator.PreparationListener() {
            @Override public void prepared() {
                if (!isDestroyed() && !isFinishing() && attempt == transportGeneration && connectingAttempt) connectSelected();
            }
            @Override public void failed() {
                if (!isDestroyed() && !isFinishing() && attempt == transportGeneration && connectingAttempt)
                    showAuthenticationFailure("授權不可恢復；請取得新邀請重掃");
            }
        });
    }
    private void authenticateWifi() {
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

        connectingAttempt = true;
        authenticateButton.setEnabled(false);
        receiverStatus.setText("正在驗證 Windows Receiver…");

        FinalTextTransport.Listener listener = new FinalTextTransport.Listener() {
            @Override
            public void onAuthenticated() {
                runOnUiThread(() -> {
                    if (isFinishing() || attempt != transportGeneration) {
                        return;
                    }
                    connectingAttempt = false; receiverAuthenticated = true;
                    knownFailure = null; reloadTargets();
                    refreshReadiness();
                    authenticateButton.setEnabled(true);
                    authenticateButton.setText("連線（恢復應用授權）");
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
                                "應用授權被拒絕；請取得新邀請重掃。"
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
                                    + "此連線已結束，不會自動重送；按連線才能再次輸入。"
                                    + (coordinator.outcomeUnknown() ? " 較早一筆輸入結果仍不明；請檢查電腦。" : "")
                    );
                });
            }

            @Override
            public void onFailure(String reason) {
                runOnUiThread(() -> {
                    if (attempt != transportGeneration || isFinishing()) {
                        return;
                    }
                    connectingAttempt = false; receiverAuthenticated = false;
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
                    knownFailure = message; reloadTargets();
                    utterance.fail(message);
                    refreshReadiness();
                    if (recognition != null) { recognition.close(); recognition = null; }
                });
            }
        };
        transport = new WebSocketFinalTextTransport(listener);
        transport.authenticate(coordinator);
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
        if (requestCode == 20) {
            Runnable next = permissionContinuation; permissionContinuation = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) { if (next != null) next.run(); }
            else {
                connectingAttempt = false;
                receiverStatus.setText("所需權限遭拒，未取得輸入就緒；修復後重掃或按連線");
                if (permissions.length > 0 && !shouldShowRequestPermissionRationale(permissions[0])) showSettings("權限已拒絕；請在應用設定允許相機、附近裝置或通知");
                refreshReadiness();
            }
        }
        if (requestCode == 1) {
            voiceStatus.setText(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                    ? "已允許麥克風；請重新按住說話（本次沒有收音）"
                    : "麥克風權限遭拒，未收音或傳送；請在系統設定允許後重試");
            refreshReadiness();
        }
    }

    private void showAuthenticationFailure(String message) {
        connectingAttempt = false; receiverAuthenticated = false;
        knownFailure = message;
        if (authorizationStore != null) reloadTargets();
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

    @Override public Object onRetainNonConfigurationInstance() {
        return waitingBond && coordinator != null ? new BondRetention(coordinator, authorizationStore, bondingDevice, bondDeadline) : null;
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("bondWasWaiting", waitingBond); // Never persist the invitation, pin or secret in a Bundle.
        state.putInt("channel", selectedChannel);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart(); activityVisible = true;
        if (waitingBond && bondingDevice != null) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) bondCancelled();
            else { attachBondReceiver(); reconcileBond(); }
        }
        bindService(new Intent(this, BluetoothConnectionService.class), serviceConnection, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        activityVisible = false;
        if (utterance.active()) cancelVoice();
        if (bluetooth != null) bluetooth.observe(null);
        unbindService(serviceConnection);
        bluetooth = null;
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (bluetoothSelected() && receiverAuthenticated
                && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                || !getSystemService(NotificationManager.class).areNotificationsEnabled()
                || BluetoothConnectionService.ensureChannel(getSystemService(NotificationManager.class)).getImportance() == NotificationManager.IMPORTANCE_NONE)) {
            disconnectAll(); showSettings("所需藍牙或通知權限已撤銷；連線已停止，請修復後按連線");
        }
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
        transportGeneration++; snapshotGeneration++;
        if (coordinator != null) {
            if (ownedPreparation != null) ownedPreparation.cancel(); // Never cancel the Service's request.
            if (!isChangingConfigurations() && coordinator.waitingForSystemBond()) coordinator.disconnected();
        }
        if (transport != null) {
            transport.close();
            transport = null;
        }
        stopBondWait(); permissionContinuation = null; main.removeCallbacksAndMessages(null);
        authorizationWorker.shutdownNow();
        super.onDestroy();
    }
}
