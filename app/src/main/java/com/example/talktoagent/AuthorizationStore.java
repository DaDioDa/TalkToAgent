package com.example.talktoagent;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Non-exportable phone identity and separate authenticated, synchronous metadata storage.
 * All instances serialize reads and writes; unreadable state is never reset to untrusted/empty.
 * Call from an I/O worker, not the UI thread. No invitation secret enters this API.
 */
public final class AuthorizationStore implements AuthorizationRepository {
    private static final Object LOCK = new Object();
    private static final String IDENTITY = "talktoagent.authorization.p256.v1";
    private static final String METADATA = "talktoagent.authorization.metadata.v1";
    private static final byte[] AAD = "TalkToAgent authorization metadata v1".getBytes(StandardCharsets.US_ASCII);
    private final AtomicFile file;
    private final KeyStore keys;

    public AuthorizationStore(Context context) throws Exception {
        synchronized (LOCK) {
            file = new AtomicFile(new File(context.getNoBackupFilesDir(), "authorization-v1.bin"));
            keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            // Do not mint a replacement identity or encryption key over existing state.
            if (storageExists() && (!keys.containsAlias(IDENTITY) || !keys.containsAlias(METADATA)))
                throw new GeneralSecurityException("Authorization keys unavailable");
            if (!keys.containsAlias(IDENTITY)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
                generator.initialize(new KeyGenParameterSpec.Builder(IDENTITY, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build());
                generator.generateKeyPair();
            }
            if (!keys.containsAlias(METADATA)) {
                KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(METADATA,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build());
                generator.generateKey();
            }
            AuthorizationProtocol.publicKey(Invitation.encode(phoneKey()));
            read(); // Fail closed at construction, not just when starting a connection.
        }
    }

    @Override public byte[] phoneKey() throws Exception {
        synchronized (LOCK) {
            if (keys.getCertificate(IDENTITY) == null) throw new GeneralSecurityException("Identity unavailable");
            return keys.getCertificate(IDENTITY).getPublicKey().getEncoded();
        }
    }
    @Override public byte[] sign(byte[] transcript) throws Exception {
        synchronized (LOCK) {
            PrivateKey key = (PrivateKey) keys.getKey(IDENTITY, null);
            if (key == null || key.getEncoded() != null) throw new GeneralSecurityException("Identity unavailable");
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(key); signature.update(transcript); return signature.sign();
        }
    }
    @Override public Endpoint pending() throws Exception { synchronized (LOCK) { return read().pending; } }
    @Override public List<Endpoint> active() throws Exception {
        synchronized (LOCK) { return new ArrayList<>(read().active); }
    }
    @Override public void savePending(Endpoint endpoint) throws Exception {
        synchronized (LOCK) {
            State state = read(); checkPin(state, endpoint);
            state.pending = endpoint; write(state);
        }
    }
    @Override public void activate(Endpoint endpoint, int epoch, String channels) throws Exception {
        synchronized (LOCK) {
            State state = read(); checkPin(state, endpoint);
            if (epoch < 0 || !(channels.equals("wifi") || channels.equals("bt") || channels.equals("bt,wifi"))
                    || !List.of(channels.split(",")).contains(endpoint.channel))
                throw new GeneralSecurityException("Authorization metadata invalid");
            // Retain only targets actually authorized by this Receiver. A new channel's target
            // must come from that channel's verified invite, never guessed from a channels list.
            state.active.removeIf(old -> old.channel.equals(endpoint.channel)
                    || !List.of(channels.split(",")).contains(old.channel) || state.epoch != epoch);
            state.active.add(endpoint); state.epoch = epoch; state.channels = channels;
            state.pending = null; write(state);
        }
    }
    @Override public void clearPending() throws Exception {
        synchronized (LOCK) { State state = read(); state.pending = null; write(state); }
    }
    private static void checkPin(State state, Endpoint endpoint) throws GeneralSecurityException {
        if (endpoint == null) throw new GeneralSecurityException("Authorization target invalid");
        if (state.pending != null && !state.pending.fingerprint.equals(endpoint.fingerprint))
            throw new GeneralSecurityException("Receiver pin mismatch");
        for (Endpoint old : state.active)
            if (!old.fingerprint.equals(endpoint.fingerprint)) throw new GeneralSecurityException("Receiver pin mismatch");
    }
    private static final class State {
        Endpoint pending;
        List<Endpoint> active = new ArrayList<>();
        int epoch;
        String channels = "";
    }
    private static String encode(Endpoint endpoint) {
        return endpoint == null ? "" : StrictJson.frame("channel", endpoint.channel,
                "target", endpoint.target, "fingerprint", endpoint.fingerprint);
    }
    private static Endpoint decode(String encoded) throws Exception {
        if (encoded.isEmpty()) return null;
        Map<String, Object> value = StrictJson.object(encoded);
        StrictJson.keys(value, "channel", "target", "fingerprint");
        return new Endpoint(StrictJson.string(value, "channel"), StrictJson.string(value, "target"), StrictJson.string(value, "fingerprint"));
    }
    private SecretKey metadataKey() throws Exception {
        SecretKey key = (SecretKey) keys.getKey(METADATA, null);
        if (key == null) throw new GeneralSecurityException("Metadata key unavailable");
        return key;
    }
    private boolean storageExists() {
        return file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists();
    }
    private State read() throws Exception {
        State state = new State();
        if (!storageExists()) return state;
        if (file.getBaseFile().length() > 16384) throw new GeneralSecurityException("Authorization storage invalid");
        byte[] bytes = file.readFully();
        if (bytes.length < 29 || bytes.length > 16384 || bytes[0] != 1)
            throw new GeneralSecurityException("Authorization storage invalid");
        byte[] iv = new byte[12]; System.arraycopy(bytes, 1, iv, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, metadataKey(), new GCMParameterSpec(128, iv)); cipher.updateAAD(AAD);
        byte[] plaintext = cipher.doFinal(bytes, 13, bytes.length - 13);
        String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString();
        Map<String, Object> value = StrictJson.object(json);
        StrictJson.keys(value, "v", "phoneKey", "epoch", "channels", "pending", "wifi", "bt");
        if (StrictJson.integer(value, "v") != 1 || !StrictJson.string(value, "phoneKey").equals(Invitation.encode(phoneKey())))
            throw new GeneralSecurityException("Authorization identity mismatch");
        state.epoch = StrictJson.integer(value, "epoch"); state.channels = StrictJson.string(value, "channels");
        if (!(state.channels.isEmpty() || state.channels.equals("wifi") || state.channels.equals("bt") || state.channels.equals("bt,wifi")))
            throw new GeneralSecurityException("Authorization channels invalid");
        for (String channel : new String[]{"wifi", "bt"}) {
            Endpoint endpoint = decode(StrictJson.string(value, channel));
            if (endpoint != null) {
                if (!endpoint.channel.equals(channel) || !List.of(state.channels.split(",")).contains(channel))
                    throw new GeneralSecurityException("Authorization channel mismatch");
                checkPin(state, endpoint); state.active.add(endpoint);
            }
        }
        state.pending = decode(StrictJson.string(value, "pending"));
        if (state.pending != null) checkPin(state, state.pending);
        return state;
    }
    private void write(State state) throws Exception {
        Endpoint wifi = null, bt = null;
        for (Endpoint endpoint : state.active) {
            if (endpoint.channel.equals("wifi")) wifi = endpoint; else bt = endpoint;
        }
        String json = StrictJson.frame("v", 1, "phoneKey", Invitation.encode(phoneKey()),
                "epoch", state.epoch, "channels", state.channels, "pending", encode(state.pending), "wifi", encode(wifi), "bt", encode(bt));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, metadataKey()); cipher.updateAAD(AAD);
        byte[] ciphertext = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] committed = ByteBuffer.allocate(13 + ciphertext.length).put((byte) 1).put(cipher.getIV()).put(ciphertext).array();
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(committed);
            output.flush(); output.getFD().sync();
            file.finishWrite(output); output = null;
            // AtomicFile.finishWrite logs some rename failures rather than throwing: do not
            // authenticate merely because it returned. Confirm the new record is readable.
            if (!java.security.MessageDigest.isEqual(committed, file.readFully()))
                throw new GeneralSecurityException("Authorization commit failed");
            java.io.FileDescriptor directory = android.system.Os.open(file.getBaseFile().getParent(),
                    android.system.OsConstants.O_RDONLY, 0);
            try { android.system.Os.fsync(directory); } finally { android.system.Os.close(directory); }
        } finally { if (output != null) file.failWrite(output); }
    }
}
