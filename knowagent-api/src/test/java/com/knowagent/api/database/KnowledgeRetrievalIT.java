package com.knowagent.api.database;

import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.service.KnowledgeCitation;
import com.knowagent.knowledge.application.service.KnowledgeRetrievalCommand;
import com.knowagent.knowledge.application.service.KnowledgeRetrievalResult;
import com.knowagent.knowledge.application.service.KnowledgeRetrievalService;
import com.knowagent.knowledge.vector.VectorChunk;
import com.knowagent.knowledge.vector.VectorHit;
import com.knowagent.knowledge.vector.VectorQuery;
import com.knowagent.knowledge.vector.VectorStoreGateway;
import com.knowagent.model.embedding.EmbeddingGateway;
import com.knowagent.model.embedding.EmbeddingResult;
import com.knowagent.security.context.TenantContext;
import com.knowagent.security.principal.TenantPrincipal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.milvus.MilvusContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL 16 + Milvus 2.5.6 semantic retrieval integration. It proves the
 * tenant/knowledge-base filter against Milvus itself and then proves PostgreSQL is
 * the final authorization and citation source for stale or poisoned vector hits.
 */
@Testcontainers
class KnowledgeRetrievalIT {

    private static final String COLLECTION = "it_semantic_retrieval";
    private static final int DIMENSION = 4;
    private static final AtomicInteger EMBEDDING_CALLS = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowagent")
            .withUsername("knowagent")
            .withPassword("integration_only");

    @Container
    static final MilvusContainer MILVUS = new MilvusContainer("milvusdb/milvus:v2.5.6");

    private static ConfigurableApplicationContext context;
    private static DataSource dataSource;

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID KB_A = UUID.randomUUID();
    private static final UUID KB_A_OTHER = UUID.randomUUID();
    private static final UUID KB_B = UUID.randomUUID();
    private static final UUID PROVIDER_A = UUID.randomUUID();
    private static final UUID PROVIDER_B = UUID.randomUUID();

    private static final UUID FILE_READY_A = UUID.randomUUID();
    private static final UUID FILE_FAILED_A = UUID.randomUUID();
    private static final UUID FILE_OTHER_KB = UUID.randomUUID();
    private static final UUID FILE_B = UUID.randomUUID();
    private static final UUID CHUNK_READY_A = UUID.randomUUID();
    private static final UUID CHUNK_FAILED_FILE_A = UUID.randomUUID();
    private static final UUID CHUNK_OTHER_KB = UUID.randomUUID();
    private static final UUID CHUNK_B = UUID.randomUUID();
    private static final UUID CHUNK_POISONED_B = UUID.randomUUID();

