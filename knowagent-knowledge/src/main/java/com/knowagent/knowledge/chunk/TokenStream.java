package com.knowagent.knowledge.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A positioned token stream over a document text produced by the deterministic estimator
 * ({@link DeterministicTokenCounter}). The atomic tokens partition the text: every
 * character belongs to at most one token, whitespace belongs to none, and token
 * boundaries always fall between Unicode code points, so a surrogate pair (e.g. an emoji)
 * is never split.
 *
 * <p>Because {@code countTokens} is exactly the number of atomic tokens, the prefix
 * lookups below are exact for any slice whose boundaries are token boundaries: the token
 * count of {@code text[start..end)} equals {@code tokenCountAt(end) - tokenCountAt(start)}.
 *
 * <p>Estimator {@code char-run-v1}: every CJK ideograph / fullwidth form counts as one
 * token; every run of other non-space code points counts as one token per (up to) four
 * code points.
 */
public final class TokenStream {

    /** One atomic token: the inclusive {@code startChar} and exclusive {@code endChar} char offsets. */
    public record Token(int startChar, int endChar) {
        public Token {
            if (startChar < 0 || endChar <= startChar) {
                throw new IllegalArgumentException("token offsets must satisfy 0 <= startChar < endChar");
            }
        }
    }

    private final List<Token> tokens;

    private TokenStream(List<Token> tokens) {
        this.tokens = tokens;
    }

    static TokenStream of(String text) {
        Objects.requireNonNull(text, "text must not be null");
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int next = index + Character.charCount(codePoint);
            if (isWhitespace(codePoint)) {
                // whitespace separates tokens and contributes none itself
            } else if (isIdeographic(codePoint)) {
                tokens.add(new Token(index, next));
            } else {
                int runStart = index;
                int runCodePoints = 0;
                while (index < text.length()) {
                    codePoint = text.codePointAt(index);
                    if (isWhitespace(codePoint) || isIdeographic(codePoint)) {
                        break;
                    }
                    runCodePoints++;
                    index += Character.charCount(codePoint);
                }
                splitRun(tokens, text, runStart, index, runCodePoints);
                continue;
            }
            index = next;
        }
        return fromTokens(text, tokens);
    }

    /**
     * Creates a positioned stream supplied by another tokenizer implementation. Tokens must
     * be ordered, non-overlapping, contained in {@code text}, and may not split a Unicode
     * surrogate pair. Gaps are allowed because whitespace may intentionally remain outside
     * every token.
     */
    public static TokenStream fromTokens(String text, List<Token> tokens) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(tokens, "tokens must not be null");
        int previousEnd = 0;
        for (Token token : tokens) {
            Objects.requireNonNull(token, "tokens must not contain null");
            if (token.startChar() < previousEnd || token.endChar() > text.length()) {
                throw new IllegalArgumentException("tokens must be ordered, non-overlapping and within the text");
            }
            if (!isCodePointBoundary(text, token.startChar()) || !isCodePointBoundary(text, token.endChar())) {
                throw new IllegalArgumentException("token boundaries must not split a Unicode surrogate pair");
            }
            previousEnd = token.endChar();
        }
        return new TokenStream(List.copyOf(tokens));
    }

    private static boolean isCodePointBoundary(String text, int offset) {
        return offset == 0 || offset == text.length()
                || !(Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset)));
    }

    /** Total number of atomic tokens in the stream. */
    public int count() {
        return tokens.size();
    }

    /**
     * Number of atomic tokens fully contained in {@code [0, charOffset)}. For an offset
     * inside a token (e.g. a section boundary that falls mid-word) the partial token is
     * not counted.
     */
    public int tokenCountAt(int charOffset) {
        int low = 0;
        int high = tokens.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (tokens.get(mid).endChar() <= charOffset) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** Char offset where the token at {@code tokenIndex} starts. Index must be in {@code [0, count())}. */
    public int tokenStartChar(int tokenIndex) {
        return tokens.get(tokenIndex).startChar();
    }

    /** Char offset just past the token at {@code tokenIndex}. Index must be in {@code [0, count())}. */
    public int tokenEndChar(int tokenIndex) {
        return tokens.get(tokenIndex).endChar();
    }

    private static void splitRun(List<Token> tokens, String text, int start, int end, int codePoints) {
        int cursor = start;
        int remaining = codePoints;
        while (cursor < end) {
            int count = Math.min(4, remaining);
            int boundary = cursor;
            for (int k = 0; k < count; k++) {
                boundary += Character.charCount(text.codePointAt(boundary));
            }
            tokens.add(new Token(cursor, boundary));
            cursor = boundary;
            remaining -= count;
        }
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
