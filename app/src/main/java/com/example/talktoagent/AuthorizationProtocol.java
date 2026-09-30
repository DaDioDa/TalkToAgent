package com.example.talktoagent;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** JCA P-256/SPKI/DER implementation of authorization-protocol.md (no JSON signing). */
public final class AuthorizationProtocol {
    private AuthorizationProtocol() {}
    static byte[] pack(String... values) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(b);
        for (String value : values) {
            if (!StrictJson.wellFormed(value)) throw StrictJson.invalid();
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
        }
        return b.toByteArray();
    }
    static byte[] transcript(String domain, List<String> t, String... tail) throws IOException {
        List<String> values = new ArrayList<>(); values.add(domain); values.addAll(t); values.addAll(Arrays.asList(tail));
        return pack(values.toArray(new String[0]));
    }
    static PublicKey publicKey(String encoded) throws Exception {
        byte[] der = Invitation.decode(encoded, 91);
        // Named P-256, id-ecPublicKey, uncompressed point, canonical SPKI DER.
        byte[] prefix = new byte[]{0x30,0x59,0x30,0x13,0x06,0x07,0x2a,(byte)0x86,0x48,(byte)0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,(byte)0x86,0x48,(byte)0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00,0x04};
        if (!Arrays.equals(prefix, Arrays.copyOf(der, prefix.length))) throw StrictJson.invalid();
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        if (!(key instanceof ECPublicKey) || !Arrays.equals(key.getEncoded(), der)) throw StrictJson.invalid();
        ECPublicKey ec = (ECPublicKey) key;
        AlgorithmParameters p = AlgorithmParameters.getInstance("EC"); p.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = p.getParameterSpec(ECParameterSpec.class), actual = ec.getParams();
        if (!actual.getCurve().equals(expected.getCurve()) || !actual.getGenerator().equals(expected.getGenerator())
                || !actual.getOrder().equals(expected.getOrder()) || actual.getCofactor() != expected.getCofactor()) throw StrictJson.invalid();
        // Explicit point membership check (do not rely on provider validation).
        BigInteger prime = ((ECFieldFp) actual.getCurve().getField()).getP(), x = ec.getW().getAffineX(), y = ec.getW().getAffineY();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(prime) >= 0 || y.compareTo(prime) >= 0
                || !y.multiply(y).mod(prime).equals(x.pow(3).add(actual.getCurve().getA().multiply(x)).add(actual.getCurve().getB()).mod(prime))) throw StrictJson.invalid();
        return key;
    }
    static void verify(PublicKey key, byte[] transcript, String encoded) throws Exception {
        byte[] der = Invitation.decode(encoded, -1);
        if (der.length < 8 || der.length > 72 || der[0] != 0x30 || (der[1] & 255) != der.length - 2) throw StrictJson.invalid();
        int offset = 2;
        for (int i = 0; i < 2; i++) {
            if (offset + 2 >= der.length || der[offset++] != 2) throw StrictJson.invalid();
            int length = der[offset++] & 255;
            if (length < 1 || length > 33 || offset + length > der.length || (der[offset] & 128) != 0
                    || (length > 1 && der[offset] == 0 && (der[offset + 1] & 128) == 0)) throw StrictJson.invalid();
            BigInteger n = new BigInteger(Arrays.copyOfRange(der, offset, offset + length));
            if (n.signum() <= 0 || n.compareTo(((ECPublicKey) key).getParams().getOrder()) >= 0) throw StrictJson.invalid();
            offset += length;
        }
        if (offset != der.length) throw StrictJson.invalid();
        Signature signature = Signature.getInstance("SHA256withECDSA"); signature.initVerify(key); signature.update(transcript);
        if (!signature.verify(der)) throw new GeneralSecurityException("Receiver signature rejected");
    }
    static final class Session {
        private final AuthorizationRepository repository;
        private final AuthorizationRepository.Endpoint endpoint;
        private Invitation invitation;
        private final List<String> t = new ArrayList<>();
        private PublicKey receiver;
        private int epoch;
        private int stage;
        Session(AuthorizationRepository repository, AuthorizationRepository.Endpoint endpoint, Invitation invitation, SecureRandom random) throws Exception {
            this.repository = repository; this.endpoint = endpoint; this.invitation = invitation;
            byte[] nonce = new byte[32]; random.nextBytes(nonce);
            String phone = Invitation.encode(repository.phoneKey()); publicKey(phone);
            Collections.addAll(t, "1", invitation == null ? "resume" : "invite", endpoint.channel, endpoint.target,
                    invitation == null ? "" : invitation.id, phone, Invitation.encode(nonce));
        }
        String hello() {
            if (stage != 0) throw new IllegalStateException("Handshake order"); stage = 1;
            return StrictJson.frame("v", 1, "type", "hello", "mode", t.get(1), "channel", t.get(2), "target", t.get(3),
                    "invite", t.get(4), "phoneKey", t.get(5), "clientNonce", t.get(6));
        }
        String challenge(Map<String, Object> frame) throws Exception {
            if (stage != 1) throw StrictJson.invalid();
            StrictJson.keys(frame, "v", "type", "receiverKey", "serverNonce", "epoch", "signature");
            String receiverKey = StrictJson.string(frame, "receiverKey"), nonce = StrictJson.string(frame, "serverNonce");
            Invitation.decode(nonce, 32); epoch = StrictJson.integer(frame, "epoch");
            byte[] der = Invitation.decode(receiverKey, 91);
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(der), Invitation.decode(endpoint.fingerprint, 32)))
                throw new GeneralSecurityException("Receiver pin rejected");
            receiver = publicKey(receiverKey);
            Collections.addAll(t, receiverKey, nonce, Integer.toString(epoch));
            verify(receiver, transcript("receiver-challenge", t), StrictJson.string(frame, "signature"));
            String proof = "";
            if (invitation != null) {
                Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(invitation.secret(), "HmacSHA256"));
                proof = Invitation.encode(mac.doFinal(transcript("invite-proof", t)));
            }
            String signature = Invitation.encode(repository.sign(transcript("phone-proof", t)));
            invitation = null; stage = 2;
            return StrictJson.frame("v", 1, "type", "proof", "signature", signature, "inviteProof", proof);
        }
        void authorized(Map<String, Object> frame) throws Exception {
            if (stage != 2) throw StrictJson.invalid();
            StrictJson.keys(frame, "v", "type", "epoch", "channels", "target", "signature");
            String channels = StrictJson.string(frame, "channels"), target = StrictJson.string(frame, "target");
            if (StrictJson.integer(frame, "epoch") != epoch || !target.equals(endpoint.target)
                    || !(channels.equals("bt") || channels.equals("wifi") || channels.equals("bt,wifi"))
                    || !Arrays.asList(channels.split(",")).contains(endpoint.channel)) throw StrictJson.invalid();
            verify(receiver, transcript("receiver-authorized", t, Integer.toString(epoch), channels, target), StrictJson.string(frame, "signature"));
            repository.activate(endpoint, epoch, channels); // Never authenticated until durable metadata commit succeeds.
            stage = 3;
        }
    }
}
