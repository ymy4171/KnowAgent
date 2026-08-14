package com.knowagent.security.application.service;

import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Records a failed login attempt in a plain independent transaction.
 *
 * <p>{@link LoginService} holds no transaction, so this {@code @Transactional}
 * method starts and commits its own transaction and the failed count survives the
 * login method throwing right after. Because it is not nested inside an outer
 * transaction (no {@code REQUIRES_NEW}), a login never holds two database
 * connections at once. The count increments atomically in the database
 * ({@link UserRepository#recordLoginFailure}) so concurrent failed attempts never
 * lose a count and cannot race past the lock threshold.
 */
@Service
public class LoginFailureRecorder {

    private final UserRepository users;
    private final LoginPolicies policies;

    public LoginFailureRecorder(UserRepository users, LoginPolicies policies) {
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
    }

    /**
     * Atomically increments the failed count and, once the configured threshold is
     * reached, sets the account {@code LOCKED} with a temporary lock window. Every
     * recorded attempt lands: the database increments the counter itself, so two
     * concurrent wrong passwords both count.
     */
    @Transactional
    public void recordFailedAttempt(User user, Instant now) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(now, "now must not be null");
        users.recordLoginFailure(
                user.tenantId(),
                user.id(),
                now,
                policies.maxFailedAttempts(),
                now.plus(policies.lockDuration()));
    }
}
