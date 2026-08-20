package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeChunkRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeFileRepository;
import com.knowagent.knowledge.chunk.ChunkDraft;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.chunk.DeterministicChunker;
import com.knowagent.knowledge.chunk.KnowledgeChunk;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFilePage;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * ChunkWriteService orchestrates the replacement transaction: locks the file row, converts
 * drafts into PENDING chunks with deterministic Java-generated UUIDs, replaces the chunk set, and applies
 * the version-guarded statistics update - failing with 404 for a missing file and CONFLICT
 * for a lost version race, never touching chunks in either failure.
 */
class ChunkWriteServiceTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final UUID KB = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();
    private static final long FILE_VERSION = 7L;

    @Test
    void replacesChunksWithPendingStatusAndUpdatesFileStatistics() {
        FakeFileRepository files = new FakeFileRepository(file(FILE_VERSION));
        FakeChunkRepository chunks = new FakeChunkRepository();
        ChunkWriteService service = new ChunkWriteService(files, chunks);
        List<ChunkDraft> drafts = drafts(2);

        service.replaceChunks(TENANT, KB, FILE, drafts);

        assertThat(chunks.replacedTenant).isEqualTo(TENANT);
        assertThat(chunks.replacedKb).isEqualTo(KB);
        assertThat(chunks.replacedFile).isEqualTo(FILE);
        assertThat(chunks.replaced).hasSize(2);
        assertThat(chunks.replaced).allSatisfy(chunk -> {
            assertThat(chunk.indexStatus()).isEqualTo(ChunkIndexStatus.PENDING);
            assertThat(chunk.tenantId()).isEqualTo(TENANT);
            assertThat(chunk.knowledgeBaseId()).isEqualTo(KB);
            assertThat(chunk.fileId()).isEqualTo(FILE);
            assertThat(chunk.id()).isNotNull();
            assertThat(chunk.version()).isZero();
        });
        assertThat(chunks.replaced.get(0).id()).isNotEqualTo(chunks.replaced.get(1).id());

        long expectedTokens = chunks.replaced.stream().mapToLong(KnowledgeChunk::tokenCount).sum();
        assertThat(files.updatedChunkCount).isEqualTo(2);
        assertThat(files.updatedTokenCount).isEqualTo(expectedTokens);
        assertThat(files.updatedVersion).isEqualTo(FILE_VERSION);
    }

    @Test
    void identicalRetryKeepsChunkIdsStableAndChangedContentGetsANewId() {
        FakeFileRepository files = new FakeFileRepository(file(FILE_VERSION));
        FakeChunkRepository chunks = new FakeChunkRepository();
        ChunkWriteService service = new ChunkWriteService(files, chunks);
        List<ChunkDraft> original = drafts(2);

        service.replaceChunks(TENANT, KB, FILE, original);
        List<UUID> firstIds = chunks.replaced.stream().map(KnowledgeChunk::id).toList();
        service.replaceChunks(TENANT, KB, FILE, original);
        assertThat(chunks.replaced).extracting(KnowledgeChunk::id).containsExactlyElementsOf(firstIds);

        String changed = original.get(0).content() + " changed";
        ChunkDraft changedFirst = new ChunkDraft(0, changed, DeterministicChunker.sha256Hex(changed), 4,
                0, changed.length(), 0, 4, 1, List.of("1"), java.util.Map.of());
        service.replaceChunks(TENANT, KB, FILE, List.of(changedFirst, original.get(1)));
        assertThat(chunks.replaced.get(0).id()).isNotEqualTo(firstIds.get(0));
        assertThat(chunks.replaced.get(1).id()).isEqualTo(firstIds.get(1));
    }

    @Test
    void missingFileReturnsNotFoundAndTouchesNothing() {
        FakeFileRepository files = new FakeFileRepository(null);
        FakeChunkRepository chunks = new FakeChunkRepository();
        ChunkWriteService service = new ChunkWriteService(files, chunks);

        BusinessException failure = catchThrowableOfType(
                () -> service.replaceChunks(TENANT, KB, FILE, drafts(1)),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(chunks.replaced).isNull();
        assertThat(files.updateCalled).isFalse();
    }

    @Test
    void lostVersionRaceSurfacesAsConflict() {
        FakeFileRepository files = new FakeFileRepository(file(FILE_VERSION));
        files.updateResult = false;
        FakeChunkRepository chunks = new FakeChunkRepository();
        ChunkWriteService service = new ChunkWriteService(files, chunks);

        BusinessException failure = catchThrowableOfType(
                () -> service.replaceChunks(TENANT, KB, FILE, drafts(1)),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(chunks.replaced).hasSize(1); // replacement happened, but the transaction rolls it back
    }

    // --- fixtures ---------------------------------------------------------------

    private static List<ChunkDraft> drafts(int count) {
        List<ChunkDraft> drafts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String content = "Chunk number " + i + " content with enough words to tokenize deterministically.";
            long start = (long) i * content.length();
            drafts.add(new ChunkDraft(i, content, DeterministicChunker.sha256Hex(content), 4,
                    start, start + content.length(), 0, 4, 1, List.of("1"), java.util.Map.of()));
        }
        return drafts;
    }

    private static KnowledgeFile file(long version) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        return new KnowledgeFile(UUID.randomUUID(), TENANT, KB, null, null,
                "report.txt", "report.txt", "tenants/x/files/y/source", "text/plain", "txt",
                "a".repeat(64), 1234L, KnowledgeFileStatus.UPLOADED, 0, 0,
                json.objectNode(), json.objectNode(), null, null, false,
                null, null, version, Instant.now(), Instant.now(), null);
    }

    private static final class FakeFileRepository implements KnowledgeFileRepository {
        private final Optional<KnowledgeFile> locked;
        private boolean updateCalled;
        private boolean updateResult = true;
        private int updatedChunkCount;
        private long updatedTokenCount;
        private long updatedVersion;

        private FakeFileRepository(KnowledgeFile locked) {
            this.locked = Optional.ofNullable(locked);
        }

        @Override
        public void save(KnowledgeFile file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeFile> findById(TenantId tenantId, UUID knowledgeBaseId, UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeFile> findByTenantAndId(TenantId tenantId, UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeFile> findByIdForUpdate(TenantId tenantId, UUID knowledgeBaseId, UUID id) {
            return locked;
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
            updateCalled = true;
            updatedChunkCount = chunkCount;
            updatedTokenCount = tokenCount;
            updatedVersion = version;
            return updateResult;
        }

        @Override
        public Optional<KnowledgeFile> findByUploadIdempotencyKey(TenantId tenantId, UUID knowledgeBaseId, String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeFilePage page(TenantId tenantId, UUID knowledgeBaseId,
                                      KnowledgeFileStatus status, int page, int size) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeChunkRepository implements KnowledgeChunkRepository {
        private TenantId replacedTenant;
        private UUID replacedKb;
        private UUID replacedFile;
        private List<KnowledgeChunk> replaced;

        @Override
        public void replaceAll(TenantId tenantId, UUID knowledgeBaseId, UUID fileId, List<KnowledgeChunk> chunks) {
            replacedTenant = tenantId;
            replacedKb = knowledgeBaseId;
            replacedFile = fileId;
            replaced = List.copyOf(chunks);
        }

        @Override
        public List<KnowledgeChunk> findByFile(TenantId tenantId, UUID knowledgeBaseId, UUID fileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int transitionIndexStatus(TenantId tenantId, UUID knowledgeBaseId, UUID fileId,
                                         ChunkIndexStatus expected, ChunkIndexStatus target,
                                         String embeddingModelSpec, String errorCode, String errorMessage) {
            throw new UnsupportedOperationException();
        }
    }
}
