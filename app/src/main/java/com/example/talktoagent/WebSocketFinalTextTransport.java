package com.example.talktoagent;

import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONException;
import org.json.JSONObject;

final class WebSocketFinalTextTransport implements FinalTextTransport {
    private static final int CONNECTING = 0;
    private static final int AUTHENTICATED = 1;
    private static final int SENDING = 2;
    private static final int DONE = 3;

    private final OkHttpClient client = new OkHttpClient();
    private final Listener listener;
    private final AtomicBoolean terminalCallbackDelivered = new AtomicBoolean(false);
    private volatile WebSocket webSocket;
    private volatile int state = CONNECTING;
    private String pairingCode;

    WebSocketFinalTextTransport(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void authenticate(String ipAddress, int port, String pairingCode) {
        if (!isIpv4Address(ipAddress) || port < 1 || port > 65535
                || pairingCode == null || pairingCode.isEmpty()) {
            fail("invalid_configuration");
            return;
        }

        this.pairingCode = pairingCode;
        Request request = new Request.Builder()
                .url("ws://" + ipAddress + ":" + port + "/ws")
                .build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                String frame;
                String code = WebSocketFinalTextTransport.this.pairingCode;
                WebSocketFinalTextTransport.this.pairingCode = null;
                try {
                    frame = ManualTextProtocol.authentication(code);
                } catch (IllegalArgumentException exception) {
                    fail("invalid_configuration");
                    socket.cancel();
                    return;
                }
                if (!socket.send(frame)) {
                    fail("connection_failed");
                    socket.cancel();
                }
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                handleResponse(socket, text);
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                fail("invalid_response");
                socket.cancel();
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                if (!terminalCallbackDelivered.get()) {
                    fail("connection_closed");
                }
                socket.close(code, reason);
            }

            @Override
            public void onFailure(WebSocket socket, Throwable failure, Response response) {
                fail("connection_failed");
            }
        });
    }

    private void handleResponse(WebSocket socket, String frame) {
        final JSONObject response;
        try {
            response = new JSONObject(frame);
        } catch (JSONException exception) {
            fail("invalid_response");
            socket.cancel();
            return;
        }

        String type = response.optString("type", "");
        if ("authenticated".equals(type) && response.length() == 1 && state == CONNECTING) {
            state = AUTHENTICATED;
            listener.onAuthenticated();
            return;
        }
        if ("authentication_failed".equals(type)
                && response.length() == 1 && state == CONNECTING) {
            state = DONE;
            if (terminalCallbackDelivered.compareAndSet(false, true)) {
                listener.onAuthenticationFailed();
            }
            socket.close(1000, "authentication failed");
            return;
        }
        if ("pasted".equals(type) && response.length() == 1 && state == SENDING) {
            state = DONE;
            if (terminalCallbackDelivered.compareAndSet(false, true)) {
                listener.onPasteComplete();
            }
            socket.close(1000, "complete");
            return;
        }
        if ("error".equals(type)
                && response.length() == 2
                && response.has("code")
                && response.opt("code") instanceof String) {
            String code = response.optString("code", "invalid_response");
            fail("receiver_" + code);
            socket.close(1000, "request rejected");
            return;
        }

        fail("invalid_response");
        socket.cancel();
    }

    @Override
    public void sendFinalText(String text) {
        if (state != AUTHENTICATED || webSocket == null) {
            fail("connection_closed");
            return;
        }
        final String frame;
        try {
            frame = ManualTextProtocol.finalText(text);
        } catch (IllegalArgumentException exception) {
            fail("invalid_text");
            webSocket.close(1000, "invalid text");
            return;
        }

        state = SENDING;
        if (!webSocket.send(frame)) {
            fail("connection_failed");
            webSocket.cancel();
        }
    }

    private static boolean isIpv4Address(String address) {
        if (address == null) {
            return false;
        }
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int index = 0; index < octet.length(); index++) {
                if (octet.charAt(index) < '0' || octet.charAt(index) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private void fail(String reason) {
        state = DONE;
        pairingCode = null;
        if (terminalCallbackDelivered.compareAndSet(false, true)) {
            listener.onFailure(reason);
        }
    }

    @Override
    public void close() {
        state = DONE;
        pairingCode = null;
        WebSocket current = webSocket;
        if (current != null) {
            current.cancel();
        }
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }
}
