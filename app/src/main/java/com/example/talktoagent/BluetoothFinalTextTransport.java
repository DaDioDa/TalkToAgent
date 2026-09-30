package com.example.talktoagent;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Secure RFCOMM only; specified device must have finished system bonding before connection. */
final class BluetoothFinalTextTransport {
    static final UUID SERVICE_UUID = UUID.fromString("9c8f8513-7d4d-4a70-82c7-11e1da28a041");
    interface Listener {
        void authenticated();
        void authenticationFailed();
        void pasted(String id);
        void rejected(String id, String code);
        void disconnected(String pendingId);
    }
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile BluetoothSocket socket;
    private volatile boolean closed;
    private ConnectionCoordinator flow;
    BluetoothFinalTextTransport(Listener listener) { this.listener = listener; }
    void connect(BluetoothDevice device, ConnectionCoordinator coordinator) {
        if (device == null || coordinator == null) throw new IllegalArgumentException("Authorization required");
        flow = coordinator;
        worker.execute(() -> {
            try {
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) throw new IOException("Bond required");
                String hello;
                synchronized (this) { if (closed) return; hello = flow.begin(); }
                BluetoothSocket connected = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                synchronized (this) {
                    if (closed) { connected.close(); return; }
                    socket = connected;
                }
                connected.connect(); BluetoothFrames.write(connected.getOutputStream(), hello);
                while (!closed) {
                    String wire = BluetoothFrames.read(connected.getInputStream());
                    if (closed) break;
                    ConnectionCoordinator.Result result = flow.receive(wire);
                    switch (result.event) {
                        case PROOF: BluetoothFrames.write(connected.getOutputStream(), result.outbound); break;
                        case AUTHORIZED: listener.authenticated(); break;
                        case PASTED: listener.pasted(result.id); break;
                        case REJECTED:
                            if (result.id == null) { listener.authenticationFailed(); return; }
                            listener.rejected(result.id, result.code);
                            if (!flow.ready()) return;
                            break;
                    }
                }
            } catch (SecurityException permissionRevoked) {
                // Permissions can be revoked after the UI check. Stop instead of retrying.
                listener.authenticationFailed();
            } catch (Exception ignored) {
                // Never log wire data or exceptions containing invitations. Never retry text.
            } finally {
                String unknown = flow.pendingId();
                if (!closed) flow.disconnected();
                closeSocket();
                if (!closed) listener.disconnected(unknown);
            }
        });
    }
    void send(String id, String text) {
        if (!ManualTextProtocol.isValidFinalText(text)) throw new IllegalArgumentException("Invalid final text");
        writer.execute(() -> {
            if (closed || !flow.ready() || flow.pending()) { listener.rejected(id, "not_connected"); return; }
            try {
                String frame = flow.finalText(id, text); // Mark unknown outcome before any partial write.
                BluetoothFrames.write(socket.getOutputStream(), frame);
            } catch (Exception failure) { closeSocket(); }
        });
    }
    synchronized void close() {
        closed = true; closeSocket();
        if (flow != null) flow.disconnected();
        worker.shutdownNow(); writer.shutdownNow();
    }
    private synchronized void closeSocket() {
        if (socket != null) { try { socket.close(); } catch (IOException ignored) { } socket = null; }
    }
}
