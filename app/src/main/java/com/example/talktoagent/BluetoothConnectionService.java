package com.example.talktoagent;

import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.os.*;
import java.util.UUID;

/** Session-scoped foreground connection; no transcript or pairing code is persisted to disk. */
public final class BluetoothConnectionService extends Service {
    static final String DISCONNECT = "com.example.talktoagent.DISCONNECT";
    interface Observer { void update(boolean ready, boolean pending, String status); }
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothFinalTextTransport transport;
    private BluetoothDevice device;
    private String code;
    private Observer observer;
    private boolean running;
    private boolean ready;
    private boolean pending;
    private boolean outcomeUnknown;
    private String status = "藍牙尚未連線";
    private int generation;
    private int retrySeconds = 2;

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
    void connect(BluetoothDevice selected, String pairingCode) {
        boolean uncertain = outcomeUnknown || pending;
        disconnectConnection();
        device = selected;
        code = pairingCode;
        running = true;
        outcomeUnknown = uncertain;
        retrySeconds = 2;
        status = uncertain
                ? "上一筆輸入結果不明；先檢查電腦再決定是否重送；正在重新連線"
                : "正在連接藍牙 Receiver…";
        startForeground(1, notification());
        publish();
        attempt();
    }
    private void attempt() {
        if (!running) return;
        int attempt = ++generation;
        if (transport != null) transport.close();
        BluetoothFinalTextTransport current = new BluetoothFinalTextTransport(new BluetoothFinalTextTransport.Listener() {
            @Override public void authenticated() { main.post(() -> {
                if (attempt != generation) return;
                ready = true;
                retrySeconds = 2;
                status = outcomeUnknown
                        ? "藍牙已重連；上一筆輸入結果不明，請先檢查電腦再決定是否重送"
                        : "藍牙 Receiver 已授權；可輸入";
                publish();
            }); }
            @Override public void authenticationFailed() { main.post(() -> {
                if (attempt != generation) return;
                status = "應用配對碼錯誤；請重新連線";
                disconnectConnection(); publish(); stopSelf();
            }); }
            @Override public void pasted(String id) { main.post(() -> {
                if (attempt != generation) return;
                pending = false;
                outcomeUnknown = false;
                status = "Receiver 已完成貼上動作；不保證目標欄位接受文字";
                publish();
            }); }
            @Override public void rejected(String id, String error) { main.post(() -> {
                if (attempt != generation) return;
                pending = false;
                outcomeUnknown = false;
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
                outcomeUnknown |= unknownId != null || pending;
                pending = false;
                status = outcomeUnknown ? "輸入結果不明；先查看電腦再決定是否重送；正在重連但不補送" : "藍牙已斷線；正在重連（不補送）";
                publish();
                int delay = retrySeconds;
                retrySeconds = Math.min(60, retrySeconds * 2);
                main.postDelayed(() -> { if (attempt == generation && running) attempt(); }, delay * 1000L);
            }); }
        });
        transport = current;
        try { current.connect(device, code); }
        catch (RuntimeException failure) { status = "藍牙連線失敗：請檢查權限及配對"; disconnect(); }
    }
    boolean send(String text) {
        if (!ready || pending || transport == null) return false;
        if (!ManualTextProtocol.isValidFinalText(text)) {
            status = "文字為空或超過 4 KiB UTF-8；未送出"; publish(); return false;
        }
        pending = true;
        status = "等待貼上確認；斷線時結果可能不明";
        publish();
        transport.send(UUID.randomUUID().toString(), text);
        return true;
    }
    void disconnect() { outcomeUnknown |= pending; disconnectConnection(); status = outcomeUnknown ? "輸入結果不明；請先檢查電腦，未自動重送" : "藍牙已手動中斷"; publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    private void disconnectConnection() {
        running = false;
        generation++;
        ready = false;
        pending = false;
        if (transport != null) { transport.close(); transport = null; }
        code = null;
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
    @Override public void onDestroy() { disconnectConnection(); super.onDestroy(); }
}
