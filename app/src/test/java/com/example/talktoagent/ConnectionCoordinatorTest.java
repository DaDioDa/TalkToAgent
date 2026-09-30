package com.example.talktoagent;

import com.google.gson.*;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionCoordinatorTest {
    static final class MemoryRepository implements AuthorizationRepository {
        final KeyPair phone;
        AuthorizationRepository.Endpoint pending;
        List<AuthorizationRepository.Endpoint> active = new ArrayList<>();
        boolean failPending, failActive;
        MemoryRepository() throws Exception {
            JsonObject crypto = AuthorizationVectors.load().getAsJsonObject("crypto");
            PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getUrlDecoder().decode(crypto.get("phoneKey").getAsString())));
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC"); params.init(new ECGenParameterSpec("secp256r1"));
            PrivateKey priv = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(BigInteger.TWO, params.getParameterSpec(ECParameterSpec.class)));
            phone = new KeyPair(pub, priv);
        }
        public byte[] phoneKey() { return phone.getPublic().getEncoded(); }
        public byte[] sign(byte[] bytes) throws Exception { Signature s = Signature.getInstance("SHA256withECDSA"); s.initSign(phone.getPrivate()); s.update(bytes); return s.sign(); }
        public Endpoint pending() { return pending; }
        public List<Endpoint> active() { return new ArrayList<>(active); }
        public void savePending(Endpoint endpoint) throws Exception { if (failPending) throw new Exception("storage"); pending = endpoint; }
        public void activate(Endpoint endpoint, int epoch, String channels) throws Exception { if (failActive) throw new Exception("storage"); active = new ArrayList<>(List.of(endpoint)); pending = null; }
        public void clearPending() { pending = null; }
    }
    private ConnectionCoordinator coordinator(MemoryRepository store) throws Exception {
        JsonArray transcript = AuthorizationVectors.load().getAsJsonObject("crypto").getAsJsonArray("transcript");
        byte[] nonce = Base64.getUrlDecoder().decode(transcript.get(6).getAsString());
        return new ConnectionCoordinator(store, new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) { System.arraycopy(nonce, 0, bytes, 0, bytes.length); }
        });
    }
    private String uri() throws Exception { return AuthorizationVectors.load().getAsJsonArray("valid").get(0).getAsJsonObject().get("uri").getAsString(); }
    private String challenge() throws Exception {
        JsonObject c = AuthorizationVectors.load().getAsJsonObject("crypto");
        JsonObject f = new JsonObject(); f.addProperty("v", 1); f.addProperty("type", "challenge");
        f.add("receiverKey", c.get("receiverKey")); f.addProperty("serverNonce", c.getAsJsonArray("transcript").get(8).getAsString());
        f.addProperty("epoch", 0); f.add("signature", c.get("challengeSignature")); return f.toString();
    }
    private String authorized() throws Exception {
        JsonObject c = AuthorizationVectors.load().getAsJsonObject("crypto");
        JsonObject f = new JsonObject(); f.addProperty("v", 1); f.addProperty("type", "authorized"); f.addProperty("epoch", 0);
        f.add("channels", c.get("channels")); f.addProperty("target", c.getAsJsonArray("transcript").get(3).getAsString()); f.add("signature", c.get("authorizedSignature")); return f.toString();
    }
    @Test public void recreatedUiContinuesSpecifiedBondWithOriginalInMemoryInvitation() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator original = coordinator(store);
        String uri = AuthorizationVectors.load().getAsJsonArray("valid").get(1).getAsJsonObject().get("uri").getAsString();
        original.selectInvitation(uri); original.waitForSystemBond(); original.cancelPreparation();
        // A non-configuration holder hands the same workflow to a new UI; no secret is serialized.
        ConnectionCoordinator recreated = original;
        assertTrue(recreated.waitingForSystemBond()); assertFalse(recreated.canScan()); assertNull(store.pending());
        assertEquals(ConnectionCoordinator.BondProgress.IGNORED,
                recreated.observeSystemBond("bt:112233445566", ConnectionCoordinator.BondState.BONDED));
        assertEquals(ConnectionCoordinator.BondProgress.WAITING,
                recreated.observeSystemBond(recreated.selected().target, ConnectionCoordinator.BondState.BONDING));
        assertEquals(ConnectionCoordinator.BondProgress.CONNECT,
                recreated.observeSystemBond(recreated.selected().target, ConnectionCoordinator.BondState.BONDED));
        assertEquals("invite", JsonParser.parseString(recreated.begin()).getAsJsonObject().get("mode").getAsString());
        assertNotNull(store.pending());
    }
    @Test public void cancelledBondCannotConnectAndProcessDeathDoesNotAutoResumeInvitation() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        String uri = AuthorizationVectors.load().getAsJsonArray("valid").get(1).getAsJsonObject().get("uri").getAsString();
        flow.selectInvitation(uri); flow.waitForSystemBond();
        assertEquals(ConnectionCoordinator.BondProgress.CANCELLED,
                flow.observeSystemBond(flow.selected().target, ConnectionCoordinator.BondState.NONE));
        assertTrue(flow.canScan()); assertThrows(IllegalStateException.class, flow::begin);
        assertEquals(ConnectionCoordinator.BondProgress.IGNORED,
                flow.observeSystemBond(Invitation.parse(uri).target, ConnectionCoordinator.BondState.BONDED));
        ConnectionCoordinator cold = coordinator(store);
        assertFalse(cold.waitingForSystemBond()); assertFalse(cold.attempting()); assertNull(cold.selected()); assertNull(store.pending());
    }
    @Test public void differentReceiverInvitationCannotReplaceStoredComputer() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        flow.selectInvitation(uri()); flow.begin(); flow.receive(challenge()); flow.receive(authorized());
        flow.disconnected();
        String other = uri().replace(store.active().get(0).fingerprint,
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThrows(Exception.class, () -> { flow.selectInvitation(other); flow.begin(); });
        assertNull(store.pending());
        assertEquals("wifi:192.168.1.20:8765", store.active().get(0).target);
        assertFalse(flow.attempting());
    }
    @Test public void bluetoothSessionNeverReusesAcknowledgedFinalId() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        JsonObject fixture = AuthorizationVectors.load();
        flow.selectInvitation(fixture.getAsJsonArray("valid").get(1).getAsJsonObject().get("uri").getAsString());
        JsonObject hello = JsonParser.parseString(flow.begin()).getAsJsonObject();
        List<String> t = new ArrayList<>(); t.add("1");
        for (String key : new String[]{"mode", "channel", "target", "invite", "phoneKey", "clientNonce"}) t.add(hello.get(key).getAsString());
        JsonObject crypto = fixture.getAsJsonObject("crypto");
        t.add(crypto.get("receiverKey").getAsString()); t.add(crypto.getAsJsonArray("transcript").get(8).getAsString()); t.add("0");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC"); parameters.init(new ECGenParameterSpec("secp256r1"));
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(BigInteger.ONE, parameters.getParameterSpec(ECParameterSpec.class)));
        Signature signer = Signature.getInstance("SHA256withECDSA"); signer.initSign(key);
        signer.update(AuthorizationProtocol.transcript("receiver-challenge", t));
        flow.receive(StrictJson.frame("v", 1, "type", "challenge", "receiverKey", t.get(7), "serverNonce", t.get(8), "epoch", 0,
                "signature", Invitation.encode(signer.sign())));
        signer.initSign(key); signer.update(AuthorizationProtocol.transcript("receiver-authorized", t, "0", "bt", t.get(3)));
        flow.receive(StrictJson.frame("v", 1, "type", "authorized", "epoch", 0, "channels", "bt", "target", t.get(3),
                "signature", Invitation.encode(signer.sign())));
        flow.finalText("once", "繁體字"); flow.receive("{\"v\":1,\"type\":\"pasted\",\"id\":\"once\"}");
        assertTrue(flow.ready());
        assertThrows(IllegalStateException.class, () -> flow.finalText("once", "another"));
    }
    @Test public void sameSessionNeverEmitsDuplicateFinalId() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        flow.selectInvitation(uri()); flow.begin(); flow.receive(challenge()); flow.receive(authorized());
        assertEquals("{\"v\":1,\"type\":\"final_text\",\"id\":\"one\",\"text\":\"繁體\"}", flow.finalText("one", "繁體"));
        flow.receive("{\"v\":1,\"type\":\"pasted\",\"id\":\"one\"}");
        // Wi-Fi's once-per-connection rule is enforced even if caller tries another send.
        assertThrows(IllegalStateException.class, () -> flow.finalText("one", "another"));
    }
    @Test public void unknownOutcomePersistsAfterDisconnectAndManualReconnect() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        flow.selectInvitation(uri()); flow.begin(); flow.receive(challenge()); flow.receive(authorized());
        flow.finalText("one", "text"); flow.disconnected();
        assertTrue(flow.outcomeUnknown()); assertFalse(flow.pending());
        flow.selectResume(store.active().get(0));
        assertEquals("resume", JsonParser.parseString(flow.begin()).getAsJsonObject().get("mode").getAsString());
        assertTrue(flow.outcomeUnknown());
    }
    @Test public void persistedPendingCanResumeLostAuthorizedWithoutReusingSecret() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator first = coordinator(store);
        first.selectInvitation(uri()); first.begin(); first.receive(challenge()); first.disconnected();
        ConnectionCoordinator restarted = coordinator(store); restarted.selectResume(store.pending());
        JsonObject hello = JsonParser.parseString(restarted.begin()).getAsJsonObject();
        assertEquals("resume", hello.get("mode").getAsString()); assertEquals("", hello.get("invite").getAsString());
        assertTrue(store.active().isEmpty());
    }
    @Test public void pendingStorageFailurePreventsAnyHelloAndPreservesActive() throws Exception {
        MemoryRepository store = new MemoryRepository(); store.failPending = true;
        ConnectionCoordinator flow = coordinator(store); flow.selectInvitation(uri());
        assertThrows(Exception.class, flow::begin); assertFalse(flow.attempting()); assertFalse(flow.ready());
    }
    @Test public void activeCommitFailureCannotReportAuthenticated() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        flow.selectInvitation(uri()); flow.begin(); flow.receive(challenge()); store.failActive = true;
        assertThrows(Exception.class, () -> flow.receive(authorized())); assertFalse(flow.ready()); assertNotNull(store.pending());
    }
    @Test public void untrustedOrAmbiguousResponsesCannotProducePhoneProof() throws Exception {
        String valid = challenge();
        String[] bad = {valid.replace("\"v\":1", "\"v\":1,\"v\":1"), valid.replace("\"v\":1", "\"v\":\"1\""),
                valid.replace("\"epoch\":0", "\"epoch\":0.0"), valid.replace("\"epoch\":0", "\"epoch\":-1"),
                valid.replace("\"epoch\":0", "\"epoch\":2147483648"), valid.replace("\"epoch\":0", "\"epoch\":0,\"unknown\":1"),
                valid + "{}", valid.replace("\"type\":\"challenge\"", "\"type\":\"authorized\""),
                valid.replace("\"signature\":\"", "\"signature\":\"AA"), valid.replace("\"receiverKey\":\"", "\"receiverKey\":\"AA")};
        for (String frame : bad) {
            MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
            flow.selectInvitation(uri()); flow.begin();
            assertThrows(Exception.class, () -> flow.receive(frame)); assertFalse(flow.ready()); assertTrue(store.active().isEmpty());
        }
    }
    @Test public void cannotScanDuringConnectUtteranceOrUnacknowledgedFinal() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        flow.selectInvitation(uri()); flow.setUtteranceActive(true); assertFalse(flow.canScan());
        flow.setUtteranceActive(false); flow.begin(); assertFalse(flow.canScan());
        flow.receive(challenge()); flow.receive(authorized()); assertFalse(flow.canScan());
        flow.finalText("one", "text"); assertFalse(flow.canScan());
        flow.disconnected(); assertTrue(flow.canScan());
    }
    @Test public void aNewExplicitCompletedOperationDoesNotEraseEarlierUnknownOutcome() throws Exception {
        // A new operation's pasted reply only describes that operation, never a previously lost reply.
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        flow.selectInvitation(uri()); flow.begin(); flow.receive(challenge()); flow.receive(authorized());
        flow.finalText("one", "text"); flow.disconnected();
        flow.selectInvitation(uri()); flow.begin(); flow.receive(challenge()); flow.receive(authorized());
        flow.finalText("two", "new text"); flow.receive("{\"v\":1,\"type\":\"pasted\",\"id\":\"two\"}");
        assertTrue(flow.outcomeUnknown());
    }
    @Test public void authorizedSessionRejectsStaleOrDuplicateDeliveryResults() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        flow.selectInvitation(uri()); flow.begin(); flow.receive(challenge()); flow.receive(authorized());
        flow.finalText("one", "text");
        assertThrows(Exception.class, () -> flow.receive("{\"v\":1,\"type\":\"pasted\",\"id\":\"old\"}"));
        assertTrue(flow.outcomeUnknown()); assertFalse(flow.ready());
    }
    @Test public void scannedInvitationUsesSharedSignedHandshakeAndPersistsBeforeReady() throws Exception {
        MemoryRepository store = new MemoryRepository(); ConnectionCoordinator flow = coordinator(store);
        flow.selectInvitation(uri());
        JsonObject hello = JsonParser.parseString(flow.begin()).getAsJsonObject();
        assertEquals("invite", hello.get("mode").getAsString()); assertNotNull(store.pending()); assertTrue(store.active().isEmpty());
        JsonObject proof = JsonParser.parseString(flow.receive(challenge()).outbound).getAsJsonObject();
        JsonObject vector = AuthorizationVectors.load().getAsJsonObject("crypto");
        assertEquals(vector.get("inviteProof").getAsString(), proof.get("inviteProof").getAsString());
        assertFalse(flow.ready());
        assertEquals(ConnectionCoordinator.Event.AUTHORIZED, flow.receive(authorized()).event);
        assertTrue(flow.ready()); assertNull(store.pending()); assertEquals("wifi:192.168.1.20:8765", store.active().get(0).target);
    }
}
