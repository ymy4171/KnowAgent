package com.knowagent.api.auth.dto;

import java.util.Objects;

/**
 * HTTP response body for {@code POST /api/v1/auth/login}.
 *
 * <p>Carries only the credentials a client needs: the access token, the one-time
 * raw refresh token and the access token lifetime. Never exposes password hashes,
 * token hashes or internal lock fields. Both tokens are secrets - the access token
 * is returned exactly once and the refresh token is returned exactly once - so
 * {@link #toString()} redacts them to keep them out of logs, responses and
 * exception messages.
 *
 * @param tokenType    token type, always {@code Bearer}
 * @param accessToken  signed JWT access token
 * @param refreshToken one-time raw refresh token (only its SHA-256 hash is stored)
 * @param expiresIn    access token lifetime in seconds
 */
public record LoginResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        long expiresIn) {

    public LoginResponse {
        Objects.requireNonNull(tokenType, "tokenType must not be null");
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
        if (expiresIn <= 0) {
            throw new IllegalArgumentException("expiresIn must be positive");
        }
    }

    @Override
    public String toString() {
        return "LoginResponse[tokenType=" + tokenType
                + ", accessToken=[REDACTED]"
                + ", refreshToken=[REDACTED]"
                + ", expiresIn=" + expiresIn + "]";
    }
}
