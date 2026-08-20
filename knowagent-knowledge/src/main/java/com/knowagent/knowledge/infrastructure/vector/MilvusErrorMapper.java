package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import io.milvus.v2.exception.MilvusClientException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Maps SDK and timeout failures to the stable {@link VectorStoreException} contract.
 * Messages are fixed and generic - the Milvus error body, endpoint, credentials and
 * any vector/chunk content never reach the message (Rules 10 and 12).
 */
final class MilvusErrorMapper {

    private MilvusErrorMapper() {
    }

    static VectorStoreException map(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof VectorStoreException stored) {
            return stored;
        }
        if (cause instanceof TimeoutException) {
            return new VectorStoreException(ErrorCode.VECTOR_UNAVAILABLE,
                    "The vector store call exceeded its timeout.");
        }
        if (cause instanceof InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new VectorStoreException(ErrorCode.VECTOR_UNAVAILABLE,
                    "The vector store call was interrupted.");
        }
        if (cause instanceof MilvusClientException milvus) {
            return mapMilvus(milvus);
        }
        return new VectorStoreException(ErrorCode.VECTOR_UNAVAILABLE,
                "The vector store call failed.");
    }

    private static VectorStoreException mapMilvus(MilvusClientException exception) {
        if (exception.getErrorCode() == io.milvus.v2.exception.ErrorCode.COLLECTION_NOT_FOUND) {
            // The configured collection is missing at runtime (for example dropped out
            // of band): the store is not in the expected state - never auto-create or
            // drop anything here, just surface a stable schema error.
            return new VectorStoreException(ErrorCode.VECTOR_SCHEMA_MISMATCH,
                    "The vector store collection is missing.");
        }
        return new VectorStoreException(ErrorCode.VECTOR_UNAVAILABLE,
                "The vector store call failed.");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
