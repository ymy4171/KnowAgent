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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the knowledge-base management endpoints against a real
 * PostgreSQL 16 database: RBAC (401/403/200), slug normalization and defaults, provider
 * binding validation, tenant isolation (404), duplicate slugs, paging/filters, the
 * delete guard for knowledge bases that still own files, soft-delete slug reuse,
 * optimistic-lock conflicts (409) and the response contract that never exposes the
 * persistence object or undefined JSONB.
 */
@Testcontainers
class KnowledgeBaseIT {

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
    private static String adminToken;
    private static UUID alphaTenant;
    private static UUID betaTenant;
    private static UUID embeddingProvider;
    private static UUID rerankProvider;
    private static UUID disabledEmbeddingProvider;
    private static UUID chatOnlyProvider;

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

        embeddingProvider = seedProvider(adminToken, "kb-embedding", true, new String[]{"EMBEDDING"},
                new Object[]{Map.of("name", "text-embedding-3-small", "capability", "EMBEDDING")});
        rerankProvider = seedProvider(adminToken, "kb-rerank", true, new String[]{"RERANK"},
                new Object[]{Map.of("name", "bge-reranker-v2", "capability", "RERANK")});
        disabledEmbeddingProvider = seedProvider(adminToken, "kb-embedding-disabled", false,
                new String[]{"EMBEDDING"},
                new Object[]{Map.of("name", "text-embedding-3-small", "capability", "EMBEDDING")});
        chatOnlyProvider = seedProvider(adminToken, "kb-chat-only", true, new String[]{"CHAT"},
                new Object[]{Map.of("name", "gpt-4o-mini", "capability", "CHAT")});
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anonymousAccessIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-bases"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"x\",\"name\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userWithoutKnowledgeBasePermissionGets403() throws Exception {
        String token = login("alpha", "viewer@alpha.test");

        mockMvc.perform(get("/api/v1/knowledge-bases").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"x\",\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createAppliesDefaultsAndNeverExposesPersistenceInternals() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"  HR-Manual  ","name":"HR Manual","description":"Employee handbook",
                                 "metadata":{"owner":"hr"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("slug").asText()).isEqualTo("hr-manual");
        assertThat(body.path("name").asText()).isEqualTo("HR Manual");
        assertThat(body.path("description").asText()).isEqualTo("Employee handbook");
        assertThat(body.path("knowledgeType").asText()).isEqualTo("LOCAL");
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("metadata").path("owner").asText()).isEqualTo("hr");
        assertThat(body.path("chunkPolicy").path("strategy").asText()).isEqualTo("RECURSIVE");
        assertThat(body.path("chunkPolicy").path("maxTokens").asInt()).isEqualTo(800);
        assertThat(body.path("chunkPolicy").path("overlapTokens").asInt()).isEqualTo(100);
        assertThat(body.path("retrievalConfig").path("topK").asInt()).isEqualTo(10);
        assertThat(body.path("retrievalConfig").path("scoreThreshold").asDouble()).isZero();
        assertThat(body.path("retrievalConfig").path("rerankEnabled").asBoolean()).isFalse();
        assertThat(body.path("embeddingProviderId").isNull()).isTrue();
        assertThat(body.path("rerankProviderId").isNull()).isTrue();

