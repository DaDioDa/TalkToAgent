package com.example.talktoagent;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmVolumeTest {
    @Test public void readsSignedLittleEndianSamplesWithoutChangingAudio() {
        byte[] pcm = {0, 0, 0, 64, 0, (byte) 192}; // silence, +16384, -16384
        byte[] original = pcm.clone();
        assertEquals(0.408248f, PcmVolume.level(pcm, 6), .00001f);
        assertEquals(0f, PcmVolume.level(pcm, 2), 0f);
        assertEquals(1f, PcmVolume.level(new byte[]{0, (byte) 128}, 2), 0f);
        assertArrayEquals(original, pcm);
        assertTrue(Float.isNaN(PcmVolume.level(pcm, 0)));
        assertTrue(Float.isNaN(PcmVolume.level(pcm, 1)));
    }
}
