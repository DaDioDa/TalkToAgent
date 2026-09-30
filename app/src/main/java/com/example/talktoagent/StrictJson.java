package com.example.talktoagent;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Flat wire objects only. Detect duplicates before construction; no coercion or trailing data. */
final class StrictJson {
    static Map<String, Object> object(String json) throws Exception {
        if (json == null || !wellFormed(json) || json.getBytes(StandardCharsets.UTF_8).length > 32768) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.setStrictness(Strictness.STRICT);
            r.beginObject();
            while (r.hasNext()) {
                String key = r.nextName();
                if (result.containsKey(key)) throw invalid();
                Object value;
                if (r.peek() == JsonToken.STRING) { value = r.nextString(); if (!wellFormed((String) value)) throw invalid(); }
                else if (r.peek() == JsonToken.NUMBER) {
                    String number = r.nextString();
                    if (!number.matches("0|[1-9][0-9]{0,9}")) throw invalid();
                    value = Integer.valueOf(number);
                } else throw invalid();
                result.put(key, value);
            }
            r.endObject();
            if (r.peek() != JsonToken.END_DOCUMENT) throw invalid();
        }
        return result;
    }
    static void keys(Map<String, Object> o, String... keys) {
        if (!o.keySet().equals(Set.of(keys))) throw invalid();
    }
    static String string(Map<String, Object> o, String key) {
        if (!(o.get(key) instanceof String)) throw invalid(); return (String) o.get(key);
    }
    static int integer(Map<String, Object> o, String key) {
        if (!(o.get(key) instanceof Integer)) throw invalid(); return (Integer) o.get(key);
    }
    static String frame(Object... pairs) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) b.append(',');
            b.append(ManualTextProtocol.quote((String) pairs[i])).append(':');
            Object value = pairs[i + 1]; b.append(value instanceof String ? ManualTextProtocol.quote((String) value) : value);
        }
        return b.append('}').toString();
    }
    static boolean wellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == s.length() || !Character.isLowSurrogate(s.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid protocol frame"); }
}
