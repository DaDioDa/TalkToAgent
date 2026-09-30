package com.example.talktoagent;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Strict, ASCII-only QR invitation. Secret stays in memory and is never part of metadata. */
public final class Invitation {
    public final String channel, target, id, fingerprint;
    private final byte[] secret;
    private Invitation(String channel, String target, String id, String fingerprint, byte[] secret) {
        this.channel = channel; this.target = target; this.id = id; this.fingerprint = fingerprint; this.secret = secret;
    }
    byte[] secret() { return secret.clone(); }
    public static Invitation parse(String uri) {
        if (uri == null || uri.length() > 1024 || !uri.startsWith("talktoagent://invite/1?")) throw invalid();
        for (char c : uri.toCharArray()) if (c <= 32 || c >= 127 || c == '%' || c == '#') throw invalid();
        Map<String, String> q = new HashMap<>();
        for (String field : uri.substring("talktoagent://invite/1?".length()).split("&", -1)) {
            String[] pair = field.split("=", -1);
            if (pair.length != 2 || pair[1].isEmpty() || q.putIfAbsent(pair[0], pair[1]) != null) throw invalid();
        }
        String channel = q.get("c"), target;
        if ("wifi".equals(channel)) {
            if (!q.keySet().equals(Set.of("c", "h", "p", "i", "k", "r"))) throw invalid();
            validateIpv4(q.get("h"));
            String port = q.get("p");
            if (!port.matches("[1-9][0-9]{0,4}") || Integer.parseInt(port) > 65535) throw invalid();
            target = "wifi:" + q.get("h") + ":" + port;
        } else if ("bt".equals(channel)) {
            if (!q.keySet().equals(Set.of("c", "a", "i", "k", "r"))) throw invalid();
            String mac = q.get("a");
            if (!mac.matches("[A-F0-9]{12}") || mac.equals("000000000000") || mac.equals("FFFFFFFFFFFF")) throw invalid();
            target = "bt:" + mac;
        } else throw invalid();
        decode(q.get("i"), 16); decode(q.get("r"), 32);
        return new Invitation(channel, target, q.get("i"), q.get("r"), decode(q.get("k"), 32));
    }
    static void validateIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) throw invalid();
        long value = 0;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) throw invalid();
            value = (value << 8) | Integer.parseInt(octet);
        }
        if (value == 0 || value == 0xffffffffL || (value >>> 28) == 14) throw invalid();
    }
    static byte[] decode(String value, int length) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(value); } catch (IllegalArgumentException e) { throw invalid(); }
        if ((length >= 0 && bytes.length != length) || !encode(bytes).equals(value)) throw invalid();
        return bytes;
    }
    static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid invitation/encoding"); }
}
