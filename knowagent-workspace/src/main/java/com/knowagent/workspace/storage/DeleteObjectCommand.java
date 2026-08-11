package com.knowagent.workspace.storage;

import com.knowagent.common.tenant.TenantId;

import java.util.Objects;

public record DeleteObjectCommand(TenantId tenantId, ObjectKey key) {

    public DeleteObjectCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(key, "key must not be null");
    }
}
