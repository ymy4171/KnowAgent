package com.knowagent.api.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import jakarta.servlet.Filter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the login loop and the current-user endpoint through the real HTTP
 * security chain against a PostgreSQL 16 container.
 *
 * <p>Runs only under the {@code docker-it} profile (Failsafe). It bootstraps a set
 * of tenants/users via the production {@link AdminBootstrap} service, then drives
 * {@code POST /api/v1/auth/login} and {@code GET /api/v1/users/me} through MockMvc.
 * It proves: a correct login returns a Bearer access token + one-time raw refresh
 * token (only the SHA-256 hash persisted) and that the access token reaches
 * {@code /users/me}; wrong/disabled/locked credentials yield the stable error codes;
 * consecutive failures lock the account while a successful login clears the count;
 * tenant A cannot authenticate as or see tenant B's user/roles; and DTO validation
 * failures yield a unified JSON 400. Password hashes, token hashes and internal
 * lock fields never appear in any response.
 */
@Testcontainers
class AuthFlowIT {

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
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.url=redis://127.0.0.1:1",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--bootstrap.enabled=false",
                        "--auth.login.max-failed-attempts=3",
                        "--auth.login.lock-duration=15m",
                        // LoginService holds no transaction: reads are auto-commit and
                        // each write runs in its own single-connection transaction, so
                        // a login never holds more than one connection. A modest pool
                        // covers the 16-simultaneous-login concurrency test below.
                        "--spring.datasource.hikari.maximum-pool-size=24",
                        "--jwt.issuer=" + ISSUER,
                        "--jwt.audience=" + AUDIENCE,
                        "--jwt.secret=" + JWT_SECRET,
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR");
        // webAppContextSetup does not auto-register the security filter chain in a
        // programmatically booted context, so the chain is added explicitly.
        mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();

        AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
        bootstrap.initialize(new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("beta", null, "admin@beta.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("disabledco", null, "admin@disabled.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("lockedco", null, "admin@locked.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("lockflow", null, "admin@lockflow.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("clearflow", null, "admin@clearflow.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("conc", null, "admin@conc.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("expire", null, "admin@expire.test", null, RAW_PASSWORD));
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void correctPasswordLoginIssuesTokensAndAccessTokenReachesUsersMe() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("acme", "admin@acme.test", RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(login.getResponse().getContentAsString());
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        String accessToken = body.path("accessToken").asText();
        String refreshToken = body.path("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(body.path("expiresIn").asLong()).isGreaterThan(0);
        // Internal credential material must never appear in the response.
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.has("tokenHash")).isFalse();
        assertThat(body.has("loginFailedCount")).isFalse();
        assertThat(body.has("loginLockedUntil")).isFalse();

        // Only the SHA-256 hash of the refresh token is persisted, never the raw value.
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "acme");
        UUID userId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?", tenantId, "admin@acme.test");
        String storedHash = singleString("""
                SELECT token_hash FROM refresh_tokens
                WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                ORDER BY issued_at DESC LIMIT 1""", tenantId, userId);
        assertThat(storedHash).isEqualTo(sha256Hex(refreshToken));
        assertThat(storedHash).isNotEqualTo(refreshToken);

        // The signed access token reaches the protected /users/me endpoint.
        MvcResult me = mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode meBody = OBJECT_MAPPER.readTree(me.getResponse().getContentAsString());
        assertThat(meBody.path("userId").asText()).isEqualTo(userId.toString());
        assertThat(meBody.path("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(meBody.path("tenantSlug").asText()).isEqualTo("acme");
        assertThat(meBody.path("loginName").asText()).isEqualTo("admin@acme.test");
        assertThat(meBody.path("displayName").asText()).isNotBlank();
        assertThat(meBody.path("roles").toString()).contains("ADMIN");
        assertThat(meBody.path("permissions").toString()).contains("USER_READ");
        assertThat(meBody.has("passwordHash")).isFalse();
        assertThat(meBody.has("loginFailedCount")).isFalse();
        // The refresh token is returned exactly once - in the login response - and
        // must never appear anywhere else: not in the /users/me response, and not in
        // the persisted form (only its hash is stored, asserted above).
        assertThat(me.getResponse().getContentAsString()).doesNotContain(refreshToken);
        assertThat(me.getResponse().getContentAsString()).doesNotContain(accessToken);
        assertThat(login.getResponse().getContentAsString()).doesNotContain(RAW_PASSWORD);
    }

    @Test
    void wrongPasswordReturnsUnifiedInvalidCredentials() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("acme", "admin@acme.test", "wrong-" + RAW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(RAW_PASSWORD);
    }

    @Test
    void disabledAccountReturnsStableDisabledError() throws Exception {
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "disabledco");
        update("""
                UPDATE users SET status = 'DISABLED'
                WHERE tenant_id = ? AND login_name = ?""", tenantId, "admin@disabled.test");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("disabledco", "admin@disabled.test", RAW_PASSWORD)))
                .andExpect(status().isForbidden())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void lockedAccountReturnsStableLockedError() throws Exception {
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "lockedco");
        update("""
                UPDATE users SET status = 'LOCKED'
                WHERE tenant_id = ? AND login_name = ?""", tenantId, "admin@locked.test");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("lockedco", "admin@locked.test", RAW_PASSWORD)))
                .andExpect(status().isForbidden())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void consecutiveFailuresLockTheAccountAndEvenTheCorrectPasswordIsRejected() throws Exception {
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "lockflow");
        UUID userId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?", tenantId, "admin@lockflow.test");

