package com.knowagent.model.application.port.out;

import com.knowagent.common.tenant.TenantId;

import java.util.UUID;

/**
 * Whether an active (non-deleted) knowledge base references a model provider as its
 * embedding or rerank provider. Deletion consults this port so a referenced provider
 * yields a clean 409 instead of a database constraint violation.
 */
public interface ModelProviderReferenceChecker {

    boolean isReferencedByActiveKnowledgeBase(TenantId tenantId, UUID providerId);
}
