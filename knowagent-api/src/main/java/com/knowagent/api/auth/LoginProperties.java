package com.knowagent.api.auth;

import com.knowagent.security.application.service.LoginPolicies;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for login hardening, bound from the {@code auth.login.*} properties.
 *
 * <p>Values are tunable at runtime through environment variables
 * ({@code AUTH_LOGIN_MAX_FAILED_ATTEMPTS}, {@code AUTH_LOGIN_LOCK_DURATION},
 * {@code AUTH_LOGIN_REFRESH_TOKEN_TTL}); {@code application.yml} only carries safe
 * defaults. No secrets are involved, so this record's default {@link #toString()}
 * is safe to log.
 *
 * @param maxFailedAttempts consecutive wrong passwords before the account is locked
 * @param lockDuration      how long a triggered lock lasts
 * @param refreshTokenTtl   lifetime of a newly issued refresh token
 */
@ConfigurationProperties(prefix = "auth.login")
public record LoginProperties(
        int maxFailedAttempts,
        Duration lockDuration,
        Duration refreshTokenTtl) {

    public LoginProperties {
        if (maxFailedAttempts < 1) {
            throw new IllegalArgumentException("auth.login.max-failed-attempts must be at least 1");
        }
        Objects.requireNonNull(lockDuration, "auth.login.lock-duration must not be null");
        Objects.requireNonNull(refreshTokenTtl, "auth.login.refresh-token-ttl must not be null");
        if (lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("auth.login.lock-duration must be positive");
        }
        if (refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()) {
            throw new IllegalArgumentException("auth.login.refresh-token-ttl must be positive");
        }
    }

    /** Converts the bound values into the security module's policy value object. */
    public LoginPolicies toPolicies() {
        return new LoginPolicies(maxFailedAttempts, lockDuration, refreshTokenTtl);
    }
}
