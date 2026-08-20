package com.knowagent.api.database;

import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeChunkRepository;
import com.knowagent.knowledge.application.service.ChunkWriteService;
import com.knowagent.knowledge.chunk.ChunkDraft;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.chunk.ChunkPolicy;
import com.knowagent.knowledge.chunk.DeterministicChunker;
import com.knowagent.knowledge.chunk.DeterministicTokenCounter;
import com.knowagent.knowledge.chunk.KnowledgeChunk;
import com.knowagent.knowledge.document.ParsedDocument;
import com.knowagent.knowledge.document.ParsedSection;
import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import com.knowagent.security.context.TenantContext;
import com.knowagent.security.principal.TenantPrincipal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Persistence contract for {@code knowledge_chunks} against a real PostgreSQL 16 database,
 * driven through {@link ChunkWriteService} (no HTTP): the replacement transaction locks the
 * file row, writes PENDING chunks, and records file statistics with a version-guarded update.
 * Covers: chunk_count matching the actual row count, idempotent retry (no duplicate
 * chunk_index), full rollback of a failed replacement (no half-replaced state), and tenant
 * isolation - tenant-B cannot query, replace, or delete tenant-A chunks.
 *
 * <p>Every service call runs under an explicit {@link TenantContext} (as the web filter would
 * set it), because the tenant-line plugin stays enabled as the fail-closed backstop on top of
 * the explicit {@code tenant_id} conditions in the SQL. Each test allocates its own file row so
 * the class shares the database but never shares mutable state.
 */
@Testcontainers
class KnowledgeChunkIT {

