package com.knowagent.api.error;

import java.util.Objects;

/**
 * The API's uniform JSON error body: {@code errorCode} + {@code message}.
 *
 * <p>This is the same shape the security entry point and access-denied handler
 * produce, so every error the API returns - whether from Spring Security or from a
 * {@link com.knowagent.common.error.BusinessException} - is a consistent JSON body.
 * Error codes are stable identifiers callers can match on; messages are
 * human-readable and never contain credentials or tokens.
 */
public record ApiErrorResponse(String errorCode, String message) {

    public ApiErrorResponse {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
