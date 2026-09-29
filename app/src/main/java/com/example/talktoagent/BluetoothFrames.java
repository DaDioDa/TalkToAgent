package com.example.talktoagent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** RFCOMM framing: unsigned big-endian 32-bit byte length followed by strict UTF-8 JSON. */
final class BluetoothFrames {
    static final int MAX_FRAME_BYTES = 32768;

    private BluetoothFrames() {}

    static void write(OutputStream output, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > MAX_FRAME_BYTES) throw new IOException("Invalid frame length");
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    static String read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        int length = data.readInt();
        if (length < 1 || length > MAX_FRAME_BYTES) throw new IOException("Invalid frame length");
        byte[] payload = new byte[length];
        data.readFully(payload);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException("Invalid UTF-8", invalidUtf8);
        }
    }
}
