package com.example.talktoagent;

import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.os.*;
import java.util.UUID;

/** Explicit session foreground connection; resumes durable authorization, never resends text. */
public final class BluetoothConnectionService extends Service {
    static final String DISCONNECT = "com.example.talktoagent.DISCONNECT";
    interface Observer { void update(boolean ready, boolean pending, String status); }
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothFinalTextTransport transport;
    private BluetoothDevice device;
    private ConnectionCoordinator flow;
    private Observer observer;
    private boolean running;
    private boolean ready;
    private boolean pending;
    enum Outcome { NONE, PENDING, COMPLETED, UNKNOWN }
    private Outcome outcome = Outcome.NONE;
    Outcome outcome() { return outcome; }
    private boolean outcomeUnknown;
    private String status = "藍牙尚未連線";
    private int generation;

    final class LocalBinder extends Binder { BluetoothConnectionService service() { return BluetoothConnectionService.this; } }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() {
        super.onCreate();
        ensureChannel(getSystemService(NotificationManager.class));
    }
    static NotificationChannel ensureChannel(NotificationManager manager) {
        manager.createNotificationChannel(new NotificationChannel("bluetooth", "藍牙輸入連線", NotificationManager.IMPORTANCE_LOW));
        return manager.getNotificationChannel("bluetooth");
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && DISCONNECT.equals(intent.getAction())) { disconnect(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }
    void observe(Observer value) { observer = value; publish(); }
    ConnectionCoordinator coordinator() { return running ? flow : null; }
    boolean busy() { return running; }
    void connect(BluetoothDevice selected, ConnectionCoordinator coordinator) {
        boolean uncertain = outcomeUnknown || pending || coordinator.outcomeUnknown();
        disconnectConnection();
        device = selected;
        flow = coordinator;
        running = true;
        outcomeUnknown = uncertain;
        status = uncertain
                ? "上一筆輸入結果不明；請檢查電腦，不自動重送；正在依使用者操作連線"
                : "正在連接藍牙 Receiver…";
        startForeground(1, notification());
        publish();
        attempt();
    }
    private void attempt() {
        if (!running) return;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = ensureChannel(notifications);
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                || !notifications.areNotificationsEnabled() || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            outcomeUnknown |= pending;
            disconnectConnection(); status = "藍牙或通知權限不可用；連線已停止，請在設定修復後按連線";
            publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return;
        }
        int attempt = ++generation;
        if (transport != null) transport.close();
        if (flow.ready() || flow.attempting()) flow.disconnected();
        BluetoothFinalTextTransport current = new BluetoothFinalTextTransport(new BluetoothFinalTextTransport.Listener() {
            @Override public void authenticated() { main.post(() -> {
                if (attempt != generation) return;
                ready = true;
                status = outcomeUnknown
                        ? "藍牙已連線；上一筆輸入結果不明，請檢查電腦，不自動重送"
                        : "藍牙 Receiver 已授權；可輸入";
                publish();
            }); }
            @Override public void authenticationFailed() { main.post(() -> {
                if (attempt != generation) return;
                status = "應用授權被拒絕；請取得新邀請並重掃";
                disconnectConnection(); publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }); }
            @Override public void pasted(String id) { main.post(() -> {
                if (attempt != generation) return;
                pending = false;
                outcome = Outcome.COMPLETED;
                status = "Receiver 已完成貼上動作；不保證目標欄位接受文字" + (outcomeUnknown ? "；較早一筆輸入結果仍不明" : "");
                publish();
            }); }
            @Override public void rejected(String id, String error) { main.post(() -> {
                if (attempt != generation) return;
                pending = false;
                outcome = Outcome.UNKNOWN;
                if ("session_locked".equals(error)) {
                    ready = false;
                    status = "Windows 已鎖定或無法輸入；請解鎖後重新連線，未自動補送";
                } else {
                    status = "Receiver 拒絕輸入：" + error + "；未自動重送";
                }
                publish();
            }); }
            @Override public void disconnected(String unknownId) { main.post(() -> {
                if (attempt != generation || !running) return;
                ready = false;
                outcomeUnknown |= unknownId != null || pending || flow.outcomeUnknown();
                if (pending) outcome = Outcome.UNKNOWN;
                status = outcomeUnknown ? "輸入結果不明；請檢查電腦，按連線才能恢復，不補送" : "藍牙已斷線；請主動按連線恢復";
                disconnectConnection(); publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }); }
        });
        transport = current;
        try { current.connect(device, flow); }
        catch (RuntimeException failure) { status = "藍牙連線失敗：請檢查權限及配對"; disconnect(); }
    }
    boolean send(String text) {
        if (!ready || pending || transport == null) return false;
        if (!ManualTextProtocol.isValidFinalText(text)) {
            status = "文字為空或超過 4 KiB UTF-8；未送出"; publish(); return false;
        }
        pending = true;
        outcome = Outcome.PENDING;
        status = "等待貼上確認；斷線時結果可能不明";
        publish();
        transport.send(UUID.randomUUID().toString(), text);
        return true;
    }
    void disconnect() { outcomeUnknown |= pending; disconnectConnection(); status = outcomeUnknown ? "輸入結果不明；請先檢查電腦，未自動重送" : "藍牙已手動中斷"; publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    private void disconnectConnection() {
        if (pending) outcome = Outcome.UNKNOWN;
        running = false;
        generation++;
        ready = false;
        pending = false;
        if (transport != null) { transport.close(); transport = null; }
        if (flow != null) flow.cancelPreparation();
        device = null;
    }
    private void publish() {
        if (running) getSystemService(NotificationManager.class).notify(1, notification());
        if (observer != null) observer.update(ready, pending, status);
    }
    private Notification notification() {
        Intent disconnect = new Intent(this, BluetoothConnectionService.class).setAction(DISCONNECT);
        PendingIntent action = PendingIntent.getService(this, 1, disconnect, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "bluetooth").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("TalkToAgent 藍牙輸入").setContentText(status)
                .setOngoing(true).addAction(new Notification.Action.Builder(null, "中斷連線", action).build()).build();
    }
    @Override public void onDestroy() { outcomeUnknown |= pending; disconnectConnection(); super.onDestroy(); }
}
