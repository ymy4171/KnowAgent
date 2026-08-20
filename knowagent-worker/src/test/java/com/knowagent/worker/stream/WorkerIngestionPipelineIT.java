package com.knowagent.worker.stream;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.service.KnowledgeFileIngestionService;
import com.knowagent.knowledge.application.service.KnowledgeFileService;
import com.knowagent.knowledge.application.service.UploadFileCommand;
import com.knowagent.knowledge.application.service.UploadFileResult;
import com.knowagent.knowledge.document.ParseSource;
import com.knowagent.knowledge.document.ParsedDocument;
import com.knowagent.knowledge.document.ParserRegistry;
import com.knowagent.knowledge.vector.VectorChunk;
import com.knowagent.knowledge.vector.VectorHit;
import com.knowagent.knowledge.vector.VectorQuery;
import com.knowagent.knowledge.vector.VectorStoreGateway;
import com.knowagent.model.embedding.EmbeddingGateway;
import com.knowagent.model.embedding.EmbeddingRequest;
import com.knowagent.model.embedding.EmbeddingResult;
import com.knowagent.observability.application.service.OutboxPublisherService;
import com.knowagent.observability.outbox.OutboxEvent;
import com.knowagent.security.context.TenantContext;
import com.knowagent.security.principal.TenantPrincipal;
import com.knowagent.worker.KnowAgentWorkerApplication;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.milvus.MilvusContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL 16 + Redis 7 + MinIO + Milvus 2.5.6 ingestion integration. */
@Testcontainers
class WorkerIngestionPipelineIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowagent").withUsername("knowagent").withPassword("integration_only");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2023-03-20T20-16-18Z")
            .withUserName("knowagent").withPassword("knowagent_dev");
    @Container
    static final MilvusContainer MILVUS = new MilvusContainer("milvusdb/milvus:v2.5.6");

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final UUID KNOWLEDGE_BASE_ID = UUID.randomUUID();
    private static final String COLLECTION = "worker_ingestion_it";

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static KnowledgeFileService files;
    private static OutboxPublisherService outbox;
    private static StringRedisTemplate redis;
    private static IngestionEventCodec codec;
    private static WorkerTenantScope tenantScope;
    private static KnowledgeFileIngestionService ingestion;
    private static VectorStoreGateway vectors;
    private static TestEmbeddingGateway embeddings;
    private static TestParserRegistry parsers;
    private static TestVectorStoreGateway testVectors;

    @BeforeAll
    static void boot() {
        String migrations = Path.of("..", "knowagent-api", "src", "main", "resources", "db", "migration")
                .toAbsolutePath().normalize().toString().replace('\\', '/');
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + migrations).load().migrate();

        context = new SpringApplicationBuilder(KnowAgentWorkerApplication.class, TestBeans.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.url=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                        "--spring.flyway.enabled=false",
                        "--minio.endpoint=" + MINIO.getS3URL(),
                        "--minio.access-key=" + MINIO.getUserName(),
                        "--minio.secret-key=" + MINIO.getPassword(),
                        "--minio.bucket=knowledge",
                        "--knowagent.vector.milvus.uri=" + MILVUS.getEndpoint(),
                        "--knowagent.vector.milvus.collection-name=" + COLLECTION,
                        "--knowagent.vector.milvus.dimension=4",
                        "--knowagent.vector.milvus.init-timeout=3m",
                        "--knowagent.worker.stream.publisher-enabled=false",
                        "--knowagent.worker.stream.consumer-enabled=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        jdbc = context.getBean(JdbcTemplate.class);
        files = context.getBean(KnowledgeFileService.class);
        outbox = context.getBean(OutboxPublisherService.class);
        redis = context.getBean(StringRedisTemplate.class);
        codec = context.getBean(IngestionEventCodec.class);
        tenantScope = context.getBean(WorkerTenantScope.class);
        ingestion = context.getBean(KnowledgeFileIngestionService.class);
        vectors = context.getBean(VectorStoreGateway.class);
        embeddings = context.getBean(TestEmbeddingGateway.class);
        parsers = context.getBean(TestParserRegistry.class);
        testVectors = context.getBean(TestVectorStoreGateway.class);
        seedTenantAndKnowledgeBase();
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void txtCrashAfterRedisBeforePgMarkDeliversTwiceButExecutesBusinessOnce() throws Exception {
        Delivery delivery = delivery("crash");
        UploadFileResult uploaded = upload("source.txt", "alpha beta gamma\n".getBytes());
        UUID eventId = UUID.fromString(uploaded.file().processingParams().path("outbox_event_id").asText());
        OutboxEvent claimed = outbox.claim(1, "crashed-publisher", Duration.ofMillis(50)).getFirst();

        redis.opsForStream().add(StreamRecords.newRecord().in(delivery.properties().key())
                .ofStrings(java.util.Map.of(IngestionEventCodec.ENVELOPE_FIELD, codec.encode(claimed))));
        Thread.sleep(100);
        delivery.publisher().publishReady(); // writes the duplicate, then marks PostgreSQL PUBLISHED
        assertThat(redis.opsForStream().size(delivery.properties().key())).isEqualTo(2);

        embeddings.blockNextCall();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> processing = executor.submit(delivery.consumer()::pollNew);
            assertThat(embeddings.awaitEntered()).isTrue();
            assertFileAndTask(uploaded, "EMBEDDING", "RUNNING", "EMBEDDING", 55);
            embeddings.release();
            processing.get(30, TimeUnit.SECONDS);
        }

        assertReadyAndIndexed(uploaded);
        assertThat(count("SELECT count(*) FROM inbox_events WHERE consumer_name = ? AND event_id = ?",
                delivery.properties().group(), eventId)).isEqualTo(1);
        assertThat(value("SELECT status FROM outbox_events WHERE id = ?", String.class, eventId))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void pdfPendingMessageIsReclaimedAfterTheOriginalConsumerDies() throws Exception {
        Delivery delivery = delivery("reclaim");
        UploadFileResult uploaded = upload("source.pdf", pdf("PDF pipeline content"));
        delivery.publisher().publishReady();
        redis.opsForStream().createGroup(delivery.properties().key(), ReadOffset.from("0-0"),
                delivery.properties().group());
        var deadRead = redis.opsForStream().read(
                Consumer.from(delivery.properties().group(), "dead-worker"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(delivery.properties().key(), ReadOffset.lastConsumed()));
        assertThat(deadRead).hasSize(1);
        assertThat(redis.opsForStream().pending(delivery.properties().key(),
                delivery.properties().group()).getTotalPendingMessages()).isEqualTo(1);

        Thread.sleep(100);
        delivery.consumer().reclaimPending();

        assertReadyAndIndexed(uploaded);
        assertThat(redis.opsForStream().pending(delivery.properties().key(),
                delivery.properties().group()).getTotalPendingMessages()).isZero();
    }

    @Test
    void docxFlowsFromMinioToReadyWithSynchronizedTaskProgress() throws Exception {
        Delivery delivery = delivery("docx");
        UploadFileResult uploaded = upload("source.docx", docx("DOCX pipeline content"));

        delivery.publisher().publishReady();
        delivery.consumer().pollNew();

        assertReadyAndIndexed(uploaded);
    }

    @Test
    void twoWorkersReceivingTheSameEventDoNotDuplicateChunksOrVectors() throws Exception {
        String suffix = "concurrent-" + UUID.randomUUID();
        IngestionStreamProperties firstProperties = properties(suffix, "worker-a", 1);
        IngestionStreamProperties secondProperties = properties(suffix, "worker-b", 1);
        RedisIngestionConsumer first = new RedisIngestionConsumer(
                redis, codec, tenantScope, ingestion, firstProperties);
        RedisIngestionConsumer second = new RedisIngestionConsumer(
                redis, codec, tenantScope, ingestion, secondProperties);
        UploadFileResult uploaded = upload("concurrent.txt", "one event, two workers".getBytes());
        OutboxEvent claimed = outbox.claim(1, "concurrent-publisher", Duration.ofMinutes(1)).getFirst();
        String envelope = codec.encode(claimed);
        redis.opsForStream().add(StreamRecords.newRecord().in(firstProperties.key())
                .ofStrings(java.util.Map.of(IngestionEventCodec.ENVELOPE_FIELD, envelope)));
        redis.opsForStream().add(StreamRecords.newRecord().in(firstProperties.key())
                .ofStrings(java.util.Map.of(IngestionEventCodec.ENVELOPE_FIELD, envelope)));
        outbox.publish(claimed);

        int callsBefore = embeddings.callCount();
        embeddings.blockNextCall();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> active = executor.submit(first::pollNew);
            assertThat(embeddings.awaitEntered()).isTrue();
            second.pollNew();
            embeddings.release();
            active.get(30, TimeUnit.SECONDS);
        }
        assertThat(redis.opsForStream().pending(firstProperties.key(), firstProperties.group())
                .getTotalPendingMessages()).isEqualTo(1);
        Thread.sleep(100);
        second.reclaimPending();

        assertReadyAndIndexed(uploaded);
        assertThat(embeddings.callCount() - callsBefore).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM knowledge_chunks WHERE file_id = ?", Long.class,
                uploaded.file().id())).isEqualTo(value(
                "SELECT count(DISTINCT chunk_index) FROM knowledge_chunks WHERE file_id = ?",
                Long.class, uploaded.file().id()));
        assertThat(redis.opsForStream().pending(firstProperties.key(), firstProperties.group())
                .getTotalPendingMessages()).isZero();
    }

    @Test
    void transientParserFailureIsRetriedFromParsingAndCompletesOnAttemptTwo() throws Exception {
        Delivery delivery = delivery("parser-retry");
        UploadFileResult uploaded = upload("retry-parser.txt", "parser retry".getBytes());
        parsers.failNext(ErrorCode.DOCUMENT_TIMEOUT);

        delivery.publisher().publishReady();
        delivery.consumer().pollNew();

        assertRetryScheduled(uploaded, delivery, ErrorCode.DOCUMENT_TIMEOUT, 10);
        makeRetryDue(uploaded);
        delivery.consumer().reclaimPending();

        assertReadyAndIndexed(uploaded, 2);
        assertPendingIsEmpty(delivery);
    }

    @Test
    void transientEmbeddingFailureIsRetriedFromParsingAndCompletesOnAttemptTwo() throws Exception {
        Delivery delivery = delivery("embedding-retry");
        UploadFileResult uploaded = upload("retry-embedding.txt", "embedding retry".getBytes());
        embeddings.failNext(ErrorCode.MODEL_RATE_LIMITED);

        delivery.publisher().publishReady();
        delivery.consumer().pollNew();

        assertRetryScheduled(uploaded, delivery, ErrorCode.MODEL_RATE_LIMITED, 55);
        makeRetryDue(uploaded);
        delivery.consumer().reclaimPending();

        assertReadyAndIndexed(uploaded, 2);
        assertPendingIsEmpty(delivery);
    }

    @Test
    void transientMilvusFailureIsRetriedFromParsingAndCompletesOnAttemptTwo() throws Exception {
        Delivery delivery = delivery("milvus-retry");
        UploadFileResult uploaded = upload("retry-milvus.txt", "milvus retry".getBytes());
        testVectors.failNextUpsert(ErrorCode.VECTOR_UNAVAILABLE);

        delivery.publisher().publishReady();
        delivery.consumer().pollNew();

        assertRetryScheduled(uploaded, delivery, ErrorCode.VECTOR_UNAVAILABLE, 80);
        makeRetryDue(uploaded);
        delivery.consumer().reclaimPending();

        assertReadyAndIndexed(uploaded, 2);
        assertPendingIsEmpty(delivery);
    }

    private static Delivery delivery(String label) {
        String suffix = label + "-" + UUID.randomUUID();
        IngestionStreamProperties properties = properties(suffix, "survivor-" + suffix, 20);
        return new Delivery(properties,
                new RedisOutboxPublisher(outbox, redis, codec, properties),
                new RedisIngestionConsumer(redis, codec, tenantScope, ingestion, properties));
    }

    private static IngestionStreamProperties properties(String suffix, String consumer, int batchSize) {
        return new IngestionStreamProperties(
                "knowagent:it:" + suffix, "group-" + suffix, consumer, batchSize,
                Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMinutes(5),
                Duration.ofMillis(50), true, true);
    }

    private static UploadFileResult upload(String filename, byte[] bytes) {
        TenantContext.set(new TenantPrincipal(TenantId.of(TENANT_ID), USER_ID,
                Set.of("KNOWLEDGE_EDITOR"), Set.of("knowledge:file:write")));
        try {
            return files.upload(new UploadFileCommand(TenantId.of(TENANT_ID), KNOWLEDGE_BASE_ID,
                    USER_ID, UUID.randomUUID().toString(), filename, new ByteArrayInputStream(bytes)));
        } finally {
            TenantContext.clear();
        }
    }

    private static void assertReadyAndIndexed(UploadFileResult uploaded) {
        assertReadyAndIndexed(uploaded, 1);
    }

    private static void assertReadyAndIndexed(UploadFileResult uploaded, int expectedAttempts) {
        UUID fileId = uploaded.file().id();
        UUID taskId = uploaded.taskId();
        assertFileAndTask(uploaded, "READY", "SUCCEEDED", "READY", 100);
        Integer chunkCount = value("SELECT chunk_count FROM knowledge_files WHERE id = ?", Integer.class, fileId);
        assertThat(chunkCount).isPositive();
        List<UUID> pgIds = jdbc.queryForList(
                "SELECT id FROM knowledge_chunks WHERE tenant_id = ? AND knowledge_base_id = ? AND file_id = ? ORDER BY chunk_index",
                UUID.class, TENANT_ID, KNOWLEDGE_BASE_ID, fileId);
        assertThat(pgIds).hasSize(chunkCount);
        assertThat(jdbc.queryForList("SELECT index_status FROM knowledge_chunks WHERE file_id = ?",
                String.class, fileId)).containsOnly("READY");
        assertThat(vectors.search(new VectorQuery(TenantId.of(TENANT_ID), KNOWLEDGE_BASE_ID,
                new float[]{1F, 0F, 0F, 0F}, 100, -1D, List.of(fileId))))
                .extracting(hit -> hit.chunkId()).containsExactlyInAnyOrderElementsOf(pgIds);
        assertThat(value("SELECT attempt_count FROM tasks WHERE id = ?", Integer.class, taskId))
                .isEqualTo(expectedAttempts);
    }

    private static void assertRetryScheduled(UploadFileResult uploaded, Delivery delivery,
                                             ErrorCode errorCode, int progress) {
        assertFileAndTask(uploaded, "FAILED", "PENDING", "RETRY_WAIT", progress);
        assertThat(value("SELECT error_code FROM knowledge_files WHERE id = ?", String.class,
                uploaded.file().id())).isEqualTo(errorCode.name());
        assertThat(value("SELECT retryable FROM knowledge_files WHERE id = ?", Boolean.class,
                uploaded.file().id())).isTrue();
        assertThat(value("SELECT error_code FROM tasks WHERE id = ?", String.class,
                uploaded.taskId())).isEqualTo(errorCode.name());
        assertThat(value("SELECT retryable FROM tasks WHERE id = ?", Boolean.class,
                uploaded.taskId())).isTrue();
        assertThat(value("SELECT attempt_count FROM tasks WHERE id = ?", Integer.class,
                uploaded.taskId())).isEqualTo(1);
        assertThat(value("SELECT next_retry_at IS NOT NULL FROM tasks WHERE id = ?", Boolean.class,
                uploaded.taskId())).isTrue();
        UUID eventId = UUID.fromString(uploaded.file().processingParams().path("outbox_event_id").asText());
        assertThat(count("SELECT count(*) FROM inbox_events WHERE consumer_name = ? AND event_id = ?",
                delivery.properties().group(), eventId)).isZero();
    }

    private static void makeRetryDue(UploadFileResult uploaded) throws InterruptedException {
        jdbc.update("UPDATE tasks SET next_retry_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                uploaded.taskId());
        Thread.sleep(100);
    }

    private static void assertPendingIsEmpty(Delivery delivery) {
        assertThat(redis.opsForStream().pending(delivery.properties().key(), delivery.properties().group())
                .getTotalPendingMessages()).isZero();
    }

    private static void assertFileAndTask(UploadFileResult uploaded, String fileStatus,
                                          String taskStatus, String stage, int progress) {
        assertThat(value("SELECT status FROM knowledge_files WHERE id = ?", String.class, uploaded.file().id()))
                .isEqualTo(fileStatus);
        assertThat(value("SELECT status FROM tasks WHERE id = ?", String.class, uploaded.taskId()))
                .isEqualTo(taskStatus);
        assertThat(value("SELECT stage FROM tasks WHERE id = ?", String.class, uploaded.taskId()))
                .isEqualTo(stage);
        assertThat(value("SELECT progress FROM tasks WHERE id = ?", Integer.class, uploaded.taskId()))
                .isEqualTo(progress);
    }

    private static <T> T value(String sql, Class<T> type, Object... args) {
        return jdbc.queryForObject(sql, type, args);
    }

    private static long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static void seedTenantAndKnowledgeBase() {
        jdbc.update("INSERT INTO tenants(id, slug, name) VALUES (?, 'worker-it', 'Worker IT')", TENANT_ID);
        jdbc.update("INSERT INTO users(id, tenant_id, login_name, display_name, password_hash) VALUES (?, ?, 'worker', 'Worker', 'not-used')",
                USER_ID, TENANT_ID);
        jdbc.update("""
                INSERT INTO model_providers(id, tenant_id, provider_key, display_name, base_url,
                                            capabilities, enabled_models, public_config, created_by, updated_by)
                VALUES (?, ?, 'embedding-it', 'Embedding IT', 'http://127.0.0.1',
                        '["EMBEDDING"]'::jsonb, '["embedding-it"]'::jsonb, '{}'::jsonb, ?, ?)
                """, PROVIDER_ID, TENANT_ID, USER_ID, USER_ID);
        jdbc.update("""
                INSERT INTO knowledge_bases(id, tenant_id, slug, name, embedding_provider_id,
                                            embedding_model, chunk_policy, retrieval_config,
                                            created_by, updated_by)
                VALUES (?, ?, 'worker-it', 'Worker IT', ?, 'embedding-it',
                        '{"strategy":"RECURSIVE","maxTokens":800,"overlapTokens":100}'::jsonb,
                        '{"topK":10,"scoreThreshold":0.0,"rerankEnabled":false}'::jsonb, ?, ?)
                """, KNOWLEDGE_BASE_ID, TENANT_ID, PROVIDER_ID, USER_ID, USER_ID);
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }

    private record Delivery(IngestionStreamProperties properties,
                            RedisOutboxPublisher publisher,
                            RedisIngestionConsumer consumer) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        TestEmbeddingGateway testEmbeddingGateway() {
            return new TestEmbeddingGateway();
        }

        @Bean
        @Primary
        TestParserRegistry testParserRegistry(@Qualifier("parserRegistry") ParserRegistry delegate) {
            return new TestParserRegistry(delegate);
        }

        @Bean
        @Primary
        TestVectorStoreGateway testVectorStoreGateway(
                @Qualifier("vectorStoreGateway") VectorStoreGateway delegate) {
            return new TestVectorStoreGateway(delegate);
        }
    }

    static final class TestEmbeddingGateway implements EmbeddingGateway {
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<BusinessException> nextFailure = new AtomicReference<>();

        void failNext(ErrorCode errorCode) {
            nextFailure.set(new BusinessException(errorCode, "injected integration failure"));
        }

        void blockNextCall() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(10, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
            calls.incrementAndGet();
            BusinessException failure = nextFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            CountDownLatch currentEntered = entered;
            CountDownLatch currentRelease = release;
            if (currentEntered != null && currentRelease != null) {
                currentEntered.countDown();
                try {
                    if (!currentRelease.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test embedding release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test embedding interrupted", interrupted);
                } finally {
                    entered = null;
                    release = null;
                }
            }
            List<float[]> result = new ArrayList<>(request.texts().size());
            request.texts().forEach(ignored -> result.add(new float[]{1F, 0F, 0F, 0F}));
            return new EmbeddingResult(result, 4, request.model(), 1, request.texts().size());
        }
    }

    static final class TestParserRegistry extends ParserRegistry {
        private final ParserRegistry delegate;
        private final AtomicReference<BusinessException> nextFailure = new AtomicReference<>();

        TestParserRegistry(ParserRegistry delegate) {
            super(List.of());
            this.delegate = delegate;
        }

        void failNext(ErrorCode errorCode) {
            nextFailure.set(new BusinessException(errorCode, "injected integration failure"));
        }

        @Override
        public ParsedDocument parse(ParseSource source) {
            BusinessException failure = nextFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return delegate.parse(source);
        }
    }

    static final class TestVectorStoreGateway implements VectorStoreGateway {
        private final VectorStoreGateway delegate;
        private final AtomicReference<BusinessException> nextUpsertFailure = new AtomicReference<>();

        TestVectorStoreGateway(VectorStoreGateway delegate) {
            this.delegate = delegate;
        }

        void failNextUpsert(ErrorCode errorCode) {
            nextUpsertFailure.set(new BusinessException(errorCode, "injected integration failure"));
        }

        @Override
        public void upsert(List<VectorChunk> chunks) {
            BusinessException failure = nextUpsertFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            delegate.upsert(chunks);
        }

        @Override
        public List<VectorHit> search(VectorQuery query) {
            return delegate.search(query);
        }

        @Override
        public void deleteByFile(UUID tenantId, UUID knowledgeBaseId, UUID fileId) {
            delegate.deleteByFile(tenantId, knowledgeBaseId, fileId);
        }
    }
}
