package com.knowagent.api.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.api.KnowAgentApiApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.security.application.port.out.PasswordHasher;
import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the tenant-scoped user management queries
 * ({@code GET /api/v1/users} and {@code GET /api/v1/users/{userId}}).
 *
 * <p>Boots the real application on a Testcontainers PostgreSQL 16 database, seeds
 * two tenants through the production {@link AdminBootstrap} plus raw-SQL users and
 * roles (passwords hashed with the real {@link PasswordHasher}), and logs in over
 * HTTP so every assertion runs through the true role → permission → authority →
 * {@code @PreAuthorize} chain.
 */
@Testcontainers
class UserQueryIT {

    private static final String ISSUER = "https://knowagent.test";
    private static final String AUDIENCE = "knowagent-api";
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(
            "integration-test-only-key-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
    private static final String RAW_PASSWORD = "CorrectHorseBatteryStaple1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("knowagent")
                    .withUsername("knowagent")
                    .withPassword("integration_only");

    private static ConfigurableApplicationContext context;
    private static MockMvc mockMvc;
    private static DataSource dataSource;
    private static PasswordHasher passwordHasher;

    // Seeded identities (created in @BeforeAll), shared across tests.
    private static UUID alphaTenant;
    private static UUID betaTenant;
    private static UUID alphaAdminId;
    private static UUID betaUserId;
    private static String adminToken;

    @BeforeAll
    static void bootContext() throws Exception {
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
                        "--bootstrap.enabled=false",
                        "--auth.login.max-failed-attempts=3",
                        "--auth.login.lock-duration=15m",
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
        passwordHasher = context.getBean(PasswordHasher.class);

        AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
        bootstrap.initialize(new AdminBootstrapRequest("alpha", null, "admin@alpha.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("beta", null, "admin@beta.test", null, RAW_PASSWORD));

        seedUsers();

        adminToken = login("alpha", "admin@alpha.test");
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anonymousCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
                });
    }

    @Test
    void userWithoutUserReadPermissionGets403() throws Exception {
        String token = login("alpha", "viewer@alpha.test");

        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
                });
    }

    @Test
    void expiredRoleBindingDoesNotGrantUserRead() throws Exception {
        // The expired@alpha.test binding has expires_at in the past, so login still
        // succeeds (status ACTIVE, correct password) but the user holds no effective
        // role and therefore no USER_READ permission -> 403, not 401.
        String token = login("alpha", "expired@alpha.test");

        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
                });
    }

    @Test
    void adminListsOnlyUsersInsideItsOwnTenant() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("total").asLong()).isEqualTo(7);
        assertThat(body.path("items")).hasSize(7);
        for (JsonNode item : body.path("items")) {
            String loginName = item.path("loginName").asText();
            // beta's admin and beta-user must never appear in alpha's listing.
            assertThat(loginName).doesNotContain("beta");
        }
        // The alpha admin itself is present.
        assertThat(body.path("items").findValuesAsText("loginName"))
                .contains("admin@alpha.test");
    }

    @Test
    void paginationReturnsAStableSliceAndTheSameTotal() throws Exception {
        // Newest first: admin (bootstrapped just now), then the seeded users with
        // created_at one minute apart going backwards.
        assertPage(adminToken, "page=1&size=2", 2, 7, "admin@alpha.test", "alice@alpha.test");
        assertPage(adminToken, "page=2&size=2", 2, 7, "bob@alpha.test", "charlie@alpha.test");
        assertPage(adminToken, "page=1&size=100", 7, 7, "admin@alpha.test");
    }

    @Test
    void keywordAndStatusFiltersAreCaseInsensitiveAndTenantScoped() throws Exception {
        // Fuzzy keyword against login name, case-insensitive.
        assertPage(adminToken, "keyword=alice", 1, 1, "alice@alpha.test");
        // Fuzzy keyword against display name ("Charlie Admin").
        assertPage(adminToken, "keyword=CHARLIE", 1, 1, "charlie@alpha.test");
        // Status filter excludes the disabled user.
        assertPage(adminToken, "status=ACTIVE", 6, 6);
        assertPage(adminToken, "status=DISABLED", 1, 1, "dave@alpha.test");
        // Keyword that matches nothing.
        assertPage(adminToken, "keyword=zzz", 0, 0);
        // Combined filters.
        assertPage(adminToken, "keyword=charlie&status=ACTIVE", 1, 1, "charlie@alpha.test");
    }

    @Test
    void invalidParametersReturn400ValidationError() throws Exception {
        assertBadRequest(adminToken, "/api/v1/users?page=0");
        assertBadRequest(adminToken, "/api/v1/users?size=0");
        assertBadRequest(adminToken, "/api/v1/users?size=101");
        assertBadRequest(adminToken, "/api/v1/users?page=2147483647&size=100");
        assertBadRequest(adminToken, "/api/v1/users?status=BOGUS");
        // A non-UUID path variable is also a client error, not a 500.
        mockMvc.perform(get("/api/v1/users/not-a-uuid").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
                });
    }

    @Test
    void crossTenantUserIdReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + betaUserId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
                });
    }

    @Test
    void detailReturnsTheUserWithoutInternalFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/" + alphaAdminId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("userId").asText()).isEqualTo(alphaAdminId.toString());
        assertThat(body.path("loginName").asText()).isEqualTo("admin@alpha.test");
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.has("loginFailedCount")).isFalse();
        assertThat(body.has("loginLockedUntil")).isFalse();
        assertThat(body.has("lastFailedLoginAt")).isFalse();
        assertThat(body.has("deletedAt")).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static void seedUsers() {
        alphaTenant = tenantId("alpha");
        betaTenant = tenantId("beta");
        alphaAdminId = userId(alphaTenant, "admin@alpha.test");
        String passwordHash = passwordHasher.encode(RAW_PASSWORD);

        // viewer: a role with no permissions, bound forever.
        UUID viewerRole = insertRole(alphaTenant, "VIEWER", "[]");
        UUID viewerId = insertUser(alphaTenant, "viewer@alpha.test", "Viewer",
                "ACTIVE", passwordHash, minutesAgo(6));
        insertAssignment(alphaTenant, viewerId, viewerRole, null);

        // expired: a role with USER_READ but the binding is already in the past, so
        // it must not grant the permission.
        UUID readerRole = insertRole(alphaTenant, "READER", "[\"USER_READ\"]");
        UUID expiredId = insertUser(alphaTenant, "expired@alpha.test", "Expired",
                "ACTIVE", passwordHash, minutesAgo(5));
        insertAssignment(alphaTenant, expiredId, readerRole, OffsetDateTime.now().minusDays(1));

        // Plain users for paging/filtering. created_at is spread one minute apart so
        // the DESC ordering is deterministic.
        insertUser(alphaTenant, "alice@alpha.test", "Alice", "ACTIVE", passwordHash, minutesAgo(1));
        insertUser(alphaTenant, "bob@alpha.test", "Bob", "ACTIVE", passwordHash, minutesAgo(2));
        insertUser(alphaTenant, "charlie@alpha.test", "Charlie Admin", "ACTIVE", passwordHash, minutesAgo(3));
        insertUser(alphaTenant, "dave@alpha.test", "Dave", "DISABLED", passwordHash, minutesAgo(4));

        // A user in the other tenant: must never be visible to alpha's admin.
        UUID betaUser = insertUser(betaTenant, "beta-user@beta.test", "Beta User",
                "ACTIVE", passwordHash, minutesAgo(1));
        betaUserId = betaUser;
    }

    private static String login(String tenantSlug, String loginName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(tenantSlug, loginName, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        return body.path("accessToken").asText();
    }

    private static String loginBody(String tenantSlug, String loginName, String password) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "tenantSlug", tenantSlug,
                "loginName", loginName,
                "password", password));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Performs a paged list request and asserts total / item count / leading login names. */
    private static void assertPage(String token, String query, int expectedItems, long expectedTotal,
                                   String... leadingLoginNames) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users?" + query)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("total").asLong()).isEqualTo(expectedTotal);
        assertThat(body.path("items")).hasSize(expectedItems);
        for (int index = 0; index < leadingLoginNames.length; index++) {
            assertThat(body.path("items").path(index).path("loginName").asText())
                    .isEqualTo(leadingLoginNames[index]);
        }
    }

    private static void assertBadRequest(String token, String url) throws Exception {
        mockMvc.perform(get(url).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
                });
    }

    private static UUID tenantId(String slug) {
        return singleUuid("SELECT id FROM tenants WHERE slug = ?", slug);
    }

    private static UUID userId(UUID tenantId, String loginName) {
        return singleUuid("SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, loginName);
    }

    private static UUID insertUser(UUID tenantId, String loginName, String displayName,
                                   String status, String passwordHash, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users (id, tenant_id, login_name, display_name, password_hash, status, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, loginName);
            statement.setString(4, displayName);
            statement.setString(5, passwordHash);
            statement.setString(6, status);
            statement.setObject(7, createdAt);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UUID insertRole(UUID tenantId, String code, String permissionsJson) {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO roles (id, tenant_id, code, name, permissions, is_system, status)
                     VALUES (?, ?, ?, ?, ?::jsonb, false, 'ACTIVE')
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, code);
            statement.setString(4, code);
            statement.setString(5, permissionsJson);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void insertAssignment(UUID tenantId, UUID userId, UUID roleId, OffsetDateTime expiresAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO user_roles (tenant_id, user_id, role_id, granted_at, expires_at)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, userId);
            statement.setObject(3, roleId);
            statement.setObject(4, OffsetDateTime.now().minusDays(2));
            statement.setObject(5, expiresAt);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static OffsetDateTime minutesAgo(long minutes) {
        return OffsetDateTime.now().minusMinutes(minutes);
    }

    private static UUID singleUuid(String sql, Object... parameters) {
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
