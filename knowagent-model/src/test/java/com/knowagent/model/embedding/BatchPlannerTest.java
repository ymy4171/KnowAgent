package com.knowagent.model.embedding;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link BatchPlanner} honors the text count, estimated token total and
 * request body size limits at once, never produces an empty batch, keeps input order
 * and rejects an empty input or a single text that cannot fit a batch on its own.
 */
class BatchPlannerTest {

    private static final BatchPlanner.TokenEstimator ESTIMATOR = CharRunTokenEstimator.INSTANCE;

    private static BatchPlanner.Limits limits(int maxTexts, long maxTokens, int maxBytes) {
        return new BatchPlanner.Limits(maxTexts, maxTokens, maxBytes, ESTIMATOR);
    }

    @Test
    void rejectsEmptyOrBlankInput() {
        assertThatThrownBy(() -> BatchPlanner.plan(List.of(), limits(100, 8000, 200_000)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> BatchPlanner.plan(List.of("  "), limits(100, 8000, 200_000)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> BatchPlanner.plan(java.util.Arrays.asList("ok", null), limits(100, 8000, 200_000)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void rejectsSingleTextThatCannotFitEvenAlone() {
        // "aaaaaaaa" is one run of 8 -> 2 tokens; a limit of 1 token cannot fit it.
        assertThatThrownBy(() -> BatchPlanner.plan(List.of("aaaaaaaa"), limits(100, 1, 200_000)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        // A text whose JSON-aware request-body estimate exceeds the limit alone:
        // "abcde" -> 8 + 5 = 13 bytes + 256 fixed overhead = 269 > 268.
        assertThatThrownBy(() -> BatchPlanner.plan(List.of("abcde"), limits(100, 8000, 268)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void accountsForJsonControlCharacterEscapingInBodyLimit() {
        // Ten NUL characters serialize as ten six-byte "\\u0000" escapes. The old
        // four-bytes-per-code-unit estimate incorrectly accepted this under 310 bytes.
        String controls = "\0".repeat(10);

        assertThatThrownBy(() -> BatchPlanner.plan(List.of(controls), limits(100, 100, 310)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void includesVariableRequestOverheadInBodyLimit() {
        BatchPlanner.Limits withModelOverhead = new BatchPlanner.Limits(
                100, 8000, 300, 33, ESTIMATOR);

        assertThatThrownBy(() -> BatchPlanner.plan(List.of("abcd"), withModelOverhead))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void splitsOnTextCountLimit() {
        List<String> texts = List.of("a", "b", "c", "d", "e");
        List<BatchPlanner.Batch> batches = BatchPlanner.plan(texts, limits(2, 8000, 200_000));

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0).texts()).containsExactly("a", "b");
        assertThat(batches.get(1).texts()).containsExactly("c", "d");
        assertThat(batches.get(2).texts()).containsExactly("e");
        assertThat(concat(batches)).isEqualTo(texts);
        assertThat(batches).allSatisfy(batch -> assertThat(batch.texts()).isNotEmpty());
    }

    @Test
    void splitsOnEstimatedTokenLimit() {
        // "aaaaaaa" = 7-run -> 2 tokens; "aaaa" = 1; "aaa" = 1.
        List<String> texts = List.of("aaaaaaa", "aaaa", "aaa");
        List<BatchPlanner.Batch> batches = BatchPlanner.plan(texts, limits(100, 2, 200_000));

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).texts()).containsExactly("aaaaaaa");
        assertThat(batches.get(1).texts()).containsExactly("aaaa", "aaa");
        assertThat(batches.get(0).estimatedTokens()).isEqualTo(2);
        assertThat(batches.get(1).estimatedTokens()).isEqualTo(2);
        assertThat(concat(batches)).isEqualTo(texts);
    }

    @Test
    void splitsOnRequestBodySizeLimit() {
        // Per-text ASCII estimate is 8 + length. Overhead 256. With limit 270 each
        // text fits alone, while "ab" and "abcd" together need 278 bytes.
        List<String> texts = List.of("ab", "abcd", "ab");
        List<BatchPlanner.Batch> batches = BatchPlanner.plan(texts, limits(100, 8000, 270));

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0).texts()).containsExactly("ab");
        assertThat(batches.get(1).texts()).containsExactly("abcd");
        assertThat(batches.get(2).texts()).containsExactly("ab");
        assertThat(concat(batches)).isEqualTo(texts);
        assertThat(batches).allSatisfy(batch ->
                assertThat(batch.estimatedRequestBodyBytes()).isLessThanOrEqualTo(270));
    }

    @Test
    void allLimitsAreRespectedSimultaneously() {
        // Four texts of 8 chars = 2 tokens each, so token limit 4 and text limit 3
        // both bind: batch1 [t0,t1,t2] = 6 tokens -> too many, so token limit binds at 4;
        // with text limit 3 the first batch holds 2 texts (4 tokens), etc.
        List<String> texts = List.of("aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd");
        List<BatchPlanner.Batch> batches = BatchPlanner.plan(texts, limits(3, 4, 200_000));

        assertThat(concat(batches)).isEqualTo(texts);
        assertThat(batches).allSatisfy(batch -> {
            assertThat(batch.texts()).isNotEmpty();
            assertThat(batch.texts().size()).isLessThanOrEqualTo(3);
            assertThat(batch.estimatedTokens()).isLessThanOrEqualTo(4);
        });
        assertThat(batches.get(0).estimatedTokens()).isEqualTo(4); // two 2-token texts
    }

    @Test
    void validatesLimits() {
        assertThatThrownBy(() -> limits(0, 8000, 200_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(100, 0, 200_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(100, 8000, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> concat(List<BatchPlanner.Batch> batches) {
        return batches.stream().flatMap(batch -> batch.texts().stream()).toList();
    }
}
