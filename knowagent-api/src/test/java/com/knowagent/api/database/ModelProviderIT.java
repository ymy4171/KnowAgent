package com.knowagent.api.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.api.KnowAgentApiApplication;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the model-provider configuration endpoints against a real
 * PostgreSQL 16 database: RBAC (401/403/200), secret encryption at rest, tenant
 * isolation, optimistic-lock soft delete, and the referenced-provider 409.
 */
@Testcontainers
class ModelProviderIT {

    private static final String ISSUER = "https://knowagent.test";
    private static final String AUDIENCE = "knowagent-api";
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(
            "integration-test-only-key-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
    private static final String RAW_PASSWORD = "CorrectHorseBatteryStaple1";
    private static final String PLAINTEXT_SECRET = "sk-proxy-test-secret-0123456789abcdef";
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
    private static String adminToken;
    private static UUID alphaTenant;
    private static UUID betaTenant;

    @BeforeAll
    static void bootContext() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;

        String masterKey = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        context = new SpringApplicationBuilder(KnowAgentApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.url=redis://127.0.0.1:1",
                        "--server.port=0",
                        "--bootstrap.enabled=false",
                        "--spring.datasource.hikari.maximum-pool-size=24",
                        "--jwt.issuer=" + ISSUER,
                        "--jwt.audience=" + AUDIENCE,
                        "--jwt.secret=" + JWT_SECRET,
                        "--model-provider.secret-key=" + masterKey,
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR");
        mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();

        AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
        bootstrap.initialize(new AdminBootstrapRequest("alpha", null, "admin@alpha.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("beta", null, "admin@beta.test", null, RAW_PASSWORD));

        alphaTenant = singleUuid("SELECT id FROM tenants WHERE slug = 'alpha'");
        betaTenant = singleUuid("SELECT id FROM tenants WHERE slug = 'beta'");
        seedViewer();
        adminToken = login("alpha", "admin@alpha.test");
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anonymousAccessIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/model-providers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userWithoutModelProviderPermissionGets403() throws Exception {
        String token = login("alpha", "viewer@alpha.test");

        mockMvc.perform(get("/api/v1/model-providers").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createEncryptsTheSecretAtRest() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("openai", PLAINTEXT_SECRET)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("hasSecret").asBoolean()).isTrue();
        assertThat(body.path("providerKey").asText()).isEqualTo("openai");
        assertThat(body.has("secretCiphertext")).isFalse();
        assertThat(body.has("secretKeyVersion")).isFalse();
        assertThat(body.has("headersCiphertext")).isFalse();
        UUID id = UUID.fromString(body.path("id").asText());

        // The stored ciphertext must never contain the plaintext and must carry a key version.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT secret_ciphertext, secret_key_version FROM model_providers WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("secret_ciphertext")).isNotNull()
                        .doesNotContain(PLAINTEXT_SECRET);
                assertThat(resultSet.getInt("secret_key_version")).isEqualTo(1);
            }
        }
    }

    @Test
    void listIsTenantScoped() throws Exception {
        createProvider("alpha-scope", null);
        // Create a provider in the other tenant.
        String betaToken = login("beta", "admin@beta.test");
        mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(betaToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("beta-scope", null)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/v1/model-providers")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        java.util.List<String> keys = body.path("items").findValuesAsText("providerKey");
        assertThat(keys).contains("alpha-scope").doesNotContain("beta-scope");
        for (JsonNode item : body.path("items")) {
            assertThat(item.path("providerKey").asText()).doesNotContain("beta-scope");
        }
    }

    @Test
    void crossTenantAccessReturns404() throws Exception {
        UUID id = createProvider("alpha-404", null);

        String betaToken = login("beta", "admin@beta.test");
        mockMvc.perform(get("/api/v1/model-providers/" + id).header("Authorization", bearer(betaToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/model-providers/" + id)
                        .header("Authorization", bearer(betaToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"hijack\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/model-providers/" + id).header("Authorization", bearer(betaToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateClearSecretClearsIt() throws Exception {
        UUID id = createProvider("clearsecret", PLAINTEXT_SECRET);

        mockMvc.perform(patch("/api/v1/model-providers/" + id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearSecret\":true}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("hasSecret").asBoolean()).isFalse();
                });
    }

    @Test
    void deleteReferencedProviderReturns409() throws Exception {
        UUID id = createProvider("referenced", null);
        insertKnowledgeBaseReferencing(id);

        mockMvc.perform(delete("/api/v1/model-providers/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("CONFLICT");
                });
    }

    @Test
    void softDeleteAllowsProviderKeyReuse() throws Exception {
        UUID id = createProvider("reusable", null);

        mockMvc.perform(delete("/api/v1/model-providers/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("reusable", null)))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidPagingIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/model-providers?page=0").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/model-providers?size=101").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidConfigurationAndAmbiguousSecretUpdateReturn400() throws Exception {
        String invalidConfig = """
                {"providerKey":"invalid-config","displayName":"Invalid","baseUrl":"file:///tmp/model",
                 "capabilities":["CHAT"],"enabledModels":[],"publicConfig":[]}
                """;
        mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidConfig))
                .andExpect(status().isBadRequest());

        UUID id = createProvider("ambiguous-secret", PLAINTEXT_SECRET);
        mockMvc.perform(patch("/api/v1/model-providers/" + id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"replacement\",\"clearSecret\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantProviderReferenceIsRejectedByTheCompositeForeignKey() throws Exception {
        UUID providerId = createProvider("cross-tenant-fk", null);

        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> insertKnowledgeBaseReferencing(connection, betaTenant, providerId))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23503"));
        }
    }

    @Test
    void deleteWaitsForProviderUsageLockAndThenSeesTheNewReference() throws Exception {
        UUID providerId = createProvider("locked-reference", null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("""
                    SELECT id FROM model_providers
                    WHERE tenant_id = ? AND id = ?
                    FOR KEY SHARE
                    """)) {
                lock.setObject(1, alphaTenant);
                lock.setObject(2, providerId);
                try (ResultSet resultSet = lock.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                }
            }

            Future<MvcResult> deletion = executor.submit(() -> mockMvc.perform(
                            delete("/api/v1/model-providers/" + providerId)
                                    .header("Authorization", bearer(adminToken)))
                    .andReturn());

            Thread.sleep(250);
            assertThat(deletion.isDone()).as("delete must wait for the provider usage lock").isFalse();
            insertKnowledgeBaseReferencing(connection, alphaTenant, providerId);
            connection.commit();

            assertThat(deletion.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static UUID createProvider(String key, String secret) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key, secret)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(OBJECT_MAPPER.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private static String createBody(String key, String secret) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("providerKey", key);
        payload.put("displayName", key);
        payload.put("baseUrl", "https://api.example.com/v1");
        payload.put("capabilities", new String[]{"CHAT"});
        payload.put("enabledModels", new Object[]{Map.of("name", "gpt-4o-mini", "capability", "CHAT")});
        if (secret != null) {
            payload.put("secret", secret);
        }
        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    private static void seedViewer() {
        UUID roleId = insertRole(alphaTenant, "VIEWER", "[]");
        UUID viewerId = insertUser(alphaTenant, "viewer@alpha.test", "Viewer");
        insertAssignment(alphaTenant, viewerId, roleId);
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
            statement.executeUpdate();
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UUID insertUser(UUID tenantId, String loginName, String displayName) {
        String passwordHash = context.getBean(PasswordHasher.class).encode(RAW_PASSWORD);
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users (id, tenant_id, login_name, display_name, password_hash, status, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, loginName);
            statement.setString(4, displayName);
            statement.setString(5, passwordHash);
            statement.executeUpdate();
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void insertAssignment(UUID tenantId, UUID userId, UUID roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO user_roles (tenant_id, user_id, role_id, granted_at)
                     VALUES (?, ?, ?, ?)
                     """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, userId);
            statement.setObject(3, roleId);
            statement.setObject(4, OffsetDateTime.now().minusDays(1));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void insertKnowledgeBaseReferencing(UUID providerId) {
        try (Connection connection = dataSource.getConnection()) {
            insertKnowledgeBaseReferencing(connection, alphaTenant, providerId);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void insertKnowledgeBaseReferencing(Connection connection, UUID tenantId, UUID providerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO knowledge_bases (id, tenant_id, slug, name, embedding_provider_id, embedding_model)
                     VALUES (?, ?, ?, 'KB', ?, 'text-embedding-3-small')
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId);
            statement.setString(3, "kb-" + System.nanoTime());
            statement.setObject(4, providerId);
            statement.executeUpdate();
        }
    }

    private static String login(String tenantSlug, String loginName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "tenantSlug", tenantSlug, "loginName", loginName, "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static UUID singleUuid(String sql) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getObject(1, UUID.class);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
