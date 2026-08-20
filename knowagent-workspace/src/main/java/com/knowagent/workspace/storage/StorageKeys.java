package com.knowagent.workspace.storage;

import com.knowagent.common.tenant.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side object key construction and ownership checks (rule: MinIO object keys
 * are built and verified on the server; a client never submits a full key, bucket or
 * physical path).
 *
 * <p>Every knowledge file lives at the deterministic path
 * {@code tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/files/{fileId}/source}.
 * The adapter re-verifies the {@code tenants/{tenantId}/} prefix on every operation so
 * a caller can never address an object owned by another tenant even if it somehow
 * obtained a foreign key string.
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    private static final String TENANT_PREFIX = "tenants/";

    public static ObjectKey knowledgeFileSource(TenantId tenantId, UUID knowledgeBaseId, UUID fileId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        return new ObjectKey(TENANT_PREFIX + tenantId.value()
                + "/knowledge-bases/" + knowledgeBaseId
                + "/files/" + fileId
                + "/source");
    }

    /**
     * Whether {@code key} lives under this tenant's {@code tenants/{tenantId}/} prefix.
     * The adapter enforces this on every put/stat/get/delete so tenant isolation is
     * enforced at the storage boundary, not just by the calling service.
     */
    public static boolean isOwnedBy(TenantId tenantId, ObjectKey key) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        return key.value().startsWith(TENANT_PREFIX + tenantId.value() + "/");
    }
}
