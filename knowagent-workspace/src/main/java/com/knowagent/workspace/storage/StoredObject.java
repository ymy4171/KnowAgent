package com.knowagent.workspace.storage;

import com.knowagent.common.tenant.TenantId;

import java.util.Objects;

public record StoredObject(
        TenantId tenantId,
        ObjectKey key,
        String contentType,
        long size,
        String sha256
) {

    public StoredObject {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }
}