        assertThat(body.has("version")).isFalse();
        assertThat(body.has("createdBy")).isFalse();
        assertThat(body.has("updatedBy")).isFalse();
        assertThat(body.has("deletedAt")).isFalse();
        assertThat(body.has("tenantId")).isFalse();
    }

    @Test
    void createBindsEnabledProvidersWithMatchingCapabilities() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "slug", "bound-kb", "name", "Bound KB",
                                "embeddingProviderId", embeddingProvider.toString(),
                                "embeddingModel", "text-embedding-3-small",
                                "rerankProviderId", rerankProvider.toString(),
                                "rerankModel", "bge-reranker-v2"))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("embeddingProviderId").asText()).isEqualTo(embeddingProvider.toString());
        assertThat(body.path("embeddingModel").asText()).isEqualTo("text-embedding-3-small");
        assertThat(body.path("rerankProviderId").asText()).isEqualTo(rerankProvider.toString());
        assertThat(body.path("rerankModel").asText()).isEqualTo("bge-reranker-v2");
    }

    @Test
    void detailReturnsTheKnowledgeBaseAndUnknownIdReturns404() throws Exception {
        UUID id = createKnowledgeBase("detail-kb", null);

        MvcResult result = mockMvc.perform(get("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(OBJECT_MAPPER.readTree(result.getResponse().getContentAsString())
                .path("slug").asText()).isEqualTo("detail-kb");

        mockMvc.perform(get("/api/v1/knowledge-bases/" + UUID.randomUUID())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/knowledge-bases/not-a-uuid")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRenamesAndDisablesButRejectsDeletionTargets() throws Exception {
        UUID id = createKnowledgeBase("update-kb", null);

        MvcResult renamed = mockMvc.perform(patch("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed KB\",\"slug\":\"renamed-kb\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode renamedBody = OBJECT_MAPPER.readTree(renamed.getResponse().getContentAsString());
        assertThat(renamedBody.path("name").asText()).isEqualTo("Renamed KB");
        assertThat(renamedBody.path("slug").asText()).isEqualTo("renamed-kb");
        assertThat(renamedBody.has("version")).isFalse();

        // A no-op same-status patch is rejected while ACTIVE ...
        mockMvc.perform(patch("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());

        // ... disable is legal from ACTIVE ...
        mockMvc.perform(patch("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        // ... deletion targets are never settable via PATCH ...
        mockMvc.perform(patch("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETING\"}"))
                .andExpect(status().isBadRequest());

        // ... and re-enabling from DISABLED is legal.
        mockMvc.perform(patch("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidSlugAndInvalidConfigurationsReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"Bad Slug\",\"name\":\"X\"}"))
                .andExpect(status().isBadRequest());

        // chunk size must be > 0 and overlap < chunk size.
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"bad-chunk-0","name":"X","chunkPolicy":{"strategy":"RECURSIVE","maxTokens":0,"overlapTokens":0}}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"bad-chunk-overlap","name":"X","chunkPolicy":{"strategy":"RECURSIVE","maxTokens":100,"overlapTokens":100}}
                                """))
                .andExpect(status().isBadRequest());

        // topK must be within [1, 100] and scoreThreshold within [0, 1].
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"bad-topk","name":"X","retrievalConfig":{"topK":0,"scoreThreshold":0.0,"rerankEnabled":false}}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"bad-threshold","name":"X","retrievalConfig":{"topK":10,"scoreThreshold":1.5,"rerankEnabled":false}}
                                """))
                .andExpect(status().isBadRequest());

        // metadata must be a JSON object.
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"bad-metadata","name":"X","metadata":[]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidProviderBindingsReturn400Or404() throws Exception {
        // Half-configured embedding pair.
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"half-embed","name":"X","embeddingProviderId":"%s"}
                                """.formatted(embeddingProvider)))
                .andExpect(status().isBadRequest());

        // Disabled provider.
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"disabled-embed","name":"X","embeddingProviderId":"%s","embeddingModel":"text-embedding-3-small"}
                                """.formatted(disabledEmbeddingProvider)))
                .andExpect(status().isBadRequest());

        // Provider without the EMBEDDING capability.
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"chat-as-embed","name":"X","embeddingProviderId":"%s","embeddingModel":"gpt-4o-mini"}
                                """.formatted(chatOnlyProvider)))
                .andExpect(status().isBadRequest());

        // Model not present in the provider's enabled_models catalog.
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"unknown-model","name":"X","embeddingProviderId":"%s","embeddingModel":"not-in-catalog"}
                                """.formatted(embeddingProvider)))
                .andExpect(status().isBadRequest());

        // Provider declares EMBEDDING but only registers the model for CHAT: the catalog
        // entry's capability must match the role, not just the provider's capabilities.
        UUID mixedProvider = seedProvider(adminToken, "mixed-catalog", true,
                new String[]{"CHAT", "EMBEDDING"},
                new Object[]{Map.of("name", "gpt-4o-mini", "capability", "CHAT")});
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"cap-mismatch-model","name":"X","embeddingProviderId":"%s","embeddingModel":"gpt-4o-mini"}
                                """.formatted(mixedProvider)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantProviderBindingIsANonRevealing404() throws Exception {
        String betaToken = login("beta", "admin@beta.test");
        UUID betaProvider = seedProvider(betaToken, "beta-embedding", true, new String[]{"EMBEDDING"},
                new Object[]{Map.of("name", "text-embedding-3-small", "capability", "EMBEDDING")});

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"beta-bound","name":"X","embeddingProviderId":"%s","embeddingModel":"text-embedding-3-small"}
                                """.formatted(betaProvider)))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateSlugReturns409() throws Exception {
        createKnowledgeBase("docs", null);

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"DOCS\",\"name\":\"Duplicate\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void listSupportsPagingAndFilters() throws Exception {
        // Slugs and names are unique across tests because the database is shared for
        // the class (another test also uses the name "HR Manual").
        createKnowledgeBase("list-docs", "\"name\":\"Developer docs\"");
        createKnowledgeBase("list-hr-manual", "\"name\":\"List HR Manual\"");
        UUID archive = createKnowledgeBase("list-archive", "\"name\":\"Archived\"");
        mockMvc.perform(patch("/api/v1/knowledge-bases/" + archive)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        JsonNode byName = list("?name=list");
        assertThat(byName.path("total").asLong()).isEqualTo(1);
        assertThat(byName.path("items").get(0).path("slug").asText()).isEqualTo("list-hr-manual");

        JsonNode bySlug = list("?slug=list-doc");
        assertThat(bySlug.path("total").asLong()).isEqualTo(1);
        assertThat(bySlug.path("items").get(0).path("slug").asText()).isEqualTo("list-docs");

        JsonNode disabledOnly = list("?status=DISABLED");
        assertThat(disabledOnly.path("total").asLong()).isEqualTo(1);
        assertThat(disabledOnly.path("items").get(0).path("slug").asText()).isEqualTo("list-archive");

        // The paging assertions are scoped to this test's slugs so the shared
        // database's other knowledge bases do not affect the totals.
        JsonNode page1 = list("?slug=list-&page=1&size=2");
        assertThat(page1.path("items").size()).isEqualTo(2);
        assertThat(page1.path("total").asLong()).isEqualTo(3);
        JsonNode page2 = list("?slug=list-&page=2&size=2");
        assertThat(page2.path("items").size()).isEqualTo(1);
        assertThat(page2.path("total").asLong()).isEqualTo(3);
    }

    @Test
    void invalidPagingAndStatusFilterReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-bases?page=0").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/knowledge-bases?size=101").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/knowledge-bases?status=BOGUS").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantResourceAccessReturns404AndNeverLeaks() throws Exception {
        UUID id = createKnowledgeBase("alpha-only", null);
        String betaToken = login("beta", "admin@beta.test");

        mockMvc.perform(get("/api/v1/knowledge-bases/" + id).header("Authorization", bearer(betaToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(betaToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hijack\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/knowledge-bases/" + id).header("Authorization", bearer(betaToken)))
                .andExpect(status().isNotFound());

        // The beta list must not surface alpha's knowledge bases.
        MvcResult result = mockMvc.perform(get("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(betaToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("total").asLong()).isZero();
        assertThat(body.path("items").isArray()).isTrue();
        assertThat(body.path("items").isEmpty()).isTrue();
    }

    @Test
    void deleteEmptyKnowledgeBaseSucceedsAndAllowsSlugReuse() throws Exception {
        UUID id = createKnowledgeBase("reusable", null);

        mockMvc.perform(delete("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());

        createKnowledgeBase("reusable", null);
    }

    @Test
    void deleteKnowledgeBaseWithActiveFilesReturns409() throws Exception {
        UUID id = createKnowledgeBase("with-files", null);
        insertActiveFile(id);

        mockMvc.perform(delete("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("CONFLICT");
                });

        // Still present and deletable after the file is removed.
        mockMvc.perform(get("/api/v1/knowledge-bases/" + id)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
    }

    @Test
    void updateOptimisticLockConflictReturns409() throws Exception {
        UUID id = createKnowledgeBase("lock-docs", null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Bump the version in an uncommitted transaction so the row is locked and
            // its committed version differs from the one the service will read.
            try (PreparedStatement bump = connection.prepareStatement("""
                    UPDATE knowledge_bases
                    SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND id = ?
                    """)) {
                bump.setObject(1, alphaTenant);
                bump.setObject(2, id);
                assertThat(bump.executeUpdate()).isEqualTo(1);
            }

            Future<MvcResult> update = executor.submit(() -> mockMvc.perform(
                            patch("/api/v1/knowledge-bases/" + id)
                                    .header("Authorization", bearer(adminToken))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"name\":\"stale write\"}"))
                    .andReturn());

            Thread.sleep(250);
            assertThat(update.isDone()).as("update must wait for the row lock").isFalse();
            connection.commit();

            assertThat(update.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createBlocksOnTheProviderLockAndSurfacesAConcurrentDeleteAs404() throws Exception {
        // Proves the create path really takes FOR KEY SHARE on its bound providers: while
        // a delete transaction holds FOR UPDATE, the create must BLOCK (a plain MVCC read
        // would not), then after the delete commits it must re-read the soft-deleted row
        // as not-found and bind nothing - closing the validate-then-insert TOCTOU.
        UUID providerId = seedProvider(adminToken, "lock-race-provider", true,
                new String[]{"EMBEDDING"},
                new Object[]{Map.of("name", "text-embedding-3-small", "capability", "EMBEDDING")});
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("""
                    SELECT id FROM model_providers
                    WHERE tenant_id = ? AND id = ?
                    FOR UPDATE
                    """)) {
                lock.setObject(1, alphaTenant);
                lock.setObject(2, providerId);
                try (ResultSet resultSet = lock.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                }
            }

            Future<MvcResult> create = executor.submit(() -> mockMvc.perform(
                            post("/api/v1/knowledge-bases")
                                    .header("Authorization", bearer(adminToken))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                            "slug", "lock-provider-kb", "name", "Lock Provider KB",
                                            "embeddingProviderId", providerId.toString(),
                                            "embeddingModel", "text-embedding-3-small"))))
                    .andReturn());

            Thread.sleep(250);
            assertThat(create.isDone())
                    .as("create must wait for the provider delete lock (FOR KEY SHARE)")
                    .isFalse();

            // The delete transaction soft-deletes the provider and commits before the
            // create can insert its knowledge base.
            try (PreparedStatement softDelete = connection.prepareStatement("""
                    UPDATE model_providers
                    SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                        version = version + 1
                    WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                    """)) {
                softDelete.setObject(1, alphaTenant);
                softDelete.setObject(2, providerId);
                assertThat(softDelete.executeUpdate()).isEqualTo(1);
            }
            connection.commit();

            assertThat(create.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(404);
        } finally {
            executor.shutdownNow();
        }
        // The rejected create must not have left a knowledge base behind.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement count = connection.prepareStatement(
                     "SELECT COUNT(*) FROM knowledge_bases WHERE tenant_id = ? AND slug = ?")) {
            count.setObject(1, alphaTenant);
            count.setString(2, "lock-provider-kb");
            try (ResultSet resultSet = count.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong(1)).isZero();
            }
        }
    }

    @Test
    void concurrentSlugChangeResolvesToOneWinnerAndOneConflict() throws Exception {
        // Both PATCHes pass the slug pre-check (no committed target yet), then the raw
        // connection holds both rows locked so neither update can commit first. After the
        // lock is released one update takes the slug and the other hits the partial unique
        // index - which must surface as a stable 409, never a 500.
        UUID first = createKnowledgeBase("race-a", null);
        UUID second = createKnowledgeBase("race-b", null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("""
                    SELECT id FROM knowledge_bases
                    WHERE tenant_id = ? AND id IN (?, ?)
                    FOR UPDATE
                    """)) {
                lock.setObject(1, alphaTenant);
                lock.setObject(2, first);
                lock.setObject(3, second);
                try (ResultSet resultSet = lock.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.next()).isTrue();
                }
            }

            Future<MvcResult> changeFirst = executor.submit(() -> mockMvc.perform(
                            patch("/api/v1/knowledge-bases/" + first)
                                    .header("Authorization", bearer(adminToken))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"slug\":\"race-target\"}"))
                    .andReturn());
            Future<MvcResult> changeSecond = executor.submit(() -> mockMvc.perform(
                            patch("/api/v1/knowledge-bases/" + second)
                                    .header("Authorization", bearer(adminToken))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"slug\":\"race-target\"}"))
                    .andReturn());

            Thread.sleep(250);
            assertThat(changeFirst.isDone()).as("first patch must wait for its row lock").isFalse();
            assertThat(changeSecond.isDone()).as("second patch must wait for its row lock").isFalse();
            connection.commit();

            int firstStatus = changeFirst.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            int secondStatus = changeSecond.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(firstStatus).isIn(200, 409);
            assertThat(secondStatus).isIn(200, 409);
            assertThat(firstStatus + secondStatus)
                    .as("exactly one patch wins the slug, the other conflicts")
                    .isEqualTo(200 + 409);
        } finally {
            executor.shutdownNow();
        }
        // Exactly one active knowledge base may carry the contested slug.
        assertThat(singleUuid("SELECT id FROM knowledge_bases WHERE tenant_id = '"
                + alphaTenant + "' AND slug = 'race-target' AND deleted_at IS NULL")).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private static UUID createKnowledgeBase(String slug, String extraJson) throws Exception {
        String body = "{\"slug\":\"" + slug + "\",\"name\":\"" + slug + "\""
                + (extraJson == null ? "" : "," + extraJson) + "}";
        MvcResult result = mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(OBJECT_MAPPER.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private static JsonNode list(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/knowledge-bases" + query)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static UUID seedProvider(String token, String key, boolean enabled,
                                     String[] capabilities, Object[] enabledModels) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("providerKey", key);
        payload.put("displayName", key);
        payload.put("baseUrl", "https://api.example.com/v1");
        payload.put("capabilities", capabilities);
        payload.put("enabledModels", enabledModels);
        payload.put("enabled", enabled);
        MvcResult result = mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(OBJECT_MAPPER.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private static void insertActiveFile(UUID knowledgeBaseId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO knowledge_files (id, tenant_id, knowledge_base_id, display_name,
                                                  original_filename, object_key, content_type, sha256, file_size_bytes)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, alphaTenant);
            statement.setObject(3, knowledgeBaseId);
            statement.setString(4, "report.pdf");
            statement.setString(5, "report.pdf");
            statement.setString(6, "tenant/" + alphaTenant + "/" + knowledgeBaseId + "/report.pdf");
            statement.setString(7, "application/pdf");
            statement.setString(8, "a".repeat(64));
            statement.setLong(9, 1024L);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
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
