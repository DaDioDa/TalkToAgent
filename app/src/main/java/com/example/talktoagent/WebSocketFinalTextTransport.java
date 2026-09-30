package com.example.talktoagent;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import okio.ByteString;

/** One explicit Wi-Fi authorization and one correlated final; never retries text. */
final class WebSocketFinalTextTransport implements FinalTextTransport {
    private final OkHttpClient client = new OkHttpClient();
    private final Listener listener;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile WebSocket socket;
    private volatile ConnectionCoordinator flow;
    private volatile boolean closed;
    WebSocketFinalTextTransport(Listener listener) { this.listener = listener; }
    @Override public void authenticate(ConnectionCoordinator coordinator) {
        flow = coordinator;
        worker.execute(() -> {
            try {
                AuthorizationRepository.Endpoint endpoint = flow.selected();
                if (endpoint == null || !"wifi".equals(endpoint.channel)) throw new IllegalArgumentException();
                String hello;
                synchronized (WebSocketFinalTextTransport.this) {
                    if (closed) return;
                    hello = flow.begin();
                }
                String[] parts = endpoint.target.split(":");
                Request request = new Request.Builder().url("ws://" + parts[1] + ":" + parts[2] + "/ws").build();
                if (closed) { flow.disconnected(); return; }
                socket = client.newWebSocket(request, new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) {
                        if (closed || !socket.send(hello)) fail("connection_failed");
                    }
                    @Override public void onMessage(WebSocket socket, String wire) {
                        if (closed || terminal.get()) return;
                        try {
                            ConnectionCoordinator.Result result = flow.receive(wire);
                            switch (result.event) {
                                case PROOF:
                                    if (!socket.send(result.outbound)) fail("connection_failed");
                                    break;
                                case AUTHORIZED: listener.onAuthenticated(); break;
                                case PASTED:
                                    if (terminal.compareAndSet(false, true)) listener.onPasteComplete();
                                    socket.close(1000, "complete"); break;
                                case REJECTED:
                                    if (result.id == null) {
                                        flow.disconnected();
                                        if (terminal.compareAndSet(false, true)) listener.onAuthenticationFailed();
                                    } else fail("receiver_" + result.code);
                                    socket.close(1000, "rejected"); break;
                            }
                        } catch (Exception failure) { fail("invalid_response"); socket.cancel(); }
                    }
                    @Override public void onMessage(WebSocket socket, ByteString bytes) { fail("invalid_response"); socket.cancel(); }
                    @Override public void onClosing(WebSocket socket, int code, String reason) {
                        fail("connection_closed"); socket.close(code, "closed");
                    }
                    @Override public void onFailure(WebSocket socket, Throwable failure, Response response) { fail("connection_failed"); }
                });
            } catch (Exception failure) { fail("authorization_unavailable"); }
        });
    }
    @Override public void sendFinalText(String text) {
        try {
            String wire = flow.finalText(UUID.randomUUID().toString(), text);
            WebSocket current = socket;
            if (current == null || !current.send(wire)) fail("connection_failed");
        } catch (IllegalArgumentException failure) { fail("invalid_text"); }
        catch (Exception failure) { fail("connection_closed"); }
    }
    private void fail(String reason) {
        if (closed || !terminal.compareAndSet(false, true)) return;
        if (flow != null) flow.disconnected();
        listener.onFailure(reason);
    }
    @Override public synchronized void close() {
        closed = true;
        if (flow != null) flow.disconnected();
        if (socket != null) socket.cancel();
        worker.shutdownNow(); client.dispatcher().cancelAll(); client.connectionPool().evictAll();
    }
}
