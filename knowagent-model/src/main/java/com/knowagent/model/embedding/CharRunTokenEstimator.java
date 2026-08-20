package com.knowagent.model.embedding;

/**
 * Deterministic token estimator ({@code char-run-v1}) used until a provider tokenizer is
 * wired in. It shares the exact counting rule of the knowledge module's
 * {@code DeterministicTokenCounter}: every CJK ideograph / fullwidth form counts as one
 * token and every run of other non-space code points counts as one token per (up to)
 * four code points; whitespace contributes nothing. It is an <em>estimate</em>, never a
 * precise provider token count.
 *
 * <p>Kept in the model module rather than reusing the knowledge implementation because
 * the model module cannot depend on the knowledge module; the two estimators are pinned
 * to the same rule by their own tests.
 */
public final class CharRunTokenEstimator implements BatchPlanner.TokenEstimator {

    public static final CharRunTokenEstimator INSTANCE = new CharRunTokenEstimator();

    private CharRunTokenEstimator() {
    }

    @Override
    public long estimateTokens(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        long tokens = 0;
        int run = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isWhitespace(codePoint)) {
                tokens += ceilQuarter(run);
                run = 0;
            } else if (isIdeographic(codePoint)) {
                tokens += ceilQuarter(run) + 1;
                run = 0;
            } else {
                run++;
            }
        }
        return tokens + ceilQuarter(run);
    }

    private static long ceilQuarter(int run) {
        return (run + 3) / 4;
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint);
    }

    private static boolean isIdeographic(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)     // CJK Unified Ext A
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)  // CJK Unified
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)  // CJK Compatibility
                || (codePoint >= 0x20000 && codePoint <= 0x2A6DF) // Ext B
                || (codePoint >= 0x2A700 && codePoint <= 0x2B73F) // Ext C
                || (codePoint >= 0x2B740 && codePoint <= 0x2B81F) // Ext D
                || (codePoint >= 0x2B820 && codePoint <= 0x2CEAF) // Ext E
                || (codePoint >= 0x2CEB0 && codePoint <= 0x2EBEF) // Ext F
                || (codePoint >= 0x30000 && codePoint <= 0x3134F) // Ext G
                || (codePoint >= 0x3000 && codePoint <= 0x303F)   // CJK punctuation
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);  // fullwidth forms
    }
}
