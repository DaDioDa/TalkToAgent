package com.example.talktoagent;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Secure paired-device RFCOMM transport. Caller owns lifecycle and must supply a paired device. */
final class BluetoothFinalTextTransport {
    static final UUID SERVICE_UUID = UUID.fromString("9c8f8513-7d4d-4a70-82c7-11e1da28a041");
    interface Listener {
        void authenticated();
        void authenticationFailed();
        void pasted(String id);
        void rejected(String id, String code);
        /** An in-flight send has an unknown outcome after connection loss. */
        void disconnected(String pendingId);
    }

    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private BluetoothSocket socket;
    private volatile String pendingId;
    private volatile boolean authenticated;
    private volatile boolean closed;

    BluetoothFinalTextTransport(Listener listener) { this.listener = listener; }

    void connect(BluetoothDevice device, String pairingCode) {
        if (device == null || pairingCode == null || pairingCode.isEmpty()) throw new IllegalArgumentException("Pairing required");
        worker.execute(() -> {
            try {
                BluetoothSocket connected = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                synchronized (this) {
                    if (closed) { connected.close(); return; }
                    socket = connected;
                }
                connected.connect();
                BluetoothFrames.write(connected.getOutputStream(), new JSONObject()
                        .put("type", "authenticate").put("pairingCode", pairingCode).toString());
                while (!closed) {
                    JSONObject response = new JSONObject(BluetoothFrames.read(connected.getInputStream()));
                    String type = response.optString("type");
                    if (!authenticated) {
                        if ("authenticated".equals(type) && response.length() == 1) {
                            authenticated = true;
                            listener.authenticated();
                        } else if ("authentication_failed".equals(type) && response.length() == 1) {
                            listener.authenticationFailed();
                            break;
                        } else break;
                    } else if (pendingId != null && pendingId.equals(response.optString("id"))) {
                        String id = pendingId;
                        if ("pasted".equals(type) && response.length() == 2) {
                            pendingId = null;
                            listener.pasted(id);
                        } else if ("error".equals(type) && response.opt("code") instanceof String) {
                            pendingId = null;
                            listener.rejected(id, response.optString("code"));
                        } else break;
                    } else break; // Never attribute a stale or uncorrelated result to a new send.
                }
            } catch (Exception ignored) {
                // Disconnect after a write cannot prove whether the receiver pasted the text.
            } finally {
                String unknown = pendingId;
                authenticated = false;
                pendingId = null;
                closeSocket();
                if (!closed) listener.disconnected(unknown);
            }
        });
    }

    void send(String id, String text) {
        if (id == null || id.isEmpty() || !ManualTextProtocol.isValidFinalText(text))
            throw new IllegalArgumentException("Invalid final text or id");
        writer.execute(() -> {
            if (!authenticated || pendingId != null || closed) {
                listener.rejected(id, "not_connected");
                return;
            }
            pendingId = id; // Set before writing: a partial write is an unknown outcome.
            try {
                BluetoothFrames.write(socket.getOutputStream(), new JSONObject()
                        .put("type", "final_text").put("id", id).put("text", text).toString());
            } catch (Exception failure) {
                closeSocket();
            }
        });
    }

    void close() {
        closed = true;
        closeSocket();
        worker.shutdownNow();
        writer.shutdownNow();
    }

    private synchronized void closeSocket() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
            socket = null;
        }
    }
}
