package com.example.talktoagent;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the confirmed workflow seam with real Android Keystore/file boundaries.
 * This is not a Bluetooth or camera hardware test. Run only in the dedicated debug test app.
 */
public class AuthorizationPersistenceTest {
    private JsonObject vectors() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open("authorization-vectors.json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private Context isolatedContext() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(app.getNoBackupFilesDir(), "authorization-test-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        return new ContextWrapper(app) { @Override public File getNoBackupFilesDir() { return directory; } };
    }
    private void cleanup(Context context) {
        File directory = context.getNoBackupFilesDir();
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }
    // Receiver test-only scalar 1 is documented in the shared fixture. Phone identity is never fixed.
    private String signature(List<String> transcript, String domain, String... tail) throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        PrivateKey receiver = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(
                BigInteger.ONE, parameters.getParameterSpec(ECParameterSpec.class)));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(receiver); signature.update(AuthorizationProtocol.transcript(domain, transcript, tail));
        return Invitation.encode(signature.sign());
    }
    private void authorize(ConnectionCoordinator flow, String hello, JsonObject vector) throws Exception {
        JsonObject frame = JsonParser.parseString(hello).getAsJsonObject();
        List<String> transcript = new ArrayList<>();
        transcript.add("1");
        for (String field : new String[]{"mode", "channel", "target", "invite", "phoneKey", "clientNonce"})
            transcript.add(frame.get(field).getAsString());
        String receiverKey = vector.get("receiverKey").getAsString();
        String nonce = vector.getAsJsonArray("transcript").get(8).getAsString();
        transcript.add(receiverKey); transcript.add(nonce); transcript.add("0");
        ConnectionCoordinator.Result proof = flow.receive(StrictJson.frame("v", 1, "type", "challenge",
                "receiverKey", receiverKey, "serverNonce", nonce, "epoch", 0,
                "signature", signature(transcript, "receiver-challenge")));
        assertEquals(ConnectionCoordinator.Event.PROOF, proof.event);
        // Verify the real non-exportable phone key proves the exact current transcript.
        JsonObject phoneProof = JsonParser.parseString(proof.outbound).getAsJsonObject();
        AuthorizationProtocol.verify(AuthorizationProtocol.publicKey(frame.get("phoneKey").getAsString()),
                AuthorizationProtocol.transcript("phone-proof", transcript), phoneProof.get("signature").getAsString());
        String channel = frame.get("channel").getAsString(), target = frame.get("target").getAsString();
        assertEquals(ConnectionCoordinator.Event.AUTHORIZED, flow.receive(StrictJson.frame("v", 1, "type", "authorized",
                "epoch", 0, "channels", channel, "target", target,
                "signature", signature(transcript, "receiver-authorized", "0", channel, target))).event);
        assertTrue(flow.ready());
    }
    @Test public void lostReplyRecoversPendingWithSameKeystoreIdentityAndNoInviteSecret() throws Exception {
        Context context = isolatedContext();
        try {
            JsonObject fixture = vectors();
            AuthorizationStore firstStore = new AuthorizationStore(context);
            ConnectionCoordinator first = new ConnectionCoordinator(firstStore);
            first.selectInvitation(fixture.getAsJsonArray("valid").get(0).getAsJsonObject().get("uri").getAsString());
            JsonObject hello = JsonParser.parseString(first.begin()).getAsJsonObject(); first.disconnected();
            AuthorizationStore restartedStore = new AuthorizationStore(context);
            ConnectionCoordinator restarted = new ConnectionCoordinator(restartedStore);
            restarted.selectResume(restartedStore.pending());
            String resumed = restarted.begin();
            JsonObject frame = JsonParser.parseString(resumed).getAsJsonObject();
            assertEquals("resume", frame.get("mode").getAsString());
            assertEquals("", frame.get("invite").getAsString());
            assertEquals(hello.get("phoneKey"), frame.get("phoneKey"));
            authorize(restarted, resumed, fixture.getAsJsonObject("crypto"));
            restarted.disconnected();
            AuthorizationStore activeStore = new AuthorizationStore(context);
            ConnectionCoordinator cold = new ConnectionCoordinator(activeStore);
            assertFalse(cold.ready()); assertFalse(cold.attempting());
            assertNull(activeStore.pending());
            cold.selectResume(activeStore.active().get(0));
            authorize(cold, cold.begin(), fixture.getAsJsonObject("crypto"));
        } finally { cleanup(context); }
    }
    @Test public void bluetoothSessionCannotReuseAnAcknowledgedFinalId() throws Exception {
        Context context = isolatedContext();
        try {
            JsonObject fixture = vectors();
            ConnectionCoordinator flow = new ConnectionCoordinator(new AuthorizationStore(context));
            flow.selectInvitation(fixture.getAsJsonArray("valid").get(1).getAsJsonObject().get("uri").getAsString());
            authorize(flow, flow.begin(), fixture.getAsJsonObject("crypto"));
            flow.finalText("once", "繁體字"); flow.receive("{\"v\":1,\"type\":\"pasted\",\"id\":\"once\"}");
            assertTrue(flow.ready());
            assertThrows(IllegalStateException.class, () -> flow.finalText("once", "another"));
        } finally { cleanup(context); }
    }
}
