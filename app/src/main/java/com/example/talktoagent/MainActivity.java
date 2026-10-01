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
import java.util.ArrayList;
import java.util.List;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;

public final class MainActivity extends Activity {
    private static final int CHANNEL_WIFI = 0;
    private static final int CHANNEL_BLUETOOTH = 1;
    private int selectedChannel;
    private ConnectionCoordinator.Targets targetsSnapshot;
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
                if (!pending && bluetooth.outcome() == BluetoothConnectionService.Outcome.COMPLETED)
                    utterance.outcome(submittedSession, true);
                else if (!pending && bluetooth.outcome() == BluetoothConnectionService.Outcome.UNKNOWN)
                    utterance.outcome(submittedSession, false);
                if (authorizationStore != null) reloadTargets();
                setReceiverStatus(status);
                refreshReadiness();
            });
            if (waitingBond && activityVisible) reconcileBond();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bluetooth = null;
            receiverAuthenticated = false;
            leaveVoice();
            refreshReadiness();
        }
    };
    private String knownFailure;
    private GeminiKeyStore keyStore;
    private GeminiLiveTranscriber recognition;
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
        setTitle("TalkToAgent");
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        selectedChannel = savedInstanceState == null ? CHANNEL_WIFI : savedInstanceState.getInt("channel", CHANNEL_WIFI);
        keyStore = new GeminiKeyStore(this);
        screen = new NativeCScreen(this, this::handleAction);
        utterance = new VoiceUtterance(this::deliverVoiceFinal, message -> {
            voiceMessage = message;
            refreshReadiness();
        }, TraditionalText::convert, android.os.SystemClock::elapsedRealtime, this::voiceEffect);
        if (savedInstanceState != null) {
            try { utterance.restoreResult(VoiceUtterance.Phase.valueOf(savedInstanceState.getString("voiceResult", "IDLE"))); }
            catch (IllegalArgumentException ignored) { /* Unknown metadata never resumes work. */ }
        }
        setContentView(screen);
        registerReceiver(screenOffReceiver, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        refreshReadiness();
        if (savedInstanceState != null && savedInstanceState.getBoolean("bondWasWaiting") && !(retained instanceof BondRetention))
            setReceiverStatus("配對邀請因程序結束已失效；已停止，請取得新邀請重掃（不會冷啟動自動連線）");
        reloadTargets();
    }

    private NativeCScreen screen;
    private String receiverMessage = "Receiver 尚未驗證", voiceMessage = "", targetMessage = "尚無已授權電腦", latest = "";
    private long submittedSession = -1;
    private int displayedRemaining = -1;
    private boolean panelOpen;
    private final android.content.BroadcastReceiver screenOffReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { leaveVoice(); }
    };
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (utterance != null) {
                utterance.tick();
                if (utterance.active() && utterance.remainingSeconds() != displayedRemaining) refreshReadiness();
            }
            main.postDelayed(this, 200);
        }
    };

    private void refreshReadiness() {
        if (screen == null || utterance == null) return;
        if (coordinator != null) coordinator.setUtteranceActive(utterance.busy());
        boolean permission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean key = !keyStore.read().isEmpty();
        boolean ready = InputReadiness.evaluate(receiverAuthenticated, key, permission, utterance.busy(), knownFailure).ready()
                && !bluetoothPending && !connectingAttempt;
        List<NativeCScreen.Blocker> blockers = new ArrayList<>();
        if (!key) blockers.add(new NativeCScreen.Blocker("請設定 Gemini 金鑰", NativeCScreen.Action.KEY));
        if (!permission) blockers.add(new NativeCScreen.Blocker("需要麥克風權限", NativeCScreen.Action.PERMISSION));
        if (!receiverAuthenticated || connectingAttempt) blockers.add(new NativeCScreen.Blocker(receiverMessage, NativeCScreen.Action.CONNECTION));
        if (knownFailure != null) blockers.add(new NativeCScreen.Blocker(knownFailure, NativeCScreen.Action.CONNECTION));
        NativeCScreen.Phase phase;
        switch (utterance.phase()) {
            case PREPARING: phase = NativeCScreen.Phase.PREPARING; break;
            case RECORDING: phase = NativeCScreen.Phase.RECORDING; break;
            case WAITING: phase = NativeCScreen.Phase.FINALIZING; break;
            case SENDING: phase = NativeCScreen.Phase.SENDING; break;
            case COMPLETED: phase = NativeCScreen.Phase.COMPLETED; break;
            case CANCELLED: phase = NativeCScreen.Phase.CANCELLED; break;
            case FAILED: phase = NativeCScreen.Phase.FAILED; break;
            case UNKNOWN: phase = NativeCScreen.Phase.UNKNOWN; break;
            default: phase = ready ? NativeCScreen.Phase.READY : NativeCScreen.Phase.UNAVAILABLE;
        }
        getWindow().getDecorView().setKeepScreenOn(phase == NativeCScreen.Phase.RECORDING);
        displayedRemaining = utterance.remainingSeconds();
        String connectionSummary = targetMessage + " · " + (connectingAttempt ? "驗證中" : receiverAuthenticated ? "已連線" : "未驗證");
        NativeCScreen.State snapshot = new NativeCScreen.State(phase, ready, connectionSummary,
                displayedRemaining, latest, blockers, receiverAuthenticated && !utterance.busy() && !bluetoothPending && !connectingAttempt, voiceMessage);
        if (!panelOpen || utterance.busy()) {
            panelOpen = false;
            screen.render(snapshot);
        } else screen.update(snapshot);
    }

    private void handleAction(NativeCScreen.Event event) {
        if (utterance.busy() && event.action != NativeCScreen.Action.FINISH && event.action != NativeCScreen.Action.CANCEL) return;
        switch (event.action) {
            case START: panelOpen = false; pressToTalk(); break;
            case FINISH: releaseToTalk(); break;
            case CANCEL: cancelVoice(); break;
            case HOME: panelOpen = false; refreshReadiness(); break;
            case CONNECTION: case SETTINGS: case LATEST: case KEY: case PERMISSION: case DIAGNOSTICS:
                panelOpen = true; break;
            case SCAN_QR: scanInvitation(); break;
            case RECOVER_AUTHORIZATION:
                if (targetsSnapshot != null && targetsSnapshot.pending(bluetoothSelected() ? "bt" : "wifi") != null)
                    new android.app.AlertDialog.Builder(this).setMessage("選擇既有授權或上次未完成授權")
                        .setPositiveButton("既有授權", (d, w) -> authenticateReceiver(false))
                        .setNeutralButton("未完成授權", (d, w) -> authenticateReceiver(true)).setNegativeButton("取消", null).show();
                else authenticateReceiver();
                break;
            case BLUETOOTH: case WIFI:
                int channel = event.action == NativeCScreen.Action.BLUETOOTH ? CHANNEL_BLUETOOTH : CHANNEL_WIFI;
                if (selectedChannel != channel) { disconnectAll(); selectedChannel = channel; updateTargetLabel(); }
                break;
            case DISCONNECT: disconnectAll(); break;
            case COPY:
                if (!latest.isEmpty()) ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("口述定稿", latest));
                break;
            case SAVE_KEY: case CLEAR_KEY:
                try { keyStore.save(event.action == NativeCScreen.Action.CLEAR_KEY ? "" : event.text.trim()); knownFailure = null; }
                catch (Exception failure) { knownFailure = "無法安全儲存金鑰"; }
                panelOpen = false; refreshReadiness(); break;
            case REQUEST_PERMISSION:
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                            && getPreferences(MODE_PRIVATE).getBoolean("microphoneRequested", false)) showSettings("請允許麥克風權限，再主動開始");
                    else { getPreferences(MODE_PRIVATE).edit().putBoolean("microphoneRequested", true).apply(); requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); }
                }
                break;
            case MANUAL_SEND: sendManual(event.text); break;
        }
    }

    private void voiceEffect(VoiceUtterance.Effect effect) {
        switch (effect) {
            case CAPTURE: haptic(false); break;
            case FINISH: haptic(false); if (recognition != null) recognition.release(); break;
            case WARNING: haptic(true); break;
            case CLOSE: if (recognition != null) { recognition.close(); recognition = null; } break;
            case FINALIZATION_DEADLINE: if (recognition != null) recognition.finalizeAtDeadline(); break;
        }
        refreshReadiness();
    }
    private void haptic(boolean warning) {
        android.os.Vibrator vibrator = getSystemService(android.os.Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (warning) vibrator.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 25, 70, 25}, -1));
            else vibrator.vibrate(android.os.VibrationEffect.createOneShot(25, 60));
        } catch (SecurityException ignored) { /* Visual cues remain available. */ }
    }
    private void leaveVoice() { utterance.leave(); if (recognition != null) { recognition.close(); recognition = null; } refreshReadiness(); }
    private void setReceiverStatus(String message) { receiverMessage = message; refreshReadiness(); }
    private void setVoiceStatus(String message) { voiceMessage = message; refreshReadiness(); }

    private void disconnectBluetooth() {
        // A started service may exist before the binding callback (or after unbind).
        startService(new Intent(this, BluetoothConnectionService.class).setAction(BluetoothConnectionService.DISCONNECT));
    }

    private boolean bluetoothSelected() {
        return selectedChannel == CHANNEL_BLUETOOTH;
    }

    private void scanInvitation() {
        if (coordinator == null || !coordinator.canScan() || connectingAttempt || waitingBond || bluetoothPending
                || (bluetooth != null && bluetooth.busy())) return;
        ensurePermission(Manifest.permission.CAMERA, "掃描電腦短效 QR 需要相機；不會儲存 QR 圖片", () ->
                startActivityForResult(new Intent(this, InvitationScanActivity.class), 40));
    }
    private void updateTargetLabel() {
        String channel = bluetoothSelected() ? "bt" : "wifi";
        AuthorizationRepository.Endpoint endpoint = null;
        if (targetsSnapshot != null) for (AuthorizationRepository.Endpoint value : targetsSnapshot.active)
            if (channel.equals(value.channel)) endpoint = value;
        AuthorizationRepository.Endpoint pending = targetsSnapshot == null ? null : targetsSnapshot.pending(channel);
        String label = endpoint == null ? "此通道尚無已授權電腦" : "已授權電腦：" + endpoint.target
                + "（" + endpoint.fingerprint.substring(0, 12) + "…）";
        if (pending != null) label += "\n上次未完成授權（尚非 active）：" + pending.target + "；可明確按恢復";
        targetMessage = label; refreshReadiness();
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
        cancelVoice(); utterance.leave(); stopBondWait(); connectingAttempt = false; permissionContinuation = null;
        transportGeneration++; snapshotGeneration++;
        if (transport != null) { transport.close(); transport = null; }
        if (bluetooth != null) bluetooth.disconnect(); else disconnectBluetooth();
        if (coordinator != null) coordinator.disconnected();
        receiverAuthenticated = false; bluetoothPending = false;
        setReceiverStatus(coordinator != null && coordinator.outcomeUnknown()
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
            setReceiverStatus("等待指定電腦的系統藍牙配對確認；尚未取得應用授權"); refreshReadiness();
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
            else { setReceiverStatus("等待指定電腦系統配對；邀請僅保留於記憶體"); refreshReadiness(); }
        } catch (SecurityException failure) { bondCancelled(); showSettings("附近裝置權限已撤銷；請修復後重掃"); }
    }
    private void stopBondWait() {
        waitingBond = false; bondingDevice = null; bondGeneration++;
        if (bondReceiverRegistered) { unregisterReceiver(bondReceiver); bondReceiverRegistered = false; }
    }
    private void bondCancelled() {
        stopBondWait(); connectingAttempt = false;
        setReceiverStatus("系統配對已取消或未完成；已停止嘗試，請取得新邀請重掃");
        if (coordinator != null) coordinator.disconnected();
        refreshReadiness();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 40 || resultCode != RESULT_OK || data == null) return;
        if (coordinator == null || authorizationStore == null || !coordinator.canScan() || connectingAttempt || waitingBond) {
            setReceiverStatus("授權儲存尚未就緒或連線忙碌；請稍後重掃，既有授權未變更"); return;
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
                        selectedChannel = channel;
                        targetMessage = "邀請指定電腦：" + coordinator.selected().target + "；尚未授權"; connectSelected();
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
            setVoiceStatus("先等待本次辨識結束，再重新驗證 Receiver");
            return;
        }
        final int attempt = ++transportGeneration;
        if (transport != null) {
            transport.close();
            transport = null;
        }
        receiverAuthenticated = false;
        knownFailure = null;
        refreshReadiness();

        connectingAttempt = true;
        setReceiverStatus("正在驗證 Windows Receiver…");

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
                    setReceiverStatus(
                            "Windows Receiver 已驗證；請先在 Windows 目標欄位放置游標。"
                                    + (keyStore.read().isEmpty() ? " 尚需設定 Gemini 金鑰。" : " 可點按開始。")
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
                    utterance.outcome(submittedSession, true);
                    refreshReadiness();
                    setReceiverStatus(
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
                    setReceiverStatus(message);
                    knownFailure = message; reloadTargets();
                    utterance.outcome(submittedSession, false);
                    utterance.fail(message);
                    refreshReadiness();
                    if (recognition != null) { recognition.close(); recognition = null; }
                });
            }
        };
        transport = new WebSocketFinalTextTransport(listener);
        transport.authenticate(coordinator);
    }

    private void sendManual(String text) {
        if (utterance.busy() || bluetoothPending || !receiverAuthenticated || !ManualTextProtocol.isValidFinalText(text)) return;
        utterance.submitManual(true, text);
        panelOpen = false; refreshReadiness();
    }

    private void pressToTalk() {
        if (utterance.busy() || bluetoothPending || connectingAttempt) return;
        String key = keyStore.read();
        boolean allowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        final long session = utterance.start(InputReadiness.evaluate(receiverAuthenticated, !key.isEmpty(), allowed,
                utterance.busy(), knownFailure).ready());
        if (session < 0) return;
        knownFailure = null;
        recognition = new GeminiLiveTranscriber(key, new GeminiLiveTranscriber.Listener() {
            @Override public void onCaptureStarted() { utterance.microphoneStarted(session); }
            @Override public void onStage(String stage) {
                if (session == utterance.session() && utterance.active()) setVoiceStatus(stage);
            }
            @Override public void onInterim(String text) {
                if (session == utterance.session() && utterance.phase() == VoiceUtterance.Phase.RECORDING)
                    setVoiceStatus("正在辨識；暫定片段不傳送");
            }
            @Override public void onFinal(String text) {
                if (session != utterance.session() || utterance.phase() != VoiceUtterance.Phase.WAITING) return;
                utterance.finalText(session, text); recognition = null; refreshReadiness();
            }
            @Override public void onError(String reason) {
                if (session != utterance.session() || !utterance.active()) return;
                knownFailure = reason; utterance.fail(session, reason); recognition = null; refreshReadiness();
            }
        });
        refreshReadiness();
    }

    private void releaseToTalk() { utterance.finish(); refreshReadiness(); }
    private void cancelVoice() {
        utterance.cancel();
        if (recognition != null) { recognition.close(); recognition = null; }
        refreshReadiness();
    }

    private void deliverVoiceFinal(String text) {
        if (!utterance.manualOperation()) latest = text;
        submittedSession = utterance.session();
        if (!receiverAuthenticated || (bluetoothSelected() ? bluetooth == null : transport == null)) {
            utterance.notSubmitted(submittedSession, "Receiver 已失去驗證，未傳送");
            knownFailure = "Receiver 已失去驗證，未自動重送";
        } else if (bluetoothSelected()) {
            if (bluetoothPending || !bluetooth.send(text))
                utterance.notSubmitted(submittedSession, "藍牙未接受本次文字，未傳送");
        } else {
            receiverAuthenticated = false;
            transport.sendFinalText(text);
        }
        refreshReadiness();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 20) {
            Runnable next = permissionContinuation; permissionContinuation = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) { if (next != null) next.run(); }
            else {
                connectingAttempt = false;
                setReceiverStatus("所需權限遭拒，未取得輸入就緒；修復後重掃或按連線");
                if (permissions.length > 0 && !shouldShowRequestPermissionRationale(permissions[0])) showSettings("權限已拒絕；請在應用設定允許相機、附近裝置或通知");
                refreshReadiness();
            }
        }
        if (requestCode == 1) {
            setVoiceStatus(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                    ? "已允許麥克風；請重新點按開始（本次沒有收音）"
                    : "麥克風權限遭拒，未收音或傳送；請在系統設定允許後重試");
            refreshReadiness();
        }
    }

    private void showAuthenticationFailure(String message) {
        if (utterance.active()) cancelVoice();
        utterance.leave();
        connectingAttempt = false; receiverAuthenticated = false;
        knownFailure = message;
        if (authorizationStore != null) reloadTargets();
        setReceiverStatus(message);
        refreshReadiness();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return waitingBond && coordinator != null ? new BondRetention(coordinator, authorizationStore, bondingDevice, bondDeadline) : null;
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("bondWasWaiting", waitingBond); // Never persist the invitation, pin or secret in a Bundle.
        state.putInt("channel", selectedChannel);
        state.putString("voiceResult", utterance.phase().name()); // Result metadata only, never transcript/key/session.
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart(); activityVisible = true;
        main.removeCallbacks(clockTick);
        main.post(clockTick);
        if (waitingBond && bondingDevice != null) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) bondCancelled();
            else { attachBondReceiver(); reconcileBond(); }
        }
        bindService(new Intent(this, BluetoothConnectionService.class), serviceConnection, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        activityVisible = false;
        main.removeCallbacks(clockTick);
        leaveVoice();
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
        leaveVoice();
        refreshReadiness();
    }

    @Override
    protected void onDestroy() {
        leaveVoice();
        unregisterReceiver(screenOffReceiver);
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
