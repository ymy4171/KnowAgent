package com.knowagent.security.application.service;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.RefreshTokenStore;
import com.knowagent.security.application.port.out.RoleRepository;
import com.knowagent.security.application.port.out.TenantRepository;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.token.RefreshToken;
import com.knowagent.security.domain.token.RefreshTokenStatus;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Rotates Refresh Tokens and logs sessions out.
 *
 * <p>Rotation is one transaction: the presented token is located by its hash, then
 * the whole family is serialised by locking its root row ({@code id = family_id})
 * with {@code FOR UPDATE}. Under that lock the presented token is re-read, checked,
 * marked {@code CONSUMED} and replaced by a child token in the same family; the
 * fresh Access Token is then signed from the user's current roles and permissions.
 * A consumed token reappearing is a replay - the family's remaining ACTIVE tokens
 * are revoked and a stable {@link RefreshTokenInvalidException} is thrown. Because
 * every refresh, replay and logout of one family takes the same root lock, a replay
 * revocation can always see the whole family, including a successor issued moments
 * ago, and no rotation can slip a new token in between a revocation scan and its
 * commit.
 *
 * <p>{@link RefreshTokenInvalidException} is declared {@code noRollbackFor} so the
 * replay revocation commits even though the request fails; a genuine infrastructure
 * failure still rolls the consume and child insert back together. The child insert
 * runs in a savepoint ({@link RefreshTokenStore#insertChild}) so a unique violation
 * on {@code uq_refresh_tokens_one_child} is recoverable inside the transaction and
 * is mapped to a replay result rather than aborting it.
 *
 * <p>Everything runs before authentication exists, so no {@code TenantContext} is
 * present: the token is found by its global hash (the documented exception) and
 * every subsequent read and write carries an explicit {@code tenant_id}.
 */
@Service
public class RefreshTokenService implements RefreshTokens {

    private static final int MAX_USER_AGENT_LENGTH = 512;
    private static final String REPLAY_REASON = "REPLAY_DETECTED";
    private static final String CONCURRENT_REASON = "CONCURRENT_ROTATION";
    private static final String LOGOUT_REASON = "USER_LOGOUT";

    private final RefreshTokenStore refreshTokenStore;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final RoleRepository roles;
    private final LoginPolicies policies;

    public RefreshTokenService(
            RefreshTokenStore refreshTokenStore,
            UserRepository users,
            TenantRepository tenants,
            RoleRepository roles,
            LoginPolicies policies) {
        this.refreshTokenStore = Objects.requireNonNull(refreshTokenStore, "refreshTokenStore must not be null");
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.roles = Objects.requireNonNull(roles, "roles must not be null");
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
    }

    @Override
    @Transactional(noRollbackFor = RefreshTokenInvalidException.class)
    public LoginResult refresh(RefreshCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Instant now = Instant.now();
        RefreshToken presented = refreshTokenStore.findByTokenHash(
                        RefreshTokenHashes.hash(command.refreshToken()))
                .orElseThrow(RefreshTokenInvalidException::new);

        // Serialise every refresh, replay and logout of one family on its root row
        // (id = family_id). This is the single lock point: a replay and a successor
        // rotation can never run against each other, so a revocation always sees the
        // whole family and cannot miss a just-inserted token.
        if (refreshTokenStore.findFamilyRootForUpdate(presented.tenantId(), presented.familyId()).isEmpty()) {
            throw new RefreshTokenInvalidException();
        }

        // Re-read under the family lock: the status observed here is authoritative,
        // even if it changed between the hash lookup and the lock.
        RefreshToken token = refreshTokenStore.findById(presented.tenantId(), presented.id())
                .orElseThrow(RefreshTokenInvalidException::new);

        switch (token.status()) {
            case CONSUMED -> {
                // A consumed token reappearing is a replay attack: revoke every
                // remaining ACTIVE token in the family (including the successor just
                // issued) and reject without revealing anything about the family.
                revokeFamily(token, REPLAY_REASON);
                throw new RefreshTokenInvalidException();
            }
            case REVOKED, EXPIRED -> throw new RefreshTokenInvalidException();
            case ACTIVE -> {
            }
        }

        if (!token.expiresAt().isAfter(now)) {
            throw new RefreshTokenInvalidException();
        }

        // The account and tenant must still allow authentication before rotating.
        // The shared policy also treats a future login_locked_until as an active
        // lock when the row still says ACTIVE, closing a status/timestamp mismatch.
        User user = users.findById(token.tenantId(), token.userId())
                .orElseThrow(RefreshTokenInvalidException::new);
        if (!AccountAuthenticationPolicy.allowsRefresh(user, now)) {
            throw new RefreshTokenInvalidException();
        }
        tenants.findActiveById(token.tenantId())
                .orElseThrow(RefreshTokenInvalidException::new);

        if (!refreshTokenStore.consume(token, now)) {
            // The ACTIVE guard failed, which can only mean a concurrent rotation won
            // the race: treat it as a replay and reject.
            revokeFamily(token, CONCURRENT_REASON);
            throw new RefreshTokenInvalidException();
        }

        RefreshTokenIssue issued = issueChildToken(token, command, now);
        try {
            // The child insert runs in a savepoint so the unique-constraint failure
            // below does not abort the whole transaction before the revocation runs.
            refreshTokenStore.insertChild(issued.refreshToken());
        } catch (DuplicateKeyException exception) {
            // Only the one-child constraint means this token was rotated before and
            // maps to a replay result; any other unique violation is surfaced.
            if (!RefreshTokenHashes.isOneChildConstraint(exception)) {
                throw exception;
            }
            revokeFamily(token, REPLAY_REASON);
            throw new RefreshTokenInvalidException();
        }

        List<Role> effectiveRoles = roles.findEffectiveByUser(token.tenantId(), token.userId());
        Set<String> roleCodes = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (Role role : effectiveRoles) {
            roleCodes.add(role.code());
            permissions.addAll(role.permissions());
        }
        TenantPrincipal principal = new TenantPrincipal(token.tenantId(), token.userId(), roleCodes, permissions);
        return new LoginResult(principal, permissions, issued.rawValue(), issued.refreshToken().expiresAt());
    }

    @Override
    @Transactional
    public void logout(LogoutCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        RefreshToken token = refreshTokenStore.findByTokenHash(RefreshTokenHashes.hash(command.refreshToken()))
                .orElse(null);
        if (token == null) {
            return; // unknown or already fully revoked: logout stays idempotent
        }
        // Lock the family root (same lock refresh and replay take) so a concurrent
        // rotation cannot slip a new ACTIVE token in between locating the family and
        // revoking it; the transaction holds the lock across both statements.
        if (refreshTokenStore.findFamilyRootForUpdate(token.tenantId(), token.familyId()).isEmpty()) {
            return;
        }
        refreshTokenStore.revokeFamily(token.tenantId(), token.familyId(), Instant.now(), LOGOUT_REASON);
    }

    private void revokeFamily(RefreshToken token, String reason) {
        refreshTokenStore.revokeFamily(token.tenantId(), token.familyId(), Instant.now(), reason);
    }

    private RefreshTokenIssue issueChildToken(RefreshToken parent, RefreshCommand command, Instant now) {
        UUID tokenId = UUID.randomUUID();
        String rawValue = RefreshTokenHashes.generateRaw();
        Instant expiresAt = now.plus(policies.refreshTokenTtl());
        RefreshToken child = new RefreshToken(
                tokenId,
                parent.tenantId(),
                parent.userId(),
                parent.familyId(),           // inherit the family
                parent.id(),                 // parent_token_id points at the consumed token
                RefreshTokenHashes.hash(rawValue),
                RefreshTokenStatus.ACTIVE,
                now, expiresAt,
                null, null, null,
                command.issuedIp(),
                RefreshTokenHashes.truncate(command.userAgent(), MAX_USER_AGENT_LENGTH),
                0L);
        return new RefreshTokenIssue(child, rawValue);
    }

    /**
     * The child Refresh Token together with its one-time raw value. The raw value is
     * deliberately omitted from {@link #toString()} because this result may cross
     * logging and exception-reporting boundaries.
     */
    record RefreshTokenIssue(RefreshToken refreshToken, String rawValue) {

        RefreshTokenIssue {
            Objects.requireNonNull(refreshToken, "refreshToken must not be null");
            Objects.requireNonNull(rawValue, "rawValue must not be null");
        }

        @Override
        public String toString() {
            return "RefreshTokenIssue[refreshTokenId=" + refreshToken.id() + ", rawValue=[REDACTED]]";
        }
    }
}
