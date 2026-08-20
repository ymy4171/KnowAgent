package com.knowagent.api.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.service.KnowledgeFileService;
import com.knowagent.security.application.port.out.PasswordHasher;
import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import com.knowagent.workspace.storage.DeleteObjectCommand;
import com.knowagent.workspace.storage.MinioObjectStorageAdapter;
import com.knowagent.workspace.storage.ObjectStorageGateway;
import com.knowagent.workspace.storage.StorageKeys;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import jakarta.servlet.Filter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end knowledge-file upload on real PostgreSQL 16 and MinIO (Testcontainers):
 * TXT/PDF/DOCX accepted asynchronously ({@code 202}, file/task/outbox written in one
 * transaction), empty/oversize/fake-MIME/unknown content stably rejected by content
 * sniffing, idempotency replay vs conflict, compensation of the orphaned object when
 * the database transaction fails after storage, 401/403/cross-tenant 404 for the read
 * paths, and the never-leak rule for bucket/object keys in every response.
 */
@Testcontainers
class KnowledgeFileUploadIT {

    private static final String ISSUER = "https://knowagent.test";
    private static final String AUDIENCE = "knowagent-api";
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(
            "integration-test-only-key-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
    private static final String RAW_PASSWORD = "CorrectHorseBatteryStaple1";
    private static final String BUCKET = "knowledge";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("knowagent")
                    .withUsername("knowagent")
                    .withPassword("integration_only");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2023-03-20T20-16-18Z")
            .withUserName("knowagent")
            .withPassword("knowagent_dev");

    private static ConfigurableApplicationContext context;
    private static MockMvc mockMvc;
    private static PGSimpleDataSource dataSource;
    private static ObjectStorageGateway storage;
    private static MinioClient rawClient;

    private static UUID alphaTenant;
    private static UUID betaTenant;
    private static String adminToken;
    private static String betaToken;
    private static String viewerToken;

    // Rows/objects created by the current test, removed in @AfterEach.
    private final List<UUID> knowledgeBasesToDelete = new ArrayList<>();
    private final List<FileRef> filesToDelete = new ArrayList<>();

    @BeforeAll
    static void bootContext() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

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
                        "--minio.endpoint=" + MINIO.getS3URL(),
                        "--minio.access-key=" + MINIO.getUserName(),
                        "--minio.secret-key=" + MINIO.getPassword(),
                        "--minio.bucket=" + BUCKET,
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR");
        mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();

        rawClient = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();
        storage = new MinioObjectStorageAdapter(rawClient, BUCKET);

        AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
        bootstrap.initialize(new AdminBootstrapRequest("alpha", null, "admin@alpha.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("beta", null, "admin@beta.test", null, RAW_PASSWORD));
        seedViewer();

        alphaTenant = tenantId("alpha");
        betaTenant = tenantId("beta");
        adminToken = login("alpha", "admin@alpha.test");
        betaToken = login("beta", "admin@beta.test");
        viewerToken = login("alpha", "viewer@alpha.test");
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @AfterEach
    void cleanup() {
        for (FileRef ref : filesToDelete) {
            deleteById("knowledge_files", ref.fileId());
            execute("DELETE FROM tasks WHERE aggregate_id = ?", ref.fileId().toString());
            execute("DELETE FROM outbox_events WHERE aggregate_id = ?", ref.fileId().toString());
            try {
                storage.delete(new DeleteObjectCommand(
                        TenantId.of(ref.tenantId()), StorageKeys.knowledgeFileSource(
                                TenantId.of(ref.tenantId()), ref.knowledgeBaseId(), ref.fileId())));
            } catch (RuntimeException ignored) {
                // the compensation test already removed the object; delete is idempotent anyway
            }
        }
        for (UUID id : knowledgeBasesToDelete) {
            deleteById("knowledge_bases", id);
        }
    }

    // ------------------------------------------------------------ uploadable types

    @Test
    void textPdfAndDocxUploadsAreAcceptedAndQueued() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");

        // TXT
        byte[] txt = "plain text content\n".getBytes(StandardCharsets.UTF_8);
        MvcResult txtResult = upload(kb, "notes.txt", MediaType.TEXT_PLAIN_VALUE, txt, null);
        assertThat(txtResult.getResponse().getStatus()).isEqualTo(202);
        JsonNode txtBody = accepted(txtResult);
        UUID txtFile = UUID.fromString(txtBody.path("fileId").asText());
        UUID txtTask = UUID.fromString(txtBody.path("taskId").asText());
        assertThat(txtBody.path("status").asText()).isEqualTo("QUEUED");
        assertThat(txtBody.path("sha256").asText()).isEqualTo(sha256(txt));
        assertThat(txtBody.path("fileSizeBytes").asLong()).isEqualTo(txt.length);
        filesToDelete.add(new FileRef(alphaTenant, kb, txtFile));

        // PDF (magic bytes, content-detected)
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.ISO_8859_1);
        JsonNode pdfBody = accepted(upload(kb, "report.pdf", "application/pdf", pdf, null));
        filesToDelete.add(new FileRef(alphaTenant, kb, UUID.fromString(pdfBody.path("fileId").asText())));

        // DOCX (minimal OOXML container, content-detected)
        byte[] docx = docxBytes();
        JsonNode docxBody = accepted(upload(kb, "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx, null));
        filesToDelete.add(new FileRef(alphaTenant, kb, UUID.fromString(docxBody.path("fileId").asText())));

        // The three objects exist under the deterministic tenant/kb prefix.
        List<String> objects = listObjects(TenantId.of(alphaTenant), kb);
        assertThat(objects).hasSize(3);
        assertThat(objects.get(0)).startsWith("tenants/" + alphaTenant + "/knowledge-bases/" + kb + "/files/");

        // One file row, one ingest task and one outbox event per upload.
        assertThat(singleLong("SELECT count(*) FROM knowledge_files WHERE knowledge_base_id = ?", kb)).isEqualTo(3);
        assertThat(singleLong("SELECT count(*) FROM tasks WHERE aggregate_id = ?", txtFile.toString())).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", txtFile.toString())).isEqualTo(1);
        assertThat(txtTask).isNotNull();
    }

    // ------------------------------------------------------------- stable rejections

    @Test
    void emptyContentIsRejected() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");

        MvcResult result = upload(kb, "empty.txt", MediaType.TEXT_PLAIN_VALUE, new byte[0], null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertError(result, "VALIDATION_ERROR");
        assertThat(objectCount(TenantId.of(alphaTenant), kb)).isZero();
    }

    @Test
    void oversizedContentIsRejectedAtTheSpoolBound() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");
        byte[] oversized = new byte[(int) KnowledgeFileService.MAX_UPLOAD_BYTES + 1];

        MvcResult result = upload(kb, "huge.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, oversized, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertError(result, "VALIDATION_ERROR");
        assertThat(objectCount(TenantId.of(alphaTenant), kb)).isZero();
        assertThat(singleLong("SELECT count(*) FROM knowledge_files WHERE knowledge_base_id = ?", kb)).isZero();
    }

    @Test
    void fakeMimeAndUnknownTypesAreRejectedByContentNotHeaderOrName() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");

        // Claimed text/plain, but the bytes are a PNG -> rejected by content sniffing.
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01};
        MvcResult fakeMime = upload(kb, "image.txt", MediaType.TEXT_PLAIN_VALUE, png, null);
        assertThat(fakeMime.getResponse().getStatus()).isEqualTo(400);
        assertError(fakeMime, "VALIDATION_ERROR");

        // Opaque binary with an unknown extension -> rejected.
        byte[] random = new byte[512];
        new java.security.SecureRandom().nextBytes(random);
        MvcResult unknown = upload(kb, "data.xyz", MediaType.APPLICATION_OCTET_STREAM_VALUE, random, null);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertError(unknown, "VALIDATION_ERROR");

        assertThat(objectCount(TenantId.of(alphaTenant), kb)).isZero();
        assertThat(singleLong("SELECT count(*) FROM knowledge_files WHERE knowledge_base_id = ?", kb)).isZero();
    }

    @Test
    void missingOrDisabledKnowledgeBaseIsRejected() throws Exception {
        MvcResult missing = upload(UUID.randomUUID(), "x.txt", MediaType.TEXT_PLAIN_VALUE,
                "x".getBytes(StandardCharsets.UTF_8), null);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertError(missing, "RESOURCE_NOT_FOUND");

        UUID disabled = newKnowledgeBase("DISABLED");
        MvcResult inactive = upload(disabled, "x.txt", MediaType.TEXT_PLAIN_VALUE,
                "x".getBytes(StandardCharsets.UTF_8), null);
        assertThat(inactive.getResponse().getStatus()).isEqualTo(409);
        assertError(inactive, "CONFLICT");
        assertThat(objectCount(TenantId.of(alphaTenant), disabled)).isZero();
    }

    // ----------------------------------------------------------- transaction safety

    @Test
    void databaseFailureAfterStorageCompensatesTheOrphanedObject() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");
        createFailInsertTrigger();
        try {
            try {
                MvcResult result = upload(kb, "doomed.txt", MediaType.TEXT_PLAIN_VALUE,
                        "will not survive".getBytes(StandardCharsets.UTF_8), null);
                // When the exception is surfaced as a 500 (no handler), it is still a failure.
                assertThat(result.getResponse().getStatus()).isEqualTo(500);
            } catch (jakarta.servlet.ServletException expected) {
                // MockMvc propagates the unhandled database exception instead of a 500
                // result. Either way the upload must never have been accepted.
            }
            // The transaction rolled back: no file/task/outbox rows survived.
            assertThat(singleLong("SELECT count(*) FROM knowledge_files WHERE knowledge_base_id = ?", kb)).isZero();
            // And the freshly uploaded object was compensated away.
            assertThat(objectCount(TenantId.of(alphaTenant), kb)).isZero();
        } finally {
            dropFailInsertTrigger();
        }
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void matchingIdempotencyKeyReplaysTheOriginalFileTaskAndOutbox() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");
        byte[] bytes = "stable payload".getBytes(StandardCharsets.UTF_8);
        String key = "key-" + UUID.randomUUID();

        JsonNode first = accepted(upload(kb, "guide.txt", MediaType.TEXT_PLAIN_VALUE, bytes, key));
        JsonNode second = accepted(upload(kb, "guide.txt", MediaType.TEXT_PLAIN_VALUE, bytes, key));

        UUID firstFile = UUID.fromString(first.path("fileId").asText());
        assertThat(first.path("replayed").asBoolean()).isFalse();
        assertThat(second.path("fileId").asText()).isEqualTo(firstFile.toString());
        assertThat(second.path("taskId").asText()).isEqualTo(first.path("taskId").asText());
        assertThat(second.path("replayed").asBoolean()).isTrue();
        filesToDelete.add(new FileRef(alphaTenant, kb, firstFile));

        // No second file, task or outbox event was created by the replay.
        assertThat(singleLong("SELECT count(*) FROM knowledge_files WHERE upload_idempotency_key = ?", key)).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM tasks WHERE aggregate_id = ?", firstFile.toString())).isEqualTo(1);
        assertThat(singleLong("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", firstFile.toString())).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentContentIsAConflict() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");
        byte[] firstContent = "first version".getBytes(StandardCharsets.UTF_8);
        byte[] different = "completely different".getBytes(StandardCharsets.UTF_8);
        String key = "key-" + UUID.randomUUID();

        JsonNode first = accepted(upload(kb, "guide.txt", MediaType.TEXT_PLAIN_VALUE, firstContent, key));
        filesToDelete.add(new FileRef(alphaTenant, kb, UUID.fromString(first.path("fileId").asText())));

        MvcResult conflict = upload(kb, "guide.txt", MediaType.TEXT_PLAIN_VALUE, different, key);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertError(conflict, "CONFLICT");
        assertThat(singleLong("SELECT count(*) FROM knowledge_files WHERE upload_idempotency_key = ?", key)).isEqualTo(1);
    }

