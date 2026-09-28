package com.example.talktoagent;

import android.icu.text.Transliterator;

/** Convert final Chinese text to traditional glyphs without rewriting Latin text or punctuation. */
final class TraditionalText {
    private TraditionalText() { }

    static String convert(String text) {
        return Transliterator.getInstance("Simplified-Traditional").transliterate(text);
    }
}
