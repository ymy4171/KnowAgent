package com.knowagent.common.error;

public enum ErrorCode {
    VALIDATION_ERROR,
    AUTHENTICATION_REQUIRED,
    ACCESS_DENIED,
    RESOURCE_NOT_FOUND,
    CONFLICT,
    EXTERNAL_SERVICE_ERROR,
    INTERNAL_ERROR,
    /** The request body exceeds an enforced size limit (multipart upload too large). */
    PAYLOAD_TOO_LARGE,
    /**
     * Unified, non-revealing credential failure. Covers an unknown tenant, an
     * unknown user and a wrong password, so callers cannot distinguish which part
     * of the credentials was wrong.
     */
    INVALID_CREDENTIALS,
    /** The account is permanently disabled and cannot authenticate. */
    ACCOUNT_DISABLED,
    /** The account is locked (status lock or an active temporary lock window). */
    ACCOUNT_LOCKED,
    /** No local parser supports the document's detected MIME type. */
    UNSUPPORTED_DOCUMENT_TYPE,
    /** The document contains no extractable text (blank/empty content). */
    EMPTY_DOCUMENT,
    /** The document exceeds a configured parse limit (bytes, pages, uncompressed size or text characters). */
    DOCUMENT_TOO_LARGE,
    /** The document is corrupted or unreadable and cannot be parsed. */
    CORRUPT_DOCUMENT,
    /** Parsing did not finish within the configured timeout. */
    DOCUMENT_TIMEOUT,
    /** The document has no text layer and needs an external OCR service to be read. */
    OCR_REQUIRED,
    /** The model provider rejected the request credentials (HTTP 401/403). */
    MODEL_AUTH_FAILED,
    /** The model provider is throttling requests (HTTP 429) and retries were exhausted. */
    MODEL_RATE_LIMITED,
    /** A model call exceeded the configured connect, read or total timeout. */
    MODEL_TIMEOUT,
    /** The model provider returned a malformed response: wrong count, wrong order, empty vector, non-finite value or unreadable body. */
    MODEL_BAD_RESPONSE,
    /** The model provider returned a server-side error (HTTP 5xx) and retries were exhausted, or was unreachable. */
    MODEL_SERVICE_ERROR,
    /** The model provider configuration or the submitted request cannot be served (missing/disabled provider, missing capability, unlisted model, other HTTP 4xx). */
    MODEL_CONFIGURATION_ERROR,
    /** The vector store (Milvus) is unreachable, timed out or returned a transient failure. */
    VECTOR_UNAVAILABLE,
    /** The vector store collection schema, dimension or metric is incompatible with the validated configuration (startup is refused; existing collections are never dropped). */
    VECTOR_SCHEMA_MISMATCH,
    /** The vector store returned a malformed response (missing/illegal entity id or score, count mismatches). */
    VECTOR_BAD_RESPONSE
}