    @Test
    void uploadWaitsForConcurrentKnowledgeBaseDeleteThenCompensatesAndReturns404() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");
        byte[] content = "delete wins while upload waits".getBytes(StandardCharsets.UTF_8);
        long tasksBefore = singleLong(
                "SELECT count(*) FROM tasks WHERE tenant_id = ? AND task_type = 'knowledge_file.ingest'", alphaTenant);
        long eventsBefore = singleLong(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'knowledge_file.ingested'",
                alphaTenant);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection deleting = dataSource.getConnection()) {
            deleting.setAutoCommit(false);
            try (PreparedStatement lock = deleting.prepareStatement("""
                    SELECT id
                    FROM knowledge_bases
                    WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                    FOR UPDATE
                    """)) {
                lock.setObject(1, alphaTenant);
                lock.setObject(2, kb);
                try (ResultSet rows = lock.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            try (PreparedStatement statement = deleting.prepareStatement("""
                    UPDATE knowledge_bases
                    SET status = 'DELETED', deleted_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                    """)) {
                statement.setObject(1, alphaTenant);
                statement.setObject(2, kb);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }

            Future<MvcResult> pending = executor.submit(() ->
                    upload(kb, "guide.txt", MediaType.TEXT_PLAIN_VALUE, content, null));

            // The HTTP thread has already uploaded the object, but its database
            // transaction is blocked on FOR KEY SHARE behind this delete transaction.
            awaitObjectCount(TenantId.of(alphaTenant), kb, 1);
            assertThat(pending.isDone()).isFalse();

            deleting.commit();
            MvcResult result = pending.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertError(result, "RESOURCE_NOT_FOUND");
        } finally {
            executor.shutdownNow();
        }

        // Once the delete commits, the lock read no longer finds an active row. The
        // upload transaction never creates business rows and compensates its MinIO object.
        awaitObjectCount(TenantId.of(alphaTenant), kb, 0);
        assertThat(singleLong("SELECT count(*) FROM knowledge_files WHERE knowledge_base_id = ?", kb)).isZero();
        assertThat(singleLong(
                "SELECT count(*) FROM tasks WHERE tenant_id = ? AND task_type = 'knowledge_file.ingest'", alphaTenant))
                .isEqualTo(tasksBefore);
        assertThat(singleLong(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'knowledge_file.ingested'",
                alphaTenant)).isEqualTo(eventsBefore);
    }

    // ----------------------------------------------------------- read path + walls

    @Test
    void anonymousReadsAre401AndUnprivilegedReadsAre403() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");
        JsonNode body = accepted(upload(kb, "guide.txt", MediaType.TEXT_PLAIN_VALUE,
                "read me".getBytes(StandardCharsets.UTF_8), null));
        UUID fileId = UUID.fromString(body.path("fileId").asText());
        filesToDelete.add(new FileRef(alphaTenant, kb, fileId));

        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files", kb))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}", kb, fileId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}/content", kb, fileId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files", kb)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}", kb, fileId)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}/content", kb, fileId)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unprivilegedUploadIs403() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");

        mockMvc.perform(multipart("/api/v1/knowledge-bases/{kb}/files", kb)
                        .file(new MockMultipartFile("file", "x.txt", MediaType.TEXT_PLAIN_VALUE,
                                "x".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isForbidden());
        assertThat(objectCount(TenantId.of(alphaTenant), kb)).isZero();
    }

    @Test
    void listDetailAndContentNeverLeakStorageInternals() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");
        byte[] bytes = "list me".getBytes(StandardCharsets.UTF_8);
        JsonNode uploadBody = accepted(upload(kb, "guide.txt", MediaType.TEXT_PLAIN_VALUE, bytes, null));
        UUID fileId = UUID.fromString(uploadBody.path("fileId").asText());
        filesToDelete.add(new FileRef(alphaTenant, kb, fileId));

        JsonNode list = readTree(mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files", kb)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("id").asText()).isEqualTo(fileId.toString());
        assertNoStorageInternals(list);

        JsonNode detail = readTree(mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}", kb, fileId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(detail.path("displayName").asText()).isEqualTo("guide.txt");
        assertThat(detail.path("contentType").asText()).isEqualTo("text/plain");
        assertThat(detail.path("status").asText()).isEqualTo("QUEUED");
        assertThat(detail.path("sha256").asText()).isEqualTo(sha256(bytes));
        assertNoStorageInternals(detail);

        MvcResult content = mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}/content", kb, fileId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment"))
                .andReturn();
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(bytes);
    }

    @Test
    void crossTenantAndCrossKnowledgeBaseReadsAreUniform404() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");
        JsonNode body = accepted(upload(kb, "guide.txt", MediaType.TEXT_PLAIN_VALUE,
                "secret to alpha".getBytes(StandardCharsets.UTF_8), null));
        UUID fileId = UUID.fromString(body.path("fileId").asText());
        filesToDelete.add(new FileRef(alphaTenant, kb, fileId));

        // Beta (a fully-privileged ADMIN in its own tenant) cannot read alpha's file:
        // list, detail and content all use the same non-enumerating 404 response.
        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}", kb, fileId)
                        .header("Authorization", bearer(betaToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}/content", kb, fileId)
                        .header("Authorization", bearer(betaToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files", kb)
                        .header("Authorization", bearer(betaToken)))
                .andExpect(status().isNotFound());

        // The same file id under a different (owned) knowledge base is also 404.
        UUID otherKb = newKnowledgeBase("ACTIVE");
        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}", otherKb, fileId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files/{file}/content", otherKb, fileId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listSupportsStatusFilteringAndPaging() throws Exception {
        UUID kb = newKnowledgeBase("ACTIVE");
        JsonNode first = accepted(upload(kb, "one.txt", MediaType.TEXT_PLAIN_VALUE, "one".getBytes(StandardCharsets.UTF_8), null));
        JsonNode second = accepted(upload(kb, "two.txt", MediaType.TEXT_PLAIN_VALUE, "two".getBytes(StandardCharsets.UTF_8), null));
        filesToDelete.add(new FileRef(alphaTenant, kb, UUID.fromString(first.path("fileId").asText())));
        filesToDelete.add(new FileRef(alphaTenant, kb, UUID.fromString(second.path("fileId").asText())));

        JsonNode filtered = readTree(mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files", kb)
                        .param("status", "QUEUED")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(filtered.path("total").asLong()).isEqualTo(2);

        JsonNode paged = readTree(mockMvc.perform(get("/api/v1/knowledge-bases/{kb}/files", kb)
                        .param("page", "1")
                        .param("size", "1")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(paged.path("items").size()).isEqualTo(1);
        assertThat(paged.path("total").asLong()).isEqualTo(2);
    }

    // ------------------------------------------------------------------- helpers

    private MvcResult upload(UUID kb, String filename, String contentType, byte[] bytes, String key)
            throws Exception {
        MockMultipartFile part = new MockMultipartFile("file", filename, contentType, bytes);
        var request = multipart("/api/v1/knowledge-bases/{knowledgeBaseId}/files", kb)
                .file(part)
                .header("Authorization", bearer(adminToken));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static JsonNode accepted(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        return readTree(result);
    }

    private static JsonNode readTree(MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertError(MvcResult result, String errorCode) throws Exception {
        JsonNode body = readTree(result);
        assertThat(body.path("errorCode").asText()).isEqualTo(errorCode);
        assertThat(body.path("message").asText()).isNotBlank();
    }

    private static void assertNoStorageInternals(JsonNode body) {
        String json = body.toString();
        assertThat(json).doesNotContain("objectKey", "object_key", "bucket", "processingParams",
                "processing_params", "x-amz-meta-sha256", "tenants/");
    }

    /** Creates a knowledge base through the real API so chunk_policy/retrieval_config are populated. */
    private UUID newKnowledgeBase(String status) throws Exception {
        String slug = "kb-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
                                "slug", slug, "name", "KB " + slug))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(readTree(result).path("id").asText());
        knowledgeBasesToDelete.add(id);
        if (!"ACTIVE".equals(status)) {
            execute("UPDATE knowledge_bases SET status = ? WHERE id = ?", status, id);
        }
        return id;
    }

    private List<String> listObjects(TenantId tenantId, UUID kb) {
        String prefix = "tenants/" + tenantId.value() + "/knowledge-bases/" + kb + "/files/";
        List<String> names = new ArrayList<>();
        try {
            for (Result<Item> item : rawClient.listObjects(
                    ListObjectsArgs.builder().bucket(BUCKET).prefix(prefix).build())) {
                names.add(item.get().objectName());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO listing failed", exception);
        }
        return names;
    }

    private int objectCount(TenantId tenantId, UUID kb) {
        return listObjects(tenantId, kb).size();
    }

    private void awaitObjectCount(TenantId tenantId, UUID kb, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (objectCount(tenantId, kb) == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(objectCount(tenantId, kb)).isEqualTo(expected);
    }

    private void createFailInsertTrigger() {
        execute("CREATE OR REPLACE FUNCTION it_force_file_insert_failure() RETURNS trigger AS $$"
                + "BEGIN RAISE EXCEPTION 'simulated knowledge_files insert failure'; END $$"
                + " LANGUAGE plpgsql");
        execute("DROP TRIGGER IF EXISTS trg_it_force_file_insert_failure ON knowledge_files");
        execute("CREATE TRIGGER trg_it_force_file_insert_failure BEFORE INSERT ON knowledge_files"
                + " FOR EACH ROW EXECUTE FUNCTION it_force_file_insert_failure()");
    }

    private void dropFailInsertTrigger() {
        execute("DROP TRIGGER IF EXISTS trg_it_force_file_insert_failure ON knowledge_files");
        execute("DROP FUNCTION IF EXISTS it_force_file_insert_failure()");
    }

    private static byte[] docxBytes() throws IOException {
        String contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Override PartName="/word/document.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body>
                </w:document>
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(contentTypes.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(document.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static void seedViewer() {
        UUID tenant = tenantId("alpha");
        String passwordHash = passwordHasher().encode(RAW_PASSWORD);

        UUID viewerRole = insertRole(tenant, "VIEWER", "[]");
        UUID viewerId = insertUser(tenant, "viewer@alpha.test", "Viewer", "ACTIVE", passwordHash);
        insertAssignment(tenant, viewerId, viewerRole);
    }

    private static PasswordHasher passwordHasher() {
        return context.getBean(PasswordHasher.class);
    }

    private static String login(String tenantSlug, String loginName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
                                "tenantSlug", tenantSlug,
                                "loginName", loginName,
                                "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static UUID tenantId(String slug) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id FROM tenants WHERE slug = ?")) {
            statement.setString(1, slug);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getObject(1, UUID.class) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UUID insertUser(UUID tenantId, String loginName, String displayName,
                                   String status, String passwordHash) {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO users (id, tenant_id, login_name, display_name, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, tenantId, loginName, displayName, passwordHash, status);
        return id;
    }

    private static UUID insertRole(UUID tenantId, String code, String permissionsJson) {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO roles (id, tenant_id, code, name, permissions, is_system, status)
                VALUES (?, ?, ?, ?, ?::jsonb, false, 'ACTIVE')
                """, id, tenantId, code, code, permissionsJson);
        return id;
    }

    private static void insertAssignment(UUID tenantId, UUID userId, UUID roleId) {
        execute("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, granted_at, expires_at)
                VALUES (?, ?, ?, ?, NULL)
                """, tenantId, userId, roleId, OffsetDateTime.now().minusDays(2));
    }

    private static long singleLong(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : -1;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void deleteById(String table, UUID id) {
        execute("DELETE FROM " + table + " WHERE id = ?", id);
    }

    private static void execute(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.executeUpdate();
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record FileRef(UUID tenantId, UUID knowledgeBaseId, UUID fileId) {
    }
}
