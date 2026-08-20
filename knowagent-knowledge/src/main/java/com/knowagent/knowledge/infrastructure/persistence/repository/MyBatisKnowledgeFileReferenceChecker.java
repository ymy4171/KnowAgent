package com.knowagent.knowledge.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeFileReferenceChecker;
import com.knowagent.knowledge.infrastructure.persistence.mapper.KnowledgeFileReferenceMapper;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

/** Implements the knowledge-base delete guard using knowledge-owned file data. */
@Repository
public class MyBatisKnowledgeFileReferenceChecker implements KnowledgeFileReferenceChecker {

    private final KnowledgeFileReferenceMapper mapper;

    public MyBatisKnowledgeFileReferenceChecker(KnowledgeFileReferenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public boolean hasActiveFiles(TenantId tenantId, UUID knowledgeBaseId) {
        return mapper.countActiveFiles(tenantId.value(), knowledgeBaseId) > 0;
    }
}
