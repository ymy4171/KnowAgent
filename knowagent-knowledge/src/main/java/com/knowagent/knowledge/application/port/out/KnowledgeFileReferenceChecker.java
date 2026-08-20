package com.knowagent.knowledge.application.port.out;

import com.knowagent.common.tenant.TenantId;

import java.util.UUID;

/**
 * Guards knowledge-base deletion: a knowledge base that still owns undeleted files
 * cannot be deleted in this milestone (the cascade-delete flow is 提示词十九). The
 * implementation reads the knowledge-owned {@code knowledge_files} table.
 */
public interface KnowledgeFileReferenceChecker {

    /** Whether any file for this knowledge base is not yet soft-deleted. */
    boolean hasActiveFiles(TenantId tenantId, UUID knowledgeBaseId);
}
