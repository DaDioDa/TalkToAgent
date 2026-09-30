package com.example.talktoagent;

import java.util.List;

/** Persistence/Keystore boundary: implementations must fail closed and synchronously commit. */
public interface AuthorizationRepository {
    final class Endpoint {
        public final String channel, target, fingerprint;
        public Endpoint(String channel, String target, String fingerprint) {
            // Reuse the canonical target rules without allowing metadata to introduce a new URI format.
            String fields;
            if ("wifi".equals(channel) && target.startsWith("wifi:")) {
                String[] parts = target.split(":", -1);
                if (parts.length != 3) throw new IllegalArgumentException("Target");
                fields = "h=" + parts[1] + "&p=" + parts[2];
            } else if ("bt".equals(channel) && target.startsWith("bt:")) fields = "a=" + target.substring(3);
            else throw new IllegalArgumentException("Channel");
            Invitation canonical = Invitation.parse("talktoagent://invite/1?c=" + channel + "&" + fields
                    + "&i=AAAAAAAAAAAAAAAAAAAAAA&k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&r=" + fingerprint);
            if (!canonical.target.equals(target)) throw new IllegalArgumentException("Target");
            this.channel = channel; this.target = target; this.fingerprint = fingerprint;
        }
        public String bluetoothAddress() {
            if (!"bt".equals(channel)) throw new IllegalStateException("Not Bluetooth");
            return target.substring(3).replaceAll("(..)(?!$)", "$1:");
        }
    }
    byte[] phoneKey() throws Exception;
    byte[] sign(byte[] transcript) throws Exception;
    Endpoint pending() throws Exception;
    List<Endpoint> active() throws Exception;
    void savePending(Endpoint endpoint) throws Exception;
    void activate(Endpoint endpoint, int epoch, String channels) throws Exception;
    void clearPending() throws Exception;
}
