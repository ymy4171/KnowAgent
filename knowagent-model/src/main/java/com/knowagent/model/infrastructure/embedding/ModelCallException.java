package com.knowagent.model.infrastructure.embedding;

import com.knowagent.common.error.ErrorCode;

import java.util.Objects;

/**
 * Internal carrier for a provider HTTP error. Deliberately holds only the HTTP status
 * number - never the response body, status text or any header - so an exception message
 * can never leak a provider secret (Rule 10 / prompt: no raw vendor body on errors). It
 * is converted to a stable {@link ErrorCode} by the gateway before it leaves the
 * adapter; it is never exposed on a public boundary.
 */
final class ModelCallException extends RuntimeException {

    enum Kind {
        /** HTTP 401/403 - credentials rejected; not retryable. */
        AUTH,
        /** HTTP 429 - throttled; retryable. */
        RATE_LIMITED,
        /** HTTP 5xx - provider-side failure; retryable. */
        TRANSIENT_SERVICE,
        /** Transport-level connect/read timeout; retryable. */
        TIMEOUT,
        /** Other HTTP 4xx - the request or configuration is wrong; not retryable. */
        CLIENT_CONFIG
    }

    private final Kind kind;
    private final int status;

    ModelCallException(Kind kind, int status) {
        super("Model provider returned HTTP status " + status + ".");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.status = status;
    }

    Kind kind() {
        return kind;
    }

    int status() {
        return status;
    }

    boolean retryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.TRANSIENT_SERVICE || kind == Kind.TIMEOUT;
    }

    ErrorCode toErrorCode() {
        return switch (kind) {
            case AUTH -> ErrorCode.MODEL_AUTH_FAILED;
            case RATE_LIMITED -> ErrorCode.MODEL_RATE_LIMITED;
            case TRANSIENT_SERVICE -> ErrorCode.MODEL_SERVICE_ERROR;
            case TIMEOUT -> ErrorCode.MODEL_TIMEOUT;
            case CLIENT_CONFIG -> ErrorCode.MODEL_CONFIGURATION_ERROR;
        };
    }
}
