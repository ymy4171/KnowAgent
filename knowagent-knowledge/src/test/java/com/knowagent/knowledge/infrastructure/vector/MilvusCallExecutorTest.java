package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import io.milvus.v2.exception.MilvusClientException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the per-operation timeout wrapper: successful calls return the value,
 * SDK failures are mapped to the stable contract, and a call exceeding its
 * timeout is aborted as VECTOR_UNAVAILABLE without leaking the supplier's
 * exception type.
 */
class MilvusCallExecutorTest {

    private static final String COLLECTION = "knowledge_chunks";

    @Test
    void aSuccessfulCallReturnsTheValue() {
        try (MilvusCallExecutor executor = new MilvusCallExecutor(new VectorMetrics(null))) {
            String value = executor.call(COLLECTION, "search", Duration.ofSeconds(5), () -> "ok");
            assertThat(value).isEqualTo("ok");
        }
    }

    @Test
    void anSdkFailureIsMappedToUnavailable() {
        try (MilvusCallExecutor executor = new MilvusCallExecutor(new VectorMetrics(null))) {
            assertThatThrownBy(() -> executor.call(COLLECTION, "upsert", Duration.ofSeconds(5),
                    () -> {
                        throw new MilvusClientException(io.milvus.v2.exception.ErrorCode.RPC_ERROR, "boom");
                    }))
                    .isInstanceOf(VectorStoreException.class)
                    .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                            .isEqualTo(ErrorCode.VECTOR_UNAVAILABLE));
        }
    }

    @Test
    void aSlowCallIsAbortedByItsTimeout() {
        try (MilvusCallExecutor executor = new MilvusCallExecutor(new VectorMetrics(null))) {
            assertThatThrownBy(() -> executor.call(COLLECTION, "search", Duration.ofMillis(150),
                    () -> {
                        try {
                            Thread.sleep(5_000);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return "late";
                    }))
                    .isInstanceOf(VectorStoreException.class)
                    .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                            .isEqualTo(ErrorCode.VECTOR_UNAVAILABLE));
        }
    }
}