        for (int attempt = 0; attempt < 3; attempt++) {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("lockflow", "admin@lockflow.test", "wrong-" + RAW_PASSWORD)))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
            assertThat(body.path("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");
        }

        // The third failure crossed the threshold: the account is locked, so even
        // the correct password is now rejected with the stable locked error.
        MvcResult locked = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("lockflow", "admin@lockflow.test", RAW_PASSWORD)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode lockedBody = OBJECT_MAPPER.readTree(locked.getResponse().getContentAsString());
        assertThat(lockedBody.path("errorCode").asText()).isEqualTo("ACCOUNT_LOCKED");

        assertThat(singleString("SELECT status FROM users WHERE id = ?", userId)).isEqualTo("LOCKED");
        assertThat(singleInt("SELECT login_failed_count FROM users WHERE id = ?", userId)).isEqualTo(3);
        assertThat(singleInstant("SELECT login_locked_until FROM users WHERE id = ?", userId))
                .isAfter(Instant.now());
    }

    @Test
    void successfulLoginClearsPreviousFailures() throws Exception {
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "clearflow");
        UUID userId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?", tenantId, "admin@clearflow.test");

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("clearflow", "admin@clearflow.test", "wrong-" + RAW_PASSWORD)))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("clearflow", "admin@clearflow.test", RAW_PASSWORD)))
                .andExpect(status().isOk());

        assertThat(singleString("SELECT status FROM users WHERE id = ?", userId)).isEqualTo("ACTIVE");
        assertThat(singleInt("SELECT login_failed_count FROM users WHERE id = ?", userId)).isZero();
        assertThat(singleInstant("SELECT login_locked_until FROM users WHERE id = ?", userId)).isNull();
        assertThat(singleInstant("SELECT last_login_at FROM users WHERE id = ?", userId)).isNotNull();
    }

    @Test
    void concurrentWrongPasswordsAllAccumulateAndReachTheLockThreshold() throws Exception {
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "conc");
        UUID userId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?", tenantId, "admin@conc.test");

        int attempts = 16;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        String wrongBody = loginBody("conc", "admin@conc.test", "wrong-" + RAW_PASSWORD);
        for (int index = 0; index < attempts; index++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wrongBody))
                        .andReturn().getResponse().getStatus();
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        // Every concurrent wrong password either runs against the account (401,
        // counted) or hits the lock that the run itself produced (403, not counted).
        long unauthorized = 0;
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 401) {
                unauthorized++;
            } else {
                assertThat(status).isEqualTo(403);
            }
        }
        pool.shutdown();

        // No count is lost: the database-incremented count equals the number of 401s.
        // Before the atomic-SQL fix the optimistic-lock read-modify-write dropped
        // every concurrent failure but one, so the threshold could be raced past.
        int failed = singleInt("SELECT login_failed_count FROM users WHERE id = ?", userId);
        assertThat(failed).isEqualTo((int) unauthorized);
        assertThat(failed).isGreaterThanOrEqualTo(3);
        assertThat(singleString("SELECT status FROM users WHERE id = ?", userId)).isEqualTo("LOCKED");
        assertThat(singleInstant("SELECT login_locked_until FROM users WHERE id = ?", userId))
                .isAfter(Instant.now());

        // The lock holds: even the correct password is now rejected.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("conc", "admin@conc.test", RAW_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountLockedByRealFailuresRecoversAfterTheLockWindowElapses() throws Exception {
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "expire");
        UUID userId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?", tenantId, "admin@expire.test");

        // Real failures through the security chain lock the account.
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("expire", "admin@expire.test", "wrong-" + RAW_PASSWORD)))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(singleString("SELECT status FROM users WHERE id = ?", userId)).isEqualTo("LOCKED");

        // The lock window elapses: only the timestamp moves; the status stays LOCKED
        // exactly as the recorder left it. The account must be retryable again.
        update("""
                UPDATE users SET login_locked_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?""", userId);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("expire", "admin@expire.test", RAW_PASSWORD)))
                .andExpect(status().isOk());

        assertThat(singleString("SELECT status FROM users WHERE id = ?", userId)).isEqualTo("ACTIVE");
        assertThat(singleInt("SELECT login_failed_count FROM users WHERE id = ?", userId)).isZero();
        assertThat(singleInstant("SELECT login_locked_until FROM users WHERE id = ?", userId)).isNull();
    }

    @Test
    void tenantACannotAuthenticateAsOrSeeTenantBUser() throws Exception {
        // The shared login name pattern: acme owns admin@acme.test, beta owns
        // admin@beta.test. A cross-tenant login name must yield the unified error.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("acme", "admin@beta.test", RAW_PASSWORD)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("beta", "admin@acme.test", RAW_PASSWORD)))
                .andExpect(status().isUnauthorized());

        // A valid acme login resolves strictly to the acme tenant.
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("acme", "admin@acme.test", RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = OBJECT_MAPPER.readTree(login.getResponse().getContentAsString())
                .path("accessToken").asText();

        UUID acmeTenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "acme");
        UUID acmeUserId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?", acmeTenantId, "admin@acme.test");
        UUID betaUserId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                singleUuid("SELECT id FROM tenants WHERE slug = ?", "beta"), "admin@beta.test");

        MvcResult me = mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode meBody = OBJECT_MAPPER.readTree(me.getResponse().getContentAsString());
        assertThat(meBody.path("userId").asText()).isEqualTo(acmeUserId.toString());
        assertThat(meBody.path("userId").asText()).isNotEqualTo(betaUserId.toString());
        assertThat(meBody.path("tenantSlug").asText()).isEqualTo("acme");
        assertThat(meBody.path("roles").toString()).contains("ADMIN");
        // Beta's roles must not leak into acme's identity.
        assertThat(meBody.path("roles").toString()).doesNotContain("BETA");

        // The refresh token issued by the acme login is scoped to acme's tenant.
        UUID refreshTenantId = singleUuid("""
                SELECT tenant_id FROM refresh_tokens
                WHERE user_id = ? AND status = 'ACTIVE'
                ORDER BY issued_at DESC LIMIT 1""", acmeUserId);
        assertThat(refreshTenantId).isEqualTo(acmeTenantId);
    }

    @Test
    void dtoValidationFailuresReturnUnifiedJson400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("", "admin@acme.test", RAW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(
                            result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
                });

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(
                                Map.of("tenantSlug", "acme", "loginName", "admin@acme.test"))))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(
                            result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
                });

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(
                            result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
                });
    }

    private static String loginBody(String tenantSlug, String loginName, String password) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "tenantSlug", tenantSlug,
                "loginName", loginName,
                "password", password));
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

    private int singleInt(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
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

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
        return statement;
    }
}
