package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.RefreshTokenStore;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.token.RefreshToken;
import com.knowagent.security.domain.token.RefreshTokenStatus;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists a successful login in one transaction: the user's advanced login state
 * and the freshly issued Refresh Token commit together or roll back together.
 *
 * <p>This is deliberately a separate transaction service. {@link LoginService} holds
 * no transaction, so a failed login can record its failed count in an independent
 * transaction without nested {@code REQUIRES_NEW}; every login then holds at most
 * one database connection, and concurrent failed logins cannot exhaust the
 * connection pool waiting on a second connection.
 */
@Service
public class LoginSuccessHandler {

    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final UserRepository users;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginPolicies policies;

    public LoginSuccessHandler(UserRepository users, RefreshTokenStore refreshTokenStore, LoginPolicies policies) {
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.refreshTokenStore = Objects.requireNonNull(refreshTokenStore, "refreshTokenStore must not be null");
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
    }

    @Transactional
    public LoginSuccess recordSuccess(User user, LoginCommand command, Instant now) {
        if (!users.updateLoginState(succeed(user, now))) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "The user record changed concurrently; please retry the login.");
        }
        RefreshTokenIssue issued = issueRefreshToken(user.tenantId(), user.id(), command, now);
        refreshTokenStore.insert(issued.refreshToken());
        return new LoginSuccess(issued.refreshToken(), issued.rawValue());
    }

    /**
     * The updated user for a successful login: failed count and lock window are
     * cleared, the status returns to ACTIVE (clearing an expired lock), and
     * {@code lastLoginAt} advances. The version is kept so the repository can guard
     * the update against concurrent modification.
     */
    private static User succeed(User user, Instant now) {
        return new User(
                user.id(), user.tenantId(), user.departmentId(), user.loginName(), user.displayName(),
                user.email(), user.phoneNumber(), user.avatarObjectKey(), user.passwordHash(),
                UserStatus.ACTIVE, 0, null, null, now,
                user.version(), user.createdAt(), now, user.deletedAt());
    }

    private RefreshTokenIssue issueRefreshToken(
            TenantId tenantId, UUID userId, LoginCommand command, Instant now) {
        UUID tokenId = UUID.randomUUID();
        String rawValue = generateRawRefreshToken();
        String tokenHash = sha256Hex(rawValue);
        Instant expiresAt = now.plus(policies.refreshTokenTtl());
        RefreshToken token = new RefreshToken(
                tokenId, tenantId, userId,
                tokenId,                    // root token: familyId == id
                null,                       // root token has no parent
                tokenHash,
                RefreshTokenStatus.ACTIVE,
                now, expiresAt,
                null, null, null,
                command.issuedIp(),
                truncate(command.userAgent(), MAX_USER_AGENT_LENGTH),
                0L);
        return new RefreshTokenIssue(token, rawValue);
    }

    private static String generateRawRefreshToken() {
        byte[] random = new byte[REFRESH_TOKEN_BYTES];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", exception);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * The persisted Refresh Token together with its one-time raw value.
     *
     * <p>The raw value is deliberately omitted from {@link #toString()} because
     * this result may cross logging and exception-reporting boundaries.
     */
    public record LoginSuccess(RefreshToken refreshToken, String rawValue) {

        public LoginSuccess {
            Objects.requireNonNull(refreshToken, "refreshToken must not be null");
            Objects.requireNonNull(rawValue, "rawValue must not be null");
        }

        @Override
        public String toString() {
            return "LoginSuccess[refreshTokenId=" + refreshToken.id() + ", rawValue=[REDACTED]]";
        }
    }

    /** A generated Refresh Token together with its one-time raw value. */
    private record RefreshTokenIssue(RefreshToken refreshToken, String rawValue) {
    }
}
