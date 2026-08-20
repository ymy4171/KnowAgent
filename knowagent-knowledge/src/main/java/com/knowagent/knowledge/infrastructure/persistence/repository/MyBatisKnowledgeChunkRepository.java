package com.knowagent.knowledge.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeChunkRepository;
import com.knowagent.knowledge.chunk.KnowledgeChunk;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.infrastructure.persistence.converter.KnowledgeChunkPersistenceConverter;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeChunkPo;
import com.knowagent.knowledge.infrastructure.persistence.mapper.KnowledgeChunkMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class MyBatisKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final KnowledgeChunkMapper mapper;

    public MyBatisKnowledgeChunkRepository(KnowledgeChunkMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public void replaceAll(TenantId tenantId, UUID knowledgeBaseId, UUID fileId, List<KnowledgeChunk> chunks) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(chunks, "chunks must not be null");
        mapper.deleteByFile(tenantId.value(), knowledgeBaseId, fileId);
        for (KnowledgeChunk chunk : chunks) {
            mapper.insert(KnowledgeChunkPersistenceConverter.toPersistence(chunk));
        }
    }

    @Override
    public List<KnowledgeChunk> findByFile(TenantId tenantId, UUID knowledgeBaseId, UUID fileId) {
        return mapper.selectByFile(tenantId.value(), knowledgeBaseId, fileId).stream()
                .map(KnowledgeChunkPersistenceConverter::toDomain)
                .toList();
    }

    @Override
    public int transitionIndexStatus(TenantId tenantId, UUID knowledgeBaseId, UUID fileId,
                                     ChunkIndexStatus expected, ChunkIndexStatus target,
                                     String embeddingModelSpec, String errorCode, String errorMessage) {
        return mapper.transitionIndexStatus(tenantId.value(), knowledgeBaseId, fileId,
                expected, target, embeddingModelSpec, errorCode, errorMessage);
    }
}
