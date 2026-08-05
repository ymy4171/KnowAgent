package com.knowagent.workspace.storage;

import com.knowagent.common.tenant.TenantId;

import java.io.InputStream;

public record StorageCommand(
        TenantId tenantId,
        ObjectKey key,
        String contentType,
        long size,
        InputStream content
) {
}

