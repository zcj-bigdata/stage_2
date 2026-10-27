package com.zcj.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tokenizer shared by indexing and search (same rules as Stage 1):
 * lowercase the text, treat every character outside a-z as a separator
 * and return the words in order of appearance.
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int start = -1;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean letter = c >= 'a' && c <= 'z';
            if (letter && start < 0) {
                start = i;
            } else if (!letter && start >= 0) {
                tokens.add(lower.substring(start, i));
                start = -1;
            }
        }
        if (start >= 0) {
            tokens.add(lower.substring(start));
        }
        return tokens;
    }
}
