package com.knowagent.knowledge.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeFileRepository;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFilePage;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.infrastructure.persistence.converter.KnowledgeFilePersistenceConverter;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeFilePo;
import com.knowagent.knowledge.infrastructure.persistence.mapper.KnowledgeFileMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisKnowledgeFileRepository implements KnowledgeFileRepository {

    private final KnowledgeFileMapper mapper;

    public MyBatisKnowledgeFileRepository(KnowledgeFileMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public void save(KnowledgeFile file) {
        mapper.insert(KnowledgeFilePersistenceConverter.toPersistence(file));
    }

    @Override
    public Optional<KnowledgeFile> findById(TenantId tenantId, UUID knowledgeBaseId, UUID id) {
        KnowledgeFilePo record = mapper.selectByIdAndTenant(tenantId.value(), knowledgeBaseId, id);
        return Optional.ofNullable(record).map(KnowledgeFilePersistenceConverter::toDomain);
    }

    @Override
    public Optional<KnowledgeFile> findByTenantAndId(TenantId tenantId, UUID id) {
        KnowledgeFilePo record = mapper.selectByTenantAndId(tenantId.value(), id);
        return Optional.ofNullable(record).map(KnowledgeFilePersistenceConverter::toDomain);
    }

    @Override
    public Optional<KnowledgeFile> findByIdForUpdate(TenantId tenantId, UUID knowledgeBaseId, UUID id) {
        KnowledgeFilePo record = mapper.selectByIdAndTenantForUpdate(tenantId.value(), knowledgeBaseId, id);
        return Optional.ofNullable(record).map(KnowledgeFilePersistenceConverter::toDomain);
    }

    @Override
    public Optional<KnowledgeFile> findByTenantAndIdForUpdate(TenantId tenantId, UUID id) {
        KnowledgeFilePo record = mapper.selectByTenantAndIdForUpdate(tenantId.value(), id);
        return Optional.ofNullable(record).map(KnowledgeFilePersistenceConverter::toDomain);
    }

    @Override
    public boolean transitionStatus(KnowledgeFile current, KnowledgeFile target) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(target, "target must not be null");
        return mapper.transitionStatus(
                current.tenantId().value(), current.knowledgeBaseId(), current.id(), current.status(),
                target.status(), target.errorCode(), target.errorMessage(), target.retryable(),
                current.version()) == 1;
    }

    @Override
    public boolean updateChunkStatistics(TenantId tenantId, UUID knowledgeBaseId, UUID id,
                                         int chunkCount, long tokenCount, long version) {
        return mapper.updateChunkStatistics(tenantId.value(), knowledgeBaseId, id, chunkCount, tokenCount, version) > 0;
    }

    @Override
    public Optional<KnowledgeFile> findByUploadIdempotencyKey(TenantId tenantId, UUID knowledgeBaseId, String key) {
        KnowledgeFilePo record = mapper.selectByUploadIdempotencyKey(tenantId.value(), knowledgeBaseId, key);
        return Optional.ofNullable(record).map(KnowledgeFilePersistenceConverter::toDomain);
    }

    @Override
    public KnowledgeFilePage page(TenantId tenantId, UUID knowledgeBaseId,
                                  KnowledgeFileStatus status, int page, int size) {
        long total = mapper.countAll(tenantId.value(), knowledgeBaseId, status);
        List<KnowledgeFile> files = mapper.selectPage(tenantId.value(), knowledgeBaseId, status,
                        size, Math.multiplyExact(page - 1, size))
                .stream()
                .map(KnowledgeFilePersistenceConverter::toDomain)
                .toList();
        return new KnowledgeFilePage(files, total);
    }
}
