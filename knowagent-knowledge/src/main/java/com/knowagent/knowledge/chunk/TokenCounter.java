package com.knowagent.knowledge.chunk;

/**
 * Port for counting (and, later, tokenizing) text into tokens for chunk-size budgeting
 * and per-chunk token offsets. Until a provider tokenizer is wired in ({@code prompt 15}),
 * the single implementation is the deterministic estimator {@link DeterministicTokenCounter},
 * whose algorithm version is persisted in every chunk's metadata so an estimate can never
 * be mistaken for an exact token count.
 */
public interface TokenCounter {

    /** Positioned atomic tokens over {@code text} under this counter's algorithm. */
    TokenStream tokenize(String text);

    /** Deterministic token count of {@code text} under this counter's algorithm. */
    int countTokens(String text);

    /** Stable identifier of the estimation algorithm, persisted in chunk metadata. */
    String algorithmVersion();
}
