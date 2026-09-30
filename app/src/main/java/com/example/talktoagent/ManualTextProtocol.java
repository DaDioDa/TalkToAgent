package com.example.talktoagent;

/** Encodes the shared WebSocket messages used for one manual final-text delivery. */
final class ManualTextProtocol {
    static final int MAX_TEXT_BYTES = 4096;

    private ManualTextProtocol() {}

    static String authentication(String pairingCode) {
        if (pairingCode == null || pairingCode.isEmpty() || !isWellFormedUnicode(pairingCode)) {
            throw new IllegalArgumentException("Pairing code is required.");
        }
        return "{\"type\":\"authenticate\",\"pairingCode\":" + quote(pairingCode) + "}";
    }

    static String finalText(String id, String text) {
        if (id == null || !id.matches("[\\x00-\\x7F]{1,128}") || !isValidFinalText(text))
            throw new IllegalArgumentException("Invalid final text/id");
        return "{\"v\":1,\"type\":\"final_text\",\"id\":" + quote(id) + ",\"text\":" + quote(text) + "}";
    }

    static String finalText(String text) {
        if (!isValidFinalText(text)) {
            throw new IllegalArgumentException("Final text must be nonblank and at most 4 KiB of UTF-8.");
        }
        return "{\"type\":\"final_text\",\"text\":" + quote(text) + "}";
    }

    static boolean isValidFinalText(String text) {
        if (text == null || text.isEmpty() || !isWellFormedUnicode(text)) {
            return false;
        }
        boolean hasNonWhitespace = false;
        int utf8Bytes = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (codePoint == 0) {
                return false;
            }
            // Python's str.strip() on the receiver also treats U+0085 as whitespace.
            if (codePoint != 0x0085
                    && !Character.isWhitespace(codePoint)
                    && !Character.isSpaceChar(codePoint)) {
                hasNonWhitespace = true;
            }
            if (codePoint <= 0x7f) {
                utf8Bytes++;
            } else if (codePoint <= 0x7ff) {
                utf8Bytes += 2;
            } else if (codePoint <= 0xffff) {
                utf8Bytes += 3;
            } else {
                utf8Bytes += 4;
            }
            if (utf8Bytes > MAX_TEXT_BYTES) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return hasNonWhitespace;
    }

    private static boolean isWellFormedUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.append('"').toString();
    }
}
