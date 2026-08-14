package com.knowagent.security.application.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Login hardening policy. Values come from configuration ({@code auth.login.*} in
 * the web module) so operators can tune failed-attempt thresholds and the
 * temporary lock window without code changes.
 */
public record LoginPolicies(
        int maxFailedAttempts,
        Duration lockDuration,
        Duration refreshTokenTtl) {

    public LoginPolicies {
        if (maxFailedAttempts < 1) {
            throw new IllegalArgumentException("maxFailedAttempts must be at least 1");
        }
        Objects.requireNonNull(lockDuration, "lockDuration must not be null");
        Objects.requireNonNull(refreshTokenTtl, "refreshTokenTtl must not be null");
        if (lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("lockDuration must be positive");
        }
        if (refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()) {
            throw new IllegalArgumentException("refreshTokenTtl must be positive");
        }
    }
}