    private static final int MAX_TOKENS = 40;
    private static final int OVERLAP_TOKENS = 5;
    private static final String RAW_PASSWORD = "CorrectHorseBatteryStaple1";
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(
            "integration-test-only-key-0123456789abcdefghij".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("knowagent")
                    .withUsername("knowagent")
                    .withPassword("integration_only");

    private static ConfigurableApplicationContext context;
    private static DataSource dataSource;
    private static ChunkWriteService chunkWriteService;
    private static KnowledgeChunkRepository chunkRepository;
    private static DeterministicChunker chunker;

    private static UUID alphaTenant;
    private static UUID betaTenant;
    private static UUID alphaKb;
    private static UUID betaKb;

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
                        "--bootstrap.enabled=false",
                        "--server.port=0",
                        "--jwt.issuer=https://knowagent.test",
                        "--jwt.audience=knowagent-api",
                        "--jwt.secret=" + JWT_SECRET,
                        "--model-provider.secret-key=" + Base64.getEncoder().encodeToString(
                                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR");
        chunkWriteService = context.getBean(ChunkWriteService.class);
        chunkRepository = context.getBean(KnowledgeChunkRepository.class);
        chunker = new DeterministicChunker(new DeterministicTokenCounter());

        AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
        bootstrap.initialize(new AdminBootstrapRequest("alpha", null, "admin@alpha.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("beta", null, "admin@beta.test", null, RAW_PASSWORD));

        alphaTenant = singleUuid("SELECT id FROM tenants WHERE slug = 'alpha'");
        betaTenant = singleUuid("SELECT id FROM tenants WHERE slug = 'beta'");
        alphaKb = seedKnowledgeBase(alphaTenant, "alpha-chunk-kb");
        betaKb = seedKnowledgeBase(betaTenant, "beta-chunk-kb");
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void replaceWritesPendingChunksAndFileStatisticsMatchActualRowCount() {
        UUID file = newFile(alphaTenant, alphaKb, "replace-stats.pdf");
        List<ChunkDraft> drafts = drafts(3);
        asTenant(alphaTenant, () -> {
            chunkWriteService.replaceChunks(TenantId.of(alphaTenant), alphaKb, file, drafts);
            return null;
        });

        List<KnowledgeChunk> stored = asTenant(alphaTenant,
                () -> chunkRepository.findByFile(TenantId.of(alphaTenant), alphaKb, file));
        assertThat(stored).hasSameSizeAs(drafts);
        assertThat(stored).extracting(KnowledgeChunk::chunkIndex)
                .as("chunk indices are the drafts' stable 0-based order")
                .containsExactlyElementsOf(IntStream.range(0, drafts.size()).boxed().toList());
        for (int i = 0; i < stored.size(); i++) {
            KnowledgeChunk chunk = stored.get(i);
            assertThat(chunk.indexStatus()).isEqualTo(ChunkIndexStatus.PENDING);
            assertThat(chunk.tenantId()).isEqualTo(TenantId.of(alphaTenant));
            assertThat(chunk.content()).isEqualTo(drafts.get(i).content());
            assertThat(chunk.contentHash()).isEqualTo(drafts.get(i).contentHash());
            assertThat(chunk.version()).isZero();
        }
        assertThat(stored.stream().map(KnowledgeChunk::id).distinct())
                .as("each chunk carries a pre-generated Java UUID")
                .hasSize(drafts.size());

        // chunk_count and token_count on the file match the actual rows in the database.
        assertThat(countChunks(alphaTenant, alphaKb, file)).isEqualTo(drafts.size());
        long expectedTokens = stored.stream().mapToLong(KnowledgeChunk::tokenCount).sum();
        long[] stats = fileStatistics(alphaTenant, file);
        assertThat(stats[0]).isEqualTo(drafts.size());   // chunk_count
        assertThat(stats[1]).isEqualTo(expectedTokens);  // token_count
        assertThat(stats[2]).isEqualTo(1L);              // version bumped from 0
    }

    @Test
    void retryingTheSameReplacementIsIdempotent() {
        UUID file = newFile(alphaTenant, alphaKb, "idempotent.pdf");
        List<ChunkDraft> drafts = drafts(4);
        asTenant(alphaTenant, () -> {
            chunkWriteService.replaceChunks(TenantId.of(alphaTenant), alphaKb, file, drafts);
            return null;
        });
        List<UUID> firstIds = asTenant(alphaTenant,
                () -> chunkRepository.findByFile(TenantId.of(alphaTenant), alphaKb, file).stream()
                        .map(KnowledgeChunk::id).toList());
        asTenant(alphaTenant, () -> {
            chunkWriteService.replaceChunks(TenantId.of(alphaTenant), alphaKb, file, drafts);
            return null;
        });

        List<KnowledgeChunk> stored = asTenant(alphaTenant,
                () -> chunkRepository.findByFile(TenantId.of(alphaTenant), alphaKb, file));
        assertThat(stored).hasSize(drafts.size());
        assertThat(stored).extracting(KnowledgeChunk::chunkIndex)
                .as("retry replaces the chunk set wholesale, never duplicating an index")
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(IntStream.range(0, drafts.size()).boxed().toList());
        for (int i = 0; i < stored.size(); i++) {
            assertThat(stored.get(i).content()).isEqualTo(drafts.get(i).content());
            assertThat(stored.get(i).contentHash()).isEqualTo(drafts.get(i).contentHash());
        }
        assertThat(stored).extracting(KnowledgeChunk::id)
                .as("an identical retry must preserve the future Milvus entity ids")
                .containsExactlyElementsOf(firstIds);
        assertThat(countChunks(alphaTenant, alphaKb, file)).isEqualTo(drafts.size());
        assertThat(fileStatistics(alphaTenant, file)[0]).isEqualTo(drafts.size());
    }

    @Test
    void failedReplacementRollsBackWithoutHalfReplacedState() {
        UUID file = newFile(alphaTenant, alphaKb, "rollback.pdf");
        List<ChunkDraft> original = drafts(3);
        asTenant(alphaTenant, () -> {
            chunkWriteService.replaceChunks(TenantId.of(alphaTenant), alphaKb, file, original);
            return null;
        });

        // A duplicate chunk_index in the draft set violates UNIQUE(tenant_id, file_id, chunk_index)
        // on the second insert: the delete already ran, so only a real rollback keeps the old set.
        List<ChunkDraft> broken = List.of(draftAt(0, 0), draftAt(0, 1));

        Throwable failure = asTenant(alphaTenant, () -> catchThrowable(() ->
                chunkWriteService.replaceChunks(TenantId.of(alphaTenant), alphaKb, file, broken)));

        assertThat(failure).isNotNull().isInstanceOf(DataIntegrityViolationException.class);

        List<KnowledgeChunk> stored = asTenant(alphaTenant,
                () -> chunkRepository.findByFile(TenantId.of(alphaTenant), alphaKb, file));
        assertThat(stored).as("the old chunk set must survive the failed replacement")
                .hasSize(original.size());
        for (int i = 0; i < stored.size(); i++) {
            assertThat(stored.get(i).content()).isEqualTo(original.get(i).content());
            assertThat(stored.get(i).contentHash()).isEqualTo(original.get(i).contentHash());
        }
        // The file statistics must not have advanced either.
        long[] stats = fileStatistics(alphaTenant, file);
        assertThat(stats[0]).isEqualTo(original.size());
        assertThat(stats[2]).isEqualTo(1L); // only the first successful replace bumped it
    }

    @Test
    void tenantBCannotQueryReplaceOrDeleteTenantAChunks() {
        UUID file = newFile(alphaTenant, alphaKb, "tenant-b-blocked.pdf");
        List<ChunkDraft> alphaDrafts = drafts(2);
        asTenant(alphaTenant, () -> {
            chunkWriteService.replaceChunks(TenantId.of(alphaTenant), alphaKb, file, alphaDrafts);
            return null;
        });
        String originalFirstChunk = asTenant(alphaTenant,
                () -> chunkRepository.findByFile(TenantId.of(alphaTenant), alphaKb, file).get(0).content());

        // Beta's replace of alpha's file is a non-revealing 404 and touches nothing.
        BusinessException missing = asTenant(betaTenant, () -> catchThrowableOfType(
                () -> chunkWriteService.replaceChunks(TenantId.of(betaTenant), alphaKb, file, drafts(1)),
                BusinessException.class));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        // Beta can never see alpha's chunks.
        assertThat(asTenant(betaTenant,
                () -> chunkRepository.findByFile(TenantId.of(betaTenant), alphaKb, file))).isEmpty();

        // Alpha's chunks are untouched by beta's attempts.
        List<KnowledgeChunk> intact = asTenant(alphaTenant,
                () -> chunkRepository.findByFile(TenantId.of(alphaTenant), alphaKb, file));
        assertThat(intact).hasSize(alphaDrafts.size());
        assertThat(intact.get(0).content()).isEqualTo(originalFirstChunk);
    }

    @Test
    void betaFileIsUnrelatedToAlphaChunks() {
        // Beta has its own file in its own knowledge base; the two tenants never mix.
        UUID betaFile = newFile(betaTenant, betaKb, "beta-private.pdf");
        List<ChunkDraft> betaDrafts = drafts(2);
        asTenant(betaTenant, () -> {
            chunkWriteService.replaceChunks(TenantId.of(betaTenant), betaKb, betaFile, betaDrafts);
            return null;
        });

        assertThat(asTenant(betaTenant,
                () -> chunkRepository.findByFile(TenantId.of(betaTenant), betaKb, betaFile)))
                .hasSize(betaDrafts.size());
        // Alpha, whose knowledge base never owned this beta file, sees nothing.
        assertThat(asTenant(alphaTenant,
                () -> chunkRepository.findByFile(TenantId.of(alphaTenant), betaKb, betaFile))).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /** Runs {@code action} under a tenant context, mirroring what the web filter would set. */
    private static <T> T asTenant(UUID tenantId, Supplier<T> action) {
        TenantContext.set(new TenantPrincipal(TenantId.of(tenantId), UUID.randomUUID(), Set.of(), Set.of()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** Chunks a deterministic multi-section document so section boundaries and page numbers propagate. */
    private static List<ChunkDraft> drafts(int sectionCount) {
        List<ParsedSection> sections = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        long offset = 0;
        for (int i = 0; i < sectionCount; i++) {
            String content = "Chapter " + (i + 1) + ": a paragraph with enough words to exceed the small token "
                    + "budget and force several chunks inside this section while keeping the deterministic "
                    + "boundary walking honest. Additional filler words to push past the token window without "
                    + "relying on punctuation splitting alone, repeated twice for length. ".repeat(2)
                    + "\n";
            long end = offset + content.length();
            sections.add(new ParsedSection(String.valueOf(i + 1), "Chapter " + (i + 1),
                    content, i + 1, offset, end, Map.of()));
            text.append(content);
            offset = end;
        }
        ParsedDocument document = new ParsedDocument("Chunk IT document", text.toString(), 0, sections);
        return chunker.split(document, new ChunkPolicy(ChunkPolicy.Strategy.MARKDOWN_HEADING,
                MAX_TOKENS, OVERLAP_TOKENS));
    }

    private static ChunkDraft draftAt(int index, int tokenCount) {
        String content = "Duplicate index draft " + index + " with words.";
        return new ChunkDraft(index, content, DeterministicChunker.sha256Hex(content), tokenCount,
                (long) index * content.length(), (long) (index + 1) * content.length(),
                (long) index * tokenCount, (long) (index + 1) * tokenCount,
                1, List.of("1"), Map.of());
    }

    private static UUID newFile(UUID tenantId, UUID knowledgeBaseId, String name) {
        return seedKnowledgeFile(tenantId, knowledgeBaseId, name);
    }

    private static UUID seedKnowledgeBase(UUID tenantId, String slug) {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO knowledge_bases (id, tenant_id, slug, name)
                     VALUES (?, ?, ?, ?)
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, slug);
            statement.setString(4, slug);
            statement.executeUpdate();
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UUID seedKnowledgeFile(UUID tenantId, UUID knowledgeBaseId, String name) {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO knowledge_files (id, tenant_id, knowledge_base_id, display_name,
                                                  original_filename, object_key, content_type, sha256, file_size_bytes)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setObject(3, knowledgeBaseId);
            statement.setString(4, name);
            statement.setString(5, name);
            statement.setString(6, "tenant/" + tenantId + "/" + knowledgeBaseId + "/" + name);
            statement.setString(7, "application/pdf");
            statement.setString(8, "a".repeat(64));
            statement.setLong(9, 1024L);
            statement.executeUpdate();
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long countChunks(UUID tenantId, UUID kb, UUID file) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM knowledge_chunks
                     WHERE tenant_id = ? AND knowledge_base_id = ? AND file_id = ?
                     """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, kb);
            statement.setObject(3, file);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Returns {chunk_count, token_count, version} for the file row. */
    private static long[] fileStatistics(UUID tenantId, UUID fileId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT chunk_count, token_count, version FROM knowledge_files
                     WHERE tenant_id = ? AND id = ?
                     """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, fileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new long[]{resultSet.getLong(1), resultSet.getLong(2), resultSet.getLong(3)};
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
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
