package com.knowagent.api.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.api.auth.RefreshAuthenticationService;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.RefreshTokenStore;
import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import com.knowagent.security.application.service.Login;
import com.knowagent.security.application.service.LoginCommand;
import com.knowagent.security.application.service.LoginResult;
import com.knowagent.security.application.service.RefreshCommand;
import com.knowagent.security.application.service.RefreshTokens;
import com.knowagent.security.domain.token.RefreshToken;
import com.knowagent.security.infrastructure.persistence.repository.MyBatisRefreshTokenStore;
import jakarta.servlet.Filter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises Refresh Token rotation and logout against a real PostgreSQL 16 container
 * through the production Spring wiring and HTTP security chain.
 *
 * <p>Runs only under the {@code docker-it} profile (Failsafe). It proves the token
 * family contract from {@code docs/database-schema.md}: a successful refresh consumes
 * the presented ACTIVE token and issues a child in the same family (parent pointing
 * at the consumed token, root satisfying {@code family_id = id}); a consumed token
 * reappearing is a replay that revokes the whole family and returns the stable 401;
 * concurrent refreshes let at most one request succeed; a child refresh racing a root
 * replay or a logout converges to a family with nothing left ACTIVE; expired/revoked/
 * random tokens are all rejected; locked and disabled users cannot refresh; a genuine
 * unique-child conflict is recovered through a savepoint and mapped to a replay
 * result instead of a 500; a genuine store failure rolls the consume and child insert
 * back together; and the raw token is never persisted (only its SHA-256 hash) or
 * echoed anywhere except the one-time response.
 */
@Testcontainers
class RefreshRotationIT {

    private static final String RAW_PASSWORD = "CorrectHorseBatteryStaple1";
    private static final String ISSUER = "https://knowagent.test";
    private static final String AUDIENCE = "knowagent-api";
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(
            "integration-test-only-key-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowagent")
            .withUsername("knowagent")
            .withPassword("integration_only");

    private static ConfigurableApplicationContext context;
    private static MockMvc mockMvc;
    private static DataSource dataSource;

    @BeforeAll
    static void bootContext() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;

