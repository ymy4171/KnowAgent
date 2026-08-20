package com.knowagent.model.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link CharRunTokenEstimator} to the exact {@code char-run-v1} rule used by the
 * knowledge module's {@code DeterministicTokenCounter}: every CJK ideograph / fullwidth
 * form is one token, every run of other non-space code points is one token per (up to)
 * four code points, and whitespace contributes nothing. Both implementations are kept
 * in lock-step by these mirrored expectations.
 */
class CharRunTokenEstimatorTest {

    private final CharRunTokenEstimator estimator = CharRunTokenEstimator.INSTANCE;

    @Test
    void countsEveryCjkIdeographAsOneToken() {
        assertThat(estimator.estimateTokens("你好世界")).isEqualTo(4);
        assertThat(estimator.estimateTokens("𠀀"))   // CJK Ext B astral code point
                .isEqualTo(1);
    }

    @Test
    void countsFullwidthFormsAsOneTokenEach() {
        assertThat(estimator.estimateTokens("ＡＢＣ")).isEqualTo(3);
    }

    @Test
    void countsRunsOfFourNonSpaceCodePointsAsOneToken() {
        assertThat(estimator.estimateTokens("abc")).isEqualTo(1);
        assertThat(estimator.estimateTokens("abcd")).isEqualTo(1);
        assertThat(estimator.estimateTokens("abcde")).isEqualTo(2);
        assertThat(estimator.estimateTokens("abcdefgh")).isEqualTo(2);
    }

    @Test
    void whitespaceContributesNothingAndSeparatesRuns() {
        assertThat(estimator.estimateTokens("hello world")).isEqualTo(4); // 2 + 2
        assertThat(estimator.estimateTokens("  a   ")).isEqualTo(1);
    }

    @Test
    void mixesIdeographsAndRuns() {
        assertThat(estimator.estimateTokens("你好abc")).isEqualTo(3);
        assertThat(estimator.estimateTokens(" 你好世界 ")).isEqualTo(4);
    }

    @Test
    void doesNotSplitAnAstralEmojiIntoSurrogatePairs() {
        // 😀 is one code point, so one run of length 1 -> one token, not two.
        assertThat(estimator.estimateTokens("😀")).isEqualTo(1);
    }

    @Test
    void emptyTextHasNoTokens() {
        assertThat(estimator.estimateTokens("")).isZero();
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> estimator.estimateTokens(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
