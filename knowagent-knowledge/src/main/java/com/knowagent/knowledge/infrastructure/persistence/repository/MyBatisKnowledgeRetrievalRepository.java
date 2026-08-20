package com.knowagent.knowledge.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeRetrievalRepository;
import com.knowagent.knowledge.application.port.out.RetrievalChunkRecord;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeRetrievalChunkPo;
import com.knowagent.knowledge.infrastructure.persistence.mapper.KnowledgeChunkMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class MyBatisKnowledgeRetrievalRepository implements KnowledgeRetrievalRepository {

    private final KnowledgeChunkMapper mapper;

    public MyBatisKnowledgeRetrievalRepository(KnowledgeChunkMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public List<RetrievalChunkRecord> findByChunkIds(TenantId tenantId, UUID knowledgeBaseId,
                                                     List<UUID> chunkIds) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectRetrievalChunks(tenantId.value(), knowledgeBaseId, chunkIds).stream()
                .map(MyBatisKnowledgeRetrievalRepository::toRecord)
                .toList();
    }

    private static RetrievalChunkRecord toRecord(KnowledgeRetrievalChunkPo row) {
        return new RetrievalChunkRecord(row.getChunkId(), TenantId.of(row.getTenantId()),
                row.getKnowledgeBaseId(), row.getFileId(), row.getDisplayName(), row.getContent(),
                row.getPageNumber(), row.getSectionPath(), row.getIndexStatus(), row.getFileStatus(),
                row.getFileDeletedAt() == null ? null : row.getFileDeletedAt().toInstant());
    }
}
