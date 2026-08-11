package com.knowagent.workspace.storage;

import com.knowagent.common.tenant.TenantId;

import java.io.InputStream;
import java.util.Objects;

public record PutObjectCommand(
        TenantId tenantId,
        ObjectKey key,
        String contentType,
        long size,
        InputStream content
) {

    public PutObjectCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }
}
