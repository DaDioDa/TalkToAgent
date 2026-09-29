package com.example.talktoagent;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class BluetoothFramesTest {
    @Test public void multipleFramesAndUnicode() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BluetoothFrames.write(bytes, "{\"text\":\"繁體 hello\"}");
        BluetoothFrames.write(bytes, "{\"type\":\"pasted\"}");
        ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
        assertEquals("{\"text\":\"繁體 hello\"}", BluetoothFrames.read(input));
        assertEquals("{\"type\":\"pasted\"}", BluetoothFrames.read(input));
    }
    @Test public void rejectsOversizedAndTruncated() throws Exception {
        assertThrows(IOException.class, () -> BluetoothFrames.write(new ByteArrayOutputStream(), "x".repeat(32769)));
        assertThrows(IOException.class, () -> BluetoothFrames.read(new ByteArrayInputStream(new byte[]{0, 0, (byte) 128, 1})));
        assertThrows(EOFException.class, () -> BluetoothFrames.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 2, 65})));
    }
    @Test public void rejectsInvalidUtf8() {
        assertThrows(IOException.class, () -> BluetoothFrames.read(new ByteArrayInputStream(new byte[]{0,0,0,1,(byte) 0xff})));
    }
}
