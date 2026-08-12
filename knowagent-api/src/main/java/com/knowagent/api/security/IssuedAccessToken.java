package com.knowagent.api.security;

import java.time.Instant;
import java.util.Objects;

/**
 * An issued Access Token together with its validity window.
 *
 * <p>Only the value is sensitive: {@link #toString()} and the exception messages
 * built by this phase never include it, so the token cannot leak through logs,
 * responses or stack traces. The value is returned to the caller exactly once.
 */
public record IssuedAccessToken(String value, Instant issuedAt, Instant expiresAt) {

    public IssuedAccessToken {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    @Override
    public String toString() {
        return "IssuedAccessToken[value=[REDACTED], issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt + "]";
    }
}
