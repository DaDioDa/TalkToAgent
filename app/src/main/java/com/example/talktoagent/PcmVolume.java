package com.example.talktoagent;

/** Read-only, normalized RMS of existing 16kHz mono PCM16 LE chunks. Not calibrated dB. */
final class PcmVolume {
    private PcmVolume() { }
    static float level(byte[] pcm, int count) {
        if (pcm == null || count < 2 || count > pcm.length) return Float.NaN;
        double squares = 0;
        int samples = count / 2;
        for (int i = 0; i < samples * 2; i += 2) {
            int sample = (short) ((pcm[i] & 255) | ((pcm[i + 1] & 255) << 8));
            double normalized = sample / 32768.0;
            squares += normalized * normalized;
        }
        return (float) Math.sqrt(squares / samples);
    }
}
