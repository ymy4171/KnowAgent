package com.knowagent.knowledge.chunk;

import java.util.Objects;

/**
 * Deterministic token estimator ({@code char-run-v1}) used until a provider tokenizer is
 * wired in. It is explicitly an <em>estimate</em>: every CJK ideograph / fullwidth form
 * counts as one token and every run of other non-space code points counts as one token
 * per (up to) four code points. The algorithm version is recorded in chunk metadata so
 * consumers can never confuse a character count with a precise token count.
 *
 * <p>Because the counting is defined at the atomic-token level and tokens partition the
 * text, chunking with {@link DeterministicChunker} produces exact, stable token offsets
 * and reproducible budgets for the same input and policy.
 */
public final class DeterministicTokenCounter implements TokenCounter {

    static final String ALGORITHM_VERSION = "char-run-v1";

    @Override
    public TokenStream tokenize(String text) {
        return TokenStream.of(Objects.requireNonNull(text, "text must not be null"));
    }

    @Override
    public int countTokens(String text) {
        return TokenStream.of(Objects.requireNonNull(text, "text must not be null")).count();
    }

    @Override
    public String algorithmVersion() {
        return ALGORITHM_VERSION;
    }
}
