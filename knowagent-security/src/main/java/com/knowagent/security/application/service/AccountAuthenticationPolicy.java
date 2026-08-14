package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Central account-state rules shared by password login and token refresh.
 *
 * <p>A future {@code loginLockedUntil} always represents an active temporary
 * lock, even if a damaged or manually edited row still says {@code ACTIVE}.
 * Password login may recover a {@code LOCKED} account after its temporary window
 * expires; refresh remains stricter and requires {@code ACTIVE}, forcing a fresh
 * password login to normalize an expired locked account first.
 */
final class AccountAuthenticationPolicy {

    private AccountAuthenticationPolicy() {
    }

    static void requireLoginAllowed(User user, Instant now) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (hasActiveTemporaryLock(user, now)) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "The account is temporarily locked.");
        }
        if (user.status() == UserStatus.LOCKED && user.loginLockedUntil() == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "The account is locked.");
        }
        if (user.status() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "The account is disabled.");
        }
    }

    static boolean allowsRefresh(User user, Instant now) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return user.status() == UserStatus.ACTIVE && !hasActiveTemporaryLock(user, now);
    }

    private static boolean hasActiveTemporaryLock(User user, Instant now) {
        return user.loginLockedUntil() != null && user.loginLockedUntil().isAfter(now);
    }
}
