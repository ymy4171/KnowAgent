package com.knowagent.common.error;

public enum ErrorCode {
    VALIDATION_ERROR,
    AUTHENTICATION_REQUIRED,
    ACCESS_DENIED,
    RESOURCE_NOT_FOUND,
    CONFLICT,
    EXTERNAL_SERVICE_ERROR,
    INTERNAL_ERROR,
    /**
     * Unified, non-revealing credential failure. Covers an unknown tenant, an
     * unknown user and a wrong password, so callers cannot distinguish which part
     * of the credentials was wrong.
     */
    INVALID_CREDENTIALS,
    /** The account is permanently disabled and cannot authenticate. */
    ACCOUNT_DISABLED,
    /** The account is locked (status lock or an active temporary lock window). */
    ACCOUNT_LOCKED
}

