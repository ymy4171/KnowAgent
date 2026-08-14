package com.knowagent.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * HTTP request body for {@code POST /api/v1/auth/refresh} and
 * {@code POST /api/v1/auth/logout}.
 *
 * <p>A pure HTTP DTO: it never crosses into the application layer - the controller
 * maps it to a {@code RefreshCommand} or {@code LogoutCommand}. The raw Refresh
 * Token is a one-time secret, so {@link #toString()} redacts it: only its SHA-256
 * hash ever reaches the database or appears in an audit record.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken must not be blank")
        @Size(max = 512, message = "refreshToken must not exceed 512 characters")
        String refreshToken) {

    public RefreshTokenRequest {
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    }

    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=[REDACTED]]";
    }
}
