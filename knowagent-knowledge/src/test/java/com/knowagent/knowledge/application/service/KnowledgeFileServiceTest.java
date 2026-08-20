package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeBaseRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeFileRepository;
import com.knowagent.knowledge.file.DocumentType;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFilePage;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;
import com.knowagent.observability.application.service.SubmitTaskCommand;
import com.knowagent.observability.application.service.TaskSubmission;
import com.knowagent.observability.application.service.TaskSubmissionResult;
import com.knowagent.workspace.storage.DeleteObjectCommand;
import com.knowagent.workspace.storage.GetObjectCommand;
import com.knowagent.workspace.storage.ObjectKey;
import com.knowagent.workspace.storage.ObjectStorageException;
import com.knowagent.workspace.storage.ObjectStorageGateway;
import com.knowagent.workspace.storage.PutObjectCommand;
import com.knowagent.workspace.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeFileServiceTest {

    private static final TenantId TENANT_A = TenantId.of(UUID.randomUUID());
    private static final TenantId TENANT_B = TenantId.of(UUID.randomUUID());
    private static final UUID KB = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String TXT = "text/plain";

    private Optional<DocumentType> detectionResult = Optional.of(DocumentType.TEXT_PLAIN);

    private final FakeKnowledgeBaseRepository knowledgeBases = new FakeKnowledgeBaseRepository();
    private final FakeKnowledgeFileRepository files = new FakeKnowledgeFileRepository();
    private final FakeObjectStorageGateway storage = new FakeObjectStorageGateway();
    private final FakeTaskSubmission taskSubmission = new FakeTaskSubmission();
    private final KnowledgeFileSubmissionService submission =
            new KnowledgeFileSubmissionService(taskSubmission, files, knowledgeBases);
    private final KnowledgeFileService service = new KnowledgeFileService(
            knowledgeBases, files, path -> detectionResult, storage, submission);

    /** JUnit test order is unspecified: reset every mutable fake so tests stay independent. */
    @BeforeEach
    void resetFakes() {
        detectionResult = Optional.of(DocumentType.TEXT_PLAIN);
        knowledgeBases.present = true;
        knowledgeBases.active = true;
        files.byId.clear();
        files.byKey.clear();
        files.saved.clear();
        files.failNextSaveWithDuplicate = false;
        files.failNextSaveWithRuntime = false;
        files.winnerOnDuplicate = null;
        storage.putThrows = null;
        storage.getThrows = null;
        storage.deleteThrows = false;
        storage.putKeys.clear();
        storage.putSizes.clear();
        storage.putSha256.clear();
        storage.putContentTypes.clear();
        storage.deleteKeys.clear();
        taskSubmission.submitted.clear();
    }

    // ------------------------------------------------------------------ success

    @Test
    void uploadWritesObjectThenPersistsQueuedFileTaskAndOutbox() {
        String content = "hello knowledge base\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);

        UploadFileResult result = service.upload(upload("guide.txt", bytes));

        assertThat(result.replayed()).isFalse();
        assertThat(result.file().status()).isEqualTo(KnowledgeFileStatus.QUEUED);
        assertThat(result.file().sha256()).isEqualTo(sha);
        assertThat(result.file().fileSizeBytes()).isEqualTo(bytes.length);
        assertThat(result.file().contentType()).isEqualTo(TXT);
        assertThat(result.file().objectKey()).startsWith(
                "tenants/" + TENANT_A.value() + "/knowledge-bases/" + KB + "/files/");
        assertThat(result.taskId()).isNotNull();
        assertThat(result.file().processingParams().get("task_id").asText())
                .isEqualTo(result.taskId().toString());

        // One object write, one file insert, one task/event submission.
        assertThat(storage.putKeys).hasSize(1);
        assertThat(storage.putSizes.get(0)).isEqualTo(bytes.length);
        assertThat(storage.putSha256.get(0)).isEqualTo(sha);
        assertThat(storage.putContentTypes.get(0)).isEqualTo(TXT);
        assertThat(storage.deleteKeys).isEmpty();
        assertThat(files.saved).hasSize(1);
        assertThat(taskSubmission.submitted).hasSize(1);
    }

    @Test
    void uploadStoresSha256AndSizeAndContentTypeMatchingTheObject() {
        byte[] bytes = "a second document".getBytes(StandardCharsets.UTF_8);
        service.upload(upload("second.txt", bytes));

        assertThat(storage.putSha256).containsExactly(sha256(bytes));
        assertThat(storage.putSizes).containsExactly((long) bytes.length);
        assertThat(storage.putContentTypes).containsExactly(TXT);
        assertThat(files.saved.get(0).sha256()).isEqualTo(sha256(bytes));
    }

    // --------------------------------------------------------------- validation

    @Test
    void uploadRejectsMissingAndInactiveKnowledgeBase() {
        knowledgeBases.present = false;
        assertNotFound(() -> service.upload(upload("x.txt", "content".getBytes(StandardCharsets.UTF_8))));
        assertThat(storage.putKeys).isEmpty();

        knowledgeBases.present = true;
        knowledgeBases.active = false;
        assertThatThrownBy(() -> service.upload(upload("x.txt", "content".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void uploadRejectsEmptyContent() {
        assertValidationError(() -> service.upload(upload("empty.txt", new byte[0])));
        assertThat(storage.putKeys).isEmpty();
        assertThat(files.saved).isEmpty();
    }

    @Test
    void uploadRejectsUnsupportedDocumentTypeBeforeStorage() {
        detectionResult = Optional.empty();

        assertValidationError(() -> service.upload(upload("opaque.bin", randomBytes(64))));
        assertThat(storage.putKeys).isEmpty();
        assertThat(files.saved).isEmpty();
        assertThat(taskSubmission.submitted).isEmpty();
    }

    @Test
    void uploadRejectsBlankFilenameAndOverlongIdempotencyKey() {
        assertValidationError(() -> service.upload(
                new UploadFileCommand(TENANT_A, KB, ACTOR, null, "  ", new ByteArrayInputStream(new byte[1]))));
        assertValidationError(() -> service.upload(
                new UploadFileCommand(TENANT_A, KB, ACTOR, "k".repeat(129), "x.txt",
                        new ByteArrayInputStream(new byte[1]))));
    }

    @Test
    void uploadRejectsContentOverTheSpoolBound() {
        byte[] oversized = new byte[(int) KnowledgeFileService.MAX_UPLOAD_BYTES + 1];

        assertValidationError(() -> service.upload(upload("huge.bin", oversized)));
        assertThat(storage.putKeys).isEmpty();
        assertThat(files.saved).isEmpty();
    }

    @Test
    void uploadTrimsIdempotencyKeyAndFilename() {
        service.upload(new UploadFileCommand(TENANT_A, KB, ACTOR, "  k1  ", "  guide.txt ",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));

        assertThat(files.saved.get(0).uploadIdempotencyKey()).isEqualTo("k1");
        assertThat(files.saved.get(0).displayName()).isEqualTo("guide.txt");
        assertThat(files.saved.get(0).fileExtension()).isEqualTo("txt");
    }

    // -------------------------------------------------------------- idempotency

    @Test
    void matchingIdempotencyKeyReplaysOriginalFileAndSubmitsNothing() {
        byte[] bytes = "stable content".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        KnowledgeFile existing = queuedFile(UUID.randomUUID(), "guide.txt", sha);
        files.byKey.put(keyOf("k1"), existing);

        UploadFileResult result = service.upload(new UploadFileCommand(
                TENANT_A, KB, ACTOR, "k1", "guide.txt", new ByteArrayInputStream(bytes)));

        assertThat(result.replayed()).isTrue();
        assertThat(result.file().id()).isEqualTo(existing.id());
        assertThat(result.taskId()).isNotNull();
        assertThat(storage.putKeys).isEmpty();
        assertThat(files.saved).isEmpty();
        assertThat(taskSubmission.submitted).isEmpty();
    }

    @Test
    void differentContentForAnExistingKeyReturnsConflict() {
        KnowledgeFile existing = queuedFile(UUID.randomUUID(), "guide.txt", sha256("other".getBytes(StandardCharsets.UTF_8)));
        files.byKey.put(keyOf("k1"), existing);

        assertThatThrownBy(() -> service.upload(new UploadFileCommand(
                TENANT_A, KB, ACTOR, "k1", "guide.txt",
                new ByteArrayInputStream("fresh".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(storage.putKeys).isEmpty();
    }

    @Test
    void concurrentDuplicateKeyRaceReplaysTheWinnerWhenContentMatches() {
        byte[] bytes = "winner content".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        KnowledgeFile winner = queuedFile(UUID.randomUUID(), "guide.txt", sha);
        files.failNextSaveWithDuplicate = true;
        files.winnerOnDuplicate = winner;

        UploadFileResult result = service.upload(new UploadFileCommand(
                TENANT_A, KB, ACTOR, "k1", "guide.txt", new ByteArrayInputStream(bytes)));

        assertThat(result.replayed()).isTrue();
        assertThat(result.file().id()).isEqualTo(winner.id());
        // Our losing object was compensated away.
        assertThat(storage.deleteKeys).hasSize(1);
        assertThat(storage.deleteKeys.get(0).value()).isEqualTo(storage.putKeys.get(0).value());
    }

    @Test
    void concurrentDuplicateKeyRaceReturnsConflictWhenContentDiffers() {
        KnowledgeFile winner = queuedFile(UUID.randomUUID(), "guide.txt",
                sha256("winner".getBytes(StandardCharsets.UTF_8)));
        files.failNextSaveWithDuplicate = true;
        files.winnerOnDuplicate = winner;

        assertThatThrownBy(() -> service.upload(new UploadFileCommand(
                TENANT_A, KB, ACTOR, "k1", "guide.txt",
                new ByteArrayInputStream("loser".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(storage.deleteKeys).hasSize(1);
    }

    // -------------------------------------------------------------- compensation

    @Test
    void databaseFailureAfterStorageCompensatesTheObjectAndNeverReportsSuccess() {
        files.failNextSaveWithRuntime = true;

        assertThatThrownBy(() -> service.upload(upload("guide.txt", "data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated db failure");
        // The object was removed again and no task/event survived.
        assertThat(storage.putKeys).hasSize(1);
        assertThat(storage.deleteKeys).hasSize(1);
        assertThat(storage.deleteKeys.get(0).value()).isEqualTo(storage.putKeys.get(0).value());
        assertThat(files.saved).isEmpty();
        assertThat(taskSubmission.submitted).hasSize(1);
    }

    @Test
    void failedCompensationStillNeverReportsSuccess() {
        files.failNextSaveWithRuntime = true;
        storage.deleteThrows = true;

        assertThatThrownBy(() -> service.upload(upload("guide.txt", "data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.deleteKeys).hasSize(1);
        assertThat(files.saved).isEmpty();
    }

    @Test
    void storagePutFailureIsAStableExternalServiceError() {
        storage.putThrows = new ObjectStorageException(
                ObjectStorageException.Reason.UNAVAILABLE, "storage down");

        assertThatThrownBy(() -> service.upload(upload("guide.txt", "data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
        assertThat(storage.putKeys).isEmpty();
        assertThat(files.saved).isEmpty();
        assertThat(taskSubmission.submitted).isEmpty();
    }

    // ---------------------------------------------------------------- read paths

    @Test
    void listDelegatesWithPagingAndValidatesBounds() {
        service.list(TENANT_A, KB, KnowledgeFileStatus.QUEUED, 2, 10);
        assertThat(files.lastTenantId).isEqualTo(TENANT_A);
        assertThat(files.lastKbId).isEqualTo(KB);
        assertThat(files.lastStatus).isEqualTo(KnowledgeFileStatus.QUEUED);
        assertThat(files.lastPage).isEqualTo(2);
        assertThat(files.lastSize).isEqualTo(10);

        assertValidationError(() -> service.list(TENANT_A, KB, null, 0, 20));
        assertValidationError(() -> service.list(TENANT_A, KB, null, 1, 0));
        assertValidationError(() -> service.list(TENANT_A, KB, null, 1, 101));
    }

    @Test
    void listReturnsNotFoundForUnknownOrCrossTenantKnowledgeBase() {
        assertNotFound(() -> service.list(TENANT_B, KB, null, 1, 20));
        knowledgeBases.present = false;
        assertNotFound(() -> service.list(TENANT_A, KB, null, 1, 20));
    }

    @Test
    void getReturnsNotFoundForUnknownOrCrossTenantFile() {
        KnowledgeFile file = queuedFile(UUID.randomUUID(), "guide.txt", sha256(new byte[0]));
        files.byId.put(file.id(), file);

        assertNotFound(() -> service.get(TENANT_A, KB, UUID.randomUUID()));
        assertNotFound(() -> service.get(TENANT_B, KB, file.id()));
        assertNotFound(() -> service.get(TENANT_A, UUID.randomUUID(), file.id()));
        assertThat(service.get(TENANT_A, KB, file.id()).id()).isEqualTo(file.id());
    }

    @Test
    void contentStreamsTheSourceObjectWithStoredMetadata() throws IOException {
        byte[] bytes = "download me".getBytes(StandardCharsets.UTF_8);
        KnowledgeFile file = queuedFile(UUID.randomUUID(), "guide.txt", sha256(bytes));
        files.byId.put(file.id(), file);
        storage.getReturns = new ByteArrayInputStream(bytes);

        FileContent content = service.content(TENANT_A, KB, file.id());

        assertThat(content.contentType()).isEqualTo(TXT);
        assertThat(content.displayName()).isEqualTo("guide.txt");
        assertThat(content.size()).isEqualTo(file.fileSizeBytes());
        try (InputStream in = content.content()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void contentStorageReadFailureIsAStableExternalServiceError() {
        KnowledgeFile file = queuedFile(UUID.randomUUID(), "guide.txt", sha256(new byte[0]));
        files.byId.put(file.id(), file);
        storage.getThrows = new ObjectStorageException(
                ObjectStorageException.Reason.OBJECT_NOT_FOUND, "gone");

        assertThatThrownBy(() -> service.content(TENANT_A, KB, file.id()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
    }

    // ------------------------------------------------------------------ helpers

    private static UploadFileCommand upload(String filename, byte[] bytes) {
        return new UploadFileCommand(TENANT_A, KB, ACTOR, null, filename,
                new ByteArrayInputStream(bytes));
    }

    private static Key keyOf(String key) {
        return new Key(TENANT_A, KB, key);
    }

    private static KnowledgeFile queuedFile(UUID id, String name, String sha) {
        ObjectNode processing = JsonNodeFactory.instance.objectNode()
                .put("task_id", UUID.randomUUID().toString());
        return new KnowledgeFile(id, TENANT_A, KB, null, "k1", name, name,
                "tenants/" + TENANT_A.value() + "/knowledge-bases/" + KB + "/files/" + id + "/source",
                TXT, "txt", sha, 1, KnowledgeFileStatus.QUEUED, 0, 0,
                processing, JsonNodeFactory.instance.objectNode(),
                null, null, false, ACTOR, ACTOR, 0L, Instant.EPOCH, Instant.EPOCH, null);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void assertValidationError(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private record Key(TenantId tenantId, UUID kbId, String key) {
    }

    private static final class FakeKnowledgeBaseRepository implements KnowledgeBaseRepository {
        boolean present = true;
        boolean active = true;

        @Override
        public Optional<KnowledgeBase> findById(TenantId tenantId, UUID id) {
            if (!present) {
                return Optional.empty();
            }
            if (active) {
                if (!tenantId.equals(TENANT_A) || !id.equals(KB)) {
                    return Optional.empty();
                }
                return Optional.of(new KnowledgeBase(id, tenantId, "kb", "KB", null,
                        KnowledgeType.LOCAL, KnowledgeBaseStatus.ACTIVE, null, null, null, null,
                        com.knowagent.knowledge.chunk.ChunkPolicy.defaults(),
                        com.knowagent.knowledge.knowledgebase.RetrievalConfig.defaults(),
                        JsonNodeFactory.instance.objectNode(), ACTOR, ACTOR, 0L,
                        Instant.EPOCH, Instant.EPOCH, null));
            }
            return Optional.of(new KnowledgeBase(id, tenantId, "kb", "KB", null,
                    KnowledgeType.LOCAL, KnowledgeBaseStatus.DISABLED, null, null, null, null,
                    com.knowagent.knowledge.chunk.ChunkPolicy.defaults(),
                    com.knowagent.knowledge.knowledgebase.RetrievalConfig.defaults(),
                    JsonNodeFactory.instance.objectNode(), ACTOR, ACTOR, 0L,
                    Instant.EPOCH, Instant.EPOCH, null));
        }

        @Override
        public void save(KnowledgeBase knowledgeBase) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeBase> findByIdForUpdate(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override
        public Optional<KnowledgeBase> findByIdForKeyShare(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override
        public Optional<KnowledgeBase> findActiveBySlug(TenantId tenantId, String slug) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.knowagent.knowledge.knowledgebase.KnowledgeBasePage page(
                TenantId tenantId, String namePattern, String slugPattern,
                KnowledgeBaseStatus status, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateConfig(KnowledgeBase knowledgeBase) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int softDelete(TenantId tenantId, UUID id, long version) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeKnowledgeFileRepository implements KnowledgeFileRepository {
        final Map<UUID, KnowledgeFile> byId = new HashMap<>();
        final Map<Key, KnowledgeFile> byKey = new HashMap<>();
        final List<KnowledgeFile> saved = new ArrayList<>();
        boolean failNextSaveWithDuplicate;
        boolean failNextSaveWithRuntime;
        KnowledgeFile winnerOnDuplicate;
        TenantId lastTenantId;
        UUID lastKbId;
        KnowledgeFileStatus lastStatus;
        int lastPage;
        int lastSize;

        @Override
        public void save(KnowledgeFile file) {
            if (failNextSaveWithDuplicate) {
                failNextSaveWithDuplicate = false;
                // A concurrent upload won the unique index; only now does it become visible
                // to a re-query, exactly like the committed row in the real database.
                if (winnerOnDuplicate != null) {
                    byKey.put(new Key(winnerOnDuplicate.tenantId(),
                            winnerOnDuplicate.knowledgeBaseId(),
                            winnerOnDuplicate.uploadIdempotencyKey()), winnerOnDuplicate);
                }
                throw new DuplicateKeyException("simulated idempotency unique-key race");
            }
            if (failNextSaveWithRuntime) {
                failNextSaveWithRuntime = false;
                throw new IllegalStateException("simulated db failure");
            }
            saved.add(file);
            byId.put(file.id(), file);
            if (file.uploadIdempotencyKey() != null) {
                byKey.put(new Key(file.tenantId(), file.knowledgeBaseId(), file.uploadIdempotencyKey()), file);
            }
        }

        @Override
        public Optional<KnowledgeFile> findById(TenantId tenantId, UUID knowledgeBaseId, UUID id) {
            KnowledgeFile file = byId.get(id);
            return file != null && file.tenantId().equals(tenantId)
                    && file.knowledgeBaseId().equals(knowledgeBaseId)
                    ? Optional.of(file) : Optional.empty();
        }

        @Override
        public Optional<KnowledgeFile> findByTenantAndId(TenantId tenantId, UUID id) {
            KnowledgeFile file = byId.get(id);
            return file != null && file.tenantId().equals(tenantId) ? Optional.of(file) : Optional.empty();
        }

        @Override
        public Optional<KnowledgeFile> findByUploadIdempotencyKey(TenantId tenantId, UUID knowledgeBaseId, String key) {
            return Optional.ofNullable(byKey.get(new Key(tenantId, knowledgeBaseId, key)));
        }

        @Override
        public KnowledgeFilePage page(TenantId tenantId, UUID knowledgeBaseId,
                                      KnowledgeFileStatus status, int page, int size) {
            lastTenantId = tenantId;
            lastKbId = knowledgeBaseId;
            lastStatus = status;
            lastPage = page;
            lastSize = size;
            return new KnowledgeFilePage(List.of(), 0);
        }

        @Override
        public Optional<KnowledgeFile> findByIdForUpdate(TenantId tenantId, UUID knowledgeBaseId, UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeFile> findByTenantAndIdForUpdate(TenantId tenantId, UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean transitionStatus(KnowledgeFile current, KnowledgeFile target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateChunkStatistics(TenantId tenantId, UUID knowledgeBaseId, UUID id,
                                             int chunkCount, long tokenCount, long version) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeObjectStorageGateway implements ObjectStorageGateway {
        final List<ObjectKey> putKeys = new ArrayList<>();
        final List<Long> putSizes = new ArrayList<>();
        final List<String> putSha256 = new ArrayList<>();
        final List<String> putContentTypes = new ArrayList<>();
        final List<ObjectKey> deleteKeys = new ArrayList<>();
        ObjectStorageException putThrows;
        ObjectStorageException getThrows;
        boolean deleteThrows;
        InputStream getReturns = new ByteArrayInputStream(new byte[0]);

        @Override
        public StoredObject put(PutObjectCommand command) {
            if (putThrows != null) {
                throw putThrows;
            }
            putKeys.add(command.key());
            putSizes.add(command.size());
            putSha256.add(command.sha256());
            putContentTypes.add(command.contentType());
            return new StoredObject(command.tenantId(), command.key(),
                    command.contentType(), command.size(), command.sha256() == null ? "" : command.sha256());
        }

        @Override
        public StoredObject stat(GetObjectCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream get(GetObjectCommand command) {
            if (getThrows != null) {
                throw getThrows;
            }
            return getReturns;
        }

        @Override
        public void delete(DeleteObjectCommand command) {
            // Record the attempt even when it will fail: compensation must be observable.
            deleteKeys.add(command.key());
            if (deleteThrows) {
                throw new ObjectStorageException(ObjectStorageException.Reason.UNAVAILABLE, "delete failed");
            }
        }
    }

    private static final class FakeTaskSubmission implements TaskSubmission {
        final List<SubmitTaskCommand> submitted = new ArrayList<>();

        @Override
        public TaskSubmissionResult submit(SubmitTaskCommand command) {
            submitted.add(command);
            return new TaskSubmissionResult(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        }
    }
}
