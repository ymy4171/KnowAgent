package com.knowagent.security.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserPage;
import com.knowagent.security.domain.user.UserStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    /** Finds a non-deleted user inside one tenant by normalized login name. */
    Optional<User> findByTenantAndLoginName(TenantId tenantId, String loginName);

    /** Finds a non-deleted user inside one tenant by its id. */
    Optional<User> findById(TenantId tenantId, UUID userId);

    /**
     * Persists a user's login state (failed count, lock window, last login) under an
     * optimistic-lock guard. The supplied {@link User} must carry the row's current
     * version; the update applies only when that version still matches, and returns
     * {@code false} when a concurrent modification won the race. The caller then
     * decides whether to retry or fail the operation.
     */
    boolean updateLoginState(User user);

    /**
     * Atomically records one failed login attempt: increments {@code login_failed_count}
     * with a database-side increment (so concurrent attempts never lose a count) and,
     * once {@code maxFailedAttempts} is reached, marks the account {@code LOCKED}
     * with a temporary lock window ending at {@code lockUntil}. Returns the number
     * of rows affected; {@code 0} means the user no longer exists.
     */
    int recordLoginFailure(TenantId tenantId, UUID userId, Instant now,
                           int maxFailedAttempts, Instant lockUntil);

    /**
     * Returns one page of non-deleted users strictly inside {@code tenantId},
     * optionally filtered by fuzzy keyword (matched against login name and display
     * name) and by status. {@code page} is 1-based and {@code size} is the page
     * size; {@link UserPage#total()} counts the same filtered result set the page
     * is sliced from. The tenant id is never derived from the request - the caller
     * passes the tenant from the authenticated principal.
     *
     * @param keywordPattern a pre-escaped {@code LIKE} pattern (or {@code null} to
     *                       match all); the {@code %} / {@code _} / {@code \}
     *                       metacharacters are already escaped by the caller
     */
    UserPage search(TenantId tenantId, String keywordPattern, UserStatus status,
                    int page, int size);
}
