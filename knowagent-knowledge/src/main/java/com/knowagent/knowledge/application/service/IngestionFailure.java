package com.knowagent.knowledge.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.observability.application.service.ErrorMessageSanitizer;
import com.knowagent.workspace.storage.ObjectStorageException;

/** Stable, non-sensitive Worker failure classification and retry decision. */
record IngestionFailure(ErrorCode errorCode, String message, boolean retryable) {

    static IngestionFailure from(Throwable failure) {
        if (failure instanceof ObjectStorageException storage) {
            return switch (storage.reason()) {
                case UNAVAILABLE -> stable(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "Object storage is temporarily unavailable.", true);
                case OBJECT_NOT_FOUND -> stable(ErrorCode.RESOURCE_NOT_FOUND,
                        "The source document is unavailable.", false);
                case ACCESS_DENIED, INVALID_OPERATION -> stable(ErrorCode.INTERNAL_ERROR,
                        "The source document could not be accessed.", false);
            };
        }
        if (failure instanceof BusinessException business) {
            ErrorCode code = business.errorCode();
            boolean retryable = switch (code) {
                case MODEL_RATE_LIMITED, MODEL_TIMEOUT, MODEL_SERVICE_ERROR,
                        VECTOR_UNAVAILABLE, DOCUMENT_TIMEOUT, EXTERNAL_SERVICE_ERROR,
                        CONFLICT -> true;
                default -> false;
            };
            return stable(code, stableMessage(code), retryable);
        }
        return stable(ErrorCode.INTERNAL_ERROR, "The ingestion task failed unexpectedly.", false);
    }

    private static IngestionFailure stable(ErrorCode code, String message, boolean retryable) {
        return new IngestionFailure(code, ErrorMessageSanitizer.sanitize(message), retryable);
    }

    private static String stableMessage(ErrorCode code) {
        return switch (code) {
            case EMPTY_DOCUMENT -> "The document contains no extractable text.";
            case DOCUMENT_TOO_LARGE -> "The document exceeds an ingestion limit.";
            case CORRUPT_DOCUMENT -> "The document is corrupted or unreadable.";
            case DOCUMENT_TIMEOUT -> "Document parsing timed out.";
            case OCR_REQUIRED -> "The document requires OCR, which is not configured.";
            case UNSUPPORTED_DOCUMENT_TYPE -> "The document type is not supported.";
            case MODEL_RATE_LIMITED -> "The embedding provider is rate limited.";
            case MODEL_TIMEOUT -> "The embedding provider timed out.";
            case MODEL_SERVICE_ERROR -> "The embedding provider is temporarily unavailable.";
            case MODEL_AUTH_FAILED, MODEL_CONFIGURATION_ERROR -> "The embedding provider configuration is invalid.";
            case MODEL_BAD_RESPONSE -> "The embedding provider returned an invalid response.";
            case VECTOR_UNAVAILABLE -> "The vector store is temporarily unavailable.";
            case VECTOR_SCHEMA_MISMATCH -> "The vector store schema is incompatible.";
            case VECTOR_BAD_RESPONSE -> "The vector store returned an invalid response.";
            case RESOURCE_NOT_FOUND -> "A required ingestion resource does not exist.";
            case CONFLICT -> "The ingestion state changed concurrently.";
            default -> "The ingestion task failed.";
        };
    }
}
