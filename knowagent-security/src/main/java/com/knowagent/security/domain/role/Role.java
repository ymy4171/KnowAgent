package com.knowagent.security.domain.role;

import com.knowagent.common.tenant.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Role(
        UUID id,
        TenantId tenantId,
        String code,
        String name,
        String description,
        Set<String> permissions,
        boolean system,
        RoleStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public Role {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions must not be null"));
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
