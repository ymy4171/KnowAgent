package com.knowagent.security.application.service;

import com.knowagent.security.principal.TenantPrincipal;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Outcome of a successful login.
 *
 * <p>Carries everything the web layer needs to complete the response: the
 * authenticated principal (used to sign the Access Token), the effective
 * permissions, and the newly issued raw Refresh Token. The raw Refresh Token is a
 * one-time secret - {@link #toString()} redacts it and it must never be persisted
 * or logged; only its SHA-256 hash reaches the database.
 */
public record LoginResult(
        TenantPrincipal principal,
        Set<String> permissions,
        String refreshToken,
        Instant refreshTokenExpiresAt) {

    public LoginResult {
        Objects.requireNonNull(principal, "principal must not be null");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions must not be null"));
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
        Objects.requireNonNull(refreshTokenExpiresAt, "refreshTokenExpiresAt must not be null");
        if (!refreshTokenExpiresAt.isAfter(Instant.EPOCH)) {
            throw new IllegalArgumentException("refreshTokenExpiresAt must be after the epoch");
        }
    }

    @Override
    public String toString() {
        return "LoginResult[principal=" + principal
                + ", permissions=" + permissions
                + ", refreshToken=[REDACTED]"
                + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + "]";
    }
}
