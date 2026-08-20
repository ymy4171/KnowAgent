package com.knowagent.model.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.provider.ModelProvider;
import com.knowagent.model.provider.ModelProviderPage;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for model-provider configuration. The tenant id is always supplied
 * by the caller (from the authenticated principal); it is never parsed from a request.
 * All operations are tenant-scoped and soft-delete aware.
 */
public interface ModelProviderRepository {

    void save(ModelProvider provider);

    Optional<ModelProvider> findById(TenantId tenantId, UUID id);

    /** Locks the active provider row until the surrounding transaction completes. */
    Optional<ModelProvider> findByIdForUpdate(TenantId tenantId, UUID id);

    /**
     * Locks the active provider row in KEY SHARE mode until the surrounding
     * transaction completes. Unlike {@link #findByIdForUpdate} it does not conflict
     * with other KEY SHARE readers, so multiple knowledge bases can bind the same
     * provider concurrently, while a provider delete (which takes FOR UPDATE)
     * serializes against it. The read re-evaluates {@code deleted_at IS NULL} after
     * acquiring the lock, so a delete committed while this waits surfaces as an empty
     * result (deleted / cross-tenant provider → not found).
     */
    Optional<ModelProvider> findByIdForKeyShare(TenantId tenantId, UUID id);

    Optional<ModelProvider> findActiveByKey(TenantId tenantId, String providerKey);

    ModelProviderPage page(TenantId tenantId, int page, int size);

    /** Version-guarded update; returns the number of affected rows (0 = conflict). */
    int updateConfig(ModelProvider provider);

    /** Version-guarded soft delete; returns the number of affected rows (0 = conflict). */
    int softDelete(TenantId tenantId, UUID id, long version);
}
