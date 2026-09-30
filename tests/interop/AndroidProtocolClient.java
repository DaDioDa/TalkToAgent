package com.example.talktoagent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;

/** JVM harness for the actual Android coordinator; stdin/stdout carry protocol frames only. */
public final class AndroidProtocolClient {
    private static final class Repository implements AuthorizationRepository {
        private final KeyPair identity;
        private Endpoint pending;
        private final List<Endpoint> active = new ArrayList<>();
        Repository() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            identity = generator.generateKeyPair();
        }
        public byte[] phoneKey() { return identity.getPublic().getEncoded(); }
        public byte[] sign(byte[] message) throws Exception {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(identity.getPrivate()); signature.update(message); return signature.sign();
        }
        public Endpoint pending() { return pending; }
        public List<Endpoint> active() { return new ArrayList<>(active); }
        public void savePending(Endpoint endpoint) { pending = endpoint; }
        public void activate(Endpoint endpoint, int epoch, String channels) {
            active.removeIf(old -> old.channel.equals(endpoint.channel)); active.add(endpoint); pending = null;
        }
        public void clearPending() { pending = null; }
    }
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        Repository repository = new Repository();
        ConnectionCoordinator coordinator = new ConnectionCoordinator(repository);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String uri = input.readLine();
        coordinator.selectInvitation(uri);
        emit(coordinator.begin());
        emit(coordinator.receive(input.readLine()).outbound);
        if ("lost".equals(args[0])) {
            // Receiver commits, but authorized reply never reaches Android.
            coordinator.disconnected();
            coordinator.selectResume(repository.pending());
            emit(coordinator.begin());
            emit(coordinator.receive(input.readLine()).outbound);
        }
        ConnectionCoordinator.Result authorized = coordinator.receive(input.readLine());
        if (authorized.event != ConnectionCoordinator.Event.AUTHORIZED || !coordinator.ready())
            throw new AssertionError("Expected authenticated coordinator");
        emit(coordinator.finalText("interop-one", "繁體中文 Mixed English：只貼一次"));
        ConnectionCoordinator.Result pasted = coordinator.receive(input.readLine());
        if (pasted.event != ConnectionCoordinator.Event.PASTED) throw new AssertionError("Expected pasted");
        emit("PASTED");
        coordinator.disconnected();
        coordinator.selectResume(repository.active().get(0));
        emit(coordinator.begin());
        emit(coordinator.receive(input.readLine()).outbound);
        coordinator.receive(input.readLine());
        emit(coordinator.finalText("interop-two", "原手機重連，不補送舊文字"));
        pasted = coordinator.receive(input.readLine());
        if (pasted.event != ConnectionCoordinator.Event.PASTED) throw new AssertionError("Expected resumed paste");
        emit("RESUMED");
    }
    private static void emit(String frame) { System.out.println(frame); System.out.flush(); }
}
