package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.PasswordHasher;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginServiceTest {

    private static final String CORRECT_PASSWORD = "correct-password-123";
    private static final String WRONG_PASSWORD = "wrong-password-456";

    @Test
    void successfulLoginReturnsPrincipalPermissionsAndIssuesHashedRefreshToken() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        LoginService service = fakes.service();

        LoginResult result = service.login(new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, "test-agent"));

        assertThat(result.principal().tenantId()).isEqualTo(fakes.tenantA().id());
        assertThat(result.principal().userId()).isEqualTo(fakes.adminA().id());
        assertThat(result.principal().roles()).containsExactly("ADMIN");
        assertThat(result.permissions()).contains("USER_READ", "TENANT_WRITE");
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.refreshTokenExpiresAt()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));

        // The stored token holds the SHA-256 hash of the raw value, never the raw value.
        assertThat(fakes.refreshTokens).hasSize(1);
        RefreshToken stored = fakes.refreshTokens.getFirst();
        assertThat(stored.tokenHash()).isEqualTo(sha256Hex(result.refreshToken()));
        assertThat(stored.tokenHash()).isNotEqualTo(result.refreshToken());
        assertThat(stored.status()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(stored.familyId()).isEqualTo(stored.id());
        assertThat(stored.parentTokenId()).isNull();
        assertThat(stored.issuedIp()).isNull();

        // The stored user's login state advanced and the failed count was cleared.
        User storedUser = fakes.userBy(Optional.of(fakes.adminA().id())).orElseThrow();
        assertThat(storedUser.lastLoginAt()).isNotNull();
        assertThat(storedUser.loginFailedCount()).isZero();
        assertThat(storedUser.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void wrongPasswordIncrementsFailedCountAndReturnsUnifiedInvalidCredentials() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        LoginService service = fakes.service();

        assertThatThrownBy(() -> service.login(
                new LoginCommand("acme", "admin@acme.test", WRONG_PASSWORD, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        User stored = fakes.userBy(Optional.of(fakes.adminA().id())).orElseThrow();
        assertThat(stored.loginFailedCount()).isEqualTo(1);
        assertThat(stored.lastFailedLoginAt()).isNotNull();
        assertThat(stored.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void consecutiveFailuresReachThresholdAndLockTheAccount() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        LoginService service = fakes.service();
        LoginCommand command = new LoginCommand("acme", "admin@acme.test", WRONG_PASSWORD, null, null);

        assertThatThrownBy(() -> service.login(command)).hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> service.login(command)).hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> service.login(command)).hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);

        User stored = fakes.userBy(Optional.of(fakes.adminA().id())).orElseThrow();
        assertThat(stored.loginFailedCount()).isEqualTo(3);
        assertThat(stored.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(stored.loginLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void successfulLoginClearsFailedCountAndExpiredTemporaryLock() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        // 2 earlier failures and an expired temporary lock, but status ACTIVE.
        User previouslyFailed = withLoginState(fakes.adminA(),
                UserStatus.ACTIVE, 2, Instant.now().minusSeconds(120), null);
        fakes.replaceUser(previouslyFailed);
        LoginService service = fakes.service();

        LoginResult result = service.login(
                new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, null));

        assertThat(result.principal().userId()).isEqualTo(fakes.adminA().id());
        User stored = fakes.userBy(Optional.of(fakes.adminA().id())).orElseThrow();
        assertThat(stored.loginFailedCount()).isZero();
        assertThat(stored.loginLockedUntil()).isNull();
        assertThat(stored.lastFailedLoginAt()).isNull();
        assertThat(stored.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(stored.lastLoginAt()).isNotNull();
    }

    @Test
    void disabledUserIsRejectedWithStableError() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        fakes.replaceUser(withStatus(fakes.adminA(), UserStatus.DISABLED));
        LoginService service = fakes.service();

        assertThatThrownBy(() -> service.login(
                new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_DISABLED);
    }

    @Test
    void lockedUserIsRejectedWithStableError() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        fakes.replaceUser(withLoginState(fakes.adminA(), UserStatus.LOCKED, 3,
                Instant.now().plus(Duration.ofMinutes(5)), null));
        LoginService service = fakes.service();

        assertThatThrownBy(() -> service.login(
                new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    void temporarilyLockedUserIsRejectedWithStableError() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        // Status ACTIVE but an active temporary lock window still blocks login.
        fakes.replaceUser(withLoginState(fakes.adminA(), UserStatus.ACTIVE, 0,
                Instant.now().plus(Duration.ofMinutes(5)), null));
        LoginService service = fakes.service();

        assertThatThrownBy(() -> service.login(
                new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    void expiredTemporaryLockProducedByRealFailuresIsRecoverable() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        LoginService service = fakes.service();
        LoginCommand wrong = new LoginCommand("acme", "admin@acme.test", WRONG_PASSWORD, null, null);

        // Real flow: three wrong passwords lock the account through the recorder.
        assertThatThrownBy(() -> service.login(wrong))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> service.login(wrong))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> service.login(wrong))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
        User locked = fakes.userBy(Optional.of(fakes.adminA().id())).orElseThrow();
        assertThat(locked.status()).isEqualTo(UserStatus.LOCKED);

        // The lock window elapses: only the timestamp moves; the status stays LOCKED
        // exactly as the recorder left it - the state the earlier tests never covered.
        fakes.replaceUser(withLoginState(locked, UserStatus.LOCKED, locked.loginFailedCount(),
                Instant.now().minusSeconds(30), locked.lastFailedLoginAt()));

        LoginResult result = service.login(
                new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, null));
        assertThat(result.principal().userId()).isEqualTo(fakes.adminA().id());

        User recovered = fakes.userBy(Optional.of(fakes.adminA().id())).orElseThrow();
        assertThat(recovered.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(recovered.loginFailedCount()).isZero();
        assertThat(recovered.loginLockedUntil()).isNull();
        assertThat(recovered.lastLoginAt()).isNotNull();
    }

    @Test
    void wrongPasswordAfterExpiredLockIsANormalFailureAndReLocks() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        // LOCKED status whose window has already passed: retryable, not a lock
        // rejection - a wrong password is a normal failure that re-locks.
        fakes.replaceUser(withLoginState(fakes.adminA(), UserStatus.LOCKED, 3,
                Instant.now().minusSeconds(30), Instant.now().minusSeconds(30)));
        LoginService service = fakes.service();

        assertThatThrownBy(() -> service.login(
                new LoginCommand("acme", "admin@acme.test", WRONG_PASSWORD, null, null)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);

        User stored = fakes.userBy(Optional.of(fakes.adminA().id())).orElseThrow();
        assertThat(stored.loginFailedCount()).isEqualTo(4);
        assertThat(stored.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(stored.loginLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void unknownTenantAndUnknownUserReturnTheSameUnifiedError() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        LoginService service = fakes.service();

        assertThatThrownBy(() -> service.login(
                new LoginCommand("ghost", "admin@acme.test", CORRECT_PASSWORD, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> service.login(
                new LoginCommand("acme", "ghost@acme.test", CORRECT_PASSWORD, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void unknownTenantStillRunsAUserLookupSoTimingDoesNotRevealTheTenant() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        LoginService service = fakes.service();

        assertThatThrownBy(() -> service.login(
                new LoginCommand("ghost", "admin@acme.test", CORRECT_PASSWORD, null, null)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);

        // The unknown-tenant path still ran a user lookup against the dummy tenant,
        // so the work equals a known-tenant unknown-user attempt: tenant lookup +
        // user lookup + Argon2. Without it the response would be faster and reveal
        // that the tenant does not exist.
        assertThat(fakes.queriedUserTenants()).contains(LoginService.dummyTenantId());
    }

    @Test
    void loginNormalizesSlugAndLoginNameToLowerCase() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        LoginService service = fakes.service();

        LoginResult result = service.login(
                new LoginCommand("  ACME ", "ADMIN@ACME.TEST", CORRECT_PASSWORD, null, null));

        assertThat(result.principal().userId()).isEqualTo(fakes.adminA().id());
        assertThat(result.principal().roles()).containsExactly("ADMIN");
    }

    @Test
    void tenantAUserCannotAuthenticateUnderTenantB() {
        Fakes fakes = Fakes.withTwoTenants();
        LoginService service = fakes.service();

        // Tenant B's user tries tenant A's slug - the user lookup is scoped to A.
        assertThatThrownBy(() -> service.login(
                new LoginCommand("acme", "admin@beta.test", CORRECT_PASSWORD, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        // Tenant A's user under tenant B's slug - same unified error.
        assertThatThrownBy(() -> service.login(
                new LoginCommand("beta", "admin@acme.test", CORRECT_PASSWORD, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void tenantALoginLoadsOnlyTenantARolesAndPermissions() {
        Fakes fakes = Fakes.withTwoTenants();
        LoginService service = fakes.service();

        LoginResult acme = service.login(
                new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, null));
        assertThat(acme.principal().roles()).containsExactly("ADMIN");
        assertThat(acme.permissions()).contains("USER_READ").doesNotContain("BETA_ONLY");

        LoginResult beta = service.login(
                new LoginCommand("beta", "admin@beta.test", CORRECT_PASSWORD, null, null));
        assertThat(beta.principal().roles()).containsExactly("BETA_ADMIN");
        assertThat(beta.permissions()).contains("BETA_ONLY").doesNotContain("USER_READ");
    }

    @Test
    void concurrentUserChangeOnSuccessfulLoginThrowsConflict() {
        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        fakes.failNextLoginStateUpdate = true;
        LoginService service = fakes.service();

        assertThatThrownBy(() -> service.login(
                new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void loginCommandAndResultRedactSecrets() {
        LoginCommand command = new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, null);
        assertThat(command.toString()).doesNotContain(CORRECT_PASSWORD).contains("[REDACTED]");

        Fakes fakes = Fakes.withTenantAndAdmin("acme", CORRECT_PASSWORD);
        LoginResult result = fakes.service().login(
                new LoginCommand("acme", "admin@acme.test", CORRECT_PASSWORD, null, null));
        assertThat(result.toString()).doesNotContain(result.refreshToken()).contains("[REDACTED]");

        RefreshToken storedToken = fakes.refreshTokens.getFirst();
        LoginSuccessHandler.LoginSuccess success =
                new LoginSuccessHandler.LoginSuccess(storedToken, result.refreshToken());
        assertThat(success.toString())
                .doesNotContain(result.refreshToken(), storedToken.tokenHash())
                .contains(storedToken.id().toString(), "rawValue=[REDACTED]");
    }

    private static User withStatus(User user, UserStatus status) {
        return new User(user.id(), user.tenantId(), user.departmentId(), user.loginName(), user.displayName(),
                user.email(), user.phoneNumber(), user.avatarObjectKey(), user.passwordHash(), status,
                user.loginFailedCount(), user.lastFailedLoginAt(), user.loginLockedUntil(), user.lastLoginAt(),
                user.version(), user.createdAt(), user.updatedAt(), user.deletedAt());
    }

    private static User withLoginState(User user, UserStatus status, int failedCount,
                                       Instant loginLockedUntil, Instant lastFailedLoginAt) {
        return new User(user.id(), user.tenantId(), user.departmentId(), user.loginName(), user.displayName(),
                user.email(), user.phoneNumber(), user.avatarObjectKey(), user.passwordHash(), status,
                failedCount, lastFailedLoginAt, loginLockedUntil, user.lastLoginAt(),
                user.version(), user.createdAt(), user.updatedAt(), user.deletedAt());
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", exception);
        }
    }

    /**
     * In-memory doubles with the same scoping semantics as the database adapters.
     * {@code updateLoginState} replaces the stored user and returns
     * {@code false} when {@link #failNextLoginStateUpdate} is set, simulating a
     * version conflict.
     */
    private static final class Fakes implements TenantRepository, RoleRepository,
            RefreshTokenStore, PasswordHasher {

        private final List<Tenant> tenants = new ArrayList<>();
        private final List<User> users = new ArrayList<>();
        private final Map<String, List<Role>> rolesByTenantUser = new HashMap<>();
        private final List<RefreshToken> refreshTokens = new ArrayList<>();
        private final List<TenantId> queriedUserTenants = new ArrayList<>();
        private boolean failNextLoginStateUpdate;

        private static final LoginPolicies POLICIES =
                new LoginPolicies(3, Duration.ofMinutes(15), Duration.ofDays(30));

        static Fakes withTenantAndAdmin(String slug, String password) {
            Fakes fakes = new Fakes();
            fakes.addTenantAndAdmin(slug, "admin@" + slug + ".test", password, Set.of("ADMIN"),
                    Set.of("USER_READ", "TENANT_WRITE"));
            return fakes;
        }

        static Fakes withTwoTenants() {
            Fakes fakes = new Fakes();
            fakes.addTenantAndAdmin("acme", "admin@acme.test", CORRECT_PASSWORD, Set.of("ADMIN"),
                    Set.of("USER_READ", "TENANT_WRITE"));
            fakes.addTenantAndAdmin("beta", "admin@beta.test", CORRECT_PASSWORD, Set.of("BETA_ADMIN"),
                    Set.of("BETA_ONLY"));
            return fakes;
        }

        private void addTenantAndAdmin(String slug, String login, String password,
                                       Set<String> roleCodes, Set<String> permissions) {
            Instant now = Instant.now();
            Tenant tenant = new Tenant(TenantId.of(UUID.randomUUID()), slug, slug, TenantStatus.ACTIVE,
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                    0L, now, now, null);
            tenants.add(tenant);
            User user = new User(UUID.randomUUID(), tenant.id(), null, login, "Admin User",
                    null, null, null, encode(password), UserStatus.ACTIVE,
                    0, null, null, null, 0L, now, now, null);
            users.add(user);
            for (String roleCode : roleCodes) {
                Role role = new Role(UUID.randomUUID(), tenant.id(), roleCode, roleCode, null,
                        permissions, roleCode.equals("ADMIN"), RoleStatus.ACTIVE, 0L, now, now, null);
                rolesByTenantUser.computeIfAbsent(key(tenant.id(), user.id()), k -> new ArrayList<>()).add(role);
            }
        }

        LoginService service() {
            UserRepository users = new UserRepositoryFake();
            // The failure recorder and the success handler share the in-memory
            // UserRepository fake, so a recorded failure or a successful login
            // advances the stored user exactly as in the database.
            return new LoginService(this, users, this, this,
                    new LoginFailureRecorder(users, POLICIES),
                    new LoginSuccessHandler(users, this, POLICIES));
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
                return Fakes.this.findByTenantAndLoginName(tenantId, loginName);
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
                return Fakes.this.updateLoginState(user);
            }

            @Override
            public int recordLoginFailure(TenantId tenantId, UUID userId, Instant now,
                                          int maxFailedAttempts, Instant lockUntil) {
                return Fakes.this.recordLoginFailure(tenantId, userId, now, maxFailedAttempts, lockUntil);
            }

            @Override
            public UserPage search(TenantId tenantId, String keywordPattern, UserStatus status,
                                   int page, int size) {
                // Not exercised by the login service; present only to satisfy the
                // port contract.
                throw new UnsupportedOperationException("search is not used by LoginService");
            }
        }

        Tenant tenantA() {
            return tenants.getFirst();
        }

        User adminA() {
            return users.getFirst();
        }

        void replaceUser(User user) {
            for (int index = 0; index < users.size(); index++) {
                if (users.get(index).id().equals(user.id())) {
                    users.set(index, user);
                    return;
                }
            }
            users.add(user);
        }

        Optional<User> userBy(Optional<UUID> id) {
            return users.stream().filter(user -> id.map(idValue -> user.id().equals(idValue)).orElse(false))
                    .findFirst();
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
            return tenants.stream()
                    .filter(tenant -> tenant.id().equals(tenantId)
                            && tenant.status() == TenantStatus.ACTIVE
                            && tenant.deletedAt() == null)
                    .findFirst();
        }

        // UserRepository
        public Optional<User> findByTenantAndLoginName(TenantId tenantId, String loginName) {
            queriedUserTenants.add(tenantId);
            return users.stream()
                    .filter(user -> user.tenantId().equals(tenantId)
                            && user.loginName().equals(loginName)
                            && user.deletedAt() == null)
                    .findFirst();
        }

        List<TenantId> queriedUserTenants() {
            return List.copyOf(queriedUserTenants);
        }

        public boolean updateLoginState(User user) {
            if (failNextLoginStateUpdate) {
                return false;
            }
            replaceUser(user);
            return true;
        }

        public int recordLoginFailure(TenantId tenantId, UUID userId, Instant now,
                                      int maxFailedAttempts, Instant lockUntil) {
            // Mirrors the database-side atomic increment: reads the current stored
            // count, so a stale caller still lands every recorded failure.
            for (int index = 0; index < users.size(); index++) {
                User current = users.get(index);
                if (!current.tenantId().equals(tenantId) || !current.id().equals(userId)) {
                    continue;
                }
                int failed = current.loginFailedCount() + 1;
                boolean lockNow = failed >= maxFailedAttempts;
                users.set(index, new User(
                        current.id(), current.tenantId(), current.departmentId(), current.loginName(),
                        current.displayName(), current.email(), current.phoneNumber(), current.avatarObjectKey(),
                        current.passwordHash(), lockNow ? UserStatus.LOCKED : current.status(),
                        failed, now, lockNow ? lockUntil : null, current.lastLoginAt(),
                        current.version() + 1, current.createdAt(), now, current.deletedAt()));
                return 1;
            }
            return 0;
        }

        // RoleRepository
        @Override
        public List<Role> findEffectiveByUser(TenantId tenantId, UUID userId) {
            return List.copyOf(rolesByTenantUser.getOrDefault(key(tenantId, userId), List.of()));
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
            refreshTokens.add(token);
        }

        @Override
        public boolean consume(RefreshToken token, Instant consumedAt) {
            // Compare-and-set on status, mirroring the SQL ACTIVE guard.
            for (int index = 0; index < refreshTokens.size(); index++) {
                RefreshToken current = refreshTokens.get(index);
                if (!current.tenantId().equals(token.tenantId()) || !current.id().equals(token.id())) {
                    continue;
                }
                if (current.status() != RefreshTokenStatus.ACTIVE) {
                    return false;
                }
                refreshTokens.set(index, withStatus(current, RefreshTokenStatus.CONSUMED, consumedAt, null, null));
                return true;
            }
            return false;
        }

        @Override
        public int revokeFamily(TenantId tenantId, UUID familyId, Instant revokedAt, String reason) {
            int revoked = 0;
            for (int index = 0; index < refreshTokens.size(); index++) {
                RefreshToken current = refreshTokens.get(index);
                if (!current.tenantId().equals(tenantId)
                        || !current.familyId().equals(familyId)
                        || current.status() != RefreshTokenStatus.ACTIVE) {
                    continue;
                }
                refreshTokens.set(index, withStatus(current, RefreshTokenStatus.REVOKED, null, revokedAt, reason));
                revoked++;
            }
            return revoked;
        }

        private static RefreshToken withStatus(RefreshToken token, RefreshTokenStatus status,
                                               Instant consumedAt, Instant revokedAt, String revokeReason) {
            return new RefreshToken(token.id(), token.tenantId(), token.userId(), token.familyId(),
                    token.parentTokenId(), token.tokenHash(), status,
                    token.issuedAt(), token.expiresAt(), consumedAt, revokedAt, revokeReason,
                    token.issuedIp(), token.userAgent(), token.version() + 1);
        }

        // PasswordHasher
        @Override
        public String encode(CharSequence rawPassword) {
            return "hashed:" + rawPassword.hashCode();
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encode(rawPassword).contentEquals(encodedPassword);
        }

        private static String key(TenantId tenantId, UUID userId) {
            return tenantId.value() + ":" + userId;
        }
    }
}
