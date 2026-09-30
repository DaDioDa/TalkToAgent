package com.example.talktoagent;

/** Replaceable boundary for delivering one manually finalized text to a receiver. */
interface FinalTextTransport {
    interface Listener {
        void onAuthenticated();

        void onAuthenticationFailed();

        void onPasteComplete();

        void onFailure(String reason);
    }

    void authenticate(ConnectionCoordinator coordinator);

    void sendFinalText(String text);

    void close();
}
