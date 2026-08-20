package com.knowagent.knowledge.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.infrastructure.persistence.mapper.KnowledgeModelProviderReferenceMapper;
import com.knowagent.model.application.port.out.ModelProviderReferenceChecker;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

/** Implements the model module's reference-check port using knowledge-owned data. */
@Repository
public class KnowledgeModelProviderReferenceChecker implements ModelProviderReferenceChecker {

    private final KnowledgeModelProviderReferenceMapper mapper;

    public KnowledgeModelProviderReferenceChecker(KnowledgeModelProviderReferenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public boolean isReferencedByActiveKnowledgeBase(TenantId tenantId, UUID providerId) {
        return mapper.countActiveReferences(tenantId.value(), providerId) > 0;
    }
}
