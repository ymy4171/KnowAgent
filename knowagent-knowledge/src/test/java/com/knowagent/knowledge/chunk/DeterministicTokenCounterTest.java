package com.knowagent.knowledge.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deterministic estimator {@code char-run-v1}: every CJK ideograph / fullwidth form is
 * one token; every run of other non-space code points is one token per (up to) four code
 * points. Counting is stable across calls, empty and whitespace-only text counts zero, and
 * the atomic token stream never splits a surrogate pair.
 */
class DeterministicTokenCounterTest {

    private final DeterministicTokenCounter counter = new DeterministicTokenCounter();

    @Test
    void cjkIdeographsCountOneTokenEach() {
        assertThat(counter.countTokens("你好世界")).isEqualTo(4);
        assertThat(counter.countTokens("中文")).isEqualTo(2);
        assertThat(counter.countTokens("全角ＡＢＣ")).isEqualTo(5); // 2 CJK + 3 fullwidth forms
    }

    @Test
    void runsOfNonCjkCodePointsSplitEveryFour() {
        assertThat(counter.countTokens("a")).isEqualTo(1);
        assertThat(counter.countTokens("hello")).isEqualTo(2);      // "hell" + "o"
        assertThat(counter.countTokens("abcdefgh")).isEqualTo(2);    // "abcd" + "efgh"
        assertThat(counter.countTokens("a b c")).isEqualTo(3);       // three one-char runs
    }

    @Test
    void whitespaceCountsNothing() {
        assertThat(counter.countTokens("")).isZero();
        assertThat(counter.countTokens("   \n\t  ")).isZero();
    }

    @Test
    void mixedChineseAndEnglishAddUp() {
        assertThat(counter.countTokens("Hello 世界")).isEqualTo(4);  // "Hello"→2 + 世+界→2
    }

    @Test
    void emojiArePartOfTheSurroundingRun() {
        assertThat(counter.countTokens("😀")).isEqualTo(1);
        assertThat(counter.countTokens("a😀b")).isEqualTo(1);        // 3 code points, one run
        assertThat(counter.countTokens("a😀b😀c😀d😀e")).isEqualTo(3); // 9 code points → 3 atomic tokens
    }

    @Test
    void tokenStreamNeverSplitsSurrogatePairs() {
        String text = "a😀b😀c😀d😀e";
        TokenStream stream = counter.tokenize(text);
        for (TokenStream.Token token : allTokens(stream)) {
            assertThat(Character.isHighSurrogate(text.charAt(token.endChar() - 1)))
                    .describedAs("token ends on a lone high surrogate")
                    .isFalse();
            assertThat(Character.isLowSurrogate(text.charAt(token.startChar())))
                    .describedAs("token starts on a lone low surrogate")
                    .isFalse();
        }
    }

    @Test
    void countingIsDeterministic() {
        String text = "第一段中文 English words 混合 😀 内容";
        assertThat(counter.countTokens(text)).isEqualTo(counter.countTokens(text));
        assertThat(counter.tokenize(text).count()).isEqualTo(counter.countTokens(text));
    }

    @Test
    void algorithmVersionIsStableAndReported() {
        assertThat(counter.algorithmVersion()).isEqualTo("char-run-v1");
    }

    @Test
    void positionedTokenFactorySupportsOtherTokenizersAndRejectsInvalidRanges() {
        TokenStream stream = TokenStream.fromTokens("ab cd",
                java.util.List.of(new TokenStream.Token(0, 2), new TokenStream.Token(3, 5)));
        assertThat(stream.count()).isEqualTo(2);
        assertThat(stream.tokenCountAt(5)).isEqualTo(2);

        assertThatThrownBy(() -> TokenStream.fromTokens("abcde",
                java.util.List.of(new TokenStream.Token(1, 4), new TokenStream.Token(3, 5))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenStream.fromTokens("a😀b",
                java.util.List.of(new TokenStream.Token(0, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surrogate pair");
    }

    private static Iterable<TokenStream.Token> allTokens(TokenStream stream) {
        return new java.util.ArrayList<>() {{
            for (int i = 0; i < stream.count(); i++) {
                add(new TokenStream.Token(stream.tokenStartChar(i), stream.tokenEndChar(i)));
            }
        }};
    }
}
