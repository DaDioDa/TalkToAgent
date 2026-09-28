package com.example.talktoagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ManualTextProtocolTest {
    @Test
    public void authenticationFrameMatchesSharedWireContract() {
        assertEquals(
                "{\"type\":\"authenticate\",\"pairingCode\":\"abc-123\"}",
                ManualTextProtocol.authentication("abc-123"));
    }

    @Test
    public void finalTextFrameEscapesJsonAndPreservesUnicodeText() {
        assertEquals(
                "{\"type\":\"final_text\",\"text\":\"繁體 \\\"quoted\\\"\\nEnglish\\\\tail\"}",
                ManualTextProtocol.finalText("繁體 \"quoted\"\nEnglish\\tail"));
    }

    @Test
    public void finalTextValidationUsesUtf8ByteLimitWithoutTrimming() {
        assertTrue(ManualTextProtocol.isValidFinalText("中".repeat(1365) + "a"));
        assertFalse(ManualTextProtocol.isValidFinalText("中".repeat(1366)));
        assertFalse(ManualTextProtocol.isValidFinalText(" \t\n"));
        assertFalse(ManualTextProtocol.isValidFinalText("\u00a0"));
        assertFalse(ManualTextProtocol.isValidFinalText("\u0085"));
        assertFalse(ManualTextProtocol.isValidFinalText("bad\0text"));
        assertTrue(ManualTextProtocol.isValidFinalText("  exact text  "));
    }

    @Test
    public void unpairedSurrogateIsNotValidProtocolText() {
        assertFalse(ManualTextProtocol.isValidFinalText("bad\uD800"));
        try {
            ManualTextProtocol.finalText("bad\uD800");
            fail("Expected malformed Unicode to be rejected");
        } catch (IllegalArgumentException expected) {
            // An unpaired surrogate cannot be represented as valid UTF-8 JSON text.
        }
    }
}
