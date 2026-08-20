package com.knowagent.knowledge.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeChunkRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeFileRepository;
import com.knowagent.knowledge.chunk.ChunkDraft;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.chunk.KnowledgeChunk;
import com.knowagent.knowledge.file.KnowledgeFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists the deterministic chunk output for one file. Everything happens in a single
 * transaction: the file row is locked ({@code FOR UPDATE} via
 * {@link KnowledgeFileRepository#findByIdForUpdate}), its current chunk set is replaced
 * wholesale (idempotent retry - the position unique constraint is
 * {@code (tenant_id, file_id, chunk_index)}, so a re-run can never duplicate indices),
 * then {@code knowledge_files.chunk_count}/{@code token_count} are updated with the
 * version-guarded conditional update. Any failure rolls the whole replacement back, so old
 * and new data are never left half-swapped.
 *
 * <p>The tenant id always comes from the caller (an authenticated principal or a trusted
 * worker envelope); it is never parsed from request data. Chunks are written with
 * {@code index_status = PENDING}; this service does not advance the file's own status.
 */
@Service
public class ChunkWriteService {

    private final KnowledgeFileRepository fileRepository;
    private final KnowledgeChunkRepository chunkRepository;

    public ChunkWriteService(KnowledgeFileRepository fileRepository,
                             KnowledgeChunkRepository chunkRepository) {
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.chunkRepository = Objects.requireNonNull(chunkRepository, "chunkRepository must not be null");
    }

    /** Replaces the file's chunk set with {@code drafts} and updates the file statistics. */
    @Transactional
    public void replaceChunks(TenantId tenantId, UUID knowledgeBaseId, UUID fileId, List<ChunkDraft> drafts) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(drafts, "drafts must not be null");

        KnowledgeFile file = fileRepository.findByIdForUpdate(tenantId, knowledgeBaseId, fileId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "knowledge file not found"));

        List<KnowledgeChunk> chunks = toChunks(tenantId, knowledgeBaseId, fileId, drafts);
        chunkRepository.replaceAll(tenantId, knowledgeBaseId, fileId, chunks);

        long tokenTotal = 0;
        for (KnowledgeChunk chunk : chunks) {
            tokenTotal += chunk.tokenCount();
        }
        boolean updated = fileRepository.updateChunkStatistics(
                tenantId, knowledgeBaseId, fileId, chunks.size(), tokenTotal, file.version());
        if (!updated) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "chunk statistics update lost a version race; retry the replacement");
        }
    }

    private static List<KnowledgeChunk> toChunks(TenantId tenantId, UUID knowledgeBaseId, UUID fileId,
                                                 List<ChunkDraft> drafts) {
        Instant now = Instant.now();
        List<KnowledgeChunk> chunks = new ArrayList<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            chunks.add(new KnowledgeChunk(
                    deterministicChunkId(tenantId, knowledgeBaseId, fileId, draft),
                    tenantId,
                    knowledgeBaseId,
                    fileId,
                    draft.chunkIndex(),
                    draft.content(),
                    draft.contentHash(),
                    draft.tokenCount(),
                    toIntOrNull(draft.startCharOffset()),
                    toIntOrNull(draft.endCharOffset()),
                    toIntOrNull(draft.startTokenOffset()),
                    toIntOrNull(draft.endTokenOffset()),
                    draft.pageNumber(),
                    draft.sectionPath(),
                    draft.metadata(),
                    ChunkIndexStatus.PENDING,
                    null,
                    null,
                    null,
                    0L,
                    now,
                    now));
        }
        return List.copyOf(chunks);
    }

    private static Long toIntOrNull(long value) {
        return (long) Math.toIntExact(value);
    }

    /** Stable for an identical file/chunk retry; changed content produces a new vector identity. */
    private static UUID deterministicChunkId(TenantId tenantId, UUID knowledgeBaseId, UUID fileId,
                                             ChunkDraft draft) {
        String name = tenantId.value() + "\0" + knowledgeBaseId + "\0" + fileId + "\0"
                + draft.chunkIndex() + "\0" + draft.contentHash();
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
