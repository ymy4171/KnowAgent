package com.knowagent.knowledge.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeBaseRepository;
import com.knowagent.knowledge.infrastructure.persistence.converter.KnowledgeBasePersistenceConverter;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeBasePo;
import com.knowagent.knowledge.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.knowledgebase.KnowledgeBasePage;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper mapper;

    public MyBatisKnowledgeBaseRepository(KnowledgeBaseMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public void save(KnowledgeBase knowledgeBase) {
        mapper.insert(KnowledgeBasePersistenceConverter.toPersistence(knowledgeBase));
    }

    @Override
    public Optional<KnowledgeBase> findById(TenantId tenantId, UUID id) {
        KnowledgeBasePo record = mapper.selectByIdAndTenant(tenantId.value(), id);
        return Optional.ofNullable(record).map(KnowledgeBasePersistenceConverter::toDomain);
    }

    @Override
    public Optional<KnowledgeBase> findByIdForUpdate(TenantId tenantId, UUID id) {
        KnowledgeBasePo record = mapper.selectByIdAndTenantForUpdate(tenantId.value(), id);
        return Optional.ofNullable(record).map(KnowledgeBasePersistenceConverter::toDomain);
    }

    @Override
    public Optional<KnowledgeBase> findByIdForKeyShare(TenantId tenantId, UUID id) {
        KnowledgeBasePo record = mapper.selectByIdAndTenantForKeyShare(tenantId.value(), id);
        return Optional.ofNullable(record).map(KnowledgeBasePersistenceConverter::toDomain);
    }

    @Override
    public Optional<KnowledgeBase> findActiveBySlug(TenantId tenantId, String slug) {
        KnowledgeBasePo record = mapper.selectActiveBySlug(tenantId.value(), slug);
        return Optional.ofNullable(record).map(KnowledgeBasePersistenceConverter::toDomain);
    }

    @Override
    public KnowledgeBasePage page(TenantId tenantId, String namePattern, String slugPattern,
                                  KnowledgeBaseStatus status, int page, int size) {
        long total = mapper.countAll(tenantId.value(), namePattern, slugPattern, status);
        List<KnowledgeBase> knowledgeBases = mapper.selectPage(tenantId.value(), namePattern, slugPattern,
                        status, size, Math.multiplyExact(page - 1, size))
                .stream()
                .map(KnowledgeBasePersistenceConverter::toDomain)
                .toList();
        return new KnowledgeBasePage(knowledgeBases, total);
    }

    @Override
    public int updateConfig(KnowledgeBase knowledgeBase) {
        return mapper.updateConfig(
                knowledgeBase.tenantId().value(),
                knowledgeBase.id(),
                knowledgeBase.slug(),
                knowledgeBase.name(),
                knowledgeBase.description(),
                knowledgeBase.knowledgeType(),
                knowledgeBase.status(),
                knowledgeBase.embeddingProviderId(),
                knowledgeBase.embeddingModel(),
                knowledgeBase.rerankProviderId(),
                knowledgeBase.rerankModel(),
                knowledgeBase.chunkPolicy(),
                knowledgeBase.retrievalConfig(),
                knowledgeBase.metadata(),
                knowledgeBase.updatedBy(),
                knowledgeBase.version());
    }

    @Override
    public int softDelete(TenantId tenantId, UUID id, long version) {
        return mapper.softDelete(tenantId.value(), id, version);
    }
}
