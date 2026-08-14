package com.knowagent.security.principal;

import com.knowagent.common.tenant.TenantId;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TenantPrincipal(
        TenantId tenantId,
        UUID userId,
        Set<String> roles,
        Set<String> permissions
) {

    public TenantPrincipal {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions must not be null"));
    }
}

