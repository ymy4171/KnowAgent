package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.RefreshTokenStore;
import com.knowagent.security.application.port.out.RoleRepository;
import com.knowagent.security.application.port.out.TenantRepository;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.role.RoleStatus;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.tenant.TenantStatus;
import com.knowagent.security.domain.token.RefreshToken;
import com.knowagent.security.domain.token.RefreshTokenStatus;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserPage;
import com.knowagent.security.domain.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class RefreshTokenServiceTest {

    private static final LoginPolicies POLICIES =
            new LoginPolicies(3, Duration.ofMinutes(15), Duration.ofDays(30));

    private static final String REPLAY_REASON = "REPLAY_DETECTED";
    private static final String CONCURRENT_REASON = "CONCURRENT_ROTATION";
    private static final String LOGOUT_REASON = "USER_LOGOUT";

    @Test
    void refreshConsumesThePresentedTokenAndIssuesASuccessorInTheSameFamily() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        String originalRaw = fakes.rawToken();
        InetAddress ip = InetAddress.getLoopbackAddress();
        RefreshTokens service = fakes.service();

        LoginResult result = service.refresh(new RefreshCommand(originalRaw, ip, "test-agent"));

        assertThat(result.principal().tenantId()).isEqualTo(fakes.tenantId());
        assertThat(result.principal().userId()).isEqualTo(fakes.userId());
        assertThat(result.principal().roles()).containsExactly("ADMIN");
        assertThat(result.permissions()).contains("USER_READ", "TENANT_WRITE");
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo(originalRaw);
        assertThat(result.refreshTokenExpiresAt())
                .isAfter(Instant.now().minus(Duration.ofMinutes(1)));

        List<RefreshToken> all = fakes.allTokens();
        RefreshToken original = all.stream()
                .filter(token -> token.tokenHash().equals(RefreshTokenHashes.hash(originalRaw)))
                .findFirst().orElseThrow();
        assertThat(original.status()).isEqualTo(RefreshTokenStatus.CONSUMED);
        assertThat(original.consumedAt()).isNotNull();

        // Exactly one child was issued: same family, parent pointing at the
        // consumed token, and only the SHA-256 hash of the new raw value stored.
        List<RefreshToken> children = all.stream()
                .filter(token -> token.parentTokenId() != null)
                .collect(Collectors.toList());
        assertThat(children).hasSize(1);
        RefreshToken child = children.getFirst();
        assertThat(child.familyId()).isEqualTo(original.familyId());
        assertThat(child.parentTokenId()).isEqualTo(original.id());
        assertThat(child.status()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(child.tokenHash()).isEqualTo(RefreshTokenHashes.hash(result.refreshToken()));
        assertThat(child.issuedIp()).isEqualTo(ip);
    }

    @Test
    void childUserAgentIsTruncatedToTheColumnLimit() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        RefreshTokens service = fakes.service();

        String longAgent = "x".repeat(2000);
        service.refresh(new RefreshCommand(fakes.rawToken(), null, longAgent));

        RefreshToken child = fakes.allTokens().stream()
                .filter(token -> token.parentTokenId() != null)
                .findFirst().orElseThrow();
        assertThat(child.userAgent()).hasSize(512);
    }

    @Test
    void replayingAConsumedTokenRevokesTheSuccessorAndRejects() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        String originalRaw = fakes.rawToken();
        RefreshTokens service = fakes.service();
        service.refresh(new RefreshCommand(originalRaw, null, "test-agent"));
        RefreshToken child = fakes.allTokens().stream()
                .filter(token -> token.parentTokenId() != null)
                .findFirst().orElseThrow();

        // The old token reappearing is a replay: the whole family dies, including
        // the successor just issued, and the error is the stable 401.
        assertThatThrownBy(() -> service.refresh(new RefreshCommand(originalRaw, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        RefreshToken revokedChild = fakes.allTokens().stream()
                .filter(token -> token.parentTokenId() != null)
                .findFirst().orElseThrow();
        assertThat(revokedChild.status()).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(revokedChild.revokedAt()).isNotNull();
        assertThat(revokedChild.revokeReason()).isEqualTo(REPLAY_REASON);
        assertThat(fakes.revocations()).singleElement()
                .extracting(FamilyRevocation::reason)
                .isEqualTo(REPLAY_REASON);
    }

    @Test
    void concurrentRotationThatLosesTheCompareAndSetRevokesTheFamilyAndRejects() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        fakes.failNextConsume = true;
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        // The loser's token is still ACTIVE in the snapshot, so the defensive
        // revocation also retires it - nothing usable survives the race.
        RefreshToken original = fakes.allTokens().getFirst();
        assertThat(original.status()).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(original.revokeReason()).isEqualTo(CONCURRENT_REASON);
        assertThat(fakes.revocations()).singleElement()
                .extracting(FamilyRevocation::reason)
                .isEqualTo(CONCURRENT_REASON);
    }

    @Test
    void expiredTokenIsRejected() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        fakes.replaceToken(fakes.original(), expired(fakes.original()));
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(fakes.revocations()).isEmpty();
        assertThat(fakes.allTokens().getFirst().status()).isEqualTo(RefreshTokenStatus.ACTIVE);
    }

    @Test
    void revokedTokenIsRejected() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        fakes.replaceToken(fakes.original(), withStatus(fakes.original(), RefreshTokenStatus.REVOKED,
                Instant.now().minus(Duration.ofHours(1)), null));
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void randomUnknownTokenIsRejectedWithAStableMessage() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        RefreshTokens service = fakes.service();
        String unknown = RefreshTokenHashes.generateRaw();

        Throwable thrown = catchThrowable(() -> service.refresh(new RefreshCommand(unknown, null, null)));
        assertThat(thrown).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(thrown)
                .isInstanceOf(RefreshTokenInvalidException.class)
                .hasMessage("Invalid or expired refresh token.");
        assertThat(fakes.revocations()).isEmpty();
    }

    @Test
    void disabledUserCannotRefresh() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        fakes.disableUser();
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(fakes.allTokens().getFirst().status()).isEqualTo(RefreshTokenStatus.ACTIVE);
    }

    @Test
    void permanentlyLockedUserCannotRefresh() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        fakes.lockUser(null); // no lock window: a permanent lock
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        // Rejected before the token is touched: it stays ACTIVE and usable after an
        // account-level problem is resolved.
        assertThat(fakes.allTokens().getFirst().status()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(fakes.revocations()).isEmpty();
    }

    @Test
    void temporarilyLockedUserCannotRefreshWhileTheWindowIsOpen() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        fakes.lockUser(Instant.now().plus(Duration.ofMinutes(15)));
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(fakes.allTokens().getFirst().status()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(fakes.revocations()).isEmpty();
    }

    @Test
    void activeStatusWithATemporaryLockWindowCannotRefresh() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        // Account-state checks must not trust status alone: login already rejects
        // this transitional/damaged row shape, and refresh must apply the same lock.
        fakes.replaceUser(UserStatus.ACTIVE, Instant.now().plus(Duration.ofMinutes(15)));
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(fakes.allTokens().getFirst().status()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(fakes.revocations()).isEmpty();
    }

    @Test
    void inactiveOrMissingTenantCannotRefresh() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        fakes.tenantInactive = true;
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(fakes.allTokens().getFirst().status()).isEqualTo(RefreshTokenStatus.ACTIVE);
    }

    @Test
    void uniqueChildConflictIsConvertedIntoTheReplayRejection() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        fakes.failNextChildInsert = true;
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        // The parent was consumed before the conflict; the family is then revoked,
        // so the database constraint error never escapes as a stack trace.
        assertThat(fakes.allTokens().getFirst().status()).isEqualTo(RefreshTokenStatus.CONSUMED);
        assertThat(fakes.revocations()).singleElement()
                .extracting(FamilyRevocation::reason)
                .isEqualTo(REPLAY_REASON);
    }

    @Test
    void aSiblingChildConflictIsConvertedToTheReplayRejection() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        RefreshTokens service = fakes.service();

        // Seed an inconsistent family: the ACTIVE root already has a child, so the
        // one-child constraint would fire when a successor is inserted. The service
        // must not leak the database error; it revokes the family and returns 401.
        RefreshToken root = fakes.original();
        fakes.insertChild(new RefreshToken(UUID.randomUUID(), root.tenantId(), root.userId(),
                root.familyId(), root.id(), RefreshTokenHashes.hash(RefreshTokenHashes.generateRaw()),
                RefreshTokenStatus.ACTIVE, Instant.now(), Instant.now().plus(Duration.ofDays(1)),
                null, null, null, null, "seed", 0L));

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(fakes.revocations()).singleElement()
                .extracting(FamilyRevocation::reason)
                .isEqualTo(REPLAY_REASON);
    }

    @Test
    void anUnrelatedUniqueViolationIsNotSwallowedAsAReplay() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        // Force a unique violation on a constraint that is NOT the one-child rule;
        // only that rule maps to a replay result, any other violation is surfaced.
        fakes.failNextChildInsert = true;
        fakes.nextChildInsertConstraint = "uq_refresh_tokens_hash";
        RefreshTokens service = fakes.service();

        assertThatThrownBy(() -> service.refresh(new RefreshCommand(fakes.rawToken(), null, null)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(fakes.revocations()).isEmpty();
    }

    @Test
    void refreshAndLogoutAcquireTheFamilyRootLock() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        RefreshTokens service = fakes.service();

        LoginResult rotated = service.refresh(new RefreshCommand(fakes.rawToken(), null, "test-agent"));
        // One family lock for the rotation (root row of the presented token's family).
        assertThat(fakes.familyLockCalls()).isEqualTo(1);

        service.logout(new LogoutCommand(rotated.refreshToken()));
        // The logout takes the same family lock so a concurrent rotation cannot slip
        // a new ACTIVE token past the revocation.
        assertThat(fakes.familyLockCalls()).isEqualTo(2);
    }

    @Test
    void logoutRevokesTheWholeFamilyAndStaysIdempotent() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        RefreshTokens service = fakes.service();
        LoginResult rotated = service.refresh(new RefreshCommand(fakes.rawToken(), null, "test-agent"));
        String successorRaw = rotated.refreshToken();

        service.logout(new LogoutCommand(successorRaw));

        RefreshToken child = fakes.allTokens().stream()
                .filter(token -> token.parentTokenId() != null)
                .findFirst().orElseThrow();
        assertThat(child.status()).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(child.revokeReason()).isEqualTo(LOGOUT_REASON);
        assertThat(fakes.revocations()).singleElement()
                .extracting(FamilyRevocation::reason)
                .isEqualTo(LOGOUT_REASON);

        // Repeating the logout with the same token is idempotent: it still locates
        // the family by hash and re-revokes it, but zero rows change and no session
        // state is revealed. Exactly one token stays REVOKED.
        service.logout(new LogoutCommand(successorRaw));
        service.logout(new LogoutCommand(RefreshTokenHashes.generateRaw()));
        assertThat(fakes.allTokens())
                .filteredOn(token -> token.status() == RefreshTokenStatus.REVOKED)
                .singleElement()
                .extracting(RefreshToken::id)
                .isEqualTo(child.id());
    }

    @Test
    void logoutWithAPresentedRootTokenRevokesTheFamilyItStarted() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        RefreshTokens service = fakes.service();
        service.refresh(new RefreshCommand(fakes.rawToken(), null, "test-agent"));

        // Logging out with the consumed root still locates the family by hash and
        // revokes whatever is still active - here the successor.
        service.logout(new LogoutCommand(fakes.rawToken()));

        assertThat(fakes.allTokens().stream()
                .filter(token -> token.status() == RefreshTokenStatus.ACTIVE))
                .isEmpty();
        assertThat(fakes.revocations()).singleElement()
                .extracting(FamilyRevocation::reason)
                .isEqualTo(LOGOUT_REASON);
    }

    @Test
    void rawTokenIsNeverPersistedAndEverySecretRedacted() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        String originalRaw = fakes.rawToken();
        RefreshTokens service = fakes.service();
        LoginResult result = service.refresh(new RefreshCommand(originalRaw, null, "test-agent"));

        // Only SHA-256 hashes reach the store; the raw values never appear.
        for (RefreshToken stored : fakes.allTokens()) {
            assertThat(stored.tokenHash()).doesNotContain(originalRaw, result.refreshToken());
            assertThat(stored.toString()).doesNotContain(stored.tokenHash());
        }

        RefreshCommand command = new RefreshCommand(originalRaw, null, "test-agent");
        assertThat(command.toString())
                .doesNotContain(originalRaw)
                .contains("[REDACTED]");

        LogoutCommand logout = new LogoutCommand(originalRaw);
        assertThat(logout.toString()).doesNotContain(originalRaw).contains("[REDACTED]");

        assertThat(result.toString()).doesNotContain(result.refreshToken()).contains("[REDACTED]");
    }

    @Test
    void refreshTokenIssueRedactsTheRawValue() {
        Fakes fakes = Fakes.withAdminAndActiveRefreshToken();
        String secret = "super-secret-raw-token";
        RefreshTokenService.RefreshTokenIssue issue =
                new RefreshTokenService.RefreshTokenIssue(fakes.original(), secret);

        assertThat(issue.toString())
                .contains("[REDACTED]")
                .doesNotContain(secret);
    }

    private static RefreshToken withStatus(RefreshToken token, RefreshTokenStatus status,
                                           Instant consumedAt, Instant revokedAt) {
        return new RefreshToken(token.id(), token.tenantId(), token.userId(), token.familyId(),
                token.parentTokenId(), token.tokenHash(), status,
                token.issuedAt(), token.expiresAt(), consumedAt, revokedAt, token.revokeReason(),
                token.issuedIp(), token.userAgent(), token.version() + 1);
    }

    private static RefreshToken withRevokeReason(RefreshToken token, String reason) {
        return new RefreshToken(token.id(), token.tenantId(), token.userId(), token.familyId(),
                token.parentTokenId(), token.tokenHash(), token.status(),
                token.issuedAt(), token.expiresAt(), token.consumedAt(), token.revokedAt(), reason,
                token.issuedIp(), token.userAgent(), token.version());
    }

    /** A status-ACTIVE token whose lifetime has already passed. */
    private static RefreshToken expired(RefreshToken token) {
        Instant issued = Instant.now().minus(Duration.ofDays(2));
        return new RefreshToken(token.id(), token.tenantId(), token.userId(), token.familyId(),
                token.parentTokenId(), token.tokenHash(), RefreshTokenStatus.ACTIVE,
                issued, issued.plus(Duration.ofDays(1)), null, null, null,
                token.issuedIp(), token.userAgent(), token.version() + 1);
    }

    /**
     * In-memory doubles with the same scoping semantics as the database adapters.
     * The refresh-token methods honour the ACTIVE guards, and the compare-and-set
     * failure / child-insert conflict are injectable via flags.
     */
    private static final class Fakes implements RefreshTokenStore, TenantRepository, RoleRepository {

        private final List<Tenant> tenants = new ArrayList<>();
        private final List<User> users = new ArrayList<>();
        private final Map<String, List<Role>> rolesByTenantUser = new HashMap<>();
        private final List<RefreshToken> refreshTokens = new ArrayList<>();
        private final List<FamilyRevocation> revocations = new ArrayList<>();
        private String rawToken;
        private boolean failNextConsume;
        private boolean failNextChildInsert;
        private String nextChildInsertConstraint = "uq_refresh_tokens_one_child";
        private boolean tenantInactive;
        private int familyLockCalls;

        static Fakes withAdminAndActiveRefreshToken() {
            Fakes fakes = new Fakes();
            fakes.addTenantAndAdmin();
            fakes.seedActiveRootToken();
            return fakes;
        }

        private void addTenantAndAdmin() {
            Instant now = Instant.now();
            Tenant tenant = new Tenant(TenantId.of(UUID.randomUUID()), "acme", "acme", TenantStatus.ACTIVE,
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                    0L, now, now, null);
            tenants.add(tenant);
            User user = new User(UUID.randomUUID(), tenant.id(), null, "admin@acme.test", "Admin User",
                    null, null, null, "not-used", UserStatus.ACTIVE,
                    0, null, null, null, 0L, now, now, null);
            users.add(user);
            Role role = new Role(UUID.randomUUID(), tenant.id(), "ADMIN", "Administrator", null,
                    Set.of("USER_READ", "TENANT_WRITE"), true, RoleStatus.ACTIVE, 0L, now, now, null);
            rolesByTenantUser.computeIfAbsent(key(tenant.id(), user.id()), k -> new ArrayList<>()).add(role);
        }

        private void seedActiveRootToken() {
            Instant now = Instant.now();
            UUID id = UUID.randomUUID();
            String raw = RefreshTokenHashes.generateRaw();
            refreshTokens.add(new RefreshToken(id, tenantId(), userId(), id, null,
                    RefreshTokenHashes.hash(raw), RefreshTokenStatus.ACTIVE,
                    now, now.plus(POLICIES.refreshTokenTtl()),
                    null, null, null, null, "seed-agent", 0L));
            this.rawToken = raw;
        }

        RefreshTokens service() {
            return new RefreshTokenService(this, new UserRepositoryFake(), this, this, POLICIES);
        }

        TenantId tenantId() {
            return tenants.getFirst().id();
        }

        UUID userId() {
            return users.getFirst().id();
        }

        RefreshToken original() {
            return refreshTokens.getFirst();
        }

        String rawToken() {
            return rawToken;
        }

        List<RefreshToken> allTokens() {
            return List.copyOf(refreshTokens);
        }

        List<FamilyRevocation> revocations() {
            return List.copyOf(revocations);
        }

        void replaceToken(RefreshToken oldToken, RefreshToken newToken) {
            for (int index = 0; index < refreshTokens.size(); index++) {
                if (refreshTokens.get(index).id().equals(oldToken.id())) {
                    refreshTokens.set(index, newToken);
                    return;
                }
            }
            refreshTokens.add(newToken);
        }

        void disableUser() {
            replaceUser(UserStatus.DISABLED, users.getFirst().loginLockedUntil());
        }

        /** Marks the admin locked; a {@code null} window means a permanent lock. */
        void lockUser(Instant lockedUntil) {
            replaceUser(UserStatus.LOCKED, lockedUntil);
        }

        private void replaceUser(UserStatus status, Instant lockedUntil) {
            User user = users.getFirst();
            users.set(0, new User(user.id(), user.tenantId(), user.departmentId(), user.loginName(),
                    user.displayName(), user.email(), user.phoneNumber(), user.avatarObjectKey(),
                    user.passwordHash(), status, user.loginFailedCount(),
                    user.lastFailedLoginAt(), lockedUntil, user.lastLoginAt(),
                    user.version(), user.createdAt(), user.updatedAt(), user.deletedAt()));
        }

        int familyLockCalls() {
            return familyLockCalls;
        }

        // RefreshTokenStore
        @Override
        public Optional<RefreshToken> findByTokenHash(String tokenHash) {
            return refreshTokens.stream().filter(token -> token.tokenHash().equals(tokenHash)).findFirst();
        }

        @Override
        public Optional<RefreshToken> findById(TenantId tenantId, UUID tokenId) {
            return refreshTokens.stream()
                    .filter(token -> token.tenantId().equals(tenantId) && token.id().equals(tokenId))
                    .findFirst();
        }

        @Override
        public Optional<RefreshToken> findFamilyRootForUpdate(TenantId tenantId, UUID familyId) {
            familyLockCalls++;
            return refreshTokens.stream()
                    .filter(token -> token.tenantId().equals(tenantId)
                            && token.familyId().equals(familyId)
                            && token.familyId().equals(token.id()))
                    .findFirst();
        }

        @Override
        public void insert(RefreshToken token) {
            refreshTokens.add(token);
        }

        @Override
        public void insertChild(RefreshToken token) {
            if (token.parentTokenId() == null) {
                throw new IllegalArgumentException("insertChild only accepts child tokens");
            }
            boolean siblingExists = refreshTokens.stream().anyMatch(existing ->
                    existing.tenantId().equals(token.tenantId())
                            && token.parentTokenId().equals(existing.parentTokenId()));
            if (failNextChildInsert || siblingExists) {
                throw new DuplicateKeyException(
                        "refresh_tokens duplicate key value violates unique constraint \""
                                + nextChildInsertConstraint + "\"");
            }
            refreshTokens.add(token);
        }

        @Override
        public boolean consume(RefreshToken token, Instant consumedAt) {
            if (failNextConsume) {
                return false;
            }
            for (int index = 0; index < refreshTokens.size(); index++) {
                RefreshToken current = refreshTokens.get(index);
                if (!current.tenantId().equals(token.tenantId()) || !current.id().equals(token.id())) {
                    continue;
                }
                if (current.status() != RefreshTokenStatus.ACTIVE) {
                    return false;
                }
                refreshTokens.set(index, withStatus(current, RefreshTokenStatus.CONSUMED, consumedAt, null));
                return true;
            }
            return false;
        }

        @Override
        public int revokeFamily(TenantId tenantId, UUID familyId, Instant revokedAt, String reason) {
            revocations.add(new FamilyRevocation(tenantId, familyId, reason));
            int count = 0;
            for (int index = 0; index < refreshTokens.size(); index++) {
                RefreshToken current = refreshTokens.get(index);
                if (!current.tenantId().equals(tenantId)
                        || !current.familyId().equals(familyId)
                        || current.status() != RefreshTokenStatus.ACTIVE) {
                    continue;
                }
                refreshTokens.set(index, withRevokeReason(
                        withStatus(current, RefreshTokenStatus.REVOKED, null, revokedAt), reason));
                count++;
            }
            return count;
        }

        // TenantRepository
        @Override
        public Optional<Tenant> findActiveBySlug(String slug) {
            return tenants.stream()
                    .filter(tenant -> tenant.slug().equals(slug)
                            && tenant.status() == TenantStatus.ACTIVE
                            && tenant.deletedAt() == null)
                    .findFirst();
        }

        @Override
        public Optional<Tenant> findActiveById(TenantId tenantId) {
            if (tenantInactive) {
                return Optional.empty();
            }
            return tenants.stream()
                    .filter(tenant -> tenant.id().equals(tenantId)
                            && tenant.status() == TenantStatus.ACTIVE
                            && tenant.deletedAt() == null)
                    .findFirst();
        }

        /**
         * UserRepository view over the same in-memory user state. A separate class is
         * required because {@link UserRepository#findById} and
         * {@link RefreshTokenStore#findById} share a signature but differ in return
         * type, so one class cannot implement both interfaces.
         */
        private final class UserRepositoryFake implements UserRepository {
            @Override
            public Optional<User> findByTenantAndLoginName(TenantId tenantId, String loginName) {
                return Optional.empty();
            }

            @Override
            public Optional<User> findById(TenantId tenantId, UUID userId) {
                return users.stream()
                        .filter(user -> user.tenantId().equals(tenantId)
                                && user.id().equals(userId)
                                && user.deletedAt() == null)
                        .findFirst();
            }

            @Override
            public boolean updateLoginState(User user) {
                return false;
            }

            @Override
            public int recordLoginFailure(TenantId tenantId, UUID userId, Instant now,
                                          int maxFailedAttempts, Instant lockUntil) {
                return 0;
            }

            @Override
            public UserPage search(TenantId tenantId, String keywordPattern, UserStatus status,
                                   int page, int size) {
                // Not exercised by the refresh-token service; present only to satisfy
                // the port contract.
                throw new UnsupportedOperationException("search is not used by RefreshTokenService");
            }
        }

        // RoleRepository
        @Override
        public List<Role> findEffectiveByUser(TenantId tenantId, UUID userId) {
            return List.copyOf(rolesByTenantUser.getOrDefault(key(tenantId, userId), List.of()));
        }

        private static String key(TenantId tenantId, UUID userId) {
            return tenantId.value() + ":" + userId;
        }
    }

    /** Records that {@code revokeFamily} was called and with which stable reason. */
    private record FamilyRevocation(TenantId tenantId, UUID familyId, String reason) {
    }
}