    @BeforeAll
    static void bootAndSeed() throws Exception {
        String jwtSecret = Base64.getEncoder().encodeToString(
                "integration-test-only-key-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
        context = new SpringApplicationBuilder(KnowAgentApiApplication.class, RetrievalTestConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.url=redis://127.0.0.1:1",
                        "--server.port=0",
                        "--bootstrap.enabled=false",
                        "--jwt.issuer=https://knowagent.test",
                        "--jwt.audience=knowagent-api",
                        "--jwt.secret=" + jwtSecret,
                        "--knowagent.vector.milvus.uri=" + MILVUS.getEndpoint(),
                        "--knowagent.vector.milvus.collection-name=" + COLLECTION,
                        "--knowagent.vector.milvus.dimension=" + DIMENSION,
                        "--knowagent.vector.milvus.index-type=FLAT",
                        "--knowagent.vector.milvus.init-timeout=180s",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
        seedPostgres();
        seedMilvus(context.getBean(VectorStoreGateway.class));
    }

    @AfterAll
    static void close() {
        TenantContext.clear();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void realMilvusFilterAndPostgresHydrationReturnOnlyVerifiedReadyCitation() {
        VectorStoreGateway vectors = context.getBean(VectorStoreGateway.class);
        List<VectorHit> rawHits = vectors.search(new VectorQuery(TenantId.of(TENANT_A), KB_A,
                vector(), 10, -1.0, null));
        assertThat(rawHits).extracting(VectorHit::chunkId)
                .contains(CHUNK_READY_A, CHUNK_FAILED_FILE_A, CHUNK_POISONED_B)
                .doesNotContain(CHUNK_B, CHUNK_OTHER_KB);

        KnowledgeRetrievalService retrieval = context.getBean(KnowledgeRetrievalService.class);
        KnowledgeRetrievalResult result = withTenant(TENANT_A, () -> retrieval.retrieve(
                new KnowledgeRetrievalCommand(TenantId.of(TENANT_A), KB_A, "safe query", 10, 0.5, null)));

        assertThat(EMBEDDING_CALLS.get()).isEqualTo(1);
        assertThat(result.citations()).hasSize(1);
        KnowledgeCitation citation = result.citations().getFirst();
        assertThat(citation.chunkId()).isEqualTo(CHUNK_READY_A);
        assertThat(citation.fileId()).isEqualTo(FILE_READY_A);
        assertThat(citation.displayName()).isEqualTo("ready-a.pdf");
        assertThat(citation.content()).isEqualTo("authoritative postgres content");
        assertThat(citation.pageNumber()).isEqualTo(5);
        assertThat(citation.sectionPath()).containsExactly("2", "2.1");
        assertThat(citation.rank()).isEqualTo(1);

        assertThatThrownBy(() -> withTenant(TENANT_A, () -> retrieval.retrieve(
                new KnowledgeRetrievalCommand(TenantId.of(TENANT_A), KB_A, "safe query",
                        10, 0.5, List.of(FILE_B)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(EMBEDDING_CALLS.get()).isEqualTo(1);
    }

    private static void seedPostgres() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertTenant(connection, TENANT_A, "retrieval-a");
            insertTenant(connection, TENANT_B, "retrieval-b");
            insertProvider(connection, TENANT_A, PROVIDER_A, "provider-a");
            insertProvider(connection, TENANT_B, PROVIDER_B, "provider-b");
            insertKnowledgeBase(connection, TENANT_A, KB_A, PROVIDER_A, "kb-a");
            insertKnowledgeBase(connection, TENANT_A, KB_A_OTHER, PROVIDER_A, "kb-a-other");
            insertKnowledgeBase(connection, TENANT_B, KB_B, PROVIDER_B, "kb-b");
            insertFile(connection, TENANT_A, KB_A, FILE_READY_A, "ready-a.pdf", "READY");
            insertFile(connection, TENANT_A, KB_A, FILE_FAILED_A, "failed-a.txt", "FAILED");
            insertFile(connection, TENANT_A, KB_A_OTHER, FILE_OTHER_KB, "other-kb.txt", "READY");
            insertFile(connection, TENANT_B, KB_B, FILE_B, "tenant-b.txt", "READY");
            insertChunk(connection, TENANT_A, KB_A, FILE_READY_A, CHUNK_READY_A,
                    "authoritative postgres content", 0, 5, "[\"2\",\"2.1\"]");
            insertChunk(connection, TENANT_A, KB_A, FILE_FAILED_A, CHUNK_FAILED_FILE_A,
                    "must be filtered", 0, null, "[]");
            insertChunk(connection, TENANT_A, KB_A_OTHER, FILE_OTHER_KB, CHUNK_OTHER_KB,
                    "other knowledge base", 0, null, "[]");
            insertChunk(connection, TENANT_B, KB_B, FILE_B, CHUNK_B,
                    "tenant b", 0, null, "[]");
            insertChunk(connection, TENANT_B, KB_B, FILE_B, CHUNK_POISONED_B,
                    "poisoned tenant b", 1, null, "[]");
            connection.commit();
        }
    }

    private static void seedMilvus(VectorStoreGateway gateway) {
        gateway.upsert(List.of(
                vectorChunk(TENANT_A, KB_A, FILE_READY_A, CHUNK_READY_A),
                // Poisoned scalar scope: Milvus returns this id for tenant A/KB A,
                // but PostgreSQL owns it under tenant B/KB B, so hydration drops it.
                vectorChunk(TENANT_A, KB_A, FILE_READY_A, CHUNK_POISONED_B)));
        gateway.upsert(List.of(vectorChunk(TENANT_A, KB_A, FILE_FAILED_A, CHUNK_FAILED_FILE_A)));
        gateway.upsert(List.of(vectorChunk(TENANT_A, KB_A_OTHER, FILE_OTHER_KB, CHUNK_OTHER_KB)));
        gateway.upsert(List.of(vectorChunk(TENANT_B, KB_B, FILE_B, CHUNK_B)));
    }

    private static VectorChunk vectorChunk(UUID tenant, UUID kb, UUID file, UUID chunk) {
        return new VectorChunk(TenantId.of(tenant), kb, file, chunk, "not stored in milvus",
                vector(), "test/embedding-model");
    }

    private static float[] vector() {
        return new float[]{1f, 0f, 0f, 0f};
    }

    private static void insertTenant(Connection connection, UUID id, String slug) throws Exception {
        execute(connection, "INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)", id, slug, slug);
    }

    private static void insertProvider(Connection connection, UUID tenant, UUID id, String key) throws Exception {
        execute(connection, """
                INSERT INTO model_providers
                    (id, tenant_id, provider_key, display_name, base_url, capabilities, enabled_models)
                VALUES (?, ?, ?, ?, 'https://example.invalid/v1', '["EMBEDDING"]'::jsonb,
                        '[{"name":"embedding-model","capability":"EMBEDDING"}]'::jsonb)
                """, id, tenant, key, key);
    }

    private static void insertKnowledgeBase(Connection connection, UUID tenant, UUID id,
                                            UUID provider, String slug) throws Exception {
        execute(connection, """
                INSERT INTO knowledge_bases
                    (id, tenant_id, slug, name, status, embedding_provider_id, embedding_model,
                     chunk_policy, retrieval_config)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, 'embedding-model',
                        '{"strategy":"RECURSIVE","maxTokens":800,"overlapTokens":100}'::jsonb,
                        '{"topK":10,"scoreThreshold":0.0,"rerankEnabled":false}'::jsonb)
                """, id, tenant, slug, slug, provider);
    }

    private static void insertFile(Connection connection, UUID tenant, UUID kb, UUID id,
                                   String name, String status) throws Exception {
        execute(connection, """
                INSERT INTO knowledge_files
                    (id, tenant_id, knowledge_base_id, display_name, original_filename, object_key,
                     content_type, sha256, file_size_bytes, status)
                VALUES (?, ?, ?, ?, ?, ?, 'text/plain', ?, 4, ?)
                """, id, tenant, kb, name, name, "tenants/" + tenant + "/files/" + id,
                "a".repeat(64), status);
    }

    private static void insertChunk(Connection connection, UUID tenant, UUID kb, UUID file, UUID id,
                                    String content, int index, Integer page, String sectionPath) throws Exception {
        execute(connection, """
                INSERT INTO knowledge_chunks
                    (id, tenant_id, knowledge_base_id, file_id, chunk_index, content, content_hash,
                     token_count, page_number, section_path, index_status, embedding_model_spec)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?::jsonb, 'READY', 'test/embedding-model')
                """, id, tenant, kb, file, index, content, "b".repeat(64), page, sectionPath);
    }

    private static void execute(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static <T> T withTenant(UUID tenantId, java.util.concurrent.Callable<T> invocation) {
        TenantContext.clear();
        TenantContext.set(new TenantPrincipal(TenantId.of(tenantId), UUID.randomUUID(),
                Set.of("ADMIN"), Set.of("KNOWLEDGE_RETRIEVE")));
        try {
            return invocation.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            TenantContext.clear();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RetrievalTestConfiguration {

        @Bean
        @Primary
        EmbeddingGateway retrievalTestEmbeddingGateway() {
            return request -> {
                EMBEDDING_CALLS.incrementAndGet();
                return new EmbeddingResult(List.of(vector()), DIMENSION,
                        "embedding-model", 1, 2);
            };
        }
    }
}