        context = new SpringApplicationBuilder(KnowAgentApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(contextArguments());
        mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();

        AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
        bootstrap.initialize(new AdminBootstrapRequest("rot", null, "admin@rot.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("replay", null, "admin@replay.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("concur", null, "admin@concur.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("expiredco", null, "admin@expiredco.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("logoutco", null, "admin@logoutco.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("logoutroot", null, "admin@logoutroot.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("rawco", null, "admin@rawco.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("conflictco", null, "admin@conflictco.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("locku", null, "admin@locku.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("lockstamp", null, "admin@lockstamp.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("disab", null, "admin@disab.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("famreplay", null, "admin@famreplay.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("famlogout", null, "admin@famlogout.test", null, RAW_PASSWORD));
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void refreshSucceedsOnceAndOldTokenBecomesConsumedWithAChildInTheSameFamily() throws Exception {
        String tenantSlug = "rot";
        String loginName = "admin@rot.test";
        String raw = login(tenantSlug, loginName);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        UUID userId = singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);

        MvcResult refresh = refreshRequest(raw);
        assertThat(refresh.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = OBJECT_MAPPER.readTree(refresh.getResponse().getContentAsString());
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        String successor = body.path("refreshToken").asText();
        assertThat(successor).isNotBlank().isNotEqualTo(raw);
        assertThat(body.path("accessToken").asText()).isNotBlank();
        assertThat(body.path("expiresIn").asLong()).isGreaterThan(0);

        // The presented token is consumed; exactly one child was issued in the same
        // family with parent_token_id pointing at the consumed root.
        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NULL""", tenantId, userId))
                .isEqualTo("CONSUMED");
        assertThat(singleInstant("""
                SELECT consumed_at FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NULL""", tenantId, userId))
                .isNotNull();
        assertThat(singleLong("""
                SELECT count(*) FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo(1L);
        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo("ACTIVE");
        assertThat(singleUuid("""
                SELECT parent_token_id FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo(singleUuid("""
                        SELECT id FROM refresh_tokens
                        WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NULL""", tenantId, userId));
        assertThat(singleUuid("""
                SELECT family_id FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo(singleUuid("""
                        SELECT id FROM refresh_tokens
                        WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NULL""", tenantId, userId));
        assertThat(singleLong("""
                SELECT version FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NULL""", tenantId, userId))
                .isEqualTo(1L);

        // Only the SHA-256 hash of the successor is persisted, never the raw value.
        assertThat(singleString("""
                SELECT token_hash FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo(sha256Hex(successor))
                .isNotEqualTo(successor);

        // The token is single-use: the same raw value cannot be redeemed again.
        assertThat(refreshRequest(raw).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void replayingAConsumedTokenRevokesTheSuccessorAndReturnsTheStable401() throws Exception {
        String tenantSlug = "replay";
        String loginName = "admin@replay.test";
        String raw = login(tenantSlug, loginName);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        UUID userId = singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);
        String successor = rotate(raw);

        // The old token reappearing is a replay: 401, and the successor dies with it.
        JsonNode replay = expect401(refreshRequest(raw));
        assertThat(replay.path("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(refreshRequest(raw).getResponse().getContentAsString())
                .doesNotContain(raw, successor);

        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo("REVOKED");
        assertThat(singleString("""
                SELECT revoke_reason FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo("REPLAY_DETECTED");
        // The successor, now revoked, is also unusable.
        assertThat(refreshRequest(successor).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void concurrentRefreshesAllowAtMostOneSuccess() throws Exception {
        String tenantSlug = "concur";
        String loginName = "admin@concur.test";
        String raw = login(tenantSlug, loginName);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        UUID userId = singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        String body = refreshBody(raw);
        for (int index = 0; index < attempts; index++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successes = 0;
        int rejections = 0;
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                successes++;
            } else if (status == 401) {
                rejections++;
            } else {
                throw new AssertionError("unexpected status " + status);
            }
        }
        pool.shutdown();

        // At most one rotation wins; every other request is a stable 401.
        assertThat(successes).isEqualTo(1);
        assertThat(rejections).isEqualTo(attempts - 1);

        // The family converges to fully retired: the consumed root and its revoked
        // successor, with nothing left ACTIVE.
        assertThat(singleLong("""
                SELECT count(*) FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'""", tenantId, userId))
                .isZero();
        assertThat(singleLong("""
                SELECT count(*) FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ?""", tenantId, userId))
                .isEqualTo(2L);
    }

    @Test
    void expiredRevokedAndRandomTokensAreAllRejected() throws Exception {
        // Random / unknown token.
        assertThat(refreshRequest(randomRaw()).getResponse().getStatus()).isEqualTo(401);

        // Revoked token: logout retires the family, after which refresh is rejected.
        String revokedRaw = login("expiredco", "admin@expiredco.test");
        expectNoContent(logoutRequest(revokedRaw));
        expect401(refreshRequest(revokedRaw));

        // Expired token: an ACTIVE token whose lifetime has passed is rejected and
        // left untouched (nothing is consumed or revoked by a failed rotation).
        String expiredRaw = login("expiredco", "admin@expiredco.test");
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "expiredco");
        // Backdate the whole lifetime so expires_at stays after issued_at (the
        // ck_refresh_tokens_expiry CHECK) while the token is already past its end.
        update("""
                UPDATE refresh_tokens
                SET issued_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day'
                WHERE tenant_id = ? AND token_hash = ?""", tenantId, sha256Hex(expiredRaw));

        expect401(refreshRequest(expiredRaw));
        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND token_hash = ?""", tenantId, sha256Hex(expiredRaw)))
                .isEqualTo("ACTIVE");
        assertThat(singleInstant("""
                SELECT revoked_at FROM refresh_tokens
                WHERE tenant_id = ? AND token_hash = ?""", tenantId, sha256Hex(expiredRaw)))
                .isNull();
    }

    @Test
    void logoutRevokesTheWholeFamilyAndIsIdempotent() throws Exception {
        String tenantSlug = "logoutco";
        String loginName = "admin@logoutco.test";
        String raw = login(tenantSlug, loginName);
        String successor = rotate(raw);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        UUID userId = singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);

        expectNoContent(logoutRequest(successor));

        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo("REVOKED");
        assertThat(singleString("""
                SELECT revoke_reason FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo("USER_LOGOUT");
        assertThat(refreshRequest(successor).getResponse().getStatus()).isEqualTo(401);

        // Repeating the logout and logging out with an unknown token are silent no-ops.
        expectNoContent(logoutRequest(successor));
        expectNoContent(logoutRequest(randomRaw()));
    }

    @Test
    void logoutWithTheRootTokenRetiresTheFamilyItStarted() throws Exception {
        String tenantSlug = "logoutroot";
        String loginName = "admin@logoutroot.test";
        String raw = login(tenantSlug, loginName);
        String successor = rotate(raw);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        UUID userId = singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);

        // Logging out with the consumed root still locates the family and revokes
        // whatever is still ACTIVE - here the successor.
        expectNoContent(logoutRequest(raw));

        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND parent_token_id IS NOT NULL""", tenantId, userId))
                .isEqualTo("REVOKED");
        assertThat(refreshRequest(successor).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void rawRefreshTokenNeverAppearsInTheDatabase() throws Exception {
        String tenantSlug = "rawco";
        String loginName = "admin@rawco.test";
        String raw = login(tenantSlug, loginName);
        String successor = rotate(raw);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        UUID userId = singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);

        // Only SHA-256 hashes are stored: the raw values never appear in any column.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT token_hash FROM refresh_tokens
                     WHERE tenant_id = ? AND user_id = ?
                     ORDER BY issued_at""")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> hashes = new ArrayList<>();
                while (resultSet.next()) {
                    hashes.add(resultSet.getString(1));
                }
                assertThat(hashes).hasSize(2);
                assertThat(hashes.get(0)).isEqualTo(sha256Hex(raw)).isNotEqualTo(raw);
                assertThat(hashes.get(1)).isEqualTo(sha256Hex(successor)).isNotEqualTo(successor);
                for (String hash : hashes) {
                    assertThat(hash).doesNotContain(raw, successor);
                }
            }
        }
    }

    @Test
    void transactionFailureRollsBackConsumeAndChildInsertTogether() {
        try (ConfigurableApplicationContext failingContext =
                     new SpringApplicationBuilder(KnowAgentApiApplication.class)
                             .sources(FailingRefreshTokenConfiguration.class)
                             .web(WebApplicationType.SERVLET)
                             .run(contextArguments())) {
            AdminBootstrap bootstrap = failingContext.getBean(AdminBootstrap.class);
            Login login = failingContext.getBean(Login.class);
            RefreshTokens refreshTokens = failingContext.getBean(RefreshTokens.class);
            bootstrap.initialize(new AdminBootstrapRequest(
                    "rollback-refresh", null, "admin@rollback-refresh.test", null, RAW_PASSWORD));
            LoginResult loginResult = login.login(new LoginCommand(
                    "rollback-refresh", "admin@rollback-refresh.test", RAW_PASSWORD, null, "test-agent"));
            String raw = loginResult.refreshToken();
            UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "rollback-refresh");
            UUID userId = singleUuid(
                    "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                    tenantId, "admin@rollback-refresh.test");
            assertThat(singleLong(
                    "SELECT count(*) FROM refresh_tokens WHERE tenant_id = ? AND user_id = ?",
                    tenantId, userId)).isEqualTo(1L);

            // A genuine infrastructure failure while inserting the child (not the
            // handled DuplicateKeyException) must roll the consume and the child
            // insert back together: the root stays ACTIVE and no child exists.
            assertThatThrownBy(() -> refreshTokens.refresh(
                    new RefreshCommand(raw, null, "test-agent")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("injected refresh failure");

            assertThat(singleLong(
                    "SELECT count(*) FROM refresh_tokens WHERE tenant_id = ? AND user_id = ?",
                    tenantId, userId)).isEqualTo(1L);
            assertThat(singleString(
                    "SELECT status FROM refresh_tokens WHERE tenant_id = ? AND user_id = ?",
                    tenantId, userId)).isEqualTo("ACTIVE");
            assertThat(singleInstant(
                    "SELECT consumed_at FROM refresh_tokens WHERE tenant_id = ? AND user_id = ?",
                    tenantId, userId)).isNull();
        }
    }

    @Test
    void accessTokenSigningFailureRollsBackTheWholeRotation() {
        try (ConfigurableApplicationContext failingContext =
                     new SpringApplicationBuilder(KnowAgentApiApplication.class)
                             .sources(FailingJwtEncoderConfiguration.class)
                             .web(WebApplicationType.SERVLET)
                             .run(contextArguments())) {
            AdminBootstrap bootstrap = failingContext.getBean(AdminBootstrap.class);
            Login login = failingContext.getBean(Login.class);
            RefreshAuthenticationService authentication =
                    failingContext.getBean(RefreshAuthenticationService.class);
            bootstrap.initialize(new AdminBootstrapRequest(
                    "rollback-signing", null, "admin@rollback-signing.test", null, RAW_PASSWORD));
            String raw = login.login(new LoginCommand(
                    "rollback-signing", "admin@rollback-signing.test", RAW_PASSWORD, null, "test-agent"))
                    .refreshToken();
            UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "rollback-signing");
            UUID userId = singleUuid(
                    "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                    tenantId, "admin@rollback-signing.test");

            // Rotation writes happen before signing. The API transaction facade must
            // keep them uncommitted until signing succeeds, so this injected encoder
            // failure restores the root and removes the unreturned child.
            assertThatThrownBy(() -> authentication.refresh(new RefreshCommand(raw, null, "test-agent")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("injected access token signing failure");

            assertThat(singleLong(
                    "SELECT count(*) FROM refresh_tokens WHERE tenant_id = ? AND user_id = ?",
                    tenantId, userId)).isEqualTo(1L);
            assertThat(singleString(
                    "SELECT status FROM refresh_tokens WHERE tenant_id = ? AND user_id = ?",
                    tenantId, userId)).isEqualTo("ACTIVE");
            assertThat(singleInstant(
                    "SELECT consumed_at FROM refresh_tokens WHERE tenant_id = ? AND user_id = ?",
                    tenantId, userId)).isNull();
        }
    }

    @Test
    void aUniqueChildConflictIsRecoveredThroughTheSavepointAndRevokesTheFamily() throws Exception {
        String tenantSlug = "conflictco";
        String loginName = "admin@conflictco.test";
        String raw = login(tenantSlug, loginName);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        UUID userId = singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);
        UUID rootId = singleUuid("SELECT id FROM refresh_tokens WHERE tenant_id = ? AND token_hash = ?",
                tenantId, sha256Hex(raw));

        // Seed an inconsistent family through the database: the ACTIVE root already
        // has a child, so rotating it violates uq_refresh_tokens_one_child. The child
        // insert runs in a savepoint, so the constraint error must not abort the whole
        // transaction; the service maps the conflict to a replay result and revokes
        // the family - the response is a stable 401, never a 500.
        String seededHash = sha256Hex(randomRaw());
        update("""
                INSERT INTO refresh_tokens
                    (id, tenant_id, user_id, family_id, parent_token_id, token_hash, status,
                     issued_at, expires_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '30 days', 0)""",
                UUID.randomUUID(), tenantId, userId, rootId, rootId, seededHash);

        JsonNode response = expect401(refreshRequest(raw));
        assertThat(response.path("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");

        // The root was consumed and the seeded sibling revoked; nothing stays ACTIVE
        // and the failed successor insert left no row behind.
        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND id = ?""", tenantId, rootId))
                .isEqualTo("CONSUMED");
        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND token_hash = ?""", tenantId, seededHash))
                .isEqualTo("REVOKED");
        assertThat(singleLong("""
                SELECT count(*) FROM refresh_tokens
                WHERE tenant_id = ? AND family_id = ? AND status = 'ACTIVE'""", tenantId, rootId))
                .isZero();
        assertThat(singleLong("""
                SELECT count(*) FROM refresh_tokens
                WHERE tenant_id = ? AND family_id = ?""", tenantId, rootId))
                .isEqualTo(2L);
    }

    @Test
    void lockedUserCannotRefresh() throws Exception {
        String tenantSlug = "locku";
        String loginName = "admin@locku.test";
        String raw = login(tenantSlug, loginName);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        update("""
                UPDATE users
                SET status = 'LOCKED',
                    login_failed_count = 3,
                    login_locked_until = CURRENT_TIMESTAMP + INTERVAL '15 minutes'
                WHERE tenant_id = ? AND login_name = ?""", tenantId, loginName);

        expect401(refreshRequest(raw));
        // Rejected before the token is touched: still ACTIVE, nothing revoked.
        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND token_hash = ?""", tenantId, sha256Hex(raw)))
                .isEqualTo("ACTIVE");
        assertThat(singleInstant("""
                SELECT revoked_at FROM refresh_tokens
                WHERE tenant_id = ? AND token_hash = ?""", tenantId, sha256Hex(raw)))
                .isNull();
    }

    @Test
    void activeUserWithAFutureLockTimestampCannotRefresh() throws Exception {
        String tenantSlug = "lockstamp";
        String loginName = "admin@lockstamp.test";
        String raw = login(tenantSlug, loginName);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        update("""
                UPDATE users
                SET status = 'ACTIVE',
                    login_locked_until = CURRENT_TIMESTAMP + INTERVAL '15 minutes'
                WHERE tenant_id = ? AND login_name = ?""", tenantId, loginName);

        expect401(refreshRequest(raw));
        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND token_hash = ?""", tenantId, sha256Hex(raw)))
                .isEqualTo("ACTIVE");
    }

    @Test
    void disabledUserCannotRefresh() throws Exception {
        String tenantSlug = "disab";
        String loginName = "admin@disab.test";
        String raw = login(tenantSlug, loginName);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        update("""
                UPDATE users SET status = 'DISABLED'
                WHERE tenant_id = ? AND login_name = ?""", tenantId, loginName);

        expect401(refreshRequest(raw));
        assertThat(singleString("""
                SELECT status FROM refresh_tokens
                WHERE tenant_id = ? AND token_hash = ?""", tenantId, sha256Hex(raw)))
                .isEqualTo("ACTIVE");
    }

    @Test
    void childRefreshConcurrentWithRootReplayLeavesNothingActive() throws Exception {
        String tenantSlug = "famreplay";
        String loginName = "admin@famreplay.test";
        String rootRaw = login(tenantSlug, loginName);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        UUID userId = singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);
        UUID rootId = singleUuid("SELECT id FROM refresh_tokens WHERE tenant_id = ? AND token_hash = ?",
                tenantId, sha256Hex(rootRaw));
        String childRaw = rotate(rootRaw);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            // Even threads rotate the successor, odd threads replay the consumed root:
            // they all contend on the same family-root lock.
            String body = refreshBody(index % 2 == 0 ? childRaw : rootRaw);
            results.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successes = 0;
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                successes++;
            } else if (status != 401) {
                throw new AssertionError("unexpected status " + status);
            }
        }
        pool.shutdown();

        // Only the successor rotation can ever win - and at most once; every replay
        // is a stable 401. Whichever order the family lock admits, a replay that runs
        // after a successful rotation revokes the grandchild too: nothing stays ACTIVE.
        assertThat(successes).isLessThanOrEqualTo(1);
        assertThat(singleLong("""
                SELECT count(*) FROM refresh_tokens
                WHERE tenant_id = ? AND family_id = ? AND status = 'ACTIVE'""", tenantId, rootId))
                .isZero();
    }

    @Test
    void childRefreshConcurrentWithLogoutLeavesNothingActive() throws Exception {
        String tenantSlug = "famlogout";
        String loginName = "admin@famlogout.test";
        String rootRaw = login(tenantSlug, loginName);
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", tenantSlug);
        UUID userId = singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);
        UUID rootId = singleUuid("SELECT id FROM refresh_tokens WHERE tenant_id = ? AND token_hash = ?",
                tenantId, sha256Hex(rootRaw));
        String childRaw = rotate(rootRaw);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            if (index % 2 == 0) {
                String body = refreshBody(childRaw);
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return mockMvc.perform(post("/api/v1/auth/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn().getResponse().getStatus();
                }));
            } else {
                String body = refreshBody(rootRaw);
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return mockMvc.perform(post("/api/v1/auth/logout")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn().getResponse().getStatus();
                }));
            }
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successes = 0;
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                successes++;
            } else if (status != 204 && status != 401) {
                throw new AssertionError("unexpected status " + status);
            }
        }
        pool.shutdown();

        // A rotation can win before the logout revokes the family - but the logout
        // (transactional, on the same family-root lock) then retires the grandchild;
        // a logout that runs first leaves nothing to rotate. Either way the family
        // converges to no ACTIVE token.
        assertThat(successes).isLessThanOrEqualTo(1);
        assertThat(singleLong("""
                SELECT count(*) FROM refresh_tokens
                WHERE tenant_id = ? AND family_id = ? AND status = 'ACTIVE'""", tenantId, rootId))
                .isZero();
    }

    // ---- HTTP helpers ---------------------------------------------------------

    private static String login(String tenantSlug, String loginName) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "tenantSlug", tenantSlug,
                                "loginName", loginName,
                                "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return OBJECT_MAPPER.readTree(login.getResponse().getContentAsString())
                .path("refreshToken").asText();
    }

    private String rotate(String raw) throws Exception {
        MvcResult refresh = refreshRequest(raw);
        assertThat(refresh.getResponse().getStatus()).isEqualTo(200);
        return OBJECT_MAPPER.readTree(refresh.getResponse().getContentAsString())
                .path("refreshToken").asText();
    }

    private MvcResult refreshRequest(String raw) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(raw)))
                .andReturn();
    }

    private MvcResult logoutRequest(String raw) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(raw)))
                .andReturn();
    }

    private static String refreshBody(String raw) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(Map.of("refreshToken", raw));
    }

    private static JsonNode expect401(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static void expectNoContent(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
    }

    private static String randomRaw() {
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", exception);
        }
    }

    // ---- DB helpers ------------------------------------------------------------

    private long singleLong(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String singleString(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID singleUuid(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getObject(1, UUID.class);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Instant singleInstant(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            OffsetDateTime value = resultSet.getObject(1, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void update(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters)) {
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
        return statement;
    }

    private static String[] contextArguments() {
        return new String[]{
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.url=redis://127.0.0.1:1",
                "--server.port=0",
                "--management.server.port=0",
                "--bootstrap.enabled=false",
                "--auth.login.max-failed-attempts=3",
                "--auth.login.lock-duration=15m",
                "--spring.datasource.hikari.maximum-pool-size=24",
                "--jwt.issuer=" + ISSUER,
                "--jwt.audience=" + AUDIENCE,
                "--jwt.secret=" + JWT_SECRET,
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR"};
    }

    /**
     * Marks the decorated store as the primary {@link RefreshTokenStore} so the
     * refresh service uses it, while the real MyBatis adapter stays available as the
     * delegate. Root tokens (the login insert) pass through; a child insert - the
     * rotation's second write, after the consume - throws, so the whole rotation
     * transaction must roll back. Deliberately a plain class, not
     * {@code @Configuration}, so it is only registered when added via
     * {@code .sources(...)}.
     */
    static class FailingRefreshTokenConfiguration {
        @Bean
        @Primary
        RefreshTokenStore failingRefreshTokenStore(MyBatisRefreshTokenStore delegate) {
            return new FailingRefreshTokenStore(delegate);
        }
    }

    /** Injects a post-rotation signing failure to prove the API facade owns the transaction. */
    static class FailingJwtEncoderConfiguration {
        @Bean
        @Primary
        JwtEncoder failingJwtEncoder() {
            return parameters -> {
                throw new RuntimeException("injected access token signing failure");
            };
        }
    }

    private static final class FailingRefreshTokenStore implements RefreshTokenStore {
        private final RefreshTokenStore delegate;

        private FailingRefreshTokenStore(RefreshTokenStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<RefreshToken> findByTokenHash(String tokenHash) {
            return delegate.findByTokenHash(tokenHash);
        }

        @Override
        public java.util.Optional<RefreshToken> findById(TenantId tenantId, UUID tokenId) {
            return delegate.findById(tenantId, tokenId);
        }

        @Override
        public java.util.Optional<RefreshToken> findFamilyRootForUpdate(TenantId tenantId, UUID familyId) {
            return delegate.findFamilyRootForUpdate(tenantId, familyId);
        }

        @Override
        public void insert(RefreshToken token) {
            delegate.insert(token);
        }

        @Override
        public void insertChild(RefreshToken token) {
            if (token.parentTokenId() != null) {
                throw new RuntimeException("injected refresh failure");
            }
            delegate.insertChild(token);
        }

        @Override
        public boolean consume(RefreshToken token, Instant consumedAt) {
            return delegate.consume(token, consumedAt);
        }

        @Override
        public int revokeFamily(TenantId tenantId, UUID familyId, Instant revokedAt, String reason) {
            return delegate.revokeFamily(tenantId, familyId, revokedAt, reason);
        }
    }
}
