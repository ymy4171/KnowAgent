package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import io.milvus.v2.exception.MilvusClientException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the stable error mapping: SDK failures and timeouts become
 * VECTOR_UNAVAILABLE, a missing collection becomes VECTOR_SCHEMA_MISMATCH, and an
 * interruption restores the interrupt flag. Messages stay generic - no Milvus body,
 * endpoint or credentials (Rules 10 and 12).
 */
class MilvusErrorMapperTest {

    @Test
    void aMissingCollectionIsASchemaMismatch() {
        VectorStoreException mapped = MilvusErrorMapper.map(
                new MilvusClientException(io.milvus.v2.exception.ErrorCode.COLLECTION_NOT_FOUND,
                        "collection not found"));
        assertThat(mapped.errorCode()).isEqualTo(ErrorCode.VECTOR_SCHEMA_MISMATCH);
        assertThat(mapped.getMessage()).doesNotContain("collection not found");
    }

    @Test
    void sdkFailuresMapToUnavailableWithGenericMessages() {
        for (io.milvus.v2.exception.ErrorCode code : new io.milvus.v2.exception.ErrorCode[]{
                io.milvus.v2.exception.ErrorCode.RPC_ERROR,
                io.milvus.v2.exception.ErrorCode.SERVER_ERROR,
                io.milvus.v2.exception.ErrorCode.TIMEOUT,
                io.milvus.v2.exception.ErrorCode.CLIENT_ERROR,
                io.milvus.v2.exception.ErrorCode.INVALID_PARAMS}) {
            MilvusClientException sdk = new MilvusClientException(code, "sensitive sdk body with endpoint http://x:19530");
            VectorStoreException mapped = MilvusErrorMapper.map(sdk);
            assertThat(mapped.errorCode()).isEqualTo(ErrorCode.VECTOR_UNAVAILABLE);
            assertThat(mapped.getMessage())
                    .doesNotContain("sensitive")
                    .doesNotContain("endpoint")
                    .doesNotContain("http");
            assertThat(mapped).hasNoCause();
        }
    }

    @Test
    void timeoutsAndWrappedTimeoutsMapToUnavailable() {
        assertThat(MilvusErrorMapper.map(new TimeoutException("slow call")).errorCode())
                .isEqualTo(ErrorCode.VECTOR_UNAVAILABLE);
        assertThat(MilvusErrorMapper.map(
                new CompletionException(new TimeoutException("slow call"))).errorCode())
                .isEqualTo(ErrorCode.VECTOR_UNAVAILABLE);
        assertThat(MilvusErrorMapper.map(
                new ExecutionException(new MilvusClientException(io.milvus.v2.exception.ErrorCode.RPC_ERROR, "x"))).errorCode())
                .isEqualTo(ErrorCode.VECTOR_UNAVAILABLE);
    }

    @Test
    void interruptionMapsToUnavailableAndRestoresTheFlag() {
        Thread.currentThread().interrupt();
        try {
            VectorStoreException mapped = MilvusErrorMapper.map(new InterruptedException("stopped"));
            assertThat(mapped.errorCode()).isEqualTo(ErrorCode.VECTOR_UNAVAILABLE);
        } finally {
            boolean flagRestored = Thread.currentThread().isInterrupted();
            Thread.interrupted(); // clear so the test thread stays clean
            assertThat(flagRestored).isTrue();
        }
    }

    @Test
    void anAlreadyMappedExceptionPassesThrough() {
        VectorStoreException original = new VectorStoreException(ErrorCode.VECTOR_BAD_RESPONSE, "bad response");
        assertThat(MilvusErrorMapper.map(original)).isSameAs(original);
    }

    @Test
    void unknownFailuresMapToUnavailable() {
        assertThat(MilvusErrorMapper.map(new IllegalStateException("boom")).errorCode())
                .isEqualTo(ErrorCode.VECTOR_UNAVAILABLE);
    }
}
